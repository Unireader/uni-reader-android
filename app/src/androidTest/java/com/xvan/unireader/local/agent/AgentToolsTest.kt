package com.xvan.unireader.local.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xvan.unireader.local.MdNotes
import com.xvan.unireader.local.PdfSource
import com.xvan.unireader.local.Trash
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
    private var deletePings = 0
    private var exercisePings = 0
    private var libraryPings = 0
    private val opened = ArrayList<String>()

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
            override fun annotationsChanged(documentId: String) { deletePings++ }
            override fun string(id: Int, vararg args: Any): String = ctx.getString(id, *args)
            override fun exercisesChanged() { exercisePings++ }
            override fun openExercises(scopeKey: String, exerciseId: String?) { opened += "$scopeKey|$exerciseId" }
            override fun markdownRename(rel: String, name: String, folder: String?, then: (String?) -> Unit) {
                val item = MdNotes.Item(rel)
                val newRel = (folder ?: item.folder).trim('/').let { if (it.isEmpty()) "" else "$it/" } + MdNotes.safeFileName(name) + ".md"
                val dst = MdNotes.file(dir, newRel)
                if (dst.exists()) return then(null)
                dst.parentFile?.mkdirs()
                then(if (MdNotes.file(dir, rel).renameTo(dst)) newRel else null)
            }
            override fun libraryChanged() { libraryPings++ }
            override fun documentToc(documentId: String) = if (documentId == doc.id) pdf.toc else null
            override fun markdownDelete(rel: String, then: (Boolean) -> Unit) {
                val bin = File(MdNotes.root(dir), ".trash").apply { mkdirs() }
                then(MdNotes.file(dir, rel).renameTo(File(bin, MdNotes.Item(rel).title + ".md")))
            }
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

    @Test
    fun deleteAnnotationsArchivesFirst() {
        call("add_highlight", """{"page":1,"quote":"Hello World"}""")
        call("add_bookmark", """{"page":2,"title":"第二页"}""")
        call("add_note", """{"page":1,"quote":"Second line","text":"留着"}""")
        val listed = call("list_annotations", "{}")
        val hl = Regex("\\[highlight ([0-9A-F]{8})]").find(listed)!!.groupValues[1]
        val bm = Regex("\\[bookmark ([0-9A-F]{8})]").find(listed)!!.groupValues[1]

        // 有一个 id 对不上 → 一条都不删、回收站里什么都没有
        val err = fails("delete_annotations", """{"ids":["$hl","DEADBEEF"]}""")
        assertTrue(err, err.contains("DEADBEEF") && err.contains("Nothing was deleted"))
        assertEquals(3, read { it.notes(doc.id) }.count { it.kind in AgentToolset.ANNOTATION_KINDS })
        assertTrue(Trash.scan(dir).isEmpty())

        val out = call("delete_annotations", """{"ids":["$hl","${bm.lowercase()}"]}""")
        assertTrue(out, out.startsWith("Deleted 2 annotation(s)"))
        val left = read { it.notes(doc.id) }.filter { it.kind in AgentToolset.ANNOTATION_KINDS }
        assertEquals(listOf(NoteKind.TEXT), left.map { it.kind })
        assertEquals(1, deletePings)
        val e = Trash.scan(dir).single()
        assertEquals(Trash.KIND_ANNOTATIONS, e.manifest.kind)
        assertEquals(doc.id, e.manifest.documentId)
        assertEquals(1, e.manifest.counts.highlight)
        assertEquals(1, e.manifest.counts.bookmark)
        assertEquals(ctx.getString(com.xvan.unireader.R.string.agent_trash_and_more, "Hello World", 1), e.manifest.title)

        // 从回收站放回去：两条回来、条目没了
        read { s -> Trash.restore(s, e) }
        assertEquals(3, read { it.notes(doc.id) }.count { it.kind in AgentToolset.ANNOTATION_KINDS })
        assertTrue(Trash.scan(dir).isEmpty())
    }

    @Test
    fun deleteMemoryArchivesFirst() {
        val a = Regex("id ([0-9A-F-]{36})").find(call("save_memory", """{"content":"第一条\n第二行"}"""))!!.groupValues[1]
        call("save_memory", """{"content":"留着的"}""")
        assertTrue(fails("delete_memory", """{"ids":["${a.take(8)}","0000000"]}""").contains("Nothing was deleted"))
        assertEquals(2, read { it.agentMemories() }.size)

        assertEquals("Moved 1 memory(ies) to the Trash.", call("delete_memory", """{"ids":["${a.take(8)}"]}"""))
        assertEquals(listOf("留着的"), read { it.agentMemories() }.map { it.content })
        val e = Trash.scan(dir).single()
        assertEquals(Trash.KIND_AGENT_MEMORIES, e.manifest.kind)
        assertEquals("第一条", e.manifest.title)
        assertEquals(1, e.manifest.counts.agentMemory)

        read { s -> Trash.restore(s, e) }
        assertEquals(2, read { it.agentMemories() }.size)
    }

    @Test
    fun conversations() {
        assertEquals("No conversations are saved in this workspace.", call("list_conversations", "{}"))
        read { s ->
            fun turn(conv: String, i: Int, at: String) = com.xvan.unireader.local.store.LibAgentTurn(
                "T$conv$i", conv, "unireader-android", "", "问题 $i\n第二行", "回答 $i", at, at,
            )
            for (i in 1..3) s.insertAgentTurn(turn("A", i, "2026-11-16T08:0$i:00Z"))
            s.insertAgentTurn(turn("B", 1, "2026-11-16T09:00:00Z"))
            s.insertAgentTurn(turn("A", 1, "2030-01-01T00:00:00Z"))   // 同 id 不覆盖（只追加）
        }
        val list = call("list_conversations", "{}").lines()
        assertEquals(2, list.size)
        assertTrue(list[0], list[0].contains("id B") && list[0].contains("1 turns"))
        assertTrue(list[1], list[1].contains("问题 1 第二行") && list[1].contains("3 turns"))

        val page1 = call("read_conversation", """{"id":"A","limit":2}""")
        assertTrue(page1, page1.contains("## Turn 1") && page1.contains("**unireader-android:** 回答 2") && page1.endsWith("(more: offset 3)"))
        val page2 = call("read_conversation", """{"id":"A","offset":3}""")
        assertTrue(page2, page2.startsWith("## Turn 3") && !page2.contains("more:"))
        assertTrue(fails("read_conversation", """{"id":"NOPE"}""").contains("list_conversations"))
    }

    @Test
    fun agentContexts() {
        assertEquals("No agent contexts are saved in this workspace.", call("list_agent_contexts", "{}"))
        assertTrue(fails("save_agent_context", """{"title":"x","summary":"  "}""").contains("non-empty summary"))
        val saved = call("save_agent_context", """{"title":"## **高数复习**","summary":"  做到第三章\n下一步：习题  "}""")
        assertEquals("Saved the agent context “高数复习”.", saved)
        val c = read { it.agentContexts() }.single()
        assertEquals(c.id, c.id.uppercase())
        assertEquals("做到第三章\n下一步：习题", c.summary)
        assertEquals(AgentToolset.AGENT_NAME, c.agent)

        val listed = call("list_agent_contexts", "{}")
        assertTrue(listed, listed.startsWith("1 agent context(s)") && listed.contains("id ${c.id}"))
        assertEquals("# 高数复习\n\n做到第三章\n下一步：习题", call("read_agent_context", """{"id":"${c.id.take(8).lowercase()}"}"""))

        // 带 id 改：没变不写（updated_at 不动），变了才写
        call("save_agent_context", """{"id":"${c.id}","title":"高数复习","summary":"做到第三章\n下一步：习题"}""")
        assertEquals(c.updatedAt, read { it.agentContexts() }.single().updatedAt)
        assertEquals("Updated the agent context “高数复习”.", call("save_agent_context", """{"id":"${c.id.take(8)}","title":"高数复习","summary":"做到第四章"}"""))
        val after = read { it.agentContexts() }.single()
        assertEquals("做到第四章", after.summary)
        assertEquals(c.createdAt, after.createdAt)
        assertTrue(fails("read_agent_context", """{"id":"NOPE1234"}""").contains("list_agent_contexts"))
    }

    @Test
    fun searchWholeWorkspace() {
        // 第 1 页有 OCR（一句话被换行切开），第 2 页没有 → 书开在「标签」里，用 PDF 自带文字层补
        val ocr = """{"runs":[{"text":"矛盾的普","x":0.1,"y":0.1,"w":0.3,"h":0.02},{"text":"遍性是指矛盾存在于一切事物","x":0.1,"y":0.13,"w":0.6,"h":0.02}]}"""
        store.withMirrorDb { db ->
            db.exec(
                "INSERT INTO ocr_page(content_hash,page,provider,payload,lang,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf<Any?>("a".repeat(64), 0, "paddle-http", ocr.toByteArray(), "ch", "2026-11-16T10:00:00.000Z"),
            )
        }
        call("create_markdown", """{"title":"哲学","text":"# 唯物辩证法\n\n## 矛盾\n矛盾是事物发展的动力\n"}""")
        call("save_memory", """{"content":"在复习矛盾论"}""")
        read { s ->
            s.insertAgentTurn(com.xvan.unireader.local.store.LibAgentTurn("T1", "C1", "unireader-android", "", "讲讲矛盾", "矛盾有普遍性与特殊性", "2026-11-16T09:00:00.000Z", "2026-11-16T09:00:00.000Z"))
        }

        val r1 = call("search", """{"query":"矛盾的普遍性"}""")
        assertTrue(r1, r1.lines()[1].startsWith("1. [PDF] “测试书” p.1"))
        assertTrue(r1, r1.contains("[矛盾的普 遍性]"))

        val r2 = call("search", """{"query":"hello again"}""")
        assertTrue(r2, r2.contains("[PDF] “测试书” p.2"))

        val r3 = call("search", """{"query":"矛盾"}""")
        for (k in listOf("[PDF]", "[Note] “哲学” line 3 § 矛盾 (note_ref 哲学.md)", "[Memory]", "[Conversation] “讲讲矛盾” turn 1 (conversation_id C1)")) {
            assertTrue("$k 不在结果里：$r3", r3.contains(k))
        }
        val r4 = call("search", """{"query":"矛盾","kinds":["memory"]}""")
        assertTrue(r4, r4.startsWith("1 result(s)") && r4.contains("[Memory]"))

        val r5 = call("search", """{"query":"没有这个词 动力"}""")
        assertTrue(r5, r5.contains("any word") && r5.contains("[Note]"))
        assertEquals("No results for “完全没有”.", call("search", """{"query":"完全没有"}"""))
        assertTrue(fails("search", """{"query":"x","kinds":["video"]}""").contains("kinds must be among"))
    }

    /** 量速度（不断言快慢，只打日志 `UniReader/SearchPerf`）：2000 页 × 约 800 字的 OCR，首次 / 缓存后各搜一次。 */
    @Test
    fun searchTiming() {
        val bigDoc = read { s -> s.findOrCreate("b".repeat(64), "大书", 2000, "PDFs/big.pdf") }
        val line = "进程是程序的一次执行过程，是系统进行资源分配和调度的一个独立单位。线程是处理机调度的基本单位。"
        store.withMirrorDb { db ->
            db.transaction {
                for (pg in 0 until 2000) {
                    val runs = (0 until 16).joinToString(",") { i -> """{"text":"$line 第${pg}页$i","x":0.1,"y":${0.05 + i * 0.05},"w":0.8,"h":0.02}""" }
                    db.exec(
                        "INSERT INTO ocr_page(content_hash,page,provider,payload,lang,created_at) VALUES(?,?,?,?,?,?)",
                        arrayOf<Any?>("b".repeat(64), pg, "paddle-http", """{"runs":[$runs]}""".toByteArray(), "ch", "2026-11-16T10:00:00.000Z"),
                    )
                }
            }
        }
        fun timed(q: String): Long { val t0 = System.nanoTime(); call("search", """{"query":"$q","document_ids":["${bigDoc.id}"]}"""); return (System.nanoTime() - t0) / 1_000_000 }
        val first = timed("第1999页15")
        val cached = timed("调度 独立单位")
        val cached2 = timed("第1999页15")
        android.util.Log.i("UniReader/SearchPerf", "2000 页：首次 ${first}ms，缓存后 ${cached}ms / ${cached2}ms")
        assertTrue(call("search", """{"query":"第1999页15"}""").contains("p.2000"))
    }

    @Test
    fun updateAnnotations() {
        call("add_highlight", """{"page":1,"quote":"Hello World"}""")
        call("add_bookmark", """{"page":2,"title":"旧名"}""")
        call("add_note", """{"page":1,"quote":"Second line","text":"旧正文"}""")
        val listed = call("list_annotations", "{}")
        fun idOf(k: String) = Regex("\\[$k ([0-9A-F]{8})]").find(listed)!!.groupValues[1]
        val hl = idOf("highlight"); val bm = idOf("bookmark"); val nt = idOf("note")

        // 有一条不合规（书签没有 color）→ 整批不改
        val err = fails("update_annotations", """{"updates":[{"id":"$hl","color":"Green"},{"id":"$bm","color":"Blue"}]}""")
        assertTrue(err, err.contains("a bookmark has no color") && err.endsWith("Nothing was changed."))
        assertTrue(fails("update_annotations", """{"updates":[{"id":"$hl","color":"none"}]}""").contains("cannot be cleared"))

        val out = call("update_annotations", """{"updates":[
            {"id":"$hl","color":"Green","style":"underline"},
            {"id":"$bm","title":" 新名 "},
            {"id":"${nt.lowercase()}","text":"新正文","display":"always"}]}""")
        assertTrue(out, out.startsWith("Changed 3 annotation(s)"))
        val rows = read { it.notes(doc.id) }.associateBy { it.id.take(8) }
        fun js(id: String) = JSONObject(String(rows.getValue(id).payload, Charsets.UTF_8))
        assertEquals(150, js(hl).getJSONObject("color").getInt("r"))
        assertEquals("underline", js(hl).getString("style"))
        assertEquals("Hello World", js(hl).optString("quote"))           // 没要改的键原样留着
        assertEquals("新名", js(bm).getString("title"))
        assertEquals("新正文", js(nt).getString("text"))
        assertEquals("always", js(nt).getString("display"))
        assertEquals("agent", js(nt).getJSONObject("source").getString("kind"))
        assertTrue(deletePings >= 1)
    }

    @Test
    fun exercises() {
        store.withMirrorDb { db ->
            fun ex(id: String, kind: Int, seq: Int, payload: String) = db.exec(
                "INSERT INTO exercise(id,document_id,page,seq,kind,number,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, doc.id, 0, seq, kind, "${seq + 1}", payload.toByteArray(), "2026-11-01T00:00:00.000Z", "2026-11-01T00:00:00.000Z"),
            )
            ex("EXCHOICE01", 0, 0, """{"stem":"选哪个","options":[{"key":"A","text":"甲"},{"key":"B","text":"乙"}],"answer":["B"]}""")
            ex("EXFILL0001", 3, 1, """{"stem":"1+1=__，2+2=__","options":[],"answer":["2","4"],"blanks":2,"accept":[["2.0"],[]]}""")
            ex("EXSUBJ0001", 4, 2, """{"stem":"证明根号 2 是无理数","options":[],"answer":["反证法……"]}""")
        }
        val ids = read { s ->
            listOf(
                s.recordExerciseAttempt("EXCHOICE01", "B", true, 1000),
                s.recordExerciseAttempt("EXFILL0001", com.xvan.unireader.local.exercise.Exercise.fillAnswerString(listOf("2", "5")), null, 1000),
                s.recordExerciseAttempt("EXSUBJ0001", "设根号 2 = p/q……", null, 1000),
            )
        }
        val (aChoice, aFill, aSubj) = ids

        val listed = call("list_exercises", "{}")
        assertTrue(listed, listed.startsWith("3 exercise(s) in “测试书”"))
        assertTrue(listed, listed.contains("Answer: (1) 2 [also: 2.0]; (2) 4") && listed.contains("A. 甲") && listed.contains("1 attempt(s), 1 right"))

        val ungraded = call("list_exercise_attempts", """{"ungraded_only":true}""")
        assertTrue(ungraded, ungraded.startsWith("2 answer(s)") && ungraded.contains("blank matches: true, false") && ungraded.contains("设根号 2"))

        // 选择题不能批改 → 整批不写
        val err = fails("grade_exercise_attempts", """{"grades":[{"attempt_id":"$aFill","correct":false},{"attempt_id":"$aChoice","correct":false}]}""")
        assertTrue(err, err.contains("choice question") && err.contains("nothing was saved"))
        assertTrue(call("list_exercise_attempts", """{"ungraded_only":true}""").startsWith("2 answer(s)"))

        val graded = call("grade_exercise_attempts", """{"grades":[
            {"attempt_id":"${aFill.take(8)}","correct":false,"feedback":"第二空应为 4"},
            {"attempt_id":"$aSubj","correct":true,"feedback":"思路对"}]}""")
        assertTrue(graded, graded.startsWith("Graded 2 answer(s)") && graded.contains("wrong (by agent)") && graded.contains("Feedback: 第二空应为 4"))
        assertEquals(1, exercisePings)
        assertTrue(read { it.exercise("EXFILL0001")!!.wrong })
        assertTrue(!read { it.exercise("EXSUBJ0001")!!.wrong })
        assertEquals("No ungraded fill / subjective answers.", call("list_exercise_attempts", """{"ungraded_only":true}"""))

        call("open_exercises", """{"exercise_id":"EXFILL00"}""")
        call("open_exercises", """{"list":"mistakes"}""")
        assertEquals(listOf("doc:${doc.id}|EXFILL0001", "wrong|null"), opened)
        assertTrue(fails("open_exercises", """{"list":"mistakes","exercise_id":"EXSUBJ0001"}""").contains("not in that group"))
    }

    @Test
    fun schedule() {
        val today = com.xvan.unireader.local.schedule.ScheduleDay.today()
        val tomorrow = com.xvan.unireader.local.schedule.ScheduleDay.adding(1, today)!!
        val yesterday = com.xvan.unireader.local.schedule.ScheduleDay.adding(-1, today)!!
        assertTrue(fails("add_schedule_items", """{"items":[{"title":"x","day":"2026-02-30"}]}""").contains("YYYY-MM-DD"))
        val added = call("add_schedule_items", """{"plan":{"title":"第三章复习","goal":"两周内"},"items":[
            {"title":"看 3.1","day":"$yesterday","duration_minutes":60,"detail":"[[测试书#page=1]]"},
            {"title":"做习题","day":"$today","start":"09:30"},
            {"title":"再看一遍","day":"$today"},
            {"title":"以后再说"}]}""")
        val planId = Regex("to plan ([0-9A-F-]{36})").find(added)!!.groupValues[1]
        val ids = Regex("Item ids: (.*)$").find(added)!!.groupValues[1].split(", ")
        assertEquals(4, ids.size)

        val listed = call("list_schedule", "{}")
        assertTrue(listed, listed.contains("- 第三章复习 (plan_id $planId): 0/4 done") && listed.contains("goal: 两周内"))
        assertTrue(listed, listed.contains("看 3.1 (1 h) — overdue") && listed.contains("[ ] 09:30 做习题") && listed.contains("No day yet"))
        // 不定时的排在定了时间的前面（ORDER BY start_min：-1 在前）
        assertTrue(listed, listed.indexOf("再看一遍") < listed.indexOf("09:30 做习题"))

        val upd = call("update_schedule_items", """{"items":[
            {"item_id":"${ids[0].take(8)}","day":"$tomorrow"},
            {"item_id":"${ids[1]}","done":true,"result":"做完了"},
            {"item_id":"${ids[2]}","title":"再看一遍"}],
            "plans":[{"plan_id":"$planId","goal":"三周内"}]}""")
        assertEquals("Changed 2 item(s) (1 already so), 1 plan(s).", upd)
        val after = call("list_schedule", """{"plan_id":"${planId.take(8)}"}""")
        assertTrue(after, after.contains("1/4 done") && after.contains("[x] 09:30 做习题") && after.contains("result: 做完了") && after.contains("goal: 三周内"))
        assertTrue(after, !after.contains("overdue"))
        assertTrue(fails("update_schedule_items", """{"items":[{"item_id":"${ids[3]}","day":"bad"},{"item_id":"${ids[2]}","done":true}]}""").contains("YYYY-MM-DD"))
        assertTrue(call("list_schedule", """{"status":"done"}""").contains("做习题"))

        assertEquals("Moved 4 item(s) and 1 plan(s) to the Trash.", call("delete_schedule", """{"plan_ids":["$planId"]}"""))
        assertTrue(call("list_schedule", "{}").contains("No items."))
        val e = Trash.scan(dir).single()
        assertEquals(Trash.KIND_SCHEDULE, e.manifest.kind)
        assertEquals("第三章复习", e.manifest.title)
        assertEquals(4, e.manifest.counts.scheduleItem)
        read { s -> Trash.restore(s, e) }
        assertTrue(call("list_schedule", """{"plan_id":"$planId"}""").contains("1/4 done"))
    }

    @Test
    fun markdownRenameMoveDelete() {
        call("create_markdown", """{"title":"极限","text":"# 极限\n"}""")
        call("create_markdown", """{"title":"目录","text":"见 [[极限]] 与 [[极限#定义|定义]]，还有 [[别的]]\n"}""")
        call("create_markdown", """{"title":"已有","text":""}""")
        assertTrue(fails("rename_markdown", """{"note_ref":"极限.md","title":"已有"}""").contains("Nothing was changed"))

        // 挪目录：名字不变，[[极限]] 照样认得 → 不报断链
        val moved = call("rename_markdown", """{"note_ref":"极限.md","folder":"数学"}""")
        assertTrue(moved, moved.startsWith("Renamed 极限.md → 数学/极限.md") && moved.contains("No [[links]]"))
        // 改名：两处 [[极限…]] 都断
        val renamed = call("rename_markdown", """{"note_ref":"数学/极限.md","title":"函数极限"}""")
        assertTrue(renamed, renamed.contains("→ 数学/函数极限.md") && renamed.contains("- 目录.md: [[极限]]") && renamed.contains("- 目录.md: [[极限#定义]]"))
        assertEquals("见 [[极限]] 与 [[极限#定义|定义]]，还有 [[别的]]\n", File(MdNotes.root(dir), "目录.md").readText())   // 别人的正文一个字不改

        val del = call("delete_markdown", """{"note_ref":"已有.md"}""")
        assertTrue(del, del.startsWith("Moved 已有.md to Notes/.trash") && del.contains("No [[links]]"))
        assertTrue(File(MdNotes.root(dir), ".trash/已有.md").isFile)
        assertTrue(fails("open_markdown", """{"note_ref":"已有.md"}""").contains("list_markdown_notes"))
        assertEquals("Opened 目录.md.", call("open_markdown", """{"note_ref":"目录.md"}"""))
    }

    @Test
    fun documentInfoAndUpdate() {
        val info = call("get_document", "{}")
        assertTrue(info, info.startsWith("“测试书” (document_id ${doc.id}): 2 pages") && info.contains("OCR text on 0 page(s)") && info.contains("no outline"))

        assertTrue(fails("update_document", "{}").contains("title and/or group"))
        val out = call("update_document", """{"title":"新书名","group":"操作系统"}""")
        assertTrue(out, out.contains("- “新书名” · group 操作系统") && out.endsWith("Groups now: 操作系统"))
        assertEquals(1, libraryPings)
        val d = read { it.allDocuments() }.single()
        assertEquals("新书名", d.title)
        assertEquals("操作系统", d.group)
        call("update_document", """{"document_ids":["${doc.id.take(8)}"],"group":""}""")
        assertEquals("", read { it.allDocuments() }.single().group)
    }
}
