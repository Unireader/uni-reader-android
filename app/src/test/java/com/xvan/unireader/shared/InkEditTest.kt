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

    // ---------- scaled（框选缩放） ----------

    @Test
    fun 点集绕锚点按轴缩放并逐点clamp() {
        // 锚点 (0.5,0.5)，sx=2、sy=0.5：x 向翻倍（越界的逐点停在页边），y 向减半
        val s = Stroke(0, pen, listOf(Pt3(0.6f, 0.6f, 0.4f), Pt3(0.9f, 0.7f, 0.9f)), id = "a")
        val out = InkEdit.scaled(s, 0.5f, 0.5f, 2f, 0.5f)
        assertEquals(0.7f, out.pts[0].x, 1e-6f)
        assertEquals(0.55f, out.pts[0].y, 1e-6f)
        assertEquals("越界点 clamp 在页边，不是整条推回来", 1f, out.pts[1].x, 0f)
        assertEquals(0.6f, out.pts[1].y, 1e-6f)
        assertEquals("压感不参与缩放", 0.4f, out.pts[0].p, 0f)
        assertEquals("a", out.id)
    }

    @Test
    fun 缩放继承id与layerId与padId() {
        val s = Stroke(
            page = 0, pen = pen,
            pts = listOf(Pt3(0.4f, 0.4f, 0.5f), Pt3(0.6f, 0.6f, 0.5f)),
            id = "note-id", layerId = "layer-id", padId = "pad-id",
        )
        val out = InkEdit.scaled(s, 0.5f, 0.5f, 1.5f, 1.5f)
        assertEquals("note-id", out.id)
        assertEquals("layer-id", out.layerId)
        assertEquals("pad-id", out.padId)
    }

    @Test
    fun 线宽按几何平均缩放并clamp到半到四十() {
        val w = { width: Float, sx: Float, sy: Float ->
            InkEdit.scaled(Stroke(0, pen.copy(w = width), listOf(Pt3(0.5f, 0.5f, 0.5f))), 0f, 0f, sx, sy).pen.w
        }
        // √(2×0.5)=1：等比平均不变，线宽不动
        assertEquals(6f, w(6f, 2f, 0.5f), 1e-6f)
        // √(4×4)=4：6→24
        assertEquals(24f, w(6f, 4f, 4f), 1e-6f)
        // 缩没防护：6×√(0.05×0.05)=0.3 → clamp 0.5
        assertEquals(0.5f, w(6f, 0.05f, 0.05f), 1e-6f)
        // 撑爆防护：20×√(4×4)=80 → clamp 40
        assertEquals(40f, w(20f, 4f, 4f), 1e-6f)
    }

    @Test
    fun 矩形缩放两个角各绕锚点clamp() {
        // 未越界：绕 (0,0) 放大两倍，尺寸跟着翻倍
        assertRect(
            doubleArrayOf(0.2, 0.4, 0.4, 0.2),
            InkEdit.scaledRect(doubleArrayOf(0.1, 0.2, 0.2, 0.1), 0.0, 0.0, 2.0, 2.0),
            "未越界时尺寸按倍率变化",
        )
        // 右下越界：角停在 1，宽高收缩（同 translatedRect 的两角口径）
        // (0.3,0.3,0.4,0.4) 绕原点 ×2 → 角 (0.6,0.6) 与 (1.4,1.4) clamp 成 (1,1)：宽/高 0.8→0.4
        assertRect(
            doubleArrayOf(0.6, 0.6, 0.4, 0.4),
            InkEdit.scaledRect(doubleArrayOf(0.3, 0.3, 0.4, 0.4), 0.0, 0.0, 2.0, 2.0),
            "越界角 clamp 在页边",
        )
        // 单轴缩放：另一轴不动（边中点手柄的提交语义）
        assertRect(
            doubleArrayOf(0.2, 0.2, 0.4, 0.1),
            InkEdit.scaledRect(doubleArrayOf(0.1, 0.2, 0.2, 0.1), 0.0, 0.0, 2.0, 1.0),
            "sy=1 时 y 与高度不变",
        )
        // 点注解的零尺寸 anchor 照样缩放
        assertRect(
            doubleArrayOf(0.7, 0.4, 0.0, 0.0),
            InkEdit.scaledRect(doubleArrayOf(0.9, 0.6, 0.0, 0.0), 0.5, 0.2, 0.5, 0.5),
            "零尺寸 anchor 缩放后仍是零尺寸",
        )
    }

    // ---------- pointInPolygon（自由框选命中） ----------

    private val unitSquare = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    @Test
    fun 多边形内外判定() {
        assertTrue(InkEdit.pointInPolygon(0.5f, 0.5f, unitSquare))
        assertTrue(!InkEdit.pointInPolygon(1.5f, 0.5f, unitSquare))
        assertTrue(!InkEdit.pointInPolygon(0.5f, -0.1f, unitSquare))
        // 凹多边形：凹陷处判外（矩形判定会把这里误判成内）
        val concave = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0.5f, 0.5f, 0f, 1f)
        assertTrue(!InkEdit.pointInPolygon(0.5f, 0.8f, concave))
        assertTrue(InkEdit.pointInPolygon(0.2f, 0.5f, concave))
    }

    @Test
    fun 边界上的点算内() {
        assertTrue("顶点", InkEdit.pointInPolygon(0f, 0f, unitSquare))
        assertTrue("边上", InkEdit.pointInPolygon(0.5f, 0f, unitSquare))
    }

    @Test
    fun 少于三个点不构成选区() {
        assertTrue(!InkEdit.pointInPolygon(0.5f, 0.5f, floatArrayOf(0f, 0f, 1f, 1f)))
        assertTrue(!InkEdit.pointInPolygon(0.5f, 0.5f, floatArrayOf(0.5f, 0.5f)))
        assertTrue(!InkEdit.pointInPolygon(0.5f, 0.5f, FloatArray(0)))
    }

    // ---------- bounds / fitTranslation（框选整团平移不变形） ----------
    //
    // 🔴 钉的是用户 2026-08-30 在平板上报的「画板模式下框选移动把笔迹压缩了」：逐点 clamp 的
    // translated 单独用时越界那一头会被摁成一条线；先过 fitTranslation 夹位移就不会。
    // 断言与 Mac `spike/ink-edit-test.swift` 同一批数字（两条权威路径必须形状一致）。

    private fun st(vararg pts: Pt3) = Stroke(1, pen, pts.toList(), id = "s")

    @Test
    fun 包围盒按点算不被页角撑大() {
        assertTrue("空集 → null", InkEdit.bounds(emptyList()) == null)
        assertTrue("无点的笔画 → null", InkEdit.bounds(listOf(st())) == null)
        val b = InkEdit.bounds(listOf(st(Pt3(1.2f, 0.3f, 1f), Pt3(1.8f, 0.5f, 1f)), st(Pt3(1.4f, 0.2f, 1f))))!!
        assertEquals(1.2f, b[0], 1e-6f); assertEquals(0.2f, b[1], 1e-6f)
        assertEquals(1.8f, b[2], 1e-6f); assertEquals(0.5f, b[3], 1e-6f)
    }

    @Test
    fun 整团平移撞边界只停不变形() {
        val far = InkEdit.bounds(listOf(st(Pt3(1.2f, 0.3f, 1f), Pt3(1.8f, 0.5f, 1f))))
        // 页边上限 2.0（xMargin=1）时想右移 0.5：位移夹成 0.2，两点间距仍是 0.6
        val d = InkEdit.fitTranslation(0.5f, 0f, far, xMargin = 1f)
        assertEquals("撞上界 → 位移夹成 0.2", 0.2f, d[0], 1e-6f)
        val moved = InkEdit.translated(listOf(Pt3(1.2f, 0.3f, 1f), Pt3(1.8f, 0.5f, 1f)), d[0], d[1], 1f)
        assertEquals("形状不变", 0.6f, moved[1].x - moved[0].x, 1e-6f)
        // 反例存档：不夹位移直接平移就是那个 bug（间距 0.6 → 0.3）
        val squashed = InkEdit.translated(listOf(Pt3(1.2f, 0.3f, 1f), Pt3(1.8f, 0.5f, 1f)), 0.5f, 0f, 1f)
        assertEquals("反例：被压扁", 0.3f, squashed[1].x - squashed[0].x, 1e-6f)
        // 区间够宽 → 原样通过
        assertEquals(0.5f, InkEdit.fitTranslation(0.5f, 0f, far, xMargin = 8f)[0], 1e-6f)
        // y 恒按页内 0…1 夹（页边只横向延伸）
        assertEquals(0.5f, InkEdit.fitTranslation(0f, 0.8f, far, xMargin = 8f)[1], 1e-6f)
        assertEquals(-0.3f, InkEdit.fitTranslation(0f, -0.9f, far, xMargin = 8f)[1], 1e-6f)
    }

    @Test
    fun 同选注解时取交集且退化情形不夹() {
        // 注解不能被拖出页：笔迹还能走很远，但注解 maxX 0.9 只准再走 0.1
        val ink = InkEdit.bounds(listOf(st(Pt3(0.1f, 0.5f, 1f))))
        val note = floatArrayOf(0.7f, 0.5f, 0.9f, 0.55f)
        assertEquals(0.1f, InkEdit.fitTranslation(0.5f, 0f, ink, 8f, note)[0], 1e-6f)
        // 只选中注解
        assertEquals(-0.2f, InkEdit.fitTranslation(-0.5f, 0f, null, 8f, floatArrayOf(0.2f, 0.5f, 0.4f, 0.55f))[0], 1e-6f)
        // 两类都没有 → 原样返回（调用方自己 guard）
        assertEquals(0.3f, InkEdit.fitTranslation(0.3f, 0.3f, null, 8f)[0], 1e-6f)
        // 选中集比可写区间还宽 → 不夹（保形优先，逐点 clamp 兜底）
        val wide = InkEdit.bounds(listOf(st(Pt3(-3f, 0.3f, 1f), Pt3(3f, 0.5f, 1f))))
        assertEquals(0.5f, InkEdit.fitTranslation(0.5f, 0f, wide, xMargin = 0f)[0], 1e-6f)
    }
}
