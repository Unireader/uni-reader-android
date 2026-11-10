package com.xvan.unireader.shared

/**
 * 全文搜索的纯逻辑（模式1，规则照 Mac `Sources/App/TextSearch.swift`，本端 `TextSearchTest`）：
 *
 * - 一页的文字**去掉空白、折大小写**后找，所以跨行断开的词（「操作系」+「统」）也找得到；一页里出现几次就是几处。
 * - 两层文字：OCR 层（库里 `ocr_page`，已滤水印的可见行按阅读顺序接起来）与 PDF 自带文字层；
 *   **已有 OCR 命中的页不再报自带文字层的命中**（扫描书的隐形文字层和 OCR 是同一段字，报两遍就重了）。
 * - 结果按「页 → 页内先后」排；摘要 = 命中前 [SNIPPET_BEFORE] 个字 + 命中 + 后 [SNIPPET_AFTER] 个字（空白并成一个空格）。
 */
object TextSearch {
    const val SNIPPET_BEFORE = 16
    const val SNIPPET_AFTER = 40
    /** 一次搜索最多给多少处（再多列表也翻不过来）。 */
    const val MAX_HITS = 500

    /**
     * 一处命中：页（0 起）、页内纵向比例（跳过去停在这儿；-1 = 不知道，由调用方另算）、摘要与摘要里命中那一段的位置。
     * [origin] = 命中开头在这一页原文里的字符位置（自带文字层用它去问 Pdfium 字符框）。
     */
    data class Hit(
        val page: Int, val frac: Float, val snippet: String, val hitStart: Int, val hitEnd: Int, val origin: Int,
        /** 命中在原文里占几个字符（自带文字层问行框用）。 */
        val length: Int = 0,
        /** 命中的行框（归一化 x,y,w,h，一行一个）；OCR 层当场算好，自带文字层由调用方补。 */
        val rects: List<FloatArray> = emptyList(),
    )

    /** 折好的一页：`hay` = 去空白 + 小写后的字，`at[i]` = 第 i 个字在原文里的位置。 */
    class Folded(val text: String, val hay: String, val at: IntArray)

    fun fold(text: String): Folded {
        val sb = StringBuilder(text.length)
        val at = IntArray(text.length)
        var n = 0
        for ((i, ch) in text.withIndex()) {
            if (ch.isWhitespace() || ch == ' ' || ch == '　') continue
            sb.append(ch.lowercaseChar())
            at[n++] = i
        }
        return Folded(text, sb.toString(), at.copyOf(n))
    }

    fun foldQuery(q: String): String = fold(q).hay

    /** 在折好的一页里找 [needle]（已折）的全部出现处，返回每处在原文里的 [起, 止) 位置。 */
    fun find(page: Folded, needle: String): List<IntRange> {
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val i = page.hay.indexOf(needle, from)
            if (i < 0) break
            out += page.at[i]..page.at[i + needle.length - 1]
            from = i + needle.length
        }
        return out
    }

    /** 摘要：原文里 [r] 前后各取几个字，空白并成一个空格；返回（摘要，命中在摘要里的起止）。两头截断处加 `…`。 */
    fun snippet(text: String, r: IntRange): Triple<String, Int, Int> {
        fun squash(s: String) = s.replace(Regex("\\s+"), " ")
        val lo = maxOf(0, r.first - SNIPPET_BEFORE)
        val hi = minOf(text.length, r.last + 1 + SNIPPET_AFTER)
        val pre = (if (lo > 0) "…" else "") + squash(text.substring(lo, r.first)).trimStart()
        val mid = squash(text.substring(r.first, r.last + 1))
        val post = squash(text.substring(r.last + 1, hi)).trimEnd() + if (hi < text.length) "…" else ""
        return Triple(pre + mid + post, pre.length, pre.length + mid.length)
    }

    /** OCR 层的一页：可见行按给来的顺序用换行接起来；命中的纵向位置 = 命中开头所在那一行的上沿。 */
    fun searchOcrPage(page: Int, runs: List<TextRun>, needle: String, into: MutableList<Hit>) {
        if (runs.isEmpty()) return
        val starts = IntArray(runs.size)
        val sb = StringBuilder()
        for ((i, r) in runs.withIndex()) {
            starts[i] = sb.length
            sb.append(r.text).append('\n')
        }
        val text = sb.toString()
        for (r in find(fold(text), needle)) {
            var line = starts.binarySearch(r.first)
            if (line < 0) line = -line - 2
            line = line.coerceIn(0, runs.size - 1)
            val (s, a, b) = snippet(text, r)
            // 命中框：命中跨到的每一行各按字符裁一段（`OcrTextSelect.clip`，同划字）；裁不出来就整行
            val rects = ArrayList<FloatArray>()
            var li = line
            while (li < runs.size && starts[li] <= r.last) {
                val run = runs[li]
                val lo = (r.first - starts[li]).coerceIn(0, run.text.length)
                val hi = (r.last + 1 - starts[li]).coerceIn(0, run.text.length)
                if (hi > lo) {
                    val c = OcrTextSelect.clip(run, run.text.codePointCount(0, lo), run.text.codePointCount(0, hi)) ?: run
                    rects += floatArrayOf(c.x, c.y, c.w, c.h)
                }
                li++
            }
            into += Hit(page, runs[line].y.coerceIn(0f, 1f), s, a, b, r.first, r.last + 1 - r.first, rects)
        }
    }

    /** 自带文字层的一页（纵向位置先记 -1，调用方拿 [Hit.origin] 去问字符框）。 */
    fun searchPlainPage(page: Int, text: String, needle: String, into: MutableList<Hit>) {
        if (text.isEmpty()) return
        for (r in find(fold(text), needle)) {
            val (s, a, b) = snippet(text, r)
            into += Hit(page, -1f, s, a, b, r.first, r.last + 1 - r.first)
        }
    }

    /** 两层合起来：有 OCR 命中的页丢掉自带文字层的命中，按页 → 页内先后排。 */
    fun merge(ocr: List<Hit>, plain: List<Hit>): List<Hit> {
        val ocrPages = ocr.map { it.page }.toHashSet()
        return (ocr + plain.filter { it.page !in ocrPages })
            .sortedWith(compareBy<Hit> { it.page }.thenBy { it.frac }.thenBy { it.origin })
    }
}
