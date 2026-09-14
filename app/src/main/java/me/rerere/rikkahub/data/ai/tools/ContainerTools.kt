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
 * 容器桥接工具（droidspaces）— 方案 A（配置放助手卡 JSON，不动 DB）
 *
 * 所有命令都不是在 app sandbox 里跑：经 `su -c /data/local/exec-tool.sh <mode> [容器名]`
 * 送进 droidspaces 容器（mode=arch，默认）/ 真机全局 ns（root）/ Termux（termux）。
 * 命令体写到执行器进程的 stdin（执行器内部 `CMD=$(cat)`）→ 天然 heredoc，零转义。
 *
 * ⚠️ 本文件不做任何 mount / nsenter；挂载一律改 container.config。
 */
private const val EXEC_TOOL = "/data/local/exec-tool.sh"
private const val WS_TOOL = "/data/local/ws"
private const val DROIDSPACES = "/data/local/Droidspaces/bin/droidspaces"

private const val MAX_READ_BYTES = 2L * 1024 * 1024
private const val MAX_WRITE_BYTES = 2 * 1024 * 1024
private const val MAX_TIMEOUT_SEC = 600L
private const val MAX_OUTPUT_CHARS = 120_000
private const val META_PREFIX = "__meta__ "
private const val READ_META = META_PREFIX + "size="

private data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** POSIX 单引号安全包裹 */
private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

private fun b64(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).chunked(76).joinToString("\n")

/** 单行 base64（要嵌进 python 单引号字面量时用，绝不能带换行） */
private fun b64flat(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private fun sanitizeContainer(name: String): String {
    val cleaned = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    return cleaned.ifBlank { "arch" }
}

private fun trimOutput(s: String): String =
    if (s.length <= MAX_OUTPUT_CHARS) s
    else s.take(MAX_OUTPUT_CHARS) + "\n...[truncated; original ${s.length} chars]"

private suspend fun runProcess(argv: Array<String>, stdin: String?, timeoutSec: Long): ExecResult {
    val process = try {
        Runtime.getRuntime().exec(argv)
    } catch (e: Exception) {
        error("container bridge: failed to start ${argv.firstOrNull()}: ${e.message}")
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
                        // 子进程提前退出会关掉 stdin；不是致命错误
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
            "container bridge: command timed out after ${timeoutSec}s and was killed. " +
                "For long tasks (pacman/docker pull/build) use container_bg + container_log."
        )
    } finally {
        runCatching { process.destroy() }
    }
    return result
}

/** 经 /data/local/exec-tool.sh 执行（mode = arch | root | termux） */
private suspend fun runInMode(
    mode: String,
    container: String,
    command: String,
    timeoutSec: Long,
): ExecResult {
    val argv = when (mode) {
        "root" -> arrayOf("su", "-c", "$EXEC_TOOL root")
        "termux" -> arrayOf("su", "-c", "$EXEC_TOOL termux")
        else -> arrayOf("su", "-c", "$EXEC_TOOL arch $container")
    }
    return runProcess(argv, command, timeoutSec)
}

/** 直接跑宿主机侧的辅助脚本（/data/local/ws 等），不经过 exec-tool */
private suspend fun runHostScript(
    shellCommand: String,
    stdin: String?,
    timeoutSec: Long,
): ExecResult = runProcess(arrayOf("su", "-c", shellCommand), stdin, timeoutSec)

private fun metaLine(result: ExecResult): Pair<Long, String> {
    val text = result.stdout
    val nl = text.indexOf('\n')
    if (nl >= 0 && text.startsWith(READ_META)) {
        val size = text.substring(READ_META.length, nl).trim().toLongOrNull() ?: -1L
        return size to text.substring(nl + 1)
    }
    return -1L to text
}

private fun failWith(stderr: String, exitCode: Int, fallback: String): Nothing {
    val msg = stderr.trim().ifBlank { "$fallback (exit $exitCode)" }
    error(msg)
}

fun createContainerTools(
    context: Context,
    mode: String,
    container: String,
    cwd: String,
    defaultTimeout: Int,
): List<Tool> {
    val modeSan = if (mode in listOf("arch", "root", "termux")) mode else "arch"
    val containerSan = sanitizeContainer(container)
    val baseCwd = cwd.ifBlank { "/root" }
    val defTimeout = if (defaultTimeout in 5..MAX_TIMEOUT_SEC.toInt()) defaultTimeout.toLong() else 60L

    val execTool = Tool(
        name = "container_exec",
        description = """
            Run a shell command INSIDE the droidspaces container (mode=$modeSan, container=$containerSan) via /data/local/exec-tool.sh — NOT in the app sandbox.
            It runs with root inside the container (systemd + docker available). Quotes/backticks/heredocs in `command` are passed through verbatim (no escaping needed).
            Default working directory is $baseCwd; override per call with `cwd`.
            Long tasks (pacman / docker pull / builds) MUST use container_bg + container_log, otherwise they hit the timeout.
            Returns JSON: exit_code, stdout, stderr (stderr is returned verbatim, never swallowed).
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run inside the container. Multi-line scripts are fine.")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Working directory inside the container (absolute). Default: $baseCwd")
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
            val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("container_exec: 'command' is required and must not be empty")
            val workdir = args.jsonObject["cwd"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: baseCwd
            val timeout = (args.jsonObject["timeout_sec"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: defTimeout).coerceIn(5L, MAX_TIMEOUT_SEC)

            val script = buildString {
                append("cd ").append(shq(workdir))
                append(" || { echo 'container_exec: no such working directory' >&2; exit 4; }\n")
                append(command)
            }
            val res = runInMode(modeSan, containerSan, script, timeout)
            if (res.exitCode == 4 && res.stderr.contains("no such working directory")) {
                error("container_exec: cwd not found inside container: $workdir")
            }
            val payload = buildJsonObject {
                put("mode", modeSan)
                put("container", containerSan)
                put("cwd", workdir)
                put("exit_code", JsonPrimitive(res.exitCode))
                put("stdout", JsonPrimitive(trimOutput(res.stdout)))
                put("stderr", JsonPrimitive(trimOutput(res.stderr)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val readTool = Tool(
        name = "container_read_file",
        description = """
            Read a text file INSIDE the droidspaces container (mode=$modeSan, container=$containerSan) via /data/local/exec-tool.sh — NOT in the app sandbox.
            `path` must be an absolute path inside the container (e.g. /root/src/x.kt, /etc/os-release).
            Reads line-wise: `offset` is the 1-based first line (default 1), `limit` the number of lines (default 200).
            Files larger than 2MB are refused unless offset/limit are given.
            Returns JSON: path, size, offset, limit, text.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path of the file inside the container")
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
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("container_read_file: 'path' is required")
            val offset = args.jsonObject["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val limit = args.jsonObject["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val start = offset ?: 1
            if (start < 1) error("container_read_file: offset must be >= 1")
            val count = limit ?: 200
            if (count !in 1..20000) error("container_read_file: limit must be between 1 and 20000")
            val end = start + count - 1
            val sliced = offset != null || limit != null

            val script = buildString {
                append("p=").append(shq(path)).append("\n")
                append("[ -e \"\$p\" ] || { echo \"container_read_file: no such file or directory\" >&2; exit 3; }\n")
                append("[ -f \"\$p\" ] || { echo \"container_read_file: not a regular file\" >&2; exit 4; }\n")
                append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
                if (!sliced) {
                    append("if [ \"\$sz\" -gt $MAX_READ_BYTES ]; then echo \"container_read_file: file is \$sz bytes (> 2MB); pass offset/limit to read a slice\" >&2; exit 7; fi\n")
                }
                append("echo \"${META_PREFIX}size=\$sz\"\n")
                append("sed -n '").append(start).append(",").append(end).append("p' \"\$p\"\n")
            }
            val res = runInMode(modeSan, containerSan, script, defTimeout)
            if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "container_read_file failed")
            val (size, text) = metaLine(res)
            val payload = buildJsonObject {
                put("path", path)
                put("size", JsonPrimitive(size))
                put("offset", JsonPrimitive(start))
                put("limit", JsonPrimitive(count))
                put("text", JsonPrimitive(text))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val writeTool = Tool(
        name = "container_write_file",
        description = """
            Write (create or overwrite) a UTF-8 text file INSIDE the droidspaces container (mode=$modeSan, container=$containerSan) via /data/local/exec-tool.sh — NOT in the app sandbox.
            Content is transferred base64 → decoded by `base64 -d`, so quotes/newlines are safe. Parent directories are created automatically.
            `path` must be absolute. Max 2MB per call; for bigger payloads use container_exec with a heredoc.
            Returns JSON: path, bytes, preview (first 200 chars).
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute destination path inside the container")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "UTF-8 text content to write (overwrites the file)")
                    })
                },
                required = listOf("path", "content"),
            )
        },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("container_write_file: 'path' is required")
            val content = args.jsonObject["content"]?.jsonPrimitive?.contentOrNull
                ?: error("container_write_file: 'content' is required")
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_WRITE_BYTES) {
                error("container_write_file: content too large (${bytes.size} bytes > 2MB); use container_exec with a heredoc for bigger files")
            }
            val script = buildString {
                append("p=").append(shq(path)).append("\n")
                append("[ -d \"\$p\" ] && { echo 'container_write_file: path is a directory' >&2; exit 4; }\n")
                append("mkdir -p \"\$(dirname \"\$p\")\" || { echo 'container_write_file: cannot create parent directory' >&2; exit 5; }\n")
                append("base64 -d > \"\$p\" <<'__CB64__'\n")
                append(b64(content)).append("\n")
                append("__CB64__\n")
                append("rc=\$?\n")
                append("[ \"\$rc\" -eq 0 ] || { echo 'container_write_file: base64 decode failed' >&2; exit 6; }\n")
                append("sz=\$(wc -c < \"\$p\" 2>/dev/null); sz=\${sz:-0}\n")
                append("echo \"${META_PREFIX}size=\$sz\"\n")
            }
            val res = runInMode(modeSan, containerSan, script, defTimeout)
            if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "container_write_file failed")
            val (written, _) = metaLine(res)
            val payload = buildJsonObject {
                put("path", path)
                put("bytes", JsonPrimitive(written))
                put("preview", JsonPrimitive(content.take(200)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val editTool = Tool(
        name = "container_edit_file",
        description = """
            Exact string replacement in a text file INSIDE the droidspaces container (mode=$modeSan, container=$containerSan) via /data/local/exec-tool.sh — NOT in the app sandbox.
            Uses python3 inside the container. `old_string` MUST exist exactly as given, otherwise the call fails (nothing is written silently).
            If `old_string` occurs more than once you must set replace_all=true (or add more context).
            Returns JSON: path, result {replacements, lines, bytes_before, bytes_after}.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Absolute path of the file inside the container")
                    })
                    put("old_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact text to find (must exist; include surrounding context to make it unique)")
                    })
                    put("new_string", buildJsonObject {
                        put("type", "string")
                        put("description", "Replacement text")
                    })
                    put("replace_all", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Replace every occurrence (default false = single, errors out if not unique)")
                    })
                },
                required = listOf("path", "old_string", "new_string"),
            )
        },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("container_edit_file: 'path' is required")
            val oldString = args.jsonObject["old_string"]?.jsonPrimitive?.contentOrNull
                ?: error("container_edit_file: 'old_string' is required")
            val newString = args.jsonObject["new_string"]?.jsonPrimitive?.contentOrNull
                ?: error("container_edit_file: 'new_string' is required")
            if (oldString.isEmpty()) error("container_edit_file: 'old_string' must not be empty")
            val replaceAll = args.jsonObject["replace_all"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false

            val py = listOf(
                "import base64, json, sys",
                "",
                "def d(s):",
                "    return base64.b64decode(s).decode('utf-8')",
                "",
                "path = d('" + b64flat(path) + "')",
                "old = d('" + b64flat(oldString) + "')",
                "new = d('" + b64flat(newString) + "')",
                "replace_all = " + (if (replaceAll) "True" else "False"),
                "",
                "try:",
                "    text = open(path, 'r', encoding='utf-8').read()",
                "except FileNotFoundError:",
                "    sys.stderr.write('container_edit_file: file not found\\n')",
                "    sys.exit(3)",
                "except IsADirectoryError:",
                "    sys.stderr.write('container_edit_file: path is a directory\\n')",
                "    sys.exit(4)",
                "except Exception as e:",
                "    sys.stderr.write('container_edit_file: read failed: ' + str(e) + '\\n')",
                "    sys.exit(6)",
                "cnt = text.count(old)",
                "if cnt == 0:",
                "    sys.stderr.write('container_edit_file: old_string not found\\n')",
                "    sys.exit(7)",
                "if cnt > 1 and not replace_all:",
                "    sys.stderr.write('container_edit_file: old_string occurs ' + str(cnt) + ' times; set replace_all=true or add more context\\n')",
                "    sys.exit(8)",
                "lines = []",
                "i = text.find(old)",
                "while i >= 0:",
                "    lines.append(text.count(chr(10), 0, i) + 1)",
                "    if not replace_all:",
                "        break",
                "    i = text.find(old, i + len(old))",
                "new_text = text.replace(old, new) if replace_all else text.replace(old, new, 1)",
                "try:",
                "    open(path, 'w', encoding='utf-8').write(new_text)",
                "except Exception as e:",
                "    sys.stderr.write('container_edit_file: write failed: ' + str(e) + '\\n')",
                "    sys.exit(9)",
                "print(json.dumps({'replacements': len(lines), 'lines': lines, 'bytes_before': len(text.encode('utf-8')), 'bytes_after': len(new_text.encode('utf-8'))}))",
            )
            val script = buildString {
                append("command -v python3 >/dev/null 2>&1 || { echo 'container_edit_file: python3 not available in this mode/container' >&2; exit 127; }\n")
                append("python3 - <<'__CPY__'\n")
                append(py.joinToString("\n")).append("\n")
                append("__CPY__\n")
            }
            val res = runInMode(modeSan, containerSan, script, defTimeout)
            if (res.exitCode != 0) failWith(res.stderr, res.exitCode, "container_edit_file failed")
            val payload = buildJsonObject {
                put("path", path)
                put("result", JsonPrimitive(res.stdout.trim()))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val listTool = Tool(
        name = "container_list",
        description = """
            Show the droidspaces containers, the background jobs started via container_bg, and (if docker is running) the containers inside $containerSan.
            Runs on the host side through /data/local/ws + droidspaces, plus one `docker ps` INSIDE the container — NOT in the app sandbox.
            Takes no arguments. Returns JSON: containers, jobs, docker_ps (raw text blocks).
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { },
            )
        },
        execute = {
            val script = buildString {
                append("DS=").append(DROIDSPACES).append("; ")
                append("WS=").append(WS_TOOL).append("; ")
                append("export WS_NAME=").append(containerSan).append("; ")
                append("echo '=== droidspaces show ==='; \$DS show 2>&1; ")
                append("echo; echo '=== background jobs (ws jobs) ==='; \$WS jobs 2>&1; ")
                append("echo; echo '=== docker ps (inside ").append(containerSan).append(") ==='; ")
                append("timeout 25 \$DS --name=").append(containerSan)
                append(" run sh -c 'docker ps --format \"{{.Names}}  {{.Image}}  {{.Status}}\" 2>&1 || true' 2>&1")
            }
            val res = runHostScript(script, null, defTimeout.coerceAtLeast(30L))
            val payload = buildJsonObject {
                put("mode", modeSan)
                put("container", containerSan)
                put("exit_code", JsonPrimitive(res.exitCode))
                put("output", JsonPrimitive(trimOutput(res.stdout)))
                if (res.stderr.isNotBlank()) put("stderr", JsonPrimitive(trimOutput(res.stderr)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val bgTool = Tool(
        name = "container_bg",
        description = """
            Start a LONG-RUNNING command in the background INSIDE the droidspaces container ($containerSan) — use this instead of container_exec for pacman/docker pull/builds to escape the shell timeout.
            Implementation: the script is written to the container's /root/.wsjobs/${'$'}name.sh and started as a systemd unit (ws-<name>), so it keeps running after this tool call returns.
            `name` may only contain A-Za-z0-9_.- . The command starts in $baseCwd.
            NOTE: jobs started this way do NOT inherit the exec-tool env injection (${'$'}GITHUB_TOKEN etc.) — source /etc/agent.env inside the script if you need it.
            Returns JSON: name, status. Fetch output later with container_log.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Job name (A-Za-z0-9_.- only), used later by container_log")
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell script to run in the background inside the container")
                    })
                },
                required = listOf("name", "command"),
            )
        },
        execute = { args ->
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it in "_.-" }) {
                error("container_bg: 'name' must be non-empty and contain only A-Za-z0-9_.-")
            }
            val command = args.jsonObject["command"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: error("container_bg: 'command' is required")
            val script = buildString {
                append("cd ").append(shq(baseCwd)).append(" || exit 1\n")
                append(command).append("\n")
            }
            val res = runHostScript("WS_NAME=$containerSan $WS_TOOL bg $name", script, 120L)
            val stderr = res.stderr.trim()
            if (res.exitCode != 0 || stderr.contains("ws: 启动失败") || stderr.contains("ws: 启动超时")) {
                failWith(res.stderr, res.exitCode, "container_bg failed to start job '$name'")
            }
            val payload = buildJsonObject {
                put("name", name)
                put("status", "started")
                put("note", "background job '$name' running inside container $containerSan; read it with container_log")
                put("output", JsonPrimitive((res.stdout + stderr).trim()))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    val logTool = Tool(
        name = "container_log",
        description = """
            Read the log of a background job started with container_bg (systemd unit ws-<name> inside the droidspaces container).
            Returns the unit's active state plus the last `lines` journal lines (default 60).
            Runs on the host side via /data/local/ws log — NOT in the app sandbox.
        """.trimIndent().replace("\n", " "),
        needsApproval = { false },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Job name used in container_bg")
                    })
                    put("lines", buildJsonObject {
                        put("type", "integer")
                        put("description", "How many trailing log lines to fetch (default 60)")
                    })
                },
                required = listOf("name"),
            )
        },
        execute = { args ->
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it in "_.-" }) {
                error("container_log: 'name' must be non-empty and contain only A-Za-z0-9_.-")
            }
            val lines = (args.jsonObject["lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 60)
                .coerceIn(1, 5000)
            val res = runHostScript(
                "WS_NAME=$containerSan $WS_TOOL log $name $lines",
                null,
                defTimeout.coerceAtLeast(30L),
            )
            val payload = buildJsonObject {
                put("name", name)
                put("exit_code", JsonPrimitive(res.exitCode))
                put("log", JsonPrimitive(trimOutput(res.stdout)))
                if (res.stderr.isNotBlank()) put("stderr", JsonPrimitive(trimOutput(res.stderr)))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )

    return listOf(execTool, readTool, writeTool, editTool, listTool, bgTool, logTool)
}
