package com.xvan.unireader.local

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import java.io.File
import java.util.ArrayDeque

/**
 * 存储卷枚举 + 全盘查找 `.unrd` 工作区。
 *
 * **为什么要单独一层**：工作区的常态就是放在**外部存储**上（SD 卡 / OTG U 盘 / 同步盘，见
 * `ANDROID-STANDALONE-PLAN.md §1`），而 `Environment.getExternalStorageDirectory()` 只给得到
 * 内置的那块共享存储（`/storage/emulated/0`）——从它一级级往上点能不能摸到 `/storage/XXXX-XXXX`
 * 全看 ROM 心情，中间还夹着 `emulated`/`self` 这种点进去毫无意义的目录。所以卷这一层要显式列出来。
 *
 * 四条来源合并去重（哪条都可能在某个 ROM 上失灵，所以不挑一条用，是全都试）：
 * 1. [StorageManager.getStorageVolumes]——**名字最准**（"内部共享存储空间"/"SD 卡"/U 盘的卷标），
 *    API 30+ 有公开的 `getDirectory()`；30 以下只能反射 `getPath()`（非 SDK 接口，可能被拦，
 *    拦了就靠后面几条兜住）。
 * 2. [Context.getExternalFilesDirs]——API 30 以下拿外置卷路径最可靠的一条：返回的
 *    `…/Android/data/<包名>/files` 往上剥到 `Android` 的父目录就是卷根。
 * 3. `Environment.getExternalStorageDirectory()`——内置那块，一定有。
 * 4. 直接列 `/storage` 与 `/mnt/media_rw`——**OTG U 盘**在部分 ROM 上前三条都给不到。
 *
 * `/mnt/media_rw/<UUID>` 下的卷先过一道 [preferMirror]：有 ext4 映射（`内部存储/USB/<卷标>`）就换成映射。
 *
 * 全程只读 + 每一步都打日志：「插了 U 盘却看不到」这类问题不打点就只能靠猜（同 [Workspace] 的口径）。
 */
object StorageScan {

    const val TAG = "UniReader/Scan"

    /** 从卷根往下钻的最大层数。工作区再怎么放也不至于埋六层，再深就是在替用户遍历整块盘 */
    const val MAX_DEPTH = 6

    /** 扫描时限。慢卷（U 盘/同步盘）上遍历是秒级起步，不设限就会一直转下去 */
    const val DEFAULT_BUDGET_MS = 45_000L

    /** 最多走过多少个文件夹。跟时限一样是兜底，命中任一条都会在结果里说明「没走完」 */
    const val MAX_DIRS = 20_000

    /** `/storage` 下点进去没有意义的挂载点（`emulated` 的内容由第 3 条来源给） */
    private val SKIP_MOUNTS = setOf("emulated", "self", "container", "knox-emulated", "enc_emulated")

    /** 遍历时整棵跳过的目录：`Android/` 是 App 私有区（还多半没权限），其余是文件系统的垃圾桶 */
    private val SKIP_DIRS = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")

    /** 一块能浏览/扫描的存储卷。[primary] = 内置那块（排在最前） */
    data class Volume(val label: String, val dir: File, val primary: Boolean)

    /** 扫描的取消令牌：后台线程每处理一个目录看一眼，用户关掉窗口就停 */
    class Cancel {
        @Volatile
        var stopped = false
            private set

        fun stop() {
            stopped = true
        }
    }

    /**
     * @param found 找到的 `.unrd` 文件夹（按发现顺序＝广度优先，浅的先出）。
     * @param truncated 非 null 表示**没走完**，值是原因（超时/超上限/被停止）。
     *   宁可明说也不要假装扫全了——「扫描到 2 个」和「扫到 2 个就没时间了」是两回事。
     */
    data class Result(val found: List<File>, val dirs: Int, val ms: Long, val truncated: String?)

    // ---------- 卷 ----------

    fun volumes(ctx: Context): List<Volume> {
        val out = LinkedHashMap<String, Volume>()
        val primaryPath = canon(Environment.getExternalStorageDirectory())

        fun add(label: String, raw: File?, primary: Boolean) {
            if (raw == null) return
            val dir = preferMirror(raw, label)
            if (!dir.isDirectory) return
            if (!dir.canRead()) {
                Log.i(TAG, "跳过不可读的卷：${dir.absolutePath}（没权限或没挂载）")
                return
            }
            val key = canon(dir)
            val name = label.ifBlank { if (key == primaryPath) "内部存储" else dir.name }
            val old = out[key]
            // 同一块盘被多条来源发现时，留信息更全的那个名字（StorageManager 给的卷标最准）
            if (old == null || (label.isNotBlank() && old.label == File(key).name)) {
                out[key] = Volume(name, File(key), primary || key == primaryPath)
            }
        }

        // ① StorageManager
        runCatching {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm.storageVolumes) {
                val dir =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory else pathByReflection(v)
                Log.i(TAG, "StorageManager 卷：${v.getDescription(ctx)} → ${dir?.absolutePath} 状态=${v.state}")
                add(v.getDescription(ctx).orEmpty(), dir, v.isPrimary)
            }
        }.onFailure { Log.w(TAG, "StorageManager 枚举失败，靠其余来源兜住", it) }

        // ② getExternalFilesDirs：…/Android/data/<pkg>/files 往上剥
        runCatching {
            for (f in ctx.getExternalFilesDirs(null)) add("", volumeRootOf(f), false)
        }.onFailure { Log.w(TAG, "getExternalFilesDirs 失败", it) }

        // ③ 内置共享存储
        add("内部存储", Environment.getExternalStorageDirectory(), true)

        // ④ 直接列挂载点（OTG U 盘常常只有这条能看见）
        for (base in listOf(File("/storage"), File("/mnt/media_rw"))) {
            val kids = base.listFiles()
            if (kids == null) {
                Log.i(TAG, "列不了 ${base.absolutePath}（正常，多数 ROM 不给列）")
                continue
            }
            for (d in kids) {
                if (d.name in SKIP_MOUNTS) continue
                add("", d, false)
            }
        }

        val list = out.values.sortedWith(compareByDescending<Volume> { it.primary }.thenBy { it.label })
        Log.i(TAG, "可用存储卷 ${list.size} 块：" + list.joinToString { "${it.label}=${it.dir.absolutePath}" })
        return list
    }

    /**
     * API 30 以下只能反射 `StorageVolume.getPath()`。它是非 SDK 接口，targetSdk 高时可能被拦——
     * 拦了就返回 null，卷路径交给来源 ②④ 兜底，不让整条枚举因此炸掉。
     */
    private fun pathByReflection(v: StorageVolume): File? = runCatching {
        (v.javaClass.getMethod("getPath").invoke(v) as? String)?.let(::File)
    }.onFailure { Log.i(TAG, "反射 getPath 不可用（正常）：${it.message}") }.getOrNull()

    private fun volumeRootOf(f: File?): File? {
        var cur = f
        while (cur != null) {
            if (cur.name == "Android") return cur.parentFile
            cur = cur.parentFile
        }
        return null
    }

    /** 规范化路径：`/sdcard` 与 `/storage/emulated/0` 是同一块盘，不折算就会扫两遍 */
    private fun canon(f: File): String = runCatching { f.canonicalPath }.getOrElse { f.absolutePath }

    // ---------- ext4 映射（Magisk 模块 usbext4） ----------

    private const val MEDIA_RW = "/mnt/media_rw/"

    /** 模块把盘映射到内部存储下的这个目录（模块配置 `EXP_NAME`，默认 USB） */
    private const val MIRROR_DIR = "USB"

    /**
     * `/mnt/media_rw/<UUID>/…` 换成 ext4 映射里的同一个位置；不在那下面、或找不到映射就原样返回。
     *
     * **为什么要换**：有「所有文件访问权限」时 `StorageManager` 会把没对应用开放的 U 盘也列出来，给的却是
     * `/mnt/media_rw/<UUID>`。PixelOS 上 SELinux 不让普通应用查看 `/mnt/media_rw` 本身，SQLite 开库时逐级
     * `lstat` 路径就 `SQLITE_CANTOPEN`，写文件也不行；经内部存储的映射走，读写由存储服务代办才行
     * （ext4 盘的映射与所有者处理见 `agents-home/usbext4-magisk`）。HyperOS 上 exFAT 盘直接走这条路径是能用的，
     * 所以找不到映射时不改。
     *
     * @param label 卷标（`StorageVolume.getDescription`），不知道就传空。
     */
    fun preferMirror(dir: File, label: String = ""): File {
        val path = dir.absolutePath
        if (!path.startsWith(MEDIA_RW)) return dir
        val uuid = path.removePrefix(MEDIA_RW).substringBefore('/')
        if (uuid.isEmpty()) return dir
        val rest = path.removePrefix(MEDIA_RW + uuid).trimStart('/')
        val mirror = mirrorOf(File(MEDIA_RW + uuid), uuid, label) ?: return dir
        val out = if (rest.isEmpty()) mirror else File(mirror, rest)
        Log.i(TAG, "换成 ext4 映射：$path → ${out.absolutePath}")
        return out
    }

    /**
     * 找 [root]（`/mnt/media_rw/<UUID>`）的映射。模块的命名规则：卷标（`/`、空白、`\`、`|` 换成 `_`），
     * 重名时加 `-<UUID 第一段>`，没卷标用 UUID。按这个顺序先猜名字，再退到 `USB/` 下的全部文件夹；
     * 每个候选都要**根目录下的文件名与原卷一致**才算（挡掉没收干净的空目录、别的盘）。
     * 原卷列不出来时没法比对，只认按名字猜中的。
     */
    private fun mirrorOf(root: File, uuid: String, label: String): File? {
        val base = File(Environment.getExternalStorageDirectory(), MIRROR_DIR)
        val name = label.replace(Regex("[/ \t\\\\|]"), "_")
        val guessed = buildList {
            if (name.isNotBlank()) {
                add("$name-${uuid.substringBefore('-')}")
                add(name)
            }
            add(uuid)
        }.map { File(base, it) }
        val want = topNames(root)
        if (want == null) return guessed.firstOrNull { it.isDirectory && it.canRead() }
        val others = base.listFiles()?.filter { it.isDirectory && it !in guessed }.orEmpty()
        return (guessed + others).firstOrNull { it.isDirectory && topNames(it) == want }
    }

    /** 根目录下的文件名，不含点开头的（存储服务会藏掉一部分隐藏文件，两边不一定一样） */
    private fun topNames(dir: File): Set<String>? = dir.list()?.filterNot { it.startsWith(".") }?.toSet()

    // ---------- 扫描 ----------

    /**
     * 从 [roots] 广度优先找 `.unrd` 文件夹。
     *
     * **广度优先而不是深度优先**：工作区一般就在盘根或 `Documents/` 这种浅处，广度优先能让它们
     * 先出结果——扫描是边扫边显示的，先出的就是最可能要开的那个。
     *
     * [onFound]/[onDir] **在后台线程上被调用**，要动界面的调用方自己 post 回主线程。
     * 返回 [Result.truncated] 非 null 时务必把原因显示出来（§「不许静默截断」）。
     */
    fun scan(
        roots: List<File>,
        cancel: Cancel,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        onFound: (File) -> Unit = {},
        onDir: (File) -> Unit = {},
    ): Result {
        val t0 = SystemClock.uptimeMillis()
        val seen = HashSet<String>()
        val found = ArrayList<File>()
        val queue = ArrayDeque<Pair<File, Int>>()
        for (r in roots) if (seen.add(canon(r))) queue.add(r to 0)
        Log.i(TAG, "开始扫描 ${queue.size} 个根：" + roots.joinToString { it.absolutePath })

        var dirs = 0
        var deepSkipped = 0
        var truncated: String? = null
        while (queue.isNotEmpty()) {
            if (cancel.stopped) {
                truncated = "被停止"
                break
            }
            if (SystemClock.uptimeMillis() - t0 > budgetMs) {
                truncated = "超过 ${budgetMs / 1000} 秒时限"
                break
            }
            if (dirs >= MAX_DIRS) {
                truncated = "已走过 $MAX_DIRS 个文件夹（上限）"
                break
            }
            val (dir, depth) = queue.removeFirst()
            dirs++
            onDir(dir)
            // null = 没权限或不是真目录：跳过即可，这在 /storage 下很常见，不值得报错
            val kids = dir.listFiles() ?: continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val name = k.name
                if (name.startsWith(".")) continue
                if (Workspace.looksLikeWorkspace(k)) {
                    if (seen.add(canon(k))) {
                        found.add(k)
                        val hasDb = File(k, Workspace.DB_REL).isFile
                        // 缺库文件的也照样报出去：让用户点开时看到 `Workspace.check` 给的具体原因，
                        // 比在这里悄悄滤掉、用户以为"扫不到我的库"要好
                        if (hasDb) Log.i(TAG, "找到工作区：${k.absolutePath}")
                        else Log.w(TAG, "有 .unrd 但没有 ${Workspace.DB_REL}：${k.absolutePath}")
                        onFound(k)
                    }
                    continue   // .unrd 里面不再往下钻（里面只有 UniReader/ 和 PDFs/）
                }
                if (name in SKIP_DIRS) continue
                if (depth + 1 > MAX_DEPTH) {
                    deepSkipped++
                    continue
                }
                if (seen.add(canon(k))) queue.add(k to depth + 1)
            }
        }

        val ms = SystemClock.uptimeMillis() - t0
        // 深度上限也算「没走全」，只是不值得为它中断整次扫描——但必须留在日志里：
        // 「扫不到我的库」十有八九就是它埋得比 MAX_DEPTH 还深
        if (deepSkipped > 0) Log.i(TAG, "有 $deepSkipped 个文件夹超过 $MAX_DEPTH 层没往下钻")
        Log.i(
            TAG,
            "扫描结束：找到 ${found.size} 个，走过 $dirs 个文件夹，${ms}ms" +
                (truncated?.let { "，**没走完**：$it" } ?: ""),
        )
        return Result(found, dirs, ms, truncated)
    }
}
