package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯函数的 JVM 单测（不碰 Android 框架，所以能跑在 `:app:testDebugUnitTest` 里）。
 *
 * 这几个函数是**同一算法多份实现**（Mac/网页/安卓各一份），断言写的是 Mac 端
 * `Sources/App/InkEdit.swift` 的行为——对不上就意味着「同一个工作区在两端形状不同」。
 */
class InkEditTest {

    private fun assertRect(exp: DoubleArray, got: DoubleArray, msg: String) {
        for (i in 0 until 4) assertEquals("$msg [$i]", exp[i], got[i], 1e-9)
    }

    @Test
    fun 点集平移逐点clamp而不是整条推回来() {
        val pts = listOf(Pt3(0.1f, 0.05f, 0.4f), Pt3(0.3f, 0.30f, 0.9f))
        val out = InkEdit.translated(pts, 0f, -0.2f)
        // 首点被页顶挡住停在 0，次点照常上移 → 整条被压扁（同 Mac 的 min(1,max(0,…))）
        assertEquals(0f, out[0].y, 0f)
        assertEquals(0.1f, out[1].y, 1e-6f)
        assertEquals("压感不参与平移", 0.4f, out[0].p, 0f)
    }

    @Test
    fun 矩形平移两个角各自clamp() {
        // 未越界：整体平移，尺寸不变
        assertRect(
            doubleArrayOf(0.3, 0.4, 0.2, 0.1),
            InkEdit.translatedRect(doubleArrayOf(0.1, 0.2, 0.2, 0.1), 0.2, 0.2),
            "未越界时尺寸不变",
        )
        // 上边界外：y 停在 0，高度收缩（0.1 → 0.05），不是整块推回来
        assertRect(
            doubleArrayOf(0.1, 0.0, 0.2, 0.05),
            InkEdit.translatedRect(doubleArrayOf(0.1, 0.05, 0.2, 0.1), 0.0, -0.1),
            "顶边越界要收缩高度",
        )
        // 右下角外：宽高一起收缩
        assertRect(
            doubleArrayOf(0.8, 0.9, 0.2, 0.1),
            InkEdit.translatedRect(doubleArrayOf(0.7, 0.85, 0.3, 0.2), 0.1, 0.05),
            "右下越界要收缩宽高",
        )
        // 整块被推出页外：退化成贴边的零尺寸矩形（而不是负宽高）
        assertRect(
            doubleArrayOf(1.0, 0.5, 0.0, 0.1),
            InkEdit.translatedRect(doubleArrayOf(0.7, 0.5, 0.2, 0.1), 0.5, 0.0),
            "全出界退化成零宽",
        )
    }

    @Test
    fun 点注解的零尺寸anchor照样平移() {
        assertRect(
            doubleArrayOf(0.6, 0.7, 0.0, 0.0),
            InkEdit.translatedRect(doubleArrayOf(0.5, 0.5, 0.0, 0.0), 0.1, 0.2),
            "零尺寸 anchor 平移后仍是零尺寸",
        )
    }

    // ---------- splitStroke（局部擦除切段） ----------

    private val pen = Pen(20, 20, 20, 1f, 6f, 0)

    @Test
    fun 切段继承id与layerId与padId() {
        // 草稿纸笔迹：page 恒 0，pts 是画布坐标（可负无界），padId 指回 scratch_pad.id。
        // 漏带 padId 的话被擦过的笔迹会当场从界面消失、却以 kind=2 污染页内笔迹（handoff §2.5）。
        val s = Stroke(
            page = 0, pen = pen,
            pts = (0 until 6).map { Pt3(it * 10f - 25f, 0f, 0.5f) },   // -25 .. 25，画布坐标
            id = "note-id", layerId = "layer-id", padId = "pad-id",
        )
        val segs = InkEdit.splitStroke(s, nx = 5f, ny = 0f, page = 0, r2 = 100f)   // 擦掉 x=5 附近的点
        assertEquals("x∈{-5,5,15} 被剔除，剩两段", 2, segs.size)
        assertEquals(listOf(-25f, -15f), segs[0].pts.map { it.x })
        assertEquals(listOf(25f), segs[1].pts.map { it.x })
        for (seg in segs) {
            assertEquals("note-id", seg.id)
            assertEquals("layer-id", seg.layerId)
            assertEquals("每一段都必须继承 padId", "pad-id", seg.padId)
            assertEquals(pen, seg.pen)
        }
    }

    @Test
    fun 没擦到就原样返回同一实例() {
        val s = Stroke(2, pen, listOf(Pt3(0.5f, 0.5f, 0.5f)), id = "a")
        val out = InkEdit.splitStroke(s, nx = 0.9f, ny = 0.9f, page = 2, r2 = 0.01f)
        assertEquals(1, out.size)
        assertTrue("零变化必须返回原实例（调用方按 === 判零变化）", out[0] === s)
    }

    @Test
    fun 全部命中返回空表即整笔消除() {
        val s = Stroke(0, pen, listOf(Pt3(0f, 0f, 0.5f), Pt3(0.1f, 0f, 0.5f)), padId = "p")
        assertTrue(InkEdit.splitStroke(s, 0f, 0f, 0, r2 = 1f).isEmpty())
    }

    @Test
    fun 页过滤在函数内做别的页原样返回() {
        val s = Stroke(3, pen, listOf(Pt3(0.5f, 0.5f, 0.5f)))
        val out = InkEdit.splitStroke(s, nx = 0.5f, ny = 0.5f, page = 1, r2 = 1f)
        assertEquals(1, out.size)
        assertTrue(out[0] === s)
    }
}
