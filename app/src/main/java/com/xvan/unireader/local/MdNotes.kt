package com.xvan.unireader.local

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer

/**
 * 工作区 Markdown 笔记的纯逻辑（`../docs/MARKDOWN-NOTES-PLAN.md`，Mac `NoteTree` / `MarkdownLink` / `MarkdownImport` 的同款规则）：
 * 扫 `<工作区>/Notes/`、建多级目录树、`[[…]]` 按名字解析、显示用的 Markdown 转换。
 *
 * 🔴 **任何自动维护都不改笔记正文**（红线 9）：这里的转换只产生**显示用的副本**，写回文件的永远是用户自己编辑的原文。
 * 本端只做内建源 `Notes/`；Mac 上「引用的外部目录」存的是 Mac 的绝对路径，平板上不存在（见 [referencedNames]）。
 */
object MdNotes {
    const val DIR = "Notes"

    /** 一篇笔记：源内相对路径（`数学/极限.md`）+ 标题（文件名去扩展名，Obsidian 的惯例） */
    data class Item(val relPath: String) {
        val title: String get() = dropExt(relPath.substringAfterLast('/'))
        val folder: String get() = relPath.substringBeforeLast('/', "")
    }

    /** 一层目录：子目录 + 笔记（都按名字排） */
    class Folder(val name: String, val path: String) {
        val folders = ArrayList<Folder>()
        val notes = ArrayList<Item>()
    }

    class Scan(val notes: List<Item>, val files: List<String>, val dirs: List<String>, val root: Folder)

    fun isMarkdown(name: String): Boolean = name.substringAfterLast('.', "").lowercase().let { it == "md" || it == "markdown" }

    fun dropExt(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }

    fun root(ws: File) = File(ws, DIR)

    fun file(ws: File, relPath: String) = File(root(ws), relPath)

    /** 走一遍 `Notes/`（跳过 `.` 开头的与 `trash`，同 Mac `MarkdownImport.walk`）；不读正文 */
    fun scan(ws: File): Scan {
        val base = root(ws)
        val notes = ArrayList<Item>()
        val files = ArrayList<String>()
        val dirs = ArrayList<String>()
        fun walk(d: File, rel: String) {
            val kids = d.listFiles() ?: return
            for (f in kids.sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) {
                    if (f.name == "trash") continue
                    dirs.add(r)
                    walk(f, r)
                } else if (isMarkdown(f.name)) {
                    notes.add(Item(r))
                } else {
                    files.add(r)
                }
            }
        }
        if (base.isDirectory) walk(base, "")
        return Scan(notes, files, dirs, tree(notes, dirs))
    }

    private fun tree(notes: List<Item>, dirs: List<String>): Folder {
        val top = Folder("", "")
        val byPath = HashMap<String, Folder>().apply { put("", top) }
        fun folder(path: String): Folder = byPath.getOrPut(path) {
            val parent = folder(path.substringBeforeLast('/', ""))
            Folder(path.substringAfterLast('/'), path).also { parent.folders.add(it) }
        }
        for (d in dirs) folder(d)
        for (n in notes) folder(n.folder).notes.add(n)
        fun sort(f: Folder) {
            f.folders.sortBy { it.name.lowercase() }
            f.notes.sortBy { it.title.lowercase() }
            f.folders.forEach(::sort)
        }
        sort(top)
        return top
    }

    /** Mac 上引用的外部目录（`meta.note_sources`，`kind == reference`）的名字——平板上打不开，只列出来说一声 */
    fun referencedNames(metaJson: String?): List<String> = runCatching {
        val a = org.json.JSONArray(metaJson ?: return emptyList())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("kind") != "reference") null else o.optString("name").ifEmpty { o.optString("path") }
        }
    }.getOrDefault(emptyList())

    // ---------- 名字解析（Mac `NoteIndex`） ----------

    /**
     * `[[…]]` / `![[…]]` 的解析表。名字的次序同 Mac：① 标题精确 → ② 相对路径去扩展名 → ③ 带扩展名的写法 →
     * ④ 路径对不上时退回末段文件名 → ⑤ 忽略大小写；重名取相对路径字典序最小的那篇（稳定、可预期）。
     */
    class Index(scan: Scan) {
        private val exact = HashMap<String, String>()
        private val folded = HashMap<String, String>()
        private val files = HashMap<String, String>()
        private val filesByName = HashMap<String, String>()

        init {
            for (n in scan.notes.sortedBy { it.relPath }) {
                add(n.title, n.relPath)
                add(dropExt(n.relPath), n.relPath)
            }
            for (f in scan.files) {
                val k = norm(f)
                if (k !in files) files[k] = f
                val name = norm(f.substringAfterLast('/'))
                if (name !in filesByName) filesByName[name] = f
            }
        }

        private fun add(key: String, rel: String) {
            val k = norm(key)
            if (k.isEmpty()) return
            if (k !in exact) exact[k] = rel
            if (k.lowercase() !in folded) folded[k.lowercase()] = rel
        }

        /** `[[名字]]` → 笔记相对路径；先按原样查（名字里本来带 `#` 的笔记），不中再收拾别名段 / 锚点 / 转义 */
        fun note(key: String): String? {
            val raw = norm(key)
            if (raw.isEmpty()) return null
            lookup(raw)?.let { return it }
            val t = norm(linkTarget(raw))
            return if (t != raw) lookup(t) else null
        }

        private fun lookup(k: String): String? {
            (exact[k] ?: folded[k.lowercase()])?.let { return it }
            val noExt = dropExt(k)
            if (noExt != k) (exact[noExt] ?: folded[noExt.lowercase()])?.let { return it }
            val last = k.substringAfterLast('/')
            if (last != k) {
                val l = norm(dropExt(last))
                (exact[l] ?: folded[l.lowercase()])?.let { return it }
            }
            return null
        }

        /** `![[图.png]]` / `![[attachments/图.png]]` → 附件相对路径（先按路径，再按文件名全库找） */
        fun file(key: String): String? {
            val k = norm(key)
            if (k.isEmpty()) return null
            return files[k] ?: filesByName[norm(k.substringAfterLast('/'))]
        }
    }

    /** macOS 上文件名是 NFD、正文多半是 NFC——不归一化「é」这类名字永远查不中（同 Mac `NoteIndex.normalize`） */
    fun norm(s: String): String = Normalizer.normalize(s.trim(), Normalizer.Form.NFC)

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

    // ---------- 显示用的 Markdown（只读副本，不写回） ----------

    const val WIKI_SCHEME = "uniwiki:"
    const val IMG_SCHEME = "uniimg:"

    /** 一段显示内容：Markdown 文字，或单独成行的 `![[图]]`（画成一张图） */
    sealed class Segment {
        class Text(val md: String) : Segment()
        class Image(val target: String) : Segment()
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
        for (line in text.split('\n')) {
            val t = line.trim()
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

    // ---------- 文件读写 ----------

    /** 存正文：`.part` 临时名 + 改名（中途断电不留半个文件） */
    fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.part")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) { tmp.delete(); throw java.io.IOException("改名失败：${tmp.path} → ${f.path}") }
        }
    }

    /** 文件名里不能出现的字符换成 `-`（同 Mac `MarkdownImport.safeFileName`） */
    fun safeFileName(s: String): String {
        var out = s
        for (c in listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|", "#", "^", "[", "]")) out = out.replace(c, "-")
        return out.trim().ifEmpty { "-" }
    }

    /** 撞名加 `-2` / `-3`…（同 Mac `MarkdownImport.availablePath`，比较忽略大小写） */
    fun availablePath(rel: String, used: Set<String>): String {
        val low = used.map { it.lowercase() }.toSet()
        if (rel.lowercase() !in low) return rel
        val dir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val base = dropExt(name)
        val ext = name.substringAfterLast('.', "")
        var n = 2
        while (true) {
            val cand = (if (dir.isEmpty()) "" else "$dir/") + "$base-$n" + (if (ext.isEmpty()) "" else ".$ext")
            if (cand.lowercase() !in low) return cand
            n++
        }
    }
}
