package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 跳转历史的三条规则 + 游标 + 上限（规则同 Mac `JumpHistory.swift`）。 */
class JumpHistoryTest {
    private fun r(p: Int, f: Float = 0f) = JumpMark(p, f, JumpKind.READING)
    private fun t(p: Int, label: String = "") = JumpMark(p, 0f, JumpKind.TOC, label)
    private fun s(p: Int, word: String) = JumpMark(p, 0f, JumpKind.SEARCH, word)
    private fun JumpHistory.pages() = marks.map { it.page }

    @Test
    fun recordAndWalk() {
        val h = JumpHistory()
        assertFalse(h.canGoBack || h.canGoForward)
        assertNull(h.back())
        h.record(r(3, 0.4f), t(10))            // 空历史：先垫离开点
        assertEquals(listOf(3, 10), h.pages())
        assertEquals(1, h.cursor)
        h.record(r(10), t(20))                 // 离开点就是当前这条：不重复垫
        assertEquals(listOf(3, 10, 20), h.pages())
        h.record(r(22, 0.5f), t(30))           // 跳完自己滚了一段再跳：把滚到的地方垫进去
        assertEquals(listOf(3, 10, 20, 22, 30), h.pages())
        assertEquals(22, h.back()?.page)
        assertEquals(20, h.back()?.page)
        assertTrue(h.canGoForward)
        assertEquals(22, h.forward()?.page)
        // 后退到中途再跳：前进分支作废
        h.back()
        h.record(r(20), t(50))
        assertEquals(listOf(3, 10, 20, 50), h.pages())
        assertFalse(h.canGoForward)
    }

    @Test
    fun samePlaceAndSearchFold() {
        val h = JumpHistory()
        h.record(r(1), t(5, "第一章"))
        val id = h.current!!.id
        h.record(r(5), t(5, "第一章 再点一次"))   // 落到同一处：就地更新、id 不变
        assertEquals(listOf(1, 5), h.pages())
        assertEquals(id, h.current!!.id)
        assertEquals("第一章 再点一次", h.current!!.label)
        // 同一个搜索词（含前缀）的连续命中只留一条；换词才新增
        h.record(r(5), s(7, "傅"))
        h.record(r(7), s(8, "傅里"))
        h.record(r(8), s(9, "傅里叶"))
        assertEquals(listOf(1, 5, 9), h.pages())
        h.record(r(9), s(12, "卷积"))
        assertEquals(listOf(1, 5, 9, 12), h.pages())
        assertEquals(5, h.goTo(id)?.page)
        assertEquals(1, h.cursor)
        assertNull(h.goTo(-1))
    }

    @Test
    fun limit() {
        val h = JumpHistory()
        for (i in 0 until 100) h.record(r(i * 2), t(i * 2 + 1))
        assertEquals(JumpHistory.LIMIT, h.marks.size)
        assertEquals(JumpHistory.LIMIT - 1, h.cursor)
        assertEquals(199, h.current?.page)
    }
}
