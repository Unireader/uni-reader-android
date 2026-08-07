package com.xvan.unireader.shared

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 草稿纸无限画布的**纯几何**：视口换算、软边界、回中/适应内容、底纹步长、CSS rgba 解析。
 * 不碰 Android 框架，全部可 JVM 单测（`test/.../shared/ScratchGeomTest.kt`）。
 *
 * 与 Mac `ScratchPadModel.swift`（`ScratchViewport`/`ScratchBounds`/`ScratchGridLayer`）和
 * web `scratch.ts` 是**同一算法三份实现**——表里的契约数改一边必须同步另两边，
 * 对不上的表现是「同一张纸在两端的格子大小/可滚范围不一样」。
 *
 * 单位约定（handoff §1，🔴 弄错全盘皆错）：画布坐标 = **dp**（逻辑点，≈ Mac pt / CSS px），
 * 原点 = 纸创建那一刻的视口中心，x 右 y 下，**可负无界**；视口尺寸同样按 dp 传进来。
 * View 层负责把 `MotionEvent` 的物理像素除 density 再进这里、绘制时乘回去——本文件一个
 * density 都不知道，也**不该**知道。
 *
 * 视口（origin/zoom）不落库、不上线：三端各自独立缩放滚动，打开一律回中。
 */
object ScratchGeom {

    const val MIN_ZOOM = 0.2f
    const val MAX_ZOOM = 8f

    /**
     * 橡皮半径的画布换算基准（**三端契约**，Mac `ScratchPad.eraserRefWidth` / web `PAD_ERASER_REF_W`）：
     * `eraserSize` 是页宽归一化的（0.02 = 页宽 2%），草稿纸没有「页宽」，统一按 800 折成画布 dp：
     * `画布半径 = eraserSize × ERASER_REF_W`。
     */
    const val ERASER_REF_W = 800f

    // ---- 底纹契约数（handoff §4.2 的表，三端对齐过，直接用别自己拍） ----
    const val GRID_BASE = 24f     // 画布步长起点，按 2 的幂折算
    const val GRID_MIN = 22f      // 屏幕间距（dp）舒适区间下限
    const val GRID_MAX = 88f      // 上限
    const val DOT_ALPHA = 0.18f   // 点阵不透明度（方点不用圆点：每帧重画上万个点，方点便宜得多）
    const val GRID_ALPHA = 0.085f // 小格线不透明度（比点阵再淡一档：线更抢戏）
    const val CROSS_HALF = 9f     // 原点十字半长（dp）
    const val CROSS_ALPHA = 0.16f
    /** 极端缩放下的点数安全阀（同 Mac/web），超过就整层不画 */
    const val GRID_MAX_CELLS = 20_000

    /** 软边界：可视区必须与「内容包围盒 ± SLACK 屏」相交，越界拉回（用户明确要求，不能省） */
    const val SLACK = 1.5f

    /** 「适应内容」的留边（dp，每侧；同 Mac `ScratchBounds.fit` 的 padding=40） */
    const val FIT_PAD = 40f

    /** 视口：origin = 视口左上角对应的画布坐标，zoom = 画布→屏幕（dp）的倍率 */
    class Viewport(var ox: Float = 0f, var oy: Float = 0f, var zoom: Float = 1f)

    // ---------- 底纹 ----------

    /** 画布步长：从 24 起按 2 的幂折算，直到屏幕间距落进 [22, 88] dp（任何缩放级别密度都差不多） */
    fun gridStep(zoom: Float): Float {
        var s = GRID_BASE
        val z = max(zoom, 0.0001f)
        while (s * z < GRID_MIN) s *= 2
        while (s * z > GRID_MAX) s /= 2
        return s
    }

    /** 点阵方点的边长（屏幕 dp）：`max(1.5, min(3, zoom × 1.8))`——三端同一条公式 */
    fun dotSize(zoom: Float): Float = min(3f, max(1.5f, zoom * 1.8f))

    /**
     * 解析自由 CSS rgba 串 → `[r, g, b, a]`（各 0~255；`rgb(...)` 无 alpha 按不透明）。
     * Mac 写进库里的就是这个形态；解析失败返回 null，调用方兜白纸——坏数据不该让纸打不开。
     */
    fun parseCssRgba(css: String): IntArray? {
        val m = Regex("""rgba?\(([^)]+)\)""").find(css) ?: return null
        val p = m.groupValues[1].split(",").mapNotNull { it.trim().toFloatOrNull() }
        if (p.size < 3) return null
        val a = if (p.size > 3) (p[3] * 255f).roundToInt().coerceIn(0, 255) else 255
        return intArrayOf(
            p[0].roundToInt().coerceIn(0, 255),
            p[1].roundToInt().coerceIn(0, 255),
            p[2].roundToInt().coerceIn(0, 255),
            a,
        )
    }

    /**
     * 底纹/提示文字的墨色是深还是浅：由**纸色明度**推（浅纸深纹、深纸浅纹），
     * **绝不跟系统深浅外观走**——纸色是这张纸自己的属性，深色外观 + 白纸时跟外观走网格就
     * 整个消失（Mac 已经以另一种形式栽过一次，handoff §4.2 🔴）。
     */
    fun inkIsDark(r: Int, g: Int, b: Int): Boolean =
        (0.299f * r + 0.587f * g + 0.114f * b) / 255f > 0.5f

    // ---------- 内容包围盒 ----------

    /**
     * 笔迹集合（含正在写的这一笔的点集）的画布包围盒 `[x, y, w, h]`；空集 → null。
     * 软边界 / 适应内容 / minimap 共用（同 Mac `ScratchBounds.contentBounds`）。
     */
    fun contentBounds(strokes: List<Stroke>, livePts: List<Pt3>? = null): FloatArray? {
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        var any = false
        fun eat(p: Pt3) {
            any = true
            x0 = min(x0, p.x); x1 = max(x1, p.x)
            y0 = min(y0, p.y); y1 = max(y1, p.y)
        }
        for (s in strokes) for (p in s.pts) eat(p)
        livePts?.forEach(::eat)
        if (!any) return null
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    // ---------- 视口操作（viewW/viewH = 视口 dp 尺寸；返回的 origin 都是画布坐标） ----------

    /** 回中：画布原点（= 纸创建的位置、「从该处显示」的落点）回视口正中。zoom 复位由调用方做 */
    fun centeredOrigin(viewW: Float, viewH: Float, zoom: Float = 1f): FloatArray =
        floatArrayOf(-viewW / (2 * zoom), -viewH / (2 * zoom))

    /**
     * 软边界 clamp（同 Mac `ScratchBounds.clamp` / web `padClamp`）：origin 只能落在
     * 「内容包围盒外扩 1.5 屏」的可达域里；内容为空退化成围着原点的一块，
     * 于是新建的空白纸只能在原点附近小范围移动。返回 `[ox, oy]`。
     */
    fun clampOrigin(
        ox: Float,
        oy: Float,
        zoom: Float,
        content: FloatArray?,
        viewW: Float,
        viewH: Float,
    ): FloatArray {
        val z = max(zoom, 0.0001f)
        val visW = viewW / z
        val visH = viewH / z
        val bx = content?.get(0) ?: 0f
        val by = content?.get(1) ?: 0f
        val bw = content?.get(2) ?: 0f
        val bh = content?.get(3) ?: 0f
        val sx = visW * SLACK
        val sy = visH * SLACK
        // 视口左上角可达域：从「视口右下角刚碰到外扩区左上角」到「视口左上角刚碰到外扩区右下角」
        val minX = bx - sx - visW
        val maxX = bx + bw + sx
        val minY = by - sy - visH
        val maxY = by + bh + sy
        return floatArrayOf(ox.coerceIn(minX, maxX), oy.coerceIn(minY, maxY))
    }

    /**
     * 适应内容：把包围盒（留 [padding] 边距）装进视口。返回 `[ox, oy, zoom]`；
     * 内容为空或视口未就绪 → null（调用方退化为回中，同 Mac/web）。
     */
    fun fit(content: FloatArray?, viewW: Float, viewH: Float, padding: Float = FIT_PAD): FloatArray? {
        if (content == null || viewW <= 1f || viewH <= 1f) return null
        val w = max(content[2], 1f)
        val h = max(content[3], 1f)
        val z = min((viewW - padding * 2) / w, (viewH - padding * 2) / h).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return floatArrayOf(
            content[0] + content[2] / 2 - viewW / (2 * z),
            content[1] + content[3] / 2 - viewH / (2 * z),
            z,
        )
    }

    /**
     * 以某个**视口内 dp 点**为锚缩放（双指捏合）：该点下的画布内容不动
     * （同 Mac `ScratchViewport.zoomed` / web `padZoomAt`）。返回 `[ox, oy, zoom]`。
     */
    fun zoomAt(ox: Float, oy: Float, zoom: Float, factor: Float, ax: Float, ay: Float): FloatArray {
        val z = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (z == zoom) return floatArrayOf(ox, oy, zoom)
        val cx = ox + ax / zoom
        val cy = oy + ay / zoom
        return floatArrayOf(cx - ax / z, cy - ay / z, z)
    }
}
