package com.xvan.unireader.local.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import com.xvan.unireader.local.MdNotes
import com.xvan.unireader.local.PdfSource
import com.xvan.unireader.local.store.LibAgentMemory
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.TextNotePayload
import com.xvan.unireader.shared.TextRun
import com.xvan.unireader.shared.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Agent 碰阅读界面的唯一入口（`../docs/ANDROID-AGENT-PLAN.md §4`），由 `ReaderActivity` 实现。
 * 同 Mac 的 `MCPFacade`：工具只认这个接口，不直接摸 Activity 的字段。**全部方法只在主线程调。**
 * 页码一律 0 起（对模型说的 1 起只在 [AgentToolset] 里换算，同 Mac 的 `PageNo`）。
 */
interface AgentHost {
    val workspaceDir: File?
    val workspaceName: String
    fun storeQueue(): StoreQueue?

    /** 当前标签：null = 没开着标签 */
    fun currentView(): AgentView?

    /** 已在标签里装载好的 PDF（Pdfium 实例 + 画布上的 OCR 行）；没装载 = null */
    fun loadedPdf(documentId: String): AgentPdf?

    /** 打开 / 切到这篇（没开着就开新标签）。 */
    fun openDocument(documentId: String)

    /** 跳到某篇某页某处（不是当前标签就先切过去）；记进跳转历史。 */
    fun gotoPage(documentId: String, page: Int, frac: Float)

    // ---- 写入（第二批，`../docs/ANDROID-AGENT-PLAN.md §5.2`）----

    /**
     * 往某篇写批注 / 高亮：那篇在标签里装载着 → 走画布（进撤销栈、马上显示，同界面新建）；否则直接写库。
     * [done] 在主线程调，参数 = 写库时的异常（null = 成功）。
     */
    fun writeNotes(documentId: String, what: String, work: (LibraryStore) -> Unit, done: (Throwable?) -> Unit)

    /** 书签变了：刷抽屉里的目录 / 书签。 */
    fun bookmarksChanged()

    /** Markdown 笔记此刻编辑器里的正文（开着且已读进来）；否则 null，读文件。 */
    fun markdownLiveText(rel: String): String?

    /** 整份换掉一篇 Markdown 笔记的正文：开着就换编辑器里的字并马上存，返回 true；没开着返回 false（调用方写文件）。 */
    fun markdownApply(rel: String, text: String): Boolean

    /** `Notes/` 里多了 / 少了文件：重扫目录树、刷抽屉，扫完调 [then]。 */
    fun markdownTreeChanged(then: () -> Unit)

    /** 在标签里打开一篇 Markdown 笔记。 */
    fun openMarkdown(rel: String)

    // ---- 删除（第三批，`../docs/ANDROID-AGENT-PLAN.md §5.3`）----

    /** 这篇的批注在库里被删了（已归档进回收站）：开着的标签从库里重读批注 / 图片笔记、刷书签。 */
    fun annotationsDeleted(documentId: String)

    /** 界面文案（回收站条目名要随系统语言）。 */
    fun string(id: Int, vararg args: Any): String
}

data class AgentView(
    /** pdf / board / markdown / exercise */
    val kind: String,
    val documentId: String,
    val title: String,
    /** 视口顶所在页（0 起），只有 PDF 有意义 */
    val page: Int,
    val pageCount: Int,
)

class AgentPdf(val pdf: PdfSource, val ocr: Map<Int, List<TextRun>>)

/** 在库队列上读一次，挂起等结果（队列线程上抛的异常带回来；30 秒还没轮到 = 当失败）。 */
suspend fun <T> StoreQueue.read(what: String, work: (LibraryStore) -> T): T = withTimeout(30_000) {
    suspendCancellableCoroutine { c ->
        submit(what, { s -> runCatching { work(s) } }) { r -> if (c.isActive) c.resume(r) }
    }
}.getOrThrow()

/**
 * 第一批工具（用户 2026-11-15 选「只读为主 + 记忆」）。名字、参数、页码口径照 Mac 的 MCP（`../Sources/MCP/MCPTools*.swift`），
 * 同一个模型换端用起来一样；返回给模型看的是纯文字（Mac 那边的 `text` 部分），不带结构化 JSON。
 */
class AgentToolset(private val host: AgentHost) {

    val tools: List<AgentTool> = listOf(
        GetState(), ListDocuments(), ReadPages(), SearchText(), Goto(), OpenDocument(),
        ListAnnotations(), ListMarkdownNotes(), ReadMarkdown(), ListMemories(), SaveMemory(),
        // 第二批：写入（2026-11-15）
        AddNote(), AddHighlight(), AddBookmark(), CreateMarkdown(), EditMarkdown(),
        // 第三批：删除，先归档进回收站（2026-11-16）
        DeleteAnnotations(), DeleteMemory(),
        // 对话记录（agent_turn，2026-11-16）
        ListConversations(), ReadConversation(),
    )

    // ---------------- 小工具 ----------------

    private fun str(name: String, desc: String) = ToolParameterDescriptor(name, desc, ToolParameterType.String)
    private fun int(name: String, desc: String) = ToolParameterDescriptor(name, desc, ToolParameterType.Integer)

    private suspend fun <T> main(body: AgentHost.() -> T): T = withContext(Dispatchers.Main) { host.body() }

    private suspend fun queue(): StoreQueue =
        main { storeQueue() } ?: throw IllegalStateException("No workspace is open.")

    private suspend fun <T> db(what: String, work: (LibraryStore) -> T): T = queue().read(what, work)

    /** 文档参数：缺省 = 当前 PDF 标签；给了就按 id 找（也认 id 前 8 位、认书名）。 */
    private suspend fun resolveDoc(arg: String?): LibDocument {
        val docs = db("Agent 列文档") { it.allDocuments() }
        val key = arg?.trim().orEmpty()
        if (key.isEmpty()) {
            val v = main { currentView() }
            if (v == null || v.kind != "pdf") throw IllegalArgumentException("No PDF is open; pass document (an id from list_documents).")
            return docs.firstOrNull { it.id == v.documentId } ?: throw IllegalArgumentException("The open document is not in the library.")
        }
        return docs.firstOrNull { it.id.equals(key, true) }
            ?: docs.filter { it.id.startsWith(key, true) }.singleOrNull()
            ?: docs.filter { it.title == key }.singleOrNull()
            ?: throw IllegalArgumentException("No document matches \"$key\"; call list_documents for ids.")
    }

    /** 那篇得在标签里装载着才读得到文字（本机没有别的 Pdfium 实例）；没开就替用户开，等它装好。 */
    private suspend fun loaded(doc: LibDocument): AgentPdf {
        main { loadedPdf(doc.id) }?.let { return it }
        main { openDocument(doc.id) }
        repeat(50) {
            kotlinx.coroutines.delay(200)
            main { loadedPdf(doc.id) }?.let { return it }
        }
        throw IllegalStateException("\"${doc.title}\" could not be opened on this device (the PDF may not be downloaded).")
    }

    /** 一页的文字：有 OCR 行用 OCR（按行拼），否则 PDF 自带文字层。 */
    private fun pageText(p: AgentPdf, page: Int): String {
        val runs = p.ocr[page]
        if (!runs.isNullOrEmpty()) return runs.joinToString("\n") { it.text }
        return p.pdf.pageText(page)
    }

    // ---------------- 工具 ----------------

    private abstract inner class T(name: String, desc: String, req: List<ToolParameterDescriptor> = emptyList(), opt: List<ToolParameterDescriptor> = emptyList()) : AgentTool {
        override val descriptor = ToolDescriptor(name, desc, req, opt)
    }

    private inner class GetState : T(
        "get_state",
        "Where the user is: the open workspace and the current tab (kind, document id, title, the page at the top of the view, page count). Call this first.",
    ) {
        override suspend fun run(args: JSONObject): String = main {
            val v = currentView()
            buildString {
                appendLine("Workspace: ${workspaceName.ifEmpty { "(none)" }}")
                if (v == null) appendLine("No tab is open.")
                else {
                    appendLine("Current tab: ${v.kind} \"${v.title}\" (id ${v.documentId})")
                    if (v.kind == "pdf") appendLine("Page ${v.page + 1} of ${v.pageCount}")
                }
            }.trim()
        }
    }

    private inner class ListDocuments : T(
        "list_documents",
        "List the PDF documents in the workspace library: id, title, page count, group, reading progress.",
    ) {
        override suspend fun run(args: JSONObject): String {
            val docs = db("Agent 列文档") { it.allDocuments() }
            if (docs.isEmpty()) return "The library is empty."
            return docs.joinToString("\n") { d ->
                "- ${d.title} (id ${d.id}, ${d.pageCount} pages${if (d.group.isNotEmpty()) ", group ${d.group}" else ""}, read up to page ${d.readPage + 1})"
            }
        }
    }

    private inner class ReadPages : T(
        "read_pages",
        "Read the text of PDF pages (OCR text when the workspace has it, otherwise the PDF's own text layer). Pages are 1-based. At most 20 pages per call.",
        req = listOf(int("start", "first page (1-based)")),
        opt = listOf(
            int("end", "last page (1-based, inclusive); default = start"),
            str("document", "document id (or title); default = the PDF in the current tab"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val p = loaded(doc)
            val n = p.pdf.pageCount
            val s = args.optInt("start", 1).coerceIn(1, n)
            val e = args.optInt("end", s).coerceIn(s, minOf(n, s + 19))
            return withContext(Dispatchers.IO) {
                buildString {
                    for (pg in s..e) {
                        appendLine("--- Page $pg ---")
                        appendLine(pageText(p, pg - 1).ifBlank { "(no text on this page)" })
                    }
                    if (e < args.optInt("end", s)) appendLine("(stopped at page $e: at most 20 pages per call)")
                }.trim()
            }
        }
    }

    private inner class SearchText : T(
        "search_text",
        "Find text in a PDF (ignores case, spaces and line breaks). Returns pages and snippets, at most 100 hits.",
        req = listOf(str("query", "text to find")),
        opt = listOf(str("document", "document id (or title); default = the PDF in the current tab")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val q = args.optString("query").trim()
            require(q.isNotEmpty()) { "query is required" }
            val doc = resolveDoc(args.optString("document", ""))
            val p = loaded(doc)
            val needle = TextSearch.foldQuery(q)
            val hits = withContext(Dispatchers.IO) {
                val ocr = ArrayList<TextSearch.Hit>()
                for ((page, runs) in p.ocr) TextSearch.searchOcrPage(page, runs, needle, ocr)
                val plain = ArrayList<TextSearch.Hit>()
                for (page in 0 until p.pdf.pageCount) TextSearch.searchPlainPage(page, p.pdf.pageText(page), needle, plain)
                TextSearch.merge(ocr, plain).sortedBy { it.page }.take(100)
            }
            if (hits.isEmpty()) return "No match for \"$q\" in \"${doc.title}\"."
            return "${hits.size} hit(s) in \"${doc.title}\":\n" +
                hits.joinToString("\n") { "- page ${it.page + 1}: ${it.snippet.replace('\n', ' ')}" }
        }
    }

    private inner class Goto : T(
        "goto",
        "Scroll the reader to a page (1-based) so the user sees it; switches to that document's tab first if needed.",
        req = listOf(int("page", "page number (1-based)")),
        opt = listOf(str("document", "document id (or title); default = the PDF in the current tab")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val page = args.optInt("page", 1).coerceIn(1, maxOf(1, doc.pageCount))
            main { gotoPage(doc.id, page - 1, 0f) }
            return "Showing page $page of \"${doc.title}\"."
        }
    }

    private inner class OpenDocument : T(
        "open_document",
        "Open a document in a tab (or switch to it if it is already open).",
        req = listOf(str("document", "document id (or title)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document"))
            main { openDocument(doc.id) }
            return "Opened \"${doc.title}\"."
        }
    }

    private inner class ListAnnotations : T(
        "list_annotations",
        "List the user's text notes (批注), highlights, bookmarks and image notes on a PDF, by page, with their ids. Pages are 1-based.",
        opt = listOf(
            str("document", "document id (or title); default = the PDF in the current tab"),
            int("page", "only this page (1-based)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val only = if (args.has("page")) args.optInt("page") - 1 else null
            val rows = db("Agent 列批注") { s -> s.notes(doc.id) }
                .filter { it.kind in ANNOTATION_KINDS }
                .filter { only == null || it.page == only }
                .sortedWith(compareBy({ it.page }, { it.anchorY }))
            if (rows.isEmpty()) return "No annotations${if (only != null) " on page ${only + 1}" else ""} in \"${doc.title}\"."
            return rows.joinToString("\n") { n ->
                val o = runCatching { JSONObject(String(n.payload, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                val pg = "page ${n.page + 1}"
                when (n.kind) {
                    NoteKind.TEXT -> {
                        val tp = TextNotePayload(o)
                        val quote = if (tp.quote.isNotBlank()) " on \"${tp.quote.take(200)}\"" else ""
                        "- [note ${n.id.take(8)}] $pg$quote: ${tp.text}"
                    }
                    NoteKind.HIGHLIGHT -> "- [highlight ${n.id.take(8)}] $pg: \"${o.optString("quote").take(300)}\""
                    NoteKind.IMAGE -> "- [image_note ${n.id.take(8)}] $pg: ${o.optString("caption").ifEmpty { "(no caption)" }}"
                    else -> "- [bookmark ${n.id.take(8)}] $pg: ${o.optString("title")}"
                }
            }
        }
    }

    private inner class ListMarkdownNotes : T(
        "list_markdown_notes",
        "List the Markdown notes (Markdown 笔记) in the workspace's Notes folder; note_ref = path relative to that folder.",
    ) {
        override suspend fun run(args: JSONObject): String {
            val ws = main { workspaceDir } ?: return "No workspace is open."
            val scan = withContext(Dispatchers.IO) { MdNotes.scan(ws) }
            if (scan.notes.isEmpty()) return "The workspace has no Markdown notes."
            return scan.notes.joinToString("\n") { "- ${it.relPath}" }
        }
    }

    private inner class ReadMarkdown : T(
        "read_markdown",
        "Read a Markdown note with line numbers, optionally a line range (1-based, inclusive). At most 400 lines per call.",
        req = listOf(str("note_ref", "path relative to the Notes folder, from list_markdown_notes")),
        opt = listOf(int("start_line", "first line (1-based)"), int("end_line", "last line (1-based, inclusive)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val ws = main { workspaceDir } ?: return "No workspace is open."
            val rel = args.optString("note_ref").trim().trimStart('/')
            val root = MdNotes.root(ws).canonicalFile
            val f = File(root, rel).canonicalFile
            require(f.path.startsWith(root.path + File.separator)) { "note_ref must be inside the Notes folder" }
            require(f.isFile) { "No note \"$rel\"; call list_markdown_notes." }
            val lines = withContext(Dispatchers.IO) { f.readText().split('\n') }
            val s = args.optInt("start_line", 1).coerceIn(1, maxOf(1, lines.size))
            val e = args.optInt("end_line", lines.size).coerceIn(s, minOf(lines.size, s + 399))
            return buildString {
                appendLine("$rel (${lines.size} lines)")
                for (i in s..e) appendLine("$i\t${lines[i - 1]}")
                if (e < lines.size) appendLine("(more lines after $e)")
            }.trim()
        }
    }

    private inner class ListMemories : T(
        "list_memories",
        "List the long-term memories (Agent 记忆) saved in this workspace, most recently updated first: short facts for later conversations — the user's preferences and goals, what they are studying and how far they got.",
        opt = listOf(str("query", "only memories containing all of these words (case-insensitive)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val words = args.optString("query").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val list = db("Agent 读记忆") { it.agentMemories() }
                .filter { m -> words.all { m.content.lowercase().contains(it) } }
            if (list.isEmpty()) return if (words.isEmpty()) "No memories are saved in this workspace." else "No memory matches."
            return list.joinToString("\n") { "- [${it.id.take(8)}] ${it.content}" }
        }
    }

    private inner class SaveMemory : T(
        "save_memory",
        "Save one long-term memory in the workspace for later conversations: a short fact worth keeping (the user's preferences and goals, what they are studying and how far they got, conventions). One fact per memory, in the user's language; never secrets. Give id to rewrite an existing memory instead of adding a near-duplicate — check list_memories first.",
        req = listOf(str("content", "the memory (Markdown, at most 2000 characters)")),
        opt = listOf(str("id", "existing memory id to rewrite (full, or its first 8 characters)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val content = args.optString("content").trim()
            require(content.isNotEmpty()) { "content is required" }
            require(content.length <= 2000) { "content is longer than 2000 characters" }
            val idArg = args.optString("id", "").trim()
            return db("Agent 存记忆") { s ->
                val now = com.xvan.unireader.local.store.nowIso()   // 与库里其余时间同一种写法（Mac 照它解析）
                val old = if (idArg.isEmpty()) null else s.agentMemories().let { all ->
                    all.firstOrNull { it.id.equals(idArg, true) }
                        ?: all.filter { it.id.startsWith(idArg, true) }.singleOrNull()
                        ?: throw IllegalArgumentException("No memory with id $idArg")
                }
                val m = old?.copy(content = content, agent = AGENT_NAME, updatedAt = now)
                    ?: LibAgentMemory(UUID.randomUUID().toString().uppercase(), content, AGENT_NAME, now, now)
                s.upsertAgentMemory(m)
                if (old == null) "Saved the memory (id ${m.id})." else "Updated the memory (id ${m.id})."
            }
        }
    }

    // ================= 对话记录（`../docs/ANDROID-AGENT-PLAN.md §5.4`，同 Mac `MCPTools+Memory.swift`）=================

    private inner class ListConversations : T(
        "list_conversations",
        "List earlier conversations held in UniReader's Agent panel in this workspace (each turn — the user's message and the agent's reply, without tool output — is saved when it finishes), most recent first. Read one with read_conversation.",
        opt = listOf(int("limit", "how many (default 20, max 100)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val list = db("Agent 列对话") { it.recentAgentConversations(limit) }
            if (list.isEmpty()) return "No conversations are saved in this workspace."
            return list.joinToString("\n") { c ->
                "- ${c.lastAt} [${c.agent}] ${conversationTitle(c.firstUserText)} (${c.turns} turns, id ${c.id})"
            }
        }
    }

    private inner class ReadConversation : T(
        "read_conversation",
        "Read the turns of an earlier conversation in UniReader's Agent panel: what the user said and what the agent replied (tool output and thinking are not saved). Long conversations come in pages: pass offset = the number given after \"more: offset\" to go on.",
        req = listOf(str("id", "conversation id from list_conversations")),
        opt = listOf(int("offset", "first turn to return, 1-based (default 1)"), int("limit", "max turns (default 20, max 100)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val id = args.optString("id").trim()
            require(id.isNotEmpty()) { "id is required" }
            val offset = maxOf(1, args.optInt("offset", 1))
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val all = db("Agent 读对话") { it.agentTurns(id) }
            require(all.isNotEmpty()) { "no conversation with id $id; call list_conversations" }
            val start = minOf(offset - 1, all.size)
            val end = minOf(start + limit, all.size)
            var text = (start until end).joinToString("\n\n") { i ->
                val t = all[i]
                "## Turn ${i + 1} — ${t.createdAt}\n**User:** ${t.userText}\n\n**${t.agent.ifEmpty { "Agent" }}:** ${t.replyText}"
            }
            if (end < all.size) text += "\n\n(more: offset ${end + 1})"
            return text.ifEmpty { "No turns." }
        }
    }

    // ================= 第三批：删除（`../docs/ANDROID-AGENT-PLAN.md §5.3`）=================
    //
    // 🔴 先归档进回收站、成了才删（红线 11），一次调用一条回收站条目；有一个 id 对不上就一条都不删（同 Mac）。

    /** 完整 id，或至少 8 个字符的前缀（唯一才算）。 */
    private fun <T> resolveIds(raw: List<String>, all: List<T>, id: (T) -> String, what: String): List<T> {
        val picked = ArrayList<T>()
        val missing = ArrayList<String>()
        val ambiguous = ArrayList<String>()
        for (r in raw) {
            val key = r.trim().uppercase()
            val hits = if (key.length >= 8) all.filter { id(it).uppercase().startsWith(key) } else emptyList()
            when {
                hits.isEmpty() -> missing += r
                hits.size > 1 -> ambiguous += r
                picked.none { id(it) == id(hits[0]) } -> picked += hits[0]
            }
        }
        require(missing.isEmpty()) { "not found: ${missing.joinToString(", ")}. Take ids from $what. Nothing was deleted." }
        require(ambiguous.isEmpty()) { "these id prefixes match more than one item: ${ambiguous.joinToString(", ")}; pass the full ids. Nothing was deleted." }
        return picked
    }

    private fun idList(args: JSONObject): List<String> {
        val a = args.optJSONArray("ids")
        require(a != null && a.length() > 0) { "ids must be a non-empty array of strings" }
        require(a.length() <= MAX_DELETE_IDS) { "at most $MAX_DELETE_IDS ids per call" }
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun strArray(name: String, desc: String) =
        ToolParameterDescriptor(name, desc, ToolParameterType.List(ToolParameterType.String))

    private inner class DeleteAnnotations : T(
        "delete_annotations",
        "Delete highlights, text notes, bookmarks or image notes from one document by id. Take the ids from list_annotations (the full id, or the 8-character prefix shown in its output). Everything deleted in one call goes to UniReader's Trash as one item the user can put back; if any id is not found, nothing is deleted. Handwriting, documents and Markdown notes cannot be deleted with this tool.",
        req = listOf(strArray("ids", "ids to delete (at most $MAX_DELETE_IDS)")),
        opt = listOf(str("document", "document id (or title); default = the PDF in the current tab")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val raw = idList(args)
            val doc = resolveDoc(args.optString("document", ""))
            val ws = main { workspaceDir } ?: throw IllegalStateException("No workspace is open.")
            class Item(val id: String, val kind: String, val page: Int, val label: String)
            val all = db("Agent 列批注") { s -> s.notes(doc.id) }.filter { it.kind in ANNOTATION_KINDS }.map { n ->
                val o = runCatching { JSONObject(String(n.payload, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                when (n.kind) {
                    NoteKind.TEXT -> TextNotePayload(o).let { Item(n.id, "note", n.page, it.quote.ifBlank { it.text }) }
                    NoteKind.HIGHLIGHT -> Item(n.id, "highlight", n.page, o.optString("quote"))
                    NoteKind.IMAGE -> Item(n.id, "image_note", n.page, o.optString("caption"))
                    else -> Item(n.id, "bookmark", n.page, o.optString("title"))
                }
            }
            val picked = resolveIds(raw, all, { it.id }, "list_annotations (only highlights, text notes, bookmarks and image notes can be deleted)")
            val head = picked[0].label.replace(Regex("\\s+"), " ").trim().take(24)
            val title = main {
                when {
                    head.isEmpty() -> string(com.xvan.unireader.R.string.agent_trash_annotations)
                    picked.size == 1 -> "“$head”"
                    else -> string(com.xvan.unireader.R.string.agent_trash_and_more, head, picked.size - 1)
                }
            }
            db("Agent 删批注") { s -> com.xvan.unireader.local.Trash.deleteAnnotations(s, ws, doc.id, doc.title, picked.map { it.id }, title) }
            main { annotationsDeleted(doc.id) }
            return (listOf("Deleted ${picked.size} annotation(s) from \"${doc.title}\"; they are in the Trash as “$title”.") +
                picked.map { "- p.${it.page + 1} ${it.kind} [${it.id.take(8)}] ${it.label.take(80)}" }).joinToString("\n")
        }
    }

    private inner class DeleteMemory : T(
        "delete_memory",
        "Delete memories that are wrong or no longer useful (they go to UniReader's Trash and can be restored there). Rewrite an outdated one with save_memory id instead of deleting and re-adding it.",
        req = listOf(strArray("ids", "memories to delete: memory id (full, or its first 8 characters)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val raw = idList(args)
            val ws = main { workspaceDir } ?: throw IllegalStateException("No workspace is open.")
            val all = db("Agent 读记忆") { it.agentMemories() }
            val picked = resolveIds(raw, all, { it.id }, "list_memories")
            val title = if (picked.size == 1) memoryTitle(picked[0].content)
            else main { string(com.xvan.unireader.R.string.agent_trash_memories, picked.size) }
            db("Agent 删记忆") { s -> com.xvan.unireader.local.Trash.deleteAgentMemories(s, ws, picked.map { it.id }, title) }
            return "Moved ${picked.size} memory(ies) to the Trash."
        }
    }

    // ================= 第二批：写入（`../docs/ANDROID-AGENT-PLAN.md §5.2`）=================

    private fun num(name: String, desc: String) = ToolParameterDescriptor(name, desc, ToolParameterType.Float)
    private fun bool(name: String, desc: String) = ToolParameterDescriptor(name, desc, ToolParameterType.Boolean)
    private fun enumOf(name: String, desc: String, vararg v: String) =
        ToolParameterDescriptor(name, desc, ToolParameterType.Enum(arrayOf(*v)))

    /** 经宿主写批注 / 高亮（开着的走画布：进撤销栈、马上显示），挂起等写完。 */
    private suspend fun writeNotes(docId: String, what: String, work: (LibraryStore) -> Unit) {
        val err = withContext(Dispatchers.Main) {
            withTimeout(30_000) {
                suspendCancellableCoroutine<Throwable?> { c ->
                    host.writeNotes(docId, what, work) { e -> if (c.isActive) c.resume(e) }
                }
            }
        }
        if (err != null) throw err
    }

    private fun pageArg(args: JSONObject, doc: LibDocument): Int {
        require(args.has("page")) { "page is required" }
        val p = args.optInt("page", 0)
        require(p in 1..maxOf(1, doc.pageCount)) { "page must be 1…${doc.pageCount}" }
        return p
    }

    /**
     * 在某页找一段原文，返回行框（归一化 x,y,w,h，一行一个）。同 Mac `MCPDocReader.locate`：
     * 那页有 OCR 就在 OCR 行里找（与用户手划的高亮同一套框），否则在 PDF 自带文字层里找；忽略大小写、空白与换行。
     */
    private suspend fun locate(p: AgentPdf, page: Int, quote: String): List<FloatArray>? = withContext(Dispatchers.IO) {
        val needle = TextSearch.foldQuery(quote)
        if (needle.isEmpty()) return@withContext null
        val runs = p.ocr[page]
        if (!runs.isNullOrEmpty()) {
            val hits = ArrayList<TextSearch.Hit>()
            TextSearch.searchOcrPage(page, runs, needle, hits)
            return@withContext hits.firstOrNull()?.rects?.takeIf { it.isNotEmpty() }
        }
        val hits = ArrayList<TextSearch.Hit>()
        TextSearch.searchPlainPage(page, p.pdf.pageText(page), needle, hits)
        val h = hits.firstOrNull() ?: return@withContext null
        p.pdf.charRects(page, h.origin, h.length).takeIf { it.isNotEmpty() }
    }

    private fun bbox(rects: List<FloatArray>): FloatArray {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (r in rects) { x0 = minOf(x0, r[0]); y0 = minOf(y0, r[1]); x1 = maxOf(x1, r[0] + r[2]); y1 = maxOf(y1, r[1] + r[3]) }
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private inner class AddNote : T(
        "add_note",
        "Add a text note to a PDF page: an annotation pinned to a spot on the page (批注 / 文字笔记 in the UI). Not for Markdown notes, which are separate .md files — write those with edit_markdown or create_markdown. Anchor it to a passage by giving `quote` (the exact text on that page; it is located and the note pins to it), or to an area with `rect`, or to the top of the page with neither. The user can undo it. Pages are 1-based.",
        req = listOf(int("page", "page number (1-based)")),
        opt = listOf(
            str("text", "the note body (Markdown; \$…\$ for formulas). Required unless quote is given."),
            str("quote", "passage on that page to attach the note to (must exist on the page, case-insensitive)"),
            ToolParameterDescriptor("rect", "[x, y, w, h] normalized area on the page, top-left origin; used when there is no quote",
                ToolParameterType.List(ToolParameterType.Float)),
            str("type", "note type name as shown in UniReader (must already exist); omit for the generic type"),
            enumOf("display", "how the bubble opens on the page (default tap)", "tap", "hover", "always"),
            str("document", "document id (or title); default = the PDF in the current tab"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val page = pageArg(args, doc)
            val text = args.optString("text", "")
            val quote = args.optString("quote", "").trim()
            require(quote.isNotEmpty() || text.isNotBlank()) { "a note without a quote needs text" }
            val display = when (args.optString("display", "tap")) {
                "tap", "" -> com.xvan.unireader.shared.NOTE_TAP
                "hover" -> com.xvan.unireader.shared.NOTE_HOVER
                "always" -> com.xvan.unireader.shared.NOTE_ALWAYS
                else -> throw IllegalArgumentException("display must be tap, hover or always")
            }
            val typeName = args.optString("type", "").trim()
            val typeId = if (typeName.isEmpty()) null else {
                val types = db("Agent 读笔记类型") { it.noteTypes() }
                types.firstOrNull { it.name.equals(typeName, true) }?.id
                    ?: throw IllegalArgumentException(
                        "note type '$typeName' does not exist; available: ${types.joinToString(", ") { it.name }.ifEmpty { "(none)" }}",
                    )
            }
            // 锚点同 Mac `addNote`：行框 → 行框包围盒；只给了矩形 → 它；都没有 → 页左上一条横条
            val rects: List<FloatArray>
            val anchor: FloatArray
            val rectArg = args.optJSONArray("rect")
            if (quote.isNotEmpty()) {
                rects = locate(loaded(doc), page - 1, quote)
                    ?: throw IllegalArgumentException("quote not found on page $page; use read_pages to copy the exact text, or pass rect instead")
                anchor = bbox(rects)
            } else if (rectArg != null && rectArg.length() == 4) {
                anchor = FloatArray(4) { rectArg.getDouble(it).toFloat().coerceIn(0f, 1f) }
                rects = listOf(anchor)
            } else {
                anchor = floatArrayOf(0.05f, 0.05f, 0.9f, 0.02f)
                rects = emptyList()
            }
            val id = UUID.randomUUID().toString().uppercase()
            val source = JSONObject().put("kind", "agent").put("provider", AGENT_NAME).put("url", "")
                .put("at", com.xvan.unireader.local.store.nowIso())
            writeNotes(doc.id, "Agent 加批注") { s ->
                s.insertSelectionNote(doc.id, id, page - 1, anchor, quote, rects, text, typeId, display, source = source)
            }
            return "Added note on page $page${if (quote.isEmpty()) "" else " at \"${quote.take(40)}…\""} · id $id"
        }
    }

    private inner class AddHighlight : T(
        "add_highlight",
        "Highlight a passage on a page. `quote` must be the exact text on that page (copy it from read_pages); it is located in the OCR text when the page has it, otherwise in the PDF's own text, so line breaks inside the passage are fine. `style`: fill (highlighter, default), underline, box (outline only), or strike. Fails, rather than guessing, when the text is not found. The user can undo it.",
        req = listOf(int("page", "page number (1-based)"), str("quote", "exact passage to highlight")),
        opt = listOf(
            str("color", "Yellow, Green, Blue, Pink or #RRGGBB (default Yellow)"),
            enumOf("style", "how to draw it (default fill)", "fill", "underline", "box", "strike"),
            str("document", "document id (or title); default = the PDF in the current tab"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val page = pageArg(args, doc)
            val quote = args.optString("quote").trim()
            require(quote.isNotEmpty()) { "quote is required" }
            val colorArg = args.optString("color", "").ifBlank { "Yellow" }
            val rgb = HIGHLIGHT_PALETTE.entries.firstOrNull { it.key.equals(colorArg, true) }?.value
                ?: parseHex(colorArg)
                ?: throw IllegalArgumentException("color must be one of ${HIGHLIGHT_PALETTE.keys.joinToString(", ")} or #RRGGBB")
            val style = when (args.optString("style", "fill").lowercase()) {
                "fill", "" -> com.xvan.unireader.shared.HL_FILL
                "underline" -> com.xvan.unireader.shared.HL_UNDERLINE
                "box" -> com.xvan.unireader.shared.HL_BOX
                "strike" -> com.xvan.unireader.shared.HL_STRIKE
                else -> throw IllegalArgumentException("style must be one of fill, underline, box, strike")
            }
            val rects = locate(loaded(doc), page - 1, quote)
                ?: throw IllegalArgumentException("quote not found on page $page; use read_pages to copy the exact text")
            val id = UUID.randomUUID().toString().uppercase()
            writeNotes(doc.id, "Agent 加高亮") { s -> s.insertHighlight(doc.id, id, page - 1, bbox(rects), quote, rects, rgb, style) }
            return "Highlighted \"${quote.take(60)}${if (quote.length > 60) "…" else ""}\" on page $page · id $id"
        }
    }

    private inner class AddBookmark : T(
        "add_bookmark",
        "Add a named bookmark at a page (and optionally a position inside it). Bookmarks appear in the outline next to the PDF's own entries. Pages are 1-based.",
        req = listOf(int("page", "page number (1-based)"), str("title", "bookmark name (not empty)")),
        opt = listOf(
            num("frac", "position inside the page, 0 = top … 1 = bottom (default 0)"),
            str("document", "document id (or title); default = the PDF in the current tab"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val page = pageArg(args, doc)
            val title = args.optString("title").trim()
            require(title.isNotEmpty()) { "title must not be empty" }
            val frac = args.optDouble("frac", 0.0).toFloat().coerceIn(0f, 1f)
            val id = db("Agent 加书签") { s -> s.insertBookmark(doc.id, page - 1, frac, title) }
                ?: throw IllegalStateException("could not add bookmark")
            main { bookmarksChanged() }
            return "Added bookmark \"$title\" at page $page · id $id"
        }
    }

    private fun notesRoot(ws: File): File = MdNotes.root(ws).canonicalFile

    /** note_ref → 文件；只认 `Notes/` 里面的。 */
    private fun noteFile(ws: File, rel: String): File {
        val root = notesRoot(ws)
        val f = File(root, rel.trim().trimStart('/')).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "note_ref must be inside the Notes folder" }
        return f
    }

    private inner class CreateMarkdown : T(
        "create_markdown",
        "Create a new Markdown note — a separate .md file in the workspace's Notes folder — with an optional starting text. The title becomes the file name (and the name [[wiki links]] use). Missing folders are created. If a note with that name already exists there, nothing is written and you get its note_ref: change it with edit_markdown instead. Create notes only when the user asks for them.",
        req = listOf(str("title", "note title = file name, without .md (characters / \\ : * ? \" < > | # ^ [ ] become -)")),
        opt = listOf(
            str("text", "starting Markdown body (optional; default empty)"),
            str("folder", "folder inside Notes, e.g. \"数学/第三章\" (created if missing); default = the Notes folder itself"),
            bool("open", "open the new note in a tab afterwards (default false)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val ws = main { workspaceDir } ?: return "No workspace is open."
            val title = args.optString("title").trim()
            require(title.isNotEmpty()) { "title is required" }
            val folder = args.optString("folder", "").trim().trim('/')
                .split('/').filter { it.isNotBlank() }.joinToString("/") { MdNotes.safeFileName(it) }
            val rel = (if (folder.isEmpty()) "" else "$folder/") + MdNotes.safeFileName(title) + ".md"
            val f = noteFile(ws, rel)
            if (f.exists()) return "A note named \"$title\" already exists (note_ref $rel); nothing was written. Change it with edit_markdown."
            val text = args.optString("text", "")
            withContext(Dispatchers.IO) { MdNotes.write(f, text) }
            com.xvan.unireader.local.server.ServerSync.noteSaved(ws)   // 服务器工作区：马上推上去
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { c -> host.markdownTreeChanged { if (c.isActive) c.resume(Unit) } }
            }
            val open = args.optBoolean("open", false)
            if (open) main { openMarkdown(rel) }
            return "Created Markdown note \"$title\" · note_ref $rel · ${MdEdit.lineCount(text)} lines${if (open) " · opened in a tab" else ""}"
        }
    }

    private inner class EditMarkdown : T(
        "edit_markdown",
        "Change part of a Markdown note without resending the whole text, like a code editor's find-and-replace. Each edit either replaces old_text (exact match, including spaces and line breaks) with new_text, or inserts new_text after insert_line (0 = at the top). Pass one edit via old_text/new_text/replace_all/insert_line, or several via edits (a JSON array of objects with those fields); they apply in order and all-or-nothing. Works on the live editor text, so the user's unsaved typing is kept; call read_markdown first to copy old_text exactly. Only change what the user asked for: keep existing [[wiki links]], aliases, anchors and embeds as they are.",
        req = listOf(str("note_ref", "path relative to the Notes folder, from list_markdown_notes")),
        opt = listOf(
            str("old_text", "exact text to replace"),
            str("new_text", "replacement, or the lines to insert"),
            bool("replace_all", "replace every occurrence of old_text (default false: it must match exactly once)"),
            int("insert_line", "insert new_text after this line (1-based; 0 = at the top) instead of replacing"),
            str("edits", "several edits as a JSON array: [{\"old_text\":…,\"new_text\":…}, {\"insert_line\":3,\"new_text\":…}]"),
        ),
    ) {
        private fun parseEdit(o: JSONObject, label: String): MdEdit.Edit {
            require(o.has("new_text") && !o.isNull("new_text")) { "$label: 'new_text' is required" }
            val new = o.getString("new_text")
            if (o.has("insert_line") && !o.isNull("insert_line")) {
                require(!o.has("old_text") || o.isNull("old_text")) { "$label: use either old_text or insert_line, not both" }
                return MdEdit.Edit.Insert(o.getInt("insert_line"), new)
            }
            require(o.has("old_text") && !o.isNull("old_text")) { "$label: 'old_text' or 'insert_line' is required" }
            return MdEdit.Edit.Replace(o.getString("old_text"), new, o.optBoolean("replace_all", false))
        }

        override suspend fun run(args: JSONObject): String {
            val ws = main { workspaceDir } ?: return "No workspace is open."
            val rel = args.optString("note_ref").trim().trimStart('/')
            val f = noteFile(ws, rel)
            require(f.isFile) { "No note \"$rel\"; call list_markdown_notes." }
            val single = listOf("old_text", "new_text", "replace_all", "insert_line").any { args.has(it) && !args.isNull(it) }
            val edits = ArrayList<MdEdit.Edit>()
            val rawEdits = args.opt("edits")
            if (rawEdits != null && rawEdits != JSONObject.NULL && rawEdits.toString().isNotBlank()) {
                require(!single) { "pass either edits or old_text/new_text/insert_line, not both" }
                // 模型可能按 schema 传字符串，也可能直接传数组
                val arr = rawEdits as? org.json.JSONArray ?: org.json.JSONArray(rawEdits.toString())
                require(arr.length() > 0) { "edits must be a non-empty array" }
                for (i in 0 until arr.length()) edits += parseEdit(arr.getJSONObject(i), "edits[$i]")
            } else {
                require(single) { "pass old_text + new_text, insert_line + new_text, or edits" }
                edits += parseEdit(args, "edit")
            }
            // 开着就在编辑器里的字上改（用户没存的输入保留），否则读文件
            val before = main { markdownLiveText(rel) } ?: withContext(Dispatchers.IO) { f.readText() }
            val r = try {
                MdEdit.apply(edits, before)
            } catch (e: MdEdit.EditError) {
                throw IllegalArgumentException(if (e.index > 0 && edits.size > 1) "edit ${e.index}: ${e.message}" else e.message)
            }
            if (!main { markdownApply(rel, r.text) }) {
                withContext(Dispatchers.IO) { MdNotes.write(f, r.text) }
                com.xvan.unireader.local.server.ServerSync.noteSaved(ws)
            }
            val places = r.counts.sum()
            return "Edited $rel · $places place(s) changed · ${MdEdit.lineCount(r.text)} lines now\n" +
                MdEdit.snippet(r.text, r.changedLines)
        }
    }

    private fun parseHex(s: String): IntArray? {
        val h = s.trim().removePrefix("#")
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    companion object {
        /** 记忆行的 `agent` 列（Mac 记的是 MCP 客户端名，如 `kimi`）。 */
        const val AGENT_NAME = "unireader-android"

        /** 高亮四色，名字与值同 Mac `Highlight.palette`。 */
        val HIGHLIGHT_PALETTE = linkedMapOf(
            "Yellow" to intArrayOf(255, 214, 40),
            "Green" to intArrayOf(150, 220, 120),
            "Blue" to intArrayOf(120, 190, 255),
            "Pink" to intArrayOf(255, 150, 190),
        )

        /** list_annotations / delete_annotations 认的四种批注（同 Mac：手写、AI 线程不算）。 */
        val ANNOTATION_KINDS = setOf(NoteKind.TEXT, NoteKind.HIGHLIGHT, NoteKind.BOOKMARK, NoteKind.IMAGE)

        /** 一次最多删几条（同 Mac `MCPTools.maxDeleteIds`）。 */
        const val MAX_DELETE_IDS = 200

        /** 对话列表里一段对话的名字：第一句话压成一行，最多 40 字（同 Mac `AgentMemoryText.conversationTitle`）。 */
        fun conversationTitle(firstUserText: String): String {
            val flat = firstUserText.split('\n', '\r').filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (flat.isEmpty()) return "Conversation"
            return if (flat.length > 40) flat.take(40) + "…" else flat
        }

        /** 回收站条目里一条记忆的名字：第一行，最多 40 字（同 Mac `WorkspaceManager.memoryTitle`）。 */
        fun memoryTitle(content: String): String {
            val line = content.split('\n', limit = 2).first()
            return if (line.length > 40) line.take(40) + "…" else line
        }
    }
}
