package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibReview
import com.xvan.unireader.local.store.LibTocEntry
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.nowIso
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.ReaderDrawer
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.TocItem
import com.xvan.unireader.shared.Ui
import java.io.File

/**
 * 自建目录（note kind=7）与回顾点（kind=8）的模式1 界面与读写（`../docs/TOC-REVIEW-PLAN.md`，照 Mac `TOCReviewActions`）。
 *
 * 只经构造参数碰阅读界面（[ReaderActivity] 那 3600 行别再长）；读写一律走库队列、写完重读，不在内存里推算。
 * 🔴 删除先进回收站（`Trash.deleteAnnotations`，条数对上才删，红线 11）。
 */
class TocReviewController(
    private val a: Activity,
    private val drawer: ReaderDrawer,
    private val queue: () -> StoreQueue?,
    private val workspace: () -> File?,
    /** 当前 PDF 标签的文档 id（不是 PDF 标签 = null） */
    private val currentDoc: () -> String?,
    /** 当前阅读位置（视口顶那一页 + 页内比例） */
    private val currentPos: () -> Pair<Int, Float>?,
    /** 当前 PDF 自带目录（没装好 = null） */
    private val pdfToc: () -> List<TocItem>?,
    private val docTitles: () -> Map<String, String>,
    /** 跳到当前文档某处（走宿主的 `jumpTo`，退得回来） */
    private val jump: (page: Int, frac: Float, label: String) -> Unit,
    /** 打开另一篇并跳到某处 */
    private val openAt: (docId: String, page: Int, frac: Float) -> Unit,
    /** 某篇的生效目录变了：宿主重推抽屉目录 */
    private val onTocChanged: (docId: String) -> Unit,
    /** 某篇的回顾点变了：宿主把标记给那篇的画布 */
    private val onMarks: (docId: String, marks: List<PageCanvasView.ReviewMark>) -> Unit,
) {
    private val customToc = HashMap<String, List<LibTocEntry>>()
    private val reviewsByDoc = HashMap<String, List<LibReview>>()
    /** 切回了「PDF 自带目录」的文档（只在内存，同 Mac） */
    private val showPdf = HashSet<String>()
    /** 整个工作区的回顾点（回顾页「整个工作区」用；null = 还没读 / 作废了） */
    private var allReviews: List<LibReview>? = null

    // 回顾页的筛选（只在内存）
    private var wholeWorkspace = false
    private var tagFilter: String? = null
    private var periodFilter: Int? = null

    private val prefs get() = a.getSharedPreferences("review", Activity.MODE_PRIVATE)
    private var step: Int
        get() = prefs.getInt("step", 1).coerceAtLeast(1)
        set(v) { prefs.edit().putInt("step", v.coerceAtLeast(1)).apply() }
    private var lastTag: String
        get() = prefs.getString("lastTag", "") ?: ""
        set(v) { prefs.edit().putString("lastTag", v).apply() }
    private var lastPeriod: Int
        get() = prefs.getInt("lastPeriod", 1).coerceAtLeast(1)
        set(v) { prefs.edit().putInt("lastPeriod", v.coerceAtLeast(1)).apply() }

    fun clear() {
        customToc.clear(); reviewsByDoc.clear(); showPdf.clear(); allReviews = null
        tagFilter = null; periodFilter = null
    }

    // ---------- 读 ----------

    /** 读一篇的自建目录 + 回顾点（开标签 / 库被别处改了时调） */
    fun load(docId: String) {
        val q = queue() ?: return
        q.submit("读自建目录与回顾点", { s -> s.customToc(docId) to s.reviews(docId) }) { (toc, revs) ->
            val tocChanged = customToc[docId] != toc
            customToc[docId] = toc
            if (toc.isEmpty()) showPdf.remove(docId)
            reviewsByDoc[docId] = revs
            if (tocChanged) onTocChanged(docId)
            onMarks(docId, marks(revs))
        }
    }

    /** 同步拉到 / 回收站放回了 note 行：开着的几篇重读，整个工作区那份作废 */
    fun reloadAll(openDocs: Collection<String>) {
        allReviews = null
        for (d in openDocs) load(d)
        drawer.refreshReviews()
    }

    private fun marks(list: List<LibReview>) = list.map { PageCanvasView.ReviewMark(it.id, it.page, it.frac) }

    /** 抽屉里这一篇显示自建目录 */
    fun showsCustom(docId: String): Boolean = !customToc[docId].isNullOrEmpty() && docId !in showPdf

    /** 生效目录：自建优先（同 Mac `DocSession.applyTOC`）。自建目录带页内位置，不像 Pdfium 书签只到页顶 */
    fun effectiveToc(docId: String, pdf: List<TocItem>?): List<TocItem>? =
        if (showsCustom(docId)) customToc[docId]!!.map { TocItem(it.level, it.page, it.frac, it.title) } else pdf

    /** 抽屉目录的去重键：内容变了才重推（重推会清掉用户手动折叠的分支） */
    fun tocKey(docId: String): String = "${showsCustom(docId)}#${customToc[docId]?.hashCode() ?: 0}"

    fun menuLabel(docId: String): String =
        a.getString(if (showsCustom(docId)) R.string.toc_custom else R.string.toc_pdf)

    // ---------- 自建目录：抽屉「目录操作」与长按 ----------

    fun showTocMenu() {
        val docId = currentDoc() ?: return
        val custom = customToc[docId].orEmpty()
        val pdf = pdfToc().orEmpty()
        var dlg: AlertDialog? = null
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun row(icon: Int, text: String, enabled: Boolean = true, body: () -> Unit) {
            col.addView(PadPanels.iconRow(a, icon, text) { dlg?.dismiss(); body() }.apply {
                isEnabled = enabled
                isClickable = enabled
                alpha = if (enabled) 1f else 0.4f
            })
        }
        if (custom.isNotEmpty()) {
            val onCustom = docId !in showPdf
            row(if (onCustom) R.drawable.ic_check else R.drawable.ic_list, a.getString(R.string.toc_show_custom)) {
                showPdf.remove(docId); onTocChanged(docId)
            }
            row(if (!onCustom) R.drawable.ic_check else R.drawable.ic_doc, a.getString(R.string.toc_show_pdf)) {
                showPdf.add(docId); onTocChanged(docId)
            }
        }
        row(R.drawable.ic_plus, a.getString(R.string.toc_add_here)) { addTocEntry(docId) }
        row(R.drawable.ic_copy, a.getString(R.string.toc_copy_pdf), pdf.isNotEmpty()) { copyPdfToc(docId, pdf) }
        row(R.drawable.ic_delete, a.getString(R.string.toc_clear), custom.isNotEmpty()) { clearToc(docId) }
        dlg = Sheet(a).title(a.getString(R.string.toc_menu_title)).content(col).action(a.getString(R.string.common_cancel)).show()
    }

    /** 长按抽屉里的目录行（[index] = 生效目录里的下标）：只有显示着自建目录时才有菜单 */
    fun onTocLongPress(index: Int) {
        val docId = currentDoc() ?: return
        if (!showsCustom(docId)) return
        val e = customToc[docId]?.getOrNull(index) ?: return
        var dlg: AlertDialog? = null
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun row(icon: Int, text: String, body: () -> Unit) =
            col.addView(PadPanels.iconRow(a, icon, text) { dlg?.dismiss(); body() })
        row(R.drawable.ic_text, a.getString(R.string.toc_edit)) { editTocEntry(docId, e) }
        if (e.level > 0) row(R.drawable.ic_chevron_left, a.getString(R.string.toc_promote)) { shift(docId, e, -1) }
        if (e.level < LibTocEntry.MAX_LEVEL) row(R.drawable.ic_chevron_right, a.getString(R.string.toc_demote)) { shift(docId, e, 1) }
        row(R.drawable.ic_delete, a.getString(R.string.common_delete)) { deleteTocEntry(docId, e) }
        dlg = Sheet(a).title(e.title).content(col).action(a.getString(R.string.common_cancel)).show()
    }

    private fun addTocEntry(docId: String) {
        val (page, frac) = currentPos() ?: return
        val list = customToc[docId].orEmpty()
        // 默认层级 = 前面最近那一项的（同 Mac `CustomTOCItem.defaultLevel`）
        val level = list.lastOrNull { it.page < page || (it.page == page && it.frac <= frac) }?.level ?: 0
        tocSheet(a.getString(R.string.toc_add_title), pageHint(page), "", level) { title, lv ->
            val seq = (list.maxOfOrNull { it.seq } ?: -1) + 1
            val e = LibTocEntry(newId(), page, frac, lv, title, seq, "")
            write("加目录项", docId) { s -> s.upsertTocEntry(docId, e) }
            showPdf.remove(docId)   // 加了就切到自建目录看（不然加了看不见）
        }
    }

    private fun editTocEntry(docId: String, e: LibTocEntry) {
        tocSheet(a.getString(R.string.toc_edit_title), pageHint(e.page), e.title, e.level) { title, lv ->
            write("改目录项", docId) { s -> s.upsertTocEntry(docId, e.copy(title = title, level = lv)) }
        }
    }

    private fun shift(docId: String, e: LibTocEntry, d: Int) {
        val lv = (e.level + d).coerceIn(0, LibTocEntry.MAX_LEVEL)
        if (lv != e.level) write("目录项升降级", docId) { s -> s.upsertTocEntry(docId, e.copy(level = lv)) }
    }

    private fun deleteTocEntry(docId: String, e: LibTocEntry) {
        val ws = workspace() ?: return
        val title = docTitles()[docId].orEmpty()
        write("删目录项", docId) { s -> Trash.deleteAnnotations(s, ws, docId, title, listOf(e.id), "“${e.title}”") }
    }

    private fun copyPdfToc(docId: String, pdf: List<TocItem>) {
        // 坏书签（page < 0）跳过，同 Mac `CustomTOCItem.copy`
        val items = pdf.filter { it.page >= 0 }.mapIndexed { i, t ->
            LibTocEntry(newId(), t.page, t.frac, t.depth.coerceIn(0, LibTocEntry.MAX_LEVEL), t.label.ifEmpty { "—" }, i, "")
        }
        if (items.isEmpty()) return
        val go = { replace(docId, items) }
        if (customToc[docId].isNullOrEmpty()) go()
        else Sheet(a).title(a.getString(R.string.toc_replace_title)).subtitle(a.getString(R.string.toc_replace_msg))
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.toc_replace), primary = true) { go() }.show()
    }

    private fun clearToc(docId: String) {
        Sheet(a).title(a.getString(R.string.toc_clear_title)).subtitle(a.getString(R.string.toc_clear_msg))
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.toc_clear_do), primary = true) { replace(docId, emptyList()) }.show()
    }

    /** 整份替换：旧的整份进回收站，再写新的（同 Mac `replaceCustomTOC`） */
    private fun replace(docId: String, items: List<LibTocEntry>) {
        val ws = workspace() ?: return
        val docTitle = docTitles()[docId].orEmpty()
        write("替换自建目录", docId) { s -> replaceToc(s, ws, docId, docTitle, items) }
        showPdf.remove(docId)
    }

    private fun tocSheet(title: String, hint: String, name: String, level: Int, done: (String, Int) -> Unit) {
        val edit = PadPanels.inputBox(a, a.getString(R.string.toc_entry_hint)).apply {
            setText(name)
            setSelection(text.length)
        }
        var lv = level.coerceIn(0, LibTocEntry.MAX_LEVEL)
        val levels = choiceRow((0..LibTocEntry.MAX_LEVEL).map { a.getString(R.string.toc_level, it + 1) }, lv) { lv = it }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(edit)
            addView(Ui.groupTitle(a, a.getString(R.string.toc_level_title), 12))
            addView(levels)
        }
        Sheet(a).title(title).subtitle(hint).content(col)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_save), primary = true) {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) done(t, lv)
            }.show()
    }

    // ---------- 回顾点 ----------

    /** 在当前位置加一个回顾点 */
    fun addReviewHere() {
        val docId = currentDoc() ?: run { toast(R.string.review_need_pdf); return }
        val (page, frac) = currentPos() ?: return
        reviewSheet(a.getString(R.string.review_add_title), pageHint(page), lastTag, lastPeriod, "") { tag, period, remark ->
            lastTag = tag
            lastPeriod = period
            val r = LibReview(newId(), docId, page, frac, tag, remark, period, 0, "", "")
            write("加回顾点", docId) { s -> s.upsertReviews(listOf(r)) }
        }
    }

    /** 手指点了页面上的回顾点 */
    fun onMarkTap(id: String) {
        val r = reviewsByDoc.values.flatten().firstOrNull { it.id == id } ?: return
        reviewMenu(r)
    }

    private fun reviewMenu(r: LibReview) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (r.remark.isNotEmpty()) col.addView(Ui.body(a, r.remark, variant = false))
        col.addView(Ui.body(a, subline(r, null)))
        fun row(icon: Int, text: String, body: () -> Unit) =
            col.addView(PadPanels.iconRow(a, icon, text) { dlg?.dismiss(); body() })
        row(R.drawable.ic_check, a.getString(R.string.review_advance, r.period + step)) { advance(r) }
        row(R.drawable.ic_text, a.getString(R.string.review_edit)) { editReview(r) }
        row(R.drawable.ic_delete, a.getString(R.string.common_delete)) { deleteReview(r) }
        dlg = Sheet(a).title("${r.tag} · ${a.getString(R.string.review_period, r.period)}").content(col)
            .action(a.getString(R.string.common_cancel)).show()
    }

    private fun advance(r: LibReview) =
        write("回顾后推", r.documentId) { s ->
            s.upsertReviews(listOf(r.copy(period = r.period + step, reviews = r.reviews + 1, last = nowIso())))
        }

    private fun editReview(r: LibReview) {
        val title = docTitles()[r.documentId].orEmpty()
        reviewSheet(a.getString(R.string.review_edit_title), "$title · ${pageHint(r.page)}", r.tag, r.period, r.remark) { tag, period, remark ->
            write("改回顾点", r.documentId) { s -> s.upsertReviews(listOf(r.copy(tag = tag, period = period, remark = remark))) }
        }
    }

    private fun deleteReview(r: LibReview) {
        val ws = workspace() ?: return
        val title = docTitles()[r.documentId].orEmpty()
        val head = r.remark.ifEmpty { r.tag }.take(24)
        write("删回顾点", r.documentId) { s -> Trash.deleteAnnotations(s, ws, r.documentId, title, listOf(r.id), "“$head”") }
    }

    private fun reviewSheet(title: String, hint: String, tag: String, period: Int, remark: String, done: (String, Int, String) -> Unit) {
        val tagEdit = PadPanels.inputBox(a, a.getString(R.string.review_tag_hint)).apply {
            setText(tag)
            setSelection(text.length)
        }
        val tags = knownTags()
        val periodEdit = PadPanels.inputBox(a, a.getString(R.string.review_period_hint)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("${period.coerceAtLeast(1)}")
        }
        val remarkEdit = PadPanels.inputBox(a, a.getString(R.string.review_remark_hint)).apply {
            isSingleLine = false
            minLines = 2
            maxLines = 5
            setText(remark)
        }
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(tagEdit)
            if (tags.isNotEmpty()) {
                // 用过的标签点一下就填上（连着给同一个标签加好几处不用重打）
                addView(chips(tags) { t -> tagEdit.setText(t); tagEdit.setSelection(t.length) })
            }
            addView(Ui.groupTitle(a, a.getString(R.string.review_period_title), 12))
            addView(periodEdit)
            addView(Ui.groupTitle(a, a.getString(R.string.review_remark_title), 12))
            addView(remarkEdit)
        }
        Sheet(a).title(title).subtitle(hint).content(col)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_save), primary = true) {
                val t = tagEdit.text.toString().trim()
                if (t.isEmpty()) { toast(R.string.review_tag_required); return@action }
                val p = periodEdit.text.toString().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
                done(t, p, remarkEdit.text.toString().trim())
            }.show()
    }

    private fun knownTags(): List<String> =
        ((allReviews ?: emptyList()) + reviewsByDoc.values.flatten()).map { it.tag }.filter { it.isNotEmpty() }
            .distinct().sorted()

    // ---------- 抽屉「回顾」页 ----------

    fun buildReviewPage(box: LinearLayout) {
        val docId = currentDoc()
        if (wholeWorkspace && allReviews == null) {
            val q = queue() ?: return
            box.addView(Ui.body(a, a.getString(R.string.review_loading)))
            q.submit("读全部回顾点", { s -> s.allReviews() }) { list ->
                allReviews = list
                drawer.refreshReviews()
            }
            return
        }
        val source = if (wholeWorkspace) allReviews.orEmpty() else docId?.let { reviewsByDoc[it] }.orEmpty()
        val titles = docTitles()
        val shown = source.filter { (tagFilter == null || it.tag == tagFilter) && (periodFilter == null || it.period == periodFilter) }
            .sortedWith(compareBy<LibReview>({ it.period }, { titles[it.documentId].orEmpty() }).then(LibReview.ORDER))

        box.addView(PadPanels.iconRow(a, R.drawable.ic_plus, a.getString(R.string.review_add_here)) { addReviewHere() })
        // 筛选：范围 / 标签 / 期号 / 看完后推几期（各一枚按钮，点开选）
        val tags = source.map { it.tag }.distinct().sorted()
        val periods = source.filter { tagFilter == null || it.tag == tagFilter }.map { it.period }.distinct().sorted()
        box.addView(chipRow(listOf(
            filterChip(a.getString(if (wholeWorkspace) R.string.review_scope_all else R.string.review_scope_doc)) {
                pick(a.getString(R.string.review_scope_title),
                    listOf(a.getString(R.string.review_scope_doc), a.getString(R.string.review_scope_all)),
                    if (wholeWorkspace) 1 else 0) { wholeWorkspace = it == 1; drawer.refreshReviews() }
            },
            filterChip(tagFilter ?: a.getString(R.string.review_all_tags)) {
                val opts = listOf(a.getString(R.string.review_all_tags)) + tags
                pick(a.getString(R.string.review_tag_title), opts, tagFilter?.let { tags.indexOf(it) + 1 } ?: 0) {
                    tagFilter = if (it == 0) null else tags[it - 1]; drawer.refreshReviews()
                }
            },
            filterChip(periodFilter?.let { a.getString(R.string.review_period, it) } ?: a.getString(R.string.review_all_periods)) {
                val opts = listOf(a.getString(R.string.review_all_periods)) + periods.map { a.getString(R.string.review_period, it) }
                pick(a.getString(R.string.review_period_title), opts, periodFilter?.let { periods.indexOf(it) + 1 } ?: 0) {
                    periodFilter = if (it == 0) null else periods[it - 1]; drawer.refreshReviews()
                }
            },
            filterChip(a.getString(R.string.review_step, step)) {
                val choices = listOf(1, 2, 3, 5, 7, 10)
                pick(a.getString(R.string.review_step_title), choices.map { a.getString(R.string.review_step, it) }, choices.indexOf(step)) {
                    step = choices[it]; drawer.refreshReviews()
                }
            },
        )))
        if (shown.isEmpty()) {
            box.addView(Ui.body(a, a.getString(if (source.isEmpty()) R.string.review_empty else R.string.review_no_match)))
            return
        }
        for (r in shown) box.addView(reviewRow(r, if (wholeWorkspace) titles[r.documentId] else null), LinearLayout.LayoutParams(-1, -2))
    }

    private fun reviewRow(r: LibReview, docTitle: String?): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val text = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 4), Ui.dp(a, 8))
            addView(TextView(a).apply {
                this.text = "${r.tag} · ${a.getString(R.string.review_period, r.period)}"
                textSize = 15f
                setTextColor(Ui.onSurface(a))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (r.remark.isNotEmpty()) addView(TextView(a).apply {
                this.text = r.remark
                textSize = 13f
                setTextColor(Ui.onSurface(a))
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(a).apply {
                this.text = subline(r, docTitle)
                textSize = 12f
                setTextColor(Ui.onVariant(a))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            setOnClickListener {
                if (r.documentId == currentDoc()) jump(r.page, r.frac, r.tag) else openAt(r.documentId, r.page, r.frac)
                drawer.close()
            }
            setOnLongClickListener { reviewMenu(r); true }
        }
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.iconButton(a, R.drawable.ic_check, a.getString(R.string.review_advance, r.period + step), Ui.accent(a)) { advance(r) })
        addView(Ui.iconButton(a, R.drawable.ic_more, a.getString(R.string.review_more), Ui.onVariant(a)) { reviewMenu(r) })
    }

    private fun subline(r: LibReview, docTitle: String?): String {
        var s = pageHint(r.page)
        if (docTitle != null) s = "$docTitle · $s"
        if (r.reviews > 0) s += " · " + a.getString(R.string.review_times, r.reviews)
        return s
    }

    // ---------- 小零件 ----------

    private fun pageHint(page: Int) = a.getString(R.string.review_page, page + 1)

    private fun newId() = java.util.UUID.randomUUID().toString().uppercase()

    private fun toast(id: Int) = Toast.makeText(a, a.getString(id), Toast.LENGTH_SHORT).show()

    /** 写库 → 重读这一篇（整个工作区那份作废） */
    private fun write(what: String, docId: String, body: (com.xvan.unireader.local.store.LibraryStore) -> Unit) {
        val q = queue() ?: return
        q.submit(what, { s -> runCatching { body(s) }.exceptionOrNull() }) { err ->
            if (err != null) {
                android.util.Log.w("UniReader/TocReview", "$what 失败", err)
                toast(R.string.review_write_failed)
            }
            allReviews = null
            load(docId)
            drawer.refreshReviews()
        }
    }

    private fun filterChip(label: String, onClick: () -> Unit): TextView = TextView(a).apply {
        text = "$label ▾"
        textSize = 13f
        setTextColor(Ui.onSurface(a))
        val h = Ui.dp(a, 6)
        setPadding(Ui.dp(a, 10), h, Ui.dp(a, 10), h)
        background = Ui.rippleOver(a, Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a)), Ui.RADIUS, Ui.onSurface(a))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun chipRow(views: List<View>): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 8))
            for ((i, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = Ui.dp(a, 6) })
        }
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun chips(labels: List<String>, onPick: (String) -> Unit): View =
        chipRow(labels.map { t -> filterChip(t) { onPick(t) }.apply { text = t } })

    /** 一排单选（目录层级用）：选中的那一枚 accent 色 */
    private fun choiceRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val views = ArrayList<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v -> v.setTextColor(if (i == sel) Ui.accent(a) else Ui.onSurface(a)) }
        labels.forEachIndexed { i, t ->
            views += filterChip(t) { onPick(i); paint(i) }.apply { text = t }
        }
        paint(selected)
        return chipRow(views)
    }

    /** 选一项的弹层（当前那项打勾） */
    private fun pick(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        var dlg: AlertDialog? = null
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { i, t ->
            col.addView(PadPanels.iconRow(a, if (i == selected) R.drawable.ic_check else R.drawable.ic_list, t) {
                dlg?.dismiss(); onPick(i)
            })
        }
        dlg = Sheet(a).title(title).content(col).action(a.getString(R.string.common_cancel)).show()
    }

    companion object {
        /** 整份替换自建目录（库队列线程上调）：旧的先整份进回收站（归档失败就抛、什么都不改），再写新的。Agent `set_toc` 也走这里 */
        fun replaceToc(s: com.xvan.unireader.local.store.LibraryStore, ws: File, docId: String, docTitle: String, items: List<LibTocEntry>): Int {
            val old = s.customToc(docId)
            if (old.isNotEmpty()) {
                Trash.deleteAnnotations(s, ws, docId, docTitle, old.map { it.id }, trashTitle(docTitle))
            }
            s.replaceCustomToc(docId, emptyList(), items)
            return old.size
        }

        private fun trashTitle(docTitle: String) = "《$docTitle》的目录"
    }
}
