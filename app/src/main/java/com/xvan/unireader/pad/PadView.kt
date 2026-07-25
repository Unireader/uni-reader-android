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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 输入板核心视图：页图显示 + 本地即时回显 + 触摸/手写笔采集。
 * - 单指/手写笔落墨（ink REL），双指拖动滚动（scroll UNREL），手写笔悬停（hover UNREL）
 * - 本地回显 0ms 即时：用户看到的线是本地画的；Mac 屏幕那条才有网络+渲染延迟
 * - MOVE 必须展开历史点（Android 按 batch 投递，不展开 = 采样率腰斩）
 * - 批缓冲 8ms flush（等价 udp-pad-sim.py 的 flush_batch）
 * - 坐标全部页内归一化 [0,1]，y 向下；页图 fit-width 居中，换算扣掉上下留白偏移
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 发送出口 + 统计回调（MainActivity 注入） */
    interface Listener {
        fun sendRel(body: ByteArray)
        fun sendUnrel(body: ByteArray)
        fun onInkEndSent()    // e2e 计时起点（ink end 发出时刻）
        fun onMoveFrame()     // mv/s 计数（每发一帧 ink/erase move）
        fun onToolChanged()
    }

    var listener: Listener? = null

    companion object {
        /** 4 支内置预设笔（照 udp-pad-sim.py 的 PENS；brush: 0=ballpoint 3=pencil 1=fountain 2=marker） */
        val PENS = listOf(
            WireCodec.Pen(24, 90, 210, 0.95f, 4f, 0),     // 蓝 ballpoint
            WireCodec.Pen(20, 20, 20, 1.0f, 6f, 3),       // 黑 pencil
            WireCodec.Pen(220, 40, 40, 0.9f, 2.5f, 1),    // 红 fountain
            WireCodec.Pen(255, 214, 40, 0.35f, 22f, 2),   // 黄 marker
        )
        val PEN_NAMES = listOf("ballpoint", "pencil", "fountain", "marker")
    }

    var eraseMode = false
        private set
    var penIndex = 0
        private set
    val toolName: String get() = if (eraseMode) "橡皮" else PEN_NAMES[penIndex]

    fun toggleErase() {
        eraseMode = !eraseMode
        listener?.onToolChanged()
    }

    fun cyclePen() {
        penIndex = (penIndex + 1) % PENS.size
        eraseMode = false
        listener?.onToolChanged()
    }

    // —— 页图状态 ——
    private var pageIndex = 0L
    private var pageCount = 0L
    private var pageBitmap: Bitmap? = null

    fun setPage(index: Long, count: Long) {
        pageIndex = index
        pageCount = count
    }

    fun setPageBitmap(b: Bitmap?) {
        pageBitmap = b
        doneStrokes.clear()   // 翻页后旧页回显作废（demo 只缓存当前页回显）
        invalidate()
    }

    // —— 本地回显（当前笔画 + 已完成笔画） ——
    private data class EchoStroke(val path: Path, val paint: Paint)
    private val doneStrokes = ArrayList<EchoStroke>()
    private var curPath: Path? = null
    private var curPaint: Paint? = null

    // —— 批缓冲 ——
    private val inkBatch = ArrayList<WireCodec.Pt3>()
    private val eraseBatch = ArrayList<WireCodec.Pt2>()
    private val handler = Handler(Looper.getMainLooper())
    private val flusher = object : Runnable {
        override fun run() {
            flushBatch()
            handler.postDelayed(this, 8)
        }
    }

    // —— 双指滚动锚点 ——
    private var scrolling = false
    private var lastMidY = 0f
    private var scrollPage = 0L
    private var scrollFrac = 0f

    init {
        setBackgroundColor(Color.WHITE)
        handler.postDelayed(flusher, 8)
    }

    // ---------- 坐标换算 ----------

    /** 页图 fit-width 居中显示矩形（view 坐标） */
    private fun dispRect(): RectF {
        val b = pageBitmap
        val vw = width.toFloat().coerceAtLeast(1f)
        if (b == null) return RectF(0f, 0f, vw, height.toFloat().coerceAtLeast(1f))
        val dh = b.height * (vw / b.width)
        val top = (height - dh) / 2f
        return RectF(0f, top, vw, top + dh)
    }

    /** 页内归一化 [0,1]，y 向下；扣掉上下留白偏移并 clamp */
    private fun norm(x: Float, y: Float): Pair<Float, Float> {
        val r = dispRect()
        val nx = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        val ny = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        return nx to ny
    }

    /** 手指默认 0.5；手写笔用真实压感 */
    private fun pressureOf(e: MotionEvent, p: Float): Float =
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) 0.5f else p

    // ---------- 触摸采集 ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrolling = false
                inkBatch.clear()
                eraseBatch.clear()
                if (!eraseMode) {
                    val (nx, ny) = norm(e.getX(0), e.getY(0))
                    val pt = WireCodec.Pt3(nx, ny, pressureOf(e, e.getPressure(0)))
                    listener?.sendRel(WireCodec.encodeInkBegin(pageIndex, PENS[penIndex], listOf(pt)))
                    beginEcho(e.getX(0), e.getY(0))
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2) {
                    // 第二指落下 → 转滚动。已起笔的先 flush + end 正常收笔（协议没有 C→S 的 ink 取消）
                    if (curPath != null || eraseBatch.isNotEmpty()) {
                        flushBatch()
                        if (eraseMode) {
                            listener?.sendRel(WireCodec.encodeEraseEnd())
                        } else {
                            listener?.onInkEndSent()
                            listener?.sendRel(WireCodec.encodeInkEnd())
                        }
                        finishEcho()
                    }
                    scrolling = true
                    inkBatch.clear()
                    eraseBatch.clear()
                    lastMidY = (e.getY(0) + e.getY(1)) / 2
                    if (scrollPage != pageIndex) {
                        scrollPage = pageIndex
                        scrollFrac = 0f
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling && e.pointerCount >= 2) {
                    val midY = (e.getY(0) + e.getY(1)) / 2
                    onScrollDelta(midY - lastMidY)
                    lastMidY = midY
                } else if (!scrolling) {
                    // 必须展开历史点再补当前点
                    for (i in 0 until e.historySize) addPoint(e, i, historical = true)
                    addPoint(e, 0, historical = false)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 抬起一指回到单指：本轮滚动结束，剩余手指不再续笔（防误触，从简）
                if (e.pointerCount <= 2) scrolling = false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!scrolling) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) addPoint(e, 0, historical = false)
                    flushBatch()
                    if (eraseMode) {
                        listener?.sendRel(WireCodec.encodeEraseEnd())
                    } else if (curPath != null) {
                        listener?.onInkEndSent()
                        listener?.sendRel(WireCodec.encodeInkEnd())
                    }
                    finishEcho()
                }
                scrolling = false
            }
        }
        return true
    }

    private fun addPoint(e: MotionEvent, i: Int, historical: Boolean) {
        val x = if (historical) e.getHistoricalX(0, i) else e.getX(0)
        val y = if (historical) e.getHistoricalY(0, i) else e.getY(0)
        val (nx, ny) = norm(x, y)
        if (eraseMode) {
            eraseBatch.add(WireCodec.Pt2(nx, ny))   // 橡皮点无压感（pt2），回显不画线
        } else {
            val p = if (historical) e.getHistoricalPressure(0, i) else e.getPressure(0)
            inkBatch.add(WireCodec.Pt3(nx, ny, pressureOf(e, p)))
            curPath?.lineTo(x, y)
            invalidate()
        }
    }

    private fun flushBatch() {
        if (inkBatch.isNotEmpty()) {
            listener?.sendRel(WireCodec.encodeInkMove(ArrayList(inkBatch)))
            inkBatch.clear()
            listener?.onMoveFrame()
        }
        if (eraseBatch.isNotEmpty()) {
            listener?.sendRel(WireCodec.encodeEraseMove(pageIndex, ArrayList(eraseBatch)))
            eraseBatch.clear()
            listener?.onMoveFrame()
        }
    }

    /** 双指滚动：内容跟手——手指上推(dy<0) = 向下滚动 = frac 增大。方向反了翻转这行符号。 */
    private fun onScrollDelta(dy: Float) {
        if (pageCount <= 0) return
        scrollFrac -= dy / dispRect().height()
        while (scrollFrac >= 1f && scrollPage < pageCount - 1) { scrollFrac -= 1f; scrollPage++ }
        while (scrollFrac < 0f && scrollPage > 0) { scrollFrac += 1f; scrollPage-- }
        scrollFrac = scrollFrac.coerceIn(0f, 1f)
        listener?.sendUnrel(
            WireCodec.encodeScroll(scrollPage, scrollFrac, System.currentTimeMillis().toDouble())
        )
    }

    // ---------- 手写笔悬停 ----------

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val (nx, ny) = norm(e.getX(0), e.getY(0))
                listener?.sendUnrel(WireCodec.encodeHoverMove(pageIndex, nx, ny))
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                listener?.sendUnrel(WireCodec.encodeHoverEnd())
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // ---------- 本地回显 ----------

    private fun beginEcho(x: Float, y: Float) {
        val pen = PENS[penIndex]
        curPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((pen.a * 255).toInt().coerceIn(0, 255), pen.r, pen.g, pen.b)
            strokeWidth = pen.w
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        curPath = Path().apply { moveTo(x, y) }
        invalidate()
    }

    private fun finishEcho() {
        val p = curPath
        val paint = curPaint
        if (p != null && paint != null) doneStrokes.add(EchoStroke(Path(p), paint))
        curPath = null
        curPaint = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pageBitmap?.let { canvas.drawBitmap(it, null, dispRect(), null) }
        for (s in doneStrokes) canvas.drawPath(s.path, s.paint)
        val p = curPath
        val paint = curPaint
        if (p != null && paint != null) canvas.drawPath(p, paint)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(flusher)
        super.onDetachedFromWindow()
    }
}
