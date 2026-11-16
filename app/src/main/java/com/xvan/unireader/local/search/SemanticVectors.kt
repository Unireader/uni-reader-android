package com.xvan.unireader.local.search

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xvan.unireader.local.server.ServerClient
import com.xvan.unireader.local.server.SyncWire
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 服务器同步来的块向量（`../docs/SEMANTIC-SYNC-PLAN.md §4.1`）+ 语义搜索的本机开关。
 *
 * - 本机缓存 `<noBackupFilesDir>/search-vec/<服务器工作区 id>.sqlite`：`vec(hash BLOB PRIMARY KEY, v BLOB)` + 一行 `meta(model, epoch, since)`。
 *   **派生数据**：不进 `library.sqlite`、不进工作区备份、不进系统备份（`noBackupFilesDir`）；删了只是重拉一遍。
 * - 何时拉：服务器同步一轮结束后（`ServerSync` 调 [pullAsync]），语义搜索开着且模型文件就位时；增量 `GET …/vectors?since=`，
 *   `epoch` 变了清空重拉；服务器回 404 / 405 = 程序太旧没有这个接口，当不支持。只对服务器工作区。
 * - 搜索时整份读进内存（[vectors]，7000 条约 7 MB），表一变作废。
 */
object SemanticVectors {
    private const val TAG = "UniReader/SemVec"
    private const val PREFS = "semantic"
    private const val K_ENABLED = "enabled"
    private const val PAGE = 2000
    /** 同一工作区两次拉取默认至少隔这么久（同步一轮接一轮时别每轮都问） */
    private const val MIN_GAP_MS = 20_000L

    // ---------------- 开关 ----------------

    /** 设置里「同时按意思搜索」（本机、两种工作区共用一份；默认关） */
    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ENABLED, on).apply()
        if (!on) QueryEmbedder.unload()
        changed()
    }

    /** 开关开着且模型文件就位 = 这一路能用 */
    fun usable(ctx: Context): Boolean = enabled(ctx) && EmbedModel.isInstalled(ctx)

    // ---------------- 状态（设置页显示）----------------

    /** 一个工作区的向量缓存状态。[error] = 上一次拉取出的错（null = 没出错 / 还没拉过）。 */
    data class Stats(val count: Int, val error: String?, val lastPullAt: Long, val pulling: Boolean, val unsupported: Boolean)

    private val errors = ConcurrentHashMap<String, String>()
    private val lastPull = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap<String, Boolean>()
    private val unsupported = ConcurrentHashMap<String, Boolean>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** 状态变了（主线程回调）：拉完 / 出错 / 开关 / 模型装好删掉 */
    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    fun changed() { main.post { for (l in listeners) l() } }

    /** 读条数要开库，后台线程调 */
    fun stats(ctx: Context, wid: String): Stats = Stats(
        count = runCatching { open(ctx, wid).use { db -> count(db) } }.getOrDefault(0),
        error = errors[wid], lastPullAt = lastPull[wid] ?: 0L, pulling = running[wid] == true, unsupported = unsupported[wid] == true,
    )

    // ---------------- 本机缓存 ----------------

    private fun file(ctx: Context, wid: String): File {
        val safe = wid.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(ctx.noBackupFilesDir, "search-vec"), "$safe.sqlite")
    }

    private fun open(ctx: Context, wid: String): SQLiteDatabase {
        val f = file(ctx, wid)
        f.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS vec(hash BLOB PRIMARY KEY, v BLOB NOT NULL) WITHOUT ROWID")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(model TEXT NOT NULL, epoch TEXT NOT NULL, since INTEGER NOT NULL)")
        return db
    }

    private class Meta(val model: String, val epoch: String, val since: Long)

    private fun meta(db: SQLiteDatabase): Meta? = db.rawQuery("SELECT model, epoch, since FROM meta LIMIT 1", null).use { c ->
        if (c.moveToFirst()) Meta(c.getString(0), c.getString(1), c.getLong(2)) else null
    }

    private fun setMeta(db: SQLiteDatabase, m: Meta) {
        db.execSQL("DELETE FROM meta")
        db.execSQL("INSERT INTO meta(model, epoch, since) VALUES(?, ?, ?)", arrayOf(m.model, m.epoch, m.since))
    }

    private fun count(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM vec", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 一份读进内存的向量：键 = 正文 SHA-256 的十六进制（[WorkspaceSearch.SearchChunk.hashHex]） */
    class Loaded(val map: Map<String, ByteArray>, val dim: Int)

    private class CacheEntry(val gen: Long, val loaded: Loaded)

    private val memCache = ConcurrentHashMap<String, CacheEntry>()
    /** 每个工作区的表「代」：写过一次 +1，内存那份对不上就重读 */
    private val gens = ConcurrentHashMap<String, Long>()

    private fun bump(wid: String) { gens.merge(wid, 1L, Long::plus) }

    /** 这个工作区的全部向量（只认 [Semantic.MODEL_ID]）。没有缓存文件 = 空。后台线程调 */
    fun vectors(ctx: Context, wid: String): Loaded {
        val gen = gens[wid] ?: 0L
        memCache[wid]?.takeIf { it.gen == gen }?.let { return it.loaded }
        if (!file(ctx, wid).isFile) return Loaded(emptyMap(), 0)
        val loaded = open(ctx, wid).use { db ->
            if (meta(db)?.model != Semantic.MODEL_ID) return@use Loaded(emptyMap(), 0)
            val map = HashMap<String, ByteArray>(count(db) * 2)
            var dim = 0
            db.rawQuery("SELECT hash, v FROM vec", null).use { c ->
                while (c.moveToNext()) {
                    val v = c.getBlob(1)
                    if (dim == 0) dim = v.size
                    map[Semantic.hex(c.getBlob(0))] = v
                }
            }
            Loaded(map, dim)
        }
        memCache[wid] = CacheEntry(gen, loaded)
        return loaded
    }

    // ---------------- 拉取 ----------------

    /**
     * 后台拉一次（立即返回）。开关关着 / 模型没就位 = 不拉。离上次拉不到 [minGapMs] 也不拉
     * （0 = 不管隔多久：设置页打开开关、模型刚装好）。
     */
    fun pullAsync(ctx: Context, wid: String, client: ServerClient, minGapMs: Long = MIN_GAP_MS) {
        val app = ctx.applicationContext
        if (!usable(app)) return
        if (System.currentTimeMillis() - (lastPull[wid] ?: 0L) < minGapMs) return
        if (running.putIfAbsent(wid, true) != null) return
        changed()
        Thread({
            try {
                pull(app, wid, client)
            } finally {
                running.remove(wid)
                changed()
            }
        }, "unireader-vectors").start()
    }

    /** 增量拉到底（阻塞，网络线程）。返回这一次新收的条数。 */
    private fun pull(ctx: Context, wid: String, client: ServerClient): Int {
        lastPull[wid] = System.currentTimeMillis()
        var got = 0
        try {
            open(ctx, wid).use { db ->
                var m = meta(db)?.takeIf { it.model == Semantic.MODEL_ID } ?: run {
                    // 头一次，或本机缓存是别的模型的：清空从头拉
                    db.execSQL("DELETE FROM vec")
                    Meta(Semantic.MODEL_ID, "", 0L).also { setMeta(db, it); bump(wid) }
                }
                while (true) {
                    val page = SyncWire.decodeVecPage(client.vectors(wid, Semantic.MODEL_ID, m.since, m.epoch, PAGE))
                    val reset = page.epoch != m.epoch
                    db.beginTransaction()
                    try {
                        // epoch 变了（服务器清理过孤儿）：先清空本地，这一页起就是从头给的
                        if (reset) db.execSQL("DELETE FROM vec")
                        val st = db.compileStatement("INSERT OR REPLACE INTO vec(hash, v) VALUES(?, ?)")
                        for (e in page.entries) {
                            st.bindBlob(1, e.hash); st.bindBlob(2, e.v); st.executeInsert(); st.clearBindings()
                        }
                        st.close()
                        m = Meta(Semantic.MODEL_ID, page.epoch, page.next)
                        setMeta(db, m)
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    got += page.entries.size
                    if (reset || page.entries.isNotEmpty()) bump(wid)
                    if (!page.more || page.entries.isEmpty()) break
                }
                Log.i(TAG, "$wid：拉到 $got 条，本机共 ${count(db)} 条")
            }
            errors.remove(wid)
            unsupported.remove(wid)
        } catch (e: ServerClient.Failure) {
            if (e.status == 404 || e.status == 405) {
                // 服务器程序太旧（或这个工作区在服务器上没了）：只用关键词
                unsupported[wid] = true
                errors[wid] = "server does not support vectors (HTTP ${e.status})"
                Log.i(TAG, "$wid：服务器没有向量接口（HTTP ${e.status}），只用关键词")
            } else {
                errors[wid] = "HTTP ${e.status}${if (e.message.isNullOrEmpty()) "" else ": ${e.message}"}"
                Log.w(TAG, "$wid：拉向量失败", e)
            }
        } catch (e: Throwable) {
            errors[wid] = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "$wid：拉向量失败", e)
        }
        return got
    }
}
