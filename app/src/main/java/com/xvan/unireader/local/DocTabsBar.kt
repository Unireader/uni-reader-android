package com.xvan.unireader.local

import android.app.Activity
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui

/**
 * 模式1 的标签页栏：`[工作区名 ⌄] │ 文档A ×│ 文档B ×│ +`（`ANDROID-STANDALONE-PLAN.md §13`）。
 *
 * **只有模式1 有**：模式2 的「开哪一篇」由 Mac 决定（平板跟随激活窗口），平板这边没有多篇并存的
 * 概念，所以它住在 `local/` 而不是 `shared/`。
 *
 * 形态与顶栏（`shared/TopBar`）同源：扁平、圆角、语义色、系统涟漪，深浅色跟随 `values-night`。
 * 三块从左到右固定语义——
 * - **工作区芯片**（最左，不随标签横滑）：当前工作区名 + `⌄`，点它切工作区。它是「快捷切工作区」
 *   的唯一入口，所以**必须常驻可见**，不能跟着标签一起滑走。
 * - **标签区**（中间，可横滑）：一篇一个芯片，当前那篇是 accent 底 + accent 字；每个芯片右侧一个 ×。
 * - **加号**（最右，不随标签横滑）：在本工作区里再开一篇。
 *
 * 触摸尺寸这里**破例低于 48dp**（芯片高 [CHIP]、× 命中 [CLOSE]）：顶栏已经占了 57dp，
 * 再叠一条 56dp 的标签栏，两条栏就吃掉 113dp 的阅读区。标签栏是次级导航，且每个动作都有
 * 替代路径（切标签＝也可以从「+」的文档列表点；关标签＝退出后不影响数据），所以这里按可读的
 * 34dp 走，**顶栏那条 48dp 的下限不动**。
 */
class DocTabsBar(private val a: Activity) {

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

    private var wsName = ""

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

    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true   // 挡住触摸穿透到画布
        setBackgroundColor(Ui.col(a, R.color.bar_scrim))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 4), 0)
                addView(wsChip, LinearLayout.LayoutParams(-2, Ui.dp(a, CHIP)))
                addView(
                    View(a).apply { setBackgroundColor(Ui.col(a, R.color.bar_outline)) },
                    LinearLayout.LayoutParams(1, Ui.dp(a, 22)).apply {
                        marginStart = Ui.dp(a, 6)
                        marginEnd = Ui.dp(a, 2)
                    },
                )
                addView(scroll, LinearLayout.LayoutParams(0, Ui.dp(a, CHIP), 1f))
                addView(addBtn)
            },
            LinearLayout.LayoutParams(-1, Ui.dp(a, BAR)),
        )
        addView(
            View(a).apply { setBackgroundColor(Ui.col(a, R.color.bar_outline)) },
            LinearLayout.LayoutParams(-1, 1),
        )
    }

    /** 整条的高度（含底部细线）。画布据此让开，同 `TopBar.height()` 的口径 */
    fun height(): Int = Ui.dp(a, BAR) + 1

    fun setWorkspace(name: String) {
        if (wsName == name) return
        wsName = name
        wsChip.text = name
    }

    /**
     * 整条重建。标签数是个位数，每次全建比"算差异"简单可靠得多（同 `PadDrawer.rebuild` 的取舍）。
     * 重建后把当前标签滚进视野——开到第 7 篇时它本来在屏幕外，不滚的话看着像"没切过去"。
     */
    fun setTabs(titles: List<String>, active: Int) {
        tabsRow.removeAllViews()
        var activeView: View? = null
        for ((i, t) in titles.withIndex()) {
            val on = i == active
            val chip = chip(t, on, i)
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
            addView(closeButton(fg, index))
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
