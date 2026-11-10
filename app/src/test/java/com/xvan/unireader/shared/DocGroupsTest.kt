package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Test

/** 书库分段规则（同 Mac 侧栏）：没有分组不分段；有分组时未分组在前、其余按名字，段内保持原顺序。 */
class DocGroupsTest {
    private fun secs(vararg docs: Pair<String, String>) =
        DocGroups.sections(docs.toList()) { it.second }.map { (g, l) -> g to l.map { it.first } }

    @Test
    fun noGroups() {
        assertEquals(listOf(null to listOf("a", "b")), secs("a" to "", "b" to ""))
        assertEquals(listOf(null to emptyList<String>()), secs())
    }

    @Test
    fun grouped() {
        assertEquals(
            listOf("" to listOf("c"), "数学" to listOf("b", "d"), "英语" to listOf("a")),
            secs("a" to "英语", "b" to "数学", "c" to "", "d" to "数学"),
        )
        // 没有未分组的书：没有「未分组」那一段
        assertEquals(listOf("数学" to listOf("a")), secs("a" to "数学"))
    }
}
