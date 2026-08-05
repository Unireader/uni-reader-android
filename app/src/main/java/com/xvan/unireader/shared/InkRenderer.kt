package com.xvan.unireader.shared

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 笔迹绘制：与 Mac `inkDrawStroke`（`Sources/Views/InkLayers.swift`）逐分支对齐（静态层与活体层
 * 共用同一份，保证「正在写」和「写完」观感一致）。
 *
 * 两个分支不能合并（合了就是 bug，两端都踩过）：
 * - **marker 必须整条一次成 path** + 平头 + multiply 叠加。逐段 stroke 会让相邻段的线帽互相重叠，
 *   不透明笔看不出来，半透明的荧光笔就叠成一串圆斑。
 * - ballpoint/fountain/pencil 线宽随压感变，没法整条一次描边，只能逐段。
 *
 * ## 逐段描边 → 逐段轮廓 + 一次填充（性能与观感的同一处改动）
 *
 * 这里曾是「每段 `path.reset()` + `drawPath(STROKE)`」——一条 50 点的笔迹就是 50 次 drawPath，
 * 一页字几十条笔迹就是**每帧几千次**；而且 Path 建在**视口坐标**上，滚动一像素全部几何都变，
 * HWUI 的路径缓存 100% 落空，每帧从零 tessellate。用户报的「有笔迹时滚动很卡」就是这个
 * （只给笔迹层加可见页裁剪治不了：正在写字的那页恰恰就是可见页，一条都裁不掉）。
 *
 * 改法照抄 Mac 已经落地的那一版：每段的描边先用 [Paint.getFillPath] 转成**填充轮廓**攒进同一条
 * Path，整条笔迹收尾只 `drawPath(FILL)` 一次。顺带修掉一个观感 bug——逐段各自半透明合成会让
 * 相邻段共享的圆头越叠越黑（Mac 那边记的「黑点瑕疵」根因），攒成一条后重叠只是同一次填充里的
 * 自重叠，不再重复合成。
 *
 * ## 几何缓存
 *
 * 构建出来的轮廓存在 **页局部像素坐标**里（原点 = 页左上角，x∈[0,pw]、y∈[0,ph]），画的时候只
 * `translate` 到页的当前位置。于是滚动不改变任何几何：Path 对象原样复用，HWUI 的路径缓存也就能
 * 命中。缓存 key 直接用 [Stroke] 本身（data class，按内容比较）——模式2 每次回传都是新解码出来的
 * 对象，按对象身份做 key 会全部落空，按内容就能复用。
 *
 * 缩放会改变 pw/ph 从而使几何失效（线宽是屏幕 px、不随缩放变，所以轮廓必然跟着 zoom 走）。
 * 捏合进行中由 [deferRebuild] 挡住重建、改用 canvas 缩放近似（笔迹粗细跟手缩放一拍），
 * 松手后自然重建回精确几何——否则捏合每一帧都要把整页笔迹的轮廓重算一遍，比原来还卡。
 *
 * 线宽单位：`pen.w` 是 dp（≈ 网页 CSS px），画之前乘 density。
 */
class InkRenderer(private val density: Float) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * 「一段描边 → 该段的填充轮廓」的转换笔。**必须与 [paint] 分开**：`getFillPath` 读的是这支笔的
     * style/线宽/线帽，共用一支的话几何构建会顺手改掉画笔的状态（下一条笔画就继承了错的 style）。
     */
    private val outliner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val seg = Path()
    private val segFill = Path()

    /**
     * API 26~28 的兜底。**它与 `BlendMode.MULTIPLY` 不是一回事**——`PorterDuff.Mode.MULTIPLY` 是在
     * **预乘 alpha** 上做 `Sc×Dc` 的老式合成，而网页 Canvas 的 `globalCompositeOperation='multiply'`
     * 与 Mac 的 `.multiply` 是 W3C 规定的**混合模式**（先按 blend 公式混色，再按 alpha 走 source-over）。
     *
     * 差别在半透明笔上肉眼可见（`ANDROID-STANDALONE-PLAN.md §9.4`）：黄 40% 压白底，
     * 期望 `0.4×黄 + 0.6×白 = (255,239,169)` 的浅黄，PorterDuff 给的是 `0.4×黄 × 白 ≈ (102,86,16)`
     * 的深橄榄——荧光笔因此偏暗发浊。所以 29+ 一律走 [BlendMode.MULTIPLY]，
     * 这里只是老设备上「有总比没有好」的近似。
     */
    private val multiplyLegacy = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    /**
     * 开/关 multiply 叠加。**两条路必须由同一个函数管**：`xfermode` 与 `blendMode` 写的是 Paint 里
     * 同一处状态，一处设、另一处忘了清，下一条笔画就会继承上一条的混合模式（表现为「画着画着颜色变了」）。
     */
    private fun multiply(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            paint.blendMode = if (on) BlendMode.MULTIPLY else null   // null = 恢复 SRC_OVER
        } else {
            paint.xfermode = if (on) multiplyLegacy else null
        }
    }

    /**
     * 一条笔迹已构建好的几何（页局部像素坐标）。
     *
     * `pw`/`ph` 是构建时的页显示尺寸：画的时候不等就说明缩放变过，得重建（或按 [deferRebuild] 近似）。
     * `strokeW > 0` 表示这份几何是**描边中心线**（marker：恒宽，整条一次成 path 才不会在接缝出圆斑），
     * 否则是**填充轮廓**（压感变宽的三种笔）。
     */
    private class Geom(val pw: Float, val ph: Float, val path: Path, val strokeW: Float)

    /**
     * 内容 → 几何。**LRU 有上限**：擦除切段、框选移动都会造出新内容的 Stroke，旧条目没人再问，
     * 不设上限就是一路涨的内存。上限按「几页密密麻麻的字」估。
     */
    private val cache = object : LinkedHashMap<Stroke, Geom>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stroke, Geom>) = size > MAX_CACHE
    }

    /** 捏合进行中：pw/ph 变了也别重建几何，用 canvas 缩放顶一拍（见类注释） */
    var deferRebuild = false

    /** 本轮重建了几条（诊断打点用，读完自己清零） */
    var rebuilt = 0

    /** 换文档之类的整体作废（几何本身按内容 key，不清也不会画错，只是白占内存） */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 画一条**已成形**的笔迹（静态层，走几何缓存）。
     * `ox`/`oy` 是框选移动的乐观位移（页内归一化，默认 0）——数据本身不动，只在画的时候偏一下，
     * 避免「松手弹回原位、Mac 回传才跳到新位置」的闪烁。
     */
    fun drawStroke(c: Canvas, s: Stroke, m: PageMapper, ox: Float = 0f, oy: Float = 0f) {
        if (s.pts.isEmpty()) return
        val page = s.page.toInt()
        val left = m.viewX(page, 0f)
        val pw = m.viewX(page, 1f) - left
        val top = m.viewY(page, 0f)
        val ph = m.viewY(page, 1f) - top
        if (pw <= 0f || ph <= 0f) return
        var g = cache[s]
        // 0.5px 的容差：几何是像素级的，比这更小的页宽变化重建了也看不出来
        if (g == null || (!deferRebuild && (abs(g.pw - pw) > 0.5f || abs(g.ph - ph) > 0.5f))) {
            g = build(s.pen, s.pts, pw, ph)
            cache[s] = g
            rebuilt++
        }
        render(c, g, s.pen, left + ox * pw, top + oy * ph, pw, ph)
    }

    /**
     * 画**正在写的这一笔**（活体层）。几何每帧都在变，进缓存只会把 LRU 冲垮，所以每帧重建——
     * 但走的是与静态层同一个 [build]，两者观感因此一致（这是整个类只有一份绘制代码的理由）。
     */
    fun drawLive(c: Canvas, page: Int, pen: Pen, pts: List<Pt3>, m: PageMapper) {
        if (pts.isEmpty()) return
        val left = m.viewX(page, 0f)
        val pw = m.viewX(page, 1f) - left
        val top = m.viewY(page, 0f)
        val ph = m.viewY(page, 1f) - top
        if (pw <= 0f || ph <= 0f) return
        render(c, build(pen, pts, pw, ph), pen, left, top, pw, ph)
    }

    // ---------- 几何构建（页局部像素坐标） ----------

    private fun build(pen: Pen, pts: List<Pt3>, pw: Float, ph: Float): Geom {
        val t = brushName(pen.brush)
        val path = Path()

        fun px(p: Pt3) = p.x.coerceIn(0f, 1f) * pw
        fun py(p: Pt3) = p.y.coerceIn(0f, 1f) * ph
        fun width(p: Float) = PadConst.strokeWidthFor(t, p, pen.w) * density

        var lpx = px(pts[0])
        var lpy = py(pts[0])

        // 单点 = 一个圆点（同 Mac 单点分支）
        if (pts.size == 1) {
            path.addCircle(lpx, lpy, width(pts[0].p) / 2f, Path.Direction.CW)
            return Geom(pw, ph, path, 0f)
        }

        if (t == "marker") {
            path.moveTo(lpx, lpy)
            for (i in 1 until pts.size) {
                val x = px(pts[i]); val y = py(pts[i])
                path.quadTo(lpx, lpy, (lpx + x) / 2f, (lpy + y) / 2f)
                lpx = x; lpy = y
            }
            path.lineTo(lpx, lpy)   // 补末段（同下方分支：中点平滑链止于倒数两点的中点）
            // 下限护住「strokeW > 0 即描边中心线」这条判别式：w 万一是 0，几何会被当成填充轮廓画歪
            return Geom(pw, ph, path, (pen.w * density).coerceAtLeast(0.1f))
        }

        // 压感变宽的三种笔：逐段描边转轮廓攒进同一条 path，收尾一次 FILL（见类注释）。
        //
        // 这里曾额外画一个「起笔圆点」，攒轮廓之后**不能再留**：填充按 WINDING 规则算，圆点的绕向
        // （`Path.Direction`）与 stroker 生成的轮廓绕向不一定同号，反号的重叠区 winding 会抵消成 0，
        // 起笔处于是破一个洞。Mac 的 default 分支本来也没有这个圆点——第一段从 pts[0] 起步，
        // ROUND 线帽天然在起点铺出半圆，两端就此对齐。
        var lmx = lpx
        var lmy = lpy
        for (i in 1 until pts.size) {
            val x = px(pts[i]); val y = py(pts[i])
            val mx = (lpx + x) / 2f
            val my = (lpy + y) / 2f
            seg.reset()
            seg.moveTo(lmx, lmy)
            seg.quadTo(lpx, lpy, mx, my)
            outliner.strokeWidth = width(pts[i].p)
            outliner.getFillPath(seg, segFill)
            path.addPath(segFill)
            lmx = mx; lmy = my; lpx = x; lpy = y
        }
        // 补末段：上面每步只画到「相邻两点的中点」，末点从来没被连上——长笔画差这半段看不出来，
        // 两点直线（尺子）就是整整少画一半（线尾追不上笔尖）。补一段 lastMid → 末点才落到笔尖。
        seg.reset()
        seg.moveTo(lmx, lmy)
        seg.lineTo(lpx, lpy)
        outliner.strokeWidth = width(pts.last().p)
        outliner.getFillPath(seg, segFill)
        path.addPath(segFill)
        return Geom(pw, ph, path, 0f)
    }

    // ---------- 上色 ----------

    private fun render(c: Canvas, g: Geom, pen: Pen, left: Float, top: Float, pw: Float, ph: Float) {
        val t = brushName(pen.brush)
        val alpha = (pen.a * 255f * PadConst.opacityMultFor(t)).roundToInt().coerceIn(0, 255)
        // 缩放中（deferRebuild）几何还是旧尺寸的，用 canvas 缩放顶一拍；平时恒为 1
        val sx = pw / g.pw
        val sy = ph / g.ph
        c.save()
        c.translate(left, top)
        if (sx != 1f || sy != 1f) c.scale(sx, sy)
        paint.color = Color.argb(alpha, pen.r, pen.g, pen.b)
        if (g.strokeW > 0f) {
            multiply(true)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.SQUARE   // 平头：圆头会在起收笔处鼓出来
            paint.strokeWidth = g.strokeW / sx   // 线宽不随缩放变，所以要把 canvas 的缩放除回去
            c.drawPath(g.path, paint)
            multiply(false)
            paint.strokeCap = Paint.Cap.ROUND
        } else {
            paint.style = Paint.Style.FILL
            c.drawPath(g.path, paint)
        }
        c.restore()
    }

    private companion object {
        const val MAX_CACHE = 3000
    }
}
