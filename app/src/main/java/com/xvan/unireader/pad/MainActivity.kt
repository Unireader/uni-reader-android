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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator

/**
 * 主界面：全屏 PadView + 顶栏（连接点、延迟、页码、◀▶、模式、切笔、⚙）+ 延迟曲线悬浮。
 * 连接设置弹窗（host/token/扫码/连接 + 延迟曲线开关）：未连接自动弹出，⚙ 随时重开。
 * host/token SharedPreferences 持久化（authOK 才存）；扫码内容为 http://ip:8770/?token=XXXX。
 */
class MainActivity : Activity(), MacClient.Callback, PadView.Listener {

    companion object { const val REQ_CAMERA = 42 }

    private lateinit var dot: View
    private lateinit var latText: TextView
    private lateinit var pageLabel: TextView
    private lateinit var modeBtn: Button
    private lateinit var penBtn: Button
    private lateinit var padView: PadView
    private lateinit var graphView: LatencyGraphView

    private var client: MacClient? = null
    private var udp: UdpSender? = null
    private var fetcher: PageFetcher? = null
    private val handler = Handler(Looper.getMainLooper())
    private var docV = ""

    // —— 量化指标（rtt/e2e/nackRTT/mv-s，照 udp-pad-sim.py refresh）——
    private var rtt = -1.0
    private var e2e = -1.0
    private var tEnd = 0L
    private var mvCount = 0
    private var mvRate = 0

    // —— 连接弹窗（扫码结果要回写，故持引用） ——
    private var connDialog: AlertDialog? = null
    private var dialogHost: EditText? = null
    private var dialogToken: EditText? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        handler.postDelayed(pinger, 2000)
        handler.postDelayed(sampler, 1000)
        showConnDialog()   // 未连接自动弹出
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        udp?.close()       // 内含 BYE
        client?.close()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val barH = dp(48)

        padView = PadView(this).apply { listener = this@MainActivity }

        // 连接状态点（灰=未连，绿=已认证）
        dot = View(this)
        // 顶栏
        latText = TextView(this).apply {
            text = "— ms"; textSize = 12f; setTextColor(0xFF8B949E.toInt())
        }
        pageLabel = TextView(this).apply {
            text = "— / —"; textSize = 13f; setTextColor(Color.WHITE)
        }
        val prevBtn = Button(this).apply { text = "◀" }
        val nextBtn = Button(this).apply { text = "▶" }
        modeBtn = Button(this)
        penBtn = Button(this)
        val settingsBtn = Button(this).apply { text = "⚙" }
        refreshToolButtons()

        val topbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true   // 挡住触摸穿透到 PadView（顶栏区域不算画布）
            setBackgroundColor(0xE6161B22.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
            addView(latText)
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pageLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(prevBtn)
            addView(nextBtn)
            addView(modeBtn)
            addView(penBtn)
            addView(settingsBtn)
        }

        graphView = LatencyGraphView(this)

        val root = FrameLayout(this).apply {
            addView(padView, FrameLayout.LayoutParams(-1, -1))
            addView(topbar, FrameLayout.LayoutParams(-1, barH, Gravity.TOP))
            addView(graphView, FrameLayout.LayoutParams(dp(200), dp(90), Gravity.TOP or Gravity.END).apply {
                topMargin = barH + dp(8); marginEnd = dp(8)
            })
        }
        setContentView(root)
        padView.setBarHeight(barH.toFloat())
        setDot(false)
        applyGraphVisibility()

        prevBtn.setOnClickListener { padView.turn(prev = true) }
        nextBtn.setOnClickListener { padView.turn(prev = false) }
        modeBtn.setOnClickListener { padView.cycleMode() }
        penBtn.setOnClickListener { padView.cyclePen() }
        settingsBtn.setOnClickListener { showConnDialog() }
    }

    private fun setDot(on: Boolean) {
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) 0xFF3FB950.toInt() else 0xFFF85149.toInt())
        }
    }

    private fun refreshToolButtons() {
        modeBtn.text = padView.modeLabel()
        penBtn.text = "笔:${padView.penLabel()}"
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
                    latText.text = "host/token 不能为空"
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
            latText.text = "相机权限被拒，无法扫码"
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
                latText.text = "二维码内容无法识别"
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun connect(host: String, token: String) {
        client?.close()
        udp?.close()
        udp = UdpSender(host)
        fetcher = PageFetcher(host)
        docV = ""
        client = MacClient(host, token, this)
        setDot(false)
        latText.text = "连接中…"
    }

    // ---------- 顶栏状态 ----------

    private fun refresh() {
        val u = udp
        latText.text = "rtt %.0f e2e %.0f nackRTT %.0f mv/s %d nack %d resend %d".format(
            rtt, e2e, u?.nackRttMs ?: -1.0, mvRate, u?.nacks ?: 0, u?.resends ?: 0,
        )
        pageLabel.text = "${padView.hudPage()}  ${padView.hudZoom()}"
        refreshToolButtons()
    }

    // 2s WS 心跳（ping 带当前时刻 f64，pong 回显算 rtt）
    private val pinger = object : Runnable {
        override fun run() {
            client?.send(WireCodec.encodePing(System.currentTimeMillis().toDouble()))
            handler.postDelayed(this, 2000)
        }
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
        // 连接即同步当前工具状态给 Mac（capture.html 同款）
        client?.send(WireCodec.encodeMode(padView.mode))
        client?.send(WireCodec.encodePen(padView.penIndex))
        // 连接成功才持久化 host/token（下次启动预填）
        getSharedPreferences("conn", MODE_PRIVATE).edit()
            .putString("host", dialogHost?.text?.toString()?.trim() ?: "")
            .putString("token", dialogToken?.text?.toString()?.trim() ?: "")
            .apply()
        connDialog?.dismiss()
        setDot(true)
        refresh()
    }

    override fun onAuthFail() = runOnUiThread {
        latText.text = "authFail：token 不对"
        showConnDialog()
    }

    override fun onPong(t: Double) = runOnUiThread {
        rtt = System.currentTimeMillis() - t
        graphView.addRtt(rtt.toFloat())
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

    override fun onPens(active: Int, list: List<WireCodec.Pen>) = runOnUiThread {
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

    override fun onStrokes(list: List<WireCodec.Stroke>) = runOnUiThread {
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

    override fun onError(msg: String) = runOnUiThread {
        latText.text = msg
        setDot(false)
    }

    // ---------- PadView.Listener（主线程） ----------

    override fun sendRel(body: ByteArray) {
        udp?.sendRel(body)
    }

    override fun sendUnrel(body: ByteArray) {
        udp?.sendUnrel(body)
    }

    override fun onInkEndSent() {
        tEnd = System.currentTimeMillis()
    }

    override fun onMoveFrame() {
        mvCount++
    }

    override fun onToolChanged() {
        // 本地切模式/切笔 → 同步 WS mode+pen（capture.html cycleMode/cyclePen 同款）
        client?.send(WireCodec.encodeMode(padView.mode))
        client?.send(WireCodec.encodePen(padView.penIndex))
        refresh()
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
}
