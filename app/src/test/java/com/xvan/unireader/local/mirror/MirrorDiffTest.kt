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

    private fun doc(title: String, lastOpened: String = "2026-08-30T10:00:00.000Z"): Map<String, Any?> = mapOf(
        "id" to "D1", "title" to title, "page_count" to 10L, "added_at" to "2026-08-30T09:00:00.000Z",
        "last_opened_at" to lastOpened, "sort_order" to 0L, "read_page" to 0L, "read_frac" to 0.0,
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

    @Test
    fun 没有时间戳列的表冲突保留源盘并报告() {
        val base = doc("原名")
        val plan = MirrorDiff.compute(
            base = mapOf("document" to mapOf("D1" to MirrorFp.fingerprint(base, docSpec))),
            mine = mapOf("document" to mapOf("D1" to doc("本机改的名"))),
            theirs = mapOf("document" to mapOf("D1" to doc("硬盘改的名"))),
        )
        assertEquals(1, plan.conflicts.size)
        assertEquals(MirrorDiff.Side.SOURCE, plan.conflicts[0].kept)
        assertEquals(MirrorDiff.Reason.CONFLICT_KEPT_SOURCE, plan.changes[0].reason)
        assertTrue("冲突说明要讲清为什么这么选", plan.conflicts[0].note.contains("没有时间戳"))
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
}
