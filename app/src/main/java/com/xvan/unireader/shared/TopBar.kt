package com.xvan.unireader.shared

import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui.setActive
import kotlin.math.max

/**
 * 阅读顶栏，**模式1 与模式2 共用这一份**（`ANDROID-STANDALONE-PLAN.md §4`）。
 *
 * 从前两边各写一份：同一批按钮、同一套文案、要求"逐字一致"，却靠人肉同步——已经飘了。
 * 现在按钮的形状、开关态的表达、溢出菜单的行为都在这里定义一次，两边只声明"我有哪几个键、按下去干什么"。
 *
 * 形态（用户 2026-07-30 定）：**全图标单行 + 右侧溢出菜单**。
 * - 模式键的图标随当前模式变（铅笔 / 橡皮 / 四向箭头 / 虚线绳圈），点一下轮换。
 * - 开关类按下去是 accent 底色 + accent 图标，不再靠给文案加"✓"。
 * - 「切换笔」只在笔模式下出现（[setVisible]），并染当前笔的颜色（[setTint]）。
 *
 * **2026-09-26 起顶栏上放什么由 [tools]（[Toolbox]）决定**：每颗键都是一颗可自由编组的工具，用户可以把整组拖出去
 * 浮着、拖回来，长按把手打开编辑面板换组 / 收进 ⋯。顶栏自己只剩四段：最左「固定在左」的组（[pinArea]，不滚动）、
 * 中间其余并在顶栏里的组（[dockArea]，排不下横向滚动）、「固定在右」的组（[pinRightArea]，不滚动）、最右的 ⋯。
 * 页码与模式2 的延迟读数也是工具（默认在右侧固定的「状态」组里）。
 * ⋯ 里 = 固定组放不下被收起来的 + 哪组都不在的工具 + [overflowItems] + 「编辑工具栏…」（常驻：固定组没有把手，
 * 编辑面板要从这里进），勾选态**每次弹出现算**，不缓存。
 */
class TopBar(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/TopBar"

        /**
         * 一条栏的高度。跟着 [Ui.TOUCH] 一起收（2026-09-02 用户要求整体缩 15%：56→48）——
         * 按钮 41dp + 上下各 3.5dp 的气口，比按钮自己高一点点，密排时才不显得挤。
         */
        const val BAR_H = 48

        /**
         * 模式 → 图标（`MODE_NOTE/ERASE/PAGE/LASSO`，顺序与 `PadConst.MODE_LABELS` 一致）。
         * 笔记模式用**铅笔**而不是 `ic_pen` 那支马克笔：笔模式下这颗键与「切换笔」是邻居，
         * 同一支笔出现两次没人分得清谁是谁（`tools/icons/gen.py` 里 `mode_pen` 的注释同此）。
         */
        fun modeIcon(mode: Int): Int = when (mode) {
            MODE_ERASE -> R.drawable.ic_mode_eraser
            MODE_PAGE -> R.drawable.ic_mode_pan
            MODE_LASSO -> R.drawable.ic_mode_lasso
            MODE_TEXT -> R.drawable.ic_mode_text   // 模式1 专属（选字），模式2 循环不到这一档
            else -> R.drawable.ic_mode_pen
        }
    }

    /** 溢出菜单的一项。[checked] 非空即渲染成可勾选项（原生 checkable） */
    class MenuItem(val title: String, val checked: Boolean? = null, val onClick: () -> Unit)

    /** 可自由编组的工具（顶栏上的键、画板 / 草稿纸控制栏、⋯ 里的操作都登记在这里） */
    val tools = Toolbox(a, this)

    /** 并在顶栏里的**固定**组：排在最左、不跟着横向滚动（由 [Toolbox] 管） */
    val pinArea = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 并在顶栏里的其余组按先后摆在这里，排不下就横向滚动（由 [Toolbox] 管） */
    val dockArea = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 并在顶栏里的**固定在右**的组：排在 ⋯ 左边、不跟着横向滚动（由 [Toolbox] 管） */
    val pinRightArea = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 最右一段：固定在右的组 + ⋯（⋯ 是编辑工具栏的入口，永远在最右、不是可摆放的工具） */
    private val tail = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val keyed = HashMap<String, ImageButton>()

    /** 页码/缩放。点它跳页（两模式同）——文案格式统一走 [setPageLabel] */
    val pageLabel: TextView = TextView(a).apply {
        text = "— / —"
        textSize = 13f
        maxLines = 1
        setTextColor(Ui.col(a, R.color.bar_on_variant))
        val h = Ui.dp(a, 6)
        setPadding(Ui.dp(a, 8), h, Ui.dp(a, 8), h)
        background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.barOn(a))
    }

    /**
     * 页码 + 缩放的**唯一**格式化处（从前模式1 用全角空格、模式2 用两个半角空格，就是这么飘的）。
     * 缩放 100% 时不显示——它只在偏离时才是信息，而顶栏的横向空间比这条信息值钱。
     */
    fun setPageLabel(page: String, zoom: String) {
        val s = if (zoom == "100%") page else "$page · $zoom"
        if (pageLabel.text?.toString() == s) return
        pageLabel.text = s
        // 文字变长时右侧尾段（固定在右的组 + ⋯）要跟着重量。这里常在布局回调里被调（画布 onLayout → 刷 HUD），
        // 那一刻 TextView 的 requestLayout 会被本轮布局吞掉 → 尾段停在旧宽度、⋯ 被挤出去半截（用户 2026-09-27 报，
        // dumpsys 实测尾段 220px = 旧页码 117 + ⋯ 103，而页码已是 163）。post 到下一拍补一次。
        pageLabel.post { pageLabel.requestLayout() }
    }

    /** ⋯ 里除工具以外的固定项（每次弹出时现算）。工具本身不写在这里——它们由 [tools] 决定在不在 ⋯ 里 */
    var overflowItems: (() -> List<MenuItem>)? = null

    private val overflowBtn = Ui.iconButton(a, R.drawable.ic_more, "更多", Ui.barOn(a)) { showOverflow() }.apply {
        setOnLongClickListener { tools.openEditor(); true }
    }

    /** 一整条（含状态栏让位 + 底部 1px 细线）。高度用 [height] 拿，画布据此让开 */
    val view: LinearLayout

    /** 非固定组的那一段：吃掉固定区与 [tail] 之外的全部宽度，排不下就横向滚动（2026-09-26 用户定：滚动，不往 ⋯ 里收） */
    private val iconScroll = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(dockArea)
    }

    private val contentRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(pinArea, LinearLayout.LayoutParams(-2, Ui.dp(a, BAR_H)))
        addView(iconScroll, LinearLayout.LayoutParams(0, Ui.dp(a, BAR_H), 1f))
        addView(tail, LinearLayout.LayoutParams(-2, -1).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    init {
        tail.addView(pinRightArea, LinearLayout.LayoutParams(-2, -1))
        tail.addView(overflowBtn)
        // 页码也是一颗可摆放的工具（默认在右侧固定的「状态」组里，见 ToolLayout.defaults）
        tools.register("pageLabel", a.getString(R.string.tools_page_label), pageLabel)
        view = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true   // 挡住触摸穿透到画布
            setBackgroundColor(Ui.col(a, R.color.bar_scrim))
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
            addView(contentRow, LinearLayout.LayoutParams(-1, -2))
            addView(
                View(a).apply { setBackgroundColor(Ui.col(a, R.color.bar_outline)) },
                LinearLayout.LayoutParams(-1, 1),
            )
        }
    }

    /**
     * 固定组最多能占多宽：整行减去右侧固定区，再给滚动段留一颗键的位置（固定组多到连这都放不下，
     * [Toolbox] 才把放不下的收进 ⋯）。还没布局过时 0。
     */
    internal fun pinAvailWidth(): Int {
        if (contentRow.width <= 0) return 0
        return max(0, contentRow.width - overflowBtn.width - Ui.dp(a, Ui.TOUCH))
    }

    /**
     * 登记一颗按钮工具（放在哪由 [tools] 的布局决定，不一定在顶栏上）。[key] 用于之后改图标/改开关态
     * （见 [setIcon]/[setActive]），也是布局里认它的名字。
     * [desc] 是无障碍文案，同时是 ⋯ 菜单里那一行与编辑面板里的名字，所以要写成「做什么」而不是「叫什么」。
     *
     * [spillFirst]：顶栏排不下先收这个。给「上一页/下一页」这类**有别的办法完成**的操作用。
     * [toggle]：开关类（⋯ 里画成可勾选项，勾没勾按 [setActive] 设的状态）。
     * [available]：此刻能不能用（如「夜间模式」只在开着 PDF 时）；不能用的不显示、也不进 ⋯。
     */
    fun icon(
        key: String,
        iconRes: Int,
        desc: String,
        spillFirst: Boolean = false,
        toggle: Boolean = false,
        available: () -> Boolean = { true },
        onClick: () -> Unit,
    ): ImageButton {
        val b = Ui.iconButton(a, iconRes, desc, Ui.barOn(a), onClick)
        keyed[key] = b
        tools.register(key, desc, b, toggle = toggle, spillFirst = spillFirst, available = available)
        return b
    }

    /** 显示/藏掉某个键（藏 = 拿走，不是置灰——置灰用 [setEnabled]）；藏掉的也不进 ⋯ */
    fun setVisible(key: String, visible: Boolean) {
        val b = keyed[key] ?: return
        val v = if (visible) View.VISIBLE else View.GONE
        if (b.visibility != v) {
            b.visibility = v
            tools.requestRefresh()
        }
    }

    /** 给某个键的图标染色（「切换笔」染当前笔色）；null = 回默认 barOn */
    fun setTint(key: String, color: Int?) {
        keyed[key]?.imageTintList = ColorStateList.valueOf(color ?: Ui.barOn(a))
    }

    fun setIcon(key: String, iconRes: Int) {
        keyed[key]?.setImageResource(iconRes)
    }

    fun setActive(key: String, active: Boolean) {
        keyed[key]?.setActive(active, Ui.barOn(a), Ui.accent(a))
    }

    fun setEnabled(key: String, enabled: Boolean) {
        keyed[key]?.let { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.35f }
    }

    /** 拖着一组工具经过顶栏时高亮（松手就并进来） */
    fun setDropHighlight(on: Boolean) {
        if ((view.foreground != null) == on) return
        view.foreground = if (on) android.graphics.drawable.ColorDrawable(
            (Ui.col(a, R.color.accent_container) and 0x00FFFFFF) or (0x80 shl 24),
        ) else null
    }

    private fun showOverflow() {
        // 被挤下去 / 收进来的工具排在最前：用户是在这儿"找"它们，不是在浏览设置
        val list = tools.overflow() + (overflowItems?.invoke() ?: emptyList()) +
            MenuItem(a.getString(R.string.tools_edit_menu)) { tools.openEditor() }
        val menu = PopupMenu(a, overflowBtn, Gravity.END)
        list.forEachIndexed { i, item ->
            val mi = menu.menu.add(Menu.NONE, i, i, item.title)
            if (item.checked != null) {
                mi.isCheckable = true
                mi.isChecked = item.checked
            }
        }
        menu.setOnMenuItemClickListener { mi ->
            list.getOrNull(mi.itemId)?.onClick?.invoke()
            true
        }
        menu.show()
    }

    /**
     * 让开状态栏。返回整条的**总高度**（含让位），画布拿它当上边距。
     * 与从前一样必须在 insets 回调里调——刘海/挖孔机型上状态栏高度不是常数。
     */
    fun applyTopInset(top: Int): Int {
        view.setPadding(Ui.dp(a, 4), top, Ui.dp(a, 4), 0)
        return Ui.dp(a, BAR_H) + 1 + top
    }

    /** 没有 insets 时（模式2 沉浸全屏）的高度 */
    fun height(): Int = Ui.dp(a, BAR_H) + 1
}

/** 顶栏之外的浮层（状态胶囊等）要贴着它底下时用；两模式共用同一个数值 */
fun barFloatMargin(a: Activity): Int = Ui.dp(a, 10)

/** 让 [FrameLayout] 里的浮层避开顶栏，语义比到处写 `Gravity.TOP or …` 清楚 */
fun FrameLayout.LayoutParams.below(barH: Int) = apply { topMargin = barH }
