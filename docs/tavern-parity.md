# 酒馆（SillyTavern）兼容规范 · v1

> 目的：让同一张酒馆卡在 rikkahub 与 SillyTavern 里得到**同一条发送给模型的消息序列**。
> 参照实现：[Lianues/fast-tavern](https://github.com/Lianues/fast-tavern)（`py-fast-tavern` / `npm-fast-tavern`），
> 其字段对齐 `st-api-wrapper` 的**新格式**（`PresetInfo` / `PromptInfo` / `WorldBookEntry` / `RegexScriptData`）。

---

## 0. 兼容边界（三层，别混）

| 层 | 内容 | 归属 | v1 | 后续 |
|---|---|---|---|---|
| **① 卡** | 角色卡字段、内嵌世界书、**内嵌正则** | 卡自带 | ✅ 全部导入 | — |
| **② 预设** | prompt 列表、`utilityPrompts`、预设级正则、采样参数 | **玩家自己导入** | ✅ | — |
| **③ 脚本** | 酒馆助手（TavernHelper JS）、MVU 变量更新逻辑 | 卡/扩展自带 | ❌ 只做「识别 + 降级 + 静态提取」 | v2 变量数据层 / v3 起 JS 层 |

**v1 不做**：执行卡内任意 JS。理由：需要 STscript 解释器 + TavernHelper API 大半个 surface
（`eventOn` / `injectPrompts` / 楼层 DOM 渲染），且在用户卡里跑任意代码本身要单独设计沙箱。

**v1 的硬指标**：预设导入能跑通；同一张「卡 + 预设」在两边发出的消息序列**逐条一致**。

---

## 1. 现状（本 fork 已有什么）

| 能力 | 位置 | 备注 |
|---|---|---|
| 角色卡 V2/V3 全字段 | `data/model/TavernCard.kt` | 含 `depth_prompt`、`embeddedBook`、`extensionsRaw` 无损保留 |
| 卡导入 / 导出 | `ui/pages/assistant/detail/AssistantImporter.kt` · `utils/CardExporter.kt` | PNG chara chunk + JSON |
| 世界书 | `data/model/Assistant.kt` 的 `Lorebook` / `PromptInjection.RegexInjection` | **超集**：比 fast-tavern 多 sticky/cooldown/delay/budget/minActivations/groupScoring |
| 世界书激活与注入 | `data/ai/transformers/PromptInjectionTransformer.kt` | 粘性/冷却按 `assistantId:conversationId` 隔离 |
| 宏 2.0 | `data/ai/transformers/MacroEngine.kt` · `PlaceholderTransformer.kt` | if/else、变量族、datetimeformat、pick/roll… |
| 作者注 / Persona / 群聊 | `AuthorsNoteTransformer.kt` · `Persona.kt` · `GroupChat.kt` | 位置、深度、间隔、锁定 |
| 角色卡 UI / 开场白 | `ui/pages/assistant/detail/TavernCharacterCard.kt` | 1942 行 |

### 缺什么（v1 要补）

1. **预设层完全不存在**。repo 里 `preset` 全是主题/人设/`presetMessages`(开场白)的同名噪音。
   → 没有 prompt 列表，没有 `relative/fixed`，没有 `depth/order`，没有 `utilityPrompts`。
2. **装配顺序写死在 Kotlin 里**（这是「同一张卡体验不一样」的根因）：
   - `GenerationHandler.buildCachedSystemPrompt()` 的 `useOfficialSplit` 分支
   - `Assistant.assembleCharacterCardMessages()` 硬编码 描述→性格→场景 三条 system 消息
   - 世界书插槽靠 `UIMessageAnnotation.CharacterCardData` **反推**锚点
   - 酒馆里这些位置**全部由预设的 prompt 列表顺序决定**
3. **卡内嵌正则没导入**：`extensions.regex_scripts` 被忽略。
4. **正则模型是简化版**：`AssistantRegex` 只有 `findRegex/replaceString/affectingScope/visualOnly`；
   缺 `targets × view` 矩阵、`trimRegex`、`macroMode`、`minDepth/maxDepth`。
5. **变量缺作用域**：只有全局 `macroGlobalVariables`（跨对话持久）；缺
   - `local`（本次构建内，临时）
   - `chat` 级 global（酒馆 `chat_metadata.variables`，按对话隔离）
6. 无多阶段调试视图（`raw / afterMacro / afterPostRegex`）。

---

## 2. 数据结构（Kotlin，对齐 st-api-wrapper 新格式）

新增 `data/model/PromptPreset.kt`：

```kotlin
@Serializable
data class PromptPreset(
    val id: Uuid = Uuid.random(),
    val name: String = "Default",
    val prompts: List<PromptItem> = emptyList(),   // 顺序即骨架顺序
    val regexScripts: List<RegexRule> = emptyList(),
    val utilityPrompts: UtilityPrompts = UtilityPrompts(),
    val other: String = "",                        // 采样参数等原样 JSON（无损）
    val sourceKind: String = "openai",             // openai | textgenerationwebui | imported-raw
)

@Serializable
data class PromptItem(
    val identifier: String,          // "main" / "chatHistory" / "worldInfoBefore"…
    val name: String = "",
    val enabled: Boolean = true,
    val index: Int = 0,              // 骨架排序（导入时来自 prompt_order）
    val role: MessageRole = MessageRole.SYSTEM,
    val content: String = "",
    val depth: Int = 0,              // 仅 position=fixed 有意义
    val order: Int = 100,            // 仅 position=fixed 有意义
    val trigger: List<String> = emptyList(),
    val position: PromptPosition = PromptPosition.RELATIVE,  // relative | fixed
    val marker: Boolean = false,     // 是占位块（charDescription 等）还是真内容
)
```

`UtilityPrompts` 字段（对齐 `UtilityPrompts` TypedDict）：
`impersonationPrompt` `worldInfoFormat` `scenarioFormat` `personalityFormat`
`groupNudgePrompt` `newChatPrompt` `newGroupChatPrompt` `newExampleChatPrompt`
`continueNudgePrompt` `sendIfEmpty` `seed`

改动既有模型：

- `Assistant` 增 `tavernMode: Boolean = false`（酒馆模式开关）、`presetId: Uuid? = null`（引用全局预设库）
- `AssistantRegex` 增 `targets` / `view` / `macroMode` / `trimRegex` / `minDepth` / `maxDepth`，
  旧字段 `affectingScope` / `visualOnly` 保留做兼容映射（不删，否则存量卡解析失败）
- `Settings` 增 `promptPresets: List<PromptPreset>`（全局预设库，DataStore `PROMPT_PRESETS` 键）

---

## 3. 装配流水线（照搬 fast-tavern，逐步）

输入：`preset` + `assistant(角色卡)` + `lorebooks`（全局 ∪ 卡内嵌）+ `history` + `variables` + `macros`

```
1) 历史归一化      history → 线性节点，每条带 historyDepth（0 = 最后一条）
2) 宏/变量上下文   macros{user,char,persona,...} + variables{local, chatGlobal}
3) 世界书激活      always / keyword / vector(未实现) + probability + 递归
                   （沿用 rikkahub 现有引擎，它是超集；只在「插槽映射」处对齐）
4) 装配 tagged 列表 ← 核心，见 3.1
5) 正则合并        preset.regexScripts + assistant.regexes + 卡内嵌 regex
6) 分阶段编译      afterMacro(宏+变量宏) → afterPostRegex(正则)
7) 输出            openai / gemini / text / tagged
```

### 3.1 装配（`assembleTaggedPromptList` 语义，务必逐条照做）

```
enabled  = prompts.filter { enabled != false }
relative = enabled.filter { position == relative }      // 按 index 升序 = 骨架顺序

for p in relative:
    # (a) 世界书插槽条目：position != fixed 且 positionMap[entry.position] == p.identifier
    slot = activeEntries.filter { position != fixed && map(position) == p.identifier }
                        .sortedBy { order }
    emit slot...                                        // tag="Worldbook: <name>", target="worldBook"

    # (b) 主内容
    if p.identifier == "chatHistory":
        dialogue = history.map { node ->                   // role: user→userInput / model→aiOutput / 其他→slashCommands
            { tag="History: <role>", target, role, text, historyDepth }
        }
        injections = enabled.filter{position==fixed && depth/order 是数字} +    // target="slashCommands"
                     activeEntries.filter{position=="fixed" && depth/order 是数字}  // target="worldBook"

        # ★ 关键细节：同 depth 的条目插到同一位置时，后插的排在前面
        #   所以要按 (depth, -order, -idx) 排序（idx: preset 用 0..n，worldbook 用 10000+）
        injections.sortBy { (depth, -order, -idx) }

        originalCount = dialogue.size
        for inj in injections:
            targetIndex = max(0, originalCount - inj.depth)   // ★ 用 originalCount，不是当前长度
            dialogue.insert(targetIndex, inj)

        emit dialogue...; continue

    # (c) 普通 relative 块
    if p.content 非空: emit { tag="Preset: <name>", target="slashCommands", role, text=content }
```

`positionMap` 默认 `{ "beforeChar" → "charBefore", "afterChar" → "charAfter" }`，
`chatHistoryIdentifier` 默认 `"chatHistory"`。

### 3.2 酒馆 identifier 映射

导入时 `worldInfoBefore → charBefore`、`worldInfoAfter → charAfter`（`ST_IDENTIFIER_MAP`）。
`prompt_order` 里用的是**酒馆原始 identifier**，查表时两个键都要试。

### 3.3 预设导入（`convert_preset_from_silly_tavern` 语义）

```
other        = raw.other ?? raw.apiSetting ?? (去掉已知键后的其余字段)
utility      = extract(other) ⊕ raw.utilityPrompts        # 显式优先
regexScripts = raw.regexScripts ?? other.extensions.regex_scripts ?? []
order        = raw.prompt_order ?? apiSetting.prompt_order ?? other.prompt_order
               # 是数组时取【最后一个】含 order 数组的条目（character_id:100001 才是实际用的）
               # order_map[identifier] = {enabled, index=数组下标}
per prompt:
    position = raw.position ∈ {relative,fixed} ? raw.position
                                              : (injection_position == 1 ? fixed : relative)
    depth    = injection_depth ?? raw.depth ?? 0
    order    = injection_order ?? raw.order ?? 100
    enabled  = order_map[identifier]?.enabled ?? (order_map 非空 ? false : raw.enabled)
    index    = order_map[identifier]?.index ?? raw.index
prompts 按 index 升序（无 index 的排最后，保原下标序）
```

### 3.4 ST 默认预设顺序（导入的预设缺 `prompt_order` 时的兜底）

```
main → worldInfoBefore → charDescription → charPersonality → scenario
     → worldInfoAfter → enhanceDefinitions → dialogueExamples → chatHistory
```
（`nsfw` 紧随 main；`jailbreak` = Post-History Instructions，在 chatHistory 之后。）

**marker 块**（无内容，只占位）：`charDescription` `charPersonality` `scenario`
`worldInfoBefore` `worldInfoAfter` `dialogueExamples` `chatHistory` `enhanceDefinitions`。
marker 的文本由 rikkahub 现有的角色卡/世界书数据填充，不再硬编码位置。

---

## 4. 正则模型（补齐后语义）

```kotlin
@Serializable
data class RegexRule(
    val id: String, val name: String, val enabled: Boolean,
    val findRegex: String,                 // "/pattern/flags" 或 "pattern"
    val replaceRegex: String = "",         // {{match}} / $& / $1..$99
    val trimRegex: List<String> = emptyList(),
    val targets: Set<RegexTarget> = setOf(),   // userInput|aiOutput|slashCommands|worldBook|reasoning
    val view: Set<RegexView> = setOf(),         // user|model
    val macroMode: RegexMacroMode = NONE,       // none|raw|escaped
    val minDepth: Int? = null, val maxDepth: Int? = null,
)
```

- `{{match}}` / `$&` = **Trim Out 之后**的 match；`$1..$99` = 捕获组（不 trim）
- 替换结果里的宏**立即执行**
- `macroMode` **只影响 findRegex**：`none` 按字面 `{{user}}` 查；`raw` 先执行宏；`escaped` 执行后转义
- 语义顺序固定：**先宏，再正则**
- `minDepth/maxDepth` 只对 `userInput/aiOutput` 生效，`depth=0` = 最后一条历史
- `view`：`user` 只在显示侧、`model` 只在发送侧

**注意**：`AssistantRegex` 里的 `VariableLookbehind`（可变长后行断言模拟）是 rikkahub 独有能力，
Java 正则不支持，**移植到新模型时保留**。

---

## 5. 兼容矩阵

| 卡里带的东西 | 位置 | v1 行为 |
|---|---|---|
| `data.name/description/personality/scenario/first_mes/mes_example` | 顶层 | ✅ 已有 |
| `data.system_prompt` / `post_history_instructions` | 顶层 | ✅ 已有 |
| `data.extensions.depth_prompt` | 顶层 | ✅ 已有 |
| `data.character_book` | 顶层 | ✅ 已有 |
| **`data.extensions.regex_scripts`** | 顶层 | 🆕 **v1 导入**（每个脚本 → `AssistantRegex`/`RegexRule`） |
| `data.extensions.alternate_greetings` | 顶层 | ✅ 已有 |
| `data.extensions.talkativeness` / `fav` | 顶层 | ✅ 已有（并入 extensions） |
| `data.extensions.tavern_helper` | 顶层 | v1：**识别 + 报告**，列出用到的事件/API，不静默丢弃 |
| MVU（`initvar` / `get_message_variable` / `<UpdateVariable>`） | 世界书或卡内 | v2：变量数据层（message-scoped 变量 + 宏）；JS 更新逻辑 v3+ |

---

## 6. 分期与验收

**v1（本版）**
- 预设模型 + 全局预设库 + 导入（`openai` / `textgenerationwebui` 预设 JSON）
- `PromptAssembler`（3.1 流水线）+ 助手级「酒馆模式」开关 + 预设引用
- 卡内嵌正则导入 + 正则模型补齐 + 执行器按 `targets` 分区
- 预设列表 UI（排序 / 开关 / role / depth / order / marker）

**验收**
1. 导入一份真实酒馆预设 + 一张真实卡，开着酒馆模式发一条消息，
   逐条对比 fast-tavern 用同一份输入产出的 `stages.tagged.afterPostRegex` → **条数、role、顺序一致**
2. 卡内嵌正则能在设置里看到并生效
3. 关掉酒馆模式 → 现有行为**完全不变**（回归）

**对照 oracle**：把 `py-fast-tavern` 源码 vend 到 `app/src/main/python/`，
用 Chaquopy（3.12，零新依赖）在**测试路径**跑同一份输入做 diff；它的 pytest 也当回归用。
主生成链路不走 Python 桥（会丢多模态 parts 与工具调用结构）。
