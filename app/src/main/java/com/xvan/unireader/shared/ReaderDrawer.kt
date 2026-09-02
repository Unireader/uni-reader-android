package com.xvan.unireader.shared

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.min

/**
 * 左侧拉抽屉，**模式1 与模式2 共用这一份**：「目录」= 当前文档的 PDF 目录（可折叠树，
 * 点条目跳到章节标题那一行）；「书库」= 工作区全部文档，点它打开。
 *
 * 它原先只有模式2 有（住在 `pad/`，直接吃 `WireCodec.TocEntry`/`LibEntry`）。2026-08-28
 * 用户要「模式1 也要有目录」，于是搬进 `shared/` 并把数据换成中立模型（[TocItem]/[LibItem]）：
 * 模式2 从 Mac 的 `toc`/`library` 广播转一层，模式1 从本机 Pdfium 书签与工作区 SQLite 读。
 * **依赖方向仍是单向的**（`pad`/`local` → `shared`），这边一行都不认识 `WireCodec`。
 *
 * 目录数据是**先序拍平 + depth**（`../PROTOCOL.md §4.2`），树结构在这里按 depth 就地重建：
 * 前一项 depth 更小者即父。线上不必编码嵌套，这边也不必递归。
 *
 * 用自绘覆盖层而不是 [Sheet]：抽屉要贴着左边缘满高滑出，而 Sheet 是居中卡片。
 * 它直接加进 Activity 的 root FrameLayout，显示时盖住画布（抽屉开着时不该还能在下面写字）。
 */
class ReaderDrawer(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Drawer"

        /** 打开时停在哪一页（[open] 的参数） */
        const val TAB_TOC = 0
        const val TAB_LIB = 1

        private const val MAX_W = 340        // 面板最宽（dp）：平板上不让它占掉半屏
        private const val WIDTH_RATIO = 0.82f
    }

    private var tab = TAB_TOC
    private var toc: List<TocItem> = emptyList()
    private var tocDocId = ""
    private var docV = ""
    private var lib: List<LibItem> = emptyList()
    private var wsName = ""
    private var curPage = 0
    private val expanded = HashSet<Int>()
    private var bookmarks: List<BookmarkItem> = emptyList()
    private var bmDocId = ""

    /** 点目录条目：0-based 页 + 页内纵向比例 */
    var onJump: (Int, Float) -> Unit = { _, _ -> }

    /** 点书库条目：库文档 id */
    var onOpenDoc: (String) -> Unit = {}

    // —— 书签（`../REQUIREMENTS.md §1.9`）。三个都只是**请求**：模式2 发给 Mac、模式1 落本机库，
    // 两边都由各自的真源回推后再 [setBookmarks]，本类不改自己的表。
    /** 加一枚：参数 = 名字（落点由调用方定，两模式都取「当前视口顶」，与 Mac ⌘D 同口径） */
    var onAddBookmark: (String) -> Unit = {}
    var onRenameBookmark: (String, String) -> Unit = { _, _ -> }
    var onDeleteBookmark: (String) -> Unit = {}

    private val tocTab = tabButton("目录", R.drawable.ic_list) { switchTo(TAB_TOC) }
    private val libTab = tabButton("书库", R.drawable.ic_book) { switchTo(TAB_LIB) }

    private val bodyBox = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(16))
    }

    private val scroll = ScrollView(a).apply {
        isFillViewport = false
        addView(bodyBox, FrameLayout.LayoutParams(-1, -2))
    }

    private val panel = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Ui.surface(a))
        isClickable = true          // 吃掉点击，别穿到下面的遮罩把自己关掉
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(a.dp(10), a.dp(8), a.dp(10), a.dp(8))
                addView(tocTab)
                addView(libTab, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
                addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))   // 把关闭键顶到最右
                addView(Ui.iconButton(a, R.drawable.ic_close, "关闭", Ui.onSurface(a)) { close() })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        // ⚠️ 分隔线**不许再传一份 LayoutParams**：[Ui.divider] 自带 `(MATCH_PARENT, 1)`，
        // 而这里从前传的是 `(-1, -2)`——裸 View 的 `onMeasure` 走 `getDefaultSize`，
        // **wrap_content 会被当成「撑满 AT_MOST」**，于是这条 1px 的线在竖向 LinearLayout 里
        // 长成 900+px，把 weight=1 的 [scroll] 挤成 0 高。表现就是「目录/书库两个页签都是空白」
        // （数据全在、行也建好了，只是没地方画；那片"空白"其实是 outline 色的分隔线本身）。
        addView(Ui.divider(a))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** 整个覆盖层：遮罩 + 面板。加进 Activity 的 root，初始隐藏。 */
    val view: FrameLayout = FrameLayout(a).apply {
        visibility = View.GONE
        setBackgroundColor(Color.argb(0x47, 0, 0, 0))
        isClickable = true
        setOnClickListener { close() }   // 点遮罩关抽屉
        val dm = a.resources.displayMetrics
        val w = min((dm.widthPixels * WIDTH_RATIO).toInt(), Ui.dp(a, MAX_W))
        addView(panel, FrameLayout.LayoutParams(w, -1, Gravity.START))
    }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun toggle() {
        if (isOpen) close() else open(tab)
    }

    fun open(which: Int = tab) {
        tab = which
        view.visibility = View.VISIBLE
        rebuild()
    }

    fun close() {
        view.visibility = View.GONE
    }

    private fun switchTo(which: Int) {
        tab = which
        rebuild()
    }

    /**
     * 目录到货。**不在这里核对 [docId]**，只记下来——切档时 `layout` 与 `toc` 两条广播的先后
     * 没有保证，核对留到渲染时（见 [tocReady]），不然先到的那条会被白白丢掉。
     */
    fun setToc(docId: String, list: List<TocItem>) {
        tocDocId = docId
        toc = list
        expanded.clear()
        Log.i(TAG, "收到目录 ${list.size} 条 docId=$docId（当前显示 docV=$docV）")
        if (isOpen && tab == TAB_TOC) rebuild()
    }

    /**
     * 书签到货（模式2 = Mac 的 `bookmarks` 广播；模式1 = 本机库读出来后调）。
     * 与 [setToc] 同一条纪律：**不在这里核对 [docId]**，留到渲染时（[bmReady]）。
     */
    fun setBookmarks(docId: String, list: List<BookmarkItem>) {
        bmDocId = docId
        bookmarks = list
        Log.i(TAG, "收到书签 ${list.size} 枚 docId=$docId（当前显示 docV=$docV）")
        if (isOpen && tab == TAB_TOC) rebuild()
    }

    fun setLibrary(ws: String, list: List<LibItem>) {
        wsName = ws
        lib = list
        Log.i(TAG, "收到书库 ${list.size} 条，工作区「$ws」")
        if (isOpen && tab == TAB_LIB) rebuild()
    }

    /** 当前显示文档的内容哈希（`layout` 广播带来）：与目录的 docId 一致才敢渲染 */
    fun setDocV(v: String) {
        if (docV == v) return
        docV = v
        if (isOpen && tab == TAB_TOC) rebuild()
    }

    /** 当前页（0-based）：目录的「当前章节」高亮与自动展开用 */
    fun setCurrentPage(p: Int) {
        if (curPage == p) return
        curPage = p
        if (isOpen && tab == TAB_TOC) rebuild()
    }

    private val tocReady: Boolean get() = toc.isNotEmpty() && tocDocId == docV
    private val bmReady: Boolean get() = bookmarks.isNotEmpty() && bmDocId == docV

    /** 一行：目录项（[bm] = null）或书签。[i] 只对目录行有意义（折叠态按它记）。 */
    private class Row(
        val i: Int,
        val depth: Int,
        val page: Int,
        val frac: Float,
        val label: String,
        val kids: Boolean,
        val parents: List<Int>,
        val bm: BookmarkItem? = null,
    )

    /** 纯目录的先序拍平（合并书签之前的那一份）。 */
    private fun tocRows(): List<Row> {
        if (!tocReady) return emptyList()
        val out = ArrayList<Row>(toc.size)
        val stack = ArrayList<Int>()          // stack[d] = 深度 d 的最近一项下标
        for (i in toc.indices) {
            val e = toc[i]
            while (stack.size > e.depth) stack.removeAt(stack.size - 1)
            out.add(
                Row(
                    i, e.depth, e.page, e.frac, e.label,
                    kids = i + 1 < toc.size && toc[i + 1].depth > e.depth,
                    parents = ArrayList(stack),
                ),
            )
            while (stack.size <= e.depth) stack.add(0)
            stack[e.depth] = i
        }
        return out
    }

    /** 目录 + 书签合并（规则在 [TocMerge]，与 Mac `TOCMerge.swift` / web `tocMerge.ts` 同源）。 */
    private fun rows(): List<Row> {
        val base = tocRows()
        val bms = if (bmReady) bookmarks else emptyList()
        if (bms.isEmpty()) return base
        val slots = TocMerge.place(base.map { TocMerge.Row(it.depth, it.page) }, bms.map { it.page })
        val ins = HashMap<Int, MutableList<Row>>()
        for ((k, b) in bms.withIndex()) {
            val s = slots[k]
            val owner = if (s.owner >= 0) base[s.owner] else null
            val parents = if (owner != null) owner.parents + owner.i else emptyList()
            ins.getOrPut(s.insertBefore) { ArrayList() }
                .add(Row(-1, s.depth, b.page, b.frac, b.title, kids = false, parents = parents, bm = b))
        }
        val out = ArrayList<Row>(base.size + bms.size)
        for (i in base.indices) {
            ins[i]?.let { out.addAll(it) }
            out.add(base[i])
        }
        ins[base.size]?.let { out.addAll(it) }
        return out
    }

    /**
     * 当前章节：起点不晚于当前页的项里页码最大的那个，并列取先序靠后（更深一层）的。
     *
     * 不能简单取「最后一个 page <= curPage」——真实 PDF 的书签先序页码常常不单调（Mac 端
     * `TOCListView` 同款注释：整本书末尾挂两个空 destination 的项，一旦当成第 0 页，高亮就
     * 永远钉在最后一项）。取 argmax 对乱序书签同样免疫。
     */
    private fun currentIndex(rs: List<Row>): Int {
        var best = -1
        var bestPage = -1
        for (r in rs) {
            // 只认目录行：追踪回答的是「我在第几章」，跳到书签行上没有意义（同 Mac / web）
            if (r.bm != null || r.page < 0 || r.page > curPage) continue
            if (r.page >= bestPage) { bestPage = r.page; best = r.i }
        }
        return best
    }

    private fun rebuild() {
        tocTab.isSelected = tab == TAB_TOC
        libTab.isSelected = tab == TAB_LIB
        paintTab(tocTab, tab == TAB_TOC)
        paintTab(libTab, tab == TAB_LIB)
        bodyBox.removeAllViews()
        if (tab == TAB_TOC) buildToc() else buildLib()
    }

    private fun buildToc() {
        // 「添加书签」常驻在最上面：没目录的书也得能加（落点 = 当前视口顶，与 Mac ⌘D 同口径）
        bodyBox.addView(addBookmarkRow(), LinearLayout.LayoutParams(-1, -2))
        if (!tocReady && !bmReady) {
            bodyBox.addView(Ui.body(a, "本文档没有目录"))
            return
        }
        val rs = rows()
        val cur = currentIndex(rs)
        // 自动追踪：展开当前章节的祖先链（只增展开，不动用户手动折叠的其它分支）
        if (cur >= 0) rs.firstOrNull { it.bm == null && it.i == cur }?.let { expanded.addAll(it.parents) }
        var curView: View? = null
        for (r in rs) {
            if (!r.parents.all { expanded.contains(it) }) continue
            val row = if (r.bm != null) bookmarkRow(r, r.bm) else tocRow(r, r.i == cur)
            bodyBox.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (r.bm == null && r.i == cur) curView = row
        }
        // 展开后把当前章节滚到视野中间
        val target = curView ?: return
        scroll.post { scroll.smoothScrollTo(0, (target.top - scroll.height / 2).coerceAtLeast(0)) }
    }

    private fun tocRow(r: Row, isCur: Boolean): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(r.depth * a.dp(14), 0, 0, 0)
        // 折叠箭头：有子项才是按钮，没有也占同样宽度，标题才不会左右跳
        addView(
            if (r.kids) {
                Ui.iconButton(a, R.drawable.ic_chevron_right, "展开/折叠", Ui.onVariant(a)) {
                    if (!expanded.remove(r.i)) expanded.add(r.i)
                    rebuild()
                }.apply { rotation = if (expanded.contains(r.i)) 90f else 0f }
            } else {
                View(a)
            },
            LinearLayout.LayoutParams(a.dp(30), a.dp(30)),
        )
        val dead = r.page < 0
        val label = TextView(a).apply {
            text = r.label.ifEmpty { "—" }
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (isCur) Ui.accent(a) else if (dead) Ui.onVariant(a) else Ui.onSurface(a))
        }
        val pageNo = TextView(a).apply {
            // 坏书签留空而不是显示「1」：配合不可点表达「跳不过去」（同 Mac 端 TOCListView）
            text = if (dead) "" else "${r.page + 1}"
            textSize = 12f
            setTextColor(if (isCur) Ui.accent(a) else Ui.onVariant(a))
        }
        val hit = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(6), a.dp(9), a.dp(8), a.dp(9))
            if (isCur) background = Ui.round(Ui.col(a, R.color.accent_container), Ui.RADIUS, a)
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pageNo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
            if (!dead) {
                isClickable = true
                background = Ui.rippleOver(a, background, Ui.RADIUS, Ui.onSurface(a))
                setOnClickListener {
                    onJump(r.page, r.frac)
                    close()          // 跳完就收起，让出画布
                }
            }
        }
        addView(hit, LinearLayout.LayoutParams(0, -2, 1f))
    }

    // ---------- 书签（`../REQUIREMENTS.md §1.9`） ----------

    /** 顶上那条「添加书签」。名字必填 → 先弹输入框，输完才发请求。 */
    private fun addBookmarkRow(): View = PadPanels.iconRow(a, R.drawable.ic_bookmark, "添加书签",
        tint = Ui.onSurface(a)) {
        val edit = PadPanels.inputBox(a, "书签名字")
        Sheet(a).title("添加书签")
            .content(edit)
            .action("取消")
            .action("添加", primary = true) {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onAddBookmark(t)   // 名字必填：本端也守一遍
            }
            .show()
    }

    /**
     * 一行书签：缎带图标 + 名字 + 页码，行尾两枚键（改名 / 删除）。
     * 缩进与目录行同一套（挂在一级组下时 depth=1），于是它读起来就是那一组里的一项。
     */
    private fun bookmarkRow(r: Row, b: BookmarkItem): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(r.depth * a.dp(14), 0, 0, 0)
        // 纯标记，不可点（可点的是右边两枚键与整行）
        addView(
            ImageView(a).apply {
                setImageResource(R.drawable.ic_bookmark)
                imageTintList = ColorStateList.valueOf(Ui.col(a, R.color.bookmark))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = a.dp(8)
                setPadding(p, p, p, p)
            },
            LinearLayout.LayoutParams(a.dp(30), a.dp(30)),
        )
        val label = TextView(a).apply {
            text = b.title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Ui.onSurface(a))
        }
        val pageNo = TextView(a).apply {
            text = "${b.page + 1}"
            textSize = 12f
            setTextColor(Ui.onVariant(a))
        }
        val hit = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(6), a.dp(9), a.dp(8), a.dp(9))
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pageNo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
            setOnClickListener { onJump(b.page, b.frac); close() }
        }
        addView(hit, LinearLayout.LayoutParams(0, -2, 1f))
        addView(
            // 改名用 ic_text（草稿纸的「改名…」也是它，别为这一处新造图标）
            Ui.iconButton(a, R.drawable.ic_text, "重命名", Ui.onVariant(a)) { showRenameBookmark(b) },
            LinearLayout.LayoutParams(a.dp(30), a.dp(30)),
        )
        addView(
            Ui.iconButton(a, R.drawable.ic_close, "删除", Ui.onVariant(a)) { onDeleteBookmark(b.id) },
            LinearLayout.LayoutParams(a.dp(30), a.dp(30)),
        )
    }

    private fun showRenameBookmark(b: BookmarkItem) {
        val edit = PadPanels.inputBox(a, "书签名字").apply {
            setText(b.title)
            setSelection(text.length)
        }
        Sheet(a).title("重命名")
            .content(edit)
            .action("取消")
            .action("保存", primary = true) {
                val t = edit.text.toString().trim()
                if (t.isNotEmpty()) onRenameBookmark(b.id, t)
            }
            .show()
    }

    private fun buildLib() {
        if (wsName.isNotEmpty()) bodyBox.addView(Ui.groupTitle(a, wsName, top = 4))
        if (lib.isEmpty()) {
            bodyBox.addView(Ui.body(a, "工作区里还没有文档"))
            return
        }
        for (d in lib) {
            val badge = if (!d.open) {
                null
            } else {
                TextView(a).apply {
                    text = "已打开"
                    textSize = 11f
                    setTextColor(Ui.accent(a))
                    setPadding(a.dp(7), a.dp(1), a.dp(7), a.dp(1))
                    background = Ui.round(Ui.col(a, R.color.accent_container), Ui.PILL, a)
                }
            }
            bodyBox.addView(libRow(d, badge))
        }
    }

    private fun libRow(d: LibItem, badge: View?): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
        setPadding(a.dp(8), a.dp(12), a.dp(8), a.dp(12))
        setOnClickListener {
            // 已打开的也照发：两模式的接收端都会自己识别并切过去，而不是重复开一份
            // （模式2 = Mac `openPadDoc`，见 `../PROTOCOL.md §4.1`；模式1 = `openDoc` 切到那个标签页）
            onOpenDoc(d.id)
            close()
        }
        addView(
            ImageView(a).apply {
                setImageResource(R.drawable.ic_doc)
                imageTintList = ColorStateList.valueOf(Ui.onVariant(a))
            },
            LinearLayout.LayoutParams(a.dp(20), a.dp(20)).apply { marginEnd = a.dp(12) },
        )
        addView(
            TextView(a).apply {
                text = d.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(Ui.onSurface(a))
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (badge != null) addView(badge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = a.dp(8) })
    }

    private fun tabButton(text: String, icon: Int, onClick: () -> Unit): TextView = TextView(a).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = a.dp(6)
        setPadding(a.dp(12), a.dp(7), a.dp(14), a.dp(7))
        isClickable = true
        setOnClickListener { onClick() }
        paintTab(this, false)
    }

    private fun paintTab(v: TextView, on: Boolean) {
        val fg = if (on) Ui.col(a, R.color.on_accent) else Ui.onSurface(a)
        val bg = if (on) Ui.accent(a) else Ui.container(a)
        v.setTextColor(fg)
        v.compoundDrawableTintList = ColorStateList.valueOf(fg)
        v.background = Ui.rippleOver(a, Ui.round(bg, Ui.PILL, a), Ui.PILL, fg)
    }

    private fun Activity.dp(v: Int) = Ui.dp(this, v)
}
