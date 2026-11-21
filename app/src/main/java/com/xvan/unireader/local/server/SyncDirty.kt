package com.xvan.unireader.local.server

import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.MirrorFp

/**
 * 开着服务器工作区时记下「哪些行改过」，推送前只查这几行（[SyncClient.localChanges] 带 dirty 的那份），不再整库扫描。
 *
 * 为什么要有它（2026-11-21 用户实测）：平板每次找改动都把参与同步的每张表整表读出来逐行算指纹，
 * 一个 85MB 的工作区在 `/sdcard` 上要 2.7 秒，写完到电脑显示要七秒；这期间库队列被占着，落笔也得排在后面。
 *
 * 做法：在**主连接**上挂 TEMP 触发器，参与同步的表（[MirrorFp.specs] + OCR / 图片 / 扫描页对齐）每写一行，
 * 就往 `temp.sync_dirty` 记一笔 `(表, row_id, 写入时刻)`。TEMP 对象只活在这条连接上、不进库文件，跨端 schema 契约不受影响。
 * `sync_base` 也挂上（写入时刻记 0，不算「用户在写」）：拉取改基线、补拉清基线这类「行没动、基线动了」的情况，
 * 整库扫描能发现，只查改过的行也得发现。
 *
 * 🔴 只看得见经这条连接写的行。安卓的 `SQLiteDatabase` 开着 WAL 时写入都走主连接，所以本进程经同一个库对象的写都记得到；
 * 另开一个库对象写同一个文件的记不到（原先用 `total_changes()` 判断也一样记不到）。兜底：每次打开工作区先整库扫一遍，
 * 关掉工作区时完整同步一轮。
 *
 * 🔴 TEMP 表只在主连接上有：读它必须在事务里（事务把这条线程钉在主连接上，见 [Db.totalChanges]），
 * 事务外的 SELECT 会落到连接池的只读连接上、报「没有这张表」。下面每个函数都自己开事务。都在库队列线程上调。
 */
object SyncDirty {

    private const val TABLE = "sync_dirty"

    /** 触发器里取「此刻」：Unix 毫秒（与 `System.currentTimeMillis()` 同一个钟） */
    private const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000.0 AS INTEGER)"

    /** 这一刻记下的改动：[count] 行，最近一次用户写入在 [lastAt]（毫秒；0 = 只有基线变了，不算用户在写） */
    class Peek(val count: Int, val lastAt: Long)

    /** 挂上（已挂着就不重复挂）。返回是否挂好了 */
    fun install(db: Db): Boolean = db.transaction {
        db.exec(
            "CREATE TEMP TABLE IF NOT EXISTS $TABLE(" +
                "tbl TEXT NOT NULL, row_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(tbl, row_id))",
        )
        // 🔴 触发器体里不许出现会冲突的写：外层语句带了冲突策略（`INSERT OR ABORT` 这类）时，触发器体里自己写的
        // `OR REPLACE` / `OR IGNORE` 会被外层的顶掉，一冲突就连用户那次写入一起失败。所以先删再插、或「不在才插」。
        for ((table, id) in watched(db)) {
            fun mark(r: String) = "DELETE FROM $TABLE WHERE tbl='$table' AND row_id=${id(r)}; " +
                "INSERT INTO $TABLE(tbl,row_id,at) VALUES('$table', ${id(r)}, $NOW_MS);"
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_${table}_i AFTER INSERT ON main.$table BEGIN ${mark("NEW")} END")
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_${table}_u AFTER UPDATE ON main.$table BEGIN ${mark("OLD")} ${mark("NEW")} END")
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_${table}_d AFTER DELETE ON main.$table BEGIN ${mark("OLD")} END")
        }
        if (MirrorStore.hasSyncBase(db)) {
            // 基线变了：只要求重查这一行，不顶掉「用户写过」的时刻（不在才插），时刻记 0
            fun mark(r: String) = "INSERT INTO $TABLE(tbl,row_id,at) SELECT $r.tbl, $r.row_id, 0 " +
                "WHERE NOT EXISTS (SELECT 1 FROM $TABLE WHERE tbl=$r.tbl AND row_id=$r.row_id);"
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_base_i AFTER INSERT ON main.sync_base BEGIN ${mark("NEW")} END")
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_base_u AFTER UPDATE ON main.sync_base BEGIN ${mark("OLD")} ${mark("NEW")} END")
            db.exec("CREATE TEMP TRIGGER IF NOT EXISTS sd_base_d AFTER DELETE ON main.sync_base BEGIN ${mark("OLD")} END")
        }
        installed(db)
    }

    /** 拆掉（会话停了、库还开着时，比如变回本地工作区：别让触发器一直记下去） */
    fun uninstall(db: Db) {
        db.transaction {
            val names = db.query("SELECT name FROM sqlite_temp_master WHERE type='trigger' AND name LIKE 'sd\\_%' ESCAPE '\\'") {
                it.getString(0)
            }
            for (n in names) db.exec("DROP TRIGGER IF EXISTS temp.\"$n\"")
            db.exec("DROP TABLE IF EXISTS temp.$TABLE")
        }
    }

    /** 看一眼有没有改动（不取走）。null = 没挂着（从没挂上，或连接换过）——调用方退回整库扫描 */
    fun peek(db: Db): Peek? = db.transaction {
        if (!installed(db)) return@transaction null
        db.query("SELECT count(*), coalesce(max(at), 0) FROM $TABLE") { Peek(it.getInt(0), it.getLong(1)) }.first()
    }

    /** 取走全部记下的行（`表名 → row_id`）并清空。null = 没挂着 */
    fun take(db: Db): Map<String, Set<String>>? = db.transaction {
        if (!installed(db)) return@transaction null
        val out = HashMap<String, HashSet<String>>()
        db.query("SELECT tbl, row_id FROM $TABLE") { it.getString(0) to it.getString(1) }
            .forEach { (t, id) -> out.getOrPut(t) { HashSet() }.add(id) }
        db.exec("DELETE FROM $TABLE")
        out
    }

    /**
     * 从 [sinceMs] 起记下的行改成「不算用户在写」（时刻记 0，行照样留着复查）：拉取 / 补拉写库之后调。
     * 库队列是单线程，这一段里不会有用户的写插进来，所以这段时间记下的都是同步自己写的。没挂着 = 什么都不做
     */
    fun settle(db: Db, sinceMs: Long) {
        db.transaction { if (installed(db)) db.exec("UPDATE $TABLE SET at=0 WHERE at >= ?", arrayOf<Any?>(sinceMs - 1)) }
    }

    /** 清空（整库扫描之前：扫描会覆盖到它们） */
    fun clear(db: Db) {
        db.transaction { if (installed(db)) db.exec("DELETE FROM $TABLE") }
    }

    /** 推送没成：把取走的行放回去，下次再查（时刻记 0，不推迟下次推送）。返回是否放回去了（没挂着 = false） */
    fun putBack(db: Db, rows: Map<String, Set<String>>): Boolean = db.transaction {
        if (!installed(db)) return@transaction false
        for ((t, ids) in rows) for (id in ids) {
            db.exec("INSERT OR IGNORE INTO $TABLE(tbl,row_id,at) VALUES(?,?,0)", arrayOf<Any?>(t, id))
        }
        true
    }

    private fun installed(db: Db): Boolean =
        db.query("SELECT count(*) FROM sqlite_temp_master WHERE type='table' AND name='$TABLE'") { it.getInt(0) }.first() > 0

    /**
     * 要挂触发器的表 → 该行 row_id 的 SQL 写法（给 `NEW` / `OLD` 用）。row_id 与整库扫描里的口径逐字相同：
     * 三方判定表 = 主键列转成文字；OCR = `content_hash␟page␟provider`（[SyncRows.ocrRowId]）；图片 = sha256；对齐 = content_hash。
     * 库里没有的表（老库少画板表之类）跳过。
     */
    private fun watched(db: Db): List<Pair<String, (String) -> String>> {
        val out = ArrayList<Pair<String, (String) -> String>>()
        for (spec in MirrorFp.specs) {
            if (!MirrorStore.hasTable(db, spec.table)) continue
            val key = spec.key
            out.add(spec.table to { r: String -> "CAST($r.\"$key\" AS TEXT)" })
        }
        if (MirrorStore.hasTable(db, SyncRows.OCR)) {
            out.add(SyncRows.OCR to { r: String ->
                "$r.content_hash || char(31) || CAST($r.page AS INTEGER) || char(31) || $r.provider"
            })
        }
        if (MirrorStore.hasTable(db, SyncRows.IMAGE)) out.add(SyncRows.IMAGE to { r: String -> "$r.sha256" })
        if (MirrorStore.hasTable(db, SyncRows.ALIGN)) out.add(SyncRows.ALIGN to { r: String -> "$r.content_hash" })
        return out
    }
}
