package com.xvan.unireader.pad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.MODE_NOTE
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextNote
import com.xvan.unireader.shared.brushName

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
    private lateinit var pageLabel: TextView
    private lateinit var modeBtn: Button
    private lateinit var penBtn: Button
    private lateinit var nightBtn: Button
    private lateinit var noteBtn: Button
    private lateinit var rulerBtn: Button
    private lateinit var eyeBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var penStat: TextView
    private lateinit var layerStat: TextView
    private lateinit var padView: PadView
    private lateinit var graphView: LatencyGraphView
    private lateinit var topbar: LinearLayout
    private lateinit var showBarBtn: Button
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
        if (hasFocus) enterImmersive()   // 弹窗/切回前台后系统栏会回来，重新收掉
    }

    /** 收起/展开顶栏（同网页 hideBar/showBar）：收起后右上角浮一枚小按钮，画布拿到整屏 */
    private fun setBarHidden(hidden: Boolean) {
        topbar.visibility = if (hidden) View.GONE else View.VISIBLE
        showBarBtn.visibility = if (hidden) View.VISIBLE else View.GONE
        padView.setBarHeight(if (hidden) 0f else barHeightPx.toFloat())
    }

    private fun buildUi() {
        val barH = dp(48)
        barHeightPx = barH
        enterImmersive()

        padView = PadView(this).apply { listener = this@PadActivity }

        dot = View(this)   // 连接状态点（绿=已认证）
        latText = TextView(this).apply {
            text = "— ms"; textSize = 12f; setTextColor(0xFF8B949E.toInt())
        }
        pageLabel = TextView(this).apply {
            text = "— / —"; textSize = 13f; setTextColor(Color.WHITE)
            setOnClickListener {
                PadPanels.showGotoPage(this@PadActivity, padView.pageCountOrZero()) { padView.gotoPage(it) }
            }
        }
        val docsBtn = Button(this).apply {
            text = "文档"
            setOnClickListener {
                PadPanels.showDocsPicker(this@PadActivity, docs, docSelected, docFollowing) {
                    client?.send(WireCodec.encodeSelectDoc(it))
                }
            }
        }
        val prevBtn = Button(this).apply { text = "◀"; setOnClickListener { padView.turn(prev = true) } }
        val nextBtn = Button(this).apply { text = "▶"; setOnClickListener { padView.turn(prev = false) } }
        modeBtn = Button(this).apply { setOnClickListener { padView.cycleMode() } }
        penBtn = Button(this).apply { setOnClickListener { padView.cyclePen() } }
        nightBtn = Button(this).apply { setOnClickListener { padView.toggleNight() } }
        noteBtn = Button(this).apply { setOnClickListener { padView.toggleNoteMode() } }
        rulerBtn = Button(this).apply { setOnClickListener { padView.toggleRuler() } }
        eyeBtn = Button(this).apply { setOnClickListener { padView.toggleShowPage() } }
        lockBtn = Button(this).apply { setOnClickListener { padView.toggleZoomLock() } }
        val settingsBtn = Button(this).apply { text = "⚙"; setOnClickListener { showConnDialog() } }
        val hideBarBtn = Button(this).apply { text = "⌃"; setOnClickListener { setBarHidden(true) } }
        showBarBtn = Button(this).apply {
            text = "⌄"
            visibility = View.GONE
            setOnClickListener { setBarHidden(false) }
        }

        // 按钮多，横向可滚（同网页顶栏 overflow-x:auto）
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prevBtn); addView(nextBtn)
            addView(modeBtn); addView(penBtn)
            addView(nightBtn); addView(noteBtn); addView(rulerBtn)
            addView(eyeBtn); addView(lockBtn); addView(settingsBtn); addView(hideBarBtn)
        }
        val btnScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(btnRow)
        }

        topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // 挡住触摸穿透到 PadView（顶栏区域不算画布）
            setBackgroundColor(0xE6161B22.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(latText)
            addView(docsBtn, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(Space(this@PadActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pageLabel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(btnScroll, LinearLayout.LayoutParams(-2, -1))
        }

        // 左下状态胶囊：笔/橡皮 与 图层（对应网页 PenStat / LayerStat）
        penStat = capsule().apply {
            setOnClickListener {
                PadPanels.showPenPanel(this@PadActivity, padView, ::schedulePenset, ::scheduleEraser)
            }
        }
        layerStat = capsule().apply {
            setOnClickListener {
                PadPanels.showLayerPanel(
                    this@PadActivity, layers, layerIdx,
                    onSelect = { client?.send(WireCodec.encodeLayerSelect(it)) },
                    onToggleVisible = { i, v -> client?.send(WireCodec.encodeLayerVisible(i, v)) },
                    onAdd = { client?.send(WireCodec.encodeLayerAdd()) },
                )
            }
        }
        val capsules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(layerStat, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(penStat)
        }

        graphView = LatencyGraphView(this)

        val root = FrameLayout(this).apply {
            addView(padView, FrameLayout.LayoutParams(-1, -1))
            addView(topbar, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
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
                    topMargin = barH + dp(8); marginEnd = dp(8)
                },
            )
        }
        setContentView(root)
        padView.setBarHeight(barH.toFloat())
        setDot(false)
        applyGraphVisibility()
        refresh()
    }

    private fun capsule(): TextView = TextView(this).apply {
        textSize = 13f
        setTextColor(0xFFE6EDF3.toInt())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(0xD9161B22.toInt())
            setStroke(dp(1), 0xFF30363D.toInt())
        }
    }

    private fun setDot(on: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        }
    }

    /** 侧键：PageUp 切模式 / PageDown 切笔 / Esc 清框选（同网页 keydown） */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_UP -> { padView.cycleMode(); return true }
                KeyEvent.KEYCODE_PAGE_DOWN -> { padView.cyclePen(); return true }
                KeyEvent.KEYCODE_ESCAPE -> { padView.clearLasso(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
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

    private fun showConnDialog() {
        val prefs = getSharedPreferences("conn", MODE_PRIVATE)
        val hostEdit = EditText(this).apply {
            hint = "Mac IP（如 192.168.1.5）"; setSingleLine()
            setText(prefs.getString("host", ""))
        }
        val tokenEdit = EditText(this).apply {
            hint = "token（面板 URL 里的）"; setSingleLine()
            setText(prefs.getString("token", ""))
        }
        val scanBtn = Button(this).apply { text = "扫码连接" }
        val graphCheck = CheckBox(this).apply {
            text = "显示延迟曲线"
            isChecked = prefs.getBoolean("showGraph", true)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(hostEdit)
            addView(tokenEdit)
            addView(scanBtn)
            addView(graphCheck)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("连接 Mac")
            .setView(form)
            .setPositiveButton("连接", null)   // 拦截：失败不关窗
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostEdit.text.toString().trim()
                val token = tokenEdit.text.toString().trim()
                if (host.isEmpty() || token.isEmpty()) {
                    statusMsg = "host/token 不能为空"
                    refresh()
                    return@setOnClickListener
                }
                connect(host, token)
                dlg.dismiss()
            }
        }
        scanBtn.setOnClickListener { startScan() }
        graphCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("showGraph", checked).apply()
            applyGraphVisibility()
        }
        dialogHost = hostEdit
        dialogToken = tokenEdit
        connDialog = dlg
        dlg.setOnDismissListener { connDialog = null }
        dlg.show()
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

    /**
     * `refresh()` 挂在 `onHudChanged` 上，**滚动/缩放的每一帧都会调**——所以一律走
     * `setTextIfChanged`：文本没变就不碰 TextView。直接 setText 即便内容相同也会触发
     * measure/layout，十来个控件乘 60fps 就是白烧的一帧预算（同款坑见 Mac 端逐帧 @Published）。
     */
    private fun TextView.setTextIfChanged(s: String) {
        if (text?.toString() != s) text = s
    }

    private fun refresh() {
        val u = udp
        latText.setTextIfChanged(
            if (statusMsg.isNotEmpty()) statusMsg
            else "rtt %.0f e2e %.0f nackRTT %.0f mv/s %d nack %d resend %d".format(
                rtt, e2e, u?.nackRttMs ?: -1.0, mvRate, u?.nacks ?: 0, u?.resends ?: 0,
            )
        )
        pageLabel.setTextIfChanged("${padView.hudPage()}  ${padView.hudZoom()}")
        modeBtn.setTextIfChanged(padView.modeLabel())
        penBtn.setTextIfChanged("笔:${padView.penLabel()}")
        nightBtn.setTextIfChanged(if (padView.night) "日间" else "夜间")
        noteBtn.setTextIfChanged(if (padView.noteMode) "文字✓" else "文字")
        rulerBtn.setTextIfChanged(if (padView.rulerOn) "尺子✓" else "尺子")
        eyeBtn.setTextIfChanged(if (padView.showPage) "页图" else "页图✕")
        lockBtn.setTextIfChanged(if (padView.zoomLocked) "🔒" else "🔓")

        // 笔胶囊：笔记模式显示当前笔（类型 · 粗细），其余模式显示模式名（同网页 PenStat）
        val pen = padView.curPenOrNull()
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

    override fun onStrokes(list: List<Stroke>) = runOnUiThread {
        // ink end 发出 → 收到 strokes 广播 = e2e
        if (tEnd > 0) {
            e2e = (System.currentTimeMillis() - tEnd).toDouble()
            graphView.addE2e(e2e.toFloat())
        }
        padView.setStrokes(list)
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

    override fun requestImage(page: Int) {
        val v = docV
        fetcher?.fetch(page, v) { bmp ->
            runOnUiThread {
                if (v == docV) padView.setPageImage(page, bmp)   // 换文档后的在途回调丢弃
            }
        }
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
