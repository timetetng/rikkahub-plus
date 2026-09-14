# Rikka+ · RikkaHub Plus（自维护分支）

> 跑在 Android 上的原生 LLM 聊天客户端（Kotlin + Jetpack Compose + Material You），支持桥接 **DroidSpaces** 容器，强依赖自定义内核以及 KernelSU。
> **免责声明：本仓库（`timetetng/rikkahub-plus` 分支 `main`）完全自用，不提供除此文档之外的教学、不确保在任何设备上能正常运行，可以让 agent 自行适配。**

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
| **新增执行环境工具 `env_*`** | 7 个工具 + `target` 参数：**一套工具跑遍 droidspaces 容器 / 真机全局 / ZeroTermux / 远程 ssh 主机**，路径按宿主视角写会自动翻译（见 §4）。2.5.0 引入容器版，2.6.0 通用化，此后加 `ssh:<别名>` |
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
| **`env_*`（本分支新增）** | **执行环境工具：容器 / 真机 / Termux，见 §4** |
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

## 4. 执行环境工具（`env_*`）— 本分支新增

**一套工具，多个环境**：让 AI 直接在本机的 droidspaces 容器、真机全局命名空间或 ZeroTermux 里干活，不必手写 `su -c ... <<'EOF'` 那套转义。

> 完整使用文档（自举装机、自检清单、坑清单）：[`docs/agent-exec-guide.md`](docs/agent-exec-guide.md)；同一套工具也支持远程 ssh 主机（`target=ssh:<别名>`，别名取自容器 `~/.ssh/config`）。

| 工具 | 参数 | 行为 |
|---|---|---|
| `env_exec` | `command`, `target?`, `cwd?`, `timeout_sec?` | 在指定环境执行命令，返回 `exit_code / stdout / stderr`；超时（默认取助手「单工具执行超时」，上限 600s）**会 kill 进程** |
| `env_read_file` | `path`, `target?`, `offset?`, `limit?` | 逐行读文本；无 `offset/limit` 且 > 2 MB 时拒绝 |
| `env_write_file` | `path`, `content`, `target?` | base64 管道写入（内容含引号 / 换行 / emoji 均无需转义），自动建父目录，上限 2 MB |
| `env_edit_file` | `path`, `old_string`, `new_string`, `replace_all?`, `target?` | 精确替换（read-modify-write，**不依赖目标环境的 python3**）；`old_string` 不存在直接报错；多处匹配需显式 `replace_all` |
| `env_list` | `target?` | 环境总览：运行中的 droidspaces 容器、各环境后台任务、容器内 `docker ps` |
| `env_bg` | `name`, `command`, `target?` | 后台长任务：容器里交给 systemd（`/root/.wsjobs/`），root/termux 用 `setsid` + 日志/pid 文件；**立即返回** |
| `env_log` | `name`, `lines?`, `target?` | 取后台任务的状态与日志 |

**`target` 指定这次落在哪儿**（省略 = 助手卡片里的默认环境）：

| 值 | 含义 |
|---|---|
| 省略 | 助手配置的默认环境 |
| `arch` | 默认的 droidspaces 容器 |
| `ct:<名字>` | 指定的另一个 droidspaces 容器（裸容器名也认） |
| `root` | 真机全局 mount ns（全部 `/data/user/0`、`/data/adb`、`/system`） |
| `termux` | ZeroTermux |
| `ssh:<别名>` | 远程主机（云服务器 / 家里机器…）：走**容器里**的 `ssh`，别名、密钥、端口、跳板全部取容器 root 的 `~/.ssh/config`，七个工具照常可用 |

**路径按宿主视角写就行**：容器把宿主目录 bind 到 `/mnt/*`，工具会自动翻译并在结果里回显 `path_mapped`（例如 `/data/local/x → /mnt/hostlocal/x`），反向也认。

**实现方式**：命令经 `su -c /data/local/exec-tool.sh <mode> <容器名>` 送进目标环境，命令体写进执行器进程的 **stdin** → 天然 heredoc，引号 / 反引号 / `$()` / 内嵌 heredoc 一律原样。全部工具**免审批**。

**助手设置里可配（只配默认值，单次调用都能覆盖）**：

| 配置项 | 默认 | 说明 |
|---|---|---|
| 默认环境 | `arch` | `arch` / `root` / `termux` / `ct:<容器名>` |
| 默认工作目录 | `/root` | 该环境视角的绝对路径（root 环境默认 `/`） |

**边界与注意**：

- `env_bg` 起的后台任务**不继承**执行器注入的环境变量（如 `GITHUB_TOKEN`）——脚本里自行 `. /etc/agent.env`
- 单次读写上限 2 MB；更多用 `env_exec` + heredoc
- 这些工具**不做任何挂载**；要加挂载请改容器配置后重启容器
- 执行器 `/data/local/exec-tool.sh` 需存在于设备上（本仓库不包含它，属设备侧运维脚本）

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

## 6. 运行前提（换设备 / 换容器时要动的）

这套东西**不是纯 APK 就能跑**——它依赖设备侧的三件套：

| 组件 | 作用 | 备注 |
|---|---|---|
| **KernelSU(-Next) + `su`** | app 以 root 身份发起命令 | `su` 在 `/system/bin/su`，免密；本 app 需在 KSU 里授权 |
| **droidspaces + 一个容器** | 真正干活的 Linux 环境 | 容器名可配（见 §4 的「默认环境」）；换容器只改这一个值 |
| **设备侧执行器** `/data/local/exec-tool.sh`（+ `/data/local/ws`） | 把「su → droidspaces → 容器 shell」三层封成一个入口，顺带处理 mount ns 遮蔽 | 仓库内有**可移植副本** `docs/device/exec-tool.sh`、`docs/device/ws`；装机与自举步骤见 [`docs/agent-exec-guide.md`](docs/agent-exec-guide.md) §1。容器名作为第 2 参数传入，换容器**不用改脚本** |

换一个设备复刻时，需要按顺序确认：① KSU 授权 → ② `droidspaces show` 能看到容器 → ③ `/data/local/exec-tool.sh <mode> <容器名>` 能进容器 → ④ 助手卡片里把「默认环境」改成你的容器名。

> 换个发行版的容器（例如 Kali 而非 Arch）时，只有**容器内**的包管理与服务管理要换（`apt` / `pacman`、systemd 有或没有）；`env_*` 工具与执行器本身与发行版无关。容器内没有 systemd 时，`env_bg` 的 systemd 路径不可用，改用「root/termux 那套 `setsid` + 日志」思路即可。

## 7. 构建

**CI 只在两种情况下跑**（`.github/workflows/build.yml`）：

| 触发 | 行为 |
|---|---|
| **推版本 tag**（`v*`，如 `v2.6.0`） | release 构建：上传 artifact + **自动发 GitHub Release**（APK 挂在 Release 上，有固定下载链接） |
| **手动 dispatch**（Actions 页 Run workflow，或 API） | 只出 artifact；不发 Release、**不占版本号**（验证能否编译用这个） |

push 到 `main` **不再**触发构建 —— 改文档、改注释不浪费 CI 时间。

**发布流程**：改代码 → bump `versionCode` / `versionName` → 提交推 `main` → 打 tag `vX.Y.Z` 推上去 → CI 出包 + 发 Release。

**本地**：

```bash
./gradlew assembleRelease -x :web:buildWebUi    # 跳过 web 前端构建（需预装 pnpm）
./gradlew test                                  # JVM 单元测试
./gradlew lint                                  # Android Lint
```

- **签名**：仓库自带 `app/app.key`（`storePassword=keyPassword=android`）与 CI 里的 `local.properties` 生成步骤 → 重新构建的 APK 与原包同签名，可**直接覆盖安装、数据无损**
- Python 环境：Chaquopy 3.12，依赖见 `app/build.gradle.kts` 的 `chaquopy.pip` 块
- 需要 Android SDK（compileSdk 37 / minSdk 26）与 JDK 21

**版本策略**：每次发版 `versionCode` **+1**；`versionName` 走语义化 `MAJOR.MINOR.PATCH`——功能变更 `MINOR`+1，修 bug `PATCH`+1。
自 **`2.5.0`（code 175）** 起本分支独立计数（当前 `2.6.0` / 176），不再沿用中间层的 `2.4.6-clean` 后缀。

---

## 8. 许可与致谢

- 本项目遵循 **AGPL-3.0**（见 [LICENSE](LICENSE)）
- 基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（作者 rerere）与 [rikkahub-plus](https://github.com/heikeyangle-code/rikkahub-plus) 的酒馆增强层二次开发
