package com.xvan.unireader.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import kotlin.math.abs
import kotlin.math.max

/**
 * 模式2 输入板的画布：`PageCanvasView`（几何+输入+渲染）+ **把每个提交口翻译成线格式帧**。
 *
 * 这里一行业务逻辑都没有——所有几何/手势/命中判定都在基类，两种模式共用。本类只回答一个问题：
 * 「这一笔提交给谁」＝ 编帧发给 Mac。模式1 的本地版覆写同一批钩子改为落库（M3），
 * 「本地先画、等真源回推再清」的那套代码因此一行都不用改（`ANDROID-STANDALONE-PLAN.md §5.1`）。
 *
 * 通道选择照 `PROTOCOL.md`：ink/erase/probe 走 UDP 可靠流（丢包靠 NACK 重传），
 * scroll/hover 走 UDP 不可靠流（过时的位置没有重传价值），其余控制消息走 WS。
 */
class PadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PageCanvasView(context, attrs) {

    interface Listener {
        fun sendRel(body: ByteArray)      // UDP 可靠流（ink/erase/probe）
        fun sendUnrel(body: ByteArray)    // UDP 不可靠流（scroll/hover）
        fun sendCtl(body: ByteArray)      // WS 可靠通道（mode/pen/padGeom/lassoMove/textNote…）
        fun onInkEndSent()                // e2e 计时起点（ink end 发出时刻）
        fun onMoveFrame()                 // mv/s 计数（每发一帧 ink/erase move）
        fun onHudChanged()                // 页码/缩放/工具变化 → 顶栏刷新
        /** 文字笔记模式下点页面：打开编辑器（isNew=false 时是点中了已有笔记） */
        fun onOpenNoteEditor(
            id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean, display: Int,
        )
        /** 手指单击草稿纸图钉（index = scratchpads 列表下标；宿主发 scratchOpen 请求） */
        fun onScratchPinTap(index: Int)
        /** 手指拖动图钉松手（页内归一化新锚点，已钳位 0~1；宿主发 scratchMove 请求） */
        fun onScratchPinMove(index: Int, nx: Float, ny: Float)
    }

    var listener: Listener? = null

    /**
     * 模式2 的真源镜像是 Mac 的 `broadcastStrokes()`/`broadcastNotes()` **两条独立广播**
     * （WS/UDP 不同通道，到达顺序与间隔无保证）——框选提交的乐观预览必须随各自镜像分层退场，
     * 任一条到了就全清会让另一层跳回原位再跳回来（闪烁，2026-08-18 用户报）。
     */
    override val lassoMirrorSplit: Boolean get() = true

    /** 已应用的 Mac viewport 序号（去重用；换文档归零） */
    private var vpSeq = 0L

    /** 当前文档版本（layout 的 v，空则退回 docId）：变了才算换文档 */
    private var docV = ""

    // ---------- 提交口 → 线格式 ----------

    override fun onInkBegin(page: Int, pen: Pen, pt: Pt3, line: Boolean) {
        listener?.sendRel(WireCodec.encodeInkBegin(page.toLong(), pen, listOf(pt), line))
    }

    override fun onInkMove(pts: List<Pt3>) {
        listener?.sendRel(WireCodec.encodeInkMove(pts))
    }

    override fun onInkEnd() {
        listener?.onInkEndSent()   // 计时起点必须在发之前取，否则把编码耗时也算进 e2e
        listener?.sendRel(WireCodec.encodeInkEnd())
    }

    override fun onErase(page: Int, pts: List<Pt2>) {
        listener?.sendRel(WireCodec.encodeEraseMove(page.toLong(), pts))
    }

    override fun onEraseEnd() {
        listener?.sendRel(WireCodec.encodeEraseEnd())
    }

    override fun onLassoMoveCommit(page: Int, box: FloatArray, dx: Float, dy: Float, poly: FloatArray) {
        listener?.sendCtl(
            WireCodec.encodeLassoMove(page.toLong(), box[0], box[1], box[2], box[3], dx, dy, poly),
        )
    }

    override fun onLassoScaleCommit(
        page: Int, box: FloatArray, ax: Float, ay: Float, sx: Float, sy: Float, poly: FloatArray,
    ) {
        listener?.sendCtl(
            WireCodec.encodeLassoScale(page.toLong(), box[0], box[1], box[2], box[3], ax, ay, sx, sy, poly),
        )
    }

    override fun onNoteUpsert(id: String, page: Int, nx: Float, ny: Float, text: String, display: Int) {
        listener?.sendCtl(
            WireCodec.encodeTextNote(id, WireCodec.NOTE_UPSERT, page.toLong(), nx, ny, text, display),
        )
    }

    override fun onNoteDelete(id: String, page: Int, nx: Float, ny: Float) {
        listener?.sendCtl(WireCodec.encodeTextNote(id, WireCodec.NOTE_DELETE, page.toLong(), nx, ny, ""))
    }

    override fun onProbeBegin(page: Int, nx: Float, ny: Float) {
        listener?.sendRel(WireCodec.encodeProbeBegin(page.toLong(), listOf(Pt2(nx, ny))))
    }

    override fun onProbeMove(pts: List<Pt2>) {
        listener?.sendRel(WireCodec.encodeProbeMove(pts))
    }

    override fun onProbeEnd() {
        listener?.sendRel(WireCodec.encodeProbeEnd())
    }

    override fun onHoverMove(page: Int, nx: Float, ny: Float) {
        listener?.sendUnrel(WireCodec.encodeHoverMove(page.toLong(), nx, ny))
    }

    override fun onHoverEnd() {
        listener?.sendUnrel(WireCodec.encodeHoverEnd())
    }

    override fun onModeChanged(mode: Int) {
        listener?.sendCtl(WireCodec.encodeMode(mode))
    }

    override fun onPenSelected(index: Int) {
        listener?.sendCtl(WireCodec.encodePen(index))
    }

    override fun onGotoPage(page: Int) {
        listener?.sendCtl(WireCodec.encodeGotoPage(page.toLong()))
    }

    override fun onScrollReport(page: Int, frac: Float) {
        listener?.sendUnrel(WireCodec.encodeScroll(page.toLong(), frac, System.currentTimeMillis().toDouble()))
    }

    override fun onContentWidthChanged(dpWidth: Float) {
        listener?.sendCtl(WireCodec.encodePadGeom(dpWidth))
    }

    override fun onMoveFrame() {
        listener?.onMoveFrame()
    }

    override fun onHudChanged() {
        super.onHudChanged()
        listener?.onHudChanged()
    }

    override fun onOpenNoteEditor(
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean, display: Int,
    ) {
        listener?.onOpenNoteEditor(id, page, nx, ny, text, isNew, display)
    }

    override fun onDocumentReset() {
        vpSeq = 0
    }

    // ---------- Mac 下发 → 基类状态 ----------

    /** 收 layout：v 变化 = 换文档（notes 等 Mac 重发，不清免闪空） */
    fun setLayout(docId: String, v: String, count: Int, pages: List<Pair<Float, Float>>) {
        val newV = v.ifEmpty { docId }
        val changed = newV != docV
        docV = newV
        setPages(count, pages, reset = changed)
    }

    /** 收 viewport：书写中忽略（基类判）；force 绕 seq 去重；不回发 */
    fun applyViewport(page: Long, frac: Float, seq: Long, force: Boolean) {
        if (!force) {
            if (seq <= vpSeq) return
            vpSeq = seq
        }
        scrollToPageFrac(page.toInt(), frac)
    }

    fun setRadial(m: WireCodec.Msg.Radial) {
        setRadial(m.open, m.page.toInt(), m.cx, m.cy, m.highlight, m.items)
    }

    fun setPressRing(m: WireCodec.Msg.PressRing) {
        setPressRing(m.on, m.page.toInt(), m.nx, m.ny)
    }

    // ---------- 草稿纸图钉（scratchpads 列表的 page/nx/ny；手指单击开纸，不认笔——同模式1/web） ----------

    /** 一枚图钉：(scratchpads 列表下标, 页, 页内归一化锚点)。模式2 不开库，身份就是下标 */
    class ScratchPin(val index: Int, val page: Int, val nx: Float, val ny: Float)

    private var scratchPins = listOf<ScratchPin>()

    fun setScratchPins(list: List<ScratchPin>) {
        scratchPins = list
        invalidate()
    }

    /** 视口中心落在哪页的哪个归一化点（「在当前位置新建草稿纸」的锚点）；落在页缝给该页中心 */
    fun viewportCenterAnchor(): Triple<Int, Float, Float> {
        val loc = locate(width / 2f, barH + availH / 2f)
        return if (loc != null) Triple(loc.page, loc.nx, loc.ny)
        else Triple(topVisiblePage(), 0.5f, 0.5f)
    }

    /** 图钉半径（dp）：随页宽走但夹取，缩得再小也点得着（同 LocalCanvasView/web 的 clamp(…,11,18)） */
    private fun pinRadiusDp(): Float = (pw() / density * 0.016f).coerceIn(11f, 18f)

    /** 图钉热区命中（热区比画出来的略大，同 web padPinHit 的 max(r+6, 22)；后建的压在上面，命中先算它） */
    private fun pinAt(x: Float, y: Float): ScratchPin? {
        val hot = dp(max(pinRadiusDp() + 6f, 22f))
        for (i in scratchPins.indices.reversed()) {
            val p = scratchPins[i]
            if (p.page !in 0 until pageCount) continue
            val vx = viewX(p.page, p.nx)
            val vy = viewY(p.page, p.ny)
            if (abs(x - vx) <= hot && abs(y - vy) <= hot) return p
        }
        return null
    }

    override fun onFingerTap(x: Float, y: Float): Boolean {
        val pin = pinAt(x, y) ?: return false
        listener?.onScratchPinTap(pin.index)
        return true
    }

    // ---------- 图钉拖动（同页内挪锚点：本地乐观移动，松手发 scratchMove，scratchpads 回推为权威） ----------

    private var dragPin: ScratchPin? = null
    private var pinsBackup: List<ScratchPin>? = null
    private val dragLoc = FloatArray(2)

    override fun fingerPinHit(x: Float, y: Float): Boolean {
        dragPin = pinAt(x, y)
        pinsBackup = if (dragPin != null) scratchPins else null
        return dragPin != null
    }

    /** 乐观移动：只挪锚点那一枚（页不变，钳位 0~1 由 pageLocClamped 做） */
    private fun movePinTo(x: Float, y: Float) {
        val p = dragPin ?: return
        pageLocClamped(x, y, p.page, dragLoc)
        scratchPins = scratchPins.map {
            if (it.index == p.index) ScratchPin(it.index, it.page, dragLoc[0], dragLoc[1]) else it
        }
        invalidate()
    }

    override fun onPinDragMove(x: Float, y: Float) = movePinTo(x, y)

    override fun onPinDragEnd(x: Float, y: Float) {
        movePinTo(x, y)
        val p = dragPin ?: return
        dragPin = null
        pinsBackup = null
        listener?.onScratchPinMove(p.index, dragLoc[0], dragLoc[1])
    }

    override fun onPinDragCancel() {
        pinsBackup?.let { scratchPins = it; invalidate() }
        dragPin = null
        pinsBackup = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 形制与 LocalCanvasView 的图钉一致（圆角方片 + 两道「字迹」，与文字笔记的圆形蓝底分得清）
        if (scratchPins.isEmpty()) return
        val r = dp(pinRadiusDp())
        val corner = r * 0.34f
        for (p in scratchPins) {
            if (p.page !in 0 until pageCount) continue
            val x = viewX(p.page, p.nx)
            val y = viewY(p.page, p.ny)
            if (y < barH - r || y > height + r || x < -r || x > width + r) continue
            pinRect.set(x - r, y - r, x + r, y + r)
            pinPaint.style = Paint.Style.FILL
            pinPaint.color = Color.argb(245, 246, 248, 252)
            canvas.drawRoundRect(pinRect, corner, corner, pinPaint)
            pinPaint.style = Paint.Style.STROKE
            pinPaint.strokeWidth = dp(1.5f)
            pinPaint.color = Color.argb(217, 31, 111, 235)
            canvas.drawRoundRect(pinRect, corner, corner, pinPaint)
            pinPaint.strokeWidth = max(dp(1.2f), r * 0.14f)
            pinPaint.color = Color.argb(230, 31, 111, 235)
            canvas.drawLine(x - r * 0.45f, y - r * 0.18f, x + r * 0.45f, y - r * 0.18f, pinPaint)
            canvas.drawLine(x - r * 0.45f, y + r * 0.28f, x + r * 0.1f, y + r * 0.28f, pinPaint)
        }
    }

    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pinRect = RectF()
}
