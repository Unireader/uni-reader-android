package com.xvan.unireader.local.mirror

import android.os.StatFs
import android.util.Log
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibLocation
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.Workspace
import java.io.File
import java.util.UUID

/**
 * 建离线镜像：把一个工作区整份复制到本机内部存储（方案 `../OFFLINE-MIRROR-PLAN.md` §8.1）。
 *
 * **库全量、PDF 选择性**（§7）：平板内部存储装不下一块几十 GB 的硬盘，但 `library.sqlite`
 * 只有几十 MB。于是镜像里**所有书都看得见、所有笔记都在**，只是部分书没带 PDF，打开时提示
 * 「插回硬盘再看」。这条取舍还让 diff 完全不受「带没带文件」影响 —— 基线永远是整库的。
 *
 * 🔴 **必须在 [com.xvan.unireader.local.store.StoreQueue] 的独占线程上调用**：
 * ① 慢卷上拷 PDF 是分钟级、`VACUUM INTO` 是秒级，主线程做必 ANR（§9.5 的既有教训）；
 * ② 走同一条队列才保证拷贝期间进程内没有别人在写源库——[LibraryStore.vacuumInto] 的
 * 老机器兜底路径（整文件拷）正是靠这一条才安全。
 */
object MirrorBuilder {

    const val TAG = "UniReader/Mirror"

    /** 镜像的默认存放位置：共享存储的 Documents，用户可见、**卸载 app 不丢笔迹**（方案 §9 已拍板） */
    fun defaultParent(): File =
        File(android.os.Environment.getExternalStorageDirectory(), "Documents/UniReader")

    data class Plan(
        /** 要**带 PDF** 的文档 id。不在其中的书仍然进镜像（元数据、笔记齐全），只是打不开正文 */
        val documentsWithPDF: Set<String>,
        /** 建镜像时顺手记下的源盘位置，只为将来给一句「把那块盘插上」的人话提示（§5.3） */
        val sourceHint: String = "",
    )

    data class Estimate(
        val files: Int,
        val bytes: Long,
        /**
         * 勾了但当下解析不到文件的文档（源盘没插全、外部文件被挪走…）。**必须报出来**：
         * 静默跳过的话用户会以为带上了，等硬盘不在手上时才发现打不开。
         */
        val unresolved: List<String>,
    )

    data class Result(
        val dir: File,
        val mirrorId: String,
        val copiedFiles: Int,
        val copiedBytes: Long,
        val internalized: Int,
        val baseRows: Int,
        val unresolved: List<String>,
        /** true = 用了整文件拷贝的兜底路径（本机 SQLite < 3.27），日志/排查用 */
        val usedFileCopyFallback: Boolean,
    )

    class Failure(message: String) : Exception(message)

    // ---------- 估算 ----------

    /** 算这份计划要拷多少文件、多少字节。**拷之前必须先算**——内部存储写满会连累整个系统 */
    fun estimate(
        source: File,
        store: LibraryStore,
        plan: Plan,
        resolve: (LibLocation) -> File?,
    ): Estimate {
        var files = 0
        var bytes = File(store.dbPath).length()   // 库本身（VACUUM 后通常更小，按原大小算即多留余量）
        val unresolved = ArrayList<String>()
        for (docId in plan.documentsWithPDF.sorted()) {
            val hit = pickSource(store, docId, resolve)
            if (hit == null) {
                unresolved.add(docId)
                continue
            }
            files++
            bytes += hit.first.length()
        }
        return Estimate(files, bytes, unresolved)
    }

    // ---------- 建镜像 ----------

    /**
     * 在 [destination]（一个尚不存在的 `.unrd` 目录）建出镜像。
     *
     * 失败会**连整个新建的目录一起清掉**：留半个骨架在那儿，下次扫描会把它列出来、点开又说
     * 「这里没有 library.sqlite」，比没建成难查得多（同 [Workspace.create] 的口径）。
     */
    fun create(
        source: File,
        store: LibraryStore,
        destination: File,
        plan: Plan,
        deviceId: String,
        resolve: (LibLocation) -> File?,
        progress: ((String, Float) -> Unit)? = null,
    ): Result {
        if (store.meta(MirrorStore.META_MIRROR_OF) != null) throw Failure("这已经是一份离线镜像，不能再做镜像")
        if (destination.exists()) throw Failure("这里已经有「${destination.name}」了，换个名字")

        val est = estimate(source, store, plan, resolve)
        val need = (est.bytes * 1.05).toLong()
        val free = availableBytes(destination.parentFile)
        if (free in 0 until need) throw Failure("空间不够：需要 ${mb(need)}，可用 ${mb(free)}")

        // 源库的 id 要在拷贝**之前**就位：镜像靠它认源盘，而它是从源库拷过去的
        val sourceId = store.ensureWorkspaceId()
        val noteCount = store.noteCount()

        var created = false
        try {
            progress?.invoke("正在准备…", 0f)
            require(File(destination, "UniReader").mkdirs()) { "建不了目录：${destination.absolutePath}" }
            created = true
            require(File(destination, Workspace.PDFS_DIR).mkdirs()) { "建不了 ${Workspace.PDFS_DIR} 目录" }

            // ① 一致快照
            progress?.invoke("正在复制资料库…", 0.05f)
            store.checkpoint()
            val mirrorDb = File(destination, Workspace.DB_REL)
            val fallback = !store.vacuumInto(mirrorDb.absolutePath)
            if (fallback) {
                // 老机器：checkpoint 已把 -wal 收回主库，此刻整文件拷即是一致的
                // （本函数独占 StoreQueue 线程，进程内没有别人在写——见类注释）
                File(store.dbPath).copyTo(mirrorDb, overwrite = false)
            }
            Log.i(TAG, "库已复制 → ${mirrorDb.absolutePath}（${if (fallback) "整文件拷" else "VACUUM INTO"}）")

            // ② 镜像库自己的连接（新文件，全 app 没有第二个人持有它）
            Db.open(mirrorDb).use { mdb ->
                // ③ 拷 PDF + 内化外部文件
                var copied = 0
                var copiedBytes = 0L
                var internalized = 0
                val targets = plan.documentsWithPDF.sorted()
                targets.forEachIndexed { i, docId ->
                    progress?.invoke("正在复制文件…", 0.1f + 0.8f * i / maxOf(targets.size, 1))
                    val hit = pickSource(store, docId, resolve) ?: return@forEachIndexed
                    val (src, loc) = hit
                    // 工作区内的副本：**保持同一条相对路径**拷过去，镜像库里那行 location 原样就有效，
                    // 一个字都不用改。外部文件才需要内化。
                    val rel = if (loc.inWorkspace) loc.path else "${Workspace.PDFS_DIR}/${UUID.randomUUID()}.pdf"
                    val dst = File(destination, rel)
                    dst.parentFile?.mkdirs()
                    copyAtomically(src, dst)
                    copied++
                    copiedBytes += dst.length()
                    if (!loc.inWorkspace) {
                        // 内化：在**镜像库**里补一条工作区内 location。原来那条外部路径的行留着不动——
                        // 它在镜像上解析不到只是灰一档，而 location 本就不参与同步（方案 §4），
                        // 删它既没收益又是破坏性操作。
                        mdb.exec(
                            "INSERT INTO location(id,variant_id,path,is_valid,last_validated_at,in_workspace,is_relative) " +
                                "VALUES(?,?,?,1,?,1,0)",
                            arrayOf<Any?>(UUID.randomUUID().toString(), loc.variantId, rel, Iso.now()),
                        )
                        internalized++
                    }
                }

                // ④ 血缘 meta。**镜像必须换一个自己的 workspace_id**：拷出来的副本原样带着源库的 id，
                // 不换的话「扫一圈盘按 workspace_id 找源」会把镜像自己也认成源。
                progress?.invoke("正在写入基线…", 0.92f)
                val mirrorId = UUID.randomUUID().toString()
                for ((k, v) in listOf(
                    "workspace_id" to UUID.randomUUID().toString(),
                    MirrorStore.META_MIRROR_OF to sourceId,
                    MirrorStore.META_MIRROR_ID to mirrorId,
                    MirrorStore.META_MIRROR_CREATED_AT to Iso.now(),
                    MirrorStore.META_MIRROR_SOURCE_HINT to plan.sourceHint,
                )) {
                    mdb.exec(
                        "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        arrayOf<Any?>(k, v),
                    )
                }
                // 「本机开着哪几篇」是源设备的状态，别让镜像一打开就复现源盘的会话
                mdb.exec("DELETE FROM meta WHERE key IN ('open_documents','offline_checkouts')")

                // ⑤ 基线。必须在上面所有写入**之后**——虽然 mirror_* 都不在同步白名单里，
                // 但顺序写反了就得靠「它恰好不影响」来解释，不如一律最后算。
                val baseRows = MirrorStore.rebuildSyncBase(mdb)

                // ⑥ 源库记一笔借出（信息不是锁，§5.2）
                val list = MirrorStore.decodeCheckouts(store.meta(MirrorStore.META_CHECKOUTS))
                val c = MirrorStore.Checkout(
                    mirrorId = mirrorId,
                    deviceId = deviceId,
                    deviceName = MirrorStore.deviceName(),
                    takenAt = Iso.now(),
                    lastSyncedAt = null,
                    noteCount = noteCount,
                )
                store.setMeta(
                    MirrorStore.META_CHECKOUTS,
                    MirrorStore.encodeCheckouts(MirrorStore.upsertCheckout(list, c)),
                )

                progress?.invoke("完成", 1f)
                Log.i(TAG, "镜像建好：${destination.absolutePath} 文件=$copied 内化=$internalized 基线=$baseRows")
                return Result(
                    destination, mirrorId, copied, copiedBytes, internalized,
                    baseRows, est.unresolved, fallback,
                )
            }
        } catch (e: Exception) {
            if (created) runCatching { destination.deleteRecursively() }
            Log.e(TAG, "建镜像失败：${destination.absolutePath}", e)
            throw e
        }
    }

    // ---------- 内部 ----------

    /** 挑一条能打开的 location：**工作区内的优先**（那条随文件夹走、最稳），其次外部 */
    fun pickSource(
        store: LibraryStore,
        documentId: String,
        resolve: (LibLocation) -> File?,
    ): Pair<File, LibLocation>? {
        val locs = store.locations(documentId).sortedByDescending { it.inWorkspace }
        for (loc in locs) {
            val f = resolve(loc) ?: continue
            if (f.isFile) return f to loc
        }
        return null
    }

    /**
     * 先写临时名再原子 rename。中途拔盘/断电只会留一个 `.part`，不会留一个**看着是好的、
     * 其实只拷了一半**的 PDF —— 后者要等用户翻到那一页才发现。
     */
    private fun copyAtomically(from: File, to: File) {
        val tmp = File(to.parentFile, ".${to.name}.part")
        tmp.delete()
        from.copyTo(tmp, overwrite = true)
        to.delete()
        if (!tmp.renameTo(to)) {
            tmp.delete()
            throw Failure("拷贝后改名失败：${to.absolutePath}")
        }
    }

    /** 目标卷可用字节。取不到（路径还不存在等）返回 -1，调用方按「不知道」处理而不是「不够」 */
    private fun availableBytes(dir: File?): Long = try {
        var d = dir
        while (d != null && !d.isDirectory) d = d.parentFile
        if (d == null) -1L else StatFs(d.absolutePath).availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "取不到可用空间，跳过空间检查", e)
        -1L
    }

    private fun mb(n: Long): String = "%.1f MB".format(n / 1024.0 / 1024.0)
}
