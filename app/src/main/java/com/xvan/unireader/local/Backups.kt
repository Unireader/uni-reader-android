package com.xvan.unireader.local

import android.util.Log
import com.xvan.unireader.local.store.LibraryStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工作区定时备份（`../docs/BACKUP-PLAN.md §3`，Mac `BackupRetention` / `BackupFile` / `BackupService` 的同款）：
 * `<工作区>/UniReader/Backups/library-yyyyMMdd-HHmmss.sqlite`（本地时间），还原点多一个 `-before-restore` 尾巴。
 *
 * - **只备份 `library.sqlite`**：`VACUUM INTO` 到临时名再改名（天生一致，半截文件不会被当成一份备份）；
 * - **节流 30 分钟**（拿最近一份备份文件自己的时间戳判，不另存状态）；手动「立即备份」不受节流；
 * - **分级稀释**：最近 5 份全留 + 最近 7 天每天一份 + 最近 4 周每周一份；还原点不参与、认不出的文件一个不碰。
 * 命名与稀释规则与 Mac 逐项一致——两端写的备份互相认得、互相稀释也不会误删。
 */
object Backups {
    const val TAG = "UniReader/Backup"
    const val DIR = "Backups"
    private const val PREFIX = "library-"
    private const val RESTORE_SUFFIX = "-before-restore"
    private const val EXT = "sqlite"
    const val THROTTLE_MS = 30 * 60 * 1000L
    /** 阅读界面开着时多久跑一次（Mac 默认 6 小时） */
    const val INTERVAL_MS = 6 * 3600 * 1000L

    class Item(val file: File, val date: Date, val bytes: Long, val isRestorePoint: Boolean)

    fun folder(ws: File) = File(ws, "UniReader/$DIR")

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { isLenient = false }

    fun name(at: Date, restorePoint: Boolean = false) =
        "$PREFIX${stamp().format(at)}${if (restorePoint) RESTORE_SUFFIX else ""}.$EXT"

    /** 认得出来才算一份备份（时间戳恰好 15 个字符，同 Mac 自己卡长度那条） */
    fun parse(fileName: String): Pair<Date, Boolean>? {
        if (!fileName.startsWith(PREFIX) || !fileName.endsWith(".$EXT")) return null
        var body = fileName.removePrefix(PREFIX).removeSuffix(".$EXT")
        var rp = false
        if (body.endsWith(RESTORE_SUFFIX)) { body = body.removeSuffix(RESTORE_SUFFIX); rp = true }
        if (body.length != 15) return null
        val d = runCatching { stamp().parse(body) }.getOrNull() ?: return null
        return d to rp
    }

    /** 全部认得出的备份，新的在前 */
    fun items(ws: File): List<Item> = (folder(ws).listFiles() ?: emptyArray()).mapNotNull { f ->
        val (d, rp) = parse(f.name) ?: return@mapNotNull null
        Item(f, d, f.length(), rp)
    }.sortedByDescending { it.date }

    // ---------- 保留策略（纯函数，Mac `BackupRetention.plan` 逐条照抄） ----------

    /** 返回要**删掉**的下标（按输入顺序）。`now` 之后的时间戳一律保留（删掉一份看不懂的备份比留着糟） */
    fun planDrop(dates: List<Date>, now: Date = Date(), recent: Int = 5, dailyDays: Int = 7, weeklyWeeks: Int = 4,
                 cal: Calendar = Calendar.getInstance()): List<Int> {
        if (dates.isEmpty()) return emptyList()
        val ordered = dates.indices.sortedByDescending { dates[it] }
        val keep = HashSet<Int>()
        ordered.take(maxOf(0, recent)).forEach { keep.add(it) }
        fun dayStart(d: Date): Long = (cal.clone() as Calendar).apply {
            time = d; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun weekStart(d: Date): Long = (cal.clone() as Calendar).apply {
            time = d; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            if (timeInMillis > d.time) add(Calendar.WEEK_OF_YEAR, -1)
        }.timeInMillis
        val dayFloor = (cal.clone() as Calendar).apply { timeInMillis = dayStart(now); add(Calendar.DAY_OF_MONTH, -(maxOf(0, dailyDays) - 1)) }.timeInMillis
        val days = HashSet<Long>()
        for (i in ordered) {
            val day = dayStart(dates[i])
            if (day < dayFloor && !dates[i].after(now)) continue
            if (days.add(day)) keep.add(i)
        }
        val weekFloor = (cal.clone() as Calendar).apply { timeInMillis = weekStart(now); add(Calendar.WEEK_OF_YEAR, -(maxOf(0, weeklyWeeks) - 1)) }.timeInMillis
        val weeks = HashSet<Long>()
        for (i in ordered) {
            val w = weekStart(dates[i])
            if (w < weekFloor && !dates[i].after(now)) continue
            if (weeks.add(w)) keep.add(i)
        }
        for (i in dates.indices) if (dates[i].after(now)) keep.add(i)
        return dates.indices.filter { it !in keep }
    }

    // ---------- 跑一次 / 稀释 / 还原 ----------

    /**
     * 做一份备份（**在库队列线程上调**：`VACUUM INTO` 在一个读事务里，不必停写）。[force] = 手动「立即备份」。
     * 返回新文件；节流跳过 / 本机 SQLite 不支持 `VACUUM INTO`（API < 30）/ 失败 = null。
     */
    fun run(s: LibraryStore, ws: File, force: Boolean, now: Date = Date()): File? {
        if (!force) {
            val last = items(ws).firstOrNull { !it.isRestorePoint }
            if (last != null && now.time - last.date.time < THROTTLE_MS) return null
        }
        val dir = folder(ws).apply { mkdirs() }
        val target = File(dir, name(now))
        if (target.exists()) return null   // 同一秒里来过了
        val tmp = File(dir, ".${java.util.UUID.randomUUID()}.tmp")
        return try {
            if (!s.vacuumInto(tmp.path)) { tmp.delete(); Log.w(TAG, "本机 SQLite 不支持 VACUUM INTO，跳过备份"); return null }
            if (!tmp.renameTo(target)) { tmp.delete(); throw java.io.IOException("改名失败") }
            Log.i(TAG, "${ws.name} → ${target.name}（${target.length() / 1024} KB）")
            prune(ws, now)
            target
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "备份失败 ${ws.name}", e)
            null
        }
    }

    /** 按保留策略删多余的（还原点不参与） */
    fun prune(ws: File, now: Date = Date()) {
        val all = items(ws).filter { !it.isRestorePoint }
        for (i in planDrop(all.map { it.date }, now)) {
            all[i].file.delete()
            Log.i(TAG, "稀释掉 ${all[i].file.name}")
        }
    }

    /** 还原前先给当前库另存一份还原点（永不被稀释）。在库线程上调（还开着的那条连接） */
    fun saveRestorePoint(s: LibraryStore, ws: File): File? {
        val dir = folder(ws).apply { mkdirs() }
        val point = File(dir, name(Date(), restorePoint = true))
        if (point.exists()) return point
        return if (s.vacuumInto(point.path)) point else null
    }

    /**
     * 换库文件：**调用方必须已经关掉这个工作区的全部连接**。`-wal` / `-shm` 一起删——留着的话 SQLite 会拿旧 WAL
     * 去「恢复」新库（同 Mac `BackupService.restore`）。先拷到临时名再改名。
     */
    fun replaceLibrary(ws: File, backup: File) {
        val db = File(ws, Workspace.DB_REL)
        val tmp = File(db.parentFile, ".${db.name}.restore")
        backup.copyTo(tmp, overwrite = true)
        for (side in listOf("", "-wal", "-shm")) File(db.path + side).delete()
        if (!tmp.renameTo(db)) throw java.io.IOException("换库失败：${tmp.path} → ${db.path}")
        Log.i(TAG, "已还原 ${ws.name} ← ${backup.name}")
    }
}
