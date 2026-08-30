package com.xvan.unireader.local

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.mirror.MirrorStore
import com.xvan.unireader.local.mirror.MirrorUi
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteKind
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.showAlert
import java.io.File

/**
 * 工作区的文档列表（模式1）：标题、页数、阅读进度、笔迹条数、上次打开。
 *
 * 每次 `onResume` 重读一遍库——从阅读界面退回来时进度已经变了，缓存一份反而要自己同步。
 * 列表规模是「一个人的书架」，几十条，重读的开销可以忽略。
 *
 * 但**读库本身不能在主线程**（§9.5）：`LibraryStore.open` 在慢卷上是秒级，每个文档还要一次
 * `firstOpenablePdf`（逐条 location 做 `isFile`）。所以读盘在后台整批做完出一个 [Snapshot]，
 * 主线程只建 View。
 */
class LibraryActivity : Activity() {

    companion object {
        const val TAG = "UniReader/Library"
        const val EXTRA_WORKSPACE = "workspace"

        fun start(ctx: Context, workspaceDir: File) {
            ctx.startActivity(
                Intent(ctx, LibraryActivity::class.java)
                    .putExtra(EXTRA_WORKSPACE, workspaceDir.absolutePath),
            )
        }
    }

    /** 一行要显示的全部东西，全在后台备好（主线程不再碰库） */
    private class Row(val doc: LibDocument, val ink: Int, val pdfOk: Boolean, val opened: Boolean)
    private class Snapshot(
        val name: String,
        val info: String,
        val rows: List<Row>,
        /** 这个工作区是不是离线副本（换角标、换「打不开」的文案、换右上那颗键的动作） */
        val isMirror: Boolean,
    )

    private lateinit var list: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var header: TextView
    private var workspace: File? = null

    /** 只认最后一次 reload 的结果：`onResume` 可能在前一次读盘还没回来时又触发一次 */
    private var loadToken: Any? = null

    /** 当前工作区是不是离线副本（`read()` 里取，决定右上那颗键给哪条动作） */
    private var isMirror = false

    /**
     * 「添加 PDF」期间那条**可写**连接（用完即关，见 [startAdd]）。列书单用的是另一条只读连接，
     * 两者不共用：只读那条连 `wal_checkpoint` 都做不了，写不了库。
     */
    private var addQueue: StoreQueue? = null

    /** 这一轮添加里每个文件的状态（路径 → 尾标）：加过的行标出来，别让人重复点同一本 */
    private val addNotes = HashMap<String, String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_WORKSPACE)
        if (path == null) {
            Log.e(TAG, "缺 workspace 参数")
            finish()
            return
        }
        workspace = File(path)
        header = Ui.body(this, "").apply { setPadding(0, 0, 0, dp(4)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = Ui.title(this, "", 26f)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            addView(headerRow())
            addView(Ui.spacer(this@LibraryActivity, 12))
            addView(list)
        }
        val scroll = ScrollView(this).apply {
            addView(col)
            setBackgroundColor(Ui.surface(this@LibraryActivity))
        }
        setContentView(scroll)
        scroll.onSystemBarInsets { top, bottom -> scroll.setPadding(0, top, 0, bottom) }
    }

    /** 标题 + 一行统计占左边，右边一颗「添加 PDF」——整屏只有这一个新增入口 */
    private fun headerRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(header)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(Ui.iconButton(this@LibraryActivity, R.drawable.ic_plus, "添加 PDF") { startAdd() })
        addView(Ui.iconButton(this@LibraryActivity, R.drawable.ic_more, "更多") { showMore() })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        closeAddQueue()
        super.onDestroy()
    }

    private fun reload() {
        val ws = workspace ?: return
        val token = Any()
        loadToken = token
        // 首次才显示「正在读取」：从阅读界面退回来时保留旧列表，读完再整体换掉——
        // 先清空的话每次返回都会闪一下白屏（慢卷上闪好几秒）
        if (list.childCount == 0) header.text = "正在读取工作区…"
        runInBackground(
            what = "读库 ${ws.name}",
            work = { read(ws) },
            ok = { snap ->
                if (loadToken === token) render(ws, snap)
            },
            fail = { e ->
                if (loadToken === token) {
                    Log.e(TAG, "读库失败", e)
                    list.removeAllViews()
                    header.text = "打不开工作区：${e.message}"
                }
            },
        )
    }

    /** 后台线程：一次把库读干净就关掉连接，`store` 不外泄给主线程（它非线程安全） */
    private fun read(ws: File): Snapshot =
        LibraryStore.open(ws, readOnly = true).use { store ->
            val docs = store.allDocuments()
            // 这个工作区的标签页组（§13）：阅读界面开着哪几篇，列表上标出来——点已开着的那篇
            // 是切过去而不是重新打开，先说清楚免得以为点错了
            val opened = TabSet.openDocIds(this, ws.absolutePath)
            val mirror = MirrorUi.isMirror(store)
            // 借出记录只在源盘那边有；一行足矣（「是信息不是锁」，方案 §5.2）
            val lent = MirrorStore.decodeCheckouts(store.meta(MirrorStore.META_CHECKOUTS)).size
            Snapshot(
                name = store.workspaceName().ifEmpty { ws.name },
                isMirror = mirror,
                info = buildString {
                    append("${docs.size} 个文档")
                    if (opened.isNotEmpty()) append("　${opened.size} 个在标签页里")
                    if (mirror) append("　离线副本")
                    if (lent > 0) append("　借出 $lent 份")
                },
                rows = docs.map { d ->
                    Row(
                        doc = d,
                        ink = store.noteCount(d.id, NoteKind.INK),
                        pdfOk = Workspace.firstOpenablePdf(ws, store, d.id) != null,
                        opened = d.id in opened,
                    )
                },
            )
        }

    private fun render(ws: File, snap: Snapshot) {
        isMirror = snap.isMirror
        list.removeAllViews()
        title = ws.name
        titleView.text = snap.name
        header.text = snap.info
        for ((i, r) in snap.rows.withIndex()) {
            if (i > 0) list.addView(Ui.divider(this))
            list.addView(row(r.doc, r.ink, r.pdfOk, r.opened, snap.isMirror))
        }
        if (snap.rows.isEmpty()) {
            list.addView(
                Ui.card(this).apply {
                    addView(Ui.title(this@LibraryActivity, "还没有文档", 17f))
                    addView(
                        Ui.body(this@LibraryActivity, "从平板上挑几个 PDF 加进来。")
                            .apply { setPadding(0, dp(6), 0, dp(14)) },
                    )
                    addView(
                        Ui.button(this@LibraryActivity, "添加 PDF", filled = true) { startAdd() },
                    )
                    addView(
                        Ui.tip(this@LibraryActivity, "也可以在 Mac 上导入，再把整个 .unrd 搬过来")
                            .apply { setPadding(0, dp(8), 0, 0) },
                    )
                },
            )
        }
    }

    /**
     * 一条书。整行可点（涟漪铺满行），不是行里塞个按钮——列表项的点按目标就该是整行。
     * 进度做成一条细进度条：数字要读，条一眼就看得到读到哪儿了。
     */
    private fun row(d: LibDocument, inkCount: Int, pdfOk: Boolean, opened: Boolean, isMirror: Boolean): View {
        val col = Ui.row(this, if (pdfOk) ({ workspace?.let { ReaderActivity.start(this, it, d.id) } }) else null)
        col.addView(
            Ui.title(this, d.title, 17f).apply {
                if (!pdfOk) setTextColor(Ui.onVariant(this@LibraryActivity))
                else if (opened) setTextColor(Ui.accent(this@LibraryActivity))
            },
        )
        val ratio = if (d.pageCount > 0) ((d.readPage + d.readFrac) / d.pageCount).toFloat() else 0f
        val started = d.readPage > 0 || d.readFrac > 0
        col.addView(
            Ui.body(
                this,
                buildString {
                    append("${d.pageCount} 页")
                    append(if (started) "　读到第 ${d.readPage + 1} 页（${(ratio * 100).toInt()}%）" else "　未开始")
                    if (inkCount > 0) append("　笔迹 $inkCount 条")
                    if (opened) append("　已在标签页里")
                },
            ).apply { setPadding(0, dp(4), 0, dp(8)) },
        )
        if (started) col.addView(progressBar(ratio))
        if (!pdfOk) {
            col.addView(
                // 镜像里「没带 PDF」是刻意的（库全量、PDF 选择性，方案 §7），不是出错——
                // 说成「重新关联」会把用户引到一条根本不该走的路上
                Ui.body(this, if (isMirror) "没有离线——插回源盘才能看" else "找不到 PDF 文件——重新添加一次，或回 Mac 上重新关联")
                    .apply { setTextColor(Ui.col(this@LibraryActivity, R.color.danger)); textSize = 12f },
            )
        }
        return col
    }

    // ---------- 添加 PDF ----------

    /**
     * 「添加 PDF」：开一条**可写**连接 → 浏览本机文件挑 PDF → 每挑一本就拷进 `PDFs/` 并入库
     * （[PdfImport]）→ 关窗时收掉连接、重读列表。
     *
     * **窗不关就一直开着那条连接**：一次多半要加好几本，每本重开一次库等于每本都付一遍
     * 慢卷上的开库 + `wal_checkpoint`（秒级）。作业全排在 [StoreQueue] 的独占线程上，
     * 串行、有序，主线程一行 I/O 都不做（§9.5）。
     *
     * 阅读界面（[ReaderActivity]）可能同时开着同一个工作区的另一条连接——那是**同一个进程内的
     * 两条 SQLite 连接**，WAL + `busy_timeout=3000` 管得住；跨端的单写者约束（§9.2）说的是
     * 两台设备，不是这个。
     */
    private fun startAdd() {
        val ws = workspace ?: return
        if (addQueue != null) return
        runInBackground(
            what = "开可写库 ${ws.name}",
            work = { LibraryStore.open(ws, readOnly = false) },
            ok = { store ->
                addQueue = StoreQueue(store)
                addNotes.clear()
                browseForPdf(ws)
            },
            fail = { alert("加不了 PDF", "打不开工作区的库：${it.message}") },
            // 界面已经关了：开出来的库必须收掉，否则 WAL 一直挂着（§9.2）
            discard = { runCatching { it.close() } },
        )
    }

    private fun browseForPdf(ws: File) {
        var browser: FileBrowser? = null
        browser = FileBrowser(
            this,
            FileBrowser.Mode.PDF,
            title = "添加 PDF",
            tipText = "点一个就加一本，可以接着加下一本；文件会拷进工作区",
            fileNote = { f -> addNotes[f.absolutePath] },
            onDismiss = {
                closeAddQueue()
                reload()      // 加进来的书要出现在列表里
            },
        ) { f -> ingest(ws, f) { browser?.refresh() } }
        browser.show()
    }

    private fun ingest(ws: File, f: File, onChanged: () -> Unit) {
        val q = addQueue ?: return
        val key = f.absolutePath
        addNotes[key] = "添加中…"
        onChanged()
        q.submit(
            "导入 ${f.name}",
            { store -> runCatching { PdfImport.ingest(this, ws, store, f) } },
        ) { r ->
            r.fold(
                onSuccess = { res ->
                    addNotes[key] = when (res) {
                        is PdfImport.Result.Added -> "已添加"
                        is PdfImport.Result.Duplicate -> "已在库中"
                    }
                },
                onFailure = { e ->
                    // 失败的行把标记去掉，让用户能原地再点一次（换个文件、或等 U 盘缓过来）
                    addNotes.remove(key)
                    Log.e(TAG, "导入 ${f.name} 失败", e)
                    alert("加不进来：${f.name}", e.message ?: "未知错误")
                },
            )
            onChanged()
        }
    }

    /**
     * 收掉那条可写连接。[StoreQueue.close] 自己就把关库排在队尾（还没跑完的导入会先落盘，
     * `wal_checkpoint(TRUNCATE)` 在队列线程上做），所以这里直接调用即可，不用再往后台甩一层。
     */
    private fun closeAddQueue() {
        addQueue?.close()
        addQueue = null
    }

    /**
     * 右上「更多」：镜像与源盘**互斥**地给一条动作——镜像不能再做镜像（[MirrorBuilder] 也会拦），
     * 源盘也没有「同步回去」这回事。只出现该出现的那条，不给用户做无效选择的机会。
     */
    private fun showMore() {
        val ws = workspace ?: return
        val sheet = Sheet(this).title(if (isMirror) "离线副本" else "工作区")
        if (isMirror) {
            sheet.subtitle("插上源盘就能预览同步会做什么")
            sheet.action("同步预览…", primary = true) { MirrorUi.syncPreview(this, ws) }
        } else {
            sheet.subtitle("复制一份到内部存储，拔了盘也能看和写")
            sheet.action("做成离线副本…", primary = true) { MirrorUi.makeMirror(this, ws) { reload() } }
        }
        sheet.action("取消")
        sheet.show()
    }

    private fun alert(title: String, msg: String) = showAlert(title, msg)

    /** 3dp 高的读进度条：底槽 outline、进度 accent，纯色无渐变 */
    private fun progressBar(ratio: Float): View {
        val bar = View(this).apply { setBackgroundColor(Ui.accent(this@LibraryActivity)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(Ui.outline(this@LibraryActivity), 2, this@LibraryActivity)
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            addView(bar, LinearLayout.LayoutParams(0, -1, ratio.coerceIn(0.02f, 1f)))
            addView(View(this@LibraryActivity), LinearLayout.LayoutParams(0, -1, 1f - ratio.coerceIn(0.02f, 1f)))
        }
    }
}
