package com.xvan.unireader.pad

import com.xvan.unireader.shared.Layer
import com.xvan.unireader.shared.MODE_ERASE
import com.xvan.unireader.shared.MODE_LASSO
import com.xvan.unireader.shared.NOTE_ALWAYS
import com.xvan.unireader.shared.NOTE_HOVER
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.RK_ERASE
import com.xvan.unireader.shared.RK_PAGE
import com.xvan.unireader.shared.RK_PEN
import com.xvan.unireader.shared.RK_SCRATCH
import com.xvan.unireader.shared.RK_TEXT
import com.xvan.unireader.shared.TextNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字节级一致性测试：对照 spike/wire-vectors-swift.txt 的**全部 84 条** canonical 向量。
 * 每条向量对应的 canonical 消息值见 spike/wire-codec-test.swift 的 canonical 表（行号一一对应）；
 * Swift 那张表只允许在末尾追加新消息，故行号恒定（往中间插会静默错位掉整套跨语言凭据）。
 * 编码类断言 encode 结果逐字节等于 hex；解码类断言 decode(hex) 的字段正确。
 * 只有 S→C 的 page/layout/viewport/... 不做编码比对（平板永远不发它们），反之亦然。
 */
class WireCodecTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** canonical 笔：rgba(24,90,210,0.5) w8 ballpoint（Swift 表 #17/#23 同款） */
    private val pen = Pen(24, 90, 210, 0.5f, 8f, 0)

    @Test
    fun encodeVectors() {
        val cases: List<Pair<Int, ByteArray>> = listOf(
            // #1 auth{token:"abc123"}
            1 to WireCodec.encodeAuth("abc123"),
            // #5 ping{t:1700000000000}
            5 to WireCodec.encodePing(1700000000000.0),
            // #9 pageTurn{dir:"next"}
            9 to WireCodec.encodePageTurn(WireCodec.DIR_NEXT),
            // #10 mode{mode:"erase"}
            10 to WireCodec.encodeMode(MODE_ERASE),
            // #11 pen{index:3}
            11 to WireCodec.encodePen(3),
            // #20 scroll{page:2, frac:0.5, t:123456}
            20 to WireCodec.encodeScroll(2, 0.5f, 123456.0),
            // #21 hover move{page:1, nx:0.5, ny:0.25}
            21 to WireCodec.encodeHoverMove(1, 0.5f, 0.25f),
            // #22 hover end
            22 to WireCodec.encodeHoverEnd(),
            // #23 ink begin{page:0, pen, pts:[[0.5,0.5,0.5]]}
            23 to WireCodec.encodeInkBegin(0, pen, listOf(Pt3(0.5f, 0.5f, 0.5f))),
            // #24 ink move{pts:[[0.25,0.75,0.5],[0.5,0.5,1.0]]}
            24 to WireCodec.encodeInkMove(listOf(Pt3(0.25f, 0.75f, 0.5f), Pt3(0.5f, 0.5f, 1.0f))),
            // #25 ink end
            25 to WireCodec.encodeInkEnd(),
            // #26 erase move{page:1, pts:[[0.5,0.5],[0.25,0.25]]}
            26 to WireCodec.encodeEraseMove(1, listOf(Pt2(0.5f, 0.5f), Pt2(0.25f, 0.25f))),
            // #27 erase end
            27 to WireCodec.encodeEraseEnd(),
            // #7 latency{ms:42}
            7 to WireCodec.encodeLatency(42f),
            // #8 selectDoc{id:"1A2B"}
            8 to WireCodec.encodeSelectDoc("1A2B"),
            // #28 probe begin{page:2, pts:[[0.5,0.5]]}
            28 to WireCodec.encodeProbeBegin(2, listOf(Pt2(0.5f, 0.5f))),
            // #29 probe move{pts:[[0.25,0.25]]}
            29 to WireCodec.encodeProbeMove(listOf(Pt2(0.25f, 0.25f))),
            // #30 probe end
            30 to WireCodec.encodeProbeEnd(),
            // #35 padGeom{pageW:1024}
            35 to WireCodec.encodePadGeom(1024f),
            // #38 textNote upsert{id:"n1", page:2, nx:0.5, ny:0.25, text:"批注"}
            38 to WireCodec.encodeTextNote("n1", WireCodec.NOTE_UPSERT, 2, 0.5f, 0.25f, "批注"),
            // #39 textNote delete{id:"n1", page:2, nx:0.5, ny:0.25, text:""}
            39 to WireCodec.encodeTextNote("n1", WireCodec.NOTE_DELETE, 2, 0.5f, 0.25f, ""),
            // #41 penset{active:1, list:[ballpoint w8, marker w22]}（布局同 pens）
            41 to WireCodec.encodePenset(
                1,
                listOf(pen, Pen(255, 214, 40, 0.25f, 22f, 2)),
            ),
            // #42 eraser{size:0.02, mode:1(局部), ring:1}
            42 to WireCodec.encodeEraser(0.02f, 1, true),
            // #43 eraser{size:0.5, mode:0(整笔), ring:0}
            43 to WireCodec.encodeEraser(0.5f, 0, false),
            // #44 ink begin 带 line=true（尺子笔：整笔恒为两点）
            44 to WireCodec.encodeInkBegin(0, pen, listOf(Pt3(0.5f, 0.5f, 0.5f)), line = true),
            // #46 layerSelect{index:1}
            46 to WireCodec.encodeLayerSelect(1),
            // #53 openDoc{id:"D1E2F3"}（库文档 id，不是 docs 的窗口会话 id）
            53 to WireCodec.encodeOpenDoc("D1E2F3"),
            // #56 gotoPage 带尾部可选 frac：{page:7, frac:0.5}
            56 to WireCodec.encodeGotoDest(7, 0.5f),
            // #47 layerVisible{index:0, visible:false}
            47 to WireCodec.encodeLayerVisible(0, false),
            // #48 layerAdd
            48 to WireCodec.encodeLayerAdd(),
            // #49 mode{mode:"lasso"}
            49 to WireCodec.encodeMode(MODE_LASSO),
            // #50 lassoMove{page:2, box(0.2,0.3)-(0.6,0.5), d(0.1,-0.05)}
            50 to WireCodec.encodeLassoMove(2, 0.2f, 0.3f, 0.6f, 0.5f, 0.1f, -0.05f),
            // #51 gotoPage{page:42}
            51 to WireCodec.encodeGotoPage(42),
            // #60 scratchOpen{index:2}
            60 to WireCodec.encodeScratchOpen(2),
            // #61 scratchOpen{index:-1}（关闭 → 线上 0xFFFF）
            61 to WireCodec.encodeScratchOpen(-1),
            // #62 scratchAdd{page:5, nx:0.75, ny:0.25}
            62 to WireCodec.encodeScratchAdd(5, 0.75f, 0.25f),
            // #63 scratchPaper{index:1, bg:rgba(246,236,214,1.0), pattern:grid}
            63 to WireCodec.encodeScratchPaper(1, 246, 236, 214, 1.0f, WireCodec.PATTERN_GRID),
            // #64 scratchPaper{index:0, pattern:plain}——plain=0，专防 `?: 1` 兜底把它吃成 dots
            64 to WireCodec.encodeScratchPaper(0, 255, 255, 255, 1.0f, WireCodec.PATTERN_PLAIN),
            // #66 scratchMove{index:0, nx:0, ny:1.0}
            66 to WireCodec.encodeScratchMove(0, 0f, 1.0f),
            // #67 scratchMove{index:65535, nx:1.0, ny:0}（u16 上界）
            67 to WireCodec.encodeScratchMove(0xFFFF, 1.0f, 0f),
            // #70 scratchPageShow{index:0, show:true}（v10 页面底图开关）
            70 to WireCodec.encodeScratchPageShow(0, true),
            // #71 scratchPageShow{index:65535, show:false}——show=0 专防被 `!= 0` 之外的兜底吃掉
            71 to WireCodec.encodeScratchPageShow(0xFFFF, false),
            // #72 scratchDelete{index:0}
            72 to WireCodec.encodeScratchDelete(0),
            // #73 scratchDelete{index:65535}（u16 上界）
            73 to WireCodec.encodeScratchDelete(0xFFFF),
            // #74 scratchRename{index:3, title:"第三张·推导"}（非 ASCII 走 str 的 UTF-8 长度前缀）
            74 to WireCodec.encodeScratchRename(3, "第三张·推导"),
            // #75 scratchRename{index:0, title:""}（空串 = 回到「草稿纸 N」兜底名）
            75 to WireCodec.encodeScratchRename(0, ""),
            // #76 lassoMove 带尾部多边形：{page:2, box(0.2,0.3)-(0.6,0.5), d(0.1,-0.05), poly 3 点}
            76 to WireCodec.encodeLassoMove(
                2, 0.2f, 0.3f, 0.6f, 0.5f, 0.1f, -0.05f,
                floatArrayOf(0.2f, 0.3f, 0.6f, 0.3f, 0.4f, 0.5f),
            ),
            // #77 lassoScale{page:1, box(0.2,0.3)-(0.6,0.5), a(0.2,0.3), s(1.5,0.75)}
            77 to WireCodec.encodeLassoScale(1, 0.2f, 0.3f, 0.6f, 0.5f, 0.2f, 0.3f, 1.5f, 0.75f),
            // #78 lassoScale 带尾部多边形（poly 4 点）
            78 to WireCodec.encodeLassoScale(
                1, 0.2f, 0.3f, 0.6f, 0.5f, 0.2f, 0.3f, 1.5f, 0.75f,
                floatArrayOf(0.2f, 0.3f, 0.6f, 0.3f, 0.6f, 0.5f, 0.2f, 0.5f),
            ),
            // #79 textNote 带展开方式：{id:"n3", upsert, page:1, (0.25,0.5), "悬浮", display=悬浮}
            // display=点击(0) 的老形态由 #38/#39 覆盖（它们的字节各多了一个 00 尾字节）
            79 to WireCodec.encodeTextNote("n3", WireCodec.NOTE_UPSERT, 1, 0.25f, 0.5f, "悬浮", NOTE_HOVER),
        )
        for ((line, bytes) in cases) {
            assertEquals("向量#$line 编码不一致", VECTORS[line - 1], hex(bytes))
        }
    }

    @Test
    fun decodeVectors() {
        // #2 authOK{session:0, udpPort:0}
        val m2 = WireCodec.decode(unhex(VECTORS[1])) as WireCodec.Msg.AuthOK
        assertEquals(0L, m2.session)
        assertEquals(0, m2.udpPort)

        // #3 authOK{session:0x12345678, udpPort:8772}
        val m3 = WireCodec.decode(unhex(VECTORS[2])) as WireCodec.Msg.AuthOK
        assertEquals(0x12345678L, m3.session)
        assertEquals(8772, m3.udpPort)

        // #4 authFail
        assertTrue(WireCodec.decode(unhex(VECTORS[3])) is WireCodec.Msg.AuthFail)

        // #6 pong{t:1700000000000}
        val m6 = WireCodec.decode(unhex(VECTORS[5])) as WireCodec.Msg.Pong
        assertEquals(1700000000000.0, m6.t, 0.0)

        // #10 mode{mode:"erase"}（双向消息，解码回 mode 码）
        val m10 = WireCodec.decode(unhex(VECTORS[9])) as WireCodec.Msg.ModeSel
        assertEquals(MODE_ERASE, m10.mode)

        // #11 pen{index:3}
        val m11 = WireCodec.decode(unhex(VECTORS[10])) as WireCodec.Msg.PenSel
        assertEquals(3, m11.index)

        // #12 page{v:5, index:2, count:100, w:612, h:792}
        val m12 = WireCodec.decode(unhex(VECTORS[11])) as WireCodec.Msg.Page
        assertEquals(5L, m12.v)
        assertEquals(2L, m12.index)
        assertEquals(100L, m12.count)
        assertEquals(612f, m12.w, 0f)
        assertEquals(792f, m12.h, 0f)

        // #13 layout{docId:"H", v:"H", count:2, pages:[[612,792],[595,842]]}
        val m13 = WireCodec.decode(unhex(VECTORS[12])) as WireCodec.Msg.Layout
        assertEquals("H", m13.docId)
        assertEquals("H", m13.v)
        assertEquals(2L, m13.count)
        assertEquals(listOf(612f to 792f, 595f to 842f), m13.pages)

        // #14 viewport{page:3, frac:0.5, seq:7}（无 force → false）
        val m14 = WireCodec.decode(unhex(VECTORS[13])) as WireCodec.Msg.Viewport
        assertEquals(3L, m14.page)
        assertEquals(0.5f, m14.frac, 0f)
        assertEquals(7L, m14.seq)
        assertEquals(false, m14.force)

        // #15 viewport{page:3, frac:0.25, force:true}（seq 字段缺省为 0）
        val m15 = WireCodec.decode(unhex(VECTORS[14])) as WireCodec.Msg.Viewport
        assertEquals(3L, m15.page)
        assertEquals(0.25f, m15.frac, 0f)
        assertEquals(0L, m15.seq)
        assertEquals(true, m15.force)

        // #17 pens{active:1, list:[rgba(24,90,210,0.5) w8 ballpoint, rgba(255,214,40,0.25) w22 marker]}
        val m17 = WireCodec.decode(unhex(VECTORS[16])) as WireCodec.Msg.Pens
        assertEquals(1, m17.active)
        assertEquals(2, m17.list.size)
        assertEquals(Pen(24, 90, 210, 0.5f, 8f, 0), m17.list[0])
        assertEquals(Pen(255, 214, 40, 0.25f, 22f, 2), m17.list[1])

        // #18 inkCancel
        assertTrue(WireCodec.decode(unhex(VECTORS[17])) is WireCodec.Msg.InkCancel)

        // #19 strokes{ackRel 缺省=0；1 条：page 1, rgba(20,20,20,1) w10 pencil, pts [[0.5,0.25,0.5],[0.75,0.125,1.0]]}
        val m19 = WireCodec.decode(unhex(VECTORS[18])) as WireCodec.Msg.Strokes
        assertEquals("没建 UDP 会话时线上就是 0（浏览器恒如此）", 0L, m19.ackRel)
        assertEquals(1, m19.list.size)
        val s19 = m19.list[0]
        assertEquals(1L, s19.page)
        assertEquals(Pen(20, 20, 20, 1.0f, 10f, 3), s19.pen)
        assertEquals(listOf(Pt3(0.5f, 0.25f, 0.5f), Pt3(0.75f, 0.125f, 1.0f)), s19.pts)

        // #52 strokes 带非零 ackRel（0x36 首字段）：擦除中途快照的判据全靠它，见 PROTOCOL.md §4.2
        val m52 = WireCodec.decode(unhex(VECTORS[51])) as WireCodec.Msg.Strokes
        assertEquals(305419896L, m52.ackRel)
        assertEquals(1, m52.list.size)
        assertEquals(listOf(Pt3(0.5f, 0.25f, 0.5f), Pt3(0.75f, 0.125f, 1.0f)), m52.list[0].pts)

        // #54 library{ws:"阅读", list:[{A1,…,open}, {B2,SICP,未开}]}
        val m54 = WireCodec.decode(unhex(VECTORS[53])) as WireCodec.Msg.Library
        assertEquals("阅读", m54.ws)
        assertEquals(
            listOf(
                WireCodec.LibEntry("A1", "深入理解计算机系统", true),
                WireCodec.LibEntry("B2", "SICP", false),
            ),
            m54.list,
        )

        // #55 toc：先序拍平 + depth；第三条是坏书签（线上 hasPage=0 → 解出来 page = -1）
        val m55 = WireCodec.decode(unhex(VECTORS[54])) as WireCodec.Msg.Toc
        assertEquals("abc123", m55.docId)
        assertEquals(
            listOf(
                WireCodec.TocEntry(0, 0, 0f, "第一章"),
                WireCodec.TocEntry(1, 4, 0.25f, "1.1 引言"),
                WireCodec.TocEntry(0, -1, 0f, "坏书签"),
            ),
            m55.list,
        )

        // #31 nack{seqs:[1, 2, 3000000000]}
        val m31 = WireCodec.decode(unhex(VECTORS[30])) as WireCodec.Msg.Nack
        assertEquals(listOf(1L, 2L, 3000000000L), m31.seqs)

        // #16 docs{list:[{a,T1},{b,标题}], selected:"a", following:false}
        val m16 = WireCodec.decode(unhex(VECTORS[15])) as WireCodec.Msg.Docs
        assertEquals(false, m16.following)
        assertEquals("a", m16.selected)
        assertEquals(listOf(WireCodec.DocEntry("a", "T1"), WireCodec.DocEntry("b", "标题")), m16.list)

        // #32 radial{open:false}
        val m32 = WireCodec.decode(unhex(VECTORS[31])) as WireCodec.Msg.Radial
        assertEquals(false, m32.open)

        // #33 radial{open, page:4, cx:0.5, cy:0.25, highlight:2, items:[pen, erase, page]}
        val m33 = WireCodec.decode(unhex(VECTORS[32])) as WireCodec.Msg.Radial
        assertTrue(m33.open)
        assertEquals(4L, m33.page)
        assertEquals(0.5f, m33.cx, 0f)
        assertEquals(0.25f, m33.cy, 0f)
        assertEquals(2, m33.highlight)
        assertEquals(3, m33.items.size)
        assertEquals(RK_PEN, m33.items[0].kind)
        assertEquals(pen, m33.items[0].pen)
        assertEquals(RK_ERASE, m33.items[1].kind)
        assertEquals(RK_PAGE, m33.items[2].kind)

        // #34 radial{open, highlight:-1(线上 0xFFFF＝中心取消区), items:[]}
        val m34 = WireCodec.decode(unhex(VECTORS[33])) as WireCodec.Msg.Radial
        assertTrue(m34.open)
        assertEquals(-1, m34.highlight)
        assertEquals(0, m34.items.size)

        // #36 pressRing{on:false}
        val m36 = WireCodec.decode(unhex(VECTORS[35])) as WireCodec.Msg.PressRing
        assertEquals(false, m36.on)

        // #37 pressRing{on:true, page:3, nx:0.5, ny:0.25}
        val m37 = WireCodec.decode(unhex(VECTORS[36])) as WireCodec.Msg.PressRing
        assertTrue(m37.on)
        assertEquals(3L, m37.page)
        assertEquals(0.5f, m37.nx, 0f)
        assertEquals(0.25f, m37.ny, 0f)

        // #40 notes{list:[{n1,0,0.5,0.5,"hello"},{n2,3,0.25,0.75,"笔记"}]}
        val m40 = WireCodec.decode(unhex(VECTORS[39])) as WireCodec.Msg.Notes
        assertEquals(
            listOf(
                TextNote("n1", 0, 0.5f, 0.5f, "hello"),
                TextNote("n2", 3, 0.25f, 0.75f, "笔记"),
            ),
            m40.list,
        )

        // #80 notes 带展开方式：display 逐条不同（0 之外的两个值最容易被兜底吃掉）
        val m80 = WireCodec.decode(unhex(VECTORS[79])) as WireCodec.Msg.Notes
        assertEquals(
            listOf(
                TextNote("n1", 0, 0.5f, 0.5f, "hello", NOTE_ALWAYS),
                TextNote("n2", 3, 0.25f, 0.75f, "笔记", NOTE_HOVER),
            ),
            m80.list,
        )

        // #42/#43 eraser（双向消息，S→C 方向解码）
        val m42 = WireCodec.decode(unhex(VECTORS[41])) as WireCodec.Msg.Eraser
        assertEquals(0.02f, m42.size, 0f)
        assertEquals(1, m42.mode)
        assertTrue(m42.ring)
        val m43 = WireCodec.decode(unhex(VECTORS[42])) as WireCodec.Msg.Eraser
        assertEquals(0.5f, m43.size, 0f)
        assertEquals(0, m43.mode)
        assertEquals(false, m43.ring)

        // #45 layers{active:1, list:[老师批注(可见), My Notes(隐藏)]}
        val m45 = WireCodec.decode(unhex(VECTORS[44])) as WireCodec.Msg.Layers
        assertEquals(1, m45.active)
        assertEquals(
            listOf(
                Layer(255, 149, 0, true, "老师批注"),
                Layer(0, 122, 255, false, "My Notes"),
            ),
            m45.list,
        )

        // #49 mode{mode:"lasso"}（第 4 态）
        val m49 = WireCodec.decode(unhex(VECTORS[48])) as WireCodec.Msg.ModeSel
        assertEquals(MODE_LASSO, m49.mode)

        // #57 scratchpads{open:1, list:[P1(推导,dots,页面底图开), P2(空标题,grid,关)]}；bg 线上拆 r/g/b/a
        val m57 = WireCodec.decode(unhex(VECTORS[56])) as WireCodec.Msg.ScratchPads
        assertEquals(1, m57.open)
        assertEquals(
            listOf(
                WireCodec.ScratchPadEntry(
                    "P1", "推导", 3, 0.25f, 0.5f, 255, 255, 255, 1.0f, WireCodec.PATTERN_DOTS,
                    showPage = true,
                ),
                WireCodec.ScratchPadEntry(
                    "P2", "", 0, 0.5f, 0.125f, 250, 248, 240, 1.0f, WireCodec.PATTERN_GRID,
                    showPage = false,
                ),
            ),
            m57.list,
        )

        // #58 scratchpads{open:-1(线上 0xFFFF), list:[]}
        val m58 = WireCodec.decode(unhex(VECTORS[57])) as WireCodec.Msg.ScratchPads
        assertEquals(-1, m58.open)
        assertEquals(0, m58.list.size)

        // #59 scratchStrokes{ackRel:7, 1 条无 page 的笔迹}；pts 是画布坐标（可负无界）
        val m59 = WireCodec.decode(unhex(VECTORS[58])) as WireCodec.Msg.ScratchStrokes
        assertEquals(7L, m59.ackRel)
        assertEquals(1, m59.list.size)
        val s59 = m59.list[0]
        assertEquals(0L, s59.page)
        assertEquals(Pen(20, 20, 20, 1.0f, 10f, 3), s59.pen)
        assertEquals(listOf(Pt3(-120.5f, 64.25f, 0.5f), Pt3(512.0f, -8.125f, 1.0f)), s59.pts)

        // #65 radial{open, page:2, cx:0.5, cy:0.5, highlight:3, items:[pen, scratchAdd, textNote]}——
        // kind≠0 的项 pen 字段是 10 字节占位 0，照旧读掉
        val m65 = WireCodec.decode(unhex(VECTORS[64])) as WireCodec.Msg.Radial
        assertTrue(m65.open)
        assertEquals(2L, m65.page)
        assertEquals(0.5f, m65.cx, 0f)
        assertEquals(0.5f, m65.cy, 0f)
        assertEquals(3, m65.highlight)
        assertEquals(3, m65.items.size)
        assertEquals(RK_PEN, m65.items[0].kind)
        assertEquals(pen, m65.items[0].pen)
        assertEquals(RK_SCRATCH, m65.items[1].kind)
        assertEquals(RK_TEXT, m65.items[2].kind)
        assertEquals(Pen(0, 0, 0, 1.0f, 0f, 0), m65.items[1].pen)   // 占位 pen 原样读出
        assertEquals(Pen(0, 0, 0, 1.0f, 0f, 0), m65.items[2].pen)

        // #68 noteNew{page:0, nx:0, ny:1.0}
        val m68 = WireCodec.decode(unhex(VECTORS[67])) as WireCodec.Msg.NoteNew
        assertEquals(0L, m68.page)
        assertEquals(0f, m68.nx, 0f)
        assertEquals(1.0f, m68.ny, 0f)

        // #69 noteNew{page:0x12345678, nx:1.0, ny:0}
        val m69 = WireCodec.decode(unhex(VECTORS[68])) as WireCodec.Msg.NoteNew
        assertEquals(0x12345678L, m69.page)
        assertEquals(1.0f, m69.nx, 0f)
        assertEquals(0f, m69.ny, 0f)
    }

    @Test
    fun authOkEmptyPayload() {
        // 兼容旧版空 payload authOK → session/udpPort = 0
        val m = WireCodec.decode(byteArrayOf(WireCodec.OP_AUTH_OK.toByte())) as WireCodec.Msg.AuthOK
        assertEquals(0L, m.session)
        assertEquals(0, m.udpPort)
    }

    @Test
    fun decodeCanvas() {
        // #81~83 canvas（画板模式）：关 / 起步一档 / 跳过几档
        val off = WireCodec.decode(unhex(VECTORS[80])) as WireCodec.Msg.Canvas
        assertEquals(false, off.on)
        assertEquals(0f, off.margin, 0f)

        val one = WireCodec.decode(unhex(VECTORS[81])) as WireCodec.Msg.Canvas
        assertTrue(one.on)
        assertEquals(0.5f, one.margin, 0f)

        val far = WireCodec.decode(unhex(VECTORS[82])) as WireCodec.Msg.Canvas
        assertTrue(far.on)
        assertEquals(2.5f, far.margin, 0f)

        // #84 C→S 形态：本端编出来的请求帧要与向量逐字节相同（margin 恒 0）
        assertEquals(VECTORS[83], hex(WireCodec.encodeCanvas(true)))
    }

    /** 行号即凭据：表长变了说明上游 canonical 表动过，先核对再改这里（往中间插会整套错位） */
    @Test
    fun vectorTableSize() {
        assertEquals(84, VECTORS.size)
    }

    @Test
    fun badFramesReturnNull() {
        assertNull(WireCodec.decode(ByteArray(0)))                 // 空
        assertNull(WireCodec.decode(unhex("ff")))                  // 未知 opcode
        assertNull(WireCodec.decode(unhex("42")))                  // ink 截断（缺 phase）
        assertNull(WireCodec.decode(unhex("400100")))              // scroll 截断
        assertNull(WireCodec.decode(unhex("01ffff")))              // auth: str 长度越界
    }

    companion object {
        /** spike/wire-vectors-swift.txt 原样 84 行（只在末尾追加，行号即 canonical 表序号） */
        val VECTORS = listOf(
            "010600616263313233",
            "02000000000000",
            "02785634124422",
            "03",
            "1000008056febc7842",
            "1100008056febc7842",
            "1200002842",
            "20040031413242",
            "2101",
            "2201",
            "230300",
            "300500000002000000640000000000194400004644",
            "3101004801004802000000000019440000464400c0144400805244",
            "32030000000000003f0700000000",
            "32030000000000803e0000000001",
            "33000100610200010061020054310100620600e6a087e9a298",
            "3401000200185ad20000003f0000004100ffd6280000803e0000b04102",
            "35",
            "360000000001000000010000001414140000803f000020410302000000003f0000803e0000003f0000403f0000003e0000803f",
            "40020000000000003f000000000024fe40",
            "4101010000000000003f0000803e",
            "4102",
            "420000000000185ad20000003f000000410001000000003f0000003f0000003f00",
            "420102000000803e0000403f0000003f0000003f0000003f0000803f",
            "4202",
            "43010100000002000000003f0000003f0000803e0000803e",
            "4302",
            "44000200000001000000003f0000003f",
            "440101000000803e0000803e",
            "4402",
            "5003000100000002000000005ed0b2",
            "3700",
            "3701040000000000003f0000803e0200030000185ad20000003f0000004100010000000000803f0000000000020000000000803f0000000000",
            "3701000000000000803e0000403fffff0000",
            "4500008044",
            "3800",
            "3801030000000000003f0000803e",
            "2402006e3100020000000000003f0000803e0600e689b9e6b3a800",
            "2402006e3101020000000000003f0000803e000000",
            "39020002006e31000000000000003f0000003f050068656c6c6f0002006e32030000000000803e0000403f0600e7ac94e8aeb000",
            "2501000200185ad20000003f0000004100ffd6280000803e0000b04102",
            "460ad7a33c0101",
            "460000003f0000",
            "420000000000185ad20000003f000000410001000000003f0000003f0000003f01",
            "3a01000200ff9500010c00e88081e5b888e689b9e6b3a8007aff0008004d79204e6f746573",
            "260100",
            "27000000",
            "28",
            "2203",
            "4702000000cdcc4c3e9a99993e9a99193f0000003fcdcccc3dcdcc4cbd",
            "292a000000",
            "367856341201000000010000001414140000803f000020410302000000003f0000803e0000003f0000403f0000003e0000803f",
            "2a0600443145324633",
            "3b0600e99885e8afbb0200020041311b00e6b7b1e585a5e79086e8a7a3e8aea1e7ae97e69cbae7b3bbe7bb9f010200423204005349435000",
            "3c06006162633132330300000100000000000000000900e7acace4b880e7aba00101040000000000803e0a00312e3120e5bc95e8a880000000000000000000000900e59d8fe4b9a6e7adbe",
            "29070000000000003f",
            "3d01000200020050310600e68ea8e5afbc030000000000803e0000003fffffff0000803f0101020050320000000000000000003f0000003efaf8f00000803f0200",
            "3dffff0000",
            "3e07000000010000001414140000803f000020410302000000f1c2008080420000003f00000044000002c10000803f",
            "2b0200",
            "2bffff",
            "2c050000000000403f0000803e",
            "2d0100f6ecd60000803f02",
            "2d0000ffffff0000803f00",
            "3701020000000000003f0000003f0300030000185ad20000003f0000004100030000000000803f0000000000040000000000803f0000000000",
            "2e0000000000000000803f",
            "2effff0000803f00000000",
            "3f00000000000000000000803f",
            "3f785634120000803f00000000",
            "2f000001",
            "2fffff00",
            "480000",
            "48ffff",
            "4903001100e7acace4b889e5bca0c2b7e68ea8e5afbc",
            "4900000000",
            "4702000000cdcc4c3e9a99993e9a99193f0000003fcdcccc3dcdcc4cbd0300cdcc4c3e9a99993e9a99193f9a99993ecdcccc3e0000003f",
            "4a01000000cdcc4c3e9a99993e9a99193f0000003fcdcc4c3e9a99993e0000c03f0000403f",
            "4a01000000cdcc4c3e9a99993e9a99193f0000003fcdcc4c3e9a99993e0000c03f0000403f0400cdcc4c3e9a99993e9a99193f9a99993e9a99193f0000003fcdcc4c3e0000003f",
            "2402006e3300010000000000803e0000003f0600e682ace6b5ae01",
            "39020002006e31000000000000003f0000003f050068656c6c6f0202006e32030000000000803e0000403f0600e7ac94e8aeb001",
            // #81~83 canvas（0x4B，S→C）：u8 on · f32 margin
            "4b0000000000",
            "4b010000003f",
            "4b0100002040",
            // #84 canvas 的 C→S 形态（只有 on 有意义，margin 恒 0）
            "4b0100000000",
        )
    }
}
