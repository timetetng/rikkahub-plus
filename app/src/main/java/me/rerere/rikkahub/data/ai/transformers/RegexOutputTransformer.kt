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
        // 三层合并（全局 → 预设 → 助手/卡内）：预设正则以前只是导入不生效，现在真正参与执行
        val rules = resolveRegexes(assistant, ctx.settings.promptPresets, ctx.settings.globalRegexes)
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
