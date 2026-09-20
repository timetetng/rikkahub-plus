package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/**
 * 酒馆预设 / 正则导入解析（Kotlin 版）。
 *
 * 对齐 fast-tavern 的 convertPresetFromSillyTavern / convertRegexFromSillyTavern，
 * 语义细节见仓库 docs/tavern-parity.md §3.3、§4。
 *
 * 之所以自己解析而不是走 kotlinx 的 @Serializable：酒馆预设的字段命名极不统一
 * （snake_case / camelCase / 数字枚举 / 布尔字符串混用），且必须**无损**拿到 unknown 字段，
 * 手写解析比声明式映射更可控。
 */

private val presetJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowSpecialFloatingPointValues = true
}

// ────────────────────────────────────────────────────────────
// JSON 读取小工具
// ────────────────────────────────────────────────────────────

private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

private fun JsonObject.str(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    return (e as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.int(key: String): Int? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val p = e as? JsonPrimitive ?: return null
    return p.intOrNull ?: p.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
}

private fun JsonObject.bool(key: String): Boolean? {
    val e = this[key] ?: return null
    if (e is JsonNull) return null
    val p = e as? JsonPrimitive ?: return null
    return p.booleanOrNull ?: p.contentOrNull?.trim()?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
}

private fun JsonElement?.asArray(): List<JsonElement> = when (this) {
    is JsonArray -> this
    null, JsonNull -> emptyList()
    else -> listOf(this)
}

private fun JsonObject.firstStr(vararg keys: String): String {
    for (k in keys) {
        val v = str(k)
        if (!v.isNullOrEmpty()) return v
    }
    return ""
}

// ────────────────────────────────────────────────────────────
// 正则
// ────────────────────────────────────────────────────────────

/** 酒馆数字 placement → 新格式 target。4 在官方已被废弃，故缺席。 */
private val REGEX_TARGET_FROM_ST: Map<Int, RegexTarget> = mapOf(
    1 to RegexTarget.USER_INPUT,
    2 to RegexTarget.AI_OUTPUT,
    3 to RegexTarget.SLASH_COMMANDS,
    5 to RegexTarget.WORLD_BOOK,
    6 to RegexTarget.REASONING,
)

/** 旧 / 字符串形式的 target 别名 */
private val REGEX_TARGET_ALIAS: Map<String, RegexTarget> = mapOf(
    "userInput" to RegexTarget.USER_INPUT,
    "aiOutput" to RegexTarget.AI_OUTPUT,
    "slashCommands" to RegexTarget.SLASH_COMMANDS,
    "worldBook" to RegexTarget.WORLD_BOOK,
    "reasoning" to RegexTarget.REASONING,
    "user" to RegexTarget.USER_INPUT,
    "model" to RegexTarget.AI_OUTPUT,
    "assistant_response" to RegexTarget.AI_OUTPUT,
    "preset" to RegexTarget.SLASH_COMMANDS,
    "world_book" to RegexTarget.WORLD_BOOK,
)

private fun normalizeRegexTarget(e: JsonElement?): RegexTarget? {
    val p = e as? JsonPrimitive ?: return null
    p.intOrNull?.let { return REGEX_TARGET_FROM_ST[it] }
    val s = p.contentOrNull?.trim() ?: return null
    if (s.isEmpty()) return null
    s.toIntOrNull()?.let { return REGEX_TARGET_FROM_ST[it] }
    return REGEX_TARGET_ALIAS[s]
}

private fun normalizeRegexView(e: JsonElement?): RegexView? {
    val s = (e as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
    return when (s) {
        "user", "user_view" -> RegexView.USER
        "model", "model_view", "assistant_view" -> RegexView.MODEL
        else -> null
    }
}

private fun normalizeRegexMacroMode(e: JsonElement?): RegexMacroMode {
    val p = e as? JsonPrimitive ?: return RegexMacroMode.NONE
    p.intOrNull?.let { i ->
        return when (i) {
            1 -> RegexMacroMode.RAW
            2 -> RegexMacroMode.ESCAPED
            else -> RegexMacroMode.NONE
        }
    }
    return when (p.contentOrNull?.trim()) {
        "raw" -> RegexMacroMode.RAW
        "escaped" -> RegexMacroMode.ESCAPED
        else -> RegexMacroMode.NONE
    }
}

/**
 * 单条酒馆正则 → [AssistantRegex]。
 *
 * 兼容三套字段命名：
 * - 现行：`findRegex` / `replaceString` / `trimStrings` / `placement` / `disabled` /
 *   `markdownOnly` / `promptOnly` / `substituteRegex` / `scriptName`
 * - 新格式：`replaceRegex` / `trimRegex` / `targets` / `view` / `enabled` / `macroMode` / `id`
 * - 本 App 旧字段：`replaceString`
 */
fun convertRegexScriptFromSillyTavern(raw: JsonElement?): AssistantRegex? {
    val o = raw.asObj() ?: return null
    // 空对象直接丢弃，避免导入出一堆空正则
    if (o.isEmpty()) return null

    val name = o.firstStr("name", "scriptName")
    val findRegex = o.firstStr("findRegex")
    val replace = o.firstStr("replaceRegex", "replaceString")

    val targets = buildSet {
        for (e in (o["targets"] ?: o["placement"]).asArray()) normalizeRegexTarget(e)?.let { add(it) }
        if (isEmpty()) {
            // 兜底：没有任何 target 信息时，等价于「全部历史消息」
            add(RegexTarget.USER_INPUT)
            add(RegexTarget.AI_OUTPUT)
        }
    }

    val views = buildSet {
        for (e in o["view"].asArray()) normalizeRegexView(e)?.let { add(it) }
        if (isEmpty()) {
            if (o.bool("markdownOnly") == true) add(RegexView.USER)
            if (o.bool("promptOnly") == true) add(RegexView.MODEL)
        }
    }

    val enabled = o.bool("enabled") ?: o.bool("disabled")?.let { !it } ?: true

    return AssistantRegex(
        id = Uuid.random(),
        name = name,
        enabled = enabled,
        findRegex = findRegex,
        replaceString = replace,
        // 旧字段同步一份，保证列表页/旧执行路径立刻能用
        affectingScope = buildSet {
            if (RegexTarget.USER_INPUT in targets) add(AssistantAffectScope.USER)
            if (RegexTarget.AI_OUTPUT in targets) add(AssistantAffectScope.ASSISTANT)
        },
        visualOnly = views.isNotEmpty() && views.all { it == RegexView.USER },
        externalId = o.firstStr("id"),
        targets = targets,
        regexView = views,
        macroMode = normalizeRegexMacroMode(o["macroMode"] ?: o["substituteRegex"]),
        trimRegex = (o["trimRegex"] ?: o["trimStrings"]).asArray()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        minDepth = o.int("minDepth"),
        maxDepth = o.int("maxDepth"),
        runOnEdit = o.bool("runOnEdit") ?: true,
    )
}

/**
 * 正则集合（多形态）→ 列表。
 * 接受：单个对象 / 对象数组 / `{regexScripts:[...]}` / `{scripts:[...]}` / 上述任意嵌套。
 */
fun convertRegexScriptsFromSillyTavern(input: JsonElement?): List<AssistantRegex> {
    val raw = mutableListOf<JsonElement>()
    collectRegexItems(input, raw)
    return raw.mapNotNull { convertRegexScriptFromSillyTavern(it) }
}

private fun collectRegexItems(input: JsonElement?, out: MutableList<JsonElement>) {
    if (input == null || input is JsonNull) return
    when (input) {
        is JsonArray -> input.forEach { collectRegexItems(it, out) }

        is JsonObject -> {
            val nested = input["regexScripts"] ?: input["scripts"]
            if (nested is JsonArray) collectRegexItems(nested, out) else out.add(input)
        }

        else -> out.add(input)
    }
}

// ────────────────────────────────────────────────────────────
// 预设
// ────────────────────────────────────────────────────────────

/** 从 utility/other 里抽出的字段名（抽取后要从 other 里删掉，避免重复保管） */
private val UTILITY_PROMPT_KEYS = listOf(
    "impersonation_prompt", "wi_format", "scenario_format", "personality_format",
    "group_nudge_prompt", "new_chat_prompt", "new_group_chat_prompt",
    "new_example_chat_prompt", "continue_nudge_prompt", "send_if_empty", "seed",
    "impersonationPrompt", "wiFormat", "worldInfoFormat", "scenarioFormat",
    "personalityFormat", "groupNudgePrompt", "newChatPrompt", "newGroupChatPrompt",
    "newExampleChatPrompt", "continueNudgePrompt", "sendIfEmpty",
)

private fun extractUtilityPrompts(other: JsonObject?): UtilityPrompts {
    if (other == null) return UtilityPrompts()
    fun pick(vararg keys: String): String {
        for (k in keys) {
            val v = other.str(k)
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }
    return UtilityPrompts(
        impersonationPrompt = pick("impersonation_prompt", "impersonationPrompt"),
        worldInfoFormat = pick("wi_format", "wiFormat", "worldInfoFormat"),
        scenarioFormat = pick("scenario_format", "scenarioFormat"),
        personalityFormat = pick("personality_format", "personalityFormat"),
        groupNudgePrompt = pick("group_nudge_prompt", "groupNudgePrompt"),
        newChatPrompt = pick("new_chat_prompt", "newChatPrompt"),
        newGroupChatPrompt = pick("new_group_chat_prompt", "newGroupChatPrompt"),
        newExampleChatPrompt = pick("new_example_chat_prompt", "newExampleChatPrompt"),
        continueNudgePrompt = pick("continue_nudge_prompt", "continueNudgePrompt"),
        sendIfEmpty = pick("send_if_empty", "sendIfEmpty"),
        seed = other.int("seed"),
    )
}

private fun mergeUtility(base: UtilityPrompts, patch: UtilityPrompts): UtilityPrompts = UtilityPrompts(
    impersonationPrompt = patch.impersonationPrompt.ifEmpty { base.impersonationPrompt },
    worldInfoFormat = patch.worldInfoFormat.ifEmpty { base.worldInfoFormat },
    scenarioFormat = patch.scenarioFormat.ifEmpty { base.scenarioFormat },
    personalityFormat = patch.personalityFormat.ifEmpty { base.personalityFormat },
    groupNudgePrompt = patch.groupNudgePrompt.ifEmpty { base.groupNudgePrompt },
    newChatPrompt = patch.newChatPrompt.ifEmpty { base.newChatPrompt },
    newGroupChatPrompt = patch.newGroupChatPrompt.ifEmpty { base.newGroupChatPrompt },
    newExampleChatPrompt = patch.newExampleChatPrompt.ifEmpty { base.newExampleChatPrompt },
    continueNudgePrompt = patch.continueNudgePrompt.ifEmpty { base.continueNudgePrompt },
    sendIfEmpty = patch.sendIfEmpty.ifEmpty { base.sendIfEmpty },
    seed = patch.seed ?: base.seed,
)

private fun normalizeUtilityPrompts(e: JsonElement?): UtilityPrompts {
    val o = e.asObj() ?: return UtilityPrompts()
    fun g(key: String) = o.str(key).orEmpty()
    return UtilityPrompts(
        impersonationPrompt = g("impersonationPrompt"),
        worldInfoFormat = g("worldInfoFormat"),
        scenarioFormat = g("scenarioFormat"),
        personalityFormat = g("personalityFormat"),
        groupNudgePrompt = g("groupNudgePrompt"),
        newChatPrompt = g("newChatPrompt"),
        newGroupChatPrompt = g("newGroupChatPrompt"),
        newExampleChatPrompt = g("newExampleChatPrompt"),
        continueNudgePrompt = g("continueNudgePrompt"),
        sendIfEmpty = g("sendIfEmpty"),
        seed = o.int("seed"),
    )
}

/** prompt_order → [identifier -> (enabled, index)]。数组形态时取最后一个含 order 的条目。 */
private fun readPromptOrder(raw: JsonElement?): LinkedHashMap<String, Pair<Boolean, Int>> {
    val out = LinkedHashMap<String, Pair<Boolean, Int>>()
    val orderArray: List<JsonElement> = when {
        raw is JsonArray -> {
            val last = raw.filterIsInstance<JsonObject>()
                .lastOrNull { it["order"] is JsonArray }
            last?.get("order").asArray()
        }

        raw is JsonObject && raw["order"] is JsonArray -> raw["order"].asArray()
        else -> emptyList()
    }
    orderArray.forEachIndexed { idx, e ->
        val o = e.asObj() ?: return@forEachIndexed
        val id = o.str("identifier") ?: return@forEachIndexed
        out[id] = Pair(o.bool("enabled") ?: true, idx)
    }
    return out
}

/**
 * 单条酒馆 prompt → [PromptItem]。
 * @param orderMap prompt_order 解析结果（键是酒馆**原始** identifier）
 */
private fun convertPromptFromSillyTavern(
    raw: JsonElement?,
    orderMap: Map<String, Pair<Boolean, Int>>,
    fallbackIndex: Int,
): PromptItem? {
    val o = raw.asObj() ?: return null

    val rawIdentifier = o.str("identifier").orEmpty().trim().ifEmpty { "prompt_$fallbackIndex" }
    val identifier = ST_IDENTIFIER_MAP[rawIdentifier] ?: rawIdentifier
    // prompt_order 里用的是酒馆原始 identifier，两个键都试
    val orderItem = orderMap[rawIdentifier] ?: orderMap[identifier]

    val injectionPosition = o.int("injection_position") ?: 0
    val position = when (o.str("position")) {
        "relative" -> PromptPosition.RELATIVE
        "fixed" -> PromptPosition.FIXED
        else -> if (injectionPosition == 1) PromptPosition.FIXED else PromptPosition.RELATIVE
    }

    val enabled = orderItem?.first
        ?: if (orderMap.isNotEmpty()) false else (o.bool("enabled") ?: true)

    val explicitIndex = o.int("index")

    return PromptItem(
        identifier = identifier,
        name = o.firstStr("name").ifEmpty { identifier },
        enabled = enabled,
        index = orderItem?.second ?: explicitIndex ?: Int.MAX_VALUE,
        role = parseMessageRole(o.str("role")),
        content = o.str("content").orEmpty(),
        depth = o.int("injection_depth") ?: o.int("depth") ?: 0,
        order = o.int("injection_order") ?: o.int("order") ?: 100,
        trigger = o["injection_trigger"].asArray()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        position = position,
    )
}

private fun parseMessageRole(raw: String?): MessageRole = when (raw?.trim()?.lowercase()) {
    "user" -> MessageRole.USER
    "model", "assistant" -> MessageRole.ASSISTANT
    else -> MessageRole.SYSTEM
}

/**
 * 酒馆预设 JSON（字符串）→ [PromptPreset]。
 *
 * 接受三种形态：
 * 1. ST 导出的 Chat Completion 预设（顶层直接是 `prompts` / `prompt_order` / 采样参数）
 * 2. `{ "preset": {...} }` 包装
 * 3. `{ "presets": [ {...}, ... ] }` 数组（取第一份，其余靠 [parsePromptPresets]）
 *
 * 失败返回 null（调用方负责提示用户），绝不抛异常。
 */
fun parsePromptPreset(raw: String, fallbackName: String = "Imported"): PromptPreset? =
    parsePromptPresets(raw, fallbackName).firstOrNull()

/** 预设 JSON → 列表（一份文件里可能有多份预设） */
fun parsePromptPresets(raw: String, fallbackName: String = "Imported"): List<PromptPreset> {
    val root = runCatching { presetJson.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()

    val candidates: List<JsonObject> = when {
        root is JsonArray -> root.filterIsInstance<JsonObject>()
        root is JsonObject && root["presets"] is JsonArray ->
            (root["presets"] as JsonArray).filterIsInstance<JsonObject>()

        root is JsonObject && root["preset"] is JsonObject ->
            listOf(root["preset"] as JsonObject)

        root is JsonObject -> listOf(root)
        else -> emptyList()
    }

    return candidates.mapIndexed { i, o ->
        convertPreset(o, if (candidates.size == 1) fallbackName else "$fallbackName ${i + 1}")
    }
}

private fun convertPreset(raw: JsonObject, fallbackName: String): PromptPreset {
    // 1) other 源：new.other > old.apiSetting > 旧平铺字段
    val otherSource: JsonObject = when {
        raw["other"] is JsonObject -> JsonObject((raw["other"] as JsonObject).toMutableMap())
        raw["apiSetting"] is JsonObject -> JsonObject((raw["apiSetting"] as JsonObject).toMutableMap())
        else -> JsonObject(
            raw.toMutableMap().apply {
                remove("name")
                remove("prompts")
                remove("prompt_order")
                remove("regexScripts")
                remove("utilityPrompts")
                remove("other")
                remove("apiSetting")
            }
        )
    }.let { o ->
        JsonObject(o.toMutableMap().apply { remove("prompts"); remove("prompt_order") })
    }

    // 2) utilityPrompts：other 提取 + 显式字段覆盖
    val utilityFromOther = extractUtilityPrompts(otherSource)
    val otherStripped = JsonObject(
        otherSource.toMutableMap().apply { UTILITY_PROMPT_KEYS.forEach { remove(it) } }
    )
    val utility = mergeUtility(utilityFromOther, normalizeUtilityPrompts(raw["utilityPrompts"]))

    // 3) regexScripts：显式字段优先，否则 other.extensions.regex_scripts
    val regexScripts: List<AssistantRegex> = if (raw.containsKey("regexScripts")) {
        convertRegexScriptsFromSillyTavern(raw["regexScripts"])
    } else {
        val ext = otherStripped["extensions"] as? JsonObject
        convertRegexScriptsFromSillyTavern(ext?.get("regex_scripts") ?: ext?.get("regexScripts"))
    }

    // 3b) 酒馆助手脚本：不执行，只计数 —— 导入时必须让用户知道哪些逻辑会缺失
    val tavernHelperScripts: Int = run {
        val ext = otherStripped["extensions"] as? JsonObject ?: return@run 0
        val helper = ext["tavern_helper"] as? JsonObject ?: return@run 0
        (helper["scripts"] as? JsonArray)?.size ?: 0
    }

    // 4) prompt_order
    val apiSetting = raw["apiSetting"] as? JsonObject
    val rawOther = raw["other"] as? JsonObject
    val orderMap = readPromptOrder(
        raw["prompt_order"]
            ?: apiSetting?.get("prompt_order")
            ?: rawOther?.get("prompt_order")
    )

    // 5) prompts
    val prompts = raw["prompts"].asArray()
        .mapIndexedNotNull { i, e -> convertPromptFromSillyTavern(e, orderMap, i) }
        .sortedWith(
            compareBy(
                { if (it.index == Int.MAX_VALUE) Double.POSITIVE_INFINITY else it.index.toDouble() },
            )
        )
        .mapIndexed { i, p -> p.copy(index = i) }

    val name = raw.firstStr("name").ifEmpty { fallbackName }

    return PromptPreset(
        name = name,
        prompts = prompts,
        regexScripts = regexScripts,
        utilityPrompts = utility,
        other = otherStripped.toString(),
        sourceKind = if (apiSetting != null && !raw.containsKey("other")) {
            SOURCE_KIND_TEXTGEN
        } else {
            SOURCE_KIND_OPENAI
        },
        tavernHelperScripts = tavernHelperScripts,
    )
}

/**
 * 取助手绑定的预设；未绑定 presetId 或找不到时回退内置默认预设。
 * 只在 `assistant.tavernMode == true` 时才会被用到，所以回退代价可以接受。
 */
fun resolvePromptPreset(assistant: Assistant, presets: List<PromptPreset>): PromptPreset =
    presets.firstOrNull { it.id == assistant.presetId } ?: builtinDefaultPreset()

/**
 * 内置默认预设 —— 用户没导入预设、又想开酒馆模式时的兜底。
 * 骨架顺序对齐酒馆默认 openai 预设。
 */
fun builtinDefaultPreset(): PromptPreset = PromptPreset(
    name = "Default (内置)",
    builtin = true,
    sourceKind = SOURCE_KIND_OPENAI,
    prompts = ST_DEFAULT_PROMPT_ORDER.mapIndexed { i, id ->
        PromptItem(
            identifier = id,
            name = id,
            enabled = true,
            index = i,
            role = MessageRole.SYSTEM,
            content = if (id == "main") ST_DEFAULT_MAIN_PROMPT else "",
            position = PromptPosition.RELATIVE,
        )
    },
)
