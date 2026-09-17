package com.xvan.unireader.local

import android.content.Context
import android.util.Log
import com.xvan.unireader.local.store.LibLocation
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.Schema
import java.io.File
import org.json.JSONArray

/**
 * `.unrd` 工作区的定位与校验。
 *
 * 工作区布局（macOS 上是包，安卓上就是普通目录，见 ANDROID-STANDALONE-PLAN §6）：
 * ```
 * <名字>.unrd/
 *   UniReader/library.sqlite   # 全部元数据、笔记、笔迹、图层（schema v14）
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
    private const val KEY_SCANNED = "scanned"
    private const val KEY_SCAN_DONE = "scan_done"
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
            return bad(path, "路径不存在，U 盘或同步盘可能没挂载\n$path")
        }
        if (!dir.isDirectory) return bad(path, "不是文件夹：$path")
        if (!dir.canRead()) {
            return bad(path, "文件夹不可读，多半是没授予「所有文件访问权限」")
        }
        val db = File(dir, DB_REL)
        if (!db.isFile) {
            return bad(path, "这不是 UniReader 工作区（缺 $DB_REL）")
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

    // ---------- 新建 ----------

    /**
     * 工作区名净化，与 Mac `WorkspaceManager.sanitizedPackageName` 同口径：
     * 路径分隔符（`/`、`:`）换 `-`，去首尾空白。另外多挡了几个在 FAT32/exFAT 上非法的字符——
     * 工作区的常态就是躺在 U 盘上（§1），用 `?` 建出来的文件夹在那种卷上直接建不了。
     */
    fun sanitizeName(raw: String): String =
        raw.map { if (it in "/\\:*?\"<>|" || it.code < 0x20) '-' else it }
            .joinToString("")
            .trim()
            .trimEnd('.')          // Windows/exFAT 不接受结尾的点

    /**
     * 在 [parent] 下新建一个 `<name>.unrd` 工作区：建好 `UniReader/library.sqlite`（schema v14，
     * 见 [com.xvan.unireader.local.store.Schema]）与空的 `PDFs/`，返回工作区文件夹。
     *
     * **不覆盖已存在的同名文件夹**——那有可能是用户真正的库，覆盖等于删数据。重名直接报错，
     * 让界面把话说清楚（"这里已经有一个叫 xxx 的工作区了"），由用户换个名字。
     *
     * 建库失败会连**整个新建的文件夹**一起清掉：留一个只有半个骨架的 `.unrd` 在那儿，
     * 下次扫描会把它列出来、点开又说"这里没有 library.sqlite"，比没建成难查得多。
     * 在后台线程调用（建目录 + 建库在慢卷上是秒级，§9.5）。
     */
    fun create(parent: File, name: String): File {
        val clean = sanitizeName(name)
        require(clean.isNotEmpty()) { "工作区名不能为空" }
        require(parent.isDirectory) { "存放位置不是文件夹：${parent.absolutePath}" }
        require(parent.canWrite()) { "这个位置不可写：${parent.absolutePath}" }
        val dir = File(parent, "$clean.unrd")
        require(!dir.exists()) { "这里已经有「${dir.name}」了，换个名字" }
        var created = false
        try {
            require(dir.mkdirs()) { "建不了文件夹：${dir.absolutePath}" }
            created = true
            require(File(dir, PDFS_DIR).mkdirs()) { "建不了 $PDFS_DIR 目录" }
            Schema.createLibrary(File(dir, DB_REL), clean)
            Log.i(TAG, "新建工作区：${dir.absolutePath}")
            return dir
        } catch (e: Exception) {
            if (created) runCatching { dir.deleteRecursively() }
            Log.e(TAG, "新建工作区失败：${dir.absolutePath}", e)
            throw e
        }
    }

    /** 目录名以 .unrd 结尾 = 一眼可辨的工作区（不做强校验，真凭据是 library.sqlite） */
    fun looksLikeWorkspace(dir: File): Boolean = dir.name.endsWith(".unrd", ignoreCase = true)

    /**
     * 把一条 `location` 解析成本机文件。
     *
     * - `in_workspace=1` → 工作区内相对路径（`PDFs/xxx.pdf`），随文件夹搬动仍有效，这是主路径；
     * - `is_relative=1` → 外部文件但与工作区文件夹同在一块移动卷上，`path` 同样存的是**相对工作区
     *   文件夹的路径**（可含 `..`，见 `REQUIREMENTS.md` schema 说明）——不是「挂载点+绝对路径」，
     *   不存在「安卓/Mac 挂载点对不上」的问题：拼接基准都是当前设备上已经打开的这个工作区目录，
     *   与 `in_workspace` 走同一套 `File(workspaceDir, path)`，`..` 交给文件系统本身解析即可正确
     *   穿到同卷的兄弟目录（Mac 端见 `WorkspaceManager.resolvedPath`，两端拼接方式一致）；
     * - 其余 → Mac 上的绝对路径，在安卓上必然不存在，按路径失效处理。
     */
    fun resolvePdf(workspaceDir: File, loc: LibLocation): File? {
        if (loc.inWorkspace || loc.isRelative) {
            val f = File(workspaceDir, loc.path)
            return if (f.isFile) f else null.also {
                Log.w(TAG, "相对路径文件缺失：${loc.path}")
            }
        }
        val abs = File(loc.path)
        if (abs.isFile) return abs   // 极少数情况：路径恰好在安卓上也成立
        Log.w(TAG, "绝对路径在本机不存在（多半是 Mac 上的路径）：${loc.path}")
        return null
    }

    /** 取一个文档第一条能打开的 PDF（首版不做 variant 探测/合并，同 §6 的简化） */
    fun firstOpenablePdf(workspaceDir: File, store: LibraryStore, documentId: String): File? =
        store.locations(documentId).asSequence()
            .mapNotNull { resolvePdf(workspaceDir, it) }
            .firstOrNull()

    /**
     * 同 [firstOpenablePdf]，顺带给出**这个文件**所属版本的内容 hash（同 Mac `openTarget` 返回的 `hash`）。
     *
     * 扫描页对齐参数、OCR 文本层都按内容 hash 记，而一篇文档可以有多个版本：必须拿**真正打开的那个文件**
     * 的 hash 去查，否则会把 A 版本的参数套在 B 版本的页面上。**在 StoreQueue 线程上调用。**
     */
    fun firstOpenablePdfWithHash(workspaceDir: File, store: LibraryStore, documentId: String): Pair<File, String>? {
        for (loc in store.locations(documentId)) {
            val f = resolvePdf(workspaceDir, loc) ?: continue
            val hash = store.variants(documentId).firstOrNull { it.id == loc.variantId }?.contentHash.orEmpty()
            return f to hash
        }
        return null
    }

    // ---------- 最近工作区（只存路径；能不能开每次现场校验） ----------

    fun recents(ctx: Context): List<String> = readList(ctx, KEY_RECENTS)

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

    // ---------- 扫描结果（同样只存路径；能不能开每次现场校验） ----------

    /**
     * 上一次全盘扫描找到的 `.unrd`。存起来是因为扫一次在慢卷上要几十秒——每次回启动页都重扫
     * 既慢又吵，[scanDone] 为真就只用缓存，要刷新由用户按「重新扫描」。
     */
    fun scanned(ctx: Context): List<String> = readList(ctx, KEY_SCANNED)

    fun scanDone(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCAN_DONE, false)

    /**
     * @param markDone 只有**全盘**扫过才置位——它同时是「不必再自动扫一次」的凭据，
     *   若让「只扫某个文件夹」那种局部扫描也置位，用户就再也等不到那次全盘自动扫描了。
     */
    fun saveScanned(ctx: Context, paths: List<String>, markDone: Boolean = true) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SCANNED, JSONArray(paths).toString())
        if (markDone) e.putBoolean(KEY_SCAN_DONE, true)
        e.apply()
        Log.i(TAG, "扫描结果已存：${paths.size} 个（全盘=$markDone）")
    }

    private fun readList(ctx: Context, key: String): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "$key 解析失败，按空处理", e)
            emptyList()
        }
    }
}
