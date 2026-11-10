package com.xvan.unireader.shared

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 扫描页增强（模式1，`../docs/SCAN-ENHANCE-PLAN.md §2`）：**只在出图时处理，不改 PDF**。给扫描件去底色 / 阴影 / 杂色、让字更黑。
 *
 * 步骤与参数照 Mac `Sources/App/ScanEnhance.swift`（那边是 Core Image 滤镜链，本端没有，按同样的步骤用纯 Kotlin 在像素上算）：
 * 1. 降噪（3×3 轻模糊按比例混回去）；
 * 2. 估纸色——邻域取最亮（抹掉字）再模糊。纸色是低频的，所以**在缩小的图上估**（[paperScale]），用时双线性取样；
 * 3. 原图 ÷ 纸色——纸面统一成白，墨迹对比不变；
 * 4. 软色阶——五点色调曲线（与 Mac 同五个点，单调三次插值），只把接近白的推成白、暗部适度压深；
 * 5. 轻锐化（按亮度做 unsharp，半径 1pt）；可选去色。
 *
 * 与 Mac 的出入：没有「精细处理」（2 倍超采样，手机上太慢太占内存）；降噪不是 Core Image 那种保边降噪。数值不求逐像素相同，只求观感同类。
 * 半径按**页面 pt** 定，乘「像素/pt」换算。纯函数、不认识 Bitmap（JVM 单测 `ScanEnhanceTest`）；位图那层在 `local/PdfSource`。
 */
object ScanEnhance {
    const val MAX_RADIUS_PT = 3f
    const val BLUR_RADIUS_PT = 6f

    /** 参数（默认值同 Mac `ScanEnhanceParams.defaults`）。 */
    data class Params(
        val denoise: Float = 0.5f,
        val whitePoint: Float = 0.92f,
        val inkDarken: Float = 0.7f,
        val sharpen: Float = 0.4f,
        val grayscale: Boolean = false,
    )

    /** 估纸色用的缩小倍数：让「取最亮」的半径落在 2 像素左右（再小字抹不干净，再大白算）。 */
    fun paperScale(pxPerPt: Float): Int = max(1, (MAX_RADIUS_PT * pxPerPt / 2f).roundToInt())

    /** 缩小图上估出来的纸色（每通道 0…255）。 */
    class Paper(val w: Int, val h: Int, val r: IntArray, val g: IntArray, val b: IntArray)

    /**
     * 在缩小的图（[px] ARGB，[w]×[h]）上估纸色：每通道邻域取最亮（半径 [maxR]）→ 三遍盒式模糊（≈ 高斯，半径 [blurR]）。
     * 边缘按「延伸最外一圈」处理（同 Mac 的 clampedToExtent：页边的估计不被画布外拉暗）。
     */
    fun estimatePaper(px: IntArray, w: Int, h: Int, maxR: Int, blurR: Int): Paper {
        val ch = Array(3) { c -> IntArray(w * h) { i -> (px[i] shr (16 - 8 * c)) and 0xFF } }
        val tmp = IntArray(w * h)
        for (c in ch) {
            maxPass(c, tmp, w, h, maxR, horizontal = true)
            maxPass(tmp, c, w, h, maxR, horizontal = false)
            repeat(3) {
                boxPass(c, tmp, w, h, blurR, horizontal = true)
                boxPass(tmp, c, w, h, blurR, horizontal = false)
            }
        }
        return Paper(w, h, ch[0], ch[1], ch[2])
    }

    private fun maxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        for (l in 0 until lines) {
            for (i in 0 until n) {
                var m = 0
                for (k in max(0, i - r)..min(n - 1, i + r)) {
                    val v = if (horizontal) src[l * w + k] else src[k * w + l]
                    if (v > m) m = v
                }
                if (horizontal) dst[l * w + i] = m else dst[i * w + l] = m
            }
        }
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        if (r <= 0) { System.arraycopy(src, 0, dst, 0, w * h); return }
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val win = 2 * r + 1
        // 热循环（整幅亮度图上也跑）：不用闭包，按「起点 + 步长」直接算下标；窗口伸出边界的部分取最外一个
        val step = if (horizontal) 1 else w
        val half = win / 2
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            val last = base + (n - 1) * step
            var sum = 0
            for (k in -r..r) sum += src[base + k.coerceIn(0, n - 1) * step]
            var o = base
            for (i in 0 until n) {
                dst[o] = (sum + half) / win
                o += step
                val add = i + r + 1
                val sub = i - r
                sum += (if (add >= n) src[last] else src[base + add * step]) - (if (sub <= 0) src[base] else src[base + sub * step])
            }
        }
    }

    /**
     * 软色阶的查表（0…255 → 0…255）：五个点同 Mac（`d` = 暗部加深，`wp` = 白点），单调三次插值（不会冲过头）。
     */
    fun toneLut(p: Params): IntArray {
        val d = p.inkDarken.coerceIn(0f, 1f)
        val wp = p.whitePoint.coerceIn(0.8f, 0.98f)
        val xs = floatArrayOf(0f, 0.3f, 0.6f, wp - 0.07f, wp)
        val ys = floatArrayOf(0f, 0.3f - 0.18f * d, 0.6f - 0.1f * d, 0.97f, 1f)
        val n = xs.size
        val dx = FloatArray(n - 1) { xs[it + 1] - xs[it] }
        val sl = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / dx[it] }
        val m = FloatArray(n)
        m[0] = sl[0]
        m[n - 1] = sl[n - 2]
        for (i in 1 until n - 1) m[i] = if (sl[i - 1] * sl[i] <= 0f) 0f else (sl[i - 1] + sl[i]) / 2f
        for (i in 0 until n - 1) {   // Fritsch–Carlson：把切线压回保单调的范围
            if (sl[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / sl[i]
            val b = m[i + 1] / sl[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * sl[i]
                m[i + 1] = t * b * sl[i]
            }
        }
        return IntArray(256) { v ->
            val x = v / 255f
            val y = if (x >= wp) 1f else {
                var i = 0
                while (i < n - 2 && x > xs[i + 1]) i++
                val t = (x - xs[i]) / dx[i]
                val t2 = t * t
                val t3 = t2 * t
                (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * dx[i] * m[i] +
                    (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * dx[i] * m[i + 1]
            }
            (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    /**
     * 处理一条横带（就地改 [px]，ARGB，[w]×[rows]）。这条横带在整张图里从第 [y0] 行起，整张图高 [fullH]——
     * 纸色按整张图的位置取样，所以分条处理与整张处理结果一样（除了锐化在条的最外 [sharpR] 行看不到条外的邻居：
     * 调用方读的时候上下各多带 [sharpR] + 1 行、写回时只写中间）。
     */
    fun processStrip(
        px: IntArray, w: Int, rows: Int, y0: Int, fullH: Int,
        paper: Paper, lut: IntArray, p: Params, sharpR: Int,
    ) {
        val n = w * rows
        // 1. 降噪：3×3 轻模糊按比例混回（在亮度无关的三通道上各做）
        val k = (p.denoise.coerceIn(0f, 1f) * 0.5f * 256f).roundToInt()   // 混入比例（/256）
        val src = if (k > 0) px.copyOf() else px
        val lum = IntArray(n)
        val sx = (paper.w - 1).toFloat() / max(1, w - 1)
        val sy = (paper.h - 1).toFloat() / max(1, fullH - 1)
        // 横向取样位置每行都一样：先算好；每行再把纸色的两行竖向混成一行（宽 paper.w），逐像素只剩一次横向插值
        val colX = IntArray(w)
        val colW = IntArray(w)
        for (x in 0 until w) {
            val fx = x * sx
            val x0 = fx.toInt().coerceIn(0, paper.w - 1)
            colX[x] = x0
            colW[x] = ((fx - x0) * 256f).toInt()
        }
        val rowR = IntArray(paper.w + 1)
        val rowG = IntArray(paper.w + 1)
        val rowB = IntArray(paper.w + 1)
        for (y in 0 until rows) {
            val fy = ((y0 + y).coerceIn(0, fullH - 1)) * sy
            val py0 = fy.toInt().coerceIn(0, paper.h - 1)
            val py1 = min(paper.h - 1, py0 + 1)
            val wy = ((fy - py0) * 256f).toInt()
            for (i in 0 until paper.w) {
                val a = py0 * paper.w + i
                val b2 = py1 * paper.w + i
                rowR[i] = paper.r[a] * (256 - wy) + paper.r[b2] * wy
                rowG[i] = paper.g[a] * (256 - wy) + paper.g[b2] * wy
                rowB[i] = paper.b[a] * (256 - wy) + paper.b[b2] * wy
            }
            rowR[paper.w] = rowR[paper.w - 1]; rowG[paper.w] = rowG[paper.w - 1]; rowB[paper.w] = rowB[paper.w - 1]
            val ym = max(0, y - 1) * w
            val yc = y * w
            val yp = min(rows - 1, y + 1) * w
            for (x in 0 until w) {
                var r: Int
                var g: Int
                var b: Int
                val c = src[yc + x]
                r = (c shr 16) and 0xFF
                g = (c shr 8) and 0xFF
                b = c and 0xFF
                if (k > 0) {
                    val xm = max(0, x - 1)
                    val xp = min(w - 1, x + 1)
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var row = ym
                    for (ri in 0 until 3) {   // 不建临时数组：这是逐像素的热循环
                        val q0 = src[row + xm]
                        val q1 = src[row + x]
                        val q2 = src[row + xp]
                        sr += ((q0 shr 16) and 0xFF) + ((q1 shr 16) and 0xFF) + ((q2 shr 16) and 0xFF)
                        sg += ((q0 shr 8) and 0xFF) + ((q1 shr 8) and 0xFF) + ((q2 shr 8) and 0xFF)
                        sb += (q0 and 0xFF) + (q1 and 0xFF) + (q2 and 0xFF)
                        row = if (ri == 0) yc else yp
                    }
                    r += ((sr / 9 - r) * k) shr 8
                    g += ((sg / 9 - g) * k) shr 8
                    b += ((sb / 9 - b) * k) shr 8
                }
                // 2/3. 纸色（双线性）→ 原图 ÷ 纸色
                val px0 = colX[x]
                val wx = colW[x]
                val bgR = max(1, (rowR[px0] * (256 - wx) + rowR[px0 + 1] * wx) shr 16)
                val bgG = max(1, (rowG[px0] * (256 - wx) + rowG[px0 + 1] * wx) shr 16)
                val bgB = max(1, (rowB[px0] * (256 - wx) + rowB[px0 + 1] * wx) shr 16)
                r = min(255, r * 255 / bgR)
                g = min(255, g * 255 / bgG)
                b = min(255, b * 255 / bgB)
                // 4. 软色阶
                r = lut[r]
                g = lut[g]
                b = lut[b]
                if (p.grayscale) {
                    val l = (r * 54 + g * 183 + b * 19) shr 8
                    r = l; g = l; b = l
                }
                lum[yc + x] = (r * 54 + g * 183 + b * 19) shr 8
                px[yc + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
        // 5. 轻锐化：亮度的 unsharp（原亮度 − 模糊亮度）按强度加回三通道
        val amt = (p.sharpen.coerceIn(0f, 1f) * 0.9f * 256f).roundToInt()
        if (amt <= 0 || sharpR <= 0) return
        val blur = IntArray(n)
        val tmp = IntArray(n)
        boxPass(lum, tmp, w, rows, sharpR, horizontal = true)
        boxPass(tmp, blur, w, rows, sharpR, horizontal = false)
        for (i in 0 until n) {
            val dl = ((lum[i] - blur[i]) * amt) shr 8
            if (dl == 0) continue
            val c = px[i]
            val r = (((c shr 16) and 0xFF) + dl).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + dl).coerceIn(0, 255)
            val b = ((c and 0xFF) + dl).coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
