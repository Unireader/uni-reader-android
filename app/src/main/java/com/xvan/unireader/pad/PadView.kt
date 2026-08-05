package com.xvan.unireader.pad

import android.content.Context
import android.util.AttributeSet
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3

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
        fun onOpenNoteEditor(id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean)
    }

    var listener: Listener? = null

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
        // Mac 每收到一批擦除点就回一次 strokes 广播（`AppModel.inkErase`）。记账，好让基类分得清
        // 哪些回推是「擦到一半的中途快照」（见 PageCanvasView.setStrokes）。
        expectStrokesEcho()
        listener?.sendRel(WireCodec.encodeEraseMove(page.toLong(), pts))
    }

    override fun onEraseEnd() {
        listener?.sendRel(WireCodec.encodeEraseEnd())
    }

    override fun onLassoMoveCommit(page: Int, box: FloatArray, dx: Float, dy: Float) {
        listener?.sendCtl(WireCodec.encodeLassoMove(page.toLong(), box[0], box[1], box[2], box[3], dx, dy))
    }

    override fun onNoteUpsert(id: String, page: Int, nx: Float, ny: Float, text: String) {
        listener?.sendCtl(WireCodec.encodeTextNote(id, WireCodec.NOTE_UPSERT, page.toLong(), nx, ny, text))
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
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean,
    ) {
        listener?.onOpenNoteEditor(id, page, nx, ny, text, isNew)
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
}
