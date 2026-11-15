package com.xvan.unireader.local.agent

/**
 * `edit_markdown` 的改文字规则：逐条照 Mac `../Sources/MCP/MCPMarkdownText.swift` 的 `apply` / `snippet`
 * （同一个模型两端用起来一样：报错原文、行号口径、「恰好一处」的规矩都一致）。纯函数，JVM 单测 `MdEditTest`。
 * 偏移一律是 UTF-16（Kotlin String 本来就是，与 NSString 同口径）。
 */
object MdEdit {

    sealed interface Edit {
        /** 把 [old] 换成 [new]。[all] 为假时 [old] 必须**恰好出现一次**。 */
        data class Replace(val old: String, val new: String, val all: Boolean) : Edit
        /** 在第 [afterLine] 行后面插入（0 = 最前面；= 总行数 = 末尾）。[text] 当作整行插入。 */
        data class Insert(val afterLine: Int, val text: String) : Edit
    }

    /** [index] = 第几条（1 起；0 = 整体） */
    class EditError(val index: Int, message: String) : Exception(message)

    class Result(val text: String, val counts: List<Int>, val changedLines: List<IntRange>)

    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = text.split('\n').toMutableList()
        if (text.endsWith("\n")) out.removeAt(out.size - 1)
        return out
    }

    fun lineCount(text: String) = lines(text).size

    /** 某个偏移落在第几行（1 起）。 */
    fun lineAt(offset: Int, text: String): Int {
        val end = offset.coerceIn(0, text.length)
        var n = 1
        for (i in 0 until end) if (text[i] == '\n') n++
        return n
    }

    fun apply(edits: List<Edit>, original: String): Result {
        if (edits.isEmpty()) throw EditError(0, "edits is empty")
        var text = original
        val counts = ArrayList<Int>()
        var regions = ArrayList<IntArray>()   // [起点, 长度]，最终正文里被改过的区间

        // 这次把 [s, s+oldLen) 换成了 newLen 长的新内容：已记的区间跟着平移 / 合并
        fun record(s: Int, oldLen: Int, newLen: Int) {
            val e = s + oldLen
            val delta = newLen - oldLen
            var mergedLo = s
            var mergedLen = newLen
            val next = ArrayList<IntArray>()
            for (r in regions) {
                val rEnd = r[0] + r[1]
                if (rEnd <= s) next.add(r)
                else if (r[0] >= e) next.add(intArrayOf(r[0] + delta, r[1]))
                else {
                    val lo = minOf(r[0], s)
                    val hi = maxOf(s + newLen, if (rEnd > e) rEnd + delta else s + newLen)
                    mergedLo = lo; mergedLen = hi - lo
                }
            }
            next.add(intArrayOf(mergedLo, mergedLen))
            regions = next
        }

        for ((k, edit) in edits.withIndex()) {
            val idx = k + 1
            when (edit) {
                is Edit.Replace -> {
                    if (edit.old.isEmpty()) throw EditError(idx, "old_text is empty; use insert_line to add text")
                    if (edit.old == edit.new) throw EditError(idx, "old_text and new_text are identical")
                    val found = occurrences(edit.old, text)
                    if (found.isEmpty()) throw EditError(idx, notFoundMessage(edit.old, text))
                    if (found.size > 1 && !edit.all) {
                        val where = found.take(10).joinToString(", ") { lineAt(it, text).toString() }
                        throw EditError(
                            idx,
                            "old_text matches ${found.size} places (lines $where${if (found.size > 10) ", …" else ""}); " +
                                "include more surrounding text so it matches exactly one, or set replace_all",
                        )
                    }
                    val sb = StringBuilder(text)
                    for (at in found.reversed()) sb.replace(at, at + edit.old.length, edit.new)
                    var shift = 0
                    for (at in found) {
                        record(at + shift, edit.old.length, edit.new.length)
                        shift += edit.new.length - edit.old.length
                    }
                    text = sb.toString()
                    counts.add(found.size)
                }
                is Edit.Insert -> {
                    val all = lines(text)
                    if (edit.afterLine < 0 || edit.afterLine > all.size) {
                        throw EditError(idx, "insert_line ${edit.afterLine} is out of range (0…${all.size})")
                    }
                    if (edit.text.isEmpty()) throw EditError(idx, "new_text is empty")
                    var at = 0
                    for (l in all.take(edit.afterLine)) at += l.length + 1
                    var ins = edit.text
                    if (at > text.length) {
                        // 末尾且原文没有结尾换行：先补一个换行再接着写，不给新内容硬加结尾换行
                        at = text.length
                        ins = "\n" + edit.text
                    } else if (!ins.endsWith("\n") && text.isNotEmpty()) {
                        ins += "\n"
                    }
                    text = text.substring(0, at) + ins + text.substring(at)
                    record(at, 0, ins.length)
                    counts.add(1)
                }
            }
        }
        if (text == original) throw EditError(0, "the edits leave the note unchanged")

        val finalLines = maxOf(1, lineCount(text))
        val ranges = ArrayList<IntRange>()
        for (r in regions.sortedBy { it[0] }) {
            val a = lineAt(r[0], text)
            val endOff = if (r[1] == 0) r[0] else r[0] + maxOf(0, r[1] - 1)
            val b = maxOf(a, lineAt(endOff, text))
            val range = minOf(a, finalLines)..minOf(b, finalLines)
            val last = ranges.lastOrNull()
            if (last != null && range.first <= last.last + 1) ranges[ranges.size - 1] = last.first..maxOf(last.last, range.last)
            else ranges.add(range)
        }
        return Result(text, counts, ranges)
    }

    /** 改完后给模型核对用的片段：每处改动前后各带 [context] 行，带行号；相邻的并成一段。 */
    fun snippet(text: String, around: List<IntRange>, context: Int = 2, maxLines: Int = 60): String {
        val all = lines(text)
        if (all.isEmpty()) return "(the note is now empty)"
        val blocks = ArrayList<IntRange>()
        for (r in around) {
            val a = maxOf(1, r.first - context)
            val b = minOf(all.size, r.last + context)
            val last = blocks.lastOrNull()
            if (last != null && a <= last.last + 1) blocks[blocks.size - 1] = last.first..maxOf(last.last, b)
            else blocks.add(a..b)
        }
        val out = ArrayList<String>()
        var budget = maxLines
        for (b in blocks) {
            if (budget <= 0) { out.add("…"); break }
            val end = minOf(b.last, b.first + budget - 1)
            out.add((b.first..end).joinToString("\n") { "$it\t${all[it - 1]}" })
            if (end < b.last) out.add("…")
            budget -= end - b.first + 1
        }
        return out.joinToString("\n…\n")
    }

    private fun occurrences(needle: String, hay: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < hay.length) {
            val at = hay.indexOf(needle, i)
            if (at < 0) break
            out.add(at)
            i = at + maxOf(1, needle.length)
        }
        return out
    }

    /** 精确匹配失败时的提示（只诊断，不据此修改）。同 Mac `notFoundMessage`。 */
    private fun notFoundMessage(old: String, text: String): String {
        val msg = "old_text was not found in the note"
        val parts = old.split('\n')
        val numbered = Regex("^\\s*\\d+\\t")
        if (parts.isNotEmpty() && parts.all { it.isEmpty() || numbered.containsMatchIn(it) }) {
            return "$msg; it seems to include the line-number prefixes from read_markdown — copy only the text after the tab"
        }
        val squeeze = { s: String -> s.filterNot { it.isWhitespace() } }
        val want = squeeze(old)
        if (want.isNotEmpty()) {
            val all = lines(text)
            for (i in all.indices) {
                // 从第 i 行起往下拼，去掉空白后能对上 = 只差空白 / 换行
                val sb = StringBuilder()
                for (j in i until all.size) {
                    sb.append(squeeze(all[j]))
                    if (sb.length >= want.length) break
                }
                if (sb.toString().startsWith(want)) {
                    return "$msg; a match exists near line ${i + 1} if whitespace is ignored — copy the exact text (spaces, tabs, line breaks) from read_markdown"
                }
            }
        }
        return "$msg; the text may have changed — call read_markdown again"
    }
}
