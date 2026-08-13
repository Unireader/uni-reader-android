package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.xvan.unireader.shared.PageWidths
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 页图获取：HTTP GET `http://host:8770/page.png?i=N&v=docV&w=宽` → Bitmap
 * （token 不用带，Mac 该路由不校验）。cb 在后台线程回调，调用方自行切主线程。
 *
 * ### 宽度是平板说了算（2026-08-13）
 * 从前这里不带 `w`，Mac 一律按写死的 1600px 出图，于是 Pad 6 横屏（视口 2880px）要把它放大
 * 1.8 倍才铺满，字发虚——而同一台机器上模式1 是按 `pw()` 真宽渲的。现在把 `PageCanvasView`
 * 算好的宽度归档（[PageWidths.snap]）后报上去。档位归一放在**客户端**做，Mac 那边也会再夹一次：
 * 两边用同一张阶梯，URL 才稳定、两边的缓存才都命中。
 *
 * ### 两趟取图（[fetch] 的 cb 会被调用两次）
 * 分辨率提上去之后单页到手要 0.9~1.2s（实测：等 Mac 300ms + 解码 450~770ms），一直盯着灰色
 * 占位块很难受。所以先要一张 [PageWidths.PREVIEW] 档的**低清**立刻贴上，再要目标档换掉。
 *
 * - **cb 可能被调用两次**（低清一次、高清一次），调用方要能接受后到的覆盖先到的。
 *   `PageCanvasView.setPageImage` 天然满足：它就是 `images[i] = bmp; invalidate()`。
 * - 高清若先到（缓存命中/网络抖动），低清那趟**不再回调**（`hiDone` 判据），
 *   否则会出现「已经清楚了又变糊」。
 * - 缓存里有任何更低的档位就**零网络**先贴（缩放换档时特别值），不必再去要 PREVIEW。
 *
 * ⚠️ 这里刻意**没有**上第三方图片库（Glide/Coil/Fresco，2026-08-13 评估）：解码 30MB 位图这件事
 * 本身谁来做都一样贵，库能给的复用池/自动取消是次要收益，而代价是拉一串传递依赖（Coil 还要协程，
 * 与本项目「刻意零协程」冲突）。真正的结构性解法是 `BitmapRegionDecoder` 只解可见区域——
 * 那是下一步，要动 `shared/PageCanvasView` 的绘制路径，单独排。
 *
 * 缓存**按字节**（同模式1 的 `PdfSource`）：一张 2381px 宽的页图 ≈ 30MB，从前这里是
 * `LruCache(16)` 按**张数**记的 = 放任 700MB。见 [PageWidths.defaultCacheBytes]。
 */
class PageFetcher(private val host: String) {

    private val client = OkHttpClient()

    private val cache = object : LruCache<String, Bitmap>(PageWidths.defaultCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        // 一张 2381 宽的页图就是几十 MB，额度够装几页直接决定滚动时闪不闪白；堆上限还随机型变。
        // 出问题时第一件事就是看这行，别再靠猜。
        Log.i(
            TAG,
            "页图缓存额度 ${cache.maxSize() / 1024 / 1024}MB" +
                "（堆上限 ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB）",
        )
    }

    /** 换文档时清缓存（旧 v 的页图全部作废） */
    fun clear() = cache.evictAll()

    /**
     * 取第 [index] 页、宽度约 [widthPx] 的页图。**[cb] 可能被调用两次**（先低清后高清），见类注释。
     */
    fun fetch(index: Int, v: String, widthPx: Int, cb: (Bitmap?) -> Unit) {
        val target = PageWidths.snap(widthPx)
        cache.get(key(v, index, target))?.let { cb(it); return }

        // 高清那趟一旦到货就不再让低清覆盖（「已经清楚了又变糊」比慢更难受）
        val hiDone = AtomicBoolean(false)

        // ① 低清占位。缓存里有更低的档位就零网络直接贴，否则去要 PREVIEW 档。
        val preview = PageWidths.previewFor(target)
        if (preview > 0) {
            val ready = PageWidths.stepsBelow(target).firstNotNullOfOrNull { cache.get(key(v, index, it)) }
            if (ready != null) cb(ready)
            else load(index, v, preview) { bmp -> if (bmp != null && !hiDone.get()) cb(bmp) }
        }

        // ② 目标档
        load(index, v, target) { bmp -> hiDone.set(true); cb(bmp) }
    }

    private fun key(v: String, index: Int, w: Int) = "$v/$index@$w"

    /** 单趟：HTTP → 解码 → 进缓存 → 回调（后台线程） */
    private fun load(index: Int, v: String, w: Int, cb: (Bitmap?) -> Unit) {
        Thread {
            // 页图到手的耗时账（对端那半在 Mac 的 `PadLog`，见 `../Sources/UniReaderApp.swift`）：
            // 「等 Mac」= 发出到响应头回来（含 Mac 串行服务队列的排队 + 渲染），
            // 「下载」「解码」分开记——它俩的治法完全不同（前者削字节，后者削像素/位深）。
            val t0 = SystemClock.uptimeMillis()
            var tHead = t0
            var tBody = t0
            var bytes = 0
            val bmp = try {
                val url = "http://$host:8770/page.png?i=$index" +
                    "&v=${java.net.URLEncoder.encode(v, "UTF-8")}&w=$w"
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    tHead = SystemClock.uptimeMillis()
                    if (resp.isSuccessful) {
                        // 先整块读进内存再解码：这样「下载」与「解码」才分得开。
                        val raw = resp.body.bytes()
                        bytes = raw.size
                        tBody = SystemClock.uptimeMillis()
                        BitmapFactory.decodeByteArray(raw, 0, raw.size)
                    } else null
                }
            } catch (_: Exception) {
                null
            }
            val t1 = SystemClock.uptimeMillis()
            Log.i(
                TAG,
                "页图 #$index@$w 等 Mac ${tHead - t0}ms + 下载 ${tBody - tHead}ms + 解码 ${t1 - tBody}ms" +
                    " = ${t1 - t0}ms，${bytes / 1024}KB → " +
                    (bmp?.let { "${it.width}x${it.height} ${it.byteCount / 1024 / 1024}MB" } ?: "失败"),
            )
            if (bmp != null) cache.put(key(v, index, w), bmp)
            cb(bmp)
        }.start()
    }

    private companion object {
        const val TAG = "UniReader/PageFetch"
    }
}
