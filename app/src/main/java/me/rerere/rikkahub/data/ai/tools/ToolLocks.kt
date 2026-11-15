package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具调用的并发闸门。
 *
 * 同一 assistant turn 里多个 tool_call 是并行执行的，彼此不知道依赖。这个类给 env 工具加两层锁：
 *
 * - **target 车道**：读类操作拿共享许可（可以无限并发），`env_exec` / `env_bg` 拿独占许可。
 *   → 同一个容器上，命令执行不会和读文件交错；但读与读、读不同路径互不阻塞。
 * - **path 车道**：同一 (target, path) 的读/写互斥；不同路径完全并发。
 *
 * 用 Semaphore(PERMITS) 实现读写锁：读 = acquire(1)，写/独占 = acquire(PERMITS)。
 * kotlinx 的 Semaphore 是 FIFO，所以不会饿死独占方。
 */
object ToolLocks {
    private const val PERMITS = 1000

    private class Lane {
        val sem = Semaphore(PERMITS)
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    private fun lane(key: String): Lane = lanes.getOrPut(key) { Lane() }

    /** 共享许可：多个持有者可以同时进入。 */
    suspend fun <T> shared(key: String, block: suspend () -> T): T {
        val s = lane(key).sem
        s.acquire(1)
        try {
            return block()
        } finally {
            s.release(1)
        }
    }

    /** 独占许可：等所有共享持有者退出，且期间新的共享请求排队。 */
    suspend fun <T> exclusive(key: String, block: suspend () -> T): T {
        val s = lane(key).sem
        s.acquire(PERMITS)
        try {
            return block()
        } finally {
            s.release(PERMITS)
        }
    }

    suspend fun <T> withTargetShared(target: String, block: suspend () -> T): T =
        shared("target:$target", block)

    suspend fun <T> withTargetExclusive(target: String, block: suspend () -> T): T =
        exclusive("target:$target", block)

    suspend fun <T> withPath(target: String, path: String, block: suspend () -> T): T =
        exclusive("path:$target|$path", block)
}
