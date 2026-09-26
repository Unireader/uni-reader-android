package com.xvan.unireader.local.store

import java.security.MessageDigest

/**
 * 离线镜像三方合并的**行指纹**（方案见 `../OFFLINE-MIRROR-PLAN.md` §3）。
 *
 * 用途：镜像库里的 `sync_base(tbl,row_id,fp)` 记下「建镜像那一刻」每行的样子；合并时重算一遍
 * 就能判出新增 / 删除 / 修改，**不需要墓碑表、不需要改任何写路径、不需要动 schema 契约**。
 *
 * 🔴 **跨端契约：与 Mac `Sources/Store/MirrorFingerprint.swift` 必须字节一致**，改一边必须同步另一边。
 * 向量表 `../spike/mirror-fp-vectors.txt` 由 Mac 侧的 `spike/mirror-fp-test.swift` 生成，
 * 本端 `MirrorFpTest` 逐条比对——两端算出的 fp 差一个 bit，合并时整张表就会被误判成「全都改过」，
 * 干跑预览里刷出几万条，等于这功能废了。
 *
 * ## 编码
 *
 * 每列按 [enc] 编码，列间以 `0x1F` 分隔，整串 SHA-256，取**前 16 个 hex 字符**。
 * ```
 * enc(NULL)    = 0x00
 * enc(INTEGER) = 0x01 + 十进制 ASCII（含负号）
 * enc(REAL)    = 0x02 + IEEE754 双精度 8 字节【小端】原始字节
 * enc(TEXT)    = 0x03 + UTF-8 原文
 * enc(BLOB)    = 0x04 + 原字节
 * ```
 * - **REAL 走原始字节而不是十进制文本**：`%.17g` 这类格式化在两端的实现不保证逐字符一致，
 *   而 fp 差一个字符就是全表误判。位模式是 IEEE754 定死的，没有解释空间。
 * - **首字节是类型标签**，编码因此是单射的：NULL/空 TEXT/空 BLOB、`int 1`/`text "1"` 都不会撞。
 * - 值按**列的声明类型**强制归一（见 [coerce]），不看它在库里实际存成了什么存储类：
 *   同一个 `read_zoom=1`，一端绑 Double 存成 REAL、另一端绑 Int 存成 INTEGER，
 *   不归一就是「明明没改却判成改了」。
 */
object MirrorFp {

    // ---------- 表规格 ----------

    enum class ColType { INT, REAL, TEXT, BLOB }

    data class Column(val name: String, val type: ColType)

    /**
     * 一张参与同步的表：主键列 + 参与指纹的列（**顺序即契约**）+ 冲突时按哪一列判新旧。
     *
     * [lww]：两端都改了同一行时，按这一列的 ISO-8601 时间戳取新的（方案 §6）。
     * null = 这张表没有时间戳列，冲突一律**保留源盘那份**并报告。
     * 放在表规格里而不是另起一张映射表：这一列就在 [columns] 里躺着，分开写迟早出现
     * 「加了 updated_at 却忘了登记 LWW」。
     */
    data class TableSpec(
        val table: String,
        val key: String,
        val columns: List<Column>,
        val lww: String? = null,
    )

    private fun i(n: String) = Column(n, ColType.INT)
    private fun r(n: String) = Column(n, ColType.REAL)
    private fun t(n: String) = Column(n, ColType.TEXT)
    private fun b(n: String) = Column(n, ColType.BLOB)

    /**
     * 🔴 **列顺序是写死的，不许改成读 `PRAGMA table_info`。**
     * 那个顺序对「全新建的 v12 库」和「v1 一路 ALTER 上来的 v12 库」**是不一样的**
     * （`ADD COLUMN` 永远追加在末尾），拿它当契约 = 同一份数据在两台机器上算出两个 fp。
     * 这里的顺序 = 全新库 `CREATE TABLE` 里的顺序（[Schema] 建出来的那个，也是 Mac 的）。
     *
     * 表的取舍见方案 §4：`location` 是设备本地事实**绝不同步**、
     * `ocr_page` 纯 additive 走 `INSERT OR IGNORE` 不需要 base。
     */
    val specs: List<TableSpec> = listOf(
        TableSpec(
            "document", "id",
            listOf(
                t("id"), t("title"), i("page_count"), t("added_at"), i("sort_order"),
                i("read_page"), r("read_frac"), r("read_zoom"), r("read_hfrac"),
                t("group_name"), i("canvas_mode"),
                // ⚠️ `last_opened_at` **刻意不在这里**（方案 §4）：进了指纹的话，「在镜像上翻开过这本书」
                // 就会把整行标记成「改过」，干跑预览里满屏都是无意义条目。合并时无条件取 max 即可。
            ),
            // 🔴 用 `last_opened_at` 当 LWW 依据（2026-09-01，与 Mac 同改）。这张表**没有**
            // `updated_at`，原先 lww=null ⇒ 落到「没有时间戳可比，保留硬盘那份」——于是
            // **在离线副本上读到哪儿会被静默丢弃**。`last_opened_at` 是 NOT NULL 一定有值，
            // 语义也正好：谁最后打开过这本书，谁那份进度就是更近的那次阅读的结果。
            lww = "last_opened_at",
        ),
        TableSpec(
            "variant", "id",
            listOf(t("id"), t("document_id"), t("content_hash"), i("page_count"), t("added_at")),
        ),
        TableSpec(
            "note", "id",
            listOf(
                t("id"), t("document_id"), i("kind"), i("page"),
                r("anchor_x"), r("anchor_y"), r("anchor_w"), r("anchor_h"),
                b("payload"), t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        TableSpec(
            "ink_layer", "id",
            listOf(
                t("id"), t("document_id"), t("name"), t("color_key"),
                i("sort_order"), i("visible"), t("created_at"),
            ),
        ),
        TableSpec(
            "scratch_pad", "id",
            listOf(
                t("id"), t("document_id"), t("title"), i("anchor_page"),
                r("anchor_x"), r("anchor_y"), t("bg"), t("pattern"), i("show_page"),
                t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        // v15：Markdown 笔记的**元数据行**（同 Mac，2026-09-26 起两端表集合对齐）。本端没有 Markdown 笔记，
        // 也不复制 `Notes/` 下的正文文件——只是让这张表的行在镜像两侧照常合并（两侧谁改过元数据都不丢）。
        // `last_opened_at` 同 `document`：刻意不进指纹。
        TableSpec(
            "md_doc", "id",
            listOf(
                t("id"), t("title"), t("rel_path"), t("group_name"), i("sort_order"),
                t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        // v16：画板笔记（`../BOARD-NOTE-PLAN.md §6`）。父表在前（`board_item.board_id` 指向它），两张都按
        // `updated_at` 取新；`board_item` 一条一行，两边各加的笔迹 / 图自然并起来。
        // `last_opened_at` 同 `document`：刻意不进指纹（翻开过一次就满屏「改过」）。列顺序与 Mac 逐字一致。
        TableSpec(
            "board_note", "id",
            listOf(
                t("id"), t("title"), t("bg"), t("pattern"),
                t("group_name"), t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        // v17：分页画板的页（`../BOARD-NOTE-PLAN.md §9`），在 board_note 之后、board_item 之前（外键序，同 Mac）
        TableSpec(
            "board_page", "id",
            listOf(
                t("id"), t("board_id"), r("sort_key"), r("width"), r("height"), t("template"),
                t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        TableSpec(
            "board_item", "id",
            listOf(
                t("id"), t("board_id"), i("kind"),
                r("x"), r("y"), r("w"), r("h"),
                b("payload"), t("created_at"), t("updated_at"),
            ),
            lww = "updated_at",
        ),
        TableSpec("meta", "key", listOf(t("key"), t("value"))),
    )

    fun spec(table: String): TableSpec? = specs.firstOrNull { it.table == table }

    /**
     * `meta` 表里**参与同步**的键（方案 §4）。`meta` 是个杂物袋：既有工作区级配置（该同步），
     * 也有本机状态与库自身属性（绝不能同步）。所以它跟别的表不一样，除了列规格还要一张键白名单。
     *
     * 刻意在外的：`schema_version`/`created_at`（库自身属性）、`open_documents`（本机开着哪几篇）、
     * `workspace_id`/`mirror_*`/`offline_checkouts`（血缘元数据 —— 同步它们就是让两边互相
     * 把对方的身份覆盖掉）。
     */
    val syncedMetaKeys: Set<String> = setOf("workspace_name", "note_types")

    // ---------- 值与编码 ----------

    sealed class Value {
        object Null : Value()
        data class Int64(val v: Long) : Value()
        data class Real(val v: Double) : Value()
        data class Text(val v: String) : Value()
        class Blob(val v: ByteArray) : Value() {
            override fun equals(other: Any?) = other is Blob && v.contentEquals(other.v)
            override fun hashCode() = v.contentHashCode()
        }
    }

    const val SEPARATOR: Byte = 0x1F

    /** 类型标签（编码首字节）。**跨端契约，值不许改。** */
    const val TAG_NULL: Byte = 0x00
    const val TAG_INT: Byte = 0x01
    const val TAG_REAL: Byte = 0x02
    const val TAG_TEXT: Byte = 0x03
    const val TAG_BLOB: Byte = 0x04

    fun enc(v: Value): ByteArray = when (v) {
        is Value.Null -> byteArrayOf(TAG_NULL)
        is Value.Int64 -> byteArrayOf(TAG_INT) + v.v.toString().toByteArray(Charsets.UTF_8)
        is Value.Real -> byteArrayOf(TAG_REAL) + le8(canonical(v.v))
        is Value.Text -> byteArrayOf(TAG_TEXT) + v.v.toByteArray(Charsets.UTF_8)
        is Value.Blob -> byteArrayOf(TAG_BLOB) + v.v
    }

    /**
     * 规格化浮点：`-0.0` 归 `+0.0`、任何 NaN 归同一个位模式。
     * 两端在这两处的位模式本来就不保证一致，而它们**语义上没有区别**——不归一就是无谓的全表误判。
     */
    fun canonical(d: Double): Double = when {
        d.isNaN() -> java.lang.Double.longBitsToDouble(0x7ff8000000000000L)
        d == 0.0 -> 0.0     // -0.0 == 0.0 为真，于是这一句把 -0.0 也拍平
        else -> d
    }

    fun le8(d: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(d)
        return ByteArray(8) { ((bits ushr (8 * it)) and 0xFF).toByte() }
    }

    /** 一行的指纹：各列 [enc] 以 `0x1F` 相连 → SHA-256 → 前 16 个 hex 字符 */
    fun fingerprint(values: List<Value>): String {
        val md = MessageDigest.getInstance("SHA-256")
        values.forEachIndexed { idx, v ->
            if (idx > 0) md.update(SEPARATOR)
            md.update(enc(v))
        }
        val d = md.digest()
        return buildString(16) { for (k in 0 until 8) append("%02x".format(d[k])) }
    }

    // ---------- 从查询结果取值 ----------

    /**
     * 把一行的原始值（`String`/`Long`/`Double`/`ByteArray`/null）按 spec 的**声明类型**归一。
     * 缺列（null）按 NULL 处理，老库少一列时不炸。
     */
    fun coerce(raw: Any?, type: ColType): Value {
        if (raw == null) return Value.Null
        return when (type) {
            ColType.INT -> when (raw) {
                is Long -> Value.Int64(raw)
                is Int -> Value.Int64(raw.toLong())
                is Double -> Value.Int64(raw.toLong())
                is Float -> Value.Int64(raw.toLong())
                is String -> Value.Int64(raw.toLongOrNull() ?: 0L)
                is ByteArray -> Value.Int64(String(raw, Charsets.UTF_8).toLongOrNull() ?: 0L)
                else -> Value.Null
            }
            ColType.REAL -> when (raw) {
                is Double -> Value.Real(raw)
                is Float -> Value.Real(raw.toDouble())
                is Long -> Value.Real(raw.toDouble())
                is Int -> Value.Real(raw.toDouble())
                is String -> Value.Real(raw.toDoubleOrNull() ?: 0.0)
                is ByteArray -> Value.Real(String(raw, Charsets.UTF_8).toDoubleOrNull() ?: 0.0)
                else -> Value.Null
            }
            ColType.TEXT -> when (raw) {
                is String -> Value.Text(raw)
                is Long -> Value.Text(raw.toString())
                is Int -> Value.Text(raw.toString())
                is Double -> Value.Text(raw.toString())
                is ByteArray -> Value.Text(String(raw, Charsets.UTF_8))
                else -> Value.Null
            }
            ColType.BLOB -> when (raw) {
                is ByteArray -> Value.Blob(raw)
                is String -> Value.Blob(raw.toByteArray(Charsets.UTF_8))
                else -> Value.Null
            }
        }
    }

    /** 一行（列名 → 原始值）按 spec 算指纹 */
    fun fingerprint(row: Map<String, Any?>, spec: TableSpec): String =
        fingerprint(spec.columns.map { coerce(row[it.name], it.type) })

    /**
     * `document` 里纯粹表示「读到哪儿」的列。
     *
     * 两端各翻过同一本书这几列就都会变 —— 那是**正常使用**，不是冲突。按 `last_opened_at`
     * 取最近读过的那次即可，不该弹到用户面前让他裁决。除这几列之外还有差异，才是真冲突。
     */
    val progressColumns: Set<String> = setOf("read_page", "read_frac", "read_zoom", "read_hfrac")

    /** 忽略掉某些列之后这一行的指纹，用来判断「两端的差异是不是只在那几列上」 */
    fun fingerprint(row: Map<String, Any?>, spec: TableSpec, ignoring: Set<String>): String =
        fingerprint(spec.columns.filter { it.name !in ignoring }.map { coerce(row[it.name], it.type) })

    /** 整表：`row_id → fp`。[rows] 用 `SELECT *` 的结果即可（列多了不影响，按 spec 取） */
    fun fingerprints(rows: List<Map<String, Any?>>, spec: TableSpec): Map<String, String> {
        val out = LinkedHashMap<String, String>(rows.size)
        for (row in rows) {
            val id = coerce(row[spec.key], ColType.TEXT)
            if (id !is Value.Text) continue
            out[id.v] = fingerprint(row, spec)
        }
        return out
    }
}
