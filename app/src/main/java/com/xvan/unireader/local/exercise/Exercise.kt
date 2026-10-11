package com.xvan.unireader.local.exercise

import com.xvan.unireader.shared.MiniJson

/*
 * 习题的纯逻辑（模式1，`../EXERCISE-PLAN.md §10.2`）：逐条对应 Mac `Sources/App/Exercise.swift` 里**练习要用的那部分**
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
    /** 正确选项的键（空 = 还没有答案）。 */
    val answer: List<String>,
    /** `book` / `agent`；没有答案时为 null。 */
    val answerSource: String?,
    val explanation: String?,
    /** 题目在页上的框 `[x, y, w, h]`（归一化 0~1、左上原点）。 */
    val region: List<Double>?,
    /** 带图 / 表：练习时默认显示原题截图。 */
    val figure: Boolean,
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
            )
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
) {
    val multiple: Boolean get() = kind == KIND_MULTIPLE
    val hasAnswer: Boolean get() = payload.answer.isNotEmpty()
    val answerFromAgent: Boolean get() = hasAnswer && payload.answerSource == "agent"

    /** 判题：选中的键与答案**完全相同**才算对。没有答案 → null（没法判）。 */
    fun judge(chosen: Set<String>): Boolean? = if (!hasAnswer) null else chosen == payload.answer.toSet()

    /**
     * 以前做过的那次（用户 2026-10-11 定：做过的题直接显示上次结果）：App 里练的最近一次优先，没练过再拿书上的作答。
     * 对错按**现在的**答案现判。没做过 → null。
     */
    fun earlierAnswer(s: ExAttemptStats?): Triple<Set<String>, Boolean?, ExEarlier>? {
        fun keys(raw: String?): Set<String>? {
            val k = raw?.let(::parseAnswer) ?: return null
            return k.takeIf { it.isNotEmpty() }?.toSet()
        }
        keys(s?.lastAnswer)?.let { return Triple(it, judge(it), ExEarlier.Practiced(s?.lastAt)) }
        keys(s?.markedAnswer)?.let { return Triple(it, judge(it), ExEarlier.Book) }
        return null
    }

    /** 列表里一行显示的题干：去掉换行、并掉连续空白。 */
    val plainStem: String get() = payload.stem.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    companion object {
        const val KIND_SINGLE = 0
        const val KIND_MULTIPLE = 1
        /** 书上的作答那条的 `exercise_attempt.source`。 */
        const val MARK_SOURCE = "mark"

        private val KEY_LETTERS = "ABCDEFGH".map { it.toString() }

        /** 作答记录里存的那串：选中的键按字母序拼起来。 */
        fun answerString(keys: Set<String>): String = keys.sorted().joinToString("")

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
        }

    /** 这个范围属于哪本书（全部 / 错题本 / 收藏不属于某一本）。 */
    val documentId: String?
        get() = when (this) {
            is Document -> id
            is Chapter -> id
            is WrongDocument -> id
            is WrongChapter -> id
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
    }

    companion object {
        private const val WRONG_PREFIX = "wrong:"

        fun parse(key: String): ExScope? = when (key) {
            "all" -> All
            "wrong" -> Wrong
            "starred" -> Starred
            else -> when {
                key.startsWith(WRONG_PREFIX) -> when (val inner = parse(key.removePrefix(WRONG_PREFIX))) {
                    is Document -> WrongDocument(inner.id)
                    is Chapter -> WrongChapter(inner.id, inner.path)
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

/** 习题标签顶上的筛选（同 Mac `ExerciseFilter`，值即线上 `exDeck.filter`）。 */
enum class ExFilter(val code: Int) {
    ALL(0), UNANSWERED(1), WRONG(2), STARRED(3);

    /** `attempts` = 这道题一共做过几次（含书上的作答）。 */
    fun includes(e: Exercise, attempts: Int): Boolean = when (this) {
        ALL -> true
        UNANSWERED -> attempts == 0
        WRONG -> e.wrong
        STARRED -> e.starred
    }

    companion object {
        fun of(code: Int): ExFilter? = entries.firstOrNull { it.code == code }
    }
}

// ---------- 侧栏的数 + 章节树 + 分组（同 Mac `ExerciseSummary` / `ExerciseChapterTree` / `ExerciseGroupNode`）----------

/** 挂在某个章节路径上的题：几道、最早在第几页（0 起；全没页码 = `Int.MAX_VALUE`）、其中几道在错题本里。 */
data class ExChapterStat(val count: Int, val firstPage: Int, val wrong: Int = 0)

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
    }

    /** 错题本里一本书的章节分布：只留有错题的章节，`count` 换成错题数（`firstPage` 仍按这一节全部题，排序同书底下那棵）。 */
    fun mistakeChapters(documentId: String): Map<List<String>, ExChapterStat> =
        (chapters[documentId] ?: emptyMap()).filterValues { it.wrong > 0 }
            .mapValues { ExChapterStat(it.value.wrong, it.value.firstPage, it.value.wrong) }
}

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
data class ExGroupNode(val scope: ExScope, val title: String, val count: Int, val children: List<ExGroupNode> = emptyList()) {
    companion object {
        /**
         * 建整段（规则只有 Mac `ExerciseGroupNode.build` 一份，这里逐条照抄）：全部 / 错题本 / 收藏三行恒在；
         * 错题本底下是有错题的书 → 章节树（数的是错题）；之后每本有题的书一行，底下章节树。没有题 → 空。
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
                    chapters(id, s.mistakeChapters(id), mistakes = true, noChapter = noChapter))
            }
            val rows = arrayListOf(
                ExGroupNode(ExScope.All, title(ExScope.All), s.count(ExScope.All)),
                ExGroupNode(ExScope.Wrong, title(ExScope.Wrong), s.count(ExScope.Wrong), wrongChildren),
                ExGroupNode(ExScope.Starred, title(ExScope.Starred), s.count(ExScope.Starred)),
            )
            for ((id, t) in documents) {
                val n = s.byDocument[id] ?: 0
                if (n <= 0) continue
                rows += ExGroupNode(ExScope.Document(id), t, n, chapters(id, s.chapters[id] ?: emptyMap(), false, noChapter))
            }
            return rows
        }

        /** 一本书底下的章节树：一道题都没设章节 → 不展开；有设有没设 → 末尾一行「未分章节」。 */
        fun chapters(
            docId: String,
            stats: Map<List<String>, ExChapterStat>,
            mistakes: Boolean,
            noChapter: String,
        ): List<ExGroupNode> {
            if (stats.keys.none { it.isNotEmpty() }) return emptyList()
            fun scope(p: List<String>): ExScope = if (mistakes) ExScope.WrongChapter(docId, p) else ExScope.Chapter(docId, p)
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
