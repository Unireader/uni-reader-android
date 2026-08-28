package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画板模式页边软边界的 JVM 单测。
 *
 * [CanvasMargin] 是**同一算法三份实现**（Mac `Sources/App/CanvasMargin.swift` /
 * web `web/src/lib/shared.ts` 的 `canvasMarginFor` / 这里），断言写的是 Mac 端的行为
 * ——对不上就意味着「同一篇文档在两端页边不一样宽，页边笔迹的位置也就对不上」。
 * 用例与 Mac 的 `spike/canvas-margin-test.swift` 一一对应。
 */
class CanvasMarginTest {

    private val pen = Pen(24, 90, 210, 0.95f, 3f, 0)

    private fun stroke(vararg xy: Pair<Float, Float>) =
        Stroke(0, pen, xy.map { Pt3(it.first, it.second, 0.5f) })

    @Test
    fun 越界量取两侧最大值() {
        assertEquals(0f, CanvasMargin.overflow(emptyList()), 0f)
        assertEquals(0f, CanvasMargin.overflow(listOf(stroke(0.1f to 0.2f, 0.9f to 0.8f))), 0f)
        assertEquals(0.4f, CanvasMargin.overflow(listOf(stroke(-0.4f to 0.2f, 0.5f to 0.5f))), 1e-6f)
        assertEquals(0.7f, CanvasMargin.overflow(listOf(stroke(0.5f to 0.2f, 1.7f to 0.5f))), 1e-6f)
        assertEquals(
            0.9f,
            CanvasMargin.overflow(listOf(stroke(-0.2f to 0.1f), stroke(1.9f to 0.1f))),
            1e-6f,
        )
    }

    @Test
    fun 单点越界量() {
        assertEquals(0f, CanvasMargin.overflowOf(0.5f), 0f)
        assertEquals(0f, CanvasMargin.overflowOf(0f), 0f)
        assertEquals(0f, CanvasMargin.overflowOf(1f), 0f)
        assertEquals(0.25f, CanvasMargin.overflowOf(-0.25f), 1e-6f)
        assertEquals(0.5f, CanvasMargin.overflowOf(1.5f), 1e-6f)
    }

    @Test
    fun 页边宽度按档位跳且有上下限() {
        // 没写出去过 → 起步一档；+slack 不满一档不跳；过一档才跳
        assertEquals(0.5f, CanvasMargin.marginFor(0f), 0f)
        assertEquals(0.5f, CanvasMargin.marginFor(0.1f), 0f)
        assertEquals(1.0f, CanvasMargin.marginFor(0.2f), 0f)
        // 恰在档位上（0.15+0.35=0.5）不多跳一档
        assertEquals(0.5f, CanvasMargin.marginFor(0.15f), 0f)
        assertEquals(2.0f, CanvasMargin.marginFor(1.2f), 0f)
        // 坏数据夹到上限；负越界量按 0 处理
        assertEquals(CanvasMargin.LIMIT, CanvasMargin.marginFor(100f), 0f)
        assertEquals(0.5f, CanvasMargin.marginFor(-5f), 0f)
    }

    @Test
    fun 越界越多页边越宽单调不减() {
        var last = 0f
        for (i in 0..40) {
            val m = CanvasMargin.marginFor(i * 0.1f)
            assertTrue("i=$i", m >= last)
            last = m
        }
    }
}
