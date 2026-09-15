package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * 后台任务看门狗 —— 跑完的活儿把结果送回它所属的会话。
 *
 * 支持两种 job：
 *   1. **脚本型**（env_bg 起的）：脚本收尾把 exit code 原子写进 `<name>.rc`，本类轮询那个文件。
 *   2. **进程型**（env_exec 前台等超时后交出来的）：进程句柄还在，本类起个协程等它退出、
 *      写 rc、再走同一套回传。日志就是那个进程的输出文件（logPathHost）。
 *
 * 在看的 job 落盘成 rh-watch-jobs.json —— 那就是「钩子」：进程被杀期间完成的任务不会丢，
 * 下次起来 init() 时补报。
 */
class EnvJobWatcher(
    private val context: Context,
    private val appScope: AppScope,
    private val notifier: (Uuid, String, Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "EnvJobWatcher"

        /** 宿主侧 job 目录（root 目标直接写这里，容器经 bind 写同一份） */
        const val HOST_BG_DIR = "/data/local/tmp/rh-bg"

        /** termux 侧 job 目录的宿主视角（su 可读） */
        const val TERMUX_BG_DIR_HOST = "/data/user/0/com.termux/files/home/.rh-bg"

        /** 容器里看宿主 /data/local/tmp 的路径 */
        const val CONTAINER_HOST_TMP = "/mnt/hostlocal/tmp"

        private const val POLL_MS = 5000L
        private const val LOG_TAIL_LINES = 40
        private const val LOG_TAIL_CHARS = 4000
        private const val MARK_PREFIX = "__rc__ "

        fun jobKey(target: String, name: String) = "$target:$name"
    }

    /** 一条待观察的 job。persist 到 files/rh-watch-jobs.json，就是跨进程的钩子。 */
    @Serializable
    data class WatchedJob(
        val name: String,
        val target: String,
        val container: String = "",
        val rcPathHost: String,
        val conversationId: String,
        val startedAt: Long,
        val deadlineSec: Long = 0L,
        val notifyMode: String = "reply",
        /** 进程型 job：输出文件（app 可直读）。脚本型为空，日志按 target 去查。 */
        val logPathHost: String = "",
    )

    private val jobs = mutableListOf<WatchedJob>()
    private var loopRunning = false
    private val storeFile: File by lazy { File(context.filesDir, "rh-watch-jobs.json") }

    /** 进程启动时调一次：把上一次的钩子捡回来，顺手补报已经跑完的。 */
    fun init() {
        val loaded = runCatching {
            if (!storeFile.exists()) emptyList()
            else JsonInstant.decodeFromString(ListSerializer(WatchedJob.serializer()), storeFile.readText())
        }.getOrElse {
            Log.w(TAG, "init: cannot read ${storeFile.name}: ${it.message}")
            emptyList()
        }
        synchronized(jobs) {
            jobs.clear()
            jobs.addAll(loaded)
        }
        if (loaded.isNotEmpty()) {
            Log.i(TAG, "init: ${loaded.size} pending job(s) recovered")
            ensureLoop()
        }
    }

    /** 登记一条脚本型 job（env_bg 启动成功后调）。 */
    fun register(job: WatchedJob) {
        synchronized(jobs) {
            jobs.removeAll { jobKey(it.target, it.name) == jobKey(job.target, job.name) }
            jobs.add(job)
        }
        persist()
        ensureLoop()
        Log.i(TAG, "register: ${job.target}/${job.name} -> conversation ${job.conversationId}")
    }

    /**
     * 收养一个还在跑的前台进程（env_exec 等超时了但命令没结束）。
     *
     * 进程继续跑、输出继续写它自己的文件；这里只等它退出，然后写 rc 走常规回传。
     * 调用方不要再 kill 这个进程。
     */
    fun adoptProcess(
        process: Process,
        name: String,
        target: String,
        container: String,
        conversationId: Uuid,
        logPathHost: String,
        startedAt: Long,
        deadlineSec: Long = 0L,
        notifyMode: String = "reply",
    ) {
        val rcFile = File(context.filesDir, "rh-rc/$name.rc")
        runCatching {
            rcFile.parentFile?.mkdirs()
            rcFile.delete()
        }
        appScope.launch(Dispatchers.IO) {
            runCatching {
                while (process.isAlive) delay(500)
                val code = runCatching { process.exitValue() }.getOrDefault(-1)
                rcFile.parentFile?.mkdirs()
                rcFile.writeText(code.toString())
            }.onFailure { Log.w(TAG, "adoptProcess($name) wait failed: ${it.message}") }
        }
        register(
            WatchedJob(
                name = name,
                target = target,
                container = container,
                rcPathHost = rcFile.absolutePath,
                conversationId = conversationId.toString(),
                startedAt = startedAt,
                deadlineSec = deadlineSec,
                notifyMode = notifyMode,
                logPathHost = logPathHost,
            )
        )
    }

    /** 当前还在看在报的 job。 */
    fun snapshot(): List<WatchedJob> = synchronized(jobs) { jobs.toList() }

    private fun remove(job: WatchedJob, deleteMark: Boolean) {
        synchronized(jobs) { jobs.remove(job) }
        persist()
        appScope.launch {
            if (deleteMark) runSu("rm -f ${shq(job.rcPathHost)}", 10L)
            if (job.logPathHost.isNotEmpty()) runCatching { File(job.logPathHost).delete() }
        }
    }

    private fun persist() {
        runCatching {
            val snapshot = synchronized(jobs) { jobs.toList() }
            storeFile.writeText(
                JsonInstant.encodeToString(ListSerializer(WatchedJob.serializer()), snapshot)
            )
        }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    private fun ensureLoop() {
        if (loopRunning) return
        loopRunning = true
        appScope.launch {
            try {
                while (true) {
                    val snapshot = snapshot()
                    if (snapshot.isEmpty()) break
                    runCatching { tick(snapshot) }
                        .onFailure { Log.w(TAG, "tick failed: ${it.message}") }
                    delay(POLL_MS)
                }
            } finally {
                loopRunning = false
                // register 恰好发生在退出判定之后时，补一次
                if (snapshot().isNotEmpty()) ensureLoop()
            }
        }
    }

    private suspend fun tick(snapshot: List<WatchedJob>) {
        val marks = readMarks(snapshot)
        val now = System.currentTimeMillis()
        for (job in snapshot) {
            val rc = marks[job.rcPathHost]
            val overdue = job.deadlineSec > 0 && now - job.startedAt >= job.deadlineSec * 1000
            if (rc == null && !overdue) continue

            val elapsed = (now - job.startedAt) / 1000
            val tail = readLogTail(job)
            val text = buildString {
                if (rc != null) {
                    append("[后台任务完成] ").append(job.name)
                    append(" · ").append(targetLabel(job))
                    append(" · exit=").append(rc.trim().ifBlank { "?" })
                    append(" · 耗时 ").append(elapsed).append("s")
                } else {
                    append("[后台任务仍在运行] ").append(job.name)
                    append(" · ").append(targetLabel(job))
                    append(" · 已跑 ").append(elapsed).append("s，超过设定 ").append(job.deadlineSec).append("s")
                }
                append("\n日志尾部：\n====")
                append("\n").append(tail.ifBlank { "(空)" }).append("\n====")
                if (rc == null) {
                    append("\n（任务还在跑，需要时用 env_log 继续看）")
                }
            }
            remove(job, deleteMark = true)
            runCatching {
                notifier(Uuid.parse(job.conversationId), text, job.notifyMode == "reply")
            }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
            Log.i(TAG, "reported: ${job.target}/${job.name} (rc=${rc ?: "timeout"})")
        }
    }

    private fun targetLabel(job: WatchedJob): String =
        if (job.target == "arch") "arch:${job.container}" else job.target

    /**
     * 读 rc 标记。app 自己写得进去的（进程型 job 的 rc 在 app 私有目录）直接读，
     * 剩下的（/data/local/tmp、termux home）才走一条 su 批量读。
     */
    private suspend fun readMarks(jobs: List<WatchedJob>): Map<String, String> {
        if (jobs.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val needSu = mutableListOf<WatchedJob>()
        for (job in jobs) {
            val direct = runCatching {
                val f = File(job.rcPathHost)
                if (f.exists() && f.canRead()) f.readText() else null
            }.getOrNull()
            if (direct != null) result[job.rcPathHost] = direct else needSu.add(job)
        }
        if (needSu.isEmpty()) return result

        val paths = needSu.joinToString(" ") { shq(it.rcPathHost) }
        val script = "for p in $paths; do [ -f \"\$p\" ] && printf '%s%s=%s\\n' '$MARK_PREFIX' \"\$p\" \"\$(cat \"\$p\")\"; done\n"
        val out = runSu(script, 20L)
        out.lineSequence().mapNotNull { line ->
            val i = line.indexOf(MARK_PREFIX)
            if (i < 0) return@mapNotNull null
            val rest = line.substring(i + MARK_PREFIX.length)
            val eq = rest.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            rest.substring(0, eq) to rest.substring(eq + 1)
        }.forEach { (path, rc) -> result[path] = rc }
        return result
    }

    private suspend fun readLogTail(job: WatchedJob): String {
        if (job.logPathHost.isNotEmpty()) {
            return tailOfFile(File(job.logPathHost))
        }
        val cmd = when (job.target) {
            "arch" -> "WS_NAME=${shq(job.container)} /data/local/ws log ${shq(job.name)} $LOG_TAIL_LINES 2>&1"
            "termux" -> "tail -n $LOG_TAIL_LINES ${shq("$TERMUX_BG_DIR_HOST/${job.name}.log")} 2>&1"
            else -> "tail -n $LOG_TAIL_LINES ${shq("$HOST_BG_DIR/${job.name}.log")} 2>&1"
        }
        val out = runSu(cmd, 30L)
        return if (out.length <= LOG_TAIL_CHARS) out.trim()
        else out.takeLast(LOG_TAIL_CHARS).trim() + "\n...[已截断]"
    }

    /** 读文件尾部（大文件只读尾巴，别把内存吃满） */
    private fun tailOfFile(f: File, maxBytes: Int = LOG_TAIL_CHARS * 4): String {
        if (!f.exists() || !f.isFile) return ""
        return runCatching {
            val text = if (f.length() <= maxBytes) f.readText()
            else RandomAccessFile(f, "r").use { raf ->
                raf.seek(f.length() - maxBytes)
                val buf = ByteArray(maxBytes)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
            if (text.length <= LOG_TAIL_CHARS) text.trim()
            else "...[已截断]\n" + text.takeLast(LOG_TAIL_CHARS).trim()
        }.getOrDefault("")
    }

    private suspend fun runSu(script: String, timeoutSec: Long): String = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@withContext ""
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "runSu failed: ${e.message}")
            ""
        }
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
