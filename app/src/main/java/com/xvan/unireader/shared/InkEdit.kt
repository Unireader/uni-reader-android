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
    fun translated(pts: List<Pt3>, dx: Float, dy: Float): List<Pt3> = pts.map {
        Pt3((it.x + dx).coerceIn(0f, 1f), (it.y + dy).coerceIn(0f, 1f), it.p)
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
}
