# Rikka+ · RikkaHub Plus（自维护分支）

> 跑在 Android 上的原生 LLM 聊天客户端（Kotlin + Jetpack Compose + Material You）。
> **自 2026-09 起，本仓库（`timetetng/rikkahub-plus` 分支 `main`）完全由我们自己维护。**

| | |
|---|---|
| 包名 / 应用名 | `me.rerere.rikkahub` / **Rikka+** |
| 基座 | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) `2.4.6` |
| 中间层 | [heikeyangle-code/rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) 的「酒馆增强层」 |
| 本分支 | `main`（默认分支，唯一开发主线）；`master` 仅作原件备份 |
| 许可 | [AGPL-3.0](LICENSE) |

---

## 1. 本分支做了什么（相对中间层）

| 改动 | 说明 |
|---|---|
| **移除命理 / 排盘** | 工具层（`mingli` / `mingli_guide` 工具、14 份强制解读模板、系统提示里的排盘工作流）与**引擎层**（13 个 QuickJS 排盘引擎、约 20 个 Python 命理包、`offline_pkgs/` 全部轮子）一并删除；对应的 19 个 CI 步骤、Chaquopy 依赖、离线包缓存全部清掉 → 构建时间与 APK 体积同步下降 |
| **移除硬编码提示词注入** | 中间层塞进系统提示的 `<tool_selection>` / `<work_ethic>` / `<mingli_workflow>` 三段全部删除，提示词回到「由助手卡片（system prompt）自己决定」 |
| **永久停用更新检查** | 不再请求 `update.json`，抽屉里不会弹更新卡；版本策略改为「自己构建、自己安装」 |
| **新增 droidspaces 容器桥接** | 7 个 `container_*` 工具，让 AI 能在本机 droidspaces 容器里执行命令、读写文件、跑后台长任务（见 §4） |
| **仓库卫生** | 删除误入仓库的构建日志 zip、英文 README、死代码（`enableMingliTools`）、作者遗留的补丁脚本目录 `ci/` |

---

## 2. 功能总览

### 2.1 模型与对话

- **多提供商**：OpenAI / Anthropic(Claude) / Google(Gemini) 协议，以及任意兼容 API 的自定义 provider（自定义 base URL、headers、body、模型列表）
- 流式输出、思考链（reasoning）展示与推理等级控制、多模型收藏与快速切换
- 对话分支、重新生成、消息编辑 / 删除 / 翻译、预设消息
- **全文搜索**：会话与消息全文检索（jieba 中文分词，`app/src/main/assets/simple_dict/`）
- 多模态输入：图片、文件附件；图片生成调用

### 2.2 酒馆（SillyTavern）兼容层

- **角色卡**：V2/V3 JSON（PNG 内嵌）导入 → 20+ 字段结构化保留（示例对话、备选开场白、作者备注、历史后指令、深度提示、内嵌世界书、extensions 原样），按官方 Chat Completion 注入结构组装；支持 PNG / JSON **无损导出**与 22 字段可视化编辑页
- **世界书（Lorebook）**：逐条对齐官方 `world-info.js` 语义——四档关键词逻辑、整词 / 正则 / 大小写、条目级扫描深度、常驻、跨书分组与权重、概率、粘性 / 冷却、延迟激活、递归控制与层级开放、token 预算与豁免、`match_*` 字段——完整 `checkWorldInfo` 扫描状态机
- **宏引擎 2.0**：变量（`{{getvar::}}`、`/setvar` 等）、条件（`{{if}} / {{else}}`、比较与逻辑运算）、随机（`{{pick::A|B}}`、`{{roll::1d20}}`）、对话感知（`{{lastUserMessage}}`、`{{idleDuration}}`…）
- **斜杠命令**：21 个内置命令（`/impersonate` `/continue` `/sysgen` `/reroll-pick` `/char-update` `/persona` …），输入框直接执行，`/help` 查看全部
- **人设（Persona）**：五档注入位置（IN_PROMPT / TOP / BOTTOM / AT_DEPTH / NONE）、按角色绑定、独立 SYSTEM 消息
- **导演备注（Author's Note）**：官方间隔语义、注入深度与角色
- **群聊**：多角色同场，独立提示词 / 人设 / 模型，四种选人策略 + 自动接话

### 2.3 助手与提示词工程

- 助手（Assistant）卡片：system prompt、上下文模板（ADF 风格）、正则输入 / 输出替换、预设与快捷消息、世界书 / 模式注入绑定
- **记忆系统**：全局或按助手隔离的长期记忆 + 自动记忆提取（间隔可配）
- 提示词注入、作者注、时间提醒、技能自动触发等 transformer 管线

### 2.4 技能系统（Skills）

- 技能 = 目录 + `SKILL.md`（frontmatter + 正文 + 附属文件），按需加载进上下文
- **自动触发**：命中关键词时自动注入，不必等模型主动调用
- **外部目录** `/Rikkahub/skills`：文件管理器直接放进去就能用
- **GitHub 一键安装 / 批量下载 / 更新检测**（记录安装源与目录哈希）、内置技能注册表

### 2.5 AI 工具集

模型可调用的本地能力（在助手设置里逐项开关）：

| 工具 | 说明 |
|---|---|
| `file_read/write/list/search/copy/move/delete` | 沙箱内文件操作 |
| `execute_command` | App 沙箱 shell |
| `execute_python` | Chaquopy 3.12 常驻解释器 |
| `eval_javascript` | QuickJS（ES2020，持久上下文，`load` / `eval` / `reset`） |
| `calculator` / `database_query` | 700+ 函数计算器；SQLite 只读查询 |
| `task_*` / `memory_*` / `use_skill` | 任务待办 / 长期记忆 / 技能加载 |
| `get_time` `clipboard` `text_to_speech` `ask_user` `present_file` `screen_time` `calendar_*` | 设备能力 |
| `web_fetch` | 任意 URL 的 HTTP 请求（GET/POST/PUT/PATCH/DELETE） |
| `search_*` | 18 种搜索后端：Brave / Bing / Exa / Tavily / SearXNG / Serper / Jina / Firecrawl / Perplexity / 智谱 / Bocha / Metaso / LinkUp / Ollama / Grok / Tinyfish / RikkaHub / 自定义 JS |
| `workspace_*` | 工作区（proot 沙箱）读写与 shell |
| `conversation_*` / `worker_*` / `teammate_*` / `send_message` | 跨会话与多智能体协作 |
| **`container_*`（本分支新增）** | **droidspaces 容器桥接，见 §4** |
| MCP | 接入任意 MCP 服务器，工具自动并入工具表 |

### 2.6 文档与多媒体

- 文档解析：**PDF / DOCX / PPTX / EPUB**
- 图片输入、图片生成、代码高亮（`highlight` 模块）
- 聊天导出（Markdown / JSON / 图片等）

### 2.7 语音

- **ASR**：DashScope / StepFun / OpenAI Realtime / MiMo / 火山引擎
- **TTS**：多 provider，可被 AI 通过 `text_to_speech` 工具调用

### 2.8 数据、备份与同步

- 备份 / 恢复：本地文件、**S3**、**WebDAV**，支持定时提醒
- 导入 / 导出助手与设置

### 2.9 网页端与开发工具

- `web/` + `web-ui/`：内置 Web 服务端与配套前端（React Router + pnpm）
- `trace-cli/`、`locale-tui/`、`search/`、`speech/`、`document/`、`ai/`、`workspace/` 等独立模块

---

## 3. 目录结构

```
app/            Android 主模块：UI / ViewModel / 工具 / 数据层
ai/             AI SDK 抽象（OpenAI / Anthropic / Google 协议）
search/         搜索后端实现
speech/         ASR / TTS
document/       PDF / DOCX / PPTX / EPUB 解析
workspace/      proot 工作区沙箱
web/ web-ui/    内置 Web 服务端与前端
highlight/      material3/  common/   基础与主题
build-logic/    Gradle 约定插件
```

---

## 4. 容器桥接（`container_*`）— 本分支新增

让 AI 直接在本机的 **droidspaces 容器**（或真机全局 / Termux 环境）里干活，不必手写 `su -c ... <<'EOF'` 那套转义。

| 工具 | 参数 | 行为 |
|---|---|---|
| `container_exec` | `command`, `cwd?`, `timeout_sec?` | 容器内执行命令，返回 `exit_code / stdout / stderr`；超时（默认取助手「单工具执行超时」，上限 600s）**会 kill 进程** |
| `container_read_file` | `path`, `offset?`, `limit?` | 逐行读文本；无 `offset/limit` 且文件 > 2 MB 时拒绝 |
| `container_write_file` | `path`, `content` | base64 管道写入（内容含引号 / 换行 / emoji 均安全），自动建父目录，上限 2 MB |
| `container_edit_file` | `path`, `old_string`, `new_string`, `replace_all?` | 精确字符串替换；`old_string` 不存在**直接报错**（不静默写入）；多处匹配需显式 `replace_all` |
| `container_list` | — | 当前容器列表 + 后台任务 + 容器内 `docker ps` |
| `container_bg` | `name`, `command` | 交给容器 systemd 后台跑（长任务唯一正解），**立即返回** |
| `container_log` | `name`, `lines?` | 取后台任务的状态与日志 |

**实现方式**：命令经 `su -c /data/local/exec-tool.sh <mode> <容器名>` 送进容器，命令体写进执行器进程的 **stdin**（执行器内部 `CMD=$(cat)`）→ 天然 heredoc，引号 / 反引号 / `$()` / 内嵌 heredoc 一律原样，不存在二次转义。全部工具**免审批**。

**助手设置里可配**：

| 配置项 | 默认 | 说明 |
|---|---|---|
| 容器模式 | `arch` | `arch` = droidspaces 容器；`root` = 真机全局命名空间；`termux` = ZeroTermux |
| 容器名 | `arch` | 配合 `arch` 模式使用 |
| 默认工作目录 | `/root` | 容器内绝对路径；单次调用可用 `cwd` 覆盖 |

**边界与注意**：

- `container_bg` / `container_log` 走宿主侧 `/data/local/ws`，因此后台任务**不会**继承执行器注入的环境变量（如 `GITHUB_TOKEN`）——脚本里自行 `. /etc/agent.env`
- `container_edit_file` 依赖容器内 `python3`
- 这些工具**不做任何挂载**；要加挂载请改容器配置后重启容器
- 执行器 `/data/local/exec-tool.sh` 需存在于设备上（本仓库不包含它，属设备侧运维脚本）

---

## 5. 分支与协作模型

| 分支 | 角色 |
|---|---|
| `main` | **唯一开发主线**，也是默认分支；push 触发 CI 构建 |
| `master` | 上游 / 中间层原件备份，只读保留 |
| 已删除 | `mingli` / `mingli2` / `backup-preset-overhaul-20260807` / `feature/claude-code-systems`（命理时代与作者遗留） |

远端约定：

- `fork` → `timetetng/rikkahub-plus`（本仓库，日常 push 目标）
- `origin` → 中间层作者仓库（只读参考）
- `up` → `rikkahub/rikkahub`（上游，用于观察与择优挑选）

**与上游的关系**：本分支以中间层的酒馆增强层为基座，已删除其中的命理体系；上游（RikkaHub）的新功能**按需择优并入**，不做整支 merge。

---

## 6. 构建

**CI**：push 到 `main` 自动触发 `.github/workflows/build.yml`（GitHub Actions），产物为 `app-arm64-v8a-release.apk`；`workflow_dispatch` 也可手动触发。

**本地**：

```bash
./gradlew assembleRelease -x :web:buildWebUi    # 跳过 web 前端构建（需预装 pnpm）
./gradlew test                                  # JVM 单元测试
./gradlew lint                                  # Android Lint
```

- **签名**：仓库自带 `app/app.key`（`storePassword=keyPassword=android`）与 CI 里的 `local.properties` 生成步骤 → 重新构建的 APK 与原包同签名，可**直接覆盖安装、数据无损**
- Python 环境：Chaquopy 3.12，依赖见 `app/build.gradle.kts` 的 `chaquopy.pip` 块
- 需要 Android SDK（compileSdk 37 / minSdk 26）与 JDK 21

---

## 7. 许可与致谢

- 本项目遵循 **AGPL-3.0**（见 [LICENSE](LICENSE)）
- 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（作者 rerere）与 [rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) 的酒馆增强层二次开发
