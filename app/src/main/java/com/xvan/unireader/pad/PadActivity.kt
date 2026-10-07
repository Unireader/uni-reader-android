package com.xvan.unireader.pad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import com.xvan.unireader.R
import com.xvan.unireader.shared.BookmarkItem
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.NewBoardSheet
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.NOTE_TAP
import com.xvan.unireader.shared.DocTabsBar
import com.xvan.unireader.shared.LibItem
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PalmSettings
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.ReaderDrawer
import com.xvan.unireader.shared.RefWindow
import com.xvan.unireader.shared.TocItem
import com.xvan.unireader.shared.PageDiskCache
import com.xvan.unireader.shared.PageImageSource
import com.xvan.unireader.shared.PageWidths
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.ScratchCanvas
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.TextNote
import com.xvan.unireader.shared.TopBar
import com.xvan.unireader.shared.Toolbox
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.brushName
import com.xvan.unireader.shared.capsule
import com.xvan.unireader.shared.setPenSwatch
import com.xvan.unireader.shared.setTextIfChanged
import kotlin.math.roundToInt

/**
 * 主界面：全屏 PadView + 顶栏（连接点、延迟、文档、页码/缩放、◀▶、模式、笔、夜间、文字、尺子、
 * 页图、锁缩放、⚙）+ 左下两枚状态胶囊（笔/橡皮、图层）+ 延迟曲线悬浮。
 * 连接设置弹窗（host/token/扫码/连接 + 延迟曲线开关）：未连接自动弹出，⚙ 随时重开。
 * host/token SharedPreferences 持久化（authOK 才存）；扫码内容为 http://ip:8770/?token=XXXX。
 *
 * 侧键：PageUp 切模式 / PageDown 切笔 / Esc 清框选（对齐网页的 keydown 分支）。
 */
class PadActivity : Activity(), MacClient.Callback, PadView.Listener {

    companion object {
        const val REQ_CAMERA = 42
        const val DEBOUNCE_MS = 300L    // penset/eraser 上行防抖（同网页）

        /**
         * 页图磁盘缓存上限：一页压缩字节才 200~600KB，512MB 装得下上千页、好几篇书
         * ——内存那 85MB 只装得下一两张，「切回刚才那篇不重下」全靠这一层（见 `PageDiskCache`）。
         * 放 `cacheDir`，系统在存储紧张时可以自己清掉。
         */
        const val PAGE_DISK_BYTES = 512L * 1024 * 1024

        /** 启动页「连接 Mac」那一页选好的连接参数：带着就直接连，不再弹连接设置 */
        private const val EXTRA_HOST = "host"
        private const val EXTRA_TOKEN = "token"

        /** 进输入板。`host`/`token` 都给 = 直接连这台 Mac；不给 = 进来先弹连接设置（旧行为） */
        fun start(a: Activity, host: String? = null, token: String? = null) {
            a.startActivity(Intent(a, PadActivity::class.java).apply {
                if (!host.isNullOrEmpty() && !token.isNullOrEmpty()) {
                    putExtra(EXTRA_HOST, host)
                    putExtra(EXTRA_TOKEN, token)
                }
            })
        }
    }

    private lateinit var dot: View
    private lateinit var latText: TextView
    private lateinit var penStat: TextView
    private lateinit var layerStat: TextView
    private lateinit var padView: PadView
    private lateinit var graphView: LatencyGraphView

    /** 顶栏与模式1 共用（shared/TopBar）；`topbar` 是它的根 View，收起/展开要用 */
    private lateinit var bar: TopBar
    private lateinit var topbar: LinearLayout
    private lateinit var showBarBtn: ImageButton
    private var barHeightPx = 0

    private var client: MacClient? = null
    private var udp: UdpSender? = null
    private var fetcher: PageFetcher? = null
    private var pageDisk: PageDiskCache? = null
    private val handler = Handler(Looper.getMainLooper())
    private var docV = ""

    // —— 连接参数（authOK 后持久化；重连时不依赖弹窗还在不在） ——
    private var connHost = ""

    /** 工作区书库镜像（Mac `library` 广播）：抽屉与**参考窗**共用同一份。 */
    private var libItems = listOf<LibItem>()

    /**
     * 参考窗（只读小窗，`../../../../../../../REF-WINDOW-PLAN.md`）。模式2 的三件事都走 Mac：
     * 文档列表 = `library` 镜像、元信息 = `GET /docmeta?d=`、页图 = `/page.png?d=`。
     * 都是**本端私有的只读显示**，线格式一个字节没加。
     */
    private val refWin by lazy {
        RefWindow(
            this,
            object : RefWindow.Host {
                override fun refPickDoc(cb: (String) -> Unit) {
                    if (libItems.isEmpty()) return
                    val ll = LinearLayout(this@PadActivity).apply { orientation = LinearLayout.VERTICAL }
                    var dlg: android.app.AlertDialog? = null
                    for (d in libItems) {
                        ll.addView(
                            Ui.textButton(this@PadActivity, d.title) { dlg?.dismiss(); cb(d.id) },
                            LinearLayout.LayoutParams(-1, -2),
                        )
                    }
                    dlg = Sheet(this@PadActivity).title("参考哪本")
                        .content(android.widget.ScrollView(this@PadActivity).apply { addView(ll) })
                        .action("取消").show()
                }

                override fun refDefaultDoc(): String =
                    // Mac 当前开着的那本优先（多半就是正在读的），否则书库第一本
                    libItems.firstOrNull { it.open }?.id ?: libItems.firstOrNull()?.id ?: ""

                override fun refOpen(id: String, cb: (RefWindow.Info?) -> Unit) {
                    val h = connHost
                    if (h.isEmpty()) { cb(null); return }
                    Thread {
                        val info = try {
                            val q = java.net.URLEncoder.encode(id, "UTF-8")
                            val txt = java.net.URL("http://$h:8770/docmeta?d=$q").readText()
                            val o = org.json.JSONObject(txt)
                            val arr = o.getJSONArray("pages")
                            val pages = (0 until arr.length()).map {
                                val p = arr.getJSONArray(it)
                                p.getDouble(0).toFloat() to p.getDouble(1).toFloat()
                            }
                            RefWindow.Info(
                                o.optString("title"), pages,
                                o.optInt("readPage"), o.optDouble("readFrac").toFloat(),
                                object : PageImageSource {
                                    override fun request(page: Int, widthPx: Int, cbb: (Bitmap?) -> Unit) {
                                        val f = fetcher ?: run { cbb(null); return }
                                        // 第 4 个参数 = 库文档 id：缓存键与 URL 都带上它，
                                        // 否则参考窗和正文的同页号会互相顶掉（PageFetcher.key）。
                                        f.fetch(page, id, widthPx, id, cbb)
                                    }
                                    override fun clear() {}
                                },
                            )
                        } catch (e: Exception) {
                            null
                        }
                        runOnUiThread { cb(info) }
                    }.start()
                }

                override fun refRelease() {
                    // 取图走共享的 `fetcher`，参考文档那批图随 LRU 自然淘汰即可（没有独占资源要放）。
                }
            },
        )
    }
    private var connToken = ""

    // —— Mac 下发的列表状态（面板消费） ——
    /** `docs` 广播的全量，**跨工作区**（Mac 的工作区是窗口级、多个能同时开着，见 `DocEntry.ws`） */
    private var docs = listOf<WireCodec.DocEntry>()

    /** [docs] 里属于当前工作区的那几篇 = 标签页栏的内容（栏上的下标就是这个表的下标） */
    private var visDocs = listOf<WireCodec.DocEntry>()

    /** Mac 当前跟随的那个窗口会话 id（`docs` 广播的 `selected`） */
    private var selectedDoc = ""

    /** `library` 广播的工作区名。只在 `docs` 里那份为空（Mac 的窗口快照还没注入）时兜底显示 */
    private var libWs = ""

    /** 每个工作区上次待在哪一篇：切回去时回到它，而不是永远落在第一篇 */
    private val lastDocInWs = HashMap<String, String>()
    private var layers = listOf<Layer>()
    private var layerIdx = 0

    /** 左侧拉抽屉（目录 / 书库），与模式1 共用一份。数据由 Mac 的 `toc`/`library` 广播喂 */
    private lateinit var drawer: ReaderDrawer

    /**
     * 标签页栏，与模式1 共用一份 —— 但这里是 Mac 已打开窗口（`docs` 广播）的**只读镜像**：
     * 点标签 = `selectDoc` 切过去，`+` = 开书库让 Mac `openDoc` 新开一个，**没有 ×**
     * （关窗仍在 Mac 上做，线协议里没有「关」，用户 2026-08-28 拍板）。
     */
    private lateinit var tabsBar: DocTabsBar

    /** 草稿纸（模式2 全链路）：覆盖层画布 + 浮条 + 列表/纸样面板；真源在 Mac，这里只发请求 */
    private lateinit var scratch: PadScratch

    // —— 画板笔记（`../PROTOCOL.md §4.8`）：Mac 的 `boards` 全量镜像 ——
    /** 被跟随会话是什么：0 = PDF（或空标签）、1 = Markdown 笔记、2 = 画板笔记 */
    private var boardKind = WireCodec.BOARD_KIND_PDF

    /** kind=2 时是哪一篇 */
    private var boardCurrent = ""

    /** 当前工作区的全部画板笔记（Mac 按最近打开排好序、标题已兜底） */
    private var boards = listOf<WireCodec.BoardEntry>()

    /** 画板图片来源（按 host 建，连哪台 Mac 取哪台的图） */
    private var boardImages: BoardImageFetcher? = null

    /** 「Mac 正在看 Markdown 笔记」的空状态（kind=1 时盖住页面视图，不停在上一篇 PDF 上） */
    private lateinit var mdEmpty: LinearLayout

    // —— 量化指标（rtt/e2e/nackRTT/mv-s，照 udp-pad-sim.py refresh）——
    private var rtt = -1.0
    private var e2e = -1.0
    private var tEnd = 0L
    private var mvCount = 0
    private var mvRate = 0
    private var statusMsg = ""

    /** 回推体积打点的上次时刻（1s 一条，见 onStrokes）：e2e 爬升是不是被全量镜像拖的，看它 */
    private var lastStrokeLogAt = 0L

    // —— 连接弹窗（扫码结果要回写，故持引用） ——
    private var connDialog: AlertDialog? = null
    private var dialogHost: EditText? = null
    private var dialogToken: EditText? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PalmSettings.load(this)
        buildUi()
        handler.postDelayed(sampler, 1000)
        val host = intent.getStringExtra(EXTRA_HOST)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (!host.isNullOrEmpty() && !token.isNullOrEmpty()) {
            // 启动页已经选好了这台 Mac：直接连。token 过期（authFail）时连接设置照旧会带着 host 弹出来
            getSharedPreferences("conn", MODE_PRIVATE).edit()
                .putString("host", host).putString("token", token).apply()
            connect(host, token)
        } else {
            showConnDialog()   // 未连接自动弹出
        }
    }

    override fun onResume() {
        super.onResume()
        client?.connectNow()      // 回前台：断了立刻重连，不等退避计时器
        fetcher?.setForeground(true)   // 退后台时让出去的页图额度，回来复原
    }

    override fun onPause() {
        scratch.flushViewportSave()   // 画板位置别等 0.6s 节流：退到后台后进程随时可能被杀
        super.onPause()
    }

    /**
     * 系统要内存了。页图额度是按设备总内存给的（可到 384MB），而位图住在 native 堆
     * （API 26+）——超支不抛 OOM，是**整个进程被 lowmemorykiller 干掉**，回来就是冷启。
     * 所以退到后台/系统吃紧时主动还，回前台再由 [onResume] 复原。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        fetcher?.onTrimMemory(level)
    }


    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scratch.release()  // 停掉草稿纸的 8ms 批缓冲定时器
        udp?.close()       // 内含 BYE
        client?.close()
        super.onDestroy()
    }

    // ---------- UI ----------

    /**
     * 沉浸式全屏（对应网页顶栏的「全屏」按钮）：平板当输入板用，状态栏/导航栏纯属占地方且易误触。
     * 走 androidx 的 WindowInsetsController——targetSdk 35+ 起旧的 systemUiVisibility 标志位已失效。
     * 不锁方向：竖屏/横屏都行，布局随 onSizeChanged 自适应（同网页 toggleFull 的注释）。
     */
    private fun enterImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 诊断（第四轮）：内核层证明写字时侧键的 KEY_PAGEUP 一个不少，网页也收得到，唯独本应用收不到，
        // 而 dispatchKeyEvent（按键进 Activity 的第一站）连日志都没打——事件根本没发到**我们的窗口**。
        // 按键发给「焦点窗口」、触摸发给「触摸窗口」，两者可以不是同一个：只要书写期间窗口焦点被别人
        // （最可能是沉浸式全屏下被临时唤出的系统栏）拿走，按键就会跟着走掉，而下面这行 enterImmersive
        // 又会把它收回来，于是焦点来回切——「多按几次偶尔能中」正是这个形状。这行日志就是判据。
        Log.i(PageCanvasView.TAG, "窗口焦点=$hasFocus 书写中=${padView.isPenDown()}")
        if (hasFocus) enterImmersive()   // 弹窗/切回前台后系统栏会回来，重新收掉
    }

    /** 指标行的宽度上限：竖屏 1080 下整行指标比按钮组还宽，压到 120dp 省略显示（§9.3）；横屏不限 */
    private fun latMaxWidth(): Int =
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            dp(120)
        } else {
            Int.MAX_VALUE
        }

    /**
     * 转屏不再重建 Activity（manifest 声明了 configChanges，重建会把 WS/UDP 连接断掉）：
     * 布局随各视图的 onSizeChanged 自适应，方向相关的一次性判定只有指标行宽度这一条，在这里重判。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        latText.maxWidth = latMaxWidth()
    }

    /** 跳页：跟着 Mac 看分页画板时跳画板的页（本端视口，不上线），否则跳 PDF 的页 */
    private fun showGotoPage() {
        val bc = scratch.canvas
        if (scratch.boardMode && bc.paged) {
            PadPanels.showGotoPage(this, bc.pageCount) { bc.placeAtPageTop(it - 1) }
        } else {
            PadPanels.showGotoPage(this, padView.pageCountOrZero()) { padView.gotoPage(it) }
        }
    }

    /** 收起/展开顶栏（同网页 hideBar/showBar）：收起后右上角浮一枚小按钮，画布拿到整屏 */
    private fun setBarHidden(hidden: Boolean) {
        topbar.visibility = if (hidden) View.GONE else View.VISIBLE
        showBarBtn.visibility = if (hidden) View.VISIBLE else View.GONE
        padView.setBarHeight(if (hidden) 0f else barHeightPx.toFloat())
        applyScratchMargin()
    }

    /** 覆盖层与浮条让开顶栏（纸不铺进顶栏那条带子，§7.1）；顶栏收起时让到 0 */
    private fun applyScratchMargin() {
        val top = if (topbar.visibility == View.VISIBLE) barHeightPx else 0
        (scratch.canvas.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = top
            scratch.canvas.layoutParams = it
        }
        if (::bar.isInitialized) bar.tools.relayout()   // 浮着的工具组：没挪过的贴顶栏下方，挪过的不许钻进顶栏
        (mdEmpty.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.topMargin = top
            mdEmpty.layoutParams = it
        }
    }

    /** 图层面板（真源在 Mac：这里发的都是「请求」，权威状态等 Mac 广播回来） */
    private fun showLayerPanel() {
        PadPanels.showLayerPanel(
            this, layers, layerIdx,
            onSelect = { client?.send(WireCodec.encodeLayerSelect(it)) },
            onToggleVisible = { i, v -> client?.send(WireCodec.encodeLayerVisible(i, v)) },
            onAdd = { client?.send(WireCodec.encodeLayerAdd()) },
            emptyHint = "（还没收到 Mac 的图层表）",
        )
    }

    private fun buildUi() {
        enterImmersive()

        padView = PadView(this).apply {
            listener = this@PadActivity
            // 框选选中集有无变化 → 顶栏那两颗剪切/复制跟着灰掉或亮起
            onLassoSelChanged = { refresh() }
            // 手指点页面上的书签缎带 → 改名/删除（只发请求，等 Mac 的 bookmarks 回推）
            onBookmarkTap = { b ->
                PadPanels.showBookmarkMenu(
                    this@PadActivity, b.title, b.page,
                    onRename = { t ->
                        client?.send(WireCodec.encodeBookmarkEdit(WireCodec.BM_RENAME, b.id, title = t))
                    },
                    onDelete = { client?.send(WireCodec.encodeBookmarkEdit(WireCodec.BM_DELETE, b.id)) },
                )
            }
            // 页图源（PageCanvasView 的注入口）：模式2 从 Mac 取整页图。
            // **widthPx 要透传**——从前这里丢掉它、一律吃 Mac 写死的 1600px，于是 Pad 6 横屏
            // （视口 2880）拿到的图要放大 1.8 倍，比模式1 糊一档（见 PageFetcher 的说明）。
            // 换文档后的在途回调按 null 丢弃——旧 v 的页图贴到新文档上就是花屏。
            imageSource = object : PageImageSource {
                override fun request(page: Int, widthPx: Int, cb: (Bitmap?) -> Unit) {
                    val v = docV
                    val f = fetcher ?: run { cb(null); return }
                    f.fetch(page, v, widthPx) { bmp -> cb(if (v == docV) bmp else null) }
                }

                override fun clear() {
                    fetcher?.clear()
                }
            }
        }

        // 草稿纸：覆盖层画布 + 浮条 + 回调（出口全是发给 Mac 的请求/RT 帧，权威状态等回推）
        scratch = PadScratch(this).apply {
            sendRel = { udp?.sendRel(it) ?: 0L }
            sendCtl = { client?.send(it) }
            onMoveFrame = { mvCount++ }
            onInkEndSent = { tEnd = System.currentTimeMillis() }
            onPinsChanged = { pins -> padView.setScratchPins(pins) }
            onOpenChanged = { refresh() }
            anchorProvider = { padView.viewportCenterAnchor() }
            // 页面底图（v10）：与阅读画布**共用同一个页图源**（同一份 PageFetcher 缓存，
            // 纸上那页多半刚刚看过、直接命中），页纵横比取 layout 下发的页尺寸表
            pageSource = padView.imageSource
            pageAspect = { i -> padView.pageAspect(i) }
            // 画板会话里浮条最左那颗键 = 画板笔记列表（同 ⋯ 里那一项）
            onBoardList = { showBoardList() }
            // 工具快照现取阅读画布：纸开着时改笔/改橡皮/切尺子即时生效（尺子走 45° 吸附，同页内）
            toolsProvider = {
                ScratchCanvas.Tools(
                    inkTool = padView.mode == MODE_NOTE && !padView.noteMode,
                    eraseTool = padView.mode == MODE_ERASE,
                    pen = padView.curPenOrNull() ?: PageCanvasView.FALLBACK_PENS[0],
                    eraserSize = padView.eraserSize,
                    eraserMode = padView.eraserMode,
                    eraserRing = padView.eraserRing,
                    rulerOn = padView.rulerOn,
                    lassoTool = padView.mode == MODE_LASSO,
                    relativeInk = padView.relativeInkWidth,
                )
            }
            // 纸上的框选选中集有无变化 → 顶栏剪切 / 复制跟着灰掉或亮起（同页内）
            canvas.onLassoSelChanged = { refresh() }
        }

        dot = View(this)   // 连接状态点（绿=已认证）
        latText = TextView(this).apply {
            text = "— ms"; textSize = 12f
            setTextColor(Ui.col(this@PadActivity, R.color.bar_on_variant))
            // 被挤窄时省略而不是换行（换行会把顶栏顶高、按钮跟着变形）
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 整行指标（rtt/e2e/nackRTT/mv-s/nack/resend）要 650px，竖屏 1080 下比按钮组还宽。
            // 谁都不肯让 = 按钮被挤没（§9.3 那个 BUG 的真正原因）。**按钮优先**：窄屏把这行压到
            // 120dp 省略显示，宽屏（模式2 的正常场景＝横屏平板）保持整行不变。
            maxWidth = latMaxWidth()
        }
        // 顶栏与模式1 是同一份（shared/TopBar），2026-08-28 起**常驻键完全一致、顺序也一致**；
        // 模式2 只在末尾多一个连接态小圆点 + 延迟读数，⋯ 里多「收起顶栏 / 连接设置」两件独有的事。
        bar = TopBar(this).apply {
            // 「选择文档」那颗键 2026-08-28 拿掉了：它做的事（在 Mac 已开的几篇之间切）现在是
            // 标签页栏本身，一眼看得见哪几篇开着、点哪个切哪个，比翻一个对话框直接。
            icon("toc", R.drawable.ic_list, "目录 / 书库") { drawer.toggle() }
            icon("prev", R.drawable.ic_chevron_left, "上一页", spillFirst = true) { padView.turn(prev = true) }
            icon("next", R.drawable.ic_chevron_right, "下一页", spillFirst = true) { padView.turn(prev = false) }
            // 模式键：位置/图标/激活口径与模式1 逐字一致（两边的顶栏必须还是同一条栏）
            icon("mode", TopBar.modeIcon(MODE_NOTE), "切换模式") { padView.cycleMode() }
            // 「切换笔」只在笔模式下出现且染当前笔色（refresh() 维护），其余模式占位纯属误导
            icon("pen", R.drawable.ic_pen, "切换笔") { padView.cyclePen() }
            icon("ruler", R.drawable.ic_ruler, "尺子") { padView.toggleRuler() }
            // 撤销/重做：栈在 Mac（`../PROTOCOL.md §4.1`），这里只发意图。常亮——本端不知道
            // Mac 那边还有没有得撤，为此再加一条 S→C 广播不值当，点了没得撤就是个空操作。
            icon("undo", R.drawable.ic_undo, "撤销") { padView.requestUndo(redo = false) }
            icon("redo", R.drawable.ic_redo, "重做") { padView.requestUndo(redo = true) }
            // 剪贴板三件：只在框选模式下出现（对象就是选中集，见 refreshHud 的 setVisible）。
            // 剪贴板本身是 Mac 的系统剪贴板 → 平板复制的东西能在 Mac 上粘、也能粘进另一篇文档。
            // 草稿纸 / 画板开着时三键作用在纸上（画布坐标，`../PROTOCOL.md §4.4`）
            icon("clipCut", R.drawable.ic_cut, "剪切选中笔迹") {
                if (scratch.isOpen) scratch.clipCopy(cut = true) else padView.requestClipCopy(cut = true)
            }
            icon("clipCopy", R.drawable.ic_copy, "复制选中笔迹") {
                if (scratch.isOpen) scratch.clipCopy(cut = false) else padView.requestClipCopy(cut = false)
            }
            icon("clipPaste", R.drawable.ic_paste, "粘贴到视口中央") {
                if (scratch.isOpen) scratch.clipPaste() else padView.requestClipPaste()
            }
            // 初始先收起来：`setVisible` 的默认是「可见」，而开局一定不在框选模式
            for (k in arrayOf("clipCut", "clipCopy", "clipPaste")) setVisible(k, false)
            // 文字笔记从环形盘进（RK_TEXT），顶栏不再放开关：它只翻一个 noteMode 标志，
            // 点下去界面毫无变化，用户无法预期笔落下会变成「开编辑器」
            // 草稿纸：盖在 PDF 之上的无限白板（列表 + 「在当前位置新建」，见 PadScratch）
            icon("scratch", R.drawable.ic_scratch, "草稿纸") { scratch.showList() }
            // 锁缩放常驻（与模式1 同一位置、同一图标——两边的顶栏必须还是同一条栏）
            icon("lock", R.drawable.ic_lock, "锁定缩放") { padView.toggleZoomLock() }
            // 画板模式：**只发请求**，Mac 是开关与页边宽度的唯一真源（同 openPad 的惯例）——
            // 它执行后广播 `canvas` 回来，本端在 onCanvas 里才改布局，所以按下去到画面变化
            // 之间隔着一个 RTT。常驻顶栏而不是进 ⋯，与模式1 对齐。
            icon("canvas", R.drawable.ic_canvas, "画板模式") {
                client?.send(WireCodec.encodeCanvas(!padView.canvasModeOn()))
            }
            // 参考窗：另开一本书摆在旁边对照（纯本端只读显示，不上线）
            icon("ref", R.drawable.ic_doc, "参考窗") { refWin.toggle() }
            setEnabled("canvas", connected)   // 断线时灰掉（它只发请求，没连上按了没反应）
            // 连接状态点 + 延迟读数：也是一颗可自由摆放的工具（默认在顶栏右侧固定的「状态」组里，与页码一起）
            val latBox = LinearLayout(this@PadActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot)
                addView(latText)
            }
            tools.register("latency", getString(R.string.tools_latency), latBox)
            pageLabel.setOnClickListener { showGotoPage() }
            // 原先只在 ⋯ 里的低频项：2026-09-26 起也是可自由编组的工具（默认布局照旧收在 ⋯ 里，见 ToolLayout.MENU_KEYS；
            // key 与模式1 同名，布局两模式共用一份）。开关态在 refresh() 里按 setActive 维护
            // 画板笔记：列出 Mac 这个工作区的画板（点一篇 = boardOpen）+ 新建（boardAdd）。PDF / Markdown / 画板三种会话下都要能进（§4.8）
            icon("boardList", R.drawable.ic_grid4, getString(R.string.board_title)) { showBoardList() }
            icon("night", R.drawable.ic_moon, getString(R.string.tools_night), toggle = true) {
                padView.toggleNight(); refresh()
            }
            icon("showPage", R.drawable.ic_eye, getString(R.string.tools_show_page), toggle = true) {
                padView.toggleShowPage(); refresh()
            }
            // 防误触：开了之后单指划动不再平移，滚动/缩放一律双指（基类 twoFingerScroll）
            icon("twoFinger", R.drawable.ic_two_finger, getString(R.string.tools_two_finger), toggle = true) {
                padView.toggleTwoFingerScroll(); refresh()
            }
            // 另外三条防误触 + 左手书写：本机偏好、两模式共用一份（PalmSettings），模式2 也存本机
            PalmSettings.icons(this, this@PadActivity) { refresh() }
            // 小手：手指此刻能不能用（不能用变灰，点了说原因），默认在右侧「状态」组
            PalmSettings.fingerIcon(this, this@PadActivity)
            // 锁横向：放大了看 / 画板模式下在页边写字时，竖着划一道很难不带横向分量
            icon("hLock", R.drawable.ic_h_lock, getString(R.string.tools_h_lock), toggle = true) {
                padView.toggleHLock(); refresh()
            }
            // 书写锁定：锁定后模式键只在 笔记/擦除 间来回，长按环形盘也只剩这两项（Mac 判定，
            // 见 `../PROTOCOL.md` `lock` 0x59）；这里只发意图，`onLockChanged` 上行给 Mac
            icon("writeLock", R.drawable.ic_write_lock, getString(R.string.tools_write_lock), toggle = true) {
                padView.toggleWritingLock(); refresh()
            }
            // 相对粗细：本端按自己的缩放折算笔宽后上行（Mac 原样用），开关与 Mac 双向同步（`relInk` 0x5A）
            icon("relativeInk", R.drawable.ic_relative_width, getString(R.string.tools_relative_ink), toggle = true) {
                padView.toggleRelativeInkWidth(); refresh()
            }
            icon("layers", R.drawable.ic_layers, getString(R.string.tools_layers)) { showLayerPanel() }
            icon("gotoPage", R.drawable.ic_hash, getString(R.string.tools_goto_page)) { showGotoPage() }
            icon("hideBar", R.drawable.ic_chevron_up, getString(R.string.tools_hide_bar)) { setBarHidden(true) }
            icon("conn", R.drawable.ic_monitor, getString(R.string.tools_conn)) { showConnDialog() }
        }
        (dot.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(8); height = dp(8); marginEnd = dp(8)
        }
        // 顶栏 + 标签页栏是一整块「上边的壳」（与模式1 的 chrome 同构）：一起显示、一起被
        // 「收起顶栏」收掉，画布让开的高度也是两条之和（barHeightPx）
        topbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar.view, LinearLayout.LayoutParams(-1, -2))
        }
        showBarBtn = Ui.iconButton(this, R.drawable.ic_chevron_down, "展开顶栏", Ui.barOn(this)) {
            setBarHidden(false)
        }.apply {
            visibility = View.GONE
            background = Ui.rippleOver(
                this@PadActivity,
                Ui.round(Ui.col(this@PadActivity, R.color.bar_scrim), Ui.RADIUS, this@PadActivity),
                Ui.RADIUS,
                Ui.barOn(this@PadActivity),
            )
        }

        // 左下状态胶囊：笔/橡皮 与 图层（对应网页 PenStat / LayerStat）
        penStat = capsule(this).apply {
            setOnClickListener {
                PadPanels.showPenPanel(this@PadActivity, padView, ::schedulePenset, ::scheduleEraser)
            }
        }
        layerStat = capsule(this).apply { setOnClickListener { showLayerPanel() } }
        val capsules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layerStat, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            // 显式 wrap：默认的 MATCH_PARENT 会被 wrap 的竖向容器夹成「最宽那颗」的宽度，
            // 「翻页 · 拖动平移」会被截掉尾巴（模式1 同处一样）
            addView(penStat, LinearLayout.LayoutParams(-2, -2))
        }

        graphView = LatencyGraphView(this)

        drawer = ReaderDrawer(this).apply {
            onJump = { page, frac ->
                // 本地立刻滚过去 + 上行让 Mac 跟到同一处（Mac 走它自己那条 origin="toc" 锚点路径，
                // 跳完再 viewport 回推——本地已在位，回推是同一处，不会打架）
                padView.scrollToPageFrac(page, frac)
                client?.send(WireCodec.encodeGotoDest(page.toLong(), frac))
                refresh()
            }
            onOpenDoc = { id -> client?.send(WireCodec.encodeOpenDoc(id)) }
            // 书签：三个都只发**请求**，Mac 是唯一真源（落库后以 bookmarks 全量回推，
            // 本端不做乐观更新——同 scratchDelete/scratchRename 的惯例）。
            // 落点 = 当前视口顶那一页那一处，与 Mac 的 ⌘D 同口径。
            onAddBookmark = { title ->
                val p = padView.topVisiblePage()
                client?.send(WireCodec.encodeBookmarkEdit(
                    WireCodec.BM_ADD, java.util.UUID.randomUUID().toString(),
                    p.toLong(), padView.topFrac(), title,
                ))
            }
            onRenameBookmark = { id, title ->
                client?.send(WireCodec.encodeBookmarkEdit(WireCodec.BM_RENAME, id, title = title))
            }
            onDeleteBookmark = { id ->
                client?.send(WireCodec.encodeBookmarkEdit(WireCodec.BM_DELETE, id))
            }
            // 画板笔记（`../PROTOCOL.md §4.8`）：书库页列出 Mac 这个工作区的画板；点开 / 新建都只是请求，
            // Mac 在被跟随的窗口里开好标签后照常回推 boards / scratchpads / scratchStrokes / boardImages。
            boardMoreEnabled = false
            onOpenBoard = { id ->
                if (!(boardKind == WireCodec.BOARD_KIND_BOARD && id == boardCurrent)) {
                    client?.send(WireCodec.encodeBoardOpen(id))
                }
            }
            onAddBoard = { requestNewBoard() }
        }

        // 标签页栏：Mac `docs` 广播的只读镜像（见字段注释），只列**当前工作区**那几篇。
        // 工作区芯片与模式1 同义——点它切工作区（Mac 上开着的那几个，2026-09-06 起）；
        // 书库还有两个入口（芯片右边的 `+`、抽屉的「书库」页），不必再让芯片兼这一职。
        tabsBar = DocTabsBar(this).apply {
            canClose = false
            setWorkspace("书库")   // 占位：`docs`/`library` 广播一到就换成 Mac 那边的工作区名
            onSwitchWorkspace = { showWorkspaceSwitcher() }
            onSelect = { i -> visDocs.getOrNull(i)?.let { client?.send(WireCodec.encodeSelectDoc(it.id)) } }
            onAdd = { drawer.open(ReaderDrawer.TAB_LIB) }
        }
        topbar.addView(tabsBar.view, LinearLayout.LayoutParams(-1, -2))

        // Markdown 笔记的空状态：Mac 不会为 md 标签发 layout，不盖一层的话会停在上一篇 PDF 上（§4.8）
        mdEmpty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.col(this@PadActivity, R.color.surface_dim))
            isClickable = true   // 吃掉触摸：下面那块页面视图是上一篇的，别还能往上写
            visibility = View.GONE
            setPadding(dp(24), 0, dp(24), 0)
            addView(
                Ui.title(this@PadActivity, getString(R.string.board_markdown_title), 18f).apply {
                    gravity = Gravity.CENTER
                },
            )
            addView(
                Ui.body(this@PadActivity, getString(R.string.board_markdown_msg)).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, 0)
                },
            )
        }

        val root = FrameLayout(this).apply {
            addView(padView, FrameLayout.LayoutParams(-1, -1))
            addView(mdEmpty, FrameLayout.LayoutParams(-1, -1))
            // 草稿纸覆盖层：在顶栏**之下**（topMargin 让开顶栏那条带子——handoff §7.1 的白压白坑，
            // applyScratchMargin 负责），在页面画布之上（纸开着时吃掉全部指针事件，PDF 上一笔都
            // 落不下——这是这个功能的定义；probe/环形盘也因此天然不会在纸上触发）
            addView(scratch.canvas, FrameLayout.LayoutParams(-1, -1))
            addView(refWin.view, FrameLayout.LayoutParams(-1, -1))
            addView(topbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(
                showBarBtn,
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(8); marginEnd = dp(8)
                },
            )
            addView(
                capsules,
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    leftMargin = dp(10); bottomMargin = dp(10)
                },
            )
            addView(
                graphView,
                FrameLayout.LayoutParams(dp(200), dp(90), Gravity.TOP or Gravity.END).apply {
                    topMargin = bar.height() + tabsBar.height() + dp(8); marginEnd = dp(8)
                },
            )
            // 抽屉加在最后 = 盖在最上层（含顶栏）：开着时下面的画布不该还能写字
            addView(drawer.view, FrameLayout.LayoutParams(-1, -1))
        }
        // 可自由编组的工具栏（shared/Toolbox，与模式1 同一套、布局共用一份本机记录）：草稿纸 / 画板控制栏的每颗键
        // 也是工具。顶栏收起时并在里面的组跟着一起收
        bar.tools.apply {
            minTop = { if (topbar.visibility == View.VISIBLE) barHeightPx else 0 }
            below = drawer.view
            adopt(scratch.barView as LinearLayout, Toolbox.PAD2_BAR_KEYS) { scratch.barView.visibility == View.VISIBLE }
            attach(root)
        }
        scratch.onBarShown = { bar.tools.refresh() }
        scratch.onHudChanged = { if (scratch.boardMode) refresh() }   // 分页画板滚动 → 顶栏页码跟着变
        setContentView(root)
        barHeightPx = bar.height() + tabsBar.height()
        padView.setBarHeight(barHeightPx.toFloat())
        applyScratchMargin()
        setDot(false)
        applyGraphVisibility()
        applyLatVisibility()
        refresh()
    }

    private fun setDot(on: Boolean) {
        connected = on
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        }
        // 画板模式是**发给 Mac 的请求**，没连上按了会静默无反应 → 断线时灰掉
        // （模式/笔/尺子那几个是本地状态，断线照样能按，故只有这一颗要跟着连接走）
        if (::bar.isInitialized) bar.setEnabled("canvas", on)
    }

    /** 认证通过没（`setDot` 维护）：只发请求的那些键要据此禁用 */
    private var connected = false

    /** 返回键：抽屉开着先关抽屉（同系统抽屉惯例）；纸开着先关纸（发 scratchOpen(-1)，同模式1） */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.isOpen) { drawer.close(); return }
        // 画板会话里那张「纸」关不掉（§4.8）：返回键照常走
        if (scratch.isOpen && !scratch.boardMode) { scratch.requestClose(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * 上一次已经处理掉的按键序列。`KeyEvent.getDownTime()` 对一次按下的 DOWN 与它配对的 UP 是**同一个值**，
     * 拿它去重比时间窗精确：既不会把同一次按键触发两遍，也不会误吞快速连按的第二下。
     */
    private var lastHandledKeyDown = 0L

    /**
     * 侧键：PageUp 切模式 / PageDown 切笔 / Esc 清框选（同网页 keydown）。
     *
     * **DOWN 与 UP 谁先到就认谁**，而不是只认 ACTION_DOWN——小米智能触控笔实测（真机日志）：
     * 书写期间按侧键，有些次数只有 `action=1(UP)` 到达应用，`DOWN` 被上游整个吞掉了
     * （典型的系统全局快捷键行为：系统在 DOWN 上先判要不要自己吃掉）。只认 DOWN 的话那一次就
     * 静默失效，表现正是用户报的「按了没反应，要多按好几次」。`getevent` 证明内核层 DOWN/UP 一直是
     * 齐的，所以丢在系统那一层，应用侧只能两边都认。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val side = event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE
        // 键盘快捷键（硬件键盘，与 Mac/web 同约定）：e 橡皮来回切 / 1-9 直选笔 / n 笔记 / v 翻页 / l 框选。
        // 文本框焦点（连接设置/重命名）或带修饰键时一律放行，不抢输入。
        val shortcut = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            currentFocus !is EditText &&
            (
                event.keyCode == KeyEvent.KEYCODE_E || event.keyCode == KeyEvent.KEYCODE_N ||
                    event.keyCode == KeyEvent.KEYCODE_V || event.keyCode == KeyEvent.KEYCODE_L ||
                    event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9
                )
        if (!side && !shortcut) return super.dispatchKeyEvent(event)

        // 长按重复不算新的一次按键；同一次按键的另一半（DOWN 已处理过就轮到 UP）直接吃掉
        val fresh = event.repeatCount == 0 && event.downTime != lastHandledKeyDown
        Log.i(
            PageCanvasView.TAG,
            "侧键 keyCode=${event.keyCode}(${KeyEvent.keyCodeToString(event.keyCode)}) " +
                "action=${event.action}(0=DOWN 1=UP) repeat=${event.repeatCount} " +
                "书写中=${padView.isPenDown()} 生效=$fresh",
        )
        if (!fresh) return true
        lastHandledKeyDown = event.downTime
        when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> padView.cycleMode()
            KeyEvent.KEYCODE_PAGE_DOWN -> padView.cyclePen()
            KeyEvent.KEYCODE_ESCAPE -> padView.clearLasso()
            KeyEvent.KEYCODE_E -> padView.toggleEraser()
            KeyEvent.KEYCODE_N -> padView.setModeLocal(MODE_NOTE)
            KeyEvent.KEYCODE_V -> padView.setModeLocal(if (padView.mode == MODE_PAGE) MODE_NOTE else MODE_PAGE)
            KeyEvent.KEYCODE_L -> padView.setModeLocal(if (padView.mode == MODE_LASSO) MODE_NOTE else MODE_LASSO)
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> padView.selectPen(event.keyCode - KeyEvent.KEYCODE_1)
        }
        return true
    }

    // ---------- 防抖上行（笔宽 / 橡皮设置，同网页 300ms） ----------

    private var pensetPending: Runnable? = null
    private fun schedulePenset() {
        pensetPending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pensetPending = null
            client?.send(WireCodec.encodePenset(padView.penIndex, padView.penList()))
        }
        pensetPending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    private var eraserPending: Runnable? = null
    private fun scheduleEraser() {
        eraserPending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            eraserPending = null
            client?.send(WireCodec.encodeEraser(padView.eraserSize, padView.eraserMode, padView.eraserRing))
        }
        eraserPending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    // ---------- 连接设置弹窗 + 扫码 ----------

    /**
     * 连接设置。外观走 [Sheet]（§9.6）；**「连接」不自动关窗**——host/token 填错时关掉就得重填，
     * 所以 `dismiss = false`，校验通过了才由这里自己 `dismiss()`。
     *
     * 错误提示改成弹层内的一行红字（原先是写进顶栏的 `statusMsg`——弹窗盖着顶栏，用户根本看不见）。
     *
     * 顶上是「历史设备」（[KnownMacs]）：连过的 Mac 点一下就连，右侧垃圾桶移除。
     * 输入框预填当前这次的 host/token（authFail 后弹窗会重开，那时填过的东西不能丢），
     * 没有才回退到 prefs 里最后一次成功的那组。
     */
    private fun showConnDialog() {
        val prefs = getSharedPreferences("conn", MODE_PRIVATE)
        val hostEdit = PadPanels.inputBox(this, "Mac IP（如 192.168.1.5）").apply {
            setText(connHost.ifEmpty { prefs.getString("host", "") ?: "" })
        }
        val tokenEdit = PadPanels.inputBox(this, "token（面板 URL 里的）").apply {
            setText(connToken.ifEmpty { prefs.getString("token", "") ?: "" })
        }
        val err = Ui.body(this, "").apply {
            setTextColor(Ui.col(this@PadActivity, R.color.danger))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 行里的「点了就连」要关掉这个弹窗，而弹窗此刻还没建出来 → 建好后回填
        var dlgRef: AlertDialog? = null
        fun renderHistory() {
            history.removeAllViews()
            val known = KnownMacs.list(this)
            if (known.isEmpty()) return
            history.addView(Ui.sectionTitle(this, "历史设备"))
            for (e in known) {
                history.addView(
                    PadPanels.twoLineRow(
                        this,
                        R.drawable.ic_monitor,
                        Ui.accent(this),
                        e.label,
                        e.host,
                        trailing = Ui.iconButton(
                            this,
                            R.drawable.ic_delete,
                            "移除这台 Mac",
                            Ui.onVariant(this),
                        ) {
                            KnownMacs.forget(this, e.host)
                            renderHistory()
                        },
                    ) {
                        // 顺手回填输入框：Mac 重开过 App 的话 token 已失效（authFail），
                        // 弹窗会带着这组值重开，改一下或扫个码就行，不用从头输 IP
                        hostEdit.setText(e.host)
                        tokenEdit.setText(e.token)
                        connect(e.host, e.token)
                        dlgRef?.dismiss()
                    },
                )
            }
            history.addView(Ui.spacer(this, 12))
        }
        renderHistory()
        // 连接校验（大按钮与回车都走这条）：失败留在原地改，成功才关窗
        fun submit() {
            val host = hostEdit.text.toString().trim()
            val token = tokenEdit.text.toString().trim()
            if (host.isEmpty() || token.isEmpty()) {
                err.text = "host 和 token 都要填"
                err.visibility = View.VISIBLE
                return
            }
            connect(host, token)
            dlgRef?.dismiss()
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(history)
            addView(hostEdit)
            addView(tokenEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(err)
            // 主操作 = 「连接」大按钮；「扫码连接」是次要路径，收成无边框文字按钮
            addView(
                Ui.button(this@PadActivity, "连接", filled = true) { submit() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
            )
            addView(
                Ui.textButton(this@PadActivity, "扫码连接") { startScan() },
                LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(4)
                },
            )
            addView(
                CheckBox(this@PadActivity).apply {
                    text = "显示延迟曲线"
                    setTextColor(Ui.onSurface(this@PadActivity))
                    isChecked = prefs.getBoolean("showGraph", true)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean("showGraph", checked).apply()
                        applyGraphVisibility()
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
            addView(
                CheckBox(this@PadActivity).apply {
                    text = "显示延迟读数"
                    setTextColor(Ui.onSurface(this@PadActivity))
                    isChecked = prefs.getBoolean("showLat", true)
                    setOnCheckedChangeListener { _, checked ->
                        prefs.edit().putBoolean("showLat", checked).apply()
                        applyLatVisibility()
                    }
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) },
            )
        }
        val dlg = Sheet(this)
            .title("连接 Mac")
            .content(form)
            .action("取消")
            .show()
        dlgRef = dlg
        dialogHost = hostEdit
        dialogToken = tokenEdit
        connDialog = dlg
        dlg.setOnDismissListener { connDialog = null }
    }

    private fun applyGraphVisibility() {
        val show = getSharedPreferences("conn", MODE_PRIVATE).getBoolean("showGraph", true)
        graphView.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 顶栏延迟读数（rtt/e2e/…）的显隐：与 showGraph 同一份 prefs，都是「连接设置」这一件事 */
    private fun applyLatVisibility() {
        val show = getSharedPreferences("conn", MODE_PRIVATE).getBoolean("showLat", true)
        latText.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        launchScan()
    }

    private fun launchScan() {
        val ii = IntentIntegrator(this)
        ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        ii.setPrompt("对准 Mac 配对二维码")
        ii.setBeepEnabled(false)
        ii.setOrientationLocked(false)
        ii.initiateScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchScan()
        } else if (requestCode == REQ_CAMERA) {
            statusMsg = "相机权限被拒，无法扫码"
            refresh()
        }
    }

    @Deprecated("zxing IntentIntegrator 走传统 onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            // 二维码内容：http://<ip>:8770/?token=XXXX
            val uri = android.net.Uri.parse(result.contents)
            val host = uri.host
            val token = uri.getQueryParameter("token")
            if (!host.isNullOrEmpty() && !token.isNullOrEmpty()) {
                dialogHost?.setText(host)
                dialogToken?.setText(token)
                connect(host, token)
                connDialog?.dismiss()
            } else {
                statusMsg = "二维码内容无法识别"
                refresh()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun connect(host: String, token: String) {
        client?.close()
        udp?.close()
        connHost = host
        connToken = token
        udp = UdpSender(host)
        // 页图磁盘缓存：整台设备一份（键里带文档 contentHash，不同 Mac/不同书都不会串），
        // 所以按 host 重连也不重建——重建等于把好不容易攒下的几百页白扔了。
        if (pageDisk == null) {
            pageDisk = PageDiskCache(java.io.File(cacheDir, "pageimg"), PAGE_DISK_BYTES)
        }
        fetcher = PageFetcher(host, pageDisk, PageWidths.cacheBytes(this))
        // 画板图片：按 host 取（换了 Mac 就换一份缓存——sha 虽不会串，但那台 Mac 未必有这张图）
        boardImages = BoardImageFetcher(host).also { scratch.canvas.picSource = it }
        docV = ""
        client = MacClient(host, token, this)
        setDot(false)
        statusMsg = "连接中…"
        refresh()
    }

    // ---------- 顶栏状态 ----------

    // `refresh()` 挂在 `onHudChanged` 上，**滚动/缩放的每一帧都会调**——所以一律走
    // `setTextIfChanged`（shared/Widgets.kt）。

    private fun refresh() {
        val u = udp
        latText.setTextIfChanged(
            if (statusMsg.isNotEmpty()) statusMsg
            else "rtt %.0f e2e %.0f nackRTT %.0f mv/s %d nack %d resend %d".format(
                rtt, e2e, u?.nackRttMs ?: -1.0, mvRate, u?.nacks ?: 0, u?.resends ?: 0,
            )
        )
        // 顶栏这几行与模式1 的 `refreshHud` 是同一套表达（shared/TopBar）：
        // 模式键换图标、开关键上 accent 底色。两边各写一份文案的时代就此结束。
        // 跟着 Mac 看的是分页画板：页码显示画板的「当前页/总页数」（画布缩放），不是底下那篇 PDF 的
        val bc = scratch.canvas
        if (scratch.boardMode && bc.paged) bar.setPageLabel("${bc.currentPageIndex() + 1}/${bc.pageCount}", "${bc.zoomPct()}%")
        else bar.setPageLabel(padView.hudPage(), padView.hudZoom())
        drawer.setCurrentPage(padView.topVisiblePage())   // 目录的「当前章节」追踪
        bar.setIcon("mode", TopBar.modeIcon(padView.mode))
        bar.setActive("mode", padView.mode != MODE_PAGE)
        bar.setActive("ruler", padView.rulerOn)
        bar.setActive("scratch", scratch.isOpen)
        bar.setActive("lock", padView.zoomLocked)
        bar.setActive("canvas", padView.canvasModeOn())
        bar.setActive("night", padView.night)
        bar.setActive("showPage", padView.showPage)
        bar.setActive("twoFinger", padView.twoFingerScroll)
        PalmSettings.setActive(bar)
        bar.setActive("hLock", padView.hLocked)
        bar.setActive("writeLock", padView.writingLocked)
        bar.setActive("relativeInk", padView.relativeInkWidth)
        // 「切换笔」只在笔模式下出现，并染当前笔的颜色（其余模式它不出现，见 buildUi 的注释）
        bar.setVisible("pen", padView.mode == MODE_NOTE)
        // 剪贴板三件只在框选模式露面；剪切/复制没选中就灰掉（粘贴常亮，见 buildUi 的注释）
        val lasso = padView.mode == MODE_LASSO
        for (k in arrayOf("clipCut", "clipCopy", "clipPaste")) bar.setVisible(k, lasso)
        if (!lasso) scratch.canvas.clearLasso()   // 离开框选：纸上的选中集同页内一样作废
        val hasSel = if (scratch.isOpen) scratch.canvas.hasLassoSelection() else padView.hasLassoSelection()
        bar.setEnabled("clipCut", hasSel)
        bar.setEnabled("clipCopy", hasSel)
        bar.setTint("pen", padView.curPenOrNull()?.let { Ui.penArgb(it) })
        // 防误触是一个模式、不是两个：草稿纸那块画布跟着页内画布走（幂等赋值，不触发重绘）
        scratch.canvas.twoFingerScroll = padView.twoFingerScroll
        scratch.canvas.hLocked = padView.hLocked

        // 笔胶囊：笔记模式显示当前笔（色块 · 类型 · 粗细），其余模式显示模式名（同网页 PenStat）
        val pen = padView.curPenOrNull()
        val notePen = pen.takeIf { padView.mode == MODE_NOTE }
        penStat.setTextIfChanged(
            when (padView.mode) {
                MODE_NOTE ->
                    if (pen != null) "${PadConst.brushLabel(brushName(pen.brush))} · ${(pen.w * 100).toInt() / 100f}pt"
                    else "笔记"
                MODE_ERASE -> "橡皮擦"
                MODE_LASSO -> "框选移动"
                else -> "翻页 · 拖动平移"
            }
        )
        penStat.setPenSwatch(notePen?.let { Color.argb((it.a * 255f).roundToInt().coerceIn(0, 255), it.r, it.g, it.b) })
        layerStat.setTextIfChanged(layers.getOrNull(layerIdx)?.let { "图层：${it.name}" } ?: "图层")
        if (::bar.isInitialized) bar.tools.refresh()   // 哪些工具此刻能用变了就重排（没变不重量，见 Toolbox.refresh）
    }

    // 1s 采样窗口：mv/s + 顶栏刷新
    private val sampler = object : Runnable {
        override fun run() {
            mvRate = mvCount
            mvCount = 0
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------- MacClient.Callback（OkHttp 后台线程 → 切主线程） ----------

    override fun onAuthOK(session: Long, udpPort: Int) = runOnUiThread {
        udp?.start(session, udpPort)   // 内含 HELLO 一发
        padView.syncToolState()        // 连接即同步 mode/pen/padGeom 给 Mac
        // 连接成功才持久化 host/token（下次启动预填）
        getSharedPreferences("conn", MODE_PRIVATE).edit()
            .putString("host", connHost)
            .putString("token", connToken)
            .apply()
        // 同一次成功也记进「历史设备」；机器名另外问一句 `/info`（探不到就先按 IP 显示，
        // 下次连上再补）。回调在 OkHttp 线程，写 prefs 前切回主线程——列表只在主线程上读写。
        val host = connHost
        KnownMacs.remember(this, host, connToken)
        KnownMacs.probeName(host) { name ->
            runOnUiThread { if (!isFinishing) KnownMacs.setName(this, host, name) }
        }
        connDialog?.dismiss()
        setDot(true)
        statusMsg = ""
        refresh()
    }

    override fun onAuthFail() = runOnUiThread {
        statusMsg = "authFail：token 不对"
        refresh()
        showConnDialog()
    }

    override fun onRtt(ms: Double) = runOnUiThread {
        rtt = ms
        graphView.addRtt(ms.toFloat())
        if (statusMsg.isNotEmpty()) statusMsg = ""
        setDot(true)
        refresh()
    }

    override fun onDisconnected(msg: String) = runOnUiThread {
        statusMsg = msg
        setDot(false)
        padView.clearTransient()   // 断线时盘/环正开着 → 收掉（Mac 不会补发瞬态状态）
        refresh()
    }

    override fun onLayout(docId: String, v: String, count: Long, pages: List<Pair<Float, Float>>) = runOnUiThread {
        // 换文档**不清页图缓存**（2026-08-29 用户报「切标签页每次都要重新加载」）：缓存键里带了
        // `v`（= Mac 的 `contentHash`，见 `AppModel` 的 layout 广播），两篇文档的页图本来就不会串；
        // 留着就是「切回刚才那篇 = 零网络直接贴」。额度是按字节的 LRU（`PageWidths.cacheBytes`），
        // 多留几篇不会失控，装不下时按最久未用逐出。同一篇改了内容 → `v` 变 → 旧键自然没人再问，
        // 跟着 LRU 老死即可。（清缓存只剩 `PageFetcher.clear`，留给退出/换 Mac 那条路。）
        val newV = v.ifEmpty { docId }
        docV = newV
        Log.i(PageCanvasView.TAG, "换文档 v=${newV.take(8)} 缓存 ${fetcher?.stats() ?: "—"}")
        padView.setLayout(docId, v, count.toInt(), pages)
        // 页尺寸到位后重算草稿纸的页面底图矩形（两条广播先后无保证，见 PadScratch.refreshPageUnder）
        scratch.refreshPageUnder()
        drawer.setDocV(newV)
        refresh()
    }

    override fun onViewport(page: Long, frac: Float, seq: Long, force: Boolean) = runOnUiThread {
        padView.applyViewport(page, frac, seq, force)
        refresh()
    }

    override fun onPens(active: Int, list: List<Pen>) = runOnUiThread {
        padView.setPens(list, active)
        refresh()
    }

    override fun onPenSel(index: Int) = runOnUiThread {
        padView.setPenIndex(index)
        refresh()
    }

    override fun onModeSel(mode: Int) = runOnUiThread {
        padView.setMode(mode)
        refresh()
    }

    override fun onInkCancel() = runOnUiThread {
        padView.onInkCancel()
    }

    override fun onStrokes(ackRel: Long, list: List<Stroke>, append: Boolean) = runOnUiThread {
        // ink end 发出 → 收到回推 = e2e（收笔那一下 Mac 发的正是追加帧）
        if (tEnd > 0) {
            e2e = (System.currentTimeMillis() - tEnd).toDouble()
            graphView.addE2e(e2e.toFloat())
        }
        // 回推体积的诊断打点（1s 一条）：追加帧的体积与文档大小无关，全量帧才随累计笔迹涨。
        // 若 e2e 还在爬，看这条是哪一种、多大——写字时应当基本都是「追加」且恒定在几 KB。
        val now = System.currentTimeMillis()
        if (now - lastStrokeLogAt >= 1000) {
            lastStrokeLogAt = now
            var pts = 0
            for (s in list) pts += s.pts.size
            Log.i(
                PageCanvasView.TAG,
                "回推 ${if (append) "追加" else "全量"} ${list.size}条/${pts}点 ≈${pts * 12 / 1024}KB " +
                    "ackRel=$ackRel sentRel=${udp?.sentRel ?: 0L} e2e=${e2e.toInt()}ms",
            )
        }
        // 追加只需 ackRel（销账乐观笔）；全量还要配上「本端已发出到哪」才分得清中途快照
        if (append) padView.appendStrokes(list, ackRel)
        else padView.setStrokes(list, ackRel, udp?.sentRel ?: 0L)
        refresh()
    }

    override fun onNack(seqs: List<Long>) {
        udp?.onNack(seqs)
        runOnUiThread { refresh() }
    }

    /**
     * `docs` = Mac 已打开的**窗口会话**列表 + 当前选中的那个（`PROTOCOL.md §4.2`）。
     * 标签页栏就是它的只读镜像：一条广播来了整条重建，本地不猜、不留自己的一份"打开中"状态。
     */
    override fun onDocs(following: Boolean, selected: String, list: List<WireCodec.DocEntry>) = runOnUiThread {
        docs = list
        selectedDoc = selected
        rebuildTabs()
    }

    /**
     * 标签页栏 = 「**当前工作区**那几篇」。`docs` 是跨工作区的全量，不过滤的话几个工作区的文档
     * 会混成一排、点过去工作区凭空换掉（2026-09-06 之前就是这样）。
     *
     * 当前工作区取 **`selected` 那一项的 `ws`**，不取 `library` 广播的 wsName：两条广播的先后
     * 没有保证（同 `layout`/`toc` 那个坑），拿另一条的字段来分组会在切档瞬间错位一拍。
     */
    private fun rebuildTabs() {
        val sel = docs.firstOrNull { it.id == selectedDoc }
        val ws = sel?.ws ?: docs.firstOrNull()?.ws.orEmpty()
        if (sel != null && ws.isNotEmpty()) lastDocInWs[ws] = sel.id
        visDocs = docs.filter { it.ws == ws }
        tabsBar.setWorkspace(ws.ifEmpty { libWs.ifEmpty { "书库" } })
        tabsBar.setTabs(visDocs.map { it.title }, visDocs.indexOfFirst { it.id == selectedDoc })
    }

    /**
     * 切工作区：Mac 上开着的每个工作区一行——`docs` 按 `ws` 一分组就是它，不必再加一条协议。
     * 点一行 = 对那个工作区里**上次待过的那一篇**发 `selectDoc`（没待过就第一篇），Mac 跟着切
     * 激活窗口，`library`/`toc`/`layout` 随后自己回推。
     *
     * 平板**不能新开工作区**（那是 Mac 的窗口级概念），这里只在已经开着的之间切——所以没有
     * 模式1 那个「打开其它 .unrd…」。
     */
    private fun showWorkspaceSwitcher() {
        val curWs = docs.firstOrNull { it.id == selectedDoc }?.ws.orEmpty()
        val groups = docs.filter { it.ws.isNotEmpty() }.groupBy { it.ws }   // groupBy 保出现顺序
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(this).title("切换工作区")
        for ((ws, items) in groups) {
            val isCur = ws == curWs
            list.addView(
                PadPanels.iconRow(
                    this,
                    R.drawable.ic_folder,
                    ws,
                    if (isCur) Ui.accent(this) else Ui.onVariant(this),
                    trailing = wsRowTrailing(items.size, isCur),
                ) {
                    dlg?.dismiss()
                    if (!isCur) {
                        val want = lastDocInWs[ws]?.takeIf { id -> items.any { it.id == id } } ?: items.first().id
                        client?.send(WireCodec.encodeSelectDoc(want))
                    }
                },
            )
        }
        when {
            groups.isEmpty() -> sheet.subtitle("还没收到 Mac 那边的工作区。")
            groups.size == 1 -> sheet.subtitle("Mac 上只开着这一个工作区。")
        }
        sheet.content(list)
        sheet.action("好")
        dlg = sheet.show()
    }

    /** 工作区那一行的右侧：开着几篇 +（当前那个）一个勾 */
    private fun wsRowTrailing(count: Int, current: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            TextView(this@PadActivity).apply {
                text = "$count 篇"
                textSize = 13f
                setTextColor(Ui.onVariant(this@PadActivity))
            },
        )
        if (current) {
            addView(
                ImageView(this@PadActivity).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(Ui.accent(this@PadActivity))
                },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) },
            )
        }
    }

    override fun onLibrary(ws: String, list: List<WireCodec.LibEntry>) = runOnUiThread {
        // 线格式类型 → 中立模型：`shared/` 那边不许认识 WireCodec（依赖方向单向）
        libItems = list.map { LibItem(it.id, it.title, it.open) }
        drawer.setLibrary(ws, libItems)
        libWs = ws
        rebuildTabs()   // 芯片上的名字优先用 `docs` 里那份，这条只是兜底（见 rebuildTabs 注释）
    }

    override fun onToc(docId: String, list: List<WireCodec.TocEntry>) = runOnUiThread {
        drawer.setToc(docId, list.map { TocItem(it.depth, it.page, it.frac, it.label) })
    }

    override fun onBookmarks(docId: String, list: List<WireCodec.BookmarkEntry>) = runOnUiThread {
        // 线上已按「页 → 页内位置 → 建立时刻」有序，这里原样转一层，别再排
        drawer.setBookmarks(docId, list.map { BookmarkItem(it.id, it.page, it.frac, it.title) })
        // 页面上的红缎带（贴页右缘）：与 Mac 那面同形同色同尺寸
        padView.setBookmarkMarks(
            list.map { PageCanvasView.BookmarkMark(it.id, it.page, it.frac, it.title) },
        )
    }

    override fun onNotes(list: List<TextNote>) = runOnUiThread {
        padView.setNotes(list)
    }

    override fun onLayers(active: Int, list: List<Layer>) = runOnUiThread {
        layers = list
        layerIdx = active.coerceIn(0, maxOf(0, list.size - 1))
        refresh()
    }

    override fun onRadial(m: WireCodec.Msg.Radial) = runOnUiThread {
        // 打点：用户报「Mac 上盘出来了、安卓上没出来」。盘走 WS，与 `strokes` 全量镜像同一条有序
        // 通道，镜像大了就会把这一帧压在后面（队头阻塞）。这里记**收到**时刻 + 此刻笔还在不在纸上
        // ——`endPen` 抬笔即无条件收盘，所以「到得太晚、人已抬笔」的表现正是「安卓没显示」。
        // 与 Mac 那条「环形盘呼出 …（下发中）」一减，就是这一帧在路上花的时间。
        Log.i(
            PageCanvasView.TAG,
            "收到 radial open=${m.open} 扇区=${m.items.size} 笔还在纸上=${padView.isPenDown()}",
        )
        padView.setRadial(m)
    }

    override fun onPressRing(m: WireCodec.Msg.PressRing) = runOnUiThread {
        padView.setPressRing(m)
    }

    override fun onEraser(size: Float, mode: Int, ring: Boolean) = runOnUiThread {
        padView.setEraser(size, mode, ring)
    }

    /** Mac 侧（或另一台平板）改了书写锁定：应用但不再回发（同 [onEraser] 那套） */
    override fun onLock(on: Boolean) = runOnUiThread {
        padView.setWritingLockedLocal(on)
        refresh()
    }

    override fun onRelInk(on: Boolean) = runOnUiThread {
        padView.setRelativeInkWidthLocal(on)
        refresh()
    }

    /** 画板模式：Mac 是页边宽度的唯一真源（逐文档），照它布局即可 */
    override fun onCanvas(on: Boolean, margin: Float) = runOnUiThread {
        padView.setCanvas(on, margin)
    }

    override fun onScratchPads(open: Int, list: List<WireCodec.ScratchPadEntry>) = runOnUiThread {
        scratch.applyPads(open, list)   // Mac 是「开着哪张纸」的唯一真源：照做（开/关/换纸）
        refresh()
    }

    override fun onScratchStrokes(ackRel: Long, list: List<Stroke>) = runOnUiThread {
        // 纸上 ink end 发出 → 收到 scratchStrokes 广播 = e2e（与 onStrokes 同一条计时报表）
        if (tEnd > 0) {
            e2e = (System.currentTimeMillis() - tEnd).toDouble()
            graphView.addE2e(e2e.toFloat())
        }
        // ackRel 判据与页内 strokes 完全同款（PadScratch.applyStrokes 里）
        scratch.applyStrokes(ackRel, list, udp?.sentRel ?: 0L)
    }

    /**
     * 收 `boards`（`../PROTOCOL.md §4.8`）：被跟随会话的类型 + 画板列表。
     * - kind=2：整屏是草稿纸画布（笔迹走 scratchpads/scratchStrokes，画板那张纸永远开着），页面视图藏起来；
     * - kind=1：显示「Mac 正在看 Markdown 笔记」，不停在上一篇 PDF 上；
     * - kind=0：一切照旧。
     */
    override fun onBoards(kind: Int, current: String, list: List<WireCodec.BoardEntry>) = runOnUiThread {
        boardKind = kind
        boardCurrent = current
        boards = list
        // boardId 先于 boardMode：切进画板会话时 PadScratch 要拿它判断「手上的页是不是这一篇的」
        scratch.boardId = if (kind == WireCodec.BOARD_KIND_BOARD) current else null
        scratch.boardMode = kind == WireCodec.BOARD_KIND_BOARD
        scratch.boardTitle = list.firstOrNull { it.id == current }?.title
        drawer.setBoards(list.map {
            LibItem(it.id, it.title, open = kind == WireCodec.BOARD_KIND_BOARD && it.id == current)
        })
        applyBoardSurface()
        refresh()
    }

    /** 当前画板上的图（全量镜像；不是画板会话时 Mac 发空表，据此清掉） */
    override fun onBoardImages(list: List<WireCodec.BoardImageEntry>) = runOnUiThread {
        scratch.applyBoardImages(list)
    }

    /** 被跟随画板的页（全量镜像；空表 = 无限画布 / 不是画板会话）：排页、画背景交给草稿纸画布 */
    override fun onBoardPages(w: Float, h: Float, list: List<WireCodec.BoardPageEntry>) = runOnUiThread {
        scratch.applyBoardPages(w, h, IntArray(list.size) { list[it].template })
    }

    /** 画板在 Mac 库里存的位置：刚打开、还没动过就回到那里（「记住上次滚动位置」，见 PadScratch 末尾） */
    override fun onBoardViewport(id: String, x: Float, y: Float, zoom: Float) = runOnUiThread {
        scratch.applyBoardViewport(id, x, y, zoom)
    }

    /**
     * 新建画板：先选模式（无限画布 / 分页 + 页面大小、背景、页数），再发 `boardAdd`——分页带可选尾部，
     * 无限画布发空 payload（老 Mac 也认得）。Mac 建好、开好之后照常回推 boards / boardPages / …
     */
    private fun requestNewBoard() {
        NewBoardSheet.show(this) { spec ->
            client?.send(
                if (spec.paged) WireCodec.encodeBoardAddPaged(spec.w, spec.h, spec.template, spec.count)
                else WireCodec.encodeBoardAdd(),
            )
        }
    }

    /** 按会话类型摆页面视图 / Markdown 空状态 / 顶栏上只对 PDF 有意义的几颗键 */
    private fun applyBoardSurface() {
        val pdf = boardKind == WireCodec.BOARD_KIND_PDF
        // INVISIBLE 而不是 GONE：保留布局与几何，切回 PDF 时不用重新量一遍
        padView.visibility = if (pdf) View.VISIBLE else View.INVISIBLE
        mdEmpty.visibility = if (boardKind == WireCodec.BOARD_KIND_MARKDOWN) View.VISIBLE else View.GONE
        if (!pdf) padView.clearTransient()   // 盘 / 环留在藏起来的页面上没有意义
        // 翻页 / 草稿纸 / 画板模式都是「PDF 页面」上的事，画板与 Markdown 会话里收起来
        for (k in arrayOf("prev", "next", "scratch", "canvas")) bar.setVisible(k, pdf)
    }

    /**
     * 画板笔记列表：点一篇 = `boardOpen`（Mac 在被跟随的窗口里打开/切过去），「新建」= `boardAdd`。
     * 两个都只是请求，Mac 开好之后照常回推 boards / scratchpads / scratchStrokes / boardImages。
     */
    private fun showBoardList() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: AlertDialog? = null
        val sheet = Sheet(this).title(getString(R.string.board_title))
        if (boards.isEmpty()) sheet.subtitle(getString(R.string.board_none))
        for (b in boards) {
            val cur = boardKind == WireCodec.BOARD_KIND_BOARD && b.id == boardCurrent
            list.addView(
                PadPanels.iconRow(
                    this, R.drawable.ic_scratch, b.title,
                    if (cur) Ui.accent(this) else Ui.onSurface(this),
                    trailing = if (!cur) {
                        null
                    } else {
                        ImageView(this).apply {
                            setImageResource(R.drawable.ic_check)
                            imageTintList = ColorStateList.valueOf(Ui.accent(this@PadActivity))
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                        }
                    },
                ) {
                    dlg?.dismiss()
                    if (!cur) client?.send(WireCodec.encodeBoardOpen(b.id))
                },
            )
        }
        if (boards.isNotEmpty()) list.addView(Ui.divider(this))
        list.addView(
            PadPanels.iconRow(this, R.drawable.ic_plus, getString(R.string.board_new)) {
                dlg?.dismiss()
                requestNewBoard()
            },
        )
        sheet.content(list)
        sheet.action(getString(R.string.common_cancel))
        dlg = sheet.show()
    }

    /**
     * 收 noteNew（环形盘 textNote 扇区提交的结果）：在该页内点开文字笔记编辑器（新建态）。
     * 目标页不在视口顶部时先本地滚到锚点处（scrollToPageFrac 是纯本地定位，不上行、书写中不滚），
     * 免得编辑器开一个看不见的锚点；保存走现有 textNote 上行（onOpenNoteEditor 那条路）。
     */
    override fun onNoteNew(page: Long, nx: Float, ny: Float) = runOnUiThread {
        val p = page.toInt()
        if (p !in 0 until padView.pageCountOrZero()) return@runOnUiThread
        if (p != padView.topVisiblePage()) padView.scrollToPageFrac(p, ny)
        onOpenNoteEditor(java.util.UUID.randomUUID().toString(), p, nx, ny, "", true, NOTE_TAP)
    }

    // ---------- PadView.Listener（主线程） ----------

    override fun sendRel(body: ByteArray) {
        udp?.sendRel(body)
    }

    override fun sendUnrel(body: ByteArray) {
        udp?.sendUnrel(body)
    }

    override fun sendCtl(body: ByteArray) {
        client?.send(body)
    }

    override fun sentRelSeq(): Long = udp?.sentRel ?: 0L

    override fun onInkEndSent() {
        tEnd = System.currentTimeMillis()
    }

    override fun onMoveFrame() {
        mvCount++
    }

    override fun onHudChanged() {
        refresh()
    }

    override fun onOpenNoteEditor(
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean, display: Int,
    ) {
        PadPanels.showNoteEditor(
            this, text, isNew, display,
            onSave = { t, d ->
                // 空文本：新建 = 直接取消；已有 = 等同删除（与 Mac「空 upsert 即删除」语义一致）
                if (t.isEmpty()) { if (!isNew) padView.deleteNote(id, page, nx, ny) }
                else padView.upsertNote(id, page, nx, ny, t, d)
            },
            onDelete = { padView.deleteNote(id, page, nx, ny) },
        )
    }

    /** 手指单击图钉 → 请求 Mac 打开那张纸（等 scratchpads 回推才真的开，本地不自作主张） */
    override fun onScratchPinTap(index: Int) {
        client?.send(WireCodec.encodeScratchOpen(index))
    }

    /** 图钉拖动松手 → 发 scratchMove（本地已乐观移动，scratchpads 回推为权威，同 scratchPaper 惯例） */
    override fun onScratchPinMove(index: Int, nx: Float, ny: Float) {
        client?.send(WireCodec.encodeScratchMove(index, nx, ny))
    }
}
