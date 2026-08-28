package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展开气泡的几何（纯计算）。折行用一个「每个字符宽 = 字号」的假量字函数，
 * 于是行容量是可以手算的，断言不依赖真机字体。
 */
class NoteBubbleGeomTest {

    /** 假字体：每字符宽 = 字号（真机上由 Paint.measureText 提供） */
    private val measure: (String, Float) -> Float = { s, fs -> s.length * fs }

    private fun wrap(text: String, maxW: Float, fs: Float = 10f) =
        NoteBubbleGeom.wrap(text, maxW) { measure(it, fs) }

    // ---------- 折行 ----------

    @Test
    fun 短文本不折行() {
        assertEquals(listOf("abc"), wrap("abc", 100f))
    }

    @Test
    fun 超宽按字符折行() {
        // 每字符 10 宽、上限 35 → 每行 3 个字符
        assertEquals(listOf("abc", "def", "g"), wrap("abcdefg", 35f))
    }

    @Test
    fun 换行符各自成段() {
        assertEquals(listOf("ab", "cd"), wrap("ab\ncd", 100f))
    }

    @Test
    fun 超出上限截断并加省略号() {
        val lines = wrap("x".repeat(200), 35f)
        assertEquals(NoteBubbleGeom.MAX_LINES, lines.size)
        assertTrue("末行应有省略号：${lines.last()}", lines.last().endsWith("…"))
    }

    @Test
    fun 恰好排满不加省略号() {
        // 10 行 × 每行 3 字符 = 30 字符，正好排满且没有剩余
        val lines = wrap("x".repeat(30), 35f)
        assertEquals(NoteBubbleGeom.MAX_LINES, lines.size)
        assertFalse(lines.last().endsWith("…"))
    }

    // ---------- 布局 ----------

    /** 页宽 1000、页在视口 (0,0)-(1000,1400)；图钉半径 20 */
    private fun layout(text: String, pinX: Float, pinY: Float, sticky: Boolean = true) =
        NoteBubbleGeom.layout(
            text, 1000f, pinX, pinY, 20f, 0f, 0f, 1000f, 1400f, sticky, measure,
        )

    @Test
    fun 默认贴在图钉右侧() {
        val b = layout("ab", 100f, 300f)
        assertEquals(1000f * NoteBubbleGeom.WIDTH, b.w, 0.01f)
        assertEquals(1000f * NoteBubbleGeom.FONT, b.fs, 0.01f)
        // x = pin + r + gap
        assertEquals(100f + 20f + b.fs * NoteBubbleGeom.GAP, b.x, 0.01f)
        assertEquals(300f - 20f, b.y, 0.01f)
    }

    @Test
    fun 右侧放不下就翻到左侧() {
        val b = layout("ab", 960f, 300f)
        assertTrue("应翻到图钉左侧：x=${b.x}", b.x + b.w <= 960f)
    }

    @Test
    fun 整体钳进页内() {
        val bottom = layout("ab", 100f, 1399f)
        assertTrue("下边界：y=${bottom.y} h=${bottom.h}", bottom.y + bottom.h <= 1400f + 0.01f)
        val top = layout("ab", 100f, 0f)
        assertTrue("上边界：y=${top.y}", top.y >= -0.01f)
    }

    @Test
    fun 非常驻气泡不留铅笔位() {
        assertEquals(0f, layout("ab", 100f, 300f, sticky = false).edit, 0f)
        assertTrue(layout("ab", 100f, 300f, sticky = true).edit > 0f)
    }

    // ---------- 展开判据 ----------

    private fun note(display: Int, text: String = "正文") = TextNote("n1", 0, 0.5f, 0.5f, text, display)

    @Test
    fun 空正文永不展开() {
        assertFalse(NoteBubbleGeom.visible(note(NOTE_ALWAYS, ""), setOf("n1"), "n1"))
    }

    @Test
    fun 三种模式的展开条件() {
        assertTrue(NoteBubbleGeom.visible(note(NOTE_ALWAYS), emptySet(), null))
        assertFalse(NoteBubbleGeom.visible(note(NOTE_TAP), emptySet(), null))
        assertTrue(NoteBubbleGeom.visible(note(NOTE_TAP), setOf("n1"), null))
        assertTrue(NoteBubbleGeom.visible(note(NOTE_HOVER), emptySet(), "n1"))
        // 悬停模式在没有笔悬停的触摸端降级为点击展开
        assertTrue(NoteBubbleGeom.visible(note(NOTE_HOVER), setOf("n1"), null))
        assertFalse(NoteBubbleGeom.visible(note(NOTE_HOVER), emptySet(), null))
    }

    @Test
    fun 只有常驻气泡有铅笔() {
        assertTrue(NoteBubbleGeom.sticky(note(NOTE_ALWAYS), emptySet()))
        assertTrue(NoteBubbleGeom.sticky(note(NOTE_TAP), setOf("n1")))
        assertFalse(NoteBubbleGeom.sticky(note(NOTE_HOVER), emptySet()))
    }

    @Test
    fun 铅笔热区命中() {
        val b = layout("ab", 100f, 300f)
        val l = NoteBubbleGeom.editHitLeft(b)
        val t = NoteBubbleGeom.editHitTop(b)
        assertTrue(NoteBubbleGeom.hitEdit(b, l + b.edit / 2f, t + b.edit / 2f))
        assertFalse(NoteBubbleGeom.hitEdit(b, l - 1f, t + b.edit / 2f))
        assertFalse(NoteBubbleGeom.hitEdit(layout("ab", 100f, 300f, sticky = false), l, t))
    }
}
