package com.xvan.unireader.local.store

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * 回收站的 SQLite 一侧（`../BACKUP-PLAN.md §2`，Mac `Sources/Store/TrashStore.swift` 的同款产物）：
 * 把「要删掉的那些行」原样搬进一个独立的小库（`snapshot.sqlite`），以及把它们搬回来。
 *
 * 🔴 **schema 一个字不改**：删除对三端仍是硬删；回收站纯粹是删之前多存一份快照文件。
 * 快照里的表名 / 列名与主库**逐字相同**（Mac 那边 `CREATE TABLE … AS SELECT *` 出来的也是这样），
 * 所以安卓建的条目 Mac 能恢复、Mac 建的安卓也能恢复。
 *
 * 与 Mac 的唯一做法差异：Mac 用 `ATTACH`；本端主库开着 WAL（连接池里不止一条连接，ATTACH 只挂在其中一条上），
 * 所以快照**单独开一个库**、逐行按存储类复制（[MirrorStore] 读另一个库也是这么做的）。
 * 入口只有 `LibraryStore` 上那几个 `trash*` 方法（同 `MirrorStore` 的定位：DAO 约定在这里开一条窄口子）。
 */
object TrashStore {

    /** 文档级快照的表，**顺序就是恢复时的写入顺序**（父表在前，同 Mac `documentTables`） */
    val documentTables = listOf("document", "variant", "location", "ink_layer", "scratch_pad", "note", "exercise", "exercise_attempt")
    /** 画板条目的表（同 Mac `boardTables`） */
    val boardTables = listOf("board_note", "board_page", "board_item")

    /** 写进 manifest 的统计（Mac `TrashStore.Counts`，键名一致） */
    class Counts {
        var ink = 0
        var text = 0
        var highlight = 0
        var bookmark = 0
        var image = 0
        var aiThread = 0
        var scratchInk = 0
        var inkLayer = 0
        var scratchPad = 0
        var boardInk = 0
        var boardImage = 0
        var exercise = 0
        val total: Int get() = ink + text + highlight + bookmark + image + aiThread + scratchInk + boardInk + boardImage + exercise

        fun toJson(): JSONObject = JSONObject()
            .put("ink", ink).put("text", text).put("highlight", highlight).put("bookmark", bookmark)
            .put("image", image).put("aiThread", aiThread).put("scratchInk", scratchInk)
            .put("inkLayer", inkLayer).put("scratchPad", scratchPad)
            .put("boardInk", boardInk).put("boardImage", boardImage).put("exercise", exercise)

        companion object {
            /** 老条目少键：缺了就是 0（同 Mac 的 decodeIfPresent） */
            fun of(o: JSONObject?): Counts = Counts().apply {
                if (o == null) return@apply
                ink = o.optInt("ink"); text = o.optInt("text"); highlight = o.optInt("highlight")
                bookmark = o.optInt("bookmark"); image = o.optInt("image"); aiThread = o.optInt("aiThread")
                scratchInk = o.optInt("scratchInk"); inkLayer = o.optInt("inkLayer"); scratchPad = o.optInt("scratchPad")
                boardInk = o.optInt("boardInk"); boardImage = o.optInt("boardImage"); exercise = o.optInt("exercise")
            }
        }
    }

    /** 归档的产物：写进 manifest 的那几样 */
    class Archived(val counts: Counts, val images: List<String>, val contentHashes: List<String>, val pageCount: Int)

    // ---------- 归档 ----------

    /** 一张表要搬的行：WHERE 子句 + 参数（null = 这张表主库里没有，跳过） */
    private class Pick(val table: String, val where: String, val args: Array<out Any?>)

    fun archiveDocument(db: Db, documentId: String, path: File): Archived = archive(
        db, path,
        listOf(
            Pick("document", "id=?", arrayOf(documentId)),
            Pick("variant", "document_id=?", arrayOf(documentId)),
            Pick("location", "variant_id IN (SELECT id FROM variant WHERE document_id=?)", arrayOf(documentId)),
            Pick("ink_layer", "document_id=?", arrayOf(documentId)),
            Pick("scratch_pad", "document_id=?", arrayOf(documentId)),
            Pick("note", "document_id=?", arrayOf(documentId)),
            Pick("exercise", "document_id=?", arrayOf(documentId)),
            Pick("exercise_attempt", "exercise_id IN (SELECT id FROM exercise WHERE document_id=?)", arrayOf(documentId)),
        ),
    )

    fun archiveBoard(db: Db, boardId: String, path: File): Archived = archive(
        db, path,
        listOf(
            Pick("board_note", "id=?", arrayOf(boardId)),
            Pick("board_page", "board_id=?", arrayOf(boardId)),
            Pick("board_item", "board_id=?", arrayOf(boardId)),
        ),
    )

    fun archiveExercises(db: Db, ids: List<String>, path: File): Archived {
        val marks = ids.joinToString(",") { "?" }
        return archive(
            db, path,
            listOf(
                Pick("exercise", "id IN ($marks)", ids.toTypedArray()),
                Pick("exercise_attempt", "exercise_id IN ($marks)", ids.toTypedArray()),
            ),
        )
    }

    /**
     * 把选中的行搬进 [path] 处的**新**快照库（文件必须尚不存在：回收站里已有的快照一份都不许动）。
     * 失败时只清自己刚建的那个半截文件，然后抛出——调用方据此**放弃删除**（红线 11：先归档再删）。
     */
    private fun archive(db: Db, path: File, picks: List<Pick>): Archived {
        require(!path.exists()) { "快照已存在：${path.path}" }
        path.parentFile?.mkdirs()
        val snap = SQLiteDatabase.openOrCreateDatabase(path, null)
        // 安卓默认的日志模式关库后留着 `-journal` 文件：条目目录里只该有快照与 manifest（Mac 那边也是这两样）
        snap.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
        try {
            snap.beginTransaction()
            try {
                for (p in picks) {
                    val cols = columns(db, p.table)
                    if (cols.isEmpty()) continue   // 老库没有这张表（v20 前没有习题）：快照里也不建
                    snap.execSQL("CREATE TABLE \"${p.table}\" (${cols.joinToString(",") { "\"${it.first}\" ${it.second}" }})")
                    db.query("SELECT * FROM \"${p.table}\" WHERE ${p.where}", p.args) { c -> values(c) }
                        .forEach { snap.insertOrThrow("\"${p.table}\"", null, it) }
                }
                snap.setTransactionSuccessful()
            } finally {
                snap.endTransaction()
            }
            return summarize(snap)
        } catch (e: Exception) {
            runCatching { snap.close() }
            path.delete()
            File(path.path + "-journal").delete()
            throw e
        } finally {
            if (snap.isOpen) snap.close()
        }
    }

    /** 主库一张表的列（名 + 声明类型）；表不存在 = 空 */
    private fun columns(db: Db, table: String): List<Pair<String, String>> =
        db.query("PRAGMA table_info(\"$table\")") { c -> c.getString(c.getColumnIndexOrThrow("name")) to c.getString(c.getColumnIndexOrThrow("type")) }

    /** 一行按**存储类**原样取（不按声明类型猜），写进快照不改一个字节 */
    private fun values(c: Cursor): ContentValues {
        val v = ContentValues(c.columnCount)
        for (i in 0 until c.columnCount) {
            val k = c.getColumnName(i)
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> v.putNull(k)
                Cursor.FIELD_TYPE_INTEGER -> v.put(k, c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> v.put(k, c.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> v.put(k, c.getBlob(i))
                else -> v.put(k, c.getString(i))
            }
        }
        return v
    }

    // ---------- 恢复 ----------

    /**
     * 恢复前的探路（Mac `mergeTarget`，方案 §2.5 情形 B）：这份 PDF 是不是已经被重新导入过——
     * 返回主库里占着同一个 `content_hash` 的那篇文档 id（并入目标）；没有 / 快照里没有文档 = null。
     */
    fun mergeTarget(db: Db, path: File): String? = withSnapshot(path) { snap ->
        if (!hasTable(snap, "variant") || !hasTable(snap, "document")) return@withSnapshot null
        val own = snap.rawQuery("SELECT id FROM document", null).use { if (it.moveToFirst()) it.getString(0) else "" }
        val hashes = snap.rawQuery("SELECT content_hash FROM variant", null).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out.add(c.getString(0))
            out
        }
        for (h in hashes) {
            val hit = db.query("SELECT document_id FROM variant WHERE content_hash=?", arrayOf(h)) { it.getString(0) }.firstOrNull()
            if (hit != null && hit != own) return@withSnapshot hit
        }
        null
    }

    /**
     * 把快照写回主库（Mac `restore` 的同一套规则）：
     * - [remapDocumentId] 非 null = 并入现有那篇：所有行的 `document_id` 改写成它，`document` / `variant` / `location` 整张跳过；
     * - 有级联子表的（`document` / `variant` / `board_note` / `exercise`）用 **OR IGNORE**——REPLACE 实际是「先删再插」，
     *   会把刚放回去的子行连根删掉；其余用 **OR REPLACE**（没有子表，重复恢复幂等）。
     * 整批一个事务。返回搬了几行（只给提示用）。
     */
    fun restore(db: Db, path: File, remapDocumentId: String?): Int = withSnapshot(path) { snap ->
        var written = 0
        db.transaction {
            for (table in documentTables + boardTables) {
                if (!hasTable(snap, table)) continue
                if (remapDocumentId != null && table in setOf("document", "variant", "location")) continue
                val conflict = if (table in setOf("document", "variant", "board_note", "exercise")) "OR IGNORE" else "OR REPLACE"
                snap.rawQuery("SELECT * FROM \"$table\"", null).use { c ->
                    val cols = (0 until c.columnCount).map { c.getColumnName(it) }
                    val sql = "INSERT $conflict INTO \"$table\"(${cols.joinToString(",") { "\"$it\"" }}) " +
                        "VALUES(${cols.joinToString(",") { "?" }})"
                    while (c.moveToNext()) {
                        val args = arrayOfNulls<Any?>(cols.size)
                        for (i in cols.indices) {
                            args[i] = if (remapDocumentId != null && cols[i] == "document_id") remapDocumentId
                            else when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                else -> c.getString(i)
                            }
                        }
                        db.exec(sql, args)
                        written++
                    }
                }
            }
        }
        written
    }

    /** 快照里的统计与引用（manifest 丢了也能重建摘要） */
    fun summary(path: File): Archived = withSnapshot(path) { summarize(it) }

    private fun <T> withSnapshot(path: File, body: (SQLiteDatabase) -> T): T {
        val snap = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return body(snap)
        } finally {
            snap.close()
        }
    }

    private fun hasTable(snap: SQLiteDatabase, name: String): Boolean =
        snap.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }

    /** 数快照里有什么（同 Mac `summarize`；图片 sha 在本端解 payload，不用 json_extract） */
    private fun summarize(snap: SQLiteDatabase): Archived {
        val counts = Counts()
        val images = HashSet<String>()
        fun sha(b: ByteArray) {
            runCatching { JSONObject(String(b, StandardCharsets.UTF_8)).optString("image") }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { images.add(it) }
        }
        if (hasTable(snap, "note")) {
            snap.rawQuery("SELECT kind, payload FROM note", null).use { c ->
                while (c.moveToNext()) {
                    when (c.getInt(0)) {
                        0 -> counts.text++
                        1 -> counts.aiThread++
                        2 -> counts.ink++
                        3 -> counts.highlight++
                        4 -> counts.scratchInk++
                        5 -> counts.bookmark++
                        6 -> { counts.image++; sha(c.getBlob(1)) }
                    }
                }
            }
        }
        if (hasTable(snap, "board_item")) {
            snap.rawQuery("SELECT kind, payload FROM board_item", null).use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(0) == 1) counts.boardInk++ else if (c.getInt(0) == 2) { counts.boardImage++; sha(c.getBlob(1)) }
                }
            }
        }
        fun n(t: String) = if (!hasTable(snap, t)) 0 else snap.rawQuery("SELECT COUNT(*) FROM \"$t\"", null).use { it.moveToFirst(); it.getInt(0) }
        counts.inkLayer = n("ink_layer")
        counts.scratchPad = n("scratch_pad")
        counts.exercise = n("exercise")
        val hashes = if (!hasTable(snap, "variant")) emptyList() else
            snap.rawQuery("SELECT content_hash FROM variant", null).use { c ->
                val out = ArrayList<String>()
                while (c.moveToNext()) out.add(c.getString(0))
                out
            }
        val pages = if (!hasTable(snap, "document")) 0 else
            snap.rawQuery("SELECT page_count FROM document", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Archived(counts, images.sorted(), hashes.sorted(), pages)
    }
}
