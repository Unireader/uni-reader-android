package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.ScratchPad
import com.xvan.unireader.local.store.StoreQueue
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
import java.util.UUID

/**
 * 模式1 的草稿纸全链路：`ScratchCanvas`（几何/输入/渲染）↔ `LibraryStore`（scratch_pad 表 +
 * note kind=4）。对应 Mac `AppModel+Scratch` 与 web `PadBar.svelte` 的那一层胶水。
 *
 * 数据纪律与 `LocalCanvasView` 完全同款（§9.5）：
 * - 写库全走 [StoreQueue] 独占线程，主线程只 submit；每个作业闭包里捏着当时的 `docId`/纸 id，
 *   回主线程时先对一遍——排队期间用户完全可能切了标签页/关了纸；
 * - 落笔**乐观落地**（`addCommitted` 立刻上屏，写失败才撤掉并重读真源纠偏）；
 * - 擦除收尾一次 reconcile（[LibraryStore.reconcileScratchStrokes]），有变更/写失败才回读。
 *
 * 这个类只管「当前这一篇文档」：ReaderActivity 切标签页时先 [close] 再 [bind] 到新文档。
 * 视口（origin/zoom）在 `ScratchCanvas` 里，不落库不上线，打开一律回中（三端契约）。
 */
class ScratchController(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Scratch"

        /**
         * 可选纸色（同 Mac `ScratchPad.paperPalette` / web `PadBar.PAPERS`）。
         * **只是 UI 备选项，不是契约**——`bg` 在库里/线上都是自由 CSS rgba 串。
         */
        val PALETTE = listOf(
            "纸白" to "rgba(255,255,255,1.0)",
            "米白" to "rgba(252,247,235,1.0)",
            "浅灰" to "rgba(241,242,245,1.0)",
            "牛皮" to "rgba(246,236,214,1.0)",
            "护眼绿" to "rgba(233,243,234,1.0)",
            "淡蓝" to "rgba(234,241,250,1.0)",
        )

        val PATTERN_LABELS = listOf("plain" to "纯色", "dots" to "点阵", "grid" to "小格")

        /** 线上/库里颜色串的写法可能有细微差异（"1" vs "1.0"），比对按解析后的数值（同 web sameColor） */
        fun sameColor(x: String, y: String): Boolean {
            val a = ScratchGeom.parseCssRgba(x)
            val b = ScratchGeom.parseCssRgba(y)
            return a != null && b != null && a.contentEquals(b)
        }
    }

    val canvas = ScratchCanvas(a)

    /** 浮在纸上的工具条（ReaderActivity 加进 root、贴 chrome 下方居中；开着纸才 VISIBLE） */
    val barView: View

    /** 「在当前位置新建」的锚点来源（宿主给当前画布的视口中心） */
    var anchorProvider: (() -> Triple<Int, Float, Float>?)? = null

    /** 纸列表变了 → 宿主刷新页面上的图钉 */
    var onPinsChanged: ((List<LocalCanvasView.Pin>) -> Unit)? = null

    /** 开/关纸 → 宿主刷新顶栏入口的开关态 */
    var onOpenChanged: (() -> Unit)? = null

    private var queue: StoreQueue? = null
    private var docId = ""

    var pads = listOf<ScratchPad>()
        private set
    var openPad: ScratchPad? = null
        private set
    val isOpen: Boolean get() = openPad != null

    // ---------- 工具条 ----------

    private val barName: TextView
    private val barZoom: TextView
    private val mapBtn: ImageButton

    init {
        canvas.visibility = View.GONE
        canvas.onStrokeEnd = { pen, pts -> commitInk(pen, pts) }
        canvas.onEraseFinish = { snapshot -> commitErase(snapshot) }
        canvas.onViewportChanged = { updateBar() }
        // 工具快照每次落笔现取：纸开着时在顶栏/面板改笔、改橡皮即时生效
        canvas.tools = { toolsProvider?.invoke() }

        // 工具条：悬浮胶囊（不是横贯全宽的横杠——Mac 初版那条梁被否过）。
        // ⚠️ 对比度（handoff §7.2）：纸是浅色的，浮层控件显式给足——不透明 surface + outline
        // 描边 + on_surface 图标，不用半透明的 bar_scrim（Mac 首版低对比按钮被用户当场报过）。
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
        row.addView(Ui.iconButton(a, R.drawable.ic_palette, "纸样", on) { showPaperPanel() })
        barZoom = Ui.body(a, "", variant = false).apply {
            textSize = 12f
            setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), 0)
        }
        row.addView(barZoom)
        row.addView(Ui.iconButton(a, R.drawable.ic_close, "关闭草稿纸", on) { close() })
        barView = row
        barView.visibility = View.GONE
    }

    /** 宿主给：阅读画布的工具状态 → 草稿纸工具快照 */
    var toolsProvider: (() -> ScratchCanvas.Tools?)? = null

    // ---------- 绑定（切标签页/装载完时调） ----------

    fun bind(q: StoreQueue?, docId: String, pads: List<ScratchPad>) {
        queue = q
        this.docId = docId
        applyPads(pads, docId)
    }

    /** 当前开着哪张（给「纸开着时笔迹只落纸上」的判定与 HUD） */
    fun isOpenPad(id: String): Boolean = openPad?.id == id

    // ---------- 开 / 关 ----------

    fun open(pad: ScratchPad) {
        val q = queue ?: return
        val did = docId
        openPad = pad
        canvas.setPaper(pad.bg, pad.pattern)
        canvas.openSession()   // 打开一律回中（视口不落库不上线，三端各自独立缩放滚动）
        canvas.visibility = View.VISIBLE
        barView.visibility = View.VISIBLE
        updateBar()
        onOpenChanged?.invoke()
        Log.i(TAG, "打开草稿纸 ${pad.id.take(8)}《${pad.title}》")
        q.submit("读草稿纸笔迹 ${pad.id.take(8)}", { s ->
            s.scratchStrokes(did)[pad.id].orEmpty()
        }, { list ->
            // 排队期间换了纸/关了纸/切了文档：这份结果就是旧的了
            if (did == docId && openPad?.id == pad.id) {
                canvas.setStrokes(list)
                updateBar()
            }
        })
    }

    fun openById(padId: String) {
        pads.firstOrNull { it.id == padId }?.let { open(it) }
    }

    fun close() {
        if (openPad == null) return
        Log.i(TAG, "关闭草稿纸 ${openPad?.id?.take(8)}")
        openPad = null
        canvas.visibility = View.GONE
        barView.visibility = View.GONE
        onOpenChanged?.invoke()
    }

    // ---------- 列表（顶栏入口 / 工具条最左图标） ----------

    private fun displayName(p: ScratchPad): String {
        val i = pads.indexOfFirst { it.id == p.id }
        return p.title.ifEmpty { "草稿纸 ${i + 1}" }
    }

    fun showList() {
        if (queue == null || docId.isEmpty()) return
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(a).title("草稿纸")
        if (pads.isEmpty()) sheet.subtitle("还没有草稿纸。纸开在页面上方，写的东西不进 PDF。")
        for (p in pads) {
            val open = isOpenPad(p.id)
            list.addView(
                PadPanels.iconRow(
                    a, R.drawable.ic_scratch,
                    "${displayName(p)} · 第 ${p.anchorPage + 1} 页",
                    if (open) Ui.accent(a) else Ui.onSurface(a),
                    trailing = if (!open) {
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
                    open(p)
                },
            )
        }
        list.addView(Ui.divider(a))
        list.addView(
            PadPanels.iconRow(a, R.drawable.ic_plus, "在当前位置新建") {
                dlg?.dismiss()
                createAtCurrentPosition()
            },
        )
        sheet.content(list)
        sheet.action("取消")
        dlg = sheet.show()
    }

    /** 「在当前位置新建」：锚点 = 当前视口中心所在的页内归一化点（页面上留图钉的位置） */
    private fun createAtCurrentPosition() {
        val q = queue ?: return
        val anchor = anchorProvider?.invoke() ?: return
        val did = docId
        val now = Iso.now()
        val pad = ScratchPad(
            id = UUID.randomUUID().toString(), documentId = did, title = "",
            anchorPage = anchor.first, anchorX = anchor.second.toDouble(), anchorY = anchor.third.toDouble(),
            bg = ScratchPad.DEFAULT_BG, pattern = ScratchPad.DEFAULT_PATTERN,
            createdAt = now, updatedAt = now,
        )
        q.submit("新建草稿纸 第${anchor.first + 1}页", { s ->
            runCatching { s.upsertScratchPad(pad) }
                .onFailure { Log.e(TAG, "新建草稿纸写库失败", it) }
            s.scratchPads(did)
        }, { list ->
            if (did != docId) return@submit
            applyPads(list, did)
            val created = list.firstOrNull { it.id == pad.id }
            if (created == null) {
                // v7 老库：scratch_pad 表不存在，upsert 只能跳过（不建表是红线，见 LibraryStore）
                a.showAlert(
                    "建不了草稿纸",
                    "这个工作区还没被新版 Mac 打开过（缺 scratch_pad 表）。" +
                        "在 Mac 上打开一次这个工作区再搬回来即可。",
                )
            } else {
                open(created)
            }
        })
    }

    // ---------- 纸样面板（底纹三选一 + 纸色六选一 + 改名 + 删除） ----------

    fun showPaperPanel() {
        val pad = openPad ?: return
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(a).title(displayName(pad))

        // 底纹三选一（整行可点，当前项打勾）
        root.addView(Ui.groupTitle(a, "底纹", top = 0))
        for ((key, label) in PATTERN_LABELS) {
            root.addView(optionRow(label, pad.pattern == key) {
                setPaper(bg = null, pattern = key)
                dlg?.dismiss()
            })
        }

        // 纸色六选一（色块行，当前项 accent 描边）
        root.addView(Ui.groupTitle(a, "纸色"))
        val swatches = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for ((name, css) in PALETTE) {
            val cur = sameColor(pad.bg, css)
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
            PadPanels.iconRow(a, R.drawable.ic_text, "改名…") {
                dlg?.dismiss()
                showRename(pad)
            },
        )
        root.addView(
            PadPanels.iconRow(a, R.drawable.ic_delete, "删除这张草稿纸", Ui.col(a, R.color.danger)) {
                dlg?.dismiss()
                confirmDelete(pad)
            },
        )

        sheet.content(root)
        sheet.action("完成", primary = true)
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

    private fun showRename(pad: ScratchPad) {
        val edit = PadPanels.inputBox(a, "草稿纸名字").apply {
            setText(pad.title)
            setSelection(text.length)
        }
        Sheet(a).title("改名")
            .content(edit)
            .action("取消")
            .action("保存", primary = true) { renamePad(pad, edit.text.toString().trim()) }
            .show()
    }

    private fun confirmDelete(pad: ScratchPad) {
        Sheet(a)
            .title("删除《${displayName(pad)}》？")
            .subtitle("纸上的全部笔迹会一起删掉，这一步不可撤销。")
            .action("取消")
            .action("删除", primary = true) { deletePad(pad) }
            .show()
    }

    // ---------- 写库（全部走 StoreQueue；回主线程先对 docId/纸 id） ----------

    /** 收笔：乐观落地（同 `LocalCanvasView.onInkEnd`），写失败才撤掉并重读真源纠偏 */
    private fun commitInk(pen: Pen, pts: List<Pt3>) {
        val q = queue ?: return
        val pad = openPad ?: return
        val did = docId
        val id = UUID.randomUUID().toString()
        canvas.addCommitted(Stroke(0, pen, pts, id, "", pad.id))
        q.submit("草稿纸落笔 点数=${pts.size}", { s ->
            runCatching { s.insertScratchStroke(did, pad.id, pen, pts, id) }
                .onSuccess { Log.i(TAG, "草稿纸落笔 点数=${pts.size} id=${id.take(8)}") }
                .onFailure { Log.e(TAG, "草稿纸落笔写库失败（这一笔会丢）", it) }
                .isSuccess
        }, { ok ->
            if (!ok) {
                canvas.removeStroke(id)
                reloadStrokes()
            }
            updateBar()
        })
    }

    /** 一次擦除手势收尾：期望状态交给队列 reconcile（同 `LocalCanvasView.onEraseEnd` 的口径） */
    private fun commitErase(snapshot: List<Stroke>) {
        val q = queue ?: return
        val pad = openPad ?: return
        val did = docId
        // 必须在这里就 groupBy 定格：画布的 strokes 随后还会被回推整体换掉（同页内 onEraseEnd）
        val local = snapshot.filter { it.id.isNotEmpty() }.groupBy { it.id }
        q.submit("草稿纸擦除落库", { s ->
            val d = runCatching { s.reconcileScratchStrokes(did, pad.id, local) }
                .onSuccess {
                    if (it.changed) Log.i(TAG, "草稿纸擦除落库：删 ${it.deleted} 条，改 ${it.updated} 条，插 ${it.inserted} 段")
                }
                .onFailure { Log.e(TAG, "草稿纸擦除写库失败，回退到库里的状态", it) }
                .getOrNull()
            // 有变更或写失败才回读真源；擦到空处不值得整表重读（同页内口径）
            if (d != null && !d.changed) null else s.scratchStrokes(did)[pad.id].orEmpty()
        }, { list ->
            if (list != null && did == docId && openPad?.id == pad.id) canvas.setStrokes(list)
        })
    }

    private fun reloadStrokes() {
        val q = queue ?: return
        val pad = openPad ?: return
        val did = docId
        q.submit("重读草稿纸笔迹", { s ->
            s.scratchStrokes(did)[pad.id].orEmpty()
        }, { list ->
            if (did == docId && openPad?.id == pad.id) canvas.setStrokes(list)
        })
    }

    /** 改纸样（底色/底纹各自可单独改；不变则不写，免得白白 bump updated_at——同 Mac setPaper） */
    private fun setPaper(bg: String?, pattern: String?) {
        val q = queue ?: return
        val pad = openPad ?: return
        val did = docId
        val newBg = bg ?: pad.bg
        val newPattern = pattern ?: pad.pattern
        if (newBg == pad.bg && newPattern == pad.pattern) return
        val updated = pad.copy(bg = newBg, pattern = newPattern, updatedAt = Iso.now())
        // 乐观生效（面板里连点几个颜色不该有等待感）；真源回推会以库为准（v8 老库 pattern 写不进去
        // 会读回兜底 dots，界面跟着退回去，而不是显示一个没存进去的纸样）
        openPad = updated
        canvas.setPaper(newBg, newPattern)
        q.submit("改纸样 ${pad.id.take(8)}", { s ->
            runCatching { s.upsertScratchPad(updated) }
                .onFailure { Log.e(TAG, "改纸样写库失败", it) }
            s.scratchPads(did)
        }, { list -> applyPads(list, did) })
    }

    private fun renamePad(pad: ScratchPad, newTitle: String) {
        val q = queue ?: return
        val did = docId
        if (newTitle == pad.title) return
        val updated = pad.copy(title = newTitle, updatedAt = Iso.now())
        openPad = updated
        updateBar()
        q.submit("草稿纸改名 ${pad.id.take(8)}", { s ->
            runCatching { s.upsertScratchPad(updated) }
                .onFailure { Log.e(TAG, "草稿纸改名写库失败", it) }
            s.scratchPads(did)
        }, { list -> applyPads(list, did) })
    }

    /** 删纸：库端连带删纸上 kind=4 笔迹（见 LibraryStore.deleteScratchPad）；开着的就是这张则关 */
    private fun deletePad(pad: ScratchPad) {
        val q = queue ?: return
        val did = docId
        q.submit("删除草稿纸 ${pad.id.take(8)}", { s ->
            runCatching { s.deleteScratchPad(pad.id) }
                .onSuccess { Log.i(TAG, "删除草稿纸 ${pad.id.take(8)}（含纸上笔迹）") }
                .onFailure { Log.e(TAG, "删除草稿纸写库失败", it) }
            s.scratchPads(did)
        }, { list -> applyPads(list, did) })
    }

    /** 纸列表真源回推：换列表 + 开着的那张跟着库走（被删了就关）+ 图钉刷新 */
    private fun applyPads(list: List<ScratchPad>, did: String) {
        if (did != docId) return
        pads = list
        val op = openPad
        if (op != null) {
            val cur = list.firstOrNull { it.id == op.id }
            if (cur == null) {
                close()
            } else if (cur != op) {
                openPad = cur
                canvas.setPaper(cur.bg, cur.pattern)
            }
        }
        onPinsChanged?.invoke(
            list.map { LocalCanvasView.Pin(it.id, it.anchorPage, it.anchorX.toFloat(), it.anchorY.toFloat()) },
        )
        updateBar()
    }

    private fun updateBar() {
        val op = openPad ?: return
        barName.setTextIfChanged(displayName(op))
        // 缩放读数只在不是 100% 时出现（常驻一个「100%」是纯噪音，同 Mac 的口径）
        val pct = canvas.zoomPct()
        barZoom.setTextIfChanged(if (pct == 100) "" else "$pct%")
        mapBtn.setActive(canvas.minimapOn, Ui.onSurface(a), Ui.accent(a))
    }
}
