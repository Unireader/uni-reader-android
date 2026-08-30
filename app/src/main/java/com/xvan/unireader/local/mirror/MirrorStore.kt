package com.xvan.unireader.local.mirror

import android.content.Context
import android.database.Cursor
import com.xvan.unireader.local.store.Db
import com.xvan.unireader.local.store.MirrorFp
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 离线镜像的**记账层**：`sync_base` 基线表、血缘 meta、借出记录的编解码。
 * 方案 `../OFFLINE-MIRROR-PLAN.md` §3/§5；建镜像的文件级流程在 [MirrorBuilder]。
 *
 * 🔴 **跨端契约**：表结构、meta 键名、借出记录的 JSON 形态都写在同一个工作区的库里，
 * 与 Mac `Sources/Store/MirrorStore.swift` 必须一致，改一边同步另一边。
 */
object MirrorStore {

    // ---------- meta 键（镜像库侧） ----------

    /** 源工作区的 `workspace_id`。**镜像认源盘的唯一判据**——名字会改、路径必变 */
    const val META_MIRROR_OF = "mirror_of"

    /** 本镜像的 UUID。源库的借出记录靠它对上号（一个源可以有多份镜像） */
    const val META_MIRROR_ID = "mirror_id"
    const val META_MIRROR_CREATED_AT = "mirror_created_at"

    /**
     * 「上次见到源盘时它在哪」。**只用来给一句人话提示**（"把那块 XXX 盘插上"），
     * 绝不作为判据——判据永远是 [META_MIRROR_OF]。
     */
    const val META_MIRROR_SOURCE_HINT = "mirror_source_hint"
    const val META_MIRROR_LAST_SYNCED_AT = "mirror_last_synced_at"

    // ---------- meta 键（源库侧） ----------

    /**
     * 借出记录 JSON 数组。**它是信息不是锁**（方案 §5.2）：打开一个有记录的工作区
     * 只显示一行「有 N 份离线镜像」，不阻塞任何操作。
     */
    const val META_CHECKOUTS = "offline_checkouts"

    /** 一条借出记录。JSON 键名**与 Mac 逐字一致**（同一个库两端都要读） */
    data class Checkout(
        val mirrorId: String,
        val deviceId: String,
        val deviceName: String,
        val takenAt: String,
        val lastSyncedAt: String?,
        val noteCount: Int,
    )

    /** 坏 JSON 按空处理：这是展示用的元数据，不该让一条脏记录挡住开工作区 */
    fun decodeCheckouts(json: String?): List<Checkout> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Checkout(
                    mirrorId = o.optString("mirror_id"),
                    deviceId = o.optString("device_id"),
                    deviceName = o.optString("device_name"),
                    takenAt = o.optString("taken_at"),
                    lastSyncedAt = if (o.isNull("last_synced_at")) null else o.optString("last_synced_at"),
                    noteCount = o.optInt("note_count"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 编码。两处**必须与 Mac 逐字对齐**（同一个库两端都要读写，`spike/mirror-checkout-vector.json`
     * 是共同凭据）：
     * ① **键按字典序**输出 —— 对齐 `JSONEncoder.outputFormatting = .sortedKeys`；
     * ② `lastSyncedAt` 为 null 时**整个键省略**，而不是写 `null` —— 这是 Swift `JSONEncoder`
     *    对 nil Optional 的默认行为。写成 `"last_synced_at":null` 两端字节就对不上了。
     */
    fun encodeCheckouts(list: List<Checkout>): String {
        val arr = JSONArray()
        for (c in list) {
            // JSONObject(Map) 保留插入顺序，故手工按字典序放
            val m = LinkedHashMap<String, Any?>()
            m["device_id"] = c.deviceId
            m["device_name"] = c.deviceName
            if (c.lastSyncedAt != null) m["last_synced_at"] = c.lastSyncedAt
            m["mirror_id"] = c.mirrorId
            m["note_count"] = c.noteCount
            m["taken_at"] = c.takenAt
            arr.put(JSONObject(m))
        }
        return arr.toString()
    }

    /** 追加/替换一条借出记录（同 [Checkout.mirrorId] 覆盖，不越攒越多） */
    fun upsertCheckout(list: List<Checkout>, c: Checkout): List<Checkout> =
        list.filterNot { it.mirrorId == c.mirrorId } + c

    // ---------- 本机身份 ----------

    private const val PREFS = "mirror"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * 本机的稳定 id（**不进工作区**，存 SharedPreferences）。
     * 只用来在借出记录里区分「哪台设备借走的」，不需要真硬件 ID。
     */
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun deviceName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    // ---------- sync_base（只在镜像库里存在） ----------

    /**
     * 基线表：建镜像那一刻每行的指纹。合并时重算一遍就能判出新增/删除/修改（方案 §3.1）。
     *
     * **源库永远不认识这张表**——它只是镜像的私有记账，不进跨端 schema 契约、不占 schema 版本号，
     * 所以也**不在 [com.xvan.unireader.local.store.Schema] 里**（那份是新建工作区的建表语句）。
     */
    const val SYNC_BASE_DDL = """
    CREATE TABLE IF NOT EXISTS sync_base (
      tbl TEXT NOT NULL,
      row_id TEXT NOT NULL,
      fp TEXT NOT NULL,
      PRIMARY KEY (tbl, row_id)
    ) WITHOUT ROWID;
    """

    /**
     * 重算并覆盖整张基线表，返回记下的行数。
     *
     * **整表重算而不是增量维护**：这正是指纹方案胜过 oplog 的地方——不依赖「从建镜像起每次写都被
     * 记上」这个连续性假设，任何时候重跑一遍都得到正确的基线（方案 §3.2）。
     */
    fun rebuildSyncBase(db: Db): Int {
        db.exec(SYNC_BASE_DDL.trimIndent())
        var rows = 0
        db.transaction {
            db.exec("DELETE FROM sync_base")
            for (spec in MirrorFp.specs) {
                for ((rowId, fp) in fingerprints(db, spec)) {
                    db.exec(
                        "INSERT INTO sync_base(tbl,row_id,fp) VALUES(?,?,?)",
                        arrayOf<Any?>(spec.table, rowId, fp),
                    )
                    rows++
                }
            }
        }
        return rows
    }

    /**
     * 一张表当前的 `row_id → fp`。`meta` 只取同步白名单里的键（方案 §4）——
     * `schema_version`/`open_documents`/`mirror_*` 这些进了基线，同步时就会互相覆盖对方的本机状态。
     */
    fun fingerprints(db: Db, spec: MirrorFp.TableSpec): Map<String, String> {
        var rows = db.query("SELECT * FROM ${spec.table}") { readRow(it) }
        if (spec.table == "meta") {
            rows = rows.filter { (it["key"] as? String) in MirrorFp.syncedMetaKeys }
        }
        return MirrorFp.fingerprints(rows, spec)
    }

    /** 读回基线：`表名 → (row_id → fp)`。表不存在（不是镜像）时返回空 */
    fun syncBase(db: Db): Map<String, Map<String, String>> {
        if (!hasSyncBase(db)) return emptyMap()
        val out = HashMap<String, HashMap<String, String>>()
        db.query("SELECT tbl,row_id,fp FROM sync_base") {
            Triple(it.getString(0), it.getString(1), it.getString(2))
        }.forEach { (t, id, fp) -> out.getOrPut(t) { HashMap() }[id] = fp }
        return out
    }

    fun hasSyncBase(db: Db): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sync_base'") { it.getString(0) }
            .isNotEmpty()

    /**
     * 把当前行读成 `列名 → 原始值`，**按游标报的存储类取**（不按声明类型猜）。
     * 归一化交给 [MirrorFp.coerce]——那一层才知道每列的声明类型，两端也只在那里对齐。
     */
    fun readRow(c: Cursor): Map<String, Any?> {
        val m = HashMap<String, Any?>(c.columnCount)
        for (i in 0 until c.columnCount) {
            m[c.getColumnName(i)] = when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                else -> c.getString(i)
            }
        }
        return m
    }
}
