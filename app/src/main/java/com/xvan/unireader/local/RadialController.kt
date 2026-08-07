package com.xvan.unireader.local

import android.util.Log
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.RK_ERASE
import com.xvan.unireader.shared.RK_PAGE
import com.xvan.unireader.shared.RK_PEN
import com.xvan.unireader.shared.RK_SCRATCH
import com.xvan.unireader.shared.RK_TEXT
import com.xvan.unireader.shared.RadialItem
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 长按 → 进度环 → 环形选笔盘的**判定方**（M6），从 Mac 端 `AppModel` 那段搬过来的一份 Kotlin 版。
 *
 * 模式2 里这段跑在 Mac：平板只管把笔位发上去（ink / probe 两条流），Mac 判长按、判扇区、提交，
 * 再把盘的状态镜像下发让平板照着画。模式1 没有 Mac，判定就得自己做——但**画盘的那一份代码
 * 一行没动**（`PadOverlays.drawRadial`/`drawPressRing` 仍由基类调），两模式看到的是同一个盘。
 *
 * 与 Mac 逐条对齐的地方（改一边必须同步另一边，否则「同一个动作在两端呼出不同的东西」）：
 * - 落笔即起 [PadConst.LP.HOLD_MS] 定时，期间位移超 [PadConst.LP.MOVE_CANCEL] dp 即判为「在画」，撤销候选；
 * - 呼出瞬间丢弃正在成形的这一笔（Mac 广播 `inkCancel`，这里直接调 [PageCanvasView.onInkCancel]）；
 * - 选择**只看角度**：整圆均分、0 号扇区在正上方顺时针；半径只用来判「有没有离开中心取消区」
 *   （半径分层要求精确控制笔离中心的距离，而那个距离随缩放漂移，是旧版「很难选中」的根因）；
 * - 抬笔提交：取消区（highlight=-1）＝什么都不做；选笔顺带切回笔记模式（同 `applyPenSelection`）。
 *
 * 距离一律先把归一化 y 乘页面纵横比折成与 x 同尺度再量（[PageCanvasView.pageAspect]），
 * 再按**平板屏幕 dp** 判阈值——角度天生与缩放无关，距离则必须换算，否则缩放一变手感就飘。
 */
class RadialController(private val view: PageCanvasView) {

    companion object {
        const val TAG = "UniReader/Radial"

        /** 工具扇区不吃 pen 字段，填占位值即可（同线格式的定长约定，`PROTOCOL.md §4.2`） */
        private val TOOL_PEN = Pen(0, 0, 0, 1f, 0f, 0)
    }

    /** 盘是否开着。开着时笔的移动是在选扇区，不该再入笔/擦除（基类的 `radialActive` 同义） */
    var active = false
        private set

    /**
     * 「新建草稿纸」扇区的出口（ReaderActivity 接到 `ScratchController.createAt`）。
     * 参数 = 盘心（page, cx, cy），即长按那一处——等价顶栏「在当前位置新建」，只是锚点用盘心。
     */
    var onScratchAdd: ((page: Int, nx: Float, ny: Float) -> Unit)? = null

    /** 「新建文字笔记」扇区的出口（ReaderActivity 接到现有文字注解新建路径）；参数同上是盘心 */
    var onTextNoteAdd: ((page: Int, nx: Float, ny: Float) -> Unit)? = null

    private var page = 0
    private var cx = 0f
    private var cy = 0f
    private var watching = false      // 长按候选中（已落笔、还没超时也没判为在画）
    private var movedFar = false
    private var highlight = -1
    private var items = listOf<RadialItem>()

    private val fire = Runnable { fireLongPress() }

    /**
     * 落笔：起长按候选 + 挂进度环。笔记模式由 ink begin 触发，擦除/翻页模式由 probe begin 触发
     * ——与模式2 上行的两条流一一对应（`PadView.onInkBegin`/`onProbeBegin`）。
     */
    fun begin(page: Int, nx: Float, ny: Float) {
        cancel()
        this.page = page
        cx = nx
        cy = ny
        watching = true
        movedFar = false
        view.setPressRing(true, page, nx, ny)
        view.postDelayed(fire, PadConst.LP.HOLD_MS)
    }

    /** 笔移：盘开着 = 选扇区；否则看是不是已经在画了（在画就撤销长按候选） */
    fun move(nx: Float, ny: Float) {
        if (active) {
            updateHighlight(nx, ny)
            return
        }
        if (!watching || movedFar) return
        if (exceeds(dist(nx, ny), PadConst.LP.MOVE_CANCEL, PadConst.LP.MOVE_CANCEL_NORM)) {
            movedFar = true
            view.removeCallbacks(fire)
            view.setPressRing(false, page, cx, cy)
        }
    }

    /**
     * 抬笔。**返回 true = 这一笔被盘吃掉了**（调用方不要落库：它是一次选择，不是一条笔迹）。
     * 取消区（highlight=-1）同样返回 true——用户的意思是「算了」，而不是「画一个点」。
     */
    fun end(): Boolean {
        view.removeCallbacks(fire)
        if (watching) view.setPressRing(false, page, cx, cy)
        watching = false
        if (!active) return false
        // 选中的下标**必须先取出来**：close() 会把 highlight 复位成 -1，之后再拿它当笔下标就是 -1
        // （表现为「选了第 2 支笔却没换笔」，而日志里明明写着选中了——第一版就是这么错的）
        val index = highlight
        val sel = items.getOrNull(index)
        Log.i(TAG, "选笔盘提交 → ${describe(index, sel)}")
        close()
        if (sel != null) commit(index, sel)
        return true
    }

    /** 换文档/界面销毁等外部打断：定时器与盘/环一起收掉，别留一个盘挡着视线 */
    fun cancel() {
        view.removeCallbacks(fire)
        if (watching) view.setPressRing(false, page, cx, cy)
        watching = false
        movedFar = false
        if (active) close()
    }

    // ---------- 判定 ----------

    /** 长按达成：撤掉本地正在写的半笔，把环展开成盘（两者互斥，同 Mac `fireLongPress`） */
    private fun fireLongPress() {
        if (!watching || movedFar || active) return
        val pens = view.penList()
        if (pens.isEmpty()) return
        items = pens.map { RadialItem(RK_PEN, it) } +
            RadialItem(RK_ERASE, TOOL_PEN) + RadialItem(RK_PAGE, TOOL_PEN) +
            RadialItem(RK_SCRATCH, TOOL_PEN) + RadialItem(RK_TEXT, TOOL_PEN)
        active = true
        highlight = -1
        view.setPressRing(false, page, cx, cy)
        view.onInkCancel()
        view.setRadial(true, page, cx, cy, highlight, items)
        Log.i(TAG, "长按呼出选笔盘 page=$page 中心=(${"%.3f".format(cx)},${"%.3f".format(cy)}) 扇区=${items.size}")
    }

    /**
     * 盘开着时按笔位定扇区：离开中心取消区之后**只看角度**。
     * 角度从正上方起顺时针（页坐标 y 向下，故取 -dy），四舍五入到最近的扇区中心。
     */
    private fun updateHighlight(nx: Float, ny: Float) {
        val n = items.size
        if (n == 0) return
        val h = if (!exceeds(dist(nx, ny), PadConst.RD.HUB, PadConst.LP.DEADZONE_NORM)) {
            -1
        } else {
            var ang = atan2(nx - cx, -(ny - cy) * view.pageAspect(page)).toDouble()
            if (ang < 0) ang += 2 * Math.PI
            (ang / (2 * Math.PI) * n).roundToInt() % n
        }
        if (h == highlight) return   // 同 Mac 的 Equatable 去重：跨扇区才重画
        highlight = h
        view.setRadial(true, page, cx, cy, h, items)
    }

    private fun commit(index: Int, item: RadialItem) {
        when (item.kind) {
            RK_PEN -> {
                // 扇区表是「N 支笔在前，橡皮、翻页收尾」，所以笔扇区的下标就是笔的下标
                view.setPenIndex(index)
                view.setMode(MODE_NOTE)   // 选了支笔就是要用它画（同 Mac applyPenSelection）
            }
            RK_ERASE -> view.setMode(MODE_ERASE)
            RK_PAGE -> view.setMode(MODE_PAGE)
            // 盘心 (page, cx, cy) 就是锚点；纸开着时呼不出盘（覆盖层吃掉了指针事件），不用管纸上扇区
            RK_SCRATCH -> onScratchAdd?.invoke(page, cx, cy)
            RK_TEXT -> onTextNoteAdd?.invoke(page, cx, cy)
        }
    }

    private fun close() {
        active = false
        highlight = -1
        view.setRadial(false, page, cx, cy, -1, emptyList())
    }

    /** 笔尖到盘心的归一化距离（y 已折算成与 x 同尺度，量出来才是看上去的距离） */
    private fun dist(nx: Float, ny: Float): Float =
        hypot(nx - cx, (ny - cy) * view.pageAspect(page))

    /**
     * 归一化距离是否超过给定的**平板屏幕**阈值（dp）。页宽已知就按 dp 判（所见即所得，与画出来的
     * 盘同尺度），未知（还没布局）时退回归一化阈值——同 Mac 的 `exceedsPad`。
     */
    private fun exceeds(normDist: Float, dp: Float, norm: Float): Boolean {
        val w = view.contentWidthDp()
        return if (w > 0f) normDist * w > dp else normDist > norm
    }

    private fun describe(index: Int, item: RadialItem?): String = when {
        item == null -> "取消区（不选）"
        item.kind == RK_ERASE -> "橡皮"
        item.kind == RK_PAGE -> "翻页"
        item.kind == RK_SCRATCH -> "新建草稿纸"
        item.kind == RK_TEXT -> "新建文字笔记"
        else -> "第 ${index + 1} 支笔"
    }
}
