package com.xvan.unireader.local.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SearchText] 与 Mac `spike/search-test.swift` 的 1) 归一化 ~ 4) 打分逐条对应。 */
class SearchTextTest {

    @Test
    fun normalizeAndTerms() {
        assertEquals("矛盾的普遍性", SearchText.normalize("矛盾的普\n遍性 "))
        assertEquals("abcdef123", SearchText.normalize("ＡＢＣ　ｄｅｆ１２３"))
        assertEquals("matrixrank", SearchText.normalize("Matrix Rank"))
        assertEquals(listOf("矛盾", "普遍性"), SearchText.terms("  矛盾  \"普遍 性\" 矛盾 "))
        assertEquals(listOf("拉格朗日中值"), SearchText.terms("“拉格朗日 中值”"))
    }

    @Test
    fun chunking() {
        val cs = SearchText.chunks("甲乙丙丁戊".repeat(300), maxChars = 500)
        assertTrue(cs.size == 3 && cs.all { it.length <= 500 })
        assertEquals(listOf("第一段\n第二段\n第三段"), SearchText.chunks("第一段\n\n第二段\n\n第三段", maxChars = 500))
        val mc = SearchText.markdownChunks("---\ntags: [a]\n---\n# 极限\n定义……\n\n## 左极限\n内容\n```\n# 不是标题\n```\n尾巴")
        assertEquals(listOf(1, 4, 7), mc.map { it.line })
        assertEquals(listOf("", "极限", "左极限"), mc.map { it.heading })
        assertTrue(mc.last().text.contains("尾巴"))
    }

    @Test
    fun snippets() {
        val body = "第一章 唯物辩证法\n矛盾的普\n遍性是指矛盾存在于一切事物的发展过程中。"
        val sn = SearchText.snippet(body, listOf("矛盾的普遍性"), radius = 6)
        assertTrue(sn, sn.contains("[矛盾的普 遍性]"))
        assertTrue(sn, sn.startsWith("…") && sn.endsWith("…"))
        assertEquals("abc", SearchText.snippet("abc", listOf("zz")))
    }

    @Test
    fun scoring() {
        val df = mapOf("极限" to 2, "函数" to 1)
        val a = SearchText.score("函数极限的定义函数", "", listOf("极限", "函数"), df, 3)
        val b = SearchText.score("极限的定义", "", listOf("极限", "函数"), df, 3)
        val c = SearchText.score("极限的定义", "函数", listOf("极限", "函数"), df, 3)
        assertTrue(a > b)
        assertTrue(c > b)
    }
}
