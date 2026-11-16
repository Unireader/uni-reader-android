package com.xvan.unireader.local.search

import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * 全工作区搜索的纯文本部分（`../docs/SEARCH-PLAN.md`、`../docs/ANDROID-AGENT-PLAN.md §5.6`）：归一化、切段、摘要、打分。
 * **逐条照抄 Mac `Sources/Search/SearchText.swift`**（JVM 单测 `SearchTextTest` ↔ `spike/search-test.swift` 1)~4)）。
 *
 * 归一化（建块与查询同一套）：兼容分解（全角 → 半角）、小写、**去掉全部空白**——OCR 文字一句中文常被换行切开，去掉之后整句照样搜得中。
 * Swift 按 `Character`（字形簇）走，这里按码点走；中文与常见符号上两者一样。
 */
object SearchText {

    private fun isSpace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x200B || cp == 0xFEFF

    /** 一个码点折成的归一化字符串（可能为空，也可能不止一个字符：`ﬁ` → `fi`）。 */
    private fun fold(cp: Int): String {
        if (isSpace(cp)) return ""
        if (cp < 0x80) return if (cp in 65..90) (cp + 32).toChar().toString() else cp.toChar().toString()
        val s = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC).lowercase()
        if (s.none { it.isWhitespace() }) return s
        return s.filterNot { it.isWhitespace() }
    }

    /** 归一化后的字符串（建块、查询都用它）。 */
    fun normalize(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.append(fold(cp))
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** 同上，另给每个归一化字符（UTF-16 单元）对应原文的下标（UTF-16），摘要要从命中位置回到原文。 */
    fun normalizeWithMap(s: String): Pair<String, IntArray> {
        val out = StringBuilder(s.length)
        val map = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val piece = fold(cp)
            out.append(piece)
            repeat(piece.length) { map.add(i) }
            i += Character.charCount(cp)
        }
        return out.toString() to map.toIntArray()
    }

    /** 查询 → 词：空白分开，`"…"`（也认中文引号）括起来的算一个词；归一化、去空、去重，按出现顺序。 */
    fun terms(query: String): List<String> {
        val raw = ArrayList<String>()
        val cur = StringBuilder()
        var inQuote = false
        for (c in query) {
            if (c == '"' || c == '“' || c == '”') {
                if (inQuote) { raw.add(cur.toString()); cur.setLength(0) } else if (cur.isNotEmpty()) { raw.add(cur.toString()); cur.setLength(0) }
                inQuote = !inQuote
            } else if (c.isWhitespace() && !inQuote) {
                if (cur.isNotEmpty()) { raw.add(cur.toString()); cur.setLength(0) }
            } else {
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) raw.add(cur.toString())
        val seen = HashSet<String>()
        return raw.map(::normalize).filter { it.isNotEmpty() && seen.add(it) }
    }

    // ---------------- 切段 ----------------

    /** 一段正文切成不超过 [maxChars] 字的块：先按空行分段、段太长再按行、行还太长就硬切；相邻小段合并到接近上限。原样，不归一化。 */
    fun chunks(text: String, maxChars: Int = 500): List<String> {
        val paras = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val pieces = ArrayList<String>()
        for (p in paras) {
            if (p.length <= maxChars) { pieces.add(p); continue }
            for (line in p.split('\n').filter { it.isNotEmpty() }) {
                var l = line
                while (l.length > maxChars) { pieces.add(l.take(maxChars)); l = l.drop(maxChars) }
                if (l.isNotEmpty()) pieces.add(l)
            }
        }
        val out = ArrayList<String>()
        var cur = ""
        for (p in pieces) {
            if (cur.isEmpty()) cur = p
            else if (cur.length + 1 + p.length <= maxChars) cur += "\n" + p
            else { out.add(cur); cur = p }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    class MdChunk(val line: Int, val heading: String, val text: String)

    /** Markdown 切块：每块记起始行号（1 起）与所在标题；标题行开新块，块内超过 [maxChars] 也开新块（在行边界上）；代码块里的 `#` 不算标题。 */
    fun markdownChunks(text: String, maxChars: Int = 500): List<MdChunk> {
        val out = ArrayList<MdChunk>()
        var start = 1
        var heading = ""
        val cur = ArrayList<String>()
        var size = 0
        fun flush() {
            val body = cur.joinToString("\n").trim()
            if (body.isNotEmpty()) out.add(MdChunk(start, heading, body))
            cur.clear(); size = 0
        }
        var inFence = false
        for ((i, line) in text.split('\n').withIndex()) {
            val t = line.trim()
            if (t.startsWith("```") || t.startsWith("~~~")) inFence = !inFence
            val isHeading = !inFence && t.startsWith("#") && t.dropWhile { it == '#' }.startsWith(" ")
            if (isHeading || (size + line.length > maxChars && cur.isNotEmpty())) {
                flush()
                start = i + 1
                if (isHeading) heading = t.dropWhile { it == '#' }.trim()
            }
            if (cur.isEmpty()) start = i + 1
            var l = line
            while (l.length > maxChars) { cur.add(l.take(maxChars)); flush(); start = i + 1; l = l.drop(maxChars) }
            cur.add(l); size += l.length + 1
        }
        flush()
        return out
    }

    // ---------------- 摘要与打分 ----------------

    /** 命中处前后各 [radius] 字的摘要（原文，换行压成空格），命中的词用 `[` `]` 括起来；没找到就取开头。 */
    fun snippet(body: String, terms: List<String>, radius: Int = 60): String {
        val (norm, map) = normalizeWithMap(body)
        var hs = -1
        var he = -1
        for (t in terms.sortedByDescending { it.length }) {
            val r = norm.indexOf(t)
            if (t.isEmpty() || r < 0) continue
            val s = map[r]
            val e = map[r + t.length - 1] + Character.charCount(body.codePointAt(map[r + t.length - 1]))
            if (hs < 0 || s < hs) { hs = s; he = e }
        }
        fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ')
        if (hs < 0) {
            val head = body.take(radius * 2)
            return clean(head) + if (body.length > head.length) "…" else ""
        }
        val a = max(0, hs - radius)
        val b = min(body.length, he + radius)
        return (if (a > 0) "…" else "") + clean(body.substring(a, hs)) + "[" + clean(body.substring(hs, he)) + "]" +
            clean(body.substring(he, b)) + if (b < body.length) "…" else ""
    }

    /** 一个词在归一化文本里出现几次（不重叠）。 */
    fun count(term: String, norm: String): Int {
        if (term.isEmpty()) return 0
        var n = 0
        var from = 0
        while (true) {
            val r = norm.indexOf(term, from)
            if (r < 0) return n
            n++
            from = r + term.length
        }
    }

    /**
     * 打分（越大越靠前）：每个词按「出现次数取对数 × 稀有度」累加，标题里出现再加；命中的词越全越好；块越长略微压一点。
     * `df[t]` = 候选里含这个词的块数，[n] = 候选总数。
     */
    fun score(norm: String, titleNorm: String, terms: List<String>, df: Map<String, Int>, n: Int): Double {
        var s = 0.0
        var matched = 0
        for (t in terms) {
            val tf = count(t, norm)
            val inTitle = titleNorm.contains(t)
            if (tf == 0 && !inTitle) continue
            matched++
            val idf = ln(1 + (n + 1).toDouble() / ((df[t] ?: 0) + 1))
            s += (if (tf > 0) 1 + ln(tf.toDouble()) else 0.0) * idf
            if (inTitle) s += 0.8 * idf
        }
        if (matched == 0) return 0.0
        val coverage = matched.toDouble() / max(terms.size, 1)
        val lengthNorm = 1 + 0.15 * ln(1 + norm.length.toDouble() / 300)
        return s * coverage * coverage / lengthNorm
    }
}
