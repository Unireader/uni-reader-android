package com.xvan.unireader.local.search

import com.xvan.unireader.local.MdNotes
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.TextNotePayload
import com.xvan.unireader.shared.OcrWatermark
import org.json.JSONObject
import java.io.File

/**
 * 全工作区搜索（关键词版，`../docs/ANDROID-AGENT-PLAN.md §5.6`）。照 Mac `Sources/Search/`：内容切成块（[SearchChunk]）→
 * 挑候选（所有词都出现；一个都没有且不止一个词时退回「任一词」）→ 打分（[SearchText.score]）→ 同一页 / 同一段只留一块（[dedupe]）。
 *
 * 与 Mac 的差别：**不建持久索引**，每次现场取内容（库里的行 + `Notes/` 的文件）；PDF 的 OCR 文字按「内容 hash + OCR 版本」
 * 在进程里缓存（[pdfCache]），同一本书第二次搜不再解析几 MB 的 OCR JSON。语义那一路在 [Semantic]（块向量从服务器同步来，
 * `../docs/SEMANTIC-SYNC-PLAN.md §4`），只对服务器工作区。
 */
object WorkspaceSearch {

    val KINDS = listOf("pdf", "markdown", "annotation", "exercise", "context", "memory", "conversation")

    class SearchChunk(
        val kind: String,
        val owner: String,
        val sub: String = "",
        val page: Int? = null,
        val line: Int? = null,
        val title: String,
        val heading: String = "",
        val body: String,
    ) {
        val norm: String by lazy { SearchText.normalize(body) }
        val titleNorm: String by lazy { SearchText.normalize(title) }
        /** 正文的 SHA-256（十六进制），服务器同步来的块向量按它认（`Semantic.hash`）。PDF 的块随 [pdfCache] 一起缓存，算一次留着。 */
        val hashHex: String by lazy { Semantic.hex(Semantic.hash(body)) }
    }

    class Hit(val chunk: SearchChunk, val score: Double)

    class Result(val hits: List<Hit>, val terms: List<String>, val relaxed: Boolean)

    /** 关键词搜索（同 Mac `SearchIndex.keyword`）。 */
    fun keyword(chunks: List<SearchChunk>, query: String, limit: Int): Result {
        val terms = SearchText.terms(query)
        if (terms.isEmpty()) return Result(emptyList(), terms, false)
        fun has(c: SearchChunk, t: String) = c.norm.contains(t) || c.titleNorm.contains(t)
        var rows = chunks.filter { c -> terms.all { has(c, it) } }
        var relaxed = false
        if (rows.isEmpty() && terms.size > 1) {
            rows = chunks.filter { c -> terms.any { has(c, it) } }
            relaxed = true
        }
        val df = HashMap<String, Int>()
        for (r in rows) for (t in terms) if (r.norm.contains(t)) df[t] = (df[t] ?: 0) + 1
        val hits = rows.map { Hit(it, SearchText.score(it.norm, it.titleNorm, terms, df, rows.size)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
        return Result(dedupe(hits, limit), terms, relaxed)
    }

    /** PDF 同一页、笔记同一段只留分最高的一块（同 Mac `SearchIndex.dedupe`）。 */
    fun dedupe(hits: List<Hit>, limit: Int): List<Hit> {
        val seen = HashSet<String>()
        val out = ArrayList<Hit>()
        for (h in hits) {
            if (!seen.add(dedupeKey(h.chunk))) continue
            out.add(h)
            if (out.size >= limit) break
        }
        return out
    }

    /** 「同一处」的键：PDF 同一页、批注同一条、习题 / 上下文同一份、其余同一段（去重与两路合并共用，同 Mac `dedupe` / `fuse`）。 */
    fun dedupeKey(c: SearchChunk): String = when (c.kind) {
        "pdf" -> "pdf|${c.owner}|${c.page ?: -1}"
        "annotation" -> "ann|${c.sub}"
        "exercise" -> "ex|${c.owner}"
        "context" -> "ctx|${c.owner}"
        else -> "${c.kind}|${c.owner}|${c.line ?: -1}"
    }

    // ---------------- 取内容（同 Mac `SearchSources`）----------------

    /** 一本书的 PDF 块 + 没有 OCR 文字的页（调用方可以拿 PDF 自带文字层补）。 */
    class PdfPart(val chunks: List<SearchChunk>, val missingPages: List<Int>)

    /** 一本书切好、归一化好的块（[SearchChunk.norm] 是懒算的，算过一次就留在块上）+ 有文字的页。 */
    private class Cached(val stamp: String, val chunks: List<SearchChunk>, val pages: Set<Int>)

    /**
     * 「文档 id + 标题 + 内容 hash」→ 那份 OCR 切好的块（文字滤过水印，同 `read_pages` 与画布划字那一套）。
     * 缓存块而不只是原文：2000 页实测只缓存原文时每次还要 1.3 秒（切块 + 归一化），缓存块之后只剩比对。
     */
    private val pdfCache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Cached>?) = size > 12
    }

    /**
     * 一本书：OCR 文字够 8 个字的页成块，其余页记进 [PdfPart.missingPages]。**在库队列线程上调。**
     * 用有 OCR 的那个版本的 hash（一本书几个版本时）。
     */
    fun pdfPart(s: LibraryStore, documentId: String, title: String, pageCount: Int): PdfPart {
        var got: Cached? = null
        for (h in s.variants(documentId).map { it.contentHash }) {
            val provider = s.ocrProvider(h) ?: continue
            val key = "$documentId|$title|$h"
            val stamp = "$provider|" + s.ocrStamp(h, provider)
            got = synchronized(pdfCache) { pdfCache[key] }?.takeIf { it.stamp == stamp } ?: run {
                val raw = s.ocrRuns(h, provider)
                val profile = OcrWatermark.buildProfile(raw)
                val chunks = ArrayList<SearchChunk>()
                val pages = HashSet<Int>()
                for ((p, runs) in raw) {
                    val t = OcrWatermark.visible(runs, profile).joinToString("\n") { it.text }
                    if (t.length < 8) continue
                    pages.add(p)
                    chunks += pageChunks(documentId, title, p, t)
                }
                chunks.forEach { it.norm; it.titleNorm }   // 在库队列线程上一次算好，之后每次搜索只比对
                Cached(stamp, chunks, pages).also { c -> synchronized(pdfCache) { pdfCache[key] = c } }
            }
            break
        }
        val pages = got?.pages.orEmpty()
        return PdfPart(got?.chunks.orEmpty(), (0 until pageCount).filter { it !in pages })
    }

    fun pageChunks(documentId: String, title: String, page: Int, text: String): List<SearchChunk> =
        SearchText.chunks(text).map { SearchChunk("pdf", documentId, page = page, title = title, body = it) }

    /** 一本书的批注：批注（引文 + 正文）/ 高亮 / 书签 / 图片说明。**在库队列线程上调。** */
    fun annotationChunks(s: LibraryStore, documentId: String, title: String): List<SearchChunk> =
        s.notes(documentId).mapNotNull { n ->
            val o = runCatching { JSONObject(String(n.payload, Charsets.UTF_8)) }.getOrDefault(JSONObject())
            val (kind, body) = when (n.kind) {
                NoteKind.TEXT -> TextNotePayload(o).let { "note" to listOf(it.quote, it.text).filter { x -> x.isNotEmpty() }.joinToString("\n") }
                NoteKind.HIGHLIGHT -> "highlight" to o.optString("quote")
                NoteKind.BOOKMARK -> "bookmark" to o.optString("title")
                NoteKind.IMAGE -> "image_note" to o.optString("caption")
                else -> return@mapNotNull null
            }
            val b = body.trim()
            if (b.isEmpty()) null else SearchChunk("annotation", documentId, sub = n.id, page = n.page, title = title, heading = kind, body = b)
        }

    /** 习题（题干 / 选项 / 答案 / 解析 / 题目笔记；本端模型没有标签）。**在库队列线程上调。** */
    fun exerciseChunks(s: LibraryStore, docTitles: Map<String, String>): List<SearchChunk> {
        if (!s.hasExerciseTables()) return emptyList()
        return s.exercises().map { e ->
            val parts = ArrayList<String>()
            parts.add(e.payload.stem)
            e.payload.options.forEach { parts.add("${it.key}. ${it.text}") }
            if (e.payload.answer.isNotEmpty()) parts.add("答案 Answer: " + e.payload.answer.joinToString(", "))
            e.payload.explanation?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            if (e.note.isNotEmpty()) parts.add(e.note)
            val docTitle = e.documentId?.let { docTitles[it] }.orEmpty()
            val number = if (e.number.isEmpty()) "" else "${e.number} "
            SearchChunk(
                "exercise", e.id, sub = e.documentId?.let { "doc:$it" }.orEmpty(), page = e.page,
                title = (if (docTitle.isEmpty()) number else "$docTitle · $number").trim(),
                heading = (e.chapter + e.section).filter { it.isNotEmpty() }.joinToString(" › "),
                body = parts.filter { it.isNotEmpty() }.joinToString("\n"),
            )
        }
    }

    /** 记忆 / 上下文 / 对话记录。**在库队列线程上调。** */
    fun agentChunks(s: LibraryStore, kinds: Set<String>): List<SearchChunk> {
        val out = ArrayList<SearchChunk>()
        if ("memory" in kinds) for (m in s.agentMemories()) {
            val title = m.content.lineSequence().firstOrNull().orEmpty().take(40)
            SearchText.chunks(m.content).forEach { out += SearchChunk("memory", m.id, title = title, heading = m.agent, body = it) }
        }
        if ("context" in kinds) for (c in s.agentContexts()) {
            SearchText.chunks(c.title + "\n\n" + c.summary).forEach { out += SearchChunk("context", c.id, title = c.title, heading = c.agent, body = it) }
        }
        if ("conversation" in kinds) for (c in s.recentAgentConversations(1_000_000)) {
            val title = com.xvan.unireader.local.agent.AgentToolset.conversationTitle(c.firstUserText)
            for ((i, t) in s.agentTurns(c.id).withIndex()) {
                SearchText.chunks("User: ${t.userText}\n\nAgent: ${t.replyText}").forEach {
                    out += SearchChunk("conversation", c.id, line = i + 1, title = title, heading = t.agent, body = it)
                }
            }
        }
        return out
    }

    /** `Notes/` 里的 Markdown（[folder] 非空 = 只要这个目录下的）。读文件，任何线程都行。 */
    fun markdownChunks(ws: File, folder: String): List<SearchChunk> {
        val prefix = folder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        val root = MdNotes.root(ws)
        return MdNotes.scan(ws).notes.filter { it.relPath.startsWith(prefix) }.flatMap { n ->
            val text = runCatching { File(root, n.relPath).readText() }.getOrNull() ?: return@flatMap emptyList()
            SearchText.markdownChunks(text).map { SearchChunk("markdown", n.relPath, line = it.line, title = n.title, heading = it.heading, body = it.text) }
        }
    }
}
