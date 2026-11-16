package com.xvan.unireader.local.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 语义搜索的纯规则（`../docs/SEMANTIC-SYNC-PLAN.md §4.2`），对照 Mac `VectorMath.quantize` / `SearchIndex.semantic` / `fuse`。 */
class SemanticTest {

    private fun chunk(kind: String, owner: String, page: Int? = null, line: Int? = null, body: String = "x") =
        WorkspaceSearch.SearchChunk(kind, owner, page = page, line = line, title = owner, body = body)

    @Test
    fun 量化_四舍五入远离零_夹到正负127() {
        // 0.5 × 127 = 63.5 → 64；-63.5 → -64（远离零，不是银行家舍入）；0.1 × 127 = 12.7 → 13
        val q = Semantic.quantize(floatArrayOf(1f, -1f, 0f, 0.5f, -0.5f, 2f, -2f, 0.1f, 0.003f))
        assertArrayEquals(byteArrayOf(127, -127, 0, 64, -64, 127, -127, 13, 0), q)
    }

    @Test
    fun 哈希是正文UTF8的SHA256() {
        // echo -n "abc" | shasum -a 256
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Semantic.hex(Semantic.hash("abc")))
        assertEquals(32, Semantic.hash("中文").size)
    }

    @Test
    fun 语义排序_按点积_查不到向量和维数不对的不参与_同页去重() {
        val a = chunk("pdf", "D", page = 1, body = "a")
        val b = chunk("pdf", "D", page = 1, body = "b")      // 与 a 同一页，只留分高的
        val c = chunk("markdown", "n.md", line = 3, body = "c")
        val d = chunk("markdown", "n.md", line = 9, body = "d") // 没有向量
        val e = chunk("markdown", "n.md", line = 12, body = "e") // 维数不对
        val vec = mapOf(
            a.hashHex to byteArrayOf(127, 0), b.hashHex to byteArrayOf(64, 0),
            c.hashHex to byteArrayOf(0, 127), e.hashHex to byteArrayOf(1, 2, 3),
        )
        val hits = Semantic.rank(byteArrayOf(100, 20), listOf(a, b, c, d, e), { vec[it.hashHex] }, 10)
        assertEquals(listOf("a", "c"), hits.map { it.chunk.body })
        assertEquals(100.0 * 127 / (127.0 * 127), hits[0].score, 1e-9)
    }

    @Test
    fun 两路合并_倒数排名融合() {
        val k1 = chunk("pdf", "D", page = 1)
        val k2 = chunk("pdf", "D", page = 2)
        val s1 = chunk("markdown", "n.md", line = 1)
        // 关键词：k1, k2；语义：s1, k2（k2 两边都有 → 最靠前）
        val fused = Semantic.fuse(
            listOf(WorkspaceSearch.Hit(k1, 9.0), WorkspaceSearch.Hit(k2, 8.0)),
            listOf(WorkspaceSearch.Hit(s1, 0.9), WorkspaceSearch.Hit(k2, 0.8)),
            10,
        )
        assertEquals(listOf(k2, k1, s1), fused.map { it.chunk })
        assertEquals(1 / 62.0 + 1 / 62.0, fused[0].score, 1e-12)
        // 关键词退回「任一词」时权重 0.5：只在关键词那边的排到只在语义那边的后面
        val relaxed = Semantic.fuse(listOf(WorkspaceSearch.Hit(k1, 9.0)), listOf(WorkspaceSearch.Hit(s1, 0.9)), 10, 0.5)
        assertEquals(listOf(s1, k1), relaxed.map { it.chunk })
    }
}
