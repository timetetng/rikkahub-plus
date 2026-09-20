package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.resolveRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        // ⚠️ 这里**不套预设正则**（2026-09-20 修）：
        // 本方法的输出会被 emit 给 UI 并**落盘**，相当于 ST 里没有的一步。
        // 而 ST 的正则只作用在两处：① 显示 ② 构建 prompt 时的历史，
        // **从不在 AI 输出落盘前改写它**。把预设的 `visualOnly=false` 规则（ST 的 prompt 侧，
        // 如「TG-7楼之前只显示摘要」`([\s\S]*?)<details>…摘要…([\s\S]*?)</details>([\s\S]*)` → `$2`）
        // 打到刚生成的新消息上，会把整条消息折叠成只剩摘要 —— 症状就是
        // 「流式时能看到正文，输出完就没了」。
        // 预设正则仍在两处生效：`ChatMessage`（显示，visual=true）与
        // `TavernRegexInputTransformer`（构建 prompt 的历史）。
        val rules = resolveRegexes(assistant, emptyList(), ctx.settings.globalRegexes)
        if (rules.isEmpty()) return messages // No regexes, return original messages
        return messages.map { message ->
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@map message // Skip non-assistant messages
            }
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            part.copy(text = part.text.replaceRegexes(rules, scope, visual = false))
                        }

                        is UIMessagePart.Reasoning -> {
                            part.copy(reasoning = part.reasoning.replaceRegexes(rules, scope, visual = false))
                        }

                        else -> part
                    }
                }
            )
        }
    }
}
