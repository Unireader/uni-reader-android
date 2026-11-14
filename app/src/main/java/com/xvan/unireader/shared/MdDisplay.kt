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
        for (line in text.split('\n')) {
            val t = line.trim()
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
                continue
            }
            buf.append(wikiLine(line)).append('\n')
        }
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
