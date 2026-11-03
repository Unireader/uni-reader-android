package com.xvan.unireader.local.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开着工作区时收服务器的改动（S4，`../docs/PROTOCOL.md §9.6`）里不依赖网络和设备的那几块。
 * Mac 对应：`spike/server-client-test.swift` ⑩c。
 */
class LiveSyncTest {

    private fun row(table: String, id: String, rev: Long) = SyncWire.PulledRow(table, id, rev, false, null)

    /** 分页拉取要把所有页攒齐，修订号取最后一页的 head（`applyPulled` 必须在攒齐之后调） */
    @Test fun fetchPulledGathersAllPages() {
        val pages = mapOf(
            0L to SyncWire.PullResponse(head = 9, more = true, rows = listOf(row("note", "A", 3), row("note", "B", 5))),
            5L to SyncWire.PullResponse(head = 9, more = true, rows = listOf(row("note", "C", 7))),
            7L to SyncWire.PullResponse(head = 9, more = false, rows = listOf(row("document", "D", 9))),
        )
        val asked = ArrayList<Long>()
        val (rows, head) = SyncClient.fetchPulled(0) { since -> asked += since; pages.getValue(since) }
        assertEquals(listOf(0L, 5L, 7L), asked)
        assertEquals(listOf("A", "B", "C", "D"), rows.map { it.rowId })
        assertEquals(9L, head)
    }

    /** 没有新行也要把服务器的修订号带回来（`pulledRev` 要跟着走，否则 wait 会一直说「变了」） */
    @Test fun fetchPulledReturnsHeadWithoutRows() {
        val (rows, head) = SyncClient.fetchPulled(4) { SyncWire.PullResponse(head = 12, more = false, rows = emptyList()) }
        assertTrue(rows.isEmpty())
        assertEquals(12L, head)
    }

    /** 服务器数据被还原过（head 比本机小）：不能把修订号往回拉 */
    @Test fun fetchPulledNeverMovesBackwards() {
        val (_, head) = SyncClient.fetchPulled(50) { SyncWire.PullResponse(head = 10, more = false, rows = emptyList()) }
        assertEquals(50L, head)
    }

    /** 旧服务器没有 wait：路由不存在回的是不带说明的 404 / 405；「没有这个工作区」那种 404 带说明，不算 */
    @Test fun unsupportedRouteIsRecognised() {
        assertTrue(ServerClient.Failure(404, "").isUnsupportedRoute)
        assertTrue(ServerClient.Failure(405, "").isUnsupportedRoute)
        assertFalse(ServerClient.Failure(404, "服务器上没有这个工作区（x）").isUnsupportedRoute)
        assertFalse(ServerClient.Failure(500, "").isUnsupportedRoute)
        assertFalse(ServerClient.Failure(401, "").isUnsupportedRoute)
    }
}
