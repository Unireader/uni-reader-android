package com.xvan.unireader.shared.anim

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * 分步演示的绘制：两帧之间按物件 id 配对插值，只在一边有的淡入 / 淡出（线是「长出来」）。规则逐条照 Mac `Sources/Anim/AnimPainter.swift`。
 * 画在 dp 坐标里（调用方先 `canvas.scale(density)`）。颜色只走语义色（`R.color.*`），深浅两套跟着系统。
 */

/** 颜色表：角色 → ARGB。 */
class AnimPalette(c: Context) {
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ink = Ui.onSurface(c)
    private val secondary = Ui.onVariant(c)
    private val background = Ui.surface(c)
    private val base = mapOf(
        AnimTone.ACTIVE to Ui.accent(c),
        AnimTone.DONE to Ui.col(c, R.color.success),
        AnimTone.WRONG to Ui.col(c, R.color.danger),
        AnimTone.NEW to Ui.col(c, R.color.anim_new),
    )

    private fun alpha(color: Int, a: Float) = Color.argb((Color.alpha(color) * a).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    fun color(role: AnimRole): Int = when (role) {
        AnimRole.Ink -> ink
        AnimRole.Secondary -> secondary
        AnimRole.Grid -> alpha(ink, 0.32f)
        AnimRole.Background -> background
        is AnimRole.Fill -> when (role.tone) {
            AnimTone.NORMAL -> alpha(ink, 0.04f)
            AnimTone.MUTED -> alpha(ink, 0.06f)
            else -> alpha(base.getValue(role.tone), if (dark) 0.3f else 0.2f)
        }
        is AnimRole.Stroke -> when (role.tone) {
            AnimTone.NORMAL -> alpha(ink, 0.5f)
            AnimTone.MUTED -> alpha(ink, 0.3f)
            else -> base.getValue(role.tone)
        }
        is AnimRole.TextC -> when (role.tone) {
            AnimTone.NORMAL -> ink
            AnimTone.MUTED -> alpha(ink, 0.38f)
            else -> base.getValue(role.tone)
        }
    }
}

/** 用 StaticLayout 量字与排字；排好的版式按内容缓存，补间时每帧不重排。 */
class AnimText2D(private val density: Float) : AnimMeasure {
    private class Key(val text: String, val size: Float, val bold: Boolean, val width: Int, val align: AnimAlign) {
        override fun equals(other: Any?) = other is Key && other.text == text && other.size == size && other.bold == bold && other.width == width && other.align == align
        override fun hashCode() = ((text.hashCode() * 31 + size.hashCode()) * 31 + width) * 31 + bold.hashCode() * 7 + align.ordinal
    }

    private val cache = object : LinkedHashMap<Key, StaticLayout>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, StaticLayout>?) = size > 400
    }

    /** 字号按 dp 算（同 Mac 的「点」，不跟系统字体放大：框和字一起按密度缩放，比例不变）。版式按像素排，画的时候再除回 dp。 */
    private fun paint(size: Float, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // 🔴 别写成裸 `density`：TextPaint 自己有个 `density` 字段（默认 1），apply 里会先认它，字就小了几倍（2026-11-14 实测）
        textSize = size * this@AnimText2D.density
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun layout(text: String, size: Float, bold: Boolean, width: Float, align: AnimAlign): StaticLayout {
        val w = maxOf(1, ceil(width * density).toInt())
        val key = Key(text, size, bold, w, align)
        cache[key]?.let { return it }
        val al = when (align) {
            AnimAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            AnimAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            AnimAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, paint(size, bold), w).setAlignment(al).setIncludePad(false).build()
        cache[key] = l
        return l
    }

    override fun measure(text: String, size: Float, bold: Boolean, maxWidth: Float): ASize {
        val p = paint(size, bold)
        val limit = if (maxWidth >= Float.MAX_VALUE / 2) 100000 else maxOf(1, (maxWidth * density).toInt())
        val natural = text.split('\n').maxOf { p.measureText(it) }
        val l = StaticLayout.Builder.obtain(text, 0, text.length, p, minOf(limit, ceil(natural).toInt() + 1)).setIncludePad(false).build()
        var w = 0f
        for (i in 0 until l.lineCount) w = maxOf(w, l.getLineWidth(i))
        return ASize(ceil(w / density) + 1, ceil(l.height / density))
    }
}

object AnimPainter {
    /** 补间时长（毫秒）。 */
    const val TRANSITION_MS = 450L

    fun ease(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return if (v < 0.5f) 2 * v * v else 1 - (-2 * v + 2).pow(2) / 2
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** 画 [from] → [to] 的第 [t]（已缓动）时刻；[to] 为空就只画 [from]。画布已在 dp 坐标里。 */
    fun draw(canvas: Canvas, from: AnimFrame, to: AnimFrame?, t: Float, pal: AnimPalette, text: AnimText2D, density: Float) {
        val ctx = Ctx(canvas, pal, text, density)
        if (to == null || t <= 0f) {
            for (item in from.items) ctx.shape(item.shape, item.alpha)
            return
        }
        if (t >= 1f) {
            for (item in to.items) ctx.shape(item.shape, item.alpha)
            return
        }
        val old = HashMap<String, AnimItem>()
        for (i in from.items) if (i.id !in old) old[i.id] = i
        val fresh = to.items.mapTo(HashSet()) { it.id }
        // 节点框此刻的位置（连线的端点按它算）
        val rects = HashMap<String, ARect>()
        for (i in from.items) (i.shape as? AnimShape.Box)?.let { rects[i.id] = it.rect }
        for (i in to.items) {
            val b1 = i.shape as? AnimShape.Box ?: continue
            val b0 = old[i.id]?.shape as? AnimShape.Box
            rects[i.id] = if (b0 != null) arc(b0.rect, b1.rect, t) else b1.rect
        }
        fun linked(item: AnimItem, alpha: Float): Boolean {
            val link = item.link ?: return false
            val a = rects[link.from]
            val b = rects[link.to]
            val line = item.shape as? AnimShape.Line
            if (a == null || b == null || line == null) return true
            val len = maxOf(hypot(b.midX - a.midX, b.midY - a.midY), 1f)
            val ux = (b.midX - a.midX) / len
            val uy = (b.midY - a.midY) / len
            val pts = listOf(APoint(a.midX + ux * a.w / 2, a.midY + uy * a.h / 2), APoint(b.midX - ux * b.w / 2, b.midY - uy * b.h / 2))
            ctx.shape(line.copy(points = pts, progress = 1f), alpha)
            return true
        }
        for (item in from.items) {
            if (item.id in fresh) continue
            if (linked(item, item.alpha * (1 - t))) continue
            ctx.shape(item.shape, item.alpha * (1 - t))
        }
        for (item in to.items) {
            val a = old[item.id]
            if (linked(item, if (a == null) item.alpha * t else item.alpha)) continue
            val s = item.shape
            if (a != null) {
                ctx.blend(a, item, t)
            } else if (s is AnimShape.Line) {
                ctx.shape(s.copy(progress = s.progress * t), item.alpha)   // 新出现的线：从 0 长出来
            } else {
                ctx.shape(s, item.alpha * t)
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerp(a: APoint, b: APoint, t: Float) = APoint(lerp(a.x, b.x, t), lerp(a.y, b.y, t))
    private fun lerp(a: ARect, b: ARect, t: Float) = ARect(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))

    /** 横着换位置的格子（交换）走弧线：往右的从上面过、往左的从下面过。 */
    private fun arc(a: ARect, b: ARect, t: Float): ARect {
        val r = lerp(a, b, t)
        val dx = b.midX - a.midX
        if (abs(dx) > 1 && abs(b.midY - a.midY) < 1) {
            return r.copy(y = r.y - (if (dx > 0) 1 else -1) * sin(PI.toFloat() * t) * minOf(a.h * 0.55f, 20f))
        }
        return r
    }

    private fun blendColor(a: Int, b: Int, t: Float): Int {
        if (t <= 0) return a
        if (t >= 1) return b
        return Color.argb(lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), t).toInt(), lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), t).toInt(),
            lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), t).toInt(), lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t).toInt())
    }

    private fun withAlpha(c: Int, f: Float) = Color.argb((Color.alpha(c) * f).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private class Ctx(val canvas: Canvas, val pal: AnimPalette, val text: AnimText2D, val density: Float) {

        fun blend(a: AnimItem, b: AnimItem, t: Float) {
            val alpha = lerp(a.alpha, b.alpha, t)
            val sa = a.shape
            val sb = b.shape
            when {
                sa is AnimShape.Box && sb is AnimShape.Box -> {
                    val fill = mix(sa.fill, sb.fill, t)
                    val stroke = mix(sa.stroke, sb.stroke, t)
                    box(arc(sa.rect, sb.rect, t), lerp(sa.radius, sb.radius, t), fill, stroke, lerp(sa.lineWidth, sb.lineWidth, t), sb.dashed, alpha)
                }
                sa is AnimShape.Text && sb is AnimShape.Text -> {
                    val frame = arc(sa.frame, sb.frame, t)
                    if (sa.text == sb.text && sa.size == sb.size) {
                        text(sb.text, frame, sb.size, sa.bold, blendColor(pal.color(sa.role), pal.color(sb.role), t), sa.align, sb.backdrop, alpha)
                    } else {
                        // 字变了：前半程旧字淡出、后半程新字淡入，不同时出现（同 Mac）
                        val out = maxOf(0f, 1 - 2 * t)
                        val into = maxOf(0f, 2 * t - 1)
                        text(sa.text, frame, sa.size, sa.bold, pal.color(sa.role), sa.align, sa.backdrop, alpha * out)
                        shape(sb.copy(frame = frame), alpha * into)
                    }
                }
                sa is AnimShape.Line && sb is AnimShape.Line -> {
                    val pts = if (sa.points.size == sb.points.size) sa.points.zip(sb.points) { p, q -> lerp(p, q, t) } else sb.points
                    line(pts, blendColor(pal.color(sa.role), pal.color(sb.role), t), lerp(sa.width, sb.width, t), sb.dashed, sb.arrow, lerp(sa.progress, sb.progress, t), alpha)
                }
                sa is AnimShape.Cross && sb is AnimShape.Cross -> {
                    cross(lerp(sa.center, sb.center, t), lerp(sa.size, sb.size, t), blendColor(pal.color(sa.role), pal.color(sb.role), t), alpha)
                }
                else -> {
                    shape(sa, a.alpha * (1 - t))
                    shape(sb, b.alpha * t)
                }
            }
        }

        private fun mix(a: AnimRole?, b: AnimRole?, t: Float): Int? = when {
            a == null && b == null -> null
            b == null -> withAlpha(pal.color(a!!), 1 - t)
            a == null -> withAlpha(pal.color(b), t)
            else -> blendColor(pal.color(a), pal.color(b), t)
        }

        fun shape(s: AnimShape, alpha: Float) {
            if (alpha <= 0.003f) return
            when (s) {
                is AnimShape.Box -> box(s.rect, s.radius, s.fill?.let { pal.color(it) }, s.stroke?.let { pal.color(it) }, s.lineWidth, s.dashed, alpha)
                is AnimShape.Text -> text(s.text, s.frame, s.size, s.bold, pal.color(s.role), s.align, s.backdrop, alpha)
                is AnimShape.Line -> line(s.points, pal.color(s.role), s.width, s.dashed, s.arrow, s.progress, alpha)
                is AnimShape.Cross -> cross(s.center, s.size, pal.color(s.role), alpha)
            }
        }

        fun box(r: ARect, radius: Float, fill: Int?, stroke: Int?, lineWidth: Float, dashed: Boolean, alpha: Float) {
            if (alpha <= 0.003f) return
            val rf = RectF(r.minX, r.minY, r.maxX, r.maxY)
            val rad = minOf(radius, r.w / 2, r.h / 2).coerceAtLeast(0f)
            if (fill != null) {
                fillPaint.color = withAlpha(fill, alpha)
                canvas.drawRoundRect(rf, rad, rad, fillPaint)
            }
            if (stroke != null && lineWidth > 0) {
                strokePaint.color = withAlpha(stroke, alpha)
                strokePaint.strokeWidth = lineWidth
                strokePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(3f, 3f), 0f) else null
                canvas.drawRoundRect(rf, rad, rad, strokePaint)
            }
        }

        fun text(s: String, frame: ARect, size: Float, bold: Boolean, color: Int, align: AnimAlign, backdrop: Boolean, alpha: Float) {
            if (s.isEmpty() || alpha <= 0.003f) return
            if (backdrop) box(frame, 3f, withAlpha(pal.color(AnimRole.Background), 0.92f), null, 0f, false, alpha)
            val l = text.layout(s, size, bold, frame.w, align)
            l.paint.color = withAlpha(color, alpha)
            val h = l.height / density
            // 一行的字在框里竖直居中；多行的（说明）从顶上排（同 Mac）
            val y = if (h <= frame.h && (align == AnimAlign.CENTER || backdrop || frame.h <= 20)) frame.minY + (frame.h - h) / 2 else frame.minY
            canvas.save()
            canvas.translate(frame.minX, y)
            canvas.scale(1 / density, 1 / density)
            l.draw(canvas)
            canvas.restore()
        }

        fun line(pts: List<APoint>, color: Int, width: Float, dashed: Boolean, arrow: Boolean, progress: Float, alpha: Float) {
            if (pts.size < 2 || progress <= 0.001f || alpha <= 0.003f) return
            val lengths = (1 until pts.size).map { hypot(pts[it].x - pts[it - 1].x, pts[it].y - pts[it - 1].y) }
            val total = lengths.sum()
            if (total <= 0) return
            var remaining = total * minOf(progress, 1f)
            val drawn = arrayListOf(pts[0])
            for (i in 1 until pts.size) {
                val l = lengths[i - 1]
                if (remaining >= l) {
                    drawn.add(pts[i]); remaining -= l
                } else {
                    drawn.add(lerp(pts[i - 1], pts[i], if (l > 0) remaining / l else 0f)); break
                }
            }
            val path = Path().apply {
                moveTo(drawn[0].x, drawn[0].y)
                for (i in 1 until drawn.size) lineTo(drawn[i].x, drawn[i].y)
            }
            strokePaint.color = withAlpha(color, alpha)
            strokePaint.strokeWidth = width
            strokePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(5f, 4f), 0f) else null
            canvas.drawPath(path, strokePaint)
            if (arrow && drawn.size >= 2) {
                val tip = drawn[drawn.size - 1]
                val prev = drawn[drawn.size - 2]
                val angle = atan2(tip.y - prev.y, tip.x - prev.x)
                val size = 7f
                val head = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(tip.x - size * cos(angle - 0.4f), tip.y - size * sin(angle - 0.4f))
                    lineTo(tip.x - size * cos(angle + 0.4f), tip.y - size * sin(angle + 0.4f))
                    close()
                }
                fillPaint.color = withAlpha(color, alpha)
                canvas.drawPath(head, fillPaint)
            }
        }

        fun cross(c: APoint, size: Float, color: Int, alpha: Float) {
            strokePaint.color = withAlpha(color, alpha)
            strokePaint.strokeWidth = 2f
            strokePaint.pathEffect = null
            canvas.drawLine(c.x - size, c.y - size, c.x + size, c.y + size, strokePaint)
            canvas.drawLine(c.x + size, c.y - size, c.x - size, c.y + size, strokePaint)
        }
    }
}
