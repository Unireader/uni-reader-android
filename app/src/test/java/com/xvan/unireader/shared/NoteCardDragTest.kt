package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拖笔记卡片的纯函数（规则同 Mac `NoteCardDrag`）。页 1000×1400，图钉在 (100,100)，卡片 (130,90) 300×120，unit=2。 */
class NoteCardDragTest {
    private val move = NoteCardDrag.Zone(0, 0)
    private fun drag(
        zone: NoteCardDrag.Zone, tx: Float, ty: Float, card: NoteCard? = null,
        contentH: Float = 120f, clearance: Float = 0f,
    ) = NoteCardDrag.card(
        zone, 130f, 90f, 300f, 120f, contentH, card, tx, ty, unit = 2f, pinX = 100f, pinY = 100f,
        minW = 80f, minH = 40f, pageW = 1000f, pageH = 1400f, pinClearance = clearance,
    )

    @Test
    fun zones() {
        fun z(x: Float, y: Float) = NoteCardDrag.zoneAt(x, y, 300f, 120f, corner = 30f, edge = 14f)
        assertEquals(NoteCardDrag.Zone(-1, -1), z(5f, 5f))
        assertEquals(NoteCardDrag.Zone(1, 1), z(290f, 110f))
        assertEquals(NoteCardDrag.Zone(1, 0), z(292f, 60f))
        assertEquals(NoteCardDrag.Zone(0, 1), z(150f, 112f))
        assertEquals(NoteCardDrag.Zone(-1, 0), z(8f, 60f))
        assertTrue(z(150f, 60f).isMove)
    }

    @Test
    fun moveKeepsSizeAndClamps() {
        val c = drag(move, 100f, 50f)
        assertEquals((230f - 100f) / 2f, c.dx, 1e-4f)
        assertEquals((140f - 100f) / 2f, c.dy, 1e-4f)
        assertNull("没改过大小的那一维不动", c.w)
        assertNull(c.h)
        // 存着的宽 / 高原样留着
        val kept = drag(move, 10f, 0f, card = NoteCard(0f, 0f, 150f, 60f))
        assertEquals(150f, kept.w!!, 1e-4f)
        assertEquals(60f, kept.h!!, 1e-4f)
        // 拖出页外：钳在页内
        val far = drag(move, 5000f, 5000f)
        assertEquals((1000f - 300f - 100f) / 2f, far.dx, 1e-4f)
        assertEquals((1400f - 120f - 100f) / 2f, far.dy, 1e-4f)
    }

    @Test
    fun resize() {
        // 拖右边：只改宽，位置不动
        var c = drag(NoteCardDrag.Zone(1, 0), 60f, 0f)
        assertEquals(180f, c.w!!, 1e-4f)
        assertNull(c.h)
        assertEquals(15f, c.dx, 1e-4f)
        // 拖左边：右边不动
        c = drag(NoteCardDrag.Zone(-1, 0), 50f, 0f)
        assertEquals(125f, c.w!!, 1e-4f)
        assertEquals((180f - 100f) / 2f, c.dx, 1e-4f)
        // 最小宽
        c = drag(NoteCardDrag.Zone(1, 0), -1000f, 0f)
        assertEquals(40f, c.w!!, 1e-4f)
        // 拖下边：高度上限；内容比上限短时卡片不会比内容高，但上限照存
        c = drag(NoteCardDrag.Zone(0, 1), 0f, 80f, contentH = 150f)
        assertEquals(100f, c.h!!, 1e-4f)
        assertNull(c.w)
        // 拖上边：下边不动（可见高 = min(内容, 上限)）
        c = drag(NoteCardDrag.Zone(0, -1), 0f, 40f)
        assertEquals(40f, c.h!!, 1e-4f)
        assertEquals((130f - 100f) / 2f, c.dy, 1e-4f)
    }

    @Test
    fun keepsOffPin() {
        // 往左上拖到压住图钉：整块挪开（贴着禁区的某一边），不盖住图钉
        val c = drag(move, -100f, 0f, clearance = 20f)
        val x = 100f + c.dx * 2f
        val y = 100f + c.dy * 2f
        val over = x < 120f && x + 300f > 80f && y < 120f && y + 120f > 80f
        assertTrue("没压住图钉 ($x,$y)", !over)
        // 没压住时原样
        val free = drag(move, 200f, 200f, clearance = 20f)
        assertEquals((330f - 100f) / 2f, free.dx, 1e-4f)
    }
}
