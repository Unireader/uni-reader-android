package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.BoardItem
import com.xvan.unireader.local.store.BoardNote
import com.xvan.unireader.local.store.BoardPage
import com.xvan.unireader.local.store.BoardPageSet
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.newBoardId
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.BoardPaging
import com.xvan.unireader.shared.NewBoardSheet
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.ScratchCanvas
import com.xvan.unireader.shared.ScratchGeom
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.Ui.setActive
import com.xvan.unireader.shared.setTextIfChanged
import com.xvan.unireader.shared.showAlert
import java.io.File
import kotlin.math.abs
import org.json.JSONObject

/**
 * 模式1 的画板笔记（`../BOARD-NOTE-PLAN.md §5`）：`ScratchCanvas`（几何/输入/渲染）↔ `LibraryStore`
 * 的 `board_note` / `board_item` / `board_page` 三张表。照 [ScratchController] 写，差别只在：
 *
 * - **整页就是这张纸**：画板标签激活时画布铺满内容区，没有「关闭」、没有页面底图、没有图钉；
 * - 读写换成 board_note / board_item：笔迹落 kind=1（payload 与草稿纸 kind=4 同一份 JSON 但**不写 padId**，
 *   x/y/w/h = 包围盒），改纸样 / 改名写 board_note 并更新 updated_at；
 * - 图片（kind=2）从 `<工作区>/Images/<sha>.<ext>` 读来显示，**本轮只显示不编辑**（与平板一致）。
 *
 * **分页画板（v17，§9）**：有 `board_page` 行就是分页。画布上一律是画布坐标（页按布局契约竖排），
 * 条目落库时按「第一个点 / 矩形上沿落在哪页」换成**页内坐标** + payload `page`（换算只在 [BoardPageSet]）。
 * 插页 / 删页 / 改尺寸只写 `board_page`（删页连同页上的条目删行），写完**重读**一遍——库里的页内坐标不变，
 * 重读时按新布局换算，受影响的条目自然就挪到了新位置，一行没变的条目都不重写（同 Mac 的纪律）。
 * 本端画板没有撤销栈（Mac 那边改结构后清栈），所以这里不涉及。
 *
 * 数据纪律同 [ScratchController]：写库全走 [StoreQueue]，回主线程先核对「还是不是这一篇」；
 * 落笔乐观落地，写失败才撤掉并重读；擦除收尾一次 reconcile。
 * 视口不落库不上线，打开一律回画布原点 / 分页停在第一页顶（三端契约）。
 */
class BoardController(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Board"

        /** 图片文件的扩展名兜底顺序（`image` 表没登记时逐个试；同 Mac `ImageAssets.passthroughExts`） */
        val IMAGE_EXTS = listOf("png", "jpg", "gif", "webp", "jpeg")

        /**
         * 新建一篇画板（书库页 / 阅读界面共用）：写 `board_note`，分页再写初始页（sort_key 1…N，整本同尺寸同背景）。
         * 在队列线程上调；一个事务，失败什么都不留。
         */
        fun createBoard(s: LibraryStore, b: BoardNote, spec: NewBoardSheet.Spec?) {
            s.transaction {
                s.upsertBoard(b)
                if (spec != null && spec.paged) {
                    val n = spec.count.coerceIn(BoardPaging.MIN_PAGES, BoardPaging.MAX_PAGES)
                    for (i in 0 until n) {
                        s.upsertBoardPage(
                            BoardPage(
                                id = newBoardId(), boardId = b.id, sortKey = (i + 1).toDouble(),
                                width = spec.w.toDouble(), height = spec.h.toDouble(),
                                template = BoardPaging.templateRaw(spec.template),
                                createdAt = b.createdAt, updatedAt = b.createdAt,
                            ),
                        )
                    }
                }
            }
        }
    }

    val canvas = ScratchCanvas(a)

    /** 浮在画板上的工具条（ReaderActivity 加进 root、贴 chrome 下方居中；画板标签激活时才 VISIBLE） */
    val barView: View

    /** 宿主给：当前工具快照（笔 / 橡皮 / 尺子；每次落笔现取） */
    var toolsProvider: (() -> ScratchCanvas.Tools?)? = null

    /** 工具条最左那颗键：画板笔记列表（宿主接：切换 / 新建） */
    var onBoardList: (() -> Unit)? = null

    /** 标题 / 纸样写库成功后回调（宿主刷新标签页标题、抽屉列表） */
    var onBoardChanged: ((BoardNote) -> Unit)? = null

    private var queue: StoreQueue? = null
    private var workspace: File? = null

    /** 当前开着的那篇；null = 没开（画板标签没激活） */
    var board: BoardNote? = null
        private set

    /** 当前这篇的页（界面上显示的那份；空 = 无限画布）。落库换算、判「哪页有内容」都以它为准 */
    private var pageSet: BoardPageSet = BoardPageSet.NONE

    /** 页结构每重读一次 +1：落笔排队期间页变了，收尾时要按新布局重读一遍笔迹（见 [commitInk]） */
    private var layoutGen = 0

    private val barName: TextView
    private val barZoom: TextView
    private val barPage: TextView
    private val mapBtn: ImageButton
    private val pagesBtn: ImageButton

    init {
        canvas.visibility = View.GONE
        canvas.emptyHintTitle = a.getString(R.string.board_empty_hint)
        canvas.pullHintText = a.getString(R.string.board_pull_hint)
        canvas.onStrokeEnd = { pen, pts -> commitInk(pen, pts) }
        canvas.onEraseFinish = { snapshot -> commitErase(snapshot) }
        canvas.onViewportChanged = { updateBar() }
        canvas.onPullAddPage = { appendPages(1) }
        canvas.tools = { toolsProvider?.invoke() }
        canvas.picSource = ScratchCanvas.BoardPicSource { key, cb -> loadImage(key, cb) }

        // 工具条：与草稿纸同款悬浮胶囊（对比度同 handoff §7.2：不透明 surface + outline 描边）
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.surface(a), Ui.PILL, a, Ui.outline(a))
            val h = Ui.dp(a, 2)
            setPadding(Ui.dp(a, 6), h, Ui.dp(a, 6), h)
        }
        val on = Ui.onSurface(a)
        row.addView(Ui.iconButton(a, R.drawable.ic_list, a.getString(R.string.board_list), on) { onBoardList?.invoke() })
        barName = Ui.title(a, "", 14f).apply {
            maxLines = 1
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(barName)
        // 分页：页码读数（「第 i / N 页」，字号区分层级，颜色照常 onSurface）
        barPage = Ui.body(a, "", variant = false).apply {
            textSize = 12f
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
            visibility = View.GONE
        }
        row.addView(barPage)
        row.addView(Ui.iconButton(a, R.drawable.ic_scope, "回中", on) { canvas.recenter() })
        row.addView(Ui.iconButton(a, R.drawable.ic_fit, "适应内容", on) { canvas.fitContent() })
        mapBtn = Ui.iconButton(a, R.drawable.ic_map, "缩略图", on) {
            canvas.minimapOn = !canvas.minimapOn
            updateBar()
        }
        row.addView(mapBtn)
        // 分页：「页面」弹层（插页 / 删页 / 背景 / 多选 / 整本尺寸）
        pagesBtn = Ui.iconButton(a, R.drawable.ic_doc, a.getString(R.string.board_pages), on) { showPagesPanel() }
        pagesBtn.visibility = View.GONE
        row.addView(pagesBtn)
        row.addView(Ui.iconButton(a, R.drawable.ic_paper, "纸样", on) { showPaperPanel() })
        barZoom = Ui.body(a, "", variant = false).apply {
            textSize = 12f
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(barZoom)
        barView = row
        barView.visibility = View.GONE
    }

    /** 切工作区 / 开库之后绑一次（[q] = null 表示工作区关了） */
    fun bind(q: StoreQueue?, ws: File?) {
        queue = q
        workspace = ws
        if (q == null) close()
    }

    fun displayName(b: BoardNote): String = b.title.ifEmpty { a.getString(R.string.board_untitled) }

    // ---------- 开 / 关 ----------

    /**
     * 打开一篇（画板标签激活时调）：先按手上的行 + 页立刻铺纸（分页按页宽适配、停在首页顶；无限画布回中），
     * 再在队列上读笔迹 + 图片 + 记「最近打开」。[pages] 由宿主与画板行一起读好（分页的首屏不闪一下无限画布）。
     * 已经开着同一篇就只把画布显示出来（切回标签页不回中——视口是这一篇在本机的状态）。
     */
    fun open(b: BoardNote, pages: List<BoardPage>) {
        val q = queue ?: return
        val same = board?.id == b.id
        board = b
        canvas.setPaper(b.bg, b.pattern)
        canvas.visibility = View.VISIBLE
        barView.visibility = View.VISIBLE
        if (same) { updateBar(); return }
        applyPages(BoardPageSet(pages))
        canvas.setPics(emptyList())
        canvas.openSession()   // 打开一律回中 / 首页顶（视口不落库不上线，三端各自独立缩放滚动）
        updateBar()
        Log.i(TAG, "打开画板 ${b.id.take(8)}《${b.title}》页数=${pages.size}")
        val id = b.id
        val ps = pageSet
        q.submit("读画板 ${id.take(8)}", { s ->
            runCatching { s.touchBoardOpened(id) }.onFailure { Log.w(TAG, "记最近打开失败", it) }
            readContents(s, id, ps)
        }, { c ->
            if (board?.id != id) return@submit   // 排队期间换了一篇 / 关了
            applyContents(c)
            Log.i(TAG, "画板 ${id.take(8)} 笔迹 ${c.strokes.size} 条，图片 ${c.pics.size} 张")
        })
    }

    /** 收起（切到别的标签页 / 关掉画板标签）。视口不留：下次打开照旧回中 */
    fun close() {
        if (board == null) return
        board = null
        canvas.visibility = View.GONE
        barView.visibility = View.GONE
        canvas.setPics(emptyList())
        canvas.setStrokes(emptyList())
        applyPages(BoardPageSet.NONE)
    }

    /** 宿主在别处（书库 / 抽屉）改了名或删掉了当前这篇：跟着库走 */
    fun applyExternal(b: BoardNote?) {
        val cur = board ?: return
        if (b == null) { close(); return }
        if (b.id != cur.id) return
        board = b
        canvas.setPaper(b.bg, b.pattern)
        updateBar()
    }

    // ---------- 读内容（队列线程）/ 上屏（主线程） ----------

    private class Contents(
        val pages: BoardPageSet,
        val strokes: List<Stroke>,
        val pics: List<ScratchCanvas.BoardPic>,
        val exts: Map<String, String>,
    )

    /** 按 [ps] 把一篇的笔迹与图片读成画布坐标（队列线程） */
    private fun readContents(s: LibraryStore, id: String, ps: BoardPageSet): Contents {
        val items = s.boardItems(id)
        val exts = HashMap<String, String>()
        val pics = items.filter { it.kind == BoardItem.KIND_IMAGE }.mapNotNull { it.toPic(ps) }
        for (p in pics) if (p.key !in exts) s.imageExt(p.key)?.let { exts[p.key] = it }
        return Contents(ps, s.boardStrokes(id, ps), pics, exts)
    }

    private fun applyContents(c: Contents, keep: List<Stroke> = emptyList()) {
        imageExts.clear()
        imageExts.putAll(c.exts)
        canvas.setStrokes(c.strokes, keep)
        canvas.setPics(c.pics)
        updateBar()
    }

    /** 换一份页：画布按新布局排页（分页 ↔ 无限的切换由画布自己重新摆放） */
    private fun applyPages(ps: BoardPageSet) {
        pageSet = ps
        layoutGen++
        val t = IntArray(ps.pages.size) { BoardPaging.templateCode(ps.pages[it].template) }
        canvas.setPages(ps.layout.width, ps.layout.height, t)
    }

    // ---------- 图片（只显示不编辑） ----------

    /** sha → 扩展名（开画板时从 `image` 表查好；查不到的按 [IMAGE_EXTS] 逐个试） */
    private val imageExts = HashMap<String, String>()

    /**
     * kind=2 条目 → 画布上的一张图。payload `{image, caption, source[, page]}`，位置在 x/y/w/h 列；
     * 带 `page` 的是页内坐标，加上那页的左上角；那页不在（孤儿）→ 不显示（同 Mac `BoardImage(item:origin:)`）。
     */
    private fun BoardItem.toPic(ps: BoardPageSet): ScratchCanvas.BoardPic? {
        val o = try {
            JSONObject(String(payload, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        } ?: return null
        val sha = o.optString("image")
        if (sha.isEmpty() || w <= 0 || h <= 0) return null
        var dx = 0f
        var dy = 0f
        val pg = o.optString("page")
        if (pg.isNotEmpty()) {
            val r = ps.originOf(pg) ?: return null
            dx = r.ox; dy = r.oy
        }
        return ScratchCanvas.BoardPic(id, sha, x.toFloat() + dx, y.toFloat() + dy, w.toFloat(), h.toFloat())
    }

    /** 从 `<工作区>/Images/<sha>.<ext>` 读图（后台线程；慢卷上读文件 + 解码都不该在主线程） */
    private fun loadImage(sha: String, cb: (android.graphics.Bitmap?) -> Unit) {
        val ws = workspace ?: run { cb(null); return }
        val known = imageExts[sha]
        Bg.submit("读画板图片 ${sha.take(8)}") {
            val dir = File(ws, "Images")
            val exts = (listOfNotNull(known) + IMAGE_EXTS).distinct()
            val f = exts.map { File(dir, "$sha.$it") }.firstOrNull { it.isFile }
            val bmp = f?.let { runCatching { ScratchCanvas.decodePic(it.readBytes()) }.getOrNull() }
            if (bmp == null) Log.w(TAG, "画板图片读不到 ${sha.take(8)}（${f?.name ?: "文件不在 Images/ 里"}）")
            cb(bmp)
        }
    }

    // ---------- 纸样面板（底纹三选一 + 纸色六选一 + 改名） ----------

    fun showPaperPanel() {
        val b = board ?: return
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(a).title(displayName(b))

        // 分页画板的页底是纸色、背景走页模板，无限画布的底纹在这里没有意义
        if (!pageSet.paged) {
            root.addView(Ui.groupTitle(a, "底纹", top = 0))
            for ((key, label) in ScratchController.PATTERN_LABELS) {
                root.addView(optionRow(label, b.pattern == key) {
                    setPaper(bg = null, pattern = key)
                    dlg?.dismiss()
                })
            }
        }

        root.addView(Ui.groupTitle(a, "纸色", top = if (pageSet.paged) 0 else 16))
        val swatches = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, css) in ScratchController.PALETTE) {
            val cur = ScratchController.sameColor(b.bg, css)
            val c = ScratchGeom.parseCssRgba(css)!!
            val v = View(a).apply {
                background = Ui.round(
                    Color.argb(c[3], c[0], c[1], c[2]), 8, a,
                    if (cur) Ui.accent(a) else Ui.outline(a),
                )
                contentDescription = name
                isClickable = true
                setOnClickListener {
                    setPaper(bg = css, pattern = null)
                    dlg?.dismiss()
                }
            }
            swatches.addView(v, LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)).apply {
                marginEnd = Ui.dp(a, 10)
            })
        }
        root.addView(swatches)

        root.addView(Ui.groupTitle(a, "管理"))
        root.addView(
            PadPanels.iconRow(a, R.drawable.ic_text, a.getString(R.string.board_rename)) {
                dlg?.dismiss()
                showRename()
            },
        )

        sheet.content(root)
        sheet.action(a.getString(R.string.common_done), primary = true)
        dlg = sheet.show()
    }

    private fun optionRow(label: String, cur: Boolean, onClick: () -> Unit): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            setPadding(Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12))
            setOnClickListener { onClick() }
            addView(
                TextView(a).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Ui.onSurface(a))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (cur) {
                addView(
                    ImageView(a).apply {
                        setImageResource(R.drawable.ic_check)
                        imageTintList = ColorStateList.valueOf(Ui.accent(a))
                    },
                    LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)),
                )
            }
        }

    private fun showRename() {
        val b = board ?: return
        val edit = PadPanels.inputBox(a, a.getString(R.string.board_name_hint)).apply {
            setText(b.title)
            setSelection(text.length)
        }
        Sheet(a).title(a.getString(R.string.board_rename_title))
            .content(edit)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_save), primary = true) { rename(edit.text.toString().trim()) }
            .show()
    }

    // ---------- 页面弹层（分页画板：当前页 = 视口中心所在页） ----------

    fun showPagesPanel() {
        if (!pageSet.paged) return
        val pages = pageSet.pages
        val i = canvas.currentPageIndex().coerceIn(0, pages.size - 1)
        val cur = pages[i]
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null

        root.addView(Ui.groupTitle(a, a.getString(R.string.board_this_page, i + 1), top = 0))
        root.addView(PadPanels.iconRow(a, R.drawable.ic_plus, a.getString(R.string.board_insert_before)) {
            dlg?.dismiss(); insertPage(cur.id, after = false)
        })
        root.addView(PadPanels.iconRow(a, R.drawable.ic_plus, a.getString(R.string.board_insert_after)) {
            dlg?.dismiss(); insertPage(cur.id, after = true)
        })
        root.addView(PadPanels.iconRow(a, R.drawable.ic_paper, a.getString(R.string.board_page_background)) {
            dlg?.dismiss(); pickTemplate(a.getString(R.string.board_page_bg_current, i + 1), setOf(cur.id))
        })
        root.addView(
            PadPanels.iconRow(a, R.drawable.ic_delete, a.getString(R.string.board_delete_page), Ui.col(a, R.color.danger)) {
                dlg?.dismiss(); confirmDeletePages(setOf(i))
            },
        )

        root.addView(Ui.groupTitle(a, a.getString(R.string.board_pages)))
        root.addView(PadPanels.iconRow(a, R.drawable.ic_paper, a.getString(R.string.board_all_pages_bg)) {
            dlg?.dismiss(); pickTemplate(a.getString(R.string.board_all_pages_bg), pages.map { it.id }.toSet())
        })
        root.addView(PadPanels.iconRow(a, R.drawable.ic_list, a.getString(R.string.board_select_pages)) {
            dlg?.dismiss(); showSelectPages()
        })
        root.addView(PadPanels.iconRow(a, R.drawable.ic_doc, a.getString(R.string.board_page_size_all)) {
            dlg?.dismiss(); showPageSize()
        })

        dlg = Sheet(a).title(a.getString(R.string.board_pages))
            .content(root)
            .action(a.getString(R.string.common_done), primary = true)
            .show()
    }

    /** 背景模板六选一（[ids] 这几页一起改；它们现在都是同一个模板时打勾） */
    private fun pickTemplate(title: String, ids: Set<String>) {
        val cur = pageSet.pages.filter { it.id in ids }.map { BoardPaging.templateCode(it.template) }.toSet()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        NewBoardSheet.templateLabels(a).forEachIndexed { t, label ->
            root.addView(optionRow(label, cur.size == 1 && t in cur) {
                dlg?.dismiss(); setTemplates(ids, t)
            })
        }
        dlg = Sheet(a).title(title).content(root).action(a.getString(R.string.common_cancel)).show()
    }

    /** 多选页：勾几页 → 一起设背景 / 一起删除（同 Mac 页面弹层的页列表多选） */
    private fun showSelectPages() {
        val pages = pageSet.pages
        if (pages.isEmpty()) return
        val labels = NewBoardSheet.templateLabels(a)
        val chosen = LinkedHashSet<Int>()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        pages.forEachIndexed { i, p ->
            val label = a.getString(R.string.board_this_page, i + 1) + " · " +
                labels.getOrElse(BoardPaging.templateCode(p.template)) { labels[0] }
            root.addView(
                CheckBox(a).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Ui.onSurface(a))
                    setPadding(Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8), Ui.dp(a, 8))
                    setOnCheckedChangeListener { _, on -> if (on) chosen.add(i) else chosen.remove(i) }
                },
            )
        }
        Sheet(a).title(a.getString(R.string.board_select_pages))
            .content(root)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_delete)) {
                if (chosen.isNotEmpty()) confirmDeletePages(chosen.toSet())
            }
            .action(a.getString(R.string.board_background), primary = true) {
                if (chosen.isEmpty()) return@action
                val ids = chosen.mapNotNull { pages.getOrNull(it)?.id }.toSet()
                pickTemplate(a.getString(R.string.board_selected_n, ids.size), ids)
            }
            .show()
    }

    /** 整本页面大小：四个预设 + 横竖（当前尺寸对得上哪个预设就亮哪个） */
    private fun showPageSize() {
        val l = pageSet.layout
        val scr = NewBoardSheet.screenDp(a)
        var size = -1
        var landscape = l.width > l.height
        for (k in 0..3) {
            for (land in listOf(false, true)) {
                val wh = BoardPaging.pageSize(k, land, scr[0], scr[1])
                if (abs(wh[0] - l.width) < 0.5f && abs(wh[1] - l.height) < 0.5f && size < 0) {
                    size = k; landscape = land
                }
            }
        }
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sizeRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = ArrayList<TextView>()
        lateinit var sync: () -> Unit
        NewBoardSheet.sizeLabels(a).forEachIndexed { k, label ->
            val b = PadPanels.segButton(a, label) { size = k; sync() }
            btns.add(b)
            sizeRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (k < 3) marginEnd = Ui.dp(a, 8) })
        }
        root.addView(sizeRow)
        val orientRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, Ui.dp(a, 8), 0, 0)
        }
        val port = PadPanels.segButton(a, a.getString(R.string.board_portrait)) { landscape = false; sync() }
        val land = PadPanels.segButton(a, a.getString(R.string.board_landscape)) { landscape = true; sync() }
        orientRow.addView(port, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(a, 8) })
        orientRow.addView(land, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(orientRow)
        sync = {
            btns.forEachIndexed { k, b -> PadPanels.setSegActive(a, b, k == size) }
            PadPanels.setSegActive(a, port, !landscape)
            PadPanels.setSegActive(a, land, landscape)
        }
        sync()
        Sheet(a).title(a.getString(R.string.board_page_size_all))
            .content(root)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_done), primary = true) {
                val k = if (size < 0) BoardPaging.SIZE_A4 else size
                val wh = BoardPaging.pageSize(k, landscape, scr[0], scr[1])
                setPageSize(wh[0].toDouble(), wh[1].toDouble())
            }
            .show()
    }

    /** 删页：至少留一页；页上有内容先问一句（没内容直接删，同 Mac `confirmDeletePages`） */
    private fun confirmDeletePages(indices: Set<Int>) {
        val pages = pageSet.pages
        val del = indices.filter { it in pages.indices }.toSet()
        if (del.isEmpty()) return
        if (del.size >= pages.size) {
            a.showAlert(a.getString(R.string.board_pages), a.getString(R.string.board_keep_one_page))
            return
        }
        val l = pageSet.layout
        val hasContent = canvas.strokeList().any { s -> s.pts.isNotEmpty() && l.indexForY(s.pts[0].y) in del } ||
            canvas.picList().any { p -> l.indexForY(p.y) in del }
        val ids = del.map { pages[it].id }.toSet()
        if (!hasContent) { deletePages(ids); return }
        Sheet(a)
            .title(
                if (del.size == 1) a.getString(R.string.board_delete_page_confirm)
                else a.getString(R.string.board_delete_pages_confirm, del.size),
            )
            .subtitle(a.getString(R.string.board_delete_pages_msg))
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.common_delete), primary = true) { deletePages(ids) }
            .show()
    }

    // ---------- 页操作（写 board_page → 重读；按页 id 做，别拿下标——排队期间下标可能已经变了） ----------

    /** 在末尾加 [count] 页（沿用末页的尺寸与背景；到底上拉加页走这里） */
    fun appendPages(count: Int) {
        pageOp("画板加页 ×$count") { s, b, pages ->
            val last = pages.lastOrNull() ?: return@pageOp
            val now = Iso.now()
            for (k in 0 until count.coerceIn(1, BoardPaging.MAX_PAGES)) {
                s.upsertBoardPage(
                    BoardPage(newBoardId(), b.id, last.sortKey + k + 1, last.width, last.height, last.template, now, now),
                )
            }
        }
    }

    /** 在 [anchorId] 那页的前 / 后插一页：sort_key 取前后两页的中点，背景沿用前一页（同 Mac `insertBoardPage`） */
    private fun insertPage(anchorId: String, after: Boolean) {
        pageOp("画板插页") { s, b, pages ->
            val i = pages.indexOfFirst { it.id == anchorId }
            if (i < 0) return@pageOp
            val k = if (after) i + 1 else i   // 插到第 k 页前面
            val now = Iso.now()
            if (k >= pages.size) {
                val last = pages.last()
                s.upsertBoardPage(
                    BoardPage(newBoardId(), b.id, last.sortKey + 1, last.width, last.height, last.template, now, now),
                )
                return@pageOp
            }
            val ref = pages[k]
            val prev = if (k > 0) pages[k - 1].sortKey else ref.sortKey - 1
            val tpl = (if (k > 0) pages[k - 1] else ref).template
            s.upsertBoardPage(
                BoardPage(newBoardId(), b.id, (prev + ref.sortKey) / 2, ref.width, ref.height, tpl, now, now),
            )
        }
    }

    /** 删几页连同上面的条目（至少留一页，分页画板不会变成无限画布） */
    private fun deletePages(ids: Set<String>) {
        pageOp("画板删页 ×${ids.size}") { s, b, pages ->
            val del = pages.filter { it.id in ids }
            if (del.isEmpty() || del.size >= pages.size) return@pageOp
            val n = s.deleteBoardItemsOnPages(b.id, del.map { it.id }.toSet())
            for (p in del) s.deleteBoardPage(p.id)
            Log.i(TAG, "画板删页 ${del.size} 页，连带条目 $n 条")
        }
    }

    /** 改几页的背景（不变的页不写，免得白白 bump updated_at） */
    private fun setTemplates(ids: Set<String>, template: Int) {
        val raw = BoardPaging.templateRaw(template)
        pageOp("画板改背景 ×${ids.size}") { s, _, pages ->
            val now = Iso.now()
            for (p in pages) if (p.id in ids && p.template != raw) s.upsertBoardPage(p.copy(template = raw, updatedAt = now))
        }
    }

    /** 改整本页面尺寸：每页一起改；条目存的是页内坐标，重读后自然跟着各自那页的左上角走 */
    private fun setPageSize(w: Double, h: Double) {
        if (w <= 1 || h <= 1) return
        pageOp("画板改页面尺寸") { s, _, pages ->
            val now = Iso.now()
            for (p in pages) {
                if (abs(p.width - w) > 0.01 || abs(p.height - h) > 0.01) {
                    s.upsertBoardPage(p.copy(width = w, height = h, updatedAt = now))
                }
            }
        }
    }

    /**
     * 页操作的共用骨架：在队列上读**库里此刻的**页 → [op] 写（一个事务）→ 重读页与内容 → 上屏。
     * 写失败回退到库里的样子（重读照样做）。
     */
    private fun pageOp(what: String, op: (LibraryStore, BoardNote, List<BoardPage>) -> Unit) {
        val q = queue ?: return
        val b = board ?: return
        if (!pageSet.paged) return
        q.submit("$what ${b.id.take(8)}", { s ->
            runCatching { s.transaction { op(s, b, s.boardPages(b.id)) } }
                .onFailure { Log.e(TAG, "$what 写库失败", it) }
            val ps = BoardPageSet(s.boardPages(b.id))
            readContents(s, b.id, ps)
        }, { c ->
            if (board?.id != b.id) return@submit
            // 排队期间刚落下、还没回执的乐观笔迹先留着（它们落库后会按新布局重读一次，见 commitInk）
            val keep = canvas.strokeList().filter { it.id in pendingInk && c.strokes.none { s -> s.id == it.id } }
            applyPages(c.pages)
            applyContents(c, keep)
        })
    }

    // ---------- 写库（全部走 StoreQueue；回主线程先核对画板 id） ----------

    /** 落了笔、还没等到写库回执的笔迹 id */
    private val pendingInk = HashSet<String>()

    /**
     * 收笔：乐观落地，写失败才撤掉并重读真源纠偏（同 ScratchController.commitInk）。
     * 分页画板按**落笔时界面上的那份页**归页换算（用户是照着那份布局写的）；排队期间页结构变了
     * （插页 / 删页 / 改尺寸的重读先回来了），收尾时再按新布局重读一次笔迹，免得这一笔停在旧位置上。
     */
    private fun commitInk(pen: Pen, pts: List<Pt3>) {
        val q = queue ?: return
        val b = board ?: return
        val id = newBoardId()
        val ps = pageSet
        val gen = layoutGen
        canvas.addCommitted(Stroke(0, pen, pts, id))
        pendingInk.add(id)
        q.submit("画板落笔 点数=${pts.size}", { s ->
            runCatching { s.insertBoardStroke(b.id, pen, pts, id, ps) }
                .onSuccess { Log.i(TAG, "画板落笔 点数=${pts.size} id=${id.take(8)}") }
                .onFailure { Log.e(TAG, "画板落笔写库失败（这一笔会丢）", it) }
                .isSuccess
        }, { ok ->
            pendingInk.remove(id)
            if (!ok) {
                canvas.removeStroke(id)
                reloadStrokes()
            } else if (gen != layoutGen && board?.id == b.id) {
                reloadStrokes()
            }
            updateBar()
        })
    }

    /** 一次擦除手势收尾：期望状态交给队列 reconcile（同 ScratchController.commitErase） */
    private fun commitErase(snapshot: List<Stroke>) {
        val q = queue ?: return
        val b = board ?: return
        val ps = pageSet
        // 必须在这里就 groupBy 定格：画布的 strokes 随后还会被回推整体换掉
        val local = snapshot.filter { it.id.isNotEmpty() }.groupBy { it.id }
        q.submit("画板擦除落库", { s ->
            val d = runCatching { s.reconcileBoardStrokes(b.id, local, ps) }
                .onSuccess {
                    if (it.changed) Log.i(TAG, "画板擦除落库：删 ${it.deleted} 条，改 ${it.updated} 条，插 ${it.inserted} 段")
                }
                .onFailure { Log.e(TAG, "画板擦除写库失败，回退到库里的状态", it) }
                .getOrNull()
            if (d != null && !d.changed) null else s.boardStrokes(b.id, BoardPageSet(s.boardPages(b.id)))
        }, { list ->
            if (list != null && board?.id == b.id) canvas.setStrokes(list)
        })
    }

    private fun reloadStrokes() {
        val q = queue ?: return
        val b = board ?: return
        q.submit("重读画板笔迹", { s -> s.boardStrokes(b.id, BoardPageSet(s.boardPages(b.id))) }, { list ->
            if (board?.id == b.id) canvas.setStrokes(list)
        })
    }

    /** 改纸样（不变则不写，免得白白 bump updated_at——同 Mac setPaper） */
    private fun setPaper(bg: String?, pattern: String?) {
        val b = board ?: return
        val newBg = bg ?: b.bg
        val newPattern = pattern ?: b.pattern
        if (newBg == b.bg && newPattern == b.pattern) return
        canvas.setPaper(newBg, newPattern)   // 乐观生效，真源回推为准
        write("改画板纸样", b.copy(bg = newBg, pattern = newPattern, updatedAt = Iso.now()))
    }

    private fun rename(newTitle: String) {
        val b = board ?: return
        if (newTitle == b.title) return
        write("画板改名", b.copy(title = newTitle, updatedAt = Iso.now()))
    }

    /** 写 board_note 再读回来：界面以库为准（写失败就退回库里的样子） */
    private fun write(what: String, updated: BoardNote) {
        val q = queue ?: return
        board = updated
        updateBar()
        q.submit("$what ${updated.id.take(8)}", { s ->
            runCatching { s.upsertBoard(updated) }.onFailure { Log.e(TAG, "$what 写库失败", it) }
            s.board(updated.id)
        }, { fresh ->
            if (board?.id != updated.id) return@submit
            if (fresh != null) {
                board = fresh
                canvas.setPaper(fresh.bg, fresh.pattern)
                onBoardChanged?.invoke(fresh)
            }
            updateBar()
        })
    }

    private fun updateBar() {
        val b = board ?: return
        barName.setTextIfChanged(displayName(b))
        val pct = canvas.zoomPct()
        barZoom.setTextIfChanged(if (pct == 100) "" else "$pct%")
        val paged = pageSet.paged
        mapBtn.visibility = if (paged) View.GONE else View.VISIBLE   // 分页有页码，没有 minimap（同 Mac）
        pagesBtn.visibility = if (paged) View.VISIBLE else View.GONE
        barPage.visibility = if (paged) View.VISIBLE else View.GONE
        if (paged) {
            barPage.setTextIfChanged(
                a.getString(R.string.board_page_of, canvas.currentPageIndex() + 1, pageSet.pages.size),
            )
        }
        mapBtn.setActive(canvas.minimapOn, Ui.onSurface(a), Ui.accent(a))
    }
}
