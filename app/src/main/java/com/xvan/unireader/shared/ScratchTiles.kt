package com.xvan.unireader.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 草稿纸 / 画板笔记的笔迹**分块位图缓存**（2026-09-26，用户报「画板笔记多了拖动掉帧」）。
 *
 * 以前每一帧都把视口里的笔迹逐条 `drawPath`：几千条压感轮廓，GPU 每条都要走一遍模板缓冲，
 * 实测拖动时一帧 50~500ms。现在笔迹按「块缩放下的像素网格」切成 [T]px 的方块，在后台线程里用
 * 软件画进位图；平移时每帧只贴几十张位图，与笔迹多少无关。
 *
 * - 块坐标 = 画布坐标 × 块缩放 `zk` × density，第 (i,j) 块覆盖像素 `[i·T,(i+1)·T)`。平移不动块，只动贴的位置。
 * - 缩放变了（捏合松手 / 适应内容 / 回中）换一套块；新块出来之前旧缩放那套按比例拉伸顶着，再没有就
 *   直接画笔迹（改之前那条路，只限在缺的那块里；笔迹多时不直接画，宁可先空着等块，见 [DIRECT_MAX_PTS]）。
 *   捏合进行中不换块，整套按比例拉伸。
 * - 笔迹变了：**新加的笔迹当场画进已有的块**（收笔那一帧就在，不闪）；删掉 / 切段的笔迹让它压到的块
 *   过期，后台重画，新图出来之前旧图照贴（只替换不清空）。
 * - 块是透明底、只有笔迹：荧光笔的正片叠底只在笔迹之间生效，压在图片 / 页面背景线上是普通叠加
 *   （白纸上两者一样）。
 *
 * 全部方法只在主线程调；后台线程只碰 [Snap]（不可变快照）和自己那份 [InkRenderer]。
 */
internal class ScratchTiles(
    private val density: Float,
    /** 画布上的笔迹全集（画布自己那份 ArrayList，只在主线程改） */
    private val source: List<Stroke>,
    private val onTileReady: () -> Unit,
) {

    private class Tile(val i: Int, val j: Int) {
        var bmp: Bitmap? = null
        /** 内容版本：压到这块的笔迹变了就 +1 */
        var ver = 0
        /** 当前 bmp 是按哪个版本画的；-1 = 还没图可贴（空块也算有图：bmp = null、doneVer ≥ 0） */
        var doneVer = -1
        /** 比这更早的后台结果不收：收了会把刚当场画进去的新笔迹盖掉 */
        var minAccept = 0
        /** 最近一次排进后台的版本（后台排到旧版本就跳过，连续擦除不会排成长队） */
        @Volatile var queuedVer = -1
        @Volatile var dropped = false
        val shown get() = doneVer >= 0
    }

    /** 某一刻的笔迹全集 + 各自的包围盒（后台只读） */
    private class Snap(val list: List<Stroke>, val boxes: FloatArray)

    private val main = Handler(Looper.getMainLooper())

    /**
     * 后台出图：几条线程并行（刚打开 / 缩放后要一口气出几十块，一条线程要等很久）。空闲 2 秒线程自己退出，
     * 不用管生命周期。InkRenderer 不是线程安全的，所以备 [WORKERS] 份，谁干活谁借一份、干完还回来——
     * 放在池子里而不是跟着线程走，线程退出后几何缓存还在，下次出图不用重建轮廓。
     */
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        WORKERS, WORKERS, 2, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "scratch-tiles").apply { priority = Thread.NORM_PRIORITY - 1 } }
        .apply { allowCoreThreadTimeOut(true) }
    private val inks = java.util.concurrent.ArrayBlockingQueue<InkRenderer>(WORKERS).apply {
        repeat(WORKERS) { add(InkRenderer(density)) }
    }
    private val paintInk = InkRenderer(density)   // 主线程往已有块里补画新笔迹专用
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val clip = RectF()

    private var zk = 0f
    private var cur = HashMap<Long, Tile>()
    private var oldZk = 0f
    private var old = HashMap<Long, Tile>()
    private var snap: Snap? = null

    // ---------- 包围盒（按对象身份缓存：Stroke 不可变，同一个对象的盒子永远不变） ----------

    private val boxes = IdentityHashMap<Stroke, FloatArray>()

    /** 画布坐标包围盒 `[x0,y0,x1,y1]`，已含线宽余量；没有点 = 反向盒子（与谁都不相交） */
    fun box(s: Stroke): FloatArray = boxes.getOrPut(s) {
        if (s.pts.isEmpty()) return@getOrPut floatArrayOf(1f, 1f, 0f, 0f)
        var a0 = Float.MAX_VALUE; var b0 = Float.MAX_VALUE
        var a1 = -Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
        for (p in s.pts) {
            if (p.x < a0) a0 = p.x
            if (p.x > a1) a1 = p.x
            if (p.y < b0) b0 = p.y
            if (p.y > b1) b1 = p.y
        }
        val m = s.pen.w * 1.5f + 4f   // 线宽余量（压感加粗 / 铅笔抖动都在里面）
        floatArrayOf(a0 - m, b0 - m, a1 + m, b1 + m)
    }

    // ---------- 笔迹变化 ----------

    /** 整篇换了（开另一张纸）：块、盒子、快照全扔 */
    fun reset() {
        dropAll(cur); dropAll(old)
        cur = HashMap(); old = HashMap()
        zk = 0f; oldZk = 0f
        boxes.clear()
        snap = null
    }

    /**
     * 笔迹增删（全集已经改好）。新加的少时当场补画进已有的块；一次变很多（整篇重读、插页后整体挪位）
     * 就整套块作废——旧图上的笔迹位置可能全不对了，宁可直接画一帧，也不贴错位的旧图。
     */
    fun changed(removed: List<Stroke>, added: List<Stroke>) {
        if (removed.isEmpty() && added.isEmpty()) { pruneBoxes(); return }   // 模式2 回推：内容没变、对象全是新的
        snap = null
        dropAll(old); old = HashMap(); oldZk = 0f   // 旧缩放那套不再跟进，直接不要了
        if (removed.size + added.size > BULK) {
            dropAll(cur); cur = HashMap()
            for (s in removed) boxes.remove(s)
            pruneBoxes()
            return
        }
        for (s in removed) {
            forTiles(box(s)) { it.ver++ }
            boxes.remove(s)
        }
        for (s in added) paintIn(s)
        pruneBoxes()
    }

    private fun paintIn(s: Stroke) {
        val b = box(s)
        val k = zk * density
        forTiles(b) { t ->
            t.ver++
            t.minAccept = t.ver
            if (!t.shown) return@forTiles
            val bmp = t.bmp ?: newBitmap()?.also { t.bmp = it } ?: run { t.doneVer = -1; return@forTiles }
            paintInk.drawScratchStroke(Canvas(bmp), s, t.i * T / k, t.j * T / k, zk)
            if (t.doneVer == t.ver - 1) t.doneVer = t.ver
        }
    }

    private inline fun forTiles(b: FloatArray, f: (Tile) -> Unit) {
        if (zk <= 0f || b[2] < b[0]) return
        val k = zk * density
        val i0 = floor(b[0] * k / T).toInt()
        val i1 = floor(b[2] * k / T).toInt()
        val j0 = floor(b[1] * k / T).toInt()
        val j1 = floor(b[3] * k / T).toInt()
        if ((i1 - i0 + 1).toLong() * (j1 - j0 + 1) > cur.size) {
            for (t in cur.values) if (t.i in i0..i1 && t.j in j0..j1) f(t)
        } else {
            for (i in i0..i1) for (j in j0..j1) cur[key(i, j)]?.let(f)
        }
    }

    /** 盒子缓存只留还在全集里的（擦除切段会不断造出新对象） */
    private fun pruneBoxes() {
        if (boxes.size <= source.size * 2 + 256) return
        val keep = IdentityHashMap<Stroke, FloatArray>(source.size)
        for (s in source) boxes[s]?.let { keep[s] = it }
        boxes.clear()
        boxes.putAll(keep)
    }

    // ---------- 绘制 ----------

    /**
     * 画视口里的笔迹。[direct] = 没有块可贴时直接画笔迹（给屏幕矩形 = 只画那一块；null = 整个视口）。
     */
    fun draw(
        c: Canvas, ox: Float, oy: Float, zoom: Float, w: Int, h: Int, pinching: Boolean,
        direct: (Canvas, RectF?) -> Unit,
    ) {
        if (source.isEmpty() || w <= 0 || h <= 0 || zoom <= 0f) return
        if (!pinching && (zk <= 0f || abs(zoom - zk) > zoom * 0.001f)) switchZoom(zoom)
        if (zk <= 0f) { direct(c, null); return }   // 一上来就在捏合，还没有块
        val k = zk * density
        val s = zoom / zk
        val unit = abs(s - 1f) < 1e-4f
        // 块像素坐标下的视口左上角；不缩放时取整，块贴在整像素上才不会被双线性过滤糊掉
        val bx = if (unit) (ox * k).roundToInt().toFloat() else ox * k
        val by = if (unit) (oy * k).roundToInt().toFloat() else oy * k
        val i0 = floor(bx / T).toInt()
        val i1 = floor((bx + w / s) / T).toInt()
        val j0 = floor(by / T).toInt()
        val j1 = floor((by + h / s) / T).toInt()
        if ((i1 - i0 + 1) * (j1 - j0 + 1) > MAX_VISIBLE) {   // 捏合缩得太小：块太多，不如直接画
            direct(c, null)
            return
        }
        if (!pinching) prefetch(i0 - 1, i1 + 1, j0 - 1, j1 + 1, (i0 + i1) / 2f, (j0 + j1) / 2f)

        var anyShown = false
        for (i in i0..i1) for (j in j0..j1) if (cur[key(i, j)]?.shown == true) { anyShown = true; break }
        // 没块可贴时能不能直接画：笔迹少才行（见 DIRECT_MAX_PTS）；多了宁可先空着等块
        var light: Boolean? = null
        fun isLight() = light ?: lightInView(ox, oy, zoom, w, h).also { light = it }
        if (!anyShown && old.isEmpty()) {
            if (waitSince == 0L) startWait()
            if (isLight()) direct(c, null)   // 刚打开、一块都还没出来：笔迹少就整片直接画
            return
        }
        var allShown = true
        for (i in i0..i1) for (j in j0..j1) {
            val left = (i * T - bx) * s
            val top = (j * T - by) * s
            dst.set(left, top, left + T * s, top + T * s)
            val t = cur[key(i, j)]
            if (t != null && t.shown) {
                t.bmp?.let { c.drawBitmap(it, null, dst, bmpPaint) }
            } else {
                allShown = false
                // 旧缩放那套能整块顶上就用它；顶不全时笔迹少就直接画，多了就把有的那几块先贴上
                if (!drawOld(c, dst, ox, oy, zoom, partial = false)) {
                    if (isLight()) direct(c, dst) else drawOld(c, dst, ox, oy, zoom, partial = true)
                }
            }
        }
        if (allShown) {
            if (!pinching) endWait((i1 - i0 + 1) * (j1 - j0 + 1))
            if (old.isNotEmpty()) { dropAll(old); old = HashMap(); oldZk = 0f }
        } else if (waitSince == 0L && !pinching) {
            startWait()
        }
    }

    /** 视口里的笔迹点数是否少于 [DIRECT_MAX_PTS]（按缓存的包围盒粗筛，超了就停） */
    private fun lightInView(ox: Float, oy: Float, zoom: Float, w: Int, h: Int): Boolean {
        val k = zoom * density
        val x1 = ox + w / k
        val y1 = oy + h / k
        var n = 0
        for (st in source) {
            val b = box(st)
            if (b[2] < ox || b[0] > x1 || b[3] < oy || b[1] > y1) continue
            n += st.pts.size
            if (n > DIRECT_MAX_PTS) return false
        }
        return true
    }

    /**
     * 用旧缩放那套块顶住 [r] 这片屏幕。[partial] = false 时有一块缺就什么都不画、返回 false；
     * true 时有几块贴几块。
     */
    private fun drawOld(c: Canvas, r: RectF, ox: Float, oy: Float, zoom: Float, partial: Boolean): Boolean {
        if (oldZk <= 0f) return false
        val k = oldZk * density
        val s = zoom / oldZk
        val bx = ox * k
        val by = oy * k
        val i0 = floor((bx + r.left / s) / T).toInt()
        val i1 = floor((bx + (r.right - 0.01f) / s) / T).toInt()
        val j0 = floor((by + r.top / s) / T).toInt()
        val j1 = floor((by + (r.bottom - 0.01f) / s) / T).toInt()
        if (!partial) for (i in i0..i1) for (j in j0..j1) if (old[key(i, j)]?.shown != true) return false
        c.save()
        c.clipRect(r)
        for (i in i0..i1) for (j in j0..j1) {
            val t = old[key(i, j)] ?: continue
            val bmp = t.bmp ?: continue
            val left = (i * T - bx) * s
            val top = (j * T - by) * s
            clip.set(left, top, left + T * s, top + T * s)
            c.drawBitmap(bmp, null, clip, bmpPaint)
        }
        c.restore()
        return true
    }

    private fun switchZoom(zoom: Float) {
        dropAll(old)
        // 只有这套块里真有图时才留作过渡（连着换两次缩放，留下的是上一套还没出图的空架子没用）
        if (cur.values.any { it.shown }) { old = cur; oldZk = zk } else { dropAll(cur); old = HashMap(); oldZk = 0f }
        cur = HashMap()
        zk = zoom
    }

    /** 视口外扩一圈里缺图 / 过期的块排进后台（离视口中心近的先画）；块太多时淘汰圈外的 */
    private fun prefetch(i0: Int, i1: Int, j0: Int, j1: Int, ci: Float, cj: Float) {
        val need = ArrayList<Tile>()
        for (i in i0..i1) for (j in j0..j1) {
            val t = cur.getOrPut(key(i, j)) { Tile(i, j) }
            if (t.doneVer != t.ver && t.queuedVer != t.ver) need.add(t)
        }
        if (need.isNotEmpty()) {
            need.sortBy { (it.i - ci) * (it.i - ci) + (it.j - cj) * (it.j - cj) }
            val sn = snapshot()
            for (t in need) enqueue(t, sn)
        }
        if (cur.size > MAX_TILES) {
            val it = cur.values.iterator()
            while (it.hasNext()) {
                val t = it.next()
                if (t.i < i0 || t.i > i1 || t.j < j0 || t.j > j1) { t.dropped = true; t.bmp = null; it.remove() }
            }
        }
    }

    private fun snapshot(): Snap {
        snap?.let { return it }
        val list = ArrayList(source)
        val b = FloatArray(list.size * 4)
        for ((n, s) in list.withIndex()) System.arraycopy(box(s), 0, b, n * 4, 4)
        return Snap(list, b).also { snap = it }
    }

    private fun enqueue(t: Tile, sn: Snap) {
        val ver = t.ver
        t.queuedVer = ver
        val z = zk
        pool.execute {
            if (t.dropped || t.queuedVer != ver) return@execute   // 淘汰了 / 后面排着更新的版本
            var failed = false
            val ink = inks.take()
            val t0 = android.os.SystemClock.uptimeMillis()
            val bmp = try {
                render(t, z, sn, ink)
            } catch (e: OutOfMemoryError) {
                failed = true; null
            } finally {
                inks.put(ink)
            }
            renderMs.addAndGet(android.os.SystemClock.uptimeMillis() - t0)
            rendered.incrementAndGet()
            main.post { finish(t, ver, bmp, failed) }
        }
    }

    // ---- 计时打点（logcat 标签 UniReader/Tiles）：打开 / 换缩放之后，视口里的块多久全部出齐 ----
    private var waitSince = 0L
    private val renderMs = java.util.concurrent.atomic.AtomicLong()
    private val rendered = java.util.concurrent.atomic.AtomicInteger()

    private fun startWait() {
        waitSince = android.os.SystemClock.uptimeMillis()
        renderMs.set(0)
        rendered.set(0)
    }

    private fun endWait(visible: Int) {
        if (waitSince == 0L) return
        android.util.Log.i(
            "UniReader/Tiles",
            "视口 $visible 块出齐 ${android.os.SystemClock.uptimeMillis() - waitSince}ms，" +
                "后台共画 ${rendered.get()} 块、累计 ${renderMs.get()}ms，笔迹 ${source.size} 条，zk=$zk",
        )
        waitSince = 0L
    }

    private fun finish(t: Tile, ver: Int, bmp: Bitmap?, failed: Boolean) {
        if (t.dropped) return
        if (failed) { t.queuedVer = -1; return }   // 这块先直接画，下次再排
        if (ver < t.minAccept || ver <= t.doneVer) return
        t.bmp = bmp
        t.doneVer = ver
        onTileReady()
    }

    /** 后台：把压到这一块的笔迹画进一张新位图（一条都没有 = null，空块） */
    private fun render(t: Tile, z: Float, sn: Snap, tileInk: InkRenderer): Bitmap? {
        val k = z * density
        val x0 = t.i * T / k
        val y0 = t.j * T / k
        val x1 = (t.i + 1) * T / k
        val y1 = (t.j + 1) * T / k
        var bmp: Bitmap? = null
        var c: Canvas? = null
        val b = sn.boxes
        for ((n, s) in sn.list.withIndex()) {
            val o = n * 4
            if (b[o + 2] < x0 || b[o] > x1 || b[o + 3] < y0 || b[o + 1] > y1) continue
            if (c == null) {
                bmp = Bitmap.createBitmap(T, T, Bitmap.Config.ARGB_8888)
                c = Canvas(bmp)
            }
            tileInk.drawScratchStroke(c, s, x0, y0, z)
        }
        return bmp
    }

    private fun newBitmap(): Bitmap? =
        try { Bitmap.createBitmap(T, T, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { null }

    private fun dropAll(m: HashMap<Long, Tile>) {
        for (t in m.values) { t.dropped = true; t.bmp = null }
    }

    /** 视图离开窗口：还没开始画的任务作废（回来时按需重排）；线程空闲 2 秒自己退出 */
    fun release() {
        pool.queue.clear()
        for (t in cur.values) if (t.queuedVer != t.doneVer) t.queuedVer = -1
        for (t in old.values) if (t.queuedVer != t.doneVer) t.queuedVer = -1
    }

    private fun key(i: Int, j: Int) = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)

    companion object {
        /** 块边长（px）：一块 1MB；平板横屏一屏 6×4 块左右 */
        const val T = 512
        /** 当前缩放那套最多留几块（超了淘汰视口外一圈以外的） */
        const val MAX_TILES = 96
        /** 视口里超过这么多块（捏合缩得很小）就不贴块、直接画 */
        const val MAX_VISIBLE = 120
        /** 一次增删超过这么多条（整篇重读）：不逐条处理，整套过期重画 */
        const val BULK = 64
        /** 后台出图线程数（留两个核给界面线程与渲染线程） */
        val WORKERS = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 4)
        /**
         * 视口里的笔迹点数少于这个数才允许「没块可贴时直接画」：直接画要在主线程把轮廓现建出来，
         * 笔迹一多就是几百毫秒卡住界面——那还不如先空着、块一块块出来（2026-09-26 用户报打开时卡住半天）。
         */
        const val DIRECT_MAX_PTS = 20_000
    }
}
