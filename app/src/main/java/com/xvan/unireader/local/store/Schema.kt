package com.xvan.unireader.local.store

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * **建库**——安卓端在本机新建一个工作区时用（`Launcher` 的「新建工作区」）。
 *
 * 这是本模块**唯一**写 DDL 的地方，且只在「文件还不存在」时跑一次；[Db] 那边照旧一个字的
 * DDL 都不写（打开已存在的库不建表、不迁移，见 `Db.open`）。理由没变：往一个 Mac 建的共享库
 * 上偷偷补表/补列，会让两端对 schema 的认知悄悄分叉；而**从零建一个新库**不存在这个问题——
 * 建出来的就得是 Mac 认识的那一份。
 *
 * 所以下面这段 DDL 是从 Mac `Sources/Store/LibraryStore.swift` 的 `migrate()` **逐字抄来**的
 * （schema v12），只做了一处形式上的改动：`SQLiteDatabase.execSQL` 一次只吃一条语句，
 * 故拆成了列表。**改表结构永远先改 Mac + `REQUIREMENTS.md §8`，再同步这里。**
 *
 * 新建的库直接就是 v12（不走任何迁移分支），因此没有「补列」那一节——那是给老库用的。
 */
object Schema {

    const val TAG = "UniReader/Schema"

    /** 建库时写进 `meta.schema_version` 的版本，与 Mac `LibraryStore.schemaVersion` 同步 */
    const val VERSION = 12

    /** 与 Mac `migrate()` 里那段 `CREATE TABLE IF NOT EXISTS …` 逐字一致，只是拆成了单条语句 */
    private val DDL = listOf(
        "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)",
        """
        CREATE TABLE IF NOT EXISTS document (
          id TEXT PRIMARY KEY, title TEXT NOT NULL, page_count INTEGER NOT NULL,
          added_at TEXT NOT NULL, last_opened_at TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
          read_page INTEGER NOT NULL DEFAULT 0, read_frac REAL NOT NULL DEFAULT 0,
          read_zoom REAL NOT NULL DEFAULT 1, read_hfrac REAL NOT NULL DEFAULT 0,
          group_name TEXT NOT NULL DEFAULT '', canvas_mode INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS variant (
          id TEXT PRIMARY KEY,
          document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
          content_hash TEXT NOT NULL UNIQUE, page_count INTEGER NOT NULL, added_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_variant_document ON variant(document_id)",
        """
        CREATE TABLE IF NOT EXISTS location (
          id TEXT PRIMARY KEY,
          variant_id TEXT NOT NULL REFERENCES variant(id) ON DELETE CASCADE,
          path TEXT NOT NULL, is_valid INTEGER NOT NULL DEFAULT 1, last_validated_at TEXT,
          in_workspace INTEGER NOT NULL DEFAULT 0, is_relative INTEGER NOT NULL DEFAULT 0
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_location_variant ON location(variant_id)",
        """
        CREATE TABLE IF NOT EXISTS note (
          id TEXT PRIMARY KEY,
          document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
          kind INTEGER NOT NULL, page INTEGER NOT NULL,
          anchor_x REAL NOT NULL, anchor_y REAL NOT NULL, anchor_w REAL NOT NULL, anchor_h REAL NOT NULL,
          payload BLOB NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_note_document_page ON note(document_id, page)",
        """
        CREATE TABLE IF NOT EXISTS ocr_page (
          content_hash TEXT NOT NULL, page INTEGER NOT NULL, provider TEXT NOT NULL,
          payload BLOB NOT NULL, lang TEXT, created_at TEXT NOT NULL,
          PRIMARY KEY (content_hash, page, provider)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS ink_layer (
          id TEXT PRIMARY KEY,
          document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
          name TEXT NOT NULL, color_key TEXT NOT NULL DEFAULT '',
          sort_order INTEGER NOT NULL DEFAULT 0, visible INTEGER NOT NULL DEFAULT 1,
          created_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_ink_layer_document ON ink_layer(document_id)",
        """
        CREATE TABLE IF NOT EXISTS scratch_pad (
          id TEXT PRIMARY KEY,
          document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
          title TEXT NOT NULL DEFAULT '',
          anchor_page INTEGER NOT NULL DEFAULT 0,
          anchor_x REAL NOT NULL DEFAULT 0, anchor_y REAL NOT NULL DEFAULT 0,
          bg TEXT NOT NULL DEFAULT 'rgba(255,255,255,1.0)',
          pattern TEXT NOT NULL DEFAULT 'dots',
          show_page INTEGER NOT NULL DEFAULT 0,
          created_at TEXT NOT NULL, updated_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_scratch_pad_document ON scratch_pad(document_id)",
    )

    /**
     * 在 [dbFile]（须尚不存在）建一个空库并写好 `meta`。
     *
     * 失败时**把半成品文件删掉**：留一个建了一半的 `library.sqlite` 在那儿，下次打开会被
     * [Db.open] 当成"存在但坏了"，那比"没建成"难查得多。
     */
    fun createLibrary(dbFile: File, workspaceName: String) {
        require(!dbFile.exists()) { "库文件已存在：${dbFile.absolutePath}" }
        dbFile.parentFile?.mkdirs()
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
            db.beginTransaction()
            try {
                for (sql in DDL) db.execSQL(sql.trimIndent())
                val now = Iso.now()
                for ((k, v) in listOf(
                    "schema_version" to VERSION.toString(),
                    "created_at" to now,
                    "workspace_name" to workspaceName,
                )) {
                    db.execSQL(
                        "INSERT INTO meta(key,value) VALUES(?,?) " +
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                        arrayOf<Any?>(k, v),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            Log.i(TAG, "建库 ${dbFile.absolutePath} schema=v$VERSION name=$workspaceName")
        } catch (e: Exception) {
            runCatching { db?.close() }
            db = null
            for (f in listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm"))) {
                runCatching { f.delete() }
            }
            Log.e(TAG, "建库失败，已清掉半成品：${dbFile.absolutePath}", e)
            throw e
        } finally {
            runCatching { db?.close() }
        }
    }
}
