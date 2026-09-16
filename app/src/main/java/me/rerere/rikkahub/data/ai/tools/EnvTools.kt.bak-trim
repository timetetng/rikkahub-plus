package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.EnvJobWatcher

/**
 * 设备执行环境工具 —— **一套工具，多个环境**。
 *
 * 每个工具都可以用 `target` 指定这次落在哪儿（省略 = 用助手卡片里的默认值）：
 *   （省略）    助手配置的默认环境
 *   `arch`      默认的 droidspaces 容器
 *   `ct:<名字>`  指定的 droidspaces 容器
 *   `root`      真机全局 mount ns（能看全部 /data/user/0、/data/adb、/system）
 *   `termux`    ZeroTermux
 *
 * 路径**按宿主视角写就行**：容器把宿主目录 bind 到 /mnt 下，工具会自动翻译，并在结果里回显
 * `path_mapped`（例如 `/data/local/x → /mnt/hostlocal/x`），反过来也认。
 *
 * 命令一律经执行器 /data/local/exec-tool.sh（命令体走 stdin，零转义）；本文件不做任何 mount。
 */
private const val EXEC_TOOL = "/data/local/exec-tool.sh"
private const val WS_TOOL = "/data/local/ws"
private const val DROIDSPACES = "/data/local/Droidspaces/bin/droidspaces"
private const val HOST_BG_DIR = "/data/local/tmp/rh-bg"
private const val TERMUX_BG_DIR = "/data/user/0/com.termux/files/home/.rh-bg"
private const val SSH_BG_DIR = "\$HOME/.rh-bg"

/**
 * 远程 ssh 的固定选项：免密（BatchMode）+ 连接复用（ControlMaster）。
 * 冷连 1.7~2.5s，复用后 ~0.15s；ControlPath 建在容器 root 的 ~/.ssh/cm 下（跨调用持久）。
 */
private const val SSH_OPTS = "-T -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=30 " +
    "-o StrictHostKeyChecking=accept-new -o ControlMaster=auto -o ControlPath=/root/.ssh/cm/%C " +
    "-o ControlPersist=10m"

private const val MAX_READ_BYTES = 2L * 1024 * 1024
private const val MAX_WRITE_BYTES = 2 * 1024 * 1024
private const val MAX_TIMEOUT_SEC = 600L
private const val MAX_OUTPUT_CHARS = 120_000
private const val MAX_OUTPUT_LINES = 2000

/** 输出超限时完整落盘的地方（容器/root 侧都读得到；读回来用 env_read_file） */
private const val SPILL_DIR = "/data/user/0/me.rerere.rikkahub/cache/rh-exec"
private const val EXEC_POLL_MS = 200L
private const val MAX_FILE_READ = 512 * 1024

/**
 * 单条 exec 命令正文里 base64 载荷的上限。
 * 整条命令最终是当作 argv 交给 droidspaces 的，超过几 KB 就会被 daemon 拒（bad request），
 * 所以写大文件时要拆成多条短命令追加。必须是 4 的倍数（base64 按 4 字符对齐才能分块解码）。
 */
private const val B64_CHUNK_CHARS = 2048

/** env_exec 在前台最多等这么久，之后转后台任务。硬上限：任何参数都覆盖不了 */
private const val EXEC_FOREGROUND_DEFAULT_SEC = 30L
private const val META_PREFIX = "__meta__ "
private const val READ_META = META_PREFIX + "size="
private const val STATE_META = META_PREFIX + "state="

private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** 目标环境 */
private data class Target(val mode: String, val container: String) {
    val label: String get() = when (mode) {
        "arch" -> "arch:$container"
        "ssh" -> "ssh:$container"
        else -> mode
    }
    val isContainer: Boolean get() = mode == "arch"
    val isSsh: Boolean get() = mode == "ssh"
}

/** 宿主目录 ⇄ 容器的 bind 挂载视角（container.config 的 bind_mounts） */
private val PATH_MAP = listOf(
    "/data/local" to "/mnt/hostlocal",
    "/data/user" to "/mnt/data/user",
    "/data/app" to "/mnt/data/app",
    "/data/media/0" to "/mnt/media0",
    "/data/adb" to "/mnt/adb",
)

private fun sanitizeName(s: String): String =
    s.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

private fun defaultCwd(t: Target): String = when (t.mode) {
    "root" -> "/"
    "termux" -> "/data/user/0/com.termux/files/home"
    "ssh" -> ""                                  // 远程 shell 自带 $HOME，不 cd
    else -> "/root"
}

/** ssh 别名：允许中文等非 ASCII（Host 别名可以是「腾讯云」），剔除空白与 shell 元字符 */
private fun sanitizeSshAlias(raw: String): String =
    raw.trim().filter { !it.isWhitespace() && it !in "'\"`\\;\$&|()<>*?[]{}!#~" }

private fun parseTarget(raw: String): Target {
    val a = raw.trim()
    return when {
        a.isEmpty() || a == "arch" -> Target("arch", "arch")
        a == "root" -> Target("root", "")
        a == "termux" -> Target("termux", "")
        a.startsWith("ct:") -> Target("arch", sanitizeName(a.removePrefix("ct:")).ifBlank { "arch" })
        a.startsWith("ssh:") -> {
            val alias = sanitizeSshAlias(a.removePrefix("ssh:"))
            require(alias.isNotEmpty()) { "env: ssh target needs a Host alias, e.g. target=\"ssh:tzk123\"" }
            Target("ssh", alias)
        }
        else -> Target("arch", sanitizeName(a).ifBlank { "arch" })   // 裸容器名也认
    }
}

/** arg 为空 → 用助手配置的默认环境；否则用 arg */
private fun resolveTarget(arg: String?, cfgTarget: String): Target =
    parseTarget(arg?.trim().takeUnless { it.isNullOrEmpty() } ?: cfgTarget)

private fun argvFor(t: Target): Array<String> =
    if (t.isContainer) arrayOf("su", "-c", "$EXEC_TOOL arch ${t.container}")
    else arrayOf("su", "-c", "$EXEC_TOOL ${t.mode}")

/** 路径翻译：宿主视角 ⇄ 目标视角；返回 (最终路径, 说明) */
private fun mapPath(t: Target, path: String): Pair<String, String?> {
    if (t.mode == "termux" || t.mode == "ssh") return path to null   // 远程/termux 路径原样；只有容器需要 bind 视角翻译
    val pairs = if (t.isContainer) PATH_MAP else PATH_MAP.map { it.second to it.first }
    for ((from, to) in pairs) {
        if (path == from || path.startsWith("$from/")) {
            return path.replaceFirst(from, to) to "$from → $to"
        }
    }
    return path to null
}

/** POSIX 单引号安全包裹 */
private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * job 完成标记的路径对：(脚本视角, 宿主视角)。
 * 看门狗用宿主视角读；脚本在自己那边写 —— 容器经 bind 落到同一份文件。
 * ssh 目标不支持回传（远端写不进本机），返回空表示不登记。
 */
private fun markPaths(t: Target, name: String): Pair<String, String> = when {
    t.isContainer -> "${EnvJobWatcher.CONTAINER_HOST_TMP}/rh-bg/$name.rc" to "${EnvJobWatcher.HOST_BG_DIR}/$name.rc"
    t.mode == "termux" -> "${EnvJobWatcher.TERMUX_BG_DIR_HOST}/$name.rc" to "${EnvJobWatcher.TERMUX_BG_DIR_HOST}/$name.rc"
    t.mode == "ssh" -> "" to ""
    else -> "${EnvJobWatcher.HOST_BG_DIR}/$name.rc" to "${EnvJobWatcher.HOST_BG_DIR}/$name.rc"
}

/**
 * 同一目标 + 同一路径的「读 -> 改 -> 写」串行化。
 *
 * 模型会在一条消息里并发发出多个 env_* 调用：两个 env_edit_file 各自读到改动前的快照，
 * 后写的把先写的冲掉。用 Mutex 而不是 ReentrantLock —— 临界区里有 suspend 调用，
 * 拿阻塞锁会把调度线程一起钉死。思路同 pi-mono 的 withFileMutationQueue。
 * 只增不删：条目数 = 进程内碰过的路径数，可忽略。
 */
private val envPathLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

private suspend fun <T> withPathLock(t: Target, path: String, block: suspend () -> T): T =
    envPathLocks.computeIfAbsent("${t.label}:$path") { Mutex() }.withLock { block() }

/**
 * base64 载荷：**一行到底，不带换行**。
 *
 * 分块写回是按字符切的，而换行不参与 base64 的 4 字符对齐 —— 只要载荷里混进换行，
 * 切出来的块长度就不是 4 的倍数：GNU coreutils 的 `base64 -d` 直接 "invalid input"
 * 退出 1（文件被截断在第一个块边界），toybox 的 `base64 -d` 更阴 —— 静默丢掉余数、
 * 返回 0，于是工具报「写成功」、文件却从断点起全是乱码。
 */
private fun b64(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private fun b64decode(s: String): String =
    String(Base64.decode(s.replace(Regex("\\s"), ""), Base64.DEFAULT), Charsets.UTF_8)

/** 去掉 ANSI 转义（droidspaces 出错时会给 stderr 上色，白占 token） */
private fun stripAnsi(s: String): String =
    s.replace(Regex("\u001B\\[[0-9;]*[A-Za-z]"), "")

/**
 * 输出收尾：**留尾部** + 行数/字符双限。
 * 旧实现是 take(前 N 字符) —— 中文场景下正好把结尾的报错砍掉、留下满屏滚动日志，
 * 方向反了。抄 pi-mono bash 的 capture：「留尾 + 明说砍了多少」。
 */
private fun trimOutput(s: String): String {
    val t = stripAnsi(s)
    if (t.isEmpty()) return t
    val lines = t.split('\n')
    var start = lines.size
    var chars = 0
    while (start > 0 && lines.size - start < MAX_OUTPUT_LINES) {
        val next = chars + lines[start - 1].length + 1
        if (next > MAX_OUTPUT_CHARS) break
        chars = next
        start--
    }
    if (start == 0) return t
    val kept = lines.subList(start, lines.size).joinToString("\n")
    return "[... ${start} lines / ${t.length - chars} chars omitted — full output goes to the spill file when it is oversized ...]\n$kept"
}

/**
 * 输出太大时把完整内容从临时文件复制到 spill 目录 —— 进程一退，temp 文件就删了。
 * 抄 pi-mono bash 的 capture.spill：截断不能等于「信息消失」。
 */
private fun spillIfNeeded(f: File, tag: String): String? {
    if (!f.exists() || f.length() <= MAX_FILE_READ) return null
    return runCatching {
        val dir = File(SPILL_DIR).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
        dir.listFiles()?.forEach { old -> if (old.lastModified() < cutoff) old.delete() }
        val dest = File(dir, "$tag-${System.currentTimeMillis()}.log")
        f.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()
}

/** 前台执行的结果：要么跑完，要么超时（超时不一定要杀 —— exec 会把它转成后台任务接着跑）。 */
private sealed interface ExecOutcome {
    data class Done(val result: ExecResult) : ExecOutcome
    data class Timeout(
        val process: Process,
        val outFile: File,
        val errFile: File,
        val inFile: File?,
        val startedAt: Long,
        val timeoutSec: Long,
    ) : ExecOutcome
}

private fun cleanupFiles(vararg files: File?) {
    files.forEach { f -> runCatching { f?.delete() } }
}

/** 超时后放弃：杀进程 + 清临时文件 */
private fun ExecOutcome.Timeout.kill() {
    runCatching { process.destroyForcibly() }
    cleanupFiles(outFile, errFile, inFile)
}

/** 前台上读完就走的调用点：超时即杀即报错 */
private fun ExecOutcome.expectDone(): ExecResult = when (this) {
    is ExecOutcome.Done -> result
    is ExecOutcome.Timeout -> {
        kill()
        error(
            "env bridge: command timed out after ${timeoutSec}s and was killed. " +
                "For long tasks (pacman/docker pull/build) use env_bg (background job + env_log)."
        )
    }
}

/** 读文件尾部（大输出只留尾巴，别把内存吃满） */
private fun readFileCapped(f: File, maxBytes: Int = MAX_FILE_READ): String {
    if (!f.exists() || !f.isFile) return ""
    return runCatching {
        if (f.length() <= maxBytes) f.readText()
        else RandomAccessFile(f, "r").use { raf ->
            raf.seek(f.length() - maxBytes)
            val buf = ByteArray(maxBytes)
            raf.readFully(buf)
            String(buf, Charsets.UTF_8)
        }
    }.getOrDefault("")
}

/**
 * 前台跑一个命令。
 *
 * stdin / stdout / stderr 全部走临时文件，不再用协程读流 —— 只为了两件事：
 *   1. **取消要立刻生效**：主循环挂在 delay 上，用户一点停止就 destroyForcibly。
 *      旧实现挂在 readText() 上等 EOF，取消信号到不了，job 一直卡在 cancelling
 *      （stopGeneration 里的 job.join() 因此永远等不到）—— 这就是「点 X 没反应」的根因。
 *   2. **超时不必杀**：把还在跑的进程句柄交回去，可以降级成后台任务继续跑。
 */
private suspend fun runProcess(argv: Array<String>, stdin: String?, timeoutSec: Long): ExecOutcome {
    val outFile = File.createTempFile("rh-exec-", ".out")
    val errFile = File.createTempFile("rh-exec-", ".err")
    val inFile = if (stdin != null) {
        File.createTempFile("rh-exec-", ".in").apply { writeText(stdin) }
    } else null

    val process = try {
        ProcessBuilder(*argv)
            .redirectOutput(outFile)
            .redirectError(errFile)
            .apply {
                if (inFile != null) redirectInput(inFile)
                else redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            }
            .start()
    } catch (e: Exception) {
        cleanupFiles(outFile, errFile, inFile)
        error("env bridge: failed to start ${argv.firstOrNull()}: ${e.message}")
    }

    val startedAt = System.currentTimeMillis()
    try {
        while (true) {
            if (!process.isAlive) break
            if ((System.currentTimeMillis() - startedAt) / 1000 >= timeoutSec) {
                return ExecOutcome.Timeout(process, outFile, errFile, inFile, startedAt, timeoutSec)
            }
            delay(EXEC_POLL_MS)
        }
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        val outSpill = spillIfNeeded(outFile, "stdout")
        val errSpill = spillIfNeeded(errFile, "stderr")
        val out = readFileCapped(outFile) + (outSpill?.let { "\n[output truncated; full log: $it]" } ?: "")
        val err = readFileCapped(errFile) + (errSpill?.let { "\n[output truncated; full log: $it]" } ?: "")
        cleanupFiles(outFile, errFile, inFile)
        return ExecOutcome.Done(ExecResult(code, out, err))
    } catch (e: CancellationException) {
        // 用户点了停止：立刻杀进程，别留着它继续吃资源
        runCatching { process.destroyForcibly() }
        cleanupFiles(outFile, errFile, inFile)
        throw e
    }
}

/**
 * 在远程主机（ssh:<alias>）执行脚本：走容器 arch 里的 ssh 客户端（config 与密钥都在容器 ~/.ssh），
 * 脚本经 heredoc 喂给远程 `bash -s`，零转义；别名只写 Host 名，不需要 user@host -p port。
 */
private suspend fun runInSsh(t: Target, script: String, timeoutSec: Long): ExecResult {
    val alias = t.container
    require(!alias.startsWith("-")) { "env: ssh alias must not start with '-'" }
    var delim = "__ENV_SSH_EOF__"
    while (script.contains(delim)) delim += "_X"
    val body = if (script.endsWith("\n")) script else script + "\n"
    val wrapped = buildString {
        append("mkdir -p /root/.ssh/cm && chmod 700 /root/.ssh/cm\n")
        append("ssh ").append(SSH_OPTS).append(" ").append(shq(alias))
        append(" -- bash -s <<'").append(delim).append("'\n")
        append(body)
        append(delim).append("\n")
    }
    return runProcess(arrayOf("su", "-c", "$EXEC_TOOL arch"), wrapped, timeoutSec).expectDone()
}

/** 在目标环境里执行脚本 */
private suspend fun runInTarget(t: Target, script: String, timeoutSec: Long): ExecResult =
    if (t.isSsh) runInSsh(t, script, timeoutSec) else runProcess(argvFor(t), script, timeoutSec).expectDone()

/** 在宿主侧直接跑（ws 等辅助脚本） */
private suspend fun runHostScript(shellCommand: String, stdin: String?, timeoutSec: Long): ExecResult =
    runProcess(arrayOf("su", "-c", shellCommand), stdin, timeoutSec).expectDone()

private fun metaLine(result: ExecResult): Pair<Long, String> {
    val text = result.stdout
    val nl = text.indexOf('\n')
    if (nl >= 0 && text.startsWith(READ_META)) {
        val size = text.substring(READ_META.length, nl).trim().toLongOrNull() ?: -1L
        return size to text.substring(nl + 1)
    }
    return -1L to text
}

private fun failWith(stderr: String, exitCode: Int, fallback: String): Nothing =
    error(stderr.trim().ifBlank { "$fallback (exit $exitCode)" })

private const val TARGET_DESC =
    "Which environment this call runs in: omit = assistant default; arch (default container) | ct:<name> (another " +
        "droidspaces container) | root (real device, global mount ns) | termux (ZeroTermux) | ssh:<alias> (a remote " +
        "host, taken from the arch container's ~/.ssh/config — write the Host alias only, no user@host/-p/keys)."

fun createEnvTools(
    context: Context,
    target: String,
    cwd: String,
    defaultTimeout: Int,
    conversationId: Uuid? = null,
    jobWatcher: EnvJobWatcher? = null,
): List<Tool> {
    val cfgTarget = target.ifBlank { "arch" }
    val cfgCwd = cwd.ifBlank { "/root" }
    val defTimeout = if (defaultTimeout in 5..MAX_TIMEOUT_SEC.toInt()) defaultTimeout.toLong() else 60L

    /** 读目标里的文件（base64，逐字节精确） */
    suspend fun readRaw(t: Target, pathArg: String, timeoutSec: Long): Pair<Long, String> {
        val (path, _) = mapPath(t, pathArg)
        val script = buildString {
            append("p=").append(shq(path)).append("\n")
            append("[ -e \"\$p\" ] || { echo \"env_read_file: no such file or directory\" >&2; exit 3; }\n")
            append("[ -f \"\$p\" ] || { echo \"env_read_file: not a regular file\" >&2; exit 4; }\n")
            append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
            append("if [ \"\$sz\" -gt $MAX_READ_BYTES ]; then echo \"env_read_file: file is \$sz bytes (> 2MB)\" >&2; exit 7; fi\n")
            append("echo \"${META_PREFIX}size=\$sz\"\n")
            append("base64 < \"\$p\"\n")
        }
        val res = runInTarget(t, script, timeoutSec)
        if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "env_read_file failed")
        val (size, b64text) = metaLine(res)
        val text = try {
            b64decode(b64text)
        } catch (e: Exception) {
            error("env_read_file: decode failed (${e.message})")
        }
        return size to text
    }

    /** 小文件：一条命令写完 */
    suspend fun writeRawSingle(t: Target, path: String, payload: String, timeoutSec: Long): Pair<Long, String> {
        val script = buildString {
            append("p=").append(shq(path)).append("\n")
            append("[ -d \"\$p\" ] && { echo 'env_write_file: path is a directory' >&2; exit 4; }\n")
            append("mkdir -p \"\$(dirname \"\$p\")\" || { echo 'env_write_file: cannot create parent directory' >&2; exit 5; }\n")
            append("base64 -d > \"\$p\" <<'__CB64__'\n")
            append(payload).append("\n")
            append("__CB64__\n")
            append("rc=\$?\n")
            append("[ \"\$rc\" -eq 0 ] || { echo 'env_write_file: base64 decode failed' >&2; exit 6; }\n")
            append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
            append("echo \"${META_PREFIX}size=\$sz\"\n")
        }
        val res = runInTarget(t, script, timeoutSec)
        if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "env_write_file failed")
        val (written, _) = metaLine(res)
        return written to path
    }

    /** 大文件：截断 + 逐块追加，每条命令都短到能过 droidspaces 的 argv 限制 */
    suspend fun writeRawChunked(t: Target, path: String, payload: String, timeoutSec: Long): Pair<Long, String> {
        val head = buildString {
            append("p=").append(shq(path)).append("\n")
            append("[ -d \"\$p\" ] && { echo 'env_write_file: path is a directory' >&2; exit 4; }\n")
            append("mkdir -p \"\$(dirname \"\$p\")\" || { echo 'env_write_file: cannot create parent directory' >&2; exit 5; }\n")
            append(": > \"\$p\" || exit 6\n")
        }
        runInTarget(t, head, timeoutSec).also {
            if (it.exitCode != 0) failWith(it.stderr, it.exitCode, "env_write_file failed")
        }
        var offset = 0
        while (offset < payload.length) {
            val chunk = payload.substring(offset, minOf(offset + B64_CHUNK_CHARS, payload.length))
            val script = buildString {
                append("p=").append(shq(path)).append("\n")
                append("printf '%s' ").append(shq(chunk)).append(" | base64 -d >> \"\$p\" || exit 6\n")
            }
            runInTarget(t, script, timeoutSec).also {
                if (it.exitCode != 0) failWith(it.stderr, it.exitCode, "env_write_file failed (chunk at $offset)")
            }
            offset += chunk.length
        }
        val tail = buildString {
            append("p=").append(shq(path)).append("\n")
            append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
            append("echo \"${META_PREFIX}size=\$sz\"\n")
        }
        val res = runInTarget(t, tail, timeoutSec)
        if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "env_write_file failed")
        val (written, _) = metaLine(res)
        return written to path
    }

    /**
     * 写文件（base64 管道，内容不经过 shell 解析）。
     * 小内容一条命令搞定；大内容拆成「建目录 + 多块追加 + 收尾」，
     * 否则整条命令会撑爆 droidspaces 的 argv 限制（daemon: bad request）。
     */
    suspend fun writeRaw(t: Target, pathArg: String, content: String, timeoutSec: Long): Pair<Long, String> {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
            error("env_write_file: content too large (> 2MB); use env_exec with a heredoc for bigger files")
        }
        val (path, _) = mapPath(t, pathArg)
        val payload = b64(content)
        check(payload.length % 4 == 0) { "env_write_file: bad base64 payload length ${payload.length}" }
        val expected = content.toByteArray(Charsets.UTF_8).size.toLong()
        val (written, writtenPath) = if (payload.length <= B64_CHUNK_CHARS) {
            writeRawSingle(t, path, payload, timeoutSec)
        } else {
            writeRawChunked(t, path, payload, timeoutSec)
        }
        // 兜底：toybox 的 base64 -d 对残缺输入静默成功，只能自己拿字节数拦
        if (written != expected) {
            error("env_write_file: wrote $written bytes to $writtenPath but expected $expected " +
                "- content truncated/corrupted")
        }
        return written to writtenPath
    }

    val execTool = Tool(
        name = "env_exec",
        description = """
            Run a shell command in a device environment (NOT the app sandbox). Multi-line scripts are fine; quotes are
            passed verbatim. Long tasks (pacman/docker/builds) must use env_bg + env_log. Returns exit_code,
            stdout, stderr.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run. Multi-line scripts are fine.")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Working directory inside that environment (absolute). Default: $cfgCwd for the container.")
                    })
                    put("timeout_sec", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Hard timeout in seconds. Only honoured for ssh targets (a remote process cannot be " +
                                "adopted, so it gets killed). Local targets always wait at most " +
                                "${EXEC_FOREGROUND_DEFAULT_SEC}s in the foreground and are then handed over to a " +
                                "background job that reports back when it exits — put genuinely long work in env_bg."
                        )
                    })
                },
                required = listOf("command"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_exec: 'command' is required and must not be empty")
            val cwdArg = args.jsonObject["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val (workdir, mappedNote) = if (cwdArg == null) {
                if (t.isContainer) cfgCwd to null else defaultCwd(t) to null
            } else mapPath(t, cwdArg)
            // 本地目标的前台等待上限是硬的，参数覆盖不了 —— 否则模型顺手传个大 timeout 又能把界面卡住。
            // timeout_sec 只对 ssh 有意义（远端进程收养不了，只能硬杀）。
            val requested = (args.jsonObject["timeout_sec"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: defTimeout).coerceIn(5L, MAX_TIMEOUT_SEC)
            val timeout = if (t.isSsh) requested else EXEC_FOREGROUND_DEFAULT_SEC

            val script = buildString {
                if (workdir.isNotEmpty()) {
                    append("cd ").append(shq(workdir))
                    append(" || { echo 'env_exec: no such working directory' >&2; exit 4; }\n")
                }
                append(command)
            }
            val outcome = if (t.isSsh) {
                ExecOutcome.Done(runInSsh(t, script, timeout))
            } else {
                runProcess(argvFor(t), script, timeout)
            }
            when (outcome) {
                is ExecOutcome.Done -> {
                    val res = outcome.result
                    if (res.exitCode == 4 && res.stderr.contains("no such working directory")) {
                        error("env_exec: cwd not found in ${t.label}: $workdir")
                    }
                    val payload = buildJsonObject {
                        put("target", t.label)
                        put("cwd", workdir)
                        if (mappedNote != null) put("path_mapped", mappedNote)
                        put("exit_code", JsonPrimitive(res.exitCode))
                        put("stdout", JsonPrimitive(trimOutput(res.stdout)))
                        put("stderr", JsonPrimitive(trimOutput(res.stderr)))
                    }
                    listOf(UIMessagePart.Text(payload.toString()))
                }

                is ExecOutcome.Timeout -> {
                    // 还没跑完 —— 不杀它。交给看门狗收养成一个后台任务：进程继续跑、输出继续写，
                    // 退出后自动把结果注入本会话。ssh 不行（远端进程本地收养不了）。
                    val w = jobWatcher
                    val cid = conversationId
                    if (w != null && cid != null) {
                        val label = "exec-" + (outcome.startedAt / 1000)
                        w.adoptProcess(
                            process = outcome.process,
                            name = label,
                            target = t.mode,
                            container = if (t.isContainer) t.container else "",
                            conversationId = cid,
                            logPathHost = outcome.outFile.absolutePath,
                            startedAt = outcome.startedAt,
                            notifyMode = "reply",
                        )
                        val payload = buildJsonObject {
                            put("target", t.label)
                            put("cwd", workdir)
                            put("status", "detached")
                            put("job", label)
                            put("note", "still running after ${timeout}s — handed over to background job '$label'. " +
                                "It keeps running on its own; the result is injected into this conversation when " +
                                "it exits. Do NOT poll it, just carry on with the user.")
                            put("stdout_so_far", JsonPrimitive(trimOutput(readFileCapped(outcome.outFile))))
                            put("stderr_so_far", JsonPrimitive(trimOutput(readFileCapped(outcome.errFile))))
                        }
                        listOf(UIMessagePart.Text(payload.toString()))
                    } else {
                        outcome.kill()
                        error(
                            "env bridge: command timed out after ${timeout}s and was killed. " +
                                "For long tasks (pacman/docker pull/build) use env_bg (background job + env_log)."
                        )
                    }
                }
            }
        },
    )

    val readTool = Tool(
        name = "env_read_file",
        description = """
            Read a UTF-8 text file in a device environment. `path` is written in HOST view and auto-mapped for containers.
            offset/limit = line range (default 1 / 200). Files > 2MB need a slice. Returns size + text.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path of the file (host view; auto-translated per target)")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based first line to read (default 1)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Number of lines to read (default 200)")
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val pathArg = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_read_file: 'path' is required")
            val offset = args.jsonObject["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val limit = args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val start = offset ?: 1
            if (start < 1) error("env_read_file: offset must be >= 1")
            val count = limit ?: 200
            if (count !in 1..20000) error("env_read_file: limit must be between 1 and 20000")
            val end = start + count - 1
            val sliced = offset != null || limit != null
            val (path, mappedNote) = mapPath(t, pathArg)

            val script = buildString {
                append("p=").append(shq(path)).append("\n")
                append("[ -e \"\$p\" ] || { echo \"env_read_file: no such file or directory\" >&2; exit 3; }\n")
                append("[ -f \"\$p\" ] || { echo \"env_read_file: not a regular file\" >&2; exit 4; }\n")
                append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
                if (!sliced) {
                    append("if [ \"\$sz\" -gt $MAX_READ_BYTES ]; then echo \"env_read_file: file is \$sz bytes (> 2MB); pass offset/limit to read a slice\" >&2; exit 7; fi\n")
                }
                append("echo \"${META_PREFIX}size=\$sz\"\n")
                append("sed -n '").append(start).append(",").append(end).append("p' \"\$p\"\n")
            }
            val res = runInTarget(t, script, defTimeout)
            if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "env_read_file failed")
            val (size, text) = metaLine(res)
            val payload = buildJsonObject {
                put("target", t.label)
                put("path", path)
                if (mappedNote != null) put("path_mapped", mappedNote)
                put("size", JsonPrimitive(size))
                put("offset", JsonPrimitive(start))
                put("limit", JsonPrimitive(count))
                put("text", JsonPrimitive(text))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val writeTool = Tool(
        name = "env_write_file",
        description = """
            Write (create or overwrite) a UTF-8 text file in a device environment. Content needs no escaping (base64
            pipe), parent dirs are created. `path` is HOST view, auto-mapped. Max 2MB per call.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Destination path (host view; auto-translated per target)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "UTF-8 text content to write (overwrites the file)")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                },
                required = listOf("path", "content"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val pathArg = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_write_file: 'path' is required")
            val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
                ?: error("env_write_file: 'content' is required")
            val lockPath = mapPath(t, pathArg).first
            val (written, path) = withPathLock(t, lockPath) { writeRaw(t, pathArg, content, defTimeout) }
            val payload = buildJsonObject {
                put("target", t.label)
                put("path", path)
                put("bytes", JsonPrimitive(written))
                put("preview", JsonPrimitive(content.take(200)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val editTool = Tool(
        name = "env_edit_file",
        description = """
            Exact string replacement in a text file in a device environment (read-modify-write, no python3 needed).
            Nothing is written unless old_string is found uniquely (or replace_all=true). `path` is HOST view,
            auto-mapped. Large files are written back in chunks, so file size is not limited by the exec argv limit.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Path of the file (host view; auto-translated per target)")
                    })
                    put("old_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact text to find (must exist; add context to make it unique)")
                    })
                    put("new_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Replacement text")
                    })
                    put("replace_all", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Replace every occurrence (default false = single, errors out if not unique)")
                    })

                    put("edits", buildJsonObject {
                        put("type", "array")
                        put("description", "Several non-overlapping replacements applied to the ORIGINAL file in ONE call — prefer this over firing two env_edit_file calls in parallel (both read the same snapshot and one clobbers the other). Each item: {old_string, new_string}. Each old_string must match uniquely and must not overlap the others.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("old_string", buildJsonObject { put("type", "string") })
                                put("new_string", buildJsonObject { put("type", "string") })
                            })
                        })
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val pathArg = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_edit_file: 'path' is required")
            val edits = parseTextEdits(args.jsonObject)
            val replaceAll = args.jsonObject["replace_all"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (replaceAll && edits.size > 1) {
                error("env_edit_file: replace_all applies only to the single old_string/new_string form; use edits[] for several changes")
            }
            val (path, mappedNote) = mapPath(t, pathArg)

            var bytesIn = 0
            var written = 0L
            val result = withPathLock(t, path) {
                val (_, text) = readRaw(t, path, defTimeout)   // path 已翻译过，mapPath 不会再动它
                bytesIn = text.toByteArray(Charsets.UTF_8).size
                val r = applyTextEdits(text, edits, replaceAll)
                written = writeRaw(t, path, r.text, defTimeout).first
                r
            }
            val payload = buildJsonObject {
                put("target", t.label)
                put("path", path)
                if (mappedNote != null) put("path_mapped", mappedNote)
                put("replacements", JsonPrimitive(result.count))
                put("lines", kotlinx.serialization.json.JsonArray(result.lines.map { JsonPrimitive(it) }))
                put("bytes_before", JsonPrimitive(bytesIn))
                put("bytes_after", JsonPrimitive(written))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val listTool = Tool(
        name = "env_list",
        description = """
            List device environments: running droidspaces containers, background jobs (container systemd + host rh-bg),
            and docker ps inside the target container.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                },
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val script = buildString {
                append("DS=").append(DROIDSPACES).append("; WS=").append(WS_TOOL).append("; ")
                append("echo '=== droidspaces containers ==='; \$DS show 2>&1; ")
                append("echo; echo '=== container bg jobs (ws) ==='; WS_NAME=").append(t.container).append(" \$WS jobs 2>&1; ")
                append("echo; echo '=== host bg jobs (rh-bg) ==='; ls -1 ").append(HOST_BG_DIR).append(" 2>/dev/null || echo '(none)'; ")
                append("echo; echo '=== termux bg jobs ==='; ls -1 ").append(TERMUX_BG_DIR).append(" 2>/dev/null || echo '(none)'; ")
                append("echo; echo '=== docker ps (in ").append(t.label).append(") ==='; ")
                append("timeout 25 \$DS --name=").append(t.container)
                append(" run sh -c 'docker ps --format \"{{.Names}}  {{.Image}}  {{.Status}}\" 2>&1 || true' 2>&1")
            }
            val res = if (t.isSsh) {
                val remote = buildString {
                    append("echo '=== ssh ").append(t.container).append(" ==='\n")
                    append("hostname; uname -sr; (uptime -p 2>/dev/null || uptime)\n")
                    append("echo; echo '=== remote bg jobs (~/.rh-bg) ==='\n")
                    append("ls -1 \"\$HOME/.rh-bg\"/*.log 2>/dev/null | sed 's#.*/##' || echo '(none)'\n")
                }
                runInSsh(t, remote, defTimeout.coerceAtLeast(30L))
            } else {
                runHostScript(script, null, defTimeout.coerceAtLeast(30L))
            }
            val payload = buildJsonObject {
                put("target", t.label)
                put("exit_code", JsonPrimitive(res.exitCode))
                put("output", JsonPrimitive(trimOutput(res.stdout)))
                if (res.stderr.isNotBlank()) put("stderr", JsonPrimitive(trimOutput(res.stderr)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val bgTool = Tool(
        name = "env_bg",
        description = """
            Start a LONG-RUNNING background job in a device environment. Containers: systemd unit /root/.wsjobs/<name>.sh.
            root/termux: setsid + log file. `name` = A-Za-z0-9_.- . Jobs do NOT inherit the executor env (${'$'}GITHUB_TOKEN
            etc.). Read later with env_log.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Job name (A-Za-z0-9_.- only), used later by env_log")
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell script to run in the background")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                    put("notify", buildJsonObject {
                        put("type", "string")
                        put("description", "reply (default): when the job finishes, its result is injected into the " +
                            "conversation it was started from and a follow-up reply is generated. " +
                            "quiet: inject the result only, no reply. none: do not report back. " +
                            "Not supported for ssh targets.")
                    })
                    put("notify_after", buildJsonObject {
                        put("type", "integer")
                        put("description", "Seconds. If it is still running after this long, report an interim status " +
                            "once. 0 (default) = only report on completion.")
                    })
                },
                required = listOf("name", "command"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it in "_.-" }) {
                error("env_bg: 'name' must be non-empty and contain only A-Za-z0-9_.-")
            }
            val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_bg: 'command' is required")
            val cwdForJob = if (t.isContainer) cfgCwd else defaultCwd(t)
            val (rcScript, rcHost) = markPaths(t, name)
            // 收尾标记：job 结束时把 exit code 原子写到 <name>.rc，看门狗靠它判完成。
            // 用户命令包在子 shell 里，脚本里的 exit 不会吃掉收尾动作。
            val script = buildString {
                if (cwdForJob.isNotEmpty()) append("cd ").append(shq(cwdForJob)).append(" || true\n")
                append("(\n").append(command).append("\n)\n")
                append("__rh_rc=\$?\n")
                if (rcScript.isNotEmpty()) {
                    append("mkdir -p \"\$(dirname ").append(shq(rcScript)).append(")\" 2>/dev/null\n")
                    append("printf '%s' \"\$__rh_rc\" > ").append(shq(rcScript)).append(".tmp 2>/dev/null")
                    append(" && mv -f ").append(shq(rcScript)).append(".tmp ").append(shq(rcScript))
                    append(" 2>/dev/null\n")
                }
                append("exit \$__rh_rc\n")
            }
            val res = if (t.isContainer) {
                runHostScript("WS_NAME=${t.container} $WS_TOOL bg $name", script, 120L)
            } else {
                val dir = when (t.mode) {
                    "termux" -> TERMUX_BG_DIR
                    "ssh" -> SSH_BG_DIR
                    else -> HOST_BG_DIR
                }
                val job = buildString {
                    append("d=").append(dir).append("\n")
                    append("mkdir -p \"\$d\"\n")
                    append("cat > \"\$d/").append(name).append(".sh\" <<'__RHBG__'\n")
                    append(script)
                    append("__RHBG__\n")
                    append("setsid sh \"\$d/").append(name).append(".sh\" > \"\$d/").append(name).append(".log\" 2>&1 < /dev/null &\n")
                    append("pid=\$!\n")
                    append("echo \$pid > \"\$d/").append(name).append(".pid\"\n")
                    append("echo \"${META_PREFIX}pid=\$pid log=\$d/").append(name).append(".log\"\n")
                }
                runInTarget(t, job, 60L)
            }
            val stderr = res.stderr.trim()
            if (res.exitCode != 0 || stderr.contains("ws: 启动失败") || stderr.contains("ws: 启动超时")) {
                failWith(res.stderr, res.exitCode, "env_bg failed to start job '$name' in ${t.label}")
            }
            // 登记看门狗：跑完（或超过 notify_after）就把结果送回发起它的那个会话
            val notifyMode = args.jsonObject["notify"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "reply"
            val notifyAfter = args.jsonObject["notify_after"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val w = jobWatcher
            val cid = conversationId
            if (w != null && cid != null && rcHost.isNotEmpty() && notifyMode != "none") {
                runCatching { runHostScript("rm -f " + shq(rcHost), null, 10L) }
                w.register(
                    EnvJobWatcher.WatchedJob(
                        name = name,
                        target = t.mode,
                        container = if (t.isContainer) t.container else "",
                        rcPathHost = rcHost,
                        conversationId = cid.toString(),
                        startedAt = System.currentTimeMillis(),
                        deadlineSec = notifyAfter.coerceAtLeast(0L),
                        notifyMode = if (notifyMode == "quiet") "quiet" else "reply",
                    )
                )
            }
            val payload = buildJsonObject {
                put("target", t.label)
                put("name", name)
                put("status", "started")
                put("note", "background job '$name' started in ${t.label}; read it with env_log")
                if (notifyMode == "none" || cid == null || w == null) {
                    put("notify", "off")
                } else {
                    put(
                        "notify",
                        (if (notifyMode == "quiet") "on (quiet)" else "on (reply)") +
                            if (notifyAfter > 0) "; interim report after ${notifyAfter}s" else ""
                    )
                }
                put("output", JsonPrimitive((res.stdout + stderr).trim()))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val logTool = Tool(
        name = "env_log",
        description = """
            Read state + log tail of a job started with env_bg (container: systemd journal; root/termux: rh-bg log
            file).
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Job name used in env_bg")
                    })
                    put("lines", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many trailing log lines to fetch (default 60)")
                    })
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                },
                required = listOf("name"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it in "_.-" }) {
                error("env_log: 'name' must be non-empty and contain only A-Za-z0-9_.-")
            }
            val lines = (args.jsonObject["lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60)
                .coerceIn(1, 5000)
            val payload = if (t.isContainer) {
                val res = runHostScript("WS_NAME=${t.container} $WS_TOOL log $name $lines", null, defTimeout.coerceAtLeast(30L))
                buildJsonObject {
                    put("target", t.label)
                    put("name", name)
                    put("exit_code", JsonPrimitive(res.exitCode))
                    put("log", JsonPrimitive(trimOutput(res.stdout)))
                    if (res.stderr.isNotBlank()) put("stderr", JsonPrimitive(trimOutput(res.stderr)))
                }
            } else {
                val dir = when (t.mode) {
                    "termux" -> TERMUX_BG_DIR
                    "ssh" -> SSH_BG_DIR
                    else -> HOST_BG_DIR
                }
                val script = buildString {
                    append("d=").append(dir).append("\n")
                    append("[ -f \"\$d/").append(name).append(".log\" ] || { echo 'env_log: no such job' >&2; exit 3; }\n")
                    append("if [ -f \"\$d/").append(name).append(".pid\" ] && kill -0 \"\$(cat \"\$d/").append(name)
                        .append(".pid\")\" 2>/dev/null; then echo \"${META_PREFIX}state=running\"; else echo \"${META_PREFIX}state=finished\"; fi\n")
                    append("tail -n ").append(lines).append(" \"\$d/").append(name).append(".log\"\n")
                }
                val res = runInTarget(t, script, defTimeout.coerceAtLeast(30L))
                if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "env_log failed")
                val firstLine = res.stdout.substringBefore('\n')
                val state = firstLine.removePrefix(STATE_META).trim()
                val body = if (res.stdout.contains('\n')) res.stdout.substringAfter('\n') else ""
                buildJsonObject {
                    put("target", t.label)
                    put("name", name)
                    put("state", state)
                    put("log", JsonPrimitive(trimOutput(body)))
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    return listOf(execTool, readTool, writeTool, editTool, listTool, bgTool, logTool)
}

private fun countOccurrences(text: String, sub: String): Int {
    if (sub.isEmpty()) return 0
    var c = 0
    var i = text.indexOf(sub)
    while (i >= 0) {
        c++
        i = text.indexOf(sub, i + sub.length)
    }
    return c
}
