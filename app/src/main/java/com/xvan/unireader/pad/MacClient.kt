package com.xvan.unireader.pad

import android.os.Handler
import android.os.Looper
import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextNote
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp WebSocket 可靠通道（ws://host:8771，二进制帧 = ByteString）。
 * 连上立刻发 auth(token)；收帧经 WireCodec 解码后路由给回调。
 *
 * 自愈机制照搬 `web/src/lib/ws.ts`（平板放桌上一整天，断了必须自己回来）：
 * - **自动重连**：断开/失败后 1.5s 起步、翻倍退避封顶 10s；authOK 后重置退避。
 * - **心跳看门狗**：1s 一发 ping；5s 收不到 pong 就判定半开连接（锁屏/切网/Mac 睡眠后
 *   onClosed 迟迟不触发）主动 close 再走重连。
 * 重连后 Mac 会补发全量状态（layout/strokes/pens/layers/notes），调用方无需自己补。
 *
 * 线程：解码在 OkHttp 后台线程（不占主线程），**路由与全部内部状态一律 post 到主线程**——
 * ws/retry/ping 三组状态被两条线程读写过一次就会出玄学（重连打架、心跳双开），统一到主线程最省心。
 * 故回调也都在主线程。旧 `page` 消息在方案 B 下忽略（布局由 layout 驱动）。
 */
class MacClient(
    private val host: String,
    private val token: String,
    private val cb: Callback,
) {

    interface Callback {
        fun onAuthOK(session: Long, udpPort: Int)
        fun onAuthFail()
        /** 一轮 ping/pong 测得的 rtt（ms） */
        fun onRtt(ms: Double)
        fun onLayout(docId: String, v: String, count: Long, pages: List<Pair<Float, Float>>)
        fun onViewport(page: Long, frac: Float, seq: Long, force: Boolean)
        fun onPens(active: Int, list: List<Pen>)
        fun onPenSel(index: Int)
        fun onModeSel(mode: Int)
        fun onInkCancel()
        /**
         * @param ackRel Mac 已**应用**到的本端 REL seq（PROTOCOL.md §4.2），0 = 没建 UDP 会话
         * @param append true = 这一份是**追加**（`strokesAppend` 0x4C，Mac 只在收笔那一处发），
         *   false = 整表替换（`strokes` 0x36，擦除/框选/图层/切档/新接入都走它）
         */
        fun onStrokes(ackRel: Long, list: List<Stroke>, append: Boolean)
        fun onNack(seqs: List<Long>)
        fun onDocs(following: Boolean, selected: String, list: List<WireCodec.DocEntry>)
        fun onLibrary(ws: String, list: List<WireCodec.LibEntry>)
        fun onToc(docId: String, list: List<WireCodec.TocEntry>)
        fun onBookmarks(docId: String, list: List<WireCodec.BookmarkEntry>)
        fun onNotes(list: List<TextNote>)
        fun onLayers(active: Int, list: List<Layer>)
        fun onRadial(m: WireCodec.Msg.Radial)
        fun onPressRing(m: WireCodec.Msg.PressRing)
        fun onEraser(size: Float, mode: Int, ring: Boolean)
        /** 画板模式（../PROTOCOL.md `canvas`）：页面两侧的空白也可书写；margin = 每侧页边 ÷ 页宽 */
        fun onCanvas(on: Boolean, margin: Float)
        /** 草稿纸列表全量镜像（Mac 唯一真源）：open = 当前打开 list 里第几张，-1 = 没开 */
        fun onScratchPads(open: Int, list: List<WireCodec.ScratchPadEntry>)
        /** Mac 通知在该页的页内点开文字笔记编辑器（新建态；环形盘 textNote 扇区提交的结果） */
        fun onNoteNew(page: Long, nx: Float, ny: Float)
        /** 当前打开那张纸的全量笔迹镜像（无 page 字段，pts 是画布坐标）；ackRel 语义同 onStrokes */
        fun onScratchStrokes(ackRel: Long, list: List<Stroke>)
        /** 画板笔记列表 + 被跟随会话的类型（kind 0=PDF 1=Markdown 2=画板，`../PROTOCOL.md §4.8`） */
        fun onBoards(kind: Int, current: String, list: List<WireCodec.BoardEntry>)
        /** 当前画板上的图（不是画板会话时是空表） */
        fun onBoardImages(list: List<WireCodec.BoardImageEntry>)
        /** 被跟随画板的页（全量镜像；空表 = 不是分页画板，`../PROTOCOL.md §4.8` 分页画板） */
        fun onBoardPages(w: Float, h: Float, list: List<WireCodec.BoardPageEntry>)
        /** 连接断开（含自动重连中的每一次失败）；msg 供顶栏显示 */
        fun onDisconnected(msg: String)
    }

    companion object {
        const val RETRY_MIN = 1500L
        const val RETRY_MAX = 10_000L
        const val PING_MS = 1000L
        const val PONG_TIMEOUT_MS = 5000L
    }

    private val client = OkHttpClient()
    private val handler = Handler(Looper.getMainLooper())

    // 以下全部只在主线程读写（`send`/`isConnected` 也只从主线程调用）
    private var ws: WebSocket? = null
    @Volatile private var closed = false          // 调用方主动 close()，不再重连
    private var retryDelay = RETRY_MIN
    private var retryScheduled = false
    private var pingRunning = false
    private var lastPong = 0L

    init {
        connect()
    }

    private fun connect() {
        if (closed) return
        val req = Request.Builder().url("ws://$host:8771").build()
        ws = client.newWebSocket(req, Listener())
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(WireCodec.encodeAuth(token).toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // 解码留在后台线程；路由与状态改动一律回主线程（并丢弃旧实例的迟到事件）
            val m = WireCodec.decode(bytes.toByteArray()) ?: return
            handler.post { if (webSocket === ws) route(m) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = t.message ?: t.javaClass.simpleName
            handler.post { if (webSocket === ws) dropped("连接失败：$why") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handler.post { if (webSocket === ws) dropped("已断开 ($code)") }
        }
    }

    private fun route(m: WireCodec.Msg) {
        when (m) {
            is WireCodec.Msg.AuthOK -> {
                retryDelay = RETRY_MIN
                startPing()
                cb.onAuthOK(m.session, m.udpPort)
            }
            WireCodec.Msg.AuthFail -> cb.onAuthFail()
            is WireCodec.Msg.Pong -> {
                lastPong = System.currentTimeMillis()
                val rtt = lastPong - m.t
                send(WireCodec.encodeLatency(rtt.toFloat()))   // 回报给 Mac 面板显示
                cb.onRtt(rtt)
            }
            is WireCodec.Msg.Layout -> cb.onLayout(m.docId, m.v, m.count, m.pages)
            is WireCodec.Msg.Viewport -> cb.onViewport(m.page, m.frac, m.seq, m.force)
            is WireCodec.Msg.Pens -> cb.onPens(m.active, m.list)
            is WireCodec.Msg.PenSel -> cb.onPenSel(m.index)
            is WireCodec.Msg.ModeSel -> cb.onModeSel(m.mode)
            WireCodec.Msg.InkCancel -> cb.onInkCancel()
            is WireCodec.Msg.Strokes -> cb.onStrokes(m.ackRel, m.list, m.append)
            is WireCodec.Msg.Nack -> cb.onNack(m.seqs)
            is WireCodec.Msg.Docs -> cb.onDocs(m.following, m.selected, m.list)
            is WireCodec.Msg.Library -> cb.onLibrary(m.ws, m.list)
            is WireCodec.Msg.Toc -> cb.onToc(m.docId, m.list)
            is WireCodec.Msg.Bookmarks -> cb.onBookmarks(m.docId, m.list)
            is WireCodec.Msg.Notes -> cb.onNotes(m.list)
            is WireCodec.Msg.Layers -> cb.onLayers(m.active, m.list)
            is WireCodec.Msg.Radial -> cb.onRadial(m)
            is WireCodec.Msg.PressRing -> cb.onPressRing(m)
            is WireCodec.Msg.Eraser -> cb.onEraser(m.size, m.mode, m.ring)
            is WireCodec.Msg.Canvas -> cb.onCanvas(m.on, m.margin)
            is WireCodec.Msg.ScratchPads -> cb.onScratchPads(m.open, m.list)
            is WireCodec.Msg.NoteNew -> cb.onNoteNew(m.page, m.nx, m.ny)
            is WireCodec.Msg.ScratchStrokes -> cb.onScratchStrokes(m.ackRel, m.list)
            is WireCodec.Msg.Boards -> cb.onBoards(m.kind, m.current, m.list)
            is WireCodec.Msg.BoardImages -> cb.onBoardImages(m.list)
            is WireCodec.Msg.BoardPages -> cb.onBoardPages(m.w, m.h, m.list)
            else -> {}   // page 等不消费的消息忽略
        }
    }

    /** 断开统一入口：停心跳、通知调用方、排重连 */
    private fun dropped(msg: String) {
        ws = null
        pingRunning = false
        cb.onDisconnected(msg)
        scheduleRetry()
    }

    private fun scheduleRetry() {
        if (closed || retryScheduled) return
        retryScheduled = true
        val delay = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(RETRY_MAX)
        handler.postDelayed({
            retryScheduled = false
            if (!closed && ws == null) connect()
        }, delay)
    }

    private fun startPing() {
        lastPong = System.currentTimeMillis()
        if (pingRunning) return
        pingRunning = true
        val tick = object : Runnable {
            override fun run() {
                if (closed || !pingRunning) return
                // 看门狗：半开连接（锁屏/切网/Mac 睡眠后 onClosed 迟迟不触发）超时即杀掉重连
                if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
                    val w = ws
                    ws = null
                    pingRunning = false
                    try { w?.cancel() } catch (_: Exception) {}
                    cb.onDisconnected("心跳超时，重连中…")
                    scheduleRetry()
                    return
                }
                send(WireCodec.encodePing(System.currentTimeMillis().toDouble()))
                handler.postDelayed(this, PING_MS)
            }
        }
        handler.post(tick)
    }

    /** 回前台/网络恢复：断了立刻重连（不等退避计时器） */
    fun connectNow() {
        if (closed) return
        if (ws == null) { retryDelay = RETRY_MIN; connect() }
        else if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
            val w = ws
            ws = null
            pingRunning = false
            try { w?.cancel() } catch (_: Exception) {}
            retryDelay = RETRY_MIN
            connect()
        }
    }

    val isConnected: Boolean get() = ws != null

    /** 发可靠帧（mode/pen/padGeom/textNote/penset/eraser/图层请求/lassoMove/gotoPage/ping…） */
    fun send(frame: ByteArray) {
        ws?.send(frame.toByteString())
    }

    /** 关闭 WS 并释放 OkHttp 线程（不 shutdown 的话 dispatcher 线程会拖着进程） */
    fun close() {
        closed = true
        pingRunning = false
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
