package com.xvan.unireader.local.server

import com.xvan.unireader.local.store.MirrorFp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 连接服务器的同步线格式（`../docs/PROTOCOL.md §9`）跨端一致性：照 Mac `spike/sync-wire-test.swift` 的 canonical 表
 * 在 Kotlin 里编同一组消息，与主仓库 `spike/sync-wire-vectors.txt` 逐字节比对；文件里的字节解回来再编，一字不差。
 * 差一个字节，平板推上去的行服务器就解不出（或解错）。canonical 表**只许在末尾追加**，两边同一顺序。
 */
class SyncWireTest {

    private fun vectors(): Map<String, ByteArray> {
        var d: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (d != null) {
            val f = File(d, "spike/sync-wire-vectors.txt")
            if (f.isFile) {
                return f.readLines().filter { !it.startsWith("#") && it.contains("|") }.associate { line ->
                    val (k, h) = line.split("|", limit = 2)
                    k to ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                }
            }
            d = d.parentFile
        }
        error("找不到 spike/sync-wire-vectors.txt（从 ${System.getProperty("user.dir")} 往上）")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun t(s: String) = MirrorFp.Value.Text(s)
    private fun i(v: Long) = MirrorFp.Value.Int64(v)
    private fun r(v: Double) = MirrorFp.Value.Real(v)
    private fun b(vararg v: Int) = MirrorFp.Value.Blob(ByteArray(v.size) { v[it].toByte() })

    private val noteRow = mapOf(
        "id" to t("A1B2C3D4-0000-0000-0000-000000000001"),
        "document_id" to t("D0C00000-0000-0000-0000-000000000001"),
        "page" to i(3),
        "kind" to i(-1),
        "payload" to b(0x00, 0x01, 0xfe, 0xff),
        "x" to r(0.5),
        "y" to r(-2.5),
        "title" to t("中文 · note"),
        "updated_at" to MirrorFp.Value.Null,
    )
    private val metaRow = mapOf("key" to t("workspace_name"), "value" to t("101学习区"))

    private val canonical: List<Pair<String, ByteArray>> by lazy {
        val n1 = "A1B2C3D4-0000-0000-0000-000000000001"
        val n2 = "A1B2C3D4-0000-0000-0000-000000000002"
        listOf(
            "push_empty" to SyncWire.encode(SyncWire.PushRequest(22, emptyList())),
            "push_rows" to SyncWire.encode(
                SyncWire.PushRequest(
                    22,
                    listOf(
                        SyncWire.RowChange("note", n1, SyncWire.Op.UPSERT, null, noteRow),
                        SyncWire.RowChange("note", n2, SyncWire.Op.DELETE, "0123456789abcdef", null),
                        SyncWire.RowChange("meta", "workspace_name", SyncWire.Op.UPSERT, "fedcba9876543210", metaRow),
                        SyncWire.RowChange(
                            "ocr_page", "abc\u001F0\u001Fpaddle", SyncWire.Op.UPSERT, null,
                            mapOf(
                                "content_hash" to t("abc"), "page" to i(0), "provider" to t("paddle"),
                                "payload" to b(1, 2, 3), "lang" to MirrorFp.Value.Null,
                                "created_at" to t("2026-10-29T00:00:00.000Z"),
                            ),
                        ),
                    ),
                ),
            ),
            "push_resp" to SyncWire.encode(
                SyncWire.PushResponse(
                    head = 42,
                    results = listOf(
                        SyncWire.RowResult("note", n1, SyncWire.Outcome.APPLIED, 41),
                        SyncWire.RowResult("note", n2, SyncWire.Outcome.KEPT_SERVER, 7),
                        SyncWire.RowResult("image", "ffee", SyncWire.Outcome.MISSING_FILE, 0),
                        SyncWire.RowResult("variant", "V1", SyncWire.Outcome.SKIPPED_CLASH, 0),
                        SyncWire.RowResult("note", "N9", SyncWire.Outcome.SKIPPED_ORPHAN, 0),
                        SyncWire.RowResult("meta", "workspace_name", SyncWire.Outcome.UNCHANGED, 3),
                    ),
                    conflicts = listOf(
                        SyncWire.Conflict("note", n2, SyncWire.ConflictKind.BOTH_MODIFIED, true),
                        SyncWire.Conflict("document", "D1", SyncWire.ConflictKind.DELETE_VS_EDIT, false),
                        SyncWire.Conflict("bookmark", "B1", SyncWire.ConflictKind.BOTH_ADDED, true),
                    ),
                    serverRows = listOf(
                        SyncWire.PulledRow("note", n2, 7, false, noteRow),
                        SyncWire.PulledRow("note", "N8", 9, true, null),
                    ),
                ),
            ),
            "pull_empty" to SyncWire.encode(SyncWire.PullResponse(0, false, emptyList())),
            "pull_rows" to SyncWire.encode(
                SyncWire.PullResponse(
                    0x1_0000_0001L, true,
                    listOf(
                        SyncWire.PulledRow("meta", "workspace_name", 1, false, metaRow),
                        SyncWire.PulledRow("note", n1, 2, false, noteRow),
                        SyncWire.PulledRow("bookmark", "B1", 0x1_0000_0000L, true, null),
                    ),
                ),
            ),
        )
    }

    @Test
    fun 编码与Mac向量逐字节一致() {
        val v = vectors()
        assertEquals(canonical.size, v.size)
        for ((name, data) in canonical) {
            assertEquals(name, hex(v[name] ?: fail("缺 $name") as ByteArray), hex(data))
        }
    }

    @Test
    fun 向量解回来再编一字不差() {
        for ((name, data) in vectors()) {
            val again = when {
                name.startsWith("push_resp") -> SyncWire.encode(SyncWire.decodePushResponse(data))
                name.startsWith("push") -> SyncWire.encode(SyncWire.decodePushRequest(data))
                else -> SyncWire.encode(SyncWire.decodePullResponse(data))
            }
            assertEquals(name, hex(data), hex(again))
        }
        // 解出来的值与写进去的相等（Blob 按内容比、REAL 按位比）
        val pulled = SyncWire.decodePullResponse(vectors().getValue("pull_rows"))
        assertEquals(noteRow, pulled.rows[1].row)
        assertEquals(0x1_0000_0001L, pulled.head)
        assertTrue(pulled.more)
    }

    @Test
    fun 坏数据解不出() {
        val good = vectors().getValue("pull_rows")
        for (bad in listOf(good.copyOf(good.size - 1), good + byteArrayOf(0), good.copyOf().also { it[0] = 2 })) {
            try {
                SyncWire.decodePullResponse(bad)
                fail("应当解不出")
            } catch (_: SyncWire.DecodeError) {
            }
        }
    }

    @Test
    fun 按存储类搬运_不归一() {
        // 同一个 1：INTEGER 存的还是 INTEGER，REAL 存的还是 REAL（服务器写回去要与本机一模一样）
        assertEquals(MirrorFp.Value.Int64(1), SyncWire.value(1L))
        assertEquals(MirrorFp.Value.Real(1.0), SyncWire.value(1.0))
        assertEquals(MirrorFp.Value.Null, SyncWire.value(null))
        val back = SyncWire.sqliteRow(mapOf("a" to MirrorFp.Value.Int64(5), "b" to MirrorFp.Value.Null))
        assertEquals(5L, back["a"])
        assertTrue(back.containsKey("b") && back["b"] == null)
    }
}
