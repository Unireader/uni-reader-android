package com.xvan.unireader.shared

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 拖笔记卡片（气泡）：按下的位置决定是移动还是改大小，由位移算出新的 [NoteCard]。
 * 纯函数，逐条照 Mac `Sources/App/TextNoteModel.swift` 的 `NoteCardZone` / `NoteCardPin` / `NoteCardDrag`（本端 `NoteCardDragTest`）。
 * 全部是**页内像素**（左上原点）；存的数 × `unit` = 页内像素（`unit` = 页宽 ÷ [NoteBubbleGeom.REF_PAGE_W]）。
 */
object NoteCardDrag {

    /** 按下的区域：[h] 横向 -1 拖左边 / +1 拖右边 / 0 不改宽；[v] 纵向 -1 拖上边 / +1 拖下边 / 0 不改高。两个都 0 = 移动。 */
    data class Zone(val h: Int, val v: Int) {
        val isMove: Boolean get() = h == 0 && v == 0
    }

    /**
     * [px],[py] = 卡片内坐标。角的命中边长 [corner]、边的命中宽 [edge]（屏幕像素——手指要瞄的是屏幕上的那一块，
     * 本端比 Mac 的鼠标口径大得多）。
     */
    fun zoneAt(px: Float, py: Float, w: Float, h: Float, corner: Float, edge: Float): Zone {
        val nearL = px <= corner
        val nearR = px >= w - corner
        val nearT = py <= corner
        val nearB = py >= h - corner
        if (nearT && nearL) return Zone(-1, -1)
        if (nearT && nearR) return Zone(1, -1)
        if (nearB && nearL) return Zone(-1, 1)
        if (nearB && nearR) return Zone(1, 1)
        if (px <= edge) return Zone(-1, 0)
        if (px >= w - edge) return Zone(1, 0)
        if (py <= edge) return Zone(0, -1)
        if (py >= h - edge) return Zone(0, 1)
        return Zone(0, 0)
    }

    private fun clamp(v: Float, lo: Float, hi: Float) = min(max(v, lo), max(lo, hi))

    private fun overlaps(x: Float, y: Float, w: Float, h: Float, kl: Float, kt: Float, kr: Float, kb: Float) =
        x < kr && x + w > kl && y < kb && y + h > kt

    /**
     * @param fx,fy,fw,fh 按下时卡片在页上的样子（可见大小）
     * @param contentH 按下时内容全部露出要多高（含内边距）：高度是「上限」，可见高 = min(内容高, 上限)
     * @param card 按下时存着的卡片（null = 还没摆过）
     * @param tx,ty 手指位移
     * @param pinClearance 图钉禁区半边长：移动时压进禁区就整块挪开；改大小时拖的那条边停在禁区边上
     */
    fun card(
        zone: Zone, fx: Float, fy: Float, fw: Float, fh: Float, contentH: Float, card: NoteCard?,
        tx: Float, ty: Float, unit: Float, pinX: Float, pinY: Float,
        minW: Float, minH: Float, pageW: Float, pageH: Float, pinClearance: Float = 0f,
    ): NoteCard {
        val u = max(unit, 0.0001f)
        val fr = fx + fw
        val fb = fy + fh
        var x = fx
        var y = fy
        var w = fw
        var visible = fh
        var cap: Float? = null   // 这次拖出来的高度上限（只拖了横向时为 null，保留原值）

        when (zone.h) {
            1 -> w = clamp(fw + tx, minW, pageW - fx)
            -1 -> { w = clamp(fw - tx, minW, fr); x = fr - w }
        }
        when (zone.v) {
            1 -> { val c = clamp(fh + ty, minH, pageH - fy); cap = c; visible = min(contentH, c) }
            -1 -> {
                // 拖上边：下边不动。上限从「按下时的可见高」起算
                val c = clamp(fh - ty, minH, fb)
                cap = c
                visible = min(contentH, c)
                y = fb - visible
            }
        }
        if (zone.isMove) {
            x = clamp(fx + tx, 0f, pageW - w)
            y = clamp(fy + ty, 0f, pageH - visible)
        }

        if (pinClearance > 0f) {
            val kl = pinX - pinClearance
            val kt = pinY - pinClearance
            val kr = pinX + pinClearance
            val kb = pinY + pinClearance
            if (!zone.isMove) {
                // 改大小：拖的那条边碰到禁区就停在禁区边上（对边不动）。停不住（最小尺寸都放不下）交给下面整块挪开
                val rowOverlap = y < kb && y + visible > kt
                if (zone.h == 1 && rowOverlap && x < kl && x + w > kl && kl - x >= minW) {
                    w = kl - x
                } else if (zone.h == -1 && rowOverlap && fr > kr && x < kr && fr - kr >= minW) {
                    x = kr
                    w = fr - x
                }
                val colOverlap = x < kr && x + w > kl
                if (zone.v == 1 && colOverlap && y < kt && y + visible > kt && kt - y >= minH) {
                    cap = kt - y
                    visible = min(contentH, kt - y)
                } else if (zone.v == -1 && colOverlap && fb > kb && y < kb && fb - kb >= minH) {
                    cap = fb - kb
                    visible = min(contentH, fb - kb)
                    y = fb - visible
                }
            }
            // 压住禁区就整块挪开：试图钉右 / 左 / 下 / 上四个贴边位置（各自钳进页内），取离原位最近且不压住的
            if (overlaps(x, y, w, visible, kl, kt, kr, kb)) {
                fun cx(v: Float) = min(max(v, 0f), max(0f, pageW - w))
                fun cy(v: Float) = min(max(v, 0f), max(0f, pageH - visible))
                val cands = listOf(cx(kr) to cy(y), cx(kl - w) to cy(y), cx(x) to cy(kb), cx(x) to cy(kt - visible))
                cands.filter { !overlaps(it.first, it.second, w, visible, kl, kt, kr, kb) }
                    .minByOrNull { abs(it.first - x) + abs(it.second - y) }
                    ?.let { x = it.first; y = it.second }
            }
        }
        return NoteCard(
            dx = (x - pinX) / u, dy = (y - pinY) / u,
            w = if (zone.h != 0) w / u else card?.w,
            h = cap?.let { it / u } ?: card?.h,
        )
    }
}
