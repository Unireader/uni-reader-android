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
import com.xvan.unireader.local.store.BoardItem
import com.xvan.unireader.local.store.BoardNote
import com.xvan.unireader.local.store.Iso
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.newBoardId
import com.xvan.unireader.shared.Bg
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
import java.io.File
import org.json.JSONObject

/**
 * 模式1 的画板笔记（`../BOARD-NOTE-PLAN.md §5`）：`ScratchCanvas`（几何/输入/渲染）↔ `LibraryStore`
 * 的 `board_note` / `board_item` 两张表。照 [ScratchController] 写，差别只在：
 *
 * - **整页就是这张纸**：画板标签激活时画布铺满内容区，没有「关闭」、没有页面底图、没有图钉；
 * - 读写换成 board_note / board_item：笔迹落 kind=1（payload 与草稿纸 kind=4 同一份 JSON 但**不写 padId**，
 *   x/y/w/h = 画布坐标包围盒），改纸样 / 改名写 board_note 并更新 updated_at；
 * - 图片（kind=2）从 `<工作区>/Images/<sha>.<ext>` 读来显示，**本轮只显示不编辑**（与平板一致）。
 *
 * 数据纪律同 [ScratchController]：写库全走 [StoreQueue]，回主线程先核对「还是不是这一篇」；
 * 落笔乐观落地，写失败才撤掉并重读；擦除收尾一次 reconcile。
 * 视口不落库不上线，打开一律回画布原点（三端契约）。
 */
class BoardController(private val a: Activity) {

    companion object {
        const val TAG = "UniReader/Board"

        /** 图片文件的扩展名兜底顺序（`image` 表没登记时逐个试；同 Mac `ImageAssets.passthroughExts`） */
        val IMAGE_EXTS = listOf("png", "jpg", "gif", "webp", "jpeg")
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

    private val barName: TextView
    private val barZoom: TextView
    private val mapBtn: ImageButton

    init {
        canvas.visibility = View.GONE
        canvas.emptyHintTitle = a.getString(R.string.board_empty_hint)
        canvas.onStrokeEnd = { pen, pts -> commitInk(pen, pts) }
        canvas.onEraseFinish = { snapshot -> commitErase(snapshot) }
        canvas.onViewportChanged = { updateBar() }
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
        row.addView(Ui.iconButton(a, R.drawable.ic_scope, "回中", on) { canvas.recenter() })
        row.addView(Ui.iconButton(a, R.drawable.ic_fit, "适应内容", on) { canvas.fitContent() })
        mapBtn = Ui.iconButton(a, R.drawable.ic_map, "缩略图", on) {
            canvas.minimapOn = !canvas.minimapOn
            updateBar()
        }
        row.addView(mapBtn)
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
     * 打开一篇（画板标签激活时调）：先按手上的行立刻铺纸色回中，再在队列上读笔迹 + 图片 + 记「最近打开」。
     * 已经开着同一篇就只把画布显示出来（切回标签页不回中——视口是这一篇在本机的状态）。
     */
    fun open(b: BoardNote) {
        val q = queue ?: return
        val same = board?.id == b.id
        board = b
        canvas.setPaper(b.bg, b.pattern)
        canvas.visibility = View.VISIBLE
        barView.visibility = View.VISIBLE
        updateBar()
        if (same) return
        canvas.setPics(emptyList())
        canvas.openSession()   // 打开一律回中（视口不落库不上线，三端各自独立缩放滚动）
        Log.i(TAG, "打开画板 ${b.id.take(8)}《${b.title}》")
        val id = b.id
        q.submit("读画板 ${id.take(8)}", { s ->
            runCatching { s.touchBoardOpened(id) }.onFailure { Log.w(TAG, "记最近打开失败", it) }
            val items = s.boardItems(id)
            val exts = HashMap<String, String>()
            val pics = items.filter { it.kind == BoardItem.KIND_IMAGE }.mapNotNull { it.toPic() }
            for (p in pics) if (p.key !in exts) s.imageExt(p.key)?.let { exts[p.key] = it }
            Triple(s.boardStrokes(id), pics, exts)
        }, { (strokes, pics, exts) ->
            if (board?.id != id) return@submit   // 排队期间换了一篇 / 关了
            imageExts.clear()
            imageExts.putAll(exts)
            canvas.setStrokes(strokes)
            canvas.setPics(pics)
            updateBar()
            Log.i(TAG, "画板 ${id.take(8)} 笔迹 ${strokes.size} 条，图片 ${pics.size} 张")
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

    // ---------- 图片（只显示不编辑） ----------

    /** sha → 扩展名（开画板时从 `image` 表查好；查不到的按 [IMAGE_EXTS] 逐个试） */
    private val imageExts = HashMap<String, String>()

    /** kind=2 条目 → 画布上的一张图。payload `{image, caption, source}`，位置在 x/y/w/h 列 */
    private fun BoardItem.toPic(): ScratchCanvas.BoardPic? {
        val sha = try {
            JSONObject(String(payload, Charsets.UTF_8)).optString("image")
        } catch (e: Exception) {
            ""
        }
        if (sha.isEmpty() || w <= 0 || h <= 0) return null
        return ScratchCanvas.BoardPic(id, sha, x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
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

        root.addView(Ui.groupTitle(a, "底纹", top = 0))
        for ((key, label) in ScratchController.PATTERN_LABELS) {
            root.addView(optionRow(label, b.pattern == key) {
                setPaper(bg = null, pattern = key)
                dlg?.dismiss()
            })
        }

        root.addView(Ui.groupTitle(a, "纸色"))
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

    // ---------- 写库（全部走 StoreQueue；回主线程先核对画板 id） ----------

    /** 收笔：乐观落地，写失败才撤掉并重读真源纠偏（同 ScratchController.commitInk） */
    private fun commitInk(pen: Pen, pts: List<Pt3>) {
        val q = queue ?: return
        val b = board ?: return
        val id = newBoardId()
        canvas.addCommitted(Stroke(0, pen, pts, id))
        q.submit("画板落笔 点数=${pts.size}", { s ->
            runCatching { s.insertBoardStroke(b.id, pen, pts, id) }
                .onSuccess { Log.i(TAG, "画板落笔 点数=${pts.size} id=${id.take(8)}") }
                .onFailure { Log.e(TAG, "画板落笔写库失败（这一笔会丢）", it) }
                .isSuccess
        }, { ok ->
            if (!ok) {
                canvas.removeStroke(id)
                reloadStrokes()
            }
            updateBar()
        })
    }

    /** 一次擦除手势收尾：期望状态交给队列 reconcile（同 ScratchController.commitErase） */
    private fun commitErase(snapshot: List<Stroke>) {
        val q = queue ?: return
        val b = board ?: return
        // 必须在这里就 groupBy 定格：画布的 strokes 随后还会被回推整体换掉
        val local = snapshot.filter { it.id.isNotEmpty() }.groupBy { it.id }
        q.submit("画板擦除落库", { s ->
            val d = runCatching { s.reconcileBoardStrokes(b.id, local) }
                .onSuccess {
                    if (it.changed) Log.i(TAG, "画板擦除落库：删 ${it.deleted} 条，改 ${it.updated} 条，插 ${it.inserted} 段")
                }
                .onFailure { Log.e(TAG, "画板擦除写库失败，回退到库里的状态", it) }
                .getOrNull()
            if (d != null && !d.changed) null else s.boardStrokes(b.id)
        }, { list ->
            if (list != null && board?.id == b.id) canvas.setStrokes(list)
        })
    }

    private fun reloadStrokes() {
        val q = queue ?: return
        val b = board ?: return
        q.submit("重读画板笔迹", { s -> s.boardStrokes(b.id) }, { list ->
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
        mapBtn.setActive(canvas.minimapOn, Ui.onSurface(a), Ui.accent(a))
    }
}
