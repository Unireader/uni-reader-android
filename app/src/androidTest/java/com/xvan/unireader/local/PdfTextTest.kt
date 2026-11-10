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
            val mid = s.charTopFrac(1, hits[1].origin)
            assertTrue("第 2 页 $mid", mid in 0.43f..0.52f)
        }
    }
}
