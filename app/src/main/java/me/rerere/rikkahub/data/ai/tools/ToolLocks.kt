package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具调用的并发闸门。
 *
 * 同一 assistant turn 里多个 tool_call 是并行执行的，彼此不知道依赖。这个类给 env 工具加两层锁：
 *
 * - **target 车道**：读类操作拿共享许可（可以并发），`env_exec` / `env_bg` 拿独占许可。
 *   → 同一个容器上，命令执行不会和读文件交错；但读与读、读不同路径互不阻塞。
 * - **path 车道**：同一 (target, path) 的读/写互斥；不同路径完全并发。
 *
 * 注意：锁只防「交错」，不防「顺序」。同一轮并行发「写 + 读」，读仍可能先拿到锁看到旧内容；
 * 要保证「写完再读」的语义，用 codemode 脚本。
 */
object ToolLocks {

    private class Lane {
        val mutex = Mutex()
        var readers = 0
        var writer = false
        var waitingWriters = 0
        private var waiters = mutableListOf<CompletableDeferred<Unit>>()

        fun waiterLocked(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { waiters.add(it) }

        fun notifyAllLocked() {
            val pending = waiters
            waiters = mutableListOf()
            pending.forEach { it.complete(Unit) }
        }
    }

    private val lanes = ConcurrentHashMap<String, Lane>()

    private fun lane(key: String): Lane = lanes.getOrPut(key) { Lane() }

    /** 共享许可：多个持有者可以同时进入。 */
    suspend fun <T> shared(key: String, block: suspend () -> T): T {
        val l = lane(key)
        while (true) {
            var wait: CompletableDeferred<Unit>? = null
            l.mutex.withLock {
                if (!l.writer && l.waitingWriters == 0) {
                    l.readers++
                } else {
                    wait = l.waiterLocked()
                }
            }
            val w = wait ?: break
            w.await()
        }
        try {
            return block()
        } finally {
            l.mutex.withLock {
                l.readers--
                l.notifyAllLocked()
            }
        }
    }

    /** 独占许可：等所有共享持有者退出；期间新的共享请求排队。 */
    suspend fun <T> exclusive(key: String, block: suspend () -> T): T {
        val l = lane(key)
        while (true) {
            var wait: CompletableDeferred<Unit>? = null
            l.mutex.withLock {
                if (!l.writer && l.readers == 0 && l.waitingWriters == 0) {
                    l.writer = true
                } else {
                    l.waitingWriters++
                    wait = l.waiterLocked()
                }
            }
            val w = wait ?: break
            try {
                w.await()
            } finally {
                l.mutex.withLock { l.waitingWriters-- }
            }
        }
        try {
            return block()
        } finally {
            l.mutex.withLock {
                l.writer = false
                l.notifyAllLocked()
            }
        }
    }

    suspend fun <T> withTargetShared(target: String, block: suspend () -> T): T =
        shared("target:$target", block)

    suspend fun <T> withTargetExclusive(target: String, block: suspend () -> T): T =
        exclusive("target:$target", block)

    suspend fun <T> withPath(target: String, path: String, block: suspend () -> T): T =
        exclusive("path:$target|$path", block)
}
