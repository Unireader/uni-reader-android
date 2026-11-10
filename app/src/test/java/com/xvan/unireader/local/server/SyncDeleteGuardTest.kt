package com.xvan.unireader.local.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 「删得太多先问」的判定，与 Mac `spike/sync-delete-guard-test.swift` 逐条对应。 */
class SyncDeleteGuardTest {
    private fun del(table: String, n: Int) =
        (0 until n).map { SyncWire.RowChange(table, "$table-$it", SyncWire.Op.DELETE, "x", null) }
    private fun up(table: String, n: Int) =
        (0 until n).map { SyncWire.RowChange(table, "u$it", SyncWire.Op.UPSERT, null, emptyMap()) }
    private val base = mapOf("note" to 1000, "exercise" to 40, "meta" to 5)

    @Test
    fun thresholds() {
        assertNull(SyncDeleteGuard.excess(up("note", 500), base))
        assertNull("删 50 行（1000 行里）", SyncDeleteGuard.excess(del("note", 50), base))
        assertEquals(51, SyncDeleteGuard.excess(del("note", 51) + up("note", 3), base)?.total)
        assertNull("小表删 9 行", SyncDeleteGuard.excess(del("exercise", 9), base))
        assertNotNull("40 行的表删 10 行", SyncDeleteGuard.excess(del("exercise", 10), base))
        assertNull("1000 行的表删 30 行", SyncDeleteGuard.excess(del("note", 30), base))
        assertNull(SyncDeleteGuard.excess(del("meta", 2), base))
        val z = SyncDeleteGuard.excess(del("note", 40) + del("exercise", 12), base)!!
        assertEquals(52, z.total)
        assertEquals(listOf("note", "exercise"), z.byTable.map { it.first })
        assertNotNull("基线里没有这张表（当 0）", SyncDeleteGuard.excess(del("brand_new", 12), base))
    }

    @Test
    fun allowed() {
        val x = SyncDeleteGuard.excess(del("note", 51), base)!!
        assertNull("确认过的同一批", SyncDeleteGuard.excess(del("note", 51), base, x.keys))
        assertNull("其中一部分", SyncDeleteGuard.excess(del("note", 40), base, x.keys))
        assertNotNull("比确认的多删了", SyncDeleteGuard.excess(del("note", 60), base, x.keys))
    }
}
