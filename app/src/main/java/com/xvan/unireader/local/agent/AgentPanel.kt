package com.xvan.unireader.local.agent

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibAgentContext
import com.xvan.unireader.local.store.LibAgentMemory
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.exercise.MathText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.xvan.unireader.local.store.Iso
import java.util.Date
import kotlin.math.min

/**
 * 阅读界面右侧的 Agent 面板（`../docs/ANDROID-AGENT-PLAN.md §6`，用户 2026-11-15 定「阅读界面右侧抽屉」）。
 * 对应 Mac Inspector 的「Agent」页，只是平板上没有 Inspector，做成贴右边满高的一栏。
 *
 * **不盖遮罩**：面板外面照常能翻书、写字（边看边聊）；整个覆盖层不吃触摸，只有面板本身吃。
 * 一轮正常答完记进对话记录（`agent_turn`，同 Mac §22）；记忆在每段新对话的第一句话里自动带上（同 Mac §21）；
 * ⋯ 菜单存 / 带上 Agent 上下文（同 Mac §12）。
 */
class AgentPanel(private val a: Activity, private val host: AgentHost) {

    companion object {
        const val TAG = "UniReader/Agent"
        private const val MAX_W = 440
        private const val WIDTH_RATIO = 0.92f
        /** 悬浮小窗：默认宽、最小宽高（dp） */
        private const val DEFAULT_FW = 400
        private const val MIN_FW = 280
        private const val MIN_FH = 240
        /** 输入框（一行时）与发送键的高度（dp） */
        private const val INPUT_H = 48
        /** 小窗右下角改大小把手的边长（dp）；输入行在小窗时右 / 下多让出这么多 */
        private const val GRIP = 28
        private const val PREFS = "agent"
        private const val PREF_FLOAT = "float"
        private const val PREF_COLLAPSED = "floatCollapsed"
        private const val PREF_FX = "floatX"
        private const val PREF_FY = "floatY"
        private const val PREF_FW = "floatW"
        private const val PREF_FH = "floatH"
        private const val PREF_SYS = "sysOverlay"
    }

    private val scope = MainScope()
    private var engine: AgentEngine? = null
    /** 引擎是按哪份设置建的（设置改了就重建、对话清空） */
    private var engineKey = ""
    private var job: Job? = null
    /** 这段对话还没发过消息（第一句带上记忆） */
    private var fresh = true

    private val transcript = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val scroll = ScrollView(a).apply {
        isFillViewport = true
        addView(transcript, FrameLayout.LayoutParams(-1, -2))
    }
    private val input = EditText(a).apply {
        hint = a.getString(R.string.agent_input_hint)
        textSize = 15f
        setTextColor(Ui.onSurface(a))
        setHintTextColor(Ui.onVariant(a))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 1
        maxLines = 6
        minHeight = dp(INPUT_H)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private val sendBtn = Ui.button(a, a.getString(R.string.agent_send), filled = true) { onSendOrStop() }

    /** 「随下一句话带上的上下文」那一块（输入框上方，同 Mac `AgentQueueView` 只有「移除」的那种）；没有就不显示。 */
    private val handoffBar = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(12), 0)
        visibility = View.GONE
    }

    /** 标题行的 ⋯（菜单锚在它上面） */
    private lateinit var moreBtn: View

    /** 挂着、随下一句话带过去的上下文（`../docs/ANDROID-AGENT-PLAN.md §5.5`） */
    private val handoffs = ArrayList<LibAgentContext>()

    /** 这段对话里说过话没有（「保存这段对话为上下文」要有话可总结） */
    private var hasUserTurn = false

    /** 悬浮小窗的切换键 / 收起键（图标随状态换） */
    private lateinit var floatBtn: android.widget.ImageButton
    private lateinit var collapseBtn: android.widget.ImageButton

    /** 两种摆法（`../docs/ANDROID-AGENT-PLAN.md §9.1`）：false = 贴右边满高一栏，true = 悬浮小窗；记在本机 */
    private var floating = prefs().getBoolean(PREF_FLOAT, false)
    /** 悬浮小窗收成只剩标题行（记在本机） */
    private var collapsed = prefs().getBoolean(PREF_COLLAPSED, false)
    /** 小窗位置与大小，按「可挪范围 / 屏幕」的比例记（转屏 / 分屏后按比例摆回）；-1 = 没记过 */
    private var fx = prefs().getFloat(PREF_FX, -1f)
    private var fy = prefs().getFloat(PREF_FY, -1f)
    private var fw = prefs().getFloat(PREF_FW, -1f)
    private var fh = prefs().getFloat(PREF_FH, -1f)
    /** 最近一次的系统栏 / 输入法边距（覆盖层自己收 insets，两种摆法各用各的） */
    private var barL = 0; private var barT = 0; private var barR = 0; private var barB = 0; private var imeB = 0

    /** 标题行：悬浮小窗时拖它挪位置、双击收起 / 展开 */
    private val titleRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(6), dp(6))
        addView(Ui.title(a, "Agent", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Ui.iconButton(a, R.drawable.ic_plus, a.getString(R.string.agent_new_chat), Ui.onSurface(a)) { newChat() })
        addView(Ui.iconButton(a, R.drawable.ic_more, a.getString(R.string.agent_menu), Ui.onSurface(a)) { showMenu(moreBtn) }.also { moreBtn = it })
        addView(Ui.iconButton(a, R.drawable.ic_win_float, a.getString(R.string.agent_float), Ui.onSurface(a)) { onFloatButton() }.also { floatBtn = it })
        addView(Ui.iconButton(a, R.drawable.ic_chevron_up, a.getString(R.string.agent_collapse), Ui.onSurface(a)) { setCollapsed(!collapsed) }.also { collapseBtn = it })
        addView(Ui.iconButton(a, R.drawable.ic_close, a.getString(R.string.agent_close), Ui.onSurface(a)) { close() })
    }

    /**
     * 输入行：输入框与发送键同为 [INPUT_H] 高、底边对齐（输入框打多了往上长，按钮留在底部）。
     * 横排默认按文字基线对齐——发送键的字会去对输入框第一行、按钮被顶上去，所以关掉。
     * 右边距在小窗时加宽，给右下角的改大小把手让位（[place] 里设）。
     */
    private val inputRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        isBaselineAligned = false
        addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        addView(sendBtn, LinearLayout.LayoutParams(-2, dp(INPUT_H)).apply { marginStart = dp(8) })
    }

    /** 标题行以下的全部（悬浮小窗收起时整块藏掉，只留标题行） */
    private val body = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.divider(a))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(Ui.divider(a))
        addView(handoffBar, LinearLayout.LayoutParams(-1, -2))
        addView(
            inputRow,
            LinearLayout.LayoutParams(-1, -2),
        )
    }

    private val panel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true   // 吃掉面板上的触摸，别穿到下面的画布
        addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        elevation = Ui.dpf(a, 8f)
    }

    /** 悬浮小窗右下角的改大小把手（只在小窗展开时有） */
    private val resizeGrip = android.widget.ImageView(a).apply {
        setImageResource(R.drawable.ic_resize)
        imageTintList = android.content.res.ColorStateList.valueOf(Ui.onVariant(a))
        contentDescription = a.getString(R.string.agent_resize)
        // 图标贴右下角画 16dp：弧半径 ≈ 9dp、离两边 ≈ 3dp，与小窗 12dp 圆角大致同心
        setPadding(dp(GRIP - 16), dp(GRIP - 16), 0, 0)
        elevation = Ui.dpf(a, 9f)   // 压在面板（8）之上
    }

    /** 整个覆盖层（加进 Activity 的 root，满屏但不吃触摸），面板贴右边或浮在上面。 */
    val view: FrameLayout = FrameLayout(a).apply {
        visibility = View.GONE
        addView(panel, FrameLayout.LayoutParams(1, 1))
        addView(resizeGrip, FrameLayout.LayoutParams(dp(GRIP), dp(GRIP)))
        // 系统栏 + 输入法：edge-to-edge 下窗口不会因为键盘缩小，自己按 ime 的高度让开
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, ins ->
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
            barL = bars.left; barT = bars.top; barR = bars.right; barB = bars.bottom; imeB = ime.bottom
            place()
            ins
        }
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) post { place() }
        }
    }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        view.visibility = View.VISIBLE
        ViewCompat.requestApplyInsets(view)
        place()
        if (transcript.childCount == 0) showEmptyState()
    }

    /** 关上之后（宿主刷顶栏那颗键的开关态） */
    var onClosed: () -> Unit = {}

    fun close() {
        hideKeyboard()
        leaveSystemWindow()
        view.visibility = View.GONE
        onClosed()
    }

    // ---------------- 两种摆法（§9.1） ----------------

    private fun prefs() = a.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun setFloating(on: Boolean) {
        if (floating == on) return
        floating = on
        prefs().edit().putBoolean(PREF_FLOAT, on).apply()
        refreshFloatButtons()
        place()
    }

    private fun setCollapsed(on: Boolean) {
        if (collapsed == on) return
        collapsed = on
        if (on) hideKeyboard()
        prefs().edit().putBoolean(PREF_COLLAPSED, on).apply()
        refreshFloatButtons()
        place()
    }

    private fun refreshFloatButtons() {
        val sys = sysWin != null
        floatBtn.setImageResource(if (floating || sys) R.drawable.ic_win_dock else R.drawable.ic_win_float)
        floatBtn.contentDescription = a.getString(
            when { sys -> R.string.agent_back_to_app; floating -> R.string.agent_dock; else -> R.string.agent_float },
        )
        floatBtn.tooltipText = floatBtn.contentDescription
        val asFloat = floating || sys
        collapseBtn.visibility = if (asFloat) View.VISIBLE else View.GONE
        collapseBtn.setImageResource(if (collapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
        collapseBtn.contentDescription = a.getString(if (collapsed) R.string.agent_expand else R.string.agent_collapse)
        collapseBtn.tooltipText = collapseBtn.contentDescription
        body.visibility = if (asFloat && collapsed) View.GONE else View.VISIBLE
    }

    /** 切换键：App 内 = 一栏 ↔ 小窗；系统悬浮窗里 = 回到 App */
    private fun onFloatButton() {
        if (sysWin != null) backToApp() else setFloating(!floating)
    }

    /**
     * 小窗所在的「空间」：App 内 = 覆盖层（让开系统栏 + 输入法），系统悬浮窗 = 整块屏幕（同样让开）。
     * [w] / [h] = 整个空间的宽高（大小按它的比例记），[area] = 小窗能待的区域，四周留 8dp。
     */
    private inner class Space {
        val w: Int; val h: Int
        val area: android.graphics.Rect
        init {
            val m = dp(8)
            if (sysWin != null) {
                val s = screenSize()
                w = s.x; h = s.y
                area = android.graphics.Rect(sysBars.left + m, sysBars.top + m, w - sysBars.right - m, h - maxOf(sysBars.bottom, sysImeB) - m)
            } else {
                w = view.width; h = view.height
                area = android.graphics.Rect(barL + m, barT + m, w - barR - m, h - maxOf(barB, imeB) - m)
            }
        }
        val l get() = area.left
        val t get() = area.top
        val aw get() = maxOf(1, area.width())
        val ah get() = maxOf(1, area.height())
    }

    /** 小窗此刻的左上角（App 内 = 覆盖层坐标，系统悬浮窗 = 屏幕坐标）；拖动从这里起算 */
    private var curL = 0
    private var curT = 0

    /** 按当前摆法摆面板（App 内的覆盖层量好之后才有尺寸，量之前什么都不做） */
    private fun place() {
        val sp = Space()
        if (sp.w <= 0 || sp.h <= 0) return
        val gp = resizeGrip.layoutParams as FrameLayout.LayoutParams
        val sys = sysWin
        val asFloat = floating || sys != null
        // 把手在右下角：右边让出半个把手就碰不到发送键，底边不用让（上下留白一样）
        inputRow.setPadding(dp(12), dp(12), dp(if (asFloat) 12 + GRIP / 2 else 12), dp(12))
        if (!floating && sys == null) {
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            panel.background = android.graphics.drawable.ColorDrawable(Ui.surface(a))
            panel.clipToOutline = false
            panel.setPadding(0, barT, barR, maxOf(barB, imeB))
            lp.width = min((sp.w * WIDTH_RATIO).toInt(), dp(MAX_W)); lp.height = -1
            lp.gravity = Gravity.END; lp.leftMargin = 0; lp.topMargin = 0
            panel.layoutParams = lp
            resizeGrip.visibility = View.GONE
            return
        }
        panel.background = Ui.round(Ui.surface(a), Ui.RADIUS, a, Ui.outline(a))
        panel.clipToOutline = true
        panel.setPadding(0, 0, 0, 0)
        val minW = min(dp(MIN_FW), sp.aw); val minH = min(dp(MIN_FH), sp.ah)
        // 记的是相对整个空间的比例；没记过 = 默认大小、贴右下
        val w = (if (fw > 0f) fw * sp.w else min(dp(DEFAULT_FW).toFloat(), sp.w * 0.9f)).toInt().coerceIn(minW, sp.aw)
        val fullH = (if (fh > 0f) fh * sp.h else sp.h * 0.55f).toInt().coerceIn(minH, maxOf(minH, sp.ah))
        val shownH = if (collapsed) titleHeight() else fullH
        // 位置：可挪范围里的比例（0 = 最左 / 最上，1 = 最右 / 最下）。输入法弹出时范围下沿上移，按比例自然上移；比例本身不改
        val left = sp.l + ((if (fx >= 0f) fx else 1f) * (sp.aw - w)).toInt().coerceIn(0, maxOf(0, sp.aw - w))
        val top = sp.t + ((if (fy >= 0f) fy else 1f) * (sp.ah - shownH)).toInt().coerceIn(0, maxOf(0, sp.ah - shownH))
        curL = left; curT = top
        resizeGrip.visibility = if (collapsed) View.GONE else View.VISIBLE
        if (sys != null) {
            // 系统悬浮窗：窗口本身就是小窗那么大，面板铺满窗口，把手贴窗口右下
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            lp.width = -1; lp.height = if (collapsed) -2 else -1; lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = 0; lp.topMargin = 0
            panel.layoutParams = lp
            gp.gravity = Gravity.BOTTOM or Gravity.END; gp.leftMargin = 0; gp.topMargin = 0
            resizeGrip.layoutParams = gp
            sysParams.x = left; sysParams.y = top
            sysParams.width = w; sysParams.height = if (collapsed) android.view.WindowManager.LayoutParams.WRAP_CONTENT else fullH
            runCatching { wm.updateViewLayout(sys, sysParams) }
            return
        }
        val lp = panel.layoutParams as FrameLayout.LayoutParams
        lp.width = w; lp.height = if (collapsed) -2 else fullH
        lp.gravity = Gravity.TOP or Gravity.START; lp.leftMargin = left; lp.topMargin = top
        panel.layoutParams = lp
        gp.gravity = Gravity.TOP or Gravity.START
        gp.leftMargin = left + w - gp.width; gp.topMargin = top + shownH - gp.height
        resizeGrip.layoutParams = gp
    }

    private fun titleHeight(): Int {
        if (titleRow.height > 0) return titleRow.height
        titleRow.measure(View.MeasureSpec.makeMeasureSpec(dp(MIN_FW), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        return titleRow.measuredHeight
    }

    /** 把小窗现在的位置 / 大小折回比例并记下（拖动 / 改大小时调；[h] = null 不改记住的高度） */
    private fun rememberGeometry(left: Int, top: Int, w: Int, h: Int?) {
        val sp = Space()
        if (sp.w <= 0 || sp.h <= 0) return
        val shownH = h ?: (if (collapsed) titleHeight() else panel.height)
        fx = if (sp.aw - w > 0) ((left - sp.l).toFloat() / (sp.aw - w)).coerceIn(0f, 1f) else 0f
        fy = if (sp.ah - shownH > 0) ((top - sp.t).toFloat() / (sp.ah - shownH)).coerceIn(0f, 1f) else 0f
        fw = w.toFloat() / sp.w
        if (h != null) fh = h.toFloat() / sp.h
        prefs().edit().putFloat(PREF_FX, fx).putFloat(PREF_FY, fy).putFloat(PREF_FW, fw).putFloat(PREF_FH, fh).apply()
    }

    /** 标题行拖动挪位置、双击收起；右下角把手改大小。只在小窗（App 内或系统悬浮窗）时起作用。 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installDrag() {
        val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop
        fun asFloat() = floating || sysWin != null
        val tap = android.view.GestureDetector(a, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if (asFloat()) setCollapsed(!collapsed)
                return asFloat()
            }
        })
        var downX = 0f; var downY = 0f; var startL = 0; var startT = 0; var dragging = false
        titleRow.setOnTouchListener { _, e ->
            if (!asFloat()) return@setOnTouchListener false
            tap.onTouchEvent(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startL = curL; startT = curT; dragging = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) dragging = true
                    if (dragging) {
                        val sp = Space()
                        val shownH = if (collapsed) titleHeight() else panel.height
                        val l = (startL + dx.toInt()).coerceIn(sp.l, maxOf(sp.l, sp.l + sp.aw - panel.width))
                        val t = (startT + dy.toInt()).coerceIn(sp.t, maxOf(sp.t, sp.t + sp.ah - shownH))
                        rememberGeometry(l, t, panel.width, null)   // 拖动不改记住的高度（输入法弹着时面板是被压矮的）
                        place()
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> dragging = false
            }
            true   // 标题行上的按钮自己吃触摸，到不了这里；空白处都归拖动
        }
        var startW = 0; var startH = 0
        resizeGrip.setOnTouchListener { _, e ->
            if (!asFloat() || collapsed) return@setOnTouchListener false
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startW = panel.width; startH = panel.height; startL = curL; startT = curT
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val sp = Space()
                    val w = (startW + (e.rawX - downX).toInt()).let { val lo = min(dp(MIN_FW), sp.aw); it.coerceIn(lo, maxOf(lo, sp.l + sp.aw - startL)) }
                    val h = (startH + (e.rawY - downY).toInt()).let { val lo = min(dp(MIN_FH), sp.ah); it.coerceIn(lo, maxOf(lo, sp.t + sp.ah - startT)) }
                    rememberGeometry(startL, startT, w, h)
                    place()
                }
            }
            true
        }
    }

    // ---------------- 系统悬浮窗（§9.2） ----------------

    /** 用应用级的 WindowManager 加悬浮窗（不挂在阅读界面的窗口上：阅读界面退到后台时它的窗口不显示） */
    private val wm: android.view.WindowManager =
        a.applicationContext.getSystemService(android.view.WindowManager::class.java)

    /** 系统悬浮窗那个窗口的根（只在离开 App 后挂着；null = 面板在阅读界面里） */
    private var sysWin: FrameLayout? = null
    private val sysParams = android.view.WindowManager.LayoutParams(
        1, 1,
        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // 平时不抢焦点（不挡住下面那个 App 的键盘）、窗口外的触摸照常给下面；点外面时收到 ACTION_OUTSIDE 放回焦点
        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        android.graphics.PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "UniReader Agent"
    }
    /** 屏幕上的系统栏边距（悬浮窗不铺满屏，拿不到整屏的 insets，按屏幕的算） */
    private var sysBars = android.graphics.Rect()
    /** 悬浮窗里打字时输入法的高度（屏幕坐标，0 = 没弹） */
    private var sysImeB = 0

    /** 用户开了「离开 App 时悬浮在其他应用上」（本机）。权限要另外在系统设置里给。 */
    private var sysWanted = prefs().getBoolean(PREF_SYS, false)

    /** 本 App 里此刻「已开始」（可见）的 Activity：一个都没有 = 用户离开了 App */
    private val started = HashSet<Activity>()
    private val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(act: Activity) {
            started.add(act)
            if (sysWin != null) leaveSystemWindow()   // 回到 App（哪个界面都算）：收回阅读界面里
        }
        override fun onActivityStopped(act: Activity) {
            started.remove(act)
            if (started.isEmpty() && !act.isChangingConfigurations) maybeEnterSystemWindow()
        }
        override fun onActivityDestroyed(act: Activity) { started.remove(act) }
        override fun onActivityCreated(act: Activity, b: android.os.Bundle?) {}
        override fun onActivityResumed(act: Activity) {}
        override fun onActivityPaused(act: Activity) {}
        override fun onActivitySaveInstanceState(act: Activity, b: android.os.Bundle) {}
    }

    private fun canOverlay() = android.provider.Settings.canDrawOverlays(a)

    /** ⋯ 菜单那一项：开 = 记下，没权限就带去系统设置页；关 = 记下 */
    private fun toggleSysWanted() {
        sysWanted = !sysWanted
        prefs().edit().putBoolean(PREF_SYS, sysWanted).apply()
        if (!sysWanted) return
        if (canOverlay()) {
            android.widget.Toast.makeText(a, R.string.agent_sys_on, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(a, R.string.agent_sys_need_perm, android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                a.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + a.packageName),
                    ),
                )
            }.onFailure { Log.w(TAG, "打不开悬浮窗权限设置页", it) }
        }
    }

    /** 离开 App 了：面板开着、用户要、权限给了 → 把面板挪进系统悬浮窗 */
    private fun maybeEnterSystemWindow() {
        if (sysWin != null || !isOpen || !sysWanted || a.isFinishing || a.isDestroyed) return
        if (!canOverlay()) { Log.i(TAG, "要悬浮窗但没给「显示在其他应用上层」权限，不挂"); return }
        hideKeyboard()
        val root = object : FrameLayout(a) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) { setSysFocusable(false); return false }
                return super.dispatchTouchEvent(ev)
            }
        }
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        (resizeGrip.parent as? android.view.ViewGroup)?.removeView(resizeGrip)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        root.addView(resizeGrip, FrameLayout.LayoutParams(dp(GRIP), dp(GRIP), Gravity.BOTTOM or Gravity.END))
        // 输入法：只在它弹着、且盖住了窗口时才知道有多高（窗口没被盖住时量不到，沿用上一次）
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            if (!ins.isVisible(WindowInsetsCompat.Type.ime())) {
                if (sysImeB != 0) { sysImeB = 0; root.post { place() } }
            } else {
                val overlap = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
                if (overlap > 0) {
                    val screenH = screenSize().y
                    sysImeB = screenH - (sysParams.y + root.height) + overlap
                    root.post { place() }
                }
            }
            ins
        }
        sysBars = screenBars()
        sysImeB = 0
        sysParams.flags = sysParams.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        sysWin = root
        runCatching { wm.addView(root, sysParams) }.onFailure {
            Log.w(TAG, "挂系统悬浮窗失败", it)
            sysWin = null
            root.removeAllViews()
            view.addView(panel, 0, FrameLayout.LayoutParams(1, 1))
            view.addView(resizeGrip, FrameLayout.LayoutParams(dp(GRIP), dp(GRIP)))
            refreshFloatButtons(); place()
            return
        }
        Log.i(TAG, "离开 App：Agent 对话挂到系统悬浮窗")
        refreshFloatButtons()
        place()
    }

    /** 收回阅读界面（回到 App / 关掉 / 阅读界面要销毁了） */
    private fun leaveSystemWindow() {
        val root = sysWin ?: return
        hideKeyboard()
        sysWin = null
        runCatching { wm.removeViewImmediate(root) }
        root.removeAllViews()
        view.addView(panel, 0, FrameLayout.LayoutParams(1, 1))
        view.addView(resizeGrip, FrameLayout.LayoutParams(dp(GRIP), dp(GRIP)))
        refreshFloatButtons()
        ViewCompat.requestApplyInsets(view)
        place()
        Log.i(TAG, "回到 App：Agent 对话收回阅读界面")
        pendingInApp?.let { f -> pendingInApp = null; view.post { if (!a.isFinishing) f() } }
    }

    /** 悬浮窗上的「回到 UniReader」：把这个任务调回前台（随后 onActivityStarted 收回面板） */
    private fun backToApp() {
        val am = a.getSystemService(android.app.ActivityManager::class.java)
        runCatching { am.moveTaskToFront(a.taskId, 0) }.onFailure { Log.w(TAG, "调回前台失败", it) }
    }

    /** 悬浮窗要不要拿焦点：点输入框时拿（才能弹输入法打字），点窗口外面放回去 */
    private fun setSysFocusable(on: Boolean) {
        val root = sysWin ?: return
        val f = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val was = sysParams.flags and f == 0
        if (was == on) return
        if (!on) hideKeyboard()
        sysParams.flags = if (on) sysParams.flags and f.inv() else sysParams.flags or f
        runCatching { wm.updateViewLayout(root, sysParams) }
        if (on) input.post {
            input.requestFocus()
            a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun installSysFocus() {
        input.setOnTouchListener { _, e ->
            if (sysWin != null && e.actionMasked == android.view.MotionEvent.ACTION_UP) setSysFocusable(true)
            false
        }
    }

    /** 整块屏幕的大小（像素） */
    private fun screenSize(): android.graphics.Point =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            android.graphics.Point(dm.widthPixels, dm.heightPixels)
        }

    /** 屏幕上的系统栏边距（老系统拿不到就按阅读界面最近一次收到的算） */
    private fun screenBars(): android.graphics.Rect =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val i = wm.currentWindowMetrics.windowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
            )
            android.graphics.Rect(i.left, i.top, i.right, i.bottom)
        } else android.graphics.Rect(barL, barT, barR, barB)

    // 放在全部属性之后：Kotlin 按文本顺序初始化，提前跑会拿到还没初始化的回调对象
    init {
        installDrag()
        installSysFocus()
        // 建面板时阅读界面若已可见，先记上（否则它的 onStop 会被当成「最后一个界面也不见了」之外的事件漏掉）
        if ((a as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == true) started.add(a)
        a.application.registerActivityLifecycleCallbacks(lifecycle)
        refreshFloatButtons()
    }

    /** 换工作区：对话是挂在工作区上的（工具读的是它），清空重来。 */
    fun workspaceChanged() {
        job?.cancel()
        engine?.close(); engine = null
        engineKey = ""
        conversationId = newConversationId()
        hasUserTurn = false
        handoffs.clear()
        refreshHandoffBar()
        clearTranscript()
    }

    /** 这段对话的 id（`agent_turn.conversation_id`）：新对话 / 换引擎 / 换工作区换一个。 */
    private var conversationId = newConversationId()

    private fun newConversationId() = java.util.UUID.randomUUID().toString().uppercase()

    /**
     * 一轮正常答完记一行（同 Mac `AgentChat.recordTurn`：停止 / 出错的不记）。只记用户看得见的那句话和回复正文，
     * 上下文块、工具输出、思考过程都不记。两边都空就不记。
     */
    private fun recordTurn(userText: String, reply: String) {
        val u = userText.trim()
        val r = reply.trim()
        if (u.isEmpty() && r.isEmpty()) return
        val q = host.storeQueue() ?: return
        val now = com.xvan.unireader.local.store.nowIso()
        val t = com.xvan.unireader.local.store.LibAgentTurn(
            newConversationId(), conversationId, AgentToolset.AGENT_NAME, "", u, r, now, now,
        )
        q.submit("Agent 记一轮") { s -> s.insertAgentTurn(t) }   // 失败队列自己记日志
    }

    fun destroy() {
        leaveSystemWindow()
        a.application.unregisterActivityLifecycleCallbacks(lifecycle)
        scope.cancel()
        engine?.close(); engine = null
    }

    // ---------------- 发送 ----------------

    /** 按当前设置拿引擎：设置不全 → 弹设置、返回 null；设置变了 → 重建（对话从头来）。 */
    private fun ensureEngine(): AgentEngine? {
        val s = AgentConfig.load(a)
        if (!s.isComplete) { showSettings(); return null }
        val key = "${s.baseUrl}|${s.chatPath}|${s.model}|${s.apiKey.hashCode()}"
        if (engine == null || key != engineKey) {
            engine?.close()
            engine = AgentEngine(s, AgentToolset(host).tools, systemPrompt())
            engineKey = key
            fresh = true
            hasUserTurn = false
            conversationId = newConversationId()
        }
        return engine
    }

    private fun onSendOrStop() {
        if (job?.isActive == true) { job?.cancel(); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        ensureEngine() ?: return
        input.setText("")
        // 挂着的上下文随这一句带过去（同 Mac `AgentChat.send` 的 handoffs），带完就摘下
        val carried = handoffs.toList()
        handoffs.clear()
        refreshHandoffBar()
        val hidden = carried.map { c ->
            AgentContextText.handoff(c.title, c.summary, c.agent, c.id, Iso.instant(c.updatedAt)?.let { Date.from(it) } ?: Date())
        }
        val notices = carried.map { a.getString(R.string.agent_ctx_carried, it.title) }
        runTurn(text, hidden, notices, onDone = { reply -> recordTurn(text, reply) })
    }

    /**
     * 发一轮：显示用户那句话 → 拼上下文块（[hidden] 放进块里，用户看不到）→ 跑到模型不再调工具。
     * 正常答完调 [onDone]（参数 = 回复正文）；停止 / 出错调 [onFail]。
     */
    private fun runTurn(text: String, hidden: List<String>, notices: List<String>, onDone: (String) -> Unit, onFail: () -> Unit = {}) {
        val eng = engine ?: return
        if (transcript.childCount == 1 && transcript.getChildAt(0).tag == "empty") transcript.removeAllViews()
        addUser(text)
        for (n in notices) transcript.addView(Ui.body(a, n).apply { setPadding(0, dp(6), 0, 0); textSize = 13f })
        hasUserTurn = true
        val firstOfChat = fresh
        fresh = false
        job = scope.launch {
            setBusy(true)
            val turn = TurnView()
            var ok = false
            try {
                val ctx = contextBlock(firstOfChat, hidden)
                eng.send(text, ctx) { ev -> turn.on(ev) }
                ok = true
            } catch (e: CancellationException) {
                turn.note(a.getString(R.string.agent_stopped))
            } catch (e: Exception) {
                Log.w(TAG, "这一轮失败", e)
                turn.error(e.message ?: e.javaClass.simpleName)
            } finally {
                turn.finish()
                setBusy(false)
            }
            if (ok) onDone(turn.reply()) else onFail()
        }
    }

    // ---------------- 上下文（`../docs/ANDROID-AGENT-PLAN.md §5.5`，同 Mac `ACP-AGENT-PLAN.md §12.2`）----------------

    /** 标题行 ⋯：保存这段对话为上下文 / 随下一句话带上 › 已存的几份 / 设置…（系统 `PopupMenu`，同顶栏 ⋯） */
    private fun showMenu(anchor: View) {
        val q = host.storeQueue()
        if (q == null) { popMenu(anchor, emptyList()); return }
        q.submit("Agent 列上下文", { s -> runCatching { s.agentContexts() }.getOrDefault(emptyList()) }) { list -> popMenu(anchor, list) }
    }

    private fun popMenu(anchor: View, contexts: List<LibAgentContext>) {
        val m = android.widget.PopupMenu(a, anchor, Gravity.END)
        val busy = job?.isActive == true
        m.menu.add(0, 1, 0, a.getString(R.string.agent_ctx_save)).isEnabled =
            !busy && hasUserTurn && engine != null && host.storeQueue() != null
        val sub = m.menu.addSubMenu(0, 2, 1, a.getString(R.string.agent_ctx_attach))
        if (contexts.isEmpty()) sub.add(a.getString(R.string.agent_ctx_none)).isEnabled = false
        for ((i, c) in contexts.take(30).withIndex()) {
            sub.add(1, 100 + i, i, c.title).apply {
                isCheckable = true
                isChecked = handoffs.any { it.id == c.id }
                setOnMenuItemClickListener {
                    if (handoffs.any { it.id == c.id }) handoffs.removeAll { it.id == c.id } else handoffs.add(c)
                    refreshHandoffBar()
                    true
                }
            }
        }
        m.menu.add(0, 3, 2, a.getString(R.string.agent_settings_item))
        m.menu.add(0, 4, 3, a.getString(R.string.agent_search_settings_item))
        m.menu.add(0, 5, 4, a.getString(R.string.agent_sys_overlay)).apply { isCheckable = true; isChecked = sysWanted && canOverlay() }
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { saveContext(); true }
                // 设置两张表是阅读界面上的弹层：在系统悬浮窗里先回到 App 再弹
                3 -> { inApp { showSettings() }; true }
                4 -> { inApp { com.xvan.unireader.local.search.SemanticSearchSheet(a, host.workspaceDir).show() }; true }
                5 -> { toggleSysWanted(); true }
                else -> false
            }
        }
        m.show()
    }

    /** 要在阅读界面上弹层的操作：在系统悬浮窗里先回到 App，等面板收回再做 */
    private fun inApp(f: () -> Unit) {
        if (sysWin == null) { f(); return }
        pendingInApp = f
        backToApp()
    }
    private var pendingInApp: (() -> Unit)? = null

    private fun refreshHandoffBar() {
        handoffBar.removeAllViews()
        handoffBar.visibility = if (handoffs.isEmpty()) View.GONE else View.VISIBLE
        if (handoffs.isEmpty()) return
        handoffBar.addView(Ui.body(a, a.getString(R.string.agent_ctx_pending)).apply { textSize = 12f })
        for (c in handoffs.toList()) {
            handoffBar.addView(
                LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.body(a, c.title, variant = false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(Ui.textButton(a, a.getString(R.string.agent_ctx_remove)) {
                        handoffs.removeAll { it.id == c.id }
                        refreshHandoffBar()
                    })
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    /**
     * 保存这段对话为上下文（同 Mac `AgentChat.saveContext`）：发一句可见的请求 + 隐藏的写法要求；这一轮正常答完，
     * 把回复按第一个标题行切成标题与正文存下。停止 / 出错 = 不存。这一轮不记进对话记录（同 Mac）。
     */
    private fun saveContext() {
        if (job?.isActive == true || engine == null || !hasUserTurn) return
        runTurn(
            a.getString(R.string.agent_ctx_request), listOf(AgentContextText.SAVE_INSTRUCTION), emptyList(),
            onDone = { reply -> finishSave(reply) },
            onFail = { notice(a.getString(R.string.agent_ctx_not_saved), error = true) },
        )
    }

    private fun finishSave(reply: String) {
        val (title, summary) = AgentContextText.parse(reply)
        val q = host.storeQueue()
        if (summary.isEmpty() || q == null) { notice(a.getString(R.string.agent_ctx_no_summary), error = true); return }
        val fallback = a.getString(
            R.string.agent_context_untitled,
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(Date()),
        )
        q.submit("Agent 存上下文", { s -> runCatching { AgentToolset.addContext(s, title, summary, AgentToolset.AGENT_NAME, fallback) } }) { r ->
            r.fold(
                { c -> notice(a.getString(R.string.agent_ctx_saved, c.title), error = false) },
                { e -> Log.w(TAG, "上下文没存进去", e); notice(a.getString(R.string.agent_ctx_save_failed), error = true) },
            )
        }
    }

    private fun notice(s: String, error: Boolean) {
        transcript.addView(
            Ui.body(a, s).apply {
                setPadding(0, dp(6), 0, 0)
                textSize = 13f
                if (error) setTextColor(Ui.col(a, R.color.danger))
            },
        )
        scrollToEnd()
    }

    private fun setBusy(b: Boolean) {
        sendBtn.text = a.getString(if (b) R.string.agent_stop else R.string.agent_send)
    }

    private fun newChat() {
        job?.cancel()
        engine?.reset(systemPrompt())
        fresh = true
        hasUserTurn = false
        conversationId = newConversationId()
        clearTranscript()
    }

    private fun systemPrompt(): String = """
        You are the assistant inside UniReader, a PDF and Markdown note reader, running on the user's Android tablet.
        One workspace is one subject: its PDFs, the user's annotations (text notes, highlights, bookmarks), Markdown notes and memories.
        Use the tools to look things up instead of guessing; pages are 1-based. Call get_state first when you need to know what the user is looking at.
        To find something anywhere in the workspace (PDF pages, Markdown notes, annotations, exercises, memories, contexts, earlier conversations), use search first, then follow up with read_pages, read_markdown and the other tools.
        When you learn something worth keeping for later conversations in this workspace (the user's goals, preferences, what they study and how far they got), save it with save_memory: one short fact per memory, in the user's language.
        Earlier conversations in this workspace are saved too: to recall what was discussed, use list_conversations and read_conversation.
        Agent contexts (Agent 上下文) are handoff summaries of earlier conversations, possibly with other agents, saved in this workspace. When the user asks to continue earlier work or mentions a saved context, find it with list_agent_contexts and read it with read_agent_context. The user saves one from the panel's ⋯ menu; call save_agent_context only when the user asks you to.
        Reply in the user's language. Keep answers concise; use Markdown, and $…$ for formulas.
    """.trimIndent()

    /** 每条消息前附带的上下文块（用户看不到）：当前在看什么；新对话第一句再带上记忆。 */
    private suspend fun contextBlock(withMemories: Boolean, extra: List<String> = emptyList()): String {
        val v = host.currentView()
        val sb = StringBuilder("[UniReader context]\n")
        sb.append("Workspace: ").append(host.workspaceName.ifEmpty { "(none)" }).append('\n')
        if (v != null) {
            sb.append("Current tab: ${v.kind} \"${v.title}\" (id ${v.documentId})")
            if (v.kind == "pdf") sb.append(", page ${v.page + 1} of ${v.pageCount}")
            sb.append('\n')
        }
        if (withMemories) {
            val mems: List<LibAgentMemory> = runCatching {
                host.storeQueue()?.read("Agent 读记忆") { it.agentMemories() }
            }.getOrNull().orEmpty()
            if (mems.isNotEmpty()) {
                sb.append("Workspace memories (saved earlier, id prefix in brackets):\n")
                for (m in mems.take(50)) sb.append("- [").append(m.id.take(8)).append("] ").append(m.content).append('\n')
            }
            // 日程（同 Mac `scheduleAgentLine`）：今天几件、过期几件，一行；没有日程就不带
            runCatching {
                host.storeQueue()?.read("Agent 读日程") { st ->
                    val today = com.xvan.unireader.local.schedule.ScheduleDay.today()
                    com.xvan.unireader.local.schedule.ScheduleText.agentLine(
                        today, st.scheduleItems(today, today), st.overdueScheduleItems(today).size, st.schedulePlans().size,
                    )
                }
            }.getOrNull()?.let { sb.append(it).append('\n') }
        }
        for (e in extra) sb.append(e).append('\n')
        sb.append("[/UniReader context]")
        return sb.toString()
    }

    // ---------------- 对话记录 ----------------

    private fun clearTranscript() {
        transcript.removeAllViews()
        if (isOpen) showEmptyState()
    }

    private fun showEmptyState() {
        val s = AgentConfig.load(a)
        val msg = if (s.isComplete) a.getString(R.string.agent_empty, s.model) else a.getString(R.string.agent_need_settings)
        transcript.addView(Ui.body(a, msg).apply {
            tag = "empty"
            setPadding(dp(4), dp(16), dp(4), dp(16))
        })
    }

    private fun addUser(text: String) {
        transcript.addView(
            TextView(a).apply {
                this.text = text
                textSize = 15f
                setTextColor(Ui.onSurface(a))
                background = Ui.round(Ui.container(a), Ui.RADIUS, a)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextIsSelectable(true)
            },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(12); marginStart = dp(40) },
        )
        scrollToEnd()
    }

    private fun scrollToEnd() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    /**
     * 一轮的显示：正文段 / 思考 / 工具调用按到达顺序排下去。流式文字只往当前那段 TextView 里追加
     * （不重建视图）；一段说完（接着调工具，或整轮结束）才按 Markdown 重画一遍。
     */
    private inner class TurnView {
        private var textView: TextView? = null
        private val textBuf = StringBuilder()
        private var thinkView: TextView? = null
        private val thinkBuf = StringBuilder()
        private val toolRows = HashMap<String, TextView>()
        /** 这一轮的回复正文（几段之间空一行），记对话用 */
        private val replyAll = StringBuilder()

        fun reply(): String = replyAll.toString()

        fun on(ev: AgentEvent) {
            when (ev) {
                is AgentEvent.TextDelta -> {
                    endThinking()
                    val tv = textView ?: newText().also { textView = it }
                    textBuf.append(ev.text)
                    tv.text = textBuf
                    replyAll.append(ev.text)
                }
                is AgentEvent.ReasoningDelta -> {
                    val tv = thinkView ?: newThinking().also { thinkView = it }
                    thinkBuf.append(ev.text)
                    tv.text = a.getString(R.string.agent_thinking) + "  " + thinkBuf.takeLast(120).toString().replace('\n', ' ')
                }
                is AgentEvent.ToolStart -> {
                    endThinking(); endText()
                    if (replyAll.isNotEmpty() && !replyAll.endsWith("\n\n")) replyAll.append("\n\n")
                    val row = newToolRow(ev.name, ev.args)
                    toolRows[ev.callId] = row
                }
                is AgentEvent.ToolDone -> {
                    val row = toolRows[ev.callId] ?: return
                    val head = "${if (ev.isError) "✗" else "✓"} ${ev.name}"
                    row.tag = Pair(head, ev.output)
                    row.text = head
                    if (ev.isError) row.setTextColor(Ui.col(a, R.color.danger))
                }
                is AgentEvent.Usage -> {}
            }
            scrollToEnd()
        }

        fun note(s: String) {
            endThinking(); endText()
            transcript.addView(Ui.body(a, s).apply { setPadding(0, dp(6), 0, 0) })
        }

        fun error(s: String) {
            endThinking(); endText()
            transcript.addView(
                TextView(a).apply {
                    text = a.getString(R.string.agent_error, s)
                    textSize = 14f
                    setTextColor(Ui.col(a, R.color.danger))
                    setPadding(0, dp(6), 0, 0)
                    setTextIsSelectable(true)
                },
            )
            scrollToEnd()
        }

        fun finish() { endThinking(); endText() }

        private fun endText() {
            val tv = textView ?: return
            if (textBuf.isNotBlank()) MathText.bind(tv, textBuf.toString())
            tv.setTextIsSelectable(true)
            textView = null
            textBuf.setLength(0)
        }

        private fun endThinking() {
            val tv = thinkView ?: return
            val full = thinkBuf.toString().trim()
            tv.text = a.getString(R.string.agent_thought)
            tv.setOnClickListener {
                // 点一下展开 / 收起整段思考
                tv.text = if (tv.text == a.getString(R.string.agent_thought)) full else a.getString(R.string.agent_thought)
            }
            thinkView = null
            thinkBuf.setLength(0)
        }

        private fun newText(): TextView = TextView(a).apply {
            textSize = 15f
            setTextColor(Ui.onSurface(a))
            setLineSpacing(0f, 1.15f)
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }) }

        private fun newThinking(): TextView = TextView(a).apply {
            textSize = 13f
            setTextColor(Ui.onVariant(a))
            maxLines = 30
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }

        private fun newToolRow(name: String, args: String): TextView = TextView(a).apply {
            val head = "… $name"
            text = head
            tag = Pair(head, "")
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.onVariant(a))
            background = Ui.rippleOver(a, Ui.round(Ui.container(a), Ui.RADIUS, a), Ui.RADIUS, Ui.onSurface(a))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // 点一下展开参数与结果，再点收起
            var open = false
            setOnClickListener {
                val pair = tag as Pair<*, *>
                val h = pair.first as String
                val out = pair.second as String
                open = !open
                text = if (!open) h else buildString {
                    append(h).append("\n").append(args.take(1000))
                    if (out.isNotEmpty()) append("\n\n").append(out.take(4000))
                }
            }
        }.also { transcript.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }
    }

    // ---------------- 设置 ----------------

    fun showSettings() {
        val s = AgentConfig.load(a)
        fun field(hintId: Int, value: String, password: Boolean = false) = EditText(a).apply {
            hint = a.getString(hintId)
            setText(value)
            textSize = 15f
            setSingleLine()
            setTextColor(Ui.onSurface(a))
            setHintTextColor(Ui.onVariant(a))
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val base = field(R.string.agent_base_url_hint, s.baseUrl)
        val key = field(R.string.agent_api_key_hint, s.apiKey, password = true)
        val model = field(R.string.agent_model_hint, s.model)
        val path = field(R.string.agent_chat_path_hint, if (s.chatPath == AgentSettings.DEFAULT_CHAT_PATH) "" else s.chatPath)
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            fun add(labelId: Int, v: View) {
                addView(Ui.groupTitle(a, a.getString(labelId), top = if (childCount == 0) 0 else 12))
                addView(v, LinearLayout.LayoutParams(-1, -2))
            }
            add(R.string.agent_base_url, base)
            add(R.string.agent_api_key, key)
            add(R.string.agent_model, model)
            add(R.string.agent_chat_path, path)
            addView(Ui.tip(a, a.getString(R.string.agent_settings_tip)))
        }
        Sheet(a).title(a.getString(R.string.agent_settings))
            .size(520, 0.85f)
            .content(body)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_save), primary = true) {
                AgentConfig.save(
                    a,
                    AgentSettings(
                        baseUrl = base.text.toString().trim(),
                        apiKey = key.text.toString().trim(),
                        model = model.text.toString().trim(),
                        chatPath = path.text.toString().trim().ifEmpty { AgentSettings.DEFAULT_CHAT_PATH },
                    ),
                )
                if (transcript.childCount == 1 && transcript.getChildAt(0).tag == "empty") {
                    transcript.removeAllViews(); showEmptyState()
                }
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = a.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(v: Int) = Ui.dp(a, v)
}
