package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.prompts.PromptAssembler
import me.rerere.rikkahub.data.model.RegexTarget
import me.rerere.rikkahub.data.model.RegexView
import me.rerere.rikkahub.data.model.replaceRegexesTavern
import me.rerere.rikkahub.data.model.resolveRegexes

/**
 * 酒馆模式下的输入侧正则（发送侧，view = model）。
 *
 * 为什么要单独一个 transformer：
 * - 官方语义是「**先宏，再正则**」，所以必须排在 PlaceholderTransformer **之后**
 * - 它按 target 分区（userInput / aiOutput / slashCommands / worldBook），
 *   而既有的 RegexOutputTransformer 只管显示侧、且只认 ASSISTANT 角色，覆盖不到
 * - `minDepth` / `maxDepth` 需要知道每条消息距末尾的深度，只有在这里才拿得到
 *
 * 只在 tavernMode 生效：非酒馆模式原样返回，行为与以前完全一致。
 */
object TavernRegexInputTransformer : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        // 全局正则（设置里的正则库）跨助手生效，不属于酒馆模式；
        // 预设层与助手/卡内层走这套按 target 分区的酒馆执行器，只在酒馆模式开启。
        // 这样非酒馆用户的旧行为不受影响（全局库默认为空）。
        val rules = if (PromptAssembler.isActive(assistant)) {
            resolveRegexes(assistant, ctx.settings.promptPresets, ctx.settings.globalRegexes)
        } else {
            ctx.settings.globalRegexes
        }
        if (rules.isEmpty()) return messages

        val macros = mapOf(
            "user" to ctx.settings.displaySetting.userNickname.ifBlank { "User" },
            "char" to assistant.name,
        )

        val total = messages.size
        return messages.mapIndexed { idx, message ->
            // 第 0 条 system 是 GenerationHandler 前置的工具提示（rikkahub 自身能力），
            // 不属于装配产物，不套卡的正则
            if (idx == 0 && message.role == MessageRole.SYSTEM) return@mapIndexed message

            val target = when (message.role) {
                MessageRole.USER -> RegexTarget.USER_INPUT
                MessageRole.ASSISTANT -> RegexTarget.AI_OUTPUT
                // 装配器产出的骨架块与注入块都以 system 落在这里
                else -> RegexTarget.SLASH_COMMANDS
            }
            val depth = total - 1 - idx

            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Text -> part.copy(
                            text = part.text.replaceRegexesTavern(
                                rules = rules,
                                target = target,
                                view = RegexView.MODEL,
                                depth = depth,
                                macros = macros,
                            )
                        )

                        is UIMessagePart.Reasoning -> part.copy(
                            reasoning = part.reasoning.replaceRegexesTavern(
                                rules = rules,
                                target = RegexTarget.REASONING,
                                view = RegexView.MODEL,
                                depth = null,
                                macros = macros,
                            )
                        )

                        else -> part
                    }
                }
            )
        }
    }
}
