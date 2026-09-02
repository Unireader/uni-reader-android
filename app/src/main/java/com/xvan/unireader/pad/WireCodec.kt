package com.xvan.unireader.pad

import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.NOTE_TAP
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
    const val OP_OPEN_DOC = 0x2A
    const val OP_SCRATCH_OPEN = 0x2B
    const val OP_SCRATCH_ADD = 0x2C
    const val OP_SCRATCH_PAPER = 0x2D
    const val OP_SCRATCH_PAGE_SHOW = 0x2F
    const val OP_SCRATCH_DELETE = 0x48
    const val OP_SCRATCH_RENAME = 0x49
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
    const val OP_LIBRARY = 0x3B
    const val OP_TOC = 0x3C
    const val OP_SCRATCH_MOVE = 0x2E
    const val OP_SCRATCH_PADS = 0x3D
    const val OP_SCRATCH_STROKES = 0x3E
    const val OP_NOTE_NEW = 0x3F
    const val OP_SCROLL = 0x40
    const val OP_HOVER = 0x41
    const val OP_INK = 0x42
    const val OP_ERASE = 0x43
    const val OP_PROBE = 0x44
    const val OP_PAD_GEOM = 0x45
    const val OP_ERASER = 0x46
    const val OP_LASSO_MOVE = 0x47
    const val OP_LASSO_SCALE = 0x4A
    const val OP_CANVAS = 0x4B

    /** 与 [OP_STROKES] 逐字节相同，语义是「追加」（`../PROTOCOL.md §4.2`） */
    const val OP_STROKES_APPEND = 0x4C

    /** 书签（`../REQUIREMENTS.md §1.9`）：S→C 全量镜像 / C→S 增删改请求 */
    const val OP_BOOKMARKS = 0x4D
    const val OP_BOOKMARK_EDIT = 0x4E

    // bookmarkEdit 的 op
    const val BM_ADD = 0
    const val BM_RENAME = 1
    const val BM_DELETE = 2
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

    // 草稿纸（§4.4）
    /** 底纹编号（值 = 线格式 pattern u8）。🔴 plain = 0：编解码都要显式处理，别用 `?: 1` 这类兜底吃掉 */
    const val PATTERN_PLAIN = 0
    const val PATTERN_DOTS = 1
    const val PATTERN_GRID = 2

    /** 底纹 u8 → 编号，越界回退 dots（同 brush/mode 的解码惯例） */
    fun patternOrDefault(c: Int): Int = if (c in PATTERN_PLAIN..PATTERN_GRID) c else PATTERN_DOTS

    /** 「没打开任何一张纸」的线上哨兵（scratchpads.open / scratchOpen.index），对象里是 -1（同 radial.highlight 惯例） */
    const val SCRATCH_NO_OPEN = 0xFFFF

    // 消息里出现的对象类型（Pen/Pt3/Pt2/Stroke/TextNote/Layer/RadialItem）与 mode/RK/brush 的
    // 编号↔名字映射都在 shared/Ink.kt——它们不只属于线格式，模式1（本地开工作区）同样要用。
    // 本文件只管**字节布局**（§2/§4）：pen = u8 r,g,b + f32 a + f32 w + u8 brush 共 12 字节，
    // pt3 = f32 x,y,pressure，pt2 = f32 x,y。

    /** 文档列表项（docs 消息元素；纯线格式概念——模式1 的文档列表来自 SQLite，不走这里） */
    data class DocEntry(val id: String, val title: String)

    /**
     * 工作区书库一项（library 消息元素）。[id] 是**库文档 id**，与 [DocEntry] 的窗口会话 id
     * 不是一个空间（PROTOCOL.md §4.1 的三个 id 空间）；[open] = 该文档已在 Mac 某个窗口里开着。
     */
    data class LibEntry(val id: String, val title: String, val open: Boolean)

    /** PDF 目录一项（toc 消息元素，先序拍平）。[page] = -1 是坏书签：跳不过去，渲染成灰行。 */
    data class TocEntry(val depth: Int, val page: Int, val frac: Float, val label: String)

    /**
     * 一枚书签（bookmarks 消息元素）。用户自己加的定位记录，与 PDF 自带目录是两回事，
     * 但显示时要与目录**合并成同一棵树**（规则见 `shared/TocMerge.kt`）。
     * 线上列表恒按「页 → 页内位置 → 建立时刻」有序，**别再自己排**。
     */
    data class BookmarkEntry(val id: String, val page: Int, val frac: Float, val title: String)

    /**
     * 草稿纸一项（scratchpads 消息元素；纯线格式概念——模式1 的草稿纸来自 SQLite，不走这里）。
     * [page]/[nx]/[ny] 是创建处的图钉锚点（页内归一化）；底色拆 r/g/b(u8)+a(f32)，与 [Pen] 同惯例
     * （线上不传 CSS 串）；[pattern] 见 PATTERN_*。
     */
    data class ScratchPadEntry(
        val id: String,
        val title: String,
        val page: Long,
        val nx: Float,
        val ny: Float,
        val r: Int,
        val g: Int,
        val b: Int,
        val a: Float,
        val pattern: Int,
        /** 页面底图（v10）：这张纸要不要把它锚定的那一页垫在纸下面（几何契约见 ../PROTOCOL.md §4.4） */
        val showPage: Boolean,
    )

    // ---------- 解码结果（u32 用 Long 承载无符号值） ----------
    sealed class Msg {
        /** 兼容空 payload → session/udpPort = 0 */
        data class AuthOK(val session: Long, val udpPort: Int) : Msg()
        data object AuthFail : Msg()
        data class Pong(val t: Double) : Msg()
        data class Page(val v: Long, val index: Long, val count: Long, val w: Float, val h: Float) : Msg()
        data class Layout(val docId: String, val v: String, val count: Long, val pages: List<Pair<Float, Float>>) : Msg()
        data object InkCancel : Msg()
        /**
         * Mac 回传的成形笔迹（唯一真源）。[append] = 这一份是**追加**（`strokesAppend`, 0x4C）
         * 而不是整表替换（`strokes`, 0x36）—— 两者 payload 逐字节相同，只差这一个语义位。
         * Mac 只在纯追加（收笔）时用追加帧，擦除/框选/图层/切档一律照旧发全量（`PROTOCOL.md §4.2`）。
         *
         * @param ackRel Mac 已**应用**到的本端 REL seq（`PROTOCOL.md §4.2`）；0 = 没建 UDP 会话
         */
        data class Strokes(val ackRel: Long, val list: List<Stroke>, val append: Boolean = false) : Msg()
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
        /**
         * 画板模式（../PROTOCOL.md `canvas`）：页面两侧的空白也是可书写区。
         * [margin] = **每侧**页边宽度，单位是页宽的倍数（0.5 = 每侧半个页宽）；Mac 是唯一真源。
         * 页边笔迹仍是页内笔迹，只是归一化 x 越出 0…1 —— 除本条外线格式一个字节没变。
         */
        data class Canvas(val on: Boolean, val margin: Float) : Msg()
        /** 橡皮设置（双向；size = 归一化半径＝页宽比，mode 0=整笔 1=局部） */
        data class Eraser(val size: Float, val mode: Int, val ring: Boolean) : Msg()
        /** 工作区书库全量镜像（含 Mac 尚未打开的文档） */
        data class Library(val ws: String, val list: List<LibEntry>) : Msg()
        /** 当前文档的 PDF 目录。[docId] = 内容哈希，与 [Layout] 的 docId/v 同口径，渲染前必须核对 */
        data class Toc(val docId: String, val list: List<TocEntry>) : Msg()
        /** 当前文档的书签全量镜像（Mac 唯一真源）。[docId] 口径同 [Toc]，渲染前必须核对 */
        data class Bookmarks(val docId: String, val list: List<BookmarkEntry>) : Msg()
        /** 草稿纸列表全量镜像（Mac 唯一真源）。[open] = 当前打开 list 里第几张，-1 = 没开（线上 0xFFFF） */
        data class ScratchPads(val open: Int, val list: List<ScratchPadEntry>) : Msg()
        /** Mac 通知「在该页的页内点开文字笔记编辑器（新建态）」（环形盘 textNote 扇区提交的结果） */
        data class NoteNew(val page: Long, val nx: Float, val ny: Float) : Msg()
        /**
         * 当前打开那张纸上的全量笔迹镜像（Mac 唯一真源）。**无 page 字段**——画布不属于任何一页，
         * 这里复用 [Stroke] 时 page 恒为 0、pts 是画布坐标（逻辑点，可负无界，PROTOCOL.md §4.4）。
         * 没开纸时 Mac 发 n=0，据此清掉本地残留。[ackRel] 语义与 [Strokes] 完全一致（§4.2）。
         */
        data class ScratchStrokes(val ackRel: Long, val list: List<Stroke>) : Msg()
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

        /**
         * 尾部可选多边形（lassoMove/lassoScale，PROTOCOL.md §4.1）：扁平数组 [x0,y0,x1,y1,…]，
         * 缺省/<3 点一律不写（老形态字节不变）。
         */
        fun polyTail(poly: FloatArray?) {
            if (poly == null || poly.size < 6) return
            u16(poly.size / 2)
            for (v in poly) f32(v)
        }

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

    /**
     * 目录跳转：页 + 页内纵向比例。[frac] 是**尾部可选 f32**（PROTOCOL.md §4.1），
     * 为 0 时一律省略那 4 字节——「只跳页」的老形态字节保持不变。
     */
    fun encodeGotoDest(page: Long, frac: Float): ByteArray =
        Writer().apply { u8(OP_GOTO_PAGE); u32(page); if (frac != 0f) f32(frac) }.bytes()

    /** 打开工作区里的某个文档（**库**文档 id）。已打开的 Mac 会切过去，未打开的新开一个 Mac 窗口 */
    fun encodeOpenDoc(id: String): ByteArray =
        Writer().apply { u8(OP_OPEN_DOC); str(id) }.bytes()

    /** 平板上报自己的内容页宽（px），Mac 环形盘的像素判定基准；值变才发 */
    fun encodePadGeom(pageW: Float): ByteArray =
        Writer().apply { u8(OP_PAD_GEOM); f32(pageW) }.bytes()

    /** 文字笔记增删（op 见 NOTE_*；空文本 upsert 被 Mac 视为 delete） */
    fun encodeTextNote(
        id: String,
        op: Int,
        page: Long,
        nx: Float,
        ny: Float,
        text: String,
        display: Int = NOTE_TAP,
    ): ByteArray = Writer().apply {
        u8(OP_TEXT_NOTE); str(id); u8(op); u32(page); f32(nx); f32(ny); str(text); u8(display)
    }.bytes()

    /**
     * 书签增删改**请求**（Mac 是唯一真源，落库后以 bookmarks 全量回推为准，别乐观改本地表）。
     * add 的 [id] 由本端生成 UUID 串（同 textNote 先例）；rename 只认 id+title、delete 只认 id，
     * 其余字段填 0 即可（`../PROTOCOL.md §4.1`）。
     */
    fun encodeBookmarkEdit(
        op: Int,
        id: String,
        page: Long = 0,
        frac: Float = 0f,
        title: String = "",
    ): ByteArray = Writer().apply {
        u8(OP_BOOKMARK_EDIT); u8(op); str(id); u32(page); f32(frac); str(title)
    }.bytes()

    /** 改笔宽后整表上行（Mac 按下标对齐写回；数目不符 Mac 整包丢弃） */
    fun encodePenset(active: Int, list: List<Pen>): ByteArray =
        Writer().apply { u8(OP_PENSET); u16(active); u16(list.size); for (p in list) pen(p) }.bytes()

    /** 橡皮设置上行（size = 归一化半径，mode 0=整笔 1=局部） */
    fun encodeEraser(size: Float, mode: Int, ring: Boolean): ByteArray =
        Writer().apply { u8(OP_ERASER); f32(size); u8(mode); u8(if (ring) 1 else 0) }.bytes()

    /**
     * 请求切画板模式（C→S）。**只有 `on` 有意义**，margin 一律编 0——页边宽度轮不到客户端定
     * （../PROTOCOL.md `canvas`）。Mac 执行后照旧广播权威值回来，本端那时才改布局。
     */
    fun encodeCanvas(on: Boolean): ByteArray =
        Writer().apply { u8(OP_CANVAS); u8(if (on) 1 else 0); f32(0f) }.bytes()

    /** 图层请求：切换当前作画图层（index = layers 列表下标） */
    fun encodeLayerSelect(index: Int): ByteArray =
        Writer().apply { u8(OP_LAYER_SELECT); u16(index) }.bytes()

    /** 图层请求：显示/隐藏某层 */
    fun encodeLayerVisible(index: Int, visible: Boolean): ByteArray =
        Writer().apply { u8(OP_LAYER_VISIBLE); u16(index); u8(if (visible) 1 else 0) }.bytes()

    /** 图层请求：新建（名字/颜色/顺序由 Mac 决定，追加后即为当前作画图层） */
    fun encodeLayerAdd(): ByteArray =
        Writer().apply { u8(OP_LAYER_ADD) }.bytes()

    /**
     * 框选移动提交：框选区域包围盒（Mac 用真源复判命中）+ 位移，均页内归一化。
     * [poly] = 自由框选路径（扁平数组，≥3 点）作尾部可选多边形——有它 Mac 按多边形命中复判，
     * 缺省按矩形（老形态字节不变，PROTOCOL.md §4.1）。
     */
    fun encodeLassoMove(
        page: Long, x0: Float, y0: Float, x1: Float, y1: Float, dx: Float, dy: Float,
        poly: FloatArray? = null,
    ): ByteArray = Writer().apply {
        u8(OP_LASSO_MOVE); u32(page); f32(x0); f32(y0); f32(x1); f32(y1); f32(dx); f32(dy)
        polyTail(poly)
    }.bytes()

    /**
     * 框选缩放提交（0x4A）：包围盒 + 缩放锚点 `(ax, ay)`（被拖手柄的对侧手柄）+ 按轴缩放比
     * `(sx, sy)`（调用方已 clamp 0.05...20），均页内归一化；[poly] 尾部语义同 lassoMove。
     */
    fun encodeLassoScale(
        page: Long, x0: Float, y0: Float, x1: Float, y1: Float,
        ax: Float, ay: Float, sx: Float, sy: Float,
        poly: FloatArray? = null,
    ): ByteArray = Writer().apply {
        u8(OP_LASSO_SCALE); u32(page); f32(x0); f32(y0); f32(x1); f32(y1)
        f32(ax); f32(ay); f32(sx); f32(sy)
        polyTail(poly)
    }.bytes()

    /** 打开/关闭草稿纸（index = scratchpads 列表下标；-1 = 关闭，线上 0xFFFF） */
    fun encodeScratchOpen(index: Int): ByteArray =
        Writer().apply { u8(OP_SCRATCH_OPEN); u16(if (index < 0) SCRATCH_NO_OPEN else index) }.bytes()

    /** 请求在指定页的页内归一化位置新建一张草稿纸（Mac 判定后回推 scratchpads） */
    fun encodeScratchAdd(page: Long, nx: Float, ny: Float): ByteArray =
        Writer().apply { u8(OP_SCRATCH_ADD); u32(page); f32(nx); f32(ny) }.bytes()

    /** 图钉同页内挪锚点（页内归一化 0~1；本地乐观预览，scratchpads 回推为权威——同 scratchPaper 惯例） */
    fun encodeScratchMove(index: Int, nx: Float, ny: Float): ByteArray =
        Writer().apply { u8(OP_SCRATCH_MOVE); u16(index); f32(nx); f32(ny) }.bytes()

    /** 改第 index 张纸的纸样：底色 r/g/b(u8)+a(f32) + 底纹（PATTERN_*，plain=0 照常上线） */
    fun encodeScratchPaper(index: Int, r: Int, g: Int, b: Int, a: Float, pattern: Int): ByteArray =
        Writer().apply {
            u8(OP_SCRATCH_PAPER); u16(index); u8(r); u8(g); u8(b); f32(a); u8(pattern)
        }.bytes()

    /** 开/关第 index 张纸的页面底图（把它锚定的那一页垫在纸下面，几何契约见 ../PROTOCOL.md §4.4） */
    fun encodeScratchPageShow(index: Int, show: Boolean): ByteArray =
        Writer().apply { u8(OP_SCRATCH_PAGE_SHOW); u16(index); u8(if (show) 1 else 0) }.bytes()

    /** 删第 index 张纸（连同纸上笔迹）。Mac 判定 + 落库后回推 scratchpads/scratchStrokes */
    fun encodeScratchDelete(index: Int): ByteArray =
        Writer().apply { u8(OP_SCRATCH_DELETE); u16(index) }.bytes()

    /** 改第 index 张纸的名字（空串 = 回到「草稿纸 N」兜底名） */
    fun encodeScratchRename(index: Int, title: String): ByteArray =
        Writer().apply { u8(OP_SCRATCH_RENAME); u16(index); str(title) }.bytes()

    // ---------- 解码（S→C）；未知 opcode / 坏帧返回 null，不崩 ----------

    fun decode(d: ByteArray): Msg? {
        if (d.isEmpty()) return null
        return try {
            val r = Reader(d)
            val op = r.u8()
            when (op) {
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
                // 两者 payload 逐字节相同，只差语义（整表替换 / 追加），故共用一段解码
                OP_STROKES, OP_STROKES_APPEND -> {
                    val ackRel = r.u32()
                    val n = r.u32()
                    val list = ArrayList<Stroke>()
                    var i = 0L
                    while (i < n && r.remaining > 0) { list.add(Stroke(r.u32(), r.pen(), r.pts3())); i++ }
                    Msg.Strokes(ackRel, list, append = op == OP_STROKES_APPEND)
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
                OP_LIBRARY -> {
                    val ws = r.str()
                    val n = r.u16()
                    val list = ArrayList<LibEntry>(n)
                    var i = 0
                    while (i < n && r.remaining >= 5) { list.add(LibEntry(r.str(), r.str(), r.u8() == 1)); i++ }
                    Msg.Library(ws, list)
                }
                OP_TOC -> {
                    val docId = r.str()
                    val n = r.u16()
                    val list = ArrayList<TocEntry>(n)
                    var i = 0
                    while (i < n && r.remaining >= 12) {
                        val depth = r.u8()
                        val hasPage = r.u8() == 1
                        val page = r.u32().toInt()
                        val frac = r.f32()
                        // 坏书签（hasPage=0）→ page = -1：据此渲染成不可点的灰行
                        list.add(TocEntry(depth, if (hasPage) page else -1, frac, r.str()))
                        i++
                    }
                    Msg.Toc(docId, list)
                }
                OP_BOOKMARKS -> {
                    val docId = r.str()
                    val n = r.u16()
                    val list = ArrayList<BookmarkEntry>(n)
                    var i = 0
                    // 单条最短 12 字节（两条空 str 各 2 + u32 + f32）
                    while (i < n && r.remaining >= 12) {
                        val id = r.str()
                        val page = r.u32().toInt()
                        val frac = r.f32()
                        list.add(BookmarkEntry(id, page, frac, r.str()))
                        i++
                    }
                    Msg.Bookmarks(docId, list)
                }
                OP_SCRATCH_PADS -> {
                    val openRaw = r.u16()
                    val n = r.u16()
                    val list = ArrayList<ScratchPadEntry>(n)
                    var i = 0
                    // 单条最短 21 字节（两条空 str + u32 + 2×f32 + 3×u8 + f32 + u8 + u8 showPage）
                    while (i < n && r.remaining >= 21) {
                        list.add(
                            ScratchPadEntry(
                                r.str(), r.str(), r.u32(), r.f32(), r.f32(),
                                r.u8(), r.u8(), r.u8(), r.f32(), patternOrDefault(r.u8()),
                                r.u8() != 0,
                            ),
                        ); i++
                    }
                    Msg.ScratchPads(if (openRaw == SCRATCH_NO_OPEN) -1 else openRaw, list)
                }
                OP_NOTE_NEW -> Msg.NoteNew(r.u32(), r.f32(), r.f32())
                OP_SCRATCH_STROKES -> {
                    val ackRel = r.u32()
                    val n = r.u32()
                    val list = ArrayList<Stroke>()
                    var i = 0L
                    // 无 page 字段（区别于 strokes）：page 恒 0，pts 是画布坐标
                    while (i < n && r.remaining > 0) { list.add(Stroke(0, r.pen(), r.pts3())); i++ }
                    Msg.ScratchStrokes(ackRel, list)
                }
                OP_NOTES -> {
                    val n = r.u16()
                    val list = ArrayList<TextNote>(n)
                    var i = 0
                    while (i < n && r.remaining > 0) {
                        // 尾部 u8 = 展开方式（0=点击 1=悬停 2=始终），见 ../PROTOCOL.md §4.2
                        list.add(TextNote(r.str(), r.u32(), r.f32(), r.f32(), r.str(), r.u8())); i++
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
                OP_CANVAS -> Msg.Canvas(r.u8() != 0, r.f32())
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
