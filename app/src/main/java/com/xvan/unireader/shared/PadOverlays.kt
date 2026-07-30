package com.xvan.unireader.shared

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 叠层绘制：文字笔记标记 / 橡皮尺寸圆环 / 框选移动高亮 / 环形选笔盘 / 长按进度环。
 * 全部逐分支移植自 `web/src/lib/render.ts` 的对应函数。
 *
 * 环形盘与进度环的**判定全部在 Mac**（长按检测、扇区命中、选中提交），这里只照着下发状态画，
 * 不做任何判定；几何常量必须与 `PadConst.RD`/`PR`（进而与 Mac 的 `RadialLayout`）一致。
 */
class PadOverlays(private val density: Float) {

    private fun dp(v: Float) = v * density

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rect = RectF()
    private val rect2 = RectF()   // 扇区内圈（onDraw 里不新建对象）
    // 图标画在扇区循环内部，必须用独立的 RectF——共用会把外圈那个 rect 冲掉，后续扇区的弧就画歪了
    private val rectIcon = RectF()

    /** 文字居中的基线偏移（Canvas 的 y 是基线，网页 textBaseline:middle 是视觉中线） */
    private fun centerBaseline(): Float {
        val fm = textPaint.fontMetrics
        return -(fm.ascent + fm.descent) / 2f
    }

    // ---------- 文字笔记标记 ----------

    /**
     * 圆形底片 + 首字符（形制呼应环形盘图标）。配色日间/夜间通用——夜间只反转页图，
     * 蓝底白边在深浅页面上都可读。
     */
    fun drawNoteMarker(c: Canvas, x: Float, y: Float, r: Float, text: String) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(235, 31, 111, 235)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = Color.argb(217, 255, 255, 255)
        c.drawCircle(x, y, r, p)
        textPaint.color = Color.WHITE
        textPaint.textSize = r * 0.9f
        textPaint.isFakeBoldText = true
        c.drawText(text.ifEmpty { "T" }.substring(0, 1), x, y + centerBaseline(), textPaint)
        textPaint.isFakeBoldText = false
    }

    // ---------- 文字铺色（高亮 kind=3 / 选区注解 kind=0 的底色） ----------

    /**
     * 铺一行选区色。**画在页图之上、墨迹之下**，与 Mac `PageCellView` 的层序一致——
     * 反过来的话荧光色会盖住自己写的笔记。圆角 2dp 同 Mac 的 `fillNorm`。
     */
    fun drawTextFill(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        rect.set(min(x0, x1), min(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
        p.style = Paint.Style.FILL
        p.color = color
        val r = dp(2f)
        c.drawRoundRect(rect, r, r, p)
    }

    // ---------- 橡皮尺寸圆环 ----------

    /** 双描边（外暗内亮）保证在白页/夜间反转页上都可读；半径 = eraserSize × 当前页显示宽 */
    fun drawEraserRing(c: Canvas, x: Float, y: Float, r: Float) {
        p.style = Paint.Style.STROKE
        p.color = Color.argb(128, 0, 0, 0)
        p.strokeWidth = dp(3f)
        c.drawCircle(x, y, r, p)
        p.color = Color.argb(230, 255, 255, 255)
        p.strokeWidth = dp(1.5f)
        c.drawCircle(x, y, r, p)
    }

    // ---------- 框选移动 ----------

    private val dash = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    private val dashSel = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)

    /** 进行中的框选虚线矩形 */
    fun drawLassoBox(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        rect.set(min(x0, x1), min(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
        p.style = Paint.Style.FILL
        p.color = Color.argb(15, 31, 111, 235)
        c.drawRect(rect, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = Color.argb(230, 31, 111, 235)
        p.pathEffect = dash
        c.drawRect(rect, p)
        p.pathEffect = null
    }

    /** 选中集高亮框（带 6dp 内边距、圆角） */
    fun drawLassoSelection(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val pad = dp(6f)
        val l = min(x0, x1) - pad
        val t = min(y0, y1) - pad
        val w = maxOf(kotlin.math.abs(x1 - x0) + pad * 2, dp(16f))
        val h = maxOf(kotlin.math.abs(y1 - y0) + pad * 2, dp(16f))
        rect.set(l, t, l + w, t + h)
        val r = dp(4f)
        p.style = Paint.Style.FILL
        p.color = Color.argb(20, 31, 111, 235)
        c.drawRoundRect(rect, r, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = Color.argb(230, 31, 111, 235)
        p.pathEffect = dashSel
        c.drawRoundRect(rect, r, r, p)
        p.pathEffect = null
    }

    // ---------- 长按进度环 ----------

    /** 环展开成盘，两者互斥：盘开着时调用方不要画环 */
    fun drawPressRing(c: Canvas, x: Float, y: Float, elapsedMs: Float) {
        val prog = ((elapsedMs - PadConst.PR.DELAY_MS) / PadConst.PR.FILL_MS).coerceIn(0f, 1f)
        if (prog <= 0.001f) return   // 300ms 前不显示：轻点/快速书写不该闪一下环
        val r = dp(PadConst.PR.D) / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(PadConst.PR.LW)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(64, 255, 255, 255)          // 轨道
        c.drawCircle(x, y, r, p)
        p.color = Color.argb(255, 88, 166, 255)          // 进度（从正上方顺时针）
        rect.set(x - r, y - r, x + r, y + r)
        c.drawArc(rect, -90f, prog * 360f, false, p)
        p.strokeCap = Paint.Cap.BUTT
    }

    // ---------- 环形选笔盘 ----------

    private val toolLabel = mapOf(RK_ERASE to "橡皮", RK_PAGE to "翻页")

    /** 选中扇区的填充色：笔用自身颜色但**丢掉透明度**（荧光笔 alpha 很低，照抄会看不见高亮） */
    private fun tintOf(item: RadialItem): Int = when (item.kind) {
        RK_ERASE -> Color.argb(235, 245, 140, 51)
        RK_PAGE -> Color.argb(235, 64, 184, 179)
        else -> Color.argb(235, item.pen.r, item.pen.g, item.pen.b)
    }

    private fun penColor(pen: Pen) =
        Color.argb((pen.a * 255f).roundToInt().coerceIn(0, 255), pen.r, pen.g, pen.b)

    private fun contrastOn(pen: Pen): Int =
        if ((0.299f * pen.r + 0.587f * pen.g + 0.114f * pen.b) / 255f > 0.62f) Color.BLACK else Color.WHITE

    /** 画整个盘（盘心在 cx/cy）。items 为空 = 只有盘底，不画扇区。 */
    fun drawRadial(c: Canvas, cx: Float, cy: Float, highlight: Int, items: List<RadialItem>) {
        if (items.isEmpty()) return
        val outer = dp(PadConst.RD.OUTER)
        val inner = dp(PadConst.RD.INNER)
        val hub = dp(PadConst.RD.HUB)

        // 盘底（网页是毛玻璃 div，这里退化成一层平涂半透明底 + 细亮边——不做渐变/高光）
        p.style = Paint.Style.FILL
        p.color = Color.argb(150, 13, 17, 23)
        c.drawCircle(cx, cy, outer, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1f)
        p.color = Color.argb(46, 255, 255, 255)
        c.drawCircle(cx, cy, outer, p)

        val n = items.size
        val step = 360f / n
        val gap = min(PadConst.RD.GAP_DEG, step / 4f)
        rect.set(cx - outer, cy - outer, cx + outer, cy + outer)
        rect2.set(cx - inner, cy - inner, cx + inner, cy + inner)

        // 扇区：整圆均分，第 0 项中心在正上方（12 点）顺时针；选中整块亮起
        for (i in 0 until n) {
            val on = highlight == i
            val a0 = i * step - step / 2f + gap - 90f
            val sweep = step - gap * 2f
            path.reset()
            path.arcTo(rect, a0, sweep)
            path.arcTo(rect2, a0 + sweep, -sweep)
            path.close()
            p.style = Paint.Style.FILL
            p.color = if (on) tintOf(items[i])
            else Color.argb((PadConst.RD.WEDGE_DIM * 255).roundToInt(), 0, 0, 0)
            c.drawPath(path, p)
            if (on) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1.5f)
                p.color = Color.argb(166, 255, 255, 255)
                c.drawPath(path, p)
            }
            val ang = ((i * step - 90f) * Math.PI / 180.0)
            val ir = (inner + outer) / 2f
            drawIcon(c, cx + ir * cos(ang).toFloat(), cy + ir * sin(ang).toFloat(), items[i], on)
        }
        drawHub(c, cx, cy, hub, items.getOrNull(highlight))
    }

    /** 中心 hub：既是取消区，也回显当前指向项（下发的 items 没有笔名，故显示笔型 + 粗细） */
    private fun drawHub(c: Canvas, cx: Float, cy: Float, hub: Float, sel: RadialItem?) {
        p.style = Paint.Style.FILL
        p.color = Color.argb(
            ((if (sel != null) PadConst.RD.HUB_DIM else PadConst.RD.HUB_DIM + 0.06f) * 255).roundToInt(),
            0, 0, 0,
        )
        c.drawCircle(cx, cy, hub, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (sel != null) dp(1f) else dp(2f)
        p.color = if (sel != null) Color.argb(51, 255, 255, 255) else Color.argb(140, 255, 255, 255)
        c.drawCircle(cx, cy, hub, p)

        val title = when {
            sel == null -> "取消"
            sel.kind == RK_PEN -> PadConst.brushLabel(brushName(sel.pen.brush))
            else -> toolLabel[sel.kind] ?: ""
        }
        val sub = if (sel != null && sel.kind == RK_PEN)
            "${(sel.pen.w * 100).roundToInt() / 100f}pt" else ""

        // hub 底很淡，文字靠一层阴影保可读（功能性描边，不是装饰）
        textPaint.setShadowLayer(dp(3f), 0f, dp(1f), Color.argb(140, 0, 0, 0))
        textPaint.color = if (sel != null) Color.WHITE else Color.argb(217, 255, 255, 255)
        textPaint.textSize = dp(13f)
        textPaint.isFakeBoldText = true
        c.drawText(title, cx, (if (sub.isNotEmpty()) cy - dp(7f) else cy) + centerBaseline(), textPaint)
        textPaint.isFakeBoldText = false
        if (sub.isNotEmpty()) {
            textPaint.color = Color.argb(179, 255, 255, 255)
            textPaint.textSize = dp(10f)
            c.drawText(sub, cx, cy + dp(8f) + centerBaseline(), textPaint)
        }
        textPaint.clearShadowLayer()
    }

    /**
     * 扇区图标统一形制：一枚彩色圆片 + 符号（跟 Mac 端 `disc` 对齐）。
     * 盘底透着页面内容，裸符号会被白页吞掉，所以每个图标都自带底片。
     */
    private fun drawIcon(c: Canvas, x: Float, y: Float, item: RadialItem, on: Boolean) {
        val r = dp(if (on) 17f else 14f)
        val isPen = item.kind == RK_PEN
        p.style = Paint.Style.FILL
        p.color = if (isPen) penColor(item.pen) else tintOf(item)
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (on) dp(2f) else dp(1f)
        p.color = Color.argb(if (on) 230 else 140, 255, 255, 255)
        c.drawCircle(x, y, r, p)

        c.save()
        c.translate(x, y)
        p.style = Paint.Style.FILL
        p.color = if (isPen) contrastOn(item.pen) else Color.WHITE
        when {
            isPen -> {   // 笔尖剪影（笔杆 + 右端尖角），斜 45°
                c.rotate(-45f)
                val s = r * 0.62f
                val w = r * 0.26f
                path.reset()
                path.moveTo(-s, -w); path.lineTo(s * 0.25f, -w); path.lineTo(s, 0f)
                path.lineTo(s * 0.25f, w); path.lineTo(-s, w)
                path.close()
                c.drawPath(path, p)
            }
            item.kind == RK_ERASE -> {
                c.rotate(-36f)
                val k = r / dp(17f)
                rectIcon.set(-9f * k, -5.5f * k, 9f * k, 5.5f * k)
                c.drawRoundRect(rectIcon, 2.5f * k, 2.5f * k, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.2f * k
                p.color = Color.argb(77, 0, 0, 0)
                c.drawLine(-2f * k, -5.5f * k, -2f * k, 5.5f * k, p)
                p.style = Paint.Style.FILL
            }
            else -> {   // page：举起的手（掌 + 四指），对应 Mac 的 hand.raised.fill
                val k = r / dp(17f)
                rectIcon.set(-6.5f * k, -1f * k, 6.5f * k, 8.5f * k)
                c.drawRoundRect(rectIcon, 3f * k, 3f * k, p)
                for (f in 0 until 4) {
                    val fx = (-6f + f * 3.2f) * k
                    rectIcon.set(fx, -8.5f * k, fx + 2.4f * k, 0f)
                    c.drawRoundRect(rectIcon, 1.2f * k, 1.2f * k, p)
                }
            }
        }
        c.restore()
    }
}
