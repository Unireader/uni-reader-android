package com.xvan.unireader.shared.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局的粗检（坐标不进跨端向量，这里只防崩溃与明显出错）：同 Mac `spike/anim-test.swift`「布局」一节的三条——
 * 各步高度一致、物件不出画布、id 不重复。文字宽度用假的量法（每字 0.6 × 字号），够判越界。
 */
class AnimLayoutTest {

    private val fake = AnimMeasure { text, size, _, maxWidth ->
        val lines = text.split("\n")
        val w = (lines.maxOf { it.length } * size * 0.6f)
        if (w <= maxWidth) ASize(w + 1, lines.size * size * 1.25f)
        else ASize(maxWidth, kotlin.math.ceil(w / maxWidth) * lines.size * size * 1.25f)
    }

    private val sources = listOf(
        "kit header proto=ipv4 lang=zh", "kit header proto=tcp lang=en", "kit header proto=udp lang=zh", "kit header proto=ipv6 lang=en",
        "kit header proto=ipv4 hex=\"46 00 00 20 00 01 20 00 3f 11 00 00 0a 00 00 01 0a 00 00 02 01 01 00 00\" lang=zh",
        "kit header proto=tcp hex=\"04 89 00 50 0d d6 01 f4 00 00 00 00 70 12 40 00 c0 29 00 00 02 04 05 b4 01 01 04 02\" lang=zh",
        "kit header proto=ipv6 hex=\"60 00 00 00 00 20 06 40 20 01 0d b8 00 00 00 00 00 00 00 00 00 00 00 01 fe 80 00 00 00 00 00 00 02 1a 2b ff fe 3c 4d 5e\" lang=zh",
        "kit encap data=1 transport=udp lang=zh", "kit encap data=2000 net=ipv6 lang=en", "kit frag total=4000 mtu=576 lang=zh", "kit frag data=3980 df=1 lang=zh",
        "tree R dir=right = (A (B D E) (C _ F))\npointer p -> R[B]\nstep x\n  insert R G under C left\n  move p -> R[G]",
        "tree U dir=up = (1 (2 4 5) (3 6 7))\ntree L dir=left = (1 2 3 4)\nstep x\n  remove U 3",
        "graph SDN layout=layers dir=down layers=[[控制器], [交换机S1, 交换机S2, 交换机S3], [H1, H2, H3, H4]] = 控制器-交换机S1, 控制器-交换机S2, 控制器-交换机S3, 交换机S1-交换机S3:5, 交换机S1-H1, 交换机S2-H2, 交换机S3-H3, 交换机S3-H4\npointer p -> SDN[H1]\nstep x\n  mark SDN[交换机S1-交换机S3] done",
        "graph G layout=layers dir=up = A-B, A-C, B-D, C-D:5\ngraph K layout=layers dir=left = A->B, B->C",
        "kit ipclass lang=zh", "kit ipaddr ip=172.16.37.200/20 lang=zh", "kit ipaddr ip=224.0.0.5 lang=en",
        "kit supernet nets=172.18.129.0/24,172.18.130.0/24,172.18.132.0/24,172.18.133.0/24 lang=zh", "kit subnet net=192.168.1.0/24 hosts=50 lang=zh",
        "kit lpm dst=192.168.1.37 routes=\"192.168.1.0/24 R1, 192.168.1.32/27 R2, 0.0.0.0/0 R3\" lang=en",
    )

    @Test
    fun newElementsStayInsideAndKeepHeight() {
        for (src in sources) {
            val run = AnimEngine.run(src)
            assertTrue("$src 有错：${run.errors}", run.errors.isEmpty())
            for (width in listOf(320f, 560f, 720f)) {
                val layout = AnimLayout(run, width, fake)
                for (k in 0 until run.stepCount) {
                    val f = layout.frame(k)
                    assertEquals("$src 第 $k 步高度", layout.height, f.size.h, 0.01f)
                    val ids = f.items.map { it.id }
                    assertEquals("$src 第 $k 步 id 重复", ids.size, ids.toSet().size)
                    for (item in f.items) {
                        val r = when (val s = item.shape) {
                            is AnimShape.Box -> s.rect
                            is AnimShape.Text -> s.frame
                            else -> continue
                        }
                        assertTrue("$src 宽 $width 第 $k 步 ${item.id} 越界 $r", r.minX >= -0.5f && r.maxX <= width + 0.5f && r.minY >= -0.5f && r.maxY <= layout.height + 0.5f)
                    }
                }
            }
        }
    }

    @Test
    fun geometryHelpersMatchMac() {
        assertEquals(1, AnimLayout.bitFieldBoxes(64, 128, 32).size)
        assertEquals(4, AnimLayout.bitFieldBoxes(64, 128, 32)[0][3])
        assertEquals(2, AnimLayout.bitFieldBoxes(24, 16, 32).size)
        assertEquals(40f, AnimLayout.nodeExit(1f, 0f, 40f, 15f), 0.01f)
        assertEquals(15f, AnimLayout.nodeExit(0f, 1f, 40f, 15f), 0.01f)
        assertEquals(15f, AnimLayout.nodeExit(0.6f, 0.8f, 15f, 15f), 0.01f)
        assertEquals(20.0, AnimLayout.leadingNumber("20 B"), 0.0)
        val run = AnimEngine.run("packet P = d:1\nstep a\n  insert P 0 \"t:2\"\n  group P[0..1] \"s\"\n  insert P 0 \"i:3\"\n  group P[0..2] \"g\"")
        assertEquals(listOf(1, 2), AnimLayout.packetGroupLevels(run.state(0, 1).packets["P"]!!).values.sorted())
    }
}
