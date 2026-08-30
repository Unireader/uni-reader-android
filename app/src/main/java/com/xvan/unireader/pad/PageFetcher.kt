package com.xvan.unireader.pad

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.content.ComponentCallbacks2
import android.util.LruCache
import com.xvan.unireader.shared.PageDiskCache
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
 * `LruCache(16)` 按**张数**记的 = 放任 700MB。额度见 [PageWidths.cacheBytes]。
 *
 * ### 缓存跨文档保留 + 三格（2026-08-29，用户报「切标签页每次都要重新加载 PDF 页」）
 *
 * 键里带 `v`（Mac 的 `contentHash`），两篇文档的页图本就不会串，所以**换文档不再清缓存**
 * （`PadActivity.onLayout` 从前一进新文档就 `evictAll`，切回去必然重下一遍）。
 *
 * 光不清没用，**当时的额度在算术上就不够**：横屏一张目标档页图 2880×4073×4 ≈ 47MB，而额度
 * 曾是 `maxMemory()/3`（Java 堆的 1/3）→ 小米 Pad 6 上只有 85MB，`LruCache` 里同时**只装得下一张**
 * ——换篇文档翻一页就全没了。**那个公式本身是记错账**：API 26 起位图住 native 堆、不占 Java 堆，
 * 现已改成按设备总内存算（[PageWidths.cacheBytes]，8GB → 384MB ≈ 8 张），并接上 `onTrimMemory`
 * 主动还（[setForeground] / [onTrimMemory]）。分四格，各自独立 LRU：
 *
 * | 格 | 额度 | 一张 | 作用 |
 * |---|---|---|---|
 * | 目标档位图 | [PageWidths.cacheBytes]（总内存/20，64…384MB；后台缩到 32MB） | 26~47MB | 当前这篇的即时滚动 + 邻篇的当前屏 |
 * | 低清档位图（≤ [PageWidths.PREVIEW]） | [PageWidths.previewCacheBytes]（**额外的，不从上面切**） | 6.6MB | 切回刚才那篇**立刻有画面** |
 * | 压缩字节（Mac 回的 JPEG 原样） | [PageWidths.rawCacheBytes] | 0.2~0.6MB | 位图被挤掉也**不必再回 Mac 要**，只剩解码 |
 * | **磁盘**（`PageDiskCache`，同样是压缩字节） | 512MB | 0.2~0.6MB | 跨换文档/跨重启/Mac 掉线都还在 |
 *
 * ⚠️ **第一版（同日）把低清那格从总额里切**，结果目标档只剩 53MB、比改之前还装不下，
 * 用户实测「还是会重新加载」。低清与字节两格都很小，必须是**加**上去的，别再切回来。
 *
 * 于是「切回刚才那篇」的账：目标档位图还在（384MB 装得下两三篇的当前屏）就是**零等待**；
 * 被挤掉了退一步——低清位图立刻贴 + 目标档从磁盘读几毫秒 + 解码 450~770ms，全程不惊动 Mac。
 *
 * 还嫌不够时的下一步（**先跟用户确认**，都有代价）：模式2 解码改 `Config.HARDWARE`
 * （像素进图形内存、几乎不占进程内存，但那之后页图不能再被软件 canvas 读写，且要备好分配失败的
 * 兜底）；或 `RGB_565`（每张字节减半，扫描件可能有色带）。`largeHeap` **没用**——它只抬 Java 堆。
 */
class PageFetcher(
    private val host: String,
    private val disk: PageDiskCache? = null,
    /** 目标档位图额度（字节）。按**设备总内存**算，见 [PageWidths.cacheBytes] */
    private val fullCacheBytes: Int = PageWidths.LOW_RAM_CACHE_BYTES,
) {

    private val client = OkHttpClient()

    private val cache = object : LruCache<String, Bitmap>(fullCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 低清档（≤ [PageWidths.PREVIEW]）单独一小格，**额外的**，见 [PageWidths.previewCacheBytes] */
    private val low = object : LruCache<String, Bitmap>(PageWidths.previewCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 压缩字节（Mac 回的 JPEG 原样）：位图被挤掉后至少省掉「等 Mac + 下载」那一段 */
    private val raw = object : LruCache<String, ByteArray>(PageWidths.rawCacheBytes()) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** 这个档位归哪一格：两格各自 LRU，大图挤不掉小图（切标签页立刻有画面全靠它） */
    private fun cacheOf(w: Int): LruCache<String, Bitmap> = if (w <= PageWidths.PREVIEW) low else cache

    init {
        // 一张 2381 宽的页图就是几十 MB，额度够装几页直接决定滚动时闪不闪白；堆上限还随机型变。
        // 出问题时第一件事就是看这行，别再靠猜——「装得下几张」是这个功能的全部。
        Log.i(
            TAG,
            "页图缓存额度 目标档 ${cache.maxSize() / M}MB + 低清档 ${low.maxSize() / M}MB" +
                " + 字节 ${raw.maxSize() / M}MB（Java 堆上限 ${Runtime.getRuntime().maxMemory() / M}MB" +
                "——位图不占它，API 26 起在 native 堆）",
        )
    }

    /**
     * 丢掉全部页图。**换文档不该调它**（那正是「切回刚才那篇又要重新加载」的由来，2026-08-29 修）：
     * 缓存键里带 `v`，两篇文档的页图本来就不串。留给退出/换 Mac 这类真的要清空的场合。
     */
    fun clear() {
        cache.evictAll()
        low.evictAll()
        raw.evictAll()
        disk?.clear()
    }

    /**
     * 前台/后台（`PadActivity.onResume` / `onTrimMemory`）：后台把**目标档**那格缩到背景额度，
     * 低清与磁盘两格原样留着——切回来照样立刻有画面。同模式1 的 `PdfSource.setForeground`。
     *
     * ⚠️ 额度是按设备总内存给的（可到 384MB），native 内存超支不会抛 OOM，而是**整个进程被
     * lowmemorykiller 干掉**（回来就是冷启）。所以退到后台必须主动还，别赌系统不来收。
     */
    fun setForeground(fg: Boolean) {
        val want = if (fg) fullCacheBytes else minOf(PageWidths.BACKGROUND_CACHE_BYTES, fullCacheBytes)
        if (cache.maxSize() == want) return
        cache.resize(want)   // resize 会顺手按 LRU 淘汰超出的部分
        Log.i(TAG, "目标档额度 → ${want / M}MB（前台=$fg），现存 ${cache.size() / M}MB")
    }

    /**
     * 系统要内存了（`ComponentCallbacks2`）。判据分两档，**注意常量值不是一条单调刻度**：
     * `UI_HIDDEN`(20) 及以上 = 界面已经不可见；`RUNNING_LOW`(10)/`RUNNING_CRITICAL`(15) 是
     * **还在前台**但系统吃紧——那时不能把画面上的图丢了，只砍额度的一半。
     */
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> setForeground(false)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                val half = maxOf(PageWidths.BACKGROUND_CACHE_BYTES, fullCacheBytes / 2)
                if (cache.maxSize() > half) {
                    cache.resize(half)
                    Log.i(TAG, "系统吃紧(level=$level)，目标档额度砍到 ${half / M}MB")
                }
            }
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            // 快被杀了：位图两格全放掉（磁盘与压缩字节还在，回来重解码即可）
            cache.evictAll()
            low.evictAll()
            Log.i(TAG, "系统吃紧(level=$level)，位图全部让出")
        }
    }

    /**
     * 缓存现状一行账（换文档时打一条）。**「切回来还要重下」的判据就看这行**：
     * 走之前那篇有几张目标档在格子里，回来时还剩几张。
     */
    fun stats(): String =
        "目标档 ${cache.snapshot().size}张/${cache.size() / M}MB，" +
            "低清 ${low.snapshot().size}张/${low.size() / M}MB，" +
            "字节 ${raw.snapshot().size}张/${raw.size() / 1024}KB" +
            (disk?.let { "，${it.stats()}" } ?: "")

    /**
     * 取第 [index] 页、宽度约 [widthPx] 的页图。**[cb] 可能被调用两次**（先低清后高清），见类注释。
     */
    /**
     * @param docId 非空 = 取**别的**文档的页图（参考窗，`/page.png?d=`）。缓存键、磁盘键都带上它，
     *   否则参考窗和正文的同页号会互相顶掉。空串 = 当前跟随的那篇（旧行为，一字未改）。
     */
    fun fetch(index: Int, v: String, widthPx: Int, docId: String = "", cb: (Bitmap?) -> Unit) {
        val target = PageWidths.snap(widthPx)
        cacheOf(target).get(key(v, index, target, docId))?.let {
            Log.i(TAG, "页图 #$index@$target 命中位图，零等待")
            cb(it)
            return
        }

        // 高清那趟一旦到货就不再让低清覆盖（「已经清楚了又变糊」比慢更难受）
        val hiDone = AtomicBoolean(false)

        // ① 低清占位。缓存里有更低的档位就零网络直接贴，否则去要 PREVIEW 档。
        val preview = PageWidths.previewFor(target)
        if (preview > 0) {
            val ready = PageWidths.stepsBelow(target)
                .firstNotNullOfOrNull { cacheOf(it).get(key(v, index, it, docId)) }
            if (ready != null) cb(ready)
            else load(index, v, preview, docId) { bmp -> if (bmp != null && !hiDone.get()) cb(bmp) }
        }

        // ② 目标档
        load(index, v, target, docId) { bmp -> hiDone.set(true); cb(bmp) }
    }

    private fun key(v: String, index: Int, w: Int, d: String = "") =
        if (d.isEmpty()) "$v/$index@$w" else "$d|$v/$index@$w"

    /**
     * 单趟：（字节缓存/磁盘命中则跳过 HTTP）→ 解码 → 进缓存 → 回调（后台线程）。
     *
     * 字节与磁盘两格是位图被挤掉之后的兜底：**位图没了不等于要重新回 Mac 要**。省下的是
     * 「等 Mac + 下载」（300~450ms，还占着 Mac 那条串行服务队列），解码那 450~770ms 躲不掉
     * （要躲得动像素或位深，见类注释末尾）。
     */
    private fun load(index: Int, v: String, w: Int, docId: String, cb: (Bitmap?) -> Unit) {
        Thread {
            // 页图到手的耗时账（对端那半在 Mac 的 `PadLog`，见 `../Sources/UniReaderApp.swift`）：
            // 「等 Mac」= 发出到响应头回来（含 Mac 串行服务队列的排队 + 渲染），
            // 「下载」「解码」分开记——它俩的治法完全不同（前者削字节，后者削像素/位深）。
            val k = key(v, index, w, docId)
            val t0 = SystemClock.uptimeMillis()
            var tHead = t0
            var tBody = t0
            var src = "网络"
            var cached = raw.get(k)
            if (cached != null) src = "内存字节"
            if (cached == null) {
                cached = disk?.get(k)
                if (cached != null) { src = "磁盘"; raw.put(k, cached!!) }
            }
            val fromNet = cached == null
            val bmp = try {
                if (cached == null) {
                    val url = "http://$host:8770/page.png?i=$index" +
                        "&v=${java.net.URLEncoder.encode(v, "UTF-8")}&w=$w" +
                        (if (docId.isEmpty()) "" else "&d=${java.net.URLEncoder.encode(docId, "UTF-8")}")
                    val req = Request.Builder().url(url).build()
                    client.newCall(req).execute().use { resp ->
                        tHead = SystemClock.uptimeMillis()
                        // 先整块读进内存再解码：这样「下载」与「解码」才分得开。
                        if (resp.isSuccessful) cached = resp.body.bytes()
                    }
                }
                tBody = SystemClock.uptimeMillis()
                cached?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } catch (_: Exception) {
                null
            }
            val t1 = SystemClock.uptimeMillis()
            val bytes = cached?.size ?: 0
            Log.i(
                TAG,
                if (fromNet) {
                    "页图 #$index@$w 等 Mac ${tHead - t0}ms + 下载 ${tBody - tHead}ms + 解码 ${t1 - tBody}ms" +
                        " = ${t1 - t0}ms，${bytes / 1024}KB → "
                } else {
                    "页图 #$index@$w $src 命中，取 ${tBody - t0}ms + 解码 ${t1 - tBody}ms" +
                        " = ${t1 - t0}ms，${bytes / 1024}KB → "
                } + (bmp?.let { "${it.width}x${it.height} ${it.byteCount / M}MB" } ?: "失败"),
            )
            if (bmp != null) {
                cacheOf(w).put(k, bmp)
                if (fromNet) cached?.let { raw.put(k, it); disk?.put(k, it) }
            }
            cb(bmp)
        }.start()
    }

    private companion object {
        const val TAG = "UniReader/PageFetch"
        const val M = 1024 * 1024
    }
}
