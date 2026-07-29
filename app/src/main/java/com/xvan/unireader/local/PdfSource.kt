package com.xvan.unireader.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.xvan.unireader.shared.PageImageSource
import io.legere.pdfiumandroid.PdfiumCore
import java.io.Closeable
import java.io.File
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 本机 PDF 渲染（模式1）：Pdfium 出图 + 按字节数的 LRU 缓存 + 单后台线程（后进先出）。
 *
 * 选 Pdfium 而非内置 `PdfRenderer` 的理由见 `ANDROID-STANDALONE-PLAN.md §4`：后者只能出图，
 * 没有文字层也没有书签，下一版的搜索/选择/目录会全部卡死。
 */
class PdfSource(
    ctx: Context,
    private val file: File,
    /** 位图缓存上限（字节）。默认取可用堆的 1/3，上限 192MB（平板内存口径，见 §7） */
    cacheBytes: Int = defaultCacheBytes(),
) : PageImageSource, Closeable {

    companion object {
        const val TAG = "UniReader/Pdf"

        /** 宽度档位：连续缩放时不为每个像素宽度各渲一张，档位内复用（同 Mac 的分级思路） */
        val WIDTH_STEPS = intArrayOf(480, 720, 1080, 1440, 2160, 2880)

        fun defaultCacheBytes(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            return min(192L * 1024 * 1024, heap / 3).toInt()
        }

        /**
         * 页的显示尺寸（pt，已含旋转换边），**逐字对齐 Mac 端 `PageBitmap.displaySize`**：
         * CropBox 宽高都 >0 就用 CropBox，否则用 MediaBox；再按页旋转换边。
         *
         * 这是 §9.1 那条「最要命」的一致性要求：两端若取了不同的 box，同一份 PDF 算出的宽高比
         * 就不同 → 归一化坐标换算出的位置不同 → **Mac 上写的笔迹在平板上整体偏移/缩放**，
         * 而且是「看着差一点点、说不清哪错了」的那种 bug。所以这里不用 Pdfium 的
         * `getPageWidthPoint()`（它的口径是它自己的事），而是自己读 box 算。
         */
        fun displaySize(crop: RectF?, media: RectF?, rotation: Int): FloatArray {
            fun w(r: RectF?) = if (r == null) 0f else abs(r.right - r.left)
            fun h(r: RectF?) = if (r == null) 0f else abs(r.bottom - r.top)
            var pw = w(crop)
            var ph = h(crop)
            if (pw <= 0f || ph <= 0f) { pw = w(media); ph = h(media) }
            val rot = ((rotation % 360) + 360) % 360
            return if (rot % 180 == 0) floatArrayOf(pw, ph) else floatArrayOf(ph, pw)
        }
    }

    private val core = PdfiumCore(ctx)
    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val doc = core.newDocument(pfd)

    /** Pdfium 的文档/页对象跨线程要串行化访问；渲染本身也是 CPU 密集，一条工作线程足够 */
    private val docLock = Any()

    // 注意是函数不是属性：pdfiumandroid 里声明的是 `fun getPageCount()`，openPage 也返回可空
    val pageCount: Int = synchronized(docLock) { doc.getPageCount() }

    /** 逐页显示尺寸（pt）。打开时一次性取全（同 §7：塞进同一个 pagesWH） */
    val pageSizes: List<FloatArray> = readAllPageSizes()

    // ---------- 缓存与工作线程 ----------

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private class Req(val page: Int, val widthPx: Int, val gen: Int, val cb: (Bitmap?) -> Unit)

    private val queue = ArrayDeque<Req>()
    private var generation = 0
    private var closed = false

    /**
     * 后进先出：用户快速滚动时，最新请求的那页才是他正在看的。先进先出会让屏幕等着把
     * 一串已经滚过去的页渲完（Mac 端缩放掉帧那次的同类教训，见 HISTORY.md 0.1.5）。
     */
    private val worker = Thread({ workLoop() }, "pdf-render").apply {
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
        start()
    }

    override fun request(page: Int, widthPx: Int, cb: (Bitmap?) -> Unit) {
        if (page < 0 || page >= pageCount) { cb(null); return }
        val w = snapWidth(widthPx)
        cache.get(key(page, w))?.let { cb(it); return }   // 命中即同步返回，不排队
        synchronized(queue) {
            if (closed) { cb(null); return }
            // 同页同档位的重复请求去掉旧的那条（滚动时同一页会被反复请求）
            queue.removeAll { it.page == page && it.widthPx == w }
            queue.addLast(Req(page, w, generation, cb))
            (queue as Object).notifyAll()
        }
    }

    override fun clear() {
        synchronized(queue) {
            generation++          // 在途结果作废
            queue.clear()
        }
        cache.evictAll()
    }

    override fun close() {
        synchronized(queue) {
            closed = true
            queue.clear()
            (queue as Object).notifyAll()
        }
        cache.evictAll()
        worker.interrupt()
        synchronized(docLock) {
            runCatching { doc.close() }
            runCatching { pfd.close() }
        }
        Log.i(TAG, "关闭 ${file.name}")
    }

    private fun key(page: Int, widthPx: Int) = "$page@$widthPx"

    /** 归到最近的不小于目标宽度的档位（超过最大档位就用最大档位，别无限放大内存） */
    private fun snapWidth(widthPx: Int): Int {
        val w = max(1, widthPx)
        for (s in WIDTH_STEPS) if (s >= w) return s
        return WIDTH_STEPS.last()
    }

    private fun workLoop() {
        while (true) {
            val r: Req = synchronized(queue) {
                while (queue.isEmpty() && !closed) {
                    try {
                        (queue as Object).wait()
                    } catch (e: InterruptedException) {
                        return
                    }
                }
                if (closed) return
                queue.removeLast()      // LIFO
            }
            if (r.gen != generation) { r.cb(null); continue }
            val bmp = runCatching { render(r.page, r.widthPx) }
                .onFailure { Log.w(TAG, "第 ${r.page} 页渲染失败", it) }
                .getOrNull()
            if (bmp != null && r.gen == generation) cache.put(key(r.page, r.widthPx), bmp)
            r.cb(if (r.gen == generation) bmp else null)
        }
    }

    // ---------- 渲染 ----------

    /** 某页在给定像素宽度下的位图高度（几何层排版要先知道高度，不必等位图渲出来） */
    fun pixelHeight(page: Int, widthPx: Int): Int {
        val s = pageSizes.getOrNull(page) ?: return 0
        if (s[0] <= 0f) return 0
        return (widthPx * s[1] / s[0]).roundToInt().coerceAtLeast(1)
    }

    private fun render(page: Int, widthPx: Int): Bitmap? {
        val h = pixelHeight(page, widthPx)
        if (h <= 0) return null
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        // PDF 页面本身是透明底，白底要自己铺——不铺的话夜间反色与叠墨迹都会出鬼影
        Canvas(bmp).drawColor(Color.WHITE)
        synchronized(docLock) {
            val p = doc.openPage(page) ?: run {
                Log.w(TAG, "openPage($page) 返回 null")
                return null
            }
            p.use { it.renderPageBitmap(bmp, 0, 0, widthPx, h, true, false) }
        }
        return bmp
    }

    private fun readAllPageSizes(): List<FloatArray> = synchronized(docLock) {
        val out = ArrayList<FloatArray>(pageCount)
        for (i in 0 until pageCount) {
            val s = runCatching {
                doc.openPage(i)?.use { p ->
                    displaySize(
                        crop = runCatching { p.getPageCropBox() }.getOrNull(),
                        media = runCatching { p.getPageMediaBox() }.getOrNull(),
                        rotation = runCatching { p.getPageRotation() }.getOrDefault(0),
                    )
                }
            }.onFailure { Log.w(TAG, "第 $i 页取尺寸失败，按 0 记", it) }.getOrNull()
            out.add(s ?: floatArrayOf(0f, 0f))
        }
        Log.i(TAG, "${file.name}：$pageCount 页，尺寸表已就绪")
        return out
    }

    /**
     * 逐页打印 `宽×高 / 宽高比`，用来和 Mac 端比对（§9.1 的硬验收项）。
     * Mac 侧对照脚本：`tools/dump-page-sizes.swift`。
     */
    fun logPageSizes(tag: String = TAG) {
        for ((i, s) in pageSizes.withIndex()) {
            val ratio = if (s[0] > 0f) s[1] / s[0] else 0f
            Log.i(tag, "PAGESIZE $i ${"%.4f".format(s[0])} ${"%.4f".format(s[1])} ${"%.6f".format(ratio)}")
        }
    }
}
