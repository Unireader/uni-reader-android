package com.xvan.unireader.shared

import android.app.Activity
import android.content.res.ColorStateList
import android.util.Log
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

/**
 * 阅读顶栏，**模式1 与模式2 共用这一份**（`ANDROID-STANDALONE-PLAN.md §4`）。
 *
 * 从前两边各写一份：同一批按钮、同一套文案、要求"逐字一致"，却靠人肉同步——已经飘了
 * （模式2 是 `"笔:圆珠笔"`、模式1 是 `"圆珠笔"`；竖屏挤压的修法也只打在模式2 上）。
 * 现在按钮的形状、间距、开关态的表达、溢出菜单的行为都在这里定义一次，
 * 两边只声明"我有哪几个键、按下去干什么"。
 *
 * 形态（用户 2026-07-30 定）：**全图标单行 + 右侧溢出菜单**。
 * - 模式键 2026-08 从顶栏移除（图标怎么调都与栏上其他键不是一个视觉重量）：切模式走环形盘。
 * - 开关类（尺子）按下去是 accent 底色 + accent 图标，不再靠给文案加"✓"。
 * - 「切换笔」只在笔模式下出现（[setVisible]），并染当前笔的颜色（[setTint]）。
 * - 低频项（夜间、页图显隐、锁缩放、图层、跳页、连接设置…）进 ⋯，用系统 `PopupMenu`：
 *   勾选态是原生的 checkable item，**每次弹出现算**，不缓存——缓存就会出现"菜单里没勾但功能开着"。
 *
 * 排不下怎么办：图标区本身在 [HorizontalScrollView] 里（竖屏窄屏兜底），但正常情况下
 * 不该滑——**排不下就该往 ⋯ 里收，而不是让用户横滑找按钮**。
 */
class TopBar(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/TopBar"
    }

    /** 溢出菜单的一项。[checked] 非空即渲染成可勾选项（原生 checkable） */
    class MenuItem(val title: String, val checked: Boolean? = null, val onClick: () -> Unit)

    private val icons = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 右侧固定区：模式2 的延迟指标塞这儿，页码与 ⋯ 永远在最右 */
    private val tail = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val keyed = HashMap<String, ImageButton>()

    /** 声明过的图标键，按加入顺序。[spillFirst] 的在排不下时先被收进 ⋯ */
    private class Item(
        val key: String,
        val btn: ImageButton,
        val desc: String,
        val onClick: () -> Unit,
        val spillFirst: Boolean,
    )

    private val items = ArrayList<Item>()
    private var gapsPx = 0

    /** 当前被收进 ⋯ 的那些键（宽度不够时自动收，见 [reflow]） */
    private var spilled = listOf<Item>()

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
     * 缩放 100% 时不显示——它只在偏离时才是信息，而顶栏的横向空间比这条信息值钱：
     * 竖屏 411dp 宽下六个 48dp 触摸目标已经快占满，多这 36dp 就要开始横滑找按钮了。
     */
    fun setPageLabel(page: String, zoom: String) {
        pageLabel.setTextIfChanged(if (zoom == "100%") page else "$page · $zoom")
    }

    /** 溢出菜单的内容**每次弹出时现算**（勾选态要实时），所以这里存的是生成器不是列表 */
    var overflowItems: (() -> List<MenuItem>)? = null

    private val overflowBtn = Ui.iconButton(a, R.drawable.ic_more, "更多", Ui.barOn(a)) { showOverflow() }

    /** 一整条（含状态栏让位 + 底部 1px 细线）。高度用 [height] 拿，画布据此让开 */
    val view: LinearLayout

    /** 图标区的可用宽度就是它的宽度（weight=1，吃掉 [tail] 之外的全部） */
    private val iconScroll = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(icons)
    }

    private val contentRow = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(iconScroll, LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f))
        addView(tail, LinearLayout.LayoutParams(-2, -1).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    init {
        tail.addView(pageLabel)
        tail.addView(overflowBtn)
        // 宽度定下来（首帧、旋转、页码文案变长）后重算一次能不能排下
        contentRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reflow() }
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
     * 加一个图标键。[key] 只用于之后改图标/改开关态（见 [setIcon]/[setActive]），不参与显示。
     * [desc] 是无障碍文案——全图标之后它是唯一的文字线索，不许省；排不下被收进 ⋯ 时，
     * 它同时就是菜单里那一行的标题，所以要写成「做什么」而不是「叫什么」。
     *
     * [spillFirst]：窄屏先收这个。给「上一页/下一页」这类**有别的办法完成**的操作用
     * （滚动就能翻页），别给模式/笔这种没有替代路径的。
     */
    fun icon(
        key: String,
        iconRes: Int,
        desc: String,
        spillFirst: Boolean = false,
        onClick: () -> Unit,
    ): ImageButton {
        val b = Ui.iconButton(a, iconRes, desc, Ui.barOn(a), onClick)
        keyed[key] = b
        items.add(Item(key, b, desc, onClick, spillFirst))
        icons.addView(b)
        return b
    }

    /** 一道 8dp 的气口，把「翻页」与「工具」这类不同性质的键分开 */
    fun gap(w: Int = 8) {
        icons.addView(View(a), LinearLayout.LayoutParams(Ui.dp(a, w), 1))
        gapsPx += Ui.dp(a, w)
    }

    /**
     * 宽度不够就把键收进 ⋯（竖屏 411dp 下六个 48dp 触摸目标 + 页码 + ⋯ 正好排不下，
     * 实测「文字笔记」会被裁成 42px 宽——按得到一半，比收起来更糟）。
     *
     * 触摸目标不缩：48dp 是系统无障碍下限，为了多塞一个键把它压小是拿手指准头换排版。
     * 收谁由 [icon] 的 `spillFirst` 定。被 [setVisible] 藏掉的键不参与宽度计算、也不进 ⋯。
     */
    private fun reflow() {
        if (items.isEmpty()) return
        val avail = iconScroll.width
        val shown = items.filter { it.key !in hidden }
        if (avail > 0) {
            val unit = Ui.dp(a, Ui.TOUCH)
            // need 一律按**全部可见**键算起（不是按当前没收的），否则收过一次之后就再也长不回来了
            var need = shown.size * unit + gapsPx
            val toSpill = ArrayList<Item>()
            // ① `spillFirst` 的**整组一起收**：◀▶ 是一对，只收走一个会让人以为是 BUG。
            val first = shown.filter { it.spillFirst }
            if (need > avail && first.isNotEmpty()) {
                toSpill.addAll(first)
                need -= first.size * unit
            }
            // ② 还不够就从队尾逐个收（越靠后越次要，与声明顺序一致）
            var i = shown.size - 1
            while (need > avail && i >= 0) {
                val it = shown[i]
                if (it !in toSpill) {
                    toSpill.add(it)
                    need -= unit
                }
                i--
            }
            if (toSpill.map { it.key } != spilled.map { it.key }) {
                Log.i(TAG, "顶栏宽度不够（可用 ${avail}px），收进溢出菜单：${toSpill.joinToString { it.desc }}")
                spilled = toSpill
            }
        }
        // **必须 post**：这里可能是在 layout 回调里，此刻改可见性触发的 requestLayout 会被本轮布局
        // 吞掉（"requestLayout() improperly called during layout"）。可见性按「被藏 或 被收」现算
        // 并总是应用——同值 setVisibility 是 no-op 不会来回抖，而 setVisible 引起的这次 reflow
        // spill 结果往往没变，靠「结果没变就返回」省掉它的话藏键就永远不生效了。
        icons.post {
            for (it in items) {
                it.btn.visibility = if (it.key in hidden || it in spilled) View.GONE else View.VISIBLE
            }
        }
    }

    /** 被 [setVisible] 藏掉的键（如「切换笔」只在笔模式下出现）：不占宽度、不进 ⋯ */
    private val hidden = HashSet<String>()

    /** 显示/藏掉某个键（藏 = 从栏上拿走，不是置灰——置灰用 [setEnabled]） */
    fun setVisible(key: String, visible: Boolean) {
        val changed = if (visible) hidden.remove(key) else hidden.add(key)
        if (changed) reflow()
    }

    /** 给某个键的图标染色（「切换笔」染当前笔色）；null = 回默认 barOn */
    fun setTint(key: String, color: Int?) {
        keyed[key]?.imageTintList = ColorStateList.valueOf(color ?: Ui.barOn(a))
    }

    /** 往右侧固定区插一个自定义 View（模式2 的延迟指标），排在页码之前 */
    fun addTail(v: View, index: Int = 0) = tail.addView(v, index)

    fun setIcon(key: String, iconRes: Int) {
        keyed[key]?.setImageResource(iconRes)
    }

    fun setActive(key: String, active: Boolean) {
        keyed[key]?.setActive(active, Ui.barOn(a), Ui.accent(a))
    }

    fun setEnabled(key: String, enabled: Boolean) {
        keyed[key]?.let { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.35f }
    }

    private fun showOverflow() {
        // 被挤下去的键排在最前：它们本来该在栏上，用户是在这儿"找"它们，不是在浏览设置
        // （被 setVisible 藏掉的键不进 ⋯——藏掉就是不想让它出现）
        val spill = spilled.filter { it.key !in hidden }.map { s -> MenuItem(s.desc, null, s.onClick) }
        val list = spill + (overflowItems?.invoke() ?: emptyList())
        if (list.isEmpty()) return
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
        return Ui.dp(a, 56) + 1 + top
    }

    /** 没有 insets 时（模式2 沉浸全屏）的高度 */
    fun height(): Int = Ui.dp(a, 56) + 1
}

/** 顶栏之外的浮层（状态胶囊等）要贴着它底下时用；两模式共用同一个数值 */
fun barFloatMargin(a: Activity): Int = Ui.dp(a, 10)

/** 让 [FrameLayout] 里的浮层避开顶栏，语义比到处写 `Gravity.TOP or …` 清楚 */
fun FrameLayout.LayoutParams.below(barH: Int) = apply { topMargin = barH }
