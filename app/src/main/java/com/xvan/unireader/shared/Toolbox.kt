package com.xvan.unireader.shared

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui.isActiveOn
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 可自由编组的工具栏（2026-09-26 用户定：像 macOS 那样配置；两模式共用这一份，布局也共用一份）。
 *
 * **工具**：一颗按钮或一个读数（画板名 / 页码 / 缩放），用 key 标识，由宿主 [register]（或 [adopt] 一整排）。
 * 同一个 key 可以有几份实例——模式1 的草稿纸栏与画板栏各有一颗「适应内容」，同一时刻只有一份可用（[register] 的
 * `available`），用户摆放时只看见一颗。工具自己的开关态 / 显隐（如「切换笔」只在笔模式下出现）仍由宿主直接改那颗
 * View；这里只管它**放在哪**。
 *
 * **组**（[ToolLayout]）：每组一个横排，最左一个拖动把手。并在顶栏里的组按先后从左往右排在顶栏图标区；浮着的组
 * 挂在宿主根 `FrameLayout` 上。一组里此刻没有可显示的工具（如画板没开时的画板组）就整条收起。
 * - 按住把手拖：落在顶栏上 = 并进顶栏（落点决定排在哪两组之间），落在别处 = 浮在那里。
 * - 长按把手 / 长按 ⋯ / ⋯ 里「编辑工具栏…」：打开 [ToolbarEditor]，拖按钮换组、收进 ⋯、建组删组改名。
 * - **固定**（在左 / 在右）的组没有把手、不能拖；并在顶栏里时排在最左（[TopBar.pinArea]）或 ⋯ 左边
 *   （[TopBar.pinRightArea]），不跟着滚动。
 * - 顶栏排不下：非固定组所在的那一段横向滚动；只有固定组多到放不下时，才按「先收 spillFirst，再从右往左」收进 ⋯。
 *   哪组都不在的工具也在 ⋯ 里。
 */
class Toolbox internal constructor(private val a: Activity, private val bar: TopBar) {

    /** 一颗工具的说明（编辑面板与 ⋯ 菜单用）。[toggle] = 开关类，⋯ 里画成可勾选项 */
    class Meta(val key: String, val title: String, val toggle: Boolean, val spillFirst: Boolean)

    /**
     * 包住一颗工具的那层。[blocked] = 此刻不能用、但按 [showDisabled] 灰着显示：整层变淡并吃掉触摸，
     * **不碰工具 View 自己的 enabled / alpha**（那是宿主的，如剪切在没选中时由宿主灰掉）。
     */
    private class ItemWrap(c: android.content.Context) : FrameLayout(c) {
        var blocked = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = blocked || super.onInterceptTouchEvent(ev)
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean = blocked || super.onTouchEvent(ev)
    }

    private class Item(val view: View, val available: () -> Boolean, val wrap: ItemWrap)

    /** 一个 key 在栏上占的那一格（里面是这个 key 的全部实例，各自按可用与否显隐） */
    private inner class Cell(val key: String) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val items = ArrayList<Item>()
        var spilled = false

        /** 此刻有没有东西可显示（含按 [showDisabled] 灰着显示的） */
        fun live() = liveItem() != null

        fun liveItem(): Item? =
            items.firstOrNull { it.wrap.visibility == View.VISIBLE && it.view.visibility == View.VISIBLE }

        fun liveView(): View? = liveItem()?.view
    }

    /** 宿主经 [TopBar.setVisible] 藏起来的键（藏 = 此刻不能用，跟 `available` 为假同一个意思） */
    private val hostHidden = HashSet<String>()

    fun setHostHidden(key: String, hidden: Boolean) {
        if (if (hidden) hostHidden.add(key) else hostHidden.remove(key)) requestRefresh()
    }

    private val metas = LinkedHashMap<String, Meta>()
    private val cells = LinkedHashMap<String, Cell>()
    private val prefs = a.getSharedPreferences("toolbars", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    /**
     * 此刻不能用的按钮也灰着显示（顶栏 / 浮条 / ⋯ 里都是，点了没反应），而不是整颗拿走（用户 2026-09-27 定）。
     * 编辑面板里的勾选项，记在本机、两模式共用；默认关 = 不能用就不显示。读数（页码 / 画板名）不受影响，照旧拿走。
     */
    var showDisabled: Boolean = prefs.getBoolean(PREF_SHOW_DISABLED, false)
        set(v) {
            if (field == v) return
            field = v
            prefs.edit().putBoolean(PREF_SHOW_DISABLED, v).apply()
            lastSig = ""
            refresh()
        }

    /** 当前布局（编辑面板读它的副本，改完经 [setLayout] 交回来） */
    var layout: ToolLayout = ToolLayout.fromJson(prefs.getString(PREF_LAYOUT, null)) ?: ToolLayout.defaults()
        private set

    /** 浮着的组最高能到哪（根坐标 px）：顶栏 + 标签页栏的下沿；顶栏收起时 0 */
    var minTop: () -> Int = { 0 }

    /** 浮着的组插在根的这个子视图之下（抽屉要永远盖在最上面）；null = 最上层 */
    var below: View? = null

    private var root: FrameLayout? = null
    private val rows = ArrayList<GroupRow>()

    // ---------- 登记工具 ----------

    /**
     * 登记一颗工具。[available] = 此刻这一份能不能用（如画板组的键只在画板开着时）；
     * 宿主之后照旧直接改 [view] 的开关态 / 显隐 / 文字。
     */
    fun register(
        key: String, title: String, view: View,
        toggle: Boolean = false, spillFirst: Boolean = false, available: () -> Boolean = { true },
    ) {
        if (key !in metas) metas[key] = Meta(key, title, toggle, spillFirst)
        val cell = cells.getOrPut(key) { Cell(key) }
        (view.parent as? ViewGroup)?.removeView(view)
        val wrap = ItemWrap(a).apply { addView(view) }
        cell.items.add(Item(view, available, wrap))
        cell.box.addView(wrap, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
    }

    /**
     * 把宿主原来那一整排（画板 / 草稿纸控制栏）的子视图按 [keys] 逐个登记，排本身不再用。
     * [available] = 这一排原来什么时候显示（宿主照旧设那一排的 visibility，这里拿它当「能不能用」）。
     */
    fun adopt(row: LinearLayout, keys: List<String>, available: () -> Boolean) {
        val views = List(row.childCount) { row.getChildAt(it) }
        check(views.size == keys.size) { "工具条子视图 ${views.size} 个，key ${keys.size} 个，对不上" }
        row.removeAllViews()
        for ((i, v) in views.withIndex()) {
            val k = keys[i]
            register(k, padTitle(k), v, toggle = k in PAD_TOGGLES, available = available)
        }
    }

    /** 全部登记完、根视图建好之后调一次：补齐布局里没见过的 key，按布局摆好 */
    fun attach(root: FrameLayout) {
        this.root = root
        root.setOnDragListener { _, e -> onDrag(e) }
        // 任何一次布局（宿主藏 / 显某颗键、读数变长）之后重排一次；只有真变了才动视图，不会来回抖
        root.viewTreeObserver.addOnGlobalLayoutListener { requestRefresh(remeasure = true) }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) root.post { relayout() }
        }
        if (ToolLayout.mergeNew(layout, metas.keys)) save()
        applyLayout()
    }

    // ---------- 编辑面板用 ----------

    /** 本模式登记过的工具（登记顺序） */
    fun metas(): Collection<Meta> = metas.values

    /** 这颗工具此刻能不能用（有一份可用、宿主没藏、也没灰掉）——编辑面板据此把不能用的画灰 */
    fun isUsable(key: String): Boolean {
        if (key in hostHidden) return false
        val c = cells[key] ?: return false
        return c.items.any { it.available() && it.view.visibility == View.VISIBLE && it.view.isEnabled }
    }

    /** 编辑面板里给这颗工具画的图标（按钮取它的图；读数没有图 = null） */
    fun iconOf(key: String): Drawable? =
        (cells[key]?.items?.firstOrNull()?.view as? ImageView)?.drawable?.constantState?.newDrawable()?.mutate()

    fun groupName(g: ToolLayout.Group): String = g.name.ifEmpty {
        when (g.id) {
            ToolLayout.G_NAV -> a.getString(R.string.tools_group_nav)
            ToolLayout.G_PEN -> a.getString(R.string.tools_group_pen)
            ToolLayout.G_PAGE -> a.getString(R.string.tools_group_page)
            ToolLayout.G_PAD -> a.getString(R.string.tools_group_pad)
            ToolLayout.G_STATUS -> a.getString(R.string.tools_group_status)
            else -> a.getString(R.string.tools_group_untitled)
        }
    }

    /** 编辑面板改完一步就交回来：存下、按新布局重摆（边改边看得见） */
    fun setLayout(l: ToolLayout) {
        layout = l
        save()
        applyLayout()
    }

    /** 恢复默认（再补上本模式登记了、默认布局里没有的 key） */
    fun defaultLayout(): ToolLayout = ToolLayout.defaults().also { ToolLayout.mergeNew(it, metas.keys) }

    fun openEditor() = ToolbarEditor(a, this).show()

    private fun save() {
        prefs.edit().putString(PREF_LAYOUT, layout.toJson()).apply()
    }

    // ---------- 摆放 ----------

    /** 一组在屏幕上的那一排 */
    private inner class GroupRow(val g: ToolLayout.Group) {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val grip = ImageView(a).apply {
            setImageResource(R.drawable.ic_grip)
            imageTintList = ColorStateList.valueOf(Ui.onSurface(a))
            alpha = 0.55f
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = Ui.dp(a, 4)
            setPadding(0, p, 0, p)
            contentDescription = a.getString(R.string.tools_grip)
        }

        init {
            // 固定组没有把手（不能拖；改它只能进编辑面板，入口在 ⋯ 里）
            if (!g.pinned) {
                row.addView(grip, LinearLayout.LayoutParams(Ui.dp(a, GRIP_W), Ui.dp(a, Ui.TOUCH)))
                grip.setOnTouchListener(GripTouch(this))
            }
            for (k in g.keys) cells[k]?.let { row.addView(it.box) }
            row.addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
                if (!g.docked && g.x >= 0 && r - l != orr - ol) row.post { place(this) }   // 变宽了：别伸出屏幕
            }
        }

        fun hasLive() = g.keys.any { k -> cells[k]?.let { it.live() && !it.spilled } == true }
    }

    /** 按 [layout] 把每组摆到该在的地方（整套重建，编辑 / 拖放之后调） */
    private fun applyLayout() {
        val r = root ?: return
        for (gr in rows) (gr.row.parent as? ViewGroup)?.removeView(gr.row)
        rows.clear()
        for (c in cells.values) {
            (c.box.parent as? ViewGroup)?.removeView(c.box)
            c.spilled = false
        }
        bar.pinArea.removeAllViews()
        bar.dockArea.removeAllViews()
        bar.pinRightArea.removeAllViews()
        for (g in layout.groups) {
            val gr = GroupRow(g)
            rows.add(gr)
            if (g.docked) {
                // 固定在左 / 右的排两头、不滚动；其余在中间的滚动段里
                val area = when (g.pin) {
                    ToolLayout.PIN_LEFT -> bar.pinArea
                    ToolLayout.PIN_RIGHT -> bar.pinRightArea
                    else -> bar.dockArea
                }
                area.addView(gr.row, LinearLayout.LayoutParams(-2, -1))
            } else {
                gr.row.background = Ui.round(Ui.surface(a), Ui.PILL, a, Ui.outline(a))
                gr.row.setPadding(Ui.dp(a, 4), Ui.dp(a, 2), Ui.dp(a, 6), Ui.dp(a, 2))
                place(gr, r)
            }
        }
        lastSig = ""   // 整套重建过：顶栏排不排得下必须重新量
        refresh()
    }

    /** 顶栏高度变了 / 屏幕转了：浮着的组重新摆位 */
    fun relayout() {
        for (gr in rows) if (!gr.g.docked) place(gr)
    }

    /**
     * 浮着的组摆位：没挪过的贴顶栏下方居中（几组都没挪过就往下一条条排开，免得叠成一坨）；
     * 挪过的按记下的左上角，夹在屏幕里、不钻进顶栏。
     */
    private fun place(gr: GroupRow, r: FrameLayout? = root) {
        r ?: return
        val gap = Ui.dp(a, 10)
        val lp = if (gr.g.x < 0 || gr.g.y < 0) {
            val slot = rows.filter { !it.g.docked && (it.g.x < 0 || it.g.y < 0) }.indexOf(gr).coerceAtLeast(0)
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = minTop() + gap + slot * (Ui.dp(a, Ui.TOUCH) + Ui.dp(a, 14))
            }
        } else {
            val w = max(1, r.width)
            val h = max(1, r.height)
            val top = minTop()
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = gr.g.x.coerceIn(0, max(0, w - gr.row.width))
                topMargin = gr.g.y.coerceIn(min(top, h), max(top, h - gr.row.height))
            }
        }
        val v = gr.row
        val old = v.layoutParams as? FrameLayout.LayoutParams
        if (v.parent === r) {
            if (old == null || old.gravity != lp.gravity || old.leftMargin != lp.leftMargin ||
                old.topMargin != lp.topMargin) v.layoutParams = lp
            return
        }
        (v.parent as? ViewGroup)?.removeView(v)
        val idx = below?.let { r.indexOfChild(it) } ?: -1
        if (idx >= 0) r.addView(v, idx, lp) else r.addView(v, lp)
    }

    // ---------- 刷新：可用性 / 顶栏排不下 / 整组显隐 ----------

    private var refreshPosted = false
    private var remeasurePending = false

    /**
     * 合并到下一拍再刷（布局回调里不能直接改可见性，见 [reflow] 的注释）。
     * [remeasure] = 刚布局过，读数的宽度可能变了，顶栏排不排得下要重新量。
     */
    fun requestRefresh(remeasure: Boolean = false) {
        remeasurePending = remeasurePending || remeasure
        if (refreshPosted) return
        refreshPosted = true
        main.post { refresh() }
    }

    /**
     * 宿主状态变了（开关了画板 / 草稿纸、切了标签页）后调：重算每颗工具能不能用、顶栏排不排得下。
     * 宿主每次刷 HUD 都会调（滚动时每帧），所以「哪几颗可显示 + 顶栏多宽」没变就不重量。
     */
    fun refresh() {
        refreshPosted = false
        for (c in cells.values) {
            val hidden = c.key in hostHidden
            var anyUsable = false
            for (it in c.items) {
                val usable = !hidden && it.available()
                if (usable && it.view.visibility == View.VISIBLE) anyUsable = true
                setWrap(it, if (usable) View.VISIBLE else View.GONE, blocked = false)
            }
            // 一份都不能用、又要求灰着显示：挑第一份按钮灰着露出来（同一个 key 的几份实例只露一份）。
            // 宿主直接把 View 本身藏掉的（控制栏里按画板类型显隐的那几颗）不算，照旧不显示。
            if (!anyUsable && showDisabled) {
                c.items.firstOrNull { it.view is ImageButton && it.view.visibility == View.VISIBLE }
                    ?.let { setWrap(it, View.VISIBLE, blocked = true) }
            }
        }
        val sig = StringBuilder().append(bar.pinAvailWidth())
        for (gr in rows) if (gr.g.docked && gr.g.pinned) {
            for (k in gr.g.keys) if (cells[k]?.live() == true) sig.append(',').append(k)
        }
        val s = sig.toString()
        if (s == lastSig && !remeasurePending) {
            applyVisibility()
            return
        }
        lastSig = s
        remeasurePending = false
        reflow()
    }

    private var lastSig = ""

    private fun setWrap(it: Item, vis: Int, blocked: Boolean) {
        if (it.wrap.visibility != vis) it.wrap.visibility = vis
        it.wrap.blocked = blocked
        // 宿主自己已经灰掉的（alpha 0.35）不再叠一层淡，否则淡到看不见
        val alpha = if (blocked && it.view.isEnabled) 0.35f else 1f
        if (it.wrap.alpha != alpha) it.wrap.alpha = alpha
    }

    /**
     * 顶栏里只有**固定组**会被收进 ⋯（其余组在滚动段里，排不下就滚动）：固定组宽过 [TopBar.pinAvailWidth] 时，
     * 先整组收 spillFirst——◀▶ 是一对，只收一个像 BUG；还不够就从右往左逐个收。
     * 需要的宽度一律按**全部可显示**的工具算（不是按当前没收的），否则收过一次就再也长不回来。
     * 可见性的改动**post 到下一拍**：这里可能在布局回调里，此刻改会被本轮布局吞掉。
     */
    private fun reflow() {
        val docked = rows.filter { it.g.docked && it.g.pinned }
        val avail = bar.pinAvailWidth()
        val spill = HashSet<Cell>()
        if (avail > 0) {
            val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val widths = HashMap<Cell, Int>()
            val live = ArrayList<Cell>()
            var need = 0
            for (gr in docked) {
                for (k in gr.g.keys) {
                    val c = cells[k] ?: continue
                    if (!c.live()) continue
                    c.box.measure(unspec, unspec)
                    widths[c] = c.box.measuredWidth
                    need += c.box.measuredWidth
                    live.add(c)
                }
            }
            val first = live.filter { metas[it.key]?.spillFirst == true }
            if (need > avail && first.isNotEmpty()) {
                spill.addAll(first)
                need -= first.sumOf { widths[it] ?: 0 }
            }
            var i = live.size - 1
            while (need > avail && i >= 0) {
                val c = live[i]
                // 读数（页码 / 延迟）收进 ⋯ 也点不了、看不见，不收
                if (c !in spill && c.liveView() is ImageButton) {
                    spill.add(c)
                    need -= widths[c] ?: 0
                }
                i--
            }
        }
        for (c in cells.values) c.spilled = c in spill
        main.post { applyVisibility() }
    }

    private fun applyVisibility() {
        for (c in cells.values) {
            val v = if (c.live() && !c.spilled) View.VISIBLE else View.GONE
            if (c.box.visibility != v) c.box.visibility = v
        }
        for (gr in rows) {
            val v = if (gr.hasLive()) View.VISIBLE else View.GONE
            if (gr.row.visibility != v) gr.row.visibility = v
        }
    }

    /**
     * ⋯ 菜单里的工具：顶栏排不下被收起来的（按顶栏上的顺序）+ 哪组都不在的（按登记顺序）。
     * 只列按钮（读数不列）；此刻不能用 / 被宿主灰掉的，[showDisabled] 开着时灰着列出、关着时不列。开关类画成可勾选项。
     */
    fun overflow(): List<TopBar.MenuItem> {
        val keys = ArrayList<String>()
        for (gr in rows) if (gr.g.docked) for (k in gr.g.keys) if (cells[k]?.spilled == true) keys.add(k)
        for (k in metas.keys) if (layout.groupOf(k) == null) keys.add(k)
        val out = ArrayList<TopBar.MenuItem>()
        for (k in keys) {
            val c = cells[k] ?: continue
            val it = c.liveItem() ?: continue
            val b = it.view as? ImageButton ?: continue
            val usable = !it.wrap.blocked && b.isEnabled
            if (!usable && !showDisabled) continue
            val m = metas[k] ?: continue
            out.add(TopBar.MenuItem(m.title, if (m.toggle) b.isActiveOn() else null, enabled = usable) {
                if (usable) b.performClick()
            })
        }
        return out
    }

    // ---------- 拖动整组 ----------

    /** 根坐标下顶栏的上下沿（顶栏收起 / 不可见时 null） */
    private fun barSpan(): IntArray? {
        val r = root ?: return null
        val v = bar.view
        if (!v.isShown) return null
        val rl = IntArray(2).also { r.getLocationInWindow(it) }
        val bl = IntArray(2).also { v.getLocationInWindow(it) }
        val top = bl[1] - rl[1]
        return intArrayOf(top, top + v.height)
    }

    private fun overBar(y: Float): Boolean = barSpan()?.let { y >= it[0] && y <= it[1] } == true

    private var dragging: GroupRow? = null
    private var touchX = 0f
    private var touchY = 0f

    private fun onDrag(e: DragEvent): Boolean {
        val gr = e.localState as? GroupRow ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_LOCATION -> bar.setDropHighlight(overBar(e.y))
            DragEvent.ACTION_DRAG_EXITED -> bar.setDropHighlight(false)
            DragEvent.ACTION_DROP -> {
                bar.setDropHighlight(false)
                drop(gr, e.x, e.y)
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                bar.setDropHighlight(false)
                gr.row.alpha = 1f
                dragging = null
            }
        }
        return true
    }

    /** 松手：落在顶栏上 = 并进去，按落点横坐标插到对应的两组之间；落在别处 = 浮在那里 */
    private fun drop(gr: GroupRow, x: Float, y: Float) {
        val r = root ?: return
        val g = gr.g
        val groups = layout.groups
        if (overBar(y)) {
            // 落点右边的第一组并在滚动段里、看得见的组 → 插在它前面；没有 → 插在最后一组并在顶栏里的组后面
            // （被拖的组一定不是固定组，它只会落进滚动段；固定组的先后只在编辑面板里调）
            val rl = IntArray(2).also { r.getLocationInWindow(it) }
            var before: ToolLayout.Group? = null
            for (other in rows) {
                if (other === gr || !other.g.docked || other.g.pinned || other.row.visibility != View.VISIBLE) continue
                val ol = IntArray(2).also { other.row.getLocationInWindow(it) }
                val cx = ol[0] - rl[0] + other.row.width / 2f
                if (x < cx) { before = other.g; break }
            }
            groups.remove(g)
            g.docked = true
            val at = if (before != null) groups.indexOf(before)
            else groups.indexOfLast { it.docked } + 1
            groups.add(at.coerceIn(0, groups.size), g)
        } else {
            g.docked = false
            g.x = (x - touchX).toInt()
            g.y = (y - touchY).toInt()
        }
        save()
        applyLayout()
    }

    /** 把手：按住挪过触摸阈值 = 拖整组；按住不动到长按时长 = 打开编辑面板 */
    private inner class GripTouch(private val gr: GroupRow) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var done = false
        private val slop = ViewConfiguration.get(a).scaledTouchSlop
        private val longPress = Runnable {
            done = true
            gr.grip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openEditor()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y
                    done = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)   // 别让顶栏的横滑抢走
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!done && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                        main.removeCallbacks(longPress)
                        done = true
                        startDrag()
                        return false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }

        private fun startDrag() {
            val row = gr.row
            touchX = gr.grip.left + downX
            touchY = gr.grip.top + downY
            // 影子带胶囊底（并在顶栏里时这一排是透明底，影子会只剩几颗图标飘着）
            val bg = row.background
            row.background = Ui.round(Ui.surface(a), Ui.PILL, a, Ui.outline(a))
            val shadow = object : View.DragShadowBuilder(row) {
                override fun onProvideShadowMetrics(size: Point, touch: Point) {
                    size.set(max(1, row.width), max(1, row.height))
                    touch.set(touchX.toInt(), touchY.toInt())
                }
                override fun onDrawShadow(c: Canvas) = row.draw(c)
            }
            val clip = ClipData(ClipDescription("tools", arrayOf(MIME)), ClipData.Item(""))
            if (row.startDragAndDrop(clip, shadow, gr, 0)) {
                dragging = gr
                row.alpha = 0f
            }
            row.background = bg
        }
    }

    // ---------- 画板 / 草稿纸控制栏的 key ----------

    private fun padTitle(k: String): String = a.getString(
        when (k) {
            "padList" -> R.string.tools_pad_list
            "padName" -> R.string.tools_pad_name
            "padPage" -> R.string.tools_pad_page
            "padRecenter" -> R.string.tools_pad_recenter
            "padFit" -> R.string.tools_pad_fit
            "padMap" -> R.string.tools_pad_map
            "padPages" -> R.string.tools_pad_pages
            "padPageUnder" -> R.string.tools_pad_page_under
            "padPaper" -> R.string.tools_pad_paper
            "padZoom" -> R.string.tools_pad_zoom
            "padClose" -> R.string.tools_pad_close
            else -> R.string.tools_group_untitled
        },
    )

    companion object {
        const val GRIP_W = 18
        private const val PREF_LAYOUT = "layout"
        private const val PREF_SHOW_DISABLED = "showDisabled"
        private const val MIME = "application/x-unireader-tools"
        private val PAD_TOGGLES = setOf("padMap", "padPageUnder")

        /** 模式1 草稿纸控制栏（`ScratchController` 那一排）从左到右的 key */
        val SCRATCH_BAR_KEYS = listOf(
            "padList", "padName", "padRecenter", "padFit", "padMap", "padPageUnder", "padPaper", "padZoom", "padClose",
        )

        /** 模式1 画板控制栏（`BoardController` 那一排） */
        val BOARD_BAR_KEYS = listOf(
            "padList", "padName", "padPage", "padRecenter", "padFit", "padMap", "padPages", "padPaper", "padZoom",
        )

        /** 模式2 草稿纸 / 画板控制栏（`PadScratch` 那一排，两种会话共用） */
        val PAD2_BAR_KEYS = listOf(
            "padList", "padName", "padPage", "padRecenter", "padFit", "padMap", "padPageUnder", "padPaper",
            "padZoom", "padClose",
        )
    }
}
