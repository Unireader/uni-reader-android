package com.xvan.unireader.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 一段文字选区：逐页的行框（页内归一化 `[x,y,w,h]`）+ 拼好的原文。
 *
 * 🔴 与 Mac `TextSelection`（`Sources/Views/PageStreamSupport.swift`）同一个东西：
 * `rects` 直接就是落库时 `rects` 键的形状，`text` 直接就是 `quote`。
 */
class TextSelection(
    /** 页 → 该页的逐行框（页内归一化 `[x,y,w,h]`） */
    val rects: Map<Int, List<FloatArray>>,
    val text: String,
) {
    val isEmpty: Boolean get() = text.isEmpty() || rects.isEmpty()
    val pages: List<Int> get() = rects.keys.sorted()

    /** 某页选区的包围盒（落库时的 anchor）；该页没有框返回 null */
    fun bbox(page: Int): FloatArray? {
        val rs = rects[page]?.takeIf { it.isNotEmpty() } ?: return null
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (r in rs) {
            x0 = min(x0, r[0]); y0 = min(y0, r[1])
            x1 = max(x1, r[0] + r[2]); y1 = max(y1, r[1] + r[3])
        }
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }
}

/**
 * 由 OCR 行文本层装配文字选区（纯函数，可 JVM 单测）。
 *
 * 🔴 与 Mac `Sources/Views/ReaderSurface+Selection.swift` 的
 * `ocrLineHit` / `ocrGroupSelection` / `ocrLinearSelection` 是**同一套判定的两份实现**，
 * 改一边必须同步另一边（同 `InkEdit` / `TocMerge` 的先例）。判定不一致 = 同一本书在两端
 * 划出来的字不一样，而这段文字会被当成 `quote` 落进库里，两端各存各的。
 *
 * 行数据由调用方按页给（[Provider]），本对象一行库都不读。
 */
object TextSelect {

    /** 页 → 该页**可见**行（已滤水印）。下标须与 [groups] 对齐（同 Mac `ocrVisibleRuns`/`ocrGroups`） */
    interface Provider {
        fun runs(page: Int): List<TextRun>?
        /** 该页行的列/块分组（`OcrFlow.columnGroups`），与 [runs] 同序 */
        fun groups(page: Int): IntArray
    }

    /** 一个落点：页 + 页内归一化坐标 */
    class Point(val page: Int, val nx: Float, val ny: Float)

    /**
     * OCR 行命中：先比行(y)、同高度内再比列(x)——落在行框内 dx=0，最近行优先。
     * 与 Mac `ocrLineHit` 逐字对齐（含 `dy*1000 + dx` 这个权重）。
     */
    fun lineHit(runs: List<TextRun>?, nx: Float, ny: Float): Int? {
        if (runs.isNullOrEmpty()) return null
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (i in runs.indices) {
            val r = runs[i]
            val dy = abs(r.midY - ny)
            val dx = if (nx >= r.x && nx <= r.maxX) 0f else min(abs(nx - r.x), abs(nx - r.maxX))
            val d = dy * 1000f + dx
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * 拖选主入口：锚点/焦点各命中一行，行内再按 x 定位到字符级。
     * **同页**走分组感知（[groupSelection]），**跨页**走阅读顺序线性切片（[linearSelection]）。
     */
    fun select(p: Provider, anchor: Point, focus: Point): TextSelection? {
        val ai = lineHit(p.runs(anchor.page), anchor.nx, anchor.ny) ?: return null
        val fi = lineHit(p.runs(focus.page), focus.nx, focus.ny) ?: return null
        return if (anchor.page == focus.page) {
            groupSelection(p, anchor.page, ai, fi, anchor.nx, focus.nx)
        } else {
            linearSelection(p, anchor.page, ai, anchor.nx, focus.page, fi, focus.nx)
        }
    }

    /**
     * 同页选区（分组感知，所见即所选）：
     * · **纵向带** = 锚点行 ∪ 焦点行的竖直范围；
     * · 只选**锚点所在分组**内、midY 落在带内的行 —— 于是「左列拖右列」只落在锚点那一列；
     * · **单行横拖**（带高 ≤1.8 行高）例外：走线性切片，含右对齐页码这类同行元素；
     * · **首末行字符级裁剪**：带内首行裁掉端点左侧、末行裁掉端点右侧。
     */
    fun groupSelection(p: Provider, page: Int, ai: Int, fi: Int, ax: Float, fx: Float): TextSelection? {
        val runs = p.runs(page) ?: return null
        if (ai !in runs.indices || fi !in runs.indices) return null
        val a = runs[ai]
        val f = runs[fi]
        val bandMin = min(a.y, f.y)
        val bandMax = max(a.maxY, f.maxY)
        if (bandMax - bandMin <= 1.8f * max(a.h, f.h)) {
            return linearSelection(p, page, ai, ax, page, fi, fx)
        }
        val groups = p.groups(page)
        val ga = if (ai in groups.indices) groups[ai] else -1
        var picked = ArrayList<Int>()
        for (i in runs.indices) {
            if (i !in groups.indices || groups[i] != ga) continue
            val midY = runs[i].midY
            if (midY in bandMin..bandMax) picked.add(i)
        }
        if (picked.isEmpty()) picked = arrayListOf(ai)
        picked.sortWith(
            compareBy({ runs[it].midY }, { runs[it].x }),
        )
        // 字符级裁剪：端点在带的哪头就裁哪头（顶行裁端点左侧、底行裁端点右侧）
        val topIsAnchor = runs[ai].midY <= runs[fi].midY
        val topIdx = if (topIsAnchor) ai else fi
        val topX = if (topIsAnchor) ax else fx
        val botIdx = if (topIsAnchor) fi else ai
        val botX = if (topIsAnchor) fx else ax
        val items = picked.map { it to runs[it] }.toMutableList()
        val ti = items.indexOfFirst { it.first == topIdx }
        if (ti >= 0) {
            val run = items[ti].second
            val c = OcrTextSelect.clip(run, OcrTextSelect.charOffset(run, topX), OcrTextSelect.cpCount(run.text))
            if (c != null) items[ti] = items[ti].first to c else items.removeAt(ti)
        }
        if (botIdx != topIdx) {
            val bi = items.indexOfLast { it.first == botIdx }
            if (bi >= 0) {
                val run = items[bi].second
                val c = OcrTextSelect.clip(run, 0, OcrTextSelect.charOffset(run, botX))
                if (c != null) items[bi] = items[bi].first to c else items.removeAt(bi)
            }
        }
        val text = items.joinToString("\n") { it.second.text }
        if (text.isEmpty()) return null
        return TextSelection(mapOf(page to items.map { it.second.rect() }), text)
    }

    /**
     * 线性切片选区（单行横拖 / 跨页）：按阅读顺序（页号→行序）切片，
     * 首行裁掉端点左侧、末行裁掉端点右侧（字符级），中间行整行；同一行 = 两端点间的字符区间。
     */
    fun linearSelection(
        p: Provider,
        ap: Int,
        ai: Int,
        ax: Float,
        fp: Int,
        fi: Int,
        fx: Float,
    ): TextSelection? {
        // 同页同行：两端点 x 之间的字符区间（拖反了也一样，取 min/max）
        if (ap == fp && ai == fi) {
            val runs = p.runs(ap) ?: return null
            if (ai !in runs.indices) return null
            val run = runs[ai]
            val lo = OcrTextSelect.charOffset(run, min(ax, fx))
            val hi = OcrTextSelect.charOffset(run, max(ax, fx))
            val sub = OcrTextSelect.clip(run, lo, hi) ?: return null
            return TextSelection(mapOf(ap to listOf(sub.rect())), sub.text)
        }
        val aFirst = ap < fp || (ap == fp && ai <= fi)
        val sp = if (aFirst) ap else fp
        val si = if (aFirst) ai else fi
        val sx = if (aFirst) ax else fx
        val ep = if (aFirst) fp else ap
        val ei = if (aFirst) fi else ai
        val ex = if (aFirst) fx else ax
        val rects = LinkedHashMap<Int, List<FloatArray>>()
        val parts = ArrayList<String>()
        for (page in sp..ep) {
            val runs = p.runs(page) ?: continue
            val lo = if (page == sp) si else 0
            val hi = if (page == ep) ei else runs.size - 1
            if (lo > hi || lo < 0 || hi >= runs.size) continue
            val slice = ArrayList(runs.subList(lo, hi + 1))
            if (page == sp) {   // 首行：裁掉起点左侧（起点在行尾 → 整行不选）
                val run = slice[0]
                val c = OcrTextSelect.clip(run, OcrTextSelect.charOffset(run, sx), OcrTextSelect.cpCount(run.text))
                if (c != null) slice[0] = c else slice.removeAt(0)
            }
            if (page == ep && slice.isNotEmpty()) {   // 末行：裁掉终点右侧（终点在行首 → 整行不选）
                val li = slice.size - 1
                val run = slice[li]
                val c = OcrTextSelect.clip(run, 0, OcrTextSelect.charOffset(run, ex))
                if (c != null) slice[li] = c else slice.removeAt(li)
            }
            if (slice.isEmpty()) continue
            rects[page] = slice.map { it.rect() }
            parts.add(slice.joinToString("\n") { it.text })
        }
        val text = parts.joinToString("\n")
        if (text.isEmpty()) return null
        return TextSelection(rects, text)
    }

    /**
     * 双击选整行（同 Mac `selectWord` 的 OCR 分支：字符级同点锚定是空选区，直接选命中行整行）。
     */
    fun selectLine(p: Provider, page: Int, nx: Float, ny: Float): TextSelection? {
        val runs = p.runs(page) ?: return null
        val i = lineHit(runs, nx, ny) ?: return null
        val run = runs[i]
        if (run.text.isEmpty()) return null
        return TextSelection(mapOf(page to listOf(run.rect())), run.text)
    }
}
