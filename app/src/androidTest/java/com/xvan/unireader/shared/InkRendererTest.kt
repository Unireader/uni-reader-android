package com.xvan.unireader.shared

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InkRenderer] 的插桩测试（`Path`/`Paint` 在纯 JVM 单测里全是 stub，只能跑在设备上）。
 *
 * 要证明的是**「逐段描边 → 逐段轮廓 + 一次 FILL」这次改写没把笔迹画漏**。填充按 WINDING 规则算：
 * 若 stroker 给相邻/交叠两段生成的轮廓绕向反号，重叠区的 winding 会抵消成 0，笔迹上就破出白洞——
 * 推理认为不会，但这属于「用户一眼能看见」的那类瑕疵，得实测。
 *
 * 采样方式是「墨该在的地方必须是墨」：在笔迹中心线上逐像素查颜色，露白即失败。
 */
class InkRendererTest {

    /** 页 = 整张位图（viewX/viewY 直接按位图尺寸展开），省掉 PageCanvasView 那一整套几何 */
    private class FullBitmapPage(private val w: Float, private val h: Float) : PageMapper {
        override fun viewX(page: Int, nx: Float) = nx * w
        override fun viewY(page: Int, ny: Float) = ny * h
    }

    private val size = 400
    private val mapper = FullBitmapPage(size.toFloat(), size.toFloat())

    private fun render(pen: Pen, pts: List<Pt3>): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        InkRenderer(3f).drawStroke(c, Stroke(0L, pen, pts), mapper)
        return bmp
    }

    /** 中心线 y=200 上 [x0,x1] 区间必须处处有墨（白 = 破洞） */
    private fun assertInkAlong(bmp: Bitmap, x0: Int, x1: Int, what: String) {
        for (x in x0..x1) {
            val p = bmp.getPixel(x, size / 2)
            assertTrue(
                "$what：x=$x 处露白了（r=${Color.red(p)}），填充绕向被抵消",
                Color.red(p) < 128,
            )
        }
    }

    /** 直线一笔：最基本的「段与段之间不能有缝」 */
    @Test
    fun 逐段轮廓拼起来的直笔迹不断线() {
        val pts = (0..20).map { Pt3(0.2f + it * 0.03f, 0.5f, 0.8f) }
        assertInkAlong(render(Pen(0, 0, 0, 1f, 10f, 0), pts), 90, 310, "ballpoint 直线")
    }

    /**
     * 原路折返：去程与回程的描边轮廓绕向若反号，整条重叠区会被 WINDING 抵消成透明——
     * 这是「攒进一条 Path 再 fill」最可能翻车的形状，也是这个测试存在的理由。
     */
    @Test
    fun 原路折返的笔迹不出空洞() {
        val fwd = (0..20).map { Pt3(0.2f + it * 0.03f, 0.5f, 0.8f) }
        val back = (0..20).map { Pt3(0.8f - it * 0.03f, 0.5f, 0.8f) }
        assertInkAlong(render(Pen(0, 0, 0, 1f, 10f, 0), fwd + back), 90, 310, "ballpoint 折返")
    }

    /** 压感一路变化（线宽逐段不同）时同样不能破 */
    @Test
    fun 压感变宽的笔迹不出空洞() {
        val pts = (0..20).map { Pt3(0.2f + it * 0.03f, 0.5f, 0.2f + it * 0.03f) }
        assertInkAlong(render(Pen(0, 0, 0, 1f, 10f, 1), pts), 90, 310, "fountain 压感")
    }

    /** marker 仍走「整条一次描边」那条路（不转轮廓），顺带守住它没被改坏 */
    @Test
    fun 荧光笔整条描边照旧成线() {
        val pts = (0..20).map { Pt3(0.2f + it * 0.03f, 0.5f, 0.8f) }
        val bmp = render(Pen(255, 214, 40, 1f, 12f, 2), pts)
        for (x in 90..310) {
            val p = bmp.getPixel(x, size / 2)
            assertTrue("marker：x=$x 处没上色（b=${Color.blue(p)}）", Color.blue(p) < 200)
        }
    }

    /** 单点 = 一个圆点（改写后它是唯一还在用 addCircle 的分支） */
    @Test
    fun 单点笔迹画成圆点() {
        val bmp = render(Pen(0, 0, 0, 1f, 10f, 0), listOf(Pt3(0.5f, 0.5f, 0.8f)))
        assertTrue("单点没画出来", Color.red(bmp.getPixel(size / 2, size / 2)) < 128)
    }

    /**
     * 几何缓存的正确性：同一条笔迹连画两次（第二次必然命中缓存）结果必须逐像素相同，
     * 且换了页宽之后要按新尺寸重建——缓存画错比慢更糟，这条守住「缓存 ≠ 画旧的」。
     */
    @Test
    fun 缓存命中与页宽变化都画对() {
        val pen = Pen(0, 0, 0, 1f, 10f, 0)
        val pts = (0..20).map { Pt3(0.2f + it * 0.03f, 0.5f, 0.8f) }
        val stroke = Stroke(0L, pen, pts)
        val ink = InkRenderer(3f)

        fun shot(m: PageMapper): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            ink.drawStroke(c, stroke, m)
            return bmp
        }

        val first = shot(mapper)
        val second = shot(mapper)   // 命中缓存
        assertTrue("缓存命中后画出来的不一样", first.sameAs(second))

        // 页宽减半（相当于缩小一档）：墨迹跟着收窄，原来最右端的位置应当已经没有墨了
        val half = shot(FullBitmapPage(size / 2f, size.toFloat()))
        assertTrue(
            "页宽变了没重建几何（右端仍有墨）",
            Color.red(half.getPixel(300, size / 2)) > 200,
        )
        assertInkAlong(half, 50, 150, "半宽重建后")
    }
}
