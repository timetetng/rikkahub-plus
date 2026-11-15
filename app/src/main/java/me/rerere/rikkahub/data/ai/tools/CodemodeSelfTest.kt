package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次性自检：外部 files 目录里放 `codemode-selftest.trigger` 后启动 app，
 * 跑一段脚本并把结果写到 `codemode-selftest.out`，然后删掉 trigger。
 *
 * 只用来在真机上验证「同步阻塞桥」是否可行（不需要模型参与）。
 */
object CodemodeSelfTest {

    fun maybeRun(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val trigger = File(dir, "codemode-selftest.trigger")
        if (!trigger.exists()) return
        Thread {
            val out = File(dir, "codemode-selftest.out")
            val sb = StringBuilder()
            try {
                val state = ConcurrentHashMap<String, String>()

                fun fake(
                    name: String,
                    block: (JsonElement) -> List<UIMessagePart>,
                ): Tool = Tool(
                    name = name,
                    description = "fake tool $name (self-test)",
                    execute = { block(it) },
                )

                val tools = listOf(
                    fake("t_write") { args ->
                        state["k"] = args.jsonObject["v"]?.jsonPrimitive?.content ?: ""
                        listOf(UIMessagePart.Text("written"))
                    },
                    fake("t_read") { _ ->
                        listOf(UIMessagePart.Text(state["k"] ?: "MISSING"))
                    },
                    fake("t_boom") { _ ->
                        error("boom happened")
                    },
                )

                val code = """
                    var w = tools.t_write({ v: 'hello' });
                    var r = tools.t_read({});
                    var caught = false;
                    try { tools.t_boom({}); } catch (e) { caught = String(e.message).indexOf('boom') >= 0; }
                    var all = toolsAll([{ name: 't_read', args: {} }, { name: 't_read', args: {} }]);
                    var allOk = (all[0].ok === true) && (all[1].ok === true);
                    return {
                      wrote: w.text,
                      read: r.text,
                      ordered: (r.text === 'hello'),
                      caught: caught,
                      allOk: allOk,
                      allText: all[0].text
                    };
                """.trimIndent()

                val tool = createCodemodeTool({ tools }, 30L)
                val parts = runBlocking {
                    tool.execute(
                        buildJsonObject {
                            put("code", code)
                            put("timeout", 20)
                        }
                    )
                }
                parts.forEach { p ->
                    sb.append(
                        when (p) {
                            is UIMessagePart.Text -> p.text
                            else -> p.toString()
                        }
                    ).append('\n')
                }
            } catch (e: Throwable) {
                sb.append("EXCEPTION: ").append(e.javaClass.name).append(": ").append(e.message).append('\n')
                sb.append(e.stackTraceToString())
            } finally {
                runCatching { out.writeText(sb.toString()) }
                runCatching { trigger.delete() }
            }
        }.start()
    }
}
