package com.xvan.unireader.local.search

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 算查询向量（`../docs/SEMANTIC-SYNC-PLAN.md §4.3`）：llama.cpp（[EmbedNative]）+ Q8_0 模型，只用 CPU 4 线程。
 * 用到时才加载（约 0.6 秒），闲置 2 分钟卸载（模型 + 重排后的权重约 1 GB 内存）。所有调用串在一条线程上。
 */
object QueryEmbedder {
    private const val TAG = "UniReader/Embed"
    private const val THREADS = 4
    private const val IDLE_MS = 2 * 60_000L

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "unireader-embed") }
    private var loaded = false
    private var idle: ScheduledFuture<*>? = null

    /** 查询 → L2 归一化的向量（前面加任务说明 [Semantic.QUERY_PREFIX]）。**阻塞**，别在主线程调。模型没就位 / 出错抛异常。 */
    fun embedQuery(ctx: Context, query: String): FloatArray {
        val app = ctx.applicationContext
        val fut = exec.submit<FloatArray> {
            idle?.cancel(false)
            try {
                if (!loaded) {
                    val f = EmbedModel.file(app)
                    check(EmbedModel.isInstalled(app)) { "the search model has not been downloaded" }
                    val t = System.nanoTime()
                    EmbedNative.load(f.path, THREADS)
                    loaded = true
                    Log.i(TAG, "模型载入 ${(System.nanoTime() - t) / 1_000_000} ms")
                }
                val t = System.nanoTime()
                EmbedNative.embed(Semantic.QUERY_PREFIX + query).also {
                    Log.i(TAG, "查询向量 ${(System.nanoTime() - t) / 1_000_000} ms")
                }
            } finally {
                if (loaded) idle = exec.schedule({ doUnload() }, IDLE_MS, TimeUnit.MILLISECONDS)
            }
        }
        try {
            return fut.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** 马上卸载（关开关 / 删模型时）。排在正在算的那次后面。 */
    fun unload() { exec.execute { idle?.cancel(false); doUnload() } }

    private fun doUnload() {
        if (!loaded) return
        EmbedNative.unload()
        loaded = false
        Log.i(TAG, "模型已卸载")
    }
}
