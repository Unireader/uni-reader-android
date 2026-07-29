package com.xvan.unireader.local.store

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * 裸 `SQLiteDatabase` 的薄封装。
 *
 * **为什么不用 Room**：`library.sqlite` 的 schema 是 Mac 端写死的跨平台契约
 * （`Sources/Store/LibraryStore.swift`，schema v7）。Room 要反过来拥有 schema、还会往库里塞
 * 自己的 `room_master_table` 并校验 identity hash——那是往共享库里拉屎，Mac 侧下次打开就多出
 * 一张不认识的表。这里只负责搬字节，DDL 一个字都不写（建库永远是 Mac 的事）。
 */
class Db private constructor(private val db: SQLiteDatabase, val path: String) : Closeable {

    companion object {
        const val TAG = "UniReader/DB"

        /**
         * 打开已存在的库。**不建库、不迁移**——文件不存在直接抛，因为「安卓端悄悄建了个空库」
         * 比「打不开」难查得多（用户会以为笔记丢了）。
         */
        fun open(file: File, readOnly: Boolean = false): Db {
            require(file.isFile) { "库文件不存在：${file.absolutePath}" }
            val flags =
                if (readOnly) SQLiteDatabase.OPEN_READONLY
                else SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING
            val raw = SQLiteDatabase.openDatabase(file.absolutePath, null, flags)
            val d = Db(raw, file.absolutePath)
            // busy_timeout 对齐 Mac 侧的 3000ms（`Sources/Store/SQLite.swift`）：工作区是单写者模型，
            // 但同一进程里后台渲染线程与主线程都可能读，锁等待给足时间比直接 SQLITE_BUSY 好。
            d.pragma("busy_timeout=3000")
            // 外键约束：Mac 侧每次打开都开，删文档要靠它级联删 variant/location/note/ink_layer。
            d.pragma("foreign_keys=ON")
            if (!readOnly) {
                // Mac 侧每次打开都会 `PRAGMA journal_mode=WAL`，所以这里即使没设成 WAL 也不会
                // 破坏契约；但设成了能少一次模式切换。FAT32/exFAT 的 U 盘上 WAL 建不起来
                // （缺共享内存），此时保持原模式继续跑，只记一行日志。
                val mode = d.pragma("journal_mode=WAL")
                if (!"wal".equals(mode, ignoreCase = true)) {
                    Log.w(TAG, "WAL 没能启用（journal_mode=$mode）：可能在 FAT32/exFAT 卷上")
                }
            }
            Log.i(TAG, "打开库 ${file.name} readOnly=$readOnly")
            return d
        }
    }

    /** 执行返回单值的 PRAGMA（`journal_mode=WAL` 这类要读回结果才真正生效） */
    fun pragma(expr: String): String? = db.rawQuery("PRAGMA $expr", null).use { c ->
        if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else null
    }

    fun <T> query(sql: String, args: Array<out Any?> = emptyArray(), map: (Cursor) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(map(c))
            out
        }

    fun exec(sql: String, args: Array<out Any?> = emptyArray()) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args)
    }

    fun <T> transaction(body: () -> T): T {
        db.beginTransaction()
        try {
            val r = body()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 把 `-wal` 合并回主库并截断。
     *
     * 打开时与关闭时各做一次（`ANDROID-STANDALONE-PLAN.md §9.2`）：工作区靠 U 盘/同步盘在
     * Mac 与平板之间搬，如果最近的写入还躺在 `-wal` 里，而搬运时只拷了 `.sqlite`，那部分写入
     * 就静默消失了——用户看到的是「笔记莫名少了几笔」，最难查的一类丢数据。
     */
    fun walCheckpointTruncate() {
        val r = pragma("wal_checkpoint(TRUNCATE)")
        Log.i(TAG, "wal_checkpoint(TRUNCATE) → $r")
    }

    override fun close() {
        if (!db.isReadOnly) runCatching { walCheckpointTruncate() }
            .onFailure { Log.w(TAG, "关库前 checkpoint 失败", it) }
        db.close()
    }
}

// ---------- Cursor 取值小工具（列名取值，顺序无关） ----------

fun Cursor.str(name: String): String = getColumnIndex(name).let { if (it < 0 || isNull(it)) "" else getString(it) }
fun Cursor.strOrNull(name: String): String? = getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }
fun Cursor.int(name: String, def: Int = 0): Int = getColumnIndex(name).let { if (it < 0 || isNull(it)) def else getInt(it) }
fun Cursor.long(name: String, def: Long = 0): Long = getColumnIndex(name).let { if (it < 0 || isNull(it)) def else getLong(it) }
fun Cursor.dbl(name: String, def: Double = 0.0): Double = getColumnIndex(name).let { if (it < 0 || isNull(it)) def else getDouble(it) }
fun Cursor.bool(name: String, def: Boolean = false): Boolean = getColumnIndex(name).let { if (it < 0 || isNull(it)) def else getInt(it) != 0 }
fun Cursor.blob(name: String): ByteArray = getColumnIndex(name).let { if (it < 0 || isNull(it)) ByteArray(0) else getBlob(it) }
