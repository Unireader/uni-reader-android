package com.xvan.unireader.local.exercise

import com.xvan.unireader.shared.exercise.ExJudge
import com.xvan.unireader.shared.exercise.ExShown
import com.xvan.unireader.shared.exercise.MathText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 习题纯逻辑（模式1，`../EXERCISE-PLAN.md §10.2`）：与 Mac `spike/exercise-test.swift` 的同名几段逐条对应——
 * 判题、做过的题显示哪一次、范围键与 includes、侧栏的数、章节树与分组、目录查章节；外加本端的一组题进度
 * （[LocalExerciseDeck]，照 Mac `ExerciseDeck`）与公式写法改写（[MathText.normalize]）。
 */
class ExerciseTest {

    private fun payload(answer: List<String> = listOf("B"), src: String? = "book") = ExPayload(
        stem = "题干", options = listOf(ExOption("A", "a"), ExOption("B", "b"), ExOption("C", "c")),
        answer = answer, answerSource = if (answer.isEmpty()) null else src, explanation = null, region = null, figure = false,
    )

    private fun ex(
        id: String, doc: String? = "D1", page: Int? = 0, chapter: List<String> = emptyList(),
        wrong: Boolean = false, starred: Boolean = false, answer: List<String> = listOf("B"), kind: Int = 0,
    ) = Exercise(
        id, doc, page, 0, kind, "", "", payload(answer), starred, wrong, "kimi",
        "2026-10-10T09:00:00.000Z", "2026-10-10T09:00:00.000Z", chapter,
    )

    // ---------- payload / 判题 ----------

    @Test
    fun payloadParse() {
        val p = ExPayload.parse(
            """{"stem":"设 ${'$'}f(x)${'$'}","options":[{"key":"A","text":"x"},{"key":"B","text":""}],"answer":["A"],
              "answer_source":"agent","explanation":"e","region":[0.1,0.2,0.3,0.4],"figure":true}""".toByteArray(),
        )!!
        assertEquals("设 \$f(x)\$", p.stem)
        assertEquals(listOf(ExOption("A", "x"), ExOption("B", "")), p.options)
        assertEquals(listOf("A"), p.answer)
        assertEquals("agent", p.answerSource)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), p.region)
        assertTrue(p.figure)
        // 缺的键按默认（同 Mac decodeIfPresent），坏 JSON → null
        val q = ExPayload.parse("""{"stem":"s"}""".toByteArray())!!
        assertTrue(q.options.isEmpty() && q.answer.isEmpty() && !q.figure && q.region == null)
        assertNull(ExPayload.parse("{bad".toByteArray()))
    }

    @Test
    fun judgeAndAnswerString() {
        val e = ex("e", answer = listOf("A", "C"), kind = 1)
        assertEquals(true, e.judge(setOf("C", "A")))
        assertEquals(false, e.judge(setOf("A")))             // 少选算错
        assertEquals(false, e.judge(setOf("A", "B", "C")))   // 多选算错
        assertNull(ex("n", answer = emptyList()).judge(setOf("A")))   // 没答案：没法判
        assertEquals("AC", Exercise.answerString(setOf("C", "A")))
        assertTrue(ex("g", answer = listOf("B")).copy(payload = payload(listOf("B"), "agent")).answerFromAgent)
    }

    @Test
    fun parseAnswer() {
        assertEquals(listOf("A", "C"), Exercise.parseAnswer("a, c"))
        assertEquals(listOf("B", "D"), Exercise.parseAnswer("B、D"))
        assertEquals(listOf("A"), Exercise.parseAnswer("AA"))
        assertNull(Exercise.parseAnswer("AZ"))
        assertEquals(emptyList<String>(), Exercise.parseAnswer(""))
    }

    // ---------- 做过的题显示哪一次（同 Mac「做过的题显示以前的作答」） ----------

    @Test
    fun earlierAnswer() {
        val e = ex("e", answer = listOf("B"))
        assertNull(e.earlierAnswer(null))
        // App 里练的最近一次优先
        val s = ExAttemptStats(attempts = 2, lastAnswer = "A", lastAt = "2026-10-11T08:00:00.000Z", markedAnswer = "B")
        val p = e.earlierAnswer(s)!!
        assertEquals(setOf("A"), p.first)
        assertEquals(false, p.second)
        assertEquals(ExEarlier.Practiced("2026-10-11T08:00:00.000Z"), p.third)
        // 没练过才拿书上的作答
        val b = e.earlierAnswer(ExAttemptStats(attempts = 1, markedAnswer = "B"))!!
        assertEquals(setOf("B"), b.first)
        assertEquals(true, b.second)
        assertEquals(ExEarlier.Book, b.third)
        // 对错按现在的答案现判；没答案 → 没法判
        assertNull(ex("n", answer = emptyList()).earlierAnswer(s)!!.second)
        // 认不出的作答串（坏数据）当没做过
        assertNull(e.earlierAnswer(ExAttemptStats(attempts = 1, lastAnswer = "XYZ")))
    }

    // ---------- 范围键（与 Mac `ExerciseScope.key` 逐字相同） ----------

    @Test
    fun scopeKeys() {
        val scopes = listOf(
            ExScope.All, ExScope.Wrong, ExScope.Starred, ExScope.Document("D-1"),
            ExScope.Chapter("D-1", listOf("第二章 导数", "2.3: 冒号")), ExScope.Chapter("D-1", emptyList()),
            ExScope.WrongDocument("D-1"), ExScope.WrongChapter("D-1", listOf("第一章")), ExScope.WrongChapter("D-1", emptyList()),
        )
        for (s in scopes) assertEquals(s.key, s, ExScope.parse(s.key))
        assertEquals("chap:D-1:[\"第二章 导数\",\"2.3 求导法则\"]", ExScope.Chapter("D-1", listOf("第二章 导数", "2.3 求导法则")).key)
        assertEquals("wrong:doc:D-1", ExScope.WrongDocument("D-1").key)
        assertEquals("chap:D-1:", ExScope.Chapter("D-1", emptyList()).key)
        assertNull(ExScope.parse("wrong:all"))
        assertNull(ExScope.parse("wrong:wrong"))
        assertNull(ExScope.parse("doc:"))
        assertNull(ExScope.parse("chap:nocolon"))
        assertTrue(ExScope.Wrong.isMistakes && ExScope.WrongChapter("D", emptyList()).isMistakes)
        assertFalse(ExScope.Chapter("D", emptyList()).isMistakes || ExScope.All.isMistakes)
        assertEquals("D", ExScope.WrongChapter("D", listOf("x")).documentId)
        assertNull(ExScope.Starred.documentId)
    }

    @Test
    fun chapterTextRoundTrip() {
        val p = listOf("第二章 \"引号\"", "a\\b", "斜/杠")
        assertEquals("[\"第二章 \\\"引号\\\"\",\"a\\\\b\",\"斜/杠\"]", ExChapter.text(p))
        assertEquals(p, ExChapter.parse(ExChapter.text(p)))
        assertEquals("", ExChapter.text(emptyList()))
        assertEquals(emptyList<String>(), ExChapter.parse(""))
        assertEquals(emptyList<String>(), ExChapter.parse("坏的"))
    }

    @Test
    fun scopeIncludes() {
        val a = ex("a", chapter = listOf("第一章", "1.1"), wrong = true)
        val b = ex("b", chapter = listOf("第一章", "1.2"))
        val c = ex("c", chapter = emptyList(), starred = true)
        val other = ex("o", doc = "D2", wrong = true)
        assertTrue(ExScope.Chapter("D1", listOf("第一章")).includes(a) && ExScope.Chapter("D1", listOf("第一章")).includes(b))
        assertFalse(ExScope.Chapter("D1", listOf("第一章")).includes(c))
        assertTrue(ExScope.Chapter("D1", emptyList()).includes(c))   // 空路径 = 没设章节的
        assertFalse(ExScope.Chapter("D1", emptyList()).includes(a))
        assertTrue(ExScope.WrongChapter("D1", listOf("第一章")).includes(a))
        assertFalse(ExScope.WrongChapter("D1", listOf("第一章")).includes(b))
        assertTrue(ExScope.WrongDocument("D1").includes(a) && !ExScope.WrongDocument("D1").includes(other))
        assertTrue(ExScope.Starred.includes(c) && !ExScope.Starred.includes(a))
        assertTrue(ExFilter.UNANSWERED.includes(a, 0) && !ExFilter.UNANSWERED.includes(a, 1))
        assertTrue(ExFilter.WRONG.includes(a, 3) && !ExFilter.WRONG.includes(b, 3))
    }

    // ---------- 侧栏的数 + 章节树 + 分组（同 Mac「错题本按书 / 按章展开」「分组」两段） ----------

    private val stats = mapOf(
        listOf("第一章 函数", "1.1 映射") to ExChapterStat(1, 2, 1),
        listOf("第一章 函数", "1.2 极限") to ExChapterStat(1, 5, 0),
        listOf("第三章") to ExChapterStat(1, 30, 1),
        listOf("第二章") to ExChapterStat(1, 12, 0),
    )
    private val sum = ExSummary(total = 4, wrong = 2, starred = 0, byDocument = mapOf("D3" to 4), chapters = mapOf("D3" to stats))

    @Test
    fun summaryCounts() {
        assertEquals(2, sum.count(ExScope.WrongDocument("D3")))
        assertEquals(1, sum.count(ExScope.WrongChapter("D3", listOf("第一章 函数"))))
        assertEquals(2, sum.count(ExScope.Chapter("D3", listOf("第一章 函数"))))
        assertEquals(4, sum.count(ExScope.Document("D3")))
        val mc = sum.mistakeChapters("D3")
        assertEquals(setOf(listOf("第一章 函数", "1.1 映射"), listOf("第三章")), mc.keys)
        assertTrue(mc.values.all { it.count == 1 })
    }

    @Test
    fun chapterTreeOrder() {
        val t = ExChapterTree.build(stats)
        // 同级按「下面最早那道题的页码」排：第一章(2) → 第二章(12) → 第三章(30)
        assertEquals(listOf("第一章 函数", "第二章", "第三章"), t.map { it.label })
        assertEquals(2, t[0].count)
        assertEquals(listOf("1.1 映射", "1.2 极限"), t[0].children.map { it.label })
        assertEquals(listOf("第一章 函数", "1.2 极限"), t[0].children[1].path)
        // 同页按名字（数字按数值）
        assertTrue(ExChapterTree.naturalCompare("第2节", "第10节") < 0)
        assertTrue(ExChapterTree.naturalCompare("b", "A") > 0)
    }

    @Test
    fun groupsBuildAndFlatten() {
        val docs = listOf("nodoc" to "没题的书", "D3" to "书三")
        val g = ExGroupNode.build(sum, docs, { it.key }, "未分章节")
        assertEquals(listOf(ExScope.All, ExScope.Wrong, ExScope.Starred, ExScope.Document("D3")), g.map { it.scope })
        assertEquals(listOf(4, 2, 0, 4), g.map { it.count })
        assertEquals(listOf(ExScope.WrongDocument("D3")), g[1].children.map { it.scope })
        assertEquals(listOf("第一章 函数", "第三章"), g[1].children[0].children.map { it.title })
        assertEquals(
            listOf(ExScope.WrongChapter("D3", listOf("第一章 函数", "1.1 映射"))),
            g[1].children[0].children[0].children.map { it.scope },
        )
        val flat = ExGroupNode.flatten(g)
        assertEquals(0, flat[0].second)
        assertEquals(ExScope.Wrong, flat[1].first.scope)
        assertEquals(ExScope.WrongDocument("D3") to 1, flat[2].first.scope to flat[2].second)
        assertEquals(2, flat[3].second)
        assertTrue(ExGroupNode.build(ExSummary(), docs, { it.key }, "x").isEmpty())
        // 有设有没设 → 末尾一行「未分章节」
        val withNone = sum.copy(chapters = mapOf("D3" to stats + (emptyList<String>() to ExChapterStat(2, 0, 0))))
        assertEquals("未分章节", ExGroupNode.build(withNone, docs, { it.key }, "未分章节")[3].children.last().title)
        // 一道都没设 → 书不展开
        val noneOnly = sum.copy(chapters = mapOf("D3" to mapOf(emptyList<String>() to ExChapterStat(4, 0, 0))))
        assertTrue(ExGroupNode.build(noneOnly, docs, { it.key }, "未分章节")[3].children.isEmpty())
    }

    // ---------- 目录查章节（同 Mac `ExerciseChapter.path`） ----------

    @Test
    fun chapterPathFromToc() {
        val toc = listOf(
            ExChapterNode("第一章", 0, listOf(ExChapterNode("1.1", 2), ExChapterNode("1.2", 6))),
            ExChapterNode("第二章", null, listOf(ExChapterNode("2.1", 10))),   // 没落点：按第一个子孙算
        )
        assertEquals(listOf("第一章"), ExChapter.path(1, toc))                   // 章开头、第一节之前
        assertEquals(listOf("第一章", "1.2"), ExChapter.path(8, toc))
        assertEquals(listOf("第二章", "2.1"), ExChapter.path(11, toc))
        assertEquals(emptyList<String>(), ExChapter.path(0, listOf(ExChapterNode("后面", 5))))
        assertEquals(
            listOf(listOf("第一章"), listOf("第一章", "1.1"), listOf("第一章", "1.2"), listOf("第二章"), listOf("第二章", "2.1")),
            ExChapter.flatten(toc),
        )
        assertTrue(ExChapter.contains(listOf("a"), listOf("a", "b")) && !ExChapter.contains(listOf("a", "b"), listOf("a")))
    }

    // ---------- 一组题的进度（同 Mac `ExerciseDeck`） ----------

    @Test
    fun deckSnapshotAndReload() {
        val d = LocalExerciseDeck.shared("/ws-test-${System.nanoTime()}", ExScope.Wrong)
        val a = ex("a", wrong = true)
        val b = ex("b", wrong = true)
        d.applyFresh(LocalExerciseDeck.Fresh(listOf(a, b), emptyMap(), emptyMap()))
        assertEquals(listOf("a", "b"), d.items.map { it.id })
        d.index = 1
        // a 移出了错题本（还在库里）、c 新进来：a 这一轮留着、b 还是当前题、c 接在末尾
        val c = ex("c", wrong = true)
        d.applyReload(LocalExerciseDeck.Fresh(listOf(b, c), emptyMap(), mapOf("a" to a.copy(wrong = false))))
        assertEquals(listOf("a", "b", "c"), d.items.map { it.id })
        assertEquals(1, d.index)
        assertFalse(d.items[0].wrong)
        // b 真删了：去掉，当前题落到原位置上的那一道
        d.applyReload(LocalExerciseDeck.Fresh(listOf(c), emptyMap(), mapOf("a" to a, "b" to null)))
        assertEquals(listOf("a", "c"), d.items.map { it.id })
    }

    @Test
    fun deckAnswersAndView() {
        val d = LocalExerciseDeck.shared("/ws-test-${System.nanoTime()}", ExScope.Document("D1"))
        val a = ex("a", answer = listOf("B"))
        val b = ex("b", answer = listOf("B"))
        val st = mapOf(
            "b" to ExAttemptStats(attempts = 2, correct = 1, lastAnswer = "B", lastAt = "2026-10-11T08:00:00.000Z",
                markedAnswer = "C", markedCorrect = false),
        )
        d.applyFresh(LocalExerciseDeck.Fresh(listOf(a, b), st, emptyMap()))
        var v = d.view("书")
        assertEquals(ExShown.NONE, v.cards[0].shown)
        assertEquals(ExShown.PRACTICED, v.cards[1].shown)   // 做过的题直接显示上次结果
        assertEquals("B", v.cards[1].chosen)
        assertEquals(ExJudge.RIGHT, v.cards[1].correct)
        assertEquals(1791705600000L, v.cards[1].lastAt)   // 2026-10-11T08:00:00Z
        assertEquals("C", v.cards[1].marked)
        assertEquals(ExJudge.WRONG, v.cards[1].markedCorrect)
        assertEquals(1, v.cards[0].page)                    // 线上 / 界面一律 1 起
        // 交卷：这一轮的作答、统计立刻跟上
        val ans = d.submit("a", setOf("A"))!!
        assertEquals(false, ans.correct)
        v = d.view("书")
        assertEquals(ExShown.ROUND, v.cards[0].shown)
        assertEquals(ExJudge.WRONG, v.cards[0].correct)
        assertEquals(1, v.done)
        assertEquals(0, v.right)
        assertEquals(1, v.cards[0].tries)
        assertTrue(v.hasShown)
        // 重做：这一轮当没答过，以前的作答也先不显示
        d.redo(listOf("a", "b"))
        v = d.view("书")
        assertTrue(v.cards.all { it.shown == ExShown.NONE })
        assertFalse(v.hasShown)
        assertEquals(0, v.done)
        assertNull(d.submit("zzz", setOf("A")))             // 不在这一组里的题不收
    }

    // ---------- 公式写法（Mac 的 `$…$` → Markwon 的 `$$…$$`） ----------

    @Test
    fun mathNormalize() {
        assertEquals("设 \$\$f(x)\$\$，则 \$\$f'(1)=\$\$", MathText.normalize("设 \$f(x)\$，则 \$f'(1)=\$"))
        assertEquals("价格 \\\$5 和 \$\$x\$\$", MathText.normalize("价格 \\\$5 和 \$x\$"))     // 转义的 $ 不算
        assertEquals("已有 \$\$a+b\$\$ 不动", MathText.normalize("已有 \$\$a+b\$\$ 不动"))
        assertEquals("\$\$\n\\int_0^1 x\\,dx\n\$\$", MathText.normalize("  \$\$ \\int_0^1 x\\,dx \$\$"))   // 一行的块公式拆三行
        assertEquals("落单的 \$ 原样", MathText.normalize("落单的 \$ 原样"))
    }
}
