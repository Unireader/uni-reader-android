package com.xvan.unireader.shared

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

    /**
     * 工具模式标签，下标 = MODE_*（0 笔记 / 1 擦除 / 2 翻页 / 3 框选）。
     *
     * 🔴 **这张表就是线上契约**（`../PROTOCOL.md §4.1` 的 `mode` u8）。**不许往这里加**——
     * 加一档，模式2 的模式键就会循环到一个 Mac 不认识的值发过去。模式1 本机多出来的档
     * 放 [LOCAL_MODE_LABELS]。
     */
    val MODE_LABELS = listOf("笔记", "擦除", "翻页", "框选")

    /**
     * 模式1（独立版）的模式表：线上那四档 + 本机专属的「选字」（划字高亮/划字笔记，[MODE_TEXT]）。
     * 模式2 用 [MODE_LABELS]，循环时碰不到第五档。
     */
    val LOCAL_MODE_LABELS = MODE_LABELS + "选字"

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

    /**
     * 页面上的书签缎带（`../REQUIREMENTS.md §1.9`）：贴页右缘的一面小旗，右端切 V 口。
     * 尺寸与 Mac 的 `PageCellView.ribbonW/H`（26×15 pt）同数——两端看到的是同一面旗。
     */
    object BM {
        const val W = 26f
        const val H = 15f
        const val NOTCH = 5f     // 右端 V 口的深度
    }

    /** 长按进度环（环形盘的前置动画）：300ms 起显示、700ms 填满、直径 30dp 线宽 3dp、正上方顺时针 */
    object PR {
        const val D = 30f
        const val LW = 3f
        const val DELAY_MS = 300f
        const val FILL_MS = 700f
    }

    /**
     * 长按呼盘的**判定**阈值，逐个对齐 Mac 端 `AppModel`（`longPressSeconds`/`moveCancelPx`/
     * `moveCancelNorm`/`radialDeadzoneNorm`）。
     *
     * 模式2 里这些数在 Mac 上用平板上报的 `padGeom.pageW`（dp）换算成平板屏幕尺度；模式1 自己
     * 就知道页宽，直接用同一组 dp 值算——**手感因此天然一致**（`ANDROID-STANDALONE-PLAN.md §8`）。
     * `*_NORM` 是页宽未知时的归一化回退（模式1 几乎用不到，留着是为了口径完整）。
     */
    object LP {
        const val HOLD_MS = 1000L        // 落笔停住多久呼出盘
        const val MOVE_CANCEL = 14f      // 位移超此 dp → 判为在画，不呼出
        const val MOVE_CANCEL_NORM = 0.02f
        const val DEADZONE_NORM = 0.045f // 中心取消区（页宽未知时）；已知页宽时用 RD.HUB

        /**
         * 第二道闸：**笔尖速度**（2026-08-28 用户报「很容易误触」）。
         *
         * 只看「离落笔点的总位移」挡不住小字：写一个小字全程都在 14dp 半径里打转，停留满 1s
         * 就被当成长按、盘凭空弹出来。而**写字必然在动、长按必然不动**——用滑动窗口内的平均速度
         * 一判就分得干净。两道闸并存：位移管「跑远了」，速度管「一直在动」。
         *
         * ⚠️ 与 Mac `AppModel` 的 `holdSpeedWindow`/`holdSpeedPx`/`holdSpeedNorm` 是**同一套常量的
         * 两份实现**（模式2 的长按判定跑在 Mac，模式1 跑在本机），改一边必须同步另一边。
         */
        const val SPEED_WINDOW_MS = 150L        // 速度判定的滑动窗口
        const val SPEED_MIN_DT_MS = 40L         // 窗口太短时分母噪声会放大成假速度，不判
        const val MOVE_CANCEL_SPEED = 30f       // 窗口内平均速度超此 dp/s → 判为在画
        const val MOVE_CANCEL_SPEED_NORM = 0.043f  // 同上的归一化/秒回退（≈ 30/700，与位移那对同比例）
    }

    /**
     * 文字铺色的透明度口径（Mac `Highlight.fillOpacity` 与 `PageCellView.noteHighlight`）：
     * 高亮按自身颜色 0.38，文字注解的选区底按类型色/通用暖黄 0.32。
     * 通用暖黄 = Mac 的 `Color(red: 1, green: 0.82, blue: 0.15)` 换算成 0~255。
     */
    object FILL {
        const val HIGHLIGHT_A = 0.38f
        const val NOTE_A = 0.32f
        val NOTE_RGB = intArrayOf(255, 209, 38)
    }

    /** 文字笔记标记的命中半径（页内归一化，同 capture 的 0.03） */
    const val NOTE_HIT = 0.03f

    /**
     * 文字笔记图钉（`PadOverlays.drawNoteMarker`）——**逐项对齐 Mac `PageCellView`**。
     *
     * 🔴 [R] 是**固定 dp、不跟页缩放**：Mac 那边就是固定 9pt 的外圆（11pt 图标 + 3pt 内边距），
     * 缩放页面时图钉大小不变。本端从前按 `页宽×0.02` 夹在 12~22dp 算，放大后能长到 44dp 直径，
     * 跟 Mac 完全不是一个东西（用户 2026-09-04 报「和 macOS 端对齐」）。
     * 手指点得着靠的是**热区**（见 `noteMarkerHit` 的 `hot`），不是把图钉画大。
     *
     * [SEL_DX]/[SEL_DY] = 选区注解的图钉相对选区包围盒右上角的偏移（Mac `markerPos` 的 +9/+7）；
     * [EDGE_X]/[EDGE_Y] = 钳进页内的边距（Mac 的 12/10）。
     */
    object PIN {
        const val R = 9f
        const val SEL_DX = 9f
        const val SEL_DY = 7f
        const val EDGE_X = 12f
        const val EDGE_Y = 10f
        /** 图标画多大 ÷ 外圆直径（Mac：11pt 图标 ÷ 18pt 外圆） */
        const val ICON = 11f / 18f
    }

    /**
     * 划字选区的铺色（`PadOverlays.drawTextSelection`）：系统选择蓝 rgba(31,111,235,0.28)。
     * 与框选那套同一支蓝（`lassoBlue`），只是透明度按"压在正文上仍读得清"调。
     */
    const val TEXT_SEL_A = 71   // 0.28 × 255

    /**
     * 荧光笔预设色。**逐项对齐 Mac `Highlight.palette`**（存基色 a=1，渲染统一按
     * [FILL.HIGHLIGHT_A] 降透明）——两端选「黄」得是同一个黄，否则同一本书上的高亮会有两种颜色。
     * 第一项是默认色。
     */
    val HIGHLIGHT_PALETTE = listOf(
        "黄" to intArrayOf(255, 214, 40),
        "绿" to intArrayOf(150, 220, 120),
        "蓝" to intArrayOf(120, 190, 255),
        "粉" to intArrayOf(255, 150, 190),
    )

    // ---- 笔触类型：跟 Mac 端 PenBrushType.strokeWidth/opacityMultiplier 同一套公式 ----

    fun strokeWidthFor(t: String, p: Float, w: Float): Float = when (t) {
        "fountain" -> 0.3f + p.pow(1.6f) * w * 1.3f   // 系数 1.3，与 Mac/JS 一致（曾误写 1.15）
        "marker" -> w
        "pencil" -> 0.5f + p * w * 0.85f
        else -> 0.6f + p * w   // ballpoint / 未知兜底
    }

    fun opacityMultFor(t: String): Float = if (t == "pencil") 0.85f else 1f

    /**
     * 钢笔起收笔锥度（0~1 乘线宽）：`i/(n-1)` 为点在笔画中的归一化位置，两端渐细、中段为 1；
     * 非钢笔恒 1。与 Mac `PenBrushType.fountainTaper` / web `shared.ts fountainTaper` **逐字同式**。
     *
     * `n <= 2` 不锥：两点笔画 = 尺子直线或擦除切出的碎段，按 index 算的话整条都落在「两端」、
     * 会整体细成 0.18 倍。
     *
     * 2026-09-07 补：此前本端与 web **一行都没有**，只有 Mac 有——同一支钢笔在 Mac 上两头尖、
     * 在平板上齐头齐尾。是 `spike/ink-cross/`（三端绘制对比工具）的 `fountain-taper` 向量
     * 照出来的，改完记得再跑一次那个工具。
     */
    fun fountainTaper(t: String, i: Int, n: Int): Float {
        if (t != "fountain" || n <= 2) return 1f
        val p = i.toFloat() / (n - 1).toFloat()
        val edge = 0.16f
        val a = minOf(p, 1f - p) / edge
        return if (a >= 1f) 1f else (a * a * (3f - 2f * a)) * 0.82f + 0.18f
    }

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
