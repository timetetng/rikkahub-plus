package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「一次调用改多处」的文本替换内核 —— file 的 patch 和 env_edit_file 共用。
 *
 * 规则抄自 pi-mono 的 edit 工具（与 opencode 的 edit 同源）：
 *   1. 每个 oldText 都对着**原文**定位，不是增量定位 —— 同一调用里几条替换互不影响，
 *      模型不用操心顺序，也不会因为前一条改了字而让后一条失配；
 *   2. 落点**不许重叠** —— 挨着的改动应该由模型合并成一条，而不是让这里猜；
 *   3. 精确定位失败才退化到「按行 trim 后匹配」，且必须唯一命中。
 *
 * 为什么要这个：模型会把多个 patch 放在同一条消息里并发发出，两个调用各自读到改动前的
 * 快照、后写的把先写的冲掉（有路径锁之后不丢了，但仍然要多跑一趟）。一次调用改多处，
 * 从签名上就把这类竞态消掉。
 */
data class TextEdit(val oldText: String, val newText: String)

data class TextEditResult(val text: String, val count: Int, val lines: List<Int>)

private class Hit(val start: Int, val end: Int)

private class Span(val hit: Hit, val newText: String) {
    var line: Int = 0
}

/**
 * 解析 `edits[]`；没有这个参数就退化成老的 old_string/new_string 单条形式。
 * 两种写法都接受 `oldText`/`newText` 别名（不同 agent 的习惯叫法）。
 */
fun parseTextEdits(obj: JsonObject): List<TextEdit> {
    val arr = obj["edits"]
    if (arr != null && arr !is JsonNull) {
        val items = arr.jsonArray
        require(items.isNotEmpty()) { "edits must not be empty" }
        return items.mapIndexed { i, item ->
            val o = item.jsonObject
            val old = o["old_string"]?.jsonPrimitive?.contentOrNull
                ?: o["oldText"]?.jsonPrimitive?.contentOrNull
                ?: error("edits[$i].old_string is required")
            val new = o["new_string"]?.jsonPrimitive?.contentOrNull
                ?: o["newText"]?.jsonPrimitive?.contentOrNull
                ?: ""
            TextEdit(old, new)
        }
    }
    val old = obj["old_string"]?.jsonPrimitive?.contentOrNull
        ?: obj["oldText"]?.jsonPrimitive?.contentOrNull
        ?: error("old_string (or edits[]) is required")
    val new = obj["new_string"]?.jsonPrimitive?.contentOrNull
        ?: obj["newText"]?.jsonPrimitive?.contentOrNull
        ?: ""
    return listOf(TextEdit(old, new))
}

fun applyTextEdits(content: String, edits: List<TextEdit>, replaceAll: Boolean = false): TextEditResult {
    require(edits.isNotEmpty()) { "edits must not be empty" }
    val spans = ArrayList<Span>()
    edits.forEachIndexed { i, e ->
        require(e.oldText.isNotEmpty()) { "edit[$i]: old_string must not be empty" }
        require(e.oldText != e.newText) { "edit[$i]: old_string and new_string are identical" }
        spans += locate(content, e, replaceAll, i)
    }
    spans.sortBy { it.hit.start }
    for (i in 1 until spans.size) {
        require(spans[i].hit.start >= spans[i - 1].hit.end) {
            "edits overlap (#${i - 1} and #$i touch the same region) — merge them into one edit"
        }
    }
    val sb = StringBuilder(content)
    for (s in spans.asReversed()) sb.replace(s.hit.start, s.hit.end, s.newText)
    return TextEditResult(sb.toString(), spans.size, spans.map { it.line })
}

/** 精确匹配优先；找不到才按行 trim 模糊匹配（必须唯一） */
private fun locate(content: String, e: TextEdit, replaceAll: Boolean, idx: Int): List<Span> {
    val exact = ArrayList<Span>()
    var from = 0
    while (true) {
        val i = content.indexOf(e.oldText, from)
        if (i < 0) break
        exact += Span(Hit(i, i + e.oldText.length), e.newText).also { it.line = lineOf(content, i) }
        from = i + e.oldText.length
        if (!replaceAll) break
    }
    if (exact.isNotEmpty()) {
        require(replaceAll || exact.size == 1) {
            "edit[$idx]: old_string occurs ${exact.size} times — add more context, or set replace_all=true"
        }
        return exact
    }
    val fuzzy = fuzzyHits(content, e.oldText)
    require(fuzzy.size == 1) {
        if (fuzzy.isEmpty()) "edit[$idx]: old_string not found (checked exact and whitespace-normalized)"
        else "edit[$idx]: whitespace-normalized match is ambiguous (${fuzzy.size} candidates) — add more context"
    }
    return listOf(Span(fuzzy[0], e.newText).also { it.line = lineOf(content, fuzzy[0].start) })
}

private fun lineOf(content: String, offset: Int): Int {
    var n = 1
    var i = content.indexOf('\n')
    while (i in 0 until offset) {
        n++
        i = content.indexOf('\n', i + 1)
    }
    return n
}

/** 按行 trim 后匹配，回所有命中的字符区间 */
private fun fuzzyHits(content: String, oldText: String): List<Hit> {
    val lines = content.split("\n")
    val want = oldText.split("\n").map { it.trim() }.toMutableList()
    while (want.isNotEmpty() && want.last().isEmpty()) want.removeAt(want.size - 1)
    if (want.isEmpty() || want.size > lines.size) return emptyList()
    val starts = IntArray(lines.size + 1)
    for (i in lines.indices) starts[i + 1] = starts[i] + lines[i].length + 1
    val out = ArrayList<Hit>()
    for (i in 0..lines.size - want.size) {
        var ok = true
        for (j in want.indices) {
            if (lines[i + j].trim() != want[j]) {
                ok = false
                break
            }
        }
        if (ok) {
            val last = i + want.size - 1
            out += Hit(starts[i], starts[last] + lines[last].length)
        }
    }
    return out
}
