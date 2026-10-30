package com.xvan.unireader.local.server

import com.xvan.unireader.local.store.MirrorFp
import java.io.ByteArrayOutputStream

/**
 * 连接服务器的同步线格式（`../PROTOCOL.md §9`）：push / pull 的 HTTP 请求体与响应体。
 *
 * 🔴 **跨端契约**：与 Mac / 服务器共用的 `../Sources/Sync/SyncWire.swift` 字节一致。跨端向量
 * `../spike/sync-wire-vectors.txt`（Mac `spike/sync-wire-test.swift` 生成），本端 `SyncWireTest` 逐字节比对。
 * 改一边必须同步另一边。
 *
 * 纯 Kotlin，不碰安卓 API（JVM 单测能跑）。修订号是 u64，这里用 [Long] 装（实际用不到 2^63）。
 * 值沿用 [MirrorFp.Value]（标签与指纹那套同值）。REAL 原样搬运位模式，**不做任何规格化**（与指纹不同）。
 */
object SyncWire {

    const val VERSION = 1

    enum class Op(val raw: Int) { UPSERT(0), DELETE(1) }

    /** 客户端推上去的一行改动。[baseFp] = 这一行在本机基线里的指纹，null = 基线里没有（本机新增）；[row] 只在 upsert 时有 */
    data class RowChange(
        val table: String,
        val rowId: String,
        val op: Op,
        val baseFp: String?,
        val row: Map<String, MirrorFp.Value>?,
    )

    data class PushRequest(val schemaVersion: Int, val changes: List<RowChange>)

    /** 服务器怎么处理了推上去的某一行（↔ Swift `SyncWire.Outcome`） */
    enum class Outcome(val raw: Int) {
        APPLIED(0), KEPT_SERVER(1), SKIPPED_ORPHAN(2), SKIPPED_CLASH(3), UNCHANGED(4), MISSING_FILE(5),
    }

    data class RowResult(val table: String, val rowId: String, val outcome: Outcome, val rev: Long)

    enum class ConflictKind(val raw: Int) { BOTH_MODIFIED(0), DELETE_VS_EDIT(1), BOTH_ADDED(2) }

    data class Conflict(val table: String, val rowId: String, val kind: ConflictKind, val keptServer: Boolean)

    /** [serverRows] = outcome 是 KEPT_SERVER 的那些行在服务器上的现状，客户端按「拉下来的行」处理 */
    data class PushResponse(
        val head: Long,
        val results: List<RowResult>,
        val conflicts: List<Conflict>,
        val serverRows: List<PulledRow> = emptyList(),
    )

    data class PulledRow(
        val table: String,
        val rowId: String,
        val rev: Long,
        val deleted: Boolean,
        val row: Map<String, MirrorFp.Value>?,
    )

    data class PullResponse(val head: Long, val more: Boolean, val rows: List<PulledRow>)

    class DecodeError(msg: String) : Exception(msg)

    // ---------- 与 SQLite 行互转 ----------

    /**
     * 游标按存储类读出来的值（`MirrorStore.readRow`：String / Long / Double / ByteArray / null）→ 线上的值。
     * **按实际存储类搬运，不按列的声明类型归一**：服务器写回去的字节要和本机库里的一模一样。
     */
    fun value(raw: Any?): MirrorFp.Value = when (raw) {
        is String -> MirrorFp.Value.Text(raw)
        is Long -> MirrorFp.Value.Int64(raw)
        is Int -> MirrorFp.Value.Int64(raw.toLong())
        is Double -> MirrorFp.Value.Real(raw)
        is Float -> MirrorFp.Value.Real(raw.toDouble())
        is ByteArray -> MirrorFp.Value.Blob(raw)
        else -> MirrorFp.Value.Null
    }

    fun row(raw: Map<String, Any?>): Map<String, MirrorFp.Value> = raw.mapValues { value(it.value) }

    /** 线上的一行 → `MirrorDiff` / `MirrorApply` 认的形态（同 `MirrorStore.readRow` 的输出） */
    fun sqliteRow(row: Map<String, MirrorFp.Value>): Map<String, Any?> = row.mapValues { (_, v) ->
        when (v) {
            is MirrorFp.Value.Null -> null
            is MirrorFp.Value.Int64 -> v.v
            is MirrorFp.Value.Real -> v.v
            is MirrorFp.Value.Text -> v.v
            is MirrorFp.Value.Blob -> v.v
        }
    }

    // ---------- 编码 ----------

    fun encode(m: PushRequest): ByteArray = Writer().apply {
        u8(VERSION)
        u32(m.schemaVersion.toLong())
        u32(m.changes.size.toLong())
        for (c in m.changes) {
            str(c.table); str(c.rowId); u8(c.op.raw)
            if (c.baseFp != null) { u8(1); str(c.baseFp) } else u8(0)
            if (c.op == Op.UPSERT) row(c.row ?: emptyMap())
        }
    }.bytes()

    fun encode(m: PushResponse): ByteArray = Writer().apply {
        u8(VERSION)
        u64(m.head)
        u32(m.results.size.toLong())
        for (r in m.results) { str(r.table); str(r.rowId); u8(r.outcome.raw); u64(r.rev) }
        u32(m.conflicts.size.toLong())
        for (c in m.conflicts) { str(c.table); str(c.rowId); u8(c.kind.raw); u8(if (c.keptServer) 1 else 0) }
        pulledRows(m.serverRows)
    }.bytes()

    fun encode(m: PullResponse): ByteArray = Writer().apply {
        u8(VERSION)
        u64(m.head)
        u8(if (m.more) 1 else 0)
        pulledRows(m.rows)
    }.bytes()

    // ---------- 解码 ----------

    fun decodePushRequest(data: ByteArray): PushRequest {
        val r = Reader(data)
        r.expectVersion()
        val schema = r.u32().toInt()
        val n = r.u32()
        val changes = ArrayList<RowChange>(minOf(n, 100_000L).toInt())
        for (i in 0 until n) {
            val table = r.str()
            val rowId = r.str()
            val rawOp = r.u8()
            val op = Op.entries.firstOrNull { it.raw == rawOp } ?: throw DecodeError("bad op $rawOp")
            val base = if (r.u8() == 1) r.str() else null
            val row = if (op == Op.UPSERT) r.row() else null
            changes.add(RowChange(table, rowId, op, base, row))
        }
        r.expectEnd()
        return PushRequest(schema, changes)
    }

    fun decodePushResponse(data: ByteArray): PushResponse {
        val r = Reader(data)
        r.expectVersion()
        val head = r.u64()
        val results = ArrayList<RowResult>()
        for (i in 0 until r.u32()) {
            val table = r.str()
            val rowId = r.str()
            val raw = r.u8()
            val outcome = Outcome.entries.firstOrNull { it.raw == raw } ?: throw DecodeError("bad outcome $raw")
            results.add(RowResult(table, rowId, outcome, r.u64()))
        }
        val conflicts = ArrayList<Conflict>()
        for (i in 0 until r.u32()) {
            val table = r.str()
            val rowId = r.str()
            val raw = r.u8()
            val kind = ConflictKind.entries.firstOrNull { it.raw == raw } ?: throw DecodeError("bad kind $raw")
            conflicts.add(Conflict(table, rowId, kind, r.u8() == 1))
        }
        val serverRows = r.pulledRows()
        r.expectEnd()
        return PushResponse(head, results, conflicts, serverRows)
    }

    fun decodePullResponse(data: ByteArray): PullResponse {
        val r = Reader(data)
        r.expectVersion()
        val head = r.u64()
        val more = r.u8() == 1
        val rows = r.pulledRows()
        r.expectEnd()
        return PullResponse(head, more, rows)
    }

    // ---------- 读写器（全部小端） ----------

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun bytes(): ByteArray = out.toByteArray()

        fun u8(v: Int) = out.write(v and 0xff)
        fun u16(v: Int) = le(v.toLong(), 2)
        fun u32(v: Long) = le(v, 4)
        fun u64(v: Long) = le(v, 8)

        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            require(b.size <= 0xffff) { "SyncWire.str 超长：${b.size} 字节" }
            u16(b.size)
            out.write(b)
        }

        fun bytes32(b: ByteArray) {
            u32(b.size.toLong())
            out.write(b)
        }

        fun value(v: MirrorFp.Value) {
            when (v) {
                is MirrorFp.Value.Null -> u8(MirrorFp.TAG_NULL.toInt())
                is MirrorFp.Value.Int64 -> { u8(MirrorFp.TAG_INT.toInt()); le(v.v, 8) }
                is MirrorFp.Value.Real -> { u8(MirrorFp.TAG_REAL.toInt()); le(java.lang.Double.doubleToRawLongBits(v.v), 8) }
                is MirrorFp.Value.Text -> { u8(MirrorFp.TAG_TEXT.toInt()); bytes32(v.v.toByteArray(Charsets.UTF_8)) }
                is MirrorFp.Value.Blob -> { u8(MirrorFp.TAG_BLOB.toInt()); bytes32(v.v) }
            }
        }

        /** 列名按字典序（列名都是 ASCII，与 Swift `keys.sorted()` 同序） */
        fun row(r: Map<String, MirrorFp.Value>) {
            u16(r.size)
            for (k in r.keys.sorted()) { str(k); value(r.getValue(k)) }
        }

        fun pulledRows(rows: List<PulledRow>) {
            u32(rows.size.toLong())
            for (r in rows) {
                str(r.table); str(r.rowId); u64(r.rev); u8(if (r.deleted) 1 else 0)
                if (!r.deleted) row(r.row ?: emptyMap())
            }
        }

        private fun le(v: Long, n: Int) {
            for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xff).toInt())
        }
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun expectVersion() {
            val v = u8()
            if (v != VERSION) throw DecodeError("bad version $v")
        }

        fun expectEnd() {
            if (pos != b.size) throw DecodeError("trailing bytes")
        }

        fun u8(): Int {
            if (pos >= b.size) throw DecodeError("truncated")
            return b[pos++].toInt() and 0xff
        }

        fun u16(): Int = le(2).toInt()
        fun u32(): Long = le(4)
        fun u64(): Long = le(8)

        fun str(): String = utf8(take(u16()))

        fun value(): MirrorFp.Value = when (val tag = u8()) {
            MirrorFp.TAG_NULL.toInt() -> MirrorFp.Value.Null
            MirrorFp.TAG_INT.toInt() -> MirrorFp.Value.Int64(le(8))
            MirrorFp.TAG_REAL.toInt() -> MirrorFp.Value.Real(java.lang.Double.longBitsToDouble(le(8)))
            MirrorFp.TAG_TEXT.toInt() -> MirrorFp.Value.Text(utf8(take(len32())))
            MirrorFp.TAG_BLOB.toInt() -> MirrorFp.Value.Blob(take(len32()))
            else -> throw DecodeError("bad tag $tag")
        }

        fun row(): Map<String, MirrorFp.Value> {
            val out = LinkedHashMap<String, MirrorFp.Value>()
            repeat(u16()) {
                val k = str()
                out[k] = value()
            }
            return out
        }

        fun pulledRows(): List<PulledRow> {
            val rows = ArrayList<PulledRow>()
            for (i in 0 until u32()) {
                val table = str()
                val rowId = str()
                val rev = u64()
                val deleted = u8() == 1
                rows.add(PulledRow(table, rowId, rev, deleted, if (deleted) null else row()))
            }
            return rows
        }

        private fun len32(): Int {
            val n = u32()
            if (n > Int.MAX_VALUE) throw DecodeError("truncated")
            return n.toInt()
        }

        private fun take(n: Int): ByteArray {
            if (n < 0 || pos + n > b.size) throw DecodeError("truncated")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        private fun utf8(s: ByteArray): String {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            return try {
                dec.decode(java.nio.ByteBuffer.wrap(s)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw DecodeError("bad utf8")
            }
        }

        private fun le(n: Int): Long {
            val s = take(n)
            var v = 0L
            for (i in 0 until n) v = v or ((s[i].toLong() and 0xff) shl (8 * i))
            return v
        }
    }
}
