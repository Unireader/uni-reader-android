package com.xvan.unireader.pad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import com.xvan.unireader.R
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.MODE_PAGE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.PageImageSource
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.TextNote
import com.xvan.unireader.shared.TopBar
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
    private val handler = Handler(Looper.getMainLooper())
    private var docV = ""

    // —— 连接参数（authOK 后持久化；重连时不依赖弹窗还在不在） ——
    private var connHost = ""
    private var connToken = ""

    // —— Mac 下发的列表状态（面板消费） ——
    private var docs = listOf<WireCodec.DocEntry>()
    private var docSelected = ""
    private var docFollowing = true
    private var layers = listOf<Layer>()
    private var layerIdx = 0

    // —— 量化指标（rtt/e2e/nackRTT/mv-s，照 udp-pad-sim.py refresh）——
    private var rtt = -1.0
    private var e2e = -1.0
    private var tEnd = 0L
    private var mvCount = 0
    private var mvRate = 0
    private var statusMsg = ""

    // —— 连接弹窗（扫码结果要回写，故持引用） ——
    private var connDialog: AlertDialog? = null
    private var dialogHost: EditText? = null
    private var dialogToken: EditText? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        handler.postDelayed(sampler, 1000)
        showConnDialog()   // 未连接自动弹出
    }

    override fun onResume() {
        super.onResume()
        client?.connectNow()   // 回前台：断了立刻重连，不等退避计时器
    }


    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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

    /** 收起/展开顶栏（同网页 hideBar/showBar）：收起后右上角浮一枚小按钮，画布拿到整屏 */
    private fun setBarHidden(hidden: Boolean) {
        topbar.visibility = if (hidden) View.GONE else View.VISIBLE
        showBarBtn.visibility = if (hidden) View.VISIBLE else View.GONE
        padView.setBarHeight(if (hidden) 0f else barHeightPx.toFloat())
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
            // 页图源（PageCanvasView 的注入口）：模式2 从 Mac 取整页 PNG，widthPx 由 Mac 定、这里忽略。
            // 换文档后的在途回调按 null 丢弃——旧 v 的页图贴到新文档上就是花屏。
            imageSource = object : PageImageSource {
                override fun request(page: Int, widthPx: Int, cb: (Bitmap?) -> Unit) {
                    val v = docV
                    val f = fetcher ?: run { cb(null); return }
                    f.fetch(page, v) { bmp -> cb(if (v == docV) bmp else null) }
                }

                override fun clear() {
                    fetcher?.clear()
                }
            }
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
            // 旋转会重建 Activity（manifest 没声明 configChanges），所以这里判一次就够。
            if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                maxWidth = dp(120)
            }
        }
        // 顶栏与模式1 是同一份（shared/TopBar）：键的顺序刻意也一样，只多「文档 / 连接设置 /
        // 收起顶栏」这三件模式2 独有的事（真源在 Mac，所以还有个连接态小圆点）。
        bar = TopBar(this).apply {
            icon("docs", R.drawable.ic_doc, "选择文档") {
                PadDocsPicker.show(this@PadActivity, docs, docSelected, docFollowing) {
                    client?.send(WireCodec.encodeSelectDoc(it))
                }
            }
            gap()
            icon("prev", R.drawable.ic_chevron_left, "上一页", spillFirst = true) { padView.turn(prev = true) }
            icon("next", R.drawable.ic_chevron_right, "下一页", spillFirst = true) { padView.turn(prev = false) }
            gap()
            icon("mode", TopBar.modeIcon(MODE_NOTE), "切换模式") { padView.cycleMode() }
            icon("pen", R.drawable.ic_nib, "切换笔") { padView.cyclePen() }
            icon("ruler", R.drawable.ic_ruler, "尺子") { padView.toggleRuler() }
            icon("text", R.drawable.ic_text, "文字笔记") { padView.toggleNoteMode() }
            addTail(dot, 0)
            addTail(latText, 1)
            pageLabel.setOnClickListener {
                PadPanels.showGotoPage(this@PadActivity, padView.pageCountOrZero()) { padView.gotoPage(it) }
            }
            overflowItems = {
                listOf(
                    TopBar.MenuItem("夜间模式", padView.night) { padView.toggleNight() },
                    TopBar.MenuItem("显示页面图", padView.showPage) { padView.toggleShowPage() },
                    TopBar.MenuItem("锁定缩放", padView.zoomLocked) { padView.toggleZoomLock() },
                    TopBar.MenuItem("图层…") { showLayerPanel() },
                    TopBar.MenuItem("跳到第…页") {
                        PadPanels.showGotoPage(this@PadActivity, padView.pageCountOrZero()) { padView.gotoPage(it) }
                    },
                    TopBar.MenuItem("收起顶栏") { setBarHidden(true) },
                    TopBar.MenuItem("连接设置…") { showConnDialog() },
                )
            }
        }
        (dot.layoutParams as LinearLayout.LayoutParams).apply {
            width = dp(8); height = dp(8); marginEnd = dp(8)
        }
        topbar = bar.view
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

        val root = FrameLayout(this).apply {
            addView(padView, FrameLayout.LayoutParams(-1, -1))
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
                    topMargin = bar.height() + dp(8); marginEnd = dp(8)
                },
            )
        }
        setContentView(root)
        barHeightPx = bar.height()
        padView.setBarHeight(barHeightPx.toFloat())
        setDot(false)
        applyGraphVisibility()
        refresh()
    }

    private fun setDot(on: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        }
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
                "书写中=${padView.isPenDown()} 生效=$fresh",
        )
        if (!fresh) return true
        lastHandledKeyDown = event.downTime
        when (event.keyCode) {
            KeyEvent.KEYCODE_PAGE_UP -> padView.cycleMode()
            KeyEvent.KEYCODE_PAGE_DOWN -> padView.cyclePen()
            KeyEvent.KEYCODE_ESCAPE -> padView.clearLasso()
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
     */
    private fun showConnDialog() {
        val prefs = getSharedPreferences("conn", MODE_PRIVATE)
        val hostEdit = PadPanels.inputBox(this, "Mac IP（如 192.168.1.5）").apply {
            setText(prefs.getString("host", ""))
        }
        val tokenEdit = PadPanels.inputBox(this, "token（面板 URL 里的）").apply {
            setText(prefs.getString("token", ""))
        }
        val err = Ui.body(this, "").apply {
            setTextColor(Ui.col(this@PadActivity, R.color.danger))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hostEdit)
            addView(tokenEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(err)
            addView(
                Ui.button(this@PadActivity, "扫码连接") { startScan() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) },
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
        }
        val dlg = Sheet(this)
            .title("连接 Mac")
            .content(form)
            .action("取消")
            .action("连接", primary = true, dismiss = false) { d ->
                val host = hostEdit.text.toString().trim()
                val token = tokenEdit.text.toString().trim()
                if (host.isEmpty() || token.isEmpty()) {
                    err.text = "host 和 token 都要填"
                    err.visibility = View.VISIBLE
                    return@action
                }
                connect(host, token)
                d.dismiss()
            }
            .show()
        dialogHost = hostEdit
        dialogToken = tokenEdit
        connDialog = dlg
        dlg.setOnDismissListener { connDialog = null }
    }

    private fun applyGraphVisibility() {
        val show = getSharedPreferences("conn", MODE_PRIVATE).getBoolean("showGraph", true)
        graphView.visibility = if (show) View.VISIBLE else View.GONE
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
        fetcher = PageFetcher(host)
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
        bar.setPageLabel(padView.hudPage(), padView.hudZoom())
        bar.setIcon("mode", TopBar.modeIcon(padView.mode))
        bar.setActive("mode", padView.mode != MODE_PAGE)
        bar.setActive("ruler", padView.rulerOn)
        bar.setActive("text", padView.noteMode)
        bar.setEnabled("pen", padView.mode == MODE_NOTE)

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
        val newV = v.ifEmpty { docId }
        if (newV != docV) {
            docV = newV
            fetcher?.clear()   // 换文档：旧 v 页图全部作废
        }
        padView.setLayout(docId, v, count.toInt(), pages)
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

    override fun onStrokes(ackRel: Long, list: List<Stroke>) = runOnUiThread {
        // ink end 发出 → 收到 strokes 广播 = e2e
        if (tEnd > 0) {
            e2e = (System.currentTimeMillis() - tEnd).toDouble()
            graphView.addE2e(e2e.toFloat())
        }
        // ackRel 要配上「本端已发出到哪」才有意义：两者一比就知道这份全量快照含不含我刚发的输入
        padView.setStrokes(list, ackRel, udp?.sentRel ?: 0L)
        refresh()
    }

    override fun onNack(seqs: List<Long>) {
        udp?.onNack(seqs)
        runOnUiThread { refresh() }
    }

    override fun onDocs(following: Boolean, selected: String, list: List<WireCodec.DocEntry>) = runOnUiThread {
        docFollowing = following
        docSelected = selected
        docs = list
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
        padView.setRadial(m)
    }

    override fun onPressRing(m: WireCodec.Msg.PressRing) = runOnUiThread {
        padView.setPressRing(m)
    }

    override fun onEraser(size: Float, mode: Int, ring: Boolean) = runOnUiThread {
        padView.setEraser(size, mode, ring)
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
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean,
    ) {
        PadPanels.showNoteEditor(
            this, text, isNew,
            onSave = { t ->
                // 空文本：新建 = 直接取消；已有 = 等同删除（与 Mac「空 upsert 即删除」语义一致）
                if (t.isEmpty()) { if (!isNew) padView.deleteNote(id, page, nx, ny) }
                else padView.upsertNote(id, page, nx, ny, t)
            },
            onDelete = { padView.deleteNote(id, page, nx, ny) },
        )
    }
}
