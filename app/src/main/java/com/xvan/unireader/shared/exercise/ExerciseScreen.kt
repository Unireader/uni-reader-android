package com.xvan.unireader.shared.exercise

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

/**
 * 一个习题标签的样子（两模式共用，`../docs/EXERCISE-PLAN.md §10.1`；对应 Mac `ExerciseTabView` + 练习页 + 列表页）：
 * 顶上一条（范围名 + 进度 + 筛选 + 全部重做 + 练习 / 列表），下面是练习页或列表页。
 *
 * 只认 [ExDeckView]，操作全部交给 [ExHost]（模式1 写本机库 / 模式2 发给 Mac），改完由宿主把新的一份 [show] 回来——
 * 界面自己不推算对错、不改题目状态。只有两样是界面自己的：多选题还没交的勾选、这道题从什么时候开始看的（记用时）。
 * 「练习 / 列表」也是各端各自的（模式2 与 Mac 同一组题时只同步第几题，不同步这个）。
 *
 * **窄屏（手机，视图宽 < 600dp，2026-11-02 用户：「优化下手机UI的适配，方便刷题」）**换一套紧凑排法，宽窄随视图尺寸变
 * （转屏 / 分屏），切换时只改排法、不重建题目：顶栏「全部重做 / 只看没做过」收进 ⋯，进度另起一行；底部按钮等分宽度、
 * 放不下就把「答对自动下一题」挪到按钮上面；正文边距收窄、选项间距加大。平板（宽屏）照旧。
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

    /**
     * 答对自动下一题（同 Mac 练习页底部那个开关，`../docs/EXERCISE-PLAN.md`「答对自动下一题」一节）：本机记住、默认关、两模式共用一份。
     * 只认**这一轮刚交的**那一次：交卷时记下 [awaitingResult]，宿主交回的那一份里这道题判完了（模式2 是 Mac 回一趟）
     * 才看对错——对了停 [AUTO_NEXT_MS] 让人看到绿勾再翻；之后再重推同一份不会再触发（已经消掉了）。
     */
    private val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var autoNext = prefs.getBoolean(KEY_AUTO_NEXT, false)
    /** 交了、还在等结果的那道题（空 = 没有）。与 [submittedFor] 分开：那个在判完换样子时就清了。 */
    private var awaitingResult = ""
    /** 已经排上「过一会儿翻下一题」的那道题（空 = 没排）。 */
    private var autoNextFor = ""
    private val handler = Handler(Looper.getMainLooper())
    private val autoNextRun = Runnable { fireAutoNext() }

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
    /** 窄屏时「全部重做 / 只看没做过」收进这里（系统 `PopupMenu`）。 */
    private val moreBtn = Ui.iconButton(a, R.drawable.ic_more, s(R.string.ex_more)) { showMore() }.apply {
        visibility = View.GONE
    }
    /**
     * 这一组做过几道（`已做 x / 共 y`，同 Mac 侧栏「做过」的口径：至少一次作答，含书上的作答）。
     * 系统水平进度条，只换成语义色。
     */
    private val progressBar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        progressTintList = ColorStateList.valueOf(Ui.accent(a))
        progressBackgroundTintList = ColorStateList.valueOf(Ui.outline(a))
        visibility = View.GONE
    }
    /** 宽屏：标题 + 进度文字叠在左边；窄屏进度文字挪到第二行（[progressRow]）。 */
    private val titleCol = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(titleView)
        addView(progressView)
    }
    private val headerRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))
        for (v in listOf(redoAllBtn, unansweredBtn)) {
            addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        addView(practiceTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(listTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        addView(moreBtn, LinearLayout.LayoutParams(dp(Ui.TOUCH), dp(Ui.TOUCH)).apply { marginStart = dp(2) })
    }
    private val progressRow = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(progressBar, LinearLayout.LayoutParams(-1, -2))
    }

    private val header = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(12), dp(4))
        setBackgroundColor(Ui.surface(a))
        addView(headerRow, LinearLayout.LayoutParams(-1, -2))
        addView(progressRow, LinearLayout.LayoutParams(-1, -2))
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
    private val stemView = blocks(17f)
    private val origImage = ImageView(a).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxHeight = dp(520)
        visibility = View.GONE
    }
    private val origNote = Ui.body(a, "").apply { visibility = View.GONE }
    private val optionsBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    /** 填空作答区：每空一行「第 N 空 + 输入框」（和 [optionsBox] 互斥）。 */
    private val fillBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    /** 填空：交卷后亮出的参考答案（每空一行，另外认可的写法跟在后面；Markdown + 公式）。 */
    private val fillRef = blocks(14f)
    /** 填空：程序判不了（有对不上的空 / 没答案）时的「答对了 / 答错了」。 */
    private val selfRightBtn = Ui.button(a, s(R.string.ex_self_right)) { cur()?.let { host.judgeExercise(it.id, it.attemptId, true) } }
    private val selfWrongBtn = Ui.button(a, s(R.string.ex_self_wrong)) { cur()?.let { host.judgeExercise(it.id, it.attemptId, false) } }
    private val selfJudgeRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(selfRightBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(selfWrongBtn, LinearLayout.LayoutParams(-2, -2))
    }
    /** 填空没交的输入按题存（翻到别的题再回来还在，同 Mac `ExerciseDeck.drafts`）。 */
    private val fillDrafts = HashMap<String, List<String>>()
    /** 当前填空输入框（按空序；交卷读值 / 「提交」亮不亮看它）。 */
    private var fillEdits = emptyList<android.widget.EditText>()
    private val submitBtn = Ui.button(a, s(R.string.ex_submit), filled = true) { if (cur()?.isFill == true) submitFill() else submit() }
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
    /** 填空 / 主观题：本轮平板上不能作答（`../docs/EXERCISE-PLAN.md §11.6`）。 */
    private val macOnlyNote = note()
    private val explanationTitle = TextView(a).apply {
        text = s(R.string.ex_explanation)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Ui.onSurface(a))
    }
    private val explanationView = blocks(15f)
    private val retryBtn = Ui.button(a, s(R.string.ex_try_again)) { cur()?.let { host.redo(listOf(it.id), deck.index) } }
    private val removeWrongBtn = Ui.button(a, s(R.string.ex_remove_mistake)) { cur()?.let { host.removeFromMistakes(it.id) } }
    private val actionsRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(retryBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        addView(removeWrongBtn, LinearLayout.LayoutParams(-2, -2))
    }
    private val prevBtn = Ui.button(a, s(R.string.ex_prev)) { go(deck.index - 1) }
    private val nextBtn = Ui.button(a, s(R.string.ex_next)) { go(deck.index + 1) }
    /** 答对自动下一题（开关，在上一题 / 下一题中间）。 */
    private val autoNextBox = CheckBox(a).apply {
        text = s(R.string.ex_auto_next)
        textSize = 14f
        setTextColor(Ui.onSurface(a))
        isChecked = autoNext
        setOnCheckedChangeListener { _, on ->
            autoNext = on
            prefs.edit().putBoolean(KEY_AUTO_NEXT, on).apply()
            if (!on) cancelAutoNext()
        }
    }
    private val emptyView = Ui.body(a, "").apply {
        gravity = Gravity.CENTER
        setPadding(dp(24), dp(80), dp(24), dp(24))
    }

    private val column = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(20))
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
        addView(fillBox, lp(top = 14))
        addView(macOnlyNote, lp(top = 4))
        addView(submitBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        addView(resultRow, lp(top = 16))
        addView(fillRef, lp(top = 12))
        addView(selfJudgeRow, lp(top = 8))
        addView(historyNote, lp(top = 4).apply { marginStart = dp(30) })
        addView(agentNote, lp(top = 10))
        addView(markNote, lp(top = 8))
        addView(explanationTitle, lp(top = 16))
        addView(explanationView, lp(top = 6))
        addView(actionsRow, lp(top = 16))
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

    /**
     * 上一题 / 下一题：钉在练习页底部、滚动区外面（同 Mac，用户 2026-10-27：「要固定位置，不能随着内容变化显示高度」——
     * 原先排在题目那一栏最后，每题高度不同，按钮跟着上下跳）。左右与题目那一栏对齐（同样限宽 760 居中）。
     */
    private val navRow = NavRow(a, prevBtn, autoNextBox, nextBtn).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) }
    private val navBar = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.divider(a))
        addView(CenterColumn(a, dp(760)).apply { addView(navRow) }, LinearLayout.LayoutParams(-1, -2))
    }

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

    /** 练习页 = 题目（滚动）+ 底部固定的上一题 / 下一题。 */
    private val practicePane = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(practice, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(navBar, LinearLayout.LayoutParams(-1, -2))
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
            cancelAutoNext()
            awaitingResult = ""
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
    val view: LinearLayout = object : LinearLayout(a) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 宽窄跟着视图尺寸走（转屏 / 分屏 / 手机）：量之前定排法，这一趟量的就是新排法
            setCompact(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight < dp(COMPACT_BELOW_DP))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.surface(a))
        isClickable = true   // 别让触摸穿到下面的画布
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(Ui.divider(a))
        addView(
            FrameLayout(a).apply {
                addView(practicePane, FrameLayout.LayoutParams(-1, -1))
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
        checkAutoNext(d)
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
        progressBar.visibility = View.GONE
    }

    /** 只换了第几题（模式2 的 `exIndex`）。 */
    fun showIndex(i: Int) {
        if (i == deck.index || i !in deck.cards.indices) return
        show(deck.copy(index = i))
    }

    /**
     * 系统栏让位（edge-to-edge，`shared/Insets.kt`）：宿主把左 / 右 / 下三边给进来——底部那条按钮不许压在手势条 / 导航栏下面，
     * 横屏手机的挖孔 / 三键导航栏在侧边。上边由宿主让开顶栏时已经让过了。模式2 整屏沉浸，不调（全 0）。
     */
    fun setSystemInsets(left: Int, right: Int, bottom: Int) {
        if (view.paddingLeft == left && view.paddingRight == right && view.paddingBottom == bottom) return
        view.setPadding(left, 0, right, bottom)
    }

    /** 宿主切走 / 关标签：收掉浮着的东西。 */
    fun onHidden() {
        cancelAutoNext()
        awaitingResult = ""
        if (selecting) { selecting = false; selected.clear(); refreshSelBar(); adapter.notifyDataSetChanged() }
    }

    // ---------- 顶栏 ----------

    private fun refreshHeader() {
        titleView.text = deck.title
        // 整组进度（同 Mac「做过」：至少一次作答，含书上的作答）+ 这一轮
        val total = deck.cards.size
        val doneAll = deck.cards.count { it.tries > 0 }
        val parts = arrayListOf(if (total > 0) s(R.string.ex_progress, doneAll, total) else s(R.string.ex_count, 0))
        if (deck.done > 0) parts += s(R.string.ex_round, deck.done, deck.right)
        progressView.text = parts.joinToString(" · ")
        progressBar.visibility = if (total > 0) View.VISIBLE else View.GONE
        progressBar.max = maxOf(1, total)
        progressBar.progress = doneAll
        refreshHeaderButtons()
        paintPill(unansweredBtn, deck.filter == 1)
        redoAllBtn.isEnabled = deck.hasShown
        redoAllBtn.alpha = if (deck.hasShown) 1f else 0.4f
    }

    /** 「全部重做 / 只看没做过」：宽屏在顶栏上，窄屏收进 ⋯。 */
    private fun refreshHeaderButtons() {
        redoAllBtn.visibility = if (compact) View.GONE else View.VISIBLE
        unansweredBtn.visibility = if (compact || deck.noFilter) View.GONE else View.VISIBLE
        moreBtn.visibility = if (compact) View.VISIBLE else View.GONE
    }

    /** ⋯（窄屏）：全部重做（没有显示着作答的题时灰着）、只看没做过（可勾选；错题本那一支没有）。 */
    private fun showMore() {
        val menu = PopupMenu(a, moreBtn, Gravity.END)
        val redo = menu.menu.add(0, 1, 0, dimIfDisabled(s(R.string.ex_redo_all), deck.hasShown))
        redo.isEnabled = deck.hasShown
        if (!deck.noFilter) {
            menu.menu.add(0, 2, 1, s(R.string.ex_unanswered_only)).apply {
                isCheckable = true
                isChecked = deck.filter == 1
            }
        }
        menu.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> redoAllBtn.performClick()
                2 -> unansweredBtn.performClick()
            }
            true
        }
        menu.show()
    }

    /** 禁用项文字显式染淡（同 `TopBar.showOverflow`：MIUI / OriginOS 只把禁用项淡一点点）。 */
    private fun dimIfDisabled(t: String, enabled: Boolean): CharSequence = if (enabled) t else
        android.text.SpannableString(t).apply {
            val c = (Ui.onSurface(a) and 0x00FFFFFF) or (0x61 shl 24)
            setSpan(android.text.style.ForegroundColorSpan(c), 0, length, 0)
        }

    // ---------- 宽窄两套排法 ----------

    /** 窄屏（手机）排法开着吗（[view] 量尺寸时定，见那里）。 */
    private var compact = false

    private fun setCompact(on: Boolean) {
        if (on == compact) return
        compact = on
        // 进度文字：宽屏叠在标题下面，窄屏单独一行（进度条之上）
        (progressView.parent as? ViewGroup)?.removeView(progressView)
        if (on) progressRow.addView(progressView, 0, LinearLayout.LayoutParams(-1, -2))
        else titleCol.addView(progressView, LinearLayout.LayoutParams(-1, -2))
        header.setPadding(dp(16), dp(8), dp(if (on) 4 else 12), dp(4))
        refreshHeaderButtons()
        val side = dp(if (on) 16 else 20)
        column.setPadding(side, dp(16), side, dp(20))
        for (i in 0 until optionsBox.childCount) styleOption(optionsBox.getChildAt(i))
        navRow.equalButtons = on
        val navSide = dp(if (on) 12 else 20)
        navRow.setPadding(navSide, dp(8), navSide, dp(8))
        for (b in listOf(prevBtn, nextBtn)) b.minimumHeight = if (on) dp(48) else 0
        adapter.notifyDataSetChanged()   // 列表的题号那一列跟着换宽
    }

    /** 选项行的间距与最小高度（窄屏间距加大防误触、整行至少 48dp）。 */
    private fun styleOption(v: View) {
        (v.layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(if (compact) 8 else 6)
        v.minimumHeight = if (compact) dp(48) else 0
    }

    private fun setListMode(on: Boolean) {
        if (listMode == on) return
        listMode = on
        if (!on) onHidden()
        applyMode()
        if (on) listView.post { listView.setSelection(deck.index.coerceAtLeast(0)) }
    }

    private fun applyMode() {
        practicePane.visibility = if (listMode) View.GONE else View.VISIBLE
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
            navBar.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            emptyView.text = if (deck.filter == 1) s(R.string.ex_empty_unanswered) else s(R.string.ex_empty)
            builtFor = null
            shownKey = ""
            return
        }
        column.visibility = View.VISIBLE
        navBar.visibility = View.VISIBLE
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
            b.explanation != c.explanation || b.fillCount != c.fillCount || b.fillAnswers != c.fillAnswers ||
            b.mineBlanks != c.mineBlanks || b.blankMatch != c.blankMatch || b.correct != c.correct ||
            b.judged != c.judged || b.shown != c.shown) {
            bindBlocks(stemView, c.stem, 17f)
            bindBlocks(explanationView, c.explanation, 15f)
            buildOptions(c)
            buildFill(c)
        }
        builtFor = c

        val meta = arrayListOf(s(R.string.ex_meta_index, deck.index + 1, deck.cards.size))
        if (c.number.isNotEmpty()) meta += s(R.string.ex_meta_number, c.number)
        if (c.page > 0) meta += s(R.string.ex_meta_page, c.page)
        meta += kindLabel(c)
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
        val isFill = c.isFill
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
        submitBtn.visibility = when {
            judged -> View.GONE
            isFill || c.multiple -> View.VISIBLE
            else -> View.GONE
        }
        val canSubmit = if (isFill) fillEdits.any { it.text.isNotBlank() } else picked.isNotEmpty()
        submitBtn.isEnabled = canSubmit
        submitBtn.alpha = if (canSubmit) 1f else 0.4f
        macOnlyNote.visibility = if (c.macOnly) View.VISIBLE else View.GONE
        if (c.macOnly) macOnlyNote.text = if (c.kind == 4) s(R.string.ex_mac_only_subjective) else s(R.string.ex_mac_only)

        // 作答区：选择题一排选项，填空一排输入框，主观题（macOnly）都没有
        optionsBox.visibility = if (isFill || c.macOnly) View.GONE else View.VISIBLE
        fillBox.visibility = if (isFill) View.VISIBLE else View.GONE
        fillRef.visibility = if (isFill && c.judged && c.fillAnswers.any { it.isNotEmpty() }) View.VISIBLE else View.GONE
        selfJudgeRow.visibility = if (isFill && c.judged && c.correct == ExJudge.NONE) View.VISIBLE else View.GONE

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
    /** 题型名（练习页那行与列表共用）。 */
    private fun kindLabel(c: ExCard): String = when (c.kind) {
        1 -> s(R.string.ex_multiple)
        3 -> s(R.string.ex_fill)
        4 -> s(R.string.ex_subjective)
        else -> s(R.string.ex_single)
    }

    private fun resultLine(c: ExCard): String {
        if (c.isFill) return when (c.correct) {
            ExJudge.RIGHT -> s(R.string.ex_fill_right)
            ExJudge.WRONG -> s(R.string.ex_fill_wrong)
            else -> if (c.fillAnswers.any { it.isNotEmpty() }) s(R.string.ex_fill_pending) else s(R.string.ex_fill_no_answer)
        }
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
            val row = OptionRow(k, t, c.multiple)
            optionsBox.addView(row, LinearLayout.LayoutParams(-1, -2))
            styleOption(row)
        }
    }

    // ---------- 填空作答区（`../docs/EXERCISE-PLAN.md §11.2`，同 Mac 作答区）----------

    /**
     * 搭 [fillBox] / [fillRef]：没交 = 每空一个输入框（草稿回填、回车跳下一空、最后一空回车交卷）；
     * 交卷后 = 每空的作答锁住显示、对得上的标绿勾；参考答案每空一行（另外认可的写法跟在后面）。
     */
    private fun buildFill(c: ExCard) {
        fillBox.removeAllViews()
        fillEdits = emptyList()
        fillRef.removeAllViews()
        if (!c.isFill) return
        val edits = ArrayList<android.widget.EditText>()
        for (i in 0 until c.fillCount) {
            fillBox.addView(
                fillRow(c, i, edits),
                LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) },
            )
        }
        fillEdits = edits
        if (c.judged) {
            val md = StringBuilder("**" + s(R.string.ex_fill_reference) + "**\n")
            for (i in 0 until c.fillCount) {
                val forms = c.fillAnswers.getOrNull(i).orEmpty()
                val alt = c.fillAccept.getOrNull(i).orEmpty()
                md.append("\n").append(s(R.string.ex_blank_label, i + 1)).append("：")
                if (forms.isEmpty()) md.append("—")
                else {
                    md.append("`").append(forms).append("`")
                    if (alt.isNotEmpty()) md.append("（").append(alt.joinToString(" / ")).append("）")
                }
            }
            bindBlocks(fillRef, md.toString(), 14f)
        }
    }

    /** 一行：「第 N 空」+ 输入框（没交）或锁住的作答 + 对错标记（交卷后）。 */
    private fun fillRow(c: ExCard, i: Int, edits: ArrayList<android.widget.EditText>): View {
        val label = TextView(a).apply {
            text = s(R.string.ex_blank_label, i + 1)
            textSize = 14f
            setTextColor(Ui.onSurface(a))
            gravity = Gravity.CENTER_VERTICAL
        }
        if (c.judged) {
            val mine = c.mineBlanks.getOrElse(i) { "" }
            val body = TextView(a).apply {
                text = mine.ifEmpty { "—" }
                textSize = 16f
                setTextColor(Ui.onSurface(a))
                gravity = Gravity.CENTER_VERTICAL
            }
            val mark = ImageView(a)
            when {
                !c.blankMatch.getOrElse(i) { false } -> setIcon(mark, R.drawable.ic_close, Ui.col(a, R.color.danger))
                mine.isNotEmpty() -> setIcon(mark, R.drawable.ic_check, Ui.col(a, R.color.success))
                else -> setIcon(mark, R.drawable.ic_close, Ui.col(a, R.color.danger))
            }
            return LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(dp(72), dp(40)))
                addView(body, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(mark, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(8) })
            }
        }
        val edits = ArrayList<android.widget.EditText>()
        var ready = false   // 构造期 setText 也会触发回调：那时这条还没进 edits，忽略
        val edit = android.widget.EditText(a).apply {
            textSize = 16f
            setTextColor(Ui.onSurface(a))
            hint = ""
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(dp(12), 0, dp(12), 0)
            background = Ui.round(Ui.container(a), Ui.RADIUS, a, Ui.outline(a))
            setText(fillDrafts[c.id]?.getOrNull(i) ?: "")
            // 回车跳下一空；最后一空回车 = 交卷
            imeOptions = if (i + 1 < c.fillCount) android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            else android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { submitFill(); true }
                else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!ready) return
                    fillDrafts[c.id] = edits.map { it.text.toString() }
                    val any = edits.any { it.text.isNotBlank() }
                    if (submitBtn.isEnabled != any) { submitBtn.isEnabled = any; submitBtn.alpha = if (any) 1f else 0.4f }
                }
            })
        }
        edits += edit
        ready = true
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(dp(72), -2))
            addView(edit, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
    }

    private fun submitFill() {
        val c = cur() ?: return
        if (c.judged || submittedFor == c.id || fillEdits.isEmpty()) return
        val blanks = fillEdits.map { it.text.toString().trim() }
        if (blanks.all { it.isEmpty() }) return
        submittedFor = c.id
        awaitingResult = c.id
        fillDrafts.remove(c.id)
        host.submitBlanks(c.id, blanks, SystemClock.uptimeMillis() - shownAt)
        refreshPractice()
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
        awaitingResult = c.id   // 先记下：模式1 的宿主在 submit 里就同步交回判完的那一份
        host.submit(c.id, keys, SystemClock.uptimeMillis() - shownAt)
        // 判完的样子等宿主交回新的一份；单选这一下先把选中态画出来，别让人以为没点上
        refreshPractice()
    }

    private fun go(i: Int) {
        if (i !in deck.cards.indices || i == deck.index) return
        cancelAutoNext()
        awaitingResult = ""
        host.go(i)
    }

    // ---------- 答对自动下一题 ----------

    /** 宿主交来新的一份时：等着的那道题判完了没有；判对了、开关开着、还停在它上面 → 排上翻页。 */
    private fun checkAutoNext(d: ExDeckView) {
        val id = awaitingResult
        if (id.isEmpty()) return
        val c = d.cards.firstOrNull { it.id == id }
        if (c == null) { awaitingResult = ""; return }
        if (!c.judged) return   // 模式2：Mac 还没回结果（或这一份是别的原因重推的），接着等
        awaitingResult = ""
        if (autoNext && c.shown == ExShown.ROUND && c.correct == ExJudge.RIGHT && d.current?.id == id) {
            handler.removeCallbacks(autoNextRun)
            autoNextFor = id
            handler.postDelayed(autoNextRun, AUTO_NEXT_MS)
        }
    }

    private fun cancelAutoNext() {
        if (autoNextFor.isEmpty()) return
        autoNextFor = ""
        handler.removeCallbacks(autoNextRun)
    }

    /** 到点了：再确认一遍还是那道题、还是这一轮答对的样子（没翻走、没点「再做一次」），不是最后一题才翻。 */
    private fun fireAutoNext() {
        val id = autoNextFor
        autoNextFor = ""
        val c = cur() ?: return
        if (!autoNext || listMode || c.id != id || c.shown != ExShown.ROUND || c.correct != ExJudge.RIGHT) return
        if (deck.index >= deck.cards.size - 1) return
        go(deck.index + 1)
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
        private val body = blocks(16f)
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
            bindBlocks(body, text, 16f)
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
            else -> if (c.isFill) s(R.string.ex_unjudged) else s(R.string.ex_not_judged)
        }
        val mine = if (c.isFill) c.mineBlanks.joinToString(" / ") else c.chosen
        return s(R.string.ex_mine, mine, verdict)
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
            val noW = dp(if (compact) 40 else 52)
            (h.no.layoutParams as? LinearLayout.LayoutParams)?.takeIf { it.width != noW }?.let { it.width = noW; h.no.layoutParams = it }
            h.stem.text = c.plainStem
            val meta = arrayListOf<String>()
            if (c.chapter.isNotEmpty()) meta += c.chapter
            if (c.page > 0) meta += s(R.string.ex_meta_page, c.page)
            meta += kindLabel(c)
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

    /** 一段 Markdown + 公式 的正文（题干 / 解析 / 选项共用）：围栏代码块单独一个横滑不换行的视图，其余整段一个文字视图。 */
    private fun blocks(size: Float) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    private fun bindBlocks(v: LinearLayout, md: String, size: Float) =
        MathText.bindBlocks(v, md, dp(8), { mathView(size) }, { codeBlockView(size, it) })

    /** 一段代码：等宽 + 不换行 + 横向滚动（代码的列对齐靠不换行保住，比如并排两段进程）；底纹区别于正文。 */
    private fun codeBlockView(size: Float, code: String) = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = true   // 让人知道可以横滑
        addView(
            TextView(a).apply {
                text = code.trim('\n')
                textSize = size - 2f
                typeface = Typeface.MONOSPACE
                setTextColor(Ui.onSurface(a))
                setHorizontallyScrolling(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            },
            FrameLayout.LayoutParams(-2, -2),
        )
        background = Ui.round(Ui.container(a), Ui.RADIUS, a)
    }

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

    /**
     * 底部那条：上一题 / 答对自动下一题 / 下一题。
     * 宽屏：两个按钮按原宽贴两边，勾选框居中（同原来）。窄屏（[equalButtons]）：两个按钮等分剩下的宽度，拇指好按。
     * 一行放不下（窄屏 + 长文案，比如英文 360dp）就把勾选框单独挪到按钮上面一行、居中。
     */
    private class NavRow(c: android.content.Context, val prev: View, val box: View, val next: View) : ViewGroup(c) {
        var equalButtons = false
            set(v) { if (field != v) { field = v; requestLayout() } }
        private val gap = (8 * c.resources.displayMetrics.density).toInt()
        private var stacked = false

        init {
            addView(prev); addView(box); addView(next)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val inner = w - paddingLeft - paddingRight
            val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (v in listOf(prev, box, next)) v.measure(un, un)
            stacked = prev.measuredWidth + box.measuredWidth + next.measuredWidth + 2 * gap > inner
            val btnW = when {
                stacked -> (inner - gap) / 2
                equalButtons -> (inner - box.measuredWidth - 2 * gap) / 2
                else -> -1
            }
            if (btnW > 0) {
                for (b in listOf(prev, next)) {
                    b.measure(MeasureSpec.makeMeasureSpec(btnW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b.measuredHeight, MeasureSpec.EXACTLY))
                }
            }
            val row = maxOf(prev.measuredHeight, next.measuredHeight, if (stacked) 0 else box.measuredHeight)
            val h = paddingTop + paddingBottom + row + if (stacked) box.measuredHeight + gap / 2 else 0
            setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            var y = paddingTop
            val row = maxOf(prev.measuredHeight, next.measuredHeight, if (stacked) 0 else box.measuredHeight)
            if (stacked) {
                val bx = (w - box.measuredWidth) / 2
                box.layout(bx, y, bx + box.measuredWidth, y + box.measuredHeight)
                y += box.measuredHeight + gap / 2
            }
            fun place(v: View, x: Int) {
                val vy = y + (row - v.measuredHeight) / 2
                v.layout(x, vy, x + v.measuredWidth, vy + v.measuredHeight)
            }
            place(prev, paddingLeft)
            place(next, w - paddingRight - next.measuredWidth)
            if (!stacked) place(box, (w - box.measuredWidth) / 2)
        }
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

    private companion object {
        /** 视图宽小于它（dp）= 手机 / 窄分屏，用紧凑排法。 */
        const val COMPACT_BELOW_DP = 600
        const val PREFS = "exercise"
        const val KEY_AUTO_NEXT = "autoNext"
        /** 判对之后停多久再翻（同 Mac，让人看到绿勾）。 */
        const val AUTO_NEXT_MS = 800L
    }
}
