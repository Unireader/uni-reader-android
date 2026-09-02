package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录 + 书签合并规则（`../REQUIREMENTS.md §1.9`）。
 * 用例与 Mac 的 `spike/toc-merge-test.swift` **逐条对应**——那份是参照实现，
 * 这里跑的是它的 Kotlin 移植，两边结论必须一样。
 */
class TocMergeTest {

    private fun row(d: Int, p: Int) = TocMerge.Row(d, p)

    /**
     * 一棵典型目录：
     *  0: 第一章 p0 / 1: 1.1 p2 / 2: 1.2 p8 / 3: 1.2.1 p9（孙子项）/ 4: 第二章 p20 / 5: 2.1 p22
     */
    private val tree = listOf(row(0, 0), row(1, 2), row(1, 8), row(2, 9), row(0, 20), row(1, 22))

    @Test
    fun 归组按页号区间左闭右开() {
        assertEquals(0, TocMerge.place(tree, listOf(0))[0].owner)     // 等于组页号也算这一组
        assertEquals(0, TocMerge.place(tree, listOf(19))[0].owner)    // 小于第二章 p20 → 仍是第一章
        assertEquals(4, TocMerge.place(tree, listOf(20))[0].owner)
        assertEquals(4, TocMerge.place(tree, listOf(99))[0].owner)    // 超出最后一组 → 挂最后一个
        assertEquals(1, TocMerge.place(tree, listOf(20))[0].depth)
    }

    @Test
    fun 没组可挂就平铺树顶() {
        val late = listOf(row(0, 5), row(1, 6))
        val s = TocMerge.place(late, listOf(1))[0]
        assertEquals(-1, s.owner)
        assertEquals(0, s.depth)
        assertEquals(0, s.insertBefore)

        assertEquals(-1, TocMerge.place(emptyList(), listOf(3))[0].owner)   // 整本书没目录

        // 一级组全是坏书签（page=-1）→ 不挂到没页号的组上
        val dead = listOf(row(0, -1), row(1, 4))
        assertEquals(-1, TocMerge.place(dead, listOf(7))[0].owner)
    }

    @Test
    fun 组内插在第一个更大页号的直接子项之前() {
        assertEquals(2, TocMerge.place(tree, listOf(5))[0].insertBefore)   // 1.2(p8) 之前
        assertEquals(2, TocMerge.place(tree, listOf(3))[0].insertBefore)   // p2 不大于它，跳过
        assertEquals(1, TocMerge.place(tree, listOf(1))[0].insertBefore)   // 1.1(p2) 之前
        assertEquals(4, TocMerge.place(tree, listOf(12))[0].insertBefore)  // 没更大的 → 子树末尾
        assertEquals(tree.size, TocMerge.place(tree, listOf(30))[0].insertBefore)
    }

    @Test
    fun 孙子项不参与比较() {
        // 1.2.1 是 p9（depth=2）。书签 p8 只跟 depth==1 的比 → 落到子树末尾，而不是插在 1.2.1 之前
        assertEquals(4, TocMerge.place(tree, listOf(8))[0].insertBefore)
    }

    @Test
    fun 坏书签子项跳过顺延() {
        val withDead = listOf(row(0, 0), row(1, -1), row(1, 8))
        assertEquals(2, TocMerge.place(withDead, listOf(5))[0].insertBefore)
    }

    @Test
    fun 目录先序页号乱序时仍按页号取边界() {
        val messy = listOf(row(0, 90), row(0, 10))   // 附录在前、正文在后
        assertEquals(1, TocMerge.place(messy, listOf(20))[0].owner)
        assertEquals(0, TocMerge.place(messy, listOf(95))[0].owner)
    }

    @Test
    fun 同页多枚落同一处且落位表等长() {
        val s = TocMerge.place(tree, listOf(5, 5, 5))
        assertEquals(3, s.size)
        assertTrue(s.all { it.insertBefore == 2 && it.owner == 0 })
        assertTrue(TocMerge.place(tree, emptyList()).isEmpty())
    }
}
