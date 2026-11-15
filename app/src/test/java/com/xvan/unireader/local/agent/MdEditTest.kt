package com.xvan.unireader.local.agent

import com.xvan.unireader.local.agent.MdEdit.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** `edit_markdown` 的规则（照 Mac `MCPMarkdownText.apply`）。 */
class MdEditTest {

    private fun err(edits: List<Edit>, text: String): MdEdit.EditError {
        try { MdEdit.apply(edits, text) } catch (e: MdEdit.EditError) { return e }
        fail("应当报错"); throw IllegalStateException()
    }

    @Test
    fun replaceExactlyOnce() {
        val r = MdEdit.apply(listOf(Edit.Replace("b", "B", false)), "a\nb\nc\n")
        assertEquals("a\nB\nc\n", r.text)
        assertEquals(listOf(1), r.counts)
        assertEquals(listOf(2..2), r.changedLines)
    }

    @Test
    fun ambiguousNeedsReplaceAll() {
        val e = err(listOf(Edit.Replace("x", "y", false)), "x\nx\n")
        assertTrue(e.message!!, e.message!!.contains("matches 2 places (lines 1, 2)"))
        val r = MdEdit.apply(listOf(Edit.Replace("x", "yy", true)), "x\nx\n")
        assertEquals("yy\nyy\n", r.text)
        assertEquals(listOf(2), r.counts)
        assertEquals(listOf(1..2), r.changedLines)
    }

    @Test
    fun insertLines() {
        assertEquals("top\na\nb\n", MdEdit.apply(listOf(Edit.Insert(0, "top")), "a\nb\n").text)
        assertEquals("a\nmid\nb\n", MdEdit.apply(listOf(Edit.Insert(1, "mid")), "a\nb\n").text)
        assertEquals("a\nb\nend\n", MdEdit.apply(listOf(Edit.Insert(2, "end")), "a\nb\n").text)
        // 原文没有结尾换行：末尾插入先补换行，不给新内容硬加结尾换行
        assertEquals("a\nb\nend", MdEdit.apply(listOf(Edit.Insert(2, "end")), "a\nb").text)
        assertEquals("first", MdEdit.apply(listOf(Edit.Insert(0, "first")), "").text)
        assertTrue(err(listOf(Edit.Insert(3, "x")), "a\nb\n").message!!.contains("out of range (0…2)"))
    }

    @Test
    fun editsApplyInOrderAllOrNothing() {
        val r = MdEdit.apply(listOf(Edit.Replace("a", "A", false), Edit.Insert(1, "new")), "a\nb\n")
        assertEquals("A\nnew\nb\n", r.text)
        assertEquals(listOf(1..2), r.changedLines)
        val e = err(listOf(Edit.Replace("a", "A", false), Edit.Replace("zzz", "q", false)), "a\nb\n")
        assertEquals(2, e.index)
    }

    @Test
    fun helpfulNotFoundMessages() {
        assertTrue(err(listOf(Edit.Replace("2\tb", "x", false)), "a\nb\n").message!!.contains("line-number prefixes"))
        assertTrue(err(listOf(Edit.Replace("b  c", "x", false)), "a\nb c\n").message!!.contains("near line 2"))
        assertTrue(err(listOf(Edit.Replace("nope", "x", false)), "a\n").message!!.contains("call read_markdown again"))
        assertTrue(err(listOf(Edit.Replace("a", "a", false)), "a\n").message!!.contains("identical"))
    }

    @Test
    fun snippetShowsContext() {
        val text = (1..10).joinToString("\n") { "l$it" } + "\n"
        assertEquals("3\tl3\n4\tl4\n5\tl5\n6\tl6\n7\tl7", MdEdit.snippet(text, listOf(5..5), context = 2))
    }
}
