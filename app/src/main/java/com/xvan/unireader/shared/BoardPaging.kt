package com.xvan.unireader.shared

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 分页画板（v17，`../BOARD-NOTE-PLAN.md §9`）的**三端契约**：布局、背景模板编码、模板几何、页面尺寸预设。
 * 逐条对应 Mac `Sources/App/BoardModel.swift` 的 `BoardLayout` / `BoardTemplate` / `BoardTemplateGeometry` /
 * `BoardPageSize`——改一个数三端一起改（网页 `web/src` 同款），否则同一本画板两端画出来的页和线不在一个位置。
 *
 * 纯 Kotlin、不认识 `WireCodec` 也不认识 SQLite：模式1（`local/BoardController`）与模式2（`pad/PadScratch`）
 * 各自把「页」喂给 `ScratchCanvas.setPages`，画法与视口行为只有这一份。
 */
object BoardPaging {

    /** 页间距（画布点） */
    const val GAP = 24f

    // ---- 背景模板（线上 u8；只许尾部追加，未知值按空白画） ----
    const val T_BLANK = 0
    const val T_LINED = 1
    const val T_GRID = 2
    const val T_DOTS = 3
    const val T_CORNELL = 4
    const val T_TWO_COLUMN = 5

    /** 库里 `board_page.template` 的字符串（下标 = u8 编码），与 Mac `BoardTemplate` 的 rawValue 逐字相同 */
    val TEMPLATE_RAW = listOf("blank", "lined", "grid", "dots", "cornell", "twoColumn")

    fun templateCode(raw: String): Int = TEMPLATE_RAW.indexOf(raw).let { if (it < 0) T_BLANK else it }
    fun templateRaw(code: Int): String = TEMPLATE_RAW.getOrElse(code) { TEMPLATE_RAW[T_BLANK] }

    /**
     * 布局契约（`§9.2`）：页竖排、水平居中于画布 x = 0，第 i 页（0 起）= `(-W/2, i × (H + 24), W, H)`。
     * 运行时 / 线上一律用画布坐标，落库时换成页内坐标（减去该页左上角）。
     */
    class Layout(val width: Float, val height: Float, val count: Int) {
        val stride: Float get() = height + GAP
        fun originX(): Float = -width / 2f
        fun originY(i: Int): Float = i * stride

        /** 全部页的包围盒 `[x, y, w, h]`（没有页 → null） */
        fun bounds(): FloatArray? =
            if (count > 0) floatArrayOf(-width / 2f, 0f, width, count * stride - GAP) else null

        /** 画布 y 落在哪一页：页间空隙归上面那页，首页之上 / 末页之下夹到首 / 末页（同 Mac `index(forY:)`） */
        fun indexForY(y: Float): Int {
            if (count <= 0) return 0
            return min(max(0, floor(y / stride).toInt()), count - 1)
        }
    }

    /**
     * 模板几何（`§9.3`）：全部是**页内画布点**、与页面大小无关的固定间距。
     * [thin]/[bold] = 线段数组（每 4 个数一段：x0,y0,x1,y1），[dots] = 点中心（每 2 个数一个点）。
     * 渲染方只管映到屏幕：细线 α0.14 宽 1、粗线 α0.30 宽 1.5、点 α0.30 径 2（画布点，随缩放）。
     */
    class Shape(val thin: FloatArray, val bold: FloatArray, val dots: FloatArray)

    const val LINE_GAP = 28f
    const val GRID_STEP = 20f
    const val DOT_SIZE = 2f

    /** 与 Mac `BoardTemplateGeometry.shape` 逐行对应（含 `rounded()` 的取整口径：四舍五入、.5 远离零） */
    fun shape(template: Int, w: Float, h: Float): Shape {
        val thin = ArrayList<Float>()
        val bold = ArrayList<Float>()
        val dots = ArrayList<Float>()
        fun seg(out: ArrayList<Float>, x0: Float, y0: Float, x1: Float, y1: Float) {
            out.add(x0); out.add(y0); out.add(x1); out.add(y1)
        }
        fun hLines(from: Float, to: Float, x0: Float, x1: Float) {
            var y = from
            while (y <= to + 0.001f) {
                seg(thin, x0, y, x1, y)
                y += LINE_GAP
            }
        }
        // Swift 的 `rounded()` = 四舍五入、.5 远离零；Kotlin 的 roundToInt 在正数上与它一致（这里全是正数）。
        // 乘法按 Double 算（同 Mac），免得 Float 误差在 .5 附近把一条粗线挪一个点
        fun rnd(v: Double): Float = v.roundToInt().toFloat()
        val wd = w.toDouble()
        val hd = h.toDouble()
        when (template) {
            T_LINED -> hLines(72f, h - 36f, 36f, w - 36f)
            T_GRID -> {
                var x = GRID_STEP
                while (x < w - 0.001f) { seg(thin, x, 0f, x, h); x += GRID_STEP }
                var y = GRID_STEP
                while (y < h - 0.001f) { seg(thin, 0f, y, w, y); y += GRID_STEP }
            }
            T_DOTS -> {
                var y = GRID_STEP
                while (y < h - 0.001f) {
                    var x = GRID_STEP
                    while (x < w - 0.001f) { dots.add(x); dots.add(y); x += GRID_STEP }
                    y += GRID_STEP
                }
            }
            T_CORNELL -> {
                val y1 = rnd(hd * 0.12)
                val y2 = rnd(hd * 0.80)
                val cx = rnd(wd * 0.30)
                seg(bold, 0f, y1, w, y1)
                seg(bold, 0f, y2, w, y2)
                seg(bold, cx, y1, cx, y2)
                hLines(y1 + LINE_GAP, y2 - 8f, 0f, w)
            }
            T_TWO_COLUMN -> {
                val mid = rnd(wd / 2.0)
                seg(bold, mid, 48f, mid, h - 48f)
                hLines(72f, h - 36f, 36f, mid - 12f)
                hLines(72f, h - 36f, mid + 12f, w - 36f)
            }
            else -> Unit   // blank / 未知值：空白
        }
        return Shape(thin.toFloatArray(), bold.toFloatArray(), dots.toFloatArray())
    }

    // ---- 页面尺寸预设（画布点，竖版；横版宽高对调） ----
    const val SIZE_A4 = 0
    const val SIZE_A5 = 1
    const val SIZE_LETTER = 2
    const val SIZE_SCREEN = 3

    /**
     * 竖版尺寸 `[w, h]`。[screenW]/[screenH] = 创建那台设备的屏幕逻辑尺寸（平板取 dp），
     * 「当前屏幕」取短边为宽、长边为高并取整（同 Mac `BoardPageSize.portrait(screen:)`）。
     */
    fun portraitSize(size: Int, screenW: Float, screenH: Float): FloatArray = when (size) {
        SIZE_A5 -> floatArrayOf(420f, 595f)
        SIZE_LETTER -> floatArrayOf(612f, 792f)
        SIZE_SCREEN -> {
            val w = max(1f, min(screenW, screenH))
            val h = max(1f, max(screenW, screenH))
            floatArrayOf(w.roundToInt().toFloat(), h.roundToInt().toFloat())
        }
        else -> floatArrayOf(595f, 842f)
    }

    /** 预设 + 横竖 → `[w, h]` */
    fun pageSize(size: Int, landscape: Boolean, screenW: Float, screenH: Float): FloatArray {
        val p = portraitSize(size, screenW, screenH)
        return if (landscape) floatArrayOf(p[1], p[0]) else p
    }

    /** 初始页数上下限（`§9.4`：1~100） */
    const val MIN_PAGES = 1
    const val MAX_PAGES = 100
}
