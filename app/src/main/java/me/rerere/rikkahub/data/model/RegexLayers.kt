package me.rerere.rikkahub.data.model

/**
 * 正则的三层合并 —— 对齐 SillyTavern 的执行模型。
 *
 * 酒馆里能藏正则的地方有三个，执行时是**合并后依次套用**（不是覆盖）：
 *
 * | 层 | 来源 | 典型用途 |
 * |---|---|---|
 * | 全局 | 用户自建/从预设抽出 | 跨助手通用：思维链隐藏、世界书规则适配 |
 * | 预设 | 预设 JSON 的 `extensions.regex_scripts` | 配合该预设的提示词做格式化 |
 * | 助手/卡内 | 助手设置 + 卡 JSON 的 `data.extensions.regex_scripts` | 卡自己的前端 UI / 状态栏 |
 *
 * 顺序会影响结果（前一条的输出是后一条的输入），所以顺序固定为上面这个次序。
 *
 * ⚠️ 预设层只在 `assistant.tavernMode == true` 且绑定了解析得到的预设时参与 ——
 * 非酒馆模式没有「当前预设」这个概念，行为保持与旧版一致。
 */
fun resolveRegexes(
    assistant: Assistant?,
    presets: List<PromptPreset> = emptyList(),
    globalRegexes: List<AssistantRegex> = emptyList(),
): List<AssistantRegex> {
    if (assistant == null) return globalRegexes
    val fromPreset = if (assistant.tavernMode) {
        presets.firstOrNull { it.id == assistant.presetId }?.regexScripts.orEmpty()
    } else {
        emptyList()
    }
    if (globalRegexes.isEmpty() && fromPreset.isEmpty() && assistant.regexes.isEmpty()) {
        return emptyList()
    }
    return globalRegexes + fromPreset + assistant.regexes
}

/** 某个预设自身携带的正则条数（UI 展示用，别在别处重复数） */
fun PromptPreset.regexCount(): Int = regexScripts.count { it.enabled }

/** 只保留启用的 */
fun List<AssistantRegex>.enabledOnly(): List<AssistantRegex> = filter { it.enabled }

/** 供 UI 标注来源 */
enum class RegexSource(val label: String) {
    GLOBAL("全局"),
    PRESET("预设"),
    ASSISTANT("助手/卡内"),
}

/** 带来源标注的正则条目（正则总览页用） */
data class SourcedRegex(
    val source: RegexSource,
    val regex: AssistantRegex,
    val presetName: String = "",
)

/** 把三层拼成带来源的列表，按执行顺序排列 */
fun listSourcedRegexes(
    assistant: Assistant?,
    presets: List<PromptPreset> = emptyList(),
    globalRegexes: List<AssistantRegex> = emptyList(),
): List<SourcedRegex> {
    val out = mutableListOf<SourcedRegex>()
    globalRegexes.forEach { out += SourcedRegex(RegexSource.GLOBAL, it) }
    if (assistant != null && assistant.tavernMode) {
        val preset = presets.firstOrNull { it.id == assistant.presetId }
        preset?.regexScripts?.forEach {
            out += SourcedRegex(RegexSource.PRESET, it, preset.name)
        }
    }
    assistant?.regexes?.forEach { out += SourcedRegex(RegexSource.ASSISTANT, it) }
    return out
}
