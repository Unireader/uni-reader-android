package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 双指「平移 / 缩放」逐帧拆分（[PinchSplit]）。模拟三种典型手势，逐帧喂两指坐标，看累计缩放：
 * 双指往上滚（手指自然并拢）不该缩放；对称捏合与一指按住一指外拉要照常缩放。
 */
class PinchSplitTest {

    /** 两指各自从起点线性走到终点，分 [n] 帧，返回累计缩放系数与指距实际比例 */
    private fun run(
        ax0: Float, ay0: Float, ax1: Float, ay1: Float,
        bx0: Float, by0: Float, bx1: Float, by1: Float,
        n: Int = 30,
    ): Pair<Float, Float> {
        val s = PinchSplit(2f)
        var zoom = 1f
        var pd = hypot(ax0 - bx0, ay0 - by0)
        var pmx = (ax0 + bx0) / 2f
        var pmy = (ay0 + by0) / 2f
        for (i in 1..n) {
            val t = i.toFloat() / n
            val ax = ax0 + (ax1 - ax0) * t; val ay = ay0 + (ay1 - ay0) * t
            val bx = bx0 + (bx1 - bx0) * t; val by = by0 + (by1 - by0) * t
            val d = hypot(ax - bx, ay - by)
            val mx = (ax + bx) / 2f; val my = (ay + by) / 2f
            zoom *= s.factor(pd, d, mx - pmx, my - pmy)
            pd = d; pmx = mx; pmy = my
        }
        return zoom to hypot(ax1 - bx1, ay1 - by1) / hypot(ax0 - bx0, ay0 - by0)
    }

    @Test
    fun 双指往上滚手指并拢不缩放() {
        // 两指相距 300px，向上滚 600px，途中各往里并拢 30px（指距 -20%）
        val (z, raw) = run(400f, 1200f, 430f, 600f, 700f, 1200f, 670f, 600f)
        assertTrue("指距确实变了：$raw", raw < 0.85f)
        assertEquals(1f, z, 0.01f)
    }

    @Test
    fun 对称捏合照常缩放() {
        val (z, raw) = run(500f, 800f, 400f, 800f, 700f, 800f, 800f, 800f)
        assertEquals(raw, z, 0.01f)
    }

    @Test
    fun 一指按住一指外拉照常缩放() {
        val (z, raw) = run(500f, 800f, 500f, 800f, 700f, 800f, 900f, 800f)
        assertEquals(raw, z, 0.01f)
    }

    @Test
    fun 边滚边捏只缩一部分不超过指距() {
        // 中点挪动与指距变化相当：缩放介于 1 与指距比例之间
        val (z, raw) = run(500f, 800f, 460f, 500f, 700f, 800f, 740f, 500f)
        assertTrue("z=$z raw=$raw", z in 1f..raw)
    }
}
