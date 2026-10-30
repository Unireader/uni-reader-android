package com.xvan.unireader.local.server

import android.os.Environment
import android.util.Log
import com.xvan.unireader.local.ImageAssets
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.LibraryStore
import java.io.File
import java.util.UUID

/**
 * 服务器同步里**纯 IO 的那一半**：一轮同步、建本机缓存、上传本地工作区、文件上传下载（照 Mac `ServerSyncIO`）。
 * 调度与状态在 [ServerSync]，界面在 [ServerUi]。这里的函数全是阻塞的，只在后台线程调。
 *
 * 用到库的地方各自开**短连接**（同进程里多条 SQLite 连接由 WAL + busy_timeout 管，见 `LibraryActivity.startAdd`
 * 的说明）；但完整一轮会写用户数据，🔴 **只在阅读界面没开着这个工作区时跑**（打开前 / 关掉后，[ServerSync] 管时机）。
 */
object ServerSyncIO {

    const val TAG = "UniReader/Server"

    /**
     * 本机缓存放这里（方案 §2「安卓模式1」：共享存储，卸载 App 不丢；同离线副本的 `Documents/UniReader`）。
     * 不让用户选位置。下面一层是 `<主机-端口>`，再下面是 `<工作区名>.unrd`。
     */
    val cachesRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "Documents/UniReader/Servers")

    fun isInCachesFolder(folder: File): Boolean =
        folder.absoluteFile.normalize().path.startsWith(cachesRoot.absoluteFile.normalize().path + "/")

    /** 这个工作区连的服务器（`meta.srv_url` + `srv_id`）；不是服务器工作区 = null。只读短连接看一眼 */
    data class Link(val url: String, val serverId: String?, val workspaceId: String?)

    fun serverLink(folder: File): Link? {
        val f = File(folder, Workspace.DB_REL)
        if (!f.isFile) return null
        return runCatching {
            Db.open(f, readOnly = true).use { db ->
                fun meta(k: String) = db.query("SELECT value FROM meta WHERE key=?", arrayOf(k)) { it.getString(0) }
                    .firstOrNull()?.takeIf { it.isNotEmpty() }
                val url = meta(SyncClient.META_SERVER_URL) ?: return@use null
                Link(url, meta(SyncClient.META_SERVER_ID), meta("workspace_id"))
            }
        }.getOrNull()
    }

    // ---------- 一轮（临时连接） ----------

    /** 完整一轮：推（先传新文件）→ 拉 → 应用 → 补缺的图片。🔴 只在阅读界面没开着它时调 */
    fun closedRound(folder: File, client: ServerClient, wid: String): SyncClient.RoundReport =
        LibraryStore.open(folder).use { store ->
            store.withMirrorDb { db ->
                val rep = SyncClient.round(
                    db, folder,
                    beforePush = { changes -> uploadAll(filesToUpload(changes, db, folder), client, wid) },
                    push = { req -> SyncWire.decodePushResponse(client.push(wid, SyncWire.encode(req))) },
                    pull = { since -> SyncWire.decodePullResponse(client.pull(wid, since)) },
                    checkCancelled = { client.checkCancelled() },
                )
                downloadMissingImages(db, folder, client, wid)
                rep
            }
        }

    // ---------- 文件 ----------

    sealed class FileUp {
        data class Pdf(val hash: String, val file: File) : FileUp()
        data class Image(val sha: String, val ext: String, val file: File) : FileUp()
    }

    /**
     * 推送前要先传上去的 PDF / 图片（只读库，可以在库队列上算好再交给网络线程）：服务器收图片行要求文件已在
     * （`MISSING_FILE`）；PDF 的 variant 行没有文件也收，但那样别的设备就下不到了。
     */
    fun filesToUpload(changes: List<SyncWire.RowChange>, db: Db, folder: File): List<FileUp> {
        val out = ArrayList<FileUp>()
        for (c in changes) {
            if (c.op != SyncWire.Op.UPSERT) continue
            when (c.table) {
                "variant" -> {
                    val hash = (c.row?.get("content_hash") as? com.xvan.unireader.local.store.MirrorFp.Value.Text)?.v ?: continue
                    val f = localPdf(db, folder, c.rowId) ?: continue
                    out.add(FileUp.Pdf(hash, f))
                }
                SyncRows.IMAGE -> {
                    val ext = (c.row?.get("ext") as? com.xvan.unireader.local.store.MirrorFp.Value.Text)?.v ?: continue
                    val f = ImageAssets.file(folder, c.rowId, ext)
                    if (f.isFile) out.add(FileUp.Image(c.rowId, ext, f))
                }
            }
        }
        return out
    }

    /** 传失败就整轮放弃（行没推，下一轮再来） */
    fun uploadAll(files: List<FileUp>, client: ServerClient, wid: String) {
        for (f in files) {
            when (f) {
                is FileUp.Pdf -> client.uploadPdf(wid, f.hash, f.file)
                is FileUp.Image -> client.uploadImage(wid, f.sha, f.ext, f.file)
            }
        }
    }

    /** 这个 variant 在本机能打开的文件（工作区内的优先）。路径口径同 [Workspace.resolvePdf] */
    fun localPdf(db: Db, folder: File, variantId: String): File? {
        val rows = db.query(
            "SELECT path, in_workspace, is_relative FROM location WHERE variant_id=? ORDER BY in_workspace DESC",
            arrayOf(variantId),
        ) { c -> Triple(c.getString(0), c.getInt(1) != 0, c.getInt(2) != 0) }
        for ((p, inWs, rel) in rows) {
            if (p == null) continue
            val f = if (inWs || rel) File(folder, p) else File(p)
            if (f.isFile) return f
        }
        return null
    }

    /** 库里有行、本机没文件的图片：从服务器补下来（某一张取不到就跳过它） */
    fun downloadMissingImages(db: Db, folder: File, client: ServerClient, wid: String) {
        val rows = db.query("SELECT sha256, ext FROM image") { it.getString(0) to it.getString(1) }
        for ((sha, ext) in rows) {
            client.checkCancelled()
            if (sha == null || ext == null || ImageAssets.file(folder, sha, ext).isFile) continue
            try {
                client.downloadImage(wid, sha, ext, ImageAssets.file(folder, sha, ext))
            } catch (e: ServerClient.Cancelled) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "图片 ${sha.take(8)} 取不到，跳过", e)
            }
        }
    }

    // ---------- PDF ----------

    class NotOnServer : Exception()

    /**
     * 下载一篇文档的 PDF（第一个服务器上有的版本）：有工作区内的 location 但文件不在 → 下到那个路径；
     * 否则下到 `PDFs/<hash>.pdf` 并补一条 location。[maxBytes] 给了而文件更大 → 不下，返回 false。
     * 服务器上一个版本都没有 → [NotOnServer]。location 用一条短连接写（[store] 给了就用它）。
     */
    fun downloadPdf(
        folder: File,
        documentId: String,
        client: ServerClient,
        wid: String,
        maxBytes: Long? = null,
        store: LibraryStore? = null,
        progress: ((Long, Long) -> Unit)? = null,
    ): Boolean {
        val own = store ?: LibraryStore.open(folder)
        try {
            for (v in own.variants(documentId)) {
                val size = client.pdfSize(wid, v.contentHash) ?: continue
                if (maxBytes != null && size > maxBytes) return false
                val existing = own.locationsOfVariant(v.id).firstOrNull { it.inWorkspace }
                val rel = existing?.path ?: "${Workspace.PDFS_DIR}/${v.contentHash}.pdf"
                client.downloadPdf(wid, v.contentHash, File(folder, rel), progress)
                if (existing == null) own.addLocation(v.id, rel, inWorkspace = true)
                return true
            }
            throw NotOnServer()
        } finally {
            if (store == null) own.close()
        }
    }

    /** 本机没文件的书：服务器上有、而且不大的，下载下来（方案 §4.1：大 PDF 等用到再下） */
    fun prefetchSmallPdfs(folder: File, client: ServerClient, wid: String, maxBytes: Long): Int {
        var n = 0
        LibraryStore.open(folder).use { store ->
            for (doc in store.allDocuments()) {
                client.checkCancelled()
                if (Workspace.firstOpenablePdf(folder, store, doc.id) != null) continue
                try {
                    if (downloadPdf(folder, doc.id, client, wid, maxBytes, store)) n++
                } catch (e: ServerClient.Cancelled) {
                    throw e
                } catch (e: NotOnServer) {
                    continue
                } catch (e: Exception) {
                    Log.w(TAG, "《${doc.title}》的 PDF 没下下来", e)
                }
            }
        }
        return n
    }

    // ---------- 建本机缓存 ----------

    enum class Step { LIBRARY, PREPARING, IMAGES }

    /** 下载快照建一份本机缓存，放在 [dir] 下（名字不撞）。返回缓存文件夹 */
    fun createCache(
        dir: File,
        name: String,
        serverUrl: String,
        serverId: String?,
        client: ServerClient,
        wid: String,
        progress: (Step) -> Unit,
    ): File {
        require(dir.isDirectory || dir.mkdirs()) { "mkdir ${dir.absolutePath}" }
        val tmp = File(dir, ".${UUID.randomUUID()}.download")
        try {
            progress(Step.LIBRARY)
            val rev = client.downloadSnapshot(wid, File(tmp, Workspace.DB_REL))
            File(tmp, Workspace.PDFS_DIR).mkdirs()
            progress(Step.PREPARING)
            LibraryStore.open(tmp).use { store ->
                store.setMeta(SyncClient.META_SERVER_URL, serverUrl)
                if (serverId != null) store.setMeta(SyncClient.META_SERVER_ID, serverId)
                store.withMirrorDb { SyncClient.resetBase(it, tmp, rev) }
            }
            val dst = uniqueFolder(dir, safeName(name))
            require(tmp.renameTo(dst)) { "rename ${tmp.absolutePath}" }
            progress(Step.IMAGES)
            runCatching {
                Db.open(File(dst, Workspace.DB_REL)).use { downloadMissingImages(it, dst, client, wid) }
            }.onFailure { Log.w(TAG, "建缓存后补图片没补完（下次同步再补）", it) }
            return dst
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    /** 缓存目录里的一份缓存 */
    data class Cache(val folder: File, val workspaceId: String, val serverUrl: String?, val serverId: String?)

    /** 缓存目录里的全部缓存（读每个库的 meta；启动页离线时也靠它列出本机有的） */
    fun caches(root: File = cachesRoot): List<Cache> {
        val out = ArrayList<Cache>()
        for (host in root.listFiles().orEmpty()) {
            if (!host.isDirectory) continue
            for (ws in host.listFiles().orEmpty()) {
                if (!ws.isDirectory || !Workspace.looksLikeWorkspace(ws)) continue
                val f = File(ws, Workspace.DB_REL)
                if (!f.isFile) continue
                runCatching {
                    Db.open(f, readOnly = true).use { db ->
                        fun meta(k: String) = db.query("SELECT value FROM meta WHERE key=?", arrayOf(k)) { it.getString(0) }
                            .firstOrNull()?.takeIf { it.isNotEmpty() }
                        meta("workspace_id")?.let { id ->
                            out.add(Cache(ws, id, meta(SyncClient.META_SERVER_URL), meta(SyncClient.META_SERVER_ID)))
                        }
                    }
                }
            }
        }
        return out
    }

    fun findCache(workspaceId: String, root: File = cachesRoot): File? =
        caches(root).firstOrNull { it.workspaceId.equals(workspaceId, ignoreCase = true) }?.folder

    /** 服务器地址 → 缓存目录下的子目录名（`主机-端口`） */
    fun hostFolderName(serverUrl: String): String {
        val rest = serverUrl.substringAfter("://").substringBefore('/')
        return safeName(rest.replace(':', '-').ifEmpty { "server" })
    }

    fun safeName(s: String): String {
        val cleaned = s.map { if (it in "/\\:?*\"<>|" || it.isISOControl()) '-' else it }.joinToString("").trim()
        val noDot = if (cleaned.startsWith(".")) "_" + cleaned.drop(1) else cleaned
        return noDot.ifEmpty { "Workspace" }
    }

    fun uniqueFolder(dir: File, name: String): File {
        var f = File(dir, "$name.unrd")
        var n = 2
        while (f.exists()) {
            f = File(dir, "$name $n.unrd")
            n++
        }
        return f
    }

    // ---------- 上传本地工作区 ----------

    data class UploadFile(val rel: String, val src: File, val size: Long)

    data class UploadPlan(
        val library: File,
        val files: List<UploadFile>,
        /** 对着上传出去的那份快照算的基线（方案 §5：上传期间本机又改的不在里面，下一轮才推） */
        val base: Map<String, Map<String, String>>,
        /** 本机找不到文件的书（行照常上传，PDF 没有） */
        val missingPdfs: Int,
    )

    /**
     * 准备上传：`VACUUM INTO` 出快照（老系统退回 checkpoint + 整文件拷）；快照里每本书只留一条工作区内的 location
     * （外部文件改成 `PDFs/<uuid>.pdf`，上传时传原文件）；列出要传的 PDF、图片、`Notes/` 下的笔记；对着快照算基线。
     */
    fun prepareUpload(store: LibraryStore, folder: File, staging: File): UploadPlan {
        require(staging.isDirectory || staging.mkdirs()) { "mkdir ${staging.absolutePath}" }
        val lib = File(staging, "library.sqlite")
        store.checkpoint()
        if (!store.vacuumInto(lib.absolutePath)) File(store.dbPath).copyTo(lib, overwrite = true)
        Db.open(lib).use { db ->
            val files = ArrayList<UploadFile>()
            val used = HashSet<String>()
            var missing = 0
            for (vid in db.query("SELECT id FROM variant") { it.getString(0) }) {
                var chosen: Pair<String, File>? = null
                val locs = db.query(
                    "SELECT path, in_workspace, is_relative FROM location WHERE variant_id=? ORDER BY in_workspace DESC",
                    arrayOf(vid),
                ) { c -> Triple(c.getString(0), c.getInt(1) != 0, c.getInt(2) != 0) }
                for ((p, inWs, rel) in locs) {
                    if (p == null) continue
                    val abs = if (inWs || rel) File(folder, p) else File(p)
                    if (!abs.isFile) continue
                    chosen = (if (inWs && p !in used) p else "${Workspace.PDFS_DIR}/${UUID.randomUUID().toString().uppercase()}.pdf") to abs
                    break
                }
                db.exec("DELETE FROM location WHERE variant_id=?", arrayOf<Any?>(vid))
                val c = chosen
                if (c == null) {
                    missing++
                    continue
                }
                db.exec(
                    "INSERT INTO location(id,variant_id,path,is_valid,in_workspace,is_relative) VALUES(?,?,?,1,1,0)",
                    arrayOf<Any?>(UUID.randomUUID().toString().uppercase(), vid, c.first),
                )
                used.add(c.first)
                files.add(UploadFile(c.first, c.second, c.second.length()))
            }
            for ((sha, ext) in db.query("SELECT sha256, ext FROM image") { it.getString(0) to it.getString(1) }) {
                if (sha == null || ext == null) continue
                val src = ImageAssets.file(folder, sha, ext)
                if (src.isFile) files.add(UploadFile("${ImageAssets.FOLDER}/$sha.$ext", src, src.length()))
            }
            // Notes/ 下的文件（跳过 `.` 开头的文件和目录，含 Obsidian 的 `.trash`，同 Mac `.skipsHiddenFiles`）
            val notes = File(folder, "Notes")
            if (notes.isDirectory) {
                notes.walkTopDown()
                    .onEnter { it == notes || !it.name.startsWith(".") }
                    .filter { it.isFile && !it.name.startsWith(".") }
                    .forEach { f ->
                        files.add(UploadFile("Notes/" + f.relativeTo(notes).invariantSeparatorsPath, f, f.length()))
                    }
            }
            val base = SyncClient.computeBase(db, folder)
            db.pragma("wal_checkpoint(TRUNCATE)")
            db.pragma("journal_mode=DELETE")   // 传出去的是单个文件
            return UploadPlan(lib, files, base, missing)
        }
    }

    /** 按准备好的清单上传，全部到齐后登记。失败时放弃这次上传会话（服务器那边 7 天后也会自己清） */
    fun upload(plan: UploadPlan, client: ServerClient, progress: (Int, Int, Double) -> Unit): ServerClient.WorkspaceInfo {
        val session = client.newUpload()
        try {
            val libSize = plan.library.length()
            val total = maxOf(1L, libSize + plan.files.sumOf { it.size }).toDouble()
            val count = plan.files.size
            client.uploadFile(session, Workspace.DB_REL, plan.library) { done, _ -> progress(0, count, done / total) }
            var sent = libSize
            for ((i, f) in plan.files.withIndex()) {
                val base = sent
                client.uploadFile(session, f.rel, f.src) { done, _ -> progress(i + 1, count, (base + done) / total) }
                sent += f.size
            }
            return client.finishUpload(session)
        } catch (e: Exception) {
            client.with(null).cancelUpload(session)
            throw e
        }
    }
}
