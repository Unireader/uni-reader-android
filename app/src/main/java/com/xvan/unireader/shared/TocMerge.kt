package com.xvan.unireader.shared

/**
 * 目录 + 书签的合并规则（`../REQUIREMENTS.md §1.9`）。纯函数，只认「深度 + 页号」。
 *
 * ⚠️ **参照实现是 Mac 的 `Sources/App/TOCMerge.swift`**（spike/toc-merge-test.swift 19 项覆盖），
 * 本文件与 web 的 `lib/tocMerge.ts` 都是它的移植。**改一条规则要三端同步**，
 * 否则同一本书在两端长得不一样。
 *
 * 规则三条：
 * ① 取全部 depth==0 且有页号的目录项，按页号升序得到区间边界；页号为 b 的书签落在 `[pᵢ, pᵢ₊₁)`
 *    → 挂到第 i 个一级组下，作为它的**直接子项**（depth=1）。
 * ② 落在第一个一级组之前、或压根没有可用的一级组（含整本书没目录）→ 平铺在**树顶**（depth=0），
 *    不套任何组，也不造「书签」这种假分组标题。
 * ③ 组内位置：插在该组直接子项中**第一个页号大于它**的那一项之前（无页号的子项跳过顺延），
 *    都不大于就排在该组子树末尾。**目录项之间的相对顺序一行不动**。
 */
object TocMerge {

    /** 拍平（先序）后的一行目录。[page] = -1 是坏书签。 */
    data class Row(val depth: Int, val page: Int)

    /**
     * 一枚书签的落位。
     * @param insertBefore 插在拍平目录的第几行**之前**；等于行数表示排在最末
     * @param depth 缩进深度：0 = 平铺树顶，1 = 挂在某个一级组下
     * @param owner 挂在拍平目录的第几行（那个一级组）；-1 = 树顶
     */
    data class Slot(val insertBefore: Int, val depth: Int, val owner: Int)

    /**
     * 给每枚书签算落位。
     * @param rows 拍平（先序）后的目录行
     * @param pages 书签页号，**必须按升序**（线上就是有序的，见 `../PROTOCOL.md`）
     * @return 与 [pages] 等长、一一对应的落位表
     */
    fun place(rows: List<Row>, pages: List<Int>): List<Slot> {
        if (pages.isEmpty()) return emptyList()

        // 一级组边界：(行下标, 页号) 按**页号**升序。不能直接拿先序当顺序——真实 PDF 的目录先序
        // 并不保证页号单调（同「当前章节」那处 argmax 的理由）。
        val groups = rows.indices
            .filter { rows[it].depth == 0 && rows[it].page >= 0 }
            .map { it to rows[it].page }
            .sortedBy { it.second }

        /** 某个一级组的子树尾（开区间）：从组的下一行起，到下一个 depth==0 为止。 */
        fun subtreeEnd(idx: Int): Int {
            var j = idx + 1
            while (j < rows.size && rows[j].depth > 0) j++
            return j
        }

        return pages.map { page ->
            val g = groups.lastOrNull { it.second <= page }
                ?: return@map Slot(0, 0, -1)          // ② 树顶
            val end = subtreeEnd(g.first)
            var at = end                              // ③ 默认排在该组子树末尾
            var j = g.first + 1
            while (j < end) {
                if (rows[j].depth == 1 && rows[j].page >= 0 && rows[j].page > page) { at = j; break }
                j++
            }
            Slot(at, 1, g.first)
        }
    }
}
