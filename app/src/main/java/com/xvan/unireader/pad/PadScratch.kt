package com.xvan.unireader.pad

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.ScratchController
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.PageImageSource
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.ScratchCanvas
import com.xvan.unireader.shared.ScratchGeom
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.Ui.setActive
import com.xvan.unireader.shared.setTextIfChanged

/**
 * 模式2（输入板）的草稿纸胶水：`ScratchCanvas`（几何/输入/渲染）↔ **线协议**（Mac 是唯一真源）。
 * 对应 web `PadBar.svelte` + `scratch.ts`（同一设备形态上已经做过的答案），
 * 结构照模式1 的 `local/ScratchController`——差别只在提交口：那边落库，这边编帧发给 Mac。
 *
 * 核心语义（SCRATCHPAD-ANDROID-HANDOFF §3.2 / PROTOCOL.md §4.4，弄错全盘皆错）：
 * - **Mac 是「开着哪张纸」的唯一真源**：收到 `scratchpads` 就照做（[applyPads]），本地只发请求
 *   （scratchOpen/scratchAdd/scratchPaper）不自作主张；换纸 = `openSession()` 清状态回中（同 Mac `.id`）。
 * - `scratchStrokes` = 当前打开那张纸的全量镜像（无 page 字段，pts 是画布坐标 dp，可负无界）；
 *   没开纸时 Mac 发 n=0，据此清掉本地残留。ackRel 判据与页内 `strokes` **完全同款**（见 [applyStrokes]）。
 * - **RT 流编码函数一行不动**：纸开着时触点已在 `ScratchCanvas` 里换成画布坐标，这里只把
 *   `ink`/`erase` 的 `page` 填 0（Mac 不读）。纸开着时**不发 probe、不呼环形盘**（那套判定建立在
 *   页内归一化 + padGeom.pageW 上，喂画布坐标阈值全失真）——`ScratchCanvas` 本来就没有 probe。
 * - **一行 SQLite 都不碰**：笔迹真源在 Mac。
 *
 * 纸样入口的六色板/底纹三选一与模式1 同款：直接引 `ScratchController.PALETTE`/`PATTERN_LABELS`
 * （只是 UI 备选项不是契约，bg 线上是 r/g/b/a，对象侧拼回自由 CSS rgba 串喂 `setPaper`）。
 */
class PadScratch(private val a: Activity) {

    val canvas = ScratchCanvas(a)

    /** 浮在纸上的工具条（PadActivity 加进 root、贴顶栏下方居中；开着纸才 VISIBLE） */
    val barView: View

    // ---- 出口（PadActivity 接线；全部是「请求」/RT 帧，权威状态等 Mac 回推） ----

    /** UDP 可靠流（ink/erase，同页内 RT 的通道选择） */
    var sendRel: ((ByteArray) -> Unit)? = null

    /** WS 可靠通道（scratchOpen/scratchAdd/scratchPaper 请求） */
    var sendCtl: ((ByteArray) -> Unit)? = null

    /** 每发一帧 ink/erase move（顶栏 mv/s 计数，同页内口径） */
    var onMoveFrame: (() -> Unit)? = null

    /** ink end 发出时刻（e2e 计时起点，同页内 `PadView.Listener.onInkEndSent`） */
    var onInkEndSent: (() -> Unit)? = null

    /** 纸列表变了 → 宿主刷新页面上的图钉 */
    var onPinsChanged: ((List<PadView.ScratchPin>) -> Unit)? = null

    /** 开/关纸 → 宿主刷新顶栏入口的开关态 */
    var onOpenChanged: (() -> Unit)? = null

    /** 「在当前位置新建」的锚点来源（宿主给当前画布的视口中心，页内归一化） */
    var anchorProvider: (() -> Triple<Int, Float, Float>?)? = null

    /** 宿主给：阅读画布的工具状态 → 草稿纸工具快照（每次落笔现取，改笔/改橡皮即时生效） */
    var toolsProvider: (() -> ScratchCanvas.Tools?)? = null

    // ---- 页面底图（v10）：图从哪来 + 那一页多高（几何契约见 ScratchGeom.pageRect） ----

    /** 页图来源（模式2 = HTTP 找 Mac 要 `/page.png?i=N`，与阅读画布同一个 `PageFetcher`） */
    var pageSource: PageImageSource? = null
        set(v) {
            field = v
            canvas.pageSource = v
        }

    /** 第 i 页的显示纵横比（页高/页宽）——宿主从阅读画布的 `layout` 页尺寸表现取 */
    var pageAspect: ((Int) -> Float)? = null

    var pads = listOf<WireCodec.ScratchPadEntry>()
        private set

    /** 当前打开的是 pads 里第几张，-1 = 没开（照 Mac 的 scratchpads.open，不本地推算） */
    var open = -1
        private set
    val isOpen: Boolean get() = open >= 0

    /** 开着的那张纸的 id（换纸判定用；下标会因前面删纸而平移，id 不会） */
    private var openPadId: String? = null

    // ---------- 真源回推 ----------

    /**
     * 收 `scratchpads`：照做。open 指向的纸变了 → 跟过去（openSession 清本地状态 + 回中）；
     * open=-1 → 关纸回 PDF。纸样/标题变更同帧生效。图钉列表每次都重推（增删改都走这条）。
     */
    fun applyPads(newOpen: Int, list: List<WireCodec.ScratchPadEntry>) {
        pads = list
        open = if (newOpen in list.indices) newOpen else -1
        val entry = pads.getOrNull(open)
        if (entry == null) {
            openPadId = null
            canvas.setPageUnder(-1, null)
            canvas.visibility = View.GONE
            barView.visibility = View.GONE
        } else {
            if (entry.id != openPadId) canvas.openSession()   // 换纸/新开：丢上一张的本地状态并回中
            openPadId = entry.id
            canvas.setPaper(bgCss(entry), patternName(entry.pattern))
            applyPageUnder(entry)
            canvas.visibility = View.VISIBLE
            barView.visibility = View.VISIBLE
        }
        onPinsChanged?.invoke(
            list.mapIndexed { i, p -> PadView.ScratchPin(i, p.page.toInt(), p.nx, p.ny) },
        )
        updateBar()
        onOpenChanged?.invoke()
    }

    /**
     * 收 `scratchStrokes`：当前打开那张纸的全量笔迹镜像（没开纸时 Mac 发 n=0 → 清掉本地残留）。
     *
     * ackRel 判据与页内 `PageCanvasView.setStrokes` **一字不差**（PROTOCOL.md §4.2）：
     * 这份快照生成时 Mac 还没处理完我发出的输入（sentRel > ackRel）→ 它比本地乐观状态旧，
     * 应用它就是把刚写完/刚擦掉的恢复出来再等下一份纠偏；擦除手势还没抬笔时一律信本地
     * （最后一批擦除点可能还在 8ms 批缓冲里没 flush，那时 sentRel 没涨、判据会误判）。
     */
    fun applyStrokes(ackRel: Long, list: List<Stroke>, sentRel: Long) {
        if (ackRel > 0L && sentRel > ackRel) return
        if (erasing) return
        canvas.setStrokes(list)   // 正在写的这一笔不受影响（它还没进 strokes，见 ScratchCanvas）
    }

    // ---------- RT 流（纸开着时的唯一提交口；坐标已是画布坐标，page 恒 0） ----------

    private val handler = Handler(Looper.getMainLooper())
    private val inkBatch = ArrayList<Pt3>()
    private val eraseBatch = ArrayList<Pt2>()
    private var erasing = false   // 一次擦除手势进行中（onEraseAt 置位，onEraseFinish 收尾）
    private var optSeq = 0L

    // ---- 工具条读数（init 里装配） ----
    private val barName: TextView
    private val barZoom: TextView
    private val mapBtn: ImageButton
    private val pageBtn: ImageButton

    private val flusher = object : Runnable {
        override fun run() {
            flush()
            handler.postDelayed(this, 8)
        }
    }

    /** 8ms 一批（同页内 PageCanvasView 的批缓冲）：采样率与帧数两头都顾 */
    private fun flush() {
        if (inkBatch.isNotEmpty()) {
            sendRel?.invoke(WireCodec.encodeInkMove(ArrayList(inkBatch)))
            inkBatch.clear()
            onMoveFrame?.invoke()
        }
        if (eraseBatch.isNotEmpty()) {
            sendRel?.invoke(WireCodec.encodeEraseMove(0, ArrayList(eraseBatch)))
            eraseBatch.clear()
            onMoveFrame?.invoke()
        }
    }

    init {
        canvas.visibility = View.GONE
        // 流式落墨三钩子 → RT ink 帧（编码函数一行没动，只是 page 填 0、坐标已是画布坐标）
        canvas.onStrokeBegin = { pen, pt, line ->
            sendRel?.invoke(WireCodec.encodeInkBegin(0, pen, listOf(pt), line))
        }
        canvas.onStrokeMove = { pts -> inkBatch.addAll(pts) }
        canvas.onStrokeEnd = { pen, pts ->
            flush()   // 尾批必须先于 end 出去（UDP 可靠有序，先发先处理）
            onInkEndSent?.invoke()
            sendRel?.invoke(WireCodec.encodeInkEnd())
            // 乐观落地（同 PageCanvasView 的 pendingInk）：真源回推前先挂进画布，不然
            // 「活体层已清、scratchStrokes 还没到」之间上一笔会闪一下。回推被接受时整表替换，
            // 乐观笔自然消失；Mac 掉线真源永远不会来，3s 兜底撤掉（同页内的 OPT_INK_TIMEOUT_MS）。
            val id = "opt:s${optSeq++}"
            canvas.addCommitted(Stroke(0, pen, pts, id))
            handler.postDelayed({ canvas.removeStroke(id) }, 3000)
        }
        // 擦除：本地乐观擦除 ScratchCanvas 自己做了，这里只负责把途经点上线
        canvas.onEraseAt = { x, y ->
            erasing = true
            eraseBatch.add(Pt2(x, y))
        }
        canvas.onEraseFinish = {
            flush()
            sendRel?.invoke(WireCodec.encodeEraseEnd())
            erasing = false
        }
        canvas.onViewportChanged = { updateBar() }
        canvas.tools = { toolsProvider?.invoke() }
        handler.postDelayed(flusher, 8)

        // 工具条：悬浮胶囊，与模式1 同款（⚠️ 对比度 handoff §7.2：纸是浅色的，浮层显式给足——
        // 不透明 surface + outline 描边 + on_surface 图标，不用半透明的 bar_scrim）
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.surface(a), Ui.PILL, a, Ui.outline(a))
            val h = Ui.dp(a, 2)
            setPadding(Ui.dp(a, 6), h, Ui.dp(a, 6), h)
        }
        val on = Ui.onSurface(a)
        row.addView(Ui.iconButton(a, R.drawable.ic_list, "草稿纸列表", on) { showList() })
        barName = Ui.title(a, "", 14f).apply {
            maxLines = 1
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(barName)
        row.addView(Ui.iconButton(a, R.drawable.ic_scope, "回中", on) { canvas.recenter() })
        row.addView(Ui.iconButton(a, R.drawable.ic_fit, "适应内容", on) { canvas.fitContent() })
        mapBtn = Ui.iconButton(a, R.drawable.ic_map, "缩略图", on) {
            canvas.minimapOn = !canvas.minimapOn
            updateBar()
        }
        row.addView(mapBtn)
        // 页面底图（v10）：开/关都只发请求（scratchPageShow），Mac 判定后回推 scratchpads
        pageBtn = Ui.iconButton(a, R.drawable.ic_doc, "显示所在页面", on) { requestToggleShowPage() }
        row.addView(pageBtn)
        row.addView(Ui.iconButton(a, R.drawable.ic_palette, "纸样", on) { showPaperPanel() })
        barZoom = Ui.body(a, "", variant = false).apply {
            textSize = 12f
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(barZoom)
        // 关纸 = 发 scratchOpen(-1) 请求，等 Mac 回推 scratchpads 才真的收（本地不自作主张）
        row.addView(Ui.iconButton(a, R.drawable.ic_close, "关闭草稿纸", on) { requestClose() })
        barView = row
        barView.visibility = View.GONE
    }

    /** 停批缓冲定时器（PadActivity.onDestroy） */
    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    // ---------- 请求（本地只发请求，权威状态等 Mac 回推） ----------

    fun requestOpen(index: Int) {
        sendCtl?.invoke(WireCodec.encodeScratchOpen(index))
    }

    fun requestClose() {
        sendCtl?.invoke(WireCodec.encodeScratchOpen(-1))
    }

    /** 「在当前位置新建」：锚点 = 当前视口中心所在的页内归一化点（页面上留图钉的位置） */
    private fun requestAdd() {
        val anchor = anchorProvider?.invoke() ?: return
        sendCtl?.invoke(
            WireCodec.encodeScratchAdd(anchor.first.toLong(), anchor.second, anchor.third),
        )
    }

    /**
     * 页尺寸表（`layout`）来晚了/换文档了 → 重算页矩形。
     * **必须有这条**：`scratchpads` 与 `layout` 两条广播的先后没有保证，只在 applyPads 里算的话，
     * 先收到纸、后收到页尺寸的那一次会用 `pageAspect` 的兜底值把页画成方的，且再也不会自己纠正。
     */
    fun refreshPageUnder() {
        val entry = pads.getOrNull(open) ?: return
        applyPageUnder(entry)
    }

    /** 把「当前这张纸要不要垫页、垫哪一页、垫在哪」交给画布（关掉/没图源即撤掉） */
    private fun applyPageUnder(entry: WireCodec.ScratchPadEntry) {
        if (!entry.showPage || pageSource == null) {
            canvas.setPageUnder(-1, null)
            return
        }
        val page = entry.page.toInt()
        val asp = pageAspect?.invoke(page) ?: 0f
        canvas.setPageUnder(page, ScratchGeom.pageRect(entry.nx, entry.ny, asp))
    }

    /** 开/关页面底图：乐观生效 + 发请求，Mac 回推 scratchpads 为准（同 requestPaper 的口径） */
    private fun requestToggleShowPage() {
        val entry = pads.getOrNull(open) ?: return
        val want = !entry.showPage
        sendCtl?.invoke(WireCodec.encodeScratchPageShow(open, want))
        val optimistic = entry.copy(showPage = want)
        pads = pads.toMutableList().also { it[open] = optimistic }
        applyPageUnder(optimistic)
        updateBar()
    }

    /** 删这张纸（连同纸上笔迹）。Mac 判定 + 落库后回推 scratchpads/scratchStrokes */
    private fun requestDelete(index: Int) {
        sendCtl?.invoke(WireCodec.encodeScratchDelete(index))
    }

    /** 改名（空串 = 回到「草稿纸 N」兜底名）。同上，以回推为准，本地不改列表 */
    private fun requestRename(index: Int, title: String) {
        sendCtl?.invoke(WireCodec.encodeScratchRename(index, title))
    }

    // ---------- 列表（顶栏入口 / 工具条最左图标） ----------

    private fun displayName(p: WireCodec.ScratchPadEntry): String {
        val i = pads.indexOfFirst { it.id == p.id }
        return p.title.ifEmpty { "草稿纸 ${i + 1}" }
    }

    fun showList() {
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(a).title("草稿纸")
        if (pads.isEmpty()) sheet.subtitle("还没有草稿纸。纸开在页面上方，写的东西不进 PDF。")
        pads.forEachIndexed { i, p ->
            val cur = i == open
            list.addView(
                PadPanels.iconRow(
                    a, R.drawable.ic_scratch,
                    "${displayName(p)} · 第 ${p.page + 1} 页",
                    if (cur) Ui.accent(a) else Ui.onSurface(a),
                    trailing = if (!cur) {
                        null
                    } else {
                        ImageView(a).apply {
                            setImageResource(R.drawable.ic_check)
                            imageTintList = ColorStateList.valueOf(Ui.accent(a))
                            layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18))
                        }
                    },
                ) {
                    dlg?.dismiss()
                    // 点当前开着的那张 = 收起来（切换语义）；点别的 = 换过去
                    requestOpen(if (cur) -1 else i)
                },
            )
        }
        list.addView(Ui.divider(a))
        // 纸开着时不给「新建」：草稿纸模式下再点新建会又叠一张，要先收起这张再建
        if (!isOpen) {
            list.addView(
                PadPanels.iconRow(a, R.drawable.ic_plus, "在当前位置新建") {
                    dlg?.dismiss()
                    requestAdd()
                },
            )
        }
        if (isOpen) {
            list.addView(
                PadPanels.iconRow(a, R.drawable.ic_close, "收起草稿纸") {
                    dlg?.dismiss()
                    requestClose()
                },
            )
        }
        sheet.content(list)
        sheet.action("取消")
        dlg = sheet.show()
    }

    // ---------- 纸样面板（底纹三选一 + 纸色六选一 + 改名 + 删除；与模式1 同一套） ----------

    fun showPaperPanel() {
        val entry = pads.getOrNull(open) ?: return
        val index = open
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(a).title(displayName(entry))

        // 底纹三选一（整行可点，当前项打勾）
        root.addView(Ui.groupTitle(a, "底纹", top = 0))
        for ((key, label) in ScratchController.PATTERN_LABELS) {
            root.addView(optionRow(label, patternName(entry.pattern) == key) {
                requestPaper(index, bg = null, pattern = key)
                dlg?.dismiss()
            })
        }

        // 纸色六选一（色块行，当前项 accent 描边）
        root.addView(Ui.groupTitle(a, "纸色"))
        val swatches = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, css) in ScratchController.PALETTE) {
            val cur = ScratchController.sameColor(bgCss(entry), css)
            val c = ScratchGeom.parseCssRgba(css)!!
            val v = View(a).apply {
                background = Ui.round(
                    Color.argb(c[3], c[0], c[1], c[2]), 8, a,
                    if (cur) Ui.accent(a) else Ui.outline(a),
                )
                contentDescription = name
                isClickable = true
                setOnClickListener {
                    requestPaper(index, bg = css, pattern = null)
                    dlg?.dismiss()
                }
            }
            swatches.addView(v, LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 36)).apply {
                marginEnd = Ui.dp(a, 10)
            })
        }
        root.addView(swatches)

        // 管理（v10 起线上有 scratchRename/scratchDelete 了，与模式1 的面板一字排开）
        root.addView(Ui.groupTitle(a, "管理"))
        root.addView(
            PadPanels.iconRow(a, R.drawable.ic_text, "改名…") {
                dlg?.dismiss()
                showRename(index, entry)
            },
        )
        root.addView(
            PadPanels.iconRow(a, R.drawable.ic_delete, "删除这张草稿纸", Ui.col(a, R.color.danger)) {
                dlg?.dismiss()
                confirmDelete(index, entry)
            },
        )

        sheet.content(root)
        sheet.action("完成", primary = true)
        dlg = sheet.show()
    }

    private fun showRename(index: Int, entry: WireCodec.ScratchPadEntry) {
        val edit = PadPanels.inputBox(a, "草稿纸名字").apply {
            setText(entry.title)
            setSelection(text.length)
        }
        Sheet(a).title("改名")
            .content(edit)
            .action("取消")
            .action("保存", primary = true) { requestRename(index, edit.text.toString().trim()) }
            .show()
    }

    private fun confirmDelete(index: Int, entry: WireCodec.ScratchPadEntry) {
        Sheet(a)
            .title("删除《${displayName(entry)}》？")
            .subtitle("纸上的全部笔迹会一起删掉，这一步不可撤销。")
            .action("取消")
            .action("删除", primary = true) { requestDelete(index) }
            .show()
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

    /**
     * 改纸样请求：未指定的一侧沿用现值。乐观生效（面板里连点几个颜色不该有等待感），
     * Mac 判定落库后回推 scratchpads，以真源为准（同模式1 setPaper 的口径）。
     */
    private fun requestPaper(index: Int, bg: String?, pattern: String?) {
        val entry = pads.getOrNull(index) ?: return
        val newBg = bg ?: bgCss(entry)
        val newPattern = pattern ?: patternName(entry.pattern)
        val c = ScratchGeom.parseCssRgba(newBg) ?: return
        val pat = when (newPattern) {
            "plain" -> WireCodec.PATTERN_PLAIN
            "grid" -> WireCodec.PATTERN_GRID
            else -> WireCodec.PATTERN_DOTS
        }
        sendCtl?.invoke(
            WireCodec.encodeScratchPaper(index, c[0], c[1], c[2], c[3] / 255f, pat),
        )
        canvas.setPaper(newBg, newPattern)
    }

    // ---------- 线上形态 ↔ 对象形态（bg 线上拆 r/g/b/a，对象侧拼回自由 CSS rgba 串） ----------

    private fun bgCss(p: WireCodec.ScratchPadEntry): String = "rgba(${p.r},${p.g},${p.b},${p.a})"

    private fun patternName(pattern: Int): String = when (pattern) {
        WireCodec.PATTERN_PLAIN -> "plain"
        WireCodec.PATTERN_GRID -> "grid"
        else -> "dots"   // 越界回退 dots（同解码惯例）
    }

    // ---------- 工具条读数 ----------

    private fun updateBar() {
        val entry = pads.getOrNull(open) ?: return
        barName.setTextIfChanged(displayName(entry))
        // 缩放读数只在不是 100% 时出现（常驻一个「100%」是纯噪音，同 Mac 的口径）
        val pct = canvas.zoomPct()
        barZoom.setTextIfChanged(if (pct == 100) "" else "$pct%")
        mapBtn.setActive(canvas.minimapOn, Ui.onSurface(a), Ui.accent(a))
        pageBtn.setActive(entry.showPage, Ui.onSurface(a), Ui.accent(a))
    }
}
