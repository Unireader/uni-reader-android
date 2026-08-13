package com.xvan.unireader.shared

import kotlin.math.max
import kotlin.math.min

/**
 * 页图的**像素宽度档位**与位图缓存额度——两模式共用一份。
 *
 * 从前只有模式1 有这套（在 `local/PdfSource.kt` 里），模式2 是把 `PageCanvasView` 算好的
 * `pw()` 直接丢掉、一律按 Mac 写死的 1600px 取图。后果在 Pad 6（1800×2880，density 2.5）上
 * 很直接：横屏视口 2880px，拿到 1600px 的图要**放大 1.8 倍**才铺满，字发虚；而模式1 在同一台
 * 机器上是按 2880 渲的，于是「同一本书，模式2 明显比模式1 糊」。
 *
 * 现在改成：**两模式都用这里的档位**，模式2 把档位随 `/page.png?w=` 报给 Mac。
 *
 * ⚠️ **Mac 端 `Sources/Server/LANServer.swift` 有一份同样的阶梯**（服务端要再夹一次，
 * 防止旧客户端/手敲 URL 把缓存打散成任意宽度）。改这里必须同步改那边——
 * 两边不一致的表现是「客户端按 2160 存、服务端按 2880 渲」，缓存永远不命中。
 * 这不是线格式，所以不涉及 `PROTOCOL.md` 的字节向量。
 */
object PageWidths {

    /** 宽度档位：连续缩放时不为每个像素宽度各渲一张，档位内复用 */
    val STEPS = intArrayOf(480, 720, 1080, 1440, 2160, 2880)

    /** 归到最近的**不小于**目标宽度的档位；超出最大档位就用最大档位，别让内存无限涨 */
    fun snap(widthPx: Int): Int {
        val w = max(1, widthPx)
        for (s in STEPS) if (s >= w) return s
        return STEPS.last()
    }

    /**
     * 两趟取图的**低清那趟**用哪个档位（模式2，见 `pad/PageFetcher.kt`）。
     * 目标档本身就不高于它时返回 0 = 不必分两趟。
     *
     * 选 1080：Mac 渲它约 100ms、约 266KB、位图 6.6MB——比目标档（2381px / 30MB / ~300ms）
     * 便宜一个数量级，贴上去虽然是放大的，但**总比现在那块灰色占位强**，而且一秒内就被换掉。
     */
    fun previewFor(target: Int): Int = if (target > PREVIEW) PREVIEW else 0

    const val PREVIEW = 1080

    /** 比 [target] 低的档位里，缓存中最高的那个（换档/缩放后能零网络先贴上旧图） */
    fun stepsBelow(target: Int): List<Int> = STEPS.filter { it < target }.reversed()

    /**
     * 位图缓存上限（字节）：可用堆的 1/3，封顶 192MB。
     *
     * **必须按字节算，不能按张数**：一张 2880 宽的页图是 2880×4073×4 ≈ 47MB，
     * 而本机 `dalvik.vm.heapgrowthlimit` 才 256MB——按张数记的缓存（模式2 从前是
     * `LruCache(16)`）等于放任 700MB 的额度，必 OOM。
     */
    fun defaultCacheBytes(): Int = min(192L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 3).toInt()
}
