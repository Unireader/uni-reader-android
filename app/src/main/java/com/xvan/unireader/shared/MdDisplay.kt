package com.xvan.unireader.shared

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Markdown 笔记**显示用**的纯逻辑（两模式共用：模式1 `local/MdNoteController` 读本机文件，模式2 显示 Mac 发来的正文，
 * `../docs/PROTOCOL.md §4.10`）：链接目标收拾、把正文切成显示段（文字 / 单独一行的图 / ```anim 块）、`[[…]]` 换成标准链接。
 *
 * 🔴 这里只产生**显示用的副本**，不写回正文（红线 9）。规则照 Mac `MarkdownLink`。
 */
object MdDisplay {

    /** 链接目标收拾：去别名段（第一个竖线之后）、去尾部反斜杠、去锚点、去 Markdown 转义（Mac `MarkdownLink.linkTarget`） */
    fun linkTarget(raw: String): String {
        var s = raw
        s.indexOf('|').let { if (it >= 0) s = s.substring(0, it) }
        while (s.endsWith("\\")) s = s.dropLast(1)
        s.indexOf('#').let { if (it >= 0) s = s.substring(0, it) }
        val esc = setOf('|', '#', '[', ']', '(', ')', '*', '_', '`', '~', '\\')
        val out = StringBuilder()
        var pending = false
        for (c in s) {
            if (pending) {
                if (c !in esc) out.append('\\')
                out.append(c)
                pending = false
            } else if (c == '\\') {
                pending = true
            } else {
                out.append(c)
            }
        }
        if (pending) out.append('\\')
        return out.toString().trim()
    }

    const val WIKI_SCHEME = "uniwiki:"
    const val IMG_SCHEME = "uniimg:"

    /** 一段显示内容：Markdown 文字，或单独成行的 `![[图]]`（画成一张图） */
    sealed class Segment {
        class Text(val md: String) : Segment()
        class Image(val target: String) : Segment()
        /** ```anim 分步演示块：围栏之间的文字（`../docs/ANIM-BLOCK-PLAN.md`，画成能逐步播放的演示） */
        class Anim(val source: String) : Segment()
        /**
         * Obsidian callout（`> [!type] 标题` 开头的引用，`../docs/MARKDOWN-NOTES-PLAN.md §23`）：[type] 是规范名（未知类型按 note），
         * [title] 是写了的标题（没写为空，显示 [Callouts.defaultTitle]），[fold] 是 `'-'`（默认折叠）/ `'+'`（默认展开）/ null（不能折叠），
         * [body] 是去掉一层 `>` 之后的正文 Markdown（里面再嵌的引用 / callout 照常是引用）。
         */
        class Callout(val type: String, val typeText: String, val title: String, val fold: Char?, val body: String) : Segment()
    }

    /** callout 的类型表：名字 / 别名 / 颜色照 Obsidian（同 Mac 引擎 `QuoteSyntax.swift` 的 `CalloutType`）。颜色给名字，取色在界面层（`R.color.callout_*`）。 */
    object Callouts {
        /** 类型色的名字（`blue` / `cyan` / `green` / `orange` / `red` / `purple` / `gray`）。 */
        private class Kind(val name: String, val color: String)

        private val table: Map<String, Kind> = HashMap<String, Kind>().apply {
            fun add(names: List<String>, color: String) {
                val k = Kind(names[0], color)
                for (n in names) put(n, k)
            }
            add(listOf("note"), "blue")
            add(listOf("abstract", "summary", "tldr"), "cyan")
            add(listOf("info"), "blue")
            add(listOf("todo"), "blue")
            add(listOf("tip", "hint", "important"), "cyan")
            add(listOf("success", "check", "done"), "green")
            add(listOf("question", "help", "faq"), "orange")
            add(listOf("warning", "caution", "attention"), "orange")
            add(listOf("failure", "fail", "missing"), "red")
            add(listOf("danger", "error"), "red")
            add(listOf("bug"), "red")
            add(listOf("example"), "purple")
            add(listOf("quote", "cite"), "gray")
        }

        private fun kind(type: String) = table[type.lowercase()] ?: table.getValue("note")

        /** 规范名（`faq` → `question`）；不认识的按 note。 */
        fun canonical(type: String): String = kind(type).name

        /** 类型色的名字。 */
        fun colorName(type: String): String = kind(type).color

        /** 没写标题时显示的：写的类型名、首字母大写（同 Obsidian：`[!faq]` → `Faq`）。 */
        fun defaultTitle(typeText: String): String =
            if (typeText.isEmpty()) "" else typeText.substring(0, 1).uppercase() + typeText.substring(1).lowercase()

        private val HEADER = Regex("^\\[!([A-Za-z0-9_-]+)]([+-]?)(?:[ \t]+(.*?))?[ \t]*$")

        /** 引用内容（已去掉 `>`）是不是 callout 的第一行：返回（写的类型、折叠符、标题）。`[!note]x` 这种不算。 */
        fun header(content: String): Triple<String, Char?, String>? {
            val m = HEADER.find(content.trimStart()) ?: return null
            val fold = m.groupValues[2].firstOrNull()
            return Triple(m.groupValues[1], fold, m.groupValues[3].trim())
        }
    }

    /** 引用行的 `>` 前缀长度（至多三个空格缩进，`>` 可重复、各带一个可选空格）与层数；不是引用行返回 (0, 0)。同 Mac 引擎 `QuoteSyntax.line`。 */
    fun quotePrefix(line: String): Pair<Int, Int> {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 3 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        var level = 0
        while (i < line.length && line[i] == '>') {
            level++
            i++
            if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        }
        return if (level == 0) 0 to 0 else i to level
    }

    /** 去掉一层引用：至多三个空格缩进 + 一个 `>` + 一个可选空格。 */
    private fun stripOneLevel(line: String): String {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 3 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i < line.length && line[i] == '>') {
            i++
            if (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
            return line.substring(i)
        }
        return line
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /**
     * 把正文切成显示段：单独一行的 `![[图]]` 成为图片段，其余是文字段。文字段里的 `[[目标|别名]]` 换成
     * `[别名](uniwiki:目标)`（显示**别名**，同 Obsidian）、行内的 `![[附件]]` 换成 `[附件](uniimg:附件)`。
     * 代码块与行内代码里的不动（Mac `MarkdownLink` 的保护区）。
     */
    fun segments(text: String): List<Segment> {
        val out = ArrayList<Segment>()
        val buf = StringBuilder()
        var fence: String? = null
        fun flush() {
            if (buf.isNotEmpty()) { out.add(Segment.Text(buf.toString())); buf.setLength(0) }
        }
        var anim: StringBuilder? = null      // 正在收的 ```anim 块（没有关上就当普通文字）
        var animOpen = ""
        // 正在收的 callout（到第一行不是引用的为止）；上一行是不是引用行（callout 只认引用的第一行）
        var callout: Segment.Callout? = null
        val calloutBody = StringBuilder()
        var prevQuote = false
        fun closeCallout() {
            val c = callout ?: return
            out.add(Segment.Callout(c.type, c.typeText, c.title, c.fold, calloutBody.toString().trimEnd('\n')))
            callout = null
            calloutBody.setLength(0)
        }
        for (line in text.split('\n')) {
            val t = line.trim()
            if (callout != null) {
                if (quotePrefix(line).second > 0) {
                    calloutBody.append(wikiLine(stripOneLevel(line))).append('\n')
                    continue
                }
                closeCallout()
            }
            val a = anim
            if (a != null) {
                if (t.startsWith("```")) {
                    flush()   // 关上了才把前面的文字切出去（没关上的整段留在文字里）
                    out.add(Segment.Anim(a.toString().trim()))   // 同 Mac：去掉首尾空白再交给引擎
                    anim = null
                } else {
                    a.append(line).append('\n')
                }
                continue
            }
            if (fence == null && t.startsWith("```") && t.drop(3).trim().lowercase() == "anim") {
                anim = StringBuilder()
                animOpen = line
                continue
            }
            if (fence != null) {
                buf.append(line).append('\n')
                if (t.startsWith(fence!!)) fence = null
                continue
            }
            if (t.startsWith("```") || t.startsWith("~~~")) {
                fence = t.substring(0, 3)
                buf.append(line).append('\n')
                continue
            }
            val img = Regex("^!\\[\\[([^\\]]+)]]$").find(t)
            if (img != null) {
                flush()
                out.add(Segment.Image(linkTarget(img.groupValues[1])))
                prevQuote = false
                continue
            }
            val (qLen, qLevel) = quotePrefix(line)
            if (qLevel == 1 && !prevQuote) {
                val h = Callouts.header(line.substring(qLen))
                if (h != null) {
                    flush()
                    callout = Segment.Callout(Callouts.canonical(h.first), h.first, h.third, h.second, "")
                    prevQuote = true
                    continue
                }
            }
            prevQuote = qLevel > 0
            buf.append(wikiLine(line)).append('\n')
        }
        closeCallout()
        // 没有关上的 ```anim：原样当代码文字（同 Mac 引擎：没有关闭的围栏不算块）
        anim?.let { buf.append(animOpen).append('\n').append(it) }
        flush()
        return out
    }

    /** 一行里的 `[[…]]` / `![[…]]` 换成标准链接；反引号包着的部分原样留着 */
    private fun wikiLine(line: String): String {
        if (!line.contains("[[")) return line
        val sb = StringBuilder()
        var i = 0
        var inCode = false
        while (i < line.length) {
            val c = line[i]
            if (c == '`') { inCode = !inCode; sb.append(c); i++; continue }
            if (!inCode && line.startsWith("[[", i) || !inCode && line.startsWith("![[", i)) {
                val embed = line[i] == '!'
                val open = if (embed) i + 3 else i + 2
                val close = line.indexOf("]]", open)
                if (close > open) {
                    val inner = line.substring(open, close)
                    val bar = inner.indexOf('|')
                    val target = linkTarget(inner)
                    val shown = if (bar >= 0) inner.substring(bar + 1).trim() else inner.trimEnd('\\').trim()
                    val label = shown.ifEmpty { target }.replace("[", "\\[").replace("]", "\\]")
                    sb.append('[').append(label).append("](")
                        .append(if (embed) IMG_SCHEME else WIKI_SCHEME).append(encode(target)).append(')')
                    i = close + 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
