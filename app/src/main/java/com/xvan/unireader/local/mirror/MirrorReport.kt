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
        // 「文档信息」是表名漏到界面上。这张表存的就是书名、分组、排序这些——说「书的信息」谁都懂
        "document" -> "书的信息"
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
    fun summary(
        plan: MirrorDiff.Plan,
        titles: Map<String, String>,
        hashTitles: Map<String, String> = emptyMap(),
    ): List<Line> {
        val out = ArrayList<Line>()
        for ((side, heading) in listOf(MirrorDiff.Side.SOURCE to "写入硬盘", MirrorDiff.Side.MIRROR to "拉回本机")) {
            // 只差阅读进度的那些**不进明细**：底下「阅读进度取最近读的那次」已经把它说完整了，
            // 再以「修改书的信息 1」的面目出现一次，用户只会问「这是什么意思」。
            // ⚠️ 只是不报，plan.changes 一条不少 —— MirrorApply 照常要写下去。
            val list = plan.changesTo(side).filterNot { isProgressOnly(it, plan) }
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
        // 「另有」只在**真的还有别的**时候才说得通
        fun also(s: String) = if (out.isEmpty()) s else "另有$s"
        if (plan.progressMerges.isNotEmpty()) {
            out.add(Line(also("${plan.progressMerges.size} 篇文档两端都读过，阅读进度取最近读的那次")))
        }
        // 「上次打开」是纯记账（进度那条已经涵盖用户真正关心的），只在没有进度合并时单独说一句
        if (plan.lastOpenedMerges.isNotEmpty() && plan.progressMerges.isEmpty()) {
            out.add(Line(also("${plan.lastOpenedMerges.size} 篇文档的「上次打开」两端取较晚的那个")))
        }
        // OCR 缓存：只补不删，两个方向分开说 —— 用户关心的是"这次同步之后哪边不用再花钱重跑"
        if (plan.ocrToSource.isNotEmpty() || plan.ocrToMirror.isNotEmpty()) {
            val bits = ArrayList<String>()
            if (plan.ocrToSource.isNotEmpty()) bits.add("写入硬盘 ${plan.ocrToSource.size} 页")
            if (plan.ocrToMirror.isNotEmpty()) bits.add("拉回本机 ${plan.ocrToMirror.size} 页")
            out.add(Line(also("补齐文字识别结果：" + bits.joinToString("、")), ocrBreakdown(plan, hashTitles)))
        }
        // 扫描页对齐：开关 / 参数整行按较新的那边覆盖（`../SCAN-ALIGN-PLAN.md §5`），措辞同 Mac
        if (plan.alignToSource.isNotEmpty() || plan.alignToMirror.isNotEmpty()) {
            val bits = ArrayList<String>()
            if (plan.alignToSource.isNotEmpty()) bits.add("写入硬盘 ${plan.alignToSource.size} 本")
            if (plan.alignToMirror.isNotEmpty()) bits.add("拉回本机 ${plan.alignToMirror.size} 本")
            val names = (plan.alignToSource + plan.alignToMirror).map { hashTitles[it] ?: "（未知文档）" }
            out.add(Line(also("扫描页对齐设置：" + bits.joinToString("、")), names))
        }
        if (plan.conflicts.isNotEmpty()) {
            out.add(Line("冲突 ${plan.conflicts.size} 条", conflictLines(plan, titles)))
        }
        if (out.isEmpty()) out.add(Line("两端一致，没有要同步的东西"))
        return out
    }

    /**
     * OCR 补齐的按书明细：「《高等数学》：写入硬盘 132 页」。
     *
     * 查不到书名的照样要出现（只是显示成内容指纹的前 8 位）：这批页多半是"两边各自加过、
     * 但那本书还没同步过来"的情况，静默不提等于让用户对着一个总数猜。
     */
    fun ocrBreakdown(plan: MirrorDiff.Plan, hashTitles: Map<String, String>): List<String> {
        class Tally { var toSource = 0; var toMirror = 0 }
        val byHash = LinkedHashMap<String, Tally>()
        for (k in plan.ocrToSource) byHash.getOrPut(k.contentHash) { Tally() }.toSource++
        for (k in plan.ocrToMirror) byHash.getOrPut(k.contentHash) { Tally() }.toMirror++
        return byHash.map { (hash, n) ->
            val name = hashTitles[hash]?.let { "《$it》" } ?: "（${hash.take(8)}…）"
            val bits = ArrayList<String>()
            if (n.toSource > 0) bits.add("写入硬盘 ${n.toSource} 页")
            if (n.toMirror > 0) bits.add("拉回本机 ${n.toMirror} 页")
            "$name：" + bits.joinToString("，")
        }.sorted()
    }

    /** 按书分组的明细：「《高等数学》：笔迹 +132 −8」。用户是按书来记事的，不是按表 */
    fun bookBreakdown(list: List<MirrorDiff.Change>, titles: Map<String, String>): List<String> {
        class Tally { var add = 0; var del = 0; var mod = 0 }
        val byBook = LinkedHashMap<String, LinkedHashMap<String, Tally>>()
        val loose = LinkedHashMap<String, Int>()
        for (c in list) {
            val doc = bookId(c)
            if (doc == null) {
                loose[tableName(c.table)] = (loose[tableName(c.table)] ?: 0) + 1
                continue
            }
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
        // 不属于任何一本书的（`meta` 这类）按表名说，别一律扣上「工作区级设置」的帽子
        out += loose.entries.sortedBy { it.key }.map { (name, n) -> "$name $n 项" }
        return out
    }

    /** 冲突明细。**每条都要说清「保留了哪份」**——用户同意的是一个具体结果，不是一个数字 */
    fun conflictLines(plan: MirrorDiff.Plan, titles: Map<String, String>): List<String> {
        // 冲突里没有 docId（Conflict 只带表与 id），从 changes 里回查同一行拿标签
        val label = plan.changes.associateBy { "${it.table}/${it.rowId}" }
        return plan.conflicts.map { k ->
            val c = label["${k.table}/${k.rowId}"]
            val what = c?.let { categoryName(it) } ?: tableName(k.table)
            // `document` 行的书名要用它自己的主键去查（同 [bookId]）；查不到就别硬拼
            // ——原先这里会拼出「的一条文档信息：…」这种断头句。
            val docId = c?.let { bookId(it) } ?: if (k.table == "document") k.rowId else null
            val book = docId?.let { titles[it] }?.let { "《$it》" } ?: ""
            val page = c?.page?.let { "第 ${it + 1} 页" } ?: ""
            val where = book + page
            if (where.isEmpty()) "${what}：${k.note}" else "${where}的一条${what}：${k.note}"
        }.sorted()
    }

    /**
     * 这条改动属于哪本书。
     *
     * 🔴 `document` 表自己那一行**没有 `document_id` 列**，[MirrorDiff.Change.docId] 因此是 null ——
     * 直接拿它归组会把「改了某本书的信息」算成「工作区级设置」。那一行的主键本身就是文档 id。
     */
    fun bookId(c: MirrorDiff.Change): String? =
        c.docId ?: if (c.table == "document") c.rowId else null

    /** 这条改动是不是「只差读到哪儿」。**只影响报告，不影响要不要写** */
    fun isProgressOnly(c: MirrorDiff.Change, plan: MirrorDiff.Plan): Boolean =
        c.table == "document" && c.rowId in plan.progressMerges

    /** 一行式结论（顶栏/按钮旁用） */
    fun headline(plan: MirrorDiff.Plan): String {
        if (plan.isEmpty) return "两端一致"
        // 数的口径与明细一致——明细里不显示的，这里也不该计数，
        // 否则就是「顶上写着 1 条，底下找不到是哪条」
        val toSource = plan.changesTo(MirrorDiff.Side.SOURCE).count { !isProgressOnly(it, plan) }
        val toMirror = plan.changesTo(MirrorDiff.Side.MIRROR).count { !isProgressOnly(it, plan) }
        val bits = ArrayList<String>()
        if (toSource > 0) bits.add("写入硬盘 $toSource")
        if (toMirror > 0) bits.add("拉回本机 $toMirror")
        if (plan.conflicts.isNotEmpty()) bits.add("冲突 ${plan.conflicts.size}")
        val ocr = plan.ocrToSource.size + plan.ocrToMirror.size
        if (ocr > 0) bits.add("识别结果 $ocr 页")
        val aligns = plan.alignToSource.size + plan.alignToMirror.size
        if (aligns > 0) bits.add("扫描页对齐 $aligns 本")
        if (bits.isNotEmpty()) return bits.joinToString(" · ")
        return if (plan.progressMerges.isEmpty()) "只更新「上次打开」" else "只更新阅读进度"
    }
}
