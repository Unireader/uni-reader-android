package com.xvan.unireader.local.exercise

import com.xvan.unireader.shared.MiniJson

/*
 * 习题的纯逻辑（模式1，`../docs/EXERCISE-PLAN.md §10.2`）：逐条对应 Mac `Sources/App/Exercise.swift` 里**练习要用的那部分**
 * ——payload 解码、判题、以前的作答、范围键与 `includes`、筛选、侧栏的数、章节树、侧栏分组。
 * 导入 / 校验 / 去重是 Agent 在 Mac 上做的，这里不抄。
 *
 * 🔴 **同一套规则两份实现，改一边必须同步另一边**；JVM 单测 `ExerciseTest` 与 `../spike/exercise-test.swift` 的同名几段对应。
 * 只依赖 Kotlin 标准库 + [MiniJson]（`org.json` 在 JVM 单测里是空壳）。
 */

/** 一个选项。 */
data class ExOption(val key: String, val text: String)

/** `exercise.payload`（JSON，键 snake_case；缺的键按 Mac `decodeIfPresent` 的默认值）。 */
data class ExPayload(
    val stem: String,
    val options: List<ExOption>,
    /** 正确选项的键 / 填空的每空参考答案 / 主观题的参考答案正文（空 = 还没有答案）。 */
    val answer: List<String>,
    /** `book` / `agent`；没有答案时为 null。 */
    val answerSource: String?,
    val explanation: String?,
    /** 题目在页上的框 `[x, y, w, h]`（归一化 0~1、左上原点）。 */
    val region: List<Double>?,
    /** 带图 / 表：练习时默认显示原题截图。 */
    val figure: Boolean,
    /** 填空题的空数（v21；没答案时靠它摆输入框）。 */
    val blanks: Int?,
    /** 填空每空另外认可的写法（与 answer 等长；全空没有这个键）。 */
    val accept: List<List<String>>?,
    /**
     * 跨页的题：后面几页上各一个框（第 k 个 = `page + 1 + k` 页），同 [region] 的口径；null = 不跨页
     * （`../docs/EXERCISE-PLAN.md §26`）。只在有 [region] 时才算数（[Exercise.originalSlices]）。
     */
    val nextRegions: List<List<Double>>? = null,
) {
    companion object {
        /** 坏了 → null（同 Mac：调用方跳过这一行）。 */
        fun parse(bytes: ByteArray): ExPayload? {
            val o = MiniJson.parse(bytes) as? Map<*, *> ?: return null
            val opts = (o["options"] as? List<*> ?: emptyList<Any?>()).mapNotNull { x ->
                val m = x as? Map<*, *> ?: return@mapNotNull null
                ExOption(m["key"] as? String ?: return@mapNotNull null, m["text"] as? String ?: "")
            }
            val region = (o["region"] as? List<*>)?.mapNotNull { (it as? Double) }
            return ExPayload(
                stem = o["stem"] as? String ?: "",
                options = opts,
                answer = (o["answer"] as? List<*> ?: emptyList<Any?>()).mapNotNull { it as? String },
                answerSource = o["answer_source"] as? String,
                explanation = o["explanation"] as? String,
                region = region?.takeIf { it.size == 4 },
                figure = o["figure"] as? Boolean ?: false,
                blanks = (o["blanks"] as? Double)?.toInt(),
                accept = (o["accept"] as? List<*>)?.map { row ->
                    (row as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
                },
                nextRegions = cleanNext(o["next_regions"]),
            )
        }

        /** 跨页的题一道最多往后接几页（同 Mac `ExerciseRegion.maxNextPages`）。 */
        const val MAX_NEXT_PAGES = 3

        /** 一个框合格：4 个 0~1 的数、宽高 > 0（同 Mac `ExerciseRegion.isValid`）。 */
        fun validBox(r: List<Double>): Boolean =
            r.size == 4 && r.all { it.isFinite() && it in 0.0..1.0 } && r[2] > 0 && r[3] > 0

        /** 同 Mac `ExerciseRegion.cleanNext`：截到第一个不合格的框之前、至多 [MAX_NEXT_PAGES] 个；一个不剩 / 类型不对 → null。 */
        fun cleanNext(raw: Any?): List<List<Double>>? {
            val list = raw as? List<*> ?: return null
            val out = ArrayList<List<Double>>()
            for (b in list.take(MAX_NEXT_PAGES)) {
                val nums = (b as? List<*>)?.map { it as? Double }
                if (nums == null || nums.any { it == null }) break
                val box = nums.filterNotNull()
                if (!validBox(box)) break
                out.add(box)
            }
            return out.ifEmpty { null }
        }
    }
}

/** 一道题做过几次（`exercise_attempt` 的统计，同 Mac `ExerciseAttemptStats`）。没做过的题不在统计里。 */
data class ExAttemptStats(
    val attempts: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val lastCorrect: Boolean? = null,
    /** 书上的作答（`source = 'mark'`，每道题至多一条）。 */
    val markedAnswer: String? = null,
    val markedCorrect: Boolean? = null,
    /** App 里练的最近一次（书上那条除外：它的时间是导入那一刻）。 */
    val lastAnswer: String? = null,
    /** ISO 毫秒 UTC。 */
    val lastAt: String? = null,
    /** App 里练的最近一次那条作答的 id / 对错（填空显示用，Mac `lastPracticedCorrect`；对错**用库里记的**）。 */
    val lastAttemptId: String? = null,
    val lastPracticedCorrect: Boolean? = null,
    /** 书上作答那条的 id。 */
    val markedAttemptId: String? = null,
    /** 最近一次 / 书上那条作答的 Agent 评语（`feedback` 列）与带不带手写（`ink` 列非空）。 */
    val lastFeedback: String = "",
    val lastHasInk: Boolean = false,
    val markedFeedback: String = "",
    val markedHasInk: Boolean = false,
)

/** 以前的作答从哪来：App 里练的最近一次（带时间）/ 书上的作答。 */
sealed class ExEarlier {
    data class Practiced(val at: String?) : ExEarlier()
    object Book : ExEarlier()
}

/** 一道题（库行 + 解码后的 payload），同 Mac `Exercise`。 */
data class Exercise(
    val id: String,
    val documentId: String?,
    /** 0 起。 */
    val page: Int?,
    val seq: Int,
    /** 0 单选 · 1 多选。 */
    val kind: Int,
    val number: String,
    val section: String,
    val payload: ExPayload,
    val starred: Boolean,
    val wrong: Boolean,
    val source: String,
    val createdAt: String,
    val updatedAt: String,
    /** 所属章节：目录里的路径（顶层 → 最深一级），空 = 没设。 */
    val chapter: List<String>,
    /** 题目笔记（v22 `exercise.note`，Markdown + `$…$`；空 = 没写。用户状态，Agent 重导题目不碰）。 */
    val note: String = "",
) {
    val multiple: Boolean get() = kind == KIND_MULTIPLE
    /** 选择题（单选 / 多选）。 */
    val isChoice: Boolean get() = kind == KIND_SINGLE || kind == KIND_MULTIPLE
    /** 填空题（主观题本端仍只显示题干、提示到 Mac 上作答）。 */
    val isFill: Boolean get() = kind == KIND_FILL
    /** 主观题（计算 / 证明 / 简答）：模式1 能打字作答、自己判（2026-11-10）；手写（`ink` 列）本端不做。 */
    val isSubjective: Boolean get() = kind == KIND_SUBJECTIVE
    /** 打字作答的题型（填空 / 主观题）：作答、以前的作答、自己判定走同一条路。 */
    val isTyped: Boolean get() = isFill || isSubjective
    val hasAnswer: Boolean get() = payload.answer.any { it.isNotEmpty() }
    val answerFromAgent: Boolean get() = hasAnswer && payload.answerSource == "agent"

    /**
     * 看原题要渲的几段（同 Mac `Exercise.originalSlices`，`../docs/EXERCISE-PLAN.md §26`）：起始页那一块，
     * 跨页的题再接后面几页各一块，从上到下拼成一张。没有框 = 起始页整页一段（框为 null）。
     * 给了 [pageCount] 就去掉越过最后一页的。没页码 → 空。
     */
    fun originalSlices(pageCount: Int? = null): List<Pair<Int, List<Double>?>> {
        val p = page ?: return emptyList()
        val r = payload.region ?: return listOf(p to null)
        val out = arrayListOf<Pair<Int, List<Double>?>>(p to r)
        for ((k, n) in (payload.nextRegions ?: emptyList()).withIndex()) {
            val q = p + 1 + k
            if (pageCount != null && q >= pageCount) break
            out.add(q to n)
        }
        return out
    }

    /** 题目印到第几页为止（0 起；不跨页 = [page]）。 */
    val lastPage: Int? get() = page?.let { it + if (payload.region == null) 0 else payload.nextRegions?.size ?: 0 }

    /** 判题：选中的键与答案**完全相同**才算对。没有答案 → null（没法判）。只管选择题。 */
    fun judge(chosen: Set<String>): Boolean? = if (!isChoice || !hasAnswer) null else chosen == payload.answer.toSet()

    // ---------- 填空（v21，`../docs/EXERCISE-PLAN.md §11`，逐条照 Mac）----------

    /** 填空题有几个空（别的题型 0）：`blanks` 键与参考答案数取大。 */
    val blankCount: Int get() = if (isFill) maxOf(payload.blanks ?: 0, payload.answer.size) else 0

    /** 第 `i` 空认可的写法：参考答案 + `accept` 里的（原样，没规整）。 */
    fun acceptedForms(blank: Int): List<String> {
        val out = ArrayList<String>()
        if (payload.answer.indices.contains(blank)) out += payload.answer[blank]
        payload.accept?.getOrNull(blank)?.let { out += it }
        return out.filter { it.isNotEmpty() }
    }

    /** 填空每一空对不对得上参考答案（规整后一字不差，[normalizeBlank]）。没有答案的空 = false。 */
    fun blankMatches(blanks: List<String>): List<Boolean> =
        (0 until blankCount).map { i ->
            val mine = blanks.getOrElse(i) { "" }.let(::normalizeBlank)
            mine.isNotEmpty() && acceptedForms(i).any { normalizeBlank(it) == mine }
        }

    /**
     * 填空程序判题（拍板 ①）：**每空都对得上才判对**；有对不上的（或题目没答案）→ null 交给人判——
     * `1/2` 和 `0.5` 这类等价写法程序认不出，所以不直接判错。
     */
    fun judgeFill(blanks: List<String>): Boolean? {
        if (!isFill || !hasAnswer || blankCount <= 0) return null
        return if (blankMatches(blanks).all { it }) true else null
    }

    /**
     * 以前的填空作答（规则同 [earlierAnswer]：App 里练的最近一次优先，没练过再拿书上的作答）。
     * 对错**用库里记的**（自己判过的没法现算）。没做过 → null。
     */
    fun earlierFill(s: ExAttemptStats?): Quadruple? {
        if (!isTyped || s == null) return null
        // 主观题的 `answer` 列是原文（同 Mac `ExerciseResponse.decode`），当成一格
        fun parts(raw: String) = if (isSubjective) listOf(raw) else decodeBlanks(raw)
        if (s.lastAttemptId != null && s.lastAnswer != null)
            return Quadruple(parts(s.lastAnswer), s.lastPracticedCorrect, s.lastAttemptId, ExEarlier.Practiced(s.lastAt), s.lastFeedback, s.lastHasInk)
        if (s.markedAttemptId != null && s.markedAnswer != null)
            return Quadruple(parts(s.markedAnswer), s.markedCorrect, s.markedAttemptId, ExEarlier.Book, s.markedFeedback, s.markedHasInk)
        return null
    }

    /** [earlierFill] 的一份：每空的作答、库里记的对错、那条作答的 id、从哪来。 */
    class Quadruple(
        val blanks: List<String>, val correct: Boolean?, val attemptId: String, val earlier: ExEarlier,
        /** 那次作答的 Agent 评语（Markdown，空 = 没有）/ 带不带手写（Mac 上写的，本端只提示一句）。 */
        val feedback: String = "", val hasInk: Boolean = false,
    )

    /**
     * 以前做过的那次（用户 2026-10-11 定：做过的题直接显示上次结果）：App 里练的最近一次优先，没练过再拿书上的作答。
     * 对错按**现在的**答案现判。没做过 → null。只管选择题（填空 / 主观题的作答串不是选项字母）。
     */
    fun earlierAnswer(s: ExAttemptStats?): Triple<Set<String>, Boolean?, ExEarlier>? {
        if (!isChoice) return null
        fun keys(raw: String?): Set<String>? {
            val k = raw?.let(::parseAnswer) ?: return null
            return k.takeIf { it.isNotEmpty() }?.toSet()
        }
        keys(s?.lastAnswer)?.let { return Triple(it, judge(it), ExEarlier.Practiced(s?.lastAt)) }
        keys(s?.markedAnswer)?.let { return Triple(it, judge(it), ExEarlier.Book) }
        return null
    }

    /**
     * 上一次的结果是错的（「重做上次做错的」挑的就是这些，`../docs/EXERCISE-PLAN.md §12.2`，逐条照 Mac `Exercise.lastResultWrong`）：
     * 看的是练习页上显示的那次——App 里练的最近一次，没练过才看书上的作答。选择题按现在的答案现判；
     * 填空 / 主观题用库里记的对错（同 Mac `earlierResponse`，主观题本端不能作答、但 Mac 上判错的照样挑进来）。
     * 答对过、后来又做错的也算；还没判的（等自己判 / Agent 判、题目没答案）不算错。
     */
    fun lastResultWrong(s: ExAttemptStats?): Boolean {
        if (isChoice) return earlierAnswer(s)?.second == false
        if (s == null) return false
        if (s.lastAttemptId != null && s.lastAnswer != null) return s.lastPracticedCorrect == false
        if (s.markedAttemptId != null && s.markedAnswer != null) return s.markedCorrect == false
        return false
    }

    /** 列表里一行显示的题干：去掉换行、并掉连续空白。 */
    val plainStem: String get() = payload.stem.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        const val KIND_SINGLE = 0
        const val KIND_MULTIPLE = 1
        /** v21（2026-10-13）：填空 / 主观题。2 留给以后的判断题；不认识的值整行跳过。 */
        const val KIND_FILL = 3
        const val KIND_SUBJECTIVE = 4
        /** 书上的作答那条的 `exercise_attempt.source`。 */
        const val MARK_SOURCE = "mark"

        private val KEY_LETTERS = "ABCDEFGH".map { it.toString() }

        /** 作答记录里存的那串：选中的键按字母序拼起来。 */
        fun answerString(keys: Set<String>): String = keys.sorted().joinToString("")

        // ---------- 填空（v21，逐条照 Mac `Exercise.normalizeBlank` / `ExerciseResponse`）----------

        /**
         * 填空比对前的规整：NFKC（全角转半角）、去掉 `$` 和全部空白、几个只管排版的 LaTeX 写法、句末标点。
         * 只做「肯定等价」的变换——宁可没认出来交给人判，也不能把错的判成对。
         */
        fun normalizeBlank(s: String): String {
            var t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
            for ((a, b) in listOf(
                "\\dfrac" to "\\frac", "\\tfrac" to "\\frac", "\\left" to "", "\\right" to "",
                "\\," to "", "\\;" to "", "\\!" to "", "\\ " to "", "$" to "",
            )) {
                t = t.replace(a, b)
            }
            t = t.filter { !it.isWhitespace() }
            while (t.isNotEmpty() && t.last() in "。.，,；;") t = t.dropLast(1)
            return t
        }

        /** 填空写进 `answer` 列的串：JSON 字符串数组（写法同 Mac，同 [ExChapter.text]）。 */
        fun fillAnswerString(blanks: List<String>): String = ExChapter.text(blanks)

        /** 从 `answer` 列还原每空的作答；认不出的串（不是 JSON 数组）当成只填了一空（同 Mac `ExerciseResponse.decode`）。 */
        fun decodeBlanks(raw: String): List<String> {
            val v = MiniJson.parse(raw.toByteArray(Charsets.UTF_8)) as? List<*> ?: return if (raw.isEmpty()) emptyList() else listOf(raw)
            return v.mapNotNull { it as? String }
        }

        /**
         * 打开一组题时停在第几道（用户 2026-10-31 定「接着上次做的往下」，`../docs/EXERCISE-PLAN.md §13`），逐条照抄 Mac `Exercise.resumeIndex`：
         * 先找这一组里**在 App 里最近练过**的那道（`lastAt` 最大；书上的作答不算），从它后面找第一道没做过的；
         * 后面都做过了，从头找第一道没做过的；全做过 → 第 1 题。「没做过」同「只看没做过」（一次作答都没有，书上的也算做过）。
         * 两道时间一样时取排在后面的那道。时间按 ISO 串比：两端写的是同一种定长格式，库里挑「最近一次」的 `MAX(created_at)` 也是按串比。
         */
        fun resumeIndex(items: List<Exercise>, stats: Map<String, ExAttemptStats>): Int {
            fun undone(i: Int) = (stats[items[i].id]?.attempts ?: 0) == 0
            var last = -1
            var lastAt: String? = null
            items.forEachIndexed { i, e ->
                val t = stats[e.id]?.lastAt ?: return@forEachIndexed
                val prev = lastAt
                if (prev == null || t >= prev) { last = i; lastAt = t }
            }
            val start = last + 1
            (start until items.size).firstOrNull { undone(it) }?.let { return it }
            return (0 until start).firstOrNull { undone(it) } ?: 0
        }

        /** 「AC」「A,C」「A、C」「A C」→ [A, C]（保序去重）；含选项键以外的字母 → null（同 Mac `ExerciseImport.parseAnswer`）。 */
        fun parseAnswer(raw: String): List<String>? {
            val out = ArrayList<String>()
            for (ch in raw.uppercase()) {
                if (ch.isWhitespace() || ch in ",，、;；/") continue
                val s = ch.toString()
                if (ch.code > 127 || !ch.isLetter() || s !in KEY_LETTERS) return null
                if (s !in out) out.add(s)
            }
            return out
        }
    }
}

// ---------- 章节（同 Mac `ExerciseChapter`）----------

/** 目录的一个节点（名字、页、子节点；页 0 起，null = 目录项没有落点）。 */
data class ExChapterNode(val label: String, val page: Int?, val children: List<ExChapterNode> = emptyList())

object ExChapter {
    /** 路径规整：每段去首尾空白、去掉空段。 */
    fun clean(path: List<String>): List<String> = path.map { it.trim() }.filter { it.isNotEmpty() }

    /** 显示用的写法：「第二章 导数 › 2.3 求导法则」。 */
    fun display(path: List<String>): String = path.joinToString(" › ")

    /** 第 `page` 页（0 起）在目录里属于哪一节：每一层取「起始页 ≤ page 的最后一个」，一直到最深。 */
    fun path(page: Int, toc: List<ExChapterNode>): List<String> {
        val out = ArrayList<String>()
        var level = toc
        while (true) {
            val hit = level.lastOrNull { (startPage(it) ?: Int.MAX_VALUE) <= page } ?: break
            val label = hit.label.trim()
            if (label.isNotEmpty()) out.add(label)
            level = hit.children
        }
        return out
    }

    private fun startPage(n: ExChapterNode): Int? {
        n.page?.let { return it }
        for (c in n.children) startPage(c)?.let { return it }
        return null
    }

    /** 目录摊平成一列（「设置章节…」的选单用）：每项是到它为止的完整路径。 */
    fun flatten(toc: List<ExChapterNode>, prefix: List<String> = emptyList()): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (n in toc) {
            val label = n.label.trim()
            val p = if (label.isEmpty()) prefix else prefix + label
            if (label.isNotEmpty()) out.add(p)
            out += flatten(n.children, p)
        }
        return out
    }

    /** 路径 → 库里那一列（JSON 字符串数组；空路径存空串）。写法同 Mac `JSONSerialization`（无空格、斜杠不转义）。 */
    fun text(path: List<String>): String {
        if (path.isEmpty()) return ""
        return path.joinToString(",", "[", "]") { s ->
            val sb = StringBuilder("\"")
            for (ch in s) {
                when {
                    ch == '"' -> sb.append("\\\"")
                    ch == '\\' -> sb.append("\\\\")
                    ch == '\n' -> sb.append("\\n")
                    ch == '\r' -> sb.append("\\r")
                    ch == '\t' -> sb.append("\\t")
                    ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
                    else -> sb.append(ch)
                }
            }
            sb.append('"').toString()
        }
    }

    /** 库里那一列 → 路径（坏了 / 空 → 空路径）。 */
    fun parse(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val a = MiniJson.parse(text.toByteArray(Charsets.UTF_8)) as? List<*> ?: return emptyList()
        return a.mapNotNull { it as? String }
    }

    /** 章节 `scope` 管不管得着挂在 `chapter` 的题：空路径只管没设章节的；否则 = 它自己或它下面的小节。 */
    fun contains(scope: List<String>, chapter: List<String>): Boolean =
        if (scope.isEmpty()) chapter.isEmpty() else chapter.size >= scope.size && chapter.subList(0, scope.size) == scope
}

// ---------- 范围（标签的身份，同 Mac `ExerciseScope`）----------

sealed class ExScope {
    object All : ExScope()
    object Wrong : ExScope()
    object Starred : ExScope()
    data class Document(val id: String) : ExScope()
    /** 一本书的某一章（含它下面的各小节）。路径为空 = 这本书里没设章节的那些题。 */
    data class Chapter(val id: String, val path: List<String>) : ExScope()
    data class WrongDocument(val id: String) : ExScope()
    data class WrongChapter(val id: String, val path: List<String>) : ExScope()
    /** 收藏按书 / 按章展开的那几级（同错题本）。 */
    data class StarredDocument(val id: String) : ExScope()
    data class StarredChapter(val id: String, val path: List<String>) : ExScope()

    /** 范围键（与 Mac `ExerciseScope.key` 逐字相同：标签页存的、线上发的都是它）。 */
    val key: String
        get() = when (this) {
            All -> "all"
            Wrong -> "wrong"
            Starred -> "starred"
            is Document -> "doc:$id"
            is Chapter -> "chap:$id:" + ExChapter.text(path)
            is WrongDocument -> WRONG_PREFIX + Document(id).key
            is WrongChapter -> WRONG_PREFIX + Chapter(id, path).key
            is StarredDocument -> STARRED_PREFIX + Document(id).key
            is StarredChapter -> STARRED_PREFIX + Chapter(id, path).key
        }

    /** 这个范围属于哪本书（全部 / 错题本 / 收藏不属于某一本）。 */
    val documentId: String?
        get() = when (this) {
            is Document -> id
            is Chapter -> id
            is WrongDocument -> id
            is WrongChapter -> id
            is StarredDocument -> id
            is StarredChapter -> id
            else -> null
        }

    /** 错题本这一支：默认都在**一个错题标签**里换。 */
    val isMistakes: Boolean get() = this == Wrong || this is WrongDocument || this is WrongChapter

    fun includes(e: Exercise): Boolean = when (this) {
        All -> true
        Wrong -> e.wrong
        Starred -> e.starred
        is Document -> e.documentId == id
        is Chapter -> e.documentId == id && ExChapter.contains(path, e.chapter)
        is WrongDocument -> e.wrong && e.documentId == id
        is WrongChapter -> e.wrong && e.documentId == id && ExChapter.contains(path, e.chapter)
        is StarredDocument -> e.starred && e.documentId == id
        is StarredChapter -> e.starred && e.documentId == id && ExChapter.contains(path, e.chapter)
    }

    companion object {
        private const val WRONG_PREFIX = "wrong:"
        private const val STARRED_PREFIX = "starred:"

        fun parse(key: String): ExScope? = when (key) {
            "all" -> All
            "wrong" -> Wrong
            "starred" -> Starred
            else -> when {
                // 错题本 / 收藏底下的几级 = 前缀 + 书 / 章的键
                key.startsWith(WRONG_PREFIX) -> when (val inner = parse(key.removePrefix(WRONG_PREFIX))) {
                    is Document -> WrongDocument(inner.id)
                    is Chapter -> WrongChapter(inner.id, inner.path)
                    else -> null
                }
                key.startsWith(STARRED_PREFIX) -> when (val inner = parse(key.removePrefix(STARRED_PREFIX))) {
                    is Document -> StarredDocument(inner.id)
                    is Chapter -> StarredChapter(inner.id, inner.path)
                    else -> null
                }
                key.startsWith("chap:") -> {
                    val rest = key.substring(5)
                    val colon = rest.indexOf(':')
                    if (colon <= 0) null else Chapter(rest.substring(0, colon), ExChapter.parse(rest.substring(colon + 1)))
                }
                key.startsWith("doc:") && key.length > 4 -> Document(key.substring(4))
                else -> null
            }
        }
    }
}

/**
 * 习题标签顶上的筛选（同 Mac `ExerciseFilter`，值即线上 `exDeck.filter`）：只剩「只看没做过」一个开关
 * （用户 2026-10-11 定：哪一组题在抽屉里选，顶栏只管做题进度）。`2` 错题 / `3` 收藏已去掉，不再用。
 */
enum class ExFilter(val code: Int) {
    ALL(0), UNANSWERED(1),
    /** 重做上次做错的（同 Mac `ExerciseFilter.lastWrong`；`2` / `3` 是去掉的错题 / 收藏，不许再分配）。 */
    LAST_WRONG(4);

    /** `s` = 这道题的作答统计（null = 没做过；做过几次含书上的作答）。 */
    fun includes(e: Exercise, s: ExAttemptStats?): Boolean = when (this) {
        ALL -> true
        UNANSWERED -> (s?.attempts ?: 0) == 0
        LAST_WRONG -> e.lastResultWrong(s)
    }

    companion object {
        fun of(code: Int): ExFilter? = entries.firstOrNull { it.code == code }
    }
}

// ---------- 侧栏的数 + 章节树 + 分组（同 Mac `ExerciseSummary` / `ExerciseChapterTree` / `ExerciseGroupNode`）----------

/** 挂在某个章节路径上的题：几道、最早在第几页（0 起；全没页码 = `Int.MAX_VALUE`）、其中几道在错题本里、几道收藏了。 */
data class ExChapterStat(val count: Int, val firstPage: Int, val wrong: Int = 0, val starred: Int = 0)

data class ExSummary(
    val total: Int = 0,
    val wrong: Int = 0,
    val starred: Int = 0,
    /** 每篇有题的文档几道。 */
    val byDocument: Map<String, Int> = emptyMap(),
    /** 每篇文档每个章节**恰好**挂在这一级的题（空路径 = 没设章节的）。 */
    val chapters: Map<String, Map<List<String>, ExChapterStat>> = emptyMap(),
) {
    fun count(scope: ExScope): Int = when (scope) {
        ExScope.All -> total
        ExScope.Wrong -> wrong
        ExScope.Starred -> starred
        is ExScope.Document -> byDocument[scope.id] ?: 0
        is ExScope.Chapter -> (chapters[scope.id] ?: emptyMap())
            .entries.sumOf { if (ExChapter.contains(scope.path, it.key)) it.value.count else 0 }
        is ExScope.WrongDocument -> (chapters[scope.id] ?: emptyMap()).values.sumOf { it.wrong }
        is ExScope.WrongChapter -> (chapters[scope.id] ?: emptyMap())
            .entries.sumOf { if (ExChapter.contains(scope.path, it.key)) it.value.wrong else 0 }
        is ExScope.StarredDocument -> (chapters[scope.id] ?: emptyMap()).values.sumOf { it.starred }
        is ExScope.StarredChapter -> (chapters[scope.id] ?: emptyMap())
            .entries.sumOf { if (ExChapter.contains(scope.path, it.key)) it.value.starred else 0 }
    }

    /** 错题本里一本书的章节分布：只留有错题的章节，`count` 换成错题数（`firstPage` 仍按这一节全部题，排序同书底下那棵）。 */
    fun mistakeChapters(documentId: String): Map<List<String>, ExChapterStat> = marked(documentId) { it.wrong }

    /** 收藏里一本书的章节分布（同错题本那棵，数的是收藏）。 */
    fun starredChapters(documentId: String): Map<List<String>, ExChapterStat> = marked(documentId) { it.starred }

    private fun marked(documentId: String, n: (ExChapterStat) -> Int): Map<List<String>, ExChapterStat> =
        (chapters[documentId] ?: emptyMap()).filterValues { n(it) > 0 }
            .mapValues { it.value.copy(count = n(it.value)) }
}

/** 一个章节路径上做过几道 + 其中收藏了的几道（同 Mac `ExerciseDoneStat`）。 */
data class ExDoneStat(val done: Int, val starred: Int = 0)

/**
 * 每组题做过几道（侧栏 / 抽屉行尾「已做 / 共」，同 Mac `ExerciseDoneSummary`，`../docs/EXERCISE-PLAN.md §16`）。
 * 「做过」= 至少一次作答（含书上的作答）；数据来自 `LibraryStore.exerciseDoneCounts`（一条 GROUP BY）。
 */
data class ExDoneSummary(
    /** 文档 id → 章节路径（恰好挂在这一级）→ 做过的题数 + 其中收藏了的几道。 */
    val chapters: Map<String, Map<List<String>, ExDoneStat>> = emptyMap(),
) {
    /** 这一组做过几道。错题本那一支返回 null：里面的题都是答错进来的、全都做过，行尾只显示题数。 */
    fun done(scope: ExScope): Int? {
        fun sum(id: String?, path: List<String>?, n: (ExDoneStat) -> Int): Int {
            val books = if (id != null) listOf(chapters[id] ?: emptyMap()) else chapters.values.toList()
            var total = 0
            for (book in books) {
                for ((chapter, stat) in book) {
                    if (path == null || ExChapter.contains(path, chapter)) total += n(stat)
                }
            }
            return total
        }
        return when (scope) {
            ExScope.All -> sum(null, null) { it.done }
            ExScope.Starred -> sum(null, null) { it.starred }
            is ExScope.Document -> sum(scope.id, null) { it.done }
            is ExScope.Chapter -> sum(scope.id, scope.path) { it.done }
            is ExScope.StarredDocument -> sum(scope.id, null) { it.starred }
            is ExScope.StarredChapter -> sum(scope.id, scope.path) { it.starred }
            ExScope.Wrong, is ExScope.WrongDocument, is ExScope.WrongChapter -> null
        }
    }

    companion object {
        /** 由 `LibraryStore.exerciseDoneCounts` 的行（文档 id, 章节路径, 做过几道, 其中收藏几道）装起来。 */
        fun of(rows: List<ExDoneRow>): ExDoneSummary {
            val m = HashMap<String, HashMap<List<String>, ExDoneStat>>()
            for (r in rows) m.getOrPut(r.documentId) { HashMap() }[r.chapter] = ExDoneStat(r.done, r.starred)
            return ExDoneSummary(m)
        }
    }
}

/** `exerciseDoneCounts` 的一行。 */
data class ExDoneRow(val documentId: String, val chapter: List<String>, val done: Int, val starred: Int)

/** 一本书底下的章节树：由「每个路径恰好几道」累加出每一级的总数。 */
data class ExChapterTree(
    val label: String,
    val path: List<String>,
    val count: Int,
    val children: List<ExChapterTree>,
    val firstPage: Int,
) {
    companion object {
        /** 同级按「下面最早那道题的页码」排（书里的先后），同页按名字。空路径不进树。 */
        fun build(stats: Map<List<String>, ExChapterStat>): List<ExChapterTree> {
            fun level(prefix: List<String>): List<ExChapterTree> {
                val labels = LinkedHashSet<String>()
                for (path in stats.keys) {
                    if (path.size > prefix.size && path.subList(0, prefix.size) == prefix) labels.add(path[prefix.size])
                }
                return labels.map { l ->
                    val p = prefix + l
                    val under = stats.filterKeys { ExChapter.contains(p, it) }.values
                    ExChapterTree(l, p, under.sumOf { it.count }, level(p), under.minOfOrNull { it.firstPage } ?: Int.MAX_VALUE)
                }.sortedWith { a, b ->
                    if (a.firstPage != b.firstPage) a.firstPage.compareTo(b.firstPage) else naturalCompare(a.label, b.label)
                }
            }
            return level(emptyList())
        }

        /**
         * 名字的比较：数字段按数值比、其余不分大小写（近似 Mac `localizedStandardCompare`——只在两节最早那道题
         * 同一页时才用得上，近似够用）。
         */
        fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ie = i
                    while (ie < a.length && a[ie].isDigit()) ie++
                    var je = j
                    while (je < b.length && b[je].isDigit()) je++
                    val na = a.substring(i, ie).trimStart('0')
                    val nb = b.substring(j, je).trimStart('0')
                    if (na.length != nb.length) return na.length.compareTo(nb.length)
                    val c = na.compareTo(nb)
                    if (c != 0) return c
                    i = ie
                    j = je
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}

/** 「习题」一段里的一行（同 Mac `ExerciseGroupNode`）。 */
data class ExGroupNode(
    val scope: ExScope,
    val title: String,
    val count: Int,
    val children: List<ExGroupNode> = emptyList(),
    /** 做过几道（行尾「已做 / 共」）；null = 不显示（错题本那一支，或调用方没给做题进度）。 */
    val done: Int? = null,
) {
    /** 整棵树填上「做过几道」（同 Mac `ExerciseGroupNode.withDone`）。 */
    fun withDone(d: ExDoneSummary): ExGroupNode = copy(done = d.done(scope), children = children.map { it.withDone(d) })

    companion object {
        /**
         * 建整段（规则只有 Mac `ExerciseGroupNode.build` 一份，这里逐条照抄）：全部 / 错题本 / 收藏三行恒在；
         * 错题本、收藏底下各是有错题 / 有收藏的书 → 章节树（数的是错题 / 收藏）；之后每本有题的书一行，底下章节树。
         * 没有题 → 空。
         */
        fun build(
            s: ExSummary,
            documents: List<Pair<String, String>>,
            title: (ExScope) -> String,
            noChapter: String,
        ): List<ExGroupNode> {
            if (s.total <= 0) return emptyList()
            val wrongChildren = documents.filter { s.count(ExScope.WrongDocument(it.first)) > 0 }.map { (id, t) ->
                ExGroupNode(ExScope.WrongDocument(id), t, s.count(ExScope.WrongDocument(id)),
                    chapters(s.mistakeChapters(id), noChapter) { ExScope.WrongChapter(id, it) })
            }
            val starredChildren = documents.filter { s.count(ExScope.StarredDocument(it.first)) > 0 }.map { (id, t) ->
                ExGroupNode(ExScope.StarredDocument(id), t, s.count(ExScope.StarredDocument(id)),
                    chapters(s.starredChapters(id), noChapter) { ExScope.StarredChapter(id, it) })
            }
            val rows = arrayListOf(
                ExGroupNode(ExScope.All, title(ExScope.All), s.count(ExScope.All)),
                ExGroupNode(ExScope.Wrong, title(ExScope.Wrong), s.count(ExScope.Wrong), wrongChildren),
                ExGroupNode(ExScope.Starred, title(ExScope.Starred), s.count(ExScope.Starred), starredChildren),
            )
            for ((id, t) in documents) {
                val n = s.byDocument[id] ?: 0
                if (n <= 0) continue
                rows += ExGroupNode(ExScope.Document(id), t, n,
                    chapters(s.chapters[id] ?: emptyMap(), noChapter) { ExScope.Chapter(id, it) })
            }
            return rows
        }

        /**
         * 一本书底下的章节树：一道题都没设章节 → 不展开；有设有没设 → 末尾一行「未分章节」。
         * `scope` = 章节路径在这一支里是哪个范围（书底下 / 错题本底下 / 收藏底下）。
         */
        fun chapters(
            stats: Map<List<String>, ExChapterStat>,
            noChapter: String,
            scope: (List<String>) -> ExScope,
        ): List<ExGroupNode> {
            if (stats.keys.none { it.isNotEmpty() }) return emptyList()
            fun make(t: ExChapterTree): ExGroupNode = ExGroupNode(scope(t.path), t.label, t.count, t.children.map(::make))
            val out = ExChapterTree.build(stats).map(::make).toMutableList()
            val none = stats[emptyList()]
            if (none != null && none.count > 0) out += ExGroupNode(scope(emptyList()), noChapter, none.count)
            return out
        }

        /** 摊平成一列（先父后子），`depth` = 缩进层级（0 起）。 */
        fun flatten(nodes: List<ExGroupNode>, depth: Int = 0): List<Pair<ExGroupNode, Int>> {
            val out = ArrayList<Pair<ExGroupNode, Int>>()
            for (n in nodes) {
                out += n to depth
                out += flatten(n.children, depth + 1)
            }
            return out
        }
    }
}
