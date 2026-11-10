package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {
    private fun run(t: String, y: Float) = TextRun(t, 0.1f, y, 0.8f, 0.02f)

    @Test
    fun foldAndFind() {
        val p = TextSearch.fold("Hello  World\nhello")
        assertEquals("helloworldhello", p.hay)
        assertEquals(listOf(0..4, 13..17), TextSearch.find(p, TextSearch.foldQuery("HELLO")))
        assertEquals(listOf(4..7), TextSearch.find(p, TextSearch.foldQuery("o w")))   // 查询里的空白也去掉
        assertTrue(TextSearch.find(p, "").isEmpty())
    }

    @Test
    fun ocrAcrossLines() {
        val hits = ArrayList<TextSearch.Hit>()
        // 「操作系」+「统」跨行断开也找得到；位置 = 命中开头那一行
        TextSearch.searchOcrPage(3, listOf(run("第一章 操作系", 0.2f), run("统概述", 0.25f), run("操作系统的定义", 0.4f)), "操作系统", hits)
        assertEquals(2, hits.size)
        assertEquals(0.2f, hits[0].frac, 1e-6f)
        assertEquals(0.4f, hits[1].frac, 1e-6f)
        assertEquals("操作系 统", hits[0].snippet.substring(hits[0].hitStart, hits[0].hitEnd))
        assertTrue(hits.all { it.page == 3 })
    }

    @Test
    fun snippetCuts() {
        val text = "a".repeat(30) + "KEY" + "b".repeat(60)
        val (s, a, b) = TextSearch.snippet(text, 30..32)
        assertEquals("KEY", s.substring(a, b))
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertEquals(1 + TextSearch.SNIPPET_BEFORE + 3 + TextSearch.SNIPPET_AFTER + 1, s.length)
        val (s2, a2, _) = TextSearch.snippet("KEY tail", 0..2)
        assertEquals("KEY tail", s2)
        assertEquals(0, a2)
    }

    @Test
    fun mergePrefersOcrPages() {
        val ocr = listOf(TextSearch.Hit(2, 0.5f, "o", 0, 1, 0))
        val plain = ArrayList<TextSearch.Hit>()
        TextSearch.searchPlainPage(2, "same page x", "x", plain)
        TextSearch.searchPlainPage(1, "x and x", "x", plain)
        val all = TextSearch.merge(ocr, plain)
        assertEquals(listOf(1, 1, 2), all.map { it.page })
        assertEquals(listOf(0, 6), all.take(2).map { it.origin })
        assertEquals(0.5f, all[2].frac, 1e-6f)
    }
}
