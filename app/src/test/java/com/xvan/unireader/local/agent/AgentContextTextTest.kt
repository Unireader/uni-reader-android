package com.xvan.unireader.local.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** [AgentContextText] 与 Mac `spike/agent-context-test.swift` 的「切标题与正文 / 标题规整 / 带进新对话的那一块」逐条对应。 */
class AgentContextTextTest {

    @Test
    fun parseTitleAndSummary() {
        val r = AgentContextText.parse("# 高数第二章复习\n\n## 目标\n把导数一节的错题过一遍。\n")
        assertEquals("高数第二章复习", r.first)
        assertEquals("## 目标\n把导数一节的错题过一遍。", r.second)

        val p = AgentContextText.parse("好的，以下是交接摘要：\n\n# 习题导入进度\n已导入第 3~12 页。")
        assertEquals("习题导入进度", p.first)
        assertEquals("已导入第 3~12 页。", p.second)

        assertEquals("只有二级标题", AgentContextText.parse("## 只有二级标题\n内容").first)

        val none = AgentContextText.parse("\n\n第一行当标题\n第二行\n")
        assertEquals("第一行当标题", none.first)
        assertEquals("第一行当标题\n第二行", none.second)

        val tag = AgentContextText.parse("#标签不是标题\n正文")
        assertEquals("标签不是标题", tag.first)
        assertEquals("#标签不是标题\n正文", tag.second)

        val empty = AgentContextText.parse("   \n")
        assertEquals("", empty.first)
        assertEquals("", empty.second)

        assertEquals("", AgentContextText.parse("# 只有标题").second)
    }

    @Test
    fun cleanTitle() {
        assertEquals("加粗的标题", AgentContextText.cleanTitle("  ## **加粗的标题**  "))
        assertEquals("两行 标题", AgentContextText.cleanTitle("两行\n标题"))
        val cleaned = AgentContextText.cleanTitle("长".repeat(80))
        assertEquals(60, cleaned.length)
        assertTrue(cleaned.endsWith("…"))
    }

    @Test
    fun handoffBlock() {
        val d = Calendar.getInstance(TimeZone.getDefault()).apply { clear(); set(2026, Calendar.NOVEMBER, 2, 16, 0) }.time
        val t = AgentContextText.handoff("高数复习", "第一行\n第二行", "Kimi", "ABC", d)
        assertTrue(t, t.contains("Saved context \"高数复习\" (context_id ABC)"))
        assertTrue(t, t.contains("written by Kimi on 2026-11-02"))
        assertTrue(t.endsWith("第一行\n第二行"))
        assertTrue(AgentContextText.handoff("x", "y", "", "1", d).contains("written on 2026-11-02"))
        assertTrue(AgentContextText.SAVE_INSTRUCTION.contains("\"# \""))
    }
}
