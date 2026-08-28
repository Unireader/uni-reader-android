package com.xvan.unireader.shared

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 画板模式的**页边软边界**（纯数学，`CanvasMarginTest` 覆盖）。
 * **与 Mac `Sources/App/CanvasMargin.swift` / web `shared.ts` 的 `canvasMarginFor` 同一组常数与算法**
 * ——三份实现，改一边必须同步其余两端（同 `InkEdit` 的惯例）。
 *
 * ## 为什么不引入新坐标系
 *
 * 页边笔迹仍是**页内笔迹**（归属那一页），只是归一化 `x` 越出 `0…1` —— 单位还是「页宽的倍数」，
 * `x = -0.5` 就是页左边缘再往左半个页宽（`y` 永远还在 `0…1`，页边只横向延伸）。
 * 于是库 schema（除了 `document.canvas_mode` 那一列开关）、线格式、擦除/框选/图层全都不用动。
 *
 * ## 两模式的真源不同
 *
 * - **模式2**：Mac 单方面决定 margin 并经 `canvas`(0x4B) 下发，本端只在落笔中乐观跳档
 *   （免得写到边缘要等一个 RTT 才有地方下笔），下一条下发即以 Mac 为准。
 * - **模式1**：本机就是真源，直接用这里的 [marginFor] 从笔迹算。
 */
object CanvasMargin {
    /** 生长档位（页宽的倍数），也是画板模式的起步宽度：每侧半个页宽 */
    const val STEP = 0.5f
    /** 触发生长的余量：笔迹离边界不足这么多就跳一档 */
    const val SLACK = 0.35f
    /** 每侧上限（页宽的倍数）：防坏数据把滚动区推到天边 */
    const val LIMIT = 8f

    /** 单点的横向越界量（0 = 在页内） */
    fun overflowOf(nx: Float): Float = if (nx < 0f) -nx else if (nx > 1f) nx - 1f else 0f

    /** 一批笔迹的最大横向越界量（0 = 全在页内） */
    fun overflow(strokes: List<Stroke>): Float {
        var o = 0f
        for (s in strokes) {
            val pts = s.pts
            for (p in pts) o = max(o, overflowOf(p.x))
        }
        return o
    }

    /**
     * 越界量 → 每侧页边宽度（页宽的倍数），档位化并夹在 `STEP…LIMIT`。
     * 全在页内（`o == 0`）也给一档：画板一开就得有地方下笔。
     */
    fun marginFor(overflow: Float): Float {
        val need = max(0f, overflow) + SLACK
        return min(LIMIT, max(STEP, ceil(need / STEP) * STEP))
    }
}
