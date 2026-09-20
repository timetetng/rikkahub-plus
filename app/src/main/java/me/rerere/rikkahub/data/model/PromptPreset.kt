package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/**
 * 酒馆预设模型（对齐 st-api-wrapper 新格式 PresetInfo / PromptInfo）。
 *
 * 参照实现：Lianues/fast-tavern 的 convertFromSillyTavern / assembleTaggedPromptList。
 * 规范细节见仓库 docs/tavern-parity.md。
 *
 * 设计要点：
 * - [PromptPreset.prompts] 的**顺序**就是骨架顺序（对应酒馆的 prompt_order）
 * - position=RELATIVE 的条目是骨架块；position=FIXED 的条目是注入块（按 depth/order 插进 chatHistory 内部）
 * - marker 块（charDescription 等）本身无内容，内容由角色卡/世界书数据填充
 */

/** 条目的组装位置：relative = 骨架块，fixed = 深度注入块 */
@Serializable
enum class PromptPosition {
    @SerialName("relative")
    RELATIVE,

    @SerialName("fixed")
    FIXED,
}

/** 正则作用目标（对齐 RegexScriptData.targets） */
@Serializable
enum class RegexTarget {
    @SerialName("userInput")
    USER_INPUT,

    @SerialName("aiOutput")
    AI_OUTPUT,

    @SerialName("slashCommands")
    SLASH_COMMANDS,

    @SerialName("worldBook")
    WORLD_BOOK,

    @SerialName("reasoning")
    REASONING,
}

/** 正则视图：user = 显示侧，model = 发送侧 */
@Serializable
enum class RegexView {
    @SerialName("user")
    USER,

    @SerialName("model")
    MODEL,
}

/** 正则 findRegex 里的宏处理模式（只影响 findRegex，不影响 replaceRegex） */
@Serializable
enum class RegexMacroMode {
    @SerialName("none")
    NONE,

    @SerialName("raw")
    RAW,

    @SerialName("escaped")
    ESCAPED,
}

/**
 * 实用提示词（对齐 UtilityPrompts）。酒馆把这些散在 apiSetting/other 里，新格式抽成规范字段。
 * 本 App 只做「导入 + 无损保管 + 需要时取用」，不强行全部注入。
 */
@Serializable
data class UtilityPrompts(
    val impersonationPrompt: String = "",
    val worldInfoFormat: String = "",
    val scenarioFormat: String = "",
    val personalityFormat: String = "",
    val groupNudgePrompt: String = "",
    val newChatPrompt: String = "",
    val newGroupChatPrompt: String = "",
    val newExampleChatPrompt: String = "",
    val continueNudgePrompt: String = "",
    val sendIfEmpty: String = "",
    val seed: Int? = null,
)

/**
 * 预设里的单条提示词（对齐 PromptInfo）。
 *
 * @param identifier 酒馆标识符，如 main / charDescription / chatHistory / worldInfoBefore。
 *                   **导入时已按 ST_IDENTIFIER_MAP 归一**（worldInfoBefore → charBefore）。
 * @param index      骨架排序下标（来自 prompt_order 数组下标）；FIXED 条目忽略
 * @param depth      仅 FIXED 有意义：距对话末尾的深度（0 = 最后一条）
 * @param order      仅 FIXED 有意义：同 deepD 时的先后（小的在前）
 */
@Serializable
data class PromptItem(
    val identifier: String = "",
    val name: String = "",
    val enabled: Boolean = true,
    val index: Int = 0,
    val role: MessageRole = MessageRole.SYSTEM,
    val content: String = "",
    val depth: Int = 0,
    val order: Int = 100,
    val trigger: List<String> = emptyList(),
    val position: PromptPosition = PromptPosition.RELATIVE,
) {
    /** 是否占位块（无内容，文本由角色卡/世界书填充） */
    val isMarker: Boolean
        get() = identifier in MARKER_IDENTIFIERS

    val isChatHistory: Boolean
        get() = identifier == ID_CHAT_HISTORY
}

/**
 * 全局预设库中的一份预设。
 *
 * @param other 采样参数等原样 JSON（无损保管，导出时带回）
 * @param sourceKind 来源：openai / textgenerationwebui / custom
 * @param builtin 是否内置默认预设（不可删除）
 */
@Serializable
data class PromptPreset(
    val id: Uuid = Uuid.random(),
    val name: String = "Default",
    val prompts: List<PromptItem> = emptyList(),
    val regexScripts: List<AssistantRegex> = emptyList(),
    val utilityPrompts: UtilityPrompts = UtilityPrompts(),
    val other: String = "",
    val sourceKind: String = SOURCE_KIND_OPENAI,
    val builtin: Boolean = false,
) {
    /** 世界书条目 position → 预设 identifier 的插槽映射（对齐 fast-tavern 默认值，可被预设覆盖） */
    val positionMap: Map<String, String>
        get() = defaultPositionMap()
}

// ────────────────────────────────────────────────────────────
// 常量
// ────────────────────────────────────────────────────────────

const val SOURCE_KIND_OPENAI = "openai"
const val SOURCE_KIND_TEXTGEN = "textgenerationwebui"
const val SOURCE_KIND_CUSTOM = "custom"

/** 对话历史骨架块的 identifier */
const val ID_CHAT_HISTORY = "chatHistory"

/**
 * 占位块 identifier —— 这些条目自身不带内容，文本由角色卡/世界书数据填充。
 * 与酒馆默认 prompt 列表一致。
 */
val MARKER_IDENTIFIERS: Set<String> = setOf(
    "charBefore",
    "charAfter",
    "worldInfoBefore",
    "worldInfoAfter",
    "charDescription",
    "charPersonality",
    "scenario",
    "dialogueExamples",
    ID_CHAT_HISTORY,
    "enhanceDefinitions",
)

/**
 * 酒馆标准 marker identifier → 新格式 identifier。
 * 导入时用；prompt_order 里出现的仍是酒馆原始 identifier，查表要两个键都试。
 */
val ST_IDENTIFIER_MAP: Map<String, String> = mapOf(
    "worldInfoBefore" to "charBefore",
    "worldInfoAfter" to "charAfter",
)

/**
 * 世界书条目 position → 预设 identifier 插槽映射（对齐 fast-tavern 默认值）。
 * 键是世界书条目 position（新格式字符串），值是对应的预设 prompt identifier。
 */
fun defaultPositionMap(): Map<String, String> = mapOf(
    "beforeChar" to "charBefore",
    "afterChar" to "charAfter",
    // 示例消息前后 → 挂在 dialogueExamples 骨架块上（fast-tavern 默认表里没有，这里补上）
    "beforeEm" to "dialogueExamples",
    "afterEm" to "dialogueExamples",
    // beforeAn / afterAn / outlet 不在此表内：酒馆里它们挂在导演备注与 outlet 上，
    // 由 AuthorsNoteTransformer 单独处理（酒馆模式下这部分尚未接入，见 docs/tavern-parity.md）
)

/**
 * rikkahub 的 [InjectionPosition] → 新格式 position 字符串（[PromptAssembler] 的插槽匹配用）。
 *
 * 两套坐标系不是一个东西：rikkahub 的 InjectionPosition 同时服务 ModeInjection 与世界书，
 * 取值比酒馆世界书多；酒馆世界书只有 8 个位置
 * （beforeChar / afterChar / beforeAn / afterAn / fixed / beforeEm / afterEm / outlet）。
 * 所以下面有一半是「就近落地」而不是一一对应，逐条标注了理由。
 *
 * 返回值有两种形态：
 * - 插槽组字符串（beforeChar / afterChar / beforeEm / afterEm）→ 会被 [defaultPositionMap] 映射到骨架块
 * - 直接就是骨架块 identifier 或固定值（chatHistory / fixed）→ 不做映射，直接匹配
 *
 * @param depth 仅 position 解析为 `fixed` 时有意义（调用方传 entry 的 injectDepth）
 */
fun injectionPositionToSlot(position: InjectionPosition): String = when (position) {
    // 与酒馆一一对应
    InjectionPosition.BEFORE_CHARACTER -> "beforeChar"
    InjectionPosition.AFTER_CHARACTER -> "afterChar"
    InjectionPosition.AT_DEPTH -> "fixed"
    InjectionPosition.EM_TOP -> "beforeEm"
    InjectionPosition.EM_BOTTOM -> "afterEm"

    // 酒馆世界书没有「系统提示词前后」这两种位置，就近落到角色卡块两侧
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> "beforeChar"
    InjectionPosition.AFTER_SYSTEM_PROMPT -> "afterChar"

    // antagonize = 角色卡与对话之间，酒馆里就是 afterChar 的位置
    InjectionPosition.ANTAGONIZE -> "afterChar"

    // 对话最开头 = 语义上正好是 chatHistory 骨架块**之前**，直接命中该 identifier
    InjectionPosition.TOP_OF_CHAT -> ID_CHAT_HISTORY

    // 「最新消息之前」「最近一条 AI 回复之后」都是 depth 0 的深度注入
    InjectionPosition.BOTTOM_OF_CHAT -> "fixed"
    InjectionPosition.AFTER_DIALOG -> "fixed"

    // 导演备注位：酒馆世界书里对应 ANTop / ANBottom
    InjectionPosition.AUTHOR_NOTE -> "beforeAn"
}

/**
 * 固定注入条目实际使用的深度。
 * 只有解析为 `fixed` 的位置才看深度；其中「对话底部/对话之后」在酒馆里深度为 0。
 */
fun injectionPositionToDepth(position: InjectionPosition, injectDepth: Int): Int = when (position) {
    InjectionPosition.BOTTOM_OF_CHAT, InjectionPosition.AFTER_DIALOG -> 0
    else -> injectDepth
}

/**
 * 酒馆默认预设顺序 —— 导入的预设缺 prompt_order 时的兜底骨架。
 * 顺序来自 SillyTavern 默认 openai 预设。
 */
val ST_DEFAULT_PROMPT_ORDER: List<String> = listOf(
    "main",
    "worldInfoBefore",
    "charDescription",
    "charPersonality",
    "scenario",
    "worldInfoAfter",
    "enhanceDefinitions",
    "dialogueExamples",
    ID_CHAT_HISTORY,
)

/**
 * 默认 main prompt 文本（角色卡没有 system_prompt 时用），与酒馆默认值一致。
 * 注意：花括号是宏字面量，不是 Kotlin 模板。
 */
const val ST_DEFAULT_MAIN_PROMPT =
    "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}."
