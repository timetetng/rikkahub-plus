# 设备执行环境 · Agent 使用文档（droidspaces 版）

> **这份文档是写给「跑在这台手机上的 AI agent」看的**，不是给人类看的用户手册。
> 拓扑：**KernelSU(-Next) + ZeroTermux + rikkahub + droidspaces 容器**。
>
> 文中的 `arch` 是**容器名占位符**——把你自己那台机器的容器名（例如 `kali`）替进去即可。
> 遇到「自检」标记的条目，装完环境后请实际跑一遍，不要假设。

---

## 0. 先搞清楚你在哪一层

你（agent）跑在 **rikkahub 这个 Android app 的进程里**（untrusted_app 域、无 root、被 SELinux 与 scoped storage 限制）。
所有「真正的」操作都要经过三层之一，别把 app 沙箱当成设备：

| 层 | 你能看到什么 | 怎么进 |
|---|---|---|
| **app 沙箱**（默认） | 只有自己的 `/data/data/<包名>/`、`/storage/emulated/0` 的部分目录 | 现成的文件/命令工具 |
| **容器**（droidspaces） | 一个完整的 Linux（systemd / docker / 包管理器）；宿主目录以 `/mnt/*` 出现在容器里 | `env_exec target=arch`（或执行器 heredoc） |
| **真机全局 ns** | `/data/user/0/*`（所有 app 私有数据）、`/data/adb`、`/system`、`pm`/`dumpsys` | `env_exec target=root` |
| **ZeroTermux** | `$PREFIX`、`pkg install` | `env_exec target=termux` |

**先记住这条路**：`app → su → 执行器 /data/local/exec-tool.sh → droidspaces → 容器 shell`。
执行器存在的意义是把「su 的 mount ns 遮蔽、droidspaces 的参数、引号转义」全封在它内部——**不要绕开它手写 `su`/`nsenter`/`mount`**。

---

## 1. 自检第一步：我手上是哪一代？（决定用哪条通道）

**通道 A（首选）**：工具表里出现 `env_exec` / `env_read_file` / `env_write_file` / `env_edit_file` / `env_list` / `env_bg` / `env_log`
→ 这是 **2.6.0+** 的通用版工具，一套工具用 `target` 切环境。见 §2。

**通道 A'（旧）**：工具表里出现 `container_exec` 等 `container_*`
→ **2.5.0** 的容器专用版；参数一样，但没有 `target`（环境固定在助手卡片里），也没有路径自动翻译。也可以直接用**通道 B**。

**通道 B（万能后备）**：什么都没有
→ 走**执行器 heredoc**（§3），只要设备上有 `/data/local/exec-tool.sh` 就能干活。

> 三条通道**底层同一条链路**，只是入口不同。通道 A 省 token、免转义、能自动翻译路径。

---

## 2. 通道 A：`env_*` 工具（推荐）

| 工具 | 参数 | 说明 |
|---|---|---|
| `env_exec` | `command`, `target?`, `cwd?`, `timeout_sec?` | 返回 `exit_code / stdout / stderr`；超时上限 600s，**到点 kill 进程** |
| `env_read_file` | `path`, `target?`, `offset?`, `limit?` | 逐行读；无 offset/limit 且 >2MB 会拒绝 |
| `env_write_file` | `path`, `content`, `target?` | 内容里的引号/换行/emoji **不用转义**；自动建父目录；上限 2MB |
| `env_edit_file` | `path`, `old_string`, `new_string`, `replace_all?`, `target?` | 精确替换；`old_string` 不存在直接报错；多处匹配要显式 `replace_all` |
| `env_list` | `target?` | 容器列表 + 各环境后台任务 + 容器内 `docker ps`（**想知道有哪些容器就调它**） |
| `env_bg` / `env_log` | `name`, `command`, `target?` / `name`, `lines?`, `target?` | 长任务：立即返回，之后取日志 |

**`target` 的取值**（省略 = 助手卡片里的「默认环境」）：

| 值 | 落到哪 |
|---|---|
| `arch` | 默认容器（**换成你自己的容器名**） |
| `ct:<名字>` | 指定容器；裸容器名也认 |
| `root` | 真机全局 mount ns |
| `termux` | ZeroTermux |

**路径按宿主视角写**：`/data/local` `/data/user` `/data/app` `/data/media/0` `/data/adb` 会被自动翻译成容器里的 `/mnt/*`，结果里回显 `path_mapped`；反向也认。
（对照表见 §5.3。你的容器 bind 了哪些宿主目录**要用 `env_exec target=arch` 跑 `ls /mnt` 自检确认**——不是每台机器都一样。）

**几条铁律**：
1. 长任务（apt/pacman/docker pull/编译）**必须** `env_bg` + `env_log`，别用 `env_exec` 硬等
2. `env_bg` 起的任务**不继承**执行器注入的环境变量 → 脚本里自己 `. /etc/agent.env`（如果你的机器有这文件）
3. 输出 stdout/stderr 各截断 12 万字符；大文件用 `env_exec` + `head/tail/grep`

---

## 3. 通道 B：执行器 heredoc（万能后备）

```sh
su -c '/data/local/exec-tool.sh <mode> [容器名]' <<'CMD_EOF'
id; head -1 /etc/os-release; systemctl is-active docker
CMD_EOF
```

- `<mode>`：`arch`（进容器，缺省）/ `root`（真机全局）/ `termux`（ZeroTermux）
- **容器名是第 2 个参数**：`su -c '/data/local/exec-tool.sh arch kali'`（不改脚本就能换容器）
- 外层定界符**必须带单引号**；命令正文里**不要出现同名的定界符行**（这是 heredoc 唯一的坑）
- 引号 `"` `'`、反引号、`$()`、内嵌 heredoc 一律原样安全（执行器把命令以**变量**形式送进容器 `sh -c`，不二次解析）
- 长任务：`su -c '/data/local/ws bg <名字>' <<'CMD_EOF' … CMD_EOF`，再 `su -c '/data/local/ws log <名字>'`
- **完成判据**：`id` 出现 `uid=0`；`arch` 模式下 context 形如 `u:r:ksu:s0`

### 3.1 设备上必须先有的文件

| 文件 | 作用 |
|---|---|
| `/data/local/exec-tool.sh` | 执行器本体（**必需**） |
| `/data/local/ws` | 容器文件/命令助手 + 后台任务（`bg`/`log`/`jobs`，**强烈建议**） |
| `/data/local/{zip,unzip,zipdir}` | 可选，打包用 |

**没有的话**：本目录里带了 `exec-tool.sh` 与 `ws` 两个脚本（和我们的设备同源），安装方式：

```sh
# 把脚本内容写到宿主机（在 app 沙箱里用文件工具写到 /sdcard 不行，用 root 写）
su -c '/data/local/exec-tool.sh root' <<'CMD_EOF'
# 假设你已经把脚本放在了 /storage/emulated/0/… 下的某处
cp /storage/emulated/0/Download/exec-tool.sh /data/local/exec-tool.sh
cp /storage/emulated/0/Download/ws            /data/local/ws
chmod 755 /data/local/exec-tool.sh /data/local/ws
# 把默认容器名改成你自己的
sed -i 's/^CONTAINER=.*/CONTAINER=kali/' /data/local/exec-tool.sh
CMD_EOF
```

> 注意：**写入顺序**——刚落地的 `exec-tool.sh` 才第一次可用；上面这条用的是「还没装好/已装好」都成立的形式（本质是 su + cp）。

---

## 4. 自检清单（装完照着跑一遍，逐条打勾）

| # | 检查 | 命令 | 期望 |
|---|---|---|---|
| 1 | root 通不通 | `id`（mode=root） | `uid=0(root)` + `context=u:r:ksu:s0` |
| 2 | 容器在不在 | `droidspaces show` | 列出你的容器名（自检） |
| 3 | 能进容器 | `uname -a; head -1 /etc/os-release` | 内核字符串 + 你的发行版（`kali`/`arch`…） |
| 4 | 容器内包管理 | Kali: `apt-get -v \| head -1`；Arch: `pacman -V \| head -1` | 有输出 |
| 5 | 有没有 systemd | `systemctl is-system-running` | `running`/`degraded`；**报错=没有** → 后台任务走 setsid（§5.7） |
| 6 | 宿主映射 | `ls /mnt` | 看到 `hostlocal`/`media0`/`data` 等在不在（自检） |
| 7 | 网络 | `curl -sI https://api.github.com \| head -1`（容器内） | `HTTP/2 200`；不通看 §5.6 |
| 8 | 写→读回路 | 写 `/root/.probe` 再读回 | 内容逐字节一致 |
| 9 | 跨视角 | 容器写 `/root/.probe`，root 模式读 `/mnt/Droidspaces/<容器>/root/.probe` | 同一内容 |
| 10 | 后台任务 | 起一个 `sleep 5; echo ok` 的 job，稍后取日志 | 立即返回 + 日志里有 `ok` |
| 11 | app 侧文件 | 用文件工具列 `/storage/emulated/0/Documents/` | 能列（注意 §5.4 的属主坑） |
| 12 | 记忆/技能 | 若 app 支持 skill：`use_skill` 读一个 SKILL.md | 能读到（§7） |

**任何一条不过，先别继续**——把「命令 + 原始 stdout/stderr」记下来，这就是你后面排查的基线。

---

## 5. 坑清单（我们踩过的，你直接抄答案）

### 5.1 绝对不要在 PID1 的挂载命名空间里 mount/umount
Android 的 `/` 与 `/data` 是 **shared**，在那里 mount 会把挂载事件传播到全系统几百上千个 mount ns，直接卡死黑屏、被迫重启。
要加挂载 → 改 **droidspaces 容器配置**的 `bind_mounts`，然后重启容器。

### 5.2 裸 `su` 有 mount ns 遮蔽
直接 `su -c 'ls /data/user/0'` 只能看到自己和 GMS，别的应用「看起来不存在」但实际在。
→ 要看全量就用 `root` 模式（执行器内部走的是进入 PID1 ns 的写法）。

### 5.3 宿主 ⇄ 容器路径对照（bind_mounts 决定，**用 `ls /mnt` 自检**）

| 宿主 | 容器内（典型） | 用途 |
|---|---|---|
| `/data/local` | `/mnt/hostlocal` | 执行器、ws、容器配置与镜像 |
| `/data/user` | `/mnt/data/user` | 全量应用私有数据（仅 root 可读） |
| `/data/app` | `/mnt/data/app` | 任意 APK |
| `/data/media/0` | `/mnt/media0` | raw 通道：批处理、改属主/SELinux 标签 |
| `/data/adb` | `/mnt/adb` | KSU 模块、ksud、LSPosed 日志 |
| `/storage/emulated/0` | 同名（容器自带 FUSE） | 交付、共享存储 |

### 5.4 root/容器写进 `/storage/emulated/0` 的文件，app 自己读不到
FUSE 按「创建者」判定：root 建的文件，app（unprivileged）读会 EACCES，`chmod 666` 无效。
**交付给用户**没问题（文件管理器能读）；**你自己还要读/改**的产物必须经 app 侧落盘：
① app 侧先建占位文件 → ② root/容器 `cat src > 目标` **原地覆盖**（绝不能 `mv`/`rename`，那会把 dentry 创建者变回 root）。
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
`ws bg` 依赖容器内 `systemd-run`。没有 systemd 就退化成：
```sh
setsid sh -c '<你的脚本>' > /data/local/tmp/jobs/<名字>.log 2>&1 < /dev/null &
echo $! > /data/local/tmp/jobs/<名字>.pid
```
之后用 `tail` 看日志、`kill -0 $(cat ….pid)` 判存活。

### 5.8 别用 `pkill -f`
会匹配到你自己。用精确锚定：`pgrep -f '^/system/bin/sh /path/xx.sh$'`。

### 5.9 heredoc 定界符
外层定界符必须带单引号，且命令正文里不能出现**同名**的定界符行——否则外层会被提前截断（静默半截命令，很坑）。

---

## 6. 个性化：想加功能 / 改行为，就自己重建 APK

rikkahub 这份是**可自建的分支**，不用等别人发版。闭环：

```
改代码 → 提交 → 推 GitHub → CI 构建 → 下载 APK → 覆盖安装（同签名，数据无损）
```

**你需要的东西**：
1. 一个 fork（GitHub 账号）
2. 仓库里**自带 `app/app.key`**（`storePassword=keyPassword=android`）→ 构建出的 APK 与原包**同签名**，可直接覆盖安装、不丢数据
3. 打开 Actions：`PUT /repos/<你>/<仓库>/actions/permissions {"enabled":true,"allowed_actions":"all"}`
4. 推一个 commit 到默认分支即触发构建（产物 `app/build/outputs/apk/release/*arm64-v8a*.apk`）

**本地构建**（有 Android SDK + JDK 21 时）：
```bash
./gradlew assembleRelease -x :web:buildWebUi     # 跳过 web 前端（需 pnpm）
```

**改什么、在哪改**（轻量索引，具体看你自己的仓库结构）：
| 想改的东西 | 落点 |
|---|---|
| 系统提示词组装 | `data/ai/GenerationHandler.kt` + `data/ai/prompts/SystemPromptAssembler.kt` |
| 本地工具（AI 能调的能力） | `data/ai/tools/*.kt`；开关字段 `data/model/Assistant.kt`；注册 `service/ChatService.kt`；设置页 `ui/pages/assistant/detail/AssistantLocalToolPage.kt` |
| 版本号 | `app/build.gradle.kts` 的 `versionCode` / `versionName`（**每版 code +1**，否则覆盖安装会被拒） |
| 更新检查 | 不想被提示就让它别请求，或用 DataStore 把检查停到很远的将来 |

**版本号策略（照抄即可）**：每版 `versionCode` +1；`versionName` 语义化——功能变更 `MINOR`+1、修 bug `PATCH`+1。

> ⚠️ 覆盖安装会杀掉 app 进程 → **你（agent）的会话会断**，这是正常的，不是崩溃。

---

## 7. 给你自己的基建：技能 + 长期记忆

**为什么**：会话上下文会被压缩/丢弃，「记住的事」不能只放在对话里。要持久化成**文件**，再在需要时按需读回来。

### 7.1 技能（skill）
rikkahub 的 skill = 一个目录 + `SKILL.md`：
```
skills/<技能名>/SKILL.md          ← frontmatter（name / description）+ 正文（步骤）
skills/<技能名>/REFERENCE.md      ← 大块事实、坑清单（正文里链接过来，按需读）
skills/<技能名>/<脚本/附属文件>
```
- **frontmatter 的 `description` 是常驻开销**：它是「什么时候该用我」的触发器，写关键词，别写同义词凑数
- **正文只写「按顺序做什么」**，事实下沉到 REFERENCE —— 主文件越短，每次加载越便宜
- 每条步骤配**完成判据**（能一眼判断做完没有），否则容易半途收工
- 改完注意「真实加载路径」：多数实现读的是**外部** `files/skills/`，另有私有副本，改外部那份并同步私有（否则两边不一致）

### 7.2 长期记忆
建议一个 `memories/` 目录 + 一个索引：
```
memories/index.md      ← 目录（一句话说清每份文件放什么）
memories/env.md        ← 设备/通道/凭据等环境事实
memories/stack.md      ← 你正在维护的代码/工具链
memories/tasks.md      ← 只放**未结束**的任务（办完就删）
```
纪律：
- 每条事实写**日期**，过期就删，别堆沉积层
- 易变的值（token、路径、版本）**写指针不写快照**（例如「token 在 /etc/agent.env」而不是把 token 抄进来）
- 改旧行、别追加新的矛盾版本
- 写回后**读一遍确认**（写错路径是最常见的静默失败）

### 7.3 把这份文档变成你自己的技能
最省事的做法：新建 `skills/device-exec/SKILL.md`，把 §2/§3/§5 压缩成「按顺序做什么」+ 完成判据，把 §5 的坑整段挪进 `REFERENCE.md`，然后把**你这台机器的实测值**（容器名、发行版、`/mnt` 实际内容、包管理器、有没有 systemd、网络走哪条路）补进去。
**别人写的事实只有在你机器上自检过才算数**——包括本文档。

---

## 8. 交接清单（从零复刻需要的东西）

| 项 | 说明 |
|---|---|
| `exec-tool.sh` | 设备侧执行器，放 `/data/local/`，`chmod 755`，改默认容器名 |
| `ws` | 容器助手（bg/log/jobs/dfile/zip…），放 `/data/local/` |
| 本文档 | 读一遍 + 按 §4 自检 |
| rikkahub APK | 旧版（只影响你走哪条通道）；想用 `env_*` 就自己按 §6 重建 |
| 一套记忆/技能 | 按 §7 建，做完把上面这些**自检结果**写进去 |

**最后一条建议**：先把 §4 自检清单跑完并记录原始输出，再动手改任何东西。你后面所有判断都建立在那份基线上。
