package com.xvan.unireader.local.mirror

/**
 * 把 [MirrorDiff.Plan] 翻成人话 —— **干跑预览就是这个功能唯一的安全闸**，
 * 用户要在这里看懂「按下去会发生什么」，所以一行 id 都不许出现在报告里。
 *
 * 反面教材是「note 3f2a… → upsert」这种：用户既判断不了该不该同意，出了事也复盘不了。
 * 报告只说三件事：**动了谁的什么、往哪个方向、多少条**。
 *
 * 🔴 与 Mac `Sources/Store/MirrorReport.swift` 同一套措辞与聚合口径，改一边同步另一边——
 * 同一次同步在两端念出来的话不一样，用户会以为它们做的不是同一件事。
 */
object MirrorReport {

    /** 一行摘要。[detail] 为空表示这条不用展开 */
    data class Line(val text: String, val detail: List<String> = emptyList())

    /** `note.kind` → 人话 */
    fun noteKindName(kind: Int?): String = when (kind) {
        0 -> "文字注解"
        1 -> "AI 会话"
        2 -> "笔迹"
        3 -> "高亮"
        4 -> "草稿纸笔迹"
        else -> "笔记"
    }

    fun tableName(table: String): String = when (table) {
        "document" -> "文档信息"
        "variant" -> "文档版本"
        "ink_layer" -> "笔迹图层"
        "scratch_pad" -> "草稿纸"
        "meta" -> "工作区设置"
        else -> table
    }

    /** 一条改动的类别名（`note` 按 kind 细分，其余按表） */
    fun categoryName(c: MirrorDiff.Change): String =
        if (c.table == "note") noteKindName(c.kind) else tableName(c.table)

    private fun verbOf(r: MirrorDiff.Reason): String = when (r) {
        MirrorDiff.Reason.MIRROR_ADDED, MirrorDiff.Reason.SOURCE_ADDED -> "新增"
        MirrorDiff.Reason.MIRROR_DELETED, MirrorDiff.Reason.SOURCE_DELETED -> "删除"
        else -> "修改"
    }

    /** 干跑摘要。[titles] = `documentId → 书名`（**两侧合并后的**，源盘新增的书也要能查到名字） */
    fun summary(plan: MirrorDiff.Plan, titles: Map<String, String>): List<Line> {
        val out = ArrayList<Line>()
        for ((side, heading) in listOf(MirrorDiff.Side.SOURCE to "写入硬盘", MirrorDiff.Side.MIRROR to "拉回本机")) {
            val list = plan.changesTo(side)
            if (list.isEmpty()) continue
            // 按「类别 + 增/删/改」聚合。逐条列出来的话，一次正常同步就是几百行，等于没给用户看。
            val buckets = LinkedHashMap<String, Int>()
            for (c in list) {
                val k = verbOf(c.reason) + categoryName(c)
                buckets[k] = (buckets[k] ?: 0) + 1
            }
            val parts = buckets.entries.sortedBy { it.key }.map { "${it.key} ${it.value}" }
            out.add(Line("$heading：" + parts.joinToString("、"), bookBreakdown(list, titles)))
        }
        if (plan.lastOpenedMerges.isNotEmpty()) {
            out.add(Line("另有 ${plan.lastOpenedMerges.size} 篇文档的「上次打开」两端取较晚的那个"))
        }
        if (plan.conflicts.isNotEmpty()) {
            out.add(Line("冲突 ${plan.conflicts.size} 条", conflictLines(plan, titles)))
        }
        if (out.isEmpty()) out.add(Line("两端一致，没有要同步的东西"))
        return out
    }

    /** 按书分组的明细：「《高等数学》：笔迹 +132 −8」。用户是按书来记事的，不是按表 */
    fun bookBreakdown(list: List<MirrorDiff.Change>, titles: Map<String, String>): List<String> {
        class Tally { var add = 0; var del = 0; var mod = 0 }
        val byBook = LinkedHashMap<String, LinkedHashMap<String, Tally>>()
        var loose = 0
        for (c in list) {
            val doc = c.docId
            if (doc == null) { loose++; continue }
            val cats = byBook.getOrPut(doc) { LinkedHashMap() }
            val t = cats.getOrPut(categoryName(c)) { Tally() }
            when (verbOf(c.reason)) {
                "新增" -> t.add++
                "删除" -> t.del++
                else -> t.mod++
            }
        }
        val out = byBook.map { (doc, cats) ->
            val name = titles[doc]?.let { "《$it》" } ?: "（已删除的文档）"
            val parts = cats.entries.sortedBy { it.key }.map { (cat, t) ->
                val bits = ArrayList<String>()
                if (t.add > 0) bits.add("+${t.add}")
                if (t.del > 0) bits.add("−${t.del}")
                if (t.mod > 0) bits.add("改 ${t.mod}")
                "$cat ${bits.joinToString(" ")}"
            }
            "$name：${parts.joinToString("，")}"
        }.sorted().toMutableList()
        if (loose > 0) out.add("工作区级设置 $loose 项")
        return out
    }

    /** 冲突明细。**每条都要说清「保留了哪份」**——用户同意的是一个具体结果，不是一个数字 */
    fun conflictLines(plan: MirrorDiff.Plan, titles: Map<String, String>): List<String> {
        // 冲突里没有 docId（Conflict 只带表与 id），从 changes 里回查同一行拿标签
        val label = plan.changes.associateBy { "${it.table}/${it.rowId}" }
        return plan.conflicts.map { k ->
            val c = label["${k.table}/${k.rowId}"]
            val what = c?.let { categoryName(it) } ?: tableName(k.table)
            val book = c?.docId?.let { titles[it] }?.let { "《$it》" } ?: ""
            val page = c?.page?.let { "第 ${it + 1} 页" } ?: ""
            "${book}${page}的一条${what}：${k.note}"
        }.sorted()
    }

    /** 一行式结论（顶栏/按钮旁用） */
    fun headline(plan: MirrorDiff.Plan): String {
        if (plan.isEmpty) return "两端一致"
        val toSource = plan.changesTo(MirrorDiff.Side.SOURCE).size
        val toMirror = plan.changesTo(MirrorDiff.Side.MIRROR).size
        val bits = ArrayList<String>()
        if (toSource > 0) bits.add("写入硬盘 $toSource")
        if (toMirror > 0) bits.add("拉回本机 $toMirror")
        if (plan.conflicts.isNotEmpty()) bits.add("冲突 ${plan.conflicts.size}")
        return if (bits.isEmpty()) "只更新「上次打开」" else bits.joinToString(" · ")
    }
}
