package com.xvan.unireader.pad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 输入板核心视图（行为对齐 capture.html 整体移植）：
 * - 连续页面列几何：layout → dispH/offY/totalH（GAP=8），scrollY/scrollX 为唯一滚动真源
 * - 笔（stylus）= 落墨/擦除/翻页平移 + hover；手指 = 单指平移（8px 死区 + 松手惯性）+ 双指捏合缩放（纯本地）
 *   笔在写时忽略手指；getTouchMajor()>60px 手掌忽略
 * - 笔迹以 Mac 回传 strokes 为唯一真源；本地 cur 半笔即时回显，收 strokes 后清；inkCancel 撤半笔
 * - 渲染公式 = PenBrushType.strokeWidth 的 JS 版（fountain 系数 1.15）+ 二次贝塞尔中点平滑 + 起笔圆点
 *   （铅笔多道抖动纹理不做——Mac 端独有，capture.html 实时反馈同样简化）
 * - 滚动上报照 emitScroll：视口顶所在页 + 页内 frac，16ms 节流（等价 rAF）；◀▶ turn() 滚到相邻页顶
 * - 收 viewport → applyViewport（书写中忽略、force 绕 seq、先停惯性）
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun sendRel(body: ByteArray)
        fun sendUnrel(body: ByteArray)
        fun onInkEndSent()            // e2e 计时起点（ink end 发出时刻）
        fun onMoveFrame()             // mv/s 计数（每发一帧 ink/erase move）
        fun onToolChanged()           // 本地切模式/切笔 → MainActivity 发 WS mode+pen
        fun onHudChanged()            // 页码/缩放/工具变化 → 顶栏刷新
        fun requestImage(page: Int)   // 页图按需预取（可见区 + 上下一屏）
    }

    var listener: Listener? = null

    companion object {
        const val GAP = 8f          // 页间距(px)
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 5f
        const val PALM = 60f        // 手掌接触阈值(px)
        const val DEAD = 8f         // 平移死区(px)
        const val ERASE_R = 18f     // 橡皮命中半径(px)

        val MODE_LABELS = listOf("笔记", "擦除", "翻页")

        /** 内置兜底笔（Mac PenPresets.defaults；首连前用，pens 消息到达后整体替换） */
        val FALLBACK_PENS = listOf(
            WireCodec.Pen(24, 90, 210, 0.95f, 8f, 0),     // 蓝 ballpoint
            WireCodec.Pen(220, 40, 40, 0.95f, 9f, 1),     // 红 fountain
            WireCodec.Pen(20, 20, 20, 0.95f, 10f, 3),     // 黑 pencil
            WireCodec.Pen(255, 214, 40, 0.40f, 22f, 2),   // 荧光 marker
        )
    }

    // —— 工具状态（Mac 推送为运行时唯一源，内置 4 支仅兜底） ——
    var mode = WireCodec.MODE_NOTE
        private set
    var penIndex = 0
        private set
    private val pens = ArrayList(FALLBACK_PENS)

    private fun curPen() = pens[penIndex]
    fun modeLabel(): String = MODE_LABELS[mode]
    fun penLabel(): String = WireCodec.brushName(curPen().brush)

    fun cycleMode() {
        if (activePen) endPen()   // 切换前正常收笔（JS 是丢弃，发 end 更干净）
        mode = (mode + 1) % MODE_LABELS.size
        endHover()
        listener?.onToolChanged()
        listener?.onHudChanged()
    }

    fun cyclePen() {
        penIndex = (penIndex + 1) % pens.size
        mode = WireCodec.MODE_NOTE
        listener?.onToolChanged()
        listener?.onHudChanged()
    }

    /** 收 pens：整体替换本地列表（Mac 画布悬浮工具条实时增删改后推下来） */
    fun setPens(list: List<WireCodec.Pen>, active: Int) {
        pens.clear()
        pens.addAll(list)
        if (pens.isEmpty()) pens.addAll(FALLBACK_PENS)
        penIndex = active.coerceIn(0, pens.size - 1)
        listener?.onHudChanged()
    }

    /** 收 pen：Mac 侧切笔回推 */
    fun setPenIndex(i: Int) {
        if (i in pens.indices && i != penIndex) {
            penIndex = i
            listener?.onHudChanged()
        }
    }

    /** 收 mode：Mac 侧切模式回推（悬浮工具条/环形盘选笔后回 note） */
    fun setMode(m: Int) {
        if (m in MODE_LABELS.indices && m != mode) {
            mode = m
            listener?.onHudChanged()
        }
    }

    // —— 文档布局（连续页面列 + 缩放） ——
    private var docV = ""
    private var pageCount = 0
    private var pagesWH = listOf<Pair<Float, Float>>()
    private var dispH = FloatArray(0)
    private var offY = FloatArray(0)
    private var totalH = 0f
    private var zoom = 1f
    private var scrollX = 0f
    private var scrollY = 0f
    private var maxScrollX = 0f
    private var maxScrollY = 0f
    private var barH = 0f          // 顶栏高度（内容垂直偏移）
    private var vw = 1f
    private var availH = 1f
    private var vpSeq = 0L         // 已应用的 Mac viewport 序号

    private fun pw() = vw * zoom                                   // 页(内容)宽
    private fun contentLeft(): Float {
        val p = pw()
        return if (p <= vw) (vw - p) / 2f else -scrollX            // 内容左缘视口 x
    }

    fun setBarHeight(px: Float) {
        barH = px
        onGeomChanged()
    }

    /** 收 layout：v 变化 = 换文档 → 清笔迹/页图/滚动/缩放/vpSeq */
    fun setLayout(docId: String, v: String, count: Int, pages: List<Pair<Float, Float>>) {
        val newV = v.ifEmpty { docId }
        val changed = newV != docV
        docV = newV
        pageCount = count
        pagesWH = pages
        if (changed) {
            strokes.clear()
            clearCur()
            images.clear()
            requested.clear()
            scrollX = 0f; scrollY = 0f; zoom = 1f; vpSeq = 0
        }
        onGeomChanged()
    }

    private fun recompute() {
        val p = pw()
        var y = 0f
        dispH = FloatArray(pageCount)
        offY = FloatArray(pageCount)
        for (i in 0 until pageCount) {
            val wh = pagesWH.getOrNull(i)
            val w = wh?.first ?: 1f
            val h = wh?.second ?: 1.4142f
            val dh = if (w > 0) p * h / w else p
            offY[i] = y; dispH[i] = dh; y += dh + GAP
        }
        totalH = max(0f, y - GAP)
        maxScrollY = max(0f, totalH - availH)
        maxScrollX = max(0f, p - vw)
    }

    private fun onGeomChanged() {
        vw = width.toFloat().coerceAtLeast(1f)
        availH = (height.toFloat() - barH).coerceAtLeast(1f)
        recompute()
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        ensureImages()
        invalidate()
        listener?.onHudChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onGeomChanged()
    }

    /** 严格 <：翻到某页正顶部时必须算本页（capture.html topVisiblePage 同一边界） */
    fun topVisiblePage(): Int {
        for (i in 0 until pageCount) if (scrollY < offY[i] + dispH[i] + GAP) return i
        return max(0, pageCount - 1)
    }

    fun hudPage(): String = if (pageCount > 0) "${topVisiblePage() + 1} / $pageCount" else "— / —"
    fun hudZoom(): String = "${(zoom * 100).roundToInt()}%"

    // —— 页图（可见 + 上下各一屏预取；MainActivity 经 PageFetcher 取回 setPageImage） ——
    private val images = HashMap<Int, Bitmap>()
    private val requested = HashSet<Int>()

    private fun ensureImages() {
        if (pageCount == 0) return
        val top = scrollY - availH
        val bot = scrollY + availH * 2
        for (i in 0 until pageCount) {
            if (offY[i] + dispH[i] >= top && offY[i] <= bot && !images.containsKey(i) && requested.add(i)) {
                listener?.requestImage(i)
            }
        }
    }

    fun setPageImage(i: Int, bmp: Bitmap?) {
        requested.remove(i)
        if (bmp != null) {
            images[i] = bmp
            invalidate()
        }
    }

    // —— 坐标映射（跨页 + 缩放；视口坐标 ↔ 页内归一化） ——
    private data class Loc(val page: Int, val nx: Float, val ny: Float)

    private fun locate(x: Float, vy: Float): Loc? {
        val cl = contentLeft()
        val p = pw()
        val docY = vy - barH + scrollY
        for (i in 0 until pageCount) {
            if (docY >= offY[i] && docY <= offY[i] + dispH[i]) {
                return Loc(
                    i,
                    ((x - cl) / p).coerceIn(0f, 1f),
                    ((docY - offY[i]) / dispH[i]).coerceIn(0f, 1f),
                )
            }
        }
        return null
    }

    private fun pageToViewX(page: Int, nx: Float) = contentLeft() + nx * pw()
    private fun pageToViewY(page: Int, ny: Float) = barH + offY[page] + ny * dispH[page] - scrollY

    // —— 笔迹（Mac 回传 strokes = 唯一真源；cur = 本地正在写的半笔即时回显） ——
    private val strokes = ArrayList<WireCodec.Stroke>()
    private var curActive = false
    private var curPage = 0
    private var curPen = FALLBACK_PENS[0]
    private val curPts = ArrayList<WireCodec.Pt3>()
    private var radialActive = false   // Mac 已把半笔转成环形选笔盘：本地撤半笔、不再画（位置照发）

    private fun clearCur() {
        curActive = false
        curPts.clear()
    }

    fun setStrokes(list: List<WireCodec.Stroke>) {
        strokes.clear()
        strokes.addAll(list)
        if (!activePen) clearCur()   // 正在写的这笔不清，避免闪断（capture.html 同款）
        invalidate()
    }

    fun onInkCancel() {
        radialActive = true
        clearCur()
        invalidate()
    }

    // —— 渲染公式（capture.html strokeWidthFor/opacityMultFor 的 Kotlin 版；fountain 系数照 JS = 1.15） ——
    private fun strokeWidthFor(t: String, p: Float, w: Float): Float = when (t) {
        "fountain" -> 0.3f + p.pow(1.6f) * w * 1.15f
        "marker" -> w
        "pencil" -> 0.5f + p * w * 0.85f
        else -> 0.6f + p * w   // ballpoint / 未知兜底
    }

    private fun opacityMultFor(t: String) = if (t == "pencil") 0.85f else 1f

    private val path = Path()
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val placeholderPaint = Paint().apply { color = 0xFFE9EDF2.toInt() }
    private val tmpRect = RectF()

    /** 二次贝塞尔中点平滑 + 起笔圆点（capture.html drawStroke/liveTo 同式） */
    private fun drawStroke(c: Canvas, page: Int, pen: WireCodec.Pen, pts: List<WireCodec.Pt3>) {
        if (pts.isEmpty() || page !in 0 until pageCount) return
        val t = WireCodec.brushName(pen.brush)
        val alpha = (pen.a * 255 * opacityMultFor(t)).roundToInt().coerceIn(0, 255)
        strokePaint.color = Color.argb(alpha, pen.r, pen.g, pen.b)

        var lpx = pageToViewX(page, pts[0].x)
        var lpy = pageToViewY(page, pts[0].y)
        var lmx = lpx
        var lmy = lpy
        strokePaint.style = Paint.Style.FILL
        c.drawCircle(lpx, lpy, strokeWidthFor(t, pts[0].p, pen.w) / 2f, strokePaint)
        strokePaint.style = Paint.Style.STROKE
        for (i in 1 until pts.size) {
            val px = pageToViewX(page, pts[i].x)
            val py = pageToViewY(page, pts[i].y)
            val mx = (lpx + px) / 2f
            val my = (lpy + py) / 2f
            strokePaint.strokeWidth = strokeWidthFor(t, pts[i].p, pen.w)
            path.reset()
            path.moveTo(lmx, lmy)
            path.quadTo(lpx, lpy, mx, my)
            c.drawPath(path, strokePaint)
            lmx = mx; lmy = my; lpx = px; lpy = py
        }
        // 补末段：上面每步只画到「相邻两点的中点」，末点从来没被连上——长笔画差这半段看不出来，
        // 两点直线（尺子）就是整整少画一半（线尾追不上笔尖）。补一段 lastMid → 末点才落到笔尖。
        if (pts.size > 1) {
            strokePaint.strokeWidth = strokeWidthFor(t, pts.last().p, pen.w)
            path.reset()
            path.moveTo(lmx, lmy)
            path.lineTo(lpx, lpy)
            c.drawPath(path, strokePaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0D1117.toInt())   // capture.html 深色底
        val cl = contentLeft()
        val p = pw()
        for (i in 0 until pageCount) {
            val vy = barH + offY[i] - scrollY
            if (vy + dispH[i] < barH || vy > height) continue
            tmpRect.set(cl, vy, cl + p, vy + dispH[i])
            val bmp = images[i]
            if (bmp != null) {
                canvas.drawRect(tmpRect, pagePaint)
                canvas.drawBitmap(bmp, null, tmpRect, null)
            } else {
                canvas.drawRect(tmpRect, placeholderPaint)
            }
        }
        for (s in strokes) drawStroke(canvas, s.page.toInt(), s.pen, s.pts)
        if (curActive) drawStroke(canvas, curPage, curPen, curPts)
    }

    // —— 橡皮：本地即时 eraseHit（18px 半径命中删除）+ Mac 回传统一 ——
    private fun eraseHit(x: Float, y: Float) {
        val r2 = ERASE_R * ERASE_R
        var changed = false
        for (i in strokes.indices.reversed()) {
            val s = strokes[i]
            var hit = false
            for (pt in s.pts) {
                val dx = pageToViewX(s.page.toInt(), pt.x) - x
                val dy = pageToViewY(s.page.toInt(), pt.y) - y
                if (dx * dx + dy * dy <= r2) { hit = true; break }
            }
            if (hit) { strokes.removeAt(i); changed = true }
        }
        if (changed) invalidate()
    }

    // —— 平移 / 惯性 / 滚动上报 ——
    private var vx = 0f
    private var vy = 0f              // 速度（scroll px/ms）
    private var lastMoveT = 0L
    private var momentumRunning = false
    private var reportPending = false
    private val handler = Handler(Looper.getMainLooper())

    private fun afterPan() {
        ensureImages()
        invalidate()
        listener?.onHudChanged()
        emitScroll()
    }

    private fun panBy(dx: Float, dy: Float) {
        scrollX = (scrollX + dx).coerceIn(0f, maxScrollX)
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY)
        afterPan()
    }

    private fun cancelMomentum() {
        momentumRunning = false
    }

    /** 松手惯性：按松手速度继续滚，0.94^(dt/16) 指数衰减，碰边界该轴停；期间持续上报 */
    private fun startMomentum() {
        cancelMomentum()
        if (hypot(vx, vy) < 0.05f) return
        momentumRunning = true
        var last = SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (!momentumRunning) return
                val now = SystemClock.uptimeMillis()
                val dt = min(50f, (now - last).toFloat())
                last = now
                scrollX = (scrollX + vx * dt).coerceIn(0f, maxScrollX)
                scrollY = (scrollY + vy * dt).coerceIn(0f, maxScrollY)
                val decay = 0.94f.pow(dt / 16f)
                vx *= decay; vy *= decay
                if (scrollX <= 0f || scrollX >= maxScrollX) vx = 0f
                if (scrollY <= 0f || scrollY >= maxScrollY) vy = 0f
                afterPan()
                if (hypot(vx, vy) > 0.02f) handler.postDelayed(this, 16) else momentumRunning = false
            }
        }
        handler.postDelayed(step, 16)
    }

    /** 滚动上报：视口顶所在页 + 页内 frac，16ms 节流（等价 capture.html 的 rAF 节流） */
    private fun emitScroll() {
        if (reportPending) return
        reportPending = true
        handler.postDelayed({
            reportPending = false
            val docY = scrollY
            for (i in 0 until pageCount) {
                if (docY < offY[i] + dispH[i] + GAP) {   // 严格 <，与 topVisiblePage 同一边界
                    val frac = ((docY - offY[i]) / max(1f, dispH[i])).coerceIn(0f, 1f)
                    listener?.sendUnrel(
                        WireCodec.encodeScroll(i.toLong(), frac, System.currentTimeMillis().toDouble())
                    )
                    return@postDelayed
                }
            }
        }, 16)
    }

    /** ◀▶：滚到相邻页顶 + 上报（不再发 pageTurn，与网页版一致） */
    fun turn(prev: Boolean) {
        if (pageCount == 0 || offY.isEmpty()) return   // layout 未到时按了会卡死（capture.html 同款防御）
        cancelMomentum()
        val i = (topVisiblePage() + if (prev) -1 else 1).coerceIn(0, max(0, pageCount - 1))
        scrollY = offY[i].coerceIn(0f, maxScrollY)
        afterPan()
    }

    /** 收 Mac 视口：书写中忽略；force 绕 seq 去重；先停本地惯性；不回发 */
    fun applyViewport(page: Long, frac: Float, seq: Long, force: Boolean) {
        if (activePen) return
        cancelMomentum()
        if (!force) {
            if (seq <= vpSeq) return
            vpSeq = seq
        }
        val p = page.toInt()
        if (p >= pageCount) return
        scrollY = (offY[p] + frac * dispH[p]).coerceIn(0f, maxScrollY)
        ensureImages()
        invalidate()
        listener?.onHudChanged()
    }

    // —— 批缓冲（8ms flush；erase 点带页号，flush 时按页分组发送） ——
    private val inkBatch = ArrayList<WireCodec.Pt3>()
    private data class ErasePt(val x: Float, val y: Float, val page: Int)
    private val eraseBatch = ArrayList<ErasePt>()
    private val flusher = object : Runnable {
        override fun run() {
            flushBatch()
            handler.postDelayed(this, 8)
        }
    }

    private fun flushBatch() {
        if (inkBatch.isNotEmpty()) {
            listener?.sendRel(WireCodec.encodeInkMove(ArrayList(inkBatch)))
            inkBatch.clear()
            listener?.onMoveFrame()
        }
        if (eraseBatch.isNotEmpty()) {
            // 擦除点带页号：按页分组发送，Mac 端据此只删对应页的笔迹
            val byPage = LinkedHashMap<Int, MutableList<WireCodec.Pt2>>()
            for (pt in eraseBatch) byPage.getOrPut(pt.page) { ArrayList() }.add(WireCodec.Pt2(pt.x, pt.y))
            eraseBatch.clear()
            for ((pg, pts) in byPage) {
                listener?.sendRel(WireCodec.encodeEraseMove(pg.toLong(), pts))
                listener?.onMoveFrame()
            }
        }
    }

    init {
        handler.postDelayed(flusher, 8)
    }

    // —— 指针：笔=画/擦/平移，手指=平移/双指缩放 ——
    private var activePen = false
    private var penId = -1
    private var penMode = WireCodec.MODE_NOTE
    private var penX = 0f
    private var penY = 0f
    private var drawPage = 0

    private data class Finger(var x: Float, var y: Float)
    private val touches = HashMap<Int, Finger>()
    private val touchOrder = ArrayList<Int>()
    private var panId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var panDownX = 0f
    private var panDownY = 0f
    private var panStarted = false

    private data class Pinch(val d0: Float, val z0: Float, val fx: Float, val fy: Float)
    private var pinch: Pinch? = null

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMomentum()
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    fingerDown(e.getPointerId(0), e.getX(0), e.getY(0), e.getTouchMajor(0), e.eventTime)
                } else {
                    penDown(e, 0)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelMomentum()
                val idx = e.actionIndex
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    fingerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx), e.getTouchMajor(idx), e.eventTime)
                } else if (!activePen) {
                    penDown(e, idx)   // 笔落下：笔优先（penDown 内清掉手指状态）
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePen) {
                    val pi = e.findPointerIndex(penId)
                    if (pi >= 0) {
                        // 必须展开历史点（Android 按 batch 投递，不展开 = 采样率腰斩）
                        for (h in 0 until e.historySize) penMove(e, pi, h)
                        penMove(e, pi, -1)
                    }
                } else {
                    for (i in 0 until e.pointerCount) {
                        touches[e.getPointerId(i)]?.let { it.x = e.getX(i); it.y = e.getY(i) }
                    }
                    if (pinch != null && touchOrder.size >= 2) pinchMove()
                    else if (panId >= 0) {
                        val pi = e.findPointerIndex(panId)
                        if (pi >= 0) panMove(e, pi)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (activePen && id == penId) endPen()
                else endTouch(id)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePen) {
                    endPen()
                    touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
                } else {
                    val wasPanning = panStarted
                    touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
                    if (wasPanning) startMomentum()   // 松手甩动 → 惯性
                }
            }
        }
        return true
    }

    private fun fingerDown(id: Int, x: Float, y: Float, touchMajor: Float, eventTime: Long) {
        if (activePen) return          // 笔在写 → 忽略手掌/手指
        if (touchMajor > PALM) return  // 大面积接触（手掌）忽略
        touches[id] = Finger(x, y)
        if (id !in touchOrder) touchOrder.add(id)
        if (touchOrder.size >= 2) {
            beginPinch()
        } else {
            panId = id
            lastPanX = x; lastPanY = y; panDownX = x; panDownY = y
            panStarted = false
            vx = 0f; vy = 0f; lastMoveT = eventTime
        }
    }

    private fun endTouch(id: Int) {
        if (id !in touches) return
        touches.remove(id)
        touchOrder.remove(id)
        pinch = null
        when {
            touchOrder.size == 1 -> {
                // 回到单指平移（重新死区判定，避免松指跳动）
                panId = touchOrder[0]
                val t = touches[panId]!!
                lastPanX = t.x; lastPanY = t.y; panDownX = t.x; panDownY = t.y
                panStarted = false
            }
            touchOrder.isEmpty() -> {
                if (panStarted) startMomentum()
                panId = -1; panStarted = false
            }
            else -> beginPinch()
        }
    }

    private fun beginPinch() {
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val p = pw()
        pinch = Pinch(
            d0 = max(40f, hypot(a.x - b.x, a.y - b.y)),   // 初始间距下限，避免起手过近灵敏度爆炸
            z0 = zoom,
            fx = if (p > 0) (mx - contentLeft()) / p else 0.5f,   // 捏合中点抓住的内容比例（固定锚点）
            fy = if (totalH > 0) (my - barH + scrollY) / totalH else 0f,
        )
        panId = -1; panStarted = false
    }

    /** 双指捏合：缩放 + 整体移动都跟手（锚点比例始终跟随当前中点）；纯本地查看，不上报（避免回环） */
    private fun pinchMove() {
        val pc = pinch ?: return
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val d = hypot(a.x - b.x, a.y - b.y)
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        zoom = (pc.z0 * d / pc.d0).coerceIn(MIN_ZOOM, MAX_ZOOM)
        recompute()
        scrollY = (pc.fy * totalH - (my - barH)).coerceIn(0f, maxScrollY)
        scrollX = if (pw() > vw) (pc.fx * pw() - mx).coerceIn(0f, maxScrollX) else 0f
        ensureImages()
        invalidate()
        listener?.onHudChanged()
    }

    private fun panMove(e: MotionEvent, pi: Int) {
        val x = e.getX(pi)
        val y = e.getY(pi)
        if (!panStarted) {
            if (hypot(x - panDownX, y - panDownY) < DEAD) return
            panStarted = true
            lastPanX = x; lastPanY = y; lastMoveT = e.eventTime
            return
        }
        val dx = lastPanX - x
        val dy = lastPanY - y
        val now = e.eventTime
        val dt = now - lastMoveT
        lastMoveT = now
        if (dt in 1..99) { vx = 0.7f * vx + 0.3f * (dx / dt); vy = 0.7f * vy + 0.3f * (dy / dt) }
        panBy(dx, dy)
        lastPanX = x; lastPanY = y
    }

    // —— 笔 ——

    private fun penDown(e: MotionEvent, idx: Int) {
        // 笔优先：清掉进行中的手指平移/捏合
        touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
        activePen = true
        penId = e.getPointerId(idx)
        radialActive = false
        endHover()
        when (mode) {
            WireCodec.MODE_PAGE -> {   // 翻页模式：笔拖动平移画面
                penMode = WireCodec.MODE_PAGE
                penX = e.getX(idx); penY = e.getY(idx)
                vx = 0f; vy = 0f; lastMoveT = e.eventTime
            }
            else -> {
                val loc = locate(e.getX(idx), e.getY(idx))
                if (loc == null) { activePen = false; penId = -1; return }
                penMode = mode
                if (mode == WireCodec.MODE_NOTE) {
                    drawPage = loc.page
                    curPage = loc.page
                    curPen = curPen()
                    val pt = WireCodec.Pt3(loc.nx, loc.ny, e.getPressure(idx))
                    curPts.clear()
                    curPts.add(pt)
                    curActive = true
                    invalidate()
                    listener?.sendRel(WireCodec.encodeInkBegin(loc.page.toLong(), curPen, listOf(pt)))
                } else {
                    eraseHit(e.getX(idx), e.getY(idx))
                    eraseBatch.add(ErasePt(loc.nx, loc.ny, loc.page))
                }
            }
        }
    }

    private fun penMove(e: MotionEvent, pi: Int, h: Int) {
        val historical = h >= 0
        val x = if (historical) e.getHistoricalX(pi, h) else e.getX(pi)
        val y = if (historical) e.getHistoricalY(pi, h) else e.getY(pi)
        val p = if (historical) e.getHistoricalPressure(pi, h) else e.getPressure(pi)
        val now = if (historical) e.getHistoricalEventTime(h) else e.eventTime

        if (penMode == WireCodec.MODE_PAGE) {   // 笔拖动平移（记录速度供松手惯性）
            val dx = penX - x
            val dy = penY - y
            val dt = now - lastMoveT
            lastMoveT = now
            if (dt in 1..99) { vx = 0.7f * vx + 0.3f * (dx / dt); vy = 0.7f * vy + 0.3f * (dy / dt) }
            panBy(dx, dy)
            penX = x; penY = y
            return
        }
        val loc = locate(x, y)
        if (penMode == WireCodec.MODE_NOTE) {
            // 写出页边界：坐标 clamp 在起笔页内（capture.html 同款）
            val nx = loc?.nx ?: ((x - contentLeft()) / pw()).coerceIn(0f, 1f)
            val ny = if (loc != null && loc.page == drawPage) loc.ny
            else ((y - barH + scrollY - offY[drawPage]) / max(1f, dispH[drawPage])).coerceIn(0f, 1f)
            // 环形盘激活后本地不再画（笔移是在选笔），但位置照发让 Mac 驱动高亮
            if (!radialActive && curActive) {
                curPts.add(WireCodec.Pt3(nx, ny, p))
                invalidate()
            }
            inkBatch.add(WireCodec.Pt3(nx, ny, p))
        } else {   // erase
            if (!radialActive) {
                eraseHit(x, y)
                if (loc != null) eraseBatch.add(ErasePt(loc.nx, loc.ny, loc.page))
            }
        }
    }

    private fun endPen() {
        when (penMode) {
            WireCodec.MODE_NOTE -> {
                flushBatch()
                listener?.onInkEndSent()
                listener?.sendRel(WireCodec.encodeInkEnd())
                // 不本地落 strokes（Mac 才是真源，稍后回传）；cur 先留着，等 strokes 再清
            }
            WireCodec.MODE_ERASE -> {
                if (radialActive) { inkBatch.clear(); eraseBatch.clear() } else flushBatch()
                listener?.sendRel(WireCodec.encodeEraseEnd())
            }
            WireCodec.MODE_PAGE -> startMomentum()   // 笔翻页拖动松手 → 惯性
        }
        radialActive = false
        activePen = false
        penId = -1
    }

    // —— 悬停（手写笔；16ms 节流上报，等价 capture.html 的 rAF 节流） ——
    private var hoverPending = false
    private var hoverMsg: ByteArray? = null
    private var hoverOn = false

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!activePen && mode != WireCodec.MODE_PAGE) {
                    val loc = locate(e.getX(0), e.getY(0))
                    if (loc != null) reportHover(loc.page, loc.nx, loc.ny) else endHover()
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                endHover()
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    private fun reportHover(page: Int, nx: Float, ny: Float) {
        hoverOn = true
        hoverMsg = WireCodec.encodeHoverMove(page.toLong(), nx, ny)
        if (hoverPending) return
        hoverPending = true
        handler.postDelayed({
            hoverPending = false
            hoverMsg?.let { listener?.sendUnrel(it) }
            hoverMsg = null
        }, 16)
    }

    private fun endHover() {
        if (!hoverOn) return
        hoverOn = false
        listener?.sendUnrel(WireCodec.encodeHoverEnd())
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
