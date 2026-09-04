package com.xvan.unireader.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 划字的纯函数层：行内字符定位（[OcrTextSelect]）+ 选区装配（[TextSelect]）+ 水印剔除
 * （[OcrWatermark]）+ 列块分组（[OcrFlow]）。
 *
 * 🔴 这几样与 Mac `OCRTextSelect.swift` / `ReaderSurface+Selection.swift` / `OCRWatermark.swift` /
 * `OCRFlow.swift` 是**同一套算法的两份实现**。判定不一致 = 同一本书在两端划出来的字不一样，
 * 而那段文字会当成 `quote` 落进库里两端各存各的。对应 Mac 的
 * `spike/ocr-char-select-test.swift` / `spike/ocr-watermark-test.swift`。
 */
class TextSelectTest {

    /** 一行等宽 CJK：不带单字框，走权重摊分（每字 1.0，均分） */
    private fun cjk(text: String, x: Float, y: Float, w: Float, h: Float = 0.03f) =
        TextRun(text, x, y, w, h)

    // ---------- 行内字符定位 ----------

    @Test
    fun 权重摊分_CJK等分而ASCII更窄() {
        val b = OcrTextSelect.weightBounds("汉a汉")
        assertEquals(4, b.size)
        assertEquals(0f, b[0], 1e-6f)
        assertEquals(1f, b[3], 1e-6f)
        // 权重 1.0 / 0.55 / 1.0 → 总 2.55
        assertEquals(1.0f / 2.55f, b[1], 1e-5f)
        assertEquals(1.55f / 2.55f, b[2], 1e-5f)
        assertTrue("中间那个 ASCII 明显比汉字窄", (b[2] - b[1]) < (b[1] - b[0]))
    }

    @Test
    fun 单字框可信才用_否则回落权重() {
        val good = TextRun("三个字", 0f, 0f, 0.3f, 0.03f, listOf(0f, 0.2f, 0.7f, 1f))
        assertEquals(listOf(0f, 0.2f, 0.7f, 1f), OcrTextSelect.bounds(good))
        // 个数对不上 → 不信
        val badCount = TextRun("三个字", 0f, 0f, 0.3f, 0.03f, listOf(0f, 0.5f, 1f))
        assertEquals(OcrTextSelect.weightBounds("三个字"), OcrTextSelect.bounds(badCount))
        // 不单调 → 不信
        val badOrder = TextRun("三个字", 0f, 0f, 0.3f, 0.03f, listOf(0f, 0.7f, 0.2f, 1f))
        assertEquals(OcrTextSelect.weightBounds("三个字"), OcrTextSelect.bounds(badOrder))
    }

    @Test
    fun 行内命中按中点吸附() {
        val run = cjk("一二三四", 0.1f, 0.2f, 0.4f)   // 每字宽 0.1
        assertEquals("落在首字左半 → 0", 0, OcrTextSelect.charOffset(run, 0.11f))
        assertEquals("越过首字中线 → 1", 1, OcrTextSelect.charOffset(run, 0.16f))
        assertEquals("行尾 → 4（全选）", 4, OcrTextSelect.charOffset(run, 0.49f))
        assertEquals("行首之前也是 0", 0, OcrTextSelect.charOffset(run, 0.0f))
    }

    @Test
    fun 裁剪同时收窄子行框() {
        val run = cjk("一二三四", 0.1f, 0.2f, 0.4f)
        val sub = OcrTextSelect.clip(run, 1, 3)
        assertNotNull(sub)
        assertEquals("二三", sub!!.text)
        assertEquals(0.2f, sub.x, 1e-5f)
        assertEquals(0.2f, sub.w, 1e-5f)
        assertNull("空区间没有子串", OcrTextSelect.clip(run, 2, 2))
        // 有单字框的行：切完还带着按子区间重新归一化的字框
        val withChars = TextRun("一二三四", 0.1f, 0.2f, 0.4f, 0.03f, listOf(0f, 0.1f, 0.5f, 0.9f, 1f))
        val s2 = OcrTextSelect.clip(withChars, 1, 3)!!
        assertEquals(3, s2.chars!!.size)
        assertEquals(0f, s2.chars!![0], 1e-5f)
        assertEquals(0.5f, s2.chars!![1], 1e-5f)   // (0.5-0.1)/(0.9-0.1)
        assertEquals(1f, s2.chars!![2], 1e-5f)
    }

    // ---------- 选区装配 ----------

    /** 一页三行连续正文（同一列 → 同一分组） */
    private fun para(): TextSelect.Provider {
        val runs = listOf(
            cjk("第一行文字", 0.1f, 0.10f, 0.5f),
            cjk("第二行文字", 0.1f, 0.15f, 0.5f),
            cjk("第三行文字", 0.1f, 0.20f, 0.5f),
        )
        val groups = OcrFlow.columnGroups(runs)
        return object : TextSelect.Provider {
            override fun runs(page: Int) = if (page == 0) runs else null
            override fun groups(page: Int) = groups
        }
    }

    @Test
    fun 单行横拖取两端点之间() {
        val p = para()
        // y=0.165 落在第二行（它的 midY 就是 0.165；第一行是 0.115）
        val sel = TextSelect.select(p, TextSelect.Point(0, 0.20f, 0.165f), TextSelect.Point(0, 0.40f, 0.165f))
        assertNotNull(sel)
        assertEquals("二行", sel!!.text)
        assertEquals(1, sel.rects[0]?.size)
    }

    @Test
    fun 拖反了结果一样() {
        val p = para()
        val a = TextSelect.select(p, TextSelect.Point(0, 0.20f, 0.165f), TextSelect.Point(0, 0.40f, 0.165f))
        val b = TextSelect.select(p, TextSelect.Point(0, 0.40f, 0.165f), TextSelect.Point(0, 0.20f, 0.165f))
        assertEquals(a!!.text, b!!.text)
    }

    @Test
    fun 多行拖选_首行裁左末行裁右中间整行() {
        val p = para()
        // 从第 1 行中间拖到第 3 行中间
        val sel = TextSelect.select(p, TextSelect.Point(0, 0.30f, 0.11f), TextSelect.Point(0, 0.30f, 0.21f))
        assertNotNull(sel)
        val lines = sel!!.text.split("\n")
        assertEquals(3, lines.size)
        assertEquals("首行裁掉起点左侧", "行文字", lines[0])
        assertEquals("中间整行", "第二行文字", lines[1])
        assertEquals("末行裁掉终点右侧", "第三", lines[2])
    }

    @Test
    fun 分组感知_不把另一列拖进来() {
        // 左右两列，中间隔开：拖左列不该带出右列
        val runs = listOf(
            cjk("左一", 0.05f, 0.10f, 0.2f),
            cjk("左二", 0.05f, 0.15f, 0.2f),
            cjk("右一", 0.60f, 0.10f, 0.2f),
            cjk("右二", 0.60f, 0.15f, 0.2f),
        )
        val groups = OcrFlow.columnGroups(runs)
        assertTrue("左右应当分成两组", groups[0] == groups[1] && groups[2] == groups[3] && groups[0] != groups[2])
        val p = object : TextSelect.Provider {
            override fun runs(page: Int) = runs
            override fun groups(page: Int) = groups
        }
        // 纵向带跨两行（> 1.8 行高）才会走分组分支
        val sel = TextSelect.select(p, TextSelect.Point(0, 0.06f, 0.105f), TextSelect.Point(0, 0.70f, 0.165f))
        assertNotNull(sel)
        assertTrue("只该有左列：${sel!!.text}", !sel.text.contains("右"))
    }

    @Test
    fun 双击选整行() {
        val p = para()
        val sel = TextSelect.selectLine(p, 0, 0.3f, 0.16f)
        assertEquals("第二行文字", sel?.text)
    }

    @Test
    fun 没有文本层就选不出东西() {
        val p = object : TextSelect.Provider {
            override fun runs(page: Int): List<TextRun>? = null
            override fun groups(page: Int) = IntArray(0)
        }
        assertNull(TextSelect.select(p, TextSelect.Point(0, 0.1f, 0.1f), TextSelect.Point(0, 0.5f, 0.1f)))
    }

    @Test
    fun 选区包围盒把整段框住() {
        val p = para()
        val sel = TextSelect.select(p, TextSelect.Point(0, 0.10f, 0.11f), TextSelect.Point(0, 0.60f, 0.21f))!!
        val box = sel.bbox(0)!!
        assertEquals(0.1f, box[0], 1e-4f)
        assertEquals(0.10f, box[1], 1e-4f)
        assertTrue("高度要盖住三行", box[3] > 0.1f)
    }

    // ---------- 水印剔除 ----------

    @Test
    fun 平铺水印被剔除而正文一行不动() {
        // 正文行：矮而宽；水印块：高 3 倍、近方形，且同页三块文本沾亲带故（同页伙伴判据）
        val body = (0..9).map { cjk("正文第${it}行", 0.1f, 0.1f + it * 0.05f, 0.6f, 0.02f) }
        val marks = listOf(
            TextRun("卓机教育", 0.20f, 0.20f, 0.09f, 0.08f),
            TextRun("算机教育", 0.50f, 0.45f, 0.09f, 0.08f),
            TextRun("机教育卓", 0.30f, 0.70f, 0.09f, 0.08f),
        )
        val runs = body + marks
        val visible = OcrWatermark.visible(runs, OcrWatermark.Profile())   // 空指纹：只剩同页伙伴那条
        assertEquals("三块水印都该被剔掉", body.size, visible.size)
        assertTrue("正文一行不能少", visible.all { it.text.startsWith("正文") })
    }

    @Test
    fun 没有水印的文档一行都不动() {
        val body = (0..9).map { cjk("正文第${it}行", 0.1f, 0.1f + it * 0.05f, 0.6f, 0.02f) }
        assertEquals(body.size, OcrWatermark.visible(body, OcrWatermark.Profile()).size)
    }

    @Test
    fun 跨页重复判据要够页数才算数() {
        // 同一位置的大方块只出现在 3 页 → 达不到 minRepeatPages(8)，不判水印（章标题就是这样）
        val pages = (0 until 3).associateWith {
            listOf(TextRun("第${it}章", 0.45f, 0.45f, 0.09f, 0.08f)) +
                (0..5).map { i -> cjk("正文$i", 0.1f, 0.1f + i * 0.05f, 0.6f, 0.02f) }
        }
        val profile = OcrWatermark.buildProfile(pages)
        assertEquals(3, profile.sampledPages)
        assertEquals(8, OcrWatermark.threshold(3))
        val runs = pages.getValue(0)
        assertTrue("章标题不该被当水印", OcrWatermark.visible(runs, profile).any { it.text.startsWith("第") })
    }
}
