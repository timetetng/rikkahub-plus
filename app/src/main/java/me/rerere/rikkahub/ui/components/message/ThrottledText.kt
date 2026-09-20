package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 流式期间把「文本更新」节流到固定间隔。
 *
 * 为什么需要（2026-09-20 实测）：
 * Compose 每来一个 chunk 就重组一次，而 `MarkdownBlock` 要**重新解析整段文本**、
 * 文本还要重新 measure/layout —— 两者都是 O(n)，乘以 n 次 chunk 就是 **O(n²)**。
 * 预设要求的思维链动辄几百上千字，正好把这个平方项放大到主线程跑满
 * （实测 `dumpsys cpuinfo` 该进程 123%，主线程峰值 191%），表现为
 * 「越写越卡、最后几分钟不动」。
 *
 * 关掉正则只解决了一部分（那是另一个 O(n) 项）；Markdown 与布局这两项必须靠节流。
 *
 * 节流后每秒只更新几次，总开销降一个数量级；`loading` 转 false 时立刻用最新全文，
 * 不会留下"最后一段没显示"的问题。
 *
 * @param intervalMs 更新间隔。思维链用大一点（文本长、对"实时感"要求低），正文用小一点。
 */
@Composable
fun rememberThrottledText(
    source: String,
    loading: Boolean,
    intervalMs: Long = 250L,
): String {
    val latest by rememberUpdatedState(source)
    var shown by remember { mutableStateOf(source) }
    LaunchedEffect(loading, intervalMs) {
        if (!loading) {
            shown = latest
            return@LaunchedEffect
        }
        while (true) {
            delay(intervalMs)
            shown = latest
        }
    }
    // loading 结束直接用 source（保证最终内容一定是最新的，不依赖上面那次赋值时序）
    return if (loading) shown else source
}
