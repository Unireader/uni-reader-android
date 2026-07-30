package com.xvan.unireader.shared

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * 单发后台任务 + 主线程回调（`ANDROID-STANDALONE-PLAN.md §9.5`）。
 *
 * **为什么必须有这层**：工作区常态放在 U 盘 / SD / 同步盘上（§1 的数据来源就是这么定的），
 * 那种卷上 `File.exists/length`、`SQLiteDatabase.open`、Pdfium 读页尺寸表全是秒级——放在主线程
 * 就是 ANR。实测（模拟器 `/sdcard` FUSE 冷缓存）：`Workspace.check` 2.1s、`LibraryStore.open` 1s。
 *
 * **为什么不用协程**：本模块刻意没引 `kotlinx-coroutines`（依赖表见 `app/build.gradle.kts`），
 * 而这些活儿都是「打开时跑一次」的单发 I/O，一个线程池 + `Handler` 就够，不值得为它拉一套运行时。
 *
 * 两条容易踩的地方，都由 [runInBackground] 兜住：
 * - 结果回来时界面可能已经关了（用户等不及按了返回）。这时不能回调 UI，而且**后台已经开出来的
 *   句柄必须收掉**——`LibraryStore` 悬着不关，WAL 就一直挂在那儿，下次打开这个工作区会读到
 *   半新半旧的状态（§9.2 单写者）。所以有独立的 `discard`。
 * - 慢就要留痕。超过 [SLOW_MS] 的一律打 warn：以后再有人说「点了半天没反应」，日志里直接看得到
 *   是哪一步、花了多久。
 */
object Bg {

    const val TAG = "UniReader/Bg"

    /** 超过这个耗时就打 warn（主线程做同样的活儿到这个量级已经够触发 ANR 观察窗） */
    const val SLOW_MS = 700L

    internal val main = Handler(Looper.getMainLooper())

    /**
     * 打开流程里几件事可以并行（选目录时列目录 / 校验工作区），所以不是单线程队列；
     * 优先级压一档，别和 `pdf-render` 抢 CPU。
     */
    internal val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "unireader-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /** 甩出去就不管的收尾 I/O（关库含 `wal_checkpoint(TRUNCATE)`、关 Pdfium）。主线程做会卡退出。 */
    fun submit(what: String, work: () -> Unit) {
        val t0 = SystemClock.uptimeMillis()
        pool.execute {
            val e = runCatching(work).exceptionOrNull()
            val ms = SystemClock.uptimeMillis() - t0
            if (e != null) {
                Log.w(TAG, "$what 失败（${ms}ms）", e)
            } else if (ms > SLOW_MS) {
                Log.w(TAG, "$what 用了 ${ms}ms（慢卷）")
            }
        }
    }
}

/**
 * 在后台跑 [work]，回到主线程给 [ok]（或 [fail]）。
 *
 * @param what 日志里用来认这一步的名字（会连耗时一起打）。
 * @param discard 界面已 finishing/destroyed 时用来处置 [work] 的产物——**开出了资源就必须实现它**，
 *   否则拿不到结果的那次打开会把库句柄泄在后台（见 [Bg] 的说明）。
 */
fun <T> Activity.runInBackground(
    what: String,
    work: () -> T,
    ok: (T) -> Unit,
    fail: (Throwable) -> Unit = {},
    discard: (T) -> Unit = {},
) {
    val t0 = SystemClock.uptimeMillis()
    Bg.pool.execute {
        val r = runCatching(work)
        val ms = SystemClock.uptimeMillis() - t0
        Bg.main.post {
            val gone = isFinishing || isDestroyed
            r.fold(
                onSuccess = { v ->
                    if (gone) {
                        Log.i(Bg.TAG, "$what 完成（${ms}ms）但界面已关，丢弃结果")
                        runCatching { discard(v) }
                            .onFailure { Log.w(Bg.TAG, "处置 $what 的结果时出错", it) }
                    } else {
                        if (ms > Bg.SLOW_MS) Log.w(Bg.TAG, "$what 用了 ${ms}ms（慢卷）")
                        else Log.i(Bg.TAG, "$what 用了 ${ms}ms")
                        ok(v)
                    }
                },
                onFailure = { e ->
                    Log.e(Bg.TAG, "$what 失败（${ms}ms）", e)
                    if (!gone) fail(e)
                },
            )
        }
    }
}
