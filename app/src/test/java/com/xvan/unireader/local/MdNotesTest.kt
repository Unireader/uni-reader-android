package com.xvan.unireader.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 笔记的纯逻辑（`MdNotes`）。名字解析的次序与收拾规则逐条对应 Mac `NoteIndex` / `MarkdownLink.linkTarget`
 * （`../spike/markdown-link-test.swift` 的同名几段）。
 */
class MdNotesTest {

    private fun scanOf(notes: List<String>, files: List<String> = emptyList()) =
        MdNotes.Scan(notes.map { MdNotes.Item(it) }, files, emptyList(), MdNotes.Folder("", ""))

    // ---------- linkTarget ----------

    @Test
    fun 收拾别名锚点与转义() {
        assertEquals("极限", MdNotes.linkTarget("极限|别名"))
        assertEquals("极限", MdNotes.linkTarget("极限#小节"))
        assertEquals("极限", MdNotes.linkTarget("极限#小节|别名"))
        assertEquals("极限", MdNotes.linkTarget("极限\\|别名"))      // 表格里转义的竖线
        assertEquals("a[b]", MdNotes.linkTarget("a\\[b\\]"))
        assertEquals("C\\x", MdNotes.linkTarget("C\\x"))          // 不是 Markdown 转义的反斜杠原样留
    }

    // ---------- 名字解析 ----------

    @Test
    fun 按标题与路径解析() {
        val idx = MdNotes.Index(scanOf(listOf("数学/极限.md", "物理/力学.md")))
        assertEquals("数学/极限.md", idx.note("极限"))
        assertEquals("数学/极限.md", idx.note("数学/极限"))
        assertEquals("数学/极限.md", idx.note("极限.md"))
        assertEquals("数学/极限.md", idx.note("别的目录/极限"))   // 路径对不上退回末段
        assertEquals("物理/力学.md", idx.note("力学#牛顿|别名"))
        assertNull(idx.note("不存在"))
    }

    @Test
    fun 忽略大小写与先按原样查() {
        val idx = MdNotes.Index(scanOf(listOf("Linear Algebra.md", "C#入门.md")))
        assertEquals("Linear Algebra.md", idx.note("linear algebra"))
        assertEquals("C#入门.md", idx.note("C#入门"))   // 名字里本来带 # 的不被收拾掉
    }

    @Test
    fun 重名取路径字典序最小的() {
        val idx = MdNotes.Index(scanOf(listOf("b/笔记.md", "a/笔记.md")))
        assertEquals("a/笔记.md", idx.note("笔记"))
    }

    @Test
    fun 附件先按路径再按文件名() {
        val idx = MdNotes.Index(scanOf(emptyList(), listOf("attachments/图.png", "x/y.png")))
        assertEquals("attachments/图.png", idx.file("attachments/图.png"))
        assertEquals("attachments/图.png", idx.file("图.png"))
        assertNull(idx.file("没有.png"))
    }

    // ---------- 显示用的 Markdown ----------

    @Test
    fun 维基链接换成标准链接且显示别名() {
        val segs = MdNotes.segments("看 [[极限|定义]] 与 [[力学]]")
        val md = (segs.single() as MdNotes.Segment.Text).md
        assertTrue(md, md.contains("[定义](${MdNotes.WIKI_SCHEME}${MdNotes.encode("极限")})"))
        assertTrue(md, md.contains("[力学](${MdNotes.WIKI_SCHEME}${MdNotes.encode("力学")})"))
    }

    @Test
    fun 单独一行的图成为图片段() {
        val segs = MdNotes.segments("前\n![[attachments/图.png]]\n后")
        assertEquals(3, segs.size)
        assertEquals("attachments/图.png", (segs[1] as MdNotes.Segment.Image).target)
    }

    @Test
    fun 代码里的维基链接不动() {
        val md = (MdNotes.segments("`[[不是链接]]` 与\n```\n[[也不是]]\n```\n").first() as MdNotes.Segment.Text).md
        assertTrue(md, md.contains("`[[不是链接]]`"))
        assertTrue(md, md.contains("[[也不是]]"))
    }

    // ---------- 文件名 ----------

    @Test
    fun 撞名加序号() {
        assertEquals("a.md", MdNotes.availablePath("a.md", setOf("b.md")))
        assertEquals("a-2.md", MdNotes.availablePath("a.md", setOf("A.md")))
        assertEquals("d/a-3.md", MdNotes.availablePath("d/a.md", setOf("d/a.md", "d/a-2.md")))
        assertEquals("a-b-c", MdNotes.safeFileName("a/b:c"))
    }
}
