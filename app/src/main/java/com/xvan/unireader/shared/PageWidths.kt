package com.xvan.unireader.shared

import android.app.ActivityManager
import android.content.Context
import kotlin.math.max

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
     * 位图缓存上限（字节），**按设备总内存算**（两模式共用）。
     *
     * **必须按字节算，不能按张数**：一张 2880 宽的页图是 2880×4073×4 ≈ 47MB，
     * 按张数记的缓存（模式2 从前是 `LruCache(16)`）等于放任 700MB 的额度。
     *
     * ### 为什么不再用 `maxMemory()/3`（2026-08-29 改）
     * 那是 Java 堆的 1/3。而**从 Android 8.0（API 26）起，`Bitmap` 的像素住在 native 堆**，
     * 压根不占 Java 堆——`maxMemory()`（本机 256MB = `dalvik.vm.heapgrowthlimit`）量的是另一样东西，
     * 拿它当页图的预算是**记错了账**：8GB 内存的平板上只肯留 85MB＝一张横屏页图，
     * 于是换篇文档回来必然重下（用户 2026-08-29 报「切标签页每次都要重新加载」）。
     * 同理 `largeHeap` 对页图**没有用**（它只抬 Java 堆）。
     *
     * 换成 `totalMem/20`，夹在 64MB…384MB：8GB → 384MB（≈8 张横屏页图，够两三篇文档的当前屏），
     * 4GB → 205MB，低内存设备（`isLowRamDevice`）一律 [LOW_RAM_CACHE_BYTES]。
     *
     * ⚠️ 额度大了就**必须还得回去**：两模式都接了 `onTrimMemory`（`PadActivity` /
     * `ReaderActivity`），退到后台缩到背景额度、系统吃紧时砍半。native 内存不会像 Java 堆那样
     * 抛 OOM，代价是**整个进程被 lowmemorykiller 干掉**（回来就是冷启），所以宁可主动让。
     */
    fun cacheBytes(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return LOW_RAM_CACHE_BYTES
        if (am.isLowRamDevice) return LOW_RAM_CACHE_BYTES
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val want = if (mi.totalMem > 0) mi.totalMem / 20 else 128L * MB
        return want.coerceIn(64L * MB, 384L * MB).toInt()
    }

    /** 低内存设备（`isLowRamDevice`）的额度：只求当前这一屏不闪白 */
    const val LOW_RAM_CACHE_BYTES = 64 * 1024 * 1024

    /**
     * 退到后台/系统吃紧时缩到的额度（两模式共用；模式1 的背景标签页早就是这个数）。
     * 够留住刚才那一屏的一两页，切回来是缓存命中、不闪白，又不至于让进程成为 LMK 的首选目标。
     */
    const val BACKGROUND_CACHE_BYTES = 32 * 1024 * 1024

    private const val MB = 1024L * 1024

    /**
     * 低清档（≤ [PREVIEW]）单独一小格，**额外的，不从 [cacheBytes] 里切**
     * （模式2 的 `PageFetcher` 用）。
     *
     * 为什么必须额外加而不是切：目标档一张就 26~47MB，切走一块就可能连一张横屏页图都装不稳
     * （2026-08-29 第一版就是这么把事情弄反的：85MB 里切走 32MB，目标档只剩 53MB）。
     * 低清档一张才 6.6MB，单留 24MB 能同时兜住三四页：**切回刚才那篇立刻有画面**，
     * 目标档随后覆盖（正是 `PageFetcher` 两趟取图本来的路径）。它小且专用，不随设备内存浮动。
     */
    fun previewCacheBytes(): Int = 24 * 1024 * 1024

    /**
     * **压缩字节**（Mac 回的 JPEG 原样）的缓存额度：一页才 200~600KB，32MB 就能装几十页、
     * 好几篇文档，所以位图被挤掉之后**至少不必再回 Mac 要一次**（省掉「等 Mac + 下载」那 300~450ms，
     * 剩下的解码 450~770ms 是躲不掉的）。与位图那两格是两回事，别混在一起记账。
     */
    fun rawCacheBytes(): Int = 32 * 1024 * 1024
}
