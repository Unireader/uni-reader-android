package com.xvan.unireader.pad

import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.RadialItem
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextNote
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
    const val OP_LATENCY = 0x12
    const val OP_SELECT_DOC = 0x20
    const val OP_PAGE_TURN = 0x21
    const val OP_MODE = 0x22
    const val OP_PEN = 0x23
    const val OP_TEXT_NOTE = 0x24
    const val OP_PENSET = 0x25
    const val OP_LAYER_SELECT = 0x26
    const val OP_LAYER_VISIBLE = 0x27
    const val OP_LAYER_ADD = 0x28
    const val OP_GOTO_PAGE = 0x29
    const val OP_PAGE = 0x30
    const val OP_LAYOUT = 0x31
    const val OP_VIEWPORT = 0x32
    const val OP_DOCS = 0x33
    const val OP_PENS = 0x34
    const val OP_INK_CANCEL = 0x35
    const val OP_STROKES = 0x36
    const val OP_RADIAL = 0x37
    const val OP_PRESS_RING = 0x38
    const val OP_NOTES = 0x39
    const val OP_LAYERS = 0x3A
    const val OP_SCROLL = 0x40
    const val OP_HOVER = 0x41
    const val OP_INK = 0x42
    const val OP_ERASE = 0x43
    const val OP_PROBE = 0x44
    const val OP_PAD_GEOM = 0x45
    const val OP_ERASER = 0x46
    const val OP_LASSO_MOVE = 0x47
    const val OP_NACK = 0x50

    // phase / dir（§2）
    const val PH_BEGIN = 0
    const val PH_MOVE = 1
    const val PH_END = 2
    const val DIR_PREV = 0
    const val DIR_NEXT = 1

    /** textNote.op（§4.1）：0=upsert 1=delete（空文本 upsert 等价 delete） */
    const val NOTE_UPSERT = 0
    const val NOTE_DELETE = 1

    // 消息里出现的对象类型（Pen/Pt3/Pt2/Stroke/TextNote/Layer/RadialItem）与 mode/RK/brush 的
    // 编号↔名字映射都在 shared/Ink.kt——它们不只属于线格式，模式1（本地开工作区）同样要用。
    // 本文件只管**字节布局**（§2/§4）：pen = u8 r,g,b + f32 a + f32 w + u8 brush 共 12 字节，
    // pt3 = f32 x,y,pressure，pt2 = f32 x,y。

    /** 文档列表项（docs 消息元素；纯线格式概念——模式1 的文档列表来自 SQLite，不走这里） */
    data class DocEntry(val id: String, val title: String)

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
        /** @param ackRel Mac 已连续处理到的本端 REL seq（PROTOCOL.md §4.2）；0 = 没建 UDP 会话 */
        data class Strokes(val ackRel: Long, val list: List<Stroke>) : Msg()
        data class Nack(val seqs: List<Long>) : Msg()
        /** Mac 视口下发（force 绕过 seq 去重） */
        data class Viewport(val page: Long, val frac: Float, val seq: Long, val force: Boolean) : Msg()
        /** Mac 推送的收藏笔列表（运行时唯一源，整体替换本地） */
        data class Pens(val active: Int, val list: List<Pen>) : Msg()
        /** Mac 侧切笔/切模式回推 */
        data class PenSel(val index: Int) : Msg()
        data class ModeSel(val mode: Int) : Msg()
        /** Mac 的文档列表（following=true 时平板下拉显示「跟随 Mac」） */
        data class Docs(val following: Boolean, val selected: String, val list: List<DocEntry>) : Msg()
        /** 文字笔记全量镜像（Mac 唯一真源，平板不落库） */
        data class Notes(val list: List<TextNote>) : Msg()
        /** 图层表全量镜像（按下标对齐，active = 当前作画图层下标） */
        data class Layers(val active: Int, val list: List<Layer>) : Msg()
        /** 环形选笔盘状态镜像（Mac 唯一判定方，平板照画）；open=false 时其余字段无意义 */
        data class Radial(
            val open: Boolean,
            val page: Long,
            val cx: Float,
            val cy: Float,
            val highlight: Int,   // -1 = 指针在中心取消区（线上 0xFFFF）
            val items: List<RadialItem>,
        ) : Msg()
        /** 长按进度环（环形盘前置动画）；on=false 时其余字段无意义 */
        data class PressRing(val on: Boolean, val page: Long, val nx: Float, val ny: Float) : Msg()
        /** 橡皮设置（双向；size = 归一化半径＝页宽比，mode 0=整笔 1=局部） */
        data class Eraser(val size: Float, val mode: Int, val ring: Boolean) : Msg()
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

    // probe：擦除/翻页模式下与主流平行上报笔位置，Mac 据此做长按检测 → 环形选笔盘
    // （笔记模式不发 probe，Mac 直接拿 ink 流判长按，见 capture.html/input.ts）

    fun encodeProbeBegin(page: Long, pts: List<Pt2>): ByteArray =
        Writer().apply { u8(OP_PROBE); u8(PH_BEGIN); u32(page); pts2(pts) }.bytes()

    fun encodeProbeMove(pts: List<Pt2>): ByteArray =
        Writer().apply { u8(OP_PROBE); u8(PH_MOVE); pts2(pts) }.bytes()

    fun encodeProbeEnd(): ByteArray =
        Writer().apply { u8(OP_PROBE); u8(PH_END) }.bytes()

    /** 回报本端测得的 rtt，Mac 面板显示 */
    fun encodeLatency(ms: Float): ByteArray =
        Writer().apply { u8(OP_LATENCY); f32(ms) }.bytes()

    /** 选文档（"" = 跟随 Mac） */
    fun encodeSelectDoc(id: String): ByteArray =
        Writer().apply { u8(OP_SELECT_DOC); str(id) }.bytes()

    /** 跳页（0-based；平板 UI 输入的是 1-based，调用方先减一） */
    fun encodeGotoPage(page: Long): ByteArray =
        Writer().apply { u8(OP_GOTO_PAGE); u32(page) }.bytes()

    /** 平板上报自己的内容页宽（px），Mac 环形盘的像素判定基准；值变才发 */
    fun encodePadGeom(pageW: Float): ByteArray =
        Writer().apply { u8(OP_PAD_GEOM); f32(pageW) }.bytes()

    /** 文字笔记增删（op 见 NOTE_*；空文本 upsert 被 Mac 视为 delete） */
    fun encodeTextNote(id: String, op: Int, page: Long, nx: Float, ny: Float, text: String): ByteArray =
        Writer().apply { u8(OP_TEXT_NOTE); str(id); u8(op); u32(page); f32(nx); f32(ny); str(text) }.bytes()

    /** 改笔宽后整表上行（Mac 按下标对齐写回；数目不符 Mac 整包丢弃） */
    fun encodePenset(active: Int, list: List<Pen>): ByteArray =
        Writer().apply { u8(OP_PENSET); u16(active); u16(list.size); for (p in list) pen(p) }.bytes()

    /** 橡皮设置上行（size = 归一化半径，mode 0=整笔 1=局部） */
    fun encodeEraser(size: Float, mode: Int, ring: Boolean): ByteArray =
        Writer().apply { u8(OP_ERASER); f32(size); u8(mode); u8(if (ring) 1 else 0) }.bytes()

    /** 图层请求：切换当前作画图层（index = layers 列表下标） */
    fun encodeLayerSelect(index: Int): ByteArray =
        Writer().apply { u8(OP_LAYER_SELECT); u16(index) }.bytes()

    /** 图层请求：显示/隐藏某层 */
    fun encodeLayerVisible(index: Int, visible: Boolean): ByteArray =
        Writer().apply { u8(OP_LAYER_VISIBLE); u16(index); u8(if (visible) 1 else 0) }.bytes()

    /** 图层请求：新建（名字/颜色/顺序由 Mac 决定，追加后即为当前作画图层） */
    fun encodeLayerAdd(): ByteArray =
        Writer().apply { u8(OP_LAYER_ADD) }.bytes()

    /** 框选移动提交：框选矩形（Mac 用真源复判命中）+ 位移，均页内归一化 */
    fun encodeLassoMove(
        page: Long, x0: Float, y0: Float, x1: Float, y1: Float, dx: Float, dy: Float,
    ): ByteArray = Writer().apply {
        u8(OP_LASSO_MOVE); u32(page); f32(x0); f32(y0); f32(x1); f32(y1); f32(dx); f32(dy)
    }.bytes()

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
                    val ackRel = r.u32()
                    val n = r.u32()
                    val list = ArrayList<Stroke>()
                    var i = 0L
                    while (i < n && r.remaining > 0) { list.add(Stroke(r.u32(), r.pen(), r.pts3())); i++ }
                    Msg.Strokes(ackRel, list)
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
                OP_DOCS -> {
                    val following = r.u8() == 1
                    val selected = r.str()
                    val n = r.u16()
                    val list = ArrayList<DocEntry>(n)
                    var i = 0
                    while (i < n && r.remaining >= 4) { list.add(DocEntry(r.str(), r.str())); i++ }
                    Msg.Docs(following, selected, list)
                }
                OP_NOTES -> {
                    val n = r.u16()
                    val list = ArrayList<TextNote>(n)
                    var i = 0
                    while (i < n && r.remaining > 0) {
                        list.add(TextNote(r.str(), r.u32(), r.f32(), r.f32(), r.str())); i++
                    }
                    Msg.Notes(list)
                }
                OP_LAYERS -> {
                    val active = r.u16()
                    val n = r.u16()
                    val list = ArrayList<Layer>(n)
                    var i = 0
                    while (i < n && r.remaining > 0) {
                        list.add(Layer(r.u8(), r.u8(), r.u8(), r.u8() == 1, r.str())); i++
                    }
                    Msg.Layers(active, list)
                }
                OP_RADIAL -> {
                    if (r.u8() != 1) Msg.Radial(false, 0, 0f, 0f, -1, emptyList())
                    else {
                        val page = r.u32(); val cx = r.f32(); val cy = r.f32()
                        val hl = r.u16()
                        val n = r.u16()
                        val items = ArrayList<RadialItem>(n)
                        var i = 0
                        while (i < n && r.remaining >= 13) { items.add(RadialItem(r.u8(), r.pen())); i++ }
                        // 0xFFFF = 指针在中心取消区 → 对象里是 -1（同 JS/Swift）
                        Msg.Radial(true, page, cx, cy, if (hl == 0xFFFF) -1 else hl, items)
                    }
                }
                OP_PRESS_RING ->
                    if (r.u8() != 1) Msg.PressRing(false, 0, 0f, 0f)
                    else Msg.PressRing(true, r.u32(), r.f32(), r.f32())
                OP_ERASER -> Msg.Eraser(r.f32(), r.u8(), r.u8() != 0)
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
