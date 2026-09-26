package com.xvan.unireader.local.store

import com.xvan.unireader.shared.Pt3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 笔迹点集二进制（schema v18，`../BINARY-INK-PLAN.md §2`）的跨端一致性：逐条读主仓库的
 * `spike/ink-blob-vectors.txt`（Mac `spike/ink-blob-test.swift` 读同一份），编码逐字节相同、解码逐位相同。
 * 两端差一个字节，Mac 写的笔迹在平板上就是另一副样子（或解不出、退回 JSON）。
 */
class InkPointsBlobTest {

    /** 从测试的工作目录往上找主仓库的向量文件（Gradle 跑单测时工作目录是 android/app） */
    private fun vectorsFile(): File {
        var d: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (d != null) {
            val f = File(d, "spike/ink-blob-vectors.txt")
            if (f.isFile) return f
            d = d.parentFile
        }
        error("找不到 spike/ink-blob-vectors.txt（从 ${System.getProperty("user.dir")} 往上）")
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun 跨端向量_编码逐字节_解码逐位() {
        var n = 0
        for (line in vectorsFile().readLines()) {
            if (line.startsWith("#") || !line.contains("|")) continue
            val (ptsText, want) = line.split("|", limit = 2)
            val pts = if (ptsText.isEmpty()) emptyList() else ptsText.split(";").map { p ->
                val v = p.split(",").map { it.toFloat() }
                Pt3(v[0], v[1], v[2])
            }
            val enc = InkPointsBlob.encode(pts)
            assertEquals("向量 $n 编码", want, hex(enc))
            val dec = InkPointsBlob.decode(enc)!!
            assertEquals(pts.size, dec.size)
            for (i in pts.indices) {
                assertEquals(pts[i].x.toRawBits(), dec[i].x.toRawBits())
                assertEquals(pts[i].y.toRawBits(), dec[i].y.toRawBits())
                assertEquals(pts[i].p.toRawBits(), dec[i].p.toRawBits())
            }
            n++
        }
        assertTrue("向量文件读到 $n 条", n >= 4)
    }

    @Test
    fun 坏数据解不出() {
        assertNull(InkPointsBlob.decode(null))
        assertNull(InkPointsBlob.decode(ByteArray(0)))
        assertNull(InkPointsBlob.decode(byteArrayOf(2) + ByteArray(12)))   // 版本不认识
        assertNull(InkPointsBlob.decode(byteArrayOf(1, 0, 0)))             // 长度不对
    }

    @Test
    fun 摘掉JSON点() {
        val payload = """{"color":{"r":1},"points":[[0.1,0.2,0.5],[1,2]],"width":2}""".toByteArray()
        val (rest, had) = InkPayload.stripPoints(payload)!!
        assertTrue(had)
        assertArrayEquals("""{"color":{"r":1},"points":[],"width":2}""".toByteArray(), rest)
        assertEquals(false, InkPayload.stripPoints(rest)!!.second)   // 已经是空的：没有点
        assertNull(InkPayload.stripPoints("""{"width":2}""".toByteArray()))
    }
}
