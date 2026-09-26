package com.xvan.unireader.local.store

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * **建库 + 迁移**——本模块**唯一**写 DDL 的地方，逐条对应 Mac `Sources/Store/LibraryStore.swift` 的 `migrate()`。
 *
 * 两个入口：
 * - [createLibrary]：安卓本机新建工作区（`Launcher` 的「新建工作区」），文件还不存在时建一个空库；
 * - [migrate]：**可写**打开一个已有的库时（`LibraryStore.open`），与 Mac 一样补齐缺的表（`CREATE … IF NOT EXISTS`）、
 *   缺的列（`ADD COLUMN`），并把 `meta.schema_version` 写成 [VERSION]。只读打开不迁移。
 *
 * 🔴 2026-09-26 用户撤销了「安卓不改表结构」的旧规定（见 android/AGENTS.md）：安卓与 Mac 一样迁移老库。
 * 剩下的纪律：**schema 是两端共同的契约**——下面每一条 DDL 与 Mac `migrate()` 逐字相同（只是
 * `execSQL` 一次只吃一条语句，所以拆成了列表），改结构两端同一次一起改，并同步 `REQUIREMENTS.md §8`。
 *
 * 刻意**没抄**的只有 `page_geom`：Mac 注释写明它是 Mac 私有的纯缓存表（按内容 hash、不进镜像、
 * 不升版本、别的端不必认识）。索引 `idx_note_document_kind_page` 不是数据契约，但语句相同、建上无害，一并建。
 */
object Schema {

    const val TAG = "UniReader/Schema"

    /**
     * 与 Mac `LibraryStore.schemaVersion` 同步。v13 `image`、v14 `page_align`、v15 `md_doc`（本端只建表不用）、
     * v16 `board_note`/`board_item`、v17 `board_page`——表都建上，写进去的版本号才不撒谎。
     */
    const val VERSION = 17

    /**
     * v14 `page_align`（`../SCAN-ALIGN-PLAN.md §3`，**只有 Mac 写**）。单独拎出来是因为离线镜像合并
     * 要往**可能没有这张表**的库里整行覆盖（只读打开、没迁移过的老库），那边要先 `CREATE TABLE IF NOT EXISTS`
     * ——语句仍只在这一处（见 `MirrorApply.fillAlign` / `LibraryStore.copyPageAlignTo`）。
     */
    const val PAGE_ALIGN_DDL = """
        CREATE TABLE IF NOT EXISTS page_align (
          content_hash TEXT PRIMARY KEY,
          enabled INTEGER NOT NULL DEFAULT 0,
          page_count INTEGER NOT NULL,
          payload BLOB NOT NULL,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """

    /** v15 Markdown 笔记的元数据表（`../MARKDOWN-NOTES-PLAN.md §2`）。本端没有 Markdown 笔记，只建表不读写 */
    private val MD_DOC_DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS md_doc (
          id TEXT PRIMARY KEY,
          title TEXT NOT NULL,
          rel_path TEXT NOT NULL,
          group_name TEXT NOT NULL DEFAULT '',
          sort_order INTEGER NOT NULL DEFAULT 0,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          last_opened_at TEXT NOT NULL
        )
        """,
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_md_doc_path ON md_doc(rel_path)",
    )

    /**
     * v16 画板笔记两张表 + 索引（`../BOARD-NOTE-PLAN.md §2`）+ v17 分页画板的页（§9），
     * 与 Mac `migrate()` 里那三段**逐字相同**。改这里必须与 Mac `LibraryStore.swift` 同步。
     */
    val BOARD_DDL = listOf(
        """
        CREATE TABLE IF NOT EXISTS board_note (
          id TEXT PRIMARY KEY,
          title TEXT NOT NULL DEFAULT '',
          bg TEXT NOT NULL DEFAULT 'rgba(255,255,255,1.0)',
          pattern TEXT NOT NULL DEFAULT 'dots',
          group_name TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL,
          last_opened_at TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS board_item (
          id TEXT PRIMARY KEY,
          board_id TEXT NOT NULL REFERENCES board_note(id) ON DELETE CASCADE,
          kind INTEGER NOT NULL,
          x REAL NOT NULL, y REAL NOT NULL, w REAL NOT NULL, h REAL NOT NULL,
          payload BLOB NOT NULL,
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_board_item_board ON board_item(board_id)",
        // v17：分页画板的页。一个画板有页 = 分页模式，没有页 = 无限画布。条目 payload 带 "page"（这里的 id），
        // 点与 x/y/w/h 是页内坐标；sort_key 小数（插页取中点）；width/height 整本统一；template 背景模板名。
        """
        CREATE TABLE IF NOT EXISTS board_page (
          id TEXT PRIMARY KEY,
          board_id TEXT NOT NULL REFERENCES board_note(id) ON DELETE CASCADE,
          sort_key REAL NOT NULL,
          width REAL NOT NULL, height REAL NOT NULL,
          template TEXT NOT NULL DEFAULT 'blank',
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_board_page_board ON board_page(board_id, sort_key)",
    )

    /**
     * 与 Mac `migrate()` 里那段 `CREATE TABLE IF NOT EXISTS …` 逐字一致，只是拆成了单条语句。
     * 唯一形式上的出入：`location` 建表时就带上 `is_relative`（Mac 是 CREATE 之后 `ADD COLUMN` 补的，
     * 全新库两者列序相同——Mac `MirrorFingerprint` 的列序注释说的就是这份）。
     */
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
        "CREATE INDEX IF NOT EXISTS idx_note_document_kind_page ON note(document_id, kind, page)",
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
        """
        CREATE TABLE IF NOT EXISTS image (
          sha256 TEXT PRIMARY KEY,
          ext TEXT NOT NULL,
          width INTEGER NOT NULL, height INTEGER NOT NULL, bytes INTEGER NOT NULL,
          created_at TEXT NOT NULL,
          orphaned_at TEXT
        )
        """,
        PAGE_ALIGN_DDL,
    ) + MD_DOC_DDL + BOARD_DDL

    /**
     * 老库补列：与 Mac `migrate()` 的 `addColumnIfMissing` **逐条同序**（v1 → v12 攒下来的那些）。
     * 幂等：列已存在就跳过。v12 之后的版本只加表不加列（Mac 注释逐版写明了），没有更多条目。
     */
    private val ADD_COLUMNS = listOf(
        Triple("document", "read_page", "INTEGER NOT NULL DEFAULT 0"),
        Triple("document", "read_frac", "REAL NOT NULL DEFAULT 0"),
        Triple("location", "in_workspace", "INTEGER NOT NULL DEFAULT 0"),
        Triple("document", "read_zoom", "REAL NOT NULL DEFAULT 1"),
        Triple("document", "read_hfrac", "REAL NOT NULL DEFAULT 0"),
        Triple("location", "is_relative", "INTEGER NOT NULL DEFAULT 0"),
        Triple("scratch_pad", "pattern", "TEXT NOT NULL DEFAULT 'dots'"),
        Triple("scratch_pad", "show_page", "INTEGER NOT NULL DEFAULT 0"),
        Triple("document", "group_name", "TEXT NOT NULL DEFAULT ''"),
        Triple("document", "canvas_mode", "INTEGER NOT NULL DEFAULT 0"),
    )

    private fun columnsOf(db: Db, table: String): Set<String> =
        db.query("PRAGMA table_info($table)") { it.getString(it.getColumnIndexOrThrow("name")) }.toSet()

    private fun metaValue(db: Db, key: String): String? =
        db.query("SELECT value FROM meta WHERE key=?", arrayOf(key)) { it.getString(0) }.firstOrNull()

    private fun setMeta(db: Db, key: String, value: String) = db.exec(
        "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        arrayOf(key, value),
    )

    /**
     * **迁移一个已有的库**（可写连接），同 Mac `migrate()`：建缺的表 → 补缺的列 → 写 `schema_version`。
     * 整套一个事务（中途失败什么都不留）。调用方负责只在可写连接上调。
     *
     * 与 Mac 的一处出入：库里的版本号**比本端新**（新版 Mac 已经升到 18 了）时不往回改——
     * 表照样补（`IF NOT EXISTS` 对新库是空操作），只是不把版本号写小。Mac 那边是无条件写自己的版本。
     *
     * @return 迁移前库里写的版本号（没有 / 不是数字 → null），调用方打日志用
     */
    fun migrate(db: Db): Int? {
        val before = runCatching { metaValue(db, "schema_version") }.getOrNull()
        val beforeV = before?.toIntOrNull()
        db.transaction {
            for (sql in DDL) db.exec(sql.trimIndent())
            for ((table, col, decl) in ADD_COLUMNS) {
                if (col !in columnsOf(db, table)) {
                    db.exec("ALTER TABLE $table ADD COLUMN $col $decl")
                    Log.i(TAG, "补列 $table.$col")
                }
            }
            if (before == null) setMeta(db, "created_at", Iso.now())   // 同 Mac：没有版本号 = 新库
            if (beforeV == null || beforeV < VERSION) setMeta(db, "schema_version", VERSION.toString())
        }
        if (beforeV != VERSION) Log.i(TAG, "迁移 schema_version=$before → v$VERSION（库更新时不回写）")
        return beforeV
    }

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
