package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val TAG = "Codemode"
private const val MAX_TIMEOUT_MS = 120_000L
private const val MAX_OUTPUT_CHARS = 32 * 1024
private const val MAX_TOOL_TEXT_CHARS = 512 * 1024
private const val MEMORY_LIMIT_BYTES = 128 * 1024 * 1024
private const val MAX_IMAGES = 8

private const val CODEMODE_DESCRIPTION =
    "Run a JavaScript script that calls other tools in order; only the script's output is returned to you.\n\n" +
        "This engine is SYNCHRONOUS: the script runs as a plain function body (top-level `return` works), but there is " +
        "NO `await` and no Promise. Write calls as plain statements — each blocks until the tool finishes, so the next " +
        "line already sees its effect (write-then-read is safe).\n\n" +
        "Globals:\n" +
        "- `tools.<name>(args)` — call a tool and return `{ ok, text, data, images: [{ id, mime }], documents }`; " +
        "`data` is the parsed JSON object when the tool's output is a single JSON payload (env_*/file tools), so " +
        "`r.data.stdout` works. It throws an Error when the tool fails; use try/catch to retry.\n" +
        "- `toolsAll([{ name, args }, ...])` — run several calls in parallel; returns an array of the same result " +
        "objects, failures included as `{ ok: false, error }`.\n" +
        "- `text(value)` / `console.log(...)` — add output; top-level `return` also adds a value.\n" +
        "- `image(res.images[0])` — show an image in the output. Images from tool results are NOT sent unless you do this.\n" +
        "- `ALL_TOOLS` — every callable tool as `{ name, description }`.\n" +
        "- Optional first line: `// @options: {\"timeout_ms\": 60000}`; default 60s, max 120s.\n\n" +
        "Rules:\n" +
        "- Only `tools.*` is available: no file system, network, timers, or import.\n" +
        "- A tool that needs user approval throws inside a script — call it directly instead.\n" +
        "- Output is capped at 32KB; return a small summary, not raw data dumps.\n" +
        "- Calls that already ran are NOT undone when a later call fails.\n\n" +
        "Example:\n" +
        "const w = tools.env_write_file({ path: '/root/a.txt', content: 'hi' });\n" +
        "const r = tools.env_read_file({ path: '/root/a.txt' });\n" +
        "return { read: r.text };"

/**
 * 创建一个 codemode 工具：模型写一段同步 JS，按顺序调用其它工具。
 *
 * QuickJS 绑定没有 job queue（无法 await），所以脚本是同步的：tools.x() 阻塞直到工具返回。
 * 并行用 toolsAll()，在 Kotlin 侧真并发。
 */
fun createCodemodeTool(
    toolsProvider: () -> List<Tool>,
    defaultTimeoutSec: Long = 60,
): Tool {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    return Tool(
        name = "codemode",
        description = CODEMODE_DESCRIPTION,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("code", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "JavaScript source, run as a synchronous function body. Top-level `return` works; no await/Promise."
                        )
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put("description", "Timeout in seconds (default $defaultTimeoutSec, max ${MAX_TIMEOUT_MS / 1000})")
                    })
                },
                required = listOf("code")
            )
        },
        execute = execute@{ args ->
            val obj = args.jsonObject
            val code = obj["code"]?.jsonPrimitive?.contentOrNull
            if (code.isNullOrBlank()) {
                return@execute listOf(UIMessagePart.Text("codemode: code is required"))
            }
            val timeoutSec = (obj["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: defaultTimeoutSec)
                .coerceIn(1L, MAX_TIMEOUT_MS / 1000)
            // 自身和 eval_javascript 不能暴露：同一个单线程 JS executor 会死锁
            val tools = toolsProvider().filter { it.name != "codemode" && it.name != "eval_javascript" }
            Log.i(TAG, "codemode: ${code.length} chars, timeout=${timeoutSec}s, tools=${tools.size}")

            val outcome = runScript(code, timeoutSec * 1000, tools, json)
            val parts = mutableListOf<UIMessagePart>()
            parts.add(
                UIMessagePart.Text(
                    if (outcome.ok) renderSuccess(outcome) else renderFailure(outcome)
                )
            )
            outcome.shownImages.take(MAX_IMAGES).forEach { parts.add(it) }
            if (outcome.shownImages.size > MAX_IMAGES) {
                parts.add(UIMessagePart.Text("(${outcome.shownImages.size - MAX_IMAGES} more image(s) omitted)"))
            }
            parts
        }
    )
}

private class ScriptOutcome(
    val ok: Boolean,
    val texts: List<String>,
    val logs: List<String>,
    val ret: String?,
    val images: List<UIMessagePart.Image>,
    val shownImages: List<UIMessagePart.Image>,
    val hiddenImageCount: Int,
    val error: String?,
    val elapsedMs: Long,
)

private fun renderSuccess(o: ScriptOutcome): String = buildString {
    append("Script completed in ").append(formatSeconds(o.elapsedMs)).append('\n')
    val items = o.texts.toMutableList()
    o.ret?.let { items.add(it) }
    if (items.isNotEmpty()) {
        append("Output:\n")
        if (items.size == 1) {
            append(items.first())
        } else {
            items.forEachIndexed { i, s ->
                append("==> text ").append(i + 1).append('/').append(items.size).append(" <==\n")
                append(s).append('\n')
            }
        }
        append('\n')
    }
    if (o.logs.isNotEmpty()) {
        append("<console_output>\n")
        o.logs.forEach { append(it).append('\n') }
        append("</console_output>\n")
    }
    if (o.hiddenImageCount > 0) {
        append("Note: ").append(o.hiddenImageCount)
            .append(" image(s) returned by tools were not shown; call image(res.images[0]) to show one.\n")
    }
}

private fun renderFailure(o: ScriptOutcome): String = buildString {
    append("Script failed in ").append(formatSeconds(o.elapsedMs)).append('\n')
    val items = o.texts.toMutableList()
    o.ret?.let { items.add(it) }
    if (items.isNotEmpty()) {
        append("Partial output:\n")
        items.forEach { append(it).append('\n') }
    }
    if (o.logs.isNotEmpty()) {
        append("<console_output>\n")
        o.logs.forEach { append(it).append('\n') }
        append("</console_output>\n")
    }
    append("Script error:\n").append(o.error ?: "unknown error")
}

private fun formatSeconds(ms: Long): String = String.format(java.util.Locale.US, "%.2f s", ms / 1000.0)

private suspend fun runScript(
    code: String,
    timeoutMs: Long,
    tools: List<Tool>,
    json: Json,
): ScriptOutcome = withContext(Dispatchers.IO) {
    val started = System.nanoTime()
    val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "codemode-js").apply { isDaemon = true }
    }
    val future: Future<RawRun> = executor.submit(
        java.util.concurrent.Callable {
            runInFreshContext(code, started + timeoutMs * 1_000_000, tools, json)
        }
    )

    try {
        val raw = future.get(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
        executor.shutdown()
        toOutcome(raw)
    } catch (e: TimeoutException) {
        future.cancel(true)
        // native evaluate 不能中断，线程可能卡死：整个 executor 弃掉
        executor.shutdownNow()
        ScriptOutcome(
            ok = false,
            texts = emptyList(),
            logs = emptyList(),
            ret = null,
            images = emptyList(),
            shownImages = emptyList(),
            hiddenImageCount = 0,
            error = "script timed out after ${timeoutMs / 1000}s (the JS context was abandoned; no partial output)",
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    } catch (e: Exception) {
        future.cancel(true)
        executor.shutdownNow()
        ScriptOutcome(
            ok = false,
            texts = emptyList(),
            logs = emptyList(),
            ret = null,
            images = emptyList(),
            shownImages = emptyList(),
            hiddenImageCount = 0,
            error = "[${e.javaClass.simpleName}] ${e.message}",
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    }
}

private class RawRun(
    val scriptError: String?,
    val texts: List<String>,
    val logs: List<String>,
    val ret: String?,
    val shownIds: List<Int>,
    val images: List<UIMessagePart.Image>,
    val elapsedMs: Long,
)

private fun runInFreshContext(
    code: String,
    deadlineNanos: Long,
    tools: List<Tool>,
    json: Json,
): RawRun {
    val started = System.nanoTime()
    val ctx = QuickJSContext.create()
    val images = Collections.synchronizedList(mutableListOf<UIMessagePart.Image>())
    val toolMap = tools.associateBy { it.name }

    /** 跑一个工具，返回 {ok:true,...} 或 {ok:false,error}。 */
    suspend fun runTool(name: String, argsJson: String): kotlinx.serialization.json.JsonObject {
        val tool = toolMap[name] ?: return toolError("unknown tool: $name")
        val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
        if (remainingMs <= 0) return toolError("script deadline exceeded")
        val argsElem: JsonElement = try {
            json.parseToJsonElement(argsJson.ifBlank { "null" })
        } catch (e: Exception) {
            return toolError("invalid args for '$name': ${e.message}")
        }
        return try {
            if (tool.needsApproval(argsElem)) {
                return toolError("tool '$name' needs user approval; call it directly instead of from a script")
            }
            val parts = withTimeout(remainingMs) { tool.execute(argsElem) }
            partsToJson(parts, images)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            toolError("tool '$name' timed out")
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            // 工具自己已经带了前缀就不再重复
            toolError(if (msg.startsWith("$name:")) msg else "$name: $msg")
        }
    }

    try {
        ctx.setMemoryLimit(MEMORY_LIMIT_BYTES)

        // 原生桥：只接受/返回字符串，避免在回调里再次进入 JS
        ctx.globalObject.setProperty("__call", JSCallFunction { argv ->
            val name = argv.getOrNull(0)?.toString().orEmpty()
            val argsJson = argv.getOrNull(1)?.toString() ?: "null"
            val result = runBlocking { runTool(name, argsJson) }
            // 单次调用：失败转成 __error，JS 侧抛异常
            if (result["ok"]?.jsonPrimitive?.contentOrNull == "false") {
                buildJsonObject {
                    put("__error", result["error"]?.jsonPrimitive?.contentOrNull ?: "tool failed")
                }.toString()
            } else {
                result.toString()
            }
        })
        ctx.globalObject.setProperty("__callAll", JSCallFunction { argv ->
            val listJson = argv.getOrNull(0)?.toString() ?: "[]"
            runBlocking {
                val calls = runCatching { json.parseToJsonElement(listJson).jsonArray }.getOrElse {
                    return@runBlocking "[" + toolError("toolsAll expects an array of { name, args }") + "]"
                }
                coroutineScope {
                    calls.map { el ->
                        async(Dispatchers.IO) {
                            val o = runCatching { el.jsonObject }.getOrNull()
                                ?: return@async toolError("toolsAll entry must be an object")
                            val name = o["name"]?.jsonPrimitive?.contentOrNull
                                ?: return@async toolError("toolsAll entry needs name")
                            val a = o["args"]?.toString() ?: "null"
                            runTool(name, a)
                        }
                    }.awaitAll()
                }.joinToString(",", "[", "]")
            }
        })

        // JS 侧运行时
        ctx.evaluate(
            """
            var __texts = [];
            var __logs = [];
            var __shown = [];
            var __ret;
            function text(v) { __texts.push(typeof v === 'string' ? v : JSON.stringify(v)); }
            function image(v) {
              if (v === null || v === undefined) return;
              var id = (typeof v === 'object') ? v.id : v;
              if (id !== undefined && id !== null) __shown.push(id);
            }
            var console = {
              log: function(){ for (var i=0;i<arguments.length;i++) __logs.push(String(arguments[i])); },
              info: function(){ for (var i=0;i<arguments.length;i++) __logs.push('[INFO] ' + String(arguments[i])); },
              warn: function(){ for (var i=0;i<arguments.length;i++) __logs.push('[WARN] ' + String(arguments[i])); },
              error: function(){ for (var i=0;i<arguments.length;i++) __logs.push('[ERROR] ' + String(arguments[i])); },
              debug: function(){ for (var i=0;i<arguments.length;i++) __logs.push(String(arguments[i])); }
            };
            """.trimIndent()
        )

        // tools.<name> 包装：JSON 进出，失败抛 Error
        val toolsJs = buildString {
            append("var tools = {};\n")
            tools.forEach { t ->
                append("tools[").append(JsonPrimitive(jsIdentifier(t.name)).toString()).append("] = function(a) {\n")
                append("  var o = JSON.parse(__call(").append(JsonPrimitive(t.name).toString())
                append(", JSON.stringify(a === undefined ? null : a)));\n")
                append("  if (o && o.__error) { var e = new Error(o.__error); e.name = 'ToolError'; throw e; }\n")
                append("  return o;\n};\n")
            }
            append("function toolsAll(list) { return JSON.parse(__callAll(JSON.stringify(list || []))); }\n")
        }
        ctx.evaluate(toolsJs)

        val allToolsJson = JsonArray(tools.map { t ->
            buildJsonObject {
                put("name", t.name)
                put("description", t.description.lineSequence().firstOrNull()?.take(160) ?: "")
            }
        }).toString()
        ctx.evaluate("var ALL_TOOLS = $allToolsJson;")

        var scriptError: String? = null
        try {
            // 包成普通函数体的 IIFE：顶层 return 可用，var/const 不泄漏到下一次调用
            ctx.evaluate("__ret = (function(){\n" + code + "\n})();")
        } catch (e: Throwable) {
            scriptError = e.message ?: e.toString()
        }

        val stateJson = runCatching {
            ctx.evaluate(
                """
                (function(){
                  try {
                    return JSON.stringify({t:__texts,l:__logs,s:__shown,r:(typeof __ret === 'undefined' ? null : __ret)});
                  } catch (e) {
                    return JSON.stringify({t:__texts,l:__logs,s:__shown,r:String(__ret)});
                  }
                })()
                """.trimIndent()
            ) as? String
        }.getOrNull()

        val state = runCatching { json.parseToJsonElement(stateJson ?: "{}").jsonObject }.getOrNull()
        fun strList(key: String): List<String> = runCatching {
            state?.get(key)?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" } ?: emptyList()
        }.getOrDefault(emptyList())
        fun intList(key: String): List<Int> = runCatching {
            state?.get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() } ?: emptyList()
        }.getOrDefault(emptyList())

        val retValue = runCatching {
            val r = state?.get("r") ?: JsonNull
            when {
                r is JsonNull -> null
                r is JsonPrimitive && r.isString -> r.content
                else -> r.toString()
            }
        }.getOrNull()

        val snapshot = synchronized(images) { images.toList() }
        return RawRun(
            scriptError = scriptError,
            texts = strList("t"),
            logs = strList("l"),
            ret = retValue,
            shownIds = intList("s"),
            images = snapshot,
            elapsedMs = (System.nanoTime() - started) / 1_000_000,
        )
    } finally {
        runCatching { ctx.destroy() }
            .onFailure { Log.w(TAG, "destroy context failed: ${it.message}") }
    }
}

private fun toOutcome(raw: RawRun): ScriptOutcome {
    val shown = raw.shownIds.mapNotNull { raw.images.getOrNull(it) }
    val texts = raw.texts.map { truncate(it, MAX_OUTPUT_CHARS) }
    val ret = raw.ret?.let { truncate(it, MAX_OUTPUT_CHARS) }
    val total = texts.sumOf { it.length } + (ret?.length ?: 0)
    val capped = if (total <= MAX_OUTPUT_CHARS) {
        texts to ret
    } else {
        texts.map { truncate(it, MAX_OUTPUT_CHARS / 2) } to ret?.let { truncate(it, MAX_OUTPUT_CHARS / 2) }
    }
    return ScriptOutcome(
        ok = raw.scriptError == null,
        texts = capped.first,
        logs = raw.logs.map { truncate(it, 2000) },
        ret = capped.second,
        images = raw.images,
        shownImages = shown,
        hiddenImageCount = (raw.images.size - shown.size).coerceAtLeast(0),
        error = raw.scriptError?.let { err ->
            if (err.contains("await", ignoreCase = true) || err.contains("Promise", ignoreCase = true)) {
                err + "\nHint: this engine is synchronous — remove await/Promise and write plain statements (use toolsAll for parallel calls)."
            } else err
        },
        elapsedMs = raw.elapsedMs,
    )
}

private fun truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max) + "...[truncated]"

private fun toolError(message: String): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("ok", false)
        put("error", message)
    }

private fun partsToJson(parts: List<UIMessagePart>, images: MutableList<UIMessagePart.Image>): kotlinx.serialization.json.JsonObject {
    val textBuf = StringBuilder()
    val imageRefs = mutableListOf<JsonElement>()
    val docs = mutableListOf<JsonElement>()
    for (p in parts) {
        when (p) {
            is UIMessagePart.Text -> {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(p.text)
            }
            is UIMessagePart.Image -> {
                val id = synchronized(images) {
                    val i = images.size
                    images.add(p)
                    i
                }
                imageRefs.add(buildJsonObject {
                    put("id", id)
                    put("mime", mimeOf(p.url))
                })
            }
            is UIMessagePart.Document -> docs.add(buildJsonObject {
                put("url", p.url)
                put("fileName", p.fileName)
                put("mime", p.mime)
            })
            else -> {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(p.toString())
            }
        }
    }
    val fullText = textBuf.toString()
    // 大部分工具的输出就是一段 JSON（env_*、file 等）：顺手解析成 data，脚本里可以直接 r.data.xxx
    val data = runCatching {
        if (parts.count { it is UIMessagePart.Text } == 1) {
            kotlinx.serialization.json.Json.parseToJsonElement(fullText).jsonObject
        } else null
    }.getOrNull()
    return buildJsonObject {
        put("ok", true)
        put("text", truncate(fullText, MAX_TOOL_TEXT_CHARS))
        put("data", data ?: JsonNull)
        put("images", JsonArray(imageRefs))
        put("documents", JsonArray(docs))
    }
}

private fun mimeOf(url: String): String = when (url.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    "avif" -> "image/avif"
    else -> "image/*"
}

/** JS 标识符：非法字符换 `_`，数字开头补 `_`（与 pi codemode 一致）。 */
private fun jsIdentifier(name: String): String {
    val sb = StringBuilder()
    name.forEach { c ->
        if (c == '_' || c == '$' || c.isLetterOrDigit()) sb.append(c) else sb.append('_')
    }
    if (sb.isEmpty()) return "_"
    if (sb[0].isDigit()) sb.insert(0, '_')
    return sb.toString()
}
