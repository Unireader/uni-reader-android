package com.xvan.unireader.shared

import com.xvan.unireader.shared.exercise.MathText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引用里的块公式 + Obsidian callout（`../docs/MARKDOWN-NOTES-PLAN.md §23`，规则同 Mac 引擎 `QuoteSyntax.swift`）。 */
class MdCalloutTest {

    @Test
    fun callout切成单独一段() {
        val segs = MdDisplay.segments("前\n\n> [!WARNING]- 小心 [[极限]]\n> 正文 \$x\$\n> > 嵌套\n\n后")
        assertEquals(3, segs.size)
        val c = segs[1] as MdDisplay.Segment.Callout
        assertEquals("warning", c.type)
        assertEquals("WARNING", c.typeText)
        assertEquals("小心 [[极限]]", c.title)
        assertEquals('-', c.fold)
        assertEquals("正文 \$x\$\n> 嵌套", c.body)
        assertTrue((segs[2] as MdDisplay.Segment.Text).md.contains("后"))
    }

    @Test
    fun 不是callout的引用照旧是文字() {
        // 引用中间的 [!note] 不算；[!note]x 不算
        val segs = MdDisplay.segments("> 引用\n> [!note]\n\n> [!note]x\n")
        assertEquals(1, segs.size)
        assertTrue(segs[0] is MdDisplay.Segment.Text)
    }

    @Test
    fun 类型表与默认标题() {
        assertEquals("question", MdDisplay.Callouts.canonical("FAQ"))
        assertEquals("note", MdDisplay.Callouts.canonical("whatever"))
        assertEquals("purple", MdDisplay.Callouts.colorName("example"))
        assertEquals("Faq", MdDisplay.Callouts.defaultTitle("faq"))
        assertNull(MdDisplay.Callouts.header("[!note]x"))
        assertEquals('+', MdDisplay.Callouts.header("[!tip]+ 标题")?.second)
    }

    @Test
    fun 引用里的单行块公式拆成三行留在引用里() {
        assertEquals("> 得\n> \$\$\n> a+b\n> \$\$\n> 故", MathText.normalize("> 得\n> \$\$a+b\$\$\n> 故"))
    }

    @Test
    fun 引用里的多行块公式() {
        assertEquals("> \$\$\n> \\frac{a}{b}\n> = c\n> \$\$\n> 完",
            MathText.normalize("> \$\$\n> \\frac{a}{b}\n> = c\n> \$\$\n> 完"))
        // 没关上的：原样（行内照常改写）
        assertEquals("> \$\$x\n\n后", MathText.normalize("> \$\$x\n\n后"))
    }

    @Test
    fun callout第一行在气泡和习题里是粗体标题() {
        assertEquals("> **Example**\n> 正文", MathText.normalize("> [!example]\n> 正文"))
        assertEquals("> **标题 \$\$x\$\$**\n> b", MathText.normalize("> [!tip]- 标题 \$x\$\n> b"))
        // 引用中间那行不改
        assertEquals("> a\n> [!note]", MathText.normalize("> a\n> [!note]"))
    }

    @Test
    fun 引用外的写法不变() {
        assertEquals("\$\$\nx\n\$\$\n行内 \$\$y\$\$", MathText.normalize("\$\$x\$\$\n行内 \$y\$"))
    }
}
