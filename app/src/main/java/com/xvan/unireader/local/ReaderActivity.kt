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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibInkLayer
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.toUiLayers
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.brushName
import com.xvan.unireader.shared.capsule
import com.xvan.unireader.shared.onSystemBarInsets
import com.xvan.unireader.shared.runInBackground
import com.xvan.unireader.shared.setTextIfChanged
import java.io.File

/**
 * 模式1 的阅读界面：本机 Pdfium 出图 + `LocalCanvasView`（与模式2 同一套几何/手势）。
 *
 * 进度**双向续接**（`ANDROID-STANDALONE-PLAN.md §11.3`）：打开时按 `document` 的四列复原
 * （先缩放再滚——顺序反了 offY 还是旧的，滚到的位置会偏），退出/切后台时写回。
 *
 * 打开的那一串 I/O 全在后台（§9.5）：开库、找 PDF、Pdfium 读**全部**页尺寸（几百页的文档不便宜）、
 * 读笔迹与图层。后台备齐了才一次性装配到画布上——中途装一半的话，几何还没就绪就开始画页，
 * 会先闪一屏错位。
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
    private lateinit var pageLabel: TextView
    private lateinit var modeBtn: Button
    private lateinit var penBtn: Button
    private lateinit var rulerBtn: Button
    private lateinit var openingLabel: TextView
    private lateinit var penStat: TextView
    private lateinit var layerStat: TextView
    private var store: LibraryStore? = null

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
        val barH = dp(44)
        canvas = LocalCanvasView(this).apply {
            onProgress = { page, frac ->
                curPage = page
                curFrac = frac
                dirty = true
            }
            onHud = { refreshHud() }
        }
        pageLabel = TextView(this).apply {
            text = "— / —"
            textSize = 13f
            setTextColor(Color.WHITE)
            setOnClickListener { showGotoPage() }
        }
        // 轮换用基类的（笔记→擦除→翻页→框选，与模式2 的模式键/PageUp 同一份实现）：
        // 早先这里自己写了一份三态轮换来绕开框选，那时它的落库钩子还没做，现在有了
        modeBtn = barBtn("翻页") { canvas.cycleMode(); refreshHud() }
        penBtn = barBtn("笔") { canvas.cyclePen(); refreshHud() }
        // 尺子（45° 吸附，首版范围 §3）：走基类的 toggleRuler，吸附算法与两端同源（PadConst.rulerSnap）
        rulerBtn = barBtn("尺子") { canvas.toggleRuler(); refreshHud() }
        // 左下状态胶囊：与模式2 同一份样式与文案格式（shared/Widgets.kt + PadPanels）
        penStat = capsule(this).apply {
            setOnClickListener { PadPanels.showPenPanel(this@ReaderActivity, canvas) }
        }
        layerStat = capsule(this).apply { setOnClickListener { showLayers() } }
        // 按钮组装在可横滑的容器里、页码固定在最右：竖屏 1080 宽下五个按钮就顶到边了，
        // 按 wrap 排会把页码（也是跳页入口）直接挤出屏幕——按钮以后还会加。
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(barBtn("◀") { canvas.turn(prev = true) })
            addView(barBtn("▶") { canvas.turn(prev = false) })
            addView(modeBtn)
            addView(penBtn)
            addView(rulerBtn)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // 挡住触摸穿透到画布
            setBackgroundColor(0xE6161B22.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(btns)
                },
                LinearLayout.LayoutParams(0, -1, 1f),
            )
            addView(pageLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        openingLabel = TextView(this).apply {
            text = "正在打开…"
            textSize = 15f
            setTextColor(0xFF6B6B6B.toInt())
        }
        val capsules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layerStat, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(penStat)
        }
        val root = FrameLayout(this).apply {
            addView(canvas, FrameLayout.LayoutParams(-1, -1))
            addView(bar, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
            addView(
                openingLabel,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
            )
            addView(
                capsules,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(10); bottomMargin = dp(10)
                },
            )
        }
        setContentView(root)
        canvas.setBarHeight(barH.toFloat())
        // 顶栏避开状态栏：不处理的话按钮压在时钟上，点击会被系统栏吃掉（见 shared/Insets.kt）。
        // 胶囊同理要避开底部导航栏——模式2 不用管这个（它整屏沉浸），模式1 有系统栏。
        root.onSystemBarInsets { top, bottom ->
            val lp = bar.layoutParams as FrameLayout.LayoutParams
            if (lp.height != barH + top) {
                lp.height = barH + top
                bar.layoutParams = lp
                bar.setPadding(dp(10), top, dp(10), 0)
                canvas.setBarHeight((barH + top).toFloat())
            }
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
            )
        } catch (e: Throwable) {
            runCatching { pdfSrc?.close() }
            runCatching { s.close() }
            throw e
        }
    }

    /** 主线程：把后台备好的东西一次性装到画布上。这里一行 I/O 都不做。 */
    private fun attach(o: Opened) {
        store = o.store
        pdf = o.pdf
        openingLabel.visibility = View.GONE
        title = o.doc.title

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
        canvas.store = o.store
        canvas.documentId = docId
        layers = o.layers
        canvas.activeLayerId = layers.firstOrNull()?.id ?: LibInkLayer.DEFAULT_ID
        canvas.setMode(MODE_PAGE)
        canvas.applyStrokes(o.strokes, hiddenLayerIds())
        Log.i(
            TAG,
            "打开《${doc.title}》${o.pdf.pageCount} 页 笔迹 ${o.strokes.size} 条，" +
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
        android.app.AlertDialog.Builder(this)
            .setTitle("打不开这个文档")
            .setMessage(reason)
            .setCancelable(false)
            .setPositiveButton("返回", null)
            .setOnDismissListener { finish() }
            .show()
    }

    private fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    /** 每帧都会调（滚动/缩放都触发），一律走 `setTextIfChanged`——见 shared/Widgets.kt */
    private fun refreshHud() {
        pageLabel.setTextIfChanged("${canvas.hudPage()}　${canvas.hudZoom()}")
        modeBtn.setTextIfChanged(canvas.modeLabel())
        penBtn.setTextIfChanged(if (canvas.mode == MODE_NOTE) canvas.penLabel() else "笔")
        rulerBtn.setTextIfChanged(if (canvas.rulerOn) "尺子✓" else "尺子")
        // 胶囊文案与模式2 逐字一致（PadActivity.refresh）：两模式看起来必须是同一个 App
        val pen = canvas.curPenOrNull()
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
        layerStat.setTextIfChanged(activeLayer()?.let { "图层：${it.name}" } ?: "图层")
    }

    // ---------- 图层（面板与模式2 共用一份，见 shared/PadPanels.kt） ----------

    private fun activeLayer(): LibInkLayer? = layers.firstOrNull { it.id == canvas.activeLayerId }

    private fun hiddenLayerIds(): Set<String> =
        layers.filter { !it.visible }.map { it.id }.toSet()

    /**
     * 模式2 里这三件事只是「请求」，由 Mac 判定后广播权威状态回来；模式1 的真源就在进程内，
     * 所以**直接写 `ink_layer` 表再整表重读**——不本地推算列表，避免内存镜像与库里悄悄分叉。
     *
     * 面板按**下标**回调（线格式就是按下标发的），这里用下标换 id。
     */
    private fun showLayers() {
        val s = store ?: return
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
                    runCatching { s.upsertInkLayer(l.copy(visible = visible)) }
                        .onFailure { Log.e(TAG, "写图层可见性失败", it) }
                    layers = s.inkLayers(docId)
                    // 隐藏的图层数据一条不动，只是不画（同 Mac 的 broadcastStrokes 过滤口径）
                    canvas.reloadStrokes()
                    refreshHud()
                }
            },
            onAdd = {
                val l = runCatching { s.addInkLayer(docId) }
                    .onFailure { Log.e(TAG, "新建图层失败", it) }
                    .getOrNull() ?: return@showLayerPanel
                layers = s.inkLayers(docId)
                canvas.activeLayerId = l.id   // 新建即切过去（同 Mac 的 layerAdd）
                Log.i(TAG, "新建图层 ${l.name}，已设为作画图层")
                refreshHud()
            },
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

    private fun saveProgress() {
        if (!dirty) return
        val s = store ?: return
        dirty = false
        try {
            s.updateProgress(
                docId, curPage, curFrac.toDouble(),
                canvas.zoomLevel().toDouble(), canvas.hFrac().toDouble(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "写进度失败", e)
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()   // 切后台立刻落盘：进程随时可能被杀
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        // 收尾也别在主线程：`store.close()` 内含 wal_checkpoint(TRUNCATE)，慢卷上退出会卡住整个
        // 返回动画（§9.5）。此后主线程不再碰这两个对象，所以字段先摘干净再交给后台。
        val s = store
        val p = pdf
        store = null
        pdf = null
        canvas.store = null
        if (s != null || p != null) {
            Bg.submit("关闭文档 ${docId.take(8)}") {
                p?.close()
                s?.close()
            }
        }
        super.onDestroy()
    }
}
