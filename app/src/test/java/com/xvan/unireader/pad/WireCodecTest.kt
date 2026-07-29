package com.xvan.unireader.pad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字节级一致性测试：对照 spike/wire-vectors-swift.txt 的**全部 51 条** canonical 向量。
 * 每条向量对应的 canonical 消息值见 spike/wire-codec-test.swift 的 canonical 表（行号一一对应）；
 * Swift 那张表只允许在末尾追加新消息，故行号恒定（往中间插会静默错位掉整套跨语言凭据）。
 * 编码类断言 encode 结果逐字节等于 hex；解码类断言 decode(hex) 的字段正确。
 * 只有 S→C 的 page/layout/viewport/... 不做编码比对（平板永远不发它们），反之亦然。
 */
class WireCodecTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** canonical 笔：rgba(24,90,210,0.5) w8 ballpoint（Swift 表 #17/#23 同款） */
    private val pen = WireCodec.Pen(24, 90, 210, 0.5f, 8f, 0)

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
            10 to WireCodec.encodeMode(WireCodec.MODE_ERASE),
            // #11 pen{index:3}
            11 to WireCodec.encodePen(3),
            // #20 scroll{page:2, frac:0.5, t:123456}
            20 to WireCodec.encodeScroll(2, 0.5f, 123456.0),
            // #21 hover move{page:1, nx:0.5, ny:0.25}
            21 to WireCodec.encodeHoverMove(1, 0.5f, 0.25f),
            // #22 hover end
            22 to WireCodec.encodeHoverEnd(),
            // #23 ink begin{page:0, pen, pts:[[0.5,0.5,0.5]]}
            23 to WireCodec.encodeInkBegin(0, pen, listOf(WireCodec.Pt3(0.5f, 0.5f, 0.5f))),
            // #24 ink move{pts:[[0.25,0.75,0.5],[0.5,0.5,1.0]]}
            24 to WireCodec.encodeInkMove(listOf(WireCodec.Pt3(0.25f, 0.75f, 0.5f), WireCodec.Pt3(0.5f, 0.5f, 1.0f))),
            // #25 ink end
            25 to WireCodec.encodeInkEnd(),
            // #26 erase move{page:1, pts:[[0.5,0.5],[0.25,0.25]]}
            26 to WireCodec.encodeEraseMove(1, listOf(WireCodec.Pt2(0.5f, 0.5f), WireCodec.Pt2(0.25f, 0.25f))),
            // #27 erase end
            27 to WireCodec.encodeEraseEnd(),
            // #7 latency{ms:42}
            7 to WireCodec.encodeLatency(42f),
            // #8 selectDoc{id:"1A2B"}
            8 to WireCodec.encodeSelectDoc("1A2B"),
            // #28 probe begin{page:2, pts:[[0.5,0.5]]}
            28 to WireCodec.encodeProbeBegin(2, listOf(WireCodec.Pt2(0.5f, 0.5f))),
            // #29 probe move{pts:[[0.25,0.25]]}
            29 to WireCodec.encodeProbeMove(listOf(WireCodec.Pt2(0.25f, 0.25f))),
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
                listOf(pen, WireCodec.Pen(255, 214, 40, 0.25f, 22f, 2)),
            ),
            // #42 eraser{size:0.02, mode:1(局部), ring:1}
            42 to WireCodec.encodeEraser(0.02f, 1, true),
            // #43 eraser{size:0.5, mode:0(整笔), ring:0}
            43 to WireCodec.encodeEraser(0.5f, 0, false),
            // #44 ink begin 带 line=true（尺子笔：整笔恒为两点）
            44 to WireCodec.encodeInkBegin(0, pen, listOf(WireCodec.Pt3(0.5f, 0.5f, 0.5f)), line = true),
            // #46 layerSelect{index:1}
            46 to WireCodec.encodeLayerSelect(1),
            // #47 layerVisible{index:0, visible:false}
            47 to WireCodec.encodeLayerVisible(0, false),
            // #48 layerAdd
            48 to WireCodec.encodeLayerAdd(),
            // #49 mode{mode:"lasso"}
            49 to WireCodec.encodeMode(WireCodec.MODE_LASSO),
            // #50 lassoMove{page:2, box(0.2,0.3)-(0.6,0.5), d(0.1,-0.05)}
            50 to WireCodec.encodeLassoMove(2, 0.2f, 0.3f, 0.6f, 0.5f, 0.1f, -0.05f),
            // #51 gotoPage{page:42}
            51 to WireCodec.encodeGotoPage(42),
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
        assertEquals(WireCodec.MODE_ERASE, m10.mode)

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
        assertEquals(WireCodec.Pen(24, 90, 210, 0.5f, 8f, 0), m17.list[0])
        assertEquals(WireCodec.Pen(255, 214, 40, 0.25f, 22f, 2), m17.list[1])

        // #18 inkCancel
        assertTrue(WireCodec.decode(unhex(VECTORS[17])) is WireCodec.Msg.InkCancel)

        // #19 strokes{1 条：page 1, rgba(20,20,20,1) w10 pencil, pts [[0.5,0.25,0.5],[0.75,0.125,1.0]]}
        val m19 = WireCodec.decode(unhex(VECTORS[18])) as WireCodec.Msg.Strokes
        assertEquals(1, m19.list.size)
        val s19 = m19.list[0]
        assertEquals(1L, s19.page)
        assertEquals(WireCodec.Pen(20, 20, 20, 1.0f, 10f, 3), s19.pen)
        assertEquals(listOf(WireCodec.Pt3(0.5f, 0.25f, 0.5f), WireCodec.Pt3(0.75f, 0.125f, 1.0f)), s19.pts)

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
        assertEquals(WireCodec.RK_PEN, m33.items[0].kind)
        assertEquals(pen, m33.items[0].pen)
        assertEquals(WireCodec.RK_ERASE, m33.items[1].kind)
        assertEquals(WireCodec.RK_PAGE, m33.items[2].kind)

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
                WireCodec.TextNote("n1", 0, 0.5f, 0.5f, "hello"),
                WireCodec.TextNote("n2", 3, 0.25f, 0.75f, "笔记"),
            ),
            m40.list,
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
                WireCodec.Layer(255, 149, 0, true, "老师批注"),
                WireCodec.Layer(0, 122, 255, false, "My Notes"),
            ),
            m45.list,
        )

        // #49 mode{mode:"lasso"}（第 4 态）
        val m49 = WireCodec.decode(unhex(VECTORS[48])) as WireCodec.Msg.ModeSel
        assertEquals(WireCodec.MODE_LASSO, m49.mode)
    }

    @Test
    fun authOkEmptyPayload() {
        // 兼容旧版空 payload authOK → session/udpPort = 0
        val m = WireCodec.decode(byteArrayOf(WireCodec.OP_AUTH_OK.toByte())) as WireCodec.Msg.AuthOK
        assertEquals(0L, m.session)
        assertEquals(0, m.udpPort)
    }

    /** 行号即凭据：表长变了说明上游 canonical 表动过，先核对再改这里（往中间插会整套错位） */
    @Test
    fun vectorTableSize() {
        assertEquals(51, VECTORS.size)
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
        /** spike/wire-vectors-swift.txt 原样 51 行（只在末尾追加，行号即 canonical 表序号） */
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
            "3601000000010000001414140000803f000020410302000000003f0000803e0000003f0000403f0000003e0000803f",
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
            "2402006e3100020000000000003f0000803e0600e689b9e6b3a8",
            "2402006e3101020000000000003f0000803e0000",
            "39020002006e31000000000000003f0000003f050068656c6c6f02006e32030000000000803e0000403f0600e7ac94e8aeb0",
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
        )
    }
}
