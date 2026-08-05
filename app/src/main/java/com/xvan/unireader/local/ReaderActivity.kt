package com.xvan.unireader.local

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibInkLayer
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.toUiLayers
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextFill
import com.xvan.unireader.shared.TextNote
import com.xvan.unireader.shared.TopBar
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.brushName
import com.xvan.unireader.shared.capsule
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.setPenSwatch
import com.xvan.unireader.shared.setTextIfChanged
import java.io.File
import kotlin.math.roundToInt

/**
 * 模式1 的阅读界面：本机 Pdfium 出图 + `LocalCanvasView`（与模式2 同一套几何/手势）。
 *
 * 进度**双向续接**（`ANDROID-STANDALONE-PLAN.md §11.3`）：打开时按 `document` 的四列复原
 * （先缩放再滚——顺序反了 offY 还是旧的，滚到的位置会偏），退出/切后台时写回。
 *
 * 打开的那一串 I/O 全在后台（§9.5）：开库、找 PDF、Pdfium 读**全部**页尺寸（几百页的文档不便宜）、
 * 读笔迹与图层。后台备齐了才一次性装配到画布上——中途装一半的话，几何还没就绪就开始画页，
 * 会先闪一屏错位。
 *
 * 打开之后的读写同样不在主线程：库交给 [StoreQueue] 的独占线程，这个类里剩下的活儿只是
 * **攒好参数丢进队列、拿回结果刷界面**。
 */
class ReaderActivity : Activity() {

    companion object {
        const val TAG = "UniReader/Reader"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_DOC_ID = "docId"

        /** 进度落库节流：滚动上报是 16ms 一次，直接写库等于一秒几十次事务 */
        const val SAVE_INTERVAL_MS = 2000L

        fun start(ctx: Context, workspaceDir: File, documentId: String) {
            ctx.startActivity(
                Intent(ctx, ReaderActivity::class.java)
                    .putExtra(EXTRA_WORKSPACE, workspaceDir.absolutePath)
                    .putExtra(EXTRA_DOC_ID, documentId),
            )
        }
    }

    /** 后台一趟读齐的开档数据。主线程拿到它才开始装画布 */
    private class Opened(
        val store: LibraryStore,
        val doc: LibDocument,
        val pdf: PdfSource,
        /** 整张图层表：画布要它的可见性过滤，图层面板要它的 id（面板本身按下标交互） */
        val layers: List<LibInkLayer>,
        val strokes: List<Stroke>,
        /** 文字注解（kind=0）与它们/高亮（kind=3）的铺色，同样在后台一趟读齐 */
        val notes: List<TextNote>,
        val fills: List<TextFill>,
    ) {
        /**
         * 界面已关时的收尾：句柄不收，WAL 就一直挂着，下次打开这个工作区会读到半新半旧的状态。
         * **也得走后台**——`discard` 的回调本身在主线程上，而 `store.close()` 内含
         * `wal_checkpoint(TRUNCATE)`，实测在模拟器 FUSE 卷上就要 1 秒（§9.5）。
         */
        fun discard(docId: String) = Bg.submit("丢弃已打开的文档 ${docId.take(8)}") {
            runCatching { pdf.close() }
            runCatching { store.close() }
        }
    }

    private lateinit var canvas: LocalCanvasView
    private lateinit var bar: TopBar
    private lateinit var openingLabel: TextView
    private lateinit var penStat: TextView
    private lateinit var layerStat: TextView

    /** 库的独占线程。`attach` 之后主线程只通过它碰库（§9.5） */
    private var queue: StoreQueue? = null

    /** 当前文档的图层表（`ink_layer` 的内存镜像）。改完一律重读，不本地推算 */
    private var layers = listOf<LibInkLayer>()
    private var pdf: PdfSource? = null
    private var workspace: File? = null
    private var docId = ""
    private val handler = Handler(Looper.getMainLooper())

    // 待落库的进度（滚动线程外只有主线程写，无需加锁）
    private var curPage = 0
    private var curFrac = 0f
    private var dirty = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wsPath = intent.getStringExtra(EXTRA_WORKSPACE)
        docId = intent.getStringExtra(EXTRA_DOC_ID) ?: ""
        if (wsPath == null || docId.isEmpty()) {
            Log.e(TAG, "缺参数：workspace=$wsPath docId=$docId")
            finish()
            return
        }
        val ws = File(wsPath)
        workspace = ws
        buildUi()
        open(ws)
    }

    private fun buildUi() {
        canvas = LocalCanvasView(this).apply {
            onProgress = { page, frac ->
                curPage = page
                curFrac = frac
                dirty = true
            }
            onHud = { refreshHud() }
            // 文字笔记：编辑器面板与模式2 是同一份（shared/PadPanels），只是保存去处不同——
            // 那边编帧发给 Mac，这边直接落 note 表（kind=0）
            onNoteEditor = { id, page, nx, ny, text, isNew -> editNote(id, page, nx, ny, text, isNew) }
        }
        // 顶栏与模式2 是同一份（shared/TopBar）：这里只声明有哪几个键、按下去干什么，
        // 形状/间距/开关态的表达/溢出菜单的行为都由它统一。
        bar = TopBar(this).apply {
            icon("prev", R.drawable.ic_chevron_left, "上一页", spillFirst = true) { canvas.turn(prev = true) }
            icon("next", R.drawable.ic_chevron_right, "下一页", spillFirst = true) { canvas.turn(prev = false) }
            gap()
            // 轮换用基类的（笔记→擦除→翻页→框选，与模式2 的模式键/PageUp 同一份实现）：
            // 早先这里自己写了一份三态轮换来绕开框选，那时它的落库钩子还没做，现在有了
            icon("mode", TopBar.modeIcon(MODE_PAGE), "切换模式") { canvas.cycleMode(); refreshHud() }
            icon("pen", R.drawable.ic_nib, "切换笔") { canvas.cyclePen(); refreshHud() }
            // 尺子（45° 吸附，首版范围 §3）：走基类的 toggleRuler，吸附算法与两端同源（PadConst.rulerSnap）
            icon("ruler", R.drawable.ic_ruler, "尺子") { canvas.toggleRuler(); refreshHud() }
            // 文字笔记模式（M5）：开着时笔点页面 = 开编辑器而不是写字，与模式2 的「文字」键同一开关
            icon("text", R.drawable.ic_text, "文字笔记") { canvas.toggleNoteMode(); refreshHud() }
            pageLabel.setOnClickListener { showGotoPage() }
            // 低频项进 ⋯：夜间/页图/锁缩放的能力本来就在基类（模式2 早有），模式1 之前没接入口
            overflowItems = {
                listOf(
                    TopBar.MenuItem("夜间模式", canvas.night) { canvas.toggleNight(); refreshHud() },
                    TopBar.MenuItem("显示页面图", canvas.showPage) { canvas.toggleShowPage(); refreshHud() },
                    TopBar.MenuItem("锁定缩放", canvas.zoomLocked) { canvas.toggleZoomLock(); refreshHud() },
                    TopBar.MenuItem("图层…") { showLayers() },
                    TopBar.MenuItem("跳到第…页") { showGotoPage() },
                )
            }
        }
        // 左下状态胶囊：与模式2 同一份样式与文案格式（shared/Widgets.kt + PadPanels）。
        // 面板改完即时生效（基类自己管），这里只负责把结果存下来——模式2 那两个回调是上行给 Mac 的，
        // 模式1 没有 Mac，改完不存的话退出即丢（见 ToolPrefs）。
        penStat = capsule(this).apply {
            setOnClickListener {
                PadPanels.showPenPanel(
                    this@ReaderActivity, canvas,
                    onPenset = { ToolPrefs.save(this@ReaderActivity, canvas) },
                    onEraser = { ToolPrefs.save(this@ReaderActivity, canvas) },
                )
            }
        }
        layerStat = capsule(this).apply { setOnClickListener { showLayers() } }
        openingLabel = Ui.body(this, "正在打开…").apply { textSize = 15f }
        // 两颗胶囊各自 wrap：不给 penStat 显式 LayoutParams 的话它默认 MATCH_PARENT，
        // 在 wrap 的竖向容器里就被夹成「最宽那颗」的宽度——「翻页 · 拖动平移」会被截成「拖动平」。
        val capsules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layerStat, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(penStat, LinearLayout.LayoutParams(-2, -2))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.col(this@ReaderActivity, R.color.surface_dim))
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(bar.view, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(openingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(
                capsules,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(10); bottomMargin = dp(10)
                },
            )
        }
        setContentView(root)
        canvas.setBarHeight(bar.height().toFloat())
        // 笔/橡皮/夜间是设备级偏好，与文档无关，所以在这里（打开文档之前）就复原
        ToolPrefs.load(this, canvas)
        refreshHud()
        // 顶栏避开状态栏：不处理的话按钮压在时钟上，点击会被系统栏吃掉（见 shared/Insets.kt）。
        // 胶囊同理要避开底部导航栏——模式2 不用管这个（它整屏沉浸），模式1 有系统栏。
        root.onSystemBarInsets { top, bottom ->
            canvas.setBarHeight(bar.applyTopInset(top).toFloat())
            val clp = capsules.layoutParams as FrameLayout.LayoutParams
            if (clp.bottomMargin != dp(10) + bottom) {
                clp.bottomMargin = dp(10) + bottom
                capsules.layoutParams = clp
            }
        }
    }

    private fun open(ws: File) {
        runInBackground(
            what = "打开文档 ${docId.take(8)}",
            work = { load(ws) },
            ok = { attach(it) },
            fail = { failToOpen(it.message ?: it.javaClass.simpleName) },
            discard = { it.discard(docId) },   // 后台开完发现界面已关：句柄在这里收
        )
    }

    /**
     * 后台线程：把开这篇文档需要的一切读齐。
     *
     * 任一步失败都要**先关掉已经开出来的句柄再抛**——只抛不关的话，用户在书库里连点几本打不开的书，
     * 就攒下几个悬着的库连接（每个都带 WAL）。
     */
    private fun load(ws: File): Opened {
        val s = LibraryStore.open(ws)
        var pdfSrc: PdfSource? = null
        try {
            val doc = s.document(docId) ?: error("库里没有这个文档：$docId")
            val file = Workspace.firstOpenablePdf(ws, s, docId)
                ?: error("《${doc.title}》没有能打开的 PDF——需要在 Mac 上把文件拷进工作区")
            // applicationContext：这条任务可能比 Activity 活得久（PdfiumCore 只用它加载 native 库）
            val src = PdfSource(applicationContext, file)
            pdfSrc = src
            src.logPageSizes()   // §9.1 的比对凭据：与 tools/dump-page-sizes.swift 的输出逐行 diff
            // 落库上下文 + 当前作画图层（老文档可能没有图层行，补一条默认层，同 Mac 的行为）
            s.ensureDefaultLayer(docId)
            s.updateLastOpened(docId)
            return Opened(
                store = s,
                doc = doc,
                pdf = src,
                layers = s.inkLayers(docId),
                strokes = s.strokes(docId),
                notes = s.textNotes(docId),
                fills = s.textFills(docId),
            )
        } catch (e: Throwable) {
            runCatching { pdfSrc?.close() }
            runCatching { s.close() }
            throw e
        }
    }

    /** 主线程：把后台备好的东西一次性装到画布上。这里一行 I/O 都不做。 */
    private fun attach(o: Opened) {
        // 库在这里从 `unireader-io`（开它的那条线程）转交给 `unireader-store` 独占，
        // 主线程只留住队列本身。两次交接都过 Handler/Executor，有 happens-before。
        val q = StoreQueue(o.store)
        queue = q
        pdf = o.pdf
        openingLabel.visibility = View.GONE
        title = o.doc.title

        // 这个卷建不起 WAL（FAT32/exFAT 的 U 盘，§9.3）：不是错误，但得说一声——退出时的
        // checkpoint 会变成空操作，「搬运前先把 -wal 合并回主库」那层保险在这里没有。
        if (!q.walEnabled) {
            Log.w(TAG, "这个卷不支持 WAL：笔迹照常落库，但 wal_checkpoint 是空操作")
            Toast.makeText(
                this,
                "这个卷不支持 WAL（多半是 FAT32/exFAT 的 U 盘）：笔迹照常保存，" +
                    "搬回 Mac 前请把整个 .unrd 文件夹一起拷",
                Toast.LENGTH_LONG,
            ).show()
        }

        // 进度复原：等**几何首次就绪**之后再做（否则 offY 还是 width=0 时算的，滚过去等于滚到页顶），
        // 且顺序必须是先缩放再滚——反了 offY 是旧内容宽下的值，落点会偏。
        // **这个钩子必须挂在 setPages 之前**：此刻视口早就布局好了（打开是异步的），页表一到
        // 就地回调，挂晚一步就永远等不到第二次机会。
        val doc = o.doc
        curPage = doc.readPage
        curFrac = doc.readFrac.toFloat()
        canvas.onFirstGeometry = {
            if (doc.readZoom > 0) canvas.applyZoom(doc.readZoom.toFloat())
            canvas.applyHFrac(doc.readHFrac.toFloat())
            val ok = canvas.scrollToPageFrac(doc.readPage, doc.readFrac.toFloat())
            Log.i(TAG, "复原滚动 → 第 ${doc.readPage + 1} 页 ${"%.3f".format(doc.readFrac)} ok=$ok")
            refreshHud()
        }
        canvas.imageSource = o.pdf
        canvas.setPages(
            o.pdf.pageCount,
            o.pdf.pageSizes.map { it[0] to it[1] },
            reset = true,
        )
        canvas.store = q
        canvas.documentId = docId
        layers = o.layers
        canvas.activeLayerId = layers.firstOrNull()?.id ?: LibInkLayer.DEFAULT_ID
        canvas.setMode(MODE_PAGE)
        canvas.applyStrokes(o.strokes, hiddenLayerIds())
        canvas.applyNotes(o.notes, o.fills)
        Log.i(
            TAG,
            "打开《${doc.title}》${o.pdf.pageCount} 页 笔迹 ${o.strokes.size} 条 " +
                "注解 ${o.notes.size} 条 铺色 ${o.fills.size} 片，" +
                "复原到 第 ${doc.readPage + 1} 页 ${"%.3f".format(doc.readFrac)} " +
                "zoom=${doc.readZoom} hfrac=${doc.readHFrac}",
        )
        handler.postDelayed(saver, SAVE_INTERVAL_MS)
    }

    /**
     * 打不开就把原因摆出来再退。原先是静默 `finish()`——界面闪一下回到书库，用户只知道
     * 「点了没用」，而这几种失败（PDF 路径失效 / 库损坏 / 卷掉了）需要他去 Mac 上处理。
     */
    private fun failToOpen(reason: String) {
        openingLabel.text = "打不开这个文档"
        Sheet(this)
            .title("打不开这个文档")
            .subtitle(reason)
            .cancelable(false)
            .action("返回", primary = true)
            .show()
            .setOnDismissListener { finish() }
    }

    /**
     * 每帧都会调（滚动/缩放都触发），文本一律走 `setTextIfChanged`——见 shared/Widgets.kt。
     * 图标那几个是幂等的 setter，同值重设不触发 layout，不必额外挡。
     */
    private fun refreshHud() {
        bar.setPageLabel(canvas.hudPage(), canvas.hudZoom())
        // 模式键的图标随当前模式变，开关键按下去是 accent 底色——两模式同一套表达（shared/TopBar）
        bar.setIcon("mode", TopBar.modeIcon(canvas.mode))
        bar.setActive("mode", canvas.mode != MODE_PAGE)
        bar.setActive("ruler", canvas.rulerOn)
        bar.setActive("text", canvas.noteMode)
        bar.setEnabled("pen", canvas.mode == MODE_NOTE)
        // 胶囊文案与模式2 逐字一致（PadActivity.refresh）：两模式看起来必须是同一个 App
        val pen = canvas.curPenOrNull()
        val notePen = pen.takeIf { canvas.mode == MODE_NOTE }
        penStat.setTextIfChanged(
            when (canvas.mode) {
                MODE_NOTE ->
                    if (pen != null) "${PadConst.brushLabel(brushName(pen.brush))} · ${(pen.w * 100).toInt() / 100f}pt"
                    else "笔记"
                MODE_ERASE -> "橡皮擦"
                MODE_LASSO -> "框选移动"
                else -> "翻页 · 拖动平移"
            },
        )
        penStat.setPenSwatch(notePen?.let { Color.argb((it.a * 255f).roundToInt().coerceIn(0, 255), it.r, it.g, it.b) })
        layerStat.setTextIfChanged(activeLayer()?.let { "图层：${it.name}" } ?: "图层")
    }

    // ---------- 图层（面板与模式2 共用一份，见 shared/PadPanels.kt） ----------

    private fun activeLayer(): LibInkLayer? = layers.firstOrNull { it.id == canvas.activeLayerId }

    private fun hiddenLayerIds(): Set<String> =
        layers.filter { !it.visible }.map { it.id }.toSet()

    /**
     * 模式2 里这三件事只是「请求」，由 Mac 判定后广播权威状态回来；模式1 的真源就在进程内，
     * 所以**直接写 `ink_layer` 表再整表重读**——不本地推算列表，避免内存镜像与库里悄悄分叉。
     * 写与重读在同一个队列作业里做完，回主线程时 `layers` 与笔迹一起换掉；写失败也照读，
     * 界面于是退回库里的状态，不会显示一个没存进去的开关。
     *
     * 面板按**下标**回调（线格式就是按下标发的），这里用下标换 id。
     */
    private fun showLayers() {
        val q = queue ?: return
        PadPanels.showLayerPanel(
            this, layers.toUiLayers(), layers.indexOfFirst { it.id == canvas.activeLayerId },
            onSelect = { i ->
                layers.getOrNull(i)?.let {
                    canvas.activeLayerId = it.id
                    Log.i(TAG, "作画图层 → ${it.name}")
                    refreshHud()
                }
            },
            onToggleVisible = { i, visible ->
                layers.getOrNull(i)?.let { l ->
                    q.submit(
                        "切图层可见性 ${l.name}",
                        { s ->
                            runCatching { s.upsertInkLayer(l.copy(visible = visible)) }
                                .onFailure { Log.e(TAG, "写图层可见性失败", it) }
                            s.inkLayers(docId) to s.strokes(docId)
                        },
                        { (ls, all) ->
                            layers = ls
                            // 隐藏的图层数据一条不动，只是不画（同 Mac 的 broadcastStrokes 过滤口径）
                            canvas.applyStrokes(all, hiddenLayerIds())
                            refreshHud()
                        },
                    )
                }
            },
            onAdd = {
                q.submit(
                    "新建图层",
                    { s ->
                        val l = runCatching { s.addInkLayer(docId) }
                            .onFailure { Log.e(TAG, "新建图层失败", it) }
                            .getOrNull()
                        l to s.inkLayers(docId)
                    },
                    { (l, ls) ->
                        layers = ls
                        if (l != null) {
                            canvas.activeLayerId = l.id   // 新建即切过去（同 Mac 的 layerAdd）
                            Log.i(TAG, "新建图层 ${l.name}，已设为作画图层")
                        }
                        refreshHud()
                    },
                )
            },
        )
    }

    // ---------- 文字注解（编辑器面板与模式2 共用，见 shared/PadPanels.kt） ----------

    /**
     * 打开笔记编辑器。**空文本的处理与 Mac 一致**：新建时空文本 = 什么都不发生（不建空注解），
     * 已有的改成空 = 删除（Mac `applyTextNote` 里空 upsert 就是删）。落库走画布的钩子，
     * 由它落完再整表回推——这里不直接碰 store，免得内存与库分叉。
     */
    private fun editNote(id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean) {
        PadPanels.showNoteEditor(
            this, text, isNew,
            onSave = { t ->
                if (t.isEmpty()) { if (!isNew) canvas.deleteNote(id, page, nx, ny) }
                else canvas.upsertNote(id, page, nx, ny, t)
            },
            onDelete = { canvas.deleteNote(id, page, nx, ny) },
        )
    }

    private fun showGotoPage() {
        val n = canvas.pageCountOrZero()
        if (n <= 0) return
        PadPanels.showGotoPage(this, n) { canvas.gotoPage(it) }
    }

    // ---------- 进度落库 ----------

    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /**
     * 四个数在主线程取好再丢进队列——`zoomLevel()`/`hFrac()` 是视图状态，队列线程不许读。
     * 写失败只留日志：下一次滚动会再置脏、两秒后重来一遍。
     */
    private fun saveProgress() {
        if (!dirty) return
        val q = queue ?: return
        dirty = false
        val page = curPage
        val frac = curFrac.toDouble()
        val zoom = canvas.zoomLevel().toDouble()
        val hfrac = canvas.hFrac().toDouble()
        q.submit("写进度 第${page + 1}页") { it.updateProgress(docId, page, frac, zoom, hfrac) }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()   // 切后台立刻落盘：进程随时可能被杀
        // 顶栏切的夜间、环形盘/切笔键换的笔——都在这一刻存下来（面板改的已经即时存过了）
        ToolPrefs.save(this, canvas)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgress()   // 排进队列，一定落在下面那句关库之前（FIFO）
        // 收尾也别在主线程：`store.close()` 内含 wal_checkpoint(TRUNCATE)，慢卷上退出会卡住整个
        // 返回动画（§9.5）。关库排在队尾由队列自己做，Pdfium 那半边照旧甩给 Bg。
        val q = queue
        val p = pdf
        queue = null
        pdf = null
        canvas.store = null
        q?.close()
        if (p != null) Bg.submit("关闭 PDF ${docId.take(8)}") { p.close() }
        super.onDestroy()
    }
}
