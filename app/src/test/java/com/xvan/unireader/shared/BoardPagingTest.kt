package com.xvan.unireader.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页画板的布局 / 模板契约（`../BOARD-NOTE-PLAN.md §9.2~9.3`）。
 * 断言逐条对应 Mac `spike/board-store-test.swift` 里「BoardLayout / BoardTemplateGeometry / BoardTemplate」那几项。
 */
class BoardPagingTest {

    @Test
    fun layoutContract() {
        val l = BoardPaging.Layout(595f, 842f, 3)
        // 第 0 页矩形 = (-W/2, 0, W, H)；第 2 页 y = 2 × (H + 24)
        assertEquals(-297.5f, l.originX())
        assertEquals(0f, l.originY(0))
        assertEquals(2 * (842f + 24f), l.originY(2))
        // 页号：空隙归上面那页、首之上 / 末之下夹住
        assertEquals(0, l.indexForY(850f))
        assertEquals(1, l.indexForY(866f))
        assertEquals(0, l.indexForY(-50f))
        assertEquals(2, l.indexForY(99_999f))
        // 全部页的包围盒
        assertArrayEquals(floatArrayOf(-297.5f, 0f, 595f, 3 * 866f - 24f), l.bounds(), 1e-4f)
        assertNull(BoardPaging.Layout(595f, 842f, 0).bounds())
    }

    @Test
    fun templateGeometry() {
        val lined = BoardPaging.shape(BoardPaging.T_LINED, 595f, 842f)
        // 横线：首条 y=72、左右各留 36；间距 28 到底 36 止
        assertArrayEquals(floatArrayOf(36f, 72f, 559f, 72f), lined.thin.copyOfRange(0, 4), 0f)
        assertEquals(((842 - 36 - 72) / 28 + 1) * 4, lined.thin.size)
        // 康奈尔：三条粗线、提示栏宽 30%（四舍五入）
        val corn = BoardPaging.shape(BoardPaging.T_CORNELL, 595f, 842f)
        assertEquals(3 * 4, corn.bold.size)
        assertEquals(179f, corn.bold[8])            // (595 × 0.3).rounded() = 178.5 → 179
        assertEquals(101f, corn.bold[1])            // (842 × 0.12).rounded() = 101
        assertEquals(674f, corn.bold[5])            // (842 × 0.80).rounded() = 673.6 → 674
        // 两栏：中线 x = (595 / 2).rounded() = 298
        assertEquals(298f, BoardPaging.shape(BoardPaging.T_TWO_COLUMN, 595f, 842f).bold[0])
        // 空白 / 未知值：什么都不画
        val blank = BoardPaging.shape(BoardPaging.T_BLANK, 595f, 842f)
        assertTrue(blank.thin.isEmpty() && blank.bold.isEmpty() && blank.dots.isEmpty())
        assertTrue(BoardPaging.shape(99, 595f, 842f).thin.isEmpty())
        // 点阵：20 步长、不贴边
        val dots = BoardPaging.shape(BoardPaging.T_DOTS, 595f, 842f)
        assertEquals(29 * 42 * 2, dots.dots.size)
    }

    @Test
    fun templateCodes() {
        assertEquals(BoardPaging.T_CORNELL, BoardPaging.templateCode("cornell"))
        assertEquals(BoardPaging.T_BLANK, BoardPaging.templateCode("whatever"))
        assertEquals("twoColumn", BoardPaging.templateRaw(5))
        assertEquals("blank", BoardPaging.templateRaw(99))
    }

    @Test
    fun pageSizes() {
        assertArrayEquals(floatArrayOf(595f, 842f), BoardPaging.pageSize(BoardPaging.SIZE_A4, false, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(842f, 595f), BoardPaging.pageSize(BoardPaging.SIZE_A4, true, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(420f, 595f), BoardPaging.pageSize(BoardPaging.SIZE_A5, false, 0f, 0f), 0f)
        assertArrayEquals(floatArrayOf(612f, 792f), BoardPaging.pageSize(BoardPaging.SIZE_LETTER, false, 0f, 0f), 0f)
        // 当前屏幕：短边为宽（横着拿的平板也是竖版），取整
        assertArrayEquals(
            floatArrayOf(800f, 1280f),
            BoardPaging.pageSize(BoardPaging.SIZE_SCREEN, false, 1280.4f, 800.2f),
            0f,
        )
    }
}
