package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展开气泡的几何（纯计算）。正文高度由宿主排版（真机上是 Markwon + StaticLayout），这里用一个
 * 「每行 = 字号 × 行高比、要几行给几行」的假排版，断言不依赖真机字体。
 */
class NoteBubbleGeomTest {

    /** 假排版：固定 [lines] 行，超出 maxLines 截到 maxLines */
    private fun fakeHeight(lines: Int): (Float, Float, Int) -> Float =
        { _, fs, max -> minOf(lines, max) * fs * NoteBubbleGeom.LINE_H }

    // ---------- 布局 ----------

    /** 页宽 1000、页在视口 (0,0)-(1000,1400)；图钉半径 20 */
    private fun layout(
        text: String, pinX: Float, pinY: Float, sticky: Boolean = true, card: NoteCard? = null, lines: Int = 1,
    ) = NoteBubbleGeom.layout(
        1000f, pinX, pinY, 20f, 0f, 0f, 1000f, 1400f, sticky, card, fakeHeight(if (text.isEmpty()) 0 else lines),
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

    // ---------- 手动摆过的卡片（Mac `NoteCard`） ----------

    private val unit = 1000f / NoteBubbleGeom.REF_PAGE_W

    @Test
    fun 卡片按存的偏移落位() {
        // 偏移 (100, 200) 点 × unit；离图钉足够远，不触发挪开
        val b = layout("ab", 300f, 300f, card = NoteCard(100f, 200f, null, null))
        assertEquals(300f + 100f * unit, b.x, 0.01f)
        assertEquals(300f + 200f * unit, b.y, 0.01f)
        assertEquals(1000f * NoteBubbleGeom.WIDTH, b.w, 0.01f)   // 宽没动过 → 自动宽
    }

    @Test
    fun 卡片宽按存的数换算且不小于最小宽() {
        assertEquals(400f * unit, layout("ab", 300f, 300f, card = NoteCard(60f, 60f, 400f, null)).w, 0.01f)
        val tiny = layout("ab", 300f, 300f, card = NoteCard(60f, 60f, 1f, null))
        assertTrue("最小宽：${tiny.w}", tiny.w >= NoteBubbleGeom.CARD_MIN_W * unit - 0.01f)
    }

    @Test
    fun 卡片高度上限截行() {
        val fs = 1000f * NoteBubbleGeom.FONT
        val pad = fs * NoteBubbleGeom.PAD
        val capH = pad * 2f + 3.5f * fs * NoteBubbleGeom.LINE_H   // 放得下 3 整行
        val b = layout("ab", 300f, 300f, card = NoteCard(60f, 60f, null, capH / unit), lines = 8)
        assertEquals(3, b.maxLines)
        assertTrue("高不超上限：${b.h} > $capH", b.h <= capH + 0.01f)
        // 内容比上限矮：收到内容高
        val short = layout("ab", 300f, 300f, card = NoteCard(60f, 60f, null, capH / unit), lines = 1)
        assertEquals(pad * 2f + fs * NoteBubbleGeom.LINE_H, short.h, 0.01f)
    }

    @Test
    fun 卡片压住自己的图钉就挪开() {
        // 偏移 (0,0)：左上角正好在图钉中心 → 压住，挪到图钉右边（离原位最近）
        val b = layout("ab", 300f, 300f, card = NoteCard(0f, 0f, null, null))
        val clearance = 20f + b.fs * NoteBubbleGeom.GAP
        assertEquals(300f + clearance, b.x, 0.01f)
        assertEquals(300f, b.y, 0.01f)
    }

    @Test
    fun 卡片钳进页内() {
        val b = layout("ab", 900f, 1300f, card = NoteCard(500f, 500f, null, null))
        assertTrue(b.x + b.w <= 1000f + 0.01f)
        assertTrue(b.y + b.h <= 1400f + 0.01f)
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
