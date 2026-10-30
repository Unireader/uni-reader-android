package com.xvan.unireader.local.server

import com.xvan.unireader.local.mirror.MirrorApply
import com.xvan.unireader.local.mirror.MirrorDiff
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.MirrorFp
import com.xvan.unireader.local.store.Schema
import java.io.File

/**
 * 客户端这一侧的同步算法（`../SERVER-MODE-PLAN.md §4.2`）：找本地改动、处理推送结果、应用拉下来的行。
 * 🔴 逐条照 Mac `../Sources/Sync/SyncClient.swift`（服务器的测试就是对着那份写的），改一边同步另一边。
 *
 * 只碰一个工作区库（[Db]）和它的文件夹；网络、文件上传下载、界面都不在这里。基线沿用离线镜像那张 `sync_base`
 * （客户端私有，不进跨端 schema），通道表也记在里面：OCR / 图片的基线指纹恒为空串（= 服务器已经有了），扫描页对齐 = `updated_at`。
 * 冲突规则不另写：应用拉下来的行时交给 [MirrorDiff.compute]（SOURCE = 服务器，MIRROR = 本机）。
 *
 * 🔴 基线**按行**更新：只有某一行本地与服务器一致了（或冲突规则判本机赢、等着推上去），才改这一行的基线。
 * 本机没写成的行（父行不在被跳过）基线不动——否则下一轮会把「本机没有」误判成「本机删了」，推一条删除上去。
 */
object SyncClient {

    /** 上次拉到的修订号（客户端私有的 meta 键，不在同步白名单里） */
    const val META_PULLED_REV = "srv_pulled_rev"

    /** 这份工作区连的是哪台服务器（基础地址）。**有它 = 服务器工作区**。客户端私有，跟着库文件走 */
    const val META_SERVER_URL = "srv_url"

    /** 服务器的稳定身份（`/v1/info` 的 `serverId`）：服务器换了地址之后缓存靠它认出是哪台。客户端私有 */
    const val META_SERVER_ID = "srv_id"

    data class Key(val table: String, val rowId: String)

    // ---------- 基线 ----------

    /** 把基线设成「此刻与服务器一致」：建缓存之后调一次 */
    fun resetBase(db: Db, folder: File, pulledRev: Long) = writeBase(db, computeBase(db, folder), pulledRev)

    /**
     * 算基线（只读）：三方判定表 + 通道表。上传时对着**上传出去的那份快照**算（而不是此刻的活库）：
     * 上传期间本机又改的，基线里不该有，下一轮才会推上去。
     */
    fun computeBase(db: Db, folder: File): Map<String, Map<String, String>> =
        SyncRows.specState(db) + SyncRows.channelState(db, folder)

    /** 写基线（整表替换 + 记下拉到的修订号），一个事务 */
    fun writeBase(db: Db, base: Map<String, Map<String, String>>, pulledRev: Long) {
        db.exec(MirrorStore.SYNC_BASE_DDL.trimIndent())
        db.transaction {
            db.exec("DELETE FROM sync_base")
            for ((table, fps) in base) {
                for ((rowId, fp) in fps) {
                    db.exec("INSERT INTO sync_base(tbl,row_id,fp) VALUES(?,?,?)", arrayOf<Any?>(table, rowId, fp))
                }
            }
            setPulledRev(db, pulledRev)
        }
    }

    fun pulledRev(db: Db): Long =
        db.query("SELECT value FROM meta WHERE key=?", arrayOf(META_PULLED_REV)) { it.getString(0) }
            .firstOrNull()?.toLongOrNull() ?: 0L

    fun setPulledRev(db: Db, v: Long) {
        db.exec(
            "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            arrayOf<Any?>(META_PULLED_REV, v.toString()),
        )
    }

    /**
     * 「变回本地工作区」：去掉服务器地址 / 身份 / 拉到的修订号，删掉基线表——之后它就是普通的本地工作区，
     * 不再推也不再拉。服务器上那份不动。一个事务。
     */
    fun detach(db: Db) {
        db.transaction {
            db.exec(
                "DELETE FROM meta WHERE key IN (?,?,?)",
                arrayOf<Any?>(META_SERVER_URL, META_SERVER_ID, META_PULLED_REV),
            )
            db.exec("DROP TABLE IF EXISTS sync_base")
        }
    }

    fun setBase(db: Db, k: Key, fp: String?) {
        if (fp != null) {
            db.exec(
                "INSERT INTO sync_base(tbl,row_id,fp) VALUES(?,?,?) ON CONFLICT(tbl,row_id) DO UPDATE SET fp=excluded.fp",
                arrayOf<Any?>(k.table, k.rowId, fp),
            )
        } else {
            db.exec("DELETE FROM sync_base WHERE tbl=? AND row_id=?", arrayOf<Any?>(k.table, k.rowId))
        }
    }

    // ---------- 本地改动 ----------

    /**
     * 当前 vs 基线：三方判定表的增删改，加上通道表里服务器还没有的（或对齐参数变了的）。
     * 通道表本地删掉的不推（删除不传播）。
     */
    fun localChanges(db: Db, folder: File): List<SyncWire.RowChange> {
        val base = MirrorStore.syncBase(db)
        val out = ArrayList<SyncWire.RowChange>()
        for (spec in MirrorFp.specs) {
            val cur = MirrorStore.fingerprints(db, spec)
            val b = base[spec.table].orEmpty()
            val changed = (cur.keys + b.keys).filter { cur[it] != b[it] }.sorted()
            if (changed.isEmpty()) continue
            val rows = SyncRows.fetch(db, spec, changed.filter { cur[it] != null })
            for (id in changed) {
                val row = rows[id]
                out.add(
                    if (row != null) {
                        SyncWire.RowChange(spec.table, id, SyncWire.Op.UPSERT, b[id], SyncWire.row(row))
                    } else {
                        SyncWire.RowChange(spec.table, id, SyncWire.Op.DELETE, b[id], null)
                    },
                )
            }
        }
        val channels = SyncRows.channelState(db, folder)
        for (table in listOf(SyncRows.OCR, SyncRows.IMAGE, SyncRows.ALIGN)) {
            for ((id, fp) in channels[table].orEmpty().toSortedMap()) {
                if (base[table]?.get(id) == fp) continue
                val row = SyncRows.fetch(db, table, id) ?: continue
                out.add(SyncWire.RowChange(table, id, SyncWire.Op.UPSERT, base[table]?.get(id), SyncWire.row(row)))
            }
        }
        return out
    }

    // ---------- 推送结果 ----------

    /**
     * 服务器收下的（或本来就一致的）行：基线改成**推上去的样子**（不是本地此刻的样子——推送期间本地可能又改了，
     * 那样下一轮会照常再推）。服务器保留自己那份的，按拉下来的行处理（`response.serverRows`）。
     */
    fun applyPushResponse(
        db: Db,
        folder: File,
        pushed: List<SyncWire.RowChange>,
        response: SyncWire.PushResponse,
    ): PullReport {
        db.transaction { recordPushed(db, pushed, response) }
        return applyPulled(db, folder, response.serverRows)
    }

    /**
     * 只记基线：服务器收下的（或本来就一致的）行，基线改成推上去的样子。**不开事务、不写用户数据**。
     * 工作区开着时（只推不拉）单独用它；服务器保留自己那份的行这时不碰——等下次完整同步。
     */
    fun recordPushed(db: Db, pushed: List<SyncWire.RowChange>, response: SyncWire.PushResponse) {
        val byKey = pushed.associateBy { Key(it.table, it.rowId) }
        for (r in response.results) {
            val k = Key(r.table, r.rowId)
            val c = byKey[k] ?: continue
            if (!SyncRows.isSynced(c.table)) continue
            when (r.outcome) {
                SyncWire.Outcome.APPLIED, SyncWire.Outcome.UNCHANGED -> {
                    val fp = if (c.op == SyncWire.Op.DELETE) null
                    else SyncRows.fingerprint(c.table, c.row?.let { SyncWire.sqliteRow(it) })
                    setBase(db, k, fp)
                }
                else -> Unit
            }
        }
    }

    // ---------- 拉下来的行 ----------

    data class PullReport(
        /** 本机有没推上去的改动、服务器又改了同一行：按冲突规则处理掉的条数 */
        val conflicts: Int = 0,
        /** 父行不在、没写成的行（基线没动，下一轮再说） */
        val skipped: Int = 0,
    )

    /**
     * 应用拉下来的行。🔴 一次拉取的**全部页攒齐了再调**：父行和子行可能分在不同页（父行后来又改过，
     * 修订号比子行大），分批写会把子行当孤儿丢掉。
     */
    fun applyPulled(db: Db, folder: File, rows: List<SyncWire.PulledRow>): PullReport {
        if (rows.isEmpty()) return PullReport()
        val latest = LinkedHashMap<Key, SyncWire.PulledRow>()
        for (r in rows) {
            val k = Key(r.table, r.rowId)
            val prev = latest[k]
            if (prev == null || prev.rev <= r.rev) latest[k] = r
        }
        val base = MirrorStore.syncBase(db)

        // 三方判定表：base = 本机基线，mine = 本机现状，theirs = 服务器
        val b = HashMap<String, HashMap<String, String>>()
        val mine = HashMap<String, Map<String, Map<String, Any?>>>()
        val theirs = HashMap<String, HashMap<String, Map<String, Any?>>>()
        val idsByTable = LinkedHashMap<String, MutableList<String>>()
        for ((k, r) in latest) {
            if (MirrorFp.spec(k.table) == null) continue
            if (k.table == "meta" && k.rowId !in MirrorFp.syncedMetaKeys) continue
            idsByTable.getOrPut(k.table) { ArrayList() }.add(k.rowId)
            base[k.table]?.get(k.rowId)?.let { b.getOrPut(k.table) { HashMap() }[k.rowId] = it }
            if (!r.deleted && r.row != null) theirs.getOrPut(k.table) { HashMap() }[k.rowId] = SyncWire.sqliteRow(r.row)
        }
        // 较新表（v15 md_doc / v16~17 画板 / v20 习题）在只读打开过的老库里可能还没建：先照 Mac 迁移一遍再读
        if (idsByTable.keys.any { it == "md_doc" || it.startsWith("board_") || it.startsWith("exercise") }) Schema.migrate(db)
        for ((table, ids) in idsByTable) mine[table] = SyncRows.fetch(db, MirrorFp.spec(table)!!, ids)
        val plan = MirrorDiff.compute(b, mine, theirs)
        val localWins = plan.changesTo(MirrorDiff.Side.SOURCE).map { Key(it.table, it.rowId) }.toSet()

        var skipped = 0
        db.transaction {
            val applied = MirrorApply.Result()
            MirrorApply.write(db, plan.changesTo(MirrorDiff.Side.MIRROR), applied, MirrorDiff.Side.MIRROR)
            MirrorApply.writeLastOpened(db, plan.lastOpenedMerges)
            for ((table, ids) in idsByTable) {
                for (id in ids) {
                    val k = Key(table, id)
                    val r = latest.getValue(k)
                    val serverFp = if (r.deleted) null else SyncRows.fingerprint(table, r.row?.let { SyncWire.sqliteRow(it) })
                    val localFp = SyncRows.fingerprint(table, SyncRows.fetch(db, table, id))
                    if (localFp == serverFp || k in localWins) setBase(db, k, serverFp) else skipped++
                }
            }
            // 通道表
            for ((k, r) in latest) {
                if (k.table !in SyncRows.channelTables) continue
                if (r.deleted || r.row == null) continue   // 通道表的删除不传播
                val row = SyncWire.sqliteRow(r.row)
                if (k.table == SyncRows.ALIGN) {
                    val server = row["updated_at"] as? String ?: ""
                    val local = SyncRows.fetch(db, k.table, k.rowId)?.get("updated_at") as? String
                    if (local == null || server > local) {
                        SyncRows.writeChannelRow(db, k.table, row, SyncRows.WriteMode.REPLACE)
                    }
                    setBase(db, k, server)   // 本机更新的话基线 ≠ 本机，下一轮推上去
                } else {
                    SyncRows.writeChannelRow(db, k.table, row, SyncRows.WriteMode.IGNORE_IF_PRESENT)
                    setBase(db, k, "")
                }
            }
        }
        return PullReport(plan.conflicts.size, skipped)
    }

    // ---------- 一轮 ----------

    data class RoundReport(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val push: SyncWire.PushResponse? = null,
        val pull: PullReport = PullReport(),
    )

    /**
     * 一轮同步：推本地改动 → 拉服务器的改动（全部页攒齐）→ 应用 → 记下拉到的修订号。传输由调用方给（阻塞调用）。
     * [checkCancelled] 在应用拉下来的行之前问一次：被取消就停在那里——推上去而基线没记的，下一轮再推一次、
     * 服务器答「一致」即补上；拉下来还没应用的，修订号没记，下一轮重拉。
     */
    fun round(
        db: Db,
        folder: File,
        schemaVersion: Int = Schema.VERSION,
        beforePush: (List<SyncWire.RowChange>) -> Unit = {},
        push: (SyncWire.PushRequest) -> SyncWire.PushResponse,
        pull: (Long) -> SyncWire.PullResponse,
        checkCancelled: () -> Unit = {},
    ): RoundReport {
        db.exec(MirrorStore.SYNC_BASE_DDL.trimIndent())
        val changes = localChanges(db, folder)
        var pushResp: SyncWire.PushResponse? = null
        if (changes.isNotEmpty()) {
            beforePush(changes)
            val resp = push(SyncWire.PushRequest(schemaVersion, changes))
            applyPushResponse(db, folder, changes, resp)
            pushResp = resp
        }
        var since = pulledRev(db)
        val acc = ArrayList<SyncWire.PulledRow>()
        while (true) {
            val page = pull(since)
            acc += page.rows
            if (page.more && page.rows.isNotEmpty()) {
                since = page.rows.last().rev
            } else {
                since = maxOf(since, page.head)
                break
            }
        }
        checkCancelled()
        val rep = applyPulled(db, folder, acc)
        setPulledRev(db, since)
        return RoundReport(changes.size, acc.size, pushResp, rep)
    }
}
