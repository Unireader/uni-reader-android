package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
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
}
