package com.xvan.unireader.local.mirror

import com.xvan.unireader.local.store.MirrorFp

/**
 * 一侧的全部行：`表名 → (row_id → 行)`。
 * 放顶层是因为 Kotlin 的**嵌套 typealias 还是实验特性**（`-Xnested-type-aliases`），
 * 不为一个别名去开实验开关。对应 Mac 的 `MirrorDiff.Snapshot`。
 */
typealias MirrorSnapshot = Map<String, Map<String, Map<String, Any?>>>

/** 基线：`表名 → (row_id → fp)`（镜像库的 `sync_base`）。对应 Mac 的 `MirrorDiff.Base` */
typealias MirrorBase = Map<String, Map<String, String>>

/**
 * 离线镜像的**三方合并**：算出「哪些行要写进源盘、哪些要拉回镜像、哪些冲突」。
 * 方案 `../OFFLINE-MIRROR-PLAN.md` §3.1 的判定表 + §6 的冲突规则。
 *
 * 🔴 **本文件只算不写。** 应用合并是 M5 的事（单事务 + 合并前备份源库）。
 * 分开的理由不是洁癖：干跑预览是这个功能唯一的安全闸，它必须能在**完全不碰任何库**的前提下
 * 跑出完整结论给用户看。算和写混在一起，预览就永远只是"大概会这样"。
 *
 * 🔴 跨端契约：与 Mac `Sources/Store/MirrorDiff.swift` 是同一套判定，改一边必须同步另一边。
 * 判错一格的后果不是"某个功能不好用"，是**静默丢笔迹**。
 */
object MirrorDiff {

    /** 这条改动要落到哪一边 */
    enum class Side { SOURCE, MIRROR }

    enum class Op { UPSERT, DELETE }

    /** 为什么产生这条改动 —— 给报告用，也让「为什么它要删我的东西」永远答得上来 */
    enum class Reason {
        MIRROR_ADDED, MIRROR_DELETED, MIRROR_MODIFIED,   // 镜像侧的动作，落到源盘
        SOURCE_ADDED, SOURCE_DELETED, SOURCE_MODIFIED,   // 源盘侧的动作，落到镜像
        CONFLICT_NEWER,        // 两端都改 → 按 lww 取新的
        CONFLICT_KEPT_SOURCE,  // 两端都改但表没有时间戳列 → 保留源盘
        CONFLICT_KEPT_EDIT,    // 一端删一端改 → 保留"改"（不丢数据优先）
    }

    data class Change(
        val table: String,
        val rowId: String,
        val op: Op,
        val side: Side,
        val reason: Reason,
        /** upsert 时要写入的整行；delete 时为 null */
        val row: Map<String, Any?>?,
        /**
         * 这行属于哪篇文档、`note` 的话是哪一类（kind）。**删除也带**——报告要说
         * 「《高等数学》的一条笔迹」，而删除那条 [row] 是 null，事后就查不出来了。
         */
        val docId: String?,
        val kind: Int?,
        val page: Int?,
    )

    enum class ConflictKind { BOTH_MODIFIED, DELETE_VS_EDIT, BOTH_ADDED }

    data class Conflict(
        val table: String,
        val rowId: String,
        val kind: ConflictKind,
        val kept: Side,
        /** 人话说明（报告直接用） */
        val note: String,
    )

    /**
     * `ocr_page` 的一行的键。**这张表不走上面那套 [Change]**：它是三列复合主键
     * （`content_hash,page,provider`）、没有 `document_id`，而 Change/`sync_base` 那套
     * 从头到尾假设「单列 TEXT 主键」。方案 §4 给它定的是另一条通道：纯 additive、
     * 双向 `INSERT OR IGNORE`、不进基线。
     *
     * 🔴 跨端契约：与 Mac `Sources/Store/MirrorDiff.swift` 的 `OCRKey` 是同一个东西。
     */
    data class OcrKey(val contentHash: String, val page: Int, val provider: String) : Comparable<OcrKey> {
        override fun compareTo(other: OcrKey): Int =
            compareValuesBy(this, other, { it.contentHash }, { it.page }, { it.provider })
    }

    data class Plan(
        val changes: List<Change>,
        val conflicts: List<Conflict>,
        /**
         * `document.last_opened_at` **不在指纹里**（方案 §4：进了指纹「翻开过」就把整行标记成改过），
         * 所以 diff 看不见它 —— 这里单独算出「两边取较大的那个」，`docId → ISO`。
         *
         * 放在 Plan 里而不是留给 M5 自己记：一条不在主流程里的规则，交代在文档里迟早被漏掉。
         */
        val lastOpenedMerges: Map<String, String>,
        /**
         * 「两端都翻过、但**只差读到哪儿**」的文档 id。照常写（按 `last_opened_at` 取最近读过的
         * 那次），但**不进 [conflicts]** —— 那不是要用户裁决的事，报出去只是噪音。
         */
        val progressMerges: Set<String> = emptySet(),
        /**
         * OCR 缓存里**对面缺的那些页**（方案 §4：纯 additive，只补不删、不覆盖）。
         *
         * 只带键不带 payload：干跑要在「一个字都不写」的前提下跑完，而整库的 OCR JSON 是
         * 几百 MB 级的——把它们读进内存只为数个数，预览本身就成了卡顿源。payload 到
         * [MirrorApply.fillOcr] 那一步再按键逐页取。
         */
        val ocrToSource: List<OcrKey> = emptyList(),
        val ocrToMirror: List<OcrKey> = emptyList(),
        /**
         * 扫描页对齐参数（`page_align`，`../SCAN-ALIGN-PLAN.md §5`）要整行覆盖到对面的那些（content_hash）：
         * 按内容 hash 对，一侧缺 → 补；两侧 `updated_at` 不同 → 较新的覆盖较旧的。不进基线、删除不传播。
         * ⚠️ 与 OCR 不同，它**改的是用户设置**（开关）而且决定页面坐标，所以写入硬盘那个方向
         * 与普通改动一样**要人工确认**（计入 [pendingToSource]、挡 [isCleanPushToMirror]）。
         */
        val alignToSource: List<String> = emptyList(),
        val alignToMirror: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = changes.isEmpty() && lastOpenedMerges.isEmpty() &&
                ocrToSource.isEmpty() && ocrToMirror.isEmpty() &&
                alignToSource.isEmpty() && alignToMirror.isEmpty()

        /**
         * 这份 plan 能不能**自动静默地**从源盘推给副本（Mac 2026-09-01 拍板的方向不对称：拉回本机可以
         * 自动做，写入硬盘必须人工确认）。**逐式同 Mac `Plan.isCleanPushToMirror`**（Mac 那边还多一对
         * 图片通道，本端没有图片笔记，故缺那两项）。
         *
         * 安卓端目前没有自动推送入口，同步一律走预览 → 确认；先把判据与 Mac 对齐，接的时候直接用。
         */
        val isCleanPushToMirror: Boolean
            get() = !(changes.isEmpty() && ocrToSource.isEmpty() && ocrToMirror.isEmpty() && alignToMirror.isEmpty()) &&
                conflicts.isEmpty() && changes.all { it.side == Side.MIRROR } && alignToSource.isEmpty()

        /** 待人工确认的条数（副本 → 源盘那个方向），同 Mac `Plan.pendingToSource` */
        val pendingToSource: Int
            get() = changes.count { it.side == Side.SOURCE } + alignToSource.size

        fun changesTo(side: Side): List<Change> = changes.filter { it.side == side }

        fun count(side: Side, op: Op): Int = changes.count { it.side == side && it.op == op }
    }

    /** 一侧的某一行相对基线处于什么状态 */
    enum class RowState { ADDED, DELETED, UNCHANGED, MODIFIED, ABSENT }

    /**
     * `page_align` 那条通道（`../SCAN-ALIGN-PLAN.md §5`）：纯函数，两侧 `content_hash → updated_at`，
     * 返回（写入硬盘的，拉回本机的），各自按 hash 排好序。
     *
     * `updated_at` **直接比字符串**（同 [resolveBoth] 的口径：两端同一 ISO 格式，字典序即时间序）。
     * 🔴 跨端契约：逐条同 Mac `MirrorDiff.alignPlan(mine:theirs:)`。
     */
    fun alignPlan(mine: Map<String, String>, theirs: Map<String, String>): Pair<List<String>, List<String>> {
        val toSource = ArrayList<String>()
        val toMirror = ArrayList<String>()
        for ((hash, m) in mine) {
            val t = theirs[hash]
            if (t == null) {
                toSource.add(hash)
            } else if (m > t) {
                toSource.add(hash)
            } else if (t > m) {
                toMirror.add(hash)
            }
        }
        for (hash in theirs.keys) if (hash !in mine) toMirror.add(hash)
        return toSource.sorted() to toMirror.sorted()
    }

    fun state(base: String?, now: String?): RowState = when {
        base == null && now == null -> RowState.ABSENT
        base == null -> RowState.ADDED
        now == null -> RowState.DELETED
        base == now -> RowState.UNCHANGED
        else -> RowState.MODIFIED
    }

    /**
     * 三方合并主函数。**纯函数，不碰任何库**。
     *
     * @param base 建镜像那一刻的指纹（镜像库的 `sync_base`）
     * @param mine 镜像库现在的全部行
     * @param theirs 源库现在的全部行
     * @param mineOCR / [theirsOCR] 两侧 `ocr_page` 的键集合（不含 payload，见 [Plan.ocrToSource]）
     * @param mineAlign / [theirsAlign] 两侧 `page_align` 的 `content_hash → updated_at`（见 [Plan.alignToSource]）
     */
    fun compute(
        base: MirrorBase,
        mine: MirrorSnapshot,
        theirs: MirrorSnapshot,
        mineOCR: Set<OcrKey> = emptySet(),
        theirsOCR: Set<OcrKey> = emptySet(),
        mineAlign: Map<String, String> = emptyMap(),
        theirsAlign: Map<String, String> = emptyMap(),
    ): Plan {
        val changes = ArrayList<Change>()
        val conflicts = ArrayList<Conflict>()
        val lastOpened = HashMap<String, String>()
        val progressMerges = HashSet<String>()

        for (spec in MirrorFp.specs) {
            val t = spec.table
            val baseFps = base[t].orEmpty()
            val mineRows = mine[t].orEmpty()
            val theirsRows = theirs[t].orEmpty()
            val mineFps = mineRows.mapValues { MirrorFp.fingerprint(it.value, spec) }
            val theirsFps = theirsRows.mapValues { MirrorFp.fingerprint(it.value, spec) }

            for (id in (baseFps.keys + mineFps.keys + theirsFps.keys).sorted()) {
                apply(
                    spec, id,
                    state(baseFps[id], mineFps[id]), state(baseFps[id], theirsFps[id]),
                    mineFps[id], theirsFps[id], mineRows[id], theirsRows[id],
                    changes, conflicts, progressMerges,
                )
            }

            // `last_opened_at` 不进指纹，单独取 max（见 Plan.lastOpenedMerges）
            if (t == "document") {
                for (id in mineRows.keys.intersect(theirsRows.keys)) {
                    val a = mineRows[id]?.get("last_opened_at") as? String ?: ""
                    val b = theirsRows[id]?.get("last_opened_at") as? String ?: ""
                    val mx = maxOf(a, b)
                    if (a != b && mx.isNotEmpty()) lastOpened[id] = mx
                }
            }
        }
        // OCR 缓存：**只补对面缺的、不判改删**（方案 §4）。
        // 「一边清了缓存」于是会被另一边补回来 —— 这是刻意的：这张表是派生数据，
        // 删它的语义是"腾空间/想重跑"，不是"这份内容作废了"，而重跑一次要真花 API 的钱。
        // 扫描页对齐：按 updated_at 取新，一侧没有就补过去（见 [alignPlan]）
        val aligns = alignPlan(mineAlign, theirsAlign)
        return Plan(
            changes, conflicts, lastOpened, progressMerges,
            ocrToSource = (mineOCR - theirsOCR).sorted(),
            ocrToMirror = (theirsOCR - mineOCR).sorted(),
            alignToSource = aligns.first,
            alignToMirror = aligns.second,
        )
    }

    private fun apply(
        spec: MirrorFp.TableSpec,
        id: String,
        m: RowState,
        s: RowState,
        mineFp: String?,
        theirsFp: String?,
        mineRow: Map<String, Any?>?,
        theirsRow: Map<String, Any?>?,
        changes: MutableList<Change>,
        conflicts: MutableList<Conflict>,
        progressMerges: MutableSet<String>,
    ) {
        val t = spec.table
        // 删除那条没有 row，所以标签信息要在这里、趁两侧的行还在手上时取下来
        val any = mineRow ?: theirsRow
        fun mk(op: Op, side: Side, reason: Reason, row: Map<String, Any?>?) = Change(
            t, id, op, side, reason, row,
            docId = any?.get("document_id") as? String,
            kind = (any?.get("kind") as? Long)?.toInt(),
            page = (any?.get("page") as? Long)?.toInt(),
        )

        when {
            // —— 两边一致，什么都不用做 ——
            m == RowState.UNCHANGED && s == RowState.UNCHANGED -> return
            m == RowState.ABSENT && s == RowState.ABSENT -> return
            m == RowState.DELETED && s == RowState.DELETED -> return

            // —— 只有一边动了 ——
            m == RowState.ADDED && s == RowState.ABSENT ->
                changes.add(mk(Op.UPSERT, Side.SOURCE, Reason.MIRROR_ADDED, mineRow))
            m == RowState.ABSENT && s == RowState.ADDED ->
                changes.add(mk(Op.UPSERT, Side.MIRROR, Reason.SOURCE_ADDED, theirsRow))
            m == RowState.DELETED && s == RowState.UNCHANGED ->
                changes.add(mk(Op.DELETE, Side.SOURCE, Reason.MIRROR_DELETED, null))
            m == RowState.UNCHANGED && s == RowState.DELETED ->
                changes.add(mk(Op.DELETE, Side.MIRROR, Reason.SOURCE_DELETED, null))
            m == RowState.MODIFIED && s == RowState.UNCHANGED ->
                changes.add(mk(Op.UPSERT, Side.SOURCE, Reason.MIRROR_MODIFIED, mineRow))
            m == RowState.UNCHANGED && s == RowState.MODIFIED ->
                changes.add(mk(Op.UPSERT, Side.MIRROR, Reason.SOURCE_MODIFIED, theirsRow))

            // —— 一端删、一端改：**保留"改"**（方案 §6，不丢用户数据优先）——
            m == RowState.DELETED && s == RowState.MODIFIED -> {
                changes.add(mk(Op.UPSERT, Side.MIRROR, Reason.CONFLICT_KEPT_EDIT, theirsRow))
                conflicts.add(
                    Conflict(t, id, ConflictKind.DELETE_VS_EDIT, Side.SOURCE,
                        "本机删掉了它、硬盘上又改过它 —— 保留了硬盘上那份"),
                )
            }
            m == RowState.MODIFIED && s == RowState.DELETED -> {
                changes.add(mk(Op.UPSERT, Side.SOURCE, Reason.CONFLICT_KEPT_EDIT, mineRow))
                conflicts.add(
                    Conflict(t, id, ConflictKind.DELETE_VS_EDIT, Side.MIRROR,
                        "硬盘上删掉了它、本机又改过它 —— 保留了本机那份"),
                )
            }

            // —— 两端都动了 ——
            (m == RowState.MODIFIED && s == RowState.MODIFIED) ||
                (m == RowState.ADDED && s == RowState.ADDED) -> {
                if (mineFp == theirsFp) return // 两边改成一样了，无操作
                val kind =
                    if (m == RowState.ADDED) ConflictKind.BOTH_ADDED else ConflictKind.BOTH_MODIFIED
                resolveBoth(spec, id, kind, mineRow, theirsRow, ::mk, changes, conflicts, progressMerges)
            }

            // 剩下的组合在数学上到不了（一边 ABSENT 意味着 base 里没有，另一边就不可能是
            // UNCHANGED/MODIFIED/DELETED）。真到了说明判定表被改坏了，宁可炸也不要静默。
            else -> error("MirrorDiff: 不该出现的状态组合 $t/$id mine=$m theirs=$s")
        }
    }

    /** 两端都改了同一行：有时间戳列就取新的，没有就保留源盘（方案 §6） */
    private fun resolveBoth(
        spec: MirrorFp.TableSpec,
        id: String,
        kind: ConflictKind,
        mineRow: Map<String, Any?>?,
        theirsRow: Map<String, Any?>?,
        mk: (Op, Side, Reason, Map<String, Any?>?) -> Change,
        changes: MutableList<Change>,
        conflicts: MutableList<Conflict>,
        progressMerges: MutableSet<String>,
    ) {
        // 🔴 **只差「读到哪儿」不算冲突**：两端各翻过同一本书就会走到这里，但那是正常使用。
        // 照常按 lww 选一边写，只是**不报成冲突** —— 报了用户既判断不了也不该判断。
        if (spec.table == "document" && mineRow != null && theirsRow != null &&
            MirrorFp.fingerprint(mineRow, spec, MirrorFp.progressColumns) ==
            MirrorFp.fingerprint(theirsRow, spec, MirrorFp.progressColumns)
        ) {
            val stampCol = spec.lww ?: ""
            val a = mineRow[stampCol] as? String ?: ""
            val b = theirsRow[stampCol] as? String ?: ""
            val keepMine = a > b
            changes.add(
                mk(
                    Op.UPSERT,
                    if (keepMine) Side.SOURCE else Side.MIRROR,
                    Reason.CONFLICT_NEWER,
                    if (keepMine) mineRow else theirsRow,
                ),
            )
            progressMerges.add(id)
            return
        }
        val col = spec.lww
        if (col != null) {
            // 时间戳是定宽 UTC（`yyyy-MM-ddTHH:mm:ss.SSSZ`，两端同一格式，见 Iso/ISO），
            // **直接比字符串**：不引入日期解析，也就没有「两端的解析器对同一个串给出不同结果」这条缝。
            val a = mineRow?.get(col) as? String ?: ""
            val b = theirsRow?.get(col) as? String ?: ""
            val keepMine = a > b
            changes.add(
                mk(
                    Op.UPSERT,
                    if (keepMine) Side.SOURCE else Side.MIRROR,
                    Reason.CONFLICT_NEWER,
                    if (keepMine) mineRow else theirsRow,
                ),
            )
            conflicts.add(
                Conflict(
                    spec.table, id, kind, if (keepMine) Side.MIRROR else Side.SOURCE,
                    "两端都改过 —— 保留了较新的那份（${maxOf(a, b)}）",
                ),
            )
        } else {
            // 无时间戳列的表（document/variant/ink_layer/meta）：可冲突的字段只有标题、分组、
            // 阅读进度这类低价值项，保守选一边即可，但**必须报出来**。
            changes.add(mk(Op.UPSERT, Side.MIRROR, Reason.CONFLICT_KEPT_SOURCE, theirsRow))
            conflicts.add(
                Conflict(
                    spec.table, id, kind, Side.SOURCE,
                    "两端都改过，这张表没有时间戳可比 —— 保留了硬盘上那份",
                ),
            )
        }
    }
}
