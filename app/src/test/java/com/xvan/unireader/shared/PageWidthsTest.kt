package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宽度档位是**跨端契约**：Mac 的 `Sources/Server/LANServer.swift` 有一份同样的阶梯，
 * 客户端按它归档后写进 `?w=`，服务端再归一次。两边的阶梯一旦飘掉，双方缓存永远不命中
 * （客户端按 2160 存、服务端按 2880 渲）。这组用例把阶梯本身钉住。
 */
class PageWidthsTest {

    @Test
    fun `阶梯与 Mac 端一致且单调递增`() {
        assertEquals(listOf(480, 720, 1080, 1440, 2160, 2880), PageWidths.STEPS.toList())
        assertTrue(PageWidths.STEPS.zip(PageWidths.STEPS.drop(1)).all { (a, b) -> a < b })
    }

    @Test
    fun `归到不小于目标的最近档位`() {
        assertEquals(480, PageWidths.snap(1))
        assertEquals(480, PageWidths.snap(480))
        assertEquals(720, PageWidths.snap(481))
        assertEquals(2160, PageWidths.snap(1441))   // Pad 6 竖屏 1800 → 2160
        assertEquals(2880, PageWidths.snap(2161))   // Pad 6 横屏 2880 → 2880
    }

    @Test
    fun `超出最大档位就封顶，不无限放大内存`() {
        assertEquals(2880, PageWidths.snap(2881))
        assertEquals(2880, PageWidths.snap(100_000))
    }

    @Test
    fun `非正数不炸，落到最小档位`() {
        assertEquals(480, PageWidths.snap(0))
        assertEquals(480, PageWidths.snap(-100))
    }

    @Test
    fun `两趟取图：目标档不高于低清档时不必分两趟`() {
        assertEquals(0, PageWidths.previewFor(480))
        assertEquals(0, PageWidths.previewFor(PageWidths.PREVIEW))
        assertEquals(PageWidths.PREVIEW, PageWidths.previewFor(1440))
        assertEquals(PageWidths.PREVIEW, PageWidths.previewFor(2880))
    }

    @Test
    fun `更低的档位从高到低排，好让缓存优先命中最接近的那张`() {
        assertEquals(listOf(2160, 1440, 1080, 720, 480), PageWidths.stepsBelow(2880))
        assertEquals(listOf(480), PageWidths.stepsBelow(720))
        assertEquals(emptyList<Int>(), PageWidths.stepsBelow(480))
    }
}
