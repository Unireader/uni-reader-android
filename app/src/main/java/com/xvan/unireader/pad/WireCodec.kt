package com.xvan.unireader.pad

import java.nio.charset.StandardCharsets

/**
 * 二进制线格式编解码（PROTOCOL.md §2/§3/§4，全小端）。
 * 与 Sources/Server/WireCodec.swift、Sources/Resources/wire.js 字节级一致，
 * 逐字节对照 spike/wire-vectors-swift.txt（见 WireCodecTest）。
 */
object WireCodec {

    // opcode（§3）
    const val OP_AUTH = 0x01
    const val OP_AUTH_OK = 0x02
    const val OP_AUTH_FAIL = 0x03
    const val OP_PING = 0x10
    const val OP_PONG = 0x11
    const val OP_PAGE_TURN = 0x21
    const val OP_MODE = 0x22
    const val OP_PEN = 0x23
    const val OP_PAGE = 0x30
    const val OP_LAYOUT = 0x31
    const val OP_VIEWPORT = 0x32
    const val OP_PENS = 0x34
    const val OP_INK_CANCEL = 0x35
    const val OP_STROKES = 0x36
    const val OP_SCROLL = 0x40
    const val OP_HOVER = 0x41
    const val OP_INK = 0x42
    const val OP_ERASE = 0x43
    const val OP_NACK = 0x50

    // phase / dir（§2）
    const val PH_BEGIN = 0
    const val PH_MOVE = 1
    const val PH_END = 2
    const val DIR_PREV = 0
    const val DIR_NEXT = 1

    /** mode（§2）：0=note 1=erase 2=page */
    const val MODE_NOTE = 0
    const val MODE_ERASE = 1
    const val MODE_PAGE = 2

    /** brush 编号 → 名字（§2：0=ballpoint 1=fountain 2=marker 3=pencil，越界回退 0） */
    val BRUSH_NAMES = listOf("ballpoint", "fountain", "marker", "pencil")

    fun brushName(code: Int): String = BRUSH_NAMES.getOrElse(code) { BRUSH_NAMES[0] }

    /** pen 原语：u8 r,g,b + f32 a + f32 w + u8 brush，共 12 字节。brush: 0=ballpoint 1=fountain 2=marker 3=pencil */
    data class Pen(val r: Int, val g: Int, val b: Int, val a: Float, val w: Float, val brush: Int)

    /** pt3：f32 x + f32 y + f32 pressure（页内归一化 + 压感） */
    data class Pt3(val x: Float, val y: Float, val p: Float)

    /** pt2：f32 x + f32 y（擦除点，无压感） */
    data class Pt2(val x: Float, val y: Float)

    /** 一条成形笔迹（strokes 消息元素） */
    data class Stroke(val page: Long, val pen: Pen, val pts: List<Pt3>)

    // ---------- 解码结果（u32 用 Long 承载无符号值） ----------
    sealed class Msg {
        /** 兼容空 payload → session/udpPort = 0 */
        data class AuthOK(val session: Long, val udpPort: Int) : Msg()
        data object AuthFail : Msg()
        data class Pong(val t: Double) : Msg()
        data class Page(val v: Long, val index: Long, val count: Long, val w: Float, val h: Float) : Msg()
        data class Layout(val docId: String, val v: String, val count: Long, val pages: List<Pair<Float, Float>>) : Msg()
        data object InkCancel : Msg()
        /** Mac 回传的全部成形笔迹（唯一真源） */
        data class Strokes(val list: List<Stroke>) : Msg()
        data class Nack(val seqs: List<Long>) : Msg()
        /** Mac 视口下发（force 绕过 seq 去重） */
        data class Viewport(val page: Long, val frac: Float, val seq: Long, val force: Boolean) : Msg()
        /** Mac 推送的收藏笔列表（运行时唯一源，整体替换本地） */
        data class Pens(val active: Int, val list: List<Pen>) : Msg()
        /** Mac 侧切笔/切模式回推 */
        data class PenSel(val index: Int) : Msg()
        data class ModeSel(val mode: Int) : Msg()
    }

    // ---------- Writer ----------
    private class Writer {
        private var buf = ByteArray(64)
        private var n = 0

        private fun ensure(k: Int) {
            if (n + k <= buf.size) return
            var cap = buf.size
            while (cap < n + k) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun u8(v: Int) { ensure(1); buf[n++] = v.toByte() }
        fun u16(v: Int) { ensure(2); buf[n++] = v.toByte(); buf[n++] = (v ushr 8).toByte() }
        fun u32(v: Long) {
            ensure(4)
            buf[n++] = v.toByte(); buf[n++] = (v ushr 8).toByte()
            buf[n++] = (v ushr 16).toByte(); buf[n++] = (v ushr 24).toByte()
        }

        fun f32(v: Float) {
            val b = java.lang.Float.floatToRawIntBits(v)
            ensure(4)
            buf[n++] = b.toByte(); buf[n++] = (b ushr 8).toByte()
            buf[n++] = (b ushr 16).toByte(); buf[n++] = (b ushr 24).toByte()
        }

        fun f64(v: Double) {
            val b = java.lang.Double.doubleToRawLongBits(v)
            ensure(8)
            for (i in 0 until 8) buf[n++] = (b ushr (8 * i)).toByte()
        }

        /** str = u16 长度 + UTF-8 字节（最长 65535） */
        fun str(s: String) {
            var b = s.toByteArray(StandardCharsets.UTF_8)
            if (b.size > 65535) b = b.copyOf(65535)
            u16(b.size)
            ensure(b.size)
            System.arraycopy(b, 0, buf, n, b.size)
            n += b.size
        }

        fun pen(p: Pen) { u8(p.r); u8(p.g); u8(p.b); f32(p.a); f32(p.w); u8(p.brush) }
        fun pts3(pts: List<Pt3>) { u16(pts.size); for (p in pts) { f32(p.x); f32(p.y); f32(p.p) } }
        fun pts2(pts: List<Pt2>) { u16(pts.size); for (p in pts) { f32(p.x); f32(p.y) } }
        fun bytes(): ByteArray = buf.copyOf(n)
    }

    // ---------- Reader（越界抛异常，decode 捕获后返回 null） ----------
    private class Reader(private val d: ByteArray) {
        var n = 0
        val remaining: Int get() = d.size - n

        private fun need(k: Int) { if (n + k > d.size) throw IndexOutOfBoundsException("帧截断") }

        fun u8(): Int { need(1); return d[n++].toInt() and 0xFF }
        fun u16(): Int {
            need(2)
            val v = (d[n].toInt() and 0xFF) or ((d[n + 1].toInt() and 0xFF) shl 8)
            n += 2; return v
        }

        fun u32(): Long {
            need(4)
            var v = 0L
            for (i in 0 until 4) v = v or ((d[n + i].toLong() and 0xFF) shl (8 * i))
            n += 4; return v
        }

        fun f32(): Float = java.lang.Float.intBitsToFloat(u32().toInt())

        fun f64(): Double {
            need(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((d[n + i].toLong() and 0xFF) shl (8 * i))
            n += 8; return java.lang.Double.longBitsToDouble(v)
        }

        fun str(): String {
            val len = u16(); need(len)
            val s = String(d, n, len, StandardCharsets.UTF_8)
            n += len; return s
        }

        fun pen(): Pen = Pen(u8(), u8(), u8(), f32(), f32(), u8())

        fun pts3(): List<Pt3> {
            val m = u16()
            val out = ArrayList<Pt3>(m)
            var i = 0
            while (i < m) { out.add(Pt3(f32(), f32(), f32())); i++ }
            return out
        }
    }

    // ---------- 编码（C→S） ----------

    fun encodeAuth(token: String): ByteArray =
        Writer().apply { u8(OP_AUTH); str(token) }.bytes()

    fun encodePing(t: Double): ByteArray =
        Writer().apply { u8(OP_PING); f64(t) }.bytes()

    /** dir: 0=prev 1=next */
    fun encodePageTurn(dir: Int): ByteArray =
        Writer().apply { u8(OP_PAGE_TURN); u8(dir) }.bytes()

    /** mode: 0=note 1=erase 2=page */
    fun encodeMode(mode: Int): ByteArray =
        Writer().apply { u8(OP_MODE); u8(mode) }.bytes()

    /** 切笔（index 进 pens 列表） */
    fun encodePen(index: Int): ByteArray =
        Writer().apply { u8(OP_PEN); u16(index) }.bytes()

    fun encodeScroll(page: Long, frac: Float, t: Double): ByteArray =
        Writer().apply { u8(OP_SCROLL); u32(page); f32(frac); f64(t) }.bytes()

    fun encodeHoverMove(page: Long, nx: Float, ny: Float): ByteArray =
        Writer().apply { u8(OP_HOVER); u8(PH_MOVE); u32(page); f32(nx); f32(ny) }.bytes()

    fun encodeHoverEnd(): ByteArray =
        Writer().apply { u8(OP_HOVER); u8(PH_END) }.bytes()

    fun encodeInkBegin(page: Long, pen: Pen, pts: List<Pt3>, line: Boolean = false): ByteArray =
        // 尾部 flags（bit0=line 直线/尺子笔）：尾部可选字节，旧解码端读完 pts 即止、天然忽略
        Writer().apply { u8(OP_INK); u8(PH_BEGIN); u32(page); pen(pen); pts3(pts); u8(if (line) 1 else 0) }.bytes()

    fun encodeInkMove(pts: List<Pt3>): ByteArray =
        Writer().apply { u8(OP_INK); u8(PH_MOVE); pts3(pts) }.bytes()

    fun encodeInkEnd(): ByteArray =
        Writer().apply { u8(OP_INK); u8(PH_END) }.bytes()

    fun encodeEraseMove(page: Long, pts: List<Pt2>): ByteArray =
        Writer().apply { u8(OP_ERASE); u8(PH_MOVE); u32(page); pts2(pts) }.bytes()

    fun encodeEraseEnd(): ByteArray =
        Writer().apply { u8(OP_ERASE); u8(PH_END) }.bytes()

    // ---------- 解码（S→C）；未知 opcode / 坏帧返回 null，不崩 ----------

    fun decode(d: ByteArray): Msg? {
        if (d.isEmpty()) return null
        return try {
            val r = Reader(d)
            when (r.u8()) {
                OP_AUTH_OK ->
                    if (r.remaining >= 6) Msg.AuthOK(r.u32(), r.u16()) else Msg.AuthOK(0, 0)
                OP_AUTH_FAIL -> Msg.AuthFail
                OP_PONG -> Msg.Pong(r.f64())
                OP_PAGE -> Msg.Page(r.u32(), r.u32(), r.u32(), r.f32(), r.f32())
                OP_LAYOUT -> {
                    val docId = r.str(); val v = r.str(); val count = r.u32()
                    // count 由对端给定，按 remaining 防御性截断
                    val pages = ArrayList<Pair<Float, Float>>()
                    var i = 0L
                    while (i < count && r.remaining >= 8) { pages.add(r.f32() to r.f32()); i++ }
                    Msg.Layout(docId, v, count, pages)
                }
                OP_INK_CANCEL -> Msg.InkCancel
                OP_STROKES -> {
                    val n = r.u32()
                    val list = ArrayList<Stroke>()
                    var i = 0L
                    while (i < n && r.remaining > 0) { list.add(Stroke(r.u32(), r.pen(), r.pts3())); i++ }
                    Msg.Strokes(list)
                }
                OP_VIEWPORT -> Msg.Viewport(r.u32(), r.f32(), r.u32(), r.u8() == 1)
                OP_PENS -> {
                    val active = r.u16()
                    val pn = r.u16()
                    val list = ArrayList<Pen>(pn)
                    var i = 0
                    while (i < pn && r.remaining >= 12) { list.add(r.pen()); i++ }
                    Msg.Pens(active, list)
                }
                OP_PEN -> Msg.PenSel(r.u16())
                OP_MODE -> Msg.ModeSel(r.u8())
                OP_NACK -> {
                    val n = r.u16()
                    val seqs = ArrayList<Long>(n)
                    var i = 0
                    while (i < n && r.remaining >= 4) { seqs.add(r.u32()); i++ }
                    Msg.Nack(seqs)
                }
                else -> null   // 未知 opcode：丢弃该帧
            }
        } catch (e: Exception) {
            null
        }
    }
}
