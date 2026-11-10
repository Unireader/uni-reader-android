package com.xvan.unireader.local.server

/**
 * 「删得太多先问」（`../docs/SERVER-MODE-PLAN.md §4.6 / §13.13`），逐条照 Mac `Sources/Sync/SyncDeleteGuard.swift`
 * （阈值与判定相同，本端 `SyncDeleteGuardTest` ↔ Mac `spike/sync-delete-guard-test.swift`）：
 * 一次推送里的**删除**超过 [MAX_ROWS] 行，或某张表一次删掉它已同步行数的 [MAX_SHARE]（且至少 [MIN_FOR_SHARE] 行），
 * 就先不推，等用户确认。只拦推送：服务器上的每条删除都是某台设备推上去的，在那台设备上已经问过了。
 */
object SyncDeleteGuard {
    const val MAX_ROWS = 50
    const val MAX_SHARE = 0.2
    const val MIN_FOR_SHARE = 10

    /** [total] 这次一共要删几行；[byTable] 每张表几行（多的在前）；[keys] 这批删除的身份（用户确认的就是这一批）。 */
    data class Excess(val total: Int, val byTable: List<Pair<String, Int>>, val keys: Set<String>)

    fun key(c: SyncWire.RowChange): String = c.table + "\u0001" + c.rowId

    /** [baseCounts] = 每张表已同步的行数（`sync_base` 的条数）；[allowed] = 用户已确认过的那批。没超 → null。 */
    fun excess(changes: List<SyncWire.RowChange>, baseCounts: Map<String, Int>, allowed: Set<String> = emptySet()): Excess? {
        val deletes = changes.filter { it.op == SyncWire.Op.DELETE }
        if (deletes.isEmpty()) return null
        val keys = deletes.map(::key).toSet()
        if (allowed.containsAll(keys)) return null
        val per = deletes.groupingBy { it.table }.eachCount()
        val tooMany = deletes.size > MAX_ROWS
        val bigShare = per.any { (table, n) -> n >= MIN_FOR_SHARE && n >= (baseCounts[table] ?: 0) * MAX_SHARE }
        if (!tooMany && !bigShare) return null
        return Excess(
            deletes.size,
            per.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value },
            keys,
        )
    }

    /** 推送被这道闸拦下了（关着工作区跑的那一轮没有界面可问，抛出来由调度记成「等确认」）。 */
    class Blocked(val excess: Excess) : Exception("要删 ${excess.total} 行，等用户确认")
}
