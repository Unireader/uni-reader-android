package com.xvan.unireader.local.mirror

import com.xvan.unireader.local.store.MirrorFp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三方合并判定表（[MirrorDiff]）+ 报告（[MirrorReport]）。方案 `../OFFLINE-MIRROR-PLAN.md` §3.1/§6。
 *
 * 与 Mac `spike/mirror-diff-test.swift` 的 ①~④ 段**一一对应**——同一份判定表的两端实现，
 * 判错一格的后果不是"某个功能不好用"，是**静默丢笔迹**。
 *
 * 纯计算，能跑 JVM 单测（不碰库）。端到端那段在 `androidTest/MirrorSyncTest`。
 */
class MirrorDiffTest {

    private val noteSpec = MirrorFp.spec("note")!!
    private val docSpec = MirrorFp.spec("document")!!

    /** 造一条 note 行。`w` 变了就等于 payload 变了 → fp 变 */
    private fun note(w: Int, updated: String, page: Long = 0, doc: String = "D1"): Map<String, Any?> = mapOf(
        "id" to "N", "document_id" to doc, "kind" to 2L, "page" to page,
        "anchor_x" to 0.1, "anchor_y" to 0.2, "anchor_w" to 0.3, "anchor_h" to 0.4,
        "payload" to """{"w":$w}""".toByteArray(),
        "created_at" to "2026-08-30T10:00:00.000Z", "updated_at" to updated,
    )

    private fun doc(
        title: String,
        lastOpened: String = "2026-08-30T10:00:00.000Z",
        page: Long = 0L,
    ): Map<String, Any?> = mapOf(
        "id" to "D1", "title" to title, "page_count" to 10L, "added_at" to "2026-08-30T09:00:00.000Z",
        "last_opened_at" to lastOpened, "sort_order" to 0L, "read_page" to page, "read_frac" to 0.0,
        "read_zoom" to 1.0, "read_hfrac" to 0.0, "group_name" to "", "canvas_mode" to 0L,
    )

    /** 跑一格：给定 base/mine/theirs 三份 note 行 */
    private fun cell(
        base: Map<String, Any?>?,
        mine: Map<String, Any?>?,
        theirs: Map<String, Any?>?,
    ): MirrorDiff.Plan = MirrorDiff.compute(
        base = if (base == null) emptyMap() else mapOf("note" to mapOf("N" to MirrorFp.fingerprint(base, noteSpec))),
        mine = mapOf("note" to if (mine == null) emptyMap() else mapOf("N" to mine)),
        theirs = mapOf("note" to if (theirs == null) emptyMap() else mapOf("N" to theirs)),
    )

    private val v1 = note(1, "2026-08-30T10:00:00.000Z")
    private val v2 = note(2, "2026-08-30T11:00:00.000Z")   // 较新
    private val v3 = note(3, "2026-08-30T10:30:00.000Z")   // 较旧

    @Test
    fun 判定表_只有一边动了() {
        cell(null, v1, null).let {
            assertEquals(1, it.changes.size)
            assertEquals(MirrorDiff.Side.SOURCE, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.MIRROR_ADDED, it.changes[0].reason)
        }
        cell(null, null, v1).let {
            assertEquals(MirrorDiff.Side.MIRROR, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.SOURCE_ADDED, it.changes[0].reason)
        }
        // 🔴 有 base 作证据才敢删
        cell(v1, null, v1).let {
            assertEquals(MirrorDiff.Op.DELETE, it.changes[0].op)
            assertEquals(MirrorDiff.Side.SOURCE, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.MIRROR_DELETED, it.changes[0].reason)
        }
        cell(v1, v1, null).let {
            assertEquals(MirrorDiff.Op.DELETE, it.changes[0].op)
            assertEquals(MirrorDiff.Side.MIRROR, it.changes[0].side)
        }
        cell(v1, v2, v1).let {
            assertEquals(MirrorDiff.Side.SOURCE, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.MIRROR_MODIFIED, it.changes[0].reason)
        }
        cell(v1, v1, v2).let {
            assertEquals(MirrorDiff.Side.MIRROR, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.SOURCE_MODIFIED, it.changes[0].reason)
        }
    }

    @Test
    fun 判定表_两端都改按时间戳取新的() {
        cell(v1, v3, v2).let {   // theirs 11:00 比 mine 10:30 新
            assertEquals(1, it.conflicts.size)
            assertEquals(MirrorDiff.ConflictKind.BOTH_MODIFIED, it.conflicts[0].kind)
            assertEquals(MirrorDiff.Side.SOURCE, it.conflicts[0].kept)
            assertEquals(MirrorDiff.Side.MIRROR, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.CONFLICT_NEWER, it.changes[0].reason)
        }
        cell(v1, v2, v3).let {   // 反过来
            assertEquals(MirrorDiff.Side.MIRROR, it.conflicts[0].kept)
            assertEquals(MirrorDiff.Side.SOURCE, it.changes[0].side)
        }
    }

    @Test
    fun 判定表_删对改一律保留改() {
        // 🔴 不丢用户数据优先（方案 §6）
        cell(v1, null, v2).let {
            assertEquals(MirrorDiff.ConflictKind.DELETE_VS_EDIT, it.conflicts[0].kind)
            assertEquals(MirrorDiff.Side.SOURCE, it.conflicts[0].kept)
            assertEquals(MirrorDiff.Op.UPSERT, it.changes[0].op)
            assertEquals("把它拉回镜像而不是在源盘删掉", MirrorDiff.Side.MIRROR, it.changes[0].side)
            assertEquals(MirrorDiff.Reason.CONFLICT_KEPT_EDIT, it.changes[0].reason)
        }
        cell(v1, v2, null).let {
            assertEquals(MirrorDiff.Side.MIRROR, it.conflicts[0].kept)
            assertEquals(MirrorDiff.Op.UPSERT, it.changes[0].op)
            assertEquals(MirrorDiff.Side.SOURCE, it.changes[0].side)
        }
    }

    @Test
    fun 判定表_什么都不该做的几格() {
        // 漏判成「要删」和漏判成「不管」一样致命，所以这几格也要有用例
        assertTrue("两边都没动", cell(v1, v1, v1).changes.isEmpty())
        assertTrue("两边都删了", cell(v1, null, null).changes.isEmpty())
        assertTrue("三方都没有", cell(null, null, null).changes.isEmpty())
        assertTrue("两边改成一样了（不是冲突）", cell(v1, v2, v2).changes.isEmpty())
        assertTrue("两边各自新增了一模一样的行", cell(null, v1, v1).changes.isEmpty())
        assertTrue(cell(v1, v2, v2).conflicts.isEmpty())
    }

    @Test
    fun 判定表_两端各自新建同id但内容不同() {
        // UUID 表几乎不可能，`meta` 这种固定键会
        assertEquals(MirrorDiff.ConflictKind.BOTH_ADDED, cell(null, v3, v2).conflicts[0].kind)
    }

    /** base 恒取「原名 + 第 0 页」那份 */
    private fun docBase() = mapOf("document" to mapOf("D1" to MirrorFp.fingerprint(doc("原名"), docSpec)))

    @Test
    fun document两端都改按最后打开时间裁决() {
        // 🔴 这张表没有 updated_at，原先 lww=null ⇒ 一律「保留硬盘那份」，
        //    于是**离线副本上的阅读进度被静默丢弃**。改用 last_opened_at。
        val plan = MirrorDiff.compute(
            base = docBase(),
            mine = mapOf("document" to mapOf("D1" to doc("本机改的名", "2026-09-01T08:00:00.000Z"))),
            theirs = mapOf("document" to mapOf("D1" to doc("硬盘改的名", "2026-08-31T20:00:00.000Z"))),
        )
        assertEquals(1, plan.conflicts.size)
        assertEquals(MirrorDiff.Side.MIRROR, plan.conflicts[0].kept)
        assertEquals(MirrorDiff.Reason.CONFLICT_NEWER, plan.changes[0].reason)
        assertTrue("冲突说明点名保留了哪一份", plan.conflicts[0].note.contains("2026-09-01T08:00:00.000Z"))
    }

    @Test
    fun 只差阅读进度不算冲突() {
        val mine = doc("原名", "2026-09-01T08:00:00.000Z", page = 87L)
        val theirs = doc("原名", "2026-08-31T20:00:00.000Z", page = 12L)
        val plan = MirrorDiff.compute(
            base = docBase(),
            mine = mapOf("document" to mapOf("D1" to mine)),
            theirs = mapOf("document" to mapOf("D1" to theirs)),
        )
        // 两端各翻过同一本书是正常使用，报成冲突只是噪音（用户「几乎什么都没动」却收到冲突）
        assertTrue("只差阅读进度 → 一条冲突都不报", plan.conflicts.isEmpty())
        assertEquals(setOf("D1"), plan.progressMerges)
        assertEquals(1, plan.changes.size)
        assertEquals("本机读得更晚 → 推给硬盘", MirrorDiff.Side.SOURCE, plan.changes[0].side)
        assertEquals(87L, plan.changes[0].row?.get("read_page"))

        val back = MirrorDiff.compute(
            base = docBase(),
            mine = mapOf("document" to mapOf("D1" to theirs)),
            theirs = mapOf("document" to mapOf("D1" to mine)),
        )
        assertEquals("硬盘读得更晚 → 拉回本机", MirrorDiff.Side.MIRROR, back.changes[0].side)

        // 报告：只差进度的那条**不再以「修改书的信息」的面目又数一遍**
        val lines = MirrorReport.summary(back, mapOf("D1" to "王道 2027 计算机组成原理"))
        assertEquals("只差进度 → 报告就一行：${lines.map { it.text }}", 1, lines.size)
        assertEquals("1 篇文档两端都读过，阅读进度取最近读的那次", lines[0].text)
        assertFalse(
            "不再出现「拉回本机：修改书的信息 1」这种同一件事数两遍",
            lines.any { it.text.contains("书的信息") || it.text.contains("拉回本机") },
        )
        assertEquals("只更新阅读进度", MirrorReport.headline(back))
        assertEquals("⚠️ 只是不报，plan 里那条改动一条不少", 1, back.changes.size)
        assertEquals(87L, back.changes[0].row?.get("read_page"))
    }

    @Test
    fun lastOpenedAt不进指纹但两端取较晚的() {
        val a = doc("同名", "2026-08-30T10:00:00.000Z")
        val b = doc("同名", "2026-08-31T20:00:00.000Z")
        val plan = MirrorDiff.compute(
            base = mapOf("document" to mapOf("D1" to MirrorFp.fingerprint(a, docSpec))),
            mine = mapOf("document" to mapOf("D1" to a)),
            theirs = mapOf("document" to mapOf("D1" to b)),
        )
        // 🔴 否则预览里满屏「改了 last_opened_at」这种无意义条目
        assertTrue("只有 last_opened_at 不同 → 一条 change 都不产生", plan.changes.isEmpty())
        assertEquals("2026-08-31T20:00:00.000Z", plan.lastOpenedMerges["D1"])
        assertFalse("只有 lastOpenedMerges 时 Plan 不算空", plan.isEmpty)
    }

    @Test
    fun 报告里一行id都不许出现() {
        val titles = mapOf("D1" to "高等数学")
        val added = cell(null, note(1, "t", page = 86), null)
        val lines = MirrorReport.summary(added, titles)
        assertTrue(lines.any { it.text.contains("写入硬盘") && it.text.contains("新增笔迹 1") })
        assertEquals("《高等数学》：笔迹 +1", lines[0].detail.firstOrNull())
        assertFalse(
            "报告里不该出现行 id",
            lines.any { l -> l.text.contains("N") && l.text.contains("-") || l.detail.any { it.contains("\"N\"") } },
        )

        // 🔴 document 表自己那行**没有 document_id 列** → 从前 docId 是 null，被算进
        //    「工作区级设置」，冲突行还拼出「的一条书的信息：…」这种断头句
        val docPlan = MirrorDiff.compute(
            base = docBase(),
            mine = mapOf("document" to mapOf("D1" to doc("本机改的名", "2026-09-01T08:00:00.000Z"))),
            theirs = mapOf("document" to mapOf("D1" to doc("硬盘改的名"))),
        )
        val docLines = MirrorReport.summary(docPlan, titles)
        assertEquals("《高等数学》：书的信息 改 1", docLines[0].detail.firstOrNull())
        assertFalse("不再被当成工作区级设置", docLines.any { l -> l.detail.any { it.contains("工作区") } })
        val cf = docLines.first { it.text.contains("冲突") }.detail.first()
        assertTrue("冲突行要带上书名：$cf", cf.startsWith("《高等数学》的一条书的信息："))

        // 既没书名也没页码时（meta 就是这样）不许拼出「的一条…」
        val metaSpec = MirrorFp.spec("meta")!!
        fun metaRow(v: String) = mapOf<String, Any?>("key" to "workspace_name", "value" to v)
        val metaPlan = MirrorDiff.compute(
            base = mapOf("meta" to mapOf("workspace_name" to MirrorFp.fingerprint(metaRow("原名"), metaSpec))),
            mine = mapOf("meta" to mapOf("workspace_name" to metaRow("本机改的"))),
            theirs = mapOf("meta" to mapOf("workspace_name" to metaRow("硬盘改的"))),
        )
        val metaLines = MirrorReport.summary(metaPlan, emptyMap())
        val ml = metaLines.first { it.text.contains("冲突") }.detail.first()
        assertFalse("查不到书名/页码就别硬拼断头句：$ml", ml.startsWith("的"))
        assertTrue("直接说是哪张表的事：$ml", ml.startsWith("工作区设置："))
        assertEquals("工作区设置 1 项", metaLines[0].detail.firstOrNull())

        // 删除也要能说出是哪本书的什么（删除那条 row 是 null，靠 Change 上事先取下的 docId/kind）
        val deleted = cell(note(1, "t", page = 86), null, note(1, "t", page = 86))
        assertEquals("《高等数学》：笔迹 −1", MirrorReport.summary(deleted, titles)[0].detail.firstOrNull())

        assertEquals("笔迹", MirrorReport.noteKindName(2))
        assertEquals("草稿纸笔迹", MirrorReport.noteKindName(4))
        assertEquals("文字注解", MirrorReport.noteKindName(0))
        assertEquals("两端一致", MirrorReport.headline(MirrorDiff.Plan(emptyList(), emptyList(), emptyMap())))

        // 冲突明细必须说清「保留了哪份」——用户同意的是一个具体结果，不是一个数字
        val conflict = cell(v1, v3, note(2, "2026-08-30T11:00:00.000Z", page = 86))
        val cl = MirrorReport.conflictLines(conflict, titles)
        assertEquals(1, cl.size)
        assertTrue(cl[0], cl[0].contains("《高等数学》") && cl[0].contains("保留了较新的那份"))
        assertNull(null)
    }

    /**
     * OCR 缓存（`ocr_page`）走的是另一条通道：纯 additive、只补对面缺的、不判改删（方案 §4）。
     * 与 Mac `spike/mirror-apply-test.swift` ⑦ 段同一套判定。
     */
    @Test
    fun ocrCacheIsAdditiveBothWays() {
        fun key(page: Int) = MirrorDiff.OcrKey("h1", page, "paddle-http")
        val plan = MirrorDiff.compute(
            base = emptyMap(), mine = emptyMap(), theirs = emptyMap(),
            mineOCR = setOf(key(0), key(1), key(2)),      // 副本上识别过 0/1/2
            theirsOCR = setOf(key(0), key(3)),            // 硬盘上识别过 0/3
        )
        assertEquals(listOf(key(1), key(2)), plan.ocrToSource)
        assertEquals(listOf(key(3)), plan.ocrToMirror)
        assertTrue("OCR 缓存不进 Change 那条通道（不进指纹、不进基线）", plan.changes.isEmpty())
        assertFalse("只差 OCR 也算「有东西要同步」", plan.isEmpty)
        assertEquals("识别结果 3 页", MirrorReport.headline(plan))

        val line = MirrorReport.summary(plan, emptyMap(), mapOf("h1" to "高等数学"))
            .first { it.text.contains("补齐文字识别结果") }
        assertTrue(line.text, line.text.contains("写入硬盘 2 页") && line.text.contains("拉回本机 1 页"))
        assertEquals("《高等数学》：写入硬盘 2 页，拉回本机 1 页", line.detail.firstOrNull())

        // 一边清空缓存 → **不是「让对面也删」**，而是从对面补回来（缓存是派生数据，重跑要花钱）
        val cleared = MirrorDiff.compute(
            base = emptyMap(), mine = emptyMap(), theirs = emptyMap(),
            mineOCR = emptySet(), theirsOCR = setOf(key(0), key(1)),
        )
        assertTrue(cleared.ocrToSource.isEmpty())
        assertEquals(2, cleared.ocrToMirror.size)
    }

    /**
     * 扫描页对齐（`page_align`）那条通道：按 `updated_at` 取新、一侧缺就补（`../SCAN-ALIGN-PLAN.md §5`）。
     * 与 Mac `spike/mirror-align-test.swift` ①② 段逐条对应。
     */
    @Test
    fun 扫描页对齐_alignPlan() {
        val p = MirrorDiff.alignPlan(
            mine = mapOf(
                "a" to "2026-09-17T10:00:00.000Z",
                "b" to "2026-09-17T10:00:00.000Z",
                "c" to "2026-09-17T12:00:00.000Z",
            ),
            theirs = mapOf(
                "b" to "2026-09-17T11:00:00.000Z",
                "c" to "2026-09-17T12:00:00.000Z",
                "d" to "2026-09-01T00:00:00.000Z",
            ),
        )
        assertEquals("副本独有 → 写入硬盘", listOf("a"), p.first)
        assertEquals("硬盘较新 / 硬盘独有 → 拉回本机", listOf("b", "d"), p.second)

        val q = MirrorDiff.alignPlan(
            mine = mapOf("x" to "2026-09-17T13:00:00.000Z"),
            theirs = mapOf("x" to "2026-09-17T12:59:59.999Z"),
        )
        assertEquals("副本较新 → 写入硬盘", listOf("x"), q.first)
        assertTrue(q.second.isEmpty())

        // 两个方向都按 hash 排好序（与 Mac `.sorted()` 一致，报告明细的顺序因此两端相同）
        val r = MirrorDiff.alignPlan(mine = mapOf("z" to "1", "m" to "1"), theirs = mapOf("y" to "1", "b" to "1"))
        assertEquals(listOf("m", "z"), r.first)
        assertEquals(listOf("b", "y"), r.second)

        // compute 把它原样放进 Plan
        val plan = MirrorDiff.compute(
            base = emptyMap(), mine = emptyMap(), theirs = emptyMap(),
            mineAlign = mapOf("a" to "2026-09-17T10:00:00.000Z"),
            theirsAlign = mapOf("d" to "2026-09-01T00:00:00.000Z"),
        )
        assertEquals(listOf("a"), plan.alignToSource)
        assertEquals(listOf("d"), plan.alignToMirror)
        assertTrue("对齐参数不进 Change 那条通道（不进指纹、不进基线）", plan.changes.isEmpty())
    }

    @Test
    fun 扫描页对齐_Plan标志() {
        val empty = MirrorDiff.Plan(emptyList(), emptyList(), emptyMap())
        assertTrue(empty.isEmpty)
        assertFalse("什么都没有 → 谈不上推送", empty.isCleanPushToMirror)

        val pull = empty.copy(alignToMirror = listOf("h1"))
        assertFalse(pull.isEmpty)
        assertTrue("只有拉回本机：可自动推送", pull.isCleanPushToMirror)
        assertEquals(0, pull.pendingToSource)

        val both = pull.copy(alignToSource = listOf("h2"))
        assertFalse("有写入硬盘：挡自动推送", both.isCleanPushToMirror)
        assertEquals("计入待确认", 1, both.pendingToSource)

        val push = empty.copy(alignToSource = listOf("h3"))
        assertFalse("只有对齐改动也不算「两端一致」", push.isEmpty)
        assertFalse(push.isCleanPushToMirror)
        assertEquals(1, push.pendingToSource)

        // 普通改动的口径不变：写入硬盘的 change 计入待确认
        val added = cell(null, v1, null)
        assertEquals(1, added.pendingToSource)
        assertFalse(added.isCleanPushToMirror)
        assertTrue("硬盘新增 → 拉回本机：可自动推送", cell(null, null, v1).isCleanPushToMirror)
    }

    @Test
    fun 扫描页对齐_报告() {
        val plan = MirrorDiff.Plan(
            emptyList(), emptyList(), emptyMap(),
            alignToSource = listOf("h1"),
            alignToMirror = listOf("h2", "h3"),
        )
        assertEquals("扫描页对齐 3 本", MirrorReport.headline(plan))
        val lines = MirrorReport.summary(plan, emptyMap(), mapOf("h1" to "软件工程", "h2" to "高等数学"))
        assertEquals(1, lines.size)
        assertEquals("扫描页对齐设置：写入硬盘 1 本、拉回本机 2 本", lines[0].text)
        assertEquals(listOf("软件工程", "高等数学", "（未知文档）"), lines[0].detail)

        // 有别的改动时前缀「另有」（同 OCR 那条）
        val mixed = cell(null, v1, null).copy(alignToMirror = listOf("h2"))
        val ml = MirrorReport.summary(mixed, mapOf("D1" to "高等数学"), mapOf("h2" to "高等数学"))
        assertTrue(ml.map { it.text }.toString(), ml.any { it.text == "另有扫描页对齐设置：拉回本机 1 本" })
        assertEquals("写入硬盘 1 · 扫描页对齐 1 本", MirrorReport.headline(mixed))
    }
}
