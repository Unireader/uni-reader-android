package com.xvan.unireader.pad

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp WebSocket 可靠通道（ws://host:8771，二进制帧 = ByteString）。
 * 连接上立刻发 auth(token)；收帧经 WireCodec 解码后路由给回调。
 * 旧 `page` 消息在方案 B 下忽略（布局由 layout 驱动，capture.html 同款）。
 * 回调全在 OkHttp 后台线程，调用方负责切主线程。
 */
class MacClient(host: String, token: String, private val cb: Callback) {

    interface Callback {
        fun onAuthOK(session: Long, udpPort: Int)
        fun onAuthFail()
        fun onPong(t: Double)
        fun onLayout(docId: String, v: String, count: Long, pages: List<Pair<Float, Float>>)
        fun onViewport(page: Long, frac: Float, seq: Long, force: Boolean)
        fun onPens(active: Int, list: List<WireCodec.Pen>)
        fun onPenSel(index: Int)
        fun onModeSel(mode: Int)
        fun onInkCancel()
        fun onStrokes(list: List<WireCodec.Stroke>)
        fun onNack(seqs: List<Long>)
        fun onError(msg: String)
    }

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    init {
        val req = Request.Builder().url("ws://$host:8771").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(WireCodec.encodeAuth(token).toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                when (val m = WireCodec.decode(bytes.toByteArray())) {
                    is WireCodec.Msg.AuthOK -> cb.onAuthOK(m.session, m.udpPort)
                    WireCodec.Msg.AuthFail -> cb.onAuthFail()
                    is WireCodec.Msg.Pong -> cb.onPong(m.t)
                    is WireCodec.Msg.Layout -> cb.onLayout(m.docId, m.v, m.count, m.pages)
                    is WireCodec.Msg.Viewport -> cb.onViewport(m.page, m.frac, m.seq, m.force)
                    is WireCodec.Msg.Pens -> cb.onPens(m.active, m.list)
                    is WireCodec.Msg.PenSel -> cb.onPenSel(m.index)
                    is WireCodec.Msg.ModeSel -> cb.onModeSel(m.mode)
                    WireCodec.Msg.InkCancel -> cb.onInkCancel()
                    is WireCodec.Msg.Strokes -> cb.onStrokes(m.list)
                    is WireCodec.Msg.Nack -> cb.onNack(m.seqs)
                    else -> {}   // page/docs 等 demo 不消费，忽略
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                cb.onError("WS 失败: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                cb.onError("WS 已断开 ($code)")
            }
        })
    }

    /** 发可靠帧（auth 之外的控制/心跳：mode、pen、ping 等） */
    fun send(frame: ByteArray) {
        ws?.send(frame.toByteString())
    }

    /** 关闭 WS 并释放 OkHttp 线程（不 shutdown 的话 dispatcher 线程会拖着进程） */
    fun close() {
        ws?.close(1000, null)
        ws = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
