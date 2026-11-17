package com.xvan.unireader.shared.anim

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 分步演示的布局：状态 + 画布宽 → 一帧里要画的物件（框、字、线、叉）。逐条照抄 Mac `Sources/Anim/AnimLayout.swift` +
 * `AnimLayout+Structures.swift`。坐标原点左上、y 向下，单位 dp（Mac 是点）；文字大小经 [AnimMeasure] 量（两端字体不同，
 * 所以坐标不进跨端向量，规则照抄、观感由用户在真机上看）。物件 id 稳定：两步之间按 id 配对插值（[AnimPainter]）。
 */

data class APoint(val x: Float, val y: Float)

data class ARect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + w
    val maxY get() = y + h
    val midX get() = x + w / 2
    val midY get() = y + h / 2
    fun inset(dx: Float, dy: Float) = ARect(x + dx, y + dy, w - 2 * dx, h - 2 * dy)
}

data class ASize(val w: Float, val h: Float)

/** 量一段文字在 [maxWidth] 内折行后的大小（同 Mac `AnimMetrics.measure`：宽取整后 +1，高取整）。 */
fun interface AnimMeasure {
    fun measure(text: String, size: Float, bold: Boolean, maxWidth: Float): ASize
}

/** 颜色角色：画的时候按深浅色映射到语义色。 */
sealed class AnimRole {
    data object Ink : AnimRole()
    data object Secondary : AnimRole()
    data object Grid : AnimRole()
    data object Background : AnimRole()
    data class Fill(val tone: AnimTone) : AnimRole()
    data class Stroke(val tone: AnimTone) : AnimRole()
    data class TextC(val tone: AnimTone) : AnimRole()
}

enum class AnimAlign { LEFT, CENTER, RIGHT }

sealed class AnimShape {
    data class Box(val rect: ARect, val radius: Float, val fill: AnimRole?, val stroke: AnimRole?, val lineWidth: Float, val dashed: Boolean) : AnimShape()
    data class Text(val text: String, val frame: ARect, val size: Float, val bold: Boolean, val role: AnimRole, val align: AnimAlign, val backdrop: Boolean) : AnimShape()
    /** 折线；[progress] 0…1 = 画到全长的几成；[arrow] 在画到的末端加箭头。 */
    data class Line(val points: List<APoint>, val role: AnimRole, val width: Float, val dashed: Boolean, val arrow: Boolean, val progress: Float) : AnimShape()
    data class Cross(val center: APoint, val size: Float, val role: AnimRole) : AnimShape()
}

/** 连着两个节点的边（树）：补间时端点按两个节点此刻的位置现算。 */
data class AnimLink(val from: String, val to: String)

data class AnimItem(val id: String, val shape: AnimShape, val alpha: Float = 1f, val link: AnimLink? = null)

class AnimFrame(val size: ASize, val items: List<AnimItem>)

object AnimMetrics {
    const val PAD = 12f
    const val BODY = 12f
    const val SMALL = 10f
    const val TITLE = 13f
    const val UNIT = 10f
    const val SCENE_GAP = 16f
    const val ROW_GAP = 12f
    const val MIN_SCENE_WIDTH = 300f
}

/** Swift `.rounded()`：四舍五入、0.5 远离 0。 */
internal fun rnd(x: Float): Float = if (x >= 0) floor(x + 0.5f) else -floor(-x + 0.5f)

/** 指针要指的位置：箭头尖 [point]，标签中心 [label]；同一处几个指针沿 [horizontal] 方向错开。 */
class AnimAnchor(val point: APoint, val label: APoint, val horizontal: Boolean, val cell: ARect? = null)

class AnimLayout(val run: AnimRun, val width: Float, private val m: AnimMeasure) {
    var height = 0f
        private set

    private class SceneBox(val origin: APoint, val width: Float) {
        var titleHeight = 0f
        val elements = HashMap<String, ARect>()
        var saysTop = 0f
        var saysHeight = 0f
        var captionTop = 0f
        var captionHeight = 0f
        var height = 0f
        val laneX = HashMap<String, Float>()
        var timelineTop = 0f
        val attachedSeqs = HashMap<String, ARect>()
    }

    private val scenes = ArrayList<SceneBox>()
    private var titleHeight = 0f
    private var seqRowsAcrossScenes = 0

    init { compute() }

    private fun measure(text: String, size: Float, bold: Boolean = false, maxWidth: Float = Float.MAX_VALUE): ASize =
        if (text.isEmpty()) ASize(0f, 0f) else m.measure(text, size, bold, maxWidth)

    // —— 几何（与步无关）——

    private fun compute() {
        val pad = AnimMetrics.PAD
        val count = run.script.scenes.size
        val inner = width - 2 * pad
        val sideBySide = count >= 2 && (inner - (count - 1) * AnimMetrics.SCENE_GAP) / count >= AnimMetrics.MIN_SCENE_WIDTH
        if (sideBySide) {
            for (scene in run.script.scenes) {
                val perLane = HashMap<String, Int>()
                for (el in scene.elements) {
                    val k = el.kind
                    if (k is AnimElementKind.Seq && k.lane != null) perLane[k.lane] = (perLane[k.lane] ?: 0) + 1
                }
                seqRowsAcrossScenes = maxOf(seqRowsAcrossScenes, perLane.values.maxOrNull() ?: 0)
            }
        }
        var y = pad
        val title = run.script.title
        if (!title.isNullOrEmpty()) {
            titleHeight = measure(title, AnimMetrics.TITLE, true, width - 2 * pad).h
            y += titleHeight + 8
        }
        val sceneWidth = if (sideBySide) floor((inner - (count - 1) * AnimMetrics.SCENE_GAP) / count) else inner
        for (i in 0 until count) {
            val x = if (sideBySide) pad + i * (sceneWidth + AnimMetrics.SCENE_GAP) else pad
            val box = measureScene(i, APoint(x, y), sceneWidth)
            if (!sideBySide) y += box.height + AnimMetrics.SCENE_GAP
            scenes.add(box)
        }
        if (sideBySide) {
            val saysTop = scenes.maxOf { it.saysTop }
            val captionTop = scenes.maxOf { saysTop + if (it.saysHeight > 0) it.saysHeight + 4 else 0f }
            for (s in scenes) { s.saysTop = saysTop; s.captionTop = captionTop }
            val rowHeight = scenes.maxOf { it.captionTop + it.captionHeight - it.origin.y }
            y += rowHeight + AnimMetrics.SCENE_GAP
        }
        height = ceil(y - AnimMetrics.SCENE_GAP + pad)
    }

    private fun measureScene(index: Int, origin: APoint, w: Float): SceneBox {
        val decl = run.script.scenes[index]
        val states = run.states[index]
        val box = SceneBox(origin, w)
        var y = origin.y
        val title = decl.title
        if (!title.isNullOrEmpty() && (run.script.scenes.size > 1 || run.script.title == null)) {
            box.titleHeight = measure(title, AnimMetrics.BODY, true, w).h
            y += box.titleHeight + 6
        }
        val elements = HashMap<String, AnimElement>()
        for (e in decl.elements) if (e.name !in elements) elements[e.name] = e
        for (row in decl.rows) {
            val n = row.size
            val ew = floor((w - (n - 1) * AnimMetrics.ROW_GAP) / n)
            var rowH = 0f
            for ((j, name) in row.withIndex()) {
                val element = elements[name] ?: continue
                val x = origin.x + j * (ew + AnimMetrics.ROW_GAP)
                val h = elementHeight(element, ew, states, index, decl, box, x, y)
                box.elements[name] = ARect(x, y, ew, h)
                rowH = maxOf(rowH, h)
            }
            y += rowH + AnimMetrics.ROW_GAP
        }
        val saysLines = states.maxOf { it.says.size }
        box.saysTop = y
        if (saysLines > 0) {
            val lineH = states.flatMap { it.says }.maxOfOrNull { measure(it, AnimMetrics.BODY, false, w - 16).h } ?: 16f
            box.saysHeight = saysLines * (lineH + 8)
            y += box.saysHeight + 4
        }
        box.captionTop = y
        val captionH = states.maxOf { measure(it.caption, AnimMetrics.BODY, false, w).h }
        box.captionHeight = maxOf(captionH, if (run.stepCount > 1) 16f else 0f)
        y += box.captionHeight
        box.height = y - origin.y
        return box
    }

    private fun elementHeight(e: AnimElement, w: Float, states: List<AnimSceneState>, sceneIndex: Int, decl: AnimSceneDecl,
                              box: SceneBox, x: Float, y: Float): Float {
        val nameH = if (e.name.startsWith("#")) 0f else NAME_HEIGHT
        val pointed = pointedElements(states).contains(e.name)
        val band = if (pointed) POINTER_BAND else 0f
        return when (val k = e.kind) {
            is AnimElementKind.Arr -> {
                val count = states.maxOf { it.cells[e.name]?.size ?: 0 }
                nameH + cellSize(w, count, 36f) + 14 + band
            }
            is AnimElementKind.Queue -> {
                val count = maxOf(states.maxOf { it.cells[e.name]?.size ?: 0 }, 1)
                nameH + cellSize(w, count, 36f) + 4 + band
            }
            is AnimElementKind.LinkedList, is AnimElementKind.Tree, is AnimElementKind.Graph, is AnimElementKind.Grid, is AnimElementKind.Sheet,
            is AnimElementKind.Bits, is AnimElementKind.Packet ->
                structureHeight(e, w, states, nameH, pointed)
            is AnimElementKind.Pointer -> 0f
            is AnimElementKind.Stack -> {
                val count = maxOf(states.maxOf { it.cells[e.name]?.size ?: 0 }, 1)
                nameH + count * 24 + 6
            }
            is AnimElementKind.Variable -> 26f
            is AnimElementKind.Text -> measure(k.text, AnimMetrics.BODY, false, w).h
            is AnimElementKind.Seq -> nameH + cellSize(w, k.upper - k.lower + 1, 30f) + 18
            is AnimElementKind.Lanes -> {
                val names = k.names
                val left = x
                val right = x + w
                val side = minOf(90f, w * 0.22f)
                for ((i, lane) in names.withIndex()) {
                    val t = if (names.size == 1) 0.5f else i.toFloat() / (names.size - 1)
                    box.laneX[lane] = rnd(left + side + t * (right - left - 2 * side))
                }
                var top = y + 18
                val rows = HashMap<String, Int>()
                var maxRows = 0
                val spacing = if (names.size > 1) (right - left - 2 * side) / (names.size - 1) else w
                class Placed(val name: String, val lane: String, val r: Int, val count: Int)
                val placed = ArrayList<Placed>()
                for (el in decl.elements) {
                    val sk = el.kind
                    if (sk !is AnimElementKind.Seq || sk.lane == null) continue
                    val r = rows[sk.lane] ?: 0
                    rows[sk.lane] = r + 1
                    maxRows = maxOf(maxRows, r + 1)
                    placed.add(Placed(el.name, sk.lane, r, sk.upper - sk.lower + 1))
                }
                val seqRow = 12f + 22f + 10f
                for (p in placed) {
                    val cell = cellSize(minOf(spacing + side, w) - 8, p.count, 22f)
                    val sw = cell * p.count
                    val cx = box.laneX[p.lane] ?: x
                    val sx = minOf(maxOf(cx - sw / 2, left), right - sw)
                    box.attachedSeqs[p.name] = ARect(sx, top + p.r * seqRow + 12, sw, cell)
                }
                top += maxOf(maxRows, seqRowsAcrossScenes) * seqRow + 4
                box.timelineTop = top
                val length = run.timelineLength.getOrElse(sceneIndex) { 0.0 }
                top - y + length.toFloat() * AnimMetrics.UNIT + 8
            }
        }
    }

    // —— 某一步的画面 ——

    fun frame(step: Int): AnimFrame {
        val items = ArrayList<AnimItem>()
        val pad = AnimMetrics.PAD
        val title = run.script.title
        if (!title.isNullOrEmpty()) {
            items.add(AnimItem("title", AnimShape.Text(title, ARect(pad, pad, width - 2 * pad, titleHeight), AnimMetrics.TITLE, true, AnimRole.Ink, AnimAlign.LEFT, false)))
        }
        for ((i, box) in scenes.withIndex()) items += sceneItems(i, box, run.state(i, step))
        return AnimFrame(ASize(width, height), items)
    }

    private fun sceneItems(index: Int, box: SceneBox, state: AnimSceneState): List<AnimItem> {
        val decl = run.script.scenes[index]
        val p = "s$index."
        val items = ArrayList<AnimItem>()
        val anchors = HashMap<AnimPointerTarget, AnimAnchor>()
        val sceneTitle = decl.title
        if (box.titleHeight > 0 && sceneTitle != null) {
            items.add(AnimItem(p + "title", AnimShape.Text(sceneTitle, ARect(box.origin.x, box.origin.y, box.width, box.titleHeight), AnimMetrics.BODY, true, AnimRole.Ink, AnimAlign.LEFT, false)))
        }
        for (element in decl.elements) {
            val kind = element.kind
            if (kind is AnimElementKind.Seq && kind.lane != null) {
                box.attachedSeqs[element.name]?.let { if (kind.lane.isNotEmpty()) items += seqItems(p + element.name, element.name, it, state, false) }
                continue
            }
            val rect = box.elements[element.name] ?: continue
            val id = p + element.name
            val nameH = if (element.name.startsWith("#")) 0f else NAME_HEIGHT
            val elementTone = state.elementTones[element.name] ?: AnimTone.NORMAL
            if (nameH > 0 && kind !is AnimElementKind.Lanes && kind !is AnimElementKind.Variable) {
                items.add(AnimItem("$id.name", AnimShape.Text(element.name, ARect(rect.minX, rect.minY, rect.w, nameH), AnimMetrics.SMALL, true,
                    if (elementTone == AnimTone.NORMAL) AnimRole.Secondary else AnimRole.TextC(elementTone), AnimAlign.LEFT, false)))
            }
            val body = ARect(rect.minX, rect.minY + nameH, rect.w, rect.h - nameH)
            when (kind) {
                is AnimElementKind.Arr -> items += cellRow(id, element.name, state.cells[element.name] ?: emptyList(), body, 36f, true, elementTone, anchors)
                is AnimElementKind.Queue -> items += cellRow(id, element.name, state.cells[element.name] ?: emptyList(), body, 36f, false, elementTone, anchors)
                is AnimElementKind.Stack -> items += stackItems(id, element.name, state.cells[element.name] ?: emptyList(), body, elementTone, anchors)
                is AnimElementKind.LinkedList -> items += listItems(id, element.name, body, state, elementTone, anchors)
                is AnimElementKind.Tree -> items += treeItems(id, element, body, state, elementTone, anchors)
                is AnimElementKind.Graph -> items += graphItems(id, element, body, state, elementTone, anchors)
                is AnimElementKind.Grid -> items += gridItems(id, element.name, body, state, elementTone, anchors)
                is AnimElementKind.Sheet -> items += sheetItems(id, element.name, body, state, elementTone)
                is AnimElementKind.Bits -> items += bitsItems(id, element, body, state, elementTone)
                is AnimElementKind.Packet -> items += packetItems(id, element, body, state, elementTone)
                is AnimElementKind.Pointer -> {}
                is AnimElementKind.Variable -> {
                    val v = state.vars[element.name] ?: AnimVar("", AnimTone.NORMAL)
                    val text = "${element.name} = ${v.value}"
                    val size = measure(text, AnimMetrics.BODY)
                    val wBox = minOf(rect.w, size.w + 16)
                    val r = ARect(rect.minX, rect.minY + 2, wBox, 22f)
                    items.add(AnimItem("$id.box", AnimShape.Box(r, 5f, AnimRole.Fill(v.tone), AnimRole.Stroke(v.tone), 1f, false)))
                    items.add(AnimItem("$id.v", AnimShape.Text(text, r.inset(6f, 0f), AnimMetrics.BODY, false, AnimRole.TextC(v.tone), AnimAlign.CENTER, false)))
                }
                is AnimElementKind.Text -> items.add(AnimItem(id, AnimShape.Text(kind.text, rect, AnimMetrics.BODY, false, AnimRole.Ink, AnimAlign.LEFT, false)))
                is AnimElementKind.Seq -> items += seqItems(id, element.name, ARect(body.minX, body.minY + 3, body.w, body.h - 18), state, true)
                is AnimElementKind.Lanes -> items += laneItems(p, kind.names, rect, box, state, index)
            }
        }
        items += pointerItems(p, state, anchors)
        for ((i, say) in state.says.withIndex()) {
            val h = box.saysHeight / maxOf(state.says.size, 1)
            val r = ARect(box.origin.x, box.saysTop + i * h, box.width, h - 6)
            items.add(AnimItem(p + "say${state.step}.$i", AnimShape.Box(r, 6f, AnimRole.Fill(AnimTone.ACTIVE), null, 0f, false)))
            items.add(AnimItem(p + "sayt${state.step}.$i", AnimShape.Text(say, r.inset(8f, 3f), AnimMetrics.BODY, false, AnimRole.Ink, AnimAlign.LEFT, false)))
        }
        if (state.caption.isNotEmpty()) {
            items.add(AnimItem(p + "caption", AnimShape.Text(state.caption, ARect(box.origin.x, box.captionTop, box.width, box.captionHeight), AnimMetrics.BODY, false, AnimRole.Ink, AnimAlign.LEFT, false)))
        }
        return items
    }

    private fun cellRow(id: String, name: String, cells: List<AnimCell>, rect: ARect, maxCell: Float, indexes: Boolean, tone: AnimTone,
                        anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val items = ArrayList<AnimItem>()
        val capacity = maxOf((rect.w / 14).toInt(), 1)
        val size = cellSize(rect.w, minOf(maxOf(cells.size, 1), capacity), maxCell)
        val below = rect.minY + size + if (indexes) 13 else 1
        for (i in 0..cells.size) {
            val x = rect.minX + i * size + size / 2
            anchors[AnimPointerTarget.Index(name, i)] = AnimAnchor(APoint(x, below), APoint(x, below + POINTER_BAND / 2 + 2), true)
        }
        for ((i, cell) in cells.withIndex()) {
            val r = ARect(rect.minX + i * size, rect.minY, size, size)
            val t = if (cell.tone == AnimTone.NORMAL) tone else cell.tone
            items.add(AnimItem("$id.c${cell.id}", AnimShape.Box(r, 3f, AnimRole.Fill(t), AnimRole.Stroke(t), 1f, false)))
            items.add(AnimItem("$id.v${cell.id}", AnimShape.Text(cell.value, r.inset(1f, 0f), if (size < 22) AnimMetrics.SMALL else AnimMetrics.BODY, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            if (indexes) {
                items.add(AnimItem("$id.i$i", AnimShape.Text("$i", ARect(r.minX, r.maxY + 1, size, 12f), AnimMetrics.SMALL - 1, false, AnimRole.Secondary, AnimAlign.CENTER, false)))
            }
        }
        if (cells.isEmpty()) {
            items.add(AnimItem("$id.empty", AnimShape.Box(ARect(rect.minX, rect.minY, size, size), 3f, null, AnimRole.Grid, 1f, true)))
        }
        return items
    }

    private fun stackItems(id: String, name: String, cells: List<AnimCell>, rect: ARect, tone: AnimTone,
                           anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val items = ArrayList<AnimItem>()
        val w = minOf(rect.w, 90f)
        val h = 24f
        val bottom = rect.maxY - 3
        val x0 = rect.minX
        val x1 = rect.minX + w + 6
        items.add(AnimItem("$id.frame", AnimShape.Line(listOf(APoint(x0, rect.minY), APoint(x0, bottom + 3), APoint(x1, bottom + 3), APoint(x1, rect.minY)),
            AnimRole.Grid, 1.5f, false, false, 1f)))
        for ((i, cell) in cells.withIndex()) {
            val r = ARect(x0 + 3, bottom - (i + 1) * h, w, h - 2)
            anchors[AnimPointerTarget.Index(name, i)] = AnimAnchor(APoint(x1 + 2, r.midY), APoint(x1 + 26, r.midY), false)
            val t = if (cell.tone == AnimTone.NORMAL) tone else cell.tone
            items.add(AnimItem("$id.c${cell.id}", AnimShape.Box(r, 3f, AnimRole.Fill(t), AnimRole.Stroke(t), 1f, false)))
            items.add(AnimItem("$id.v${cell.id}", AnimShape.Text(cell.value, r, AnimMetrics.BODY, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
        }
        return items
    }

    /** 序号条：一排号 + 窗口框。 */
    private fun seqItems(id: String, name: String, rect: ARect, state: AnimSceneState, cellsOnly: Boolean): List<AnimItem> {
        val seq = state.seqs[name] ?: return emptyList()
        val items = ArrayList<AnimItem>()
        val count = seq.upper - seq.lower + 1
        val size = if (cellsOnly) cellSize(rect.w, count, 30f) else rect.h
        val top = rect.minY
        val elementTone = state.elementTones[name] ?: AnimTone.NORMAL
        if (!cellsOnly) {
            items.add(AnimItem("$id.name", AnimShape.Text(name, ARect(rect.minX, rect.minY - 13, maxOf(rect.w, 80f), 12f), AnimMetrics.SMALL - 1, true,
                if (elementTone == AnimTone.NORMAL) AnimRole.Secondary else AnimRole.TextC(elementTone), AnimAlign.LEFT, false)))
        }
        for (n in seq.lower..seq.upper) {
            val i = n - seq.lower
            val r = ARect(rect.minX + i * size, top, size, size)
            val inWindow = n >= seq.start && n < seq.start + seq.window
            val t = seq.tones[n] ?: if (inWindow) AnimTone.NORMAL else AnimTone.MUTED
            items.add(AnimItem("$id.c$n", AnimShape.Box(r, 2f, AnimRole.Fill(t), AnimRole.Grid, 0.75f, false)))
            items.add(AnimItem("$id.v$n", AnimShape.Text(seq.label(n), r, if (size < 20) AnimMetrics.SMALL - 1 else AnimMetrics.SMALL, inWindow, AnimRole.TextC(t), AnimAlign.CENTER, false)))
        }
        val first = maxOf(seq.start, seq.lower)
        val last = minOf(seq.start + seq.window - 1, seq.upper)
        if (first <= last) {
            val wr = ARect(rect.minX + (first - seq.lower) * size - 2, top - 2, (last - first + 1) * size + 4, size + 4)
            items.add(AnimItem("$id.window", AnimShape.Box(wr, 4f, null, AnimRole.Stroke(AnimTone.ACTIVE), 2f, false)))
        }
        return items
    }

    // —— 时序图 ——

    private fun laneItems(p: String, names: List<String>, rect: ARect, box: SceneBox, state: AnimSceneState, sceneIndex: Int): List<AnimItem> {
        val items = ArrayList<AnimItem>()
        val top = box.timelineTop
        val bottom = rect.maxY - 4
        fun y(t: Double) = top + t.toFloat() * AnimMetrics.UNIT
        val leftmost = names.firstOrNull()
        val rightmost = names.lastOrNull()
        for (lane in names) {
            val x = box.laneX[lane] ?: continue
            val label = measure(lane, AnimMetrics.BODY, true)
            items.add(AnimItem(p + "lane.$lane.name", AnimShape.Text(lane, ARect(x - label.w / 2 - 4, rect.minY, label.w + 8, 16f), AnimMetrics.BODY, true, AnimRole.Ink, AnimAlign.CENTER, false)))
            items.add(AnimItem(p + "lane.$lane.line", AnimShape.Line(listOf(APoint(x, top - 4), APoint(x, bottom)), AnimRole.Grid, 1.5f, false, false, 1f)))
        }
        if (state.step > 0) {
            val states = run.states[sceneIndex]
            for (k in 1..minOf(state.step, states.size - 1)) {
                val yy = y(states[k - 1].cursor)
                items.add(AnimItem(p + "slot$k", AnimShape.Text("$k", ARect(rect.minX, yy - 1, 16f, 12f), AnimMetrics.SMALL - 1, true, AnimRole.Secondary, AnimAlign.LEFT, false)))
            }
        }
        val cursor = state.cursor
        for (timer in state.timers) {
            val x = box.laneX[timer.lane] ?: continue
            val outward = if (timer.lane == rightmost && timer.lane != leftmost) 1f else -1f
            val tx = x + outward * 6
            val end = minOf(timer.end ?: cursor, cursor)
            if (end < timer.start) continue
            val span = maxOf(end - timer.start, 0.01)
            items.add(AnimItem(p + "timer${timer.id}", AnimShape.Line(listOf(APoint(tx, y(timer.start)), APoint(tx, y(timer.start + span))),
                AnimRole.Stroke(if (timer.expired) AnimTone.WRONG else AnimTone.MUTED), 2f, timer.end == null, false, 1f)))
        }
        for (msg in state.messages) {
            val x0 = box.laneX[msg.from] ?: continue
            val x1 = box.laneX[msg.to] ?: continue
            val a = APoint(x0, y(msg.start))
            val full = APoint(x1, y(msg.start + (msg.end - msg.start) / if (msg.lost) 0.55 else 1.0))
            val b = if (msg.lost) APoint(a.x + (full.x - a.x) * 0.55f, a.y + (full.y - a.y) * 0.55f) else full
            val progress = ((cursor - msg.start) / maxOf(msg.end - msg.start, 0.01)).coerceIn(0.0, 1.0).toFloat()
            val old = msg.step < state.step
            val role = if (msg.tone == AnimTone.NORMAL) AnimRole.Ink else AnimRole.Stroke(msg.tone)
            items.add(AnimItem(p + "msg${msg.id}", AnimShape.Line(listOf(a, b), role, 1.5f, msg.lost, !msg.lost, progress), if (old) 0.45f else 1f))
            if (progress > 0) {
                val size = measure(msg.label, AnimMetrics.SMALL)
                val c = APoint(a.x + (b.x - a.x) * 0.3f, a.y + (b.y - a.y) * 0.3f)
                val r = ARect(c.x - size.w / 2 - 2, c.y - size.h / 2 - 1, size.w + 4, size.h + 2)
                items.add(AnimItem(p + "msgl${msg.id}", AnimShape.Text(msg.label, r, AnimMetrics.SMALL, false,
                    if (msg.tone == AnimTone.NORMAL) AnimRole.Ink else AnimRole.TextC(msg.tone), AnimAlign.CENTER, true), if (old) 0.55f else 1f))
            }
            if (msg.lost && progress >= 1) {
                items.add(AnimItem(p + "msgx${msg.id}", AnimShape.Cross(b, 5f, AnimRole.Stroke(AnimTone.WRONG)), if (old) 0.55f else 1f))
            }
        }
        for (mark in state.marks) {
            if (mark.time > cursor + 0.001) continue
            val x = box.laneX[mark.lane] ?: continue
            val leftSide = mark.lane == leftmost && names.size > 1
            val glyph = when (mark.kind) {
                AnimMarkKind.DELIVER -> "✓ "
                AnimMarkKind.DISCARD -> "✕ "
                AnimMarkKind.BUFFER -> "▣ "
                AnimMarkKind.NOTE -> ""
                AnimMarkKind.TIMER_START -> "⏱ "
                AnimMarkKind.TIMER_STOP -> "■ "
                AnimMarkKind.TIMER_EXPIRE -> "⏰ "
            }
            val text = glyph + mark.label
            val size = measure(text, AnimMetrics.SMALL)
            val yy = y(mark.time)
            val gapX = 12f
            val r = if (leftSide) ARect(x - gapX - size.w, yy - size.h / 2, size.w, size.h) else ARect(x + gapX, yy - size.h / 2, size.w, size.h)
            val old = mark.step < state.step
            items.add(AnimItem(p + "mark${mark.id}", AnimShape.Text(text, r, AnimMetrics.SMALL, mark.kind == AnimMarkKind.TIMER_EXPIRE,
                if (mark.tone == AnimTone.NORMAL) AnimRole.Ink else AnimRole.TextC(mark.tone), if (leftSide) AnimAlign.RIGHT else AnimAlign.LEFT, true), if (old) 0.55f else 1f))
            if (mark.kind != AnimMarkKind.TIMER_START && mark.kind != AnimMarkKind.TIMER_STOP) {
                items.add(AnimItem(p + "markd${mark.id}", AnimShape.Box(ARect(x - 2.5f, yy - 2.5f, 5f, 5f), 2.5f, AnimRole.Stroke(mark.tone), null, 0f, false), if (old) 0.55f else 1f))
            }
        }
        return items
    }

    // —— 结构：链表 / 树 / 图 / 二维表 / 竖式 / 指针 ——

    private fun structureHeight(e: AnimElement, w: Float, states: List<AnimSceneState>, nameH: Float, pointed: Boolean): Float {
        val band = if (pointed) POINTER_BAND else 0f
        return when (val k = e.kind) {
            is AnimElementKind.LinkedList -> nameH + LIST_ARC + LIST_NODE_HEIGHT + LIST_ARC + band
            is AnimElementKind.Tree -> {
                if (k.dir.horizontal) {
                    val slots = maxOf(states.mapNotNull { it.trees[e.name] }.maxOfOrNull { treeSlots(it).count } ?: 1f, 1f)
                    nameH + (if (pointed) 14f else 0f) + slots * treeSlotH(pointed) + 4
                } else {
                    val depth = maxOf(states.maxOf { it.trees[e.name]?.depth ?: 0 }, 1)
                    nameH + (depth - 1) * TREE_LEVEL + GRAPH_NODE + 10
                }
            }
            is AnimElementKind.Bits -> {
                val g = bitsGeometry(e, w)
                nameH + BITS_RULER + g.rows * BITS_ROW + 4
            }
            is AnimElementKind.Packet -> {
                val levels = states.mapNotNull { it.packets[e.name] }.maxOfOrNull { packetGroupLevels(it).values.maxOrNull() ?: 0 } ?: 0
                nameH + PACKET_BAR + PACKET_LEN + levels * PACKET_GROUP + 4
            }
            is AnimElementKind.Graph -> nameH + graphCanvasHeight(e, w)
            is AnimElementKind.Sheet -> {
                val rows = maxOf(states.maxOf { it.sheets[e.name]?.rowCount ?: 0 }, 1)
                nameH + rows * SHEET_ROW + 4
            }
            is AnimElementKind.Grid -> {
                val g = gridGeometry(w, k.values.firstOrNull()?.size ?: 0, k.rowLabels, k.colLabels.isNotEmpty())
                nameH + g.colLabelH + k.values.size * g.cell + 4 + band
            }
            else -> 0f
        }
    }

    private fun listItems(id: String, name: String, rect: ARect, state: AnimSceneState, tone: AnimTone,
                          anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val list = state.lists[name] ?: return emptyList()
        val items = ArrayList<AnimItem>()
        val capacity = maxOf(run.states.flatten().maxOf { it.lists[name]?.nodes?.size ?: 0 }, 1)
        var nodeW = 44f
        var gap = 30f
        val need = capacity * nodeW + capacity * gap
        if (need > rect.w) {
            val k = rect.w / need
            nodeW = maxOf(24f, floor(nodeW * k)); gap = maxOf(14f, floor(gap * k))
        }
        val top = rect.minY + LIST_ARC
        val h = LIST_NODE_HEIGHT
        val frames = HashMap<Int, ARect>()
        for ((i, node) in list.nodes.withIndex()) frames[node.id] = ARect(rect.minX + i * (nodeW + gap), top, nodeW, h)
        for ((i, node) in list.nodes.withIndex()) {
            val r = frames[node.id]!!
            val t = if (node.tone == AnimTone.NORMAL) tone else node.tone
            items.add(AnimItem("$id.n${node.id}", AnimShape.Box(r, 5f, AnimRole.Fill(t), AnimRole.Stroke(t), 1f, false)))
            items.add(AnimItem("$id.v${node.id}", AnimShape.Text(node.value, r.inset(2f, 0f), if (nodeW < 32) AnimMetrics.SMALL else AnimMetrics.BODY, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            anchors[AnimPointerTarget.Node(name, node.id)] = AnimAnchor(APoint(r.midX, r.maxY + LIST_ARC - 2), APoint(r.midX, r.maxY + LIST_ARC + POINTER_BAND / 2 + 2), true)
            val next = node.next
            val to = next?.let { frames[it] }
            val j = next?.let { list.index(it) }
            if (next != null && to != null && j != null) {
                items.add(AnimItem("$id.a${node.id}.$next", AnimShape.Line(listArrow(r, to, j == i + 1), AnimRole.Stroke(AnimTone.NORMAL), 1.5f, false, true, 1f)))
            } else {
                items.add(AnimItem("$id.null${node.id}", AnimShape.Text("∅", ARect(r.maxX + 4, r.minY, minOf(gap - 6, 18f), h), AnimMetrics.BODY, false, AnimRole.Secondary, AnimAlign.LEFT, false)))
            }
        }
        if (list.nodes.isEmpty()) {
            items.add(AnimItem("$id.emptynull", AnimShape.Text("∅", ARect(rect.minX, top, 20f, h), AnimMetrics.BODY, false, AnimRole.Secondary, AnimAlign.LEFT, false)))
        }
        return items
    }

    private fun treeItems(id: String, e: AnimElement, rect: ARect, state: AnimSceneState, tone: AnimTone,
                          anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val name = e.name
        val tree = state.trees[name] ?: return emptyList()
        val dir = (e.kind as? AnimElementKind.Tree)?.dir ?: AnimDirection.DOWN
        val items = ArrayList<AnimItem>()
        val ts = treeSlots(tree)
        val slots = ts.count
        val all = run.states.flatten().mapNotNull { it.trees[name] }
        val depthMax = maxOf(all.maxOfOrNull { it.depth } ?: 1, 1)
        val pointed = pointedElements(run.states.flatten()).contains(name)
        // 左右各留 26 点：指针标签画在节点左边，最左的节点也放得下
        val area = rect.inset(26f, 0f)
        val d: Float
        val center: (Int) -> APoint
        if (dir.horizontal) {
            val slotH = treeSlotH(pointed)
            val slotsMax = maxOf(all.maxOfOrNull { treeSlots(it).count } ?: 1f, 1f)
            d = minOf(GRAPH_NODE, slotH - 6)
            val levelW = if (depthMax > 1) minOf(110f, (area.w - d) / (depthMax - 1)) else 0f
            val top = rect.minY + (if (pointed) 14f else 0f) + (slotsMax - slots) * slotH / 2
            center = { nid ->
                val l = ts.level[nid]!!
                val x = if (dir == AnimDirection.RIGHT) area.minX + d / 2 + l * levelW else area.maxX - d / 2 - l * levelW
                APoint(x, top + (ts.slot[nid]!! + 0.5f) * slotH)
            }
        } else {
            val slotW = minOf(52f, area.w / slots)
            d = minOf(GRAPH_NODE, slotW - 4)
            val left = area.minX + (area.w - slots * slotW) / 2
            center = { nid ->
                val l = if (dir == AnimDirection.DOWN) ts.level[nid]!! else depthMax - 1 - ts.level[nid]!!
                APoint(left + (ts.slot[nid]!! + 0.5f) * slotW, rect.minY + d / 2 + 2 + l * TREE_LEVEL)
            }
        }
        for ((nid, n) in tree.nodes) {
            if (ts.slot[nid] == null) continue
            for (c in n.children.filterNotNull()) {
                if (ts.slot[c] == null) continue
                val a = center(nid)
                val b = center(c)
                val len = maxOf(hypot(b.x - a.x, b.y - a.y), 1f)
                val ux = (b.x - a.x) / len
                val uy = (b.y - a.y) / len
                val r = d / 2
                items.add(AnimItem("$id.e$nid.$c", AnimShape.Line(listOf(APoint(a.x + ux * r, a.y + uy * r), APoint(b.x - ux * r, b.y - uy * r)), AnimRole.Grid, 1.5f, false, false, 1f),
                    link = AnimLink("$id.n$nid", "$id.n$c")))
            }
        }
        for (nid in tree.nodes.keys.sorted()) {
            if (ts.slot[nid] == null) continue
            val n = tree.nodes[nid]!!
            val c = center(nid)
            val r = ARect(c.x - d / 2, c.y - d / 2, d, d)
            val t = if (n.tone == AnimTone.NORMAL) tone else n.tone
            items.add(AnimItem("$id.n$nid", AnimShape.Box(r, d / 2, AnimRole.Fill(t), AnimRole.Stroke(t), 1.2f, false)))
            items.add(AnimItem("$id.v${n.valueID}", AnimShape.Text(n.value, r.inset(-6f, 0f), if (d < 26) AnimMetrics.SMALL else AnimMetrics.BODY, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            anchors[AnimPointerTarget.Node(name, nid)] = if (dir.horizontal) {
                // 横着长时左右两边都有边：指针标签放在节点上方
                AnimAnchor(APoint(c.x, r.minY - 1), APoint(c.x, r.minY - 14), true)
            } else {
                AnimAnchor(APoint(r.minX - 1, c.y), APoint(r.minX - 18, c.y), false)
            }
        }
        if (tree.root == null) {
            items.add(AnimItem("$id.empty", AnimShape.Box(ARect(rect.midX - d / 2, rect.minY + 2, d, d), d / 2, null, AnimRole.Grid, 1f, true)))
        }
        return items
    }

    private fun graphCanvasHeight(e: AnimElement, w: Float): Float {
        val k = e.kind as? AnimElementKind.Graph ?: return 0f
        return when (k.layout) {
            AnimGraphLayout.CIRCLE -> if (k.nodes.size <= 2) 90f else minOf(maxOf(w * 0.5f, 180f), 280f)
            AnimGraphLayout.LAYERS -> {
                val layers = graphLayers(e)
                if (k.dir.horizontal) (layers.maxOfOrNull { it.size } ?: 1) * 64f + 30
                else maxOf(layers.size - 1, 0) * GRAPH_LAYER_GAP + 70
            }
        }
    }

    /** 节点框的半宽：名字放得进圆就是圆，放不下就拉成两头圆的长条（同 Swift `graphHalfWidth`）。 */
    private fun graphHalfWidth(name: String): Float {
        val r = GRAPH_NODE / 2
        val w = measure(name, AnimMetrics.BODY, true).w
        return if (w <= GRAPH_NODE - 4) r else ceil(w / 2) + 7
    }

    private fun graphPositions(e: AnimElement, rect: ARect): Map<String, APoint> {
        val k = e.kind as? AnimElementKind.Graph ?: return emptyMap()
        val r = GRAPH_NODE / 2
        val out = HashMap<String, APoint>()
        when (k.layout) {
            AnimGraphLayout.CIRCLE -> {
                val nodes = circleOrder(k.nodes, k.edges)
                val cx = rect.midX
                val cy = rect.midY + 4
                if (nodes.size == 1) return mapOf(nodes[0] to APoint(cx, cy))
                if (nodes.size == 2) return mapOf(nodes[0] to APoint(cx - minOf(rect.w / 3, 120f), cy), nodes[1] to APoint(cx + minOf(rect.w / 3, 120f), cy))
                val rx = minOf(rect.w / 2 - r - 36, rect.h * 0.9f)
                val ry = rect.h / 2 - r - 18
                for ((i, n) in nodes.withIndex()) {
                    val a = -Math.PI / 2 + 2 * Math.PI * i / nodes.size
                    out[n] = APoint(rnd(cx + rx * cos(a).toFloat()), rnd(cy + ry * sin(a).toFloat()))
                }
            }
            AnimGraphLayout.LAYERS -> {
                val layers = graphLayers(e)
                val levels = layers.size
                if (k.dir.horizontal) {
                    // 每层一列：right = 第一层在最左
                    val side = (layers.flatten().maxOfOrNull { graphHalfWidth(it) } ?: r) + 30
                    val colW = (rect.w - 2 * side) / maxOf(levels - 1, 1)
                    for ((l, members) in layers.withIndex()) {
                        for ((i, n) in members.withIndex()) {
                            val lx = if (levels == 1) rect.midX else rect.minX + side + l * colW
                            val x = if (k.dir == AnimDirection.RIGHT) lx else rect.maxX - (lx - rect.minX)
                            val y = rect.minY + 24 + (rect.h - 30) * (i + 0.5f) / members.size
                            out[n] = APoint(rnd(x), rnd(y))
                        }
                    }
                } else {
                    // 每层一行：down = 第一层在最上
                    for ((l, members) in layers.withIndex()) {
                        val row = if (k.dir == AnimDirection.DOWN) l else levels - 1 - l
                        for ((i, n) in members.withIndex()) {
                            val x = rect.minX + rect.w * (i + 0.5f) / members.size
                            out[n] = APoint(rnd(x), rnd(rect.minY + 26 + row * GRAPH_LAYER_GAP))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun graphItems(id: String, e: AnimElement, rect: ARect, state: AnimSceneState, tone: AnimTone,
                           anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val g = state.graphs[e.name] ?: return emptyList()
        val pos = graphPositions(e, rect)
        val r = GRAPH_NODE / 2
        val half = HashMap<String, Float>()
        for (n in g.order) half[n] = graphHalfWidth(n)
        // 分层排法里同一层、中间隔着别的节点的边：往外弯
        val layerOf = HashMap<String, Pair<Int, Int>>()
        if ((e.kind as? AnimElementKind.Graph)?.layout == AnimGraphLayout.LAYERS) {
            for ((l, members) in graphLayers(e).withIndex()) for ((i, n) in members.withIndex()) layerOf[n] = l to i
        }
        val items = ArrayList<AnimItem>()
        for ((k, edge) in g.edges.withIndex()) {
            val a = pos[edge.a] ?: continue
            val b = pos[edge.b] ?: continue
            val paired = edge.directed && g.edges.any { it.directed && it.a == edge.b && it.b == edge.a }
            val len = maxOf(hypot(b.x - a.x, b.y - a.y), 1f)
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            val bend = if (paired) 10f else 0f
            val nx = -uy * bend
            val ny = ux * bend
            val ra = nodeExit(ux, uy, half[edge.a] ?: r, r)
            val rb = nodeExit(ux, uy, half[edge.b] ?: r, r)
            var p0 = APoint(a.x + ux * ra + nx * 0.5f, a.y + uy * ra + ny * 0.5f)
            var p1 = APoint(b.x - ux * (rb + 1) + nx * 0.5f, b.y - uy * (rb + 1) + ny * 0.5f)
            var mid = APoint((p0.x + p1.x) / 2 + nx, (p0.y + p1.y) / 2 + ny)
            var apex: APoint? = null
            val la = layerOf[edge.a]
            val lb = layerOf[edge.b]
            if (la != null && lb != null && la.first == lb.first && abs(la.second - lb.second) >= 2) {
                // 曲线顶点离连线 = 控制点偏移的一半：要越过中间节点（半径 + 留白）
                val lift = (if (la.second < lb.second) -1 else 1) * 2 * (r + 12 + 6 * (abs(la.second - lb.second) - 2))
                val c = APoint((a.x + b.x) / 2 - uy * lift, (a.y + b.y) / 2 + ux * lift)
                fun toward(from: APoint, hw: Float, extra: Float): APoint {
                    val l = maxOf(hypot(c.x - from.x, c.y - from.y), 1f)
                    val vx = (c.x - from.x) / l
                    val vy = (c.y - from.y) / l
                    val dd = nodeExit(vx, vy, hw, r) + extra
                    return APoint(from.x + vx * dd, from.y + vy * dd)
                }
                p0 = toward(a, half[edge.a] ?: r, 0f)
                p1 = toward(b, half[edge.b] ?: r, 1f)
                mid = c
                apex = APoint(0.25f * p0.x + 0.5f * c.x + 0.25f * p1.x, 0.25f * p0.y + 0.5f * c.y + 0.25f * p1.y)
            }
            val q0 = p0
            val q1 = p1
            val qm = mid
            val pts = (0 until 8).map { i ->
                val t = i / 7f
                APoint((1 - t) * (1 - t) * q0.x + 2 * (1 - t) * t * qm.x + t * t * q1.x, (1 - t) * (1 - t) * q0.y + 2 * (1 - t) * t * qm.y + t * t * q1.y)
            }
            val t = edge.tone
            items.add(AnimItem("$id.e$k", AnimShape.Line(pts, if (t == AnimTone.NORMAL) AnimRole.Grid else AnimRole.Stroke(t), if (t == AnimTone.NORMAL) 1.5f else 2.5f,
                t == AnimTone.MUTED, edge.directed, 1f)))
            val w = edge.weight
            if (!w.isNullOrEmpty()) {
                val size = measure(w, AnimMetrics.SMALL)
                val c = apex ?: APoint((a.x + b.x) / 2 + nx * 1.6f, (a.y + b.y) / 2 + ny * 1.6f)
                items.add(AnimItem("$id.w$k", AnimShape.Text(w, ARect(c.x - size.w / 2 - 3, c.y - size.h / 2 - 1, size.w + 6, size.h + 2), AnimMetrics.SMALL, false,
                    if (t == AnimTone.NORMAL) AnimRole.Secondary else AnimRole.TextC(t), AnimAlign.CENTER, true)))
            }
        }
        for (n in g.order) {
            val c = pos[n] ?: continue
            val node = g.nodes[n] ?: continue
            val hw = half[n] ?: r
            val box = ARect(c.x - hw, c.y - r, 2 * hw, 2 * r)
            val t = if (node.tone == AnimTone.NORMAL) tone else node.tone
            items.add(AnimItem("$id.n$n", AnimShape.Box(box, r, AnimRole.Fill(t), AnimRole.Stroke(t), 1.2f, false)))
            items.add(AnimItem("$id.t$n", AnimShape.Text(n, box.inset(-4f, 0f), AnimMetrics.BODY, true, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            if (node.label.isNotEmpty()) {
                val size = measure(node.label, AnimMetrics.SMALL)
                items.add(AnimItem("$id.l$n", AnimShape.Text(node.label, ARect(c.x - size.w / 2 - 3, box.maxY + 2, size.w + 6, size.h + 1), AnimMetrics.SMALL, false,
                    AnimRole.TextC(if (t == AnimTone.NORMAL) AnimTone.ACTIVE else t), AnimAlign.CENTER, true)))
            }
            anchors[AnimPointerTarget.GraphNode(e.name, n)] = AnimAnchor(APoint(c.x, box.minY - 1), APoint(c.x, box.minY - 16), true)
        }
        return items
    }

    private class GridGeometry(val cell: Float, val rowLabelW: Float, val colLabelH: Float)

    /** 行标题宽按最长的那个量（最多 80），同 Mac。 */
    private fun gridGeometry(w: Float, cols: Int, rowLabels: List<String>, colLabels: Boolean): GridGeometry {
        val rowLabelW = if (rowLabels.isEmpty()) 0f else minOf(80f, (rowLabels.maxOfOrNull { measure(it, AnimMetrics.SMALL, true).w } ?: 0f) + 8)
        val cell = minOf(40f, maxOf(11f, floor((w - rowLabelW) / maxOf(cols, 1))))   // 窄屏放 20 列：最小 11（字号跟着变小），同 Mac
        return GridGeometry(cell, rowLabelW, if (colLabels) 16f else 0f)
    }

    private fun gridItems(id: String, name: String, rect: ARect, state: AnimSceneState, tone: AnimTone,
                          anchors: MutableMap<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val g = state.grids[name] ?: return emptyList()
        val geo = gridGeometry(rect.w, g.cols, g.rowLabels, g.colLabels.isNotEmpty())
        val items = ArrayList<AnimItem>()
        val x0 = rect.minX + geo.rowLabelW
        val y0 = rect.minY + geo.colLabelH
        val font = if (geo.cell < 16) AnimMetrics.SMALL - 2 else if (geo.cell < 24) AnimMetrics.SMALL else AnimMetrics.BODY
        for ((j, label) in g.colLabels.withIndex()) {
            items.add(AnimItem("$id.cl$j", AnimShape.Text(label, ARect(x0 + j * geo.cell, rect.minY, geo.cell, 14f), AnimMetrics.SMALL, true, AnimRole.Secondary, AnimAlign.CENTER, false)))
        }
        for ((i, label) in g.rowLabels.withIndex()) {
            items.add(AnimItem("$id.rl$i", AnimShape.Text(label, ARect(rect.minX, y0 + i * geo.cell, geo.rowLabelW - 4, geo.cell), AnimMetrics.SMALL, true, AnimRole.Secondary, AnimAlign.RIGHT, false)))
        }
        for (i in 0 until g.rows) {
            for (j in 0 until g.cols) {
                val r = ARect(x0 + j * geo.cell, y0 + i * geo.cell, geo.cell, geo.cell)
                val t = if (g.tones[i][j] == AnimTone.NORMAL) tone else g.tones[i][j]
                items.add(AnimItem("$id.c$i.$j", AnimShape.Box(r, 0f, AnimRole.Fill(t), AnimRole.Grid, 0.75f, false)))
                if (g.values[i][j].isNotEmpty()) {
                    items.add(AnimItem("$id.v$i.$j", AnimShape.Text(g.values[i][j], r.inset(1f, 0f), font, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
                }
                anchors[AnimPointerTarget.Cell(name, i, j)] = AnimAnchor(APoint(r.midX, y0 + g.rows * geo.cell + 1),
                    APoint(r.midX, y0 + g.rows * geo.cell + POINTER_BAND / 2 + 3), true, r)
            }
        }
        return items
    }

    private fun sheetGeometry(name: String, w: Float): Pair<Float, Float> {
        val all = run.states.flatten().mapNotNull { it.sheets[name] }
        val cols = maxOf(all.maxOfOrNull { it.colCount } ?: 1, 1)
        val labelW = all.flatMap { it.labels.values }.maxOfOrNull { measure(it, AnimMetrics.SMALL).w }?.let { it + 12 } ?: 0f
        // 窄的时候格子可以缩到 6（IP 地址的二进制一行 35 列），字号跟着变小（同 Mac）
        val cell = minOf(16f, maxOf(6f, floor((w - labelW) / cols)))
        return cell to if (cell >= 14) AnimMetrics.BODY + 1 else if (cell >= 9) AnimMetrics.SMALL else AnimMetrics.SMALL - 2
    }

    private fun sheetItems(id: String, name: String, rect: ARect, state: AnimSceneState, tone: AnimTone): List<AnimItem> {
        val sheet = state.sheets[name] ?: return emptyList()
        val (cw, font) = sheetGeometry(name, rect.w)
        val rh = SHEET_ROW
        val x0 = rect.minX
        val y0 = rect.minY
        val items = ArrayList<AnimItem>()
        for ((r, row) in sheet.cells) {
            for ((c, cell) in row) {
                val box = ARect(x0 + c * cw, y0 + r * rh, cw, rh)
                val t = if (cell.tone == AnimTone.NORMAL) tone else cell.tone
                if (t != AnimTone.NORMAL) items.add(AnimItem("$id.b$r.$c", AnimShape.Box(box.inset(0f, 2f), 2f, AnimRole.Fill(t), null, 0f, false)))
                items.add(AnimItem("$id.c$r.$c", AnimShape.Text(cell.char, box, font, false, if (t == AnimTone.NORMAL) AnimRole.Ink else AnimRole.TextC(t), AnimAlign.CENTER, false)))
            }
        }
        for (rule in sheet.rules) {
            val y = if (rule.above) y0 + rule.row * rh + 1 else y0 + (rule.row + 1) * rh - 1
            items.add(AnimItem("$id.r${rule.row}.${rule.from}.${rule.to}.${rule.above}",
                AnimShape.Line(listOf(APoint(x0 + rule.from * cw, y), APoint(x0 + (rule.to + 1) * cw, y)), AnimRole.Ink, 1f, false, false, 1f)))
        }
        for (a in sheet.arrows) {
            val x = x0 + (a.col + 0.5f) * cw
            items.add(AnimItem("$id.a${a.fromRow}.${a.toRow}.${a.col}",
                AnimShape.Line(listOf(APoint(x, y0 + (a.fromRow + 1) * rh - 2), APoint(x, y0 + a.toRow * rh + 3)), AnimRole.Secondary, 1f, true, true, 1f)))
        }
        val right = x0 + maxOf(sheet.colCount, run.states.flatten().mapNotNull { it.sheets[name]?.colCount }.maxOrNull() ?: 0) * cw + 10
        for ((r, text) in sheet.labels) {
            items.add(AnimItem("$id.l$r", AnimShape.Text(text, ARect(right, y0 + r * rh, maxOf(rect.maxX - right, 10f), rh), AnimMetrics.SMALL, false, AnimRole.Secondary, AnimAlign.LEFT, false)))
        }
        return items
    }

    // —— 首部格式图（同 Swift `bitsItems`）——

    private class BitsGeometry(val bw: Float, val leftW: Float, val rows: Int, val width: Int)

    private fun bitsGeometry(e: AnimElement, w: Float): BitsGeometry {
        val k = e.kind as? AnimElementKind.Bits ?: return BitsGeometry(10f, 0f, 1, 32)
        val total = k.fields.sumOf { it.bits }
        val rows = maxOf((total + k.width - 1) / k.width, 1)
        val leftW = if (k.width % 8 == 0) 24f else 0f
        val labels = run.states.flatten().mapNotNull { it.bits[e.name] }.flatMap { it.labels.values }
        val labelW = labels.maxOfOrNull { measure(it, AnimMetrics.SMALL).w + 10 }?.let { minOf(it, w * 0.4f) } ?: 0f
        val bw = minOf(24f, maxOf(5f, floor((w - leftW - labelW) / k.width)))
        return BitsGeometry(bw, leftW, rows, k.width)
    }

    /** 放得下的最大字号（都放不下就用最小的，折行）。 */
    private fun fitSize(s: String, width: Float, sizes: List<Float>, bold: Boolean = false): Float {
        for (size in sizes) if (measure(s, size, bold).w <= width) return size
        return sizes.lastOrNull() ?: AnimMetrics.SMALL
    }

    /** 字段名：放得下就横排；1 位宽的控制位放不下时一个字母一行竖排。 */
    private fun fieldName(name: String, width: Float, height: Float, sizes: List<Float>): Pair<String, Float> {
        val size = fitSize(name, width, sizes)
        if (measure(name, size).w <= width) return name to size
        val chars = name.glyphs()
        if (chars.size in 2..4 && chars.size * 10f <= height) return chars.joinToString("\n") to 8f
        return name to size
    }

    private fun bitsItems(id: String, e: AnimElement, rect: ARect, state: AnimSceneState, tone: AnimTone): List<AnimItem> {
        val k = e.kind as? AnimElementKind.Bits ?: return emptyList()
        val b = state.bits[e.name] ?: return emptyList()
        val width = k.width
        val g = bitsGeometry(e, rect.w)
        val bw = g.bw
        val rh = BITS_ROW
        val x0 = rect.minX + g.leftW
        val y0 = rect.minY + BITS_RULER
        val items = ArrayList<AnimItem>()
        for (bit in 0 until width) {
            val show = bit == 0 || bit == width - 1 || bw >= 20 || (bw >= 12 && bit % 4 == 0) || (if (bw * 8 >= 30) bit % 8 == 0 else bit % 16 == 0)
            if (!show || !(bit == width - 1 || bit == 0 || (width - 1 - bit) * bw >= 16)) continue
            items.add(AnimItem("$id.bit$bit", AnimShape.Text("$bit", ARect(x0 + bit * bw - 8, rect.minY, bw + 16, 12f), AnimMetrics.SMALL - 1, false, AnimRole.Secondary, AnimAlign.CENTER, false)))
        }
        if (g.leftW > 0) {
            for (row in 0 until g.rows) {
                items.add(AnimItem("$id.off$row", AnimShape.Text("${row * width / 8}", ARect(rect.minX, y0 + row * rh, g.leftW - 5, rh), AnimMetrics.SMALL - 1, false, AnimRole.Secondary, AnimAlign.RIGHT, false)))
            }
        }
        val valued = HashSet<Int>()
        for (st in run.states.flatten()) st.bits[e.name]?.values?.forEachIndexed { i, x -> if (x.isNotEmpty()) valued.add(i) }
        var offset = 0
        for ((i, f) in k.fields.withIndex()) {
            val boxes = bitFieldBoxes(offset, f.bits, width)
            offset += f.bits
            val t = if (b.tones[i] == AnimTone.NORMAL) tone else b.tones[i]
            var main = ARect(0f, 0f, 0f, 0f)
            for ((n, box) in boxes.withIndex()) {
                val r = ARect(x0 + box[1] * bw, y0 + box[0] * rh, box[2] * bw, box[3] * rh)
                if (r.w * r.h > main.w * main.h) main = r
                items.add(AnimItem("$id.f$i.$n", AnimShape.Box(r, 0f, AnimRole.Fill(t), if (t == AnimTone.NORMAL) AnimRole.Grid else AnimRole.Stroke(t),
                    if (t == AnimTone.NORMAL) 1f else 1.5f, f.variable)))
            }
            val inner = main.w - 4
            val value = b.values[i]
            // 太窄（名字用最小字号也放不下）：有值时只显示值，没值时照旧竖排名字（同 Mac）
            val narrow = measure(f.name, 8f).w > inner
            if (narrow && value.isNotEmpty()) {
                val size = fitSize(value, inner, listOf(AnimMetrics.BODY, AnimMetrics.SMALL, 9f, 8f), true)
                items.add(AnimItem("$id.fv$i", AnimShape.Text(value, main.inset(2f, 0f), size, true, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            } else if (i !in valued || narrow) {
                val (text, size) = fieldName(f.name, inner, main.h, listOf(AnimMetrics.BODY, AnimMetrics.SMALL, 9f, 8f))
                items.add(AnimItem("$id.fn$i", AnimShape.Text(text, main.inset(2f, 0f), size, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            } else {
                val nameSize = fitSize(f.name, inner, listOf(AnimMetrics.SMALL, 9f, 8f))
                items.add(AnimItem("$id.fn$i", AnimShape.Text(f.name, ARect(main.minX + 2, main.midY - 15, inner, 14f), nameSize, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
                if (value.isNotEmpty()) {
                    val valueSize = fitSize(value, inner, listOf(AnimMetrics.BODY, AnimMetrics.SMALL, 9f, 8f), true)
                    items.add(AnimItem("$id.fv$i", AnimShape.Text(value, ARect(main.minX + 2, main.midY - 1, inner, 15f), valueSize, true, AnimRole.TextC(t), AnimAlign.CENTER, false)))
                }
            }
        }
        val right = x0 + width * bw + 8
        for ((row, text) in b.labels) {
            if (row >= g.rows) continue
            items.add(AnimItem("$id.l$row", AnimShape.Text(text, ARect(right, y0 + row * rh, maxOf(rect.maxX - right, 10f), rh), AnimMetrics.SMALL, false, AnimRole.Secondary, AnimAlign.LEFT, false)))
        }
        return items
    }

    // —— 分组（封装）图（同 Swift `packetItems`）——

    /** 各段的宽：先给够名字，余下按长度的平方根分；比例尺按这个元素所有步里最长的那一帧定。 */
    private fun packetWidths(name: String, segments: List<AnimSegment>, w: Float): List<Float> {
        fun mins(segs: List<AnimSegment>) = segs.map { maxOf(measure(it.name, AnimMetrics.SMALL).w + 10, 28f) }
        fun weight(seg: AnimSegment) = sqrt(leadingNumber(seg.length)).toFloat()
        val all = run.states.flatten().mapNotNull { it.packets[name]?.segments }
        var scale = Float.MAX_VALUE
        for (segs in all) {
            if (segs.isEmpty()) continue
            val free = w - mins(segs).sum()
            val total = segs.map { weight(it) }.sum()
            if (total > 0) scale = minOf(scale, maxOf(free, 0f) / total)
        }
        val m = mins(segments)
        val sumMin = m.sum()
        if (sumMin >= w) return m.map { it * w / sumMin }
        if (scale == Float.MAX_VALUE) {
            val extra = (w - sumMin) / maxOf(segments.size, 1)
            return m.map { it + extra }
        }
        return m.zip(segments).map { (mm, seg) -> mm + scale * weight(seg) }
    }

    private fun packetItems(id: String, e: AnimElement, rect: ARect, state: AnimSceneState, tone: AnimTone): List<AnimItem> {
        val k = e.kind as? AnimElementKind.Packet ?: return emptyList()
        val p = state.packets[e.name] ?: return emptyList()
        val items = ArrayList<AnimItem>()
        val widths = packetWidths(e.name, p.segments, rect.w)
        val frames = HashMap<Int, ARect>()
        var x = rect.minX
        val barTop = rect.minY
        for ((n, seg) in p.segments.withIndex()) {
            val r = ARect(x, barTop, widths[n], PACKET_BAR)
            frames[seg.id] = r
            x += widths[n]
            val t = if (seg.tone == AnimTone.NORMAL) tone else seg.tone
            items.add(AnimItem("$id.s${seg.id}", AnimShape.Box(r, 2f, AnimRole.Fill(t), if (t == AnimTone.NORMAL) AnimRole.Grid else AnimRole.Stroke(t), if (t == AnimTone.NORMAL) 1f else 1.5f, false)))
            val size = fitSize(seg.name, r.w - 4, listOf(AnimMetrics.BODY, AnimMetrics.SMALL, 9f, 8f))
            items.add(AnimItem("$id.sn${seg.id}", AnimShape.Text(seg.name, r.inset(2f, 0f), size, false, AnimRole.TextC(t), AnimAlign.CENTER, false)))
            if (seg.length.isNotEmpty()) {
                val text = if (k.unit.isEmpty()) seg.length else seg.length + " " + k.unit
                items.add(AnimItem("$id.sl${seg.id}", AnimShape.Text(text, ARect(r.minX - 6, r.maxY + 1, r.w + 12, PACKET_LEN - 1), AnimMetrics.SMALL - 1, false, AnimRole.Secondary, AnimAlign.CENTER, false)))
            }
        }
        if (p.segments.isEmpty()) {
            items.add(AnimItem("$id.empty", AnimShape.Box(ARect(rect.minX, barTop, 60f, PACKET_BAR), 2f, null, AnimRole.Grid, 1f, true)))
        }
        val levels = packetGroupLevels(p)
        for (g in p.groups) {
            val level = levels[g.id] ?: continue
            val a = frames[g.from] ?: continue
            val b = frames[g.to] ?: continue
            val x0 = minOf(a.minX, b.minX) + 2
            val x1 = maxOf(a.maxX, b.maxX) - 2
            val y = barTop + PACKET_BAR + PACKET_LEN + (level - 1) * PACKET_GROUP + 2
            items.add(AnimItem("$id.g${g.id}", AnimShape.Line(listOf(APoint(x0, y), APoint(x0, y + 5), APoint(x1, y + 5), APoint(x1, y)), AnimRole.Secondary, 1f, false, false, 1f)))
            val size = measure(g.label, AnimMetrics.SMALL)
            val lw = minOf(size.w + 8, rect.w)
            // 标签可以比括号宽，但不出元素的左右边
            val lx = minOf(maxOf((x0 + x1) / 2 - lw / 2, rect.minX), rect.maxX - lw)
            items.add(AnimItem("$id.gl${g.id}", AnimShape.Text(g.label, ARect(lx, y + 5, lw, 14f), AnimMetrics.SMALL, false, AnimRole.Ink, AnimAlign.CENTER, true)))
        }
        return items
    }

    private fun pointerItems(p: String, state: AnimSceneState, anchors: Map<AnimPointerTarget, AnimAnchor>): List<AnimItem> {
        val groups = LinkedHashMap<AnimPointerTarget, MutableList<String>>()
        for ((name, target) in state.pointers) {
            if (target != null && anchors[target] != null) groups.getOrPut(target) { ArrayList() }.add(name)
        }
        val items = ArrayList<AnimItem>()
        for ((target, names) in groups) {
            val a = anchors[target]!!
            val sorted = names.sorted()
            val sizes = sorted.map { measure(it, AnimMetrics.SMALL, true) }
            val total = if (a.horizontal) sizes.sumOf { it.w.toDouble() }.toFloat() + (sorted.size - 1) * 6 else sizes.sumOf { it.h.toDouble() }.toFloat() + (sorted.size - 1) * 2
            var cursor = (if (a.horizontal) a.label.x else a.label.y) - total / 2
            for ((k, name) in sorted.withIndex()) {
                val size = sizes[k]
                val t = state.elementTones[name] ?: AnimTone.ACTIVE
                val center: APoint
                if (a.horizontal) {
                    center = APoint(cursor + size.w / 2, a.label.y)
                    cursor += size.w + 6
                } else if (a.label.x < a.point.x) {
                    center = APoint(a.label.x + 6 - size.w / 2, cursor + size.h / 2)
                    cursor += size.h + 2
                } else {
                    center = APoint(a.label.x - 6 + size.w / 2, cursor + size.h / 2)
                    cursor += size.h + 2
                }
                val frame = ARect(center.x - size.w / 2 - 2, center.y - size.h / 2, size.w + 4, size.h)
                items.add(AnimItem("${p}ptr.$name", AnimShape.Text(name, frame, AnimMetrics.SMALL, true, AnimRole.TextC(t), AnimAlign.CENTER, true)))
                val from = if (a.horizontal) APoint(center.x, if (a.label.y < a.point.y) frame.maxY else frame.minY)
                else APoint(if (a.label.x < a.point.x) frame.maxX else frame.minX, center.y)
                val tip = if (a.horizontal) APoint(center.x, a.point.y) else a.point
                items.add(AnimItem("${p}ptra.$name", AnimShape.Line(listOf(from, tip), AnimRole.Stroke(t), 1.5f, false, true, 1f)))
                if (a.cell != null && k == 0) {
                    items.add(AnimItem("${p}ptrc.$name", AnimShape.Box(a.cell.inset(1f, 1f), 2f, null, AnimRole.Stroke(t), 2f, false)))
                }
            }
        }
        return items
    }

    companion object {
        const val NAME_HEIGHT = 14f
        const val POINTER_BAND = 26f
        const val LIST_NODE_HEIGHT = 30f
        const val LIST_ARC = 22f
        const val TREE_LEVEL = 52f
        const val GRAPH_NODE = 30f
        const val SHEET_ROW = 22f
        const val GRAPH_LAYER_GAP = 72f
        const val BITS_ROW = 34f
        const val BITS_RULER = 14f
        const val PACKET_BAR = 30f
        const val PACKET_LEN = 14f
        const val PACKET_GROUP = 22f

        class TreeSlots(val slot: HashMap<Int, Float>, val level: HashMap<Int, Int>, val count: Float)

        /** 每个节点排在第几格、第几层（同 Swift `treeSlots`）。 */
        fun treeSlots(tree: AnimTreeState): TreeSlots {
            val slot = HashMap<Int, Float>()
            val level = HashMap<Int, Int>()
            var counter = 0f
            fun place(nid: Int, depth: Int) {
                val n = tree.nodes[nid] ?: return
                level[nid] = depth
                if (n.children.size <= 2) {
                    val l = n.children.firstOrNull()
                    if (l != null) place(l, depth + 1) else if (n.children.size == 2) counter += 0.5f
                    slot[nid] = counter
                    counter += 1
                    if (n.children.size == 2) { val r = n.children[1]; if (r != null) place(r, depth + 1) else counter += 0.5f }
                } else {
                    val xs = ArrayList<Float>()
                    for (c in n.children) {
                        if (c != null) { place(c, depth + 1); xs.add(slot[c]!!) } else counter += 1
                    }
                    slot[nid] = if (xs.isEmpty()) counter else (xs.min() + xs.max()) / 2
                    if (xs.isEmpty()) counter += 1
                }
            }
            tree.root?.let { place(it, 0) }
            return TreeSlots(slot, level, maxOf(counter, 1f))
        }

        fun treeSlotH(pointed: Boolean): Float = if (pointed) 50f else 40f

        /** 分层排法的每一层：写了 `layers=` 就照它，否则广度优先、层里按声明顺序。 */
        fun graphLayers(e: AnimElement): List<List<String>> {
            val k = e.kind as? AnimElementKind.Graph ?: return emptyList()
            if (k.ranks.isNotEmpty()) return k.ranks
            val level = graphLevels(e)
            val byLevel = List((level.values.maxOrNull() ?: 0) + 1) { ArrayList<String>() }
            for (n in k.nodes) byLevel[level[n] ?: 0].add(n)
            return byLevel.filter { it.isNotEmpty() }
        }

        /** 从框中心沿单位方向走多远到框边（长条 = 直边 + 两头半圆）。 */
        fun nodeExit(ux: Float, uy: Float, hw: Float, hh: Float): Float {
            val a = maxOf(hw - hh, 0f)
            val ax = abs(ux)
            val ay = abs(uy)
            if (ay > 1e-6f) {
                val t = hh / ay
                if (t * ax <= a) return t
            }
            return a * ax + sqrt(maxOf(a * a * ax * ax - a * a + hh * hh, 0f))
        }

        /** 一个字段占的框：[行, 列, 列数, 行数]；跨整行的连成一个高框。 */
        fun bitFieldBoxes(offset: Int, bits: Int, width: Int): List<IntArray> {
            val out = ArrayList<IntArray>()
            var o = offset
            var n = bits
            while (n > 0) {
                val row = o / width
                val col = o % width
                if (col == 0 && n >= width) {
                    val k = n / width
                    out.add(intArrayOf(row, 0, width, k))
                    o += k * width; n -= k * width
                    continue
                }
                val take = minOf(n, width - col)
                out.add(intArrayOf(row, col, take, 1))
                o += take; n -= take
            }
            return out
        }

        /** 括号排第几层（同 Swift `packetGroupLevels`）。 */
        fun packetGroupLevels(p: AnimPacketState): Map<Int, Int> {
            val spans = ArrayList<IntArray>()   // id, lo, hi
            for (g in p.groups) {
                val a = p.index(g.from) ?: continue
                val b = p.index(g.to) ?: continue
                spans.add(intArrayOf(g.id, minOf(a, b), maxOf(a, b)))
            }
            spans.sortWith(compareBy<IntArray>({ it[2] - it[1] }, { it[0] }))
            val level = HashMap<Int, Int>()
            for ((k, s) in spans.withIndex()) {
                var l = 1
                for (o in spans.subList(0, k)) if (o[1] <= s[2] && o[2] >= s[1]) l = maxOf(l, level[o[0]]!! + 1)
                level[s[0]] = l
            }
            return level
        }

        /** 长度开头的数字（`1480`、`20 B` → 20）；没有数字 = 0。 */
        fun leadingNumber(s: String): Double = s.trimWs().takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0

        fun cellSize(width: Float, count: Int, max: Float): Float {
            if (count <= 0) return max
            return minOf(max, maxOf(14f, floor(width / count)))
        }

        fun pointedElements(states: List<AnimSceneState>): Set<String> {
            val out = HashSet<String>()
            for (s in states) for (t in s.pointers.values) if (t != null) out.add(t.element)
            return out
        }

        /** 链表箭头：相邻往右是直线；其余走弧线（往右跳过的从上面绕，往回指的从下面绕）。一律 12 个点。 */
        fun listArrow(a: ARect, b: ARect, forward: Boolean): List<APoint> {
            val n = 12
            if (forward) {
                val p0 = APoint(a.maxX, a.midY)
                val p1 = APoint(b.minX - 1, b.midY)
                return (0 until n).map { k -> val t = k.toFloat() / (n - 1); APoint(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t) }
            }
            if (a == b) {
                val o = APoint(a.midX, a.minY)
                return (0 until n).map { k ->
                    val t = k.toDouble() / (n - 1) * 1.6 * Math.PI + 0.7 * Math.PI
                    APoint(o.x + 10 * cos(t).toFloat(), o.y - 9 + 9 * sin(t).toFloat())
                }
            }
            val above = b.minX > a.minX
            val p0 = APoint(a.midX + if (above) 6 else -6, if (above) a.minY else a.maxY)
            val p1 = APoint(b.midX + if (above) -6 else 6, if (above) b.minY - 1 else b.maxY + 1)
            val lift = (if (above) -1 else 1) * (LIST_ARC - 4)
            val c = APoint((p0.x + p1.x) / 2, (if (above) minOf(p0.y, p1.y) else maxOf(p0.y, p1.y)) + lift * 2)
            return (0 until n).map { k ->
                val t = k.toFloat() / (n - 1)
                APoint((1 - t) * (1 - t) * p0.x + 2 * (1 - t) * t * c.x + t * t * p1.x, (1 - t) * (1 - t) * p0.y + 2 * (1 - t) * t * c.y + t * t * p1.y)
            }
        }

        /** 按层排：从第一个节点出发广度优先（边当无向），够不着的接在后面。 */
        fun graphLevels(e: AnimElement): Map<String, Int> {
            val k = e.kind as? AnimElementKind.Graph ?: return emptyMap()
            val first = k.nodes.firstOrNull() ?: return emptyMap()
            val level = hashMapOf(first to 0)
            val queue = ArrayDeque(listOf(first))
            var maxLevel = 0
            fun visit() {
                while (queue.isNotEmpty()) {
                    val n = queue.removeFirst()
                    for (edge in k.edges) {
                        val other = if (edge.a == n) edge.b else if (edge.b == n) edge.a else null
                        if (other != null && level[other] == null) {
                            level[other] = level[n]!! + 1
                            maxLevel = maxOf(maxLevel, level[other]!!)
                            queue.add(other)
                        }
                    }
                }
            }
            visit()
            for (n in k.nodes) {
                if (level[n] != null) continue
                maxLevel += 1
                level[n] = maxLevel
                queue.add(n)
                visit()
            }
            return level
        }

        private val circleCache = HashMap<String, List<String>>()

        /** 环形排列的顺序：≤ 8 个节点时挑边交叉最少的（第一个固定在顶上，同样少取声明顺序在前的），结果缓存。同 Mac。 */
        @Synchronized
        fun circleOrder(nodes: List<String>, edges: List<AnimGraphEdgeDecl>): List<String> {
            if (nodes.size < 4 || nodes.size > 8 || edges.size < 2) return nodes
            val key = nodes.joinToString(",") + "|" + edges.joinToString(",") { "${it.a}-${it.b}" }
            circleCache[key]?.let { return it }
            val index = nodes.withIndex().associate { it.value to it.index }
            val pairs = edges.mapNotNull { e -> val a = index[e.a]; val b = index[e.b]; if (a != null && b != null) a to b else null }
            fun crossings(pos: IntArray): Int {
                var n = 0
                for (i in pairs.indices) {
                    val a = pos[pairs[i].first]
                    val b = pos[pairs[i].second]
                    val lo = minOf(a, b)
                    val hi = maxOf(a, b)
                    for (j in i + 1 until pairs.size) {
                        val c = pos[pairs[j].first]
                        val d = pos[pairs[j].second]
                        if (c == a || c == b || d == a || d == b) continue
                        if ((c > lo && c < hi) != (d > lo && d < hi)) n += 1
                    }
                }
                return n
            }
            var best = IntArray(nodes.size) { it }
            var bestScore = crossings(best)
            val rest = IntArray(nodes.size - 1) { it + 1 }
            fun permute(k: Int) {
                if (bestScore == 0) return
                if (k == 1) {
                    val order = intArrayOf(0) + rest
                    val pos = IntArray(nodes.size)
                    for ((p, n) in order.withIndex()) pos[n] = p
                    val score = crossings(pos)
                    if (score < bestScore) { bestScore = score; best = order }
                    return
                }
                for (i in 0 until k) {
                    permute(k - 1)
                    val j = if (k % 2 == 0) i else 0
                    val t = rest[j]; rest[j] = rest[k - 1]; rest[k - 1] = t
                }
            }
            permute(rest.size)
            val result = best.map { nodes[it] }
            if (circleCache.size > 64) circleCache.clear()
            circleCache[key] = result
            return result
        }
    }
}
