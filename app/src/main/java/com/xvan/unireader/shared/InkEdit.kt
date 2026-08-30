package com.xvan.unireader.shared

/**
 * 笔迹编辑的纯函数，对应 Mac 端 `Sources/App/InkEdit.swift`。
 *
 * Mac 那边三个函数住在一个文件里，安卓这边按调用处散在三处——**找不到时看这张索引**：
 * | Mac `InkEdit` | 安卓 |
 * | --- | --- |
 * | `rulerSnap` | [PadConst.rulerSnap]（几何常量与它同源，就近放） |
 * | `splitStroke` | 本文件（[PageCanvasView.eraseHit] 的局部擦除分支调它） |
 * | `translated`  | 本文件 |
 * | `scaled` / `pointInPolygon` | 本文件（框选缩放 / 自由框选多边形命中） |
 *
 * 这几个都是**同一算法多份实现**（Mac/网页/安卓各一份），改一处必须同步其余——
 * 对不上的表现是「同一个工作区在两端看到的笔迹位置不一样」。
 */
object InkEdit {

    /**
     * 局部擦除切段：剔除距擦除点 (nx, ny) 不超过 r 的点（`r2` = r²，调用方算好），
     * 连续未命中段各成一条新笔迹；全部命中返回空表。
     *
     * 与 Mac `InkEdit.splitStroke` 同一算法。两处有意的差异：
     * - Mac 一次吃一批擦除点（z 槽位放页号），这里一次一个——安卓的调用处（`eraseHit`）
     *   是每个 MotionEvent 调一次，批在调用方那里；
     * - Mac 给每段发新 UUID，这里**沿用原 id**——模式1 的 [LibraryStore.reconcileStrokes]
     *   靠「头一段沿用原 id」对齐库（原因见那里的长注释），新段 id 由落库时发。
     *
     * 页过滤在这里做（`s.page != page` 原样返回），于是**坐标系无关**：页内归一化与草稿纸
     * 画布坐标都能用，调用方保证 r 与点同单位。草稿纸笔迹 `page` 恒为 0，调用方同样传 0。
     *
     * 一个点都没命中时原样返回 `listOf(s)`（**同一实例**，调用方可按 `===` 判零变化）。
     *
     * ⚠️ 切出来的段**必须带上原笔迹的 id/layerId/padId**：丢了 id 就只能整篇重写笔迹；
     * 丢了 padId，草稿纸上被局部擦过的笔迹会变成 padId 为空的孤儿——界面上当场消失
     * （按 padId 过滤取不到），却以 kind=2 的身份留在库里污染页内笔迹（handoff §2.5）。
     */
    fun splitStroke(s: Stroke, nx: Float, ny: Float, page: Int, r2: Float): List<Stroke> {
        if (s.page.toInt() != page) return listOf(s)
        val out = ArrayList<Stroke>()
        var seg = ArrayList<Pt3>()
        var anyHit = false
        fun flush() {
            if (seg.isNotEmpty()) {
                out.add(Stroke(s.page, s.pen, seg, s.id, s.layerId, s.padId))
                seg = ArrayList()
            }
        }
        for (pt in s.pts) {
            val dx = pt.x - nx
            val dy = pt.y - ny
            if (dx * dx + dy * dy <= r2) { anyHit = true; flush() } else seg.add(pt)
        }
        flush()
        return if (anyHit) out else listOf(s)
    }

    /**
     * 平移点集：各点 +(dx, dy) 后 clamp 回页内 [0,1]，压感不动。
     *
     * clamp **必须逐点做**（不是整条按包围盒推回来）：Mac 的 `translated` 就是逐点
     * `min(1, max(0, ...))`，贴页边时笔迹会被压扁——两端要压扁得一模一样，
     * 差一点就是「平板上移到页边的笔迹，回 Mac 打开时形状不同」。
     */
    /**
     * [xMargin] = 画板模式下每侧页边宽度（页宽的倍数）：x 的 clamp 放宽到 `-xMargin … 1+xMargin`，
     * 页边笔迹才能在页外平移。**默认 0 = 不出本页**，与画板模式之前逐点同行为
     *（同 Mac `InkEdit.translated` 的 `xRange` 参数）。
     */
    fun translated(pts: List<Pt3>, dx: Float, dy: Float, xMargin: Float = 0f): List<Pt3> = pts.map {
        Pt3((it.x + dx).coerceIn(-xMargin, 1f + xMargin), (it.y + dy).coerceIn(0f, 1f), it.p)
    }

    /**
     * 点集的归一化包围盒 `[minX, minY, maxX, maxY]`；一个点都没有时返回 null（不参与定界）。
     * 画板模式下 x 可越出 `0…1`，故**不能按页角起算**——整条笔画都在页外时会被硬撑到页边。
     * 同 Mac `InkEdit.bounds`。
     */
    fun bounds(strokes: List<Stroke>): FloatArray? {
        var x0 = Float.POSITIVE_INFINITY; var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
        for (s in strokes) {
            for (p in s.pts) {
                if (p.x < x0) x0 = p.x
                if (p.y < y0) y0 = p.y
                if (p.x > x1) x1 = p.x
                if (p.y > y1) y1 = p.y
            }
        }
        return if (x0 <= x1) floatArrayOf(x0, y0, x1, y1) else null
    }

    /**
     * 刚性平移的**位移夹取**：把 `(dx, dy)` 整体夹进「选中集不越界」的范围，调用方随后原样
     * [translated] 每个点。返回 `[dx, dy]`。同 Mac `InkEdit.fitTranslation`。
     *
     * 🔴 框选**整团**移动必须先过这里：[translated] 是逐点 clamp 的，一团笔迹撞上边界时越界的
     * 那一头会被逐点摁在边界线上 = 笔迹被压扁成一条线（用户 2026-08-30 在平板上报的
     * 「画板模式下框选移动把笔迹压缩了」）。注意这与 [translated] 自身「逐点 clamp」的三端一致性
     * 不冲突：那条说的是**单点越界怎么处理**，这里管的是**整团位移先夹到多少**——
     * 两条权威路径（Mac 的 `commitLassoMove`/`applyLassoMove`、模式1 的 `onLassoMoveCommit`）
     * 都先夹再平移，形状才在两端一致。
     *
     * 笔迹按 `-xMargin … 1+xMargin` 定界、文字注解按页内 `0…1` 定界（注解不出页），两者取交集；
     * null（无此类选中项）不参与。选中集本身比可写区间还宽这种退化情形不夹（保形优先，逐点 clamp 兜底）。
     * `bounds` 均为 `[minX, minY, maxX, maxY]`。
     */
    fun fitTranslation(
        dx: Float, dy: Float, inkBounds: FloatArray?, xMargin: Float = 0f, noteBounds: FloatArray? = null,
    ): FloatArray {
        var loX = -Float.MAX_VALUE; var hiX = Float.MAX_VALUE
        var loY = -Float.MAX_VALUE; var hiY = Float.MAX_VALUE
        fun fit(b: FloatArray?, xLo: Float, xHi: Float) {
            if (b == null) return
            loX = kotlin.math.max(loX, xLo - b[0]); hiX = kotlin.math.min(hiX, xHi - b[2])
            loY = kotlin.math.max(loY, -b[1]); hiY = kotlin.math.min(hiY, 1f - b[3])
        }
        fit(inkBounds, -xMargin, 1f + xMargin)
        fit(noteBounds, 0f, 1f)
        return floatArrayOf(
            if (loX <= hiX) dx.coerceIn(loX, hiX) else dx,
            if (loY <= hiY) dy.coerceIn(loY, hiY) else dy,
        )
    }

    /**
     * 平移一个归一化矩形 `[x, y, w, h]`（文字注解的 anchor 与逐行 rects，框选移动用）：
     * **两个角各自 clamp**，贴页边时宽/高跟着收缩——同 Mac `InkEdit.translatedRect`。
     * 整块推回来的话贴边后尺寸不变，两端就会长得不一样（与点集逐点 clamp 是同一条道理）。
     */
    fun translatedRect(r: DoubleArray, dx: Double, dy: Double): DoubleArray {
        fun cl(v: Double) = v.coerceIn(0.0, 1.0)
        val x1 = cl(r[0] + dx)
        val x2 = cl(r[0] + r[2] + dx)
        val y1 = cl(r[1] + dy)
        val y2 = cl(r[1] + r[3] + dy)
        return doubleArrayOf(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
    }

    /**
     * 框选缩放：点集绕锚点 `(ax, ay)` 按轴缩放 `(p−a)×s+a`，x/y 各 clamp 回页内 [0,1]
     * （压感/id/layerId/padId 不动）。线宽按几何平均 `√(sx·sy)` 同步缩放并 clamp 到 0.5...40——
     * 笔迹放大不变细、缩小不变粗（同 Mac `InkEdit.scaled`；s 本身由调用方 clamp 过）。
     * 归一化坐标 x/y 两轴尺度不同，但按轴缩放是逐轴线性变换，无需 aspect 折算。
     */
    fun scaled(s: Stroke, ax: Float, ay: Float, sx: Float, sy: Float, xMargin: Float = 0f): Stroke {
        val pts = s.pts.map {
            Pt3(
                (ax + (it.x - ax) * sx).coerceIn(-xMargin, 1f + xMargin),
                (ay + (it.y - ay) * sy).coerceIn(0f, 1f),
                it.p,
            )
        }
        val w = (s.pen.w * kotlin.math.sqrt(sx * sy)).coerceIn(0.5f, 40f)
        return Stroke(s.page, s.pen.copy(w = w), pts, s.id, s.layerId, s.padId)
    }

    /**
     * 归一化 rect 缩放（文字注解的 anchor 与逐行 rects，框选缩放用）：min/max 角各绕锚点
     * 按轴缩放并 clamp 到 0~1——同 Mac `InkEdit.scaledRect`（与 [translatedRect] 同一套两角口径，
     * 贴页边时宽/高跟着收缩；字号不缩——注解是文字不是图形）。
     */
    fun scaledRect(r: DoubleArray, ax: Double, ay: Double, sx: Double, sy: Double): DoubleArray {
        fun cl(v: Double) = v.coerceIn(0.0, 1.0)
        val x1 = cl(ax + (r[0] - ax) * sx)
        val x2 = cl(ax + (r[0] + r[2] - ax) * sx)
        val y1 = cl(ay + (r[1] - ay) * sy)
        val y2 = cl(ay + (r[1] + r[3] - ay) * sy)
        return doubleArrayOf(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
    }

    /**
     * 点在不规则多边形内（自由框选命中）：射线法（向右水平射线计奇偶交点）。
     * 与 Mac `InkEdit.pointInPolygon`、web `render.ts` 同名函数**同一算法三份实现**，改一处必须同步。
     * `poly` 是扁平数组 `[x0,y0,x1,y1,…]`（页内归一化），首尾自动闭合；< 3 个点恒 false；
     * 恰在边界上的点判内（框线擦到也算选中，手感宽；容差 1e-9，故内部按 Double 算）。
     */
    fun pointInPolygon(px: Float, py: Float, poly: FloatArray): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        val x = px.toDouble()
        val y = py.toDouble()
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val ax = poly[i * 2].toDouble(); val ay = poly[i * 2 + 1].toDouble()
            val bx = poly[j * 2].toDouble(); val by = poly[j * 2 + 1].toDouble()
            // 边界判定：p 落在线段 a-b 上（共线且在包围盒内）
            val cross = (x - ax) * (by - ay) - (y - ay) * (bx - ax)
            if (kotlin.math.abs(cross) < 1e-9 &&
                x >= minOf(ax, bx) - 1e-9 && x <= maxOf(ax, bx) + 1e-9 &&
                y >= minOf(ay, by) - 1e-9 && y <= maxOf(ay, by) + 1e-9
            ) return true
            // 射线法：边跨越 p.y 水平线时，比较交点 x 与 p.x
            if ((ay > y) != (by > y)) {
                val xInt = ax + (y - ay) / (by - ay) * (bx - ax)
                if (x < xInt) inside = !inside
            }
            j = i
        }
        return inside
    }
}
