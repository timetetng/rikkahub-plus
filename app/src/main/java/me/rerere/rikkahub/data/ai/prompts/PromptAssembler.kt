package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ID_CHAT_HISTORY
import me.rerere.rikkahub.data.model.PromptItem
import me.rerere.rikkahub.data.model.PromptPosition
import me.rerere.rikkahub.data.model.PromptPreset
import me.rerere.rikkahub.data.model.RegexTarget

/**
 * 预设驱动的提示词装配器（对齐 fast-tavern 的 assembleTaggedPromptList）。
 *
 * 为什么需要它：以前消息顺序是**写死**在 Kotlin 里的（GenerationHandler 的 useOfficialSplit 分支 +
 * assembleCharacterCardMessages + PromptInjectionTransformer 里的锚点反推），而酒馆里顺序由
 * 预设的 prompt 列表决定。同一张卡在两边体验不一样，根因就在这里。
 *
 * 本装配器只负责**顺序、角色、层级**；宏与正则仍由既有的
 * PlaceholderTransformer / RegexOutputTransformer 在装配后的消息列表上完成，不重复实现一遍。
 *
 * 铁律（照抄 fast-tavern，改一处就会偏）：
 * 1. 世界书插槽条目（position != fixed）按 order **升序**，插在对应 identifier 的骨架块**之前**
 * 2. chatHistory 内的注入块按 `(depth, -order, -idx)` 排序 —— 同 depth 插到同一位置时后插的会排在
 *    前面，所以要反着排才能让 order 小的最终落在前面
 * 3. 插入下标用 `max(0, 原始历史条数 - depth)`，**原始**条数在插入过程中恒定
 *
 * 已知未覆盖：position 为 beforeAn / afterAn / outlet 的世界书条目在酒馆模式下会被本装配器丢弃
 * （酒馆里它们挂在导演备注与 outlet 上，由 AuthorsNoteTransformer 单独处理）。见 docs/tavern-parity.md。
 */
object PromptAssembler {

    /** 装配前的一条世界书（已激活）条目 */
    data class WorldSlot(
        val name: String,
        /** 新格式 position：beforeChar / afterChar / beforeAn / afterAn / fixed / beforeEm / afterEm / outlet */
        val position: String,
        val order: Int,
        val depth: Int,
        val role: MessageRole,
        val content: String,
        val sourceId: String = "",
    )

    data class Input(
        val preset: PromptPreset,
        val assistant: Assistant,
        val userName: String,
        /** 真实对话历史（按时间正序）。工具调用 / 多模态 parts 原样保留 */
        val history: List<UIMessage> = emptyList(),
        /** mes_example 解析出的示例消息（dialogueExamples 骨架块用） */
        val exampleMessages: List<UIMessage> = emptyList(),
        /** 已激活的世界书条目 */
        val worldSlots: List<WorldSlot> = emptyList(),
        /** 角色卡的历史后指令（post_history_instructions）；预设无 jailbreak 条目时兜底 */
        val postHistoryInstructions: String = "",
    )

    /** 中间产物：一条带来源标记的文本块（对应 fast-tavern 的 TaggedContent） */
    data class TaggedItem(
        val tag: String,
        val target: RegexTarget,
        val role: MessageRole,
        val text: String,
        /** 历史条目才有：距末尾的深度（0 = 最后一条） */
        val historyDepth: Int? = null,
        /** 历史条目才有：原始 UIMessage（保留 parts / 工具调用 / 多模态） */
        val origin: UIMessage? = null,
        /** 角色卡字段 / 示例消息的既有标记，保证下游识别逻辑不失效 */
        val annotations: List<UIMessageAnnotation> = emptyList(),
    )

    // ────────────────────────────────────────────────────────
    // 主入口
    // ────────────────────────────────────────────────────────

    fun assemble(input: Input): List<UIMessage> = toMessages(buildTagged(input))

    /** 只装配出带标记的列表 —— 用于调试视图与「和酒馆逐条对比」的验收 */
    fun buildTagged(input: Input): List<TaggedItem> {
        val positionMap = input.preset.positionMap
        val enabled = input.preset.prompts.filter { it.enabled }
        val relative = enabled.filter { it.position == PromptPosition.RELATIVE }

        val result = mutableListOf<TaggedItem>()

        for (prompt in relative) {
            // (a) 世界书插槽条目：position != fixed 且映射后等于本骨架块的 identifier
            input.worldSlots
                .asSequence()
                .filter { slot ->
                    slot.position != POS_FIXED &&
                        (positionMap[slot.position] ?: slot.position) == prompt.identifier
                }
                .sortedBy { it.order }
                .forEach { slot ->
                    if (slot.content.isNotBlank()) {
                        result += TaggedItem(
                            tag = "Worldbook: ${slot.name.ifBlank { slot.sourceId }}",
                            target = RegexTarget.WORLD_BOOK,
                            role = slot.role,
                            text = slot.content,
                        )
                    }
                }

            // (b) 对话历史骨架块（含 fixed 注入）
            if (prompt.identifier == ID_CHAT_HISTORY) {
                result += buildChatHistoryBlock(enabled, input)
                continue
            }

            // (c) 示例对话骨架块：用角色卡 mes_example 解析出的消息
            if (prompt.identifier == "dialogueExamples") {
                for (msg in input.exampleMessages) {
                    result += TaggedItem(
                        tag = "History: example",
                        target = RegexTarget.SLASH_COMMANDS,
                        role = msg.role,
                        text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
                        origin = msg,
                    )
                }
                continue
            }

            // (d) 普通骨架块
            val text = resolveSkeletonText(prompt, input)
            if (text.isNotBlank()) {
                result += TaggedItem(
                    tag = "Preset: ${prompt.name.ifBlank { prompt.identifier }}",
                    target = RegexTarget.SLASH_COMMANDS,
                    role = prompt.role,
                    text = text,
                    annotations = cardAnnotations(prompt.identifier),
                )
            }
            // charBefore / charAfter / worldInfoBefore / worldInfoAfter / enhanceDefinitions
            // 是纯插槽锚点，自身不产出条目
        }

        return result
    }

    // ────────────────────────────────────────────────────────
    // chatHistory 块
    // ────────────────────────────────────────────────────────

    private fun buildChatHistoryBlock(
        enabled: List<PromptItem>,
        input: Input,
    ): List<TaggedItem> {
        // 历史条目（depth 从末尾数起，0 = 最后一条）
        val dialogue = mutableListOf<TaggedItem>()
        val count = input.history.size
        input.history.forEachIndexed { i, msg ->
            val role = msg.role
            val target = when (role) {
                MessageRole.USER -> RegexTarget.USER_INPUT
                MessageRole.ASSISTANT -> RegexTarget.AI_OUTPUT
                else -> RegexTarget.SLASH_COMMANDS
            }
            dialogue += TaggedItem(
                tag = "History: $role",
                target = target,
                role = role,
                text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text },
                historyDepth = count - 1 - i,
                origin = msg,
            )
        }

        // 注入块 = 预设里 position=fixed 的 + 世界书里 position=fixed 的
        val injections = mutableListOf<InjectSpec>()
        enabled
            .filter { it.position == PromptPosition.FIXED }
            .forEachIndexed { idx, p ->
                injections += InjectSpec(
                    item = TaggedItem(
                        tag = "Preset: ${p.name.ifBlank { p.identifier }}",
                        target = RegexTarget.SLASH_COMMANDS,
                        role = p.role,
                        text = p.content,
                    ),
                    depth = p.depth,
                    order = p.order,
                    idx = idx,
                )
            }
        input.worldSlots
            .filter { it.position == POS_FIXED }
            .forEachIndexed { idx, slot ->
                injections += InjectSpec(
                    item = TaggedItem(
                        tag = "Worldbook: ${slot.name.ifBlank { slot.sourceId }}",
                        target = RegexTarget.WORLD_BOOK,
                        role = slot.role,
                        text = slot.content,
                    ),
                    depth = slot.depth,
                    order = slot.order,
                    idx = WORLD_IDX_BASE + idx,
                )
            }

        // ★ 反转排序：同 depth 时后插先占位，故按 order/idx 倒序，最终输出里 order 小的在前
        injections.sortWith(compareBy({ it.depth }, { -it.order }, { -it.idx }))

        val originalCount = dialogue.size
        for (inj in injections) {
            if (inj.item.text.isBlank()) continue
            val at = maxOf(0, originalCount - inj.depth).coerceAtMost(dialogue.size)
            dialogue.add(at, inj.item)
        }

        // 角色卡的历史后指令：预设里没有 jailbreak 条目时的兜底
        val hasJailbreak = enabled.any { it.identifier == "jailbreak" && it.position == PromptPosition.FIXED }
        if (!hasJailbreak && input.postHistoryInstructions.isNotBlank()) {
            dialogue += TaggedItem(
                tag = "Preset: jailbreak（卡内 post_history_instructions 兜底）",
                target = RegexTarget.SLASH_COMMANDS,
                role = MessageRole.SYSTEM,
                text = input.postHistoryInstructions,
            )
        }

        return dialogue
    }

    private class InjectSpec(
        val item: TaggedItem,
        val depth: Int,
        val order: Int,
        val idx: Int,
    )

    // ────────────────────────────────────────────────────────
    // 骨架块文本
    // ────────────────────────────────────────────────────────

    /**
     * 求出骨架块的文本。marker 块没有自己的内容，需要从角色卡数据里取。
     * 未识别的 identifier 一律用 prompt.content（用户自定义块）。
     */
    private fun resolveSkeletonText(prompt: PromptItem, input: Input): String {
        val tav = input.assistant.tavernData
        return when (prompt.identifier) {
            "main" -> {
                // 酒馆：角色卡的 system_prompt 排在 main 内容之前
                val parts = listOfNotNull(
                    tav?.systemPrompt?.takeIf { it.isNotBlank() },
                    prompt.content.takeIf { it.isNotBlank() },
                )
                if (parts.isNotEmpty()) parts.joinToString("\n") else input.assistant.systemPrompt
            }

            "charDescription" -> tav?.description.orEmpty()
            "charPersonality" -> tav?.personality.orEmpty()
            "scenario" -> tav?.scenario.orEmpty()
            // charBefore / charAfter 是插槽锚点，自身无内容
            "charBefore", "charAfter", "worldInfoBefore", "worldInfoAfter" -> ""
            "enhanceDefinitions" -> ""
            else -> prompt.content
        }
    }

    private fun cardAnnotations(identifier: String): List<UIMessageAnnotation> =
        when (identifier) {
            "charDescription", "charPersonality", "scenario" ->
                listOf(UIMessageAnnotation.CharacterCardData)

            else -> emptyList()
        }

    // ────────────────────────────────────────────────────────
    // 转换
    // ────────────────────────────────────────────────────────

    /** TaggedItem → UIMessage。历史条目保留原对象（parts / 工具调用 / 多模态），其余按 role+text 造 */
    fun toMessages(tagged: List<TaggedItem>): List<UIMessage> = tagged.map { item ->
        item.origin ?: UIMessage(
            role = item.role,
            parts = listOf(UIMessagePart.Text(item.text)),
            annotations = item.annotations,
        )
    }

    /** 供 GenerationHandler 判定的「是否要走酒馆装配」 */
    fun isActive(assistant: Assistant): Boolean =
        assistant.tavernMode && assistant.tavernData != null

    private const val POS_FIXED = "fixed"

    /** 世界书注入块的 idx 基址（fast-tavern 用 10000，同值便于逐条对比） */
    private const val WORLD_IDX_BASE = 10_000
}
