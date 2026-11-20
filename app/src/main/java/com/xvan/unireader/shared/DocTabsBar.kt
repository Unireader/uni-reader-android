package com.xvan.unireader.shared

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R

/**
 * 标签页栏，**模式1 与模式2 共用这一份**：`[工作区名 ⌄] │ 文档A ×│ 文档B ×│ +`
 * （`../docs/ANDROID-STANDALONE-PLAN.md §13`）。
 *
 * 它原先只有模式1 有（住在 `local/`），因为模式2「开哪一篇」由 Mac 决定。2026-08-28 用户要
 * 「模式2 也对齐模式1 的 tab」——**但真源仍在 Mac**：模式2 的这条栏是 Mac 已打开窗口
 * （`docs` 广播）的**只读镜像**，点标签 = `selectDoc` 切过去，`+` = 从书库 `openDoc` 让 Mac 新开一个，
 * **不给 ×**（关窗口仍在 Mac 上做，线协议里没有「关」这条，用户 2026-08-28 拍板）。
 * 差异只由 [canClose] 一个开关表达，其余一行代码都不分模式。
 *
 * 形态与顶栏（[TopBar]）同源：扁平、圆角、语义色、系统涟漪，深浅色跟随 `values-night`。
 * 三块从左到右固定语义——
 * - **工作区芯片**（最左，不随标签横滑）：当前工作区名 + `⌄`，**两模式都是点它切工作区**
 *   （2026-09-06 起；此前模式2 点它是开书库）。可切的范围按模式分：模式1 = 本机最近打开与扫到的
 *   `.unrd`，模式2 = **Mac 上已经开着的那几个**（`docs` 广播按 `ws` 分组，见 `../docs/PROTOCOL.md §4.2`）。
 *   它**必须常驻可见**，不能跟着标签一起滑走。
 * - **标签区**（中间，可横滑）：一篇一个芯片，当前那篇是 accent 底 + accent 字；模式1 每个芯片右侧一个 ×。
 * - **加号**（最右，不随标签横滑）：在本工作区里再开一篇。
 *
 * 触摸尺寸这里**破例低于 48dp**（芯片高 [CHIP]、× 命中 [CLOSE]）：顶栏已经占了 57dp，
 * 再叠一条 56dp 的标签栏，两条栏就吃掉 113dp 的阅读区。标签栏是次级导航，且每个动作都有
 * 替代路径（切标签＝也可以从「+」的文档列表点；关标签＝退出后不影响数据），所以这里按可读的
 * 34dp 走，**顶栏那条 48dp 的下限不动**。
 *
 * **手机 / 窄分屏（宽 < 600dp）换一种排法**（用户 2026-11-20：「连一个标签都看不完整」）：
 * `[📁⌄] │ [当前那篇的标题……] [3] +`——工作区芯片只留图标，中间整条给当前那篇，标签数是一个小方块；
 * 点标题或方块打开 [TabOverview]（浏览器那种卡片总览：缩略图、切换、关闭、新开）。宽窄随条的实际宽度切，
 * 转屏 / 分屏拖宽了自动换回横排标签。
 */
class DocTabsBar(private val a: Activity) {

    /**
     * 一个标签的显示信息：标题 + 类型图标 + 一行小字（总览卡片上用，例「第 85 页」「画板」）。
     * 只传标题的老调用（[setTabs]）一律按文档图标显示。
     */
    data class TabInfo(val title: String, val icon: Int = R.drawable.ic_doc, val subtitle: String = "")

    companion object {
        /** 整条高度（不含底部 1px 细线） */
        const val BAR = 44

        /** 标签芯片自身的高度 */
        const val CHIP = 34

        /** × 的命中区（见类注释里的破例说明） */
        const val CLOSE = 34

        /** 单个标签最宽：再宽一屏就放不下第二个了，长标题靠 ellipsize */
        const val TAB_MAX_W = 168
    }

    /** 点工作区芯片 */
    var onSwitchWorkspace: () -> Unit = {}

    /** 点某个标签（下标） */
    var onSelect: (Int) -> Unit = {}

    /** 点某个标签的 ×（下标） */
    var onClose: (Int) -> Unit = {}

    /** 点最右的 + */
    var onAdd: () -> Unit = {}

    /**
     * 标签芯片带不带 ×。模式2 = false：「开着哪几篇」的真源在 Mac，关窗要在 Mac 上做
     * （线协议没有「关」，见类注释）。**必须在第一次 [setTabs] 之前设好**。
     */
    var canClose = true

    /**
     * 逐个标签地问带不带 ×（默认跟 [canClose] 走）。模式2 的标签都是 Mac 的、关不了，唯独平板自己开的
     * 那个习题标签（`../docs/EXERCISE-PLAN.md §10.4`）能关——它用这个开口。
     */
    var closable: (Int) -> Boolean = { canClose }

    /** 总览卡片的缩略图（宿主截的；null = 显示类型图标） */
    var thumbOf: (Int) -> Bitmap? = { null }

    /** 打开总览之前给宿主一个机会截当前那篇的缩略图，截完调 `done`（默认直接开） */
    var beforeOverview: (done: () -> Unit) -> Unit = { it() }

    private var wsName = ""
    private var tabs: List<TabInfo> = emptyList()
    private var active = -1

    /** 现在是不是手机排法（[relayout] 按条的实际宽度定） */
    var compact = false
        private set

    val overview = TabOverview(a).apply {
        onSelect = { i -> this@DocTabsBar.onSelect(i) }
        onClose = { i -> this@DocTabsBar.onClose(i) }
        onAdd = { this@DocTabsBar.onAdd() }
        closable = { i -> this@DocTabsBar.closable(i) }
        thumbOf = { i -> this@DocTabsBar.thumbOf(i) }
    }

    private val wsChip = TextView(a).apply {
        textSize = 13f
        maxLines = 1
        maxWidth = Ui.dp(a, 140)
        ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setTextColor(Ui.col(a, R.color.bar_on))
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_folder, 0, R.drawable.ic_chevron_down, 0)
        compoundDrawablePadding = Ui.dp(a, 6)
        compoundDrawableTintList = ColorStateList.valueOf(Ui.col(a, R.color.bar_on_variant))
        setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 8), 0)
        background = Ui.rippleOver(a, Ui.round(Ui.container(a), Ui.RADIUS, a), Ui.RADIUS, Ui.col(a, R.color.bar_on))
        setOnClickListener { onSwitchWorkspace() }
    }

    private val tabsRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 标签多了就横滑（它是「哪几篇开着」的全集，不该往溢出菜单里收——那样就找不到了） */
    private val scroll = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(tabsRow, LinearLayout.LayoutParams(-2, -1))
    }

    private val addBtn = smallIcon(R.drawable.ic_plus, "打开这个工作区的另一篇文档") { onAdd() }

    private val wsDivider = View(a).apply { setBackgroundColor(Ui.col(a, R.color.bar_outline)) }

    // —— 手机排法的两样：当前那篇（整条）+ 标签数方块，点哪个都开总览 ——
    private val curIcon = ImageView(a)
    private val curTitle = TextView(a).apply {
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val curChip = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0)
        isClickable = true
        addView(curIcon, LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)).apply { marginEnd = Ui.dp(a, 8) })
        addView(curTitle, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { openOverview() }
    }
    private val countText = TextView(a).apply {
        textSize = 12f
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"
        setTextColor(Ui.col(a, R.color.bar_on))
        background = Ui.round(android.graphics.Color.TRANSPARENT, 6, a, Ui.col(a, R.color.bar_on))
    }
    private val countBtn = android.widget.FrameLayout(a).apply {
        isClickable = true
        background = Ui.rippleOver(a, null, Ui.PILL, Ui.col(a, R.color.bar_on))
        addView(countText, android.widget.FrameLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22), Gravity.CENTER))
        setOnClickListener { openOverview() }
    }

    /** 中间那一行：两种排法各自往里摆（[relayout]） */
    private val row = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 4), 0)
    }

    val view: LinearLayout = object : LinearLayout(a) {
        // 宽窄按条自己的宽度切（转屏、分屏拖宽都跟着换）；切换在下一帧做，别在布局过程中改子视图
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val want = w in 1 until Ui.dp(a, 600)
            if (want != compact) post { setCompact(want) }
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true   // 挡住触摸穿透到画布
        setBackgroundColor(Ui.col(a, R.color.bar_scrim))
        addView(row, LinearLayout.LayoutParams(-1, Ui.dp(a, BAR)))
        addView(
            View(a).apply { setBackgroundColor(Ui.col(a, R.color.bar_outline)) },
            LinearLayout.LayoutParams(-1, 1),
        )
    }

    init {
        // 先按屏幕宽度猜一次，免得手机上第一帧闪一下横排标签
        compact = a.resources.configuration.screenWidthDp in 1 until 600
        relayout()
    }

    /** 整条的高度（含底部细线）。画布据此让开，同 `TopBar.height()` 的口径 */
    fun height(): Int = Ui.dp(a, BAR) + 1

    fun setWorkspace(name: String) {
        if (wsName == name) return
        wsName = name
        wsChip.text = if (compact) "" else name
        wsChip.contentDescription = name
    }

    /** 只有标题的老接口（模式2）：一律按文档图标 */
    fun setTabs(titles: List<String>, active: Int) = setTabInfos(titles.map { TabInfo(it) }, active)

    /**
     * 整条重建。标签数是个位数，每次全建比"算差异"简单可靠得多（同 `PadDrawer.rebuild` 的取舍）。
     * 重建后把当前标签滚进视野——开到第 7 篇时它本来在屏幕外，不滚的话看着像"没切过去"。
     */
    fun setTabInfos(list: List<TabInfo>, active: Int) {
        tabs = list
        this.active = active
        renderTabs()
        if (overview.isOpen) overview.update(list, active)
    }

    /** 总览此刻（或刚收起、屏幕还没换下来时）盖在界面上：宿主这时别截缩略图，截到的是总览 */
    fun overviewOnScreen(): Boolean =
        overview.isOpen || android.os.SystemClock.uptimeMillis() - overview.closedAt < 400

    /** 总览开着就关掉它（宿主的返回键先问这里），关了返回 true */
    fun closeOverview(): Boolean {
        if (!overview.isOpen) return false
        overview.close()
        return true
    }

    private fun openOverview() {
        beforeOverview { overview.show(tabs, active) }
    }

    private fun setCompact(v: Boolean) {
        if (compact == v) return
        compact = v
        relayout()
    }

    /** 按 [compact] 重新往 [row] 里摆 */
    private fun relayout() {
        row.removeAllViews()
        wsChip.text = if (compact) "" else wsName
        wsChip.compoundDrawablePadding = Ui.dp(a, if (compact) 2 else 6)
        row.addView(wsChip, LinearLayout.LayoutParams(-2, Ui.dp(a, CHIP)))
        row.addView(wsDivider, LinearLayout.LayoutParams(1, Ui.dp(a, 22)).apply {
            marginStart = Ui.dp(a, 6)
            marginEnd = Ui.dp(a, if (compact) 4 else 2)
        })
        if (compact) {
            row.addView(curChip, LinearLayout.LayoutParams(0, Ui.dp(a, CHIP), 1f))
            row.addView(countBtn, LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)).apply { marginStart = Ui.dp(a, 2) })
        } else {
            row.addView(scroll, LinearLayout.LayoutParams(0, Ui.dp(a, CHIP), 1f))
        }
        row.addView(addBtn)
        renderTabs()
    }

    private fun renderTabs() {
        if (compact) {
            val t = tabs.getOrNull(active)
            val on = t != null
            val fg = if (on) Ui.accent(a) else Ui.col(a, R.color.bar_on_variant)
            curTitle.text = t?.title ?: a.getString(R.string.tabs_none_short)
            curTitle.setTextColor(fg)
            curIcon.setImageResource(t?.icon ?: R.drawable.ic_doc)
            curIcon.imageTintList = ColorStateList.valueOf(fg)
            val bg = if (on) Ui.col(a, R.color.accent_container) else Ui.container(a)
            curChip.background = Ui.rippleOver(a, Ui.round(bg, Ui.RADIUS, a), Ui.RADIUS, fg)
            curChip.contentDescription = a.getString(R.string.tabs_show_all)
            countText.text = if (tabs.size > 99) "99+" else tabs.size.toString()
            countBtn.contentDescription = a.getString(R.string.tabs_count, tabs.size)
            return
        }
        tabsRow.removeAllViews()
        var activeView: View? = null
        for ((i, t) in tabs.withIndex()) {
            val on = i == active
            val chip = chip(t.title, on, i)
            tabsRow.addView(
                chip,
                LinearLayout.LayoutParams(-2, Ui.dp(a, CHIP)).apply { marginEnd = Ui.dp(a, 4) },
            )
            if (on) activeView = chip
        }
        val target = activeView ?: return
        scroll.post {
            val want = target.left - (scroll.width - target.width) / 2
            scroll.smoothScrollTo(want.coerceAtLeast(0), 0)
        }
    }

    private fun chip(title: String, active: Boolean, index: Int): LinearLayout {
        val fg = if (active) Ui.accent(a) else Ui.col(a, R.color.bar_on)
        val bg = if (active) Ui.col(a, R.color.accent_container) else Ui.container(a)
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rippleOver(a, Ui.round(bg, Ui.RADIUS, a), Ui.RADIUS, fg)
            isClickable = true
            setOnClickListener { onSelect(index) }
            setPadding(Ui.dp(a, 12), 0, 0, 0)
            addView(
                TextView(a).apply {
                    text = title
                    textSize = 13f
                    maxLines = 1
                    maxWidth = Ui.dp(a, TAB_MAX_W)
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(fg)
                },
                LinearLayout.LayoutParams(-2, -2),
            )
            // 模式2 没有 ×，右侧补上与左侧对称的内边距，芯片才不会看着左重右轻
            if (closable(index)) addView(closeButton(fg, index)) else setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0)
        }
    }

    private fun closeButton(tint: Int, index: Int): ImageButton =
        smallIcon(R.drawable.ic_close, "关闭这个标签页", tint, CLOSE) { onClose(index) }

    /** 顶栏的 [Ui.iconButton] 是固定 48dp，这条栏只有 44dp 高，所以自己搓一颗小的（见类注释） */
    private fun smallIcon(
        icon: Int,
        desc: String,
        tint: Int = Ui.col(a, R.color.bar_on),
        size: Int = 36,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(a).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = Ui.dp(a, (size - 16) / 2)
        setPadding(pad, pad, pad, pad)
        background = Ui.rippleOver(a, null, Ui.PILL, tint)
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, size), Ui.dp(a, size))
        setOnClickListener { onClick() }
    }
}
