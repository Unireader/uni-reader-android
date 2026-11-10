package com.xvan.unireader.local.server

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 笔记文件同步（`Notes/` 下的 Markdown 正文与附件，按文件整份同步；契约 `../docs/PROTOCOL.md §9.5`，
 * 方案 `../docs/SERVER-MODE-PLAN.md §4.4 / §13.11`）。逐条照 Mac `Sources/Sync/SyncNotes.swift` +
 * `Sources/ServerSync/ServerNoteSync.swift` 的**内建 `Notes/` 那一半**（判定表在 [decide]，本端 `NoteSyncTest`）。
 *
 * 🔴 正文一个字不改（红线 9）：冲突时两份都留——服务器那份换到原路径，本机那份另存成
 * `<原名> (冲突 <设备名> <日期>).<扩展名>` 再上传；别的设备删了、本机没动的挪进 `Notes/.trash/<时间>/`，不抹掉。
 * 服务器上压根没记录（不是墓碑）绝不当成被删。
 *
 * **没做**：引用目录镜像（`NotesMirror/<源 id>/`，Mac 引用的外部目录）——本端既不上传也不拉。
 * 阻塞 IO，在网络线程调。基线在 `UniReader/note-sync.json`（不碰库连接），格式同 Mac。
 */
object NoteSync {
    const val TAG = "UniReader/NoteSync"
    const val FOLDER = "Notes"
    const val PREFIX = "Notes/"
    const val MAX_FILE_BYTES = 100L * 1024 * 1024

    /** 服务器上一个文件的现状（`GET notes` 的一项）；[deleted] = 墓碑。 */
    data class Entry(val path: String, val rev: Long, val sha256: String, val size: Long, val deleted: Boolean)

    /** 基线：上次同步时这个文件的样子。`size` + `mtime` 没变就认为内容没变，不重算哈希。 */
    data class Base(val rev: Long, val sha: String, val size: Long, val mtime: Double)

    class Report {
        var pushed = 0
        var pulled = 0
        var removedLocal = 0
        var removedRemote = 0
        var skipped = 0
        val conflicts = ArrayList<String>()
        val failed = ArrayList<String>()
        val touched: Boolean get() = pushed + pulled + removedLocal + removedRemote > 0 || conflicts.isNotEmpty()
        /** 本机的文件变了（下载 / 冲突副本 / 挪进回收站）：界面要重扫 `Notes/`。 */
        val localChanged: Boolean get() = pulled + removedLocal > 0 || conflicts.isNotEmpty()
        val summary: String get() = "上传 $pushed 下载 $pulled 本机清走 $removedLocal 服务器删 $removedRemote 留待下轮 $skipped " +
            "冲突 ${conflicts.size} 失败 ${failed.size}"
    }

    // ---------- 路径（同 Mac `SyncNotes`） ----------

    /** 线上的路径一律 NFC。 */
    fun normalize(p: String): String = Normalizer.normalize(p, Normalizer.Form.NFC)

    /** 能同步的路径：`Notes/…` 下的普通文件，任何一级名字都不以 `.` 开头、不叫 `trash`（不分大小写），不以 `.part` 结尾。 */
    fun isSyncablePath(p: String): Boolean {
        if (!p.startsWith(PREFIX) || p.endsWith(".part") || p.contains('\u0000')) return false
        val comps = p.split("/")
        return comps.size >= 2 && comps.all { it.isNotEmpty() && !it.startsWith(".") && it.lowercase() != "trash" }
    }

    class Item(val rel: String, val file: File, val size: Long, val mtime: Double)

    /** `<工作区>/Notes` 下全部能同步的文件（不读内容）。隐藏目录与 `trash` 整个不进；符号链接不跟。 */
    fun scan(folder: File): List<Item> {
        val root = File(folder, FOLDER)
        val out = ArrayList<Item>()
        fun walk(dir: File, rel: String) {
            for (f in dir.listFiles().orEmpty()) {
                val name = f.name
                if (name.startsWith(".") || name.lowercase() == "trash") continue
                if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                val r = rel + normalize(name)
                if (f.isDirectory) walk(f, "$r/")
                else if (f.isFile && isSyncablePath(r)) out += Item(r, f, f.length(), f.lastModified() / 1000.0)
            }
        }
        if (root.isDirectory) walk(root, PREFIX)
        return out.sortedBy { it.rel }
    }

    fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** `Notes/数学/极限.md` → `Notes/数学/极限 (冲突 平板 2026-11-03 12-50).md`（扩展名保留；重名往后加 ` 2`、` 3`）。 */
    fun conflictPath(rel: String, device: String, date: Date, taken: (String) -> Boolean): String {
        val slash = rel.lastIndexOf('/')
        val dir = if (slash >= 0) rel.substring(0, slash) else ""
        val file = rel.substring(slash + 1)
        val dot = file.lastIndexOf('.')
        val stem = if (dot > 0) file.substring(0, dot) else file
        val ext = if (dot > 0) file.substring(dot + 1) else ""
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(date)
        val dev = safeDevice(device)
        var n = 1
        while (true) {
            val tag = "冲突 $dev $stamp" + if (n > 1) " $n" else ""
            val name = "$stem ($tag)" + if (ext.isEmpty()) "" else ".$ext"
            val cand = if (dir.isEmpty()) name else "$dir/$name"
            if (!taken(cand)) return cand
            n++
        }
    }

    fun safeDevice(s: String): String {
        val t = s.map { if (it in "/\\:?*\"<>|()" || it.isISOControl()) '-' else it }.joinToString("").trim()
        return t.ifEmpty { "设备" }
    }

    // ---------- 判定表（同 Mac `ServerNoteSync.syncOne`） ----------

    sealed class Action {
        object None : Action()
        /** 两边已经一致：只记基线。 */
        object Record : Action()
        object Conflict : Action()
        object Pull : Action()
        data class Push(val base: Long) : Action()
        data class DeleteRemote(val base: Long) : Action()
        /** 别的设备删了、本机没动：挪进 `.trash`。 */
        object TrashLocal : Action()
        /** 两边都没有了：清掉基线。 */
        object Forget : Action()
    }

    /**
     * [localSha] = 本机现在的内容哈希（没有这个文件 = null）；[base] = 基线；[server] = 服务器上的记录（没有记录 = null，墓碑也算有记录）；
     * [full] = 完整一轮（问过服务器）。开着工作区时 [full] 为假：只推，不知道别处的改动，对不上的留给下一次完整一轮。
     */
    fun decide(localSha: String?, base: Base?, server: Entry?, full: Boolean): Action {
        if (!full) {
            return when {
                localSha != null -> if (base != null && base.sha == localSha) Action.None else Action.Push(base?.rev ?: 0)
                base != null -> Action.DeleteRemote(base.rev)
                else -> Action.None
            }
        }
        val alive = server?.takeIf { !it.deleted }
        if (alive != null) {
            if (localSha != null) {
                if (localSha == alive.sha256) return Action.Record
                if (base == null) return Action.Conflict            // 两边各自新建了同名文件
                return when {
                    localSha == base.sha -> Action.Pull              // 本机没动
                    alive.rev == base.rev -> Action.Push(base.rev)   // 只有本机改了
                    else -> Action.Conflict                          // 两边都改了
                }
            }
            // 本机删了、别处没再改 → 删服务器的；否则下载（新文件，或别处又改过——改动优先于删除）
            return if (base != null && alive.rev == base.rev) Action.DeleteRemote(base.rev) else Action.Pull
        }
        if (localSha != null) {
            // 墓碑 + 本机没动 → 跟着删；其余一律上传（本机新建，或本机改过而别处删了）
            return if (base != null && base.sha == localSha && server?.deleted == true) Action.TrashLocal else Action.Push(0)
        }
        return Action.Forget
    }

    // ---------- 基线文件 ----------

    fun baseFile(folder: File) = File(folder, "UniReader/note-sync.json")

    fun loadBase(folder: File): MutableMap<String, Base> {
        val out = HashMap<String, Base>()
        val text = runCatching { baseFile(folder).readText() }.getOrNull() ?: return out
        runCatching {
            val o = JSONObject(text)
            for (k in o.keys()) {
                val b = o.getJSONObject(k)
                out[k] = Base(b.getLong("rev"), b.getString("sha"), b.getLong("size"), b.getDouble("mtime"))
            }
        }
        return out
    }

    fun saveBase(base: Map<String, Base>, folder: File) {
        val o = JSONObject()
        for ((k, b) in base) o.put(k, JSONObject().put("rev", b.rev).put("sha", b.sha).put("size", b.size).put("mtime", b.mtime))
        val f = baseFile(folder)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.part")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "基线没写成", it) }
    }

    // ---------- 一轮 ----------

    private val gates = HashMap<String, Any>()
    private fun gate(folder: File): Any = synchronized(gates) { gates.getOrPut(folder.absoluteFile.normalize().path) { Any() } }

    /**
     * 同步这个工作区的 `Notes/`。[full] = 完整一轮（先问服务器现状，推 + 拉 + 冲突副本）；否则只推。
     * [skip] = 这一轮完全不碰的文件（编辑器里还有没存的输入的那篇）。同一个工作区同一时刻只跑一个。
     * 网络 / 认证 / 服务器错误往外抛（整轮放弃，已做完的基线照记）；单个文件的问题记进 [Report.failed]。
     * 服务器程序太旧没有这些接口（[ServerClient.Failure.isUnsupportedRoute]）→ 当没有笔记同步，返回空报告。
     */
    fun sync(folder: File, client: ServerClient, wid: String, device: String, full: Boolean, skip: Set<String> = emptySet()): Report =
        synchronized(gate(folder)) {
            val rep = Report()
            val base = loadBase(folder)
            try {
                val server: Map<String, Entry>? = if (full) {
                    val m = HashMap<String, Entry>()
                    for (e in client.listNotes(wid)) { val old = m[e.path]; if (old == null || e.rev >= old.rev) m[e.path] = e }
                    m
                } else null
                val locals = HashMap<String, Item>()
                val oversize = HashSet<String>()
                for (it in scan(folder)) if (it.size > MAX_FILE_BYTES) oversize += it.rel else locals[it.rel] = it
                val paths = HashSet(locals.keys)
                paths += base.keys.filter { it.startsWith(PREFIX) }
                server?.let { s -> paths += s.keys.filter { it.startsWith(PREFIX) } }
                paths -= oversize      // 太大的不同步，也不能因为「没扫到」当成被删了
                paths -= skip
                for (path in paths.sorted()) {
                    client.checkCancelled()
                    try {
                        one(folder, client, wid, device, path, locals[path], base, server, full, rep)
                    } catch (e: Throwable) {
                        if (e is ServerClient.Cancelled) throw e
                        if (e is ServerClient.Failure) {
                            if (e.isUnsupportedRoute || e.status == 401 || e.status == 403 || e.status >= 500) throw e
                        } else if (e is java.io.IOException && e !is java.io.FileNotFoundException) {
                            throw e        // 网络断了：整轮放弃
                        }
                        rep.failed += "$path：${e.message}"
                    }
                }
            } catch (e: ServerClient.Failure) {
                if (!e.isUnsupportedRoute) throw e
            } finally {
                saveBase(base, folder)      // 取消 / 出错也把已经做完的记下来
            }
            rep
        }

    private fun fileOf(folder: File, rel: String) = File(folder, rel)

    private fun one(
        folder: File, client: ServerClient, wid: String, device: String, path: String, item: Item?,
        base: MutableMap<String, Base>, server: Map<String, Entry>?, full: Boolean, rep: Report,
    ) {
        val b = base[path]
        val lsha = item?.let { if (b != null && b.size == it.size && b.mtime == it.mtime) b.sha else sha256Hex(it.file) }
        val s = server?.get(path)
        when (val act = decide(lsha, b, s, full)) {
            Action.None -> Unit
            Action.Record -> base[path] = Base(s!!.rev, lsha!!, item!!.size, item.mtime)
            Action.Forget -> base.remove(path)
            Action.Pull -> pull(folder, client, wid, path, base, rep)
            is Action.Push -> push(client, wid, path, act.base, item!!, lsha!!, base, rep)
            is Action.DeleteRemote -> when (client.deleteNote(wid, path, act.base)) {
                is ServerClient.NoteWrite.Ok -> { base.remove(path); rep.removedRemote++ }
                is ServerClient.NoteWrite.Stale -> rep.skipped++
            }
            Action.TrashLocal -> {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dst = File(folder, "$FOLDER/.trash/$stamp/${path.removePrefix(PREFIX)}")
                dst.parentFile?.mkdirs()
                dst.delete()
                if (!item!!.file.renameTo(dst)) throw java.io.FileNotFoundException("挪不进 .trash：$path")
                base.remove(path)
                rep.removedLocal++
            }
            Action.Conflict -> conflict(folder, client, wid, device, path, item!!, base, server.orEmpty(), rep)
        }
    }

    /** 从服务器取下来换到本机（先写旁边的 `.part` 再改名）。基线记成下载到的修订号 + 实际内容的哈希。 */
    private fun pull(folder: File, client: ServerClient, wid: String, path: String, base: MutableMap<String, Base>, rep: Report) {
        val dst = fileOf(folder, path)
        val rev = client.downloadNote(wid, path, dst)
        base[path] = Base(rev, sha256Hex(dst), dst.length(), dst.lastModified() / 1000.0)
        rep.pulled++
    }

    private fun push(
        client: ServerClient, wid: String, path: String, baseRev: Long, item: Item, sha: String,
        base: MutableMap<String, Base>, rep: Report,
    ) {
        when (val r = client.putNote(wid, path, baseRev, item.file)) {
            is ServerClient.NoteWrite.Ok -> { base[path] = Base(r.rev, sha, item.size, item.mtime); rep.pushed++ }
            is ServerClient.NoteWrite.Stale -> rep.skipped++     // 服务器上又变过：留给下一次完整一轮
        }
    }

    /**
     * 两边都改过：服务器那份换到原路径，本机那份另存成冲突副本并上传。两份字节都在。
     * 顺序：先把本机那份复制成副本（复制不成就什么都没动）→ 下载服务器版本换掉原路径 → 上传副本。
     */
    private fun conflict(
        folder: File, client: ServerClient, wid: String, device: String, path: String, item: Item,
        base: MutableMap<String, Base>, server: Map<String, Entry>, rep: Report,
    ) {
        val copyPath = conflictPath(path, device, Date()) { cand ->
            server.containsKey(cand) || base.containsKey(cand) || fileOf(folder, cand).exists()
        }
        val copy = fileOf(folder, copyPath)
        copy.parentFile?.mkdirs()
        item.file.copyTo(copy, overwrite = false)
        try {
            pull(folder, client, wid, path, base, rep)
        } catch (e: Throwable) {
            copy.delete()       // 服务器那份没取到：原文件没动，副本也不留
            throw e
        }
        rep.conflicts += copyPath
        val ci = Item(copyPath, copy, copy.length(), copy.lastModified() / 1000.0)
        push(client, wid, copyPath, 0, ci, sha256Hex(copy), base, rep)
    }
}
