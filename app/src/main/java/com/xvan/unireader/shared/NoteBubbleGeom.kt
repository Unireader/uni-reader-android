package com.xvan.unireader.shared

import kotlin.math.max

/**
 * 文字笔记展开气泡的几何（纯计算，不碰 Canvas，便于单测）。
 *
 * 🔴 **三端渲染契约**：尺寸全部是**页宽的比例**——用户拍板「跟页缩放」，缩放页面时气泡跟着缩，
 * 页面版式看起来是一体的。这套比例常数在
 * Mac `Sources/Views/NoteBubbleView.swift`(`NoteBubble`) / web `web/src/lib/render.ts`(`BUB`) /
 * 本文件**各存一份，改一处必须同步另外两处**（同 `InkEdit` / 草稿纸底纹的先例）。
 * 折行各端用各自的排版引擎（TextKit / measureText / Paint.measureText），行末断点允许细微差异。
 */
object NoteBubbleGeom {
    // 2026-09-13 调小过一轮（字号 0.022→0.017、行高 1.35→1.25、内边距 0.55→0.30、圆角 0.5→0.4），三端同步。
    // Mac 端这套比例只在「笔记气泡跟随页面缩放」打开时用（默认是固定尺寸），网页/安卓恒跟页缩放。
    const val WIDTH = 0.30f     // 气泡宽 ÷ 页宽
    const val FONT = 0.017f     // 正文字号 ÷ 页宽
    const val LINE_H = 1.25f    // 行高 ÷ 字号
    const val PAD = 0.30f       // 内边距 ÷ 字号
    const val RADIUS = 0.4f     // 圆角 ÷ 字号
    const val GAP = 0.25f       // 图钉与气泡的间隙 ÷ 字号
    const val EDIT = 1.7f       // 右上角「编辑」按钮的边长（= 热区）÷ 字号
    const val ICON = 0.78f      // 图标画多大 ÷ 按钮边长（热区比图标大一圈，好点）
    const val MAX_LINES = 10    // 超出即截断（全文去编辑器里看）

    /** 一只气泡的屏显几何（视口 px）+ 已折好的行。 */
    class Box(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val fs: Float,
        val pad: Float,
        /** 右上角铅笔的边长；0 = 不画（笔悬停的只读预览） */
        val edit: Float,
        val lines: List<String>,
    )

    /**
     * 正文折行：逐字符塞，塞不下就换行（中英混排一律按量出来的宽度累加，够用）。
     * 超过 [MAX_LINES] 在末行加省略号——一条笔记不该糊住半页。
     */
    fun wrap(text: String, maxW: Float, measure: (String) -> Float): List<String> {
        val out = ArrayList<String>()
        var truncated = false
        val paras = text.split("\n")
        for ((pi, s) in paras.withIndex()) {
            if (truncated) break
            var line = StringBuilder()
            for (ch in s) {
                val t = line.toString() + ch
                if (line.isNotEmpty() && measure(t) > maxW) {
                    out.add(line.toString())
                    line = StringBuilder().append(ch)
                    if (out.size >= MAX_LINES) { truncated = true; break }
                } else line.append(ch)
            }
            if (truncated) break            // 段落没排完就满了
            out.add(line.toString())
            if (out.size >= MAX_LINES && pi < paras.size - 1) truncated = true
        }
        if (truncated && out.isNotEmpty()) {
            val last = out[out.size - 1]
            out[out.size - 1] = last.substring(0, max(0, last.length - 1)) + "…"
        }
        return out
    }

    /**
     * 气泡布局：图钉**右侧**优先、放不下翻左侧，再整体钳进该页的显示矩形内
     * （位置规则与 Mac `NoteBubbleView.origin` / web `noteBubbleBox` 一致）。
     *
     * @param pageW 该页当前显示宽（px）——所有尺寸的基准
     * @param pinX/pinY 图钉中心（视口 px）
     * @param pinR 图钉半径（视口 px）
     * @param sticky 常驻气泡（点开的/始终展示的）才画铅笔
     * @param measure 按气泡字号量一段文字的宽度
     */
    fun layout(
        text: String,
        pageW: Float,
        pinX: Float,
        pinY: Float,
        pinR: Float,
        pageLeft: Float,
        pageTop: Float,
        pageRight: Float,
        pageBottom: Float,
        sticky: Boolean,
        measure: (String, Float) -> Float,
    ): Box {
        val fs = pageW * FONT
        val w = pageW * WIDTH
        val pad = fs * PAD
        val edit = if (sticky) fs * EDIT else 0f
        val lines = wrap(text, max(fs, w - pad * 2f - edit)) { measure(it, fs) }
        val h = pad * 2f + lines.size * fs * LINE_H
        var x = pinX + pinR + fs * GAP
        if (x + w > pageRight) x = pinX - pinR - fs * GAP - w
        val y = pinY - pinR
        return Box(
            x = x.coerceIn(pageLeft, max(pageLeft, pageRight - w)),
            y = y.coerceIn(pageTop, max(pageTop, pageBottom - h)),
            w = w, h = h, fs = fs, pad = pad, edit = edit, lines = lines,
        )
    }

    /** 铅笔热区的左上角（与 [drawNoteBubble] 画的位置同源，见 PadOverlays）。 */
    fun editHitLeft(b: Box): Float = b.x + b.w - b.edit - b.pad * 0.4f

    fun editHitTop(b: Box): Float = b.y + b.pad * 0.4f

    /** 点 (x,y) 是否落在气泡右上角的铅笔上（edit=0 恒 false）。 */
    fun hitEdit(b: Box, x: Float, y: Float): Boolean {
        if (b.edit <= 0f) return false
        val l = editHitLeft(b)
        val t = editHitTop(b)
        return x >= l && x <= l + b.edit && y >= t && y <= t + b.edit
    }

    /** 这条笔记此刻要不要展开（空正文永不展开；hover 模式在触摸端由 expanded 降级承担）。 */
    fun visible(n: TextNote, expanded: Set<String>, hoverId: String?): Boolean {
        if (n.text.isEmpty()) return false
        return when (n.display) {
            NOTE_ALWAYS -> true
            NOTE_HOVER -> hoverId == n.id || expanded.contains(n.id)
            else -> expanded.contains(n.id)
        }
    }

    /** 常驻气泡（点开的 / 始终展示的）才有铅笔：笔悬停的预览一移开就收，那颗按钮够不着。 */
    fun sticky(n: TextNote, expanded: Set<String>): Boolean =
        n.display == NOTE_ALWAYS || expanded.contains(n.id)
}
