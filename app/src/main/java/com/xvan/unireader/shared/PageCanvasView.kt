package com.xvan.unireader.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.xvan.unireader.R
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 连续页流画布：**几何 + 输入 + 渲染，不含「提交给谁」**（`ANDROID-STANDALONE-PLAN.md §5.1`）。
 * 由模式2 输入板的 `PadView` 原地下移而来，两种模式共用同一份：
 * - 连续页面列几何：dispH/offY/totalH（GAP=8dp），scrollY/scrollX 为唯一滚动真源
 * - 笔（stylus）= 落墨/擦除/翻页平移/框选 + hover；手指 = 单指平移（死区 + 松手惯性）+ 双指捏合缩放
 *   笔在写时忽略手指；`getTouchMajor()` 超阈值的手掌忽略
 * - 笔迹以外部回填的 `setStrokes` 为真源，本地 cur 半笔即时回显；这套「乐观预览 + 真源回推」在
 *   模式2 里真源是 Mac，在模式1 里真源就在进程内（落库后自己回推），**回推路径完全一样**
 * - 环形选笔盘/长按进度环：本类只负责画，判定方是谁由子类决定（模式2=Mac，模式1=本地）
 * - 滚动上报 16ms 节流（等价 rAF）：模式2 让 Mac 跟随，模式1 用来存阅读进度
 *
 * 单位：长度常量在 `PadConst` 里是 dp，本类统一乘 density 折成像素；`onContentWidthChanged` 报出去的
 * 页宽也是 dp（Mac 的环形盘取消区半径按它换算，见 PadConst 顶部注释）。
 */
open class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), PageMapper {

    // ---------- 提交口：两种模式只覆写这些 ----------
    //
    // 这些钩子长在原先「发 UDP/WS 帧」的**同一位置**，所以模式2 的行为按构造保持不变：
    // `pad/PadView` 覆写它们去编帧发送，模式1 的本地版覆写它们去落库（M3）。
    // 基类不知道、也不需要知道提交给谁。

    /** 落笔（pt 已是页内归一化 + 压感；line = 落笔那一刻锁定的尺子状态） */
    protected open fun onInkBegin(page: Int, pen: Pen, pt: Pt3, line: Boolean) {}
    protected open fun onInkMove(pts: List<Pt3>) {}
    protected open fun onInkEnd() {}
    protected open fun onErase(page: Int, pts: List<Pt2>) {}

    /** 一次擦除拖动**动手之前**（模式1 的撤销栈在这里留快照；模式2 用不上） */
    protected open fun onEraseBegin() {}
    protected open fun onEraseEnd() {}

    /** 一次擦除拖动是否正在进行（[eraseHit] 置、抬笔清；只为 [onEraseBegin] 的「一次一叫」） */
    private var erasingGesture = false

    /**
     * 本端**已发出的最后一个 REL 序号**（模式2 接到 `UdpSender.sentRel`；模式1 恒 0 = 不适用）。
     *
     * 紧跟在某个提交钩子之后读，拿到的就是**那一帧自己**的序号——`sendRel` 是同步定序的，
     * 见 `UdpSender.sendRel`。乐观笔迹与擦除的对账全靠它（见 [pendingInk] / [lastEraseRel]）。
     */
    protected open fun sentRelSeq(): Long = 0L

    /**
     * 框选移动提交：box 为归一化 x0,y0,x1,y1、poly 为自由框选路径（扁平数组，≥3 点）——
     * 真源侧要拿它们重新判定命中（多边形优先，见 ../PROTOCOL.md `lassoMove`），不信本地下标
     */
    protected open fun onLassoMoveCommit(page: Int, box: FloatArray, dx: Float, dy: Float, poly: FloatArray) {}

    /**
     * 框选缩放提交：box/poly 同上；`(ax, ay)` = 缩放锚点（被拖手柄的对侧手柄），
     * `(sx, sy)` = 按轴缩放比（已 clamp 0.05...20）——真源侧复判命中后 `InkEdit.scaled`
     */
    protected open fun onLassoScaleCommit(
        page: Int, box: FloatArray, ax: Float, ay: Float, sx: Float, sy: Float, poly: FloatArray,
    ) {}
    /** 选中集有无变化的对外回调（顶栏据此灰掉/亮起剪切与复制）。两模式都是设一个 lambda 就够。 */
    var onLassoSelChanged: ((Boolean) -> Unit)? = null

    /** 选中集有无发生变化（顶栏剪贴板按钮的可用性；只在 0↔非 0 的翻转时叫一次） */
    protected open fun onLassoSelectionChanged(has: Boolean) {
        onLassoSelChanged?.invoke(has)
    }

    /**
     * 撤销 / 重做。**模式2 只发意图**（栈在 Mac，见 `../PROTOCOL.md §4.1`），
     * **模式1 本机就是真源**（自己一份栈）。两边都不做乐观预览：撤销要么整步成立要么不动。
     */
    protected open fun onUndoRequested(redo: Boolean) {}

    /**
     * 剪切 / 复制 / 粘贴。`op` 用 `CLIP_*`；copy/cut 带选区多边形（真源侧复判命中，不信本地下标），
     * paste 带落点 `(nx, ny)`（页内归一化，内容包围盒中心对齐到它）。
     */
    protected open fun onClipCommit(op: Int, page: Int, nx: Float, ny: Float, poly: FloatArray?) {}

    protected open fun onNoteUpsert(
        id: String, page: Int, nx: Float, ny: Float, text: String, display: Int = NOTE_TAP,
    ) {}
    protected open fun onNoteDelete(id: String, page: Int, nx: Float, ny: Float) {}

    /** 长按检测用的笔位置流（模式2 由 Mac 判长按呼出选笔盘；模式1 本地判，M6） */
    protected open fun onProbeBegin(page: Int, nx: Float, ny: Float) {}
    protected open fun onProbeMove(pts: List<Pt2>) {}
    protected open fun onProbeEnd() {}

    protected open fun onHoverMove(page: Int, nx: Float, ny: Float) {}
    protected open fun onHoverEnd() {}
    protected open fun onModeChanged(mode: Int) {}
    protected open fun onPenSelected(index: Int) {}
    protected open fun onGotoPage(page: Int) {}

    /** 视口滚动上报（16ms 节流）：模式2 让 Mac 跟随，模式1 存 read_page/read_frac */
    protected open fun onScrollReport(page: Int, frac: Float) {}

    /** 内容页宽变化（dp）：模式2 要上报给 Mac 当环形盘的像素判定基准 */
    protected open fun onContentWidthChanged(dpWidth: Float) {}

    /** 每发一帧 ink/erase move（模式2 的 mv/s 计数） */
    protected open fun onMoveFrame() {}

    /** 文字笔记模式下点页面：打开编辑器（isNew=false 时是点中了已有笔记） */
    protected open fun onOpenNoteEditor(
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean,
        display: Int = NOTE_TAP,
    ) {}

    // —— 书签缎带（`../REQUIREMENTS.md §1.9`）：贴页右缘、纵向落在书签自己的页内位置上 ——
    //
    // 两模式共用这一份：书签的身份两边都是 UUID 串（草稿纸图钉那边模式2 用列表下标、模式1 用
    // 库 id，所以那个只能各写各的）。**只认手指不认笔**——笔是用来写字的，让笔点标记必然会在
    // 标记上落笔时误触发（同草稿纸图钉与 web `endTouch` 的决策）。

    /** 页面上的一枚书签标记 */
    class BookmarkMark(val id: String, val page: Int, val frac: Float, val title: String)

    private var bookmarkMarks = listOf<BookmarkMark>()

    /** 手指点了页面上某枚书签缎带（宿主弹改名/删除） */
    var onBookmarkTap: (BookmarkMark) -> Unit = {}

    fun setBookmarkMarks(list: List<BookmarkMark>) {
        bookmarkMarks = list
        invalidate()
    }

    /** 缎带中心的视口坐标（贴页右缘，纵向按 frac 钳在页内）。页号越界返回 false。 */
    private fun ribbonAt(b: BookmarkMark, out: FloatArray): Boolean {
        if (b.page < 0 || b.page >= pageCount) return false
        val h = dispH[b.page]
        val half = dp(PadConst.BM.H) / 2f
        out[0] = viewX(b.page, 1f) - dp(PadConst.BM.W) / 2f
        out[1] = barH + offY[b.page] + (b.frac * h).coerceIn(half, (h - half).coerceAtLeast(half)) - scrollY
        return true
    }

    private fun drawBookmarkRibbons(canvas: Canvas) {
        if (bookmarkMarks.isEmpty()) return
        val margin = dp(PadConst.BM.H)
        for (b in bookmarkMarks) {
            if (!ribbonAt(b, tmpRibbon)) continue
            if (tmpRibbon[1] < barH - margin || tmpRibbon[1] > height + margin) continue   // 不在视口里就别画
            overlays.drawBookmarkRibbon(canvas, tmpRibbon[0], tmpRibbon[1])
        }
    }

    /** 手指轻点是否命中某枚缎带（热区比缎带本身放宽一圈——手指不比笔尖准） */
    private fun bookmarkHit(x: Float, y: Float): BookmarkMark? {
        val padX = dp(PadConst.BM.W) / 2f + dp(6f)
        val padY = dp(PadConst.BM.H) / 2f + dp(6f)
        for (b in bookmarkMarks) {
            if (!ribbonAt(b, tmpRibbon)) continue
            if (abs(x - tmpRibbon[0]) <= padX && abs(y - tmpRibbon[1]) <= padY) return b
        }
        return null
    }

    private val tmpRibbon = FloatArray(2)

    /**
     * 单指**轻点**（全程没越过平移死区、也没变过双指捏合）。模式1 用它点草稿纸图钉。
     * 只认手指、不认笔——平板上笔是用来写字的，让笔点图钉必然会在图钉上落笔时误触发
     * （web `input.ts endTouch` 的同款决策）。
     */
    /// 返回 true = 这一下被子类吃掉了（点开了草稿纸）；false 时基类接着判文字笔记（[tapNote]）。
    protected open fun onFingerTap(x: Float, y: Float): Boolean = false

    // —— 图钉拖动（草稿纸图钉页内挪锚点；手指专用，同 onFingerTap 的只认手指决策） ——
    //
    // 手势复用单指平移的死区：落点在图钉热区内（[fingerPinHit] 命中）且**拖过死区**才开始拖图钉，
    // 没过死区松手照旧走 onFingerTap（单击开纸语义不变）；落在图钉外则与以前一模一样地平移。

    /** 手指落点（视口坐标）是否命中图钉热区。命中时子类记下是哪枚，后续由 onPinDrag* 回报 */
    protected open fun fingerPinHit(x: Float, y: Float): Boolean = false

    /** 图钉拖动中（视口坐标）：子类做乐观移动（真源回推为权威，同乐观笔迹惯例） */
    protected open fun onPinDragMove(x: Float, y: Float) {}

    /** 松手提交（视口坐标）：子类换算成页内归一化锚点后上行/落库（钳位 0~1 用 pageLocClamped） */
    protected open fun onPinDragEnd(x: Float, y: Float) {}

    /** 拖动被打断（变双指/落笔/事件取消）：子类把乐观位移撤回到拖动前的位置 */
    protected open fun onPinDragCancel() {}

    /** 本次手指手势正在拖动图钉（已过死区） */
    protected var pinDragActive = false
    private var pinDragCandidate = false   // 落点命中图钉、还没过死区

    // 划字（选字模式）：与拖图钉共用单指平移那套死区判定，故状态位也放在一起
    private var textSelCandidate = false   // 选字模式 + 落点有文本层、还没过死区
    private var textSelDragging = false    // 已过死区，这一次手势是在划字（不平移、不甩惯性）

    /** 页码/缩放/工具状态变化 → 宿主刷新顶栏。两种模式的顶栏不同，故用回调 */
    var onHud: (() -> Unit)? = null
    protected open fun onHudChanged() { onHud?.invoke() }

    /** 页图从哪来（§5.1 的另一个注入口）：模式2 = HTTP 取 Mac 的 page.png，模式1 = 本机 Pdfium */
    var imageSource: PageImageSource? = null

    companion object {
        /** 两模式共用这一个 tag：几何/手势/命中都在基类，出问题时要能一把捞出来 */
        const val TAG = "UniReader/Canvas"

        const val ERASE_R_FALLBACK = 0.02f   // 橡皮归一化半径默认值（Mac eraser 消息到达前）

        /** 乐观笔迹的本地 id 前缀（见 `pendingInk`）：日志里一眼认出「这条还没被真源确认」 */
        const val OPT_INK_PREFIX = "opt:"

        /** 乐观笔迹等真源的兜底时限：超了就撤，别把真源里根本没有的笔迹永远挂在屏幕上 */
        const val OPT_INK_TIMEOUT_MS = 3000L

        /** 选字模式下「双击选整行」的两击间隔上限（系统 ViewConfiguration 默认也是 300ms） */
        const val DOUBLE_TAP_MS = 300L

        /** 通用笔记图钉的底色 = Mac `PageCellView.noteMarker`（1, 0.80, 0.15）换算成 0~255 */
        val GENERAL_PIN_RGB = intArrayOf(255, 204, 38)

        /** 绘制耗时打点的窗口（帧）：够算出稳定均值，又不至于把日志刷满 */
        const val DRAW_STAT_FRAMES = 120

        /** 内置兜底笔（Mac PenPresets.defaults；首连前用，pens 消息到达后整体替换） */
        val FALLBACK_PENS = listOf(
            Pen(24, 90, 210, 0.95f, 8f, 0),     // 蓝 ballpoint
            Pen(220, 40, 40, 0.95f, 9f, 1),     // 红 fountain
            Pen(20, 20, 20, 0.95f, 10f, 3),     // 黑 pencil
            Pen(255, 214, 40, 0.40f, 22f, 2),   // 荧光 marker
        )
    }

    protected val density = resources.displayMetrics.density
    protected fun dp(v: Float) = v * density

    protected val gapPx = dp(PadConst.GAP)
    protected val palmPx = dp(PadConst.PALM)
    protected val deadPx = dp(PadConst.DEAD)
    protected val lassoDeadPx = dp(PadConst.LASSO_DEAD)

    protected val ink = InkRenderer(density)
    protected val overlays = PadOverlays(density)

    // —— 工具状态（Mac 推送为运行时唯一源，内置 4 支仅兜底） ——
    // 注意：setter 保持 private——`protected set` 生成的 setMode(I)/setPenIndex(I) 会与下面
    // 同名的 setMode()/setPenIndex() 撞 JVM 签名。改工具状态一律走那两个函数。
    var mode = MODE_NOTE
        private set
    var penIndex = 0
        private set
    protected val pens = ArrayList(FALLBACK_PENS)

    /** 文字笔记模式：独立本地开关，只改笔落下时的分派（点页面开编辑器，不写字） */
    var noteMode = false
        protected set

    /** 尺子模式：独立本地开关，note 模式下笔迹吸附 45° 倍数直线 */
    var rulerOn = false
        protected set

    /** 页图显示开关（false = 纯手写板，只白底不取图） */
    var showPage = true
        protected set

    /** 夜间模式：只反转页图（墨迹/圆环不反） */
    var night = false
        protected set

    /** 锁定缩放：双指仍可整体拖动，但不改 zoom */
    var zoomLocked = false
        protected set

    /**
     * 锁定水平滚动：内容的**横向位置不再跟手改变**（单指/双指拖动、松手惯性一律只走纵向）。
     *
     * 用在放大了看、或画板模式下页边写字的时候：那两种情形里横向位置是刻意调好的，
     * 而竖着划一道很难不带一点横向分量，页面于是慢慢往旁边飘。锁缩放挡的是"写着写着变大小"，
     * 这条挡的是"写着写着跑偏"，两件事各一个开关。
     *
     * **缩放引起的横向重锚不受影响**：捏合改了 zoom 时 `scrollX` 照旧跟着锚点比例走，
     * 否则放大后画面会横向乱跳；只有"zoom 没变的双指整体挪动"才被这条挡下（见 [pinchMove]）。
     */
    var hLocked = false
        protected set

    /**
     * 双指滚动模式（防误触）：**单指划动不再平移页面**，滚动与缩放一律双指。
     *
     * 已有的两道防线（笔落下时忽略手指、接触面积大于 [PadConst.PALM] 的忽略）挡不住
     * 「落笔之前虎口/小指先蹭到屏幕」——那一下面积不大、笔也还没 down，于是被当成正经的单指平移，
     * 页面直接滑走。开了这个开关后那一下什么都不做。
     *
     * 单指仍然能做的两件事都是刻意动作、不会是误触：轻点图钉开草稿纸、按住图钉拖动。
     */
    var twoFingerScroll = false
        protected set

    /**
     * 书写锁定（用户 2026-09-26 提，09-27 改口径：锁的是**切笔本身**；`../PROTOCOL.md` `lock` 0x59）：
     * 锁定后只能在「当前这支笔 ↔ 橡皮」之间来回——[cyclePen] 在笔记档不再轮替、[selectPen] 只认当前笔，
     * [cycleMode] 只在笔记/擦除两档来回、[setModeLocal] 也拦下切到其它档（翻页/框选/选字）。本机专属状态（同 `zoomLocked` 那一批），模式1 存 `ToolPrefs`；
     * 模式2 额外把改动上行给 Mac、也接受 Mac 广播下来的改动（[onLockChanged]）。
     */
    var writingLocked = false
        protected set

    fun toggleWritingLock() {
        writingLocked = !writingLocked
        onLockChanged(writingLocked)
        onHudChanged()
    }

    /** 从远端（Mac）应用锁定状态，不再回发（同 `setEraserLocal` 的「应用而不广播」）。 */
    fun setWritingLockedLocal(v: Boolean) {
        if (v == writingLocked) return
        writingLocked = v
        onHudChanged()
    }

    /** 锁定状态变了（本地触发，不含 [setWritingLockedLocal] 的远端应用）：模式2 覆写它上行给 Mac。 */
    protected open fun onLockChanged(locked: Boolean) {}

    /**
     * 相对粗细模式（用户 2026-09-26 提；`../PROTOCOL.md` `relInk` 0x5A）：开着时落笔那一刻按**本机**当前
     * [zoom] 把预设粗细折算细（[strokePen]），放大写的字缩回原尺寸看就是当初视觉粗细的缩影；关着是绝对粗细。
     * 折算在本端做：模式1 直接落库，模式2 上行的 `pen.w` 已是折算后的值（Mac 拿不到 pad 的缩放，原样用）。
     * 草稿纸 / 画板的笔迹经 `ScratchCanvas.Tools.relativeInk` 按画布自己的缩放折算。模式1 存 `ToolPrefs`，
     * 模式2 与 Mac 双向同步（同 [writingLocked] 那套）。
     */
    var relativeInkWidth = false
        protected set

    fun toggleRelativeInkWidth() {
        relativeInkWidth = !relativeInkWidth
        onRelInkChanged(relativeInkWidth)
        onHudChanged()
    }

    /** 从远端（Mac）应用，不再回发。 */
    fun setRelativeInkWidthLocal(v: Boolean) {
        if (v == relativeInkWidth) return
        relativeInkWidth = v
        onHudChanged()
    }

    /** 相对粗细开关变了（本地触发）：模式2 覆写它上行给 Mac。 */
    protected open fun onRelInkChanged(on: Boolean) {}

    /** 这一笔实际用的笔：相对粗细开着时按当前缩放折算。 */
    protected fun strokePen(p: Pen): Pen = if (relativeInkWidth && zoom > 0.01f) p.copy(w = p.w / zoom) else p

    fun penList(): List<Pen> = pens
    fun curPenOrNull(): Pen? = pens.getOrNull(penIndex)
    /**
     * 本端的模式表。基类（= 模式2）用**线上契约那四档**；模式1 覆写成
     * [PadConst.LOCAL_MODE_LABELS]（多一档本机专属的「选字」，见 [MODE_TEXT]）。
     *
     * 🔴 别把第五档写进 [PadConst.MODE_LABELS]：模式2 的模式键会循环到它、
     * 然后把一个 Mac 不认识的 `mode=4` 发过去。
     */
    protected open val modeLabels: List<String> get() = PadConst.MODE_LABELS

    fun modeLabel(): String = modeLabels.getOrElse(mode) { "笔记" }
    fun penLabel(): String = PadConst.brushLabel(brushName(pens[penIndex].brush))

    protected fun curPenPreset() = pens[penIndex]

    /** 本地切模式 → 同步给 Mac；切走框选即放弃选中（残留高亮框会误导） */
    fun cycleMode() {
        if (activePen) endPen()   // 切换前正常收笔
        val leavingLasso = mode == MODE_LASSO
        val leavingText = mode == MODE_TEXT
        // 书写锁定：只在 笔记/擦除 间来回，不走完整的那一圈（翻页/框选/选字收起）
        mode = if (writingLocked) (if (mode == MODE_ERASE) MODE_NOTE else MODE_ERASE)
               else (mode + 1) % modeLabels.size
        eraserRingAt = null
        if (leavingLasso) clearLasso()
        if (leavingText) clearTextSelection()   // 切走选字：残留的蓝块会误导（同框选那条）
        endHover()
        onModeChanged(mode)
        invalidate()
        onHudChanged()
    }

    /** 非笔模式按切笔键 = 恢复之前那支笔，不轮替下一支；笔模式下才轮替（同 capture.ts cyclePen） */
    fun cyclePen() {
        // 切换前正常收笔（与 cycleMode 一致）。**不收笔就等于没反应**：`curStrokePen` 是落笔那一刻
        // 锁进这一笔的，中途换笔不会改写已经在画的这条，于是用户「一直写字不断笔、按一下切笔」时
        // penIndex 明明变了、画面却一点变化都没有（用户实测报的就是这个）。收掉这一笔，下一笔立刻是新笔。
        if (writingLocked && mode == MODE_NOTE) return   // 书写锁定：已经是这支笔，不轮替（橡皮档按它仍是回到笔）
        if (activePen) endPen()
        if (mode == MODE_NOTE) penIndex = (penIndex + 1) % pens.size
        if (mode == MODE_LASSO) clearLasso()
        if (mode == MODE_TEXT) clearTextSelection()   // 切走选字：残留的蓝块会误导（同框选那条）
        mode = MODE_NOTE
        onPenSelected(penIndex)
        onModeChanged(mode)
        invalidate()
        onHudChanged()
    }

    /** 键盘直切模式（n/v/l 键）：本地切 → 同步 Mac；切走框选即放弃选中。与 cycleMode 同收尾，只是目标指定。 */
    fun setModeLocal(m: Int) {
        if (m !in modeLabels.indices || m == mode) return
        if (writingLocked && m != MODE_NOTE && m != MODE_ERASE) return   // 锁定时拦下切到其它档
        if (activePen) endPen()
        if (mode == MODE_LASSO) clearLasso()
        if (mode == MODE_TEXT) clearTextSelection()   // 切走选字：残留的蓝块会误导（同框选那条）
        mode = m
        eraserRingAt = null
        endHover()
        onModeChanged(mode)
        invalidate()
        onHudChanged()
    }

    /** e 键：橡皮 ⇄ 笔记 来回切（Mac 单键监视器 / web toggleErase 同语义）。 */
    fun toggleEraser() = setModeLocal(if (mode == MODE_ERASE) MODE_NOTE else MODE_ERASE)

    /** 数字键直选某支笔：与 cyclePen 同语义（选笔即回笔记模式），只是指定槽位不轮替。 */
    fun selectPen(i: Int) {
        if (i !in pens.indices) return
        if (writingLocked && i != penIndex) return   // 书写锁定：只许回到当前这支笔
        if (activePen) endPen()
        penIndex = i
        if (mode == MODE_LASSO) clearLasso()
        if (mode == MODE_TEXT) clearTextSelection()   // 切走选字：残留的蓝块会误导（同框选那条）
        mode = MODE_NOTE
        onPenSelected(penIndex)
        onModeChanged(mode)
        invalidate()
        onHudChanged()
    }

    fun toggleNoteMode() {
        noteMode = !noteMode
        onHudChanged()
    }

    fun toggleRuler() {
        rulerOn = !rulerOn
        onHudChanged()
    }

    fun toggleShowPage() {
        showPage = !showPage
        if (showPage) ensureImages()
        invalidate()
        onHudChanged()
    }

    fun toggleNight() {
        night = !night
        invalidate()
        onHudChanged()
    }

    fun toggleZoomLock() {
        zoomLocked = !zoomLocked
        onHudChanged()
    }

    fun toggleTwoFingerScroll() {
        twoFingerScroll = !twoFingerScroll
        onHudChanged()
    }

    fun toggleHLock() {
        hLocked = !hLocked
        cancelMomentum()   // 正在甩的那一下也得当场停住横向，不然开关按下去还会飘一段
        onHudChanged()
    }

    /** 连接建立后把本地工具状态推给 Mac（同 capture 的 authOK 分支） */
    fun syncToolState() {
        onModeChanged(mode)
        onPenSelected(penIndex)
        lastGeomW = -1f
        emitGeom()   // 重连后 Mac 那边的页宽是空的，无条件补一发
    }

    /** 收 pens：整体替换本地列表（Mac 画布悬浮工具条实时增删改后推下来） */
    fun setPens(list: List<Pen>, active: Int) {
        pens.clear()
        pens.addAll(list)
        if (pens.isEmpty()) pens.addAll(FALLBACK_PENS)
        penIndex = active.coerceIn(0, pens.size - 1)
        onHudChanged()
    }

    /** 本地改笔宽：即时生效（画/回显读的就是它），上行由调用方防抖发 penset */
    fun setPenWidth(index: Int, w: Float) {
        val p = pens.getOrNull(index) ?: return
        pens[index] = p.copy(w = w)
        invalidate()
        onHudChanged()
    }

    /** 收 pen：Mac 侧切笔回推 */
    fun setPenIndex(i: Int) {
        if (i in pens.indices && i != penIndex) {
            penIndex = i
            onHudChanged()
        }
    }

    /**
     * 收 mode：Mac 侧切模式回推（悬浮工具条/环形盘选笔后回 note）。
     * 判据用**线上那张表**（不是 [modeLabels]）——Mac 只会发 0..3，发别的一律不认。
     */
    fun setMode(m: Int) {
        if (m in PadConst.MODE_LABELS.indices && m != mode) {
            if (mode == MODE_LASSO) clearLasso()   // 被 Mac 切走框选工具：同本地切模式
            if (mode == MODE_TEXT) clearTextSelection()
            mode = m
            eraserRingAt = null
            invalidate()
            onHudChanged()
        }
    }

    // —— 橡皮设置（随 eraser 消息双向同步；size = 归一化半径＝页宽比） ——
    var eraserSize = ERASE_R_FALLBACK
        protected set
    var eraserMode = 1          // 0=整笔 1=局部
        protected set
    var eraserRing = true
        protected set
    protected var eraserRingAt: FloatArray? = null   // 圆环位置（视口 px），null=不画

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
    protected var pageCount = 0
    protected var pagesWH = listOf<Pair<Float, Float>>()
    protected var dispH = FloatArray(0)
    protected var offY = FloatArray(0)
    protected var totalH = 0f
    protected var zoom = 1f
    protected var scrollX = 0f
    protected var scrollY = 0f
    protected var maxScrollX = 0f
    protected var maxScrollY = 0f
    protected var barH = 0f          // 顶栏高度（内容垂直偏移）
    protected var vw = 1f
    protected var availH = 1f
    protected var lastGeomW = -1f    // padGeom 上报去重（dp）

    protected fun pw() = vw * zoom                                   // 页宽

    // —— 画板模式（../PROTOCOL.md `canvas`）：页面两侧的空白也可书写，内容因此比页面宽 ——
    // 页边笔迹仍是页内笔迹，只是归一化 x 越出 0…1。模式2 由 Mac 下发 `canvas` 设定（唯一真源），
    // 模式1 自己从库里的 canvas_mode + 笔迹越界量算（`CanvasMargin`）。
    // 关着时 cmargin()==0，下面几个函数全部退化成画板模式之前的老式子。

    /** 画板模式开关（两模式各自的入口写它，改完必须 onGeomChanged） */
    protected var canvasOn = false
    /** 每侧页边宽度（**页宽的倍数**）；见 [CanvasMargin] */
    protected var canvasMargin = 0f

    /** 当前是否开着画板模式（顶栏开关的选中态；两模式共用） */
    fun canvasModeOn(): Boolean = canvasOn

    /** 每侧页边宽度（页宽的倍数），关着即 0 */
    protected fun cmargin(): Float = if (canvasOn) max(0f, canvasMargin) else 0f

    /**
     * **框选编辑**（移动/缩放落库）用的页边上限：画板开着就是硬上限 [CanvasMargin.LIMIT]，关着是 0。
     * 不用 [cmargin]（当前那一档软边界）—— 软边界是「跟着笔迹长出来的」，提交后
     * `refreshCanvasMargin()` 立刻会跳到够用的档位；提交那一刻还拿旧档位卡着，等于禁止把笔迹
     * 挪进「还没长出来」的那片页边，撞上去就被逐点 clamp 压扁。同 Mac `lassoEditXRange`。
     */
    protected fun cmarginMax(): Float = if (canvasOn) CanvasMargin.LIMIT else 0f
    /** 可滚动内容总宽（页 + 两侧页边） */
    protected fun contentWidth(): Float = pw() * (1f + 2f * cmargin())
    /** 内容左缘视口 x（页边最左，画页边纸用）；窄于视口时整体居中 */
    protected fun canvasLeft(): Float {
        val c = contentWidth()
        return if (c <= vw) (vw - c) / 2f else -scrollX
    }
    /** **页**左缘视口 x（页内归一化 x=0 处）——所有页内坐标换算都用它 */
    protected fun contentLeft(): Float = canvasLeft() + cmargin() * pw()

    /**
     * 设画板模式。[on] 变了 = 切了开关 → **把页面摆回视口正中**（同 Mac `canvasModeChanged`
     * 的 recenter）；只有 [margin] 变 = 软边界跳了一档 → **零位移补偿**（内容宽增量的一半，
     * 页面在屏幕上纹丝不动，否则写字时页面会在笔下平移）。两种口径与 Mac / web 一一对应。
     */
    fun setCanvas(on: Boolean, margin: Float) {
        val changedOn = canvasOn != on
        val oldW = contentWidth()
        canvasOn = on
        canvasMargin = max(0f, margin)
        ink.clearCache()   // 几何是按 clamp 后的点建的，页边一放宽/收窄，旧几何就是错的
        recompute()
        scrollX = if (changedOn) (cmargin() * pw() + (pw() - vw) / 2f).coerceIn(0f, maxScrollX)
                  else (scrollX + (contentWidth() - oldW) / 2f).coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        ensureImages()
        invalidate()
        onHudChanged()
    }

    /**
     * 打开文档时的**初值**：只设字段，不补偿、不动滚动——几何随后由 `onFirstGeometry` 那条
     * 复原链（applyZoom → applyHFrac → scrollToPageFrac）统一算。走 [setCanvas] 会在复原之前
     * 先把 scrollX 摆到「页面居中」，上次存的横向位置就丢了。
     */
    fun presetCanvas(on: Boolean, margin: Float) {
        canvasOn = on
        canvasMargin = max(0f, margin)
    }

    /**
     * 落笔中的乐观跳档（档位公式见 [CanvasMargin]，三端同一组常数）：写到离页边不足 slack
     * 就本地先放宽一档。**只增不减**。模式2 里 Mac 的下发值随后覆盖；模式1 本机就是真源。
     */
    protected fun growCanvas(nx: Float) {
        if (!canvasOn) return
        val want = CanvasMargin.marginFor(CanvasMargin.overflowOf(nx))
        if (want > canvasMargin) setCanvas(true, want)
    }

    /**
     * 真源回推的笔迹越出了当前页边 → 本地先放宽一档（**只增不减**，Mac 随后下发的值仍是权威）。
     *
     * 🔴 少了这一步就是「画板模式下框选把笔迹移到页边深处，平板上看到笔迹挤成一条」
     * （用户 2026-08-30 报，模式2）：**这一侧的渲染是按当前页边 clamp 的**
     * （`InkRenderer.buildPage` 的 `coerceIn(-xm, 1+xm)`，页外笔迹本来就该按页边裁），
     * 于是数据即使是对的，只要本端的 `canvasMargin` 还停在旧档位，画出来就是被摁在边界上的一条竖线。
     * Mac 那边确实会随后广播新的 `canvas` 档位，但那是**另一条独立广播**——到达有先后、
     * 中间这一拍屏幕上就是错的；而且它一旦没发（例如 Mac 侧那个窗口的阅读区没在跟这条会话），
     * 本端就永远卡在旧档位。本地自愈一档最省事，与落笔中的 [growCanvas] 是同一条「乐观跳档」惯例。
     */
    protected fun growCanvasFor(list: List<Stroke>) {
        if (!canvasOn) return
        val want = CanvasMargin.marginFor(CanvasMargin.overflow(list))
        if (want > canvasMargin) setCanvas(true, want)
    }

    fun setBarHeight(px: Float) {
        barH = px
        onGeomChanged()
    }

    /**
     * 设置页面尺寸表（每项 = 该页的 宽,高，单位随意，只用比例）。
     * `reset=true` = 换文档：清笔迹/页图/滚动/缩放（文字笔记不清——模式2 靠 Mac 重发，清了会闪空）。
     */
    fun setPages(count: Int, pages: List<Pair<Float, Float>>, reset: Boolean) {
        pageCount = count
        pagesWH = pages
        if (reset) {
            strokes.clear()
            pendingInk.clear()   // 上一篇文档没等到真源的乐观笔，跟着笔迹一起作废
            ink.clearCache()
            clearCur()
            clearLasso()
            images.clear()
            requested.clear()
            scrollX = 0f; scrollY = 0f; zoom = 1f
            onDocumentReset()
        }
        onGeomChanged()
        maybeFirstGeometry()   // 页表可能比首次布局还晚到（模式1 的打开是异步的，见 §9.5）
    }

    /** 换文档时子类要清的自家状态（模式2：Mac 视口序号 vpSeq） */
    protected open fun onDocumentReset() {}

    protected fun recompute() {
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
        maxScrollX = max(0f, contentWidth() - vw)   // 画板模式下 fit 也有横向可滚（页两侧的页边）
    }

    protected fun onGeomChanged() {
        vw = width.toFloat().coerceAtLeast(1f)
        availH = (height.toFloat() - barH).coerceAtLeast(1f)
        recompute()
        scrollX = scrollX.coerceIn(0f, maxScrollX)
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        ensureImages()
        invalidate()
        emitGeom()
        onHudChanged()
    }

    /**
     * 首次拿到真实尺寸时回调一次。**进度复原必须等到这时候**：`setPages` 之后视图往往还没布局，
     * 此时 `offY`/`maxScrollY` 是按 width=1 算出来的，那会儿滚过去等于滚到页顶——
     * 表现为「读到第 37 页，重开却回到第 1 页」，而日志里明明写着复原到 37。
     */
    var onFirstGeometry: (() -> Unit)? = null
    private var firstGeometryDone = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // 转屏/分屏：宽度一变，dispH 全按新 vw 比例重算，scrollY 原值会落到别的页
        // （实测：竖屏 21 页转横屏变 13 页）。先记下「页 + 页内比例」，重算后锚回原处。
        val anchor = if (oldw > 0 && w != oldw && pageCount > 0) topVisiblePage() to topFrac() else null
        super.onSizeChanged(w, h, oldw, oldh)
        onGeomChanged()
        if (anchor != null) scrollToPageFrac(anchor.first, anchor.second)
        maybeFirstGeometry()
    }

    /**
     * 「视口有尺寸」+「页表已到」两件都齐了才算几何首次就绪，**谁后到都算**。
     *
     * 原先只在 [onSizeChanged] 里判，隐含假设是页表先到。模式1 改成后台打开（§9.5）后顺序反了：
     * 布局时 `pageCount` 还是 0、页表 1.4 秒后才来，钩子于是永不触发——表现是阅读进度**静默**
     * 不复原（每次打开都停在页顶），而日志里照旧写着「复原到第 8 页」。
     */
    private fun maybeFirstGeometry() {
        if (firstGeometryDone || width <= 0 || pageCount <= 0) return
        firstGeometryDone = true
        onFirstGeometry?.invoke()
    }

    /**
     * 平板页宽上报：Mac 侧的环形盘取消区半径/长按位移阈值都是**平板屏幕上的**尺度，
     * 得知道平板页宽才能换算。单位 dp（与 PadConst.RD 同尺度）。值变才发，静止时零流量。
     */
    protected fun emitGeom() {
        val w = pw() / density
        if (abs(w - lastGeomW) < 0.5f) return
        lastGeomW = w
        onContentWidthChanged(w)
    }

    /** 严格 <：翻到某页正顶部时必须算本页（capture 的 topVisiblePage 同一边界） */
    fun topVisiblePage(): Int {
        for (i in 0 until pageCount) if (scrollY < offY[i] + dispH[i] + gapPx) return i
        return max(0, pageCount - 1)
    }

    fun pageCountOrZero(): Int = pageCount
    fun hudPage(): String = if (pageCount > 0) "${topVisiblePage() + 1}/$pageCount" else "—/—"
    fun hudZoom(): String = "${(zoom * 100).roundToInt()}%"

    // —— 页图（可见 + 上下各一屏预取；PadActivity 经 PageFetcher 取回 setPageImage） ——
    protected val images = HashMap<Int, Bitmap>()
    protected val requested = HashSet<Int>()

    protected fun ensureImages() {
        if (pageCount == 0 || !showPage) return
        val top = scrollY - availH
        val bot = scrollY + availH * 2
        for (i in 0 until pageCount) {
            if (offY[i] + dispH[i] >= top && offY[i] <= bot && !images.containsKey(i) && requested.add(i)) {
                val page = i
                imageSource?.request(page, pw().roundToInt()) { bmp ->
                    post { setPageImage(page, bmp) }
                } ?: requested.remove(page)
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

    /**
     * 丢掉已经拿到的页图，**不动笔迹/滚动/缩放**（模式1 的标签页退到背景时用，见 §13）。
     *
     * 位图是这里唯一按屏幕尺寸吃内存的东西（一页 1080×1500 就是 6MB），三个标签页各留一屏
     * 就是几十 MB 挂着不还。缓存在 `PdfSource` 那边还留着一份缩小额度的，切回来多半是命中，
     * 所以这里清得起。在途请求不必管：结果回来照旧进 [setPageImage]，只是多渲了一页。
     */
    fun trimImages() {
        images.clear()
        requested.clear()
    }

    /**
     * 按当前视口重新取页图（标签页切回前台时用）。
     *
     * **切回来必须显式调一次**：背景标签页的 View 是 `GONE`，重新 `VISIBLE` 时尺寸没变就不会走
     * [onSizeChanged]，也就没人去调 `ensureImages`——[trimImages] 清掉的那一屏于是永远补不回来，
     * 表现是"切回这个标签页只剩白底和笔迹"。
     */
    fun refreshImages() {
        ensureImages()
        invalidate()
    }

    // —— 坐标映射（跨页 + 缩放；视口坐标 ↔ 页内归一化） ——
    protected data class Loc(val page: Int, val nx: Float, val ny: Float)

    /**
     * [wide] = 画板模式下把 nx 放宽到页边（落墨/擦除/框选走这条），默认页内。
     * 同 Mac `containerPointToPageNorm` 的 `xRange`：y 永远还是 0…1，页边只横向延伸。
     */
    protected fun locate(x: Float, vy: Float, wide: Boolean = false): Loc? {
        val cl = contentLeft()
        val p = pw()
        val m = if (wide) cmargin() else 0f
        val docY = vy - barH + scrollY
        for (i in 0 until pageCount) {
            if (docY >= offY[i] && docY <= offY[i] + dispH[i]) {
                return Loc(
                    i,
                    ((x - cl) / p).coerceIn(-m, 1f + m),
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
    protected fun pageLocClamped(x: Float, y: Float, page: Int, out: FloatArray, wide: Boolean = false) {
        val cl = contentLeft()
        val p = pw()
        val m = if (wide) cmargin() else 0f
        out[0] = ((x - cl) / p).coerceIn(-m, 1f + m)
        val docY = y - barH + scrollY
        out[1] = when {
            page !in 0 until pageCount -> 0f
            docY < offY[page] -> 0f
            docY > offY[page] + dispH[page] -> 1f
            else -> ((docY - offY[page]) / max(1f, dispH[page])).coerceIn(0f, 1f)
        }
    }

    /**
     * 该页的显示纵横比（高/宽）。页内归一化空间里 x/y 尺度不同，凡是**量角度或量距离**的地方
     * 都得先用它把 y 折算成与 x 同尺度：尺子吸附（`PadConst.rulerSnap`）、长按位移判定、
     * 环形盘的扇区角度都靠这一个口径。同 Mac 的 `AppModel.currentPageAspect`。
     * 草稿纸的**页面底图**也用它定页矩形的高（`ScratchGeom.pageRect`）。
     */
    fun pageAspect(page: Int): Float =
        if (page in 0 until pageCount) dispH[page] / max(1f, pw()) else 1f

    /** 当前内容页宽（dp）：环形盘的取消区半径/长按位移阈值都按平板屏幕尺度判，见 `PadConst.LP` */
    fun contentWidthDp(): Float = pw() / density

    override fun viewX(page: Int, nx: Float): Float = contentLeft() + nx * pw()

    override fun viewY(page: Int, ny: Float): Float =
        if (page in 0 until pageCount) barH + offY[page] + ny * dispH[page] - scrollY else -1e6f

    // —— 笔迹（Mac 回传 strokes = 唯一真源；cur = 本地正在写的半笔即时回显） ——
    protected val strokes = ArrayList<Stroke>()
    protected var curActive = false
    protected var curPage = 0
    protected var curStrokePen = FALLBACK_PENS[0]
    protected val curPts = ArrayList<Pt3>()
    protected var radialActive = false   // Mac 已把半笔转成环形选笔盘：本地撤半笔、不再画（位置照发）

    protected fun clearCur() {
        curActive = false
        curPts.clear()
    }

    /**
     * **已收笔、已上行、但真源还没回推回来**的那几笔（模式2）。
     *
     * 模式2 的真源在 Mac：抬笔后本地只能先留着活体层那半笔顶着，等 `strokes` 广播回来再清。
     * 但活体层只有一个槽——连续快写时下一笔的 [penDown] 会无条件清空它，上一笔于是在
     * 「活体层已清、真源还没到」之间出现一段两头都没有的空窗，肉眼就是「上一笔闪一下」
     * （用户报的 bug）。改成收笔即把这一笔乐观并入 [strokes] 并记下 id：擦除、框选、命中判定
     * 因此全都自动覆盖到它，与模式1 `LocalCanvasView.onInkEnd` 的乐观落地是同一套路。
     *
     * 认领规则是**逐条对 REL 序号**：记下这一笔 `ink end` 帧自己的 REL 序号，回推快照的 `ackRel`
     * 追上它（`ackRel >= seq`）才算「已进真源」，撤掉乐观版；还没追上的**原样留在屏幕上**
     * （见 [setStrokes]）。ink 走 UDP **可靠有序**流，序号单调，不需要任何超时兜底或批数记账。
     *
     * ⚠️ 曾经的写法是「快照只要不含本端全部输入（`sentRel > ackRel`）就整份丢弃」。连续快写时
     * `sentRel` 每 8ms 就涨一次（ink move 批），回推路上必然又涨了好几个 → **快照永远被丢弃**，
     * 乐观笔全靠 3s 兜底撤掉，于是「上一个字的笔画依次闪一下」（2026-08-28 用户报）。
     * 落墨是纯追加，压根不需要那种全有全无的判据——只有擦除会被旧快照实质破坏，见 [lastEraseRel]。
     *
     * 模式1 的这份表恒空——它在 `onInkEnd` 里就自己落地并 `clearCur` 了，走到 [commitCurOptimistically]
     * 时已无半笔可转。
     */
    private val pendingInk = LinkedHashMap<String, Long>()   // 本地 id → 该笔 ink end 帧的 REL 序号
    private var pendingInkSeq = 0L

    /**
     * 最后一帧**擦除**（erase move/end）的 REL 序号：`ackRel` 没追上它的快照比本地旧，采纳就是
     * 把刚擦掉的笔迹恢复出来、下一份再擦掉（用户实测「删掉了又出现，过一会才真的删掉」）。
     * 只有擦除有这个问题——落墨是纯追加，旧快照顶多是「少了最后几笔」，而那几笔正由 [pendingInk] 顶着。
     */
    private var lastEraseRel = 0L

    /** 最近一次收到 `strokes` 回推的时刻（**不管有没有采纳**）：真源还在说话的活体证明，兜底撤销用 */
    private var strokesRecvAt = 0L

    /**
     * 收笔后把活体层那半笔转成乐观笔迹（见 [pendingInk]）。放在 `onInkEnd()` **之后**调用：
     * 模式1 已经在钩子里自己落地并清了活体层，这里 `curActive` 为 false，天然跳过。
     */
    private fun commitCurOptimistically() {
        if (!curActive || curPts.isEmpty()) return
        val id = "$OPT_INK_PREFIX${pendingInkSeq++}"
        strokes.add(Stroke(curPage.toLong(), curStrokePen, ArrayList(curPts), id, ""))
        pendingInk[id] = sentRelSeq()   // 紧跟 onInkEnd() 之后读 = 这一帧 ink end 自己的序号
        scheduleOptExpire(id)
        clearCur()
    }

    /**
     * 兜底：Mac 掉线时这一笔真源里永远不会有，别让它挂在屏幕上（同 lasso 预览的超时清）。
     *
     * **判据是「真源哑了」，不是「等够久了」**：只要还在收 `strokes` 回推（哪怕因为比本地旧而没采纳），
     * 就说明 Mac 活着、这一笔迟早会回来，续一轮接着等。写得快 + 回推慢时按固定时限硬撤，
     * 撤掉的就是用户刚写完还看得见的字——那正是本轮闪烁的直接成因。
     */
    private fun scheduleOptExpire(id: String) {
        handler.postDelayed({
            if (!pendingInk.containsKey(id)) return@postDelayed
            if (System.currentTimeMillis() - strokesRecvAt < OPT_INK_TIMEOUT_MS) {
                scheduleOptExpire(id)   // 真源还在回推，继续等
                return@postDelayed
            }
            Log.w(TAG, "乐观笔迹 $id 等真源超时（${OPT_INK_TIMEOUT_MS}ms 无回推），撤掉")
            pendingInk.remove(id)
            strokes.removeAll { it.id == id }
            invalidate()
        }, OPT_INK_TIMEOUT_MS)
    }

    /**
     * 回推一份**全量**笔迹真源。
     *
     * @param ackRel 生成这份快照时 Mac 已连续处理到的本端 REL 序号（`PROTOCOL.md §4.2`）。
     *   0 = 不适用：模式1（真源就在进程内）、或模式2 还没建起 UDP 会话——那就照单全收，退回旧行为。
     * @param sentRel 本端已发出的最后一个 REL 序号（`UdpSender.sentRel`）。**判据不再用它**
     *   （见函数体里的 ⚠️），保留在签名里只是给调用方/日志一个现场快照。
     */
    @Suppress("UNUSED_PARAMETER")
    fun setStrokes(list: List<Stroke>, ackRel: Long = 0L, sentRel: Long = 0L) {
        strokesRecvAt = System.currentTimeMillis()   // 真源活着的证明，采纳与否都算（见 scheduleOptExpire）
        // 手势还没结束：本地已经擦到笔尖当前位置，而这一批擦除帧可能还没 flush 出去（8ms 一批），
        // 那时 lastEraseRel 还没涨、判据会误判成「含我全部擦除」。手势期间一律信本地。
        if (activePen && penMode == MODE_ERASE) return
        // 唯一的整份丢弃判据：这份快照生成时，我发出去的**擦除**Mac 还没处理完 → 它比本地旧，
        // 采纳就是把刚擦掉的笔迹恢复出来、下一份再擦掉（用户实测「删掉了又出现，过一会才真的删掉」）。
        //
        // 之所以非要真源侧给个序号不可：`strokes` 是全量镜像，而 Mac 每收一批擦除点就广播一次
        // （`AppModel.inkErase`），擦除途中会连着回来一串中途快照。客户端**单边**分辨不了它们——
        // 此前试过「按发出批数记账」，靠猜「一批恰好回一次广播」的隐含契约、还得配超时兜底，翻车两次
        // （补丁史见 `ANDROID-STANDALONE-PLAN.md §9.9`）。ackRel 单调递增且由真源给出，不需要兜底。
        //
        // ⚠️ 这里比的是 [lastEraseRel] 而**不是** `sentRel`（全部 REL 帧的最新序号）。用 sentRel 时
        // 连续快写会让判据永远为真（ink move 每 8ms 一帧），快照一份都进不来，乐观笔只能靠超时撤掉
        // → 「上一个字的笔画依次闪一下」（2026-08-28 用户报）。落墨不怕旧快照：少的那几笔由 pendingInk 顶着。
        if (ackRel > 0L && lastEraseRel > ackRel) return
        // 逐条认领乐观笔：`ackRel` 已追上的进了真源（随整表替换退场），没追上的原样留在屏幕上，
        // 免得出现「活体层已清、真源还没到」的空窗（那就是闪烁）。
        val keep = ArrayList<Stroke>(pendingInk.size)
        if (ackRel > 0L) {
            val it = pendingInk.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                // 本地已被擦掉/切段的乐观笔在 strokes 里找不到 → 直接销账（该擦除必已被 Mac 处理过，
                // 否则上面的 lastEraseRel 判据早就整份丢弃了）
                val s = if (e.value > ackRel) strokes.firstOrNull { st -> st.id == e.key } else null
                if (s != null) keep.add(s) else it.remove()
            }
        } else {
            pendingInk.clear()   // ackRel 不适用（模式1 / UDP 会话未建）：照单全收，退回旧行为
        }
        strokes.clear()
        strokes.addAll(list)
        strokes.addAll(keep)   // 追加在末尾 = 画在最上层，本来就是最新的几笔
        growCanvasFor(strokes)
        if (!activePen) clearCur()   // 正在写的这笔不清，避免闪断
        // 框选移动/缩放已提交、正等这条回来。分层记账（lassoMirrorSplit，模式2）：这条到了 = 笔迹层
        // 改画真源（该层命中下标随新数组作废），notes 层继续乐观预览直到它的镜像也到；两条都到齐才
        // settleLasso——否则先到的那条把乐观变换全清掉，另一层跳回原位再跳回来 = 闪烁（2026-08-18 用户报，
        // Mac 的 broadcastStrokes/broadcastNotes 是两条独立广播，到达顺序与间隔无保证）。
        // 模式1 的回推一趟带齐两层（同一主线程回调连发），推迟一轮消息再结算（见 postSettleLasso）。
        if (lassoCommitted) {
            if (lassoMirrorSplit) {
                lassoSyncStrokes = true
                lassoSelection?.let { lassoSelection = it.copy(strokeIdx = emptyList()) }
                if (lassoSyncNotes) settleLasso()
            } else postSettleLasso()
        } else refreshLassoSelection()   // 整表换过了：选中集的下标会失效，按多边形重判一次
        invalidate()
    }

    /**
     * 真源**追加**了这几条（模式2 的 `strokesAppend` 0x4C，`PROTOCOL.md §4.2`）。
     *
     * Mac 只在纯追加（收笔）那一处发它——全量镜像每收一笔就重发整篇是 O(n²)，写久了 `e2e` 一路爬，
     * 大帧还会把后面几十字节的控制帧压在 WS 队列里。擦除/框选/图层显隐/切档仍走 [setStrokes]。
     *
     * 与 [setStrokes] 的两处不同，都是追加语义天然带来的：
     * - **不需要那道擦除闸**（`lastEraseRel > ackRel` 就整份丢弃）：追加不会把擦掉的笔迹复活。
     * - **只销账、不整批清**：`ackRel` 已追上的乐观笔在这里退场，没追上的原样留着继续画。
     *   销账与追加**在同一次操作里**完成，屏幕上恰好一条，不会先双份再闪掉。
     */
    fun appendStrokes(list: List<Stroke>, ackRel: Long = 0L) {
        strokesRecvAt = System.currentTimeMillis()   // 真源活着的证明，同 setStrokes
        strokes.addAll(list)
        if (ackRel > 0L && pendingInk.isNotEmpty()) {
            val it = pendingInk.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value > ackRel) continue       // 真源还没收下这一笔，乐观版继续顶着
                strokes.removeAll { s -> s.id == e.key }
                it.remove()
            }
        }
        invalidate()
    }

    fun onInkCancel() {
        radialActive = true
        clearCur()
        invalidate()
    }

    // —— 文字笔记（Mac 下发全量镜像；本地只乐观更新，回传即整体替换） ——
    protected val notes = ArrayList<TextNote>()

    fun setNotes(list: List<TextNote>) {
        notes.clear()
        notes.addAll(list)
        // 框选提交后的分层记账：与 setStrokes 对称（见那里的长注释）——notes 层改画真源，
        // 笔迹层继续乐观直到其镜像到达；两条都到齐才 settleLasso。
        if (lassoCommitted) {
            if (lassoMirrorSplit) {
                lassoSyncNotes = true
                lassoSelection?.let { lassoSelection = it.copy(noteIdx = emptyList()) }
                if (lassoSyncStrokes) settleLasso()
            } else postSettleLasso()
        } else refreshLassoSelection()   // 同 setStrokes：整表换过了，下标要按多边形重判
        invalidate()
    }

    /**
     * 文字铺色（高亮 kind=3 与选区注解 kind=0 的底色，M5）。模式1 从库里读；模式2 恒空
     * ——那边页图由 Mac 渲染，高亮画在 Mac 那一侧，线格式里根本没有这个消息。
     */
    protected val fills = ArrayList<TextFill>()

    fun setTextFills(list: List<TextFill>) {
        fills.clear()
        fills.addAll(list)
        invalidate()
    }

    // —— 划字（选字模式 MODE_TEXT）——
    //
    // 文本层来自工作区库里的 `ocr_page`（Mac 跑的 OCR，随离线镜像同步过来）；**本机不跑 OCR**。
    // 模式2 这一层恒空：那边划字在 Mac 上做，平板只是块画布。
    // 判定全在 `TextSelect`（纯函数，与 Mac `ReaderSurface+Selection` 同一套），这里只管手势与画。

    /** 页 → 该页**可见**行（已滤水印）。由宿主注入，见 `LocalCanvasView.applyTextLayer` */
    private var textRuns: Map<Int, List<TextRun>> = emptyMap()

    /** 页 → 列/块分组（`OcrFlow.columnGroups`），懒算带缓存：分组只随文本层变，一页算一次就够 */
    private val textGroupCache = HashMap<Int, IntArray>()

    /** 这本书有没有文本层——没有的话选字模式该给用户一句话，而不是"划了没反应" */
    fun hasTextLayer(): Boolean = textRuns.isNotEmpty()

    fun setTextRuns(map: Map<Int, List<TextRun>>) {
        textRuns = map
        textGroupCache.clear()
        clearTextSelection()
        Log.i(TAG, "文本层注入 ${map.size} 页，共 ${map.values.sumOf { it.size }} 行")
        invalidate()
    }

    private val textProvider = object : TextSelect.Provider {
        override fun runs(page: Int): List<TextRun>? = textRuns[page]
        override fun groups(page: Int): IntArray =
            textGroupCache.getOrPut(page) { OcrFlow.columnGroups(textRuns[page].orEmpty()) }
    }

    /** 当前选区（null = 没选）。**瞬态**：不落库、不上行，就是"此刻选中了哪几个字" */
    var textSelection: TextSelection? = null
        private set

    /** 选区变化（含清空）：宿主据此显示/收起那条「高亮 / 批注 / 复制」动作条 */
    var onTextSelectionChanged: (TextSelection?) -> Unit = {}

    private var textSelAnchor: TextSelect.Point? = null
    private var textSelActive = false

    /** 双击选整行：记上一次轻点的时间与位置 */
    private var lastTextTapT = 0L
    private var lastTextTapX = 0f
    private var lastTextTapY = 0f

    fun clearTextSelection() {
        if (textSelection == null) return
        textSelection = null
        onTextSelectionChanged(null)
        invalidate()
    }

    private fun setTextSelection(s: TextSelection?) {
        textSelection = s
        onTextSelectionChanged(s)
        invalidate()
    }

    /** 落点起选：记锚点。返回 false = 这里没有文本层，别把这一下当划字（照旧平移） */
    private fun beginTextSelect(x: Float, y: Float): Boolean {
        val loc = locate(x, y) ?: return false
        if (textRuns[loc.page].isNullOrEmpty()) return false
        textSelAnchor = TextSelect.Point(loc.page, loc.nx, loc.ny)
        textSelActive = false
        return true
    }

    /** 拖动中：锚点 → 当前点重算选区 */
    private fun moveTextSelect(x: Float, y: Float) {
        val a = textSelAnchor ?: return
        val loc = locate(x, y) ?: return
        textSelActive = true
        setTextSelection(TextSelect.select(textProvider, a, TextSelect.Point(loc.page, loc.nx, loc.ny)))
    }

    private fun endTextSelect() {
        textSelAnchor = null
        textSelActive = false
    }

    /**
     * 选字模式下的轻点（没越过死区）：**双击选整行，单击收起选区**。
     * 与 Mac 的「双击选词/行、单击取消」同语义（`selectWord` 的 OCR 分支就是选整行）。
     */
    private fun tapTextSelect(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val isDouble = now - lastTextTapT <= DOUBLE_TAP_MS &&
            hypot(x - lastTextTapX, y - lastTextTapY) <= dp(24f)
        lastTextTapT = now
        lastTextTapX = x
        lastTextTapY = y
        if (!isDouble) { clearTextSelection(); return }
        val loc = locate(x, y) ?: return
        setTextSelection(TextSelect.selectLine(textProvider, loc.page, loc.nx, loc.ny))
    }

    /** 选区铺色：页图之上、墨迹之下（同 Mac 的层序——盖在墨迹上会挡住自己写的字） */
    private fun drawTextSelection(canvas: Canvas) {
        val sel = textSelection ?: return
        for ((page, rects) in sel.rects) {
            if (page !in 0 until pageCount) continue
            val p = pw()
            val h = dispH[page]
            val top = barH + offY[page] - scrollY
            if (top > height || top + h < barH) continue
            for (r in rects) {
                val x0 = contentLeft() + r[0] * p
                val y0 = top + r[1] * h
                overlays.drawTextSelection(canvas, x0, y0, x0 + r[2] * p, y0 + r[3] * h)
            }
        }
    }

    /** 编辑器保存：乐观更新本地列表并上行（Mac 随后回传 notes 全量镜像） */
    fun upsertNote(id: String, page: Int, nx: Float, ny: Float, text: String, display: Int = NOTE_TAP) {
        onNoteUpsert(id, page, nx, ny, text, display)
        val i = notes.indexOfFirst { it.id == id }
        // 改一条已有的注解：**anchor 宽与类型原样留着**。落库那边也只改正文与展开方式
        // （`upsertTextNote` 的约定），这里丢掉的话图钉会先跳回选区起点、等回推才跳回行末。
        val old = notes.getOrNull(i)
        val rec = TextNote(id, page.toLong(), nx, ny, text, display, old?.aw ?: 0f, old?.typeId)
        if (i >= 0) notes[i] = rec else notes.add(rec)
        invalidate()
    }

    fun deleteNote(id: String, page: Int, nx: Float, ny: Float) {
        onNoteDelete(id, page, nx, ny)
        notes.removeAll { it.id == id }
        invalidate()
    }

    // —— 环形选笔盘 / 长按进度环（本类只负责画；判定方模式2 是 Mac、模式1 是本地） ——

    protected class RadialState(
        val page: Int, val cx: Float, val cy: Float, val highlight: Int, val items: List<RadialItem>,
    )
    protected class PressRingState(val page: Int, val nx: Float, val ny: Float)

    protected var radial: RadialState? = null
    protected var pressRing: PressRingState? = null
    protected var pressT0 = 0L

    /**
     * 🔴 **笔不在纸上就不许开盘/起环**（2026-09-02 用户报「环动不动凭空冒出来、还不在笔尖，
     * 正常长按反而唤不出盘」的那道硬闸）。
     *
     * 模式2 里盘与环都是 Mac 判定后**下发**的，而它们与 `strokes` 全量镜像走同一条有序 WS 通道
     * ——大帧一旦在飞，这些几十字节的控制帧就被压在后面（`LANServer` 合帧那段注释里的老账）。
     * 等它到的时候手势往往已经结束：`on=true` 落在抬笔之后，环就按**上一笔的落笔点**凭空画出来
     * （所以「不在笔尖」——笔根本不在纸上），而真正想要的那次长按，`radial` 又迟到被 `endPen`
     * 收掉，于是「唤不出」。三个症状同一个成因。
     *
     * 迟到帧一律丢弃：这两样东西**只在手势进行中才有意义**，补画出来没有任何用处。
     * `false`（撤环/收盘）永远照单接收——那是清理，迟到也得执行。
     */
    private fun overlayAllowed(open: Boolean, what: String): Boolean {
        if (!open || activePen) return true
        Log.i(TAG, "丢弃迟到的 $what：笔已不在纸上（控制帧被大帧压在后面了）")
        return false
    }

    fun setRadial(open: Boolean, page: Int, cx: Float, cy: Float, highlight: Int, items: List<RadialItem>) {
        if (!overlayAllowed(open, "radial")) return
        // 空扇区表按「没开盘」处理（同网页 drawRadial 的 `!items.length` 分支），否则会出现
        // 盘既不画、进度环也被盘挡着不画的空窗
        radial = if (open && items.isNotEmpty()) RadialState(page, cx, cy, highlight, items) else null
        invalidate()
    }

    fun setPressRing(on: Boolean, page: Int, nx: Float, ny: Float) {
        if (!overlayAllowed(on, "pressRing")) return
        // 不带时间戳：置 on 就用本机时钟起计（模式2 里 LAN RTT 的几毫秒偏差不可察觉）
        pressRing = if (on) PressRingState(page, nx, ny) else null
        if (on) {
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
        clearTextSelection()   // 选区也是瞬态的：切走这个标签页再回来，不该还挂着一段蓝
        invalidate()
    }

    // —— 绘制 ——
    protected val pagePaint = Paint().apply { color = Color.WHITE }
    protected val placeholderPaint = Paint().apply { color = 0xFFE9EDF2.toInt() }
    protected val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    protected val tmpRect = RectF()
    protected val tmp2 = FloatArray(2)

    /** 夜间模式滤镜：反亮度（invert）后 hue-rotate 180° 复原彩色，同网页 CSS filter */
    protected val nightFilter: ColorMatrixColorFilter = run {
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

    /**
     * 页与页之间的底：跟随系统深浅色（`res/values{,-night}/colors.xml` 的 surface_dim）。
     * 从前写死深色，浅色主题下就成了「白顶栏 + 黑画布」两个 App 拼在一起。
     * **夜间模式是另一件事**：那个只反转页面那一层（见 onDraw），与这里的底色互不干涉。
     *
     * **取一次存下来**：`onDraw` 每帧都跑，在里面查资源等于每帧做一次主题解析。
     * 系统深浅色一变 Activity 会重建、这个 View 跟着重建，所以缓存不会过期。
     */
    private val gutterColor = Ui.col(context, R.color.surface_dim)

    // —— 绘制耗时打点：「卡不卡」不能靠感觉，要有帧耗时才知道改动打没打中（每 120 帧一条） ——
    private var statFrames = 0
    private var statNanos = 0L
    private var statWorst = 0L

    override fun onDraw(canvas: Canvas) {
        val t0 = System.nanoTime()
        // 捏合中别重建笔迹几何：轮廓依赖页宽，每帧重算整页笔迹比不缓存还慢（见 InkRenderer 类注释）
        ink.deferRebuild = pinch != null
        canvas.drawColor(gutterColor)
        val cl = contentLeft()
        val p = pw()
        val wl = canvasLeft()          // 内容左缘（页边最左）
        val ww = contentWidth()        // 页 + 两侧页边
        // 夜间模式只反转「页面」这一层：页图、白底、以及未取到图时的占位色都要一起反
        // （网页那边是给 bg canvas 整体加 CSS filter，白底同样被反成黑底）。墨迹/圆环不反。
        val f = if (night) nightFilter else null
        bitmapPaint.colorFilter = f
        pagePaint.colorFilter = f
        placeholderPaint.colorFilter = f
        // 顺带记下可见页范围（连续竖排布局，页序即 offY 单调递增，可见页必是一段连续区间）：
        // 笔迹层按页裁剪要用，别为它再扫一遍 pageCount（下面笔迹循环最多的是全文档笔迹）。
        var firstVis = -1
        var lastVis = -1
        for (i in 0 until pageCount) {
            val vy = barH + offY[i] - scrollY
            if (vy + dispH[i] < barH || vy > height) continue
            if (firstVis < 0) firstVis = i
            lastVis = i
            // 画板模式：白纸连同两侧页边一起铺（页边是「同一页的横向延伸」，不是另一块灰底）；
            // 页图/占位色仍只占中间那 p 宽。关着时 wl==cl、ww==p，与画板模式之前逐像素同渲染。
            tmpRect.set(wl, vy, wl + ww, vy + dispH[i])
            canvas.drawRect(tmpRect, pagePaint)
            tmpRect.set(cl, vy, cl + p, vy + dispH[i])
            if (!showPage) continue   // 手写板模式：仅白底，不取图
            val bmp = images[i]
            if (bmp != null) canvas.drawBitmap(bmp, null, tmpRect, bitmapPaint)
            else canvas.drawRect(tmpRect, placeholderPaint)
        }

        drawTextFills(canvas)
        drawTextSelection(canvas)   // 划字选区压在已有铺色之上（正在选的那块要看得见）

        // 静态笔迹层（框选提交待回传期间：命中项按位移/缩放乐观渲染）。
        // ⚠️ 性能红线：`strokes` 是**全文档**笔迹（模式2 的 `broadcastStrokes` 就是整篇发过来的），
        // 页图/文字铺色/图钉都按可见范围跳过了，笔迹也得裁——但**光裁页救不了正在写字的那一页**
        // （它恰恰就是可见页，一条都裁不掉），每条笔迹的几何缓存才是滚动流畅的关键，见 [InkRenderer]。
        // 分层记账：strokes 镜像一到（lassoSyncStrokes）笔迹层即改画真源，乐观只作用于还没到的层。
        val sel = if (lassoCommitted && !lassoSyncStrokes) lassoSelection else null
        val sc = lassoScale?.takeIf { sel != null && (it[2] != 1f || it[3] != 1f) }
        // 墨迹裁到「内容宽」（页 + 两侧页边），整层裁一次：同 Mac（`PageCellView` 的墨迹 Canvas
        // 只有页宽 + 2×margin）与 web（`clipContent`）。画板关掉后页外的笔迹就此看不见。
        val xm = cmargin()   // 页内归一化 x 的放宽量（画板模式），笔迹几何与乐观变换共用
        val inkClip = canvas.save()
        canvas.clipRect(wl, 0f, wl + ww, height.toFloat())
        for (i in strokes.indices) {
            val s = strokes[i]
            val sp = s.page.toInt()
            if (sp < firstVis || sp > lastVis) continue
            if (sel != null && sel.page == sp && sel.strokeIdx.contains(i)) {
                // 缩放提交待回传：按 InkEdit.scaled 语义（点集绕锚点按轴缩放 + 线宽 ×√(sx·sy)）乐观渲染
                if (sc != null) {
                    ink.drawStroke(canvas, InkEdit.scaled(s, sc[0], sc[1], sc[2], sc[3], xm), this,
                                   xMargin = xm)
                } else {
                    ink.drawStroke(canvas, s, this, lassoDx, lassoDy, xMargin = xm)
                }
            } else {
                ink.drawStroke(canvas, s, this, xMargin = xm)
            }
        }
        // 活体层：正在写的这一笔（同在内容宽的裁剪内）
        if (curActive) ink.drawLive(canvas, curPage, curStrokePen, curPts, this, xMargin = xm)
        canvas.restoreToCount(inkClip)

        // 注解层与笔迹层分开记账：notes 镜像没到才继续乐观
        val noteSel = if (lassoCommitted && !lassoSyncNotes) lassoSelection else null
        drawNoteMarkers(canvas, noteSel)
        drawNoteBubbles(canvas, noteSel)   // 气泡压在标记之上
        drawBookmarkRibbons(canvas)

        // 橡皮尺寸圆环（擦除模式 + 开关开 + 有笔尖位置）
        val ringAt = eraserRingAt
        if (eraserRing && ringAt != null && mode == MODE_ERASE) {
            overlays.drawEraserRing(canvas, ringAt[0], ringAt[1], eraserSize * pw())
        }

        drawLassoOverlay(canvas)

        // 盘开着时不画进度环（环展开成盘，两者互斥）
        val rd = radial
        if (rd != null) {
            overlays.drawRadial(canvas, viewX(rd.page, rd.cx), viewY(rd.page, rd.cy), rd.highlight, rd.items)
        } else {
            val pr = pressRing
            if (pr != null) {
                overlays.drawPressRing(
                    canvas, viewX(pr.page, pr.nx), viewY(pr.page, pr.ny),
                    (SystemClock.uptimeMillis() - pressT0).toFloat(),
                )
                postInvalidateOnAnimation()
            }
        }

        val el = System.nanoTime() - t0
        statNanos += el
        if (el > statWorst) statWorst = el
        if (++statFrames >= DRAW_STAT_FRAMES) {
            Log.i(
                TAG,
                "绘制 ${statFrames}帧 均${"%.1f".format(statNanos / statFrames / 1e6)}ms " +
                    "最差${"%.1f".format(statWorst / 1e6)}ms 笔迹=${strokes.size}条" +
                    "(可见页 ${firstVis + 1}~${lastVis + 1}) 几何重建=${ink.rebuilt}条",
            )
            statFrames = 0; statNanos = 0L; statWorst = 0L; ink.rebuilt = 0
        }
    }

    /**
     * 文字铺色层：页图之上、墨迹之下（同 Mac `PageCellView` 的层序）。
     * 不做可见性裁剪之外的任何判定——颜色与透明度在读库时就按 Mac 口径算好了（见 `PadConst.FILL`）。
     */
    protected fun drawTextFills(canvas: Canvas) {
        if (fills.isEmpty()) return
        // 正在拖/已提交待回推的选中注解：底色要跟着图钉一起走（图钉的变换见 drawNoteMarkers）。
        // 分层记账：notes 镜像一到（lassoSyncNotes）注解层即改画真源，乐观只作用于还没到的层。
        val sel = lassoSelection?.takeIf {
            lassoDragMode == 2 || lassoDragMode == 3 || (lassoCommitted && !lassoSyncNotes)
        }
        val moving = sel?.noteIdx?.mapNotNull { notes.getOrNull(it)?.id }?.toSet() ?: emptySet()
        val sc = lassoScale?.takeIf { sel != null && (it[2] != 1f || it[3] != 1f) }
        for (f in fills) {
            val page = f.page.toInt()
            if (page !in 0 until pageCount) continue
            val top = viewY(page, 0f)
            if (top + dispH[page] < barH || top > height) continue   // 整页在视口外：跳过这条的全部行框
            val color = Color.argb((f.a * 255f).roundToInt().coerceIn(0, 255), f.r, f.g, f.b)
            val on = f.noteId.isNotEmpty() && sel != null && sel.page == page && f.noteId in moving
            val ox = if (on && sc == null) lassoDx else 0f
            val oy = if (on && sc == null) lassoDy else 0f
            for (r in f.rects) {
                if (on && sc != null) {
                    // 缩放 ghost：行框绕锚点按轴缩放（同 InkEdit.scaled(TextNote) 的 rects 语义）
                    val sr = InkEdit.scaledRect(
                        doubleArrayOf(r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()),
                        sc[0].toDouble(), sc[1].toDouble(), sc[2].toDouble(), sc[3].toDouble(),
                    )
                    overlays.drawTextFill(
                        canvas,
                        viewX(page, sr[0].toFloat()), viewY(page, sr[1].toFloat()),
                        viewX(page, (sr[0] + sr[2]).toFloat()), viewY(page, (sr[1] + sr[3]).toFloat()),
                        color,
                    )
                } else {
                    overlays.drawTextFill(
                        canvas,
                        viewX(page, r[0] + ox), viewY(page, r[1] + oy),
                        viewX(page, r[0] + r[2] + ox), viewY(page, r[1] + r[3] + oy),
                        color,
                    )
                }
            }
        }
    }

    protected fun drawNoteMarkers(canvas: Canvas, sel: LassoSelection?) {
        if (notes.isEmpty()) return
        val r = noteMarkerRadius()
        val pos = FloatArray(2)
        for (i in notes.indices) {
            if (!noteViewPos(i, sel, pos)) continue
            val x = pos[0]
            val y = pos[1]
            if (y < barH - r || y > height + r || x < -r || x > width + r) continue
            val n = notes[i]
            overlays.drawNoteMarker(canvas, x, y, r, noteTypeRgb(n.typeId), noteTypeIcon(n.typeId))
        }
    }

    // —— 笔记类型（图钉的底色与图标；`meta.note_types` 由宿主读好注入）——

    private var noteTypeRgbMap: Map<String, IntArray> = emptyMap()
    private var noteTypeIconMap: Map<String, Drawable> = emptyMap()

    /**
     * 注入笔记类型的配色与图标（模式1 从 `meta.note_types` 读；模式2 不注入 = 一律通用）。
     * key 一律**大写**的 type_id（同库层 `noteTypeColors` 的口径，Mac 写的是大写 UUID）。
     */
    fun setNoteTypes(rgb: Map<String, IntArray>, icons: Map<String, Drawable>) {
        noteTypeRgbMap = rgb
        noteTypeIconMap = icons
        invalidate()
    }

    /** 通用暖黄 = Mac `PageCellView.noteMarker`（1, 0.80, 0.15）换算成 0~255 */
    private fun noteTypeRgb(typeId: String?): IntArray =
        noteTypeRgbMap[typeId?.uppercase()] ?: GENERAL_PIN_RGB

    private fun noteTypeIcon(typeId: String?): Drawable? = noteTypeIconMap[typeId?.uppercase()]

    // —— 文字笔记展开气泡（每条自己的 display：0=点击 1=悬停 2=始终）——
    // 几何全在 [NoteBubbleGeom]（三端同一套比例常数），这里只做「哪几条展开着 + 画在哪一页」。

    /** 点开着的笔记（**瞬态、不落库/不上行**：这是本机此刻看不看得见正文，不是笔记的属性） */
    protected val noteExpanded = HashSet<String>()

    /** 笔正悬停在哪条笔记的标记上（`hover` 模式的展开条件）；手指没有悬停，走点击降级 */
    protected var noteHoverId: String? = null

    /**
     * 笔记标记半径（视口 px）：与 [drawNoteMarkers] 同一口径，命中判定/气泡避让共用。
     *
     * 🔴 **固定 dp，不跟页缩放**——Mac 那边就是固定 9pt（见 [PadConst.PIN]）。
     * 从前按 `页宽×0.02` 夹在 12~22dp 算，放大后图钉直径能到 44dp，跟 Mac 完全两样。
     */
    protected fun noteMarkerRadius(): Float = dp(PadConst.PIN.R)

    /**
     * 一条笔记标记此刻的视口坐标（含框选乐观变换），画标记/布气泡/命中判定共用。
     *
     * 落位规则**逐项对齐 Mac `PageCellView.markerPos`**：
     * · **选区注解**（[TextNote.aw] > 0）落在选区**行末右侧**（`anchor.maxX + 9dp`、
     *   `anchor.minY + 7dp`）——压在选区起点上会遮住被批注的原文；
     * · **点注解**落在锚点本身；
     * · 两者最后都钳进页内（12/10dp 边距），否则贴边的笔记图钉会有一半在页外。
     */
    private fun noteViewPos(i: Int, sel: LassoSelection?, out: FloatArray): Boolean {
        val n = notes[i]
        val page = n.page.toInt()
        if (page !in 0 until pageCount) return false
        var nnx = n.nx
        var nny = n.ny
        if (sel != null && sel.page == page && sel.noteIdx.contains(i)) {
            if (lassoGhost(nnx, nny, tmp2)) { nnx = tmp2[0]; nny = tmp2[1] }
        }
        val selNote = n.aw > 0f
        var x = viewX(page, if (selNote) nnx + n.aw else nnx) + if (selNote) dp(PadConst.PIN.SEL_DX) else 0f
        var y = viewY(page, nny) + if (selNote) dp(PadConst.PIN.SEL_DY) else 0f
        // 钳进页内（页左右缘 = 内容左缘 + 页宽；纵向 = 该页的显示高）
        val left = contentLeft()
        val right = left + pw()
        val top = barH + offY[page] - scrollY
        val bottom = top + dispH[page]
        val ex = dp(PadConst.PIN.EDGE_X)
        val ey = dp(PadConst.PIN.EDGE_Y)
        x = x.coerceIn(left + ex, max(left + ex, right - ex))
        y = y.coerceIn(top + ey, max(top + ey, bottom - ey))
        out[0] = x
        out[1] = y
        return true
    }

    /** 第 i 条笔记此刻的气泡几何（不展开/不在页内 → null） */
    private fun noteBubbleBox(i: Int, sel: LassoSelection?): NoteBubbleGeom.Box? {
        val n = notes[i]
        if (!NoteBubbleGeom.visible(n, noteExpanded, noteHoverId)) return null
        val pos = FloatArray(2)
        if (!noteViewPos(i, sel, pos)) return null
        val page = n.page.toInt()
        val top = barH + offY[page] - scrollY
        return NoteBubbleGeom.layout(
            n.text, pw(), pos[0], pos[1], noteMarkerRadius(),
            contentLeft(), top, contentLeft() + pw(), top + dispH[page],
            NoteBubbleGeom.sticky(n, noteExpanded),
        ) { s, fs -> overlays.measureNoteText(s, fs) }
    }

    protected fun drawNoteBubbles(canvas: Canvas, sel: LassoSelection?) {
        if (notes.isEmpty()) return
        for (i in notes.indices) {
            val b = noteBubbleBox(i, sel) ?: continue
            if (b.y > height || b.y + b.h < barH) continue
            overlays.drawNoteBubble(canvas, b)
        }
    }

    /** 笔记标记命中 → 那条笔记（热区比画出来的略大，同草稿纸图钉） */
    protected fun noteMarkerHit(x: Float, y: Float): TextNote? {
        if (notes.isEmpty()) return null
        val sel = if (lassoCommitted && !lassoSyncNotes) lassoSelection else null
        val r = noteMarkerRadius()
        val hot = maxOf(r + dp(6f), dp(22f))
        val pos = FloatArray(2)
        for (i in notes.indices.reversed()) {
            if (!noteViewPos(i, sel, pos)) continue
            if (abs(x - pos[0]) <= hot && abs(y - pos[1]) <= hot) return notes[i]
        }
        return null
    }

    /** 展开气泡右上角铅笔命中 → 那条笔记：点它进编辑器 */
    protected fun noteEditHit(x: Float, y: Float): TextNote? {
        if (notes.isEmpty()) return null
        val sel = if (lassoCommitted && !lassoSyncNotes) lassoSelection else null
        for (i in notes.indices.reversed()) {
            val b = noteBubbleBox(i, sel) ?: continue
            if (NoteBubbleGeom.hitEdit(b, x, y)) return notes[i]
        }
        return null
    }

    /**
     * 手指单击落在笔记上：先看铅笔（进编辑器），再看标记（展开/收起气泡）。
     * 返回 true = 这一下被笔记吃掉了，调用方别再当别的手势。
     * **只认手指**——笔是用来写字的（同草稿纸图钉的纪律）。
     */
    protected fun tapNote(x: Float, y: Float): Boolean {
        val edit = noteEditHit(x, y)
        if (edit != null) {
            onOpenNoteEditor(edit.id, edit.page.toInt(), edit.nx, edit.ny, edit.text, false, edit.display)
            return true
        }
        val n = noteMarkerHit(x, y) ?: return false
        if (n.text.isEmpty()) return false     // 空正文没有可展开的东西
        if (!noteExpanded.remove(n.id)) noteExpanded.add(n.id)
        invalidate()
        return true
    }

    /** 笔悬停命中笔记标记 → 记下它（只对 `hover` 模式的笔记生效；变了才重画） */
    protected fun hoverNote(x: Float, y: Float) {
        val n = noteMarkerHit(x, y)
        val id = if (n != null && n.text.isNotEmpty() && n.display == NOTE_HOVER) n.id else null
        if (id == noteHoverId) return
        noteHoverId = id
        invalidate()
    }

    // —— 擦除：本地即时命中（与 Mac eraseNear 两模式一一对应）+ Mac 回传统一 ——

    /**
     * 命中判定在页内归一化坐标做、同页过滤、loc 为空不擦——两端乐观/真源语义保持一致：
     * - 整笔（eraserMode==0）：任一点命中即删整条；
     * - 局部（==1）：走 [InkEdit.splitStroke]（与 Mac `InkEdit.splitStroke` 同一算法，
     *   改一边必须同步另一边）。
     */
    protected fun eraseHit(x: Float, y: Float) {
        val loc = locate(x, y, wide = true) ?: return   // 页边的笔迹也要能擦到（画板模式）
        // 一次拖动只叫一次 [onEraseBegin]：模式1 的撤销栈要在**动手之前**留一份快照，
        // 而擦除是就地改 `strokes` 的（乐观预览），到了 onEraseEnd 现场早就没了。
        // 放在这里而不是各个 pointerDown 分支：擦除有好几个入口（橡皮模式 / 笔的侧键 / 橡皮头），
        // 它们最后都汇到这一处。
        if (!erasingGesture) { erasingGesture = true; onEraseBegin() }
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
            val segs = InkEdit.splitStroke(s, loc.nx, loc.ny, loc.page, r2)
            if (segs.size == 1 && segs[0] === s) out.add(s) else { out.addAll(segs); changed = true }
        }
        if (changed) {
            strokes.clear()
            strokes.addAll(out)
            invalidate()
        }
    }

    // —— 框选（lasso：拖空白=自由框选 / 拖选中高亮框内=移动 / 拖手柄=缩放；本地判定只为预览，真源复判执行） ——

    /**
     * 本地判定的选中集：镜像 Mac `LassoSelection`，但这里只用于渲染高亮——命中算法是客户端
     * 复刻的一份乐观预览（同 eraseHit 先例），提交移动/缩放时真源用同一套算法重新判定，不信任这里的下标。
     */
    protected data class LassoSelection(
        val page: Int,
        val box: FloatArray,          // x0,y0,x1,y1（路径包围盒，归一化，提交时原样带给真源复判）
        val poly: FloatArray,         // 自由框选路径（扁平数组 ≥3 点，提交时原样带上：多边形命中）
        val strokeIdx: List<Int>,
        val noteIdx: List<Int>,
        val bounds: FloatArray,       // x,y,w,h：命中内容的联合包围盒（画高亮框/手柄用）
    )

    /**
     * 当前框选选中集。**赋值即通知**（[onLassoSelectionChanged]）——顶栏那几个剪贴板按钮要据此
     * 灰掉/亮起，而这个字段在本类里有七八处赋值点，逐个补调用迟早漏一处。
     */
    protected var lassoSelection: LassoSelection? = null
        set(v) {
            val had = field != null
            field = v
            if (had != (v != null)) onLassoSelectionChanged(v != null)
        }
    protected var lassoDragMode = 0                 // 0=无手势 1=框选 2=移动 3=缩放
    protected var lassoAnchorPage = -1
    protected var lassoAnchorNx = 0f
    protected var lassoAnchorNy = 0f
    protected var lassoDownX = 0f
    protected var lassoDownY = 0f
    protected var lassoMoved = false
    protected val lassoPath = ArrayList<Pt2>()      // 进行中的自由框选路径（页内归一化）
    protected var lassoHandle = -1                  // 缩放中拖的是哪个手柄（0..7 = tl,tr,bl,br,t,b,l,r）
    protected var lassoScale: FloatArray? = null    // [ax, ay, sx, sy]（缩放 ghost + 提交值）
    protected var lassoDx = 0f
    protected var lassoDy = 0f
    protected var lassoCommitted = false
    protected var lassoTimeout: Runnable? = null

    /**
     * 真源镜像是否**分层记账**的开关（模式2 = true）：模式2 的 strokes/notes 是 Mac 两条独立广播，
     * 到达顺序/间隔无保证——乐观预览只能随各自的镜像**分层**退场（见 setStrokes/setNotes）。
     * 模式1 真源在进程内、回推一趟带齐两层，无此问题，保持 false（任意回推即清，旧行为不变）。
     */
    protected open val lassoMirrorSplit: Boolean get() = false

    /** 提交等待期：strokes/notes 镜像各自是否已到（true = 那层已改画真源，乐观变换停止作用于它） */
    protected var lassoSyncStrokes = false
    protected var lassoSyncNotes = false

    /** 8 个手柄的名字序（四角 + 四边中点）：下标即 [lassoHandle] 与 [lassoHandlePts] 的输出序 */
    private val lassoOpp = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6)   // 对侧手柄：角的对角 / 边的对边中点

    private val lassoBoxTmp = FloatArray(4)         // lassoViewBox 的输出（x,y,w,h，视口 px）
    private val lassoHandleTmp = FloatArray(16)     // lassoHandlePts 的输出（8×2，视口 px）
    private var lassoViewPts = FloatArray(256)      // 路径/halo 点集映射到视口坐标的复用缓冲

    private fun ensureViewPts(n: Int): FloatArray {
        if (lassoViewPts.size < n) lassoViewPts = FloatArray(n * 2)
        return lassoViewPts
    }

    fun clearLasso() {
        lassoTimeout?.let { handler.removeCallbacks(it) }
        lassoTimeout = null
        lassoSelection = null
        lassoDragMode = 0
        lassoAnchorPage = -1
        lassoMoved = false
        lassoHandle = -1
        lassoPath.clear()
        lassoScale = null
        lassoCommitted = false
        lassoSyncStrokes = false
        lassoSyncNotes = false
        lassoDx = 0f; lassoDy = 0f
        invalidate()
    }

    /** 有没有选中集（顶栏剪贴板按钮的可用性；[onLassoSelectionChanged] 之外的直接查询口） */
    fun hasLassoSelection(): Boolean = lassoSelection != null

    /** 顶栏「撤销 / 重做」。 */
    fun requestUndo(redo: Boolean) = onUndoRequested(redo)

    /**
     * 顶栏「剪切 / 复制」：把选中集的**多边形**交出去让真源复判（同框选移动的分工）。
     * 剪切后本地立刻放弃选中——被剪掉的笔迹等真源回推才真的消失（几十毫秒，同擦除的观感）。
     */
    fun requestClipCopy(cut: Boolean) {
        val sel = lassoSelection ?: return
        onClipCommit(if (cut) CLIP_CUT else CLIP_COPY, sel.page, 0f, 0f, sel.poly)
        if (cut) clearLasso()
    }

    /** 顶栏「粘贴」：落点 = **视口正中**那一页那一处（平板没有鼠标指针可用，同 web 端口径）。 */
    fun requestClipPaste() {
        val loc = locate(width / 2f, height / 2f, wide = true) ?: return
        onClipCommit(CLIP_PASTE, loc.page, loc.nx, loc.ny, null)
    }

    /**
     * 框选命中（本地复刻 Mac `finishLassoSelect` / web `lassoHitTest`：笔迹任一点落多边形内=命中，
     * 注解锚点落多边形内=命中；边界上的点算内）。`poly` 为扁平数组，< 3 点不构成选区。
     */
    protected fun lassoHitTest(page: Int, poly: FloatArray): LassoSelection? {
        if (poly.size < 6) return null
        // 🔴 包围盒的初值必须取 ±∞（而不是页角 1/0）：画板模式下笔迹与框选路径可以整个落在页外
        //（x 恒 > 1 或恒 < 0），按页角起算的话 `min(1f, 1.2f)` 还是 1 —— 框的那一条边就永远钉在
        // 页边上，选中框比笔迹大出一整片页边（用户 2026-08-30 报「拖到拓展区域后拖动框变得很大，
        // 从 pdf 边缘开始算左边框」）。Mac `InkEdit.bounds` 早就是这个口径，这份复刻当时没跟上。
        var bx0 = Float.POSITIVE_INFINITY; var by0 = Float.POSITIVE_INFINITY
        var bx1 = Float.NEGATIVE_INFINITY; var by1 = Float.NEGATIVE_INFINITY
        for (i in poly.indices step 2) {
            bx0 = min(bx0, poly[i]); bx1 = max(bx1, poly[i])
            by0 = min(by0, poly[i + 1]); by1 = max(by1, poly[i + 1])
        }
        val sIdx = ArrayList<Int>()
        val nIdx = ArrayList<Int>()
        var lox = Float.POSITIVE_INFINITY; var loy = Float.POSITIVE_INFINITY
        var hix = Float.NEGATIVE_INFINITY; var hiy = Float.NEGATIVE_INFINITY
        for (i in strokes.indices) {
            val s = strokes[i]
            if (s.page.toInt() != page) continue
            if (s.pts.none { InkEdit.pointInPolygon(it.x, it.y, poly) }) continue
            sIdx.add(i)
            for (pt in s.pts) {
                lox = min(lox, pt.x); loy = min(loy, pt.y)
                hix = max(hix, pt.x); hiy = max(hiy, pt.y)
            }
        }
        for (i in notes.indices) {
            val n = notes[i]
            if (n.page.toInt() != page) continue
            if (!InkEdit.pointInPolygon(n.nx, n.ny, poly)) continue
            nIdx.add(i)
            lox = min(lox, n.nx); loy = min(loy, n.ny)
            hix = max(hix, n.nx); hiy = max(hiy, n.ny)
        }
        if (sIdx.isEmpty() && nIdx.isEmpty()) return null
        return LassoSelection(
            page, floatArrayOf(bx0, by0, bx1, by1), poly.copyOf(), sIdx, nIdx,
            floatArrayOf(lox, loy, hix - lox, hiy - loy),
        )
    }

    /**
     * 当前选中集的屏显框（视口 px，外扩 6dp + 最小 16dp，**不含 ghost**）：
     * 高亮框渲染、框内命中判定、手柄位置共用这一份，别各算各的（同 Mac `lassoDisplayBox` 惯例）。
     */
    protected fun lassoViewBox(out: FloatArray): Boolean {
        val sel = lassoSelection ?: return false
        val b = sel.bounds
        val x0 = viewX(sel.page, b[0]); val y0 = viewY(sel.page, b[1])
        val x1 = viewX(sel.page, b[0] + b[2]); val y1 = viewY(sel.page, b[1] + b[3])
        val pad = dp(6f)
        out[0] = min(x0, x1) - pad
        out[1] = min(y0, y1) - pad
        out[2] = max(kotlin.math.abs(x1 - x0) + pad * 2, dp(16f))
        out[3] = max(kotlin.math.abs(y1 - y0) + pad * 2, dp(16f))
        return true
    }

    /** 8 个手柄的屏显位置（视口 px，**不含 ghost**；序 = 名字序 tl,tr,bl,br,t,b,l,r） */
    protected fun lassoHandlePts(out: FloatArray) {
        val x = lassoBoxTmp[0]; val y = lassoBoxTmp[1]
        val w = lassoBoxTmp[2]; val h = lassoBoxTmp[3]
        val xs = floatArrayOf(x, x + w, x, x + w, x + w / 2, x + w / 2, x, x + w)
        val ys = floatArrayOf(y, y, y + h, y + h, y, y + h, y + h / 2, y + h / 2)
        for (i in 0 until 8) { out[i * 2] = xs[i]; out[i * 2 + 1] = ys[i] }
    }

    /** ghost 是否生效：拖动中（move/scale，includeDrag=true 时）或已提交等真源回传期间 */
    protected fun lassoGhostOn(includeDrag: Boolean): Boolean =
        lassoSelection != null &&
            (lassoCommitted || (includeDrag && (lassoDragMode == 2 || lassoDragMode == 3)))

    /**
     * 选中集点变换（页内归一化，clamp 0...1）：scale = 绕锚点按轴缩放，否则 = move 平移
     * （镜像 Mac `lassoGhostPoint` 语义——数据不动，画的时候偏）。无变换返回 false（out 不动）。
     */
    protected fun lassoGhost(nx: Float, ny: Float, out: FloatArray): Boolean {
        // x 用 [cmarginMax] 而不是当前档位：预览要与提交后的结果对得上（提交那边也是硬上限），
        // 拿当前档位夹的话，往还没长出来的那片页边拖时预览会被摁在边界上。Mac 的 ghost 干脆不夹。
        val m = cmarginMax()
        val sc = lassoScale
        if (sc != null && (sc[2] != 1f || sc[3] != 1f)) {
            out[0] = (sc[0] + (nx - sc[0]) * sc[2]).coerceIn(-m, 1f + m)
            out[1] = (sc[1] + (ny - sc[1]) * sc[3]).coerceIn(0f, 1f)
            return true
        }
        if (lassoDx == 0f && lassoDy == 0f) return false
        out[0] = (nx + lassoDx).coerceIn(-m, 1f + m)
        out[1] = (ny + lassoDy).coerceIn(0f, 1f)
        return true
    }

    /**
     * 越过死区后判一次形态：落笔点命中某手柄（≤10dp）→ 缩放；落在当前选中高亮框内
     * （含 8dp 抓手余量）→ 移动；否则重新自由框选（并放弃旧选中，同 Mac/web 逻辑）。
     */
    protected fun handleLassoMove(x: Float, y: Float) {
        if (lassoAnchorPage < 0) return
        if (!lassoMoved) {
            if (hypot(x - lassoDownX, y - lassoDownY) < lassoDeadPx) return
            lassoMoved = true
            var m = 1
            val sel = lassoSelection
            if (sel != null && sel.page == lassoAnchorPage && lassoViewBox(lassoBoxTmp)) {
                lassoHandlePts(lassoHandleTmp)
                var hit = -1
                for (i in 0 until 8) {
                    if (hypot(lassoDownX - lassoHandleTmp[i * 2], lassoDownY - lassoHandleTmp[i * 2 + 1]) <= dp(10f)) {
                        hit = i; break
                    }
                }
                if (hit >= 0) {
                    m = 3
                    lassoHandle = hit
                    // 缩放锚点 = 对侧手柄（屏显 → 页内归一化折算一次，整个拖动期间不变）
                    val opp = lassoOpp[hit]
                    pageLocClamped(lassoHandleTmp[opp * 2], lassoHandleTmp[opp * 2 + 1], sel.page, tmp2, wide = true)
                    lassoScale = floatArrayOf(tmp2[0], tmp2[1], 1f, 1f)
                } else if (lassoBoxContains(lassoDownX, lassoDownY)) m = 2
            }
            lassoDragMode = m
            if (m == 1) {
                lassoSelection = null
                lassoPath.clear()
                lassoPath.add(Pt2(lassoAnchorNx, lassoAnchorNy))
            }
        }
        pageLocClamped(x, y, lassoAnchorPage, tmp2, wide = true)
        when (lassoDragMode) {
            1 -> {
                // 自由路径：≥3dp 抽稀（更密的点对多边形命中无增益，白耗 O(点数×边数)）
                val last = lassoPath.last()
                val lx = viewX(lassoAnchorPage, last.x)
                val ly = viewY(lassoAnchorPage, last.y)
                if (hypot(x - lx, y - ly) >= dp(3f)) lassoPath.add(Pt2(tmp2[0], tmp2[1]))
            }
            2 -> {
                lassoDx = tmp2[0] - lassoAnchorNx
                lassoDy = tmp2[1] - lassoAnchorNy
            }
            3 -> updateLassoScale(x, y)
        }
        invalidate()
    }

    /**
     * 缩放手柄拖动中：由被拖手柄当前位置与原「手柄→对侧手柄」向量算缩放比（屏显空间；
     * 按轴线性变换，与归一化坐标严格等价，同 Mac `updateLassoScaleGhost` / web `handleLassoMove`），
     * clamp 0.05...20。**角手柄 = 等比**（取变化幅度更大的一轴；安卓没有 Shift，不做自由两轴）、
     * **边中点手柄 = 单轴**（另一轴恒 1）。
     */
    private fun updateLassoScale(x: Float, y: Float) {
        val sel = lassoSelection ?: return
        val h = lassoHandle
        if (h < 0) return
        val sc = lassoScale ?: return
        if (!lassoViewBox(lassoBoxTmp)) return
        lassoHandlePts(lassoHandleTmp)
        val aVx = viewX(sel.page, sc[0]); val aVy = viewY(sel.page, sc[1])
        val denomX = lassoHandleTmp[h * 2] - aVx
        val denomY = lassoHandleTmp[h * 2 + 1] - aVy
        var sx = 1f; var sy = 1f
        when (h) {
            4, 5 -> if (kotlin.math.abs(denomY) > 1f) sy = (y - aVy) / denomY        // t/b 竖向单轴
            6, 7 -> if (kotlin.math.abs(denomX) > 1f) sx = (x - aVx) / denomX        // l/r 横向单轴
            else -> if (kotlin.math.abs(denomX) > 1f && kotlin.math.abs(denomY) > 1f) {
                sx = (x - aVx) / denomX; sy = (y - aVy) / denomY
                val s = if (kotlin.math.abs(sx - 1) >= kotlin.math.abs(sy - 1)) sx else sy
                sx = s; sy = s
            }
        }
        sc[2] = sx.coerceIn(0.05f, 20f)
        sc[3] = sy.coerceIn(0.05f, 20f)
    }

    /**
     * 提交的移动/缩放已被真源采纳（镜像到齐）：**选中集不清空**，按变换后的多边形在真源的新数据上
     * 重判一次，乐观变换退场——高亮框/手柄/光晕留在内容的新位置上继续跟着它（同 Mac
     * `commitLassoMove` 末尾的 `lassoSelection = s`）。只有点空白、切工具、换文档才真的清
     * （2026-08-29 用户报「索套移动后就消失了」）。
     *
     * 为什么按**多边形**重判而不是按 id 认领：模式2 的笔迹根本没有 id（线格式不传，见 `Ink.Stroke`
     * 的注释），认不了；而多边形重判恰好与真源下一次收到 `lassoMove` 时的复判是同一口径
     * （平移/缩放是仿射变换，点在多边形内的关系原样保持），反倒更贴——毕竟接着拖第二次时
     * 真源看的就是这个变换后的多边形。命中为空（选中项已被擦除/删除）→ 清，同 Mac 的 `changed` 分支。
     */
    private fun settleLasso() {
        lassoTimeout?.let { handler.removeCallbacks(it) }
        lassoTimeout = null
        val sel = lassoSelection
        val poly = sel?.let { lassoCommittedPoly(it) }   // 必须在清掉 dx/scale 之前算
        lassoCommitted = false
        lassoSyncStrokes = false
        lassoSyncNotes = false
        lassoDx = 0f; lassoDy = 0f
        lassoScale = null
        lassoSelection = if (sel != null && poly != null) lassoHitTest(sel.page, poly) else null
        invalidate()
    }

    /**
     * 选中集在原地跟一次真源：整表替换过后（擦除/图层显隐/别处的框选）旧下标不再指向同一条笔迹，
     * 拿选区多边形照原口径重判一遍——命中没了就自然清空。选中集现在会一直留到用户点空白
     * （见 [settleLasso]），所以这一步必须做，否则光晕会画到不相干的笔迹上。手势/提交进行中不动它。
     */
    private fun refreshLassoSelection() {
        val sel = lassoSelection ?: return
        if (lassoDragMode != 0 || lassoCommitted) return
        lassoSelection = lassoHitTest(sel.page, sel.poly)
    }

    /**
     * 推迟到本轮主线程消息末尾再结算（模式1 用）：那边一趟回推带齐两层，但 `applyStrokes` /
     * `applyNotes` 是先后两次调用——在 `setStrokes` 里当场重判会拿**还没更新的注解**去比新多边形，
     * 注解那半必然落空。推迟一轮，两层都就位了再判。重复 post 无害：第一次结算已把 committed 落下。
     */
    private fun postSettleLasso() {
        post { if (lassoCommitted) settleLasso() }
    }

    /** 选区多边形跟着刚提交的那次变换走（逐点过 [lassoGhost]，含同一套页内 clamp） */
    private fun lassoCommittedPoly(sel: LassoSelection): FloatArray {
        val poly = sel.poly.copyOf()
        for (i in poly.indices step 2) {
            if (lassoGhost(poly[i], poly[i + 1], tmp2)) { poly[i] = tmp2[0]; poly[i + 1] = tmp2[1] }
        }
        return poly
    }

    /** 点 (x,y) 是否落在当前选中高亮框内（视口 px，含 8dp 抓手余量）；无选中/框算不出 → false */
    protected fun lassoBoxContains(x: Float, y: Float): Boolean {
        if (lassoSelection == null || !lassoViewBox(lassoBoxTmp)) return false
        val g = dp(8f)
        return x >= lassoBoxTmp[0] - g && x <= lassoBoxTmp[0] + lassoBoxTmp[2] + g &&
            y >= lassoBoxTmp[1] - g && y <= lassoBoxTmp[1] + lassoBoxTmp[3] + g
    }

    /** 提交后的共同收尾：标 committed + 记账位清零 + 1s 兜底超时（现在只是保险丝：Mac 零命中也会回传未变镜像） */
    private fun commitLassoPending() {
        lassoCommitted = true
        lassoSyncStrokes = false
        lassoSyncNotes = false
        lassoTimeout?.let { handler.removeCallbacks(it) }
        val r = Runnable { lassoTimeout = null; if (lassoCommitted) clearLasso() }
        lassoTimeout = r
        handler.postDelayed(r, 1000)
        invalidate()
    }

    /**
     * 松手收尾：纯点击（未越过死区）→ **点在选中框外**才清选中（点框内当成「还要接着操作它」，
     * 2026-08-29 用户要「点其他区域才消失」；Mac 那边是无条件清，此处刻意更宽松一档）；
     * 框选 → 本地判定命中集（只渲染高亮，不上行）；移动/缩放 → 提交给真源（复判 + 持久化）。
     */
    protected fun finishLasso() {
        val page = lassoAnchorPage
        if (!lassoMoved || lassoDragMode == 0) {
            if (lassoSelection != null && !lassoBoxContains(lassoDownX, lassoDownY)) {
                lassoSelection = null; invalidate()
            }
        } else if (lassoDragMode == 1 && page >= 0 && lassoPath.size >= 3) {
            val poly = FloatArray(lassoPath.size * 2)
            for (i in lassoPath.indices) { poly[i * 2] = lassoPath[i].x; poly[i * 2 + 1] = lassoPath[i].y }
            lassoSelection = lassoHitTest(page, poly)
            // 「框了但没选中」与「框错页了」长得一模一样，不打点只能靠猜
            Log.i(TAG, "框选 page=$page 路径=${lassoPath.size}点 命中 笔迹=${lassoSelection?.strokeIdx?.size ?: 0} 注解=${lassoSelection?.noteIdx?.size ?: 0}")
            invalidate()
        } else if (lassoDragMode == 2) {
            val sel = lassoSelection
            if (sel != null && (lassoDx != 0f || lassoDy != 0f)) {
                onLassoMoveCommit(sel.page, sel.box, lassoDx, lassoDy, sel.poly)
                commitLassoPending()
            }
        } else if (lassoDragMode == 3) {
            val sel = lassoSelection
            val sc = lassoScale
            if (sel != null && sc != null && (sc[2] != 1f || sc[3] != 1f)) {
                onLassoScaleCommit(sel.page, sel.box, sc[0], sc[1], sc[2], sc[3], sel.poly)
                commitLassoPending()
            }
        }
        lassoDragMode = 0
        lassoMoved = false
        lassoHandle = -1
        lassoAnchorPage = -1
        lassoPath.clear()
        if (!lassoCommitted) { lassoDx = 0f; lassoDy = 0f; lassoScale = null }
    }

    protected fun drawLassoOverlay(canvas: Canvas) {
        // 进行中的自由框选虚线路径
        if (lassoDragMode == 1 && lassoAnchorPage >= 0 && lassoPath.size >= 2) {
            val pg = lassoAnchorPage
            val buf = ensureViewPts(lassoPath.size * 2)
            for (i in lassoPath.indices) {
                buf[i * 2] = viewX(pg, lassoPath[i].x)
                buf[i * 2 + 1] = viewY(pg, lassoPath[i].y)
            }
            overlays.drawLassoPath(canvas, buf, lassoPath.size)
        }
        val sel = lassoSelection ?: return
        if (!lassoViewBox(lassoBoxTmp)) return
        val ghost = lassoGhostOn(true)

        // —— 选中笔迹光晕（所见即所选；ghost 期间随变换预览，注解不加光晕——高亮框已覆盖） ——
        // strokes 镜像已到（lassoSyncStrokes）则跳过：那层已改画真源，halo 的命中下标随回传作废。
        if (!lassoSyncStrokes) for (idx in sel.strokeIdx) {
            val s = strokes.getOrNull(idx) ?: continue
            if (s.page.toInt() != sel.page) continue
            val wPx = dp(s.pen.w + 5f)   // 线宽 = 笔宽 + 5，与笔迹渲染同一尺度（dp → px）
            if (s.pts.size == 1) {
                var nx = s.pts[0].x; var ny = s.pts[0].y
                if (ghost && lassoGhost(nx, ny, tmp2)) { nx = tmp2[0]; ny = tmp2[1] }
                overlays.drawLassoHaloDot(canvas, viewX(sel.page, nx), viewY(sel.page, ny), wPx / 2f)
                continue
            }
            val buf = ensureViewPts(s.pts.size * 2)
            for (j in s.pts.indices) {
                var nx = s.pts[j].x; var ny = s.pts[j].y
                if (ghost && lassoGhost(nx, ny, tmp2)) { nx = tmp2[0]; ny = tmp2[1] }
                buf[j * 2] = viewX(sel.page, nx)
                buf[j * 2 + 1] = viewY(sel.page, ny)
            }
            overlays.drawLassoHalo(canvas, buf, s.pts.size, wPx)
        }

        // —— 高亮框 + 8 手柄（ghost：scale 绕锚点缩放 / move 平移；屏显坐标系直接变换） ——
        val sc = lassoScale?.takeIf { ghost && (it[2] != 1f || it[3] != 1f) }
        val gdx = if (ghost && sc == null) lassoDx * pw() else 0f
        val gdy = if (ghost && sc == null) lassoDy * dispH[sel.page] else 0f
        val aVx = if (sc != null) viewX(sel.page, sc[0]) else 0f
        val aVy = if (sc != null) viewY(sel.page, sc[1]) else 0f
        fun gx(v: Float): Float = if (sc != null) aVx + (v - aVx) * sc[2] else v + gdx
        fun gy(v: Float): Float = if (sc != null) aVy + (v - aVy) * sc[3] else v + gdy
        val bx = lassoBoxTmp[0]; val by = lassoBoxTmp[1]
        val bw = lassoBoxTmp[2]; val bh = lassoBoxTmp[3]
        // 非等比缩放下四角不再贴包络，取四点的外接轴对齐框（同 web/Mac 的 lo/hi 归并）
        val cxs = floatArrayOf(gx(bx), gx(bx + bw), gx(bx), gx(bx + bw))
        val cys = floatArrayOf(gy(by), gy(by), gy(by + bh), gy(by + bh))
        overlays.drawLassoSelection(
            canvas,
            cxs.min(), cys.min(), cxs.max(), cys.max(),
        )
        lassoHandlePts(lassoHandleTmp)
        for (i in 0 until 8) {
            lassoHandleTmp[i * 2] = gx(lassoHandleTmp[i * 2])
            lassoHandleTmp[i * 2 + 1] = gy(lassoHandleTmp[i * 2 + 1])
        }
        overlays.drawLassoHandles(canvas, lassoHandleTmp)
    }

    // —— 平移 / 惯性 / 滚动上报 ——
    protected var vx = 0f
    protected var vy = 0f              // 速度（scroll px/ms）
    protected var lastMoveT = 0L
    protected var momentumRunning = false
    protected var reportPending = false
    private val handler = Handler(Looper.getMainLooper())   // protected 会与 View.getHandler() 撞签名

    protected fun afterPan() {
        ensureImages()
        invalidate()
        onHudChanged()
        emitScroll()
    }

    protected fun panBy(dx: Float, dy: Float) {
        if (!hLocked) scrollX = (scrollX + dx).coerceIn(0f, maxScrollX)
        scrollY = (scrollY + dy).coerceIn(0f, maxScrollY)
        afterPan()
    }

    protected fun cancelMomentum() {
        momentumRunning = false
    }

    /** 松手惯性：按松手速度继续滚，0.94^(dt/16) 指数衰减，碰边界该轴停；期间持续上报 */
    protected fun startMomentum() {
        cancelMomentum()
        if (hLocked) vx = 0f   // 横向锁死，甩出去的那一下也不许带横向分量
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
    protected fun emitScroll() {
        if (reportPending) return
        reportPending = true
        handler.postDelayed({
            reportPending = false
            val docY = scrollY
            for (i in 0 until pageCount) {
                if (docY < offY[i] + dispH[i] + gapPx) {   // 严格 <，与 topVisiblePage 同一边界
                    val frac = ((docY - offY[i]) / max(1f, dispH[i])).coerceIn(0f, 1f)
                    onScrollReport(i, frac)
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
        onGotoPage(i)
        afterPan()
    }

    /**
     * 滚到某页的某个页内比例。**书写中不动**（笔正在写时被外部滚走 = 笔迹被撕开）；先停惯性。
     * 模式2 用它落地 Mac 的视口下发，模式1 用它恢复阅读进度。返回是否真的滚了。
     */
    fun scrollToPageFrac(page: Int, frac: Float): Boolean {
        if (activePen) return false
        cancelMomentum()
        if (page < 0 || page >= pageCount || offY.isEmpty()) return false
        scrollY = (offY[page] + frac * dispH[page]).coerceIn(0f, maxScrollY)
        ensureImages()
        invalidate()
        onHudChanged()
        return true
    }

    /** 当前视口顶所在页的页内比例（存阅读进度用，与 onScrollReport 同一口径） */
    fun topFrac(): Float {
        val i = topVisiblePage()
        if (i !in 0 until pageCount || offY.isEmpty()) return 0f
        return ((scrollY - offY[i]) / max(1f, dispH[i])).coerceIn(0f, 1f)
    }

    /** 当前缩放倍率（相对 fit-width，1=贴合宽度），存 read_zoom 用 */
    fun zoomLevel(): Float = zoom

    /** 横向滚动比例（scrollX / 内容宽），存 read_hfrac 用；未缩放时恒为 0 */
    fun hFrac(): Float = if (pw() > 0f) scrollX / pw() else 0f

    /** 恢复横向滚动（须在 [applyZoom] 之后调，否则内容宽还是旧的） */
    fun applyHFrac(f: Float) {
        scrollX = (f * pw()).coerceIn(0f, maxScrollX)
        invalidate()
    }

    /** 恢复缩放（进度恢复时先设缩放再滚，否则 offY 还是旧的、滚到的位置会偏） */
    fun applyZoom(z: Float) {
        zoom = z.coerceIn(PadConst.MIN_ZOOM, PadConst.MAX_ZOOM)
        onGeomChanged()
    }

    // —— 批缓冲（8ms flush；erase 点带页号，flush 时按页分组发送） ——
    protected val inkBatch = ArrayList<Pt3>()
    protected data class ErasePt(val x: Float, val y: Float, val page: Int)
    protected val eraseBatch = ArrayList<ErasePt>()
    protected val probeBatch = ArrayList<Pt2>()
    protected val flusher = object : Runnable {
        override fun run() {
            flushBatch()
            handler.postDelayed(this, 8)
        }
    }

    protected fun flushBatch() {
        if (inkBatch.isNotEmpty()) {
            onInkMove(ArrayList(inkBatch))
            inkBatch.clear()
            onMoveFrame()
        }
        if (eraseBatch.isNotEmpty()) {
            // 擦除点带页号：按页分组发送，Mac 端据此只删对应页的笔迹
            val byPage = LinkedHashMap<Int, MutableList<Pt2>>()
            for (pt in eraseBatch) byPage.getOrPut(pt.page) { ArrayList() }.add(Pt2(pt.x, pt.y))
            eraseBatch.clear()
            for ((pg, pts) in byPage) {
                onErase(pg, pts)
                onMoveFrame()
            }
            lastEraseRel = sentRelSeq()   // 比 ackRel 用（见 setStrokes）：擦除才怕旧快照
        }
        if (probeBatch.isNotEmpty()) {
            onProbeMove(ArrayList(probeBatch))
            probeBatch.clear()
        }
    }

    init {
        handler.postDelayed(flusher, 8)
    }

    // —— 指针：笔=画/擦/平移/框选，手指=平移/双指缩放 ——
    protected var activePen = false

    /** 笔尖此刻是否压在屏幕上。诊断用：书写期间侧键收不到事件，日志里要能对上时刻 */
    fun isPenDown(): Boolean = activePen
    protected var penId = -1
    protected var penMode = MODE_NOTE
    protected var penX = 0f
    protected var penY = 0f
    protected var drawPage = 0
    protected var lineStroke = false      // 落笔那一刻锁进这一笔的尺子状态
    protected var linePress = 0f          // 尺子笔这一笔的峰值压感（两点直线恒宽，见 penMove 的尺子分支）
    protected var probing = false         // 探针流（擦除/翻页模式）：平行上报笔位置给 Mac 判长按
    protected var probePage = 0
    protected val snapOut = FloatArray(2)

    protected data class Finger(var x: Float, var y: Float)
    protected val touches = HashMap<Int, Finger>()
    protected val touchOrder = ArrayList<Int>()
    protected var panId = -1
    protected var lastPanX = 0f
    protected var lastPanY = 0f
    protected var panDownX = 0f
    protected var panDownY = 0f
    protected var panStarted = false
    /** 本次手指手势变过双指捏合（双指轻点不触发 [onFingerTap]） */
    protected var gesturePinched = false

    /** 本次手指手势被 [twoFingerScroll] 挡下过（划了但没平移）——抬手时不能再当轻点去开图钉 */
    protected var gestureBlocked = false

    protected data class Pinch(val fx: Float, val fy: Float)
    protected var pinch: Pinch? = null
    // 捏合按帧增量算（见 PinchSplit）：上一帧的两指间距与中点
    protected var pinchLastD = 0f
    protected var pinchLastMx = 0f
    protected var pinchLastMy = 0f
    protected val pinchSplit = PinchSplit(density)

    // 防误触：笔靠近时不认手指；手指手势被判成误触时还原到它开始之前（见 PenProximity）
    protected val penNear = PenProximity()
    protected var gestureT0 = 0L
    protected var gestureZoom0 = 1f
    protected var gestureSX0 = 0f
    protected var gestureSY0 = 0f

    // —— 手写笔侧键诊断 ——
    //
    // 用户报「按笔杆侧键切橡皮擦很不灵敏，要按好几次，网页那边就很跟手」。但**两端代码里都没有
    // 一行侧键处理**——所以那是系统层面把侧键映射成了别的东西（各家 ROM 做法不一：有的改
    // `toolType` 为 ERASER，有的置 `buttonState` 的 STYLUS_PRIMARY/SECONDARY 位，有的干脆发
    // `ACTION_BUTTON_PRESS`），而这个 View 恰好一个都没接住。
    //
    // 先打点把系统**实际**报上来的东西记下来，再决定接哪个信号：猜错一轮就是一次真机往返
    // （这一处已经为「靠猜」付过两次学费，见 ANDROID-STANDALONE-PLAN.md §9.9）。
    // 只在组合变化时记一条，正常书写不会刷屏。
    private var lastStylusSig = Int.MIN_VALUE

    private fun logStylus(e: MotionEvent, where: String) {
        val tt = if (e.pointerCount > 0) e.getToolType(0) else -1
        if (tt == MotionEvent.TOOL_TYPE_FINGER) return
        val sig = e.buttonState * 31 + tt
        if (sig == lastStylusSig) return
        lastStylusSig = sig
        Log.i(
            TAG,
            "笔信号 $where toolType=$tt(2=STYLUS 4=ERASER) " +
                "buttonState=0x${Integer.toHexString(e.buttonState)}" +
                "(0x20=STYLUS_PRIMARY 0x40=STYLUS_SECONDARY 0x02=SECONDARY) " +
                "action=${e.actionMasked}",
        )
    }

    /**
     * 这个事件是不是「笔在当橡皮用」——笔尾橡皮头倒过来擦，或按住侧键擦。
     * 两条都是各家 ROM 常见的映射；哪条在这台设备上真的会来，看 [logStylus] 打出来的日志。
     */
    private fun isEraserSignal(e: MotionEvent): Boolean {
        val tt = if (e.pointerCount > 0) e.getToolType(0) else -1
        if (tt == MotionEvent.TOOL_TYPE_ERASER) return true
        val b = e.buttonState
        return b and MotionEvent.BUTTON_STYLUS_PRIMARY != 0 ||
            b and MotionEvent.BUTTON_STYLUS_SECONDARY != 0 ||
            b and MotionEvent.BUTTON_SECONDARY != 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        logStylus(e, "touch")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMomentum()
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    if (fingerAllowed(e, 0)) {
                        fingerDown(e.getPointerId(0), e.getX(0), e.getY(0), e.getTouchMajor(0), e.eventTime)
                    }
                } else {
                    penNear.onPen()
                    penDown(e, 0)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelMomentum()
                val idx = e.actionIndex
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    if (fingerAllowed(e, idx)) {
                        fingerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx), e.getTouchMajor(idx), e.eventTime)
                    }
                } else if (!activePen) {
                    penNear.onPen()
                    rejectFingerGesture("笔落下")   // 手指手势进行中笔落下：那多半是手掌先着了屏
                    penDown(e, idx)   // 笔落下：笔优先（penDown 内清掉手指状态）
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePen) {
                    penNear.onPen()
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
                if (activePen && id == penId) { penNear.onPen(); endPen() }
                else if (id in touches && PenProximity.canceled(e)) rejectFingerGesture("系统判为手掌")
                else endTouch(id)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePen) {
                    penNear.onPen()
                    endPen()
                    touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
                } else if (touches.isNotEmpty() && PenProximity.canceled(e)) {
                    rejectFingerGesture("系统判为手掌")
                } else {
                    val wasPanning = panStarted
                    val wasPinDrag = pinDragActive
                    val wasTextSel = textSelDragging
                    // 单指轻点（没越过死区、没捏合过、也没拖过图钉/划过字、确实是这根手指落下的那次手势）
                    val tap = !wasPanning && !wasPinDrag && !wasTextSel && !gesturePinched &&
                        !gestureBlocked && panId >= 0 && e.actionMasked == MotionEvent.ACTION_UP
                    val tx = panDownX; val ty = panDownY
                    pinchSplit.logEnd("页面")
                    // 松手位置用于提交图钉拖动（比最后一帧 move 更准）
                    val pi = e.findPointerIndex(panId)
                    val ux = if (pi >= 0) e.getX(pi) else tx
                    val uy = if (pi >= 0) e.getY(pi) else ty
                    touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
                    pinDragCandidate = false; pinDragActive = false
                    textSelCandidate = false; textSelDragging = false
                    endTextSelect()
                    when {
                        wasTextSel -> Unit              // 划字松手：选区已经在拖动里算好了，动作条自己会弹
                        wasPanning -> startMomentum()   // 松手甩动 → 惯性
                        wasPinDrag ->
                            if (e.actionMasked == MotionEvent.ACTION_UP) onPinDragEnd(ux, uy)
                            else onPinDragCancel()
                        // 书签缎带 → 草稿纸图钉 → 文字笔记标记/气泡铅笔（缎带贴在页右缘，
                        // 与另两者不会撞位；放最前是因为它的热区最小、被别人先吃掉就点不着了）
                        // 页面上的东西优先（缎带 → 草稿纸图钉 → 笔记标记/气泡铅笔），
                        // 都没命中且在选字模式下才当划字的轻点：双击选整行、单击收起选区
                        // （与 Mac 同语义）。**次序不能反**——反了在选字模式下就点不开图钉了。
                        tap -> bookmarkHit(tx, ty)?.let { onBookmarkTap(it) }
                            ?: run {
                                if (!onFingerTap(tx, ty) && !tapNote(tx, ty) && mode == MODE_TEXT) {
                                    tapTextSelect(tx, ty)
                                }
                            }
                    }
                }
            }
        }
        return true
    }

    /** 这根手指认不认：笔在写 / 笔在附近 / 接触面过大都不认（每次落指打一行接触尺寸，调门槛看它） */
    private fun fingerAllowed(e: MotionEvent, idx: Int): Boolean {
        val why = when {
            activePen -> "笔在写"
            penNear.near() -> "笔在附近"
            e.getTouchMajor(idx) > palmPx -> "接触面过大"
            else -> null
        }
        PenProximity.logFinger("页面", e, idx, density, why)
        return why == null
    }

    /**
     * 进行中的手指手势判成误触（笔靠近 / 笔落下 / 系统判为手掌）：立即结束、不甩惯性、不当轻点；
     * 手势开始不到 [PenProximity.REVERT_MS] 就把缩放与滚动位置还原到手指落下之前。
     */
    protected fun rejectFingerGesture(reason: String) {
        if (touches.isEmpty()) return
        val young = SystemClock.uptimeMillis() - gestureT0 < PenProximity.REVERT_MS
        val moved = zoom != gestureZoom0 || scrollX != gestureSX0 || scrollY != gestureSY0
        Log.i(PenProximity.TAG, "手指手势判为误触（$reason）" + if (young && moved) "，画面还原" else "")
        pinchSplit.logEnd("页面·误触")
        if (pinDragActive) onPinDragCancel()
        touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
        pinDragCandidate = false; pinDragActive = false
        textSelCandidate = false; textSelDragging = false; endTextSelect()
        gestureBlocked = true
        cancelMomentum(); vx = 0f; vy = 0f
        if (young && moved) {
            zoom = gestureZoom0
            recompute()
            scrollX = gestureSX0.coerceIn(0f, maxScrollX)
            scrollY = gestureSY0.coerceIn(0f, maxScrollY)
            ensureImages()
            invalidate()
            emitGeom()
            onHudChanged()
            emitScroll()
        }
    }

    protected fun fingerDown(id: Int, x: Float, y: Float, touchMajor: Float, eventTime: Long) {
        if (activePen) return            // 笔在写 → 忽略手掌/手指
        if (touchMajor > palmPx) return  // 大面积接触（手掌）忽略
        if (touchOrder.isEmpty()) {   // 一次新手势的第一根手指：记下起点，误触时还原到这里
            gesturePinched = false; gestureBlocked = false
            gestureT0 = SystemClock.uptimeMillis()
            gestureZoom0 = zoom; gestureSX0 = scrollX; gestureSY0 = scrollY
            pinchSplit.reset()
        }
        touches[id] = Finger(x, y)
        if (id !in touchOrder) touchOrder.add(id)
        if (touchOrder.size >= 2) {
            beginPinch()
        } else {
            panId = id
            lastPanX = x; lastPanY = y; panDownX = x; panDownY = y
            panStarted = false
            pinDragCandidate = fingerPinHit(x, y)   // 落在图钉上：过死区前都还有可能是单击
            // 选字模式：这一下**可能**是划字。过死区才真开始（没过就是轻点，走双击选行/收起选区），
            // 与图钉拖动共用同一套死区判定；这里落在没有文本层的地方则一切照旧＝平移。
            textSelCandidate = mode == MODE_TEXT && !pinDragCandidate && beginTextSelect(x, y)
            vx = 0f; vy = 0f; lastMoveT = eventTime
        }
    }

    protected fun endTouch(id: Int) {
        if (id !in touches) return
        touches.remove(id)
        touchOrder.remove(id)
        pinch = null
        when {
            touchOrder.size == 1 -> {
                // 回到单指平移（重新死区判定，避免松指跳动）。图钉候选一并作废：
                // 双指折腾过后剩下那根手指的落点早已不在当初的图钉上
                panId = touchOrder[0]
                val t = touches[panId]!!
                lastPanX = t.x; lastPanY = t.y; panDownX = t.x; panDownY = t.y
                panStarted = false
                pinDragCandidate = false
                textSelCandidate = false; textSelDragging = false; endTextSelect()
            }
            touchOrder.isEmpty() -> {
                pinchSplit.logEnd("页面")
                if (panStarted) startMomentum()
                panId = -1; panStarted = false
                pinDragCandidate = false
                textSelCandidate = false; textSelDragging = false; endTextSelect()
            }
            else -> beginPinch()
        }
    }

    protected fun beginPinch() {
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        gesturePinched = true
        if (pinDragActive) { pinDragActive = false; onPinDragCancel() }   // 变双指：拖图钉作废
        pinDragCandidate = false
        // 变双指 = 想缩放/挪画面，不是想划字。已经划出来的选区留着（用户多半是想放大看清再动作）
        textSelCandidate = false; textSelDragging = false
        endTextSelect()
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val p = pw()
        pinch = Pinch(
            fx = if (p > 0) (mx - contentLeft()) / p else 0.5f,   // 捏合中点抓住的内容比例（固定锚点）
            fy = if (totalH > 0) (my - barH + scrollY) / totalH else 0f,
        )
        pinchLastD = hypot(a.x - b.x, a.y - b.y)
        pinchLastMx = mx; pinchLastMy = my
        panId = -1; panStarted = false
    }

    /**
     * 双指：整体移动 100% 跟手（锚点比例始终跟随当前中点）；缩放按帧由 [PinchSplit] 拆出来——
     * 双指滚动时手指自然并拢的那点间距变化不算缩放。纯本地查看，不上报位置（避免回环）
     */
    protected fun pinchMove() {
        val pc = pinch ?: return
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val d = hypot(a.x - b.x, a.y - b.y)
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        val f = pinchSplit.factor(pinchLastD, d, mx - pinchLastMx, my - pinchLastMy)
        pinchLastD = d; pinchLastMx = mx; pinchLastMy = my
        val z0 = zoom
        if (!zoomLocked) zoom = (zoom * f).coerceIn(PadConst.MIN_ZOOM, PadConst.MAX_ZOOM)
        recompute()
        val x0 = scrollX
        scrollY = (pc.fy * totalH - (my - barH)).coerceIn(0f, maxScrollY)
        // scrollX 的基准是**内容**左缘，而 fx 抓的是**页内**比例 → 画板模式下要补上左侧页边那一段
        // （关着时 cmargin()==0，与画板模式之前同式）。
        scrollX = if (contentWidth() > vw) (cmargin() * pw() + pc.fx * pw() - mx).coerceIn(0f, maxScrollX)
                  else 0f
        // 锁横向：zoom 没变 = 这是双指整体挪动，横向该被挡下；zoom 变了则是缩放重锚，照旧
        // （不然放大后画面会横向乱跳，见 [hLocked]）。
        if (hLocked && zoom == z0) scrollX = x0.coerceIn(0f, maxScrollX)
        ensureImages()
        invalidate()
        emitGeom()   // 页宽变了要告诉 Mac（选笔盘的像素判定基准），与位置无关、不构成回环
        onHudChanged()
    }

    protected fun panMove(e: MotionEvent, pi: Int) {
        val x = e.getX(pi)
        val y = e.getY(pi)
        if (!panStarted && !pinDragActive) {
            if (hypot(x - panDownX, y - panDownY) < deadPx) return
            if (textSelCandidate) {
                // 划字拖过死区 = 选字（不进平移：不算速度、松手不甩惯性，同拖图钉那条）
                textSelCandidate = false
                textSelDragging = true
            } else if (pinDragCandidate) {
                // 按住图钉拖过死区 = 拖图钉（不进平移：不算速度、松手不甩惯性）
                pinDragCandidate = false
                pinDragActive = true
            } else {
                // 双指滚动模式：单指划动到此为止——不平移、不记速度、松手也不甩惯性
                if (twoFingerScroll) { gestureBlocked = true; return }
                panStarted = true
                lastPanX = x; lastPanY = y; lastMoveT = e.eventTime
                return
            }
        }
        if (textSelDragging) {
            moveTextSelect(x, y)
            return
        }
        if (pinDragActive) {
            onPinDragMove(x, y)
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

    protected fun penDown(e: MotionEvent, idx: Int) {
        // 笔优先：清掉进行中的手指平移/捏合（拖图钉拖一半落笔 = 作废撤回）
        touches.clear(); touchOrder.clear(); pinch = null; panId = -1; panStarted = false
        if (pinDragActive) { pinDragActive = false; onPinDragCancel() }
        pinDragCandidate = false
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
                    onOpenNoteEditor(hit.id, hit.page.toInt(), hit.nx, hit.ny, hit.text, false, hit.display)
                } else {
                    onOpenNoteEditor(
                        UUID.randomUUID().toString(), loc.page, loc.nx, loc.ny, "", true, NOTE_TAP
                    )
                }
            }
            return
        }

        activePen = true
        penId = e.getPointerId(idx)
        radialActive = false
        // 上一笔的盘/环一律不带进这一笔：`endPen` 已经收过一次，但那之后仍可能有迟到的
        // `on=true` 落进来（见 overlayAllowed）。这里再收一次，代价为零。
        radial = null
        pressRing = null
        endHover()

        // 笔当橡皮用（笔尾橡皮头 / 按住侧键）：**只让这一笔走擦除**，不动全局 mode——松开笔就回到
        // 原来的工具，同 Windows Ink / Apple Pencil 的惯例。这是对用户报的「侧键切橡皮擦不灵敏」
        // 的一个推测实现：系统到底报不报这些信号，看 logStylus 的日志才能定（见那里的注释）。
        if (mode != MODE_PAGE && isEraserSignal(e)) {
            val eloc = locate(x, y, wide = true)
            if (eloc != null) {
                penMode = MODE_ERASE
                eraseHit(x, y)
                eraseBatch.add(ErasePt(eloc.nx, eloc.ny, eloc.page))
                if (eraserRing) { eraserRingAt = floatArrayOf(x, y); invalidate() }
                beginProbe(eloc.page, eloc.nx, eloc.ny, x, y)
                Log.i(TAG, "笔当橡皮用（侧键/橡皮头）→ 这一笔走擦除")
                return
            }
        }

        if (mode == MODE_PAGE) {
            // 翻页模式：笔拖动平移画面（同时起探针流，供 Mac 检测长按呼出选笔盘）
            penMode = MODE_PAGE
            penX = x; penY = y
            vx = 0f; vy = 0f; lastMoveT = e.eventTime
            val ploc = locate(x, y)
            if (ploc != null) beginProbe(ploc.page, ploc.nx, ploc.ny, x, y)
            return
        }

        if (mode == MODE_TEXT) {
            // 选字模式：笔也是用来划字的（这个模式下没有可写的东西）。与翻页模式同构——
            // 照旧起探针流，长按仍能呼出环形选笔盘换回写字，不必先切模式。
            penMode = MODE_TEXT
            penX = x; penY = y
            beginTextSelect(x, y)
            val tloc = locate(x, y)
            if (tloc != null) beginProbe(tloc.page, tloc.nx, tloc.ny, x, y)
            return
        }

        // 落墨/擦除/框选：x 放宽到页边（画板模式），并按起笔点先把软边界长够
        // （滚到页边深处再下笔的情形）。
        val loc = locate(x, y, wide = true)
        if (loc == null) { activePen = false; penId = -1; return }
        growCanvas(loc.nx)
        penMode = mode
        when (mode) {
            MODE_NOTE -> {
                drawPage = loc.page
                curPage = loc.page
                curStrokePen = strokePen(curPenPreset())
                // 尺子开关按**落笔那一刻**锁进这一笔（中途改开关不影响正在写的这笔），并随 begin 上报：
                // Mac 据此把后续 move 当「替换终点」而不是追加点，两端才都是同一条两点直线。
                lineStroke = rulerOn
                val pt = Pt3(loc.nx, loc.ny, e.getPressure(idx))
                linePress = pt.p   // 尺子笔的峰值压感起点，见 penMove 的尺子分支
                curPts.clear()
                curPts.add(pt)
                curActive = true
                invalidate()
                onInkBegin(loc.page, curStrokePen, pt, lineStroke)
            }
            MODE_ERASE -> {
                eraseHit(x, y)
                eraseBatch.add(ErasePt(loc.nx, loc.ny, loc.page))
                if (eraserRing) { eraserRingAt = floatArrayOf(x, y); invalidate() }
                beginProbe(loc.page, loc.nx, loc.ny, x, y)
            }
            MODE_LASSO -> {
                // 落笔点记下来即可：拖动形态（框选/移动/缩放）在越过死区那一刻才判定
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

    /**
     * 起探针流（擦除/翻页模式下与手势并行的那条流，唯一用途是驱动长按判定）。
     *
     * 🔴 **坐标锚在落笔那一刻的坐标系里**（[probeAt]），不是当前页面位置。
     * 翻页模式下笔拖着页面一起走 —— 页面跟着笔跑，笔相对**页面**几乎没动，于是判定方
     * （模式2 是 Mac、模式1 是 `RadialController`）看到的是一支「停着不动的笔」，
     * 拖着页面滚了半天照样满 1s 呼出盘。用户报的「动不动突然冒出进度条」就有这一份。
     */
    protected fun beginProbe(page: Int, nx: Float, ny: Float, x: Float, y: Float) {
        probing = true
        probePage = page
        probeX0 = x; probeY0 = y
        probeNx0 = nx; probeNy0 = ny
        probePw0 = max(1f, pw())
        probeH0 = max(1f, if (page in 0 until pageCount) dispH[page] else pw())
        onProbeBegin(page, nx, ny)
    }

    // 探针的冻结坐标系（落笔那一刻的页宽/页高与落点），见 [beginProbe]
    private var probeX0 = 0f
    private var probeY0 = 0f
    private var probeNx0 = 0f
    private var probeNy0 = 0f
    private var probePw0 = 1f
    private var probeH0 = 1f

    /**
     * 探针坐标：把**屏幕位移**折成落笔那一刻尺度下的页内归一化量，写进 [out]。
     *
     * 刻意**不 clamp**：判定方只拿它做差（离落笔点多远、朝哪个方向），越界值反而是对的——
     * clamp 回 0…1 等于把「拖出页面」这种最明显的移动抹平，长按闸就又瞎了。
     */
    protected fun probeAt(x: Float, y: Float, out: FloatArray) {
        out[0] = probeNx0 + (x - probeX0) / probePw0
        out[1] = probeNy0 + (y - probeY0) / probeH0
    }

    /** 写/擦出页边界时把坐标 clamp 在起笔页内（capture 同款） */
    protected fun clampToPage(x: Float, y: Float, loc: Loc?, page: Int, out: FloatArray) {
        val m = cmargin()
        out[0] = loc?.nx ?: ((x - contentLeft()) / pw()).coerceIn(-m, 1f + m)
        out[1] = if (loc != null && loc.page == page) loc.ny
        else if (page in 0 until pageCount)
            ((y - barH + scrollY - offY[page]) / max(1f, dispH[page])).coerceIn(0f, 1f)
        else 0f
    }

    protected fun penMove(e: MotionEvent, pi: Int, h: Int) {
        val historical = h >= 0
        val x = if (historical) e.getHistoricalX(pi, h) else e.getX(pi)
        val y = if (historical) e.getHistoricalY(pi, h) else e.getY(pi)
        val p = if (historical) e.getHistoricalPressure(pi, h) else e.getPressure(pi)
        val now = if (historical) e.getHistoricalEventTime(h) else e.eventTime

        if (penMode == MODE_LASSO) { handleLassoMove(x, y); return }

        val loc = locate(x, y, wide = true)   // 页边也能写/擦（画板模式）

        if (penMode == MODE_PAGE) {
            // 环形盘开着时只发探针不平移。探针用**冻结坐标系**（见 beginProbe）：这里页面正跟着
            // 笔一起走，按当前页面算的话笔相对页面根本没动，判定方会把「拖着翻页」当成长按。
            if (probing) {
                probeAt(x, y, tmp2)
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

        if (penMode == MODE_TEXT) {
            // 与翻页模式同构：盘开着时只发探针不划字（那一串点是在选扇区）
            if (probing) {
                probeAt(x, y, tmp2)
                probeBatch.add(Pt2(tmp2[0], tmp2[1]))
            }
            if (radialActive) return
            moveTextSelect(x, y)
            return
        }

        if (penMode == MODE_NOTE) {
            clampToPage(x, y, loc, drawPage, tmp2)
            growCanvas(tmp2[0])   // 写到离页边不足 slack 就本地先跳一档（模式2 的 Mac 值随后覆盖）
            var nx = tmp2[0]
            var ny = tmp2[1]
            if (lineStroke && !radialActive && curActive && curPts.isNotEmpty()) {
                // 尺子模式：以首点为锚做 45°（**视觉**角度，故传页纵横比）吸附，本地笔迹替换为
                // [首点, 吸附终点]。上行也只发这个终点——批里**只留最新一个**，否则 Mac 收到的是
                // 一串移动中的终点、追加成一条歪笔迹（begin 的 line 标记让 Mac 改为替换终点）。
                // 压感取**这一笔的峰值**而不是当前点：两点直线的线宽只由终点压感决定，而终点每帧
                // 被整个替换掉——抬笔前最后一个采样几乎没压力，整条线于是在抬笔那一刻缩成头发丝
                // （落笔起手压感还没上来，快划一条同样细）。直线本就恒宽，峰值 =「按多重画多粗」。
                // 首点一并抬到同值，两端才一致（Mac `AppModel.inkLineTo` / web 尺子分支同规则）。
                val a = curPts[0]
                PadConst.rulerSnap(a.x, a.y, nx, ny, pageAspect(drawPage), snapOut)
                nx = snapOut[0]; ny = snapOut[1]
                if (p > linePress) linePress = p
                curPts.clear()
                curPts.add(Pt3(a.x, a.y, linePress))
                curPts.add(Pt3(nx, ny, linePress))
                inkBatch.clear()
                inkBatch.add(Pt3(nx, ny, linePress))
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
            probeAt(x, y, tmp2)
            probeBatch.add(Pt2(tmp2[0], tmp2[1]))
        }
    }

    protected fun endPen() {
        when (penMode) {
            MODE_NOTE -> {
                if (radialActive) clearCur()
                flushBatch()
                onInkEnd()
                // 真源（模式2=Mac，模式1=本机库）回推之前，这一笔先以乐观笔迹的身份留在 strokes 里，
                // 别指望活体层那一个槽撑到回推——下一笔落笔就把它清了（见 pendingInk）
                commitCurOptimistically()
            }
            MODE_ERASE -> {
                if (radialActive) { inkBatch.clear(); eraseBatch.clear() } else flushBatch()
                erasingGesture = false
                onEraseEnd()
                lastEraseRel = sentRelSeq()
            }
            MODE_PAGE -> if (!radialActive) startMomentum()   // 环形盘选择不甩动
            MODE_LASSO -> finishLasso()
            MODE_TEXT -> {
                // 笔抬起：选区已在 penMove 里算好；盘开着说明这一下是去选笔的，把它撤掉
                if (radialActive) clearTextSelection()
                endTextSelect()
            }
        }
        if (probing) {   // 收尾探针流，Mac 据此提交/取消环形盘
            if (probeBatch.isNotEmpty()) {
                onProbeMove(ArrayList(probeBatch))
                probeBatch.clear()
            }
            onProbeEnd()
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
    protected var hoverPending = false
    protected var hoverLatest: FloatArray? = null   // [page, nx, ny]
    protected var hoverOn = false

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        // 侧键在**悬停**时按下走的是这条路（ACTION_BUTTON_PRESS / HOVER_MOVE），不是 onTouchEvent
        logStylus(e, "generic")
        penNear.onHover(e)
        // 手指手势进行中笔进了悬停范围：那几根"手指"多半是手侧面（防误触，见 PenProximity）
        if (e.actionMasked != MotionEvent.ACTION_HOVER_EXIT && touches.isNotEmpty() && penNear.near()) {
            rejectFingerGesture("笔靠近")
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                // 笔悬停在笔记标记上 → `hover` 模式那条笔记展开正文（**只认笔**：手指没有悬停这回事，
                // 触摸走点击降级，见 tapNote）。与上报给 Mac 的 hover 光标彼此独立，任何模式下都生效。
                if (!activePen) hoverNote(e.getX(0), e.getY(0))
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

    protected fun reportHover(page: Int, nx: Float, ny: Float) {
        hoverOn = true
        hoverLatest = floatArrayOf(page.toFloat(), nx, ny)
        if (hoverPending) return
        hoverPending = true
        handler.postDelayed({
            hoverPending = false
            hoverLatest?.let { onHoverMove(it[0].toInt(), it[1], it[2]) }
            hoverLatest = null
        }, 16)
    }

    protected fun endHover() {
        if (noteHoverId != null) { noteHoverId = null; invalidate() }   // 笔离开 = 悬浮气泡收起
        if (!hoverOn && eraserRingAt == null) return
        hoverOn = false
        if (eraserRingAt != null) { eraserRingAt = null; invalidate() }
        onHoverEnd()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
