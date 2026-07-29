package com.xvan.unireader.local

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray

/**
 * `.unrd` 工作区的定位与校验。
 *
 * 工作区布局（macOS 上是包，安卓上就是普通目录，见 ANDROID-STANDALONE-PLAN §6）：
 * ```
 * <名字>.unrd/
 *   UniReader/library.sqlite   # 全部元数据、笔记、笔迹、图层（schema v7）
 *   PDFs/<uuid>.pdf            # 拷进工作区的文件
 * ```
 *
 * M0 只做到「这个文件夹能不能当工作区打开」+ 最近列表；真正读表是 M1 的 `LibraryStore`。
 * 校验失败一律给**具体原因**：这类问题（没授权/U 盘没挂载/只拷了 .sqlite 漏了 -wal）
 * 全都长得像「打不开」，不把原因说清就得靠猜。
 */
object Workspace {

    const val TAG = "UniReader/WS"

    /** 库文件在工作区内的相对路径（与 Mac 端一致，不可改） */
    const val DB_REL = "UniReader/library.sqlite"

    /** 工作区内 PDF 目录（location.in_workspace=1 时 path 相对于工作区根） */
    const val PDFS_DIR = "PDFs"

    private const val PREFS = "workspace"
    private const val KEY_RECENTS = "recents"
    private const val MAX_RECENTS = 6

    sealed class Check {
        /**
         * @param walBytes `library.sqlite-wal` 的字节数。>0 表示上一个写者（通常是 Mac）没做
         *   checkpoint，最近的写入还在 WAL 里——**搬运工作区时必须连 `-wal`/`-shm` 一起搬**，
         *   只拷 `.sqlite` 会静默丢掉这部分（§9.2）。
         * @param readOnly 库文件不可写：能读不能改，此时不该让用户写笔迹（M1 起据此禁写）。
         */
        data class OK(val dir: File, val db: File, val walBytes: Long, val readOnly: Boolean) : Check()
        data class Bad(val reason: String) : Check()
    }

    fun check(dir: File): Check {
        val path = dir.absolutePath
        if (!dir.exists()) {
            return bad(path, "路径不存在：$path\nU 盘/同步盘可能没挂载，或已被移走")
        }
        if (!dir.isDirectory) return bad(path, "不是文件夹：$path")
        if (!dir.canRead()) {
            return bad(path, "文件夹不可读：$path\n多半是没授予「所有文件访问权限」")
        }
        val db = File(dir, DB_REL)
        if (!db.isFile) {
            return bad(path, "这里没有 $DB_REL\n它不是 UniReader 工作区（工作区文件夹名通常以 .unrd 结尾）")
        }
        if (!db.canRead()) return bad(path, "库文件不可读：${db.absolutePath}")
        val wal = File(dir, "$DB_REL-wal")
        val walBytes = if (wal.isFile) wal.length() else 0L
        Log.i(
            TAG,
            "check OK path=$path db=${db.length()}B wal=${walBytes}B " +
                "writable=${db.canWrite()} pdfs=${File(dir, PDFS_DIR).isDirectory}",
        )
        return Check.OK(dir, db, walBytes, readOnly = !db.canWrite())
    }

    private fun bad(path: String, reason: String): Check.Bad {
        Log.w(TAG, "check FAIL path=$path reason=${reason.replace('\n', ' ')}")
        return Check.Bad(reason)
    }

    /** 目录名以 .unrd 结尾 = 一眼可辨的工作区（不做强校验，真凭据是 library.sqlite） */
    fun looksLikeWorkspace(dir: File): Boolean = dir.name.endsWith(".unrd", ignoreCase = true)

    // ---------- 最近工作区（只存路径；能不能开每次现场校验） ----------

    fun recents(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECENTS, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "最近列表解析失败，按空处理", e)
            emptyList()
        }
    }

    fun remember(ctx: Context, path: String) {
        val list = ArrayList(recents(ctx))
        list.remove(path)
        list.add(0, path)
        while (list.size > MAX_RECENTS) list.removeAt(list.size - 1)
        save(ctx, list)
        Log.i(TAG, "记入最近：$path（共 ${list.size} 条）")
    }

    fun forget(ctx: Context, path: String) {
        val list = ArrayList(recents(ctx))
        if (list.remove(path)) save(ctx, list)
    }

    private fun save(ctx: Context, list: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECENTS, JSONArray(list).toString())
            .apply()
    }
}
