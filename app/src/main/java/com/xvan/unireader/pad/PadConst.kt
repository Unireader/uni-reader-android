package com.xvan.unireader.pad

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 与 `web/src/lib/shared.ts` 一一对应的常量与公式——**这两份必须同步改**
 * （笔触公式还牵连 Mac 端 `Sources/App/PenPreset.swift`，环形盘几何牵连 `RadialMenuView.swift`）。
 *
 * 单位约定：长度类常量的单位是 **dp**，用时乘 density 折成物理像素。
 * 网页那边这些数是 CSS px，而 dp ≈ CSS px，两端观感才对得上；更要紧的是环形盘：
 * Mac 判「中心取消区」用的是 `RD.hub` 这个数配合平板上报的 `padGeom.pageW` 换算，
 * 所以**上报的页宽也必须是 dp**，否则 Mac 算出的取消区和平板画出来的 hub 不是同一个圈。
 */
object PadConst {

    const val GAP = 8f          // 页间距（dp）
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 5f
    const val PALM = 60f        // 手掌接触阈值（dp，对 getTouchMajor）
    const val DEAD = 8f         // 单指平移死区（dp）
    const val LASSO_DEAD = 2f   // 框选手势最小拖动距离（dp），同 Mac `DragGesture(minimumDistance: 2)`

    /** 工具模式标签，下标 = WireCodec.MODE_*（0 笔记 / 1 擦除 / 2 翻页 / 3 框选） */
    val MODE_LABELS = listOf("笔记", "擦除", "翻页", "框选")

    val BRUSH_LABELS = mapOf(
        "ballpoint" to "圆珠笔",
        "fountain" to "钢笔",
        "marker" to "马克笔",
        "pencil" to "铅笔",
    )

    fun brushLabel(t: String): String = BRUSH_LABELS[t] ?: "圆珠笔"

    /** 环形选笔盘几何（dp）：逐个对齐 Mac 端 `RadialLayout`；wedgeDim/hubDim 越小越透 */
    object RD {
        const val HUB = 46f
        const val INNER = 54f
        const val OUTER = 134f
        const val GAP_DEG = 1.5f
        const val WEDGE_DIM = 0.16f
        const val HUB_DIM = 0.22f
    }

    /** 长按进度环（环形盘的前置动画）：300ms 起显示、700ms 填满、直径 30dp 线宽 3dp、正上方顺时针 */
    object PR {
        const val D = 30f
        const val LW = 3f
        const val DELAY_MS = 300f
        const val FILL_MS = 700f
    }

    /** 文字笔记标记的命中半径（页内归一化，同 capture 的 0.03） */
    const val NOTE_HIT = 0.03f

    // ---- 笔触类型：跟 Mac 端 PenBrushType.strokeWidth/opacityMultiplier 同一套公式 ----

    fun strokeWidthFor(t: String, p: Float, w: Float): Float = when (t) {
        "fountain" -> 0.3f + p.pow(1.6f) * w * 1.3f   // 系数 1.3，与 Mac/JS 一致（曾误写 1.15）
        "marker" -> w
        "pencil" -> 0.5f + p * w * 0.85f
        else -> 0.6f + p * w   // ballpoint / 未知兜底
    }

    fun opacityMultFor(t: String): Float = if (t == "pencil") 0.85f else 1f

    /**
     * 尺子吸附（Mac `InkEdit.rulerSnap` / JS `rulerSnap` 的同款实现）：
     * (ax,ay)→(x,y) 的角度距最近的 45° 倍数 ≤ thresholdDeg 时贴合到该倍数（保长度），否则原样。
     * `aspect` = 页高/页宽（显示比例）：归一化空间里 x/y 尺度不同，先把 y 折算成与 x 同尺度再量角、
     * 贴合完再折回去，吸附的才是**看上去**的 0/45/90°。结果写进 out[0]/out[1]（避免每点分配）。
     */
    fun rulerSnap(
        ax: Float, ay: Float, x: Float, y: Float, aspect: Float, out: FloatArray,
        thresholdDeg: Float = 7f,
    ) {
        val a = if (aspect > 0f) aspect else 1f
        val dx = x - ax
        val dy = (y - ay) * a
        val len = hypot(dx, dy)
        if (len == 0f) { out[0] = x; out[1] = y; return }
        val step = PI / 4.0   // 45°
        val ang = atan2(dy.toDouble(), dx.toDouble())
        val snapped = (ang / step).roundToInt() * step
        if (abs(ang - snapped) > thresholdDeg * PI / 180.0) { out[0] = x; out[1] = y; return }
        out[0] = ax + len * cos(snapped).toFloat()
        out[1] = ay + len * sin(snapped).toFloat() / a
    }
}

/** 页内归一化坐标 → 视口像素的映射（PadView 实现，绘制模块据此画在正确位置）。 */
interface PageMapper {
    fun viewX(page: Int, nx: Float): Float
    fun viewY(page: Int, ny: Float): Float
}
