package com.xvan.unireader.shared

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 页面文本层的统一「货币」：一段文本 + 归一化包围盒（0~1，左上原点，与页内笔迹同约定）。
 *
 * 🔴 跨端契约：Mac `Sources/App/PageText.swift` 的 `TextRun` 是同一个东西，
 * 也就是 `ocr_page.payload` 里 `runs` 数组的每一项（`{text,x,y,w,h,chars?}`）。
 *
 * [chars] = 行内**字符边界**（行框内比例 0~1，单调不减，长度 = 字数 + 1，首 0 末 1），
 * 来源是 PP-OCRv6 的单字框。null = 这一行没有实测字位（老缓存），选择回落等宽权重近似。
 *
 * ⚠️ 安卓这边**只消费不生产**：本机不跑 OCR，行框一律来自工作区库里 Mac 写下的那份
 * （`ocr_page`，随离线镜像双向同步）。所以 Mac 那边的 `boundsFromWordBoxes` 不必移植过来。
 */
class TextRun(
    val text: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val chars: List<Float>? = null,
) {
    val midX: Float get() = x + w / 2f
    val midY: Float get() = y + h / 2f
    val maxX: Float get() = x + w
    val maxY: Float get() = y + h

    /** 页内归一化框，铺色/画选区直接用（`[x, y, w, h]`，与 payload 里 `rects` 的存法一致） */
    fun rect(): FloatArray = floatArrayOf(x, y, w, h)

    override fun toString(): String = "TextRun('${text.take(12)}' @${"%.3f".format(x)},${"%.3f".format(y)})"
}

/**
 * OCR 行内**字符级**选择的纯函数。
 *
 * 🔴 与 Mac `Sources/App/OCRTextSelect.swift` 是**同一套算法的两份实现**，改一边必须同步另一边
 * （同 `InkEdit.splitStroke` / `TocMerge` 的先例）。判据差一点，两端划出来的字就不一样。
 * 用例：本端 `OcrTextSelectTest`（JVM）↔ Mac `spike/ocr-char-select-test.swift`。
 *
 * 行内定位两条精度路径，[bounds] 是**唯一入口**（命中与裁剪都走它，两边口径才一致）：
 * 1. **单字框**（[TextRun.chars]）——PP-OCRv6 给的真实字位，首选；
 * 2. **等宽权重近似**（没有单字框时兜底）：CJK/全宽标点 1.0，ASCII 0.55，空格 0.3，其余 0.8。
 *
 * ⚠️ **下标一律是「码点序号」不是 UTF-16 序号**：Mac 那边数的是 Swift 的 Character，
 * 对 OCR 实际会出的文本（BMP 内的中英文与标点）两者相等；真碰上代理对时按码点数才不会
 * 把一个字劈成两半。[cpCount]/[cpSlice] 就是干这个的。
 */
object OcrTextSelect {

    // ---------- 码点工具（Swift 的 Character 计数在 BMP 内与此等价） ----------

    fun cpCount(s: String): Int = s.codePointCount(0, s.length)

    /** 第 [i] 个码点在 UTF-16 里的起始下标（i == 码点数时返回 s.length） */
    fun cpOffset(s: String, i: Int): Int = s.offsetByCodePoints(0, i)

    fun cpSlice(s: String, lo: Int, hi: Int): String =
        s.substring(cpOffset(s, lo), cpOffset(s, hi))

    // ---------- 字符边界 ----------

    /**
     * 这一行的字符边界：**行框内比例 0~1**，单调不减，长度 = 字数 + 1，首 0 末 1。
     * 有可信的单字框就用单字框，否则按权重摊。
     */
    fun bounds(run: TextRun): List<Float> {
        val n = cpCount(run.text)
        if (n <= 0) return listOf(0f)
        val c = run.chars
        if (c != null && c.size == n + 1 && isMonotonic(c)) return c
        return weightBounds(run.text)
    }

    /** 边界数组是否可信：夹在 [0,1] 内且单调不减（落库的数据也可能来自别的端/旧版本，别信） */
    fun isMonotonic(b: List<Float>): Boolean {
        if (b.isEmpty()) return false
        if (b.first() < -0.001f || b.last() > 1.001f) return false
        for (i in 1 until b.size) if (b[i - 1] > b[i] + 1e-9f) return false
        return true
    }

    /** 每个字符的显示权重（无单字框时的兜底摊分）。0x2E80 起为 CJK 部首/假名/汉字/全宽标点区 */
    fun charWeights(text: String): List<Float> {
        val out = ArrayList<Float>(cpCount(text))
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.add(if (cp == ' '.code) 0.3f else if (cp >= 0x2E80) 1.0f else 0.55f)
            i += Character.charCount(cp)
        }
        return out
    }

    /** 权重摊出的字符边界（同 [bounds] 的约定：0~1、长度 = 字数+1） */
    fun weightBounds(text: String): List<Float> {
        val w = charWeights(text)
        if (w.isEmpty()) return listOf(0f)
        val total = w.sum()
        if (total <= 0f) return listOf(0f) + w.indices.map { (it + 1).toFloat() / w.size }
        val out = ArrayList<Float>(w.size + 1)
        out.add(0f)
        var acc = 0f
        for (cw in w) { acc += cw; out.add(acc / total) }
        return out
    }

    // ---------- 命中与裁剪 ----------

    /** 行内 x（页内归一化）→ 字符边界下标（0..字数）。中点吸附：越过某字中线即算到它后面 */
    fun charOffset(run: TextRun, nx: Float): Int {
        val n = cpCount(run.text)
        if (n <= 0 || run.w <= 0f) return 0
        val b = bounds(run)
        if (b.size != n + 1) return 0
        val target = (nx - run.x) / run.w
        for (i in 0 until n) if (target < (b[i] + b[i + 1]) / 2f) return i
        return n
    }

    /**
     * 行内字符区间 `[lo, hi)` → 子文本 + 子行框（高亮/批注锚框同步收窄）；空区间返回 null。
     * 单字框会一并切下来并按子区间重新归一化，子行框还能继续被裁。
     */
    fun clip(run: TextRun, from: Int, to: Int): TextRun? {
        val n = cpCount(run.text)
        val lo = max(0, min(from, n))
        val hi = max(lo, min(to, n))
        if (lo >= hi) return null
        val b = bounds(run)
        if (b.size != n + 1) return null
        val x0 = run.x + b[lo] * run.w
        val x1 = run.x + b[hi] * run.w
        val span = b[hi] - b[lo]
        // 只有原行**真有**单字框才往下传；权重摊出来的不算「实测字位」，切完让下游继续按权重算即可
        // （逐字权重相互独立，子串重摊与整行切片完全等价，不会因此走样）。
        val sub = if (run.chars != null && span > 0f) (lo..hi).map { (b[it] - b[lo]) / span } else null
        return TextRun(cpSlice(run.text, lo, hi), x0, run.y, x1 - x0, run.h, sub)
    }
}

/**
 * OCR 行的「文字流」分析：把一页的行聚成列/块分组（同组 = 空间上连续、可作一段连续选择的块）。
 *
 * 🔴 与 Mac `Sources/App/OCRFlow.swift` 同一套判据，改一边同步另一边。
 */
object OcrFlow {
    /**
     * 把一页 OCR 行按「竖直相邻 + 水平重叠」聚成列/块分组（并查集）。
     * 返回与 [runs] 同序的分组 id（同 id = 同一可选块），id 按出现顺序 0,1,2…。
     *
     * · 两行竖直相邻：上行底到下行顶的间隙 ∈ [-0.6, 1.2] × 行高中位数；
     * · 且水平重叠 ≥ 较窄者宽度的 35%。
     * 于是单列段落 → 一组；多列 → 各列各一组；散节点 → 各自成组。
     */
    fun columnGroups(runs: List<TextRun>): IntArray {
        val n = runs.size
        if (n == 0) return IntArray(0)
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }
        val sortedH = runs.map { it.h }.sorted()
        val lineH = max(0.0001f, sortedH[sortedH.size / 2])
        val maxGap = 1.2f * lineH
        val minGap = -0.6f * lineH
        for (i in 0 until n) {
            val a = runs[i]
            for (j in i + 1 until n) {
                val b = runs[j]
                val up = if (a.y <= b.y) a else b
                val lo = if (a.y <= b.y) b else a
                val gap = lo.y - (up.y + up.h)
                if (gap > maxGap || gap < minGap) continue
                val ov = max(0f, min(a.maxX, b.maxX) - max(a.x, b.x))
                if (ov / max(0.0001f, min(a.w, b.w)) >= 0.35f) union(i, j)
            }
        }
        val map = HashMap<Int, Int>()
        val out = IntArray(n)
        var next = 0
        for (i in 0 until n) {
            val r = find(i)
            out[i] = map.getOrPut(r) { next++ }
        }
        return out
    }
}

/**
 * 扫描件**平铺水印**（整页斜排的机构名之类）的识别，纯几何 + 跨页统计，不写死任何字符串。
 *
 * 🔴 与 Mac `Sources/App/OCRWatermark.swift` 是同一套判据与同一组阈值，改一边同步另一边。
 *
 * 为什么安卓也要它：`ocr_page` 里存的是**原始**识别行，水印块就混在正文行之间；不滤掉的话，
 * 在平板上划一段正文会连带选出「王道计」「卓机教育」这类碎片（Mac 2026-09-03 已修过一次，
 * 消费方一律走「可见行」）。安卓是这条文本层的新消费方，规矩照搬。
 */
object OcrWatermark {

    /** 候选门槛：行高 ≥ 这个倍数的页内行高中位数 */
    const val BIG_RATIO = 2.5f
    /** 候选门槛：包围盒 w/h 的上下界（斜排 ≈1，竖排 ≪1，整行水平大字 ≫1） */
    const val WH_LO = 0.7f
    const val WH_HI = 3.5f
    /** 位置网格边长（页内归一化）；判定查 3×3 邻域，实际容差 ≈ ±1 格 */
    const val CELL = 0.04f
    /** 跨页重复的绝对下限——低于它一律不判，否则「每章一次」的章标题会被当水印 */
    const val MIN_REPEAT_PAGES = 8
    /** 跨页重复的相对阈值（占样本页数的比例） */
    const val REPEAT_FRACTION = 0.06f

    /** 全书水印指纹：候选块的位置格 / 文本各出现在哪些页。空指纹时只剩同页伙伴那条判据 */
    class Profile(
        val cellPages: Map<Long, Set<Int>> = emptyMap(),
        val textPages: Map<String, Set<Int>> = emptyMap(),
        val sampledPages: Int = 0,
    ) {
        val isEmpty: Boolean get() = sampledPages == 0
    }

    /** 位置格打成一个 Long（高 32 位 cx、低 32 位 cy），省一个 data class */
    fun cellOf(run: TextRun): Long {
        val cx = floor(run.midX / CELL).toInt()
        val cy = floor(run.midY / CELL).toInt()
        return (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
    }

    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    /** 页内行高中位数（空页返回 0）。水印判定一律相对它，与页面 DPI/纸张大小无关 */
    fun medianHeight(runs: List<TextRun>): Float {
        if (runs.isEmpty()) return 0f
        val hs = runs.map { it.h }.sorted()
        return hs[hs.size / 2]
    }

    /** 是否「可能是斜排水印」的候选块：够大 + 包围盒近方形 */
    fun isCandidate(run: TextRun, medianH: Float): Boolean {
        if (medianH <= 0f || run.h <= 0f || run.w <= 0f) return false
        if (run.h < BIG_RATIO * medianH) return false
        val wh = run.w / run.h
        return wh in WH_LO..WH_HI
    }

    /** 归一化文本：去掉全部空白（OCR 对同一水印的空格切分不稳定） */
    fun normalizedText(s: String): String = s.filterNot { it.isWhitespace() }

    /** 两段文本是否有 ≥2 字的公共子串（单字文本退化为「完全相同」） */
    fun shareBigram(a: String, b: String): Boolean {
        if (a.length < 2 || b.length < 2) return a.isNotEmpty() && a == b
        val grams = HashSet<String>(a.length)
        for (i in 0 until a.length - 1) grams.add(a.substring(i, i + 2))
        for (i in 0 until b.length - 1) if (grams.contains(b.substring(i, i + 2))) return true
        return false
    }

    /** 统计已识别那部分候选块的位置/文本重复情况。[pages] = 页 → 该页**原始** OCR 行 */
    fun buildProfile(pages: Map<Int, List<TextRun>>): Profile {
        val cellPages = HashMap<Long, MutableSet<Int>>()
        val textPages = HashMap<String, MutableSet<Int>>()
        for ((page, runs) in pages) {
            val med = medianHeight(runs)
            for (run in runs) {
                if (!isCandidate(run, med)) continue
                cellPages.getOrPut(cellOf(run)) { HashSet() }.add(page)
                textPages.getOrPut(normalizedText(run.text)) { HashSet() }.add(page)
            }
        }
        return Profile(cellPages, textPages, pages.size)
    }

    fun threshold(sampledPages: Int): Int =
        max(MIN_REPEAT_PAGES, (REPEAT_FRACTION * sampledPages).toInt())

    /** 该块所在位置（含 3×3 邻域）在多少页出现过候选块。邻域容错是必需的：中心会漂移小半格 */
    fun repeatPages(run: TextRun, profile: Profile): Int {
        val c = cellOf(run)
        val cx = (c shr 32).toInt()
        val cy = c.toInt()
        val pages = HashSet<Int>()
        for (dx in -1..1) for (dy in -1..1) {
            profile.cellPages[cellKey(cx + dx, cy + dy)]?.let { pages.addAll(it) }
        }
        return pages.size
    }

    /**
     * 一页的水印掩码（与 [runs] 同序，true = 判为水印、应从选择/分组里剔除）。
     * 跨页判据（需要 [profile]）与同页伙伴判据取**并集**。
     */
    fun mask(runs: List<TextRun>, profile: Profile): BooleanArray {
        val out = BooleanArray(runs.size)
        if (runs.isEmpty()) return out
        val med = medianHeight(runs)
        val cand = runs.indices.filter { isCandidate(runs[it], med) }
        if (cand.isEmpty()) return out
        val texts = cand.map { normalizedText(runs[it].text) }
        val need = threshold(profile.sampledPages)
        for (k in cand.indices) {
            val i = cand[k]
            var hit = false
            if (!profile.isEmpty) {
                hit = repeatPages(runs[i], profile) >= need ||
                    (profile.textPages[texts[k]]?.size ?: 0) >= need
            }
            // 同页伙伴兜底：一页里三块以上大方块、且彼此文本沾亲带故 —— 正文极难凑齐这个形状
            if (!hit && cand.size >= 3) {
                hit = texts.indices.any { it != k && shareBigram(texts[k], texts[it]) }
            }
            out[i] = hit
        }
        return out
    }

    /** 便利：滤掉水印行，返回「可见行」。**选择/复制/分组一律消费这个**，别直接用原始行 */
    fun visible(runs: List<TextRun>, profile: Profile): List<TextRun> {
        if (runs.isEmpty()) return runs
        val m = mask(runs, profile)
        if (!m.any { it }) return runs
        return runs.filterIndexed { i, _ -> !m[i] }
    }
}
