package com.xvan.unireader.local

import java.util.Calendar
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份命名与分级稀释（`Backups`），逐段对应 Mac `../spike/backup-retention-test.swift` 的 1)~9)。
 * 两端规则差一点，同一个工作区里两端写的备份就会被对方「稀释」掉不该删的那份。
 */
class BackupsTest {

    private val cal: Calendar = Calendar.getInstance().apply { firstDayOfWeek = Calendar.MONDAY }
    private val now: Date = (cal.clone() as Calendar).apply { set(2026, Calendar.SEPTEMBER, 21, 14, 0, 0); set(Calendar.MILLISECOND, 0) }.time
    private fun agoH(h: Double) = Date(now.time - (h * 3600_000).toLong())
    private fun agoD(d: Double) = agoH(d * 24)
    private fun keep(dates: List<Date>, recent: Int = 5, daily: Int = 7, weekly: Int = 4): List<Int> {
        val drop = Backups.planDrop(dates, now, recent, daily, weekly, cal).toSet()
        return dates.indices.filter { it !in drop }
    }

    @Test fun 空输入() = assertTrue(Backups.planDrop(emptyList(), now, cal = cal).isEmpty())

    @Test fun 同一天密集跑只留最近5份() {
        val dates = (0 until 12).map { agoH(it.toDouble()) }
        assertEquals(listOf(0, 1, 2, 3, 4), keep(dates))
    }

    @Test fun 每天一份7天() {
        val k = keep((0 until 20).map { agoD(it.toDouble()) })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), k.take(7))
        assertTrue(k.size in 8..11)
    }

    @Test fun 每周一份4周() {
        val k = keep((0 until 10).map { agoD(it * 7.0) })
        assertTrue(k.containsAll(listOf(0, 1, 2, 3, 4)))
        assertFalse(9 in k)
        assertEquals(5, k.size)
    }

    @Test fun 一桶里留最新的那一份() {
        val filler = (0 until 5).map { agoH(it.toDouble()) }
        val early = Date(agoD(6.0).time - 6 * 3600_000)
        val k = keep(filler + listOf(agoD(6.0), early))
        assertTrue(5 in k)
        assertFalse(6 in k)
    }

    @Test fun 未来时间戳照单全收() {
        val filler = (0 until 5).map { agoH(it.toDouble()) }
        val k = keep(filler + listOf(Date(now.time + 3 * 86_400_000L), agoD(400.0)))
        assertTrue(5 in k)
        assertFalse(6 in k)
    }

    @Test fun 下标按输入顺序() {
        val k = keep(listOf(agoD(30.0), now, agoD(1.0), agoD(200.0)))
        assertTrue(1 in k && 2 in k)
    }

    @Test fun 策略全0不炸() {
        val dates = (0 until 5).map { agoD(it.toDouble()) }
        val drop = Backups.planDrop(dates, now, 0, 0, 0, cal)
        assertTrue(drop.all { it in dates.indices })
    }

    @Test fun 命名与解析() {
        val d = (cal.clone() as Calendar).apply { set(2026, Calendar.SEPTEMBER, 21, 14, 3, 11); set(Calendar.MILLISECOND, 0) }.time
        val n = Backups.name(d)
        assertEquals("library-20260921-140311.sqlite", n)
        val p = Backups.parse(n)
        assertNotNull(p)
        assertEquals(d.time, p!!.first.time)
        assertFalse(p.second)
        val r = Backups.name(d, restorePoint = true)
        assertEquals("library-20260921-140311-before-restore.sqlite", r)
        assertTrue(Backups.parse(r)!!.second)
        for (bad in listOf("library.sqlite", "library-.sqlite", "library-20260921.sqlite",
            "library-20260921-140311.sqlite.bak", "note.txt", "", "library-9999aa-bbbbbb.sqlite", ".DS_Store")) {
            assertNull("忽略「$bad」", Backups.parse(bad))
        }
    }
}
