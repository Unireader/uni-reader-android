package com.xvan.unireader.local.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MirrorFp` 跨端一致性测试：逐条比对 `../spike/mirror-fp-vectors.txt` 的**全部 40 条**向量
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
    // v17 分页画板的页（与 spike/mirror-fp-test.swift 的 board_page 那一行逐字段一致）
    private val boardPageRow: Map<String, Any?> = mapOf(
        "id" to "88888888-8888-4888-8888-888888888888",
        "board_id" to "66666666-6666-4666-8666-666666666666",
        "sort_key" to 1.5, "width" to 595.0, "height" to 842.0, "template" to "cornell",
        "created_at" to "2026-09-26T09:00:00Z", "updated_at" to "2026-09-26T09:05:00Z",
    )
    // v20 习题（与 spike/mirror-fp-test.swift 的 exercise / exercise_attempt 两行逐字段一致；correct 为 NULL）
    private val exerciseRow: Map<String, Any?> = mapOf(
        "id" to "99999999-9999-4999-8999-999999999999",
        "document_id" to "11111111-1111-4111-8111-111111111111",
        "page" to 11L, "seq" to 2L, "kind" to 1L, "number" to "5", "section" to "习题 2-3",
        "payload" to """{"answer":["A","C"],"options":[],"stem":"设 ${'$'}f(x)=x^2${'$'}"}""".toByteArray(Charsets.UTF_8),
        "starred" to 1L, "wrong" to 0L, "source" to "kimi",
        "created_at" to "2026-10-10T09:00:00.000Z", "updated_at" to "2026-10-11T08:00:00.000Z",
        "chapter" to """["第二章 导数","2.3 求导法则"]""",
    )
    private val exerciseAttemptRow: Map<String, Any?> = mapOf(
        "id" to "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
        "exercise_id" to "99999999-9999-4999-8999-999999999999",
        "answer" to "AC", "correct" to null, "elapsed_ms" to 4200L,
        "created_at" to "2026-10-11T08:00:00.000Z", "source" to "",
    )
    // v21 主观题作答（与 spike/mirror-fp-test.swift 的第二行 exercise_attempt 逐字段一致）：手写 + Agent 判了 + 评语
    private val subjectiveAttemptRow: Map<String, Any?> = mapOf(
        "id" to "BBBBBBBB-BBBB-4BBB-8BBB-BBBBBBBBBBBB",
        "exercise_id" to "99999999-9999-4999-8999-999999999999",
        "answer" to "设 ${'$'}\\sqrt2=p/q${'$'}", "correct" to 0L, "elapsed_ms" to 91000L,
        "created_at" to "2026-10-13T08:00:00.000Z", "source" to "",
        "ink" to """{"height":280,"strokes":[],"width":600}""".toByteArray(Charsets.UTF_8),
        "judged_by" to "agent", "feedback" to "少了**互质**的假设",
    )
    // v22 题目笔记（与 spike/mirror-fp-test.swift 末尾那行 exercise 逐字段一致）：同一道题写了笔记、更新时间跟着变
    private val exerciseNoteRow: Map<String, Any?> = exerciseRow + mapOf(
        "wrong" to 1L, "updated_at" to "2026-10-27T08:00:00.000Z",
        "note" to "别忘了 ${'$'}f'(x)=2x${'$'}，**链式法则**",
    )
    // v24 题目标签（与 spike/mirror-fp-test.swift 末尾那行 exercise 逐字段一致）
    private val exerciseTagsRow: Map<String, Any?> = exerciseRow + mapOf(
        "updated_at" to "2026-11-02T09:00:00.000Z",
        "chapter" to "", "note" to "", "tags" to "[\"真题\",\"2023\"]",
    ).plus("wrong" to 0L).plus("starred" to 0L)
    // v23 Agent 上下文（与 spike/mirror-fp-test.swift 末尾那行 agent_context 逐字段一致）：中文标题 + 多行 Markdown 摘要
    private val agentContextRow: Map<String, Any?> = mapOf(
        "id" to "CCCCCCCC-CCCC-4CCC-8CCC-CCCCCCCCCCCC",
        "title" to "高数第二章错题整理",
        "summary" to "## 做到哪了\n- 已导入第二章习题 1~20\n- 第 5 题答案待核对：${'$'}f'(x)=2x${'$'}\n\n## 接下来\n1. 批改主观题",
        "agent" to "Kimi", "session_id" to "session-7f3a",
        "created_at" to "2026-11-02T08:00:00.000Z", "updated_at" to "2026-11-02T08:30:00.000Z",
    )
    // v25 Agent 记忆（与 spike/mirror-fp-test.swift 的 agent_memory 那行逐字段一致）：一两句话，含中文、行内公式
    private val agentMemoryRow: Map<String, Any?> = mapOf(
        "id" to "DDDDDDDD-DDDD-4DDD-8DDD-DDDDDDDDDDDD",
        "content" to "用户在复习计算机网络（王道 2027），偏好先给结论再推导；公式写成 ${'$'}C = W\\log_2(1+S/N)${'$'}",
        "agent" to "Kimi",
        "created_at" to "2026-11-13T08:00:00.000Z", "updated_at" to "2026-11-13T09:00:00.000Z",
    )
    // v25 Agent 对话记录（与 spike/mirror-fp-test.swift 的 agent_turn 那行逐字段一致）：一轮，多行回复
    private val agentTurnRow: Map<String, Any?> = mapOf(
        "id" to "EEEEEEEE-EEEE-4EEE-8EEE-EEEEEEEEEEEE",
        "conversation_id" to "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF",
        "agent" to "Claude Code", "session_id" to "session-91c2",
        "user_text" to "QAM-16 每个码元几比特？",
        "reply_text" to "**4 比特**：16 = 2^4。\n\n- 码元速率 × 4 = 比特率",
        "created_at" to "2026-11-13T08:10:00.000Z", "updated_at" to "2026-11-13T08:10:00.000Z",
    )
    // v26 日程计划（与 spike/mirror-fp-test.swift 的 schedule_plan 那行逐字段一致）
    private val schedulePlanRow: Map<String, Any?> = mapOf(
        "id" to "12121212-1212-4121-8121-121212121212",
        "title" to "第三章复习（两周）", "goal" to "11 月底前把第三章数据链路层过完，错题清零",
        "agent" to "Kimi",
        "created_at" to "2026-11-13T10:00:00.000Z", "updated_at" to "2026-11-13T10:00:00.000Z",
    )
    // v26 日程事项（与 spike/mirror-fp-test.swift 的 schedule_item 那行逐字段一致）：定了时间、做完了、带一句结果和一个书页链接
    private val scheduleItemRow: Map<String, Any?> = mapOf(
        "id" to "34343434-3434-4343-8343-343434343434",
        "plan_id" to "12121212-1212-4121-8121-121212121212",
        "title" to "读 3.4 滑动窗口", "detail" to "读 [[王道计算机网络#page=88]] 到第 96 页，做课后 1\u20136 题",
        "day" to "2026-11-14", "start_min" to 570L, "duration_min" to 90L,
        "done_at" to "2026-11-14T03:20:00.000Z", "result" to "GBN 和 SR 的窗口大小还要再看",
        "seq" to 1L, "agent" to "Kimi",
        "created_at" to "2026-11-13T10:00:00.000Z", "updated_at" to "2026-11-14T03:20:00.000Z",
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
            R(29, "board_page", boardPageRow,
                "0338383838383838382d383838382d343838382d383838382d3838383838383838383838381f" +
                    "0336363636363636362d363636362d343636362d383636362d3636363636363636363636361f" +
                    "02000000000000f83f1f0200000000009882401f020000000000508a401f03636f726e656c6c1f" +
                    "03323032362d30392d32365430393a30303a30305a1f03323032362d30392d32365430393a30353a" +
                    "30305a",
                "2b807960580dd994"),
            // v22 起按 15 列、v24 起按 16 列编码（笔记 / 标签缺 = NULL，末尾 1f00 ×2），编码 / fp 随列规格变了（`../docs/EXERCISE-PLAN.md §12`）
            R(30, "exercise", exerciseRow,
                "0339393939393939392d393939392d343939392d383939392d3939393939393939393939391f03" +
                    "31313131313131312d313131312d343131312d383131312d3131313131313131313131311f0131" +
                    "311f01321f01311f03351f03e4b9a0e9a29820322d331f047b22616e73776572223a5b2241222c" +
                    "2243225d2c226f7074696f6e73223a5b5d2c227374656d223a22e8aebe2024662878293d785e32" +
                    "24227d1f01311f01301f036b696d691f03323032362d31302d31305430393a30303a30302e3030" +
                    "305a1f03323032362d31302d31315430383a30303a30302e3030305a1f035b22e7acace4ba8ce7" +
                    "aba020e5afbce695b0222c22322e3320e6b182e5afbce6b395e58899225d1f001f00",
                "c3e066d36723d4d3"),
            // v21 起按 10 列编码（新三列缺 = NULL，末尾 1f00 ×3），编码 / fp 随列规格变了（`../docs/EXERCISE-PLAN.md §11.5`）
            R(31, "exercise_attempt", exerciseAttemptRow,
                "0341414141414141412d414141412d344141412d384141412d4141414141414141414141411f" +
                    "0339393939393939392d393939392d343939392d383939392d3939393939393939393939391f" +
                    "0341431f001f01343230301f03323032362d31302d31315430383a30303a30302e3030305a1f" +
                    "031f001f001f00",
                "c6e9c219493ea16a"),
            R(32, "exercise_attempt", subjectiveAttemptRow,
                "0342424242424242422d424242422d344242422d384242422d4242424242424242424242421f" +
                    "0339393939393939392d393939392d343939392d383939392d3939393939393939393939391f" +
                    "03e8aebe20245c73717274323d702f71241f01301f0139313030301f03323032362d31302d31" +
                    "335430383a30303a30302e3030305a1f031f047b22686569676874223a3238302c227374726f" +
                    "6b6573223a5b5d2c227769647468223a3630307d1f036167656e741f03e5b091e4ba862a2ae4" +
                    "ba92e8b4a82a2ae79a84e58187e8aebe",
                "3bd6aba4f01a9dc0"),
            R(33, "exercise", exerciseNoteRow,
                "0339393939393939392d393939392d343939392d383939392d3939393939393939393939391f03" +
                    "31313131313131312d313131312d343131312d383131312d3131313131313131313131311f0131" +
                    "311f01321f01311f03351f03e4b9a0e9a29820322d331f047b22616e73776572223a5b2241222c" +
                    "2243225d2c226f7074696f6e73223a5b5d2c227374656d223a22e8aebe2024662878293d785e32" +
                    "24227d1f01311f01311f036b696d691f03323032362d31302d31305430393a30303a30302e3030" +
                    "305a1f03323032362d31302d32375430383a30303a30302e3030305a1f035b22e7acace4ba8ce7" +
                    "aba020e5afbce695b0222c22322e3320e6b182e5afbce6b395e58899225d1f03e588abe5bf98e4" +
                    "ba86202466272878293d327824efbc8c2a2ae993bee5bc8fe6b395e588992a2a1f00",
                "44926c98ca9bfe92"),
            R(34, "agent_context", agentContextRow,
                "0343434343434343432d434343432d344343432d384343432d4343434343434343434343431f" +
                    "03e9ab98e695b0e7acace4ba8ce7aba0e99499e9a298e695b4e790861f03232320e5819ae588" +
                    "b0e593aae4ba860a2d20e5b7b2e5afbce585a5e7acace4ba8ce7aba0e4b9a0e9a29820317e32" +
                    "300a2d20e7acac203520e9a298e7ad94e6a188e5be85e6a0b8e5afb9efbc9a2466272878293d" +
                    "3278240a0a232320e68ea5e4b88be69da50a312e20e689b9e694b9e4b8bbe8a782e9a2981f03" +
                    "4b696d691f0373657373696f6e2d376633611f03323032362d31312d30325430383a30303a30" +
                    "302e3030305a1f03323032362d31312d30325430383a33303a30302e3030305a",
                "e4c41b0aecb7e9e4"),
            // v24 题目标签（JSON 字符串数组，含中文），表尾再多一列
            R(35, "exercise", exerciseTagsRow,
                "0339393939393939392d393939392d343939392d383939392d3939393939393939393939391f03" +
                    "31313131313131312d313131312d343131312d383131312d3131313131313131313131311f0131" +
                    "311f01321f01311f03351f03e4b9a0e9a29820322d331f047b22616e73776572223a5b2241222c" +
                    "2243225d2c226f7074696f6e73223a5b5d2c227374656d223a22e8aebe2024662878293d785e32" +
                    "24227d1f01301f01301f036b696d691f03323032362d31302d31305430393a30303a30302e3030" +
                    "305a1f03323032362d31312d30325430393a30303a30302e3030305a1f031f031f035b22e79c9f" +
                    "e9a298222c2232303233225d",
                "8408973ce1a292d1"),
            R(36, "agent_memory", agentMemoryRow,
                "0344444444444444442d444444442d344444442d384444442d4444444444444444444444441f" +
                    "03e794a8e688b7e59ca8e5a48de4b9a0e8aea1e7ae97e69cbae7bd91e7bb9cefbc88e78e8be9" +
                    "81932032303237efbc89efbc8ce5818fe5a5bde58588e7bb99e7bb93e8aebae5868de68ea8e5" +
                    "afbcefbc9be585ace5bc8fe58699e68890202443203d20575c6c6f675f3228312b532f4e2924" +
                    "1f034b696d691f03323032362d31312d31335430383a30303a30302e3030305a1f0332303236" +
                    "2d31312d31335430393a30303a30302e3030305a",
                "dfdbaf81c01a38a7"),
            R(37, "agent_turn", agentTurnRow,
                "0345454545454545452d454545452d344545452d384545452d4545454545454545454545451f" +
                    "0346464646464646462d464646462d344646462d384646462d4646464646464646464646461f" +
                    "03436c6175646520436f64651f0373657373696f6e2d393163321f0351414d2d313620e6af8f" +
                    "e4b8aae7a081e58583e587a0e6af94e789b9efbc9f1f032a2a3420e6af94e789b92a2aefbc9a" +
                    "3136203d20325e34e380820a0a2d20e7a081e58583e9809fe78e8720c3972034203d20e6af94" +
                    "e789b9e78e871f03323032362d31312d31335430383a31303a30302e3030305a1f0332303236" +
                    "2d31312d31335430383a31303a30302e3030305a",
                "723f3a82e3d1c03b"),
            R(38, "schedule_plan", schedulePlanRow,
                "0331323132313231322d313231322d343132312d383132312d3132313231323132313231321f" +
                    "03e7acace4b889e7aba0e5a48de4b9a0efbc88e4b8a4e591a8efbc891f03313120e69c88e5ba" +
                    "95e5898de68a8ae7acace4b889e7aba0e695b0e68daee993bee8b7afe5b182e8bf87e5ae8cef" +
                    "bc8ce99499e9a298e6b885e99bb61f034b696d691f03323032362d31312d31335431303a3030" +
                    "3a30302e3030305a1f03323032362d31312d31335431303a30303a30302e3030305a",
                "9cf683081501b226"),
            R(39, "schedule_item", scheduleItemRow,
                "0333343334333433342d333433342d343334332d383334332d3334333433343334333433341f" +
                    "0331323132313231322d313231322d343132312d383132312d3132313231323132313231321f" +
                    "03e8afbb20332e3420e6bb91e58aa8e7aa97e58fa31f03e8afbb205b5be78e8be98193e8aea1" +
                    "e7ae97e69cbae7bd91e7bb9c23706167653d38385d5d20e588b0e7acac20393620e9a1b5efbc" +
                    "8ce5819ae8afbee5908e2031e280933620e9a2981f03323032362d31312d31341f013537301f" +
                    "0139301f03323032362d31312d31345430333a32303a30302e3030305a1f0347424e20e5928c" +
                    "20535220e79a84e7aa97e58fa3e5a4a7e5b08fe8bf98e8a681e5868de79c8b1f01311f034b69" +
                    "6d691f03323032362d31312d31335431303a30303a30302e3030305a1f03323032362d31312d" +
                    "31345430333a32303a30302e3030305a",
                "f2f7de73174ca34a"),
        )
        assertEquals(19, cases.size)
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
            // 与 Mac `MirrorFp.specs` 同一份表集合、同一顺序（17 张；location 不在其中；v20 加了习题两张、v23 Agent 上下文、
            // v25 Agent 记忆与对话记录、v26 日程两张）
            listOf(
                "document", "variant", "note", "ink_layer", "scratch_pad", "md_doc",
                "board_note", "board_page", "board_item", "exercise", "exercise_attempt", "agent_context",
                "agent_memory", "agent_turn", "schedule_plan", "schedule_item", "meta",
            ),
            MirrorFp.specs.map { it.table },
        )
        assertEquals(
            listOf(
                "id", "document_id", "page", "seq", "kind", "number", "section", "payload", "starred", "wrong",
                "source", "created_at", "updated_at", "chapter", "note", "tags",
            ),
            MirrorFp.spec("exercise")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("exercise")!!.lww)
        assertEquals(
            listOf("id", "exercise_id", "answer", "correct", "elapsed_ms", "created_at", "source",
                   "ink", "judged_by", "feedback"),
            MirrorFp.spec("exercise_attempt")!!.columns.map { it.name },
        )
        assertNull(MirrorFp.spec("exercise_attempt")!!.lww)
        assertEquals(
            listOf("id", "title", "summary", "agent", "session_id", "created_at", "updated_at"),
            MirrorFp.spec("agent_context")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("agent_context")!!.lww)
        assertEquals(
            listOf("id", "content", "agent", "created_at", "updated_at"),
            MirrorFp.spec("agent_memory")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("agent_memory")!!.lww)
        assertEquals(
            listOf("id", "conversation_id", "agent", "session_id", "user_text", "reply_text", "created_at", "updated_at"),
            MirrorFp.spec("agent_turn")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("agent_turn")!!.lww)
        assertEquals(
            listOf("id", "title", "goal", "agent", "created_at", "updated_at"),
            MirrorFp.spec("schedule_plan")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("schedule_plan")!!.lww)
        assertEquals(
            listOf(
                "id", "plan_id", "title", "detail", "day", "start_min", "duration_min", "done_at", "result", "seq", "agent",
                "created_at", "updated_at",
            ),
            MirrorFp.spec("schedule_item")!!.columns.map { it.name },
        )
        assertEquals("updated_at", MirrorFp.spec("schedule_item")!!.lww)
        // v26 两张日程表与 v25 Agent 两张一样：只读打开过的老库里可能还没建，写之前要先迁移
        for (t in listOf("agent_context", "agent_memory", "agent_turn", "schedule_plan", "schedule_item")) {
            assertEquals(t, true, MirrorFp.needsMigrate(t))
        }
        assertEquals(
            listOf("id", "board_id", "sort_key", "width", "height", "template", "created_at", "updated_at"),
            MirrorFp.spec("board_page")!!.columns.map { it.name },
        )
        assertEquals(
            listOf("id", "title", "rel_path", "group_name", "sort_order", "created_at", "updated_at"),
            MirrorFp.spec("md_doc")!!.columns.map { it.name },
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
