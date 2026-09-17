package com.xvan.unireader.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.tan

/**
 * 扫描页对齐的读端纯逻辑（[PageAlign] / [ScanAlignTable]，方案 `../SCAN-ALIGN-PLAN.md` §2/§3）。
 *
 * 与 Mac `spike/scan-align-test.swift` 的 ①（变换）②（payload）⑤（安卓 Matrix 系数）三段对应；
 * ③④ 是测量算法，只在 Mac 跑，本端没有。
 *
 * 🔴 **跨端向量**：[SAMPLE] 就是 Mac 那份 spike 里 `ScanAlignTable(width: 497.5, pages: …)` 编码出来的字节，
 * 它的戳 `7438e8a2` 在 Mac 上用 `printf '%s' '<payload>' | shasum -a 256` 与 Swift 实现各算一遍、两者一致后抄进来。
 * 两端对同一份 payload 算出的戳不一样 = 页图缓存键对不上，改算法前先看这条。
 */
class ScanAlignTest {

    private val pa = PageAlign(rot = 0.9 * PI / 180, dx = -7.25, dy = 1.5, sw = 504.7, sh = 707.75, w = 497.5)

    private fun near(a: DoubleArray, bx: Double, by: Double, eps: Double = 1e-9) =
        kotlin.math.abs(a[0] - bx) <= eps && kotlin.math.abs(a[1] - by) <= eps

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    // ---------- ① 每页变换 ----------

    @Test
    fun 正逆变换互逆() {
        val pts = listOf(0.0 to 0.0, 504.7 to 707.75, 123.4 to 456.7, -30.0 to 800.0)
        for ((x, y) in pts) {
            val q = pa.toAligned(x, y)
            assertTrue("toRaw(toAligned($x,$y))", near(pa.toRaw(q[0], q[1]), x, y))
            val p = pa.toRaw(x, y)
            assertTrue("toAligned(toRaw($x,$y))", near(pa.toAligned(p[0], p[1]), x, y))
        }
    }

    @Test
    fun 原始页中心落到W一半加dx() {
        assertTrue(near(pa.toAligned(504.7 / 2, 707.75 / 2), 497.5 / 2 - 7.25, 707.75 / 2 + 1.5))
    }

    @Test
    fun 零参数只做水平居中() {
        // 页宽 504.7 放进 497.5 → 整体往左挪 3.6，不转
        val z = PageAlign(0.0, 0.0, 0.0, 504.7, 700.0, 497.5)
        assertTrue(near(z.toAligned(10.0, 20.0), 10 - 3.6, 20.0))
    }

    @Test
    fun 正rot把往右往下斜的行转平() {
        val a = pa.toAligned(100.0, 300.0)
        val b = pa.toAligned(400.0, 300 + 300 * tan(pa.rot))
        assertEquals(a[1], b[1], 1e-9)
    }

    // ---------- ⑤ 安卓 Matrix 系数 ----------

    @Test
    fun Matrix系数等价于正变换() {
        val m = pa.matrixValues()
        assertEquals(6, m.size)
        for ((x, y) in listOf(321.5 to 88.25, 0.0 to 0.0, 504.7 to 707.75)) {
            val mx = m[0] * x + m[1] * y + m[2]
            val my = m[3] * x + m[4] * y + m[5]
            assertTrue("($x,$y)", near(pa.toAligned(x, y), mx, my))
        }
    }

    // ---------- ② payload ----------

    @Test
    fun 解码Mac写的样例() {
        val t = ScanAlignTable.decode(bytes(SAMPLE), 2)
        assertNotNull(t)
        t!!
        assertEquals(497.5, t.width, 0.0)
        assertEquals(2, t.pageCount)
        assertEquals(ScanAlignTable.Page(0.0123457, -9.25, 0.0, 506.9, 720.0), t.pages[0])
        assertEquals(ScanAlignTable.Page(-0.0063, 0.75, 0.0, 489.6, 706.7), t.pages[1])
        assertEquals(PageAlign(-0.0063, 0.75, 0.0, 489.6, 706.7, 497.5), t.page(1))
        assertNull(t.page(2))
        assertNull(t.page(-1))
        assertArrayEquals("payload 原字节不动", bytes(SAMPLE), t.payload)
    }

    @Test
    fun 戳与Mac一致() {
        val t = ScanAlignTable.decode(bytes(SAMPLE), 2)!!
        assertEquals("7438e8a2", t.stamp)   // Mac shasum / Swift ScanAlignTable 实算（见类注释）
        assertEquals(t.stamp, ScanAlignTable.stamp(bytes(SAMPLE)))
        assertTrue(t.stamp.length == 8 && t.stamp.all { it in "0123456789abcdef" })
    }

    @Test
    fun 戳按原字节算不重编码() {
        // 别的端写的：键序 / 数字格式都不同（同 Mac spike 那条）
        val foreign = """{"pages":[[0.01,-3,0,500,700]],"w":500.0,"v":1}"""
        val f = ScanAlignTable.decode(bytes(foreign), 1)
        assertNotNull(f)
        assertEquals("9b16e157", f!!.stamp)   // 同样在 Mac 上 shasum 实算
        assertEquals(ScanAlignTable.stamp(bytes(foreign)), f.stamp)
        // 数值一样、字节不一样 → 戳不一样（不重编码的直接后果）
        val sameValues = """{"v":1,"w":500,"pages":[[0.01,-3,0,500,700]]}"""
        val g = ScanAlignTable.decode(bytes(sameValues), 1)!!
        assertEquals(f.pages, g.pages)
        assertNotEquals(f.stamp, g.stamp)
        assertNotEquals(f, g)
        assertEquals(g, ScanAlignTable.decode(bytes(sameValues), 1))
    }

    @Test
    fun 非法payload一律拒收() {
        fun rej(json: String, n: Int, why: String) = assertNull(why, ScanAlignTable.decode(bytes(json), n))
        rej(SAMPLE, 3, "页数对不上")
        rej(SAMPLE, 1, "页数对不上（少）")
        rej("""{"v":2,"w":497.5,"pages":[]}""", 0, "不认识的格式版本")
        rej("""{"w":497.5,"pages":[]}""", 0, "缺 v")
        rej("""{"v":1,"w":0,"pages":[]}""", 0, "w = 0")
        rej("""{"v":1,"w":-500,"pages":[]}""", 0, "w < 0")
        rej("""{"v":1,"pages":[]}""", 0, "缺 w")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,0,700]]}""", 1, "sw = 0")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,-1,700]]}""", 1, "sw < 0")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,500,0]]}""", 1, "sh = 0")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,500]]}""", 1, "少一列")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,500,700,null]]}""", 1, "行里混进 null（Swift [[NSNumber]] 强转失败）")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,500,"700"]]}""", 1, "数写成字符串")
        rej("""{"v":1,"w":500,"pages":[[0,0,0,500,1e999]]}""", 1, "非有限值")
        rej("""{"v":1,"w":500,"pages":{}}""", 0, "pages 不是数组")
        rej("""{"v":1,"w":500,"pages":[5]}""", 1, "行不是数组")
        rej("""[1,2]""", 0, "顶层不是对象")
        rej("""{"v":1,"w":500,"pages":[]} x""", 0, "尾部有垃圾")
        rej("""{"v":1,"w":500,"pages":[[01,0,0,500,700]]}""", 1, "前导零不是合法 JSON")
        rej("", 0, "空")
        assertNull("非法 UTF-8", ScanAlignTable.decode(byteArrayOf(0x7B, 0xFF.toByte(), 0x7D), 0))
    }

    @Test
    fun 宽松处与Swift实现一致() {
        // 以下两条在 Mac 上用 Sources/App/ScanAlign.swift 实跑过：都被接受（NSNumber.intValue 截断 / 布尔是 NSNumber）
        assertNotNull(ScanAlignTable.decode(bytes("""{"v":1.9,"w":500,"pages":[[0,0,0,500,700]]}"""), 1))
        assertNotNull(ScanAlignTable.decode(bytes("""{"v":true,"w":500,"pages":[[0,0,0,500,700]]}"""), 1))
        // 多出来的列不看，未知键不看，空白随意
        assertNotNull(ScanAlignTable.decode(bytes(""" { "x" : "é" , "v":1,"w":5e2,"pages":[ [0,0,0,500,700,9] ] } """), 1))
        assertNotNull(ScanAlignTable.decode(bytes("""{"v":1,"w":500,"pages":[]}"""), 0))
    }

    @Test
    fun 显示身份() {
        val t = ScanAlignTable.decode(bytes(SAMPLE), 2)!!
        assertEquals("abc", ScanAlignTable.displayKey("abc", null))
        assertEquals("abc~a7438e8a2", ScanAlignTable.displayKey("abc", t))
        assertEquals("", ScanAlignTable.displayKey("", t))   // 没有内容 hash 就不拼戳（同 Mac）
        assertFalse(ScanAlignTable.displayKey("abc", t).contains("#"))
    }

    companion object {
        /** Mac `spike/scan-align-test.swift` ② 段编码出来的原字节（`print("payload = …")`） */
        const val SAMPLE = """{"v":1,"w":497.5,"pages":[[0.0123457,-9.25,0,506.9,720],[-0.0063,0.75,0,489.6,706.7]]}"""
    }
}
