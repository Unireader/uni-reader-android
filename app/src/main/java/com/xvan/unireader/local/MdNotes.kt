package com.xvan.unireader.local

import java.io.File
import com.xvan.unireader.shared.MdDisplay
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

    /** 正文里每个 `[[…]]` / `![[…]]` 的目标（`|` 后面的别名去掉，原样，不收拾锚点——[Index.note] 会收拾）。 */
    fun wikiTargets(text: String): List<String> {
        val out = ArrayList<String>()
        var i = text.indexOf("[[")
        while (i >= 0) {
            val j = text.indexOf("]]", i + 2)
            if (j < 0) break
            val inner = text.substring(i + 2, j)
            if (!inner.contains('\n')) out += inner.substringBefore('|').trim()
            i = text.indexOf("[[", j + 2)
        }
        return out
    }

    /**
     * 别的笔记里指向 [rel] 的 `[[…]]`：（那篇的相对路径, 链接目标）。改名 / 删除前后各算一次，对不上的就是断了的
     * （同 Mac `rename_markdown` / `delete_markdown` 报告断链；不改别人的正文，红线 9）。读文件，别在主线程调。
     */
    fun backlinks(ws: File, rel: String): List<Pair<String, String>> {
        val s = scan(ws)
        val idx = Index(s)
        val out = ArrayList<Pair<String, String>>()
        for (n in s.notes) {
            if (n.relPath == rel) continue
            val text = runCatching { file(ws, n.relPath).readText() }.getOrNull() ?: continue
            for (t in wikiTargets(text)) if (idx.note(t) == rel) out += n.relPath to t
        }
        return out
    }

    /** 链接目标收拾（规则在共用层 [MdDisplay.linkTarget]，模式2 阅读也用） */
    fun linkTarget(raw: String): String = MdDisplay.linkTarget(raw)

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
