package com.xvan.unireader.local.store

import com.xvan.unireader.shared.Pt3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 笔迹 payload 点集快读（[InkPointsScan]）。读出来的数值必须与原先 `JSONArray.optDouble(..).toFloat()`
 * 逐位相同——差一位，同一条笔迹在「整段解析」和「快读」两条路上就是两个不相等的 [com.xvan.unireader.shared.Stroke]，
 * 落库对账与块缓存都按内容比较。
 */
class InkPointsScanTest {

    private fun scan(s: String): List<Pt3>? {
        val r = InkPointsScan.span(s) ?: return null
        return InkPointsScan.points(s, r.first, r.last)
    }

    @Test
    fun Mac写的紧凑形态() {
        val s = """{"color":{"r":24,"g":90,"b":210,"a":0.95},"points":[[0.123456789,1e-3,0.5],[-12.5,3.25E2,1]],"width":2}"""
        val pts = scan(s)!!
        assertEquals(2, pts.size)
        assertEquals("0.123456789".toDouble().toFloat(), pts[0].x)
        assertEquals(0.001f, pts[0].y)
        assertEquals(-12.5f, pts[1].x)
        assertEquals(325f, pts[1].y)
        assertEquals(1f, pts[1].p)
    }

    @Test
    fun 带空白与缺压感() {
        val pts = scan("""{ "points" : [ [ 1 , 2 ] , [3,4,0.25] ] }""")!!
        assertEquals(Pt3(1f, 2f, 0.5f), pts[0])   // 缺压感按 0.5，同整段解析
        assertEquals(Pt3(3f, 4f, 0.25f), pts[1])
    }

    @Test
    fun 空点集() {
        assertEquals(emptyList<Pt3>(), scan("""{"points":[],"width":1}"""))
    }

    @Test
    fun 键名只出现在字符串值里不算() {
        // 转义引号里的 "points" 不是键；真正的键在后面
        val s = """{"note":"a \"points\" b","points":[[5,6,0.5]]}"""
        assertEquals(listOf(Pt3(5f, 6f, 0.5f)), scan(s))
    }

    @Test
    fun 认不出的形态交回整段解析() {
        assertNull(scan("""{"points":[[1,null,0.5]]}"""))
        assertNull(scan("""{"points":[{"x":1}]}"""))
        assertNull(scan("""{"width":1}"""))
    }

    @Test
    fun 在截断的点集上不越界() {
        assertNull(scan("""{"points":[[1,2,0.5],[3"""))
    }
}
