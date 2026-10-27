package com.xvan.unireader.shared.exercise

import android.graphics.Bitmap

/*
 * 习题界面（两模式共用，`../EXERCISE-PLAN.md §10.1`）只认这一份中立数据：
 * 模式1 由本机按 Mac 同一套规则算出来（`local/exercise/LocalExerciseDeck`），模式2 是 Mac 算好经 `exDeck` 发来的。
 * 字段与线上 `exDeck` 的一道题一一对应（`../PROTOCOL.md §4.9`）。**本文件一行都不许认识 WireCodec。**
 */

/** 一道题现在该显示哪次作答（`exDeck` 的 `shown`）。 */
object ExShown {
    const val NONE = 0       // 等着作答
    const val ROUND = 1      // 这一轮刚答的
    const val PRACTICED = 2  // 以前在 App 里练的最近一次
    const val BOOK = 3       // 书上的作答
}

/** 对错（`exDeck` 的 `correct` / `markedCorrect`）。 */
object ExJudge {
    const val NONE = 0       // 没法判（题目还没有答案）
    const val RIGHT = 1
    const val WRONG = 2

    fun of(b: Boolean?): Int = when (b) { null -> NONE; true -> RIGHT; false -> WRONG }
}

/** 一道题：内容 + 该显示的作答 + 统计。 */
data class ExCard(
    val id: String,
    val number: String,
    /** 出处页，**1 起**；0 = 不知道。 */
    val page: Int,
    val multiple: Boolean,
    val starred: Boolean,
    val wrong: Boolean,
    /** 带图：默认摆出原题。 */
    val figure: Boolean,
    /** 有出处页：能看原题 / 到 PDF 原页。 */
    val hasOriginal: Boolean,
    /** 答案是 Agent 推算的（判完要提示「未经核对」）。 */
    val agentAnswer: Boolean,
    /** 显示用的章节（「第二章 › 2.3」），空 = 没设。 */
    val chapter: String,
    val section: String,
    val stem: String,
    val options: List<Pair<String, String>>,
    /** 正确答案的键拼起来，空 = 还没有答案。 */
    val answer: String,
    val explanation: String,
    val shown: Int,
    val chosen: String,
    val correct: Int,
    /** `shown = PRACTICED` 时那次的时间（Unix 毫秒），其余 0。 */
    val lastAt: Long,
    val tries: Int,
    val rights: Int,
    /** 书上的作答（空 = 没有）。 */
    val marked: String,
    val markedCorrect: Int,
    /** 题型（`exercise.kind`）：0 单选 · 1 多选 · 3 填空 · 4 主观题（`../EXERCISE-PLAN.md §11`）。 */
    val kind: Int = if (multiple) 1 else 0,
) {
    val judged: Boolean get() = shown != ExShown.NONE
    /** 填空 / 主观题：平板本轮不能作答，只显示题干，提示到 Mac 上作答（`../EXERCISE-PLAN.md §11.6`）。 */
    val macOnly: Boolean get() = kind != 0 && kind != 1
    /** 列表里一行的题干：去掉换行、并掉连续空白。 */
    val plainStem: String get() = stem.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}

/** 一组题（一个习题标签此刻的样子）。 */
data class ExDeckView(
    /** 范围键（与 Mac `ExerciseScope.key` 同）；空 = 没在练。 */
    val scope: String,
    val title: String,
    /** 0 全部 · 1 只看没做过（`2` 错题 / `3` 收藏已去掉，老 Mac 发来按「全部」显示）。 */
    val filter: Int,
    val index: Int,
    /** 这一轮做了几道、对几道。 */
    val done: Int,
    val right: Int,
    val cards: List<ExCard>,
    /** 这一组没有「只看没做过」（错题本那一支：错题都做过）——顶栏不显示那个开关。 */
    val noFilter: Boolean = false,
) {
    val current: ExCard? get() = cards.getOrNull(index)
    /** 有没有正显示着作答的题（「全部重做」能不能点）。 */
    val hasShown: Boolean get() = cards.any { it.judged }

    companion object {
        val EMPTY = ExDeckView("", "", 0, 0, 0, 0, emptyList())
    }
}

/** 抽屉「习题」一段里的一行（摊平后的，`depth` = 缩进层级）。 */
data class ExGroup(val key: String, val title: String, val count: Int, val depth: Int)

/**
 * 习题界面把操作交给谁（模式1 本机写库 / 模式2 发给 Mac）。界面只调这些，不自己改 [ExDeckView]——
 * 改完由宿主把新的一份交回来（[ExerciseScreen.show]）。
 */
interface ExHost {
    /** 交卷：`keys` 选的键拼起来（"AC"），`elapsedMs` 这道题看了多久。 */
    fun submit(id: String, keys: String, elapsedMs: Long)
    fun setStarred(id: String, on: Boolean)
    fun removeFromMistakes(id: String)
    /** 这几道当作没答过（这一轮），从第 `from` 题练起。 */
    fun redo(ids: List<String>, from: Int)
    fun setFilter(filter: Int)
    /** 翻到第几题。 */
    fun go(index: Int)
    /** 到 PDF 原页。 */
    fun openSource(id: String)
    /** 原题那一块的图（宽约 `widthPx` 像素）；回调在主线程，拿不到给 null。 */
    fun loadOriginal(id: String, widthPx: Int, done: (Bitmap?) -> Unit)
    /** 能不能「设置章节…」（只有模式1）。 */
    val canSetChapter: Boolean get() = false
    /** 设置章节（长按列表多选之后）。只有 [canSetChapter] 为真时会调。 */
    fun setChapter(ids: List<String>) {}
}
