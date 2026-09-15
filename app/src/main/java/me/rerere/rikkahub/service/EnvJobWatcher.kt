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
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * 后台任务看门狗 —— env_bg 起的 job 跑完之后，把结果送回它所属的会话。
 *
 * 跨进程契约只有一个文件：job 脚本收尾时把 exit code 原子写进 `<name>.rc`
 * （由 EnvTools 的 env_bg wrapper 注入）。本类只干两件事：
 *   1. 轮询这些 rc 文件（每 5s 一条 su 命令，批量检查所有在看的 job）；
 *   2. 出现 rc（或到达设定的超时）→ 抓日志尾部 → 注入会话（可选触发一次回复）。
 *
 * 进程被杀期间完成的任务不会丢：rc 文件还在磁盘上，等下次进程起来 init() 时补报。
 * 这是 pending job 落盘成 rh-watch-jobs.json 的意义 —— 那条记录 = 钩子。
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

    /** 登记一条 job（env_bg 启动成功后调）。 */
    fun register(job: WatchedJob) {
        synchronized(jobs) {
            jobs.removeAll { jobKey(it.target, it.name) == jobKey(job.target, job.name) }
            jobs.add(job)
        }
        persist()
        ensureLoop()
        Log.i(TAG, "register: ${job.target}/${job.name} -> conversation ${job.conversationId}")
    }

    /** 当前还在看在报的 job。 */
    fun snapshot(): List<WatchedJob> = synchronized(jobs) { jobs.toList() }

    private fun remove(job: WatchedJob, deleteMark: Boolean) {
        synchronized(jobs) { jobs.remove(job) }
        persist()
        if (deleteMark) {
            appScope.launch { runSu("rm -f ${shq(job.rcPathHost)}", 10L) }
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
                append("\n日志尾部（").append(LOG_TAIL_LINES).append(" 行）：\n====")
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

    /** 一条 su 命令批量读全部 rc 文件，返回 path -> rc。 */
    private suspend fun readMarks(jobs: List<WatchedJob>): Map<String, String> {
        if (jobs.isEmpty()) return emptyMap()
        val paths = jobs.joinToString(" ") { shq(it.rcPathHost) }
        val script = "for p in $paths; do [ -f \"\$p\" ] && printf '%s%s=%s\\n' '$MARK_PREFIX' \"\$p\" \"\$(cat \"\$p\")\"; done\n"
        val out = runSu(script, 20L)
        return out.lineSequence().mapNotNull { line ->
            val i = line.indexOf(MARK_PREFIX)
            if (i < 0) return@mapNotNull null
            val rest = line.substring(i + MARK_PREFIX.length)
            val eq = rest.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            rest.substring(0, eq) to rest.substring(eq + 1)
        }.toMap()
    }

    private suspend fun readLogTail(job: WatchedJob): String {
        val cmd = when (job.target) {
            "arch" -> "WS_NAME=${shq(job.container)} /data/local/ws log ${shq(job.name)} $LOG_TAIL_LINES 2>&1"
            "termux" -> "tail -n $LOG_TAIL_LINES ${shq("$TERMUX_BG_DIR_HOST/${job.name}.log")} 2>&1"
            else -> "tail -n $LOG_TAIL_LINES ${shq("$HOST_BG_DIR/${job.name}.log")} 2>&1"
        }
        val out = runSu(cmd, 30L)
        return if (out.length <= LOG_TAIL_CHARS) out.trim()
        else out.take(LOG_TAIL_CHARS).trim() + "\n...[已截断]"
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
