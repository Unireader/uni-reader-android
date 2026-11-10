package com.xvan.unireader.shared.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习题 / 笔记正文的围栏代码块切分（[MathText.splitBlocks]）：代码单独成块（不换行、横滑着画），
 * 其余整段交给 Markwon（表格 / 公式 / 段落）。
 */
class MathTextTest {

    @Test
    fun 没有围栏整段是Markdown() {
        val r = MathText.splitBlocks("下面是两个并发执行的进程：\n\n| a | b |\n|---|---|\n| 1 | 2 |")
        assertEquals(1, r.size)
        assertTrue(r[0] is MathText.MdBlock)
        assertEquals("下面是两个并发执行的进程：\n\n| a | b |\n|---|---|\n| 1 | 2 |", r[0].text)
    }

    @Test
    fun 围栏切出代码块() {
        val r = MathText.splitBlocks("题：\n```\nint x;\nx=1;\n```\n答：对")
        assertEquals(3, r.size)
        assertTrue(r[0] is MathText.MdBlock)
        assertEquals("题：", r[0].text)
        assertTrue(r[1] is MathText.CodeBlock)
        assertEquals("int x;\nx=1;", r[1].text)
        assertTrue(r[2] is MathText.MdBlock)
        assertEquals("答：对", r[2].text)
    }

    @Test
    fun 围栏带语言名也认() {
        val r = MathText.splitBlocks("```kotlin\nval a = 1\n```")
        assertEquals(1, r.size)
        assertTrue(r[0] is MathText.CodeBlock)
        assertEquals("val a = 1", r[0].text)
    }

    @Test
    fun 波浪围栏同认() {
        val r = MathText.splitBlocks("~~~\ncode\n~~~\n尾")
        assertEquals(2, r.size)
        assertTrue(r[0] is MathText.CodeBlock)
        assertEquals("code", r[0].text)
        assertEquals("尾", r[1].text)
    }

    @Test
    fun 没闭合的围栏当代码到结尾() {
        val r = MathText.splitBlocks("头\n```\nint x;")
        assertEquals(2, r.size)
        assertEquals("头", r[0].text)
        assertTrue(r[1] is MathText.CodeBlock)
        assertEquals("int x;", r[1].text)
    }

    @Test
    fun 代码里的美元符不切给公式() {
        val r = MathText.splitBlocks("$$\nx=1\n$$\n\n```\nif($1) go();\n```")
        assertEquals(2, r.size)
        assertTrue(r[0] is MathText.MdBlock)
        assertEquals("$$\nx=1\n$$", r[0].text)
        assertEquals("if($1) go();", r[1].text)
    }

    @Test
    fun 并排两段代码各成一块() {
        val r = MathText.splitBlocks("```\nP1\n```\n和\n```\nP2\n```")
        assertEquals(3, r.size)
        assertTrue(r[0] is MathText.CodeBlock)
        assertEquals("P1", r[0].text)
        assertEquals("和", r[1].text)
        assertTrue(r[2] is MathText.CodeBlock)
        assertEquals("P2", r[2].text)
    }

    @Test
    fun 代码块内的空行保留() {
        val r = MathText.splitBlocks("```\na\n\nb\n```")
        assertEquals(1, r.size)
        assertEquals("a\n\nb", r[0].text)
    }

    /** `==高亮==` 的认法（[HighlightPlugin.RE]，在行内解析器停在 `=` 上时从当前位置起匹配）。 */
    @Test
    fun 高亮的配对规则() {
        fun at(text: String): String? = HighlightPlugin.RE.matcher(text).let { if (it.lookingAt()) it.group() else null }
        assertEquals("==重点==", at("==重点== 后面"))
        assertEquals("==a b==", at("==a b=="))
        assertEquals("最短配对", "==a==", at("==a== 和 ==b=="))
        assertEquals("里面可以有单个等号", "==x=1==", at("==x=1=="))
        assertEquals(null, at("== 两边有空格 =="))
        assertEquals(null, at("===三个等号"))
        assertEquals(null, at("==没有结尾"))
        assertEquals("不跨行", null, at("==第一行\n第二行=="))
    }
}
