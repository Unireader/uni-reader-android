package com.xvan.unireader.local

import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模式1 剪贴板的跨空间折算。换算口径是 Mac `InkClipboard.scaled`：画布 = 页内 × 800，纵向再乘页纵横比——
 * 对不上就是「同一段笔迹从纸上粘进页里（或反过来）两端大小不一样」。
 */
class InkClipLocalTest {

    private val pen = Pen(20, 20, 20, 1f, 6f, 0)
    private fun stroke(vararg xy: Float) =
        Stroke(0, pen, xy.toList().chunked(2).map { Pt3(it[0], it[1], 0.5f) }, id = "old", padId = "p")

    @Test
    fun 纸上复制粘回纸上_中心对齐落点且只平移() {
        InkClipLocal.putCanvas(listOf(stroke(0f, 0f, 100f, 40f)))
        val out = InkClipLocal.takeCanvas(500f, -200f)
        assertEquals(1, out.size)
        val p = out[0].pts
        assertEquals(450f, p[0].x, 1e-4f); assertEquals(-220f, p[0].y, 1e-4f)
        assertEquals(550f, p[1].x, 1e-4f); assertEquals(-180f, p[1].y, 1e-4f)
        // 粘出来的是新条目：id / padId 清空，由落库方生成
        assertEquals("", out[0].id)
        assertEquals("", out[0].padId)
    }

    @Test
    fun 页里复制粘到纸上_按800与源页纵横比放大() {
        // 页内 (0.1,0.1)-(0.2,0.3)，源页纵横比 1.5 → 画布宽 80、高 0.2×800×1.5 = 240
        InkClipLocal.put(listOf(stroke(0.1f, 0.1f, 0.2f, 0.3f)), 1.5f)
        val p = InkClipLocal.takeCanvas(0f, 0f)[0].pts
        assertEquals(80f, p[1].x - p[0].x, 1e-3f)
        assertEquals(240f, p[1].y - p[0].y, 1e-3f)
        assertEquals(0f, (p[0].x + p[1].x) / 2f, 1e-3f)
    }

    @Test
    fun 纸上复制粘进页里_按800与目标页纵横比缩小() {
        // 画布 80×240，目标页纵横比 1.5 → 页内宽 0.1、高 240/(800×1.5) = 0.2
        InkClipLocal.putCanvas(listOf(stroke(0f, 0f, 80f, 240f)))
        val out = InkClipLocal.take(page = 3, cx = 0.5f, cy = 0.5f, targetAspect = 1.5f, xMargin = 0f, layerId = "L")
        val p = out[0].pts
        assertEquals(0.1f, p[1].x - p[0].x, 1e-5f)
        assertEquals(0.2f, p[1].y - p[0].y, 1e-5f)
        assertEquals(3L, out[0].page)
        assertEquals("L", out[0].layerId)
        assertTrue(p.all { it.x in 0f..1f && it.y in 0f..1f })
    }
}
