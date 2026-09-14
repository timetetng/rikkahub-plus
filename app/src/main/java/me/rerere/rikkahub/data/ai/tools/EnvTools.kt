package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

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

private const val MAX_READ_BYTES = 2L * 1024 * 1024
private const val MAX_WRITE_BYTES = 2 * 1024 * 1024
private const val MAX_TIMEOUT_SEC = 600L
private const val MAX_OUTPUT_CHARS = 120_000
private const val META_PREFIX = "__meta__ "
private const val READ_META = META_PREFIX + "size="
private const val STATE_META = META_PREFIX + "state="

private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** 目标环境 */
private data class Target(val mode: String, val container: String) {
    val label: String get() = if (mode == "arch") "arch:$container" else mode
    val isContainer: Boolean get() = mode == "arch"
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
    else -> "/root"
}

private fun parseTarget(raw: String): Target {
    val a = raw.trim()
    return when {
        a.isEmpty() || a == "arch" -> Target("arch", "arch")
        a == "root" -> Target("root", "")
        a == "termux" -> Target("termux", "")
        a.startsWith("ct:") -> Target("arch", sanitizeName(a.removePrefix("ct:")).ifBlank { "arch" })
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
    if (t.mode == "termux") return path to null   // termux 只能看 $PREFIX/HOME 与 /storage，不做映射
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

private fun b64(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).chunked(76).joinToString("\n")

private fun b64decode(s: String): String =
    String(Base64.decode(s.replace(Regex("\\s"), ""), Base64.DEFAULT), Charsets.UTF_8)

private fun trimOutput(s: String): String =
    if (s.length <= MAX_OUTPUT_CHARS) s
    else s.take(MAX_OUTPUT_CHARS) + "\n...[truncated; original ${s.length} chars]"

private suspend fun runProcess(argv: Array<String>, stdin: String?, timeoutSec: Long): ExecResult {
    val process = try {
        Runtime.getRuntime().exec(argv)
    } catch (e: Exception) {
        error("env bridge: failed to start ${argv.firstOrNull()}: ${e.message}")
    }
    val result = try {
        withTimeout(timeoutSec * 1000L) {
            coroutineScope {
                val stdinJob = async(Dispatchers.IO) {
                    try {
                        process.outputStream.use { os ->
                            if (stdin != null) os.write(stdin.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    } catch (_: Exception) {
                        // 子进程提前退出会关掉 stdin，不是致命错误
                    }
                }
                val outDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
                val errDeferred = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
                val out = outDeferred.await()
                val err = errDeferred.await()
                stdinJob.await()
                if (process.isAlive) process.waitFor(5, TimeUnit.SECONDS)
                val code = try {
                    if (process.isAlive) -1 else process.exitValue()
                } catch (_: Exception) {
                    -1
                }
                ExecResult(code, out, err)
            }
        }
    } catch (e: TimeoutCancellationException) {
        process.destroyForcibly()
        error(
            "env bridge: command timed out after ${timeoutSec}s and was killed. " +
                "For long tasks (pacman/docker pull/build) use env_bg + env_log."
        )
    } finally {
        runCatching { process.destroy() }
    }
    return result
}

/** 在目标环境里执行脚本 */
private suspend fun runInTarget(t: Target, script: String, timeoutSec: Long): ExecResult =
    runProcess(argvFor(t), script, timeoutSec)

/** 在宿主侧直接跑（ws 等辅助脚本） */
private suspend fun runHostScript(shellCommand: String, stdin: String?, timeoutSec: Long): ExecResult =
    runProcess(arrayOf("su", "-c", shellCommand), stdin, timeoutSec)

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
        "droidspaces container) | root (real device, global mount ns) | termux (ZeroTermux)."

fun createEnvTools(
    context: Context,
    target: String,
    cwd: String,
    defaultTimeout: Int,
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

    /** 写文件（base64 管道，内容不经过 shell 解析） */
    suspend fun writeRaw(t: Target, pathArg: String, content: String, timeoutSec: Long): Pair<Long, String> {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES) {
            error("env_write_file: content too large (> 2MB); use env_exec with a heredoc for bigger files")
        }
        val (path, _) = mapPath(t, pathArg)
        val script = buildString {
            append("p=").append(shq(path)).append("\n")
            append("[ -d \"\$p\" ] && { echo 'env_write_file: path is a directory' >&2; exit 4; }\n")
            append("mkdir -p \"\$(dirname \"\$p\")\" || { echo 'env_write_file: cannot create parent directory' >&2; exit 5; }\n")
            append("base64 -d > \"\$p\" <<'__CB64__'\n")
            append(b64(content)).append("\n")
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
                        put("description", "Timeout in seconds (default $defTimeout, max $MAX_TIMEOUT_SEC). On timeout the process is killed.")
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
            val timeout = (args.jsonObject["timeout_sec"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: defTimeout).coerceIn(5L, MAX_TIMEOUT_SEC)

            val script = buildString {
                append("cd ").append(shq(workdir))
                append(" || { echo 'env_exec: no such working directory' >&2; exit 4; }\n")
                append(command)
            }
            val res = runInTarget(t, script, timeout)
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
            val (written, path) = writeRaw(t, pathArg, content, defTimeout)
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
            auto-mapped.
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
                    put("target", buildJsonObject {
                        put("type", "string")
                        put("description", TARGET_DESC)
                    })
                },
                required = listOf("path", "old_string", "new_string"),
            )
        },
        execute = { args ->
            val t = resolveTarget(args.jsonObject["target"]?.jsonPrimitive?.contentOrNull, cfgTarget)
            val pathArg = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("env_edit_file: 'path' is required")
            val oldString = args.jsonObject["old_string"]?.jsonPrimitive?.contentOrNull
                ?: error("env_edit_file: 'old_string' is required")
            val newString = args.jsonObject["new_string"]?.jsonPrimitive?.contentOrNull
                ?: error("env_edit_file: 'new_string' is required")
            if (oldString.isEmpty()) error("env_edit_file: 'old_string' must not be empty")
            val replaceAll = args.jsonObject["replace_all"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            val (path, mappedNote) = mapPath(t, pathArg)

            val (_, text) = readRaw(t, path, defTimeout)   // path 已翻译过，mapPath 不会再动它
            val cnt = countOccurrences(text, oldString)
            if (cnt == 0) error("env_edit_file: old_string not found in ${t.label}:$path")
            if (cnt > 1 && !replaceAll) {
                error("env_edit_file: old_string occurs $cnt times in ${t.label}:$path; set replace_all=true or add more context")
            }
            val lines = ArrayList<Int>()
            var i = text.indexOf(oldString)
            while (i >= 0) {
                lines.add(text.substring(0, i).count { c -> c == '\n' } + 1)
                if (!replaceAll) break
                i = text.indexOf(oldString, i + oldString.length)
            }
            val newText = if (replaceAll) text.replace(oldString, newString) else text.replaceFirst(oldString, newString)
            val (written, _) = writeRaw(t, path, newText, defTimeout)
            val payload = buildJsonObject {
                put("target", t.label)
                put("path", path)
                if (mappedNote != null) put("path_mapped", mappedNote)
                put("replacements", JsonPrimitive(lines.size))
                put("lines", kotlinx.serialization.json.JsonArray(lines.map { JsonPrimitive(it) }))
                put("bytes_before", JsonPrimitive(text.toByteArray(Charsets.UTF_8).size))
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
            val res = runHostScript(script, null, defTimeout.coerceAtLeast(30L))
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
            val script = buildString {
                append("cd ").append(shq(cwdForJob)).append(" || true\n")
                append(command).append("\n")
            }
            val res = if (t.isContainer) {
                runHostScript("WS_NAME=${t.container} $WS_TOOL bg $name", script, 120L)
            } else {
                val dir = if (t.mode == "termux") TERMUX_BG_DIR else HOST_BG_DIR
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
            val payload = buildJsonObject {
                put("target", t.label)
                put("name", name)
                put("status", "started")
                put("note", "background job '$name' started in ${t.label}; read it with env_log")
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
                val dir = if (t.mode == "termux") TERMUX_BG_DIR else HOST_BG_DIR
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
