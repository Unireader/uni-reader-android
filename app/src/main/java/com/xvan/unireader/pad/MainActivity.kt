package com.xvan.unireader.pad

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 主界面：连接表单（host/token 手输 + 连接按钮）+ 工具条（翻页◀▶、橡皮、切笔）
 * + 状态栏 + PadView。生命周期：onDestroy 发 BYE 并释放 WS/UDP。
 */
class MainActivity : Activity(), MacClient.Callback, PadView.Listener {

    private lateinit var hostEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var eraseBtn: Button
    private lateinit var penBtn: Button
    private lateinit var statusBar: TextView
    private lateinit var padView: PadView

    private var client: MacClient? = null
    private var udp: UdpSender? = null
    private var fetcher: PageFetcher? = null
    private val handler = Handler(Looper.getMainLooper())

    // —— 量化指标（状态栏四项 + 计数，照 udp-pad-sim.py refresh）——
    private var rtt = -1.0
    private var e2e = -1.0
    private var tEnd = 0L          // 最近 ink end 发出时刻（e2e 起点）
    private var mvCount = 0        // 1s 窗口内 move 帧数
    private var mvRate = 0
    private var pageIndex = 0L
    private var pageCount = 0L
    private var lastFetchedPage = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        // 预填上次连接成功的 host/token
        val prefs = getSharedPreferences("conn", MODE_PRIVATE)
        hostEdit.setText(prefs.getString("host", ""))
        tokenEdit.setText(prefs.getString("token", ""))
        handler.postDelayed(pinger, 2000)
        handler.postDelayed(sampler, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        udp?.close()       // 内含 BYE
        client?.close()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi() {
        hostEdit = EditText(this).apply { hint = "Mac IP（如 192.168.1.5）"; setSingleLine() }
        tokenEdit = EditText(this).apply { hint = "token（面板 URL 里的）"; setSingleLine() }
        val connectBtn = Button(this).apply { text = "连接" }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
            addView(tokenEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(connectBtn, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val prevBtn = Button(this).apply { text = "◀" }
        val nextBtn = Button(this).apply { text = "▶" }
        eraseBtn = Button(this)
        penBtn = Button(this)
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(prevBtn)
            addView(nextBtn)
            addView(eraseBtn)
            addView(penBtn)
        }

        statusBar = TextView(this).apply { text = "未连接"; setPadding(16, 8, 16, 8) }
        padView = PadView(this).apply { listener = this@MainActivity }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(form)
            addView(tools)
            addView(statusBar)
            addView(padView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        connectBtn.setOnClickListener { connect() }
        prevBtn.setOnClickListener { client?.send(WireCodec.encodePageTurn(WireCodec.DIR_PREV)) }
        nextBtn.setOnClickListener { client?.send(WireCodec.encodePageTurn(WireCodec.DIR_NEXT)) }
        eraseBtn.setOnClickListener { padView.toggleErase(); refreshToolButtons(); refresh() }
        penBtn.setOnClickListener { padView.cyclePen(); refreshToolButtons(); refresh() }
        refreshToolButtons()
    }

    private fun refreshToolButtons() {
        eraseBtn.text = if (padView.eraseMode) "橡皮✓" else "橡皮"
        penBtn.text = "切笔(${PadView.PEN_NAMES[padView.penIndex]})"
    }

    private fun connect() {
        val host = hostEdit.text.toString().trim()
        val token = tokenEdit.text.toString().trim()
        if (host.isEmpty() || token.isEmpty()) {
            statusBar.text = "host/token 不能为空"
            return
        }
        client?.close()
        udp?.close()
        udp = UdpSender(host)
        fetcher = PageFetcher(host)
        client = MacClient(host, token, this)
        statusBar.text = "连接中…"
    }

    // ---------- 状态栏 ----------

    private fun refresh() {
        val u = udp
        val pageStr = if (pageCount > 0) "${pageIndex + 1}/$pageCount" else "-/-"
        statusBar.text = "%s page %s  rtt %.0fms  e2e %.0fms  nackRTT %.0fms  mv/s %d  nack %d  resend %d  [%s]"
            .format(
                if (u?.udpReady == true) "UDP✓" else "…",
                pageStr, rtt, e2e, u?.nackRttMs ?: -1.0,
                mvRate, u?.nacks ?: 0, u?.resends ?: 0, padView.toolName,
            )
    }

    // 2s WS 心跳（ping 带当前时刻 f64，pong 回显算 rtt）
    private val pinger = object : Runnable {
        override fun run() {
            client?.send(WireCodec.encodePing(System.currentTimeMillis().toDouble()))
            handler.postDelayed(this, 2000)
        }
    }

    // 1s 采样窗口：mv/s
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
        // 连接成功才持久化 host/token（下次启动预填）
        getSharedPreferences("conn", MODE_PRIVATE).edit()
            .putString("host", hostEdit.text.toString().trim())
            .putString("token", tokenEdit.text.toString().trim())
            .apply()
        refresh()
    }

    override fun onAuthFail() = runOnUiThread {
        statusBar.text = "authFail：token 不对（每次启动重新生成，去面板复制最新的）"
    }

    override fun onPong(t: Double) = runOnUiThread {
        rtt = System.currentTimeMillis() - t
        refresh()
    }

    override fun onPage(index: Long, count: Long, w: Float, h: Float) = runOnUiThread {
        pageIndex = index
        pageCount = count
        padView.setPage(index, count)
        if (index != lastFetchedPage) {
            lastFetchedPage = index
            fetcher?.fetch(index.toInt()) { bmp ->
                runOnUiThread { padView.setPageBitmap(bmp) }
            }
        }
        refresh()
    }

    override fun onNack(seqs: List<Long>) {
        udp?.onNack(seqs)
        runOnUiThread { refresh() }
    }

    override fun onStrokes() = runOnUiThread {
        // ink end 发出 → 收到 strokes 广播 = e2e
        if (tEnd > 0) e2e = (System.currentTimeMillis() - tEnd).toDouble()
        refresh()
    }

    override fun onError(msg: String) = runOnUiThread {
        statusBar.text = msg
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

    override fun onToolChanged() {}
}
