package com.xvan.unireader.pad

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xvan.unireader.shared.InkRenderer
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadOverlays
import com.xvan.unireader.shared.PageMapper
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextNote
import com.xvan.unireader.shared.brushName
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 输入板核心视图（行为对齐 `web/src/`＝浏览器采集页，逐段移植）：
 * - 连续页面列几何：layout → dispH/offY/totalH（GAP=8dp），scrollY/scrollX 为唯一滚动真源
 * - 笔（stylus）= 落墨/擦除/翻页平移/框选 + hover；手指 = 单指平移（死区 + 松手惯性）+ 双指捏合缩放
 *   笔在写时忽略手指；getTouchMajor() 超阈值的手掌忽略
 * - 笔迹以 Mac 回传 strokes 为唯一真源；本地 cur 半笔即时回显，收 strokes 后清；inkCancel 撤半笔
 * - 环形选笔盘/长按进度环：**判定全在 Mac**，本地只画下发状态；擦除/翻页模式并行发 probe 流供 Mac 判长按
 * - 滚动上报照 emitScroll：视口顶所在页 + 页内 frac，16ms 节流（等价 rAF）
 * - 收 viewport → applyViewport（书写中忽略、force 绕 seq、先停惯性）
 *
 * 单位：长度常量在 `PadConst` 里是 dp，本类统一乘 density 折成像素；上报给 Mac 的 `padGeom.pageW`
 * 也是 dp（Mac 的环形盘取消区半径按它换算，见 PadConst 顶部注释）。
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), PageMapper {

    interface Listener {
        fun sendRel(body: ByteArray)      // UDP 可靠流（ink/erase/probe）
        fun sendUnrel(body: ByteArray)    // UDP 不可靠流（scroll/hover）
        fun sendCtl(body: ByteArray)      // WS 可靠通道（mode/pen/padGeom/lassoMove/textNote…）
        fun onInkEndSent()                // e2e 计时起点（ink end 发出时刻）
        fun onMoveFrame()                 // mv/s 计数（每发一帧 ink/erase move）
        fun onHudChanged()                // 页码/缩放/工具变化 → 顶栏刷新
        fun requestImage(page: Int)       // 页图按需预取（可见区 + 上下一屏）
        /** 文字笔记模式下点页面：打开编辑器（isNew=false 时是点中了已有笔记） */
        fun onOpenNoteEditor(id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean)
    }

    var listener: Listener? = null

    companion object {
        const val ERASE_R_FALLBACK = 0.02f   // 橡皮归一化半径默认值（Mac eraser 消息到达前）

        /** 内置兜底笔（Mac PenPresets.defaults；首连前用，pens 消息到达后整体替换） */
        val FALLBACK_PENS = listOf(
            Pen(24, 90, 210, 0.95f, 8f, 0),     // 蓝 ballpoint
            Pen(220, 40, 40, 0.95f, 9f, 1),     // 红 fountain
            Pen(20, 20, 20, 0.95f, 10f, 3),     // 黑 pencil
            Pen(255, 214, 40, 0.40f, 22f, 2),   // 荧光 marker
        )
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gapPx = dp(PadConst.GAP)
    private val palmPx = dp(PadConst.PALM)
    private val deadPx = dp(PadConst.DEAD)
    private val lassoDeadPx = dp(PadConst.LASSO_DEAD)

    private val ink = InkRenderer(density)
    private val overlays = PadOverlays(density)

    // —— 工具状态（Mac 推送为运行时唯一源，内置 4 支仅兜底） ——
    var mode = MODE_NOTE
        private set
    var penIndex = 0
        private set
    private val pens = ArrayList(FALLBACK_PENS)

    /** 文字笔记模式：独立本地开关，只改笔落下时的分派（点页面开编辑器，不写字） */
    var noteMode = false
        private set

    /** 尺子模式：独立本地开关，note 模式下笔迹吸附 45° 倍数直线 */
    var rulerOn = false
        private set

    /** 页图显示开关（false = 纯手写板，只白底不取图） */
    var showPage = true
        private set

    /** 夜间模式：只反转页图（墨迹/圆环不反） */
    var night = false
        private set

    /** 锁定缩放：双指仍可整体拖动，但不改 zoom */
    var zoomLocked = false
        private set

    fun penList(): List<Pen> = pens
    fun curPenOrNull(): Pen? = pens.getOrNull(penIndex)
    fun modeLabel(): String = PadConst.MODE_LABELS.getOrElse(mode) { "笔记" }
    fun penLabel(): String = PadConst.brushLabel(brushName(pens[penIndex].brush))

    private fun curPenPreset() = pens[penIndex]

    /** 本地切模式 → 同步给 Mac；切走框选即放弃选中（残留高亮框会误导） */
    fun cycleMode() {
        if (activePen) endPen()   // 切换前正常收笔
        val leavingLasso = mode == MODE_LASSO
        mode = (mode + 1) % PadConst.MODE_LABELS.size
        eraserRingAt = null
        if (leavingLasso) clearLasso()
        endHover()
        listener?.sendCtl(WireCodec.encodeMode(mode))
        invalidate()
        listener?.onHudChanged()
    }

    /** 非笔模式按切笔键 = 恢复之前那支笔，不轮替下一支；笔模式下才轮替（同 capture.ts cyclePen） */
    fun cyclePen() {
        if (mode == MODE_NOTE) penIndex = (penIndex + 1) % pens.size
        if (mode == MODE_LASSO) clearLasso()
        mode = MODE_NOTE
        listener?.sendCtl(WireCodec.encodePen(penIndex))
        listener?.sendCtl(WireCodec.encodeMode(mode))
        invalidate()
        listener?.onHudChanged()
    }

    fun toggleNoteMode() {
        noteMode = !noteMode
        listener?.onHudChanged()
    }

    fun toggleRuler() {
        rulerOn = !rulerOn
        listener?.onHudChanged()
    }

    fun toggleShowPage() {
        showPage = !showPage
        if (showPage) ensureImages()
        invalidate()
        listener?.onHudChanged()
    }

    fun toggleNight() {
        night = !night
        invalidate()
        listener?.onHudChanged()
    }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        listener?.onHudChanged()
    }

    /** 连接建立后把本地工具状态推给 Mac（同 capture 的 authOK 分支） */
    fun syncToolState() {
        listener?.sendCtl(WireCodec.encodeMode(mode))
        listener?.sendCtl(WireCodec.encodePen(penIndex))
        lastGeomW = -1f
        emitGeom()   // 重连后 Mac 那边的页宽是空的，无条件补一发
    }

    /** 收 pens：整体替换本地列表（Mac 画布悬浮工具条实时增删改后推下来） */
    fun setPens(list: List<Pen>, active: Int) {
        pens.clear()
        pens.addAll(list)
        if (pens.isEmpty()) pens.addAll(FALLBACK_PENS)
        penIndex = active.coerceIn(0, pens.size - 1)
        listener?.onHudChanged()
    }

    /** 本地改笔宽：即时生效（画/回显读的就是它），上行由调用方防抖发 penset */
    fun setPenWidth(index: Int, w: Float) {
        val p = pens.getOrNull(index) ?: return
        pens[index] = p.copy(w = w)
        invalidate()
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
        if (m in PadConst.MODE_LABELS.indices && m != mode) {
            if (mode == MODE_LASSO) clearLasso()   // 被 Mac 切走框选工具：同本地切模式
            mode = m
            eraserRingAt = null
            invalidate()
            listener?.onHudChanged()
        }
    }

    // —— 橡皮设置（随 eraser 消息双向同步；size = 归一化半径＝页宽比） ——
    var eraserSize = ERASE_R_FALLBACK
        private set
    var eraserMode = 1          // 0=整笔 1=局部
        private set
    var eraserRing = true
        private set
    private var eraserRingAt: FloatArray? = null   // 圆环位置（视口 px），null=不画

    /** 收 eraser：Mac 侧变更或新连接补发 */
    fun setEraser(size: Float, m: Int, ring: Boolean) {
        if (size > 0f) eraserSize = size
        eraserMode = if (m == 0) 0 else 1
        eraserRing = ring
        if (!ring) eraserRingAt = null
        invalidate()
    }

    /** 本地改橡皮设置（面板拖动即时生效，上行由调用方防抖发 eraser） */
    fun setEraserLocal(size: Float, m: Int, ring: Boolean) {
        eraserSize = size
        eraserMode = m
        eraserRing = ring
        if (!ring) eraserRingAt = null
        invalidate()
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
    private var lastGeomW = -1f    // padGeom 上报去重（dp）

    private fun pw() = vw * zoom                                   // 页(内容)宽
    private fun contentLeft(): Float {
        val p = pw()
        return if (p <= vw) (vw - p) / 2f else -scrollX            // 内容左缘视口 x
    }

    fun setBarHeight(px: Float) {
        barH = px
        onGeomChanged()
    }

    /** 收 layout：v 变化 = 换文档 → 清笔迹/页图/滚动/缩放/vpSeq（notes 等 Mac 重发，不清免闪空） */
    fun setLayout(docId: String, v: String, count: Int, pages: List<Pair<Float, Float>>) {
        val newV = v.ifEmpty { docId }
        val changed = newV != docV
        docV = newV
        pageCount = count
        pagesWH = pages
        if (changed) {
            strokes.clear()
            clearCur()
            clearLasso()
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
            offY[i] = y; dispH[i] = dh; y += dh + gapPx
        }
        totalH = max(0f, y - gapPx)
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
        emitGeom()
        listener?.onHudChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onGeomChanged()
    }

    /**
     * 平板页宽上报：Mac 侧的环形盘取消区半径/长按位移阈值都是**平板屏幕上的**尺度，
     * 得知道平板页宽才能换算。单位 dp（与 PadConst.RD 同尺度）。值变才发，静止时零流量。
     */
    private fun emitGeom() {
        val w = pw() / density
        if (abs(w - lastGeomW) < 0.5f) return
        lastGeomW = w
        listener?.sendCtl(WireCodec.encodePadGeom(w))
    }

    /** 严格 <：翻到某页正顶部时必须算本页（capture 的 topVisiblePage 同一边界） */
    fun topVisiblePage(): Int {
        for (i in 0 until pageCount) if (scrollY < offY[i] + dispH[i] + gapPx) return i
        return max(0, pageCount - 1)
    }

    fun pageCountOrZero(): Int = pageCount
    fun hudPage(): String = if (pageCount > 0) "${topVisiblePage() + 1} / $pageCount" else "— / —"
    fun hudZoom(): String = "${(zoom * 100).roundToInt()}%"

    // —— 页图（可见 + 上下各一屏预取；PadActivity 经 PageFetcher 取回 setPageImage） ——
    private val images = HashMap<Int, Bitmap>()
    private val requested = HashSet<Int>()

    private fun ensureImages() {
        if (pageCount == 0 || !showPage) return
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

    /**
     * 框选专用：与 `locate` 不同，**不要求**命中某一页——超出锚定页上/下边缘时 clamp 到该页边缘。
     * 镜像 Mac `finishLassoSelect` 对拖出页外终点的处理，故手势允许指针滑出锚定页而不中断。
     */
    private fun pageLocClamped(x: Float, y: Float, page: Int, out: FloatArray) {
        val cl = contentLeft()
        val p = pw()
        out[0] = ((x - cl) / p).coerceIn(0f, 1f)
        val docY = y - barH + scrollY
        out[1] = when {
            page !in 0 until pageCount -> 0f
            docY < offY[page] -> 0f
            docY > offY[page] + dispH[page] -> 1f
            else -> ((docY - offY[page]) / max(1f, dispH[page])).coerceIn(0f, 1f)
        }
    }

    override fun viewX(page: Int, nx: Float): Float = contentLeft() + nx * pw()

    override fun viewY(page: Int, ny: Float): Float =
        if (page in 0 until pageCount) barH + offY[page] + ny * dispH[page] - scrollY else -1e6f

    // —— 笔迹（Mac 回传 strokes = 唯一真源；cur = 本地正在写的半笔即时回显） ——
    private val strokes = ArrayList<Stroke>()
    private var curActive = false
    private var curPage = 0
    private var curStrokePen = FALLBACK_PENS[0]
    private val curPts = ArrayList<Pt3>()
    private var radialActive = false   // Mac 已把半笔转成环形选笔盘：本地撤半笔、不再画（位置照发）

    private fun clearCur() {
        curActive = false
        curPts.clear()
    }

    fun setStrokes(list: List<Stroke>) {
        strokes.clear()
        strokes.addAll(list)
        if (!activePen) clearCur()   // 正在写的这笔不清，避免闪断
        // 框选移动已提交、正等这条回来：新数据本身就是移动后的真源，乐观预览到此为止
        if (lassoCommitted) clearLasso()
        invalidate()
    }

    fun onInkCancel() {
        radialActive = true
        clearCur()
        invalidate()
    }

    // —— 文字笔记（Mac 下发全量镜像；本地只乐观更新，回传即整体替换） ——
    private val notes = ArrayList<TextNote>()

    fun setNotes(list: List<TextNote>) {
        notes.clear()
        notes.addAll(list)
        if (lassoCommitted) clearLasso()
        invalidate()
    }

    /** 编辑器保存：乐观更新本地列表并上行（Mac 随后回传 notes 全量镜像） */
    fun upsertNote(id: String, page: Int, nx: Float, ny: Float, text: String) {
        listener?.sendCtl(
            WireCodec.encodeTextNote(id, WireCodec.NOTE_UPSERT, page.toLong(), nx, ny, text)
        )
        val rec = TextNote(id, page.toLong(), nx, ny, text)
        val i = notes.indexOfFirst { it.id == id }
        if (i >= 0) notes[i] = rec else notes.add(rec)
        invalidate()
    }

    fun deleteNote(id: String, page: Int, nx: Float, ny: Float) {
        listener?.sendCtl(
            WireCodec.encodeTextNote(id, WireCodec.NOTE_DELETE, page.toLong(), nx, ny, "")
        )
        notes.removeAll { it.id == id }
        invalidate()
    }

    // —— 环形选笔盘 / 长按进度环（Mac 判定，本地照画） ——
    private var radial: WireCodec.Msg.Radial? = null
    private var pressRing: WireCodec.Msg.PressRing? = null
    private var pressT0 = 0L

    fun setRadial(m: WireCodec.Msg.Radial) {
        // 空扇区表按「没开盘」处理（同网页 drawRadial 的 `!items.length` 分支），否则会出现
        // 盘既不画、进度环也被盘挡着不画的空窗
        radial = if (m.open && m.items.isNotEmpty()) m else null
        invalidate()
    }

    fun setPressRing(m: WireCodec.Msg.PressRing) {
        // 不下发时间戳：收到 on=1 就用本机时钟起计（LAN RTT 的几毫秒偏差不可察觉）
        pressRing = if (m.on) m else null
        if (m.on) {
            pressT0 = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        } else {
            invalidate()
        }
    }

    /** 断线：盘/环可能正开着，Mac 不会补发瞬态状态，本地收掉 */
    fun clearTransient() {
        radialActive = false
        radial = null
        pressRing = null
        invalidate()
    }

    // —— 绘制 ——
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val placeholderPaint = Paint().apply { color = 0xFFE9EDF2.toInt() }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tmpRect = RectF()
    private val tmp2 = FloatArray(2)

    /** 夜间模式滤镜：反亮度（invert）后 hue-rotate 180° 复原彩色，同网页 CSS filter */
    private val nightFilter: ColorMatrixColorFilter = run {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val hue = ColorMatrix(
            floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        invert.postConcat(hue)   // 先 invert 再 hue-rotate
        ColorMatrixColorFilter(invert)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0D1117.toInt())   // 深色底
        val cl = contentLeft()
        val p = pw()
        // 夜间模式只反转「页面」这一层：页图、白底、以及未取到图时的占位色都要一起反
        // （网页那边是给 bg canvas 整体加 CSS filter，白底同样被反成黑底）。墨迹/圆环不反。
        val f = if (night) nightFilter else null
        bitmapPaint.colorFilter = f
        pagePaint.colorFilter = f
        placeholderPaint.colorFilter = f
        for (i in 0 until pageCount) {
            val vy = barH + offY[i] - scrollY
            if (vy + dispH[i] < barH || vy > height) continue
            tmpRect.set(cl, vy, cl + p, vy + dispH[i])
            canvas.drawRect(tmpRect, pagePaint)
            if (!showPage) continue   // 手写板模式：仅白底，不取图
            val bmp = images[i]
            if (bmp != null) canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
            else canvas.drawRect(tmpRect, placeholderPaint)
        }

        // 静态笔迹层（框选提交待回传期间：命中项按位移量乐观渲染）
        val sel = if (lassoCommitted) lassoSelection else null
        for (i in strokes.indices) {
            val s = strokes[i]
            if (sel != null && sel.page == s.page.toInt() && sel.strokeIdx.contains(i)) {
                ink.drawStroke(canvas, s.page.toInt(), s.pen, s.pts, this, lassoDx, lassoDy)
            } else {
                ink.drawStroke(canvas, s.page.toInt(), s.pen, s.pts, this)
            }
        }
        // 活体层：正在写的这一笔
        if (curActive) ink.drawStroke(canvas, curPage, curStrokePen, curPts, this)

        drawNoteMarkers(canvas, sel)

        // 橡皮尺寸圆环（擦除模式 + 开关开 + 有笔尖位置）
        val ringAt = eraserRingAt
        if (eraserRing && ringAt != null && mode == MODE_ERASE) {
            overlays.drawEraserRing(canvas, ringAt[0], ringAt[1], eraserSize * pw())
        }

        drawLassoOverlay(canvas)

        // 盘开着时不画进度环（环展开成盘，两者互斥）
        val rd = radial
        if (rd != null) {
            overlays.drawRadial(canvas, viewX(rd.page.toInt(), rd.cx), viewY(rd.page.toInt(), rd.cy),
                rd.highlight, rd.items)
        } else {
            val pr = pressRing
            if (pr != null) {
                overlays.drawPressRing(
                    canvas, viewX(pr.page.toInt(), pr.nx), viewY(pr.page.toInt(), pr.ny),
                    (SystemClock.uptimeMillis() - pressT0).toFloat(),
                )
                postInvalidateOnAnimation()
            }
        }
    }

    private fun drawNoteMarkers(canvas: Canvas, sel: LassoSelection?) {
        if (notes.isEmpty()) return
        val r = (pw() * 0.02f).coerceIn(dp(12f), dp(22f))
        for (i in notes.indices) {
            val n = notes[i]
            var nnx = n.nx
            var nny = n.ny
            if (sel != null && sel.page == n.page.toInt() && sel.noteIdx.contains(i)) {
                nnx += lassoDx; nny += lassoDy
            }
            val page = n.page.toInt()
            if (page !in 0 until pageCount) continue
            val x = viewX(page, nnx)
            val y = viewY(page, nny)
            if (y < barH - r || y > height + r || x < -r || x > width + r) continue
            overlays.drawNoteMarker(canvas, x, y, r, n.text)
        }
    }

    // —— 擦除：本地即时命中（与 Mac eraseNear 两模式一一对应）+ Mac 回传统一 ——

    /**
     * 命中判定在页内归一化坐标做、同页过滤、loc 为空不擦——两端乐观/真源语义保持一致：
     * - 整笔（eraserMode==0）：任一点命中即删整条；
     * - 局部（==1）：与 Mac `InkEdit.splitStroke` 是**同一算法两份实现**，改一边必须同步另一边。
     */
    private fun eraseHit(x: Float, y: Float) {
        val loc = locate(x, y) ?: return
        val r2 = eraserSize * eraserSize
        if (eraserMode == 0) {
            var changed = false
            for (i in strokes.indices.reversed()) {
                val s = strokes[i]
                if (s.page.toInt() != loc.page) continue
                for (pt in s.pts) {
                    val dx = pt.x - loc.nx
                    val dy = pt.y - loc.ny
                    if (dx * dx + dy * dy <= r2) { strokes.removeAt(i); changed = true; break }
                }
            }
            if (changed) invalidate()
            return
        }
        // 局部：剔除命中点，连续未命中段各成新笔迹（空 = 整笔消除）
        var changed = false
        val out = ArrayList<Stroke>(strokes.size)
        for (s in strokes) {
            if (s.page.toInt() != loc.page) { out.add(s); continue }
            var anyHit = false
            var seg = ArrayList<Pt3>()
            fun flush() { if (seg.isNotEmpty()) { out.add(Stroke(s.page, s.pen, seg)); seg = ArrayList() } }
            for (pt in s.pts) {
                val dx = pt.x - loc.nx
                val dy = pt.y - loc.ny
                if (dx * dx + dy * dy <= r2) { anyHit = true; flush() } else seg.add(pt)
            }
            if (anyHit) { flush(); changed = true } else out.add(s)
        }
        if (changed) {
            strokes.clear()
            strokes.addAll(out)
            invalidate()
        }
    }

    // —— 框选移动（lasso：拖空白=框选 / 拖选中高亮框内=移动；本地判定只为预览，Mac 复判执行） ——

    /**
     * 本地判定的选中集：镜像 Mac `LassoSelection`，但这里只用于渲染高亮——命中算法是客户端
     * 复刻的一份乐观预览（同 eraseHit 先例），提交移动时 Mac 用真源重新判定，不信任这里的下标。
     */
    private data class LassoSelection(
        val page: Int,
        val box: FloatArray,          // x0,y0,x1,y1（归一化，提交时原样带给 Mac 复判）
        val strokeIdx: List<Int>,
        val noteIdx: List<Int>,
        val bounds: FloatArray,       // x,y,w,h：命中内容的联合包围盒（画高亮框用）
    )

    private var lassoSelection: LassoSelection? = null
    private var lassoDragMode = 0                 // 0=无手势 1=框选 2=移动
    private var lassoAnchorPage = -1
    private var lassoAnchorNx = 0f
    private var lassoAnchorNy = 0f
    private var lassoDownX = 0f
    private var lassoDownY = 0f
    private var lassoMoved = false
    private var lassoCurNx = 0f
    private var lassoCurNy = 0f
    private var lassoHasCur = false
    private var lassoDx = 0f
    private var lassoDy = 0f
    private var lassoCommitted = false
    private var lassoTimeout: Runnable? = null

    fun clearLasso() {
        lassoTimeout?.let { handler.removeCallbacks(it) }
        lassoTimeout = null
        lassoSelection = null
        lassoDragMode = 0
        lassoAnchorPage = -1
        lassoMoved = false
        lassoHasCur = false
        lassoCommitted = false
        lassoDx = 0f; lassoDy = 0f
        invalidate()
    }

    /** 框选命中（本地复刻 Mac `finishLassoSelect`：任一点落框=命中笔迹，锚点落框=命中注解） */
    private fun lassoHitTest(page: Int, x0: Float, y0: Float, x1: Float, y1: Float): LassoSelection? {
        val rx0 = min(x0, x1); val rx1 = max(x0, x1)
        val ry0 = min(y0, y1); val ry1 = max(y0, y1)
        val sIdx = ArrayList<Int>()
        val nIdx = ArrayList<Int>()
        var lox = 1f; var loy = 1f; var hix = 0f; var hiy = 0f
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.page.toInt() != page) continue
            if (s.pts.none { it.x in rx0..rx1 && it.y in ry0..ry1 }) continue
            sIdx.add(i)
            for (pt in s.pts) {
                lox = min(lox, pt.x); loy = min(loy, pt.y)
                hix = max(hix, pt.x); hiy = max(hiy, pt.y)
            }
        }
        for (i in notes.indices) {
            val n = notes[i]
            if (n.page.toInt() != page) continue
            if (n.nx < rx0 || n.nx > rx1 || n.ny < ry0 || n.ny > ry1) continue
            nIdx.add(i)
            lox = min(lox, n.nx); loy = min(loy, n.ny)
            hix = max(hix, n.nx); hiy = max(hiy, n.ny)
        }
        if (sIdx.isEmpty() && nIdx.isEmpty()) return null
        return LassoSelection(
            page, floatArrayOf(rx0, ry0, rx1, ry1), sIdx, nIdx,
            floatArrayOf(lox, loy, hix - lox, hiy - loy),
        )
    }

    /**
     * 越过死区后判一次形态：落笔点落在当前选中高亮框内（含 8dp 抓手余量）→ 移动；
     * 否则重新框选（并放弃旧选中，同 Mac 逻辑）。
     */
    private fun handleLassoMove(x: Float, y: Float) {
        if (lassoAnchorPage < 0) return
        if (!lassoMoved) {
            if (hypot(x - lassoDownX, y - lassoDownY) < lassoDeadPx) return
            lassoMoved = true
            var m = 1
            val sel = lassoSelection
            if (sel != null && sel.page == lassoAnchorPage) {
                val b = sel.bounds
                val px0 = viewX(sel.page, b[0]); val py0 = viewY(sel.page, b[1])
                val px1 = viewX(sel.page, b[0] + b[2]); val py1 = viewY(sel.page, b[1] + b[3])
                val g = dp(8f)
                if (lassoDownX >= min(px0, px1) - g && lassoDownX <= max(px0, px1) + g &&
                    lassoDownY >= min(py0, py1) - g && lassoDownY <= max(py0, py1) + g
                ) m = 2
            }
            lassoDragMode = m
            if (m == 1) lassoSelection = null
        }
        pageLocClamped(x, y, lassoAnchorPage, tmp2)
        if (lassoDragMode == 1) {
            lassoCurNx = tmp2[0]; lassoCurNy = tmp2[1]; lassoHasCur = true
        } else if (lassoDragMode == 2) {
            lassoDx = tmp2[0] - lassoAnchorNx
            lassoDy = tmp2[1] - lassoAnchorNy
        }
        invalidate()
    }

    /**
     * 松手收尾：纯点击（未越过死区）→ 清选中（同 Mac `.onTapGesture` 无条件清）；
     * 框选 → 本地判定命中集（只渲染高亮，不上行）；移动 → 提交位移给 Mac（真源复判 + 持久化）。
     */
    private fun finishLasso() {
        val page = lassoAnchorPage
        if (!lassoMoved || lassoDragMode == 0) {
            if (lassoSelection != null) { lassoSelection = null; invalidate() }
        } else if (lassoDragMode == 1 && page >= 0 && lassoHasCur) {
            lassoSelection = lassoHitTest(page, lassoAnchorNx, lassoAnchorNy, lassoCurNx, lassoCurNy)
            invalidate()
        } else if (lassoDragMode == 2) {
            val sel = lassoSelection
            if (sel != null && (lassoDx != 0f || lassoDy != 0f)) {
                listener?.sendCtl(
                    WireCodec.encodeLassoMove(
                        sel.page.toLong(), sel.box[0], sel.box[1], sel.box[2], sel.box[3], lassoDx, lassoDy,
                    )
                )
                lassoCommitted = true
                lassoTimeout?.let { handler.removeCallbacks(it) }
                // 兜底：Mac 判定为零命中/零变化时不会回传 strokes/notes，靠超时清掉乐观预览
                val r = Runnable { lassoTimeout = null; if (lassoCommitted) clearLasso() }
                lassoTimeout = r
                handler.postDelayed(r, 1000)
                invalidate()
            }
        }
        lassoDragMode = 0
        lassoMoved = false
        lassoHasCur = false
        lassoAnchorPage = -1
        if (!lassoCommitted) { lassoDx = 0f; lassoDy = 0f }
    }

    private fun drawLassoOverlay(canvas: Canvas) {
        if (lassoDragMode == 1 && lassoAnchorPage >= 0 && lassoHasCur) {
            val pg = lassoAnchorPage
            overlays.drawLassoBox(
                canvas,
                viewX(pg, min(lassoAnchorNx, lassoCurNx)), viewY(pg, min(lassoAnchorNy, lassoCurNy)),
                viewX(pg, max(lassoAnchorNx, lassoCurNx)), viewY(pg, max(lassoAnchorNy, lassoCurNy)),
            )
        }
        val sel = lassoSelection ?: return
        val tx = if (lassoDragMode == 2 || lassoCommitted) lassoDx else 0f
        val ty = if (lassoDragMode == 2 || lassoCommitted) lassoDy else 0f
        val b = sel.bounds
        overlays.drawLassoSelection(
            canvas,
            viewX(sel.page, b[0] + tx), viewY(sel.page, b[1] + ty),
            viewX(sel.page, b[0] + b[2] + tx), viewY(sel.page, b[1] + b[3] + ty),
        )
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

    /** 滚动上报：视口顶所在页 + 页内 frac，16ms 节流（等价 capture 的 rAF 节流） */
    private fun emitScroll() {
        if (reportPending) return
        reportPending = true
        handler.postDelayed({
            reportPending = false
            val docY = scrollY
            for (i in 0 until pageCount) {
                if (docY < offY[i] + dispH[i] + gapPx) {   // 严格 <，与 topVisiblePage 同一边界
                    val frac = ((docY - offY[i]) / max(1f, dispH[i])).coerceIn(0f, 1f)
                    listener?.sendUnrel(
                        WireCodec.encodeScroll(i.toLong(), frac, System.currentTimeMillis().toDouble())
                    )
                    return@postDelayed
                }
            }
        }, 16)
    }

    /** ◀▶：滚到相邻页顶 + 上报（不发 pageTurn，与网页版一致） */
    fun turn(prev: Boolean) {
        if (pageCount == 0 || offY.isEmpty()) return   // layout 未到时按了会卡死
        cancelMomentum()
        val i = (topVisiblePage() + if (prev) -1 else 1).coerceIn(0, max(0, pageCount - 1))
        scrollY = offY[i].coerceIn(0f, maxScrollY)
        afterPan()
    }

    /** 直接跳转到指定页码（1-based）：本地滚到该页顶部 + 上行给 Mac 跟随 */
    fun gotoPage(page1Based: Int) {
        if (pageCount == 0 || offY.isEmpty() || page1Based < 1 || page1Based > pageCount) return
        cancelMomentum()
        val i = (page1Based - 1).coerceIn(0, pageCount - 1)
        scrollY = offY[i].coerceIn(0f, maxScrollY)
        listener?.sendCtl(WireCodec.encodeGotoPage(i.toLong()))
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
    private val inkBatch = ArrayList<Pt3>()
    private data class ErasePt(val x: Float, val y: Float, val page: Int)
    private val eraseBatch = ArrayList<ErasePt>()
    private val probeBatch = ArrayList<Pt2>()
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
            val byPage = LinkedHashMap<Int, MutableList<Pt2>>()
            for (pt in eraseBatch) byPage.getOrPut(pt.page) { ArrayList() }.add(Pt2(pt.x, pt.y))
            eraseBatch.clear()
            for ((pg, pts) in byPage) {
                listener?.sendRel(WireCodec.encodeEraseMove(pg.toLong(), pts))
                listener?.onMoveFrame()
            }
        }
        if (probeBatch.isNotEmpty()) {
            listener?.sendRel(WireCodec.encodeProbeMove(ArrayList(probeBatch)))
            probeBatch.clear()
        }
    }

    init {
        handler.postDelayed(flusher, 8)
    }

    // —— 指针：笔=画/擦/平移/框选，手指=平移/双指缩放 ——
    private var activePen = false
    private var penId = -1
    private var penMode = MODE_NOTE
    private var penX = 0f
    private var penY = 0f
    private var drawPage = 0
    private var lineStroke = false      // 落笔那一刻锁进这一笔的尺子状态
    private var probing = false         // 探针流（擦除/翻页模式）：平行上报笔位置给 Mac 判长按
    private var probePage = 0
    private val snapOut = FloatArray(2)

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
        if (activePen) return            // 笔在写 → 忽略手掌/手指
        if (touchMajor > palmPx) return  // 大面积接触（手掌）忽略
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
            d0 = max(dp(40f), hypot(a.x - b.x, a.y - b.y)),   // 初始间距下限，避免起手过近灵敏度爆炸
            z0 = zoom,
            fx = if (p > 0) (mx - contentLeft()) / p else 0.5f,   // 捏合中点抓住的内容比例（固定锚点）
            fy = if (totalH > 0) (my - barH + scrollY) / totalH else 0f,
        )
        panId = -1; panStarted = false
    }

    /** 双指捏合：缩放 + 整体移动都跟手（锚点比例始终跟随当前中点）；纯本地查看，不上报位置（避免回环） */
    private fun pinchMove() {
        val pc = pinch ?: return
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val d = hypot(a.x - b.x, a.y - b.y)
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        if (!zoomLocked) zoom = (pc.z0 * d / pc.d0).coerceIn(PadConst.MIN_ZOOM, PadConst.MAX_ZOOM)
        recompute()
        scrollY = (pc.fy * totalH - (my - barH)).coerceIn(0f, maxScrollY)
        scrollX = if (pw() > vw) (pc.fx * pw() - mx).coerceIn(0f, maxScrollX) else 0f
        ensureImages()
        invalidate()
        emitGeom()   // 页宽变了要告诉 Mac（选笔盘的像素判定基准），与位置无关、不构成回环
        listener?.onHudChanged()
    }

    private fun panMove(e: MotionEvent, pi: Int) {
        val x = e.getX(pi)
        val y = e.getY(pi)
        if (!panStarted) {
            if (hypot(x - panDownX, y - panDownY) < deadPx) return
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
        val x = e.getX(idx)
        val y = e.getY(idx)

        // 文字笔记模式最优先：点空白开新笔记编辑器、点已有标记开编辑/删除。
        // 该分支绝不发 probe/ink/hover（probe 会让 Mac 呼出环形选笔盘），直接 return。
        if (noteMode) {
            val loc = locate(x, y)
            if (loc != null) {
                val hit = notes.firstOrNull {
                    it.page.toInt() == loc.page && hypot(it.nx - loc.nx, it.ny - loc.ny) < PadConst.NOTE_HIT
                }
                if (hit != null) {
                    listener?.onOpenNoteEditor(hit.id, hit.page.toInt(), hit.nx, hit.ny, hit.text, false)
                } else {
                    listener?.onOpenNoteEditor(
                        UUID.randomUUID().toString(), loc.page, loc.nx, loc.ny, "", true
                    )
                }
            }
            return
        }

        activePen = true
        penId = e.getPointerId(idx)
        radialActive = false
        endHover()

        if (mode == MODE_PAGE) {
            // 翻页模式：笔拖动平移画面（同时起探针流，供 Mac 检测长按呼出选笔盘）
            penMode = MODE_PAGE
            penX = x; penY = y
            vx = 0f; vy = 0f; lastMoveT = e.eventTime
            val ploc = locate(x, y)
            if (ploc != null) beginProbe(ploc.page, ploc.nx, ploc.ny)
            return
        }

        val loc = locate(x, y)
        if (loc == null) { activePen = false; penId = -1; return }
        penMode = mode
        when (mode) {
            MODE_NOTE -> {
                drawPage = loc.page
                curPage = loc.page
                curStrokePen = curPenPreset()
                // 尺子开关按**落笔那一刻**锁进这一笔（中途改开关不影响正在写的这笔），并随 begin 上报：
                // Mac 据此把后续 move 当「替换终点」而不是追加点，两端才都是同一条两点直线。
                lineStroke = rulerOn
                val pt = Pt3(loc.nx, loc.ny, e.getPressure(idx))
                curPts.clear()
                curPts.add(pt)
                curActive = true
                invalidate()
                listener?.sendRel(
                    WireCodec.encodeInkBegin(loc.page.toLong(), curStrokePen, listOf(pt), lineStroke)
                )
            }
            MODE_ERASE -> {
                eraseHit(x, y)
                eraseBatch.add(ErasePt(loc.nx, loc.ny, loc.page))
                if (eraserRing) { eraserRingAt = floatArrayOf(x, y); invalidate() }
                beginProbe(loc.page, loc.nx, loc.ny)
            }
            MODE_LASSO -> {
                // 落笔点记下来即可：拖动形态（框选/移动）在越过死区那一刻才判定
                // （镜像 Mac `DragGesture(minimumDistance: 2)` 起点一次性判定，纯点击不触发手势）
                lassoAnchorPage = loc.page
                lassoAnchorNx = loc.nx
                lassoAnchorNy = loc.ny
                lassoDownX = x; lassoDownY = y
                lassoMoved = false
                lassoDragMode = 0
            }
        }
    }

    private fun beginProbe(page: Int, nx: Float, ny: Float) {
        probing = true
        probePage = page
        listener?.sendRel(WireCodec.encodeProbeBegin(page.toLong(), listOf(Pt2(nx, ny))))
    }

    /** 写/擦出页边界时把坐标 clamp 在起笔页内（capture 同款） */
    private fun clampToPage(x: Float, y: Float, loc: Loc?, page: Int, out: FloatArray) {
        out[0] = loc?.nx ?: ((x - contentLeft()) / pw()).coerceIn(0f, 1f)
        out[1] = if (loc != null && loc.page == page) loc.ny
        else if (page in 0 until pageCount)
            ((y - barH + scrollY - offY[page]) / max(1f, dispH[page])).coerceIn(0f, 1f)
        else 0f
    }

    private fun penMove(e: MotionEvent, pi: Int, h: Int) {
        val historical = h >= 0
        val x = if (historical) e.getHistoricalX(pi, h) else e.getX(pi)
        val y = if (historical) e.getHistoricalY(pi, h) else e.getY(pi)
        val p = if (historical) e.getHistoricalPressure(pi, h) else e.getPressure(pi)
        val now = if (historical) e.getHistoricalEventTime(h) else e.eventTime

        if (penMode == MODE_LASSO) { handleLassoMove(x, y); return }

        val loc = locate(x, y)

        if (penMode == MODE_PAGE) {
            // 环形盘开着时只发探针不平移
            if (probing) {
                clampToPage(x, y, loc, probePage, tmp2)
                probeBatch.add(Pt2(tmp2[0], tmp2[1]))
            }
            if (radialActive) return
            val dx = penX - x
            val dy = penY - y
            val dt = now - lastMoveT
            lastMoveT = now
            if (dt in 1..99) { vx = 0.7f * vx + 0.3f * (dx / dt); vy = 0.7f * vy + 0.3f * (dy / dt) }
            panBy(dx, dy)
            penX = x; penY = y
            return
        }

        if (penMode == MODE_NOTE) {
            clampToPage(x, y, loc, drawPage, tmp2)
            var nx = tmp2[0]
            var ny = tmp2[1]
            if (lineStroke && !radialActive && curActive && curPts.isNotEmpty()) {
                // 尺子模式：以首点为锚做 45°（**视觉**角度，故传页纵横比）吸附，本地笔迹替换为
                // [首点, 吸附终点]。上行也只发这个终点——批里**只留最新一个**，否则 Mac 收到的是
                // 一串移动中的终点、追加成一条歪笔迹（begin 的 line 标记让 Mac 改为替换终点）。
                val a = curPts[0]
                val asp = if (drawPage in 0 until pageCount) dispH[drawPage] / max(1f, pw()) else 1f
                PadConst.rulerSnap(a.x, a.y, nx, ny, asp, snapOut)
                nx = snapOut[0]; ny = snapOut[1]
                curPts.clear()
                curPts.add(a)
                curPts.add(Pt3(nx, ny, p))
                inkBatch.clear()
                inkBatch.add(Pt3(nx, ny, p))
                invalidate()
                return
            }
            // 环形盘激活后本地不再画（笔移是在选笔），但位置照发让 Mac 驱动高亮
            if (!radialActive && curActive) {
                curPts.add(Pt3(nx, ny, p))
                invalidate()
            }
            inkBatch.add(Pt3(nx, ny, p))
            return
        }

        // erase
        if (!radialActive) {
            eraseHit(x, y)
            if (loc != null) eraseBatch.add(ErasePt(loc.nx, loc.ny, loc.page))
            if (eraserRing) { eraserRingAt = floatArrayOf(x, y); invalidate() }
        }
        if (probing) {
            clampToPage(x, y, loc, probePage, tmp2)
            probeBatch.add(Pt2(tmp2[0], tmp2[1]))
        }
    }

    private fun endPen() {
        when (penMode) {
            MODE_NOTE -> {
                if (radialActive) clearCur()
                flushBatch()
                listener?.onInkEndSent()
                listener?.sendRel(WireCodec.encodeInkEnd())
                // 不本地落 strokes（Mac 才是真源，稍后回传）；cur 先留着，等 strokes 再清
            }
            MODE_ERASE -> {
                if (radialActive) { inkBatch.clear(); eraseBatch.clear() } else flushBatch()
                listener?.sendRel(WireCodec.encodeEraseEnd())
            }
            MODE_PAGE -> if (!radialActive) startMomentum()   // 环形盘选择不甩动
            MODE_LASSO -> finishLasso()
        }
        if (probing) {   // 收尾探针流，Mac 据此提交/取消环形盘
            if (probeBatch.isNotEmpty()) {
                listener?.sendRel(WireCodec.encodeProbeMove(ArrayList(probeBatch)))
                probeBatch.clear()
            }
            listener?.sendRel(WireCodec.encodeProbeEnd())
            probing = false
        }
        radialActive = false
        // 抬笔必然收盘/撤环：不等 Mac 的 off 消息，丢帧也不会残留一个盘/环挡视线
        radial = null
        pressRing = null
        activePen = false
        penId = -1
        invalidate()
    }

    // —— 悬停（手写笔；16ms 节流上报，等价 capture 的 rAF 节流） ——
    private var hoverPending = false
    private var hoverMsg: ByteArray? = null
    private var hoverOn = false

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (!activePen && mode != MODE_PAGE) {
                    val loc = locate(e.getX(0), e.getY(0))
                    if (loc != null) {
                        reportHover(loc.page, loc.nx, loc.ny)
                        // 擦除模式：悬停时也显示橡皮尺寸圆环
                        if (mode == MODE_ERASE && eraserRing) {
                            eraserRingAt = floatArrayOf(e.getX(0), e.getY(0))
                            invalidate()
                        }
                    } else endHover()
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
        if (!hoverOn && eraserRingAt == null) return
        hoverOn = false
        if (eraserRingAt != null) { eraserRingAt = null; invalidate() }
        listener?.sendUnrel(WireCodec.encodeHoverEnd())
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
