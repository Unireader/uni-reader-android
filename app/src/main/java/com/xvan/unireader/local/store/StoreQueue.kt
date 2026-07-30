package com.xvan.unireader.local.store

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 一个 [LibraryStore] 的**独占线程**（`ANDROID-STANDALONE-PLAN.md §9.5`）。
 *
 * **为什么要这层**：`LibraryStore` 非线程安全，此前的做法是「后台开库 → 交给主线程独占」，
 * 于是落笔的 INSERT、擦除事务、进度 UPDATE 全在主线程上。工作区常态放 U 盘/SD/同步盘（§1），
 * 那种卷上一次事务提交要 fsync，几百毫秒起步——写一笔卡一下，正是最不能卡的时刻。
 *
 * 交出去的是**所有权，不是一把锁**：库开出来之后就只有 [exec] 这一个线程碰它，主线程一行 I/O 都不做，
 * 读也走这里（读同样要走磁盘）。想拿数据就 `submit(what, work, then)`——`work` 在队列线程跑，
 * 结果 `post` 回主线程给 `then`。**别把 `LibraryStore` 从 `work` 里漏出去**（存进字段、塞进回调），
 * 那等于把独占权又还给了主线程。
 *
 * **串行还带来一个顺带的好处**：写→重读天然有序。原先「写完 `post { 重读 }`」是靠主线程消息队列
 * 排出来的顺序，现在是队列自己的 FIFO；退出时先攒的写也一定落在 [close] 的 `wal_checkpoint` 之前。
 *
 * 线程交接（都有 happens-before，不需要额外加锁）：
 * `unireader-io` 开库 → `Handler.post` 到主线程 → `Executor.execute` 到 `unireader-store` 独占到底。
 *
 * **不引协程**同 [com.xvan.unireader.shared.Bg] 的理由：一个单线程 executor + `Handler` 就够。
 */
class StoreQueue(private val store: LibraryStore) : Closeable {

    companion object {
        const val TAG = "UniReader/StoreQ"

        /** 单次作业超过这个耗时就打 warn（慢卷留痕，口径同 `Bg.SLOW_MS`） */
        const val SLOW_MS = 700L

        /** 排队等待超过这个时长也打 warn：队列堵住和单次慢是两种病，得分得开 */
        const val WAIT_WARN_MS = 300L
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unireader-store").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1   // 别和 pdf-render 抢 CPU
        }
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false

    /** 这个卷有没有真的启用 WAL。开库时就定了，读它不碰库，主线程可以直接问 */
    val walEnabled: Boolean get() = store.walEnabled

    /** 甩出去不管的写（进度、图层可见性……）。失败只记日志——调用方随后的重读会把界面拉回真源 */
    fun submit(what: String, work: (LibraryStore) -> Unit) = enqueue(what, work, null)

    /**
     * 读/写完把结果送回主线程。[work] 在队列线程跑（这里可以随便做 I/O），
     * [then] 在主线程跑（这里只准碰界面）。界面关掉后 [then] 不会再被调用。
     */
    fun <T> submit(what: String, work: (LibraryStore) -> T, then: (T) -> Unit) =
        enqueue(what, work, then)

    private fun <T> enqueue(what: String, work: (LibraryStore) -> T, then: ((T) -> Unit)?) {
        if (closed) {
            Log.i(TAG, "$what：队列已关，丢弃")
            return
        }
        val t0 = SystemClock.uptimeMillis()
        try {
            exec.execute {
                val t1 = SystemClock.uptimeMillis()
                val r = runCatching { work(store) }
                val ran = SystemClock.uptimeMillis() - t1
                val waited = t1 - t0
                r.fold(
                    onSuccess = { v ->
                        // 等了多久 / 跑了多久分开记：队列堵住（前面有个慢作业）和这一条本身慢，
                        // 在「写一笔卡一下」的现场是两个完全不同的结论
                        if (ran > SLOW_MS || waited > WAIT_WARN_MS) {
                            Log.w(TAG, "$what 等 ${waited}ms 跑 ${ran}ms（慢卷）")
                        }
                        if (then != null) main.post { if (!closed) then(v) }
                    },
                    onFailure = { e -> Log.e(TAG, "$what 失败（等 ${waited}ms 跑 ${ran}ms）", e) },
                )
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "$what：队列已停，丢弃", e)
        }
    }

    /**
     * 关库。**排在队尾**：前面攒着的写（比如 `onDestroy` 里刚提交的那次进度）一定先落盘，
     * 再走 `store.close()` 里的 `wal_checkpoint(TRUNCATE)`——慢卷上那一步是秒级，
     * 所以整件事都不能在主线程做（§9.5）。
     *
     * 置位 [closed] 之后新提交一律丢弃、已在飞的回调也不再送回主线程（界面已经没了）。
     */
    override fun close() {
        if (closed) return
        closed = true
        try {
            exec.execute {
                runCatching { store.close() }.onFailure { Log.w(TAG, "关库失败", it) }
                Log.i(TAG, "库已关，队列结束")
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "队列已停，关库没能排进去", e)
        }
        exec.shutdown()
    }

    /**
     * 等 [close] 排在队尾的关库真正跑完。**只给测试用**——正常路径不该等队列，
     * 谁等谁就是把队列的耗时又搬回了自己的线程上（主线程等 = 白做一场）。
     */
    fun awaitClosed(timeoutMs: Long): Boolean =
        exec.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
}
