package com.xvan.unireader.pad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字节级一致性测试：对照 spike/wire-vectors-swift.txt 的 31 条 canonical 向量。
 * 每条向量对应的 canonical 消息值见 spike/wire-codec-test.swift 的 canonical 表（行号一一对应）。
 * 编码类（auth/ping/pageTurn/scroll/hover/ink/erase）断言 encode 结果逐字节等于 hex；
 * 解码类（authOK/authFail/pong/page/layout/inkCancel/strokes/nack）断言 decode(hex) 字段正确。
 * demo 不实现的消息（latency/selectDoc/mode/pen/viewport/docs/pens/probe）对应向量行不参与比对。
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
    }

    @Test
    fun authOkEmptyPayload() {
        // 兼容旧版空 payload authOK → session/udpPort = 0
        val m = WireCodec.decode(byteArrayOf(WireCodec.OP_AUTH_OK.toByte())) as WireCodec.Msg.AuthOK
        assertEquals(0L, m.session)
        assertEquals(0, m.udpPort)
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
        /** spike/wire-vectors-swift.txt 原样 31 行 */
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
            "420000000000185ad20000003f000000410001000000003f0000003f0000003f",
            "420102000000803e0000403f0000003f0000003f0000003f0000803f",
            "4202",
            "43010100000002000000003f0000003f0000803e0000803e",
            "4302",
            "44000200000001000000003f0000003f",
            "440101000000803e0000803e",
            "4402",
            "5003000100000002000000005ed0b2",
        )
    }
}
