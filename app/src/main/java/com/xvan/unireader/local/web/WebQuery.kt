package com.xvan.unireader.local.web

import com.xvan.unireader.local.exercise.Exercise
import com.xvan.unireader.local.store.LibraryStore
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 网页里 `unireader.query(name, args)` 的查询部分（`../docs/WEB-DOC-PLAN.md §5.1`）：只读，查习题库与做题状态。
 * 🔴 名字 / 参数 / 返回字段逐项照 Mac `WorkspaceManager+WebQuery.swift`，改一边要两边一起改。在库队列线程上调。
 */
object WebQuery {
    private const val MAX_LIMIT = 200
    private const val DEFAULT_LIMIT = 50

    fun run(s: LibraryStore, name: String, argsJson: String?): String {
        val args = if (argsJson.isNullOrEmpty()) JSONObject() else try {
            JSONTokener(argsJson).nextValue() as? JSONObject
        } catch (_: Exception) { null } ?: throw WebKV.Error("The query arguments must be a JSON object.")
        val out: Any = when (name) {
            "documents" -> documents(s)
            "exercises" -> exercises(s, args)
            "exercise" -> exercise(s, args)
            "exerciseStatus" -> status(s, args)
            "exerciseStats" -> stats(s, args)
            "attempts" -> attempts(s, args)
            else -> throw WebKV.Error("Unknown query: $name")
        }
        return out.toString()
    }

    private fun document(s: LibraryStore, ref: String): String {
        val r = ref.trim()
        val docs = s.allDocuments()
        docs.firstOrNull { it.id.equals(r, ignoreCase = true) }?.let { return it.id }
        val byPrefix = if (r.length >= 8) docs.filter { it.id.lowercase().startsWith(r.lowercase()) } else emptyList()
        if (byPrefix.size == 1) return byPrefix[0].id
        val byTitle = docs.filter { it.title == r }
        if (byTitle.size == 1) return byTitle[0].id
        throw WebKV.Error(if (byTitle.size > 1 || byPrefix.size > 1) "More than one document matches: $r" else "No such document: $r")
    }

    private fun int(a: JSONObject, k: String, def: Int, lo: Int, hi: Int): Int =
        if (a.has(k) && !a.isNull(k)) a.optInt(k, def).coerceIn(lo, hi) else def

    private fun strings(a: JSONObject, k: String): List<String>? =
        a.optJSONArray(k)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it, null) } }

    /** 工作区里全部题，顺序同 Mac：书按书库顺序、不关联 PDF 的在后。 */
    private fun allExercises(s: LibraryStore): List<Exercise> {
        val order = s.allDocuments().withIndex().associate { it.value.id to it.index }
        val all = s.exercises()
        val books = all.filter { it.documentId != null }.sortedBy { order[it.documentId] ?: Int.MAX_VALUE }   // 稳定排序：书内顺序不动
        return books + s.looseExercises()
    }

    private fun groupNames(s: LibraryStore): JSONArray {
        val names = s.looseExercises().mapNotNull { it.chapter.firstOrNull() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return JSONArray(names)
    }

    private fun exerciseJson(
        e: Exercise, st: com.xvan.unireader.local.exercise.ExAttemptStats?, ungraded: Int, full: Boolean,
    ): JSONObject {
        val o = JSONObject()
        o.put("id", e.id)
        o.put("type", when (e.kind) {
            Exercise.KIND_SINGLE -> "single"; Exercise.KIND_MULTIPLE -> "multiple"
            Exercise.KIND_FILL -> "fill"; else -> "subjective"
        })
        o.put("number", e.number); o.put("section", e.section); o.put("stem", e.payload.stem)
        o.put("options", JSONArray(e.payload.options.map { JSONObject().put("key", it.key).put("text", it.text) }))
        o.put("answer", when {
            e.isChoice -> e.payload.answer.joinToString("")
            e.isSubjective -> e.payload.answer.firstOrNull() ?: ""
            else -> ""
        })
        val inGroup = if (e.documentId == null) e.chapter.drop(1) else e.chapter
        o.put("chapter", JSONArray(inGroup)); o.put("tags", JSONArray(e.tags))
        o.put("link", "unireader://open?ex=" + e.id)   // 安卓不知道工作区路径：只带 ex，页面里点了由本机当前工作区处理
        o.put("starred", e.starred); o.put("in_mistakes", e.wrong)
        o.put("attempts", st?.attempts ?: 0); o.put("correct", st?.correct ?: 0); o.put("wrong", st?.wrong ?: 0)
        o.put("ungraded", ungraded)
        o.put("last_correct", st?.lastCorrect ?: JSONObject.NULL)
        if (e.isFill) o.put("blanks", JSONArray(e.payload.answer))
        if (e.documentId != null) o.put("document_id", e.documentId) else o.put("group", e.group ?: "")
        e.page?.let { o.put("page", it + 1) }
        e.payload.explanation?.let { o.put("explanation", it) }
        if (e.note.isNotEmpty()) o.put("note", e.note)
        e.payload.passage?.let { p ->
            val po = JSONObject().put("id", p.id).put("type", p.type)
            p.title?.let { po.put("title", it) }
            if (full) po.put("text", p.text)
            o.put("passage", po)
            e.payload.blank?.let { o.put("blank", it) }
        }
        return o
    }

    private fun documents(s: LibraryStore): JSONObject {
        val all = s.exercises() + s.looseExercises()
        val count = HashMap<String, Int>()
        all.forEach { e -> e.documentId?.let { count[it] = (count[it] ?: 0) + 1 } }
        return JSONObject()
            .put("documents", JSONArray(s.allDocuments().map {
                JSONObject().put("id", it.id).put("title", it.title).put("page_count", it.pageCount)
                    .put("exercise_count", count[it.id] ?: 0)
            }))
            .put("ungrouped_exercise_count", all.count { it.documentId == null })
            .put("groups", groupNames(s))
    }

    /** `exercises` / `exerciseStatus` 共用：按参数筛出题（不分页）。 */
    private fun select(
        s: LibraryStore, a: JSONObject, stats: Map<String, com.xvan.unireader.local.exercise.ExAttemptStats>,
    ): List<Exercise> {
        strings(a, "ids")?.takeIf { it.isNotEmpty() }?.let { ids -> return ids.take(MAX_LIMIT).mapNotNull { s.exercise(it) } }
        var chapter = strings(a, "chapter")?.map { it.trim() }?.filter { it.isNotEmpty() }
        var list: List<Exercise>
        if (a.has("group") && !a.isNull("group")) {
            val g = a.optString("group").trim()
            val scope = if (g.isEmpty()) emptyList() else listOf(g) + (chapter ?: emptyList())
            list = s.looseExercises().filter { com.xvan.unireader.local.exercise.ExChapter.contains(scope, it.chapter) }
            chapter = null
        } else if (a.optString("document").isNotEmpty()) {
            list = s.exercises(document(s, a.optString("document")))
        } else {
            list = allExercises(s)
        }
        when (val f = if (a.has("filter")) a.optString("filter") else "all") {
            "all" -> {}
            "mistakes" -> list = list.filter { it.wrong }
            "starred" -> list = list.filter { it.starred }
            else -> throw WebKV.Error("filter must be all, mistakes or starred.")
        }
        chapter?.let { c -> list = list.filter { com.xvan.unireader.local.exercise.ExChapter.contains(c, it.chapter) } }
        a.optString("tag").trim().takeIf { it.isNotEmpty() }?.let { t ->
            list = list.filter { e -> e.tags.any { it.equals(t, ignoreCase = true) } }
        }
        when (if (a.has("status")) a.optString("status") else "any") {
            "any" -> {}
            "new" -> list = list.filter { (stats[it.id]?.attempts ?: 0) == 0 }
            "practiced" -> list = list.filter { (stats[it.id]?.attempts ?: 0) > 0 }
            "last_correct" -> list = list.filter { stats[it.id]?.lastCorrect == true }
            "last_wrong" -> list = list.filter { stats[it.id]?.lastCorrect == false }
            else -> throw WebKV.Error("status must be any, new, practiced, last_correct or last_wrong.")
        }
        return list
    }

    private fun exercises(s: LibraryStore, a: JSONObject): JSONObject {
        val stats = s.exerciseAttemptStats()
        val ungraded = s.exerciseUngradedCounts()
        val list = select(s, a, stats)
        val offset = if (strings(a, "ids").isNullOrEmpty()) int(a, "offset", 0, 0, Int.MAX_VALUE) else 0
        val limit = int(a, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        return JSONObject().put("total", list.size).put("offset", offset).put("groups", groupNames(s))
            .put("exercises", JSONArray((if (offset == 0 && !strings(a, "ids").isNullOrEmpty()) list else list.drop(offset).take(limit))
                .map { exerciseJson(it, stats[it.id], ungraded[it.id] ?: 0, false) }))
    }

    /** 轻量状态：每道题只带 id / 题号 / 章节 / 作答统计 / 错题本 / 收藏 / 链接，不带题干选项答案。不分页（同 Mac）。 */
    private fun status(s: LibraryStore, a: JSONObject): JSONObject {
        val stats = s.exerciseAttemptStats()
        val ungraded = s.exerciseUngradedCounts()
        val list = select(s, a, stats)
        return JSONObject().put("total", list.size).put("exercises", JSONArray(list.map { e ->
            val st = stats[e.id]
            JSONObject().put("id", e.id).put("number", e.number)
                .put("chapter", JSONArray(if (e.documentId == null) e.chapter.drop(1) else e.chapter))
                .put("attempts", st?.attempts ?: 0).put("correct", st?.correct ?: 0).put("wrong", st?.wrong ?: 0)
                .put("ungraded", ungraded[e.id] ?: 0).put("last_correct", st?.lastCorrect ?: JSONObject.NULL)
                .put("in_mistakes", e.wrong).put("starred", e.starred).put("link", "unireader://open?ex=" + e.id)
        }))
    }

    private fun exercise(s: LibraryStore, a: JSONObject): Any {
        val id = a.optString("id")
        if (id.isEmpty()) throw WebKV.Error("An exercise id is required.")
        val e = s.exercise(id) ?: return JSONObject.NULL
        return exerciseJson(e, s.exerciseAttemptStats()[e.id], s.exerciseUngradedCounts()[e.id] ?: 0, true)
    }

    private fun stats(s: LibraryStore, a: JSONObject): JSONObject {
        val list: List<Exercise> = if (a.has("group") && !a.isNull("group")) {
            val g = a.optString("group").trim()
            val scope = if (g.isEmpty()) emptyList() else listOf(g)
            s.looseExercises().filter { com.xvan.unireader.local.exercise.ExChapter.contains(scope, it.chapter) }
        } else if (a.optString("document").isNotEmpty()) {
            s.exercises(document(s, a.optString("document")))
        } else allExercises(s)
        val st = s.exerciseAttemptStats()
        val ung = s.exerciseUngradedCounts()
        var practiced = 0; var attempts = 0; var correct = 0; var wrong = 0; var ungraded = 0
        for (e in list) {
            val x = st[e.id] ?: continue
            if (x.attempts <= 0) continue
            practiced++; attempts += x.attempts; correct += x.correct; wrong += x.wrong; ungraded += ung[e.id] ?: 0
        }
        return JSONObject().put("total", list.size).put("practiced", practiced).put("unpracticed", list.size - practiced)
            .put("mistakes", list.count { it.wrong }).put("starred", list.count { it.starred })
            .put("attempts", attempts).put("correct", correct).put("wrong", wrong).put("ungraded", ungraded)
    }

    private fun attempts(s: LibraryStore, a: JSONObject): JSONObject {
        val offset = int(a, "offset", 0, 0, Int.MAX_VALUE)
        val limit = int(a, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)
        val ungraded = a.optBoolean("ungraded", false)
        var ids: List<String>? = null
        if (a.optString("exerciseId").isNotEmpty()) ids = listOf(a.optString("exerciseId"))
        else strings(a, "exerciseIds")?.let { ids = it }
        val docId = if (ids == null && a.optString("document").isNotEmpty()) document(s, a.optString("document")) else null
        val (rows, total) = s.exerciseAttemptPage(exerciseIds = ids, documentId = docId, ungradedOnly = ungraded, offset = offset, limit = limit)
        return JSONObject().put("total", total).put("offset", offset).put("attempts", JSONArray(rows.map { r ->
            val o = JSONObject().put("id", r.id).put("exercise_id", r.exerciseId).put("answer", r.answer)
                .put("source", if (r.source == Exercise.MARK_SOURCE) "book" else "practice")
                .put("created_at", r.createdAt).put("elapsed_ms", r.elapsedMs)
                .put("correct", r.correct ?: JSONObject.NULL)
            if (r.feedback.isNotEmpty()) o.put("feedback", r.feedback)
            o
        }))
    }
}
