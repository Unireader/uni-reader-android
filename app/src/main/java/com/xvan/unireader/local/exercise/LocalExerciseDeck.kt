package com.xvan.unireader.local.exercise

import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.exercise.ExCard
import com.xvan.unireader.shared.exercise.ExDeckView
import com.xvan.unireader.shared.exercise.ExJudge
import com.xvan.unireader.shared.exercise.ExShown

/**
 * 模式1 的一组题 + 练到哪了（`../EXERCISE-PLAN.md §10.2`），逐条照 Mac `ExerciseDeck`：
 *
 * - 题目集合是**快照**：打开时按范围取一次，之后库里变了只**就地刷新**已在里面的题、把新进范围（且过得了筛选）的题接在末尾——
 *   移出错题本 / 取消收藏 / 刚做过（「没做过」筛选下）的题这一轮还留着，不会在眼前突然消失；真删了的才去掉。
 * - 这一轮的作答、点过「再做一次」的题只在内存里（App 开着期间），库里只记作答行。
 * - 按「工作区 + 范围」一份存在进程里（[shared]），切标签 / 切回来接着练。
 *
 * **主线程对象**：自己不碰库。读库的那一半是 [read]（在 `StoreQueue` 线程上调），读完回主线程交给 [applyFresh] / [applyReload]。
 */
class LocalExerciseDeck private constructor(val scope: ExScope) {

    /** 一道题显示的作答：选了什么、对不对（null = 没法判）、以前的作答从哪来（null = 这一轮答的）。 */
    data class Answer(val chosen: Set<String>, val correct: Boolean?, val earlier: ExEarlier?)

    /** [read] 在库队列线程上读出来的那一份。 */
    class Fresh(
        val items: List<Exercise>,
        val stats: Map<String, ExAttemptStats>,
        /** 快照里有、这次范围里没有的题，库里现在的样子（null = 真删了）。 */
        val outside: Map<String, Exercise?>,
    )

    var items: List<Exercise> = emptyList()
        private set
    var stats: Map<String, ExAttemptStats> = emptyMap()
        private set
    var index = 0
    var filter = ExFilter.ALL
        private set
    /** 读过一次库了（之前显示「正在打开」）。 */
    var loaded = false
        private set
    private val answers = HashMap<String, Answer>()
    private val redoing = HashSet<String>()

    companion object {
        private val decks = HashMap<String, LocalExerciseDeck>()

        fun shared(workspacePath: String, scope: ExScope): LocalExerciseDeck =
            decks.getOrPut(workspacePath + "|" + scope.key) { LocalExerciseDeck(scope) }

        /** 关工作区时把那个工作区的进度都扔掉（下次打开从库里重来，同 Mac 关窗口）。 */
        fun forget(workspacePath: String) {
            decks.keys.removeAll { it.startsWith("$workspacePath|") }
        }

        /**
         * 一个范围里的题（库队列线程上调），按「文档 → 页 → 页内先后」排；文档之间按书库的顺序（同 Mac `exercises(in:)`）。
         * [keep] = 快照里已有的题 id：范围里没有的那几道再单独查一次（判它是「只是出了范围」还是「真删了」）。
         */
        fun read(s: LibraryStore, scope: ExScope, keep: Collection<String> = emptyList()): Fresh {
            val docId = scope.documentId
            val items = if (docId != null) {
                s.exercises(docId).filter(scope::includes)
            } else {
                val order = s.allDocuments().withIndex().associate { it.value.id to it.index }
                s.exercises().filter(scope::includes).withIndex().sortedWith { a, b ->
                    val oa = a.value.documentId?.let { order[it] } ?: Int.MAX_VALUE
                    val ob = b.value.documentId?.let { order[it] } ?: Int.MAX_VALUE
                    if (oa != ob) oa.compareTo(ob) else a.index.compareTo(b.index)   // 同一篇里保持库里排好的「页 → 先后」
                }.map { it.value }
            }
            val have = items.map { it.id }.toHashSet()
            val outside = keep.filter { it !in have }.associateWith { s.exercise(it) }
            return Fresh(items, s.exerciseAttemptStats(), outside)
        }
    }

    val current: Exercise? get() = items.getOrNull(index)

    private fun passes(e: Exercise) = filter.includes(e, stats[e.id]?.attempts ?: 0)

    /** 第一次打开 / 换筛选：整份按范围 + 筛选重取，尽量停在原来那道题上。 */
    fun applyFresh(f: Fresh) {
        val curId = current?.id
        stats = f.stats
        items = f.items.filter(::passes)
        index = curId?.let { id -> items.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: index.coerceIn(0, maxOf(0, items.size - 1))
        loaded = true
    }

    /** 库里变了：已在快照里的就地换成新内容（真删了的去掉），新进范围、过得了筛选的接在末尾（同 Mac `reload`）。 */
    fun applyReload(f: Fresh) {
        if (!loaded) { applyFresh(f); return }
        stats = f.stats
        val byId = f.items.associateBy { it.id }
        val curId = current?.id
        val next = ArrayList<Exercise>()
        for (e in items) {
            val fresh = byId[e.id]
            if (fresh != null) { next += fresh; continue }
            val o = f.outside[e.id] ?: continue   // 真删了
            if (scope.documentId == null || o.documentId == scope.documentId) next += o   // 只是出了范围：这一轮留着
        }
        val have = next.map { it.id }.toHashSet()
        next += f.items.filter { it.id !in have && passes(it) }
        items = next
        if (curId != null) items.indexOfFirst { it.id == curId }.takeIf { it >= 0 }?.let { index = it }
        index = index.coerceIn(0, maxOf(0, items.size - 1))
    }

    fun setFilter(f: ExFilter): Boolean {
        if (f == filter) return false
        filter = f
        return true
    }

    /** 这道题要显示的作答（null = 还没答，等着作答）：这一轮答的，否则以前那次（[Exercise.earlierAnswer]）。 */
    fun answer(e: Exercise): Answer? {
        answers[e.id]?.let { return it }
        if (e.id in redoing) return null
        val p = e.earlierAnswer(stats[e.id]) ?: return null
        return Answer(p.first, p.second, p.third)
    }

    /**
     * 交卷：判题、记进这一轮（库里那一行由调用方写，[Exercise.answerString] 是要写的那串）。
     * 本地统计先跟上（同 Mac：作答次数 / 最近一次立刻变，不等库回来）。不在这一组里的题不收 → null。
     */
    fun submit(id: String, chosen: Set<String>): Answer? {
        val e = items.firstOrNull { it.id == id } ?: return null
        if (chosen.isEmpty()) return null
        val a = Answer(chosen, e.judge(chosen), null)
        answers[id] = a
        redoing.remove(id)
        val s = stats[id] ?: ExAttemptStats()
        stats = stats + (id to s.copy(
            attempts = s.attempts + 1,
            correct = s.correct + if (a.correct == true) 1 else 0,
            wrong = s.wrong + if (a.correct == false) 1 else 0,
            lastCorrect = a.correct,
            lastAnswer = Exercise.answerString(chosen),
            lastAt = Iso.now(),
        ))
        return a
    }

    /** 批量重做 / 再做一次：这几道这一轮当没答过（以前的作答也先不显示），库里的记录不动。 */
    fun redo(ids: Collection<String>) {
        for (id in ids) {
            answers.remove(id)
            redoing += id
        }
    }

    /** 给界面的那一份（字段同线上 `exDeck`，`../PROTOCOL.md §4.9`）。 */
    fun view(title: String): ExDeckView {
        val cards = items.map(::card)
        return ExDeckView(
            scope = scope.key, title = title, filter = filter.code, index = index,
            done = answers.size, right = answers.values.count { it.correct == true }, cards = cards,
        )
    }

    private fun card(e: Exercise): ExCard {
        val st = stats[e.id]
        val a = answer(e)
        val shown = when {
            a == null -> ExShown.NONE
            a.earlier == null -> ExShown.ROUND
            a.earlier is ExEarlier.Practiced -> ExShown.PRACTICED
            else -> ExShown.BOOK
        }
        val lastAt = (a?.earlier as? ExEarlier.Practiced)?.at?.let { Iso.instant(it)?.toEpochMilli() } ?: 0L
        return ExCard(
            id = e.id, number = e.number, page = e.page?.plus(1) ?: 0, multiple = e.multiple,
            starred = e.starred, wrong = e.wrong, figure = e.payload.figure,
            hasOriginal = e.documentId != null && e.page != null, agentAnswer = e.answerFromAgent,
            chapter = ExChapter.display(e.chapter), section = e.section, stem = e.payload.stem,
            options = e.payload.options.map { it.key to it.text },
            answer = e.payload.answer.joinToString(""), explanation = e.payload.explanation ?: "",
            shown = shown, chosen = a?.let { Exercise.answerString(it.chosen) } ?: "", correct = ExJudge.of(a?.correct),
            lastAt = lastAt, tries = st?.attempts ?: 0, rights = st?.correct ?: 0,
            marked = st?.markedAnswer ?: "",
            markedCorrect = if (st?.markedAnswer == null) ExJudge.NONE else ExJudge.of(st.markedCorrect),
        )
    }
}
