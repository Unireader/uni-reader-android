package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 扫描页增强的像素逻辑：色调曲线、纸色估计、去底色 / 去阴影、分条与整张一致。 */
class ScanEnhanceTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun lum(c: Int) = (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8

    /** 一页假扫描件：发黄的纸、右侧一条蓝灰阴影、几道深灰「笔画」。 */
    private fun page(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val shade = if (x > w * 3 / 4) 40 else 0
        if (y % 12 in 4..5 && x in 10 until w - 10 && x % 9 < 6) rgb(60, 58, 55)
        else rgb(235 - shade, 228 - shade, 205 - shade / 2)
    }

    @Test
    fun toneCurve() {
        val lut = ScanEnhance.toneLut(ScanEnhance.Params())
        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        assertEquals("白点以上一律纯白", 255, lut[(0.92f * 255).toInt() + 1])
        for (i in 1..255) assertTrue("单调不减 @$i", lut[i] >= lut[i - 1])
        assertTrue("暗部压深", lut[77] < 77 - 20)        // 0.3 → 0.3 − 0.126
        assertTrue("中间灰保留层次", lut[153] in 100..150)
        // 不加深时暗部基本不动
        val flat = ScanEnhance.toneLut(ScanEnhance.Params(inkDarken = 0f))
        assertTrue(kotlin.math.abs(flat[77] - 77) <= 2)
    }

    @Test
    fun cleansPaperKeepsInk() {
        val w = 120
        val h = 96
        val px = page(w, h)
        val scale = 2
        val sw = w / scale
        val sh = h / scale
        val small = IntArray(sw * sh) { i -> px[(i / sw) * scale * w + (i % sw) * scale] }
        val paper = ScanEnhance.estimatePaper(small, sw, sh, maxR = 2, blurR = 3)
        val p = ScanEnhance.Params(sharpen = 0f)
        val out = px.copyOf()
        ScanEnhance.processStrip(out, w, h, 0, h, paper, ScanEnhance.toneLut(p), p, sharpR = 0)
        // 纸面（含阴影那一条）都成了白，颜色也没了
        for ((x, y) in listOf(20 to 1, 60 to 20, w - 5 to 1, w - 8 to 80)) {
            val c = out[y * w + x]
            assertTrue("纸面 ($x,$y) 变白：${Integer.toHexString(c)}", lum(c) >= 250)
            assertTrue("纸面没有色偏", ((c shr 16) and 0xFF) - (c and 0xFF) <= 4)
        }
        // 笔画还是深的，而且比原来更深
        val ink = out[4 * w + 12]
        assertTrue("笔画够黑 ${lum(ink)}", lum(ink) < 60)
        assertTrue(lum(ink) <= lum(px[4 * w + 12]))
    }

    @Test
    fun stripsMatchWhole() {
        val w = 90
        val h = 80
        val px = page(w, h)
        val paper = ScanEnhance.estimatePaper(px, w, h, maxR = 3, blurR = 5)
        val p = ScanEnhance.Params()
        val lut = ScanEnhance.toneLut(p)
        val r = 2
        val whole = px.copyOf()
        ScanEnhance.processStrip(whole, w, h, 0, h, paper, lut, p, r)
        // 分两条做：每条上下多带 r+1 行，只取中间
        val out = IntArray(w * h)
        val m = r + 1
        for ((a, b) in listOf(0 to 40, 40 to 80)) {
            val top = maxOf(0, a - m)
            val bot = minOf(h, b + m)
            val strip = px.copyOfRange(top * w, bot * w)
            ScanEnhance.processStrip(strip, w, bot - top, top, h, paper, lut, p, r)
            System.arraycopy(strip, (a - top) * w, out, a * w, (b - a) * w)
        }
        assertTrue("分条 = 整张", whole.contentEquals(out))
    }
}
