package com.xvan.unireader.local.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.MdNotes
import com.xvan.unireader.local.PdfSource
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.StoreQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 安卓 Agent 的工具（`../docs/ANDROID-AGENT-PLAN.md §5`）对着一个 cacheDir 里新建的真工作区跑：
 * 手写的两页小 PDF（Helvetica，第 1 页 "Hello World" / "Second line"，第 2 页 "hello again"）+ 一个不经界面的假宿主。
 * 写入类工具写完回库里核对行（页码、引文、行框、`source`、颜色），Markdown 工具核对文件。不碰界面、不要 API Key。
 */
@RunWith(AndroidJUnit4::class)
class AgentToolsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: LibraryStore
    private lateinit var q: StoreQueue
    private lateinit var doc: LibDocument
    private lateinit var pdf: PdfSource
    private lateinit var tools: Map<String, AgentTool>
    private var bookmarkPings = 0
    private var treePings = 0

    private fun pdfFile(): File {
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
        for ((i, o) in objs.withIndex()) { offs += sb.length; sb.append("${i + 1} 0 obj\n$o\nendobj\n") }
        val xref = sb.length
        sb.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) sb.append("%010d 00000 n \n".format(o))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return File(dir, "PDFs/t.pdf").apply { parentFile?.mkdirs(); writeBytes(sb.toString().toByteArray(Charsets.ISO_8859_1)) }
    }

    @Before
    fun up() {
        val parent = File(ctx.cacheDir, "agenttools").apply { deleteRecursively(); mkdirs() }
        dir = Workspace.create(parent, "Agent工具")
        store = LibraryStore.open(dir)
        doc = store.findOrCreate("a".repeat(64), "测试书", 2, "PDFs/t.pdf")
        q = StoreQueue(store)
        pdf = PdfSource(ctx, pdfFile(), contentHash = "a".repeat(64), align = null)
        val host = object : AgentHost {
            override val workspaceDir: File get() = dir
            override val workspaceName: String get() = "Agent工具"
            override fun storeQueue() = q
            override fun currentView() = AgentView("pdf", doc.id, doc.title, 0, 2)
            override fun loadedPdf(documentId: String) = if (documentId == doc.id) AgentPdf(pdf, emptyMap()) else null
            override fun openDocument(documentId: String) {}
            override fun gotoPage(documentId: String, page: Int, frac: Float) {}
            override fun writeNotes(documentId: String, what: String, work: (LibraryStore) -> Unit, done: (Throwable?) -> Unit) =
                q.submit(what, { s -> runCatching { work(s) }.exceptionOrNull() }, done)
            override fun bookmarksChanged() { bookmarkPings++ }
            override fun markdownLiveText(rel: String): String? = null
            override fun markdownApply(rel: String, text: String) = false
            override fun markdownTreeChanged(then: () -> Unit) { treePings++; then() }
            override fun openMarkdown(rel: String) {}
        }
        tools = AgentToolset(host).tools.associateBy { it.descriptor.name }
    }

    @After
    fun down() {
        pdf.close()
        q.close()
    }

    private fun call(name: String, args: String): String = runBlocking { tools.getValue(name).run(JSONObject(args)) }

    private fun fails(name: String, args: String): String {
        try { call(name, args) } catch (e: Exception) { return e.message ?: "" }
        fail("$name 应当报错"); return ""
    }

    /** 在库队列上读（工具写库也走它，排在后面 = 读到的是写完之后） */
    private fun <T> read(work: (LibraryStore) -> T): T = runBlocking { q.read("测试读", work) }

    @Test
    fun readTools() {
        val state = call("get_state", "{}")
        assertTrue(state, state.contains("测试书") && state.contains("Page 1 of 2"))
        assertTrue(call("list_documents", "{}").contains(doc.id))
        val p2 = call("read_pages", """{"start":2}""")
        assertTrue(p2, p2.contains("--- Page 2 ---") && p2.contains("hello again"))
        val hits = call("search_text", """{"query":"HELLO"}""")
        assertTrue(hits, hits.contains("2 hit(s)") && hits.contains("page 1") && hits.contains("page 2"))
    }

    @Test
    fun addHighlightLocatesQuote() {
        val out = call("add_highlight", """{"page":1,"quote":"hello  world","color":"Green","style":"underline"}""")
        assertTrue(out, out.startsWith("Highlighted"))
        val n = read { it.notes(doc.id) }.single { it.kind == NoteKind.HIGHLIGHT }
        assertEquals(0, n.page)
        val o = JSONObject(String(n.payload, Charsets.UTF_8))
        assertEquals("hello  world", o.getString("quote"))
        assertEquals("underline", o.getString("style"))
        assertEquals(150, o.getJSONObject("color").getInt("r"))
        val r = o.getJSONArray("rects").getJSONArray(0)
        // 第 1 页上方那行：上沿在 1/8 附近，左边在 50/400
        assertTrue("框 $r", r.getDouble(1) in 0.05..0.15 && r.getDouble(0) in 0.1..0.15)
        assertTrue(n.anchorW > 0)

        assertTrue(fails("add_highlight", """{"page":2,"quote":"Second line"}""").contains("not found on page 2"))
        assertTrue(fails("add_highlight", """{"page":3,"quote":"x"}""").contains("page must be 1…2"))
        assertTrue(fails("add_highlight", """{"page":1,"quote":"Hello","color":"Purple"}""").contains("color must be"))
    }

    @Test
    fun addNoteAnchorsAndMarksAgent() {
        call("add_note", """{"page":1,"quote":"Second line","text":"批注正文 ${'$'}x^2${'$'}"}""")
        call("add_note", """{"page":2,"text":"页顶的一条","display":"always"}""")
        val notes = read { it.notes(doc.id) }.filter { it.kind == NoteKind.TEXT }.sortedBy { it.page }
        assertEquals(2, notes.size)
        val a = JSONObject(String(notes[0].payload, Charsets.UTF_8))
        assertEquals("Second line", a.getString("quote"))
        assertEquals("批注正文 \$x^2\$", a.getString("text"))
        assertEquals("agent", a.getJSONObject("source").getString("kind"))
        assertEquals(AgentToolset.AGENT_NAME, a.getJSONObject("source").getString("provider"))
        assertTrue("贴着下方那行", notes[0].anchorY in 0.68..0.78)
        // 没给 quote / rect：页左上一条横条（同 Mac）
        assertEquals(1, notes[1].page)
        assertEquals(0.05, notes[1].anchorY, 1e-6)
        assertEquals(0.9, notes[1].anchorW, 1e-6)
        assertEquals("always", JSONObject(String(notes[1].payload, Charsets.UTF_8)).optString("display"))
        // 文字笔记读出来认得（list_annotations 也看得到）
        assertTrue(call("list_annotations", "{}").contains("批注正文"))

        assertTrue(fails("add_note", """{"page":1}""").contains("needs text"))
        assertTrue(fails("add_note", """{"page":1,"text":"x","type":"不存在"}""").contains("does not exist"))
    }

    @Test
    fun addBookmark() {
        val out = call("add_bookmark", """{"page":2,"title":"第二页","frac":0.5}""")
        assertTrue(out, out.contains("Added bookmark"))
        val b = read { it.bookmarks(doc.id) }.single()
        assertEquals(1, b.page)
        assertEquals("第二页", b.title)
        assertEquals(0.5f, b.frac, 1e-6f)
        assertEquals(1, bookmarkPings)
        assertTrue(fails("add_bookmark", """{"page":1,"title":"  "}""").contains("title"))
    }

    @Test
    fun markdownCreateEditRead() {
        val out = call("create_markdown", """{"title":"复习/计划?","folder":"数学/第三章","text":"# 计划\n- 第一节\n- 第二节\n"}""")
        val rel = "数学/第三章/复习-计划-.md"
        assertTrue(out, out.contains("note_ref $rel"))
        assertEquals(1, treePings)
        val f = File(MdNotes.root(dir), rel)
        assertEquals("# 计划\n- 第一节\n- 第二节\n", f.readText())
        assertTrue(call("create_markdown", """{"title":"复习/计划?","folder":"数学/第三章"}""").contains("already exists"))
        assertTrue(call("list_markdown_notes", "{}").contains(rel))

        val e1 = call("edit_markdown", """{"note_ref":"$rel","old_text":"- 第二节","new_text":"- 第二节 ✓"}""")
        assertTrue(e1, e1.contains("1 place(s) changed") && e1.contains("3\t- 第二节 ✓"))
        call("edit_markdown", """{"note_ref":"$rel","edits":[{"insert_line":3,"new_text":"- 第三节"},{"old_text":"# 计划","new_text":"# 复习计划"}]}""")
        assertEquals("# 复习计划\n- 第一节\n- 第二节 ✓\n- 第三节\n", f.readText())
        // edits 当字符串传（schema 里它是 string）也认
        call("edit_markdown", """{"note_ref":"$rel","edits":"[{\"old_text\":\"- 第三节\",\"new_text\":\"- 第三节（下周）\"}]"}""")
        assertTrue(f.readText().contains("- 第三节（下周）"))

        assertTrue(fails("edit_markdown", """{"note_ref":"$rel","old_text":"- 第","new_text":"x"}""").contains("matches 3 places"))
        assertTrue(fails("edit_markdown", """{"note_ref":"../../x.md","old_text":"a","new_text":"b"}""").contains("inside the Notes folder"))
        val r = call("read_markdown", """{"note_ref":"$rel","start_line":2,"end_line":3}""")
        assertTrue(r, r.contains("2\t- 第一节") && r.contains("3\t- 第二节 ✓") && !r.contains("1\t#"))
    }

    @Test
    fun memories() {
        val saved = call("save_memory", """{"content":"在复习第三章"}""")
        val id = Regex("id ([0-9A-F-]{36})").find(saved)!!.groupValues[1]
        assertEquals(id, id.uppercase())
        assertTrue(call("list_memories", "{}").contains("在复习第三章"))
        call("save_memory", """{"content":"在复习第四章","id":"${id.take(8)}"}""")
        val all = read { it.agentMemories() }
        assertEquals(1, all.size)
        assertEquals("在复习第四章", all[0].content)
        assertEquals(AgentToolset.AGENT_NAME, all[0].agent)
        assertTrue(call("list_memories", """{"query":"第三章"}""").contains("No memory matches"))
    }
}
