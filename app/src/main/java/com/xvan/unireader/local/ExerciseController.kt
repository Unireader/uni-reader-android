package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.exercise.ExChapter
import com.xvan.unireader.local.exercise.ExChapterNode
import com.xvan.unireader.local.exercise.ExDoneSummary
import com.xvan.unireader.local.exercise.ExFilter
import com.xvan.unireader.local.exercise.ExGroupNode
import com.xvan.unireader.local.exercise.ExScope
import com.xvan.unireader.local.exercise.ExSummary
import com.xvan.unireader.local.exercise.Exercise
import com.xvan.unireader.local.exercise.LocalExerciseDeck
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.TocItem
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.exercise.ExGroup
import com.xvan.unireader.shared.exercise.ExHost
import com.xvan.unireader.shared.exercise.ExerciseScreen
import com.xvan.unireader.shared.runInBackground
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模式1 的习题（`../docs/EXERCISE-PLAN.md §10.2`）：抽屉「习题」一段的分组、一个习题标签里那一组题、把界面的操作落进本机库。
 * 照 [BoardController] 的分工：数据读写全在这里，`ReaderActivity` 只接线（标签页、抽屉、到 PDF 原页）。
 *
 * 界面是两模式共用的 [ExerciseScreen]；这里实现它的 [ExHost]：作答 / 收藏 / 移出错题本 / 设章节写库（经 [StoreQueue]），
 * 写完重读那一组（[LocalExerciseDeck.applyReload]）+ 重算抽屉的数。删除习题不做（安卓没有回收站，删只在 Mac 上做）。
 */
class ExerciseController(private val a: Activity) : ExHost {

    companion object {
        const val TAG = "UniReader/Exercise"
    }

    val screen = ExerciseScreen(a, this)

    private var queue: StoreQueue? = null
    private var workspace: File? = null
    /** 书名（文档 id → 标题），范围名与分组用。 */
    private var docs: List<Pair<String, String>> = emptyList()
    var summary = ExSummary()
        private set
    /** 每组做过几道（抽屉行尾「已做 / 共」）。 */
    private var doneSummary = ExDoneSummary()
    private var deck: LocalExerciseDeck? = null

    /** 抽屉「习题」一段（摊平的分组）变了。 */
    var onGroups: (List<ExGroup>) -> Unit = {}
    /** 到 PDF 原页：文档 id、页（0 起）、页内纵向比例。 */
    var onOpenSource: (String, Int, Float) -> Unit = { _, _, _ -> }
    /** 「看原题」/「设置章节」要开的那本书（只留最近一本，同参考窗的做法）。 */
    private var src: PdfSource? = null
    private var srcDoc = ""

    fun bind(q: StoreQueue?, dir: File?) {
        if (queue !== q) {
            workspace?.let { LocalExerciseDeck.forget(it.absolutePath) }
            deck = null
            docs = emptyList()
            summary = ExSummary()
            doneSummary = ExDoneSummary()
            src?.let { s -> Bg.submit("关闭原题 PDF") { s.close() } }
            src = null
            srcDoc = ""
        }
        queue = q
        workspace = dir
        if (q == null) {
            summary = ExSummary()
            onGroups(emptyList())
        }
    }

    // ---------- 抽屉的分组 ----------

    /** 重读抽屉的数（书库顺序 + 每篇几道 + 章节分布），推给 [onGroups]。 */
    fun refreshGroups(then: (() -> Unit)? = null) {
        val q = queue ?: return
        q.submit("读习题分组", { s ->
            Triple(s.exerciseSummary(), s.allDocuments().map { it.id to it.title }, ExDoneSummary.of(s.exerciseDoneCounts()))
        }, { (sum, list, done) ->
            summary = sum
            docs = list
            doneSummary = done
            onGroups(groups())
            then?.invoke()
        })
    }

    /** 只重读「每组做过几道」（答了一道新题：题目行没变，只有行尾的数变了，同 Mac `refreshExerciseDone`）。 */
    private fun refreshDone() {
        val q = queue ?: return
        q.submit("读习题做过几道", { s -> ExDoneSummary.of(s.exerciseDoneCounts()) }, { done ->
            if (done == doneSummary) return@submit
            doneSummary = done
            onGroups(groups())
        })
    }

    private fun groups(): List<ExGroup> =
        ExGroupNode.flatten(
            ExGroupNode.build(summary, docs, ::scopeTitle, a.getString(R.string.ex_no_chapter)).map { it.withDone(doneSummary) },
        ).map { (n, depth) -> ExGroup(n.scope.key, n.title, n.count, depth, n.done) }

    /** 范围名（同 Mac `exerciseScopeTitle`）：标签栏、习题页顶上都用它。 */
    fun scopeTitle(sc: ExScope): String = when (sc) {
        ExScope.All -> a.getString(R.string.ex_all)
        ExScope.Wrong -> a.getString(R.string.ex_mistakes)
        ExScope.Starred -> a.getString(R.string.ex_starred)
        is ExScope.Document -> docTitle(sc.id)
        is ExScope.Chapter -> docTitle(sc.id) + " · " + (sc.path.lastOrNull() ?: a.getString(R.string.ex_no_chapter))
        is ExScope.WrongDocument -> a.getString(R.string.ex_mistakes) + " · " + scopeTitle(ExScope.Document(sc.id))
        is ExScope.WrongChapter -> a.getString(R.string.ex_mistakes) + " · " + scopeTitle(ExScope.Chapter(sc.id, sc.path))
        is ExScope.StarredDocument -> a.getString(R.string.ex_starred) + " · " + scopeTitle(ExScope.Document(sc.id))
        is ExScope.StarredChapter -> a.getString(R.string.ex_starred) + " · " + scopeTitle(ExScope.Chapter(sc.id, sc.path))
    }

    private fun docTitle(id: String) = docs.firstOrNull { it.first == id }?.second ?: a.getString(R.string.ex_title)

    /** 书名表（`ReaderActivity` 读过书库就顺手给一份，免得标签标题等抽屉那次读库）。 */
    fun setDocTitles(titles: Map<String, String>) {
        docs = titles.map { it.key to it.value }
    }

    // ---------- 一个习题标签 ----------

    /** 开 / 切到一组题（标签激活时调）。第一次开先从库里读，读完画出来。 */
    fun open(scope: ExScope) {
        val ws = workspace ?: return
        val d = LocalExerciseDeck.shared(ws.absolutePath, scope)
        deck = d
        if (d.loaded) render() else screen.showLoading(scopeTitle(scope))
        reload(fresh = !d.loaded)
    }

    /** 标签切走 / 关掉。 */
    fun close() {
        deck = null
        screen.onHidden()
    }

    /** 开着服务器工作区时别的设备改了习题 / 作答（行已写进库）：开着的这一组就地刷新（不换眼前的题），分组的数重读。 */
    fun reloadFromRemote() {
        refreshGroups()
        if (deck != null) reload()
    }

    private fun render() {
        val d = deck ?: return
        screen.show(d.view(scopeTitle(d.scope)))
    }

    /** 库里变了（或第一次开）：重读这一组。`fresh` = 整份重取（第一次 / 换筛选），否则就地刷新快照。 */
    private fun reload(fresh: Boolean = false) {
        val q = queue ?: return
        val d = deck ?: return
        val keep = if (fresh) emptyList() else d.items.map { it.id }
        q.submit("读习题 ${d.scope.key.take(24)}", { s -> LocalExerciseDeck.read(s, d.scope, keep) }, { f ->
            if (fresh) d.applyFresh(f) else d.applyReload(f)
            if (deck === d) render()
        })
    }

    /** 写了改题目行的东西（收藏 / 错题本 / 章节）：重读这一组 + 抽屉的数。 */
    private fun write(what: String, body: (com.xvan.unireader.local.store.LibraryStore) -> Unit) {
        val q = queue ?: return
        q.submit(what, { s -> runCatching { body(s) }.onFailure { Log.e(TAG, "$what 写库失败", it) } }, {
            reload()
            refreshGroups()
        })
    }

    // ---------- ExHost ----------

    override fun submit(id: String, keys: String, elapsedMs: Long) {
        val d = deck ?: return
        val chosen = keys.map { it.toString() }.toSet()
        val ans = d.submit(id, chosen) ?: return
        render()
        val q = queue ?: return
        q.submit("记作答 ${id.take(8)}", { s ->
            runCatching { s.recordExerciseAttempt(id, Exercise.answerString(chosen), ans.correct, elapsedMs) }
                .onFailure { Log.e(TAG, "记作答失败", it) }
        }, {
            // 答错 = 进了错题本：题目行变了、抽屉的数也变了；答对不动题目行（同 Mac 不重读这一组），
            // 只有「已做 / 共」可能变了（这道题第一次做）
            if (ans.correct == false) { reload(); refreshGroups() } else refreshDone()
        })
    }

    override fun submitBlanks(id: String, blanks: List<String>, elapsedMs: Long) {
        val d = deck ?: return
        val ans = d.submitBlanks(id, blanks) ?: return
        render()
        val q = queue ?: return
        q.submit("记填空作答 ${id.take(8)}", { s ->
            val e = d.items.firstOrNull { it.id == id }
            val text = if (e != null) d.typedAnswerString(e, blanks) else Exercise.fillAnswerString(blanks)
            runCatching { s.recordExerciseAttempt(id, text, ans.correct, elapsedMs) }
                .getOrElse { Log.e(TAG, "记填空作答失败", it); null }
        }, { attemptId ->
            // 没判上（等自己判）不动题目行；判对 / 判错只有「已做 / 共」和错题本要刷
            if (ans.correct == false) { reload(); refreshGroups() } else refreshDone()
            // 作答 id 补进内存（填空「答对了 / 答错了」写回用），然后按新统计重画
            attemptId?.let { d.setAttemptId(id, it) }
            if (deck === d) render()
        })
    }

    override fun judgeExercise(id: String, attemptId: String, correct: Boolean) {
        val d = deck ?: return
        val aid = attemptId.ifEmpty { d.attemptIdOf(id) ?: return }
        d.judgeAttempt(id, correct)
        render()
        write("判定作答 ${aid.take(8)}") { it.judgeExerciseAttempt(aid, correct, "self") }
    }

    override fun setStarred(id: String, on: Boolean) = write("收藏 ${id.take(8)}") { it.setExerciseStarred(id, on) }

    override fun removeFromMistakes(id: String) = write("移出错题本 ${id.take(8)}") { it.setExerciseWrong(id, false) }

    override fun redo(ids: List<String>, from: Int) {
        val d = deck ?: return
        d.redo(ids)
        d.index = from.coerceIn(0, max(0, d.items.size - 1))
        render()
    }

    override fun setFilter(filter: Int) {
        val d = deck ?: return
        val f = ExFilter.of(filter) ?: return
        if (!d.setFilter(f)) return
        if (f == ExFilter.LAST_WRONG) restartLastWrong() else reload(fresh = true)
    }

    override fun restartLastWrong() {
        val q = queue ?: return
        val d = deck ?: return
        if (d.filter != ExFilter.LAST_WRONG) return
        // 排在库队列上：刚交的作答已经先写进去了，读到的统计是最新的
        q.submit("重挑上次做错的 ${d.scope.key.take(24)}", { s -> LocalExerciseDeck.read(s, d.scope) }, { f ->
            d.applyLastWrongRound(f)
            if (deck === d) render()
        })
    }

    override fun go(index: Int) {
        val d = deck ?: return
        if (index !in d.items.indices || index == d.index) return
        d.index = index
        render()
    }

    override fun openSource(id: String) {
        val e = deck?.items?.firstOrNull { it.id == id } ?: return
        val docId = e.documentId ?: return
        val page = e.page ?: return
        // 有题目框就停在框的上沿（同 Mac `showExerciseSource`）
        val frac = e.payload.region?.let { max(0.0, it[1] - 0.02).toFloat() } ?: 0f
        onOpenSource(docId, page, frac)
    }

    /**
     * 原题那一块：本机 Pdfium 把那一页按够用的宽度出图，再裁出题目框（四周放 1.5% 页宽，同 Mac `ExerciseOriginal`）。
     * 没有框就整页。跨页的题（`next_regions`，`../docs/EXERCISE-PLAN.md §26`）后面几页各裁一块，从上到下拼成一张。
     * 拿不到（文件不在）→ null；后面某页出不了图就少那一段。
     */
    override fun loadOriginal(id: String, widthPx: Int, done: (Bitmap?) -> Unit) {
        val e = deck?.items?.firstOrNull { it.id == id }
        val docId = e?.documentId
        if (e == null || docId == null || e.page == null) { done(null); return }
        withSource(docId, { done(null) }) { s ->
            val slices = e.originalSlices(s.pageCount)
            if (slices.isEmpty()) { done(null); return@withSource }
            // 页宽按「最宽那段框占页宽的比例」放大，框里的字才够清楚；几段同一个页宽 = 字一样大。PdfSource 自己会归到档位上
            val rw = slices.maxOf { it.second?.get(2) ?: 1.0 }.coerceAtLeast(0.2)
            val pageW = (widthPx / rw).roundToInt().coerceIn(600, 2880)
            val parts = ArrayList<Bitmap>(slices.size)
            fun finish() {
                val out = if (parts.isEmpty()) null else stack(parts)
                Bg.main.post { done(out) }
            }
            // 一页一页按顺序要（命中缓存时 request 同步回调，几段最多 4 层，不怕递归深）
            fun next(k: Int) {
                if (k == slices.size) { finish(); return }
                val (page, region) = slices[k]
                s.request(page, pageW) { bmp ->
                    val part = bmp?.let { crop(it, region) }
                    if (part == null) { finish(); return@request }
                    parts.add(part)
                    next(k + 1)
                }
            }
            next(0)
        }
    }

    /** 几段从上到下拼成一张（白底、左对齐，段间一条 8px 浅灰分隔，同 Mac `ExerciseOriginal.stack`）。一段就原样返回。 */
    private fun stack(parts: List<Bitmap>): Bitmap? {
        if (parts.size == 1) return parts[0]
        val gap = 8
        val w = parts.maxOf { it.width }
        val h = parts.sumOf { it.height } + gap * (parts.size - 1)
        return runCatching {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            // 图里的分隔线（页面内容的一部分，同 Mac 灰度 0.85），不是界面颜色
            val sep = Paint().apply { color = Color.rgb(217, 217, 217) }
            var y = 0f
            parts.forEachIndexed { i, b ->
                c.drawBitmap(b, 0f, y, null)
                y += b.height
                if (i < parts.size - 1) {
                    c.drawRect(0f, y, w.toFloat(), y + gap, sep)
                    y += gap
                }
            }
            out
        }.getOrNull()
    }

    private fun crop(b: Bitmap, r: List<Double>?): Bitmap? {
        if (r == null || r.size != 4) return b
        val pad = 0.015
        val x0 = ((r[0] - pad) * b.width).roundToInt().coerceIn(0, b.width - 1)
        val y0 = ((r[1] - pad * b.width / b.height) * b.height).roundToInt().coerceIn(0, b.height - 1)
        val x1 = ((r[0] + r[2] + pad) * b.width).roundToInt().coerceIn(x0 + 1, b.width)
        val y1 = ((r[1] + r[3]) * b.height + pad * b.width).roundToInt().coerceIn(y0 + 1, b.height)
        return runCatching { Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0) }.getOrNull()
    }

    /** 拿到这本书的 [PdfSource]（只留最近一本，换书才重开；开 Pdfium 在后台）。拿不到走 [fail]。 */
    private fun withSource(docId: String, fail: () -> Unit, ok: (PdfSource) -> Unit) {
        src?.takeIf { srcDoc == docId }?.let { ok(it); return }
        val q = queue ?: return fail()
        val ws = workspace ?: return fail()
        q.submit("原题找文件 ${docId.take(8)}", { s ->
            Workspace.firstOpenablePdfWithHash(ws, s, docId)?.let { (f, hash) -> Triple(f, hash, s.activePageAlign(hash)) }
        }, { t ->
            if (t == null) { fail(); return@submit }
            a.runInBackground(
                what = "原题开 PDF ${t.first.name}",
                work = { PdfSource(a.applicationContext, t.first, contentHash = t.second, align = t.third) },
                ok = { s ->
                    src?.let { old -> Bg.submit("关闭原题 PDF") { old.close() } }
                    src = s
                    srcDoc = docId
                    ok(s)
                },
                fail = { fail() },
                discard = { it.close() },
            )
        })
    }

    // ---------- 题目笔记 / 删除 ----------

    override val canEditNote: Boolean get() = true

    override fun setNote(id: String, text: String) {
        val t = text.trim()
        if (deck?.items?.firstOrNull { it.id == id }?.note == t) return
        write("题目笔记 ${id.take(8)}") { it.setExerciseNote(id, t) }
    }

    override val canDelete: Boolean get() = true

    /** 删题：先归档进回收站，成了才删（红线 11）；归档失败一道都不删、提示一句。 */
    override fun delete(ids: List<String>) {
        val q = queue ?: return
        val ws = workspace ?: return
        val d = deck ?: return
        val picked = d.items.filter { it.id in ids }
        val first = picked.firstOrNull() ?: return
        val title = if (picked.size == 1) a.getString(R.string.ex_trash_one, first.number.ifEmpty { first.id.take(8) })
        else a.getString(R.string.ex_trash_many, picked.size)
        val docTitle = first.documentId?.let { id -> docs.firstOrNull { it.first == id }?.second } ?: ""
        val del = picked.map { it.id }
        q.submit("删习题 ${del.size} 道", { s ->
            runCatching { com.xvan.unireader.local.Trash.deleteExercises(s, ws, del, title, docTitle) }
                .onFailure { Log.e(TAG, "习题归档失败，没删", it) }.isSuccess
        }, { ok ->
            if (ok) d.forgetAnswers(del) else Toast.makeText(a, R.string.trash_failed, Toast.LENGTH_LONG).show()
            reload()
            refreshGroups()
        })
    }

    // ---------- 设置章节（列表多选之后，同 Mac「设置章节…」） ----------

    override val canSetChapter: Boolean get() = true

    override fun setChapter(ids: List<String>) {
        val d = deck ?: return
        val picked = d.items.filter { it.id in ids }
        val docId = picked.firstOrNull()?.documentId ?: return
        if (picked.any { it.documentId != docId }) {
            Toast.makeText(a, R.string.ex_chapter_same_book, Toast.LENGTH_SHORT).show()
            return
        }
        withSource(docId, { showChapterSheet(picked, emptyList()) }) { s -> showChapterSheet(picked, tree(s.toc)) }
    }

    /** 抽屉那份目录是「先序拍平 + depth」，这里按 depth 还原成树（同抽屉的做法：前一项 depth 更小者即父）。 */
    private fun tree(toc: List<TocItem>): List<ExChapterNode> {
        class B(val label: String, val page: Int?, val kids: MutableList<B> = ArrayList())
        val roots = ArrayList<B>()
        val stack = ArrayList<B>()
        for (t in toc) {
            val b = B(t.label, t.page.takeIf { it >= 0 })
            while (stack.size > t.depth) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) roots += b else stack.last().kids += b
            stack += b
        }
        fun conv(b: B): ExChapterNode = ExChapterNode(b.label, b.page, b.kids.map(::conv))
        return roots.map(::conv)
    }

    private fun showChapterSheet(items: List<Exercise>, toc: List<ExChapterNode>) {
        val ids = items.map { it.id }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        fun row(label: String, depth: Int = 0, onClick: () -> Unit) {
            col.addView(
                TextView(a).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Ui.onSurface(a))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(Ui.dp(a, 12 + depth * 14), Ui.dp(a, 11), Ui.dp(a, 12), Ui.dp(a, 11))
                    isClickable = true
                    background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
                    setOnClickListener { dlg?.dismiss(); onClick() }
                },
            )
        }
        row(a.getString(R.string.ex_chapter_auto)) {
            write("按目录设章节") { s ->
                for (e in items) {
                    val p = e.page ?: continue
                    s.setExerciseChapter(listOf(e.id), ExChapter.path(p, toc))
                }
            }
        }
        row(a.getString(R.string.ex_no_chapter)) { write("清掉章节") { s -> s.setExerciseChapter(ids, emptyList()) } }
        for (path in ExChapter.flatten(toc)) {
            row(path.last(), path.size - 1) { write("设章节") { s -> s.setExerciseChapter(ids, path) } }
        }
        row(a.getString(R.string.ex_chapter_custom)) { askCustomChapter(ids, items.firstOrNull()?.chapter ?: emptyList()) }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.tip(a, a.getString(R.string.ex_chapter_tip)))
            addView(ScrollView(a).apply { addView(col) }, LinearLayout.LayoutParams(-1, -2))
        }
        dlg = Sheet(a).title(a.getString(R.string.ex_set_chapter_title)).content(body)
            .action(a.getString(R.string.common_cancel)).show()
    }

    private fun askCustomChapter(ids: List<String>, current: List<String>) {
        val edit = PadPanels.inputBox(a, a.getString(R.string.ex_chapter_hint)).apply {
            setText(ExChapter.display(current))
            setSelection(text.length)
        }
        Sheet(a).title(a.getString(R.string.ex_set_chapter_title))
            .content(edit)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_save), primary = true) {
                val path = ExChapter.clean(edit.text.toString().split('›', '>'))
                write("手填章节") { s -> s.setExerciseChapter(ids, path) }
            }
            .show()
    }
}
