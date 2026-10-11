package com.xvan.unireader.shared.exercise

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

/**
 * 一个习题标签的样子（两模式共用，`../EXERCISE-PLAN.md §10.1`；对应 Mac `ExerciseTabView` + 练习页 + 列表页）：
 * 顶上一条（范围名 + 进度 + 筛选 + 全部重做 + 练习 / 列表），下面是练习页或列表页。
 *
 * 只认 [ExDeckView]，操作全部交给 [ExHost]（模式1 写本机库 / 模式2 发给 Mac），改完由宿主把新的一份 [show] 回来——
 * 界面自己不推算对错、不改题目状态。只有两样是界面自己的：多选题还没交的勾选、这道题从什么时候开始看的（记用时）。
 * 「练习 / 列表」也是各端各自的（模式2 与 Mac 同一组题时只同步第几题，不同步这个）。
 */
class ExerciseScreen(private val a: Activity, private val host: ExHost) {

    private var deck = ExDeckView.EMPTY
    private var listMode = false

    /** 当前题还没交的勾选（多选）；换题 / 重做后清空。 */
    private val picked = LinkedHashSet<String>()
    /** 已经交了、还在等宿主回结果的那道题（空 = 没有）。 */
    private var submittedFor = ""
    /** 当前显示的是哪一题、作答状态（换了就清勾选、重新计时）。 */
    private var shownKey = ""
    private var shownAt = SystemClock.uptimeMillis()
    /** 当前题的内容（内容没变就不重建选项，免得公式重排一遍）。 */
    private var builtFor: ExCard? = null
    /** 原题图开着吗（按题记：带图的题默认开）。 */
    private val originalShown = HashMap<String, Boolean>()
    private var originalFor = ""

    private fun dp(v: Int) = Ui.dp(a, v)
    private fun s(id: Int, vararg args: Any) = a.getString(id, *args)

    // ---------- 顶栏 ----------

    private val titleView = TextView(a).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.onSurface(a))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val progressView = TextView(a).apply {
        textSize = 12f
        setTextColor(Ui.onSurface(a))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /**
     * 只看没做过（开关，开着时高亮；同 Mac）。哪一组题在抽屉里选，顶栏只管做题进度；
     * 错题本那一支不显示（错题都做过，[ExDeckView.noFilter]）。
     */
    private val unansweredBtn = pill(s(R.string.ex_unanswered_only)) { host.setFilter(if (deck.filter == 1) 0 else 1) }
    /** 全部重做（同 Mac）：当前这一组（范围 + 筛选）都当没答过，从第一题练起。没有显示着作答的题时灰着。 */
    private val redoAllBtn = pill(s(R.string.ex_redo_all)) {
        if (!deck.hasShown) return@pill
        host.redo(deck.cards.map { it.id }, 0)
        setListMode(false)
    }
    private val practiceTab = pill(s(R.string.ex_practice)) { setListMode(false) }
    private val listTab = pill(s(R.string.ex_list)) { setListMode(true) }

    private val header = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(12), dp(8))
        setBackgroundColor(Ui.surface(a))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(progressView)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        for (v in listOf(redoAllBtn, unansweredBtn)) {
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        addView(practiceTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(listTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
    }

    // ---------- 练习页 ----------

    private val metaView = TextView(a).apply {
        textSize = 13f
        setTextColor(Ui.onSurface(a))
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val starBtn = Ui.iconButton(a, R.drawable.ic_star, s(R.string.ex_star)) { cur()?.let { host.setStarred(it.id, !it.starred) } }
    private val origBtn = Ui.iconButton(a, R.drawable.ic_image, s(R.string.ex_original)) { toggleOriginal() }
    private val srcBtn = Ui.iconButton(a, R.drawable.ic_doc, s(R.string.ex_open_page)) { cur()?.let { host.openSource(it.id) } }
    private val stemView = mathView(17f)
    private val origImage = ImageView(a).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(520)
        visibility = View.GONE
    }
    private val origNote = Ui.body(a, "").apply { visibility = View.GONE }
    private val optionsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val submitBtn = Ui.button(a, s(R.string.ex_submit), filled = true) { submit() }
    private val resultIcon = ImageView(a)
    private val resultText = TextView(a).apply {
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.onSurface(a))
    }
    private val resultRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(resultIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        addView(resultText, LinearLayout.LayoutParams(0, -2, 1f))
    }
    private val historyNote = note()
    private val agentNote = note()
    private val markNote = note()
    private val explanationTitle = TextView(a).apply {
        text = s(R.string.ex_explanation)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.onSurface(a))
    }
    private val explanationView = mathView(15f)
    private val retryBtn = Ui.button(a, s(R.string.ex_try_again)) { cur()?.let { host.redo(listOf(it.id), deck.index) } }
    private val removeWrongBtn = Ui.button(a, s(R.string.ex_remove_mistake)) { cur()?.let { host.removeFromMistakes(it.id) } }
    private val actionsRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(retryBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(removeWrongBtn, LinearLayout.LayoutParams(-2, -2))
    }
    private val prevBtn = Ui.button(a, s(R.string.ex_prev)) { go(deck.index - 1) }
    private val nextBtn = Ui.button(a, s(R.string.ex_next)) { go(deck.index + 1) }
    private val emptyView = Ui.body(a, "").apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(80), dp(24), dp(24))
    }

    private val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(32))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(metaView, LinearLayout.LayoutParams(0, -2, 1f))
                addView(starBtn)
                addView(origBtn)
                addView(srcBtn)
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(stemView, lp(top = 10))
        addView(origImage, lp(top = 12))
        addView(origNote, lp(top = 8))
        addView(optionsBox, lp(top = 14))
        addView(submitBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        addView(resultRow, lp(top = 16))
        addView(historyNote, lp(top = 4).apply { marginStart = dp(30) })
        addView(agentNote, lp(top = 10))
        addView(markNote, lp(top = 8))
        addView(explanationTitle, lp(top = 16))
        addView(explanationView, lp(top = 6))
        addView(actionsRow, lp(top = 16))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(prevBtn)
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
                addView(nextBtn)
            },
            lp(top = 28),
        )
    }

    /** 正文那一栏限宽居中（平板横屏上一行太长不好读，同 Mac 760pt）。 */
    private val columnHost = CenterColumn(a, dp(760)).apply {
        addView(column)
        addView(emptyView)
    }

    /**
     * 左右划翻题（竖向滚动照常；横向明显占优、够快、够远才算）。在 [practice] 的分发入口上看全部触摸——
     * 从选项上起手的划动也认；认成翻题的那一下把抬起改成取消，免得同时点中手指下面那个选项。
     */
    private val flip = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val dx = e2.x - (e1?.x ?: e2.x)
            if (abs(vx) < 1200f || abs(vx) < abs(vy) * 1.8f || abs(dx) < dp(80)) return false
            go(deck.index + if (vx < 0) 1 else -1)
            return true
        }
    })

    private val practice = object : ScrollView(a) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (flip.onTouchEvent(ev) && ev.actionMasked == MotionEvent.ACTION_UP) {
                val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(c)
                c.recycle()
                return true
            }
            return super.dispatchTouchEvent(ev)
        }
    }.apply {
        isFillViewport = true
        addView(columnHost, FrameLayout.LayoutParams(-1, -2))
    }

    // ---------- 列表页 ----------

    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val adapter = ListAdapter()
    private val listView = ListView(a).apply {
        divider = null
        adapter = this@ExerciseScreen.adapter
        setOnItemClickListener { _, _, pos, _ ->
            val c = deck.cards.getOrNull(pos) ?: return@setOnItemClickListener
            if (selecting) { toggleSelected(c.id); return@setOnItemClickListener }
            host.go(pos)
            setListMode(false)
        }
        setOnItemLongClickListener { _, _, pos, _ ->
            val c = deck.cards.getOrNull(pos) ?: return@setOnItemLongClickListener false
            if (!selecting) { selecting = true; selected.clear() }
            toggleSelected(c.id)
            true
        }
    }
    private val selCount = TextView(a).apply {
        textSize = 14f
        setTextColor(Ui.onSurface(a))
    }
    private val selActions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val selBar = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(8), dp(6))
        setBackgroundColor(Ui.container(a))
        addView(selCount, LinearLayout.LayoutParams(0, -2, 1f))
        addView(android.widget.HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false; addView(selActions) })
        visibility = View.GONE
    }
    private val listPane = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(selBar, LinearLayout.LayoutParams(-1, -2))
        visibility = View.GONE
    }

    /** 整个习题页（宿主把它铺在内容区里，让开顶栏 + 标签页栏）。 */
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.surface(a))
        isClickable = true   // 别让触摸穿到下面的画布
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(Ui.divider(a))
        addView(
            FrameLayout(a).apply {
                addView(practice, FrameLayout.LayoutParams(-1, -1))
                addView(listPane, FrameLayout.LayoutParams(-1, -1))
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
    }

    init {
        applyMode()
    }

    // ---------- 对外 ----------

    /** 宿主交来新的一份（打开 / 作答 / 换筛选 / 库里刷新之后）。 */
    fun show(d: ExDeckView) {
        deck = d
        selected.retainAll(d.cards.map { it.id }.toSet())
        if (selected.isEmpty()) selecting = false
        refreshHeader()
        refreshPractice()
        adapter.notifyDataSetChanged()
        refreshSelBar()
    }

    /** 还在读（模式1 读库 / 模式2 等 Mac 回 `exDeck`）：只摆标题，正文空着——别先闪一句「这里还没有题」。 */
    fun showLoading(title: String) {
        show(ExDeckView.EMPTY.copy(title = title))
        emptyView.text = ""
        progressView.text = ""
    }

    /** 只换了第几题（模式2 的 `exIndex`）。 */
    fun showIndex(i: Int) {
        if (i == deck.index || i !in deck.cards.indices) return
        show(deck.copy(index = i))
    }

    /** 宿主切走 / 关标签：收掉浮着的东西。 */
    fun onHidden() {
        if (selecting) { selecting = false; selected.clear(); refreshSelBar(); adapter.notifyDataSetChanged() }
    }

    // ---------- 顶栏 ----------

    private fun refreshHeader() {
        titleView.text = deck.title
        val parts = arrayListOf(s(R.string.ex_count, deck.cards.size))
        if (deck.done > 0) parts += s(R.string.ex_round, deck.done, deck.right)
        progressView.text = parts.joinToString(" · ")
        unansweredBtn.visibility = if (deck.noFilter) View.GONE else View.VISIBLE
        paintPill(unansweredBtn, deck.filter == 1)
        redoAllBtn.isEnabled = deck.hasShown
        redoAllBtn.alpha = if (deck.hasShown) 1f else 0.4f
    }

    private fun setListMode(on: Boolean) {
        if (listMode == on) return
        listMode = on
        if (!on) onHidden()
        applyMode()
        if (on) listView.post { listView.setSelection(deck.index.coerceAtLeast(0)) }
    }

    private fun applyMode() {
        practice.visibility = if (listMode) View.GONE else View.VISIBLE
        listPane.visibility = if (listMode) View.VISIBLE else View.GONE
        paintPill(practiceTab, !listMode)
        paintPill(listTab, listMode)
    }

    // ---------- 练习页 ----------

    private fun cur(): ExCard? = deck.current

    private fun refreshPractice() {
        val c = cur()
        if (c == null) {
            column.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            emptyView.text = if (deck.filter == 1) s(R.string.ex_empty_unanswered) else s(R.string.ex_empty)
            builtFor = null
            shownKey = ""
            return
        }
        column.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        val key = c.id + "#" + c.judged
        if (key != shownKey) {
            if (c.id != shownKey.substringBefore('#')) practice.scrollTo(0, 0)
            shownKey = key
            picked.clear()
            submittedFor = ""
            shownAt = SystemClock.uptimeMillis()
        }
        val b = builtFor
        if (b == null || b.id != c.id || b.stem != c.stem || b.options != c.options || b.multiple != c.multiple ||
            b.explanation != c.explanation) {
            MathText.bind(stemView, c.stem)
            MathText.bind(explanationView, c.explanation)
            buildOptions(c)
        }
        builtFor = c

        val meta = arrayListOf(s(R.string.ex_meta_index, deck.index + 1, deck.cards.size))
        if (c.number.isNotEmpty()) meta += s(R.string.ex_meta_number, c.number)
        if (c.page > 0) meta += s(R.string.ex_meta_page, c.page)
        meta += if (c.multiple) s(R.string.ex_multiple) else s(R.string.ex_single)
        if (c.section.isNotEmpty()) meta += c.section
        if (c.chapter.isNotEmpty()) {
            val last = c.chapter.substringAfterLast(" › ")
            if (last != c.section) meta += last
        }
        metaView.text = meta.joinToString(" · ")

        starBtn.imageTintList = ColorStateList.valueOf(if (c.starred) Ui.accent(a) else Ui.onSurface(a))
        starBtn.contentDescription = if (c.starred) s(R.string.ex_unstar) else s(R.string.ex_star)
        for (v in listOf(origBtn, srcBtn)) {
            v.isEnabled = c.hasOriginal
            v.alpha = if (c.hasOriginal) 1f else 0.35f
        }
        val showOrig = c.hasOriginal && (originalShown[c.id] ?: c.figure)
        origBtn.imageTintList = ColorStateList.valueOf(if (showOrig) Ui.accent(a) else Ui.onSurface(a))
        loadOriginal(c, showOrig)

        val judged = c.judged
        val chosen = if (judged) c.chosen.map { it.toString() }.toSet() else picked
        val truth = c.answer.map { it.toString() }.toSet()
        for (i in 0 until optionsBox.childCount) {
            val row = optionsBox.getChildAt(i) as OptionRow
            row.setState(
                selected = row.key in chosen,
                locked = judged,
                mark = if (judged && c.correct != ExJudge.NONE) {
                    when {
                        row.key in truth -> ExJudge.RIGHT
                        row.key in chosen -> ExJudge.WRONG
                        else -> ExJudge.NONE
                    }
                } else ExJudge.NONE,
            )
        }
        submitBtn.visibility = if (!judged && c.multiple) View.VISIBLE else View.GONE
        submitBtn.isEnabled = picked.isNotEmpty()
        submitBtn.alpha = if (picked.isNotEmpty()) 1f else 0.4f

        resultRow.visibility = if (judged) View.VISIBLE else View.GONE
        if (judged) {
            when (c.correct) {
                ExJudge.RIGHT -> setIcon(resultIcon, R.drawable.ic_check, Ui.col(a, R.color.success))
                ExJudge.WRONG -> setIcon(resultIcon, R.drawable.ic_close, Ui.col(a, R.color.danger))
                else -> setIcon(resultIcon, R.drawable.ic_info, Ui.onSurface(a))
            }
            resultText.text = resultLine(c)
        }
        historyNote.visibility = if (c.shown == ExShown.PRACTICED) View.VISIBLE else View.GONE
        if (c.shown == ExShown.PRACTICED) {
            val counts = s(R.string.ex_tries, c.tries, c.rights)
            historyNote.text = if (c.lastAt > 0) formatWhen(c.lastAt) + " · " + counts else counts
        }
        agentNote.visibility = if (judged && c.agentAnswer) View.VISIBLE else View.GONE
        agentNote.text = s(R.string.ex_agent_note)
        // 显示的就是书上那条时，结果那行已经说了，不重复
        val showMark = judged && c.shown != ExShown.BOOK && c.marked.isNotEmpty()
        markNote.visibility = if (showMark) View.VISIBLE else View.GONE
        if (showMark) {
            val verdict = when (c.markedCorrect) {
                ExJudge.RIGHT -> s(R.string.ex_right)
                ExJudge.WRONG -> s(R.string.ex_wrong)
                else -> s(R.string.ex_not_judged)
            }
            markNote.text = s(R.string.ex_mark_note, c.marked, verdict)
        }
        val hasExp = c.explanation.isNotEmpty()
        explanationTitle.visibility = if (judged && hasExp) View.VISIBLE else View.GONE
        explanationView.visibility = if (judged && hasExp) View.VISIBLE else View.GONE
        retryBtn.visibility = if (judged) View.VISIBLE else View.GONE
        removeWrongBtn.visibility = if (c.wrong) View.VISIBLE else View.GONE
        actionsRow.visibility = if (judged || c.wrong) View.VISIBLE else View.GONE
        setEnabled(prevBtn, deck.index > 0)
        setEnabled(nextBtn, deck.index < deck.cards.size - 1)
    }

    /** 结果那行：这一轮刚答的 / 上次作答 / 书上的作答，各说各的（同 Mac `resultText`）。 */
    private fun resultLine(c: ExCard): String {
        val mine = c.chosen
        val truth = c.answer
        return when (c.shown) {
            ExShown.PRACTICED -> when (c.correct) {
                ExJudge.RIGHT -> s(R.string.ex_last_correct, mine)
                ExJudge.WRONG -> s(R.string.ex_last_wrong, mine, truth)
                else -> s(R.string.ex_last_none, mine)
            }
            ExShown.BOOK -> when (c.correct) {
                ExJudge.RIGHT -> s(R.string.ex_book_correct, mine)
                ExJudge.WRONG -> s(R.string.ex_book_wrong, mine, truth)
                else -> s(R.string.ex_book_none, mine)
            }
            else -> when (c.correct) {
                ExJudge.RIGHT -> s(R.string.ex_result_correct)
                ExJudge.WRONG -> s(R.string.ex_result_wrong, truth)
                else -> s(R.string.ex_result_none)
            }
        }
    }

    /** 「上次作答」那次的时间（日期 + 时刻，按本机语言）。 */
    private fun formatWhen(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    private fun buildOptions(c: ExCard) {
        optionsBox.removeAllViews()
        for ((k, t) in c.options) {
            optionsBox.addView(OptionRow(k, t, c.multiple), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun toggle(key: String) {
        val c = cur() ?: return
        if (c.judged || submittedFor == c.id) return   // 交了、等宿主回结果的那一会儿不再收点选
        if (!c.multiple) {
            picked.clear()
            picked += key
            submit()
            return
        }
        if (!picked.remove(key)) picked += key
        refreshPractice()
    }

    private fun submit() {
        val c = cur() ?: return
        if (c.judged || picked.isEmpty() || submittedFor == c.id) return
        val keys = picked.sorted().joinToString("")
        submittedFor = c.id   // 模式2 要等 Mac 回一趟：这期间连点不许记两次作答
        host.submit(c.id, keys, SystemClock.uptimeMillis() - shownAt)
        // 判完的样子等宿主交回新的一份；单选这一下先把选中态画出来，别让人以为没点上
        refreshPractice()
    }

    private fun go(i: Int) {
        if (i !in deck.cards.indices || i == deck.index) return
        host.go(i)
    }

    private fun toggleOriginal() {
        val c = cur() ?: return
        if (!c.hasOriginal) return
        originalShown[c.id] = !(originalShown[c.id] ?: c.figure)
        refreshPractice()
    }

    private fun loadOriginal(c: ExCard, show: Boolean) {
        origImage.visibility = if (show && origImage.drawable != null && originalFor == c.id) View.VISIBLE else View.GONE
        if (!show) { origNote.visibility = View.GONE; return }
        if (originalFor == c.id) return
        originalFor = c.id
        origImage.setImageDrawable(null)
        origImage.visibility = View.GONE
        origNote.visibility = View.GONE
        val px = min(1600, maxOf(600, a.resources.displayMetrics.widthPixels))
        host.loadOriginal(c.id, px) { bmp: Bitmap? ->
            if (originalFor != c.id) return@loadOriginal
            if (bmp == null) {
                origNote.text = s(R.string.ex_original_failed)
                origNote.visibility = View.VISIBLE
                originalFor = ""   // 再点一次「原题」可以重试
                return@loadOriginal
            }
            origImage.setImageBitmap(bmp)
            origImage.visibility = View.VISIBLE
        }
    }

    /** 一个选项：左边圆（单选）/ 方（多选）的字母键 + 选项正文 + 判完的对错标记。点整行都算点选。 */
    private inner class OptionRow(val key: String, text: String, multiple: Boolean) : LinearLayout(a) {
        private val badge = TextView(a).apply {
            this.text = key
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        private val body = mathView(16f)
        private val mark = ImageView(a)
        private val radius = if (multiple) 8 else Ui.PILL
        private var locked = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            setOnClickListener { if (!locked) toggle(key) }
            addView(badge, LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
            addView(body, LayoutParams(0, -2, 1f))
            addView(mark, LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
            MathText.bind(body, text)
        }

        fun setState(selected: Boolean, locked: Boolean, mark: Int) {
            this.locked = locked
            val fg = if (selected) Ui.col(a, R.color.on_accent) else Ui.onSurface(a)
            badge.setTextColor(fg)
            badge.background = Ui.round(if (selected) Ui.accent(a) else Ui.surface(a), radius, a,
                if (selected) null else Ui.outline(a))
            when (mark) {
                ExJudge.RIGHT -> setIcon(this.mark, R.drawable.ic_check, Ui.col(a, R.color.success))
                ExJudge.WRONG -> setIcon(this.mark, R.drawable.ic_close, Ui.col(a, R.color.danger))
                else -> this.mark.setImageDrawable(null)
            }
        }
    }

    // ---------- 列表页 ----------

    private fun toggleSelected(id: String) {
        if (!selected.remove(id)) selected += id
        if (selected.isEmpty()) selecting = false
        adapter.notifyDataSetChanged()
        refreshSelBar()
    }

    private fun refreshSelBar() {
        selBar.visibility = if (selecting && listMode) View.VISIBLE else View.GONE
        if (!selecting) return
        selCount.text = s(R.string.ex_selected, selected.size)
        selActions.removeAllViews()
        val cards = deck.cards.filter { it.id in selected }
        // 重做：只算正显示着作答的那几道（同 Mac「重做这 N 道题」），从其中第一道练起
        val shown = cards.filter { it.judged }
        if (shown.isNotEmpty()) {
            selActions.addView(Ui.textButton(a, s(R.string.ex_redo_n, shown.size), primary = true) {
                val first = deck.cards.indexOfFirst { it.id == shown[0].id }
                host.redo(shown.map { it.id }, first.coerceAtLeast(0))
                selecting = false
                selected.clear()
                setListMode(false)
            })
        }
        if (host.canSetChapter) {
            selActions.addView(Ui.textButton(a, s(R.string.ex_set_chapter)) {
                host.setChapter(cards.map { it.id })
            })
        }
        if (cards.size == 1) {
            val c = cards[0]
            selActions.addView(Ui.textButton(a, if (c.starred) s(R.string.ex_unstar) else s(R.string.ex_star)) {
                host.setStarred(c.id, !c.starred)
            })
            if (c.wrong) selActions.addView(Ui.textButton(a, s(R.string.ex_remove_mistake)) { host.removeFromMistakes(c.id) })
            if (c.hasOriginal) selActions.addView(Ui.textButton(a, s(R.string.ex_open_page)) { host.openSource(c.id) })
        }
        selActions.addView(Ui.textButton(a, a.getString(R.string.common_cancel)) {
            selecting = false
            selected.clear()
            adapter.notifyDataSetChanged()
            refreshSelBar()
        })
    }

    /** 列表「我的答案」那一格：同练习页那一份（`A（错）`），没在显示作答 = 空。 */
    private fun mineText(c: ExCard): String {
        if (!c.judged) return ""
        val verdict = when (c.correct) {
            ExJudge.RIGHT -> s(R.string.ex_right)
            ExJudge.WRONG -> s(R.string.ex_wrong)
            else -> s(R.string.ex_not_judged)
        }
        return s(R.string.ex_mine, c.chosen, verdict)
    }

    private inner class ListAdapter : BaseAdapter() {
        override fun getCount() = deck.cards.size
        override fun getItem(position: Int) = deck.cards[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? RowHolder) ?: RowHolder()
            val c = deck.cards[position]
            h.check.visibility = if (selecting) View.VISIBLE else View.GONE
            h.check.isChecked = c.id in selected
            h.no.text = c.number.ifEmpty { "${position + 1}" }
            h.stem.text = c.plainStem
            val meta = arrayListOf<String>()
            if (c.chapter.isNotEmpty()) meta += c.chapter
            if (c.page > 0) meta += s(R.string.ex_meta_page, c.page)
            meta += if (c.multiple) s(R.string.ex_multiple) else s(R.string.ex_single)
            if (c.tries > 0) meta += s(R.string.ex_tries_short, c.tries, c.rights)
            h.meta.text = meta.joinToString(" · ")
            h.mine.text = mineText(c)
            h.mine.setTextColor(
                when (c.correct) {
                    ExJudge.RIGHT -> Ui.col(a, R.color.success)
                    ExJudge.WRONG -> Ui.col(a, R.color.danger)
                    else -> Ui.onSurface(a)
                },
            )
            h.star.visibility = if (c.starred) View.VISIBLE else View.GONE
            h.root.background = if (position == deck.index) {
                Ui.round(Ui.col(a, R.color.accent_container), Ui.RADIUS, a)
            } else {
                null
            }
            return h.root
        }
    }

    private inner class RowHolder {
        val check = CheckBox(a).apply { isClickable = false; isFocusable = false }
        val no = TextView(a).apply {
            textSize = 13f
            setTextColor(Ui.onSurface(a))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val stem = TextView(a).apply {
            textSize = 15f
            setTextColor(Ui.onSurface(a))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = TextView(a).apply {
            textSize = 12f
            setTextColor(Ui.onSurface(a))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val mine = TextView(a).apply { textSize = 13f; maxLines = 1 }
        val star = ImageView(a).apply {
            setImageResource(R.drawable.ic_star)
            imageTintList = ColorStateList.valueOf(Ui.accent(a))
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(check, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            addView(no, LinearLayout.LayoutParams(dp(52), -2))
            addView(
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(stem)
                    addView(meta)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(mine, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            addView(star, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })
            tag = this@RowHolder
        }
    }

    // ---------- 小件 ----------

    private fun mathView(size: Float) = TextView(a).apply {
        textSize = size
        setTextColor(Ui.onSurface(a))
        setLineSpacing(Ui.dpf(a, 4f), 1f)
    }

    private fun note() = TextView(a).apply {
        textSize = 13f
        setTextColor(Ui.onSurface(a))
        setLineSpacing(Ui.dpf(a, 3f), 1f)
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun pill(label: String, onClick: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        isClickable = true
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { onClick() }
        paintPill(this, false)
    }

    private fun paintPill(v: TextView, on: Boolean) {
        val fg = if (on) Ui.accent(a) else Ui.onSurface(a)
        val bg = if (on) Ui.col(a, R.color.accent_container) else Ui.container(a)
        v.setTextColor(fg)
        v.background = Ui.rippleOver(a, Ui.round(bg, Ui.PILL, a), Ui.PILL, fg)
    }

    private fun setIcon(v: ImageView, res: Int, tint: Int) {
        v.setImageResource(res)
        v.imageTintList = ColorStateList.valueOf(tint)
    }

    private fun setEnabled(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    /** 子项按 `min(宽, maxW)` 量、水平居中、从顶上摆（练习页那一栏限宽用）。 */
    private class CenterColumn(c: android.content.Context, private val maxW: Int) : ViewGroup(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cw = min(w, maxW)
            var h = 0
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v.visibility == GONE) continue
                v.measure(
                    MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                )
                h = maxOf(h, v.measuredHeight)
            }
            setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val v = getChildAt(i)
                if (v.visibility == GONE) continue
                val x = (r - l - v.measuredWidth) / 2
                v.layout(x, 0, x + v.measuredWidth, v.measuredHeight)
            }
        }
    }
}
