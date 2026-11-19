package com.xvan.unireader.local.web

import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * 网页（静态 HTML 课件，`../docs/WEB-DOC-PLAN.md`）的**文件层**：`<工作区>/Web/<sha256>`，一个文件一份，按内容寻址。
 * 🔴 逐条照 Mac `Sources/Store/WebAssets.swift`（清单编码、路径规则、入口挑选、Range、清理），两端写出来的清单字节相同。
 * 一份网页 = `web_doc` 一行 + 清单（相对路径 → 哈希），文件本体在 `Web/` 里；写入一律 `.part` → 改名。
 */
object WebAssets {

    const val FOLDER = "Web"

    fun folder(ws: File) = File(ws, FOLDER)
    fun file(ws: File, sha: String) = File(folder(ws), sha)
    fun exists(ws: File, sha: String) = file(ws, sha).isFile

    fun isSha256Hex(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    // ---------- 清单（跨端契约：JSON 数组，键 h / n / p，按路径排序） ----------

    data class Entry(val p: String, val h: String, val n: Long)

    /** 编码：按路径排序、键序 h,n,p（同 Mac `JSONEncoder` + sortedKeys + withoutEscapingSlashes）。 */
    fun encode(entries: Collection<Entry>): String {
        val sb = StringBuilder("[")
        entries.sortedWith { a, b -> compareUtf16(a.p, b.p) }.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"h\":").append(quote(e.h)).append(",\"n\":").append(e.n).append(",\"p\":").append(quote(e.p)).append('}')
        }
        return sb.append(']').toString()
    }

    /** Swift `String <` 按 Unicode 标量比；路径里不会有代理对之外的差别，按码点比即可。 */
    private fun compareUtf16(a: String, b: String): Int {
        val ia = a.codePoints().toArray()
        val ib = b.codePoints().toArray()
        for (k in 0 until minOf(ia.size, ib.size)) if (ia[k] != ib[k]) return ia[k].compareTo(ib[k])
        return ia.size.compareTo(ib.size)
    }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append(String.format("\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** 解码：坏 JSON = 空；不合规的条目丢掉。 */
    fun decode(json: String): List<Entry> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val e = Entry(o.optString("p"), o.optString("h"), o.optLong("n"))
            if (isSafeRelative(e.p) && isSha256Hex(e.h)) e else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun hashes(json: String): Set<String> = decode(json).mapTo(HashSet()) { it.h }

    fun isSafeRelative(p: String): Boolean {
        if (p.isEmpty() || p.startsWith("/") || p.contains('\\')) return false
        if (p.any { it.code < 0x20 || it.code == 0x7F }) return false
        return p.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    // ---------- 按路径找文件 / 入口 ----------

    fun resolve(path: String, entries: Map<String, Entry>): Entry? {
        val p = path.trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) return entries[p + "index.html"] ?: entries[p + "index.htm"]
        return entries[p] ?: entries["$p/index.html"] ?: entries["$p/index.htm"]
    }

    fun isHtml(path: String): Boolean = path.substringAfterLast('.', "").lowercase().let { it == "html" || it == "htm" || it == "xhtml" }

    fun defaultEntry(entries: Collection<Entry>): String? {
        val paths = entries.mapTo(HashSet()) { it.p }
        for (n in listOf("index.html", "index.htm", "Index.html", "INDEX.HTML")) if (n in paths) return n
        return entries.map { it.p }.filter(::isHtml)
            .minWithOrNull(compareBy<String> { it.split('/').size }.thenComparator { a, b -> a.lowercase().compareTo(b.lowercase()) })
    }

    // ---------- 类型 ----------

    /** 不带 charset 的 MIME（WebView 的 `WebResourceResponse` 编码另传）。 */
    fun mimeType(path: String): String = MIME[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    fun isText(mime: String) = mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || mime == "image/svg+xml"

    private val MIME = mapOf(
        "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
        "json" to "application/json", "map" to "application/json", "txt" to "text/plain", "md" to "text/plain",
        "csv" to "text/csv", "xml" to "application/xml", "svg" to "image/svg+xml", "wasm" to "application/wasm",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "avif" to "image/avif", "ico" to "image/x-icon", "bmp" to "image/bmp",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "ogv" to "video/ogg", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "flac" to "audio/flac", "vtt" to "text/vtt", "pdf" to "application/pdf", "zip" to "application/zip",
    )

    // ---------- Range ----------

    /** `bytes=a-b` / `bytes=a-` / `bytes=-n` → 闭区间；不认识 / 越界 = null（按整份回）。 */
    fun byteRange(header: String?, size: Long): LongRange? {
        if (header == null || size <= 0) return null
        val h = header.trim()
        if (!h.lowercase().startsWith("bytes=")) return null
        val spec = h.substring(6)
        if (spec.contains(',')) return null
        val parts = spec.split('-', limit = 2)
        if (parts.size != 2) return null
        val a = parts[0].trim()
        val b = parts[1].trim()
        if (a.isEmpty()) {
            val n = b.toLongOrNull() ?: return null
            if (n <= 0) return null
            return maxOf(0, size - n)..(size - 1)
        }
        val start = a.toLongOrNull() ?: return null
        if (start >= size) return null
        val end = if (b.isEmpty()) size - 1 else minOf(b.toLongOrNull() ?: -1, size - 1)
        if (end < start) return null
        return start..end
    }

    fun read(f: File, range: LongRange): ByteArray = RandomAccessFile(f, "r").use { raf ->
        val buf = ByteArray((range.last - range.first + 1).toInt())
        raf.seek(range.first)
        raf.readFully(buf)
        buf
    }

    // ---------- 写 ----------

    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** 拷进 `Web/<sha>`（已在就只把修改时间记成现在：清理按它判「是不是刚放进来的」）。 */
    fun copy(src: File, ws: File, sha: String) {
        val dst = file(ws, sha)
        if (dst.isFile) { dst.setLastModified(System.currentTimeMillis()); return }
        val dir = folder(ws).apply { mkdirs() }
        val tmp = File(dir, ".$sha.${UUID.randomUUID().toString().take(8)}.part")
        src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        tmp.setLastModified(System.currentTimeMillis())
        if (dst.isFile) { tmp.delete(); return }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw java.io.IOException("cannot move $tmp to $dst") }
    }

    /** 内存里的字节存成 `Web/<sha>`，返回哈希。 */
    fun write(data: ByteArray, ws: File): String {
        val sha = sha256Hex(data)
        val dst = file(ws, sha)
        if (dst.isFile) { dst.setLastModified(System.currentTimeMillis()); return sha }
        val dir = folder(ws).apply { mkdirs() }
        val tmp = File(dir, ".$sha.${UUID.randomUUID().toString().take(8)}.part")
        tmp.writeBytes(data)
        if (dst.isFile) { tmp.delete(); return sha }
        if (!tmp.renameTo(dst)) { tmp.delete(); throw java.io.IOException("cannot move $tmp to $dst") }
        return sha
    }

    // ---------- 导入 ----------

    const val MAX_FILES = 20_000
    const val MAX_BYTES = 4L shl 30

    class ImportError(val kind: Kind) : Exception(kind.name) {
        enum class Kind { NOT_A_FOLDER, EMPTY, NO_HTML, TOO_MANY_FILES, TOO_LARGE }
    }

    fun isJunk(name: String) = name.startsWith(".") || name == "Thumbs.db" || name == "desktop.ini" || name == "__MACOSX"

    /** 扫描一个文件夹：相对路径 → 文件。跳过隐藏文件 / 符号链接 / 垃圾。 */
    fun scan(src: File): List<Pair<String, File>> {
        if (!src.isDirectory) throw ImportError(ImportError.Kind.NOT_A_FOLDER)
        val base = src.canonicalFile
        val out = ArrayList<Pair<String, File>>()
        var total = 0L
        fun walk(dir: File, prefix: String) {
            val kids = dir.listFiles() ?: return
            for (f in kids.sortedBy { it.name }) {
                if (isJunk(f.name)) continue
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                if (f.isDirectory) { walk(f, rel); continue }
                if (!f.isFile || !isSafeRelative(rel)) continue
                out += rel to f
                total += f.length()
                if (out.size > MAX_FILES) throw ImportError(ImportError.Kind.TOO_MANY_FILES)
                if (total > MAX_BYTES) throw ImportError(ImportError.Kind.TOO_LARGE)
            }
        }
        walk(base, "")
        if (out.isEmpty()) throw ImportError(ImportError.Kind.EMPTY)
        if (out.none { isHtml(it.first) }) throw ImportError(ImportError.Kind.NO_HTML)
        return out
    }

    /** 导入整个文件夹（在后台线程调）：逐个算哈希、拷进 `Web/`，返回清单。 */
    fun importFolder(src: File, ws: File, progress: (Int, Int) -> Unit = { _, _ -> }): List<Entry> {
        val files = scan(src)
        return files.mapIndexed { i, (rel, f) ->
            progress(i, files.size)
            val sha = sha256Hex(f)
            copy(f, ws, sha)
            Entry(rel, sha, f.length())
        }.also { progress(files.size, files.size) }
    }

    // ---------- 清理 ----------

    /** 删掉没人用的文件：[keep] 以外、且修改时间早于 [olderThanMs]；一天前的 `.part` 残留也删。返回删了几个。 */
    fun purge(ws: File, keep: Set<String>, olderThanMs: Long): Int {
        val names = folder(ws).list() ?: return 0
        var n = 0
        val now = System.currentTimeMillis()
        for (name in names) {
            val isPart = name.startsWith(".") && name.endsWith(".part")
            if (!isPart && !(isSha256Hex(name) && name !in keep)) continue
            val f = File(folder(ws), name)
            val cutoff = if (isPart) now - 86_400_000L else olderThanMs
            if (f.lastModified() >= cutoff) continue
            if (f.delete() && !isPart) n++
        }
        return n
    }
}
