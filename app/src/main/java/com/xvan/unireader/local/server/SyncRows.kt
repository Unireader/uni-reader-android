package com.xvan.unireader.local.server

import com.xvan.unireader.local.ImageAssets
import com.xvan.unireader.local.mirror.MirrorDiff
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.MirrorFp
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * 同步里「一行」的定位、读取、指纹与写入（`../docs/SERVER-MODE-PLAN.md §4.1`）。
 * 🔴 逐条照 Mac `../Sources/Sync/SyncRows.swift`，改一边同步另一边。
 *
 * 两类表：
 * - **三方判定的表** = [MirrorFp.specs]（单列 TEXT 主键，冲突规则见 [MirrorDiff]）；
 * - **通道表** = `ocr_page` / `image` / `page_align`，同离线镜像：OCR 与图片只补不删，扫描页对齐按 `updated_at` 取新。
 *   它们的「指纹」：OCR / 图片恒为空串（有就行），对齐 = `updated_at`。
 */
object SyncRows {

    const val OCR = "ocr_page"
    const val IMAGE = "image"
    const val ALIGN = "page_align"
    val channelTables = setOf(OCR, IMAGE, ALIGN)

    /** 图片待删除满这么久就不算「拿得出来」（同 Mac `LibraryStore.imagePurgeAfter`，30 天） */
    private val IMAGE_PURGE_AFTER: Duration = Duration.ofDays(30)

    fun isSynced(table: String): Boolean = MirrorFp.spec(table) != null || table in channelTables

    // ---------- OCR 的复合主键 ----------

    private const val SEP = '\u001F'

    /** `ocr_page` 是三列复合主键，同步里写成一个串：`content_hash␟page␟provider`（0x1F 分隔） */
    fun ocrRowId(k: MirrorDiff.OcrKey): String = "${k.contentHash}$SEP${k.page}$SEP${k.provider}"

    fun ocrKey(rowId: String): MirrorDiff.OcrKey? {
        val p = rowId.split(SEP)
        if (p.size != 3) return null
        val page = p[1].toIntOrNull() ?: return null
        return MirrorDiff.OcrKey(p[0], page, p[2])
    }

    // ---------- 读 ----------

    /** 读一行当前的内容；不在 = null。`meta` 只认同步白名单里的键 */
    fun fetch(db: Db, table: String, rowId: String): Map<String, Any?>? {
        MirrorFp.spec(table)?.let { spec ->
            if (table == "meta" && rowId !in MirrorFp.syncedMetaKeys) return null
            // 不逐行探表在不在（拉取时每行都要读一次）：走到这里的表要么本机有行，要么已经迁移过（[SyncClient.applyPulled]）
            return db.query("SELECT * FROM $table WHERE ${spec.key}=?", arrayOf(rowId)) { MirrorStore.readRow(it) }
                .firstOrNull()
        }
        return when (table) {
            OCR -> {
                val k = ocrKey(rowId) ?: return null
                db.query(
                    "SELECT * FROM ocr_page WHERE content_hash=? AND page=? AND provider=?",
                    arrayOf<Any?>(k.contentHash, k.page, k.provider),
                ) { MirrorStore.readRow(it) }.firstOrNull()
            }
            IMAGE -> db.query("SELECT * FROM image WHERE sha256=?", arrayOf(rowId)) { MirrorStore.readRow(it) }
                .firstOrNull()
            ALIGN -> {
                if (!MirrorStore.hasTable(db, ALIGN)) return null
                db.query("SELECT * FROM page_align WHERE content_hash=?", arrayOf(rowId)) { MirrorStore.readRow(it) }
                    .firstOrNull()
            }
            else -> null
        }
    }

    /** 批量读一张三方判定表里的若干行：`row_id → 行` */
    fun fetch(db: Db, spec: MirrorFp.TableSpec, ids: List<String>): Map<String, Map<String, Any?>> {
        val out = HashMap<String, Map<String, Any?>>()
        if (!MirrorStore.hasTable(db, spec.table)) return out
        val wanted = if (spec.table == "meta") ids.filter { it in MirrorFp.syncedMetaKeys } else ids
        for (chunk in wanted.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            db.query("SELECT * FROM ${spec.table} WHERE ${spec.key} IN ($marks)", chunk.toTypedArray()) {
                MirrorStore.readRow(it)
            }.forEach { row ->
                val id = MirrorFp.coerce(row[spec.key], MirrorFp.ColType.TEXT)
                if (id is MirrorFp.Value.Text) out[id.v] = row
            }
        }
        return out
    }

    /** 一行的指纹（行不在 = null）。三方判定表 = [MirrorFp]；OCR / 图片 = 空串；扫描页对齐 = `updated_at` */
    fun fingerprint(table: String, row: Map<String, Any?>?): String? {
        if (row == null) return null
        MirrorFp.spec(table)?.let { return MirrorFp.fingerprint(row, it) }
        if (table == ALIGN) return row["updated_at"] as? String ?: ""
        return ""
    }

    /**
     * 通道表当前的全部键 → 指纹：`表名 → (row_id → fp)`。
     * 图片只算**真拿得出来**的（行在、文件在、没有待删除到期，同 Mac `MirrorStore.imageKeys`）。
     */
    fun channelState(db: Db, folder: File): Map<String, Map<String, String>> = mapOf(
        OCR to MirrorStore.ocrKeys(db).associate { ocrRowId(it) to "" },
        IMAGE to imageKeys(db, folder).associateWith { "" },
        ALIGN to MirrorStore.alignStamps(db),
    )

    fun imageKeys(db: Db, folder: File, now: Instant = Instant.now()): Set<String> {
        if (!MirrorStore.hasTable(db, IMAGE)) return emptySet()
        val cutoff = Iso.string(now.minus(IMAGE_PURGE_AFTER))
        return db.query(
            "SELECT sha256, ext FROM image WHERE orphaned_at IS NULL OR orphaned_at >= ?",
            arrayOf(cutoff),
        ) { c -> c.getString(0) to c.getString(1) }
            .filter { (sha, ext) -> sha != null && ext != null && ImageAssets.file(folder, sha, ext).isFile }
            .map { it.first }
            .toSet()
    }

    /** 三方判定表当前的全部行指纹：`表名 → (row_id → fp)`（`meta` 只取白名单键） */
    fun specState(db: Db): Map<String, Map<String, String>> =
        MirrorFp.specs.associate { it.table to MirrorStore.fingerprints(db, it) }

    // ---------- 写（通道表） ----------

    enum class WriteMode { IGNORE_IF_PRESENT, REPLACE }

    /**
     * 按**目标库实际有的列**写一行（同 `MirrorApply.write` 的口径：两端 schema 可能差一格）。
     * 返回是否真的写进去了（IGNORE_IF_PRESENT 时已存在 = false）。
     */
    fun writeChannelRow(db: Db, table: String, row: Map<String, Any?>, mode: WriteMode): Boolean {
        if (table !in channelTables || !MirrorStore.hasTable(db, table)) return false
        val cols = db.query("PRAGMA table_info($table)") { it.getString(it.getColumnIndexOrThrow("name")) }
        val use = cols.filter { row.containsKey(it) }
        if (use.isEmpty()) return false
        val rowId = channelRowId(table, row) ?: return false
        if (mode == WriteMode.IGNORE_IF_PRESENT && fetch(db, table, rowId) != null) return false
        val verb = if (mode == WriteMode.REPLACE) "INSERT OR REPLACE" else "INSERT OR IGNORE"
        db.exec(
            "$verb INTO $table(${use.joinToString(",")}) VALUES(${use.joinToString(",") { "?" }})",
            use.map { row[it] }.toTypedArray(),
        )
        return true
    }

    /** 通道表一行的 row_id（与 [channelState] / [fetch] 同一口径） */
    fun channelRowId(table: String, row: Map<String, Any?>): String? = when (table) {
        OCR -> {
            val h = row["content_hash"] as? String
            val p = row["page"] as? Long
            val pr = row["provider"] as? String
            if (h == null || p == null || pr == null) null else ocrRowId(MirrorDiff.OcrKey(h, p.toInt(), pr))
        }
        IMAGE -> row["sha256"] as? String
        ALIGN -> row["content_hash"] as? String
        else -> null
    }
}
