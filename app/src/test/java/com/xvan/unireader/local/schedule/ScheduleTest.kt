package com.xvan.unireader.local.schedule

import com.xvan.unireader.local.store.LibScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ScheduleDay] / [ScheduleTime] / [ScheduleText.agentLine] 与 Mac `spike/schedule-test.swift` 的同名几段逐条对应。 */
class ScheduleTest {

    @Test
    fun days() {
        assertTrue(ScheduleDay.isValid("2026-11-14"))
        assertFalse(ScheduleDay.isValid("2026-02-30"))
        assertFalse(ScheduleDay.isValid("2026-11-4") || ScheduleDay.isValid("2026/11/14") || ScheduleDay.isValid(""))
        assertTrue(ScheduleDay.isValid("2028-02-29") && !ScheduleDay.isValid("2027-02-29"))
        assertEquals("2027-01-01", ScheduleDay.adding(1, "2026-12-31"))
        assertEquals("2026-02-28", ScheduleDay.adding(-1, "2026-03-01"))
        assertEquals(7, ScheduleDay.days("2026-11-13", "2026-11-20"))
        assertEquals(-7, ScheduleDay.days("2026-11-20", "2026-11-13"))
        assertEquals("2026-11-14", ScheduleDay.string(ScheduleDay.date("2026-11-14")!!))
    }

    @Test
    fun times() {
        assertEquals("09:30", ScheduleTime.string(570))
        assertNull(ScheduleTime.string(-1))
        assertEquals(570, ScheduleTime.minutes("09:30"))
        assertEquals(545, ScheduleTime.minutes("9:05"))
        assertEquals(1439, ScheduleTime.minutes("2359"))
        assertNull(ScheduleTime.minutes("24:00"))
        assertNull(ScheduleTime.minutes("9:5"))
        assertNull(ScheduleTime.minutes("abc"))
        assertEquals("1 h 30 min", ScheduleTime.durationText(90))
    }

    @Test
    fun agentLine() {
        fun item(title: String, start: Int) = LibScheduleItem("I$title", "", title, "", "2026-11-14", start, 0, "", "", 0, "", "", "")
        val line = ScheduleText.agentLine("2026-11-14", listOf(item("八点", 480), item("不定时的一件", -1)), 1, 1)!!
        assertTrue(line.startsWith("Workspace schedule"))
        assertTrue(line, line.contains("2 item(s), 0 done") && line.contains("1 overdue") && line.contains("1 plan(s)"))
        assertTrue(line, line.contains("[ ] 08:00 八点") && line.contains("[ ] 不定时的一件"))
        assertTrue(line.contains("list_schedule") && line.contains("add_schedule_items"))
        assertNull(ScheduleText.agentLine("2026-11-14", emptyList(), 0, 0))
    }
}
