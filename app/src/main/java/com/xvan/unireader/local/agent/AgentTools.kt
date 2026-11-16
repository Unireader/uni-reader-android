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

    /** 应用 Context：语义搜索读开关 / 模型 / 向量缓存（`../docs/SEMANTIC-SYNC-PLAN.md §4.2`）。null = 不走语义那一路（测试里的假宿主）。 */
    val appContext: android.content.Context? get() = null

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

    /** 这篇的批注在库里被删了 / 改了：开着的标签从库里重读批注 / 图片笔记、刷书签。 */
    fun annotationsChanged(documentId: String)

    /** 界面文案（回收站条目名要随系统语言）。 */
    fun string(id: Int, vararg args: Any): String

    // ---- 习题（`../docs/ANDROID-AGENT-PLAN.md §5.8`）----

    /** 作答 / 错题本在库里变了（Agent 批改）：开着的练习页、抽屉的数就地刷新。 */
    fun exercisesChanged()

    /** 改名 / 挪目录（[folder] 相对 `Notes/`，null = 原目录）；开着的标签跟着换。[then] 在主线程，拿到新路径（撞名 / 失败 null）。 */
    fun markdownRename(rel: String, name: String, folder: String?, then: (String?) -> Unit)

    /** 删一篇（挪进 `Notes/.trash/`），开着的标签关掉。 */
    fun markdownDelete(rel: String, then: (Boolean) -> Unit)

    /** 书库里的书名 / 分组变了：刷抽屉、开着的标签上的名字。 */
    fun libraryChanged()

    /** 开在标签里的那本书的目录（Pdfium 读的书签树）；没开着 = null。 */
    fun documentToc(documentId: String): List<com.xvan.unireader.shared.TocItem>?

    /** 在公用习题标签里开一组题（范围键同 Mac `ExerciseScope.key`），[exerciseId] 非空 = 停在那道题。 */
    fun openExercises(scopeKey: String, exerciseId: String?)
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
        // Agent 上下文（agent_context，2026-11-16）
        ListAgentContexts(), ReadAgentContext(), SaveAgentContext(),
        // 全工作区搜索（关键词，2026-11-16）
        Search(),
        // 改批注（2026-11-16）
        UpdateAnnotations(),
        // 习题（2026-11-16）
        ListExercises(), ListExerciseAttempts(), GradeExerciseAttempts(), OpenExercises(),
        // 日程（2026-11-16）
        ListSchedule(), AddScheduleItems(), UpdateScheduleItems(), DeleteSchedule(),
        // Markdown 笔记：打开 / 改名挪目录 / 删除（2026-11-16）
        OpenMarkdown(), RenameMarkdown(), DeleteMarkdown(),
        // 书：目录 / 改名改分组（2026-11-16）
        GetDocument(), UpdateDocument(),
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

    // ================= Agent 上下文（`../docs/ANDROID-AGENT-PLAN.md §5.5`，同 Mac `MCPTools+AgentContext.swift`）=================

    private fun resolveContext(all: List<com.xvan.unireader.local.store.LibAgentContext>, id: String) =
        all.firstOrNull { it.id.equals(id, true) }
            ?: id.uppercase().let { key -> if (key.length >= 8) all.filter { it.id.uppercase().startsWith(key) } else emptyList() }
                .let { hits ->
                    require(hits.size == 1) {
                        if (hits.isEmpty()) "no agent context with id $id; call list_agent_contexts" else "id $id matches several agent contexts; give the full id"
                    }
                    hits[0]
                }

    private inner class ListAgentContexts : T(
        "list_agent_contexts",
        "List the agent contexts saved in this workspace, most recently updated first. An agent context (Agent 上下文) is a handoff summary of an earlier conversation with some agent, saved so that any agent can continue that work. Read one with read_agent_context.",
    ) {
        override suspend fun run(args: JSONObject): String {
            val all = db("Agent 列上下文") { it.agentContexts() }
            if (all.isEmpty()) return "No agent contexts are saved in this workspace."
            return "${all.size} agent context(s):\n" + all.joinToString("\n") { c ->
                "- ${c.title} (id ${c.id}${if (c.agent.isNotEmpty()) ", by ${c.agent}" else ""}, updated ${c.updatedAt}, ${c.summary.length} characters)"
            }
        }
    }

    private inner class ReadAgentContext : T(
        "read_agent_context",
        "Read one saved agent context: its title and the full handoff summary (Markdown). Use it as background to continue the work it describes.",
        req = listOf(str("id", "context id from list_agent_contexts (full, or its first 8 characters)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val id = args.optString("id").trim()
            require(id.isNotEmpty()) { "id is required" }
            val c = resolveContext(db("Agent 读上下文") { it.agentContexts() }, id)
            return "# ${c.title}\n\n${c.summary}"
        }
    }

    private inner class SaveAgentContext : T(
        "save_agent_context",
        "Save a handoff summary of the current conversation as an agent context in the workspace, so the user can continue this work later with any agent. Only when the user asks to save the context. Write the summary in the user's language as Markdown: the goal, what was done and decided, key facts the next agent needs (document titles with document id, note_ref, page numbers, ids), the current state, open questions and next steps. Give id to replace the title / summary of an existing context instead of adding a new one.",
        req = listOf(str("title", "short title (at most 30 characters)"), str("summary", "the handoff summary (Markdown), without the title")),
        opt = listOf(str("id", "existing context id to update (optional)")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val title = args.optString("title")
            val summary = args.optString("summary").trim()
            require(summary.isNotEmpty()) { "title and a non-empty summary are required" }
            val idArg = args.optString("id", "").trim()
            val fallback = main { string(com.xvan.unireader.R.string.agent_context_untitled, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())) }
            return db("Agent 存上下文") { s ->
                if (idArg.isNotEmpty()) {
                    val c = resolveContext(s.agentContexts(), idArg)
                    "Updated the agent context “${updateContext(s, c, title, summary).title}”."
                } else {
                    "Saved the agent context “${addContext(s, title, summary, AGENT_NAME, fallback).title}”."
                }
            }
        }
    }

    // ================= 全工作区搜索（`../docs/ANDROID-AGENT-PLAN.md §5.6`，同 Mac `MCPTools+Search.swift`；语义那路 `../docs/SEMANTIC-SYNC-PLAN.md §4.2`）=================

    private inner class Search : T(
        "search",
        "Search everything in the workspace at once: the text of every PDF page (OCR text where the workspace has it; for books without OCR, only those open in a tab), Markdown notes, annotations (text notes, highlights, bookmarks, image-note captions), exercises, saved agent contexts, workspace memories and earlier Agent-panel conversations. Matching ignores spaces, line breaks, case and full-width/half-width differences, so a phrase broken across lines in a scanned book still matches. Several words = all must appear (if nothing matches, any of them); put a phrase in quotes to keep it together. When semantic search is turned on in the search settings and the workspace is on a server, results that mean the same thing in other words are mixed in (mode hybrid). Results are ranked, one per PDF page / note section, with ids to follow up: read_pages or search_text for PDF pages, read_markdown with start_line = line for notes, list_annotations, read_conversation (offset = turn), read_agent_context.",
        req = listOf(str("query", "Words or phrases to find. Chinese works without spaces; separate several keywords with spaces.")),
        opt = listOf(
            strArray("kinds", "Only these kinds (default: all): ${com.xvan.unireader.local.search.WorkspaceSearch.KINDS.joinToString(", ")}"),
            strArray("document_ids", "Only these PDFs (their pages, annotations and exercises)"),
            str("note_folder", "Markdown notes: only notes under this folder (relative to the Notes folder)"),
            enumOf("mode", "auto = hybrid when semantic search is on and ready, otherwise keyword", "auto", "keyword", "semantic", "hybrid"),
            int("limit", "Max results (default 20, max 100)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val WS = com.xvan.unireader.local.search.WorkspaceSearch
            val query = args.optString("query").trim()
            require(query.isNotEmpty()) { "query is required" }
            val kinds = args.optJSONArray("kinds")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }?.takeIf { it.isNotEmpty() }
                ?: WS.KINDS.toSet()
            require(kinds.all { it in WS.KINDS }) { "kinds must be among ${WS.KINDS.joinToString(", ")}" }
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val modeArg = args.optString("mode", "auto").ifEmpty { "auto" }
            require(modeArg in listOf("auto", "keyword", "semantic", "hybrid")) { "mode must be auto, keyword, semantic or hybrid" }
            val ws = main { workspaceDir } ?: throw IllegalStateException("No workspace is open.")
            val docsAll = db("Agent 列文档") { it.allDocuments() }
            val owners = args.optJSONArray("document_ids")?.let { a -> (0 until a.length()).map { resolveDoc(a.optString(it)).id }.toSet() }.orEmpty()
            val docs = if (owners.isEmpty()) docsAll else docsAll.filter { it.id in owners }
            val titles = docsAll.associate { it.id to it.title }

            class Got(val chunks: List<com.xvan.unireader.local.search.WorkspaceSearch.SearchChunk>, val missing: Map<String, List<Int>>)
            val got = db("Agent 搜索取内容") { s ->
                val out = ArrayList<com.xvan.unireader.local.search.WorkspaceSearch.SearchChunk>()
                val missing = HashMap<String, List<Int>>()
                for (d in docs) {
                    if ("pdf" in kinds) {
                        val part = WS.pdfPart(s, d.id, d.title, d.pageCount)
                        out += part.chunks
                        if (part.missingPages.isNotEmpty()) missing[d.id] = part.missingPages
                    }
                    if ("annotation" in kinds) out += WS.annotationChunks(s, d.id, d.title)
                }
                if ("exercise" in kinds) out += WS.exerciseChunks(s, titles).filter { owners.isEmpty() || it.sub.removePrefix("doc:") in owners }
                if (owners.isEmpty()) out += WS.agentChunks(s, kinds)
                Got(out, missing)
            }
            val chunks = ArrayList(got.chunks)
            // 没 OCR 的页：书开在标签里就用 PDF 自带文字层补；没开的记下来告诉模型（本机只有标签里的 Pdfium 实例）
            val skipped = ArrayList<String>()
            for ((docId, pages) in got.missing) {
                val p = main { loadedPdf(docId) }
                if (p == null) {
                    // 只有整本都没 OCR 文字才算「没搜到」（个别空白页、插图页不提）
                    if (pages.size >= (docs.firstOrNull { it.id == docId }?.pageCount ?: 0)) skipped += titles[docId].orEmpty()
                    continue
                }
                withContext(Dispatchers.IO) {
                    for (pg in pages) {
                        val t = runCatching { p.pdf.pageText(pg) }.getOrDefault("")
                        if (t.isNotBlank()) chunks += WS.pageChunks(docId, titles[docId].orEmpty(), pg, t)
                    }
                }
            }
            if ("markdown" in kinds && owners.isEmpty()) {
                chunks += withContext(Dispatchers.IO) { WS.markdownChunks(ws, args.optString("note_folder", "")) }
            }
            val want = limit
            val sem = semanticPart(ws, chunks, query, modeArg, want)
            val r = withContext(Dispatchers.Default) { WS.keyword(chunks, query, if (sem.mode == "keyword") want else want * 3) }
            val hits: List<com.xvan.unireader.local.search.WorkspaceSearch.Hit> = when {
                sem.mode == "keyword" -> r.hits.take(want)
                sem.mode == "semantic" && sem.hits.isNotEmpty() -> sem.hits.take(want)
                else -> com.xvan.unireader.local.search.Semantic.fuse(r.hits, sem.hits, want, if (r.relaxed) 0.5 else 1.0)
            }
            val lines = hits.mapIndexed { i, h ->
                val c = h.chunk
                val head = when (c.kind) {
                    "pdf" -> "[PDF] “${c.title}” p.${(c.page ?: 0) + 1} (document_id ${c.owner})"
                    "markdown" -> "[Note] “${c.title}” line ${c.line}${if (c.heading.isNotEmpty()) " § ${c.heading}" else ""} (note_ref ${c.owner})"
                    "annotation" -> "[${c.heading}] “${c.title}” p.${(c.page ?: 0) + 1} (id ${c.sub.take(8)}, document_id ${c.owner})"
                    "exercise" -> "[Exercise] ${if (c.title.isEmpty()) "" else "“${c.title}” "}(exercise_id ${c.owner}${c.page?.let { ", p.${it + 1}" } ?: ""})"
                    "memory" -> "[Memory] (memory_id ${c.owner.take(8)})"
                    "conversation" -> "[Conversation] “${c.title}” turn ${c.line} (conversation_id ${c.owner})"
                    else -> "[Context] “${c.title}” (context_id ${c.owner})"
                }
                "${i + 1}. $head\n   ${com.xvan.unireader.local.search.SearchText.snippet(c.body, r.terms)}"
            }
            var text = if (hits.isEmpty()) "No results for “$query”."
            else "${hits.size} result(s) for “$query” (${sem.mode}${if (r.relaxed) ", any word" else ""}):\n" + lines.joinToString("\n")
            if (sem.notices.isNotEmpty()) text += "\n" + sem.notices.joinToString("\n")
            if (skipped.isNotEmpty()) {
                text += "\nNot searched (no OCR text on this device and not open in a tab): " + skipped.joinToString(", ") { "“$it”" } +
                    ". Open one with open_document, or use search_text on it, to search its own text layer."
            }
            text += "\nsemantic: " + org.json.JSONObject()
                .put("enabled", sem.enabled).put("vectors", sem.vectors).put("matched", sem.matched)
                .put("error", sem.error ?: org.json.JSONObject.NULL)
            return text
        }
    }

    /** 语义那一路的结果与说明（`semantic{enabled, vectors, matched, error}` 同 Mac 的 `index.semantic`，多一个 matched）。 */
    private class SemanticPart(
        val mode: String,
        val hits: List<com.xvan.unireader.local.search.WorkspaceSearch.Hit>,
        val enabled: Boolean, val vectors: Int, val matched: Int, val error: String?,
        val notices: List<String>,
    )

    /**
     * 语义一路（`../docs/SEMANTIC-SYNC-PLAN.md §4.2`）：块向量是服务器同步来的（只有服务器工作区有），这里只算查询向量。
     * `matched` = 本次切出的块里查到向量的块数（命中率低 = 两端切块不一致）。mode 的语义照 Mac：auto = 开着且有向量就混合，否则关键词。
     */
    private suspend fun semanticPart(
        ws: File,
        chunks: List<com.xvan.unireader.local.search.WorkspaceSearch.SearchChunk>,
        query: String,
        modeArg: String,
        want: Int,
    ): SemanticPart {
        val SV = com.xvan.unireader.local.search.SemanticVectors
        val ctx = host.appContext
        val notices = ArrayList<String>()
        val on = ctx != null && SV.enabled(ctx)
        val installed = ctx != null && com.xvan.unireader.local.search.EmbedModel.isInstalled(ctx)
        val wid = if (on) withContext(Dispatchers.IO) { com.xvan.unireader.local.server.ServerSyncIO.serverLink(ws)?.workspaceId } else null
        val enabled = on && installed && wid != null
        if (ctx == null || !enabled) {
            // 同 Mac：auto 时安静地退回关键词，明说要 semantic / hybrid 才解释为什么没有
            if (modeArg == "semantic" || modeArg == "hybrid") notices += when {
                !on -> "Semantic search is off (Agent panel ⋯ › Search settings), so this was a keyword search."
                !installed -> "Semantic search is on but the search model has not been downloaded (Agent panel ⋯ › Search settings), so this was a keyword search."
                else -> "Semantic search only works for workspaces on a UniReader server (the passage vectors come from the Mac through the server), so this was a keyword search."
            }
            return SemanticPart("keyword", emptyList(), enabled, 0, 0, null, notices)
        }
        // 顺带看看服务器上有没有新向量（离上次拉超过 5 分钟才真拉，不等它）
        com.xvan.unireader.local.server.ServerSync.pullVectors(ctx, ws, 5 * 60_000L)
        val loaded = withContext(Dispatchers.IO) { SV.vectors(ctx, wid!!) }
        val err = withContext(Dispatchers.IO) { SV.stats(ctx, wid!!).error }
        val matched = withContext(Dispatchers.Default) { chunks.count { loaded.map.containsKey(it.hashHex) } }
        val mode = if (modeArg == "auto") (if (loaded.map.isNotEmpty()) "hybrid" else "keyword") else modeArg
        if (mode == "keyword") return SemanticPart(mode, emptyList(), true, loaded.map.size, matched, err, notices)
        if (loaded.map.isEmpty()) notices += "No passage vectors have been synced from the server yet (the Mac computes them; they arrive after a sync), so only keyword results are shown."
        else if (matched == 0) notices += "None of the searched passages has a vector yet, so only keyword results are shown."
        var hits = emptyList<com.xvan.unireader.local.search.WorkspaceSearch.Hit>()
        var error = err
        if (matched > 0) {
            try {
                val qv = withContext(Dispatchers.IO) { com.xvan.unireader.local.search.QueryEmbedder.embedQuery(ctx, query) }
                val q = com.xvan.unireader.local.search.Semantic.quantize(qv)
                hits = withContext(Dispatchers.Default) {
                    com.xvan.unireader.local.search.Semantic.rank(q, chunks, { loaded.map[it.hashHex] }, want * 3)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: e.javaClass.simpleName
                notices += "Semantic search failed ($error); keyword results only."
            }
        }
        return SemanticPart(mode, hits, true, loaded.map.size, matched, error, notices)
    }

    // ================= 改批注（`../docs/ANDROID-AGENT-PLAN.md §5.7`，同 Mac `MCPFacade+Annotations.updateAnnotations`）=================

    private inner class UpdateAnnotations : T(
        "update_annotations",
        "Change existing annotations of one document in place, keeping their ids: a text note's text, color, style, type or display; a highlight's color or style; a bookmark's title; an image note's caption or display. Take ids from list_annotations. All changes in one call apply together; if any id is unknown or a field does not fit that kind, nothing is changed. Position, quote and handwriting cannot be changed (delete and re-add instead). Change annotations only when the user asks. The user can undo it.",
        req = listOf(
            ToolParameterDescriptor(
                "updates", "changes to apply (at most $MAX_DELETE_IDS); fields left out stay as they are",
                ToolParameterType.List(
                    ToolParameterType.Object(
                        listOf(
                            str("id", "annotation id from list_annotations (full id or its 8-character prefix)"),
                            str("text", "text note: the new note text; image note: the new caption"),
                            str("title", "bookmark: the new title (must not be empty)"),
                            str("color", "text note or highlight: ${HIGHLIGHT_PALETTE.keys.joinToString(", ")} or #RRGGBB; for a text note, \"none\" goes back to the note type's color"),
                            enumOf("style", "text note or highlight: how the selection is drawn", "fill", "underline", "box", "strike"),
                            str("type", "text note: note type name as shown in UniReader (must already exist); \"\" = the generic type"),
                            enumOf("display", "text note or image note: how the bubble opens on the page", "tap", "hover", "always"),
                        ),
                        listOf("id"),
                    ),
                ),
            ),
        ),
        opt = listOf(str("document", "document id (or title); default = the PDF in the current tab")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val list = args.optJSONArray("updates")
            require(list != null && list.length() > 0) { "updates must be a non-empty array of objects" }
            require(list.length() <= MAX_DELETE_IDS) { "at most $MAX_DELETE_IDS updates per call" }
            val doc = resolveDoc(args.optString("document", ""))
            val rows = db("Agent 列批注") { s -> s.notes(doc.id) }.filter { it.kind in ANNOTATION_KINDS }
            val types = db("Agent 读笔记类型") { it.noteTypes() }
            val problems = ArrayList<String>()
            val out = ArrayList<com.xvan.unireader.local.store.LibNote>()
            val report = ArrayList<String>()
            val now = com.xvan.unireader.local.store.nowIso()
            for (i in 0 until list.length()) {
                val u = list.optJSONObject(i) ?: run { problems += "updates[$i] is not an object"; null } ?: continue
                val label = if (list.length() > 1) "updates[$i] " else ""
                val key = u.optString("id").trim().uppercase()
                val hits = if (key.length >= 8) rows.filter { it.id.uppercase().startsWith(key) } else emptyList()
                if (hits.size != 1) {
                    problems += "${label}id '${u.optString("id")}' " + if (hits.isEmpty()) "not found (take ids from list_annotations)" else "matches several annotations; pass the full id"
                    continue
                }
                val n = hits[0]
                if (out.any { it.id == n.id }) { problems += "${label}id '${u.optString("id")}' appears twice"; continue }
                val touched = listOf("text", "title", "color", "style", "type", "display").filter { u.has(it) }
                fun reject(kind: String, allowed: List<String>): Boolean {
                    if (touched.isEmpty()) { problems += "${label}nothing to change for $kind ${u.optString("id")}"; return true }
                    val bad = touched.filter { it !in allowed }
                    if (bad.isNotEmpty()) { problems += "${label}a $kind has no ${bad.joinToString("/")} (it can change ${allowed.joinToString(", ")})"; return true }
                    return false
                }
                fun color(): Pair<Boolean, IntArray?> {   // (ok, rgb；null = none)
                    val v = u.optString("color").trim()
                    if (v.isEmpty() || v.equals("none", true)) return true to null
                    val rgb = HIGHLIGHT_PALETTE.entries.firstOrNull { it.key.equals(v, true) }?.value ?: parseHex(v)
                    if (rgb == null) problems += "${label}color must be one of ${HIGHLIGHT_PALETTE.keys.joinToString(", ")}, #RRGGBB or none"
                    return (rgb != null) to rgb
                }
                fun style(): Int? = when (u.optString("style").lowercase()) {
                    "fill" -> com.xvan.unireader.shared.HL_FILL
                    "underline" -> com.xvan.unireader.shared.HL_UNDERLINE
                    "box" -> com.xvan.unireader.shared.HL_BOX
                    "strike" -> com.xvan.unireader.shared.HL_STRIKE
                    else -> { problems += "${label}style must be fill, underline, box or strike"; null }
                }
                fun displayKey(): String? = u.optString("display").takeIf { it in listOf("tap", "hover", "always") }
                    ?: run { problems += "${label}display must be tap, hover or always"; null }
                val o = runCatching { JSONObject(String(n.payload, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                val kind: String
                when (n.kind) {
                    NoteKind.TEXT -> {
                        kind = "note"
                        if (reject("text note", listOf("text", "color", "style", "type", "display"))) continue
                        val p = TextNotePayload(o)
                        if (u.has("text")) {
                            val t = u.optString("text")
                            if (p.quote.isEmpty() && t.isBlank()) { problems += "${label}a note without a quote needs text"; continue }
                            o.put("text", t)
                        }
                        if (u.has("color")) { val (ok, rgb) = color(); if (!ok) continue; p.withColor(rgb) }
                        if (u.has("style")) { p.withStyle(style() ?: continue) }
                        if (u.has("display")) o.put("display", displayKey() ?: continue)
                        if (u.has("type")) {
                            val name = if (u.isNull("type")) "" else u.optString("type").trim()
                            if (name.isEmpty()) p.withTypeId(null) else {
                                val ty = types.firstOrNull { it.name.equals(name, true) }
                                if (ty == null) { problems += "${label}note type '$name' does not exist; available: ${types.joinToString(", ") { it.name }.ifEmpty { "(none)" }}"; continue }
                                p.withTypeId(ty.id)
                            }
                        }
                    }
                    NoteKind.HIGHLIGHT -> {
                        kind = "highlight"
                        if (reject("highlight", listOf("color", "style"))) continue
                        val p = TextNotePayload(o)
                        if (u.has("color")) {
                            val (ok, rgb) = color(); if (!ok) continue
                            if (rgb == null) { problems += "${label}a highlight needs a color; it cannot be cleared"; continue }
                            p.withColor(rgb)
                        }
                        if (u.has("style")) { p.withStyle(style() ?: continue) }
                    }
                    NoteKind.BOOKMARK -> {
                        kind = "bookmark"
                        if (reject("bookmark", listOf("title"))) continue
                        val t = u.optString("title").trim()
                        if (t.isEmpty()) { problems += "${label}a bookmark title must not be empty"; continue }
                        o.put("title", t)
                    }
                    else -> {
                        kind = "image_note"
                        if (reject("image note", listOf("text", "title", "display"))) continue
                        if (u.has("text") || u.has("title")) o.put("caption", if (u.has("text")) u.optString("text") else u.optString("title"))
                        if (u.has("display")) o.put("display", displayKey() ?: continue)
                    }
                }
                out += n.copy(payload = o.toString().toByteArray(Charsets.UTF_8), updatedAt = now)
                report += "- p.${n.page + 1} $kind [${n.id.take(8)}] ${touched.joinToString(", ")}"
            }
            if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; ") + ". Nothing was changed.")
            writeNotes(doc.id, "Agent 改批注") { s -> s.transaction { out.forEach(s::upsertNote) } }
            main { annotationsChanged(doc.id) }
            return (listOf("Changed ${out.size} annotation(s); ids stay the same.") + report).joinToString("\n")
        }
    }

    // ================= 习题（`../docs/ANDROID-AGENT-PLAN.md §5.8`，同 Mac `MCPTools+Exercises.swift` / `MCPFacade+Exercises.swift`）=================

    private fun exType(e: com.xvan.unireader.local.exercise.Exercise) = when (e.kind) {
        com.xvan.unireader.local.exercise.Exercise.KIND_SINGLE -> "single"
        com.xvan.unireader.local.exercise.Exercise.KIND_MULTIPLE -> "multiple"
        com.xvan.unireader.local.exercise.Exercise.KIND_FILL -> "fill"
        else -> "subjective"
    }

    /** 参考答案一行（同 Mac `referenceAnswerText`）：选择题字母；填空「(1) 2 [also: 2.0]; (2) 4」；主观题正文。没有 → 空串。 */
    private fun referenceAnswer(e: com.xvan.unireader.local.exercise.Exercise): String = when {
        e.isChoice -> e.payload.answer.joinToString("")
        e.isSubjective -> e.payload.answer.filter { it.isNotEmpty() }.joinToString("\n\n")
        !e.hasAnswer -> ""
        else -> (0 until e.blankCount).joinToString("; ") { i ->
            val forms = e.acceptedForms(i)
            "(${i + 1}) ${forms.firstOrNull().orEmpty()}" + if (forms.size > 1) " [also: ${forms.drop(1).joinToString(", ")}]" else ""
        }
    }

    private fun resolveExercises(all: List<com.xvan.unireader.local.exercise.Exercise>, raw: List<String>) =
        resolveIds(raw, all, { it.id }, "list_exercises")

    private inner class ListExercises : T(
        "list_exercises",
        "List the exercises saved in UniReader for a document (or the whole workspace with all_documents), in practice order, with ids, question numbers, stems, options, reference answers and the user's practice stats. With exercise_ids it returns just those questions.",
        opt = listOf(
            str("document", "document id (or title); default = the PDF in the current tab"),
            bool("all_documents", "list every document in the workspace instead of one (default false)"),
            strArray("exercise_ids", "only these exercises (full id or first 8 characters), in this order; other filters are ignored"),
            enumOf("filter", "only the user's mistakes or starred ones (default all)", "all", "mistakes", "starred"),
            int("offset", "skip this many (paging)"),
            int("limit", "at most this many (default 50, max 200)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val offset = maxOf(0, args.optInt("offset", 0))
            val limit = args.optInt("limit", 50).coerceIn(1, 200)
            val filter = args.optString("filter", "all").ifEmpty { "all" }
            require(filter in listOf("all", "mistakes", "starred")) { "filter must be all, mistakes or starred" }
            val idsArg = args.optJSONArray("exercise_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val docs = db("Agent 列文档") { it.allDocuments() }
            val titles = docs.associate { it.id to it.title }
            val docOrder = docs.withIndex().associate { it.value.id to it.index }
            val (list, header) = if (idsArg.isNotEmpty()) {
                val all = db("Agent 列习题") { it.exercises() }
                resolveExercises(all, idsArg) to ""
            } else {
                val doc = if (args.optBoolean("all_documents", false)) null else resolveDoc(args.optString("document", ""))
                val rows = db("Agent 列习题") { it.exercises(doc?.id) }
                    .sortedWith(compareBy({ docOrder[it.documentId] ?: Int.MAX_VALUE }, { it.page ?: -1 }, { it.seq }, { it.createdAt }))
                    .filter { filter == "all" || (filter == "mistakes" && it.wrong) || (filter == "starred" && it.starred) }
                rows to (doc?.let { " in “${it.title}”" } ?: " in the workspace")
            }
            val stats = db("Agent 读作答统计") { it.exerciseAttemptStats() }
            val page = list.drop(offset).take(limit)
            val out = StringBuilder("${list.size} exercise(s)$header; showing ${page.size} from $offset.")
            for (e in page) {
                val st = stats[e.id]
                out.append("\n\n- [${e.id.take(8)}] ${e.id}")
                if (e.documentId != null) out.append(" · “${titles[e.documentId].orEmpty()}”")
                e.page?.let { out.append(" p.${it + 1}") }
                if (e.number.isNotEmpty()) out.append(" #${e.number}")
                out.append(" · ${exType(e)}")
                val ch = (e.chapter + e.section).filter { it.isNotEmpty() }
                if (ch.isNotEmpty()) out.append(" · ${ch.joinToString(" › ")}")
                if (e.wrong) out.append(" · in mistakes")
                if (e.starred) out.append(" · starred")
                out.append("\n  Stem: ${e.payload.stem.trim().take(600)}")
                for (o in e.payload.options) out.append("\n  ${o.key}. ${o.text.trim().take(200)}")
                val ref = referenceAnswer(e)
                out.append("\n  Answer: ${ref.ifEmpty { "—" }.take(400)}${e.payload.answerSource?.takeIf { e.hasAnswer }?.let { " ($it)" } ?: ""}")
                if (st != null && st.attempts > 0) {
                    out.append("\n  Practice: ${st.attempts} attempt(s), ${st.correct} right, ${st.wrong} wrong")
                    st.lastCorrect?.let { out.append(", last ${if (it) "right" else "wrong"}") }
                }
            }
            return out.toString()
        }
    }

    private fun attemptText(a: com.xvan.unireader.local.store.LibExerciseAttempt, e: com.xvan.unireader.local.exercise.Exercise): String {
        val sb = StringBuilder("- attempt ${a.id} · exercise ${e.id.take(8)}${if (e.number.isNotEmpty()) " #${e.number}" else ""} · ${exType(e)} · ${a.createdAt}")
        sb.append(" · ${if (a.source == com.xvan.unireader.local.exercise.Exercise.MARK_SOURCE) "book" else "practice"}")
        sb.append("\n  Stem: ${e.payload.stem.trim().take(600)}")
        sb.append("\n  Reference answer: ${referenceAnswer(e).ifEmpty { "(none)" }.take(800)}")
        if (e.isFill) {
            val blanks = com.xvan.unireader.local.exercise.Exercise.decodeBlanks(a.answer)
            sb.append("\n  User's answer: ${blanks.joinToString(" / ").ifEmpty { "(empty)" }}")
            sb.append(" · blank matches: ${e.blankMatches(blanks).joinToString(", ")}")
        } else {
            sb.append("\n  User's answer: ${a.answer.ifEmpty { "(empty)" }.take(1500)}")
        }
        if (a.hasInk) sb.append("\n  (has handwriting, which cannot be shown on this device)")
        e.payload.explanation?.takeIf { it.isNotBlank() }?.let { sb.append("\n  Explanation: ${it.trim().take(600)}") }
        sb.append("\n  Verdict: " + when (a.correct) {
            null -> "not graded"
            else -> (if (a.correct) "right" else "wrong") + " (by " + when (a.judgedBy) { "self" -> "user"; "agent" -> "agent"; else -> "app" } + ")"
        })
        if (a.feedback.isNotEmpty()) sb.append("\n  Feedback: ${a.feedback.take(600)}")
        return sb.toString()
    }

    private inner class ListExerciseAttempts : T(
        "list_exercise_attempts",
        "List the user's answers to exercises, newest first — with the question, its reference answer and the verdict — so you can grade fill-in-the-blank and subjective answers (then save your verdict with grade_exercise_attempts). Narrow it with exercise_ids or document; ungraded_only lists only fill / subjective answers nobody has graded yet.",
        opt = listOf(
            strArray("exercise_ids", "only answers to these exercises (full id or first 8 characters)"),
            str("document", "only exercises of this document (id or title)"),
            bool("ungraded_only", "only fill / subjective answers that are not graded yet (default false)"),
            int("offset", "skip this many (paging)"),
            int("limit", "at most this many (default 20, max 100)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val offset = maxOf(0, args.optInt("offset", 0))
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val ungraded = args.optBoolean("ungraded_only", false)
            val idsArg = args.optJSONArray("exercise_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val all = db("Agent 列习题") { it.exercises() }
            val exIds = if (idsArg.isNotEmpty()) resolveExercises(all, idsArg).map { it.id } else null
            val docId = if (exIds == null && args.optString("document", "").isNotBlank()) resolveDoc(args.optString("document")).id else null
            val (rows, total) = db("Agent 列作答") { it.exerciseAttemptPage(exIds, docId, ungraded, null, offset, limit) }
            if (total == 0) return if (ungraded) "No ungraded fill / subjective answers." else "No answers yet."
            val byId = all.associateBy { it.id }
            return "$total answer(s); showing ${rows.size} from $offset.\n\n" +
                rows.mapNotNull { a -> byId[a.exerciseId]?.let { attemptText(a, it) } }.joinToString("\n\n")
        }
    }

    private inner class GradeExerciseAttempts : T(
        "grade_exercise_attempts",
        "Save your verdict (right / wrong) and feedback on the user's fill-in-the-blank or subjective answers, after comparing them with the reference answer (list_exercise_attempts). Your verdict replaces any earlier one, including the user's own; a wrong answer puts the question into the user's mistake list. When there is no reference answer, judge it yourself and say so in the feedback. Choice questions are judged by the app and cannot be graded here. Every grade is checked first; if any one is invalid, nothing is saved.",
        req = listOf(
            ToolParameterDescriptor(
                "grades", "verdicts, one per answer (at most 50)",
                ToolParameterType.List(
                    ToolParameterType.Object(
                        listOf(
                            str("attempt_id", "attempt id from list_exercise_attempts (full, or its first 8 characters)"),
                            bool("correct", "your verdict: true = right, false = wrong"),
                            str("feedback", "short feedback for the user (Markdown + \$math\$): what is right, what is missing or wrong, how to fix it; in the user's language"),
                        ),
                        listOf("attempt_id", "correct"),
                    ),
                ),
            ),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val list = args.optJSONArray("grades")
            require(list != null && list.length() > 0) { "grades must be a non-empty array" }
            require(list.length() <= 50) { "at most 50 grades per call" }
            class G(val id: String, val correct: Boolean, val feedback: String)
            val gs = (0 until list.length()).map { i ->
                val o = list.optJSONObject(i) ?: throw IllegalArgumentException("grades[$i] must be an object")
                val id = o.optString("attempt_id").trim()
                require(id.isNotEmpty()) { "grades[$i].attempt_id is required" }
                require(o.has("correct") && !o.isNull("correct")) { "grades[$i].correct is required" }
                G(id, o.optBoolean("correct"), o.optString("feedback", "").trim())
            }
            val result = db("Agent 批改") { s ->
                // 整批先校验（id 对得上、不是选择题），有一条不对就一条都不写（同 Mac）
                val found = gs.map { g ->
                    require(g.id.length >= 8) { "attempt id '${g.id}' is too short; pass at least 8 characters" }
                    val rows = s.exerciseAttemptPage(prefix = g.id, limit = 2).first
                    require(rows.size == 1) { if (rows.isEmpty()) "answer ${g.id} not found; call list_exercise_attempts" else "attempt id '${g.id}' is ambiguous; pass the full id" }
                    val a = rows[0]
                    val e = s.exercise(a.exerciseId) ?: throw IllegalStateException("the exercise of answer ${g.id} is gone; nothing was saved")
                    require(!e.isChoice) { "answer ${a.id.take(8)} is to a ${exType(e)}-choice question, which the app judges by itself; nothing was saved" }
                    a to e
                }
                s.transaction {
                    for ((g, ae) in gs.zip(found)) s.judgeExerciseAttempt(ae.first.id, g.correct, by = "agent", feedback = g.feedback)
                }
                val fresh = s.exerciseAttemptPage(exerciseIds = found.map { it.second.id }.distinct(), limit = 500).first.associateBy { it.id }
                found.mapNotNull { (a, e) -> fresh[a.id]?.let { attemptText(it, e) } }
            }
            main { exercisesChanged() }
            return "Graded ${gs.size} answer(s).\n\n" + result.joinToString("\n\n")
        }
    }

    private inner class OpenExercises : T(
        "open_exercises",
        "Show exercises in UniReader's practice tab: a book's questions, the mistakes (错题本) or the starred ones (收藏) — and stop on one question with exercise_id (e.g. the one you are explaining, or the next one the user should do). With just exercise_id it opens that question in its own book.",
        opt = listOf(
            str("exercise_id", "stop on this question (full id or its first 8 characters)"),
            str("document", "only this book (id or title); default: the book of exercise_id, or every book for mistakes / starred"),
            enumOf("list", "all questions, the mistakes or the starred ones (default all)", "all", "mistakes", "starred"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val list = args.optString("list", "all").ifEmpty { "all" }
            require(list in listOf("all", "mistakes", "starred")) { "list must be all, mistakes or starred" }
            val exArg = args.optString("exercise_id", "").trim()
            val ex = if (exArg.isEmpty()) null else resolveExercises(db("Agent 列习题") { it.exercises() }, listOf(exArg)).single()
            val docId = args.optString("document", "").takeIf { it.isNotBlank() }?.let { resolveDoc(it).id } ?: ex?.documentId
            val scope: com.xvan.unireader.local.exercise.ExScope = when (list) {
                "mistakes" -> if (docId != null) com.xvan.unireader.local.exercise.ExScope.WrongDocument(docId) else com.xvan.unireader.local.exercise.ExScope.Wrong
                "starred" -> if (docId != null) com.xvan.unireader.local.exercise.ExScope.StarredDocument(docId) else com.xvan.unireader.local.exercise.ExScope.Starred
                else -> if (docId != null) com.xvan.unireader.local.exercise.ExScope.Document(docId) else com.xvan.unireader.local.exercise.ExScope.All
            }
            if (ex != null) require(scope.includes(ex)) { "exercise ${ex.id.take(8)} is not in that group (check list / document)" }
            main { openExercises(scope.key, ex?.id) }
            return "Opened the exercises (${scope.key})${ex?.let { " at ${it.id.take(8)}${if (it.number.isNotEmpty()) " #${it.number}" else ""}" } ?: ""}."
        }
    }

    // ================= 日程（`../docs/ANDROID-AGENT-PLAN.md §5.9`，同 Mac `MCPTools+Schedule.swift`）=================

    private fun resolvePlan(all: List<com.xvan.unireader.local.store.LibSchedulePlan>, id: String) =
        all.firstOrNull { it.id.equals(id.trim(), true) }
            ?: id.trim().uppercase().let { k -> if (k.length >= 8) all.filter { it.id.uppercase().startsWith(k) } else emptyList() }.let { hits ->
                require(hits.size == 1) { if (hits.isEmpty()) "no plan with id $id; call list_schedule" else "id $id matches several plans; give the full id" }
                hits[0]
            }

    private fun resolveItem(s: LibraryStore, id: String): com.xvan.unireader.local.store.LibScheduleItem {
        val key = id.trim()
        s.scheduleItem(key)?.let { return it }
        s.scheduleItem(key.uppercase())?.let { return it }
        val hits = if (key.length >= 8) s.scheduleItemsByPrefix(key) else emptyList()
        require(hits.size == 1) { if (hits.isEmpty()) "no schedule item with id $id; call list_schedule" else "id $id matches several items; give the full id" }
        return hits[0]
    }

    private inner class ListSchedule : T(
        "list_schedule",
        "Read the workspace schedule (日程, calendar + to-dos): the plans and the items on each day — what to do, when, how long, whether it is done, and the note the user wrote on how it went. By default: today and the next 13 days, plus every overdue item (not done, earlier day) and the items with no day yet. plan_id lists one plan's items on every day. Use this before planning or rescheduling.",
        opt = listOf(
            str("from", "first day, YYYY-MM-DD (default today)"),
            str("to", "last day, YYYY-MM-DD (default from + 13 days; at most 366 days)"),
            str("plan_id", "only this plan's items (all its days unless from / to are given)"),
            enumOf("status", "which items (default all)", "all", "open", "done"),
            bool("include_overdue", "also list overdue items before from (default true)"),
            bool("include_undated", "also list items with no day (default true)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val D = com.xvan.unireader.local.schedule.ScheduleDay
            val today = D.today()
            val f = args.optString("from", "").trim().ifEmpty { null }
            val t = args.optString("to", "").trim().ifEmpty { null }
            for ((k, v) in listOf("from" to f, "to" to t)) require(v == null || D.isValid(v)) { "$k must be YYYY-MM-DD, got $v" }
            val status = args.optString("status", "all").ifEmpty { "all" }
            require(status in listOf("all", "open", "done")) { "status must be all, open or done" }
            val withOverdue = args.optBoolean("include_overdue", true)
            val withUndated = args.optBoolean("include_undated", true)
            val planArg = args.optString("plan_id", "").trim()
            return db("Agent 读日程") { s ->
                val plans = s.schedulePlans()
                val plan = if (planArg.isEmpty()) null else resolvePlan(plans, planArg)
                var start = f ?: today
                var end = t ?: (D.adding(13, start) ?: start)
                if (end < start) { val x = start; start = end; end = x }
                if ((D.days(start, end) ?: 0) > 366) end = D.adding(366, start) ?: end
                var items: List<com.xvan.unireader.local.store.LibScheduleItem>
                if (plan != null && f == null && t == null) {
                    items = s.scheduleItems(plan.id)
                    val days = items.map { it.day }.filter { it.isNotEmpty() }
                    if (days.isNotEmpty()) { start = days.min(); end = days.max() }
                } else {
                    items = s.scheduleItems(start, end, plan?.id)
                    if (withOverdue) items = s.overdueScheduleItems(minOf(start, today)).filter { plan == null || it.planId == plan.id } + items
                    if (withUndated) items = items + s.undatedScheduleItems().filter { plan == null || it.planId == plan.id }
                }
                val seen = HashSet<String>()
                items = items.filter { seen.add(it.id) }
                items = when (status) { "open" -> items.filter { !it.isDone }; "done" -> items.filter { it.isDone }; else -> items }
                val counts = s.schedulePlanCounts()
                val lines = arrayListOf("Today is $today. Listing $start … $end.")
                val shownPlans = plans.filter { plan == null || it.id == plan.id }
                if (shownPlans.isNotEmpty()) {
                    lines += "Plans:"
                    for (p in shownPlans) {
                        val c = counts[p.id] ?: (0 to 0)
                        val days = s.scheduleItems(p.id).map { it.day }.filter { it.isNotEmpty() }
                        var x = "- ${p.title} (plan_id ${p.id}): ${c.second}/${c.first} done"
                        if (days.isNotEmpty()) x += ", ${days.min()} … ${days.max()}"
                        if (p.goal.isNotEmpty()) x += " — goal: ${p.goal}"
                        lines += x
                    }
                }
                if (items.isEmpty()) lines += "No items."
                val titles = plans.associate { it.id to it.title }
                var lastDay: String? = "\u0000"
                for (m in items.take(500)) {
                    if (m.day != lastDay) { lines += if (m.day.isEmpty()) "\nNo day yet" else "\n${m.day}"; lastDay = m.day }
                    var x = "  [${if (m.isDone) "x" else " "}] "
                    com.xvan.unireader.local.schedule.ScheduleTime.string(m.startMin)?.let { x += "$it " }
                    x += m.title
                    if (m.durationMin > 0) x += " (${com.xvan.unireader.local.schedule.ScheduleTime.durationText(m.durationMin)})"
                    if (!m.isDone && m.day.isNotEmpty() && m.day < today) x += " — overdue"
                    titles[m.planId]?.let { x += " · $it" }
                    x += " · item_id ${m.id}"
                    lines += x
                    if (m.detail.isNotEmpty()) lines += "      " + m.detail.replace("\n", "\n      ")
                    if (m.result.isNotEmpty()) lines += "      result: " + m.result
                }
                if (items.size > 500) lines += "\n(more than 500 items; narrow from / to)"
                lines.joinToString("\n")
            }
        }
    }

    private inner class AddScheduleItems : T(
        "add_schedule_items",
        "Add items to the workspace schedule, e.g. a study plan the user asked for. Give plan (title + goal) to group the items as one new plan; or plan_id to add to an existing plan; or neither for loose items. Each item is one concrete task on one day: a short title, day (YYYY-MM-DD, omit for no day yet), optional start (HH:mm, only when the user gave times), duration_minutes, and detail with exact pages as [[book title#page=N]] links or which exercises to do. Keep each day realistic, and check list_schedule first so you don't duplicate items. The user sees the schedule in UniReader on the Mac (this tablet has no schedule screen yet).",
        req = listOf(
            ToolParameterDescriptor(
                "items", "the items, in order (at most 300)",
                ToolParameterType.List(
                    ToolParameterType.Object(
                        listOf(
                            str("title", "what to do (short)"),
                            str("day", "YYYY-MM-DD; omit or empty = no day yet"),
                            str("start", "HH:mm; omit = any time that day"),
                            int("duration_minutes", "estimated minutes (0–1440)"),
                            str("detail", "details (Markdown, [[book title#page=N]] links)"),
                        ),
                        listOf("title"),
                    ),
                ),
            ),
        ),
        opt = listOf(
            ToolParameterDescriptor(
                "plan", "make a new plan for these items",
                ToolParameterType.Object(listOf(str("title", "plan name, e.g. 第三章复习（两周）"), str("goal", "what it should achieve, by when")), listOf("title")),
            ),
            str("plan_id", "add to this existing plan instead"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val ST = com.xvan.unireader.local.schedule.ScheduleText
            val list = args.optJSONArray("items")
            require(list != null && list.length() > 0) { "items is required" }
            require(list.length() <= 300) { "at most 300 items per call" }
            class Draft(val title: String, val day: String, val start: Int, val dur: Int, val detail: String)
            val drafts = (0 until list.length()).map { i ->
                val o = list.optJSONObject(i) ?: throw IllegalArgumentException("items[$i] must be an object")
                val title = o.optString("title").trim()
                require(title.isNotEmpty()) { "items[$i].title is required" }
                val day = o.optString("day", "").trim()
                require(day.isEmpty() || com.xvan.unireader.local.schedule.ScheduleDay.isValid(day)) { "items[$i].day must be YYYY-MM-DD, got $day" }
                val st = o.optString("start", "").trim()
                val start = if (st.isEmpty()) -1 else com.xvan.unireader.local.schedule.ScheduleTime.minutes(st)
                    ?: throw IllegalArgumentException("items[$i].start must be HH:mm, got $st")
                Draft(title, day, start, o.optInt("duration_minutes", 0).coerceIn(0, 1440), o.optString("detail", ""))
            }
            val planObj = args.optJSONObject("plan")
            val planArg = args.optString("plan_id", "").trim()
            require(planObj == null || planArg.isEmpty()) { "give plan or plan_id, not both" }
            return db("Agent 排日程") { s ->
                require(s.hasScheduleTables()) { "this workspace has no schedule tables (open it once in a newer UniReader)" }
                val now = com.xvan.unireader.local.store.nowIso()
                var pid = ""
                if (planArg.isNotEmpty()) pid = resolvePlan(s.schedulePlans(), planArg).id
                else if (planObj != null) {
                    val t = ST.clean(planObj.optString("title"), ST.TITLE_MAX)
                    require(t.isNotEmpty()) { "plan.title is required" }
                    pid = UUID.randomUUID().toString().uppercase()
                    s.upsertSchedulePlan(com.xvan.unireader.local.store.LibSchedulePlan(pid, t, ST.clean(planObj.optString("goal", ""), ST.DETAIL_MAX), AGENT_NAME, now, now))
                }
                // 同 Mac `addScheduleItems`：同一天里新加的排在已有的后面、按给的先后；created_at 依次加 1 毫秒保住先后
                val nextSeq = HashMap<String, Int>()
                val base = java.time.Instant.now()
                val out = drafts.mapIndexed { i, d ->
                    val seq = nextSeq[d.day] ?: s.nextScheduleSeq(d.day)
                    nextSeq[d.day] = seq + 1
                    val at = com.xvan.unireader.local.store.Iso.string(base.plusMillis(i.toLong()))
                    com.xvan.unireader.local.store.LibScheduleItem(
                        UUID.randomUUID().toString().uppercase(), pid, ST.clean(d.title, ST.TITLE_MAX), ST.clean(d.detail, ST.DETAIL_MAX),
                        d.day, if (d.start in 0 until 1440) d.start else -1, d.dur, "", "", seq, AGENT_NAME, at, at,
                    )
                }
                s.upsertScheduleItems(out)
                "Added ${out.size} item(s)${if (pid.isNotEmpty()) " to plan $pid" else ""}. Item ids: ${out.joinToString(", ") { it.id }}"
            }
        }
    }

    private inner class UpdateScheduleItems : T(
        "update_schedule_items",
        "Change existing schedule items: reschedule (day, or empty day for no day; start, or empty start for any time), rename, rewrite details, set duration, tick off or reopen (done), write the result note, or move to another plan (plan_id, empty = none). Only the fields you give change. plans renames a plan or rewrites its goal. Move overdue items forward instead of adding duplicates.",
        opt = listOf(
            ToolParameterDescriptor(
                "items", "items to change (at most 300)",
                ToolParameterType.List(
                    ToolParameterType.Object(
                        listOf(
                            str("item_id", "item id (full, or its first 8 characters)"),
                            str("title", "new title"), str("detail", "new details"),
                            str("day", "YYYY-MM-DD, or empty for no day"), str("start", "HH:mm, or empty for any time"),
                            int("duration_minutes", "estimated minutes (0 = not given)"),
                            bool("done", "true = tick off, false = reopen"), str("result", "note on how it went"),
                            str("plan_id", "move to this plan; empty = no plan"),
                        ),
                        listOf("item_id"),
                    ),
                ),
            ),
            ToolParameterDescriptor(
                "plans", "plans to rename or re-goal",
                ToolParameterType.List(ToolParameterType.Object(listOf(str("plan_id", "plan id"), str("title", "new name"), str("goal", "new goal")), listOf("plan_id"))),
            ),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val ST = com.xvan.unireader.local.schedule.ScheduleText
            val items = args.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            val plans = args.optJSONArray("plans")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            require(items.isNotEmpty() || plans.isNotEmpty()) { "give items or plans" }
            require(items.size <= 300) { "at most 300 items per call" }
            return db("Agent 改日程") { s ->
                val allPlans = s.schedulePlans()
                val now = com.xvan.unireader.local.store.nowIso()
                // 先全部校验、拼好，再一次写（一项不对整批不动，同 Mac）
                val changed = items.mapIndexed { i, o ->
                    val id = o.optString("item_id").trim()
                    require(id.isNotEmpty()) { "items[$i].item_id is required" }
                    var m = resolveItem(s, id)
                    if (o.has("title")) { val t = o.optString("title"); require(t.isNotBlank()) { "items[$i].title can't be empty" }; m = m.copy(title = t) }
                    if (o.has("detail")) m = m.copy(detail = o.optString("detail"))
                    if (o.has("day")) {
                        val d = o.optString("day").trim()
                        require(d.isEmpty() || com.xvan.unireader.local.schedule.ScheduleDay.isValid(d)) { "items[$i].day must be YYYY-MM-DD or empty" }
                        m = m.copy(day = d)
                    }
                    if (o.has("start")) {
                        val st = o.optString("start").trim()
                        m = m.copy(startMin = if (st.isEmpty()) -1 else com.xvan.unireader.local.schedule.ScheduleTime.minutes(st)
                            ?: throw IllegalArgumentException("items[$i].start must be HH:mm or empty"))
                    }
                    if (o.has("duration_minutes")) m = m.copy(durationMin = o.optInt("duration_minutes").coerceIn(0, 1440))
                    if (o.has("done")) { val d = o.optBoolean("done"); if (d != m.isDone) m = m.copy(doneAt = if (d) now else "") }
                    if (o.has("result")) m = m.copy(result = o.optString("result"))
                    if (o.has("plan_id")) { val p = o.optString("plan_id").trim(); m = m.copy(planId = if (p.isEmpty()) "" else resolvePlan(allPlans, p).id) }
                    m
                }
                val planChanges = plans.mapIndexed { i, o ->
                    val id = o.optString("plan_id").trim()
                    require(id.isNotEmpty()) { "plans[$i].plan_id is required" }
                    val p = resolvePlan(allPlans, id)
                    var n = p
                    if (o.has("title")) { val t = ST.clean(o.optString("title"), ST.TITLE_MAX); require(t.isNotEmpty()) { "plans[$i].title can't be empty" }; n = n.copy(title = t) }
                    if (o.has("goal")) n = n.copy(goal = ST.clean(o.optString("goal"), ST.DETAIL_MAX))
                    p to n
                }
                // 写：同 Mac `updateScheduleItems`——规整、跟库里一样的不写、挪到别的日子的排到那天最后
                val nextSeq = HashMap<String, Int>()
                val out = ArrayList<com.xvan.unireader.local.store.LibScheduleItem>()
                for (m0 in changed) {
                    val old = s.scheduleItem(m0.id) ?: continue
                    var m = m0.copy(
                        title = ST.clean(m0.title, ST.TITLE_MAX).ifEmpty { old.title },
                        detail = ST.clean(m0.detail, ST.DETAIL_MAX), result = ST.clean(m0.result, ST.DETAIL_MAX),
                        createdAt = old.createdAt, updatedAt = old.updatedAt,
                    )
                    if (m == old) continue
                    if (m.day != old.day && m.seq == old.seq) {
                        val n = nextSeq[m.day] ?: s.nextScheduleSeq(m.day)
                        m = m.copy(seq = n); nextSeq[m.day] = n + 1
                    }
                    out += m.copy(updatedAt = now)
                }
                var plansUpdated = 0
                s.transaction {
                    s.upsertScheduleItems(out)
                    for ((p, n) in planChanges) if (n != p) { s.upsertSchedulePlan(n.copy(updatedAt = now)); plansUpdated++ }
                }
                "Changed ${out.size} item(s) (${changed.size - out.size} already so), $plansUpdated plan(s)."
            }
        }
    }

    private inner class DeleteSchedule : T(
        "delete_schedule",
        "Delete schedule items, or whole plans together with all their items (they go to UniReader's Trash and can be restored there). Prefer update_schedule_items to reschedule or rewrite; delete only what the user no longer wants, or a plan you are replacing at their request.",
        opt = listOf(strArray("item_ids", "items to delete (full id or first 8 characters)"), strArray("plan_ids", "plans to delete with all their items")),
    ) {
        override suspend fun run(args: JSONObject): String {
            fun arr(k: String) = args.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val itemArgs = arr("item_ids")
            val planArgs = arr("plan_ids")
            require(itemArgs.isNotEmpty() || planArgs.isNotEmpty()) { "give item_ids or plan_ids" }
            val ws = main { workspaceDir } ?: throw IllegalStateException("No workspace is open.")
            // 先认出要删的（有一个对不上就报错、一样都不删），再取回收站条目名（要随系统语言），最后先归档后删
            class Pick(val plans: List<com.xvan.unireader.local.store.LibSchedulePlan>, val items: List<com.xvan.unireader.local.store.LibScheduleItem>, val total: Int)
            val pick = db("Agent 认日程") { s ->
                val allPlans = s.schedulePlans()
                val plans = planArgs.map { resolvePlan(allPlans, it) }.distinctBy { it.id }
                val planSet = plans.map { it.id }.toSet()
                val items = itemArgs.map { resolveItem(s, it) }.distinctBy { it.id }.filter { it.planId !in planSet }
                Pick(plans, items, items.size + plans.sumOf { s.scheduleItems(it.id).size })
            }
            val title = when {
                pick.plans.size == 1 && pick.items.isEmpty() -> pick.plans[0].title
                pick.plans.isEmpty() && pick.items.size == 1 -> pick.items[0].title
                pick.items.isEmpty() -> main { string(com.xvan.unireader.R.string.agent_trash_plans, pick.plans.size) }
                else -> main { string(com.xvan.unireader.R.string.agent_trash_schedule_items, pick.items.size + pick.plans.size) }
            }
            db("Agent 删日程") { s -> com.xvan.unireader.local.Trash.deleteSchedule(s, ws, pick.plans.map { it.id }, pick.items.map { it.id }, title) }
            return "Moved ${pick.total} item(s) and ${pick.plans.size} plan(s) to the Trash."
        }
    }

    // ================= Markdown 笔记：打开 / 改名 / 删除（`../docs/ANDROID-AGENT-PLAN.md §5.10`，同 Mac `MCPTools+Markdown.swift`）=================

    private suspend fun existingNote(ref: String): Pair<File, String> {
        val ws = main { workspaceDir } ?: throw IllegalStateException("No workspace is open.")
        val rel = ref.trim().trimStart('/')
        val f = noteFile(ws, rel)
        require(f.isFile) { "No note \"$rel\"; call list_markdown_notes." }
        return ws to rel
    }

    private fun brokenText(broken: List<Pair<String, String>>): String =
        if (broken.isEmpty()) "No [[links]] in other notes broke."
        else "These [[links]] in other notes no longer resolve (UniReader does not rewrite note text; fix them with edit_markdown only if the user asks):\n" +
            broken.joinToString("\n") { "- ${it.first}: [[${it.second}]]" }

    private inner class OpenMarkdown : T(
        "open_markdown",
        "Show an existing Markdown note in a tab (switches to it if a tab already shows it). Use it to bring the user to a note you are talking about.",
        req = listOf(str("note_ref", "path relative to the Notes folder, from list_markdown_notes")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val (_, rel) = existingNote(args.optString("note_ref"))
            main { openMarkdown(rel) }
            return "Opened $rel."
        }
    }

    private inner class RenameMarkdown : T(
        "rename_markdown",
        "Rename a Markdown note and/or move it to another folder inside the Notes folder. The title is the file name, so this renames the .md file; the text is not changed. A tab showing the note follows it, and unsaved typing is saved first. [[wiki links]] in other notes that use the old name break: UniReader never rewrites note text by itself, so the result lists the notes whose links broke — tell the user, and fix those links with edit_markdown only if the user asks. Moving to another folder keeps the name, so [[name]] links keep working. If a note with the new name already exists there, nothing is changed. Rename or move notes only when the user asks.",
        req = listOf(str("note_ref", "the note to rename, from list_markdown_notes")),
        opt = listOf(
            str("title", "new title (file name without .md); default = keep the name"),
            str("folder", "move into this folder (relative to the Notes folder; \"\" = the top); default = stay in its folder"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val (ws, rel) = existingNote(args.optString("note_ref"))
            val title = args.optString("title", "").trim().ifEmpty { MdNotes.Item(rel).title }
            val folder = if (args.has("folder") && !args.isNull("folder")) args.optString("folder") else null
            require(args.optString("title", "").isNotBlank() || folder != null) { "give title and/or folder" }
            val before = withContext(Dispatchers.IO) { MdNotes.backlinks(ws, rel) }
            val newRel = withContext(Dispatchers.Main) {
                withTimeout(30_000) { suspendCancellableCoroutine<String?> { c -> host.markdownRename(rel, title, folder) { r -> if (c.isActive) c.resume(r) } } }
            } ?: throw IllegalStateException("could not rename $rel: a note with that name already exists there, or the name is not allowed. Nothing was changed.")
            if (newRel == rel) return "Nothing to change: the note is already $rel."
            val broken = withContext(Dispatchers.IO) {
                val idx = MdNotes.Index(MdNotes.scan(ws))
                before.filter { (from, target) -> idx.note(target) != newRel && from != newRel }
            }
            return "Renamed $rel → $newRel (note_ref $newRel). " + brokenText(broken)
        }
    }

    private inner class DeleteMarkdown : T(
        "delete_markdown",
        "Delete a Markdown note: it moves to the Notes folder's .trash folder (the same place Obsidian uses), where the user can take it back. Unsaved typing is saved first, a tab showing it closes. [[wiki links]] to it in other notes break and are left unchanged; the result lists them. Delete notes only when the user explicitly asks.",
        req = listOf(str("note_ref", "the note to delete, from list_markdown_notes")),
    ) {
        override suspend fun run(args: JSONObject): String {
            val (ws, rel) = existingNote(args.optString("note_ref"))
            val before = withContext(Dispatchers.IO) { MdNotes.backlinks(ws, rel) }
            val ok = withContext(Dispatchers.Main) {
                withTimeout(30_000) { suspendCancellableCoroutine<Boolean> { c -> host.markdownDelete(rel) { r -> if (c.isActive) c.resume(r) } } }
            }
            if (!ok) throw IllegalStateException("could not move $rel to the trash; nothing was deleted")
            return "Moved $rel to Notes/.trash. " + brokenText(before)
        }
    }

    // ================= 书：目录 / 改名改分组（`../docs/ANDROID-AGENT-PLAN.md §5.11`）=================

    private inner class GetDocument : T(
        "get_document",
        "A PDF's page count, group, reading progress, whether it has cached OCR text, and its table of contents (outline) with 1-based pages — use the outline to find a chapter's pages before read_pages or goto. Opens the book in a tab if needed (the outline is read from the PDF).",
        opt = listOf(
            str("document", "document id (or title); default = the PDF in the current tab"),
            bool("include_toc", "include the outline (default true)"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val doc = resolveDoc(args.optString("document", ""))
            val ocrPages = db("Agent 数 OCR 页") { s ->
                s.variants(doc.id).map { it.contentHash }.firstNotNullOfOrNull { h -> s.ocrProvider(h)?.let { p -> s.ocrStamp(h, p).substringBefore('|').toIntOrNull() } } ?: 0
            }
            val sb = StringBuilder("“${doc.title}” (document_id ${doc.id}): ${doc.pageCount} pages")
            if (doc.group.isNotEmpty()) sb.append(", group ${doc.group}")
            sb.append(", read up to page ${doc.readPage + 1}, OCR text on $ocrPages page(s).")
            if (args.optBoolean("include_toc", true)) {
                loaded(doc)
                val toc = main { documentToc(doc.id) }.orEmpty()
                if (toc.isEmpty()) sb.append("\nThe PDF has no outline.")
                else {
                    sb.append("\nOutline:")
                    for (t in toc.take(500)) sb.append("\n").append("  ".repeat(t.depth)).append("- ${t.label} (p.${t.page + 1})")
                    if (toc.size > 500) sb.append("\n(… ${toc.size - 500} more entries)")
                }
            }
            return sb.toString()
        }
    }

    private inner class UpdateDocument : T(
        "update_document",
        "Change how a PDF appears in the workspace library: its display title (the file on disk keeps its name), and/or its group (\"\" = no group; a new group name creates the group). Several documents can be moved to a group at once with document_ids. Do this only when the user asks.",
        opt = listOf(
            str("document", "document id (or title) to change; default = the PDF in the current tab"),
            strArray("document_ids", "several documents (group change only)"),
            str("title", "new display title (one document only)"),
            str("group", "group to put the document(s) in; \"\" = no group"),
        ),
    ) {
        override suspend fun run(args: JSONObject): String {
            val many = args.optJSONArray("document_ids")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val title = if (args.has("title")) args.optString("title").trim() else null
            val group = if (args.has("group") && !args.isNull("group")) args.optString("group").trim() else null
            require(title != null || group != null) { "give title and/or group" }
            require(title == null || title.isNotEmpty()) { "title must not be empty" }
            require(title == null || many.size <= 1) { "title changes one document at a time" }
            val docs = if (many.isNotEmpty()) many.map { resolveDoc(it) }.distinctBy { it.id } else listOf(resolveDoc(args.optString("document", "")))
            db("Agent 改书") { s ->
                s.transaction {
                    for (d in docs) {
                        if (title != null) s.renameDocument(d.id, title)
                        if (group != null) s.setDocumentGroup(d.id, group)
                    }
                }
            }
            main { libraryChanged() }
            val after = db("Agent 列文档") { it.allDocuments() }
            val ids = docs.map { it.id }.toSet()
            val groups = after.map { it.group }.filter { it.isNotEmpty() }.distinct().sorted()
            return (listOf("Updated ${docs.size} document(s):") + after.filter { it.id in ids }.map { d ->
                "- “${d.title}” · group ${d.group.ifEmpty { "(no group)" }} · ${d.id}"
            } + "Groups now: ${groups.joinToString(", ").ifEmpty { "(none)" }}").joinToString("\n")
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
            main { annotationsChanged(doc.id) }
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

        /** 新存一份上下文（同 Mac `WorkspaceManager.addAgentContext`）：标题规整，规整完是空的用 [fallbackTitle]。在库队列上调。 */
        fun addContext(s: LibraryStore, title: String, summary: String, agent: String, fallbackTitle: String): com.xvan.unireader.local.store.LibAgentContext {
            val now = com.xvan.unireader.local.store.nowIso()
            val t = AgentContextText.cleanTitle(title).ifEmpty { fallbackTitle }
            val c = com.xvan.unireader.local.store.LibAgentContext(UUID.randomUUID().toString().uppercase(), t, summary.trim(), agent, "", now, now)
            s.upsertAgentContext(c)
            return c
        }

        /** 改标题 / 摘要（同 Mac `updateAgentContext`）：没变就不写，免得 `updated_at` 白变、同步白传。在库队列上调。 */
        fun updateContext(s: LibraryStore, c: com.xvan.unireader.local.store.LibAgentContext, title: String?, summary: String?): com.xvan.unireader.local.store.LibAgentContext {
            val t = title?.let(AgentContextText::cleanTitle)?.ifEmpty { c.title } ?: c.title
            val sm = summary?.trim() ?: c.summary
            if (t == c.title && sm == c.summary) return c
            val n = c.copy(title = t, summary = sm, updatedAt = com.xvan.unireader.local.store.nowIso())
            s.upsertAgentContext(n)
            return n
        }

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
