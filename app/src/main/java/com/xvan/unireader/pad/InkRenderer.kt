package com.xvan.unireader.pad

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.roundToInt

/**
 * 笔迹绘制：`web/src/lib/render.ts` 的 `drawStroke` 逐分支移植（静态层与活体层共用同一份，
 * 保证「正在写」和「写完」观感一致）。
 *
 * 两个分支不能合并（合了就是 bug，两端都踩过）：
 * - **marker 必须整条一次成 path** + 平头 + multiply 叠加。逐段 stroke 会让相邻段的线帽互相重叠，
 *   不透明笔看不出来，半透明的荧光笔就叠成一串圆斑。
 * - ballpoint/fountain/pencil 线宽随压感变，只能逐段画（同 Mac `inkDrawStroke` 的 default 分支）。
 *
 * 线宽单位：`pen.w` 是 dp（≈ 网页 CSS px），画之前乘 density。
 */
class InkRenderer(private val density: Float) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    /**
     * 画一整条笔迹。`ox`/`oy` 是框选移动的乐观位移（页内归一化，默认 0）——数据本身不动，
     * 只在画的时候偏一下，避免「松手弹回原位、Mac 回传才跳到新位置」的闪烁。
     */
    fun drawStroke(
        c: Canvas,
        page: Int,
        pen: WireCodec.Pen,
        pts: List<WireCodec.Pt3>,
        m: PageMapper,
        ox: Float = 0f,
        oy: Float = 0f,
    ) {
        if (pts.isEmpty()) return
        val t = WireCodec.brushName(pen.brush)
        val alpha = (pen.a * 255f * PadConst.opacityMultFor(t)).roundToInt().coerceIn(0, 255)
        val color = Color.argb(alpha, pen.r, pen.g, pen.b)

        fun vx(p: WireCodec.Pt3) = m.viewX(page, (p.x + ox).coerceIn(0f, 1f))
        fun vy(p: WireCodec.Pt3) = m.viewY(page, (p.y + oy).coerceIn(0f, 1f))
        fun width(p: Float) = PadConst.strokeWidthFor(t, p, pen.w) * density

        var lpx = vx(pts[0])
        var lpy = vy(pts[0])

        // 单点 = 一个圆点（同 Mac 端单点分支）
        if (pts.size == 1) {
            paint.xfermode = null
            paint.style = Paint.Style.FILL
            paint.color = color
            c.drawCircle(lpx, lpy, width(pts[0].p) / 2f, paint)
            return
        }

        if (t == "marker") {
            path.reset()
            path.moveTo(lpx, lpy)
            for (i in 1 until pts.size) {
                val px = vx(pts[i]); val py = vy(pts[i])
                path.quadTo(lpx, lpy, (lpx + px) / 2f, (lpy + py) / 2f)
                lpx = px; lpy = py
            }
            path.lineTo(lpx, lpy)   // 补末段（同下方分支：中点平滑链止于倒数两点的中点）
            paint.xfermode = multiply
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.SQUARE   // 平头：圆头会在起收笔处鼓出来
            paint.strokeWidth = pen.w * density
            paint.color = color
            c.drawPath(path, paint)
            paint.xfermode = null
            paint.strokeCap = Paint.Cap.ROUND
            return
        }

        paint.xfermode = null
        paint.color = color
        paint.style = Paint.Style.FILL
        c.drawCircle(lpx, lpy, width(pts[0].p) / 2f, paint)   // 起笔圆点
        paint.style = Paint.Style.STROKE
        var lmx = lpx
        var lmy = lpy
        for (i in 1 until pts.size) {
            val px = vx(pts[i]); val py = vy(pts[i])
            val mx = (lpx + px) / 2f
            val my = (lpy + py) / 2f
            paint.strokeWidth = width(pts[i].p)
            path.reset()
            path.moveTo(lmx, lmy)
            path.quadTo(lpx, lpy, mx, my)
            c.drawPath(path, paint)
            lmx = mx; lmy = my; lpx = px; lpy = py
        }
        // 补末段：上面每步只画到「相邻两点的中点」，末点从来没被连上——长笔画差这半段看不出来，
        // 两点直线（尺子）就是整整少画一半（线尾追不上笔尖）。补一段 lastMid → 末点才落到笔尖。
        paint.strokeWidth = width(pts.last().p)
        path.reset()
        path.moveTo(lmx, lmy)
        path.lineTo(lpx, lpy)
        c.drawPath(path, paint)
    }
}
