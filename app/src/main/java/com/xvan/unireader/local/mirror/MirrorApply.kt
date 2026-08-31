package com.xvan.unireader.local.mirror

import android.util.Log
import com.xvan.unireader.local.Workspace
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibLocation
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.MirrorFp
import java.io.File
import java.util.UUID

/**
 * **应用**三方合并的结果（方案 `../OFFLINE-MIRROR-PLAN.md` §8.3 第 4~5 步）。
 * 算是 [MirrorDiff] 的事，这里只负责把算好的 Plan 落到两边的库和文件上。
 *
 * 🔴 这是整个功能里**唯一会大批量改用户数据**的一段。四道保险：
 * ① 合并前先把源库整份备份出来（`VACUUM INTO`，留最近 3 份）；
 * ② 每一侧的行操作在**一个事务**里，中途出错整体回滚；
 * ③ 文件搬运在事务外、幂等、可重入 —— 拷一半再来一次结果一样；
 * ④ 基线**两侧都成功了才重算**。
 *
 * ## 为什么"半途而废"是安全的
 *
 * 两个库在两个文件上，跨库事务不存在，所以理论上会出现「源盘写了、镜像没写」。
 * 这在本方案里**能自愈**，不需要补偿逻辑：下一次跑 diff 时，那些已经落到源盘的行会变成
 * 「两端相对基线都改成了同一个样子」（`mineFp == theirsFp`）→ 判为无操作；没落的那些仍然
 * 照常被算出来。所以顺序是**先源盘后镜像**：源盘在可移动介质上，中途被拔的概率更高，
 * 让它先落地、失败也只是回滚到原样。
 *
 * 🔴 与 Mac `Sources/Store/MirrorApply.swift` 同一套流程与顺序，改一边同步另一边。
 * 🔴 **必须跑在 `StoreQueue` 的独占线程上**（同 [MirrorBuilder]）。
 */
object MirrorApply {

    const val TAG = "UniReader/MirrorApply"

    data class Result(
        var sourceUpserts: Int = 0,
        var sourceDeletes: Int = 0,
        var mirrorUpserts: Int = 0,
        var mirrorDeletes: Int = 0,
        /** 因为父文档已经不在了而被丢弃的行（见 [livingDocuments]） */
        var orphansSkipped: Int = 0,
        /** 因为**对面已经有同一份内容**而被丢弃的 `variant` 行（见 [write] 里那段） */
        var hashClashesSkipped: Int = 0,
        var lastOpenedTouched: Int = 0,
        var filesCopiedToSource: Int = 0,
        var backup: File? = null,
    )

    // ---------- 备份 ----------

    /**
     * 合并前把源库整份备份到 `<源>/UniReader/backup/library-<时间戳>.sqlite`，保留最近 [keep] 份。
     *
     * 廉价保险：库通常几十 MB，而这一步保护的是用户全部的笔迹。
     * **失败即中止整次合并** —— 没有备份就动手，是把"最坏情况"从"回滚"变成"没得救"。
     */
    fun backupSource(store: LibraryStore, folder: File, keep: Int = 3): File {
        val dir = File(folder, "UniReader/backup").apply { mkdirs() }
        val dst = File(dir, "library-${Iso.now().replace(':', '-')}.sqlite")
        store.checkpoint()
        if (!store.vacuumInto(dst.absolutePath)) {
            // 老机器没有 VACUUM INTO：checkpoint 已把 -wal 收回主库，整文件拷即是一致的
            // （本函数独占 StoreQueue 线程，进程内没有别人在写）
            File(store.dbPath).copyTo(dst, overwrite = false)
        }
        prune(dir, keep)
        return dst
    }

    private fun prune(dir: File, keep: Int) {
        val all = dir.listFiles { f -> f.name.startsWith("library-") && f.name.endsWith(".sqlite") }
            ?.sortedBy { it.name }   // 名字里带 ISO 时间戳 → 字典序即时间序
            ?: return
        for (old in all.dropLast(keep)) old.delete()
    }

    // ---------- 写一侧 ----------

    /**
     * 目标表实际有哪些列。**按目标库的列来写**，不按源行带来的键：两端 schema 版本可能差一格，
     * 硬写会因"没有这一列"整批失败。
     */
    private fun columns(db: Db, table: String): List<String> =
        db.query("PRAGMA table_info($table)") { it.getString(it.getColumnIndexOrThrow("name")) }

    /**
     * 合并之后目标库里**还活着**的文档 id：现有的 ∪ 本批要插的 − 本批要删的。
     *
     * 拿它过滤 note/ink_layer/scratch_pad 的 upsert。不过滤的话会撞外键：「源盘上把这本书删了、
     * 同时我在镜像上给它写了新笔迹」——那条笔迹推到源盘时父文档已经没了，**整个事务回滚、
     * 整次同步失败**。宁可丢掉那一行并报出来，也不要让用户面对一个「点了没反应、也不知道
     * 为什么」的同步。
     */
    fun livingDocuments(db: Db, changes: List<MirrorDiff.Change>): Set<String> {
        val live = db.query("SELECT id FROM document") { it.getString(0) }.toMutableSet()
        for (c in changes.filter { it.table == "document" }) {
            if (c.op == MirrorDiff.Op.UPSERT) live.add(c.rowId) else live.remove(c.rowId)
        }
        return live
    }

    /** 把一批改动写进一个库（**调用方负责包事务**） */
    fun write(db: Db, changes: List<MirrorDiff.Change>, result: Result, side: MirrorDiff.Side) {
        val live = livingDocuments(db, changes)
        val order = MirrorFp.specs.map { it.table }

        // ① 先删，后插。反过来会撞 `variant.content_hash` 的 UNIQUE：
        //    「删掉旧版本、换一份同内容的进来」在同一批里就是先插会重、先删才对。
        for (table in order.reversed()) {
            val spec = MirrorFp.spec(table) ?: continue
            for (c in changes.filter { it.table == table && it.op == MirrorDiff.Op.DELETE }) {
                db.exec("DELETE FROM $table WHERE ${spec.key}=?", arrayOf<Any?>(c.rowId))
                if (side == MirrorDiff.Side.SOURCE) result.sourceDeletes++ else result.mirrorDeletes++
            }
        }
        // ② 插：按表依赖序（document 在前，其余都指向它）
        for (table in order) {
            val spec = MirrorFp.spec(table) ?: continue
            val cols = columns(db, table)
            for (c in changes.filter { it.table == table && it.op == MirrorDiff.Op.UPSERT }) {
                val row = c.row ?: continue
                if (table != "document" && table != "meta") {
                    val doc = row["document_id"] as? String
                    if (doc != null && doc !in live) {
                        result.orphansSkipped++
                        continue
                    }
                }
                // `variant.content_hash` 是 UNIQUE：同一份 PDF 在两份镜像上各自入过库时，
                // 两边的 variant **id 不同、hash 相同**，硬插就是 UNIQUE 失败 → 整个事务回滚 →
                // **整次同步失败**，抛给用户的还是一句看不懂的 SQLite 报错
                // （2026-08-31 多镜像用例实测到）。跳过并计数：那一行本来就是冗余的。
                // ⚠️ 它**不会自动收敛**——下次干跑还会算成待写。这是刻意的：「这两本是不是同一本书」
                // 是用户的语义判断，同步这一步不该替他决定。
                if (table == "variant") {
                    val hash = row["content_hash"] as? String
                    if (hash != null) {
                        val clash = db.query(
                            "SELECT id FROM variant WHERE content_hash=? AND id<>?",
                            arrayOf(hash, c.rowId),
                        ) { it.getString(0) }
                        if (clash.isNotEmpty()) {
                            result.hashClashesSkipped++
                            continue
                        }
                    }
                }
                val use = cols.filter { row.containsKey(it) }
                if (use.isEmpty()) continue
                val ph = use.joinToString(",") { "?" }
                val sets = use.filter { it != spec.key }.joinToString(",") { "$it=excluded.$it" }
                val tail = if (sets.isEmpty()) "${spec.key}=excluded.${spec.key}" else sets
                db.exec(
                    "INSERT INTO $table(${use.joinToString(",")}) VALUES($ph) " +
                        "ON CONFLICT(${spec.key}) DO UPDATE SET $tail",
                    use.map { row[it] }.toTypedArray(),
                )
                if (side == MirrorDiff.Side.SOURCE) result.sourceUpserts++ else result.mirrorUpserts++
            }
        }
    }

    /** `document.last_opened_at`：不进指纹，两端一律取较晚的那个（方案 §4） */
    fun writeLastOpened(db: Db, merges: Map<String, String>): Int {
        for ((id, iso) in merges) {
            db.exec(
                "UPDATE document SET last_opened_at=? WHERE id=? AND last_opened_at<?",
                arrayOf<Any?>(iso, id, iso),
            )
        }
        return merges.size
    }

    // ---------- 文件补齐 ----------

    /**
     * 镜像上新加的书要把 PDF 拷回源盘（方案 §7 的那一条幂等规则）。
     *
     * **不走 diff**：`location` 是设备本地事实、不参与同步，所以这一步是「合并完之后扫一遍、
     * 缺什么补什么」。幂等、可重入 —— 拷一半断电再来一次结果一样。
     */
    fun fillFilesToSource(
        mirrorFolder: File,
        mirrorStore: LibraryStore,
        sourceFolder: File,
        sourceStore: LibraryStore,
    ): Int {
        fun resolve(ws: File, loc: LibLocation): File? = Workspace.resolvePdf(ws, loc)
        var copied = 0
        for (doc in sourceStore.allDocuments()) {
            for (v in sourceStore.variants(doc.id)) {
                val srcLocs = sourceStore.locationsOfVariant(v.id)
                if (srcLocs.any { resolve(sourceFolder, it)?.isFile == true }) continue
                val hit = mirrorStore.locationsOfVariant(v.id)
                    .firstOrNull { it.inWorkspace && resolve(mirrorFolder, it)?.isFile == true }
                val from = hit?.let { resolve(mirrorFolder, it) } ?: continue
                val rel = "${Workspace.PDFS_DIR}/${UUID.randomUUID()}.pdf"
                val to = File(sourceFolder, rel).apply { parentFile?.mkdirs() }
                val tmp = File(to.parentFile, ".${to.name}.part")
                tmp.delete()
                from.copyTo(tmp, overwrite = true)
                to.delete()
                if (!tmp.renameTo(to)) { tmp.delete(); continue }
                sourceStore.addLocation(v.id, rel, inWorkspace = true)
                copied++
            }
        }
        return copied
    }

    // ---------- 主流程 ----------

    /**
     * 应用一次合并。**在 StoreQueue 的独占线程上调用。**
     *
     * [plan] 就是**干跑给用户看的那一份**，不在这里重算 —— 重算就意味着「用户看到的」和
     * 「实际做的」可能不是同一件事，而这一步会大批量改用户数据。
     */
    fun apply(
        plan: MirrorDiff.Plan,
        mirrorFolder: File,
        mirrorStore: LibraryStore,
        sourceFolder: File,
        sourceStore: LibraryStore,
        progress: ((String, Float) -> Unit)? = null,
    ): Result {
        val r = Result()

        // ① 备份。失败即中止 —— 没有备份就动手是把"最坏情况"从回滚变成没得救。
        progress?.invoke("正在备份硬盘上的资料库…", 0.05f)
        r.backup = backupSource(sourceStore, sourceFolder)

        // ② 源盘侧（先做：它在可移动介质上，中途被拔的概率更高，失败也只是回滚到原样）
        progress?.invoke("正在写入硬盘…", 0.25f)
        sourceStore.withMirrorDb { db ->
            db.transaction {
                write(db, plan.changesTo(MirrorDiff.Side.SOURCE), r, MirrorDiff.Side.SOURCE)
                r.lastOpenedTouched += writeLastOpened(db, plan.lastOpenedMerges)
            }
        }

        // ③ 镜像侧
        progress?.invoke("正在写入本机…", 0.55f)
        mirrorStore.withMirrorDb { db ->
            db.transaction {
                write(db, plan.changesTo(MirrorDiff.Side.MIRROR), r, MirrorDiff.Side.MIRROR)
                writeLastOpened(db, plan.lastOpenedMerges)
            }
        }

        // ④ 文件补齐（事务外，幂等可重入）
        progress?.invoke("正在补齐文件…", 0.75f)
        r.filesCopiedToSource = fillFilesToSource(mirrorFolder, mirrorStore, sourceFolder, sourceStore)

        // ⑤ 两侧都成功了才重算基线 —— 这一步之前任何失败都靠"下次再跑一遍"自愈（见类型注释）
        progress?.invoke("正在重置基线…", 0.9f)
        mirrorStore.rebuildSyncBase()

        // ⑥ 记账
        val now = Iso.now()
        mirrorStore.setMeta(MirrorStore.META_MIRROR_LAST_SYNCED_AT, now)
        val mirrorId = mirrorStore.meta(MirrorStore.META_MIRROR_ID)
        if (mirrorId != null) {
            val list = MirrorStore.decodeCheckouts(sourceStore.meta(MirrorStore.META_CHECKOUTS))
            list.firstOrNull { it.mirrorId == mirrorId }?.let { c ->
                val updated = c.copy(lastSyncedAt = now, noteCount = sourceStore.noteCount())
                sourceStore.setMeta(
                    MirrorStore.META_CHECKOUTS,
                    MirrorStore.encodeCheckouts(MirrorStore.upsertCheckout(list, updated)),
                )
            }
        }
        progress?.invoke("完成", 1f)
        Log.i(
            TAG,
            "合并完成：硬盘 +${r.sourceUpserts}/-${r.sourceDeletes}，" +
                "本机 +${r.mirrorUpserts}/-${r.mirrorDeletes}，" +
                "孤儿 ${r.orphansSkipped}，重复内容 ${r.hashClashesSkipped}，补齐文件 ${r.filesCopiedToSource}",
        )
        return r
    }
}
