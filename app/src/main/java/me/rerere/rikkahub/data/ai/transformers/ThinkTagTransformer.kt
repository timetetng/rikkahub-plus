package me.rerere.rikkahub.data.ai.transformers

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Clock

private val THINKING_REGEX = Regex("<think>([\\s\\S]*?)</think>", RegexOption.DOT_MATCHES_ALL)
// 流式期间为了能实时显示思考，未闭合的也先当思考；
// 但**最终落盘时绝不能用它**（见 onGenerationFinish）。
private val THINKING_UNCLOSED_REGEX = Regex("<think>([\\s\\S]*?)(?:</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val CLOSING_TAG_REGEX = Regex("</think>")

// 部分供应商不会返回reasoning parts, 所以需要这个transformer
object ThinkTagTransformer : OutputMessageTransformer {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text && THINKING_UNCLOSED_REGEX.containsMatchIn(part.text)) {
                            val stripped = part.text.replace(THINKING_UNCLOSED_REGEX, "")
                            val reasoning =
                                THINKING_UNCLOSED_REGEX.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                                    ?: ""
                            val hasClosingTag = CLOSING_TAG_REGEX.containsMatchIn(part.text)
                            listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = reasoning,
                                    createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                    finishedAt = if (hasClosingTag) Clock.System.now() else null,
                                ),
                                part.copy(text = stripped),
                            )
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val now = Clock.System.now()
        // ⚠️ 这里只认**闭合的** <think>...</think>。
        // 以前用的是 "<think>([\s\S]*?)(?:</think>|$)" —— 模型如果只写了 <think> 而收尾写错标签
        // （实测出现过 </think_rules>），正则找不到 </think> 就一路匹配到结尾，
        // **把正文整条删掉、只留下思考**（症状：思考完就没了）。宁可把思考当正文留下，也不能把正文吃掉。
        return messages.map { message ->
            if (message.role == MessageRole.ASSISTANT && message.hasPart<UIMessagePart.Text>()) {
                message.copy(
                    parts = message.parts.flatMap { part ->
                        if (part is UIMessagePart.Text && THINKING_REGEX.containsMatchIn(part.text)) {
                            val stripped = part.text.replace(THINKING_REGEX, "")
                            val reasoning =
                                THINKING_REGEX.find(part.text)?.groupValues?.getOrNull(1)?.trim()
                                    ?: ""
                            listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = reasoning,
                                    createdAt = message.createdAt.toInstant(timeZone = TimeZone.currentSystemDefault()),
                                    finishedAt = now,
                                ),
                                part.copy(text = stripped),
                            )
                        } else {
                            listOf(part)
                        }
                    }
                )
            } else {
                message
            }
        }
    }
}
