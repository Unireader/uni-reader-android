package com.xvan.unireader.pad

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP 发送端（UDP-PLAN §7 原样落地，传输头见 PROTOCOL §6）。
 * - 双 seq 空间：seqUnrel/seqRel 各从 1 递增
 * - REL（ink/erase）：组 [01 02 session seq]+帧本体 → 存 ring(cap 512 淘汰最旧) → 发
 * - UNREL（scroll/hover）：组 [01 01 session seq]+帧本体 → 发，不留存
 * - authOK 后立刻发一发 HELLO [01 03 session]，不保活（评审定案）；退出发 BYE [01 04 session]
 * - 收 nack：ring 里有就原样重发（含原 seq），没有跳过
 *
 * 线程：所有 socket 操作（建 socket、DNS、send）都在专用 HandlerThread 上——
 * Android 主线程做网络 I/O 会抛 NetworkOnMainThreadException（默认 StrictMode 策略），
 * 之前 catch 吞掉导致「全部数据报静默发送失败」。调用方线程只负责组帧+post。
 */
class UdpSender(private val host: String) {

    companion object {
        const val RING_CAP = 512
        const val TAG = "UdpSender"
    }

    private val thread = HandlerThread("udp-sender").apply { start() }
    private val io = Handler(thread.looper)

    // —— 以下全部只在 io 线程上读写 ——
    private var socket: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var session = 0L
    private var udpPort = 0
    private var ready = false
    private var seqUnrel = 0L

    /** seq → 首发时刻（nackRTT 用），随 ring 同步淘汰 */
    private val sendTime = HashMap<Long, Long>()

    /** seq → 完整数据报；LinkedHashMap 插入序，超 cap 淘汰最旧 */
    private val ring = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean {
            if (size > RING_CAP) {
                eldest?.key?.let { sendTime.remove(it) }
                return true
            }
            return false
        }
    }

    // —— 量化指标（状态栏跨线程读） ——
    @Volatile var udpReady = false
        private set
    @Volatile var nacks = 0
        private set
    @Volatile var resends = 0
        private set
    @Volatile var nackRttMs = -1.0
        private set

    /** authOK 后调用：记 session/udpPort，立刻发一发 HELLO（不保活） */
    fun start(session: Long, udpPort: Int) {
        io.post {
            this.session = session
            this.udpPort = udpPort
            if (socket == null) {
                try {
                    socket = DatagramSocket()
                    addr = InetAddress.getByName(host)
                } catch (e: Exception) {
                    Log.w(TAG, "UDP socket 创建失败", e)
                    return@post
                }
            }
            ready = true
            udpReady = true
            sendRaw(header(3))   // HELLO [01 03 session]
        }
    }

    /**
     * 已发出的最后一个 REL 序号。拿去与 `strokes` 广播回来的 `ackRel` 比，就知道那份全量快照
     * 含不含本端某一帧输入（`PROTOCOL.md §4.2`）。
     *
     * **定序是同步的**（调用方线程上就 ++，不等 io 线程）：`PageCanvasView` 要在 `onInkEnd()` 之后
     * 立刻读到「刚那一帧 ink end 的序号」，才能逐条认领乐观笔迹。放到 io 线程上加的话读回来的是
     * 上一帧的号，乐观笔迹会被提前销账 → 「上一笔闪一下」（2026-08-28）。
     *
     * 发送顺序不受影响：定序与 `io.post` 在同一个 `seqLock` 里完成，Handler 又是 FIFO，
     * 序号序 == 入队序 == 发送序。
     */
    @Volatile
    var sentRel = 0L
        private set

    private val seqLock = Any()

    /**
     * REL：定序 → 存 ring（含 sendTime）→ 发。
     * @return 本帧的 REL 序号；0 = UDP 还没就绪，这帧没发出去。
     */
    fun sendRel(body: ByteArray): Long = synchronized(seqLock) {
        if (!udpReady) return 0L
        val seq = sentRel + 1
        sentRel = seq
        io.post {
            if (!ready) return@post
            val dg = header(2, seq) + body
            ring[seq] = dg
            sendTime[seq] = System.currentTimeMillis()
            sendRaw(dg)
        }
        seq
    }

    /** UNREL：最新胜，发完不留存 */
    fun sendUnrel(body: ByteArray) {
        io.post {
            if (!ready) return@post
            seqUnrel++
            sendRaw(header(1, seqUnrel) + body)
        }
    }

    /** 收 WS nack{seqs}：ring 里有就原样重发（含原 seq），没有（已被淘汰）跳过 */
    fun onNack(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        io.post {
            nacks += seqs.size
            val now = System.currentTimeMillis()
            for (sq in seqs) {
                sendTime[sq]?.let { nackRttMs = (now - it).toDouble() }
                ring[sq]?.let { sendRaw(it); resends++ }
            }
        }
    }

    /** 退出时发 BYE（可选，纯优化；Mac 重置该 session 重排状态） */
    fun bye() {
        io.post {
            if (ready) sendRaw(header(4))
        }
    }

    fun close() {
        bye()
        io.post {
            ready = false
            udpReady = false
            socket?.close()
            socket = null
        }
        thread.quitSafely()
    }

    /** 传输头：DATA 10 字节 / HELLO、BYE 6 字节（PROTOCOL §6）。io 线程上调用。 */
    private fun header(ptype: Int, seq: Long? = null): ByteArray {
        val b = ByteArray(if (seq != null) 10 else 6)
        b[0] = 0x01                      // ver
        b[1] = ptype.toByte()
        var v = session
        for (i in 0 until 4) { b[2 + i] = (v and 0xFF).toByte(); v = v ushr 8 }
        if (seq != null) {
            var s: Long = seq
            for (i in 0 until 4) { b[6 + i] = (s and 0xFF).toByte(); s = s ushr 8 }
        }
        return b
    }

    private fun sendRaw(dg: ByteArray) {
        val s = socket ?: return
        val a = addr ?: return
        try {
            s.send(DatagramPacket(dg, dg.size, a, udpPort))
        } catch (e: Exception) {
            // LAN 偶发发送失败不影响后续帧（UNREL 等下一帧，REL 有 nack 兜底），但留日志防静默
            Log.w(TAG, "UDP 发送失败: ${e.message}")
        }
    }
}
