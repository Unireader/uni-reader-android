package com.xvan.unireader.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 文字笔记展开气泡的几何（纯计算，不碰 Canvas，便于单测）。
 *
 * 🔴 **三端渲染契约**：尺寸全部是**页宽的比例**——用户拍板「跟页缩放」，缩放页面时气泡跟着缩，
 * 页面版式看起来是一体的。这套比例常数在
 * Mac `Sources/Views/NoteBubbleView.swift`(`NoteBubble`) / web `web/src/lib/render.ts`(`BUB`) /
 * 本文件**各存一份，改一处必须同步另外两处**（同 `InkEdit` / 草稿纸底纹的先例）。
 * 正文各端用各自的排版引擎（Mac Markdown 引擎 / web measureText / 本端 Markwon + StaticLayout，见 [NoteBubbleText]），
 * 行末断点允许细微差异。
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

    /**
     * 手动摆过的卡片（Mac `NoteCard`）存的数是「固定尺寸口径下的点」，跟页缩放口径按 页宽 ÷ 参考页宽 换算
     * （Mac `NoteBubble.refPageWidth` = 最大宽 280 ÷ 宽比 0.30 ≈ 933pt）。本端恒为跟页缩放口径。
     */
    const val REF_PAGE_W = 280f / WIDTH
    /** 卡片最小宽的「点」数（Mac `NoteBubble.widthRange.lowerBound`） */
    const val CARD_MIN_W = 80f

    /** 一只气泡的屏显几何（视口 px）。正文由宿主按 [contentW] / [maxLines] 排好版再画（[NoteBubbleText]）。 */
    class Box(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val fs: Float,
        val pad: Float,
        /** 右上角铅笔的边长；0 = 不画（笔悬停的只读预览） */
        val edit: Float,
        /** 正文排版宽（px） */
        val contentW: Float,
        /** 正文最多几行（超出省略号；摆过高度的卡片按高度上限折算） */
        val maxLines: Int,
    )

    /**
     * 气泡布局（位置规则与 Mac `NoteBubble.origin` / web `noteBubbleBox` 一致）：
     * - 没摆过：图钉**右侧**优先、放不下翻左侧，再整体钳进该页的显示矩形内；宽 = 页宽 × [WIDTH]。
     * - 摆过（[card] 非 null，Mac `NoteBubble.cardOrigin` / `cardWidth` / `placed`）：左上角 = 图钉中心 + 存的偏移，
     *   钳进页内，压住自己的图钉就整块挪开；宽 / 高上限按存的数换算（没动过的那一维照自动规则）。
     *
     * @param pageW 该页当前显示宽（px）——所有尺寸的基准
     * @param pinX/pinY 图钉中心（视口 px）
     * @param pinR 图钉半径（视口 px）
     * @param sticky 常驻气泡（点开的/始终展示的）才画铅笔
     * @param textHeight 正文按 (排版宽, 字号, 最多行数) 排完有多高（px）
     */
    fun layout(
        pageW: Float,
        pinX: Float,
        pinY: Float,
        pinR: Float,
        pageLeft: Float,
        pageTop: Float,
        pageRight: Float,
        pageBottom: Float,
        sticky: Boolean,
        card: NoteCard? = null,
        textHeight: (contentW: Float, fs: Float, maxLines: Int) -> Float,
    ): Box {
        val fs = pageW * FONT
        val pad = fs * PAD
        val edit = if (sticky) fs * EDIT else 0f
        val lineH = fs * LINE_H
        val unit = pageW / REF_PAGE_W
        val minW = max(CARD_MIN_W * unit, fs * EDIT + pad * 2f + fs * 3f)
        val minH = lineH + pad * 2f
        val w = card?.w?.let { (it * unit).coerceIn(minW, max(minW, pageRight - pageLeft)) } ?: pageW * WIDTH
        val capH = card?.h?.let { max(it * unit, minH) }
        // 高度上限能完整放下几行（Mac 那边超出在卡片里滚动；本端画布里不滚，截成整行 + 省略号）
        val maxLines = capH?.let { max(1, ((it - pad * 2f) / lineH).toInt()) } ?: MAX_LINES
        val contentW = max(fs, w - pad * 2f - edit)
        var h = pad * 2f + textHeight(contentW, fs, maxLines)
        if (capH != null) h = min(h, capH)
        val maxX = max(pageLeft, pageRight - w)
        val maxY = max(pageTop, pageBottom - h)
        if (card != null) {
            var x = (pinX + card.dx * unit).coerceIn(pageLeft, maxX)
            var y = (pinY + card.dy * unit).coerceIn(pageTop, maxY)
            // 不许盖住自己的图钉（Mac `NoteCardPin.pushOut`）：试右 / 左 / 下 / 上四个贴边位置，取离原位最近且不压住的
            val c = pinR + fs * GAP
            val kl = pinX - c
            val kt = pinY - c
            val kr = pinX + c
            val kb = pinY + c
            fun overlaps(ox: Float, oy: Float) = ox < kr && ox + w > kl && oy < kb && oy + h > kt
            if (overlaps(x, y)) {
                val cands = listOf(
                    kr.coerceIn(pageLeft, maxX) to y,
                    (kl - w).coerceIn(pageLeft, maxX) to y,
                    x to kb.coerceIn(pageTop, maxY),
                    x to (kt - h).coerceIn(pageTop, maxY),
                )
                cands.filter { !overlaps(it.first, it.second) }
                    .minByOrNull { abs(it.first - x) + abs(it.second - y) }
                    ?.let { x = it.first; y = it.second }
            }
            return Box(x, y, w, h, fs, pad, edit, contentW, maxLines)
        }
        var x = pinX + pinR + fs * GAP
        if (x + w > pageRight) x = pinX - pinR - fs * GAP - w
        val y = pinY - pinR
        return Box(
            x = x.coerceIn(pageLeft, maxX),
            y = y.coerceIn(pageTop, maxY),
            w = w, h = h, fs = fs, pad = pad, edit = edit, contentW = contentW, maxLines = maxLines,
        )
    }

    // —— 图片笔记的气泡（Mac `ImageBubble`，比例常数同那边）——
    const val IMG_MIN_W = 120f              // 自动宽下限的「点」数（Mac `NoteBubble.fixedMinWidth`）
    const val IMG_MAX_THUMB_H = 1.0f        // 缩略图高上限 ÷ 气泡宽
    const val IMG_CAPTION_LINES = 3
    const val IMG_CAPTION_GAP = 0.35f       // 缩略图与说明之间 ÷ 字号
    const val IMG_MIN_W_RATIO = 0.45f       // 竖图收窄的下限 ÷ 最大宽
    const val IMG_MISSING_H = 3.5f          // 图不在时占位高 ÷ 字号

    /** 图片气泡：外框（[box]，没有铅笔）+ 缩略图在屏上的位置 */
    class ImageBox(val box: Box, val tx: Float, val ty: Float, val tw: Float, val th: Float)

    /**
     * 图片气泡布局（Mac `ImageBubble.size` + 同文字气泡的落位规则）：横图撑满口径宽；竖图高到上限（= 气泡宽）后
     * 按比例缩窄，气泡跟着收窄贴着图（下限 45% 宽）；说明在缩略图下面，最多 3 行；图不在时占位一小条。
     * 摆过的卡片：宽定死、高是上限（比内容矮时截掉下面）。
     *
     * @param pixelW/pixelH 图的像素尺寸（按显示方向）；[missing] = 图文件不在
     * @param captionHeight 说明按 (排版宽, 字号, 最多行数) 排完多高；说明为空时不会被调用
     */
    fun imageLayout(
        pageW: Float,
        pinX: Float,
        pinY: Float,
        pinR: Float,
        pageLeft: Float,
        pageTop: Float,
        pageRight: Float,
        pageBottom: Float,
        card: NoteCard?,
        pixelW: Int,
        pixelH: Int,
        missing: Boolean,
        hasCaption: Boolean,
        captionHeight: (contentW: Float, fs: Float, maxLines: Int) -> Float,
    ): ImageBox {
        val fs = pageW * FONT
        val pad = fs * PAD
        val lineH = fs * LINE_H
        val unit = pageW / REF_PAGE_W
        val maxW = pageW * WIDTH
        val minCardW = max(CARD_MIN_W * unit, fs * EDIT + pad * 2f + fs * 3f)
        val cardW = card?.w?.let { (it * unit).coerceIn(minCardW, max(minCardW, pageRight - pageLeft)) }
        val budget = cardW ?: maxW
        val fullW = max(1f, budget - pad * 2f)
        val tw: Float
        val th: Float
        if (missing || pixelW <= 0 || pixelH <= 0) {
            tw = fullW
            th = fs * IMG_MISSING_H
        } else {
            val aspect = pixelH.toFloat() / pixelW
            val capH = budget * IMG_MAX_THUMB_H
            th = min(fullW * aspect, capH)
            tw = if (th >= capH) max(1f, capH / aspect) else fullW
        }
        val w = cardW ?: min(maxW, max(tw + pad * 2f, max(IMG_MIN_W * unit, maxW * IMG_MIN_W_RATIO)))
        // 说明最多 3 行由排版那一侧截（maxLines），这里只加间隙
        val captionPart = if (!hasCaption) 0f else fs * IMG_CAPTION_GAP + captionHeight(max(1f, w - pad * 2f), fs, IMG_CAPTION_LINES)
        val contentH = pad * 2f + th + captionPart
        val capH = card?.h?.let { max(it * unit, lineH + pad * 2f) }
        val h = if (capH != null && contentH > capH) capH else contentH
        // 落位与文字气泡同一套（右侧优先 / 卡片偏移 + 让开图钉）：借 [layout] 算，正文高度直接给成内容高
        val b = layout(
            pageW, pinX, pinY, pinR, pageLeft, pageTop, pageRight, pageBottom, sticky = false,
            card = card?.let { NoteCard(it.dx, it.dy, w / unit, null) },
            textHeight = { _, _, _ -> h - pad * 2f },
        ).let { lb ->
            if (card != null) lb else {
                // 自动宽可能比文字气泡窄（竖图）：按真实宽重算一次左右翻转与钳位
                var x = pinX + pinR + fs * GAP
                if (x + w > pageRight) x = pinX - pinR - fs * GAP - w
                Box(
                    x.coerceIn(pageLeft, max(pageLeft, pageRight - w)),
                    (pinY - pinR).coerceIn(pageTop, max(pageTop, pageBottom - h)),
                    w, h, fs, pad, 0f, max(1f, w - pad * 2f), IMG_CAPTION_LINES,
                )
            }
        }
        val box = Box(b.x, b.y, w, h, fs, pad, 0f, max(1f, w - pad * 2f), IMG_CAPTION_LINES)
        return ImageBox(box, b.x + (w - tw) / 2f, b.y + pad, tw, th)
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
