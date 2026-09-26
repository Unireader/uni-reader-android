package com.xvan.unireader.local.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MirrorFp` 跨端一致性测试：逐条比对 `../spike/mirror-fp-vectors.txt` 的**全部 29 条**向量
 * （由 Mac 侧 `spike/mirror-fp-test.swift` 生成）。
 *
 * 每条断言两件事：**编码字节**逐字节相等、**fp** 相等。只比 fp 不够——fp 对不上时，
 * 编码 hex 能一眼看出是哪一列、哪种类型编错了，省掉一轮二分。
 *
 * 🔴 向量表**只允许在末尾追加**：往中间插会静默错位掉整套跨端凭据（同 `WireCodecTest` 的纪律）。
 */
class MirrorFpTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** 把一组值按契约拼成编码缓冲（列间 0x1F），用于比对「编码hex」那一列 */
    private fun encAll(vs: List<MirrorFp.Value>): ByteArray {
        var out = ByteArray(0)
        vs.forEachIndexed { i, v ->
            if (i > 0) out += MirrorFp.SEPARATOR
            out += MirrorFp.enc(v)
        }
        return out
    }

    private data class C(
        val idx: Int,
        val label: String,
        val values: List<MirrorFp.Value>,
        val enc: String,
        val fp: String,
    )

    private data class R(
        val idx: Int,
        val table: String,
        val row: Map<String, Any?>,
        val enc: String,
        val fp: String,
    )

    private fun nul() = MirrorFp.Value.Null
    private fun int(v: Long) = MirrorFp.Value.Int64(v)
    private fun real(v: Double) = MirrorFp.Value.Real(v)
    private fun text(v: String) = MirrorFp.Value.Text(v)
    private fun blob(vararg v: Byte) = MirrorFp.Value.Blob(v)
    private fun blob(v: String) = MirrorFp.Value.Blob(v.toByteArray(Charsets.UTF_8))

    // —— canonical 行（与 spike/mirror-fp-test.swift 的 ⑤ 逐字段一致）——

    private val documentRow: Map<String, Any?> = mapOf(
        "id" to "11111111-1111-4111-8111-111111111111", "title" to "高等数学",
        "page_count" to 412L, "added_at" to "2026-08-30T10:00:00Z",
        "last_opened_at" to "2026-08-30T12:34:56Z", "sort_order" to 3L,
        "read_page" to 86L, "read_frac" to 0.25, "read_zoom" to 1.0, "read_hfrac" to 0.0,
        "group_name" to "考研", "canvas_mode" to 1L,
    )
    private val noteRow: Map<String, Any?> = mapOf(
        "id" to "22222222-2222-4222-8222-222222222222",
        "document_id" to "11111111-1111-4111-8111-111111111111",
        "kind" to 2L, "page" to 86L,
        "anchor_x" to 0.1, "anchor_y" to 0.2, "anchor_w" to 0.3, "anchor_h" to 0.4,
        "payload" to """{"w":8}""".toByteArray(Charsets.UTF_8),
        "created_at" to "2026-08-30T11:00:00Z", "updated_at" to "2026-08-30T11:00:01Z",
    )
    private val inkLayerRow: Map<String, Any?> = mapOf(
        "id" to "33333333-3333-4333-8333-333333333333",
        "document_id" to "11111111-1111-4111-8111-111111111111",
        "name" to "批注", "color_key" to "blue", "sort_order" to 1L, "visible" to 1L,
        "created_at" to "2026-08-30T09:00:00Z",
    )
    private val scratchPadRow: Map<String, Any?> = mapOf(
        "id" to "44444444-4444-4444-8444-444444444444",
        "document_id" to "11111111-1111-4111-8111-111111111111",
        "title" to "推导", "anchor_page" to 86L, "anchor_x" to 0.5, "anchor_y" to 0.5,
        "bg" to "rgba(255,255,255,1.0)", "pattern" to "dots", "show_page" to 0L,
        "created_at" to "2026-08-30T11:10:00Z", "updated_at" to "2026-08-30T11:20:00Z",
    )
    private val variantRow: Map<String, Any?> = mapOf(
        "id" to "55555555-5555-4555-8555-555555555555",
        "document_id" to "11111111-1111-4111-8111-111111111111",
        "content_hash" to "abc123", "page_count" to 412L, "added_at" to "2026-08-30T10:00:00Z",
    )
    private val metaRow: Map<String, Any?> = mapOf("key" to "workspace_name", "value" to "考研")

    // v16 画板笔记（与 spike/mirror-fp-test.swift 的 boardRow / boardItemRow 逐字段一致）
    private val boardRow: Map<String, Any?> = mapOf(
        "id" to "66666666-6666-4666-8666-666666666666", "title" to "极限草稿",
        "bg" to "rgba(252,247,235,1.0)", "pattern" to "grid", "group_name" to "",
        "created_at" to "2026-09-24T09:00:00Z", "updated_at" to "2026-09-24T09:30:00Z",
        "last_opened_at" to "2026-09-24T10:00:00Z",
    )
    private val boardItemRow: Map<String, Any?> = mapOf(
        "id" to "77777777-7777-4777-8777-777777777777",
        "board_id" to "66666666-6666-4666-8666-666666666666",
        "kind" to 1L, "x" to -120.5, "y" to 40.25, "w" to 300.0, "h" to 88.0,
        "payload" to """{"width":2}""".toByteArray(Charsets.UTF_8),
        "created_at" to "2026-09-24T09:10:00Z", "updated_at" to "2026-09-24T09:10:00Z",
    )

    @Test
    fun valueVectors() {
        val cases = listOf(
            C(0, "null", listOf(nul()), "00", "6e340b9cffb37a98"),
            C(1, "int 0", listOf(int(0L)), "0130", "44c808fd166dbb89"),
            C(2, "int -1", listOf(int(-1L)), "012d31", "6a5d0dea228f8fad"),
            C(3, "int max", listOf(int(Long.MAX_VALUE)),
                "0139323233333732303336383534373735383037", "e37ca04885110b97"),
            C(4, "int min", listOf(int(Long.MIN_VALUE)),
                "012d39323233333732303336383534373735383038", "a5d92cfb1c06b9e0"),
            C(5, "real 0", listOf(real(0.0)), "020000000000000000", "4322fd2bc0a137d1"),
            C(6, "real -0", listOf(real(-0.0)), "020000000000000000", "4322fd2bc0a137d1"),
            C(7, "real 1", listOf(real(1.0)), "02000000000000f03f", "ea3d69b9666200b1"),
            C(8, "real -1.5", listOf(real(-1.5)), "02000000000000f8bf", "bb58f2eff9a9ac4c"),
            C(9, "real 0.1", listOf(real(0.1)), "029a9999999999b93f", "7c2d405427146eaa"),
            C(10, "real pi", listOf(real(3.141592653589793)), "02182d4454fb210940", "da9490c271358b69"),
            C(11, "text empty", listOf(text("")), "03", "084fed08b978af4d"),
            C(12, "text hello", listOf(text("hello")), "0368656c6c6f", "0b4d354d56ea9a98"),
            C(13, "text cjk", listOf(text("中文 · 笔迹")),
                "03e4b8ade6968720c2b720e7ac94e8bfb9", "fa9be8822c64a753"),
            C(14, "text NUL", listOf(text("\u0000")), "0300", "9b4fb24edd6d1d88"),
            C(15, "blob empty", listOf(blob()), "04", "e52d9c508c502347"),
            C(16, "blob 00 1f ff", listOf(blob(0x00, 0x1F, 0xFF.toByte())), "04001fff", "5e34d9a9a93091fd"),
            C(17, "two text a,b", listOf(text("a"), text("b")), "03611f0362", "fc890294b5108773"),
            C(18, "one text a US b", listOf(text("a\u001Fb")), "03611f62", "81e5e095eec98f74"),
            C(19, "two blob a,b", listOf(blob("a"), blob("b")), "04611f0462", "9e972b1ae57c542b"),
            C(20, "mixed row",
                listOf(text("id-1"), int(7L), real(0.25), nul(), blob(0xDE.toByte(), 0xAD.toByte())),
                "0369642d311f01371f02000000000000d03f1f001f04dead", "ae600e2c6769fdba"),
        )
        assertEquals("向量条数（只许在末尾追加）", 21, cases.size)
        for (c in cases) {
            assertEquals("#${c.idx} ${c.label} 编码", c.enc, hex(encAll(c.values)))
            assertEquals("#${c.idx} ${c.label} fp", c.fp, MirrorFp.fingerprint(c.values))
        }
    }

    @Test
    fun rowVectors() {
        val cases = listOf(
            R(21, "document", documentRow,
                "0331313131313131312d313131312d343131312d383131312d3131313131313131313131311f" +
                    "03e9ab98e7ad89e695b0e5ada61f013431321f03323032362d30382d33305431303a30303a3030" +
                    "5a1f01331f0138361f02000000000000d03f1f02000000000000f03f1f02000000000000000" +
                    "01f03e88083e7a0941f0131",
                "b728e2aed6d179a6"),
            R(22, "note", noteRow,
                "0332323232323232322d323232322d343232322d383232322d3232323232323232323232321f" +
                    "0331313131313131312d313131312d343131312d383131312d3131313131313131313131311f" +
                    "01321f0138361f029a9999999999b93f1f029a9999999999c93f1f02333333333333d33f1f02" +
                    "9a9999999999d93f1f047b2277223a387d1f03323032362d30382d33305431313a30303a3030" +
                    "5a1f03323032362d30382d33305431313a30303a30315a",
                "cbdaa078c1217a46"),
            R(23, "ink_layer", inkLayerRow,
                "0333333333333333332d333333332d343333332d383333332d3333333333333333333333331f" +
                    "0331313131313131312d313131312d343131312d383131312d3131313131313131313131311f" +
                    "03e689b9e6b3a81f03626c75651f01311f01311f03323032362d30382d33305430393a30303a" +
                    "30305a",
                "e72302fede25c90f"),
            R(24, "scratch_pad", scratchPadRow,
                "0334343434343434342d343434342d343434342d383434342d3434343434343434343434341f" +
                    "0331313131313131312d313131312d343131312d383131312d3131313131313131313131311f" +
                    "03e68ea8e5afbc1f0138361f02000000000000e03f1f02000000000000e03f1f037267626128" +
                    "3235352c3235352c3235352c312e30291f03646f74731f01301f03323032362d30382d333054" +
                    "31313a31303a30305a1f03323032362d30382d33305431313a32303a30305a",
                "f106cc70f781ee96"),
            R(25, "variant", variantRow,
                "0335353535353535352d353535352d343535352d383535352d3535353535353535353535351f" +
                    "0331313131313131312d313131312d343131312d383131312d3131313131313131313131311f" +
                    "036162633132331f013431321f03323032362d30382d33305431303a30303a30305a",
                "c08a0fb25628bee7"),
            R(26, "meta", metaRow,
                "03776f726b73706163655f6e616d651f03e88083e7a094", "84a4a4c8bae2ae38"),
            R(27, "board_note", boardRow,
                "0336363636363636362d363636362d343636362d383636362d3636363636363636363636361f" +
                    "03e69e81e99990e88d89e7a8bf1f0372676261283235322c3234372c3233352c312e30291f" +
                    "03677269641f031f03323032362d30392d32345430393a30303a30305a1f03323032362d30392d" +
                    "32345430393a33303a30305a",
                "8919c0fed8d3b9f4"),
            R(28, "board_item", boardItemRow,
                "0337373737373737372d373737372d343737372d383737372d3737373737373737373737371f" +
                    "0336363636363636362d363636362d343636362d383636362d3636363636363636363636361f" +
                    "01311f020000000000205ec01f0200000000002044401f020000000000c072401f02000000000000" +
                    "56401f047b227769647468223a327d1f03323032362d30392d32345430393a31303a30305a1f0332" +
                    "3032362d30392d32345430393a31303a30305a",
                "556ae27f204196f5"),
        )
        assertEquals(8, cases.size)
        for (c in cases) {
            val sp = MirrorFp.spec(c.table)!!
            val vs = sp.columns.map { MirrorFp.coerce(c.row[it.name], it.type) }
            assertEquals("#${c.idx} row ${c.table} 编码", c.enc, hex(encAll(vs)))
            assertEquals("#${c.idx} row ${c.table} fp", c.fp, MirrorFp.fingerprint(c.row, sp))
        }
    }

    @Test
    fun semanticEquivalence() {
        // -0.0 与 +0.0 的位模式不同但语义相同——不归一就是无谓的全表误判
        assertEquals(MirrorFp.fingerprint(listOf(real(0.0))), MirrorFp.fingerprint(listOf(real(-0.0))))
        assertEquals(
            MirrorFp.fingerprint(listOf(real(Double.NaN))),
            MirrorFp.fingerprint(listOf(real(-Double.NaN))),
        )
        // 类型标签让编码单射：以下四组都不许撞
        assertNotEquals(MirrorFp.fingerprint(listOf(nul())), MirrorFp.fingerprint(listOf(text(""))))
        assertNotEquals(MirrorFp.fingerprint(listOf(text(""))), MirrorFp.fingerprint(listOf(blob())))
        assertNotEquals(MirrorFp.fingerprint(listOf(int(1L))), MirrorFp.fingerprint(listOf(text("1"))))
        assertNotEquals(
            MirrorFp.fingerprint(listOf(text("a"), text("b"))),
            MirrorFp.fingerprint(listOf(text("a\u001Fb"))),
        )
    }

    @Test
    fun coerceNormalizesStorageClassDrift() {
        // `read_zoom REAL`：一端绑 Double 存成 REAL、另一端绑 Int 存成 INTEGER，fp 必须相同
        assertEquals(real(1.0), MirrorFp.coerce(1L, MirrorFp.ColType.REAL))
        assertEquals(real(1.0), MirrorFp.coerce(1.0, MirrorFp.ColType.REAL))
        // `page INTEGER`：反向漂移
        assertEquals(int(3L), MirrorFp.coerce(3.0, MirrorFp.ColType.INT))
        // `payload BLOB`（BLOB 亲和不做转换，绑 String 就真的存成 TEXT）
        assertEquals(blob("{}"), MirrorFp.coerce("{}", MirrorFp.ColType.BLOB))
        // 缺列不炸
        assertEquals(nul(), MirrorFp.coerce(null, MirrorFp.ColType.TEXT))
    }

    @Test
    fun tableSpecs() {
        assertEquals(
            // Mac 在 scratch_pad 与 board_note 之间多一张 md_doc（本端没有 Markdown 笔记，见 MirrorFp.specs 的注释）
            listOf("document", "variant", "note", "ink_layer", "scratch_pad", "board_note", "board_item", "meta"),
            MirrorFp.specs.map { it.table },
        )
        // 画板两张的列表与 Mac 逐字一致（board_note 不含 last_opened_at，同 document）
        assertEquals(
            listOf("id", "title", "bg", "pattern", "group_name", "created_at", "updated_at"),
            MirrorFp.spec("board_note")!!.columns.map { it.name },
        )
        assertEquals(
            listOf("id", "board_id", "kind", "x", "y", "w", "h", "payload", "created_at", "updated_at"),
            MirrorFp.spec("board_item")!!.columns.map { it.name },
        )
        // 🔴 location 是设备本地事实：同步它就是制造满屏假「路径失效」
        assertNull(MirrorFp.spec("location"))
        // document 不含 last_opened_at：进了指纹的话「翻开过」就会把整行标记成改过
        val docSpec = MirrorFp.spec("document")!!
        assertEquals(
            listOf(
                "id", "title", "page_count", "added_at", "sort_order",
                "read_page", "read_frac", "read_zoom", "read_hfrac", "group_name", "canvas_mode",
            ),
            docSpec.columns.map { it.name },
        )
        val touched = documentRow.toMutableMap().apply { this["last_opened_at"] = "2099-01-01T00:00:00Z" }
        assertEquals(MirrorFp.fingerprint(documentRow, docSpec), MirrorFp.fingerprint(touched, docSpec))
        val read = documentRow.toMutableMap().apply { this["read_page"] = 87L }
        assertNotEquals(MirrorFp.fingerprint(documentRow, docSpec), MirrorFp.fingerprint(read, docSpec))
    }

    @Test
    fun tableFingerprintsKeyedByPrimaryKey() {
        val sp = MirrorFp.spec("document")!!
        val touched = documentRow.toMutableMap().apply { this["last_opened_at"] = "2099-01-01T00:00:00Z" }
        val m = MirrorFp.fingerprints(listOf(documentRow, touched), sp)
        assertEquals(1, m.size)
        assertEquals(MirrorFp.fingerprint(documentRow, sp), m["11111111-1111-4111-8111-111111111111"])
    }
}
