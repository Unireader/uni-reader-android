package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xvan.unireader.Launcher
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibDocument
import com.xvan.unireader.local.store.LibInkLayer
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.ScratchPad
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.local.store.toUiLayers
import com.xvan.unireader.shared.Bg
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.ScratchCanvas
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
import com.xvan.unireader.shared.showAlert
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 模式1 的阅读界面：本机 Pdfium 出图 + `LocalCanvasView`（与模式2 同一套几何/手势）。
 *
 * **它是「一个工作区」的界面，不是「一篇文档」的界面**（2026-08-05 起，见
 * `ANDROID-STANDALONE-PLAN.md §13`）：一条标签页栏可以同时开着这个工作区里的多篇 PDF，
 * 栏最左的工作区芯片可以整组换到另一个工作区。**标签页组属于工作区**——切走时整组收起、
 * 切回来原样恢复（存在 [TabSet]，只存开着哪几篇，进度照旧在库里）。
 *
 * 三条由此定下来的架构约束：
 * - **一个工作区一个库**：`library.sqlite` 只开一份，全部标签页共用同一条 [StoreQueue]
 *   （§9.2 单写者；同时开两个库还会各挂一份 WAL）。切工作区 = 关掉这一份再开另一份。
 * - **懒装载**：标签页只有被激活过才装载（Pdfium 开文档 + 读**全部**页尺寸表 + 读笔迹，
 *   几百页的文档是秒级）。没装载的标签页在内存里只有 id 和标题。
 * - **LRU 保活 [MAX_LIVE] 篇**：装载过的留着（切回来是瞬间的），超出就把最久没看的那篇卸掉；
 *   退到背景的那几篇丢掉页图位图（[PageCanvasView.trimImages]）并把 Pdfium 缓存缩额
 *   （[PdfSource.setForeground]），否则三份各按堆的 1/3 分配必然 OOM。
 *
 * 进度**双向续接**（`§11.3`）：打开时按 `document` 的四列复原（先缩放再滚——顺序反了 offY 还是
 * 旧的，滚到的位置会偏），切走标签页/退出/切后台时写回。**卸载再回来也照样复原**——进度在卸载前
 * 已经落库，重新装载时就是一次普通的打开。
 *
 * 打开的那一串 I/O 全在后台（§9.5），且**分两跳**：先在库队列上读文档行/路径/笔迹，回到主线程再
 * 甩给 [Bg] 开 Pdfium。不合成一跳是因为开 PDF 是这里最慢的一步，压在库队列上会连带堵住别的
 * 标签页的落笔与进度写入。
 */
class ReaderActivity : Activity() {

    companion object {
        const val TAG = "UniReader/Reader"
        const val EXTRA_WORKSPACE = "workspace"
        const val EXTRA_DOC_ID = "docId"

        /** 进度落库节流：滚动上报是 16ms 一次，直接写库等于一秒几十次事务 */
        const val SAVE_INTERVAL_MS = 2000L

        /** 标签页数量上限。再多就成了"栏上找不到那一篇"，且每篇都占一份库读写的份额 */
        const val MAX_TABS = 8

        /** 同时保活（装载着）的标签页上限，见类注释里的内存账 */
        const val MAX_LIVE = 3

        /**
         * @param documentId 要打开/切到的那一篇。工作区与当前开着的不同 = 顺带切工作区。
         */
        fun start(ctx: Context, workspaceDir: File, documentId: String) {
            ctx.startActivity(
                Intent(ctx, ReaderActivity::class.java)
                    .putExtra(EXTRA_WORKSPACE, workspaceDir.absolutePath)
                    .putExtra(EXTRA_DOC_ID, documentId)
                    // 已经开着就把这个实例调到前台走 [onNewIntent]（加一个标签页），别叠第二个实例——
                    // 标签页的画布、Pdfium 句柄、库队列全在实例里，叠一个等于同一个库开两份。
                    // 用 REORDER_TO_FRONT 而不是 CLEAR_TOP：后者会把书库从栈里清掉，
                    // 之后按返回键就直接回启动页了，而用户是从书库点进来的，该退回书库。
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /**
     * 一个标签页。**[canvas]/[pdf] 可能为 null**：没装载过、或被 LRU 卸掉了（见类注释）。
     * 那时它在内存里只剩 [docId] 与 [title]，重新激活时按库里的进度整份装回来。
     */
    private class Tab(val docId: String, var title: String) {
        var canvas: LocalCanvasView? = null
        var pdf: PdfSource? = null

        /** 这一篇的图层表（`ink_layer` 的内存镜像）。改完一律重读，不本地推算 */
        var layers = listOf<LibInkLayer>()

        /** 这一篇的草稿纸列表（`scratch_pad` 的内存镜像，created_at ASC）。改完一律重读 */
        var pads = listOf<ScratchPad>()

        // 待落库的进度（只有主线程写）
        var curPage = 0
        var curFrac = 0f
        var dirty = false

        /** 装载在飞：别因为连点两下同一个标签页装两遍 */
        var loading = false

        /** 最后一次被激活的时刻（LRU 卸载按它挑） */
        var usedAt = 0L
    }

    /** 库队列上一趟读齐的开档数据。[reason] 非空 = 打不开，其余字段无意义 */
    private class DocData(
        val doc: LibDocument?,
        val file: File?,
        val layers: List<LibInkLayer>,
        val strokes: List<Stroke>,
        val notes: List<TextNote>,
        val fills: List<TextFill>,
        val pads: List<ScratchPad>,
        val reason: String?,
    )

    /** 后台一趟开好的工作区库（顺带把书库列表读出来：标签页栏要标题，"+"要文档列表） */
    private class WsOpened(val store: LibraryStore, val name: String, val docs: List<LibDocument>)

    private lateinit var canvasHost: FrameLayout
    private lateinit var bar: TopBar
    private lateinit var tabsBar: DocTabsBar
    private lateinit var openingLabel: TextView
    private lateinit var capsules: LinearLayout
    private lateinit var penStat: TextView
    private lateinit var layerStat: TextView

    /** 草稿纸（模式1 全链路）：覆盖层画布 + 浮条 + 列表/纸样面板 + 库读写 */
    private lateinit var scratch: ScratchController

    /** 当前工作区。切工作区时整个换掉 */
    private var workspace: File? = null

    /** 这个工作区的库队列，**全部标签页共用**（§9.5 的独占线程） */
    private var queue: StoreQueue? = null

    /** 库里全部文档的 id→标题（"+"的列表与新标签页的初始标题用它，省一次读库） */
    private var libTitles = mapOf<String, String>()

    private val tabs = ArrayList<Tab>()
    private var active = -1

    /** 「正在打开…」的模态（切工作区时压着，防连点开出两个） */
    private var busyDlg: AlertDialog? = null

    /** 顶栏高度（含状态栏让位）。画布要让开的是它 + 标签页栏 */
    private var barH = 0

    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 当前标签页的画布。没有标签页/还没装载完时是 null，**所有用它的地方都得受得住 null** */
    private fun cur(): LocalCanvasView? = tabs.getOrNull(active)?.canvas

    private fun curTab(): Tab? = tabs.getOrNull(active)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 重建（转屏/进程被杀后恢复）优先用存下来的那一对，**不能用 intent 里的**：
        // intent 记的是「当初从书库点进来的那一篇」和那时的工作区，中途切过标签页/切过工作区之后
        // 它就是旧的了——照它走会在转一下屏幕之后跳回第一篇（甚至跳回上一个工作区）。
        val wsPath = savedInstanceState?.getString(EXTRA_WORKSPACE)
            ?: intent.getStringExtra(EXTRA_WORKSPACE)
        if (wsPath == null) {
            Log.e(TAG, "缺 workspace 参数")
            finish()
            return
        }
        val docId = (savedInstanceState?.getString(EXTRA_DOC_ID) ?: intent.getStringExtra(EXTRA_DOC_ID))
            ?.takeIf { it.isNotEmpty() }
        buildUi()
        openWorkspace(File(wsPath), docId)
    }

    /** 转屏/被回收时记住「当前是哪个工作区的哪一篇」，见 [onCreate] */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        workspace?.let { outState.putString(EXTRA_WORKSPACE, it.absolutePath) }
        curTab()?.let { outState.putString(EXTRA_DOC_ID, it.docId) }
    }

    /**
     * 从书库（或别处）又点了一篇。同一个工作区 = 加/切一个标签页；换了工作区 = 整组换掉。
     * 走到这里说明实例还活着（见 [start] 的 flag），**不能重新走 onCreate 那一套**。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val path = intent.getStringExtra(EXTRA_WORKSPACE) ?: return
        val docId = intent.getStringExtra(EXTRA_DOC_ID)?.takeIf { it.isNotEmpty() }
        if (workspace?.absolutePath == path) {
            if (docId != null) openDoc(docId)
        } else {
            switchWorkspace(File(path), docId)
        }
    }

    // ---------- 界面骨架 ----------

    /**
     * 上一次已经处理掉的按键序列。`KeyEvent.getDownTime()` 对一次按下的 DOWN 与它配对的 UP 是**同一个值**，
     * 拿它去重比时间窗精确：既不会把同一次按键触发两遍，也不会误吞快速连按的第二下。
     */
    private var lastHandledKeyDown = 0L

    /**
     * 笔身侧键：PageUp 切模式 / PageDown 切笔 / Esc 清框选——与模式2（`PadActivity.dispatchKeyEvent`）
     * 同一套约定（`REQUIREMENTS.md §1.5`），判定逻辑也从那边照搬：
     *
     * **DOWN 与 UP 谁先到就认谁**，而不是只认 ACTION_DOWN——小米智能触控笔实测（真机日志，
     * 见 `ANDROID-STANDALONE-PLAN.md §9.10`）：书写期间按侧键，有些次数只有 `action=1(UP)` 到达应用，
     * `DOWN` 被上游整个吞掉。只认 DOWN 的话那一次就静默失效，表现正是「按了没反应，要多按好几次」。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val ours = event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE
        if (!ours) return super.dispatchKeyEvent(event)

        // 长按重复不算新的一次按键；同一次按键的另一半（DOWN 已处理过就轮到 UP）直接吃掉
        val fresh = event.repeatCount == 0 && event.downTime != lastHandledKeyDown
        Log.i(
            PageCanvasView.TAG,
            "侧键 keyCode=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) " +
                "action=${event.action}(0=DOWN 1=UP) repeat=${event.repeatCount} " +
                "书写中=${cur()?.isPenDown() == true} 生效=$fresh",
        )
        if (!fresh) return true
        lastHandledKeyDown = event.downTime
        val canvas = cur() ?: return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> canvas.cycleMode()
            KeyEvent.KEYCODE_PAGE_DOWN -> canvas.cyclePen()
            KeyEvent.KEYCODE_ESCAPE -> canvas.clearLasso()
        }
        refreshHud()
        return true
    }

    private fun buildUi() {
        canvasHost = FrameLayout(this)
        // 顶栏与模式2 是同一份（shared/TopBar）：这里只声明有哪几个键、按下去干什么，
        // 形状/间距/开关态的表达/溢出菜单的行为都由它统一。
        // **每个键都要受得住"当前没有画布"**（标签页还在装载、或刚关掉最后一篇）。
        bar = TopBar(this).apply {
            icon("prev", R.drawable.ic_chevron_left, "上一页", spillFirst = true) { cur()?.turn(prev = true) }
            icon("next", R.drawable.ic_chevron_right, "下一页", spillFirst = true) { cur()?.turn(prev = false) }
            gap()
            icon("mode", TopBar.modeIcon(MODE_PAGE), "切换模式") { cur()?.cycleMode(); refreshHud() }
            // 「切换笔」只在笔模式下出现且染当前笔色（refreshHud() 维护），其余模式占位纯属误导
            icon("pen", R.drawable.ic_nib, "切换笔") { cur()?.cyclePen(); refreshHud() }
            // 尺子（45° 吸附，首版范围 §3）：走基类的 toggleRuler，吸附算法与两端同源（PadConst.rulerSnap）
            icon("ruler", R.drawable.ic_ruler, "尺子") { cur()?.toggleRuler(); refreshHud() }
            // 文字笔记从环形盘进（RK_TEXT），顶栏不再放开关：它只翻一个 noteMode 标志，
            // 点下去界面毫无变化，用户无法预期笔落下会变成「开编辑器」
            // 草稿纸：盖在 PDF 之上的无限白板（列表 + 「在当前位置新建」，见 ScratchController）
            icon("scratch", R.drawable.ic_scratch, "草稿纸") { scratch.showList() }
            // 锁缩放：本来在 ⋯ 里，2026-08-12 用户要求提上来常驻——写字时误缩放是**当场**要止住的事，
            // 翻两级菜单已经晚了。窄屏排不下时 TopBar 会自己把它收回 ⋯（desc 就是那一行的标题）。
            icon("lock", R.drawable.ic_lock, "锁定缩放") { cur()?.toggleZoomLock(); saveTools() }
            pageLabel.setOnClickListener { showGotoPage() }
            // 低频项进 ⋯：勾选态每次弹出现算，所以这里存的是生成器（见 TopBar.overflowItems）
            overflowItems = {
                val c = cur()
                if (c == null) {
                    listOf(TopBar.MenuItem("切换工作区…") { showWorkspaceSwitcher() })
                } else {
                    listOf(
                        TopBar.MenuItem("夜间模式", c.night) { c.toggleNight(); saveTools() },
                        TopBar.MenuItem("显示页面图", c.showPage) { c.toggleShowPage(); refreshHud() },
                        // 防误触：开了之后单指划动不再平移，滚动/缩放一律双指（基类 twoFingerScroll）
                        TopBar.MenuItem("双指滚动（防误触）", c.twoFingerScroll) {
                            c.toggleTwoFingerScroll(); saveTools()
                        },
                        TopBar.MenuItem("图层…") { showLayers() },
                        TopBar.MenuItem("跳到第…页") { showGotoPage() },
                        TopBar.MenuItem("打开另一篇…") { showDocPicker() },
                        TopBar.MenuItem("切换工作区…") { showWorkspaceSwitcher() },
                    )
                }
            }
        }
        tabsBar = DocTabsBar(this).apply {
            onSwitchWorkspace = { showWorkspaceSwitcher() }
            onSelect = { i -> if (i != active) activate(i) }
            onClose = { i -> closeTab(i) }
            onAdd = { showDocPicker() }
        }
        // 左下状态胶囊：与模式2 同一份样式与文案格式（shared/Widgets.kt + PadPanels）。
        // 面板改完即时生效（基类自己管），这里只负责把结果存下来——模式2 那两个回调是上行给 Mac 的，
        // 模式1 没有 Mac，改完不存的话退出即丢（见 ToolPrefs）。
        penStat = capsule(this).apply {
            setOnClickListener {
                val c = cur() ?: return@setOnClickListener
                PadPanels.showPenPanel(
                    this@ReaderActivity, c,
                    onPenset = { ToolPrefs.save(this@ReaderActivity, c) },
                    onEraser = { ToolPrefs.save(this@ReaderActivity, c) },
                )
            }
        }
        layerStat = capsule(this).apply { setOnClickListener { showLayers() } }
        openingLabel = Ui.body(this, "正在打开…").apply { textSize = 15f }
        // 草稿纸：覆盖层画布 + 浮条 + 回调（数据读写全在 ScratchController，这里只接线）
        scratch = ScratchController(this).apply {
            anchorProvider = { cur()?.viewportCenterAnchor() }
            onPinsChanged = { pins -> cur()?.setScratchPins(pins) }
            onOpenChanged = { refreshHud() }
            // 工具快照现取当前画布：纸开着时改笔/改橡皮/切尺子即时生效（尺子走 45° 吸附，同页内）
            toolsProvider = {
                cur()?.let { c ->
                    ScratchCanvas.Tools(
                        inkTool = c.mode == MODE_NOTE && !c.noteMode,
                        eraseTool = c.mode == MODE_ERASE,
                        pen = c.curPenOrNull() ?: PageCanvasView.FALLBACK_PENS[0],
                        eraserSize = c.eraserSize,
                        eraserMode = c.eraserMode,
                        eraserRing = c.eraserRing,
                        rulerOn = c.rulerOn,
                    )
                }
            }
        }
        // 两颗胶囊各自 wrap：不给 penStat 显式 LayoutParams 的话它默认 MATCH_PARENT，
        // 在 wrap 的竖向容器里就被夹成「最宽那颗」的宽度——「翻页 · 拖动平移」会被截成「拖动平」。
        capsules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layerStat, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(penStat, LinearLayout.LayoutParams(-2, -2))
        }
        // 顶栏 + 标签页栏是一整块"上边的壳"：画布在它下面铺满，靠 setBarHeight 让开这块高度
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar.view, LinearLayout.LayoutParams(-1, -2))
            addView(tabsBar.view, LinearLayout.LayoutParams(-1, -2))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.col(this@ReaderActivity, R.color.surface_dim))
            addView(canvasHost, FrameLayout.LayoutParams(-1, -1))
            // 草稿纸覆盖层：在 chrome **之下**（顶栏/标签页栏自带底色，纸不铺进那条带子——
            // handoff §7.1 的白压白坑），在标签页画布之上（纸开着时吃掉全部指针事件，
            // PDF 上一笔都落不下——这是这个功能的定义）。topMargin 由 applyChromeHeight 让开 chrome。
            addView(scratch.canvas, FrameLayout.LayoutParams(-1, -1))
            addView(chrome, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(openingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(
                capsules,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(10); bottomMargin = dp(10)
                },
            )
            // 纸上的悬浮工具条：贴 chrome 下方居中（topMargin 同由 applyChromeHeight 给）
            addView(
                scratch.barView,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL),
            )
        }
        setContentView(root)
        barH = bar.height()
        applyChromeHeight()
        refreshHud()
        // 进度落库的心跳**只起一条**，由它自己认当前是哪个标签页（每装载一篇就起一条的话，
        // 开到第三篇时一秒钟要写三次库）
        handler.postDelayed(saver, SAVE_INTERVAL_MS)
        // 顶栏避开状态栏：不处理的话按钮压在时钟上，点击会被系统栏吃掉（见 shared/Insets.kt）。
        // 胶囊同理要避开底部导航栏——模式2 不用管这个（它整屏沉浸），模式1 有系统栏。
        root.onSystemBarInsets { top, bottom ->
            barH = bar.applyTopInset(top)
            applyChromeHeight()
            val clp = capsules.layoutParams as FrameLayout.LayoutParams
            if (clp.bottomMargin != dp(10) + bottom) {
                clp.bottomMargin = dp(10) + bottom
                capsules.layoutParams = clp
            }
        }
    }

    /** 画布要让开的上边高度 = 顶栏（含状态栏让位）+ 标签页栏 */
    private fun chromeHeight(): Int = barH + tabsBar.height()

    private fun applyChromeHeight() {
        val h = chromeHeight().toFloat()
        for (t in tabs) t.canvas?.setBarHeight(h)
        // 覆盖层与浮条让开顶栏+标签页栏（纸不铺进 chrome 那条带子，§7.1）
        (scratch.canvas.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = h.toInt()
            scratch.canvas.layoutParams = it
        }
        (scratch.barView.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = h.toInt() + dp(10)
            scratch.barView.layoutParams = it
        }
    }

    // ---------- 工作区 ----------

    /**
     * 开一个工作区：开库 + 读书库列表（一趟后台做完），然后恢复它自己的标签页组。
     *
     * @param initialDoc 非空 = 恢复完标签页组后再确保这一篇开着并激活（从书库点进来的那一篇）。
     */
    private fun openWorkspace(dir: File, initialDoc: String?) {
        workspace = dir
        tabsBar.setWorkspace(dir.name)
        title = dir.name
        showOpening("正在打开 ${dir.name}…")
        runInBackground(
            what = "开工作区库 ${dir.name}",
            work = {
                val s = LibraryStore.open(dir)
                try {
                    WsOpened(s, s.workspaceName(), s.allDocuments())
                } catch (e: Throwable) {
                    // 只抛不关的话，这个库连接（带 WAL）就悬在后台了（同 §9.5 的 discard 理由）
                    runCatching { s.close() }
                    throw e
                }
            },
            ok = { attachWorkspace(dir, it, initialDoc) },
            fail = { failWorkspace(dir, it.message ?: it.javaClass.simpleName) },
            discard = { o -> Bg.submit("丢弃工作区库 ${dir.name}") { o.store.close() } },
        )
    }

    private fun attachWorkspace(dir: File, o: WsOpened, initialDoc: String?) {
        val q = StoreQueue(o.store)
        queue = q
        libTitles = o.docs.associate { it.id to it.title }
        tabsBar.setWorkspace(o.name.ifEmpty { dir.name })

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

        // 恢复上次的标签页组。**库里没有的 id 直接丢掉**（Mac 上删过文档），
        // 不然栏上会挂一个点开就报错的标签页。
        val saved = TabSet.load(this, dir.absolutePath)
        tabs.clear()
        active = -1
        for (id in saved.docIds) {
            if (tabs.size >= MAX_TABS) break
            val t = libTitles[id] ?: continue
            if (tabs.none { it.docId == id }) tabs.add(Tab(id, t))
        }
        var want = saved.active.coerceIn(0, max(0, tabs.size - 1))
        if (initialDoc != null) {
            val i = tabs.indexOfFirst { it.docId == initialDoc }
            when {
                i >= 0 -> want = i
                libTitles.containsKey(initialDoc) && tabs.size < MAX_TABS -> {
                    tabs.add(Tab(initialDoc, libTitles[initialDoc] ?: "文档"))
                    want = tabs.size - 1
                }
                else -> Log.w(TAG, "要打开的文档不在库里/标签页已满：$initialDoc")
            }
        }
        Log.i(TAG, "工作区《${o.name.ifEmpty { dir.name }}》${o.docs.size} 篇文档，恢复标签页 ${tabs.size} 个")
        if (tabs.isEmpty()) {
            // 空标签页组（第一次开这个工作区，或存的几篇都被删了）：直接把文档列表摆出来，
            // 别停在一块空白上让人猜下一步点哪儿
            showOpening("这个工作区还没有打开的文档")
            tabsBar.setTabs(emptyList(), -1)
            showDocPicker()
            return
        }
        activate(want)
    }

    private fun failWorkspace(dir: File, reason: String) {
        openingLabel.text = "打不开这个工作区"
        Sheet(this)
            .title("打不开这个工作区")
            .subtitle("${dir.name}：$reason")
            .cancelable(false)
            .action("返回", primary = true)
            .show()
            .setOnDismissListener { if (tabs.isEmpty()) finish() }
    }

    /**
     * 切到另一个工作区。**先校验、后拆**：校验不过就什么都不动（原地不动比"关掉了又开不了新的、
     * 落在一片空白上"强）。校验走后台——`Workspace.check` 在慢卷冷缓存下实测 2.1 秒（§9.5）。
     */
    private fun switchWorkspace(dir: File, initialDoc: String?) {
        if (workspace?.absolutePath == dir.absolutePath) {
            if (initialDoc != null) openDoc(initialDoc)
            return
        }
        if (busyDlg != null) return
        Log.i(TAG, "切换工作区 → ${dir.absolutePath}")
        busyDlg = Sheet(this).busy("正在打开 ${dir.name}…")
        runInBackground(
            what = "校验工作区 ${dir.name}",
            work = { Workspace.check(dir) },
            ok = { c ->
                dismissBusy()
                when (c) {
                    is Workspace.Check.Bad -> {
                        Workspace.forget(this, dir.absolutePath)   // 失效的最近项别再留着
                        showAlert("打不开这个工作区", c.reason)
                    }
                    is Workspace.Check.OK -> {
                        Workspace.remember(this, dir.absolutePath)
                        if (c.readOnly) Log.w(TAG, "库文件只读：${c.db.absolutePath}")
                        closeWorkspace()
                        openWorkspace(c.dir, initialDoc)
                    }
                }
            },
            fail = {
                dismissBusy()
                showAlert("打不开这个工作区", "校验时出错：${it.message}")
            },
        )
    }

    /**
     * 收掉当前工作区：进度落库 → 记下标签页组 → 卸载全部标签页 → 关库。
     *
     * 顺序不能乱：[saveTabSet] 必须在 [tabs] 清空之前（空列表等于把这个工作区的记录删掉），
     * 关库必须最后（它排在队尾，前面攒的写才落得下去，见 [StoreQueue.close]）。
     */
    private fun closeWorkspace() {
        scratch.close()   // 开着的草稿纸属于这个工作区的文档，随它一起收
        scratch.bind(null, "", emptyList())
        for (t in tabs) saveProgress(t)
        saveTabSet()
        for (t in tabs) unload(t)
        tabs.clear()
        active = -1
        libTitles = emptyMap()
        tabsBar.setTabs(emptyList(), -1)
        queue?.close()
        queue = null
        workspace = null
        refreshHud()
    }

    /**
     * 「快捷切换工作区」的入口（标签页栏最左的芯片，2026-08-05 用户定的形态）。
     * 列的是**已经知道的那些**：最近打开 + 上次扫描到的。找新的走「打开其它 .unrd…」——
     * 目录浏览器与扫描都在启动页，不在这儿再实现一遍。
     */
    private fun showWorkspaceSwitcher() {
        val curPath = workspace?.absolutePath
        val recents = Workspace.recents(this)
        val scanned = Workspace.scanned(this).filter { it !in recents }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(this).title("切换工作区")
        for (path in recents + scanned) {
            val dir = File(path)
            val isCur = path == curPath
            list.addView(
                PadPanels.iconRow(
                    this,
                    R.drawable.ic_doc,
                    dir.name.ifEmpty { path },
                    if (isCur) Ui.accent(this) else Ui.onVariant(this),
                    // 当前这个打一个勾（纯标记，不是按钮——点它和点整行是同一件事）
                    trailing = if (!isCur) {
                        null
                    } else {
                        ImageView(this).apply {
                            setImageResource(R.drawable.ic_check)
                            imageTintList = ColorStateList.valueOf(Ui.accent(this@ReaderActivity))
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        }
                    },
                ) {
                    dlg?.dismiss()
                    if (!isCur) switchWorkspace(dir, null)
                },
            )
        }
        if (recents.isEmpty() && scanned.isEmpty()) {
            sheet.subtitle("还没有别的工作区。用下面的「打开其它 .unrd…」去找。")
        }
        sheet.content(list)
        sheet.action("取消")
        sheet.action("打开其它 .unrd…", primary = true) {
            // 启动页有目录浏览器 + 扫描；从那儿开的工作区会把这个实例调回前台（见 [start]）。
            // REORDER_TO_FRONT：栈底本来就有一个启动页（App 是从它进来的），复用它而不是再叠一个——
            // 否则每切一次工作区，栈里就多一对「启动页 + 书库」，返回键要按好几下才退得出去。
            startActivity(
                Intent(this, Launcher::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        dlg = sheet.show()
    }

    // ---------- 标签页 ----------

    private fun saveTabSet() {
        val ws = workspace ?: return
        TabSet.save(this, ws.absolutePath, tabs.map { it.docId }, max(0, active))
    }

    private fun refreshTabsBar() {
        tabsBar.setTabs(tabs.map { it.title }, active)
    }

    /** 打开（或切到）本工作区的某一篇 */
    private fun openDoc(docId: String) {
        val i = tabs.indexOfFirst { it.docId == docId }
        if (i >= 0) {
            if (i != active) activate(i) else Log.i(TAG, "《${tabs[i].title}》已经是当前标签页")
            return
        }
        if (tabs.size >= MAX_TABS) {
            showAlert("标签页太多了", "最多同时开 $MAX_TABS 篇，先关掉一个再开。")
            return
        }
        tabs.add(Tab(docId, libTitles[docId] ?: "文档"))
        activate(tabs.size - 1)
    }

    /**
     * 激活第 [i] 个标签页。已装载的就是"换个 View 显示"（滚动/缩放/笔迹都还在它自己的画布上），
     * 没装载的走 [loadTab]。
     */
    /** 这一篇的页纵横比（页高/页宽，Pdfium 报的显示尺寸）——草稿纸页面底图按它定页矩形的高 */
    private fun pageAspectOf(t: Tab): (Int) -> Float = { i ->
        val wh = t.pdf?.pageSizes?.getOrNull(i)
        if (wh != null && wh[0] > 0f) wh[1] / wh[0] else 0f
    }

    private fun activate(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        val prev = curTab()
        if (prev != null && prev !== t) deactivate(prev)
        active = i
        t.usedAt = SystemClock.uptimeMillis()
        // 草稿纸立刻绑到这一篇（哪怕还没装载完：装载中 docId 就该是它的，否则入口会列出别篇的纸；
        // 装载完 attachTab 会带着读好的纸列表再绑一次）
        scratch.bind(queue, t.docId, t.pads, t.pdf, pageAspectOf(t))
        refreshTabsBar()
        title = t.title
        saveTabSet()
        val c = t.canvas
        if (c == null) {
            loadTab(t)
            return
        }
        c.visibility = View.VISIBLE
        t.pdf?.setForeground(true)
        c.refreshImages()   // 背景期间把位图丢了，这里补回来（见 PageCanvasView.refreshImages）
        hideOpening()
        refreshHud()
        trimLive()
    }

    /** 退到背景：进度先落库（随时可能被 LRU 卸掉），再把这一屏的位图与缓存额度让出来 */
    private fun deactivate(t: Tab) {
        saveProgress(t)
        scratch.close()   // 草稿纸属于「当前这篇」：切走即关（视口不落库，重开回中，三端契约）
        t.canvas?.let { c ->
            c.clearTransient()   // 环形盘/压感环留在背景标签页上没有意义
            c.visibility = View.GONE
            c.trimImages()
        }
        t.pdf?.setForeground(false)
    }

    /**
     * 第一跳：在库队列上把这一篇要的东西读齐（文档行、PDF 路径、图层、笔迹、注解）。
     * 拿到路径后回主线程再开 Pdfium（第二跳，见 [onDocData]）。
     */
    private fun loadTab(t: Tab) {
        val q = queue ?: return
        val ws = workspace ?: return
        if (t.loading) return
        t.loading = true
        showOpening("正在打开《${t.title}》…")
        q.submit(
            "读文档 ${t.docId.take(8)}",
            { s ->
                val doc = s.document(t.docId)
                val file = if (doc == null) null else Workspace.firstOpenablePdf(ws, s, t.docId)
                if (doc == null) {
                    DocData(null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "库里没有这个文档：${t.docId}")
                } else if (file == null) {
                    DocData(
                        doc, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
                        "《${doc.title}》没有能打开的 PDF——需要在 Mac 上把文件拷进工作区",
                    )
                } else {
                    // 落库上下文 + 当前作画图层（老文档可能没有图层行，补一条默认层，同 Mac 的行为）
                    s.ensureDefaultLayer(t.docId)
                    s.updateLastOpened(t.docId)
                    DocData(
                        doc, file,
                        s.inkLayers(t.docId), s.strokes(t.docId), s.textNotes(t.docId), s.textFills(t.docId),
                        s.scratchPads(t.docId),   // v7 老库没有这张表 → 空列表，不炸（handoff §2.3①）
                        null,
                    )
                }
            },
            { d -> onDocData(t, d) },
        )
    }

    /** 第二跳：开 Pdfium（读**全部**页尺寸表，几百页的文档不便宜，所以甩给 [Bg] 而不是压库队列） */
    private fun onDocData(t: Tab, d: DocData) {
        if (d.reason != null || d.doc == null || d.file == null) {
            t.loading = false
            failToOpen(t, d.reason ?: "打不开这个文档")
            return
        }
        if (t.title != d.doc.title) {
            t.title = d.doc.title
            refreshTabsBar()
        }
        val file = d.file
        runInBackground(
            what = "开 PDF ${file.name}",
            work = {
                PdfSource(applicationContext, file).also {
                    it.logPageSizes()   // §9.1 的比对凭据：与 tools/dump-page-sizes.swift 的输出逐行 diff
                }
            },
            ok = { src -> attachTab(t, d, src) },
            fail = {
                t.loading = false
                failToOpen(t, it.message ?: it.javaClass.simpleName)
            },
            discard = { src ->
                t.loading = false
                Bg.submit("丢弃 PDF ${t.docId.take(8)}") { src.close() }
            },
        )
    }

    /** 主线程：把两跳读好的东西一次性装成一块画布。这里一行 I/O 都不做。 */
    private fun attachTab(t: Tab, d: DocData, src: PdfSource) {
        t.loading = false
        val q = queue
        // 装载途中用户可能切走了、关掉了这个标签页、甚至换了工作区：句柄在这儿收，别让它挂着
        if (q == null || curTab() !== t || t !in tabs) {
            Log.i(TAG, "《${t.title}》装载完成时已不是当前标签页，丢弃")
            Bg.submit("关闭 PDF ${t.docId.take(8)}") { src.close() }
            return
        }
        val doc = d.doc ?: return
        val c = newCanvas(t)
        t.canvas = c
        t.pdf = src
        t.layers = d.layers
        t.pads = d.pads
        canvasHost.addView(c, FrameLayout.LayoutParams(-1, -1))

        // 工具（笔/橡皮/夜间/模式…）是**设备级**的，不跟文档走：已经有别的标签页开着就照它来，
        // 不然每开一篇都跳回默认笔、夜间模式还会闪一下白。没有别的标签页才从 ToolPrefs 复原。
        val donor = tabs.firstOrNull { it !== t && it.canvas != null }?.canvas
        if (donor != null) {
            copyTools(donor, c)
        } else {
            ToolPrefs.load(this, c)
            c.setMode(MODE_PAGE)   // 新开一篇先是翻页模式，别一上手就往书上写字
        }

        // 进度复原：等**几何首次就绪**之后再做（否则 offY 还是 width=0 时算的，滚过去等于滚到页顶），
        // 且顺序必须是先缩放再滚——反了 offY 是旧内容宽下的值，落点会偏。
        // **这个钩子必须挂在 setPages 之前**：此刻视口早就布局好了（打开是异步的），页表一到
        // 就地回调，挂晚一步就永远等不到第二次机会。
        t.curPage = doc.readPage
        t.curFrac = doc.readFrac.toFloat()
        c.onFirstGeometry = {
            if (doc.readZoom > 0) c.applyZoom(doc.readZoom.toFloat())
            c.applyHFrac(doc.readHFrac.toFloat())
            val ok = c.scrollToPageFrac(doc.readPage, doc.readFrac.toFloat())
            Log.i(TAG, "复原滚动 → 第 ${doc.readPage + 1} 页 ${"%.3f".format(doc.readFrac)} ok=$ok")
            refreshHud()
        }
        c.imageSource = src
        c.setPages(src.pageCount, src.pageSizes.map { it[0] to it[1] }, reset = true)
        c.store = q
        c.documentId = t.docId
        c.activeLayerId = d.layers.firstOrNull()?.id ?: LibInkLayer.DEFAULT_ID
        c.applyStrokes(d.strokes, hiddenLayerIds(t))
        c.applyNotes(d.notes, d.fills)
        scratch.bind(queue, t.docId, t.pads, src, pageAspectOf(t))   // 装载完成：纸列表到位，图钉上页
        hideOpening()
        refreshHud()
        trimLive()
        Log.i(
            TAG,
            "打开《${doc.title}》${src.pageCount} 页 笔迹 ${d.strokes.size} 条 " +
                "注解 ${d.notes.size} 条 铺色 ${d.fills.size} 片，" +
                "复原到 第 ${doc.readPage + 1} 页 ${"%.3f".format(doc.readFrac)} " +
                "zoom=${doc.readZoom} hfrac=${doc.readHFrac}（标签页 ${tabs.indexOf(t) + 1}/${tabs.size}）",
        )
    }

    private fun newCanvas(tab: Tab): LocalCanvasView = LocalCanvasView(this).apply {
        setBarHeight(chromeHeight().toFloat())
        onProgress = { page, frac ->
            tab.curPage = page
            tab.curFrac = frac
            tab.dirty = true
        }
        // 背景标签页也可能回调（落库后回推）：不是当前这篇就别去刷顶栏，那会显示别人的页码
        onHud = { if (curTab() === tab) refreshHud() }
        // 文字笔记：编辑器面板与模式2 是同一份（shared/PadPanels），只是保存去处不同——
        // 那边编帧发给 Mac，这边直接落 note 表（kind=0）
        onNoteEditor = { id, page, nx, ny, text, isNew -> editNote(tab, id, page, nx, ny, text, isNew) }
        // 草稿纸图钉：手指单击打开对应那张纸（笔点不算——笔是用来写字的，见 PageCanvasView.onFingerTap）
        onPinTap = { padId -> scratch.openById(padId) }
        // 图钉拖动松手：只挪锚点（页不变），落库由 ScratchController 走 StoreQueue
        onPinMove = { padId, nx, ny -> scratch.movePadAnchor(padId, nx, ny) }
        // 环形盘新扇区：盘心即锚点（等价「在当前位置新建」，只是位置用长按那一处）
        onRadialScratchAdd = { page, nx, ny -> scratch.createAt(page, nx, ny) }
        onRadialTextNote = { page, nx, ny ->
            editNote(tab, java.util.UUID.randomUUID().toString(), page, nx, ny, "", true)
        }
    }

    /**
     * 把工具状态从一块画布抄到另一块（新开标签页时）。
     *
     * 基类的开关只给了 `toggleX()`（两模式都是按键切的），所以这里按目标值补差——同
     * [ToolPrefs.load] 里对 night 的做法。**不走"存一遍再读一遍"**：那要多一次 SharedPreferences
     * 往返，而且 ToolPrefs 只管笔/橡皮/夜间/锁缩放/双指滚动，模式与尺子/文字/页图会丢。
     */
    private fun copyTools(from: PageCanvasView, to: PageCanvasView) {
        to.setPens(from.penList(), from.penIndex)
        to.setEraserLocal(from.eraserSize, from.eraserMode, from.eraserRing)
        if (to.night != from.night) to.toggleNight()
        if (to.showPage != from.showPage) to.toggleShowPage()
        if (to.zoomLocked != from.zoomLocked) to.toggleZoomLock()
        if (to.twoFingerScroll != from.twoFingerScroll) to.toggleTwoFingerScroll()
        if (to.rulerOn != from.rulerOn) to.toggleRuler()
        if (to.noteMode != from.noteMode) to.toggleNoteMode()
        to.setMode(from.mode)
    }

    /**
     * 打不开就把原因摆出来，并把这个标签页摘掉——留一个点开就报错的标签页在栏上没有意义。
     * 全关光了（本来就只开着这一篇）才退出界面，回书库。
     */
    private fun failToOpen(t: Tab, reason: String) {
        Log.e(TAG, "打不开《${t.title}》：$reason")
        val i = tabs.indexOf(t)
        if (i >= 0) {
            dropTab(i)
            if (tabs.isEmpty()) {
                openingLabel.text = "打不开这个文档"
                Sheet(this)
                    .title("打不开这个文档")
                    .subtitle(reason)
                    .cancelable(false)
                    .action("返回", primary = true)
                    .show()
                    .setOnDismissListener { finish() }
                return
            }
            val next = if (i == active) i.coerceAtMost(tabs.size - 1) else if (i < active) active - 1 else active
            active = -1
            activate(next)
        }
        showAlert("打不开这个文档", reason)
    }

    /** 从列表里摘掉一个标签页并释放资源。**不管接下来激活谁**，也不 finish——那是调用方的事 */
    private fun dropTab(i: Int): Tab? {
        val t = tabs.getOrNull(i) ?: return null
        saveProgress(t)
        if (i == active) scratch.close()   // 正开着的草稿纸属于这篇，随它一起收
        unload(t)
        tabs.removeAt(i)
        return t
    }

    private fun closeTab(i: Int) {
        val t = tabs.getOrNull(i) ?: return
        Log.i(TAG, "关标签页《${t.title}》（${i + 1}/${tabs.size}）")
        val wasActive = i == active
        dropTab(i)
        if (tabs.isEmpty()) {
            // 关掉最后一个 = 这个工作区不看了：退回书库（标签页组也随之清空，见 saveTabSet）
            saveTabSet()
            finish()
            return
        }
        val next = when {
            wasActive -> i.coerceAtMost(tabs.size - 1)
            i < active -> active - 1
            else -> active
        }
        active = -1   // 强制走一遍 activate：下标变了，栏和标题都要重画
        activate(next)
    }

    /** 卸载（不从 [tabs] 里摘）：画布、Pdfium 句柄都还回去，标签页本身还在栏上 */
    private fun unload(t: Tab) {
        t.canvas?.let { c ->
            c.store = null
            c.imageSource = null
            canvasHost.removeView(c)
        }
        t.canvas = null
        t.layers = emptyList()
        val p = t.pdf
        t.pdf = null
        // 关 Pdfium 会等渲染线程停下来，不该在主线程做（同 §9.5 的口径）
        if (p != null) Bg.submit("关闭 PDF ${t.docId.take(8)}") { p.close() }
    }

    /** 装载着的超过 [MAX_LIVE] 就把最久没看的那篇卸掉（当前这篇永远留着） */
    private fun trimLive() {
        while (true) {
            val live = tabs.filter { it.canvas != null }
            if (live.size <= MAX_LIVE) return
            val victim = live.filter { it !== curTab() }.minByOrNull { it.usedAt } ?: return
            Log.i(TAG, "保活上限 $MAX_LIVE 篇：卸载《${victim.title}》（最久没看）")
            saveProgress(victim)
            unload(victim)
        }
    }

    /**
     * 「+」：在本工作区里再开一篇。列表就是书库那一份（`allDocuments` 的顺序与 Mac 一致），
     * 已经开着的标出来、点它就是切过去——用户记不住哪几篇开着，让他点了才发现"原来已经开了"
     * 比直接切过去更烦。
     */
    private fun showDocPicker() {
        val q = queue ?: return
        q.submit("读书库列表", { s -> s.allDocuments() }, { docs -> renderDocPicker(docs) })
    }

    private fun renderDocPicker(docs: List<LibDocument>) {
        libTitles = docs.associate { it.id to it.title }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(this).title("打开文档")
        if (docs.isEmpty()) {
            sheet.subtitle("这个工作区还没有文档。先在 Mac 上导入 PDF 并「拷进工作区」，再把整个 .unrd 搬过来。")
        }
        for (d in docs) {
            val opened = tabs.any { it.docId == d.id }
            val ratio = if (d.pageCount > 0) ((d.readPage + d.readFrac) / d.pageCount).toFloat() else 0f
            val started = d.readPage > 0 || d.readFrac > 0
            list.addView(
                Ui.row(this) {
                    dlg?.dismiss()
                    openDoc(d.id)
                }.apply {
                    addView(
                        Ui.title(this@ReaderActivity, d.title, 15f).apply {
                            if (opened) setTextColor(Ui.accent(this@ReaderActivity))
                        },
                    )
                    addView(
                        Ui.body(
                            this@ReaderActivity,
                            buildString {
                                append("${d.pageCount} 页")
                                if (started) append("　读到第 ${d.readPage + 1} 页（${(ratio * 100).toInt()}%）")
                                if (opened) append("　已在标签页里")
                            },
                        ).apply { textSize = 12f },
                    )
                },
            )
        }
        sheet.content(list)
        sheet.action("取消")
        dlg = sheet.show()
    }

    // ---------- HUD ----------

    private fun showOpening(msg: String) {
        openingLabel.text = msg
        openingLabel.visibility = View.VISIBLE
    }

    private fun hideOpening() {
        openingLabel.visibility = View.GONE
    }

    /**
     * 每帧都会调（滚动/缩放都触发），文本一律走 `setTextIfChanged`——见 shared/Widgets.kt。
     * 图标那几个是幂等的 setter，同值重设不触发 layout，不必额外挡。
     */
    private fun refreshHud() {
        val canvas = cur()
        bar.setActive("scratch", scratch.isOpen)
        if (canvas == null) {
            // 没有当前画布（还在装载 / 刚关掉最后一篇）：胶囊收起来，别显示上一篇的笔和图层
            bar.setPageLabel("—/—", "100%")
            bar.setVisible("pen", false)
            capsules.visibility = View.GONE
            return
        }
        capsules.visibility = View.VISIBLE
        bar.setPageLabel(canvas.hudPage(), canvas.hudZoom())
        // 模式键的图标随当前模式变，开关键按下去是 accent 底色——两模式同一套表达（shared/TopBar）
        bar.setIcon("mode", TopBar.modeIcon(canvas.mode))
        bar.setActive("mode", canvas.mode != MODE_PAGE)
        bar.setActive("ruler", canvas.rulerOn)
        bar.setActive("lock", canvas.zoomLocked)
        // 「切换笔」只在笔模式下出现，并染当前笔的颜色（与模式2 同一套表达）
        bar.setVisible("pen", canvas.mode == MODE_NOTE)
        bar.setTint("pen", canvas.curPenOrNull()?.let { Ui.penArgb(it) })
        // 防误触是一个模式、不是两个：草稿纸那块画布跟着页内画布走（幂等赋值，不触发重绘）
        scratch.canvas.twoFingerScroll = canvas.twoFingerScroll
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

    private fun activeLayer(): LibInkLayer? {
        val t = curTab() ?: return null
        return t.layers.firstOrNull { it.id == t.canvas?.activeLayerId }
    }

    private fun hiddenLayerIds(t: Tab): Set<String> =
        t.layers.filter { !it.visible }.map { it.id }.toSet()

    /**
     * 模式2 里这三件事只是「请求」，由 Mac 判定后广播权威状态回来；模式1 的真源就在进程内，
     * 所以**直接写 `ink_layer` 表再整表重读**——不本地推算列表，避免内存镜像与库里悄悄分叉。
     * 写与重读在同一个队列作业里做完，回主线程时 `layers` 与笔迹一起换掉；写失败也照读，
     * 界面于是退回库里的状态，不会显示一个没存进去的开关。
     *
     * 面板按**下标**回调（线格式就是按下标发的），这里用下标换 id。
     *
     * 多标签页之后队列是**全工作区共用**的，所以每个作业都得认准自己那一篇（闭包里捏着 `t`
     * 和它的 `docId`）：作业排在队列里等的时候，用户完全可能已经切到别的标签页了。
     */
    private fun showLayers() {
        val q = queue ?: return
        val t = curTab() ?: return
        val c = t.canvas ?: return
        val docId = t.docId
        PadPanels.showLayerPanel(
            this, t.layers.toUiLayers(), t.layers.indexOfFirst { it.id == c.activeLayerId },
            onSelect = { i ->
                t.layers.getOrNull(i)?.let {
                    c.activeLayerId = it.id
                    Log.i(TAG, "作画图层 → ${it.name}")
                    refreshHud()
                }
            },
            onToggleVisible = { i, visible ->
                t.layers.getOrNull(i)?.let { l ->
                    q.submit(
                        "切图层可见性 ${l.name}",
                        { s ->
                            runCatching { s.upsertInkLayer(l.copy(visible = visible)) }
                                .onFailure { Log.e(TAG, "写图层可见性失败", it) }
                            s.inkLayers(docId) to s.strokes(docId)
                        },
                        { (ls, all) ->
                            t.layers = ls
                            // 隐藏的图层数据一条不动，只是不画（同 Mac 的 broadcastStrokes 过滤口径）
                            t.canvas?.applyStrokes(all, hiddenLayerIds(t))
                            if (curTab() === t) refreshHud()
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
                        t.layers = ls
                        if (l != null) {
                            t.canvas?.activeLayerId = l.id   // 新建即切过去（同 Mac 的 layerAdd）
                            Log.i(TAG, "新建图层 ${l.name}，已设为作画图层")
                        }
                        if (curTab() === t) refreshHud()
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
    private fun editNote(t: Tab, id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean) {
        PadPanels.showNoteEditor(
            this, text, isNew,
            onSave = { s ->
                if (s.isEmpty()) { if (!isNew) t.canvas?.deleteNote(id, page, nx, ny) }
                else t.canvas?.upsertNote(id, page, nx, ny, s)
            },
            onDelete = { t.canvas?.deleteNote(id, page, nx, ny) },
        )
    }

    private fun showGotoPage() {
        val c = cur() ?: return
        val n = c.pageCountOrZero()
        if (n <= 0) return
        PadPanels.showGotoPage(this, n) { c.gotoPage(it) }
    }

    // ---------- 进度落库 ----------

    private val saver = object : Runnable {
        override fun run() {
            curTab()?.let { saveProgress(it) }
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    /**
     * 四个数在主线程取好再丢进队列——`zoomLevel()`/`hFrac()` 是视图状态，队列线程不许读。
     * 写失败只留日志：下一次滚动会再置脏、两秒后重来一遍。
     *
     * 没装载的标签页没有进度可写（它压根没被看过，库里那份就是最新的）。
     */
    private fun saveProgress(t: Tab) {
        val c = t.canvas ?: return
        if (!t.dirty) return
        val q = queue ?: return
        t.dirty = false
        val docId = t.docId
        val page = t.curPage
        val frac = t.curFrac.toDouble()
        val zoom = c.zoomLevel().toDouble()
        val hfrac = c.hFrac().toDouble()
        q.submit("写进度 第${page + 1}页 ${t.title.take(8)}") { it.updateProgress(docId, page, frac, zoom, hfrac) }
    }

    private fun dismissBusy() {
        busyDlg?.dismiss()
        busyDlg = null
    }

    /**
     * 顶栏/菜单上那几个**开关**改完就存（不等 [onPause]）：锁缩放、双指滚动、夜间这类是「设一次
     * 用很久」的偏好，被系统杀在后台就白设了。`apply()` 是异步落盘，点一下的开销可以忽略。
     */
    private fun saveTools() {
        val c = cur() ?: return
        ToolPrefs.save(this, c)
        refreshHud()
    }

    override fun onPause() {
        super.onPause()
        // 切后台立刻落盘：进程随时可能被杀。背景标签页的进度在切走时已经存过了，这里只管当前这篇
        curTab()?.let { saveProgress(it) }
        // 顶栏切的夜间、环形盘/切笔键换的笔——都在这一刻存下来（面板改的已经即时存过了）
        cur()?.let { ToolPrefs.save(this, it) }
    }

    /** 返回键先关草稿纸（同 Mac 的 Esc），没开着才走正常返回 */
    override fun onBackPressed() {
        if (scratch.isOpen) {
            scratch.close()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // 校验还没回来就退出：窗口先收掉，否则 WindowLeaked
        dismissBusy()
        // 收尾不在主线程：进度排进队列、关库（含 wal_checkpoint(TRUNCATE)）排在队尾由队列自己做，
        // Pdfium 那半边甩给 Bg（§9.5——慢卷上这几步会卡住整个返回动画）
        closeWorkspace()
        super.onDestroy()
    }
}
