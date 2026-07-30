package com.xvan.unireader.shared

/**
 * 笔迹编辑的纯函数，对应 Mac 端 `Sources/App/InkEdit.swift`。
 *
 * Mac 那边三个函数住在一个文件里，安卓这边按调用处散在三处——**找不到时看这张索引**：
 * | Mac `InkEdit` | 安卓 |
 * | --- | --- |
 * | `rulerSnap` | [PadConst.rulerSnap]（几何常量与它同源，就近放） |
 * | `splitStroke` | 内联在 [PageCanvasView.eraseHit] 的局部擦除分支（它要就地改视图里的 `strokes`） |
 * | `translated`  | 本文件 |
 *
 * 这几个都是**同一算法多份实现**（Mac/网页/安卓各一份），改一处必须同步其余——
 * 对不上的表现是「同一个工作区在两端看到的笔迹位置不一样」。
 */
object InkEdit {

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
