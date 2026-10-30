package com.xvan.unireader.local

import android.util.Log
import com.xvan.unireader.local.store.BoardNote
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.TrashStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * 回收站（`../docs/BACKUP-PLAN.md §2`，Mac `TrashModel` + `WorkspaceManager+Trash` 的同款）：
 * `<工作区>/UniReader/Trash/<时间>_<标题>/{snapshot.sqlite, manifest.json}`。
 *
 * 🔴 **先归档、再删除**（红线 11）：归档（快照 + manifest）写成了才删库行；归档失败 = 整个删除放弃并报错。
 * 目录名、manifest 键名、时间格式与 Mac 逐项一致——条目跟着工作区走，哪一端删的哪一端都能恢复。
 * 恢复的两种情形（照常放回 / PDF 已重新导入过 → 并入那一篇）同 Mac。
 *
 * 文件层与流程在这里；搬行在 [TrashStore]。**都在库队列线程上调**（[com.xvan.unireader.local.store.StoreQueue]）。
 */
object Trash {
    const val TAG = "UniReader/Trash"
    const val DIR = "Trash"
    const val MANIFEST = "manifest.json"
    const val SNAPSHOT = "snapshot.sqlite"
    /** 保留期（天）。Mac 那边是设置项（30 / 90 / 永不），本端先固定默认的 30 天 */
    const val RETENTION_DAYS = 30

    const val KIND_DOCUMENT = "document"
    const val KIND_LAYER = "inkLayer"
    const val KIND_BOARD = "board"
    const val KIND_ANNOTATIONS = "annotations"
    const val KIND_EXERCISES = "exercises"

    class Manifest(
        val kind: String,
        val deletedAt: Date,
        val title: String,
        val documentId: String = "",
        val documentTitle: String = "",
        val layerId: String? = null,
        val pageCount: Int = 0,
        val counts: TrashStore.Counts = TrashStore.Counts(),
        val images: List<String> = emptyList(),
        val contentHashes: List<String> = emptyList(),
    )

    /** 扫出来的一条：目录名就是身份 */
    class Entry(val id: String, val dir: File, val manifest: Manifest, val bytes: Long) {
        val snapshot: File get() = File(dir, SNAPSHOT)
    }

    fun folder(ws: File) = File(ws, "UniReader/$DIR")

    // ---------- manifest 编解码（Mac：sortedKeys + ISO8601 整秒、UTC） ----------

    private fun iso(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun encode(m: Manifest): String {
        val o = JSONObject()
            .put("v", 1).put("kind", m.kind).put("deletedAt", iso().format(m.deletedAt)).put("title", m.title)
            .put("documentId", m.documentId).put("documentTitle", m.documentTitle)
            .put("pageCount", m.pageCount).put("counts", m.counts.toJson())
            .put("images", JSONArray(m.images)).put("contentHashes", JSONArray(m.contentHashes))
        if (m.layerId != null) o.put("layerId", m.layerId)
        return o.toString(2)
    }

    fun decode(s: String): Manifest? = runCatching {
        val o = JSONObject(s)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        Manifest(
            kind = o.optString("kind", KIND_DOCUMENT),
            deletedAt = parseDate(o.optString("deletedAt")) ?: Date(0),
            title = o.optString("title"),
            documentId = o.optString("documentId"),
            documentTitle = o.optString("documentTitle"),
            layerId = o.optString("layerId").ifEmpty { null },
            pageCount = o.optInt("pageCount"),
            counts = TrashStore.Counts.of(o.optJSONObject("counts")),
            images = list("images"),
            contentHashes = list("contentHashes"),
        )
    }.getOrNull()

    /** ISO8601：Mac 写的是 `…Z`；带不带毫秒、带不带时区偏移都认 */
    private fun parseDate(s: String): Date? {
        if (s.isEmpty()) return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            val d = runCatching {
                SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)
            }.getOrNull()
            if (d != null) return d
        }
        return null
    }

    // ---------- 目录名（Mac `directoryName`：本地时间 `yyyy-MM-dd'T'HH-mm-ss` + `_` + 净化过的标题，撞名加 -2） ----------

    fun directoryName(at: Date, title: String, existing: Set<String>): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(at)
        val safe = sanitize(title)
        val base = if (safe.isEmpty()) stamp else "${stamp}_$safe"
        if (base !in existing) return base
        var n = 2
        while ("$base-$n" in existing) n++
        return "$base-$n"
    }

    /** 路径分隔符与前导点去掉，压到 40 个字符以内（同 Mac `sanitize`） */
    fun sanitize(title: String): String {
        var s = title
        for (bad in listOf("/", ":", "\\", "\u0000", "\n", "\r")) s = s.replace(bad, "-")
        s = s.trim()
        while (s.startsWith(".")) s = s.substring(1)
        return s.take(40)
    }

    // ---------- 扫描 / 到期 ----------

    /** 全部条目，最近删的在前。manifest 读不出的按「未知条目」列出（标题用目录名），没有快照的废目录忽略（同 Mac） */
    fun scan(ws: File): List<Entry> {
        val root = folder(ws)
        val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { d ->
            if (!File(d, SNAPSHOT).isFile) return@mapNotNull null
            val m = runCatching { File(d, MANIFEST).readText() }.getOrNull()?.let { decode(it) }
                ?: Manifest(KIND_DOCUMENT, Date(d.lastModified()), d.name)
            Entry(d.name, d, m, d.listFiles()?.sumOf { it.length() } ?: 0L)
        }.sortedByDescending { it.manifest.deletedAt }
    }

    fun expired(entries: List<Entry>, now: Date = Date()): List<Entry> {
        val cutoff = now.time - RETENTION_DAYS * 86_400_000L
        return entries.filter { it.manifest.deletedAt.time < cutoff }
    }

    /** 回收站里还引用着的图（图片清理要让路，方案 §2.6） */
    fun protectedImages(ws: File): Set<String> = scan(ws).flatMap { it.manifest.images }.toSet()

    // ---------- 归档 + 删除 ----------

    /** 建条目目录 → 搬行 → 写 manifest（`.part` 改名）。任何一步失败都把这个新目录整个清掉再抛出 */
    private fun archive(ws: File, title: String, build: (File) -> Manifest): Entry {
        val root = folder(ws).apply { mkdirs() }
        val now = Date()
        val name = directoryName(now, title, root.list()?.toSet().orEmpty())
        val dir = File(root, name)
        if (!dir.mkdirs()) throw java.io.IOException("建不了回收站目录：${dir.path}")
        try {
            val m = build(File(dir, SNAPSHOT))
            val tmp = File(dir, ".$MANIFEST.part")
            tmp.writeText(encode(m))
            if (!tmp.renameTo(File(dir, MANIFEST))) throw java.io.IOException("manifest 改名失败")
            Log.i(TAG, "归档 ${m.kind}《${m.title}》→ $name（${m.counts.total} 条）")
            return Entry(name, dir, m, dir.listFiles()?.sumOf { it.length() } ?: 0L)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** 删一篇画板：先归档（板 + 页 + 全部条目），成了才删行；图片随后对账（回收站护着，不会被清） */
    fun deleteBoard(s: LibraryStore, ws: File, b: BoardNote, displayName: String): Entry {
        val e = archive(ws, displayName) { snap ->
            val a = s.trashArchiveBoard(b.id, snap)
            Manifest(KIND_BOARD, Date(), displayName, counts = a.counts, images = a.images)
        }
        s.deleteBoard(b.id)
        s.reconcileImageOrphans(e.manifest.images.toSet())
        return e
    }

    /** 删一篇文档：先归档（文档 + 版本 + 路径 + 全部笔记 / 图层 / 草稿纸 / 习题），成了才删行（级联） */
    fun deleteDocument(s: LibraryStore, ws: File, d: LibDocument): Entry {
        val e = archive(ws, d.title) { snap ->
            val a = s.trashArchiveDocument(d.id, snap)
            Manifest(
                KIND_DOCUMENT, Date(), d.title, documentId = d.id, documentTitle = d.title,
                pageCount = a.pageCount, counts = a.counts, images = a.images, contentHashes = a.contentHashes,
            )
        }
        s.deleteDocument(d.id)
        s.reconcileImageOrphans(e.manifest.images.toSet())
        return e
    }

    // ---------- 恢复 / 彻底删除 ----------

    /** 恢复结果：写回几行、是不是并入了已有的那一篇（PDF 重新导入过） */
    class Restored(val rows: Int, val mergedInto: String?)

    /** 恢复一条：情形 B（同一份 PDF 已在库里属于另一篇）并入那一篇；成功后删掉条目目录、图片对账 */
    fun restore(s: LibraryStore, e: Entry): Restored {
        val target = s.trashMergeTarget(e.snapshot)
        val n = s.trashRestore(e.snapshot, target)
        e.dir.deleteRecursively()
        s.reconcileImageOrphans(e.manifest.images.toSet())
        Log.i(TAG, "恢复《${e.manifest.title}》$n 行${if (target != null) "，并入现有文档 ${target.take(8)}" else ""}")
        return Restored(n, target)
    }

    /** 彻底删除一条（回收站自己的删除没有回收站——调用方要先问一次） */
    fun purge(s: LibraryStore?, e: Entry) {
        e.dir.deleteRecursively()
        s?.reconcileImageOrphans(e.manifest.images.toSet())
        Log.i(TAG, "彻底删除《${e.manifest.title}》")
    }

    /** 打开工作区时：清掉满 [RETENTION_DAYS] 天的条目 */
    fun purgeExpired(s: LibraryStore, ws: File): Int {
        val gone = expired(scan(ws))
        for (e in gone) purge(s, e)
        return gone.size
    }
}
