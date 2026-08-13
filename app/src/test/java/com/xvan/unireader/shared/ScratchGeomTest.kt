package com.xvan.unireader.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿纸纯几何的 JVM 单测。断言写的是 Mac `ScratchPadModel.swift` / web `scratch.ts` 的行为——
 * 这些是**同一算法三份实现**，对不上就是「同一张纸在两端格子大小/可滚范围不一样」。
 */
class ScratchGeomTest {

    private val pen = Pen(20, 20, 20, 1f, 6f, 0)

    // ---------- 底纹步长（契约数：从 24 起按 2 的幂折到屏幕间距 [22,88]） ----------

    @Test
    fun 底纹步长按2的幂折进舒适区间() {
        assertEquals(24f, ScratchGeom.gridStep(1f), 0f)          // 24 ∈ [22,88]，不用折
        assertEquals(12f, ScratchGeom.gridStep(4f), 0f)          // 24×4=96 > 88 → 12×4=48
        assertEquals(6f, ScratchGeom.gridStep(8f), 0f)           // 12×8=96 > 88 → 6×8=48
        assertEquals(192f, ScratchGeom.gridStep(0.2f), 0f)       // 96×0.2=19.2 < 22 → 192×0.2=38.4
        assertEquals(96f, ScratchGeom.gridStep(0.25f), 0f)       // 96×0.25=24
        // 任何缩放级别下屏幕间距都必须落进 [22,88]
        for (z in listOf(0.2f, 0.33f, 0.5f, 1f, 1.7f, 3f, 5.5f, 8f)) {
            val d = ScratchGeom.gridStep(z) * z
            assertTrue("zoom=$z 间距 $d 应 ∈ [22,88]", d in 22f..88f)
        }
    }

    @Test
    fun 点阵方点边长同三端公式() {
        assertEquals(1.8f, ScratchGeom.dotSize(1f), 1e-6f)
        assertEquals(1.5f, ScratchGeom.dotSize(0.2f), 0f)        // 下限 1.5
        assertEquals(3f, ScratchGeom.dotSize(8f), 0f)            // 上限 3
    }

    // ---------- CSS rgba 解析（bg 是自由串，库里来的可能是任何写法） ----------

    @Test
    fun 解析CSS的rgba串() {
        assertArrayEquals(intArrayOf(255, 255, 255, 255), ScratchGeom.parseCssRgba("rgba(255,255,255,1.0)"))
        assertArrayEquals(intArrayOf(252, 247, 235, 255), ScratchGeom.parseCssRgba("rgba(252, 247, 235, 1)"))
        assertArrayEquals(intArrayOf(0, 128, 255, 255), ScratchGeom.parseCssRgba("rgb(0,128,255)"))
        assertArrayEquals(intArrayOf(10, 20, 30, 128), ScratchGeom.parseCssRgba("rgba(10,20,30,0.5)"))
        assertArrayEquals("越界分量要 clamp", intArrayOf(255, 0, 10, 255), ScratchGeom.parseCssRgba("rgba(300,-5,10,2)"))
    }

    @Test
    fun 坏串返回null由调用方兜白纸() {
        assertNull(ScratchGeom.parseCssRgba(""))
        assertNull(ScratchGeom.parseCssRgba("garbage"))
        assertNull(ScratchGeom.parseCssRgba("rgba(1,2)"))
        assertNull(ScratchGeom.parseCssRgba("#fff"))
    }

    @Test
    fun 底纹墨色由纸色明度推不跟外观走() {
        assertTrue(ScratchGeom.inkIsDark(255, 255, 255))         // 白纸 → 深纹
        assertTrue(ScratchGeom.inkIsDark(252, 247, 235))         // 米白 → 深纹
        assertTrue(!ScratchGeom.inkIsDark(20, 20, 20))           // 深纸 → 浅纹
    }

    // ---------- 内容包围盒 ----------

    @Test
    fun 包围盒含负坐标与活体点集() {
        assertNull(ScratchGeom.contentBounds(emptyList()))
        val s = Stroke(0, pen, listOf(Pt3(-120.5f, 30f, 0.5f), Pt3(40f, -60f, 0.5f)), padId = "p")
        val b = ScratchGeom.contentBounds(listOf(s))!!
        assertArrayEquals(floatArrayOf(-120.5f, -60f, 160.5f, 90f), b, 1e-4f)
        // 活体半笔也参与（擦到一半时软边界不能把正在写的地方框出去）
        val b2 = ScratchGeom.contentBounds(listOf(s), listOf(Pt3(500f, 0f, 0.5f)))!!
        assertArrayEquals(floatArrayOf(-120.5f, -60f, 620.5f, 90f), b2, 1e-4f)
    }

    // ---------- 软边界 ----------

    @Test
    fun 空纸只能在原点附近晃() {
        // 800×600 视口 zoom=1：可达域 = 原点外扩 1.5 屏 → x ∈ [-2000, 1200]、y ∈ [-1500, 900]
        val c = ScratchGeom.clampOrigin(5000f, -5000f, 1f, null, 800f, 600f)
        assertEquals(1200f, c[0], 1e-4f)
        assertEquals(-1500f, c[1], 1e-4f)
        // 回中位置（-400,-300）在域内不动
        val home = ScratchGeom.clampOrigin(-400f, -300f, 1f, null, 800f, 600f)
        assertArrayEquals(floatArrayOf(-400f, -300f), home, 1e-4f)
    }

    @Test
    fun 软边界贴着内容包围盒外扩一屏半() {
        // 内容 [100,100,200,100]，800×600 视口 zoom=1：slack=1200/900
        // x ∈ [100-1200-800, 100+200+1200] = [-1900, 1500]
        val content = floatArrayOf(100f, 100f, 200f, 100f)
        val c = ScratchGeom.clampOrigin(9999f, 0f, 1f, content, 800f, 600f)
        assertEquals(1500f, c[0], 1e-4f)
        val c2 = ScratchGeom.clampOrigin(-9999f, 0f, 1f, content, 800f, 600f)
        assertEquals(-1900f, c2[0], 1e-4f)
        // 域内不动
        val c3 = ScratchGeom.clampOrigin(0f, 0f, 1f, content, 800f, 600f)
        assertArrayEquals(floatArrayOf(0f, 0f), c3, 1e-4f)
    }

    // ---------- 回中 / 适应内容 / 锚点缩放 ----------

    @Test
    fun 回中原点落在视口正中() {
        val c = ScratchGeom.centeredOrigin(800f, 600f)
        assertArrayEquals(floatArrayOf(-400f, -300f), c, 1e-4f)
    }

    @Test
    fun 适应内容把包围盒装进视口且居中() {
        val f = ScratchGeom.fit(floatArrayOf(0f, 0f, 800f, 600f), 880f, 680f)!!
        // 留边 40×2 后可用 800×600 → zoom=1；origin = 内容中心 − 视口一半
        assertArrayEquals(floatArrayOf(-40f, -40f, 1f), f, 1e-4f)
        // 空内容 → null（调用方退化为回中）
        assertNull(ScratchGeom.fit(null, 880f, 680f))
        // 视口未就绪 → null
        assertNull(ScratchGeom.fit(floatArrayOf(0f, 0f, 10f, 10f), 0f, 0f))
    }

    @Test
    fun 适应内容的缩放夹在上下限() {
        val huge = ScratchGeom.fit(floatArrayOf(0f, 0f, 100000f, 100000f), 800f, 600f)!!
        assertEquals(ScratchGeom.MIN_ZOOM, huge[2], 1e-6f)
        val tiny = ScratchGeom.fit(floatArrayOf(0f, 0f, 1f, 1f), 800f, 600f)!!
        assertEquals(ScratchGeom.MAX_ZOOM, tiny[2], 1e-6f)
    }

    @Test
    fun 页面底图矩形按锚点落在画布原点() {
        // 三端契约：宽恒 800，高 = 800×aspect，锚点 (nx,ny) 落在画布原点 →
        // rect 原点 = (−nx·W, −ny·H)。对不上就是「同一张纸两端写的位置不一样」。
        val r = ScratchGeom.pageRect(0.25f, 0.5f, 1.5f)
        assertArrayEquals(floatArrayOf(-200f, -600f, 800f, 1200f), r, 1e-4f)
        // 拿不到页面尺寸（aspect<=0）按 A4 兜底，不能算出 0 高
        val fallback = ScratchGeom.pageRect(0f, 0f, 0f)
        assertEquals(800f * 1.4142f, fallback[3], 1e-3f)
    }

    @Test
    fun 页面底图也算内容() {
        val page = ScratchGeom.pageRect(0.5f, 0.5f, 1f)   // (-400,-400,800,800)
        // 空纸 + 垫页 → 包围盒就是页矩形（否则软边界只认笔迹，空白纸上垫了页也走不到页边）
        val onlyPage = ScratchGeom.contentBounds(emptyList(), null, page)!!
        assertArrayEquals(page, onlyPage, 1e-4f)
        // 笔迹在页外 → 并集覆盖两者
        val s = Stroke(0, Pen(0, 0, 0, 1f, 4f, 0), listOf(Pt3(1200f, -900f, 0.5f)))
        val both = ScratchGeom.contentBounds(listOf(s), null, page)!!
        assertArrayEquals(floatArrayOf(-400f, -900f, 1600f, 1300f), both, 1e-4f)
        // 不垫页时行为一字不变
        assertNull(ScratchGeom.contentBounds(emptyList(), null, null))
    }

    @Test
    fun 锚点缩放点下内容不动() {
        // origin=(10,20) zoom=2，锚 (100,50) 的画布点 = (60,45)；放大 1.5 倍后它必须还在 (100,50)
        val v = ScratchGeom.zoomAt(10f, 20f, 2f, 1.5f, 100f, 50f)
        assertEquals(3f, v[2], 1e-6f)
        assertEquals(100f, (60f - v[0]) * v[2], 1e-3f)
        assertEquals(50f, (45f - v[1]) * v[2], 1e-3f)
        // 撞到上下限：origin 不动（同 Mac 的 guard z != zoom）
        val cap = ScratchGeom.zoomAt(10f, 20f, 8f, 2f, 100f, 50f)
        assertArrayEquals(floatArrayOf(10f, 20f, 8f), cap, 1e-6f)
    }
}
