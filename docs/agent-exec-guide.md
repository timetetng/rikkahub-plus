# 设备执行环境 · Agent 使用文档（droidspaces 版）

> **这份文档是写给「跑在这台手机上的 AI agent」看的**，不是给人类看的用户手册。
> 拓扑：**KernelSU(-Next) + ZeroTermux + rikkahub(本分支) + droidspaces 容器**。
> 你手上的 APK 是 **自维护分支的 2.6.0 及以后**（内置 `env_*` 执行环境工具）。
>
> 文中的 `arch` 是**容器名占位符**——你把这台机器的容器名（例如 `kali`）替进去即可。
> 凡是标「自检」的，装完请**实际跑一遍**，不要假设。

---

## 0. 先搞清楚你在哪一层

你（agent）跑在 **rikkahub 这个 Android app 的进程里**（untrusted_app 域、无 root、被 SELinux 与 scoped storage 限制）。
所有真正的操作都要经过下面四层之一，**别把 app 沙箱当成设备**：

| 层 | 你能看到什么 | 怎么进 |
|---|---|---|
| **app 沙箱**（默认） | 只有自己的 `/data/data/<包名>/`、`/storage/emulated/0` 的一部分 | 现成的文件/命令工具 |
| **容器**（droidspaces） | 一个完整 Linux（包管理器 / 服务 / 可能是 docker）；宿主目录以 `/mnt/*` 出现在容器里 | `target=arch`（换成你的容器名） |
| **真机全局 ns** | `/data/user/0/*`（所有 app 私有数据）、`/data/adb`、`/system`、`pm`/`dumpsys` | `target=root` |
| **ZeroTermux** | `$PREFIX`、`pkg install` | `target=termux` |

**记住这条路**：`app → su → 执行器 /data/local/exec-tool.sh → droidspaces → 容器 shell`。
执行器存在的意义是把「su 的 mount ns 遮蔽、droidspaces 参数、引号转义」全封在内部 —— **不要绕开它手写 `su`/`nsenter`/`mount`**。

---

## 1. 装机与初始化（做完这四步再干别的）

### 1.1 覆盖安装（人类做，你确认结果）
新旧包**同签名**（仓库自带 `app/app.key`），versionCode 递增 → 可直接覆盖安装、**数据保留**。
装完 app 进程会被杀 → **你的会话会断**，这是正常的，不是崩溃。

### 1.2 确认版本
```sh
# 用 root 通道看（自检）
dumpsys package me.rerere.rikkahub | grep -E "versionName|versionCode"
# 期望：versionName ≥ 2.6.0
```

### 1.3 打开工具 + 设默认环境 ⚠️ **最容易漏的一步**
人类操作：**助手设置 → 本地工具 → 打开「执行环境工具」**，然后填两个框：
- **默认环境**：填**你这台机器的容器名**（例如 `kali`）。默认值是 `arch`，你这里多半没有这个容器，**不填就全都打偏**
- **默认工作目录**：容器填 `/root`（`root` 环境默认 `/`）

填完你就不必每次写 `target` 了。

### 1.4 确认你手上真有这套工具
你的工具表里应该出现 7 个：`env_exec` / `env_read_file` / `env_write_file` / `env_edit_file` / `env_list` / `env_bg` / `env_log`。
**没有** → 见**附录 A**（走执行器 heredoc 兜底，或让人类确认开关是否打开）。

---

## 2. 主路径：`env_*` 工具

| 工具 | 参数 | 说明 |
|---|---|---|
| `env_exec` | `command`, `target?`, `cwd?`, `timeout_sec?` | 返回 `exit_code / stdout / stderr`；超时上限 600s，**到点 kill 进程** |
| `env_read_file` | `path`, `target?`, `offset?`, `limit?` | 逐行读；无 offset/limit 且 >2MB 会拒绝 |
| `env_write_file` | `path`, `content`, `target?` | 内容里的引号/换行/emoji **不用转义**；自动建父目录；上限 2MB |
| `env_edit_file` | `path`, `old_string`, `new_string`, `replace_all?`, `target?` | 精确替换；`old_string` 不存在直接报错；多处匹配要显式 `replace_all` |
| `env_list` | `target?` | 容器列表 + 各环境后台任务 + 容器内 `docker ps`（**想知道有哪些容器就调它**） |
| `env_bg` / `env_log` | `name`, `command`, `target?` / `name`, `lines?`, `target?` | 长任务：立即返回，之后取日志 |

### 2.1 `target` 取值

| 值 | 落到哪 |
|---|---|
| **省略** | 助手卡片里的「默认环境」（你已经在 §1.3 设好了） |
| `arch` | 默认容器 |
| `ct:<名字>` / 裸容器名 | 指定容器（例如 `kali`） |
| `root` | 真机全局 mount ns |
| `termux` | ZeroTermux |

### 2.2 路径按**宿主视角**写
`/data/local` `/data/user` `/data/app` `/data/media/0` `/data/adb` 会被自动翻译成容器里的 `/mnt/*`，结果里回显 `path_mapped`；反向也认（容器视角写，也翻译成宿主视角）。
对照表见 §5.3，**但要用 `env_exec target=<你的容器> command="ls /mnt"` 自检确认你这台实际 bind 了哪些**。

### 2.3 三条铁律
1. **长任务**（apt/pacman/docker pull/编译）**必须** `env_bg` + `env_log`，别用 `env_exec` 硬等
2. `env_bg` 起的任务**不继承**执行器注入的环境变量（如 `$GITHUB_TOKEN`）→ 脚本里自己 `. /etc/agent.env`
3. stdout/stderr 各截断 12 万字符；大文件用 `env_exec` + `head/tail/grep`

---

## 3. 兜底路径：执行器 heredoc（**自检、装执行器、`ws` 专属子命令**时用）

这条路和 §2 是**同一条底层链路**，只是入口更原始 —— 不依赖 app 里的工具开关，**任何时候都能用**：

```sh
su -c '/data/local/exec-tool.sh <mode> [容器名]' <<'CMD_EOF'
id; head -1 /etc/os-release; systemctl is-active docker
CMD_EOF
```

- `<mode>`：`arch`（进容器，缺省）/ `root`（真机全局）/ `termux`
- **容器名是第 2 个参数**：`su -c '/data/local/exec-tool.sh arch kali'`（换容器不用改脚本）
- 外层定界符**必须带单引号**；命令正文里**不要出现同名的定界符行**（heredoc 唯一的坑）
- 引号 `"` `'`、反引号、`$()`、内嵌 heredoc 一律原样安全（执行器把命令以**变量**送进容器 `sh -c`，不二次解析）
- 长任务：`su -c '/data/local/ws bg <名字>' <<'CMD_EOF' … CMD_EOF`，再 `su -c '/data/local/ws log <名字>'`

**什么时候用它而不是 §2**：① 装机后还没打开工具开关时 ② 要用 `ws` 的专属子命令（`dlog` 看容器运行时日志、`st`、`pkg`、`rmjob`）③ 排查「工具本身是不是好的」——两条路对比着跑，能立刻区分是工具的问题还是环境的问题。

### 3.1 设备上必须先有的文件

| 文件 | 作用 |
|---|---|
| `/data/local/exec-tool.sh` | 执行器本体（**必需**，§2 和 §3 都依赖它） |
| `/data/local/ws` | 容器文件/命令助手 + 后台任务（`bg`/`log`/`jobs`/`dlog`），**强烈建议** |

**没有就装**（本交接目录里带了这两个脚本，与我们的设备同源）：

```sh
su -c '/data/local/exec-tool.sh root' <<'CMD_EOF'
# 脚本先放到 /storage/emulated/0 下某处（例如 Download），再：
cp /storage/emulated/0/Download/exec-tool.sh /data/local/exec-tool.sh
cp /storage/emulated/0/Download/ws            /data/local/ws
chmod 755 /data/local/exec-tool.sh /data/local/ws
sed -i 's/^CONTAINER=.*/CONTAINER=kali/' /data/local/exec-tool.sh   # 改成你的容器名
CMD_EOF
```

> 冷启动顺序：先把脚本放上去（用 `su -c` + `cp` 这种不需要执行器的写法），再开始用执行器。

---

## 4. 自检清单（装完照着跑，逐条打勾，结果写进你的记忆）

| # | 检查 | 怎么跑 | 期望 |
|---|---|---|---|
| 1 | 版本对 | root 通道 `dumpsys package me.rerere.rikkahub \| grep versionName` | ≥ `2.6.0` |
| 2 | 工具有 | 看自己的工具表 | 7 个 `env_*` 都在 |
| 3 | 默认环境设对 | `env_list` | 能列出你的容器（`kali`），不是 `arch` |
| 4 | 执行器在 | `env_exec("id", target=…)` | `uid=0(root)`，`arch` 模式 context 形如 `u:r:ksu:s0` |
| 5 | 容器在 | `env_exec("hostname; head -1 /etc/os-release")` | 你的容器名 + 发行版（`Kali GNU/Linux`） |
| 6 | 包管理 | Kali：`apt-get -v \| head -1`；Arch：`pacman -V \| head -1` | 有输出 |
| 7 | 有没有 systemd | `env_exec("systemctl is-system-running")` | `running`/`degraded`；**报错=没有** → 后台走 §5.7 |
| 8 | 宿主映射 | `env_exec("ls /mnt")` | 看到 `hostlocal`/`media0`/`data` 等在不在 |
| 9 | 路径翻译 | `env_read_file("/data/local/exec-tool.sh", limit=3)` | 读到内容，且结果里有 `path_mapped` |
| 10 | 写→读回路 | `env_write_file("/root/.probe","ok")` → `env_read_file` | 逐字节一致 |
| 11 | 跨视角 | 容器写 `/root/.probe`，用 `target=root` 读 `/mnt/Droidspaces/<容器>/root/.probe` | 同一内容（**路径按你机器实际挂载点调整**） |
| 12 | 网络 | `env_exec("curl -sI https://api.github.com \| head -1")` | `HTTP/2 200`；不通看 §5.6 |
| 13 | 后台任务 | `env_bg("probe","sleep 5; echo ok")` → 稍后 `env_log("probe")` | 立即返回 + 日志里有 `ok` |
| 14 | 真机通道 | `env_exec("ls /data/user/0 \| head", target="root")` | 能看到**别的应用**目录（不是只有自己+GMS） |
| 15 | app 侧文件 | 文件工具列 `/storage/emulated/0/Documents/` | 能列（注意 §5.4 的属主坑） |
| 16 | 记忆/技能 | `use_skill` 读一个 SKILL.md | 能读到（§7） |

**任何一条不过，先别继续**：把「命令 + 原始 stdout/stderr」记下来，这就是你后面所有判断的基线。

---

## 5. 坑清单（我们踩过的，你直接抄答案）

### 5.1 绝对不要在 PID1 的挂载命名空间里 mount/umount
Android 的 `/` 与 `/data` 是 **shared**，在那里 mount 会把事件传播到全系统几百上千个 mount ns → 直接卡死黑屏、被迫重启。
要加挂载 → 改 **droidspaces 容器配置**的 `bind_mounts`，然后重启容器。

### 5.2 裸 `su` 有 mount ns 遮蔽
直接 `su -c 'ls /data/user/0'` 只能看到自己和 GMS，别的应用「看起来不存在」但实际在。
→ 要看全量就用 `target=root`（执行器内部走的是进 PID1 ns 的写法）。

### 5.3 宿主 ⇄ 容器路径对照（由 `bind_mounts` 决定，**用 `ls /mnt` 自检**）

| 宿主 | 容器内（典型） | 用途 |
|---|---|---|
| `/data/local` | `/mnt/hostlocal` | 执行器、ws、容器配置与镜像 |
| `/data/user` | `/mnt/data/user` | 全量应用私有数据（仅 root 可读） |
| `/data/app` | `/mnt/data/app` | 任意 APK |
| `/data/media/0` | `/mnt/media0` | raw 通道：批处理、改属主/SELinux 标签 |
| `/data/adb` | `/mnt/adb` | KSU 模块、ksud、LSPosed 日志 |
| `/storage/emulated/0` | 同名（容器自带 FUSE） | 交付、共享存储 |

### 5.4 root/容器写进 `/storage/emulated/0` 的文件，app 自己读不到
FUSE 按「创建者」判定：root 建的文件，app（非特权）读会 EACCES，`chmod 666` 无效。
**交付给人类**没问题（文件管理器能读）；**你自己还要读/改**的产物必须经 app 侧落盘：
① app 侧先建占位文件 → ② root/容器 `cat src > 目标` **原地覆盖**（绝不能 `mv`/`rename`，那会把创建者变回 root）。
跳板目录：`/storage/emulated/0/Android/data/<包名>/files/`（app 一定读得到，谁写的都行）。

### 5.5 容器 `/tmp` 是独立 tmpfs
宿主经 `/mnt/Droidspaces/<容器>/tmp` 写的东西容器**看不见**（反之亦然），重启即失。
要跨视角/跨重启的文件放 `/root`、`/opt` 或宿主挂载点。

### 5.6 网络
- `github.com:22` 常被 reset → 用 `ssh.github.com:443`（写进 `~/.ssh/config`）
- `raw.githubusercontent.com` 常超时 → 走 `api.github.com` 的 contents 接口（`Accept: application/vnd.github.raw`）
- 容器出网依赖 droidspaces 的 net 模式；NAT 模式下容器重启会自动重建，不需要手补 iptables
- **ksu 域 su 切到非 root uid 后 DNS 会失效** → 需要下载/装包就用 root 或容器

### 5.7 容器里没有 systemd 时怎么跑后台任务
`env_bg` / `ws bg` 依赖容器内 systemd。没有就退化成：
```sh
setsid sh -c '<你的脚本>' > /data/local/tmp/jobs/<名字>.log 2>&1 < /dev/null &
echo $! > /data/local/tmp/jobs/<名字>.pid
```
之后 `tail` 看日志、`kill -0 $(cat ….pid)` 判存活。

### 5.8 别用 `pkill -f`
会匹配到你自己。用精确锚定：`pgrep -f '^/system/bin/sh /path/xx.sh$'`。

### 5.9 heredoc 定界符
外层定界符必须带单引号，且命令正文里不能出现**同名**的定界符行 —— 否则外层会被静默截断（很难查）。

---

## 6. 个性化：想加功能 / 改行为，就自己重建 APK

这套是**可自建的分支**，不用等别人发版。闭环：

```
改代码 → 提交 → 推 GitHub → CI 构建 → 下载 APK → 覆盖安装（同签名，数据无损）
```

**需要的东西**：
1. 一个 fork（GitHub 账号）
2. 仓库里**自带 `app/app.key`**（`storePassword=keyPassword=android`）→ 构建出的 APK 与原包**同签名**，可直接覆盖安装
3. 打开 Actions：`PUT /repos/<你>/<仓库>/actions/permissions {"enabled":true,"allowed_actions":"all"}`
4. 推一个 commit 到默认分支即触发构建（产物 `app/build/outputs/apk/release/*arm64-v8a*.apk`）

**本地构建**（有 Android SDK + JDK 21）：
```bash
./gradlew assembleRelease -x :web:buildWebUi     # 跳过 web 前端（需 pnpm；test / lint 同理）
```

**改什么、在哪改**：

| 想改的东西 | 落点 |
|---|---|
| 系统提示词组装 | `data/ai/GenerationHandler.kt` + `data/ai/prompts/SystemPromptAssembler.kt` |
| 本地工具（AI 能调的能力） | `data/ai/tools/*.kt`；开关字段 `data/model/Assistant.kt`；注册 `service/ChatService.kt`；设置页 `ui/pages/assistant/detail/AssistantLocalToolPage.kt` |
| 工具的执行通道 | `data/ai/tools/EnvTools.kt`（`env_*` 的实现：`target` 解析、超时、读写上限都在这） |
| 版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName`（**每版 code +1**，否则覆盖安装会被拒） |

**版本号策略（照抄）**：每版 `versionCode` +1；`versionName` 语义化 —— 功能变更 `MINOR`+1、修 bug `PATCH`+1。

> ⚠️ 两个静态坑（我们真栽过，改完 `.kt` 先自查）：
> ① **Kotlin 原始字符串 `"""…"""` 里的 `$` 照样插值** → 要字面 `$` 必须写 `${'$'}`（`\$` 在原始串里无效）
> ② **Kotlin 块注释可以嵌套** → 注释里写 `/*`（比如 `/mnt/*`）会再开一层 → `Unclosed comment`，整个文件解析失败

---

## 7. 给你自己的基建：技能 + 长期记忆

**为什么**：会话上下文会被压缩/丢弃，「记住的事」不能只放在对话里。要持久化成**文件**，再按需读回来。

### 7.1 技能（skill）
rikkahub 的 skill = 一个目录 + `SKILL.md`：
```
skills/<技能名>/SKILL.md          ← frontmatter（name / description）+ 正文（步骤）
skills/<技能名>/REFERENCE.md      ← 大块事实、坑清单（正文里链接过来，按需读）
skills/<技能名>/<脚本/附属文件>
```
- **frontmatter 的 `description` 是常驻开销**：写「什么时候该用我」的触发器关键词，别写同义词凑数
- **正文只写「按顺序做什么」**，事实下沉到 REFERENCE —— 主文件越短，每次加载越便宜
- 每条步骤配**完成判据**（能一眼判断做完没有），否则容易半途收工
- 注意真实加载路径：多数实现读**外部** `files/skills/`，另有私有副本；改外部那份并同步私有

### 7.2 长期记忆
```
memories/index.md      ← 目录（一句话说清每份文件放什么）
memories/env.md        ← 环境事实：设备、通道、容器名、凭据在哪
memories/stack.md      ← 你正在维护的代码/工具链
memories/tasks.md      ← 只放**未结束**的任务（办完就删）
```
纪律：
- 每条事实写**日期**，过期就删，别堆沉积层
- 易变的值（token、路径、版本）**写指针不写快照**（「token 在 /etc/agent.env」而不是把 token 抄进来）
- 改旧行、别追加矛盾版本；写回后**读一遍确认**（写错路径是最常见的静默失败）

### 7.3 把这份文档变成你自己的技能
最省事：新建 `skills/device-exec/SKILL.md`，把 §2/§3/§5 压成「按顺序做什么 + 完成判据」，坑整段挪进 `REFERENCE.md`，再把**你这台机器的实测值**（容器名、发行版、`/mnt` 实际内容、包管理器、有没有 systemd、网络走哪条路）补进去。
**别人写的事实只有在你机器上自检过才算数** —— 包括本文档。

---

## 8. 交接清单

| 项 | 说明 |
|---|---|
| **最新 APK** | 覆盖安装即可（同签名，保数据）；装完按 §1.3 打开工具并填**默认环境 = 你的容器名** |
| `exec-tool.sh` | 设备侧执行器 → `/data/local/`，`chmod 755`，`CONTAINER=` 改成你的容器名 |
| `ws` | 容器助手（`bg`/`log`/`jobs`/`dlog`/`df`）→ `/data/local/` |
| 本文档 | 读一遍 + 按 §4 自检（16 条） |
| 一套记忆/技能 | 按 §7 建，把自检结果写进去 |

**最后一条建议**：先把 §4 自检跑完、把原始输出存进记忆，再动手改任何东西。你后面所有判断都建立在那份基线上。

---

## 附录 A：万一你手上是**旧包**（无 `env_*` 工具）

不影响干活，只是入口不同：
- 有 `container_exec` 等 `container_*` → 参数一样，但**没有 `target`**（环境在助手卡片里写死），也没有路径自动翻译
- 什么都没有 → 全部走 **§3 的执行器 heredoc**（万能，任何版本都能用）
- 想升级：让人类覆盖安装最新 APK，或按 §6 自己构建
