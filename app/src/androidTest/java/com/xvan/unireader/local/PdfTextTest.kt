package com.xvan.unireader.local

import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.shared.TextSearch
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全文搜索要的 Pdfium 取字（`PdfSource.pageText` / `charTopFrac`）：拿一份手写的两页小 PDF（Helvetica，
 * 第 1 页上方一行 "Hello World"、下方一行 "Second line"；第 2 页一行 "hello again"）验文字取得到、纵向位置大致对。
 */
class PdfTextTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pdf(): File {
        fun stream(s: String) = "<< /Length ${s.length} >>\nstream\n$s\nendstream"
        val objs = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 800] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> >>",
            stream("BT /F1 24 Tf 50 700 Td (Hello World) Tj ET\nBT /F1 24 Tf 50 200 Td (Second line) Tj ET"),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 800] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>",
            stream("BT /F1 24 Tf 50 400 Td (hello again) Tj ET"),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        )
        val sb = StringBuilder("%PDF-1.4\n")
        val offs = ArrayList<Int>()
        for ((i, o) in objs.withIndex()) {
            offs += sb.length
            sb.append("${i + 1} 0 obj\n$o\nendobj\n")
        }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) sb.append("%010d 00000 n \n".format(o))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(ctx.cacheDir, "pdftext-test.pdf").apply { writeBytes(sb.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    @Test
    fun 取字与纵向位置() {
        PdfSource(ctx, pdf(), contentHash = "T", align = null).use { s ->
            assertEquals(2, s.pageCount)
            val t0 = s.pageText(0)
            assertTrue(t0, t0.contains("Hello World") && t0.contains("Second line"))
            assertTrue(s.pageText(1).contains("hello again"))
            assertEquals("越界页 = 空串", "", s.pageText(9))
            val hits = ArrayList<TextSearch.Hit>()
            for (p in 0 until s.pageCount) TextSearch.searchPlainPage(p, s.pageText(p), TextSearch.foldQuery("HELLO"), hits)
            assertEquals(listOf(0, 1), hits.map { it.page })
            // 基线 y=700 / 高 800 → 字的上沿在页面上方 1/8 附近；第二行（y=200）在下方 3/4 附近；第 2 页那行在中间
            val top = s.charTopFrac(0, hits[0].origin)
            assertTrue("上方那行 $top", top in 0.05f..0.15f)
            val second = s.charTopFrac(0, t0.indexOf("Second"))
            assertTrue("下方那行 $second", second in 0.68f..0.78f)
            // 命中框：一行一个，位置与字的上沿对得上，宽度在页内
            val rects = s.charRects(0, hits[0].origin, hits[0].length)
            assertEquals(1, rects.size)
            assertTrue("框 ${rects[0].toList()}", kotlin.math.abs(rects[0][1] - top) < 0.02f && rects[0][0] in 0.1f..0.15f && rects[0][2] in 0.05f..0.3f && rects[0][3] in 0.01f..0.05f)
            val mid = s.charTopFrac(1, hits[1].origin)
            assertTrue("第 2 页 $mid", mid in 0.43f..0.52f)
        }
    }

    /** 扫描页增强的位图那一层（分条读写）：开了以后出的页图纸面是白的、字是黑的，条与条之间没有接缝；关掉回到原图。 */
    @Test
    fun 增强出图() {
        fun shot(s: PdfSource): android.graphics.Bitmap {
            val latch = java.util.concurrent.CountDownLatch(1)
            var out: android.graphics.Bitmap? = null
            s.request(0, 600) { out = it; latch.countDown() }
            assertTrue(latch.await(20, java.util.concurrent.TimeUnit.SECONDS))
            return out!!
        }
        PdfSource(ctx, pdf(), contentHash = "T", align = null).use { s ->
            val plain = shot(s)
            s.setEnhance(com.xvan.unireader.shared.ScanEnhance.Params())
            val b = shot(s)
            assertTrue("开了增强要重新出图", b !== plain)
            assertEquals(plain.width, b.width)
            assertTrue("至少跨好几条（128 行一条）", b.height > 600)
            // 空白处一整列都是纯白（跨过每一条的接缝）；字的位置有黑的
            val x = b.width - 20
            for (y in 0 until b.height) assertEquals("($x,$y)", 0xFFFFFFFF.toInt(), b.getPixel(x, y))
            var dark = 0
            for (y in 0 until b.height / 4) for (xx in 0 until b.width step 2) {
                if (android.graphics.Color.red(b.getPixel(xx, y)) < 60) dark++
            }
            assertTrue("字还在 $dark", dark > 50)
            s.setEnhance(null)
            assertTrue(shot(s) !== b)
        }
    }
}
