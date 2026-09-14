# DIVERGENCE — 本分支的来历、改动与合并手册

> 维护约定：本文件描述**本仓库（`timetetng/rikkahub-plus`，分支 `main`）相对上游的差异**。
> 每次并入上游功能、或改动核心链路后，同步更新对应条目。

## 0. 三层关系

```
rikkahub/rikkahub            ← 上游（原生 LLM 客户端）
        │  分叉点 aac6e963（2026-08-13，本地基座）
        ↓
heikeyangle-code/rikkahub-plus  ← 中间层（作者 fork：酒馆增强层 + 命理体系）
        ↓  我们在此之上继续开发
timetetng/rikkahub-plus @ main  ← 本仓库（自维护主线，2026-09 起）
```

| 项 | 值 |
|---|---|
| 本分支 | `main`（默认分支，唯一开发主线） |
| 保留备份 | `master`（中间层原件，只读） |
| 上游 | `up` → `rikkahub/rikkahub` `master` |
| 中间层 | `origin` → `heikeyangle-code/rikkahub-plus` |
| 分叉点 | `aac6e963`（2026-08-13）；上游此后领先约 148 个提交 |

## 1. 本分支相对中间层做了什么

| 改动 | 范围 |
|---|---|
| **移除命理 / 排盘（工具层）** | `MingliTool` / `MingliGuideTool` 及其在 `ChatService` 的注册、设置页开关、`assets/mingli/` 14 份解读模板、python 侧 `mingli_router` 与 `routes/` |
| **移除命理 / 排盘（引擎层）** | 13 个 QuickJS 引擎的 CI 编译步骤、约 20 个 Chaquopy Python 命理包、`app/offline_pkgs/` 全部轮子、`app/src/main/python/{bazi_china/, ziwei_paipan.py, tarot_elemental_engine.py, lenormand_engine.py, lenormand-spreads.json}`、`ci/` 下全部补丁脚本 |
| **移除硬编码提示词注入** | `GenerationHandler.leadInInstructions` 里的 `<tool_selection>` / `<work_ethic>` / `<mingli_workflow>` 三段 |
| **停用更新检查** | `ChatVM.updateState` 直接返回停用态；DataStore 里 `updateCheckDisabledUntilEpochMillis` 设为 2100 年（双保险）；`UpdateChecker.API_URL` 指向本仓库 `main/update.json` |
| **新增容器桥接** | `data/ai/tools/ContainerTools.kt` + `LocalToolOption.ContainerTools` + `Assistant` 三字段 + `ChatService` 两处注册 + 设置页 UI（详见 README §4） |
| **仓库卫生** | 删除误入仓库的 `ci_latest_logs.zip`、`README_EN.md`、死字段 `enableMingliTools`、`.gitignore` 里的 stellium 残留项 |

## 2. 高风险文件（改上游或并入上游时人工审）

| 文件 | 本地改了什么 |
|---|---|
| `service/ChatService.kt` | 工具构建（含 `container_*` 注册两处）、前台服务保活、群聊生成、斜杠注入、发送链路 |
| `data/ai/GenerationHandler.kt` | 系统提示组装、transformer 链、缓存 |
| `data/ai/transformers/PromptInjectionTransformer.kt` | 世界书官方语义对齐（选择性逻辑 / 分组 / 递归 / 粘性 / 预算） |
| `data/ai/transformers/PlaceholderTransformer.kt` | 宏引擎 2.0 接入 |
| `data/model/Assistant.kt` | 工具 / 技能 / 群聊 / 酒馆 / 宏 / 容器桥接字段 |
| `data/ai/tools/*` | 本地独有工具集（见 README §2.5） |
| `ui/pages/assistant/detail/AssistantLocalToolPage.kt` | 本地工具开关与容器桥接配置项 |
| `app/build.gradle.kts` | Chaquopy pip 依赖清单（已精简为通用依赖） |
| `.github/workflows/build.yml` | 自维护 CI（触发分支 `main`） |

## 3. 本地独有 / 已删除，勿再加回

- **不要加回**：命理体系（工具、引擎、模板、python 路由）、硬编码提示词注入三段、中间层的 `ci/` 补丁脚本、`offline_pkgs/` 离线轮子
- **本地独有模块**：`LocaleTui`、`trace-cli`、`sample-skills` 等（上游无对应文件，合并时不会冲突）
- 上游已移除但本地仍在用的旧 API：合并上游时若发现上游删掉了本地依赖的接口，**先补适配再合**

## 4. 并入上游功能的原则（用户硬要求）

1. **不做整支 merge**（历史教训：96 个冲突文件）；留在本基座**逐条择优 cherry-pick**
2. **不能漏前置依赖**（如工具装配重构这类前置提交）→ 做法：**分批累积 apply → 推 CI 真编译验证**，编译不过就回头补前置
3. 每批并入前先审核 + 解冲突 + 说明难度，**给用户看 diff 之后才推**
4. 已排除：voice / ASR / TTS（用户不用）、豆包搜索（无 key）、上游的 proot workspace 相关
