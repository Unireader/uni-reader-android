package com.xvan.unireader.shared.anim

/*
 * 分步演示的状态与执行：第 k 步的画面 = 初始状态依次执行第 1…k 步的动作。逐条照抄 Mac `Sources/Anim/AnimState.swift` +
 * `AnimStructures.swift`。Swift 那边状态是值类型；这里用可变对象，每一步存档时 [AnimSceneState.deepCopy]，语义相同。
 * 时序图的时间用「单位」记，布局时再乘每单位多少 dp。
 */

class AnimCell(val id: Int, var value: String, var tone: AnimTone) {
    fun copy() = AnimCell(id, value, tone)
}

class AnimSeqState(val lower: Int, val upper: Int, val modulo: Int?, var window: Int, var start: Int,
                   val tones: MutableMap<Int, AnimTone> = HashMap()) {
    fun label(n: Int): String = (modulo?.let { ((n % it) + it) % it } ?: n).toString()
    fun copy() = AnimSeqState(lower, upper, modulo, window, start, HashMap(tones))
}

class AnimMessage(val id: Int, val name: String?, val from: String, val to: String, val label: String, val lost: Boolean,
                  var tone: AnimTone, val start: Double, var end: Double, val step: Int) {
    fun copy() = AnimMessage(id, name, from, to, label, lost, tone, start, end, step)
}

enum class AnimMarkKind { DELIVER, DISCARD, BUFFER, NOTE, TIMER_START, TIMER_STOP, TIMER_EXPIRE }

/** 竖线旁的一个标记（交付 / 丢弃 / 缓存 / 说明 / 计时器起止）。不可变。 */
class AnimLaneMark(val id: Int, val lane: String, val label: String, val kind: AnimMarkKind, val tone: AnimTone,
                   val time: Double, val step: Int)

class AnimTimerState(val id: Int, val lane: String, val label: String, val start: Double, var end: Double?, var expired: Boolean) {
    fun copy() = AnimTimerState(id, lane, label, start, end, expired)
}

class AnimVar(var value: String, var tone: AnimTone) {
    fun copy() = AnimVar(value, tone)
}

class AnimListNode(val id: Int, var value: String, var tone: AnimTone, var next: Int?) {
    fun copy() = AnimListNode(id, value, tone, next)
}

class AnimListState(val nodes: MutableList<AnimListNode>) {
    fun index(id: Int): Int? = nodes.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    fun copy() = AnimListState(nodes.mapTo(ArrayList()) { it.copy() })
}

class AnimTreeNode(val id: Int, var value: String, var valueID: Int, var tone: AnimTone, val children: MutableList<Int?>) {
    fun copy() = AnimTreeNode(id, value, valueID, tone, ArrayList(children))
}

class AnimTreeState(val nodes: MutableMap<Int, AnimTreeNode> = HashMap(), var root: Int? = null) {
    /** 按值找节点（先序第一个）。 */
    fun find(value: String): Int? {
        fun walk(id: Int?): Int? {
            val n = id?.let { nodes[it] } ?: return null
            if (n.value == value) return id
            for (c in n.children) walk(c)?.let { return it }
            return null
        }
        return walk(root)
    }

    fun parent(id: Int): Pair<Int, Int>? {
        for ((pid, n) in nodes) {
            val slot = n.children.indexOf(id)
            if (slot >= 0) return pid to slot
        }
        return null
    }

    fun subtree(id: Int): List<Int> {
        val n = nodes[id] ?: return emptyList()
        return listOf(id) + n.children.filterNotNull().flatMap { subtree(it) }
    }

    val depth: Int
        get() {
            fun d(id: Int?): Int {
                val n = id?.let { nodes[it] } ?: return 0
                return 1 + (n.children.maxOfOrNull { d(it) } ?: 0)
            }
            return d(root)
        }

    fun setChild(parent: Int, slot: Int, child: Int?) {
        val p = nodes[parent] ?: return
        while (p.children.size <= slot) p.children.add(null)
        p.children[slot] = child
        while (p.children.isNotEmpty() && p.children.last() == null) p.children.removeAt(p.children.size - 1)
    }

    fun child(id: Int, slot: Int): Int? {
        val n = nodes[id] ?: return null
        return if (slot < n.children.size) n.children[slot] else null
    }

    fun copy() = AnimTreeState(nodes.mapValuesTo(HashMap()) { it.value.copy() }, root)
}

class AnimGraphNode(var tone: AnimTone = AnimTone.NORMAL, var label: String = "") {
    fun copy() = AnimGraphNode(tone, label)
}

class AnimGraphEdge(val a: String, val b: String, val directed: Boolean, var weight: String?, var tone: AnimTone = AnimTone.NORMAL) {
    fun matches(key: String): Boolean {
        if (key.contains("->")) {
            val p = key.split("->").map { it.trimWs() }
            return p.size == 2 && p[0] == a && p[1] == b
        }
        val p = key.split("-").map { it.trimWs() }
        if (p.size != 2) return false
        return (p[0] == a && p[1] == b) || (!directed && p[0] == b && p[1] == a) || (directed && p[0] == a && p[1] == b) || (p[0] == b && p[1] == a)
    }

    fun copy() = AnimGraphEdge(a, b, directed, weight, tone)
}

class AnimGraphState(val order: List<String>, val nodes: MutableMap<String, AnimGraphNode>, val edges: MutableList<AnimGraphEdge>) {
    fun copy() = AnimGraphState(order, nodes.mapValuesTo(LinkedHashMap()) { it.value.copy() }, edges.mapTo(ArrayList()) { it.copy() })
}

class AnimGridState(val values: MutableList<MutableList<String>>, val tones: MutableList<MutableList<AnimTone>>,
                    val rowLabels: List<String>, val colLabels: List<String>) {
    val rows: Int get() = values.size
    val cols: Int get() = values.firstOrNull()?.size ?: 0
    fun copy() = AnimGridState(values.mapTo(ArrayList()) { ArrayList(it) }, tones.mapTo(ArrayList()) { ArrayList(it) }, rowLabels, colLabels)
}

class AnimSheetCell(var char: String, var tone: AnimTone = AnimTone.NORMAL) {
    fun copy() = AnimSheetCell(char, tone)
}

data class AnimSheetRule(val row: Int, val from: Int, val to: Int, val above: Boolean)
data class AnimSheetArrow(val col: Int, val fromRow: Int, val toRow: Int)

class AnimSheetState(
    val cells: MutableMap<Int, MutableMap<Int, AnimSheetCell>> = HashMap(),
    val rules: MutableList<AnimSheetRule> = ArrayList(),
    val arrows: MutableList<AnimSheetArrow> = ArrayList(),
    val labels: MutableMap<Int, String> = HashMap(),
) {
    val rowCount: Int
        get() = maxOf(cells.keys.maxOrNull() ?: -1, rules.maxOfOrNull { it.row } ?: -1, arrows.maxOfOrNull { it.toRow } ?: -1, labels.keys.maxOrNull() ?: -1) + 1
    val colCount: Int
        get() = maxOf(cells.values.mapNotNull { it.keys.maxOrNull() }.maxOrNull() ?: -1, rules.maxOfOrNull { it.to } ?: -1, arrows.maxOfOrNull { it.col } ?: -1) + 1

    fun copy() = AnimSheetState(cells.mapValuesTo(HashMap()) { r -> r.value.mapValuesTo(HashMap()) { it.value.copy() } },
        ArrayList(rules), ArrayList(arrows), HashMap(labels))
}

/** 指针指着什么。 */
sealed class AnimPointerTarget {
    abstract val element: String
    data class Index(override val element: String, val i: Int) : AnimPointerTarget()
    data class Node(override val element: String, val id: Int) : AnimPointerTarget()
    data class GraphNode(override val element: String, val name: String) : AnimPointerTarget()
    data class Cell(override val element: String, val row: Int, val col: Int) : AnimPointerTarget()
}

class AnimSceneState {
    var step = 0
    var caption = ""
    val cells = HashMap<String, MutableList<AnimCell>>()
    val vars = HashMap<String, AnimVar>()
    val seqs = HashMap<String, AnimSeqState>()
    /** 整个元素的颜色（`mark A done` 不带下标）。 */
    val elementTones = HashMap<String, AnimTone>()
    val lists = HashMap<String, AnimListState>()
    val trees = HashMap<String, AnimTreeState>()
    val graphs = HashMap<String, AnimGraphState>()
    val grids = HashMap<String, AnimGridState>()
    val sheets = HashMap<String, AnimSheetState>()
    /** 指针 → 指着什么（值为 null = 不指向任何东西；没有这个键 = 没有这个指针）。 */
    val pointers = HashMap<String, AnimPointerTarget?>()
    val messages = ArrayList<AnimMessage>()
    val marks = ArrayList<AnimLaneMark>()
    val timers = ArrayList<AnimTimerState>()
    /** 只在这一步出现的提示。 */
    val says = ArrayList<String>()
    /** 时序图「现在」到哪了（时间单位）。 */
    var cursor = 0.0

    fun deepCopy(): AnimSceneState = AnimSceneState().also { s ->
        s.step = step
        s.caption = caption
        cells.forEach { (k, v) -> s.cells[k] = v.mapTo(ArrayList()) { it.copy() } }
        vars.forEach { (k, v) -> s.vars[k] = v.copy() }
        seqs.forEach { (k, v) -> s.seqs[k] = v.copy() }
        s.elementTones.putAll(elementTones)
        lists.forEach { (k, v) -> s.lists[k] = v.copy() }
        trees.forEach { (k, v) -> s.trees[k] = v.copy() }
        graphs.forEach { (k, v) -> s.graphs[k] = v.copy() }
        grids.forEach { (k, v) -> s.grids[k] = v.copy() }
        sheets.forEach { (k, v) -> s.sheets[k] = v.copy() }
        s.pointers.putAll(pointers)
        messages.forEach { s.messages.add(it.copy()) }
        s.marks.addAll(marks)
        timers.forEach { s.timers.add(it.copy()) }
        s.says.addAll(says)
        s.cursor = cursor
    }
}

class AnimRun(val script: AnimScript, val states: List<List<AnimSceneState>>, val timelineLength: List<Double>, val errors: List<AnimDiagnostic>) {
    val stepCount: Int get() = script.stepCount

    fun state(scene: Int, step: Int): AnimSceneState {
        val list = states[scene]
        return list[step.coerceIn(0, list.size - 1)]
    }
}

object AnimEngine {
    const val TRAVEL = 6.0      // 一条消息从发出到到达
    const val GAP = 1.6         // 同一条竖线上两件事之间
    const val SLOT_PAD = 1.0    // 两步之间留白

    fun run(script: AnimScript): AnimRun {
        val errors = ArrayList(script.errors)
        val states = ArrayList<List<AnimSceneState>>()
        val lengths = ArrayList<Double>()
        for (scene in script.scenes) {
            val r = runScene(scene)
            states.add(r.first)
            lengths.add(r.second)
            errors += r.third
        }
        val seen = HashSet<String>()
        val sorted = errors.sortedBy { it.line }.filter { seen.add("${it.line}|${it.message}") }
        return AnimRun(script, states, lengths, sorted)
    }

    fun run(source: String): AnimRun = run(AnimText.parse(source))

    private fun runScene(scene: AnimSceneDecl): Triple<List<AnimSceneState>, Double, List<AnimDiagnostic>> {
        val errors = ArrayList<AnimDiagnostic>()
        var nextID = 1
        val newID = { nextID++ }

        var lanes: List<String> = emptyList()
        val initial = AnimSceneState()
        for (element in scene.elements) {
            when (val k = element.kind) {
                is AnimElementKind.Lanes -> lanes = k.names
                is AnimElementKind.Seq -> initial.seqs[element.name] = AnimSeqState(k.lower, k.upper, k.modulo, k.window, k.lower)
                is AnimElementKind.Arr -> initial.cells[element.name] = k.values.mapTo(ArrayList()) { AnimCell(newID(), it, AnimTone.NORMAL) }
                is AnimElementKind.Stack -> initial.cells[element.name] = k.values.mapTo(ArrayList()) { AnimCell(newID(), it, AnimTone.NORMAL) }
                is AnimElementKind.Queue -> initial.cells[element.name] = k.values.mapTo(ArrayList()) { AnimCell(newID(), it, AnimTone.NORMAL) }
                is AnimElementKind.Variable -> initial.vars[element.name] = AnimVar(k.value, AnimTone.NORMAL)
                is AnimElementKind.Text, is AnimElementKind.Pointer -> {}
                else -> initialStructure(element, initial, newID)
            }
        }
        val kinds = LinkedHashMap<String, AnimElementKind>()
        for (e in scene.elements) if (e.name !in kinds) kinds[e.name] = e.kind
        for (element in scene.elements) initialPointer(element, initial, kinds, errors)
        // 写在第一个 step 之前的内容：第 0 步就有
        for ((line, action) in scene.setup.firstOrNull()?.actions ?: emptyList()) {
            val handled = applyStructure(action, initial, kinds, newID) { errors.add(AnimDiagnostic(line, it)) }
            if (!handled) {
                errors.add(AnimDiagnostic(line, "before the first step only sheet / list / tree / graph / grid actions are allowed; put this inside a step"))
            }
        }

        val list = arrayListOf(initial.deepCopy())
        val state = initial
        var slotStart = 0.0
        val pendingNext = ArrayList<Pair<Int, Double>>()   // arrive=next 的消息：到达时间等下一步定

        for ((k, step) in scene.steps.withIndex()) {
            state.step = k + 1
            state.caption = step.caption
            state.says.clear()
            val clock = HashMap<String, Double>()
            val arrival = HashMap<String, Double>()
            val lastAction = HashMap<String, Double>()
            var extent = 0.0

            for ((index, pStart) in pendingNext) {
                val m = state.messages[index]
                val end = maxOf(slotStart + TRAVEL * 0.6, pStart + TRAVEL)
                m.end = end
                if (!m.lost) arrival[m.to] = maxOf(arrival[m.to] ?: 0.0, end - slotStart)
                extent = maxOf(extent, end - slotStart)
            }
            pendingNext.clear()

            fun now(lane: String) = maxOf(clock[lane] ?: 0.0, arrival[lane] ?: 0.0)
            fun checkLane(lane: String, line: Int): Boolean {
                if (lane in lanes) return true
                errors.add(AnimDiagnostic(line, if (lanes.isEmpty()) "no `lanes` declared in this scene" else "no lane “$lane” (lanes: ${lanes.joinToString(", ")})"))
                return false
            }

            for ((line, action) in step.actions) {
                val fail: (String) -> Unit = { errors.add(AnimDiagnostic(line, it)) }
                if (applyStructure(action, state, kinds, newID, fail)) continue
                when (action) {
                    is AnimAction.Say -> state.says.add(action.text)

                    is AnimAction.Send -> {
                        if (!checkLane(action.from, line) || !checkLane(action.to, line)) continue
                        if (action.from == action.to) { fail("send: from and to are the same lane"); continue }
                        if (action.id != null && state.messages.any { it.name == action.id }) { fail("as=${action.id} is already used"); continue }
                        val t = now(action.from)
                        val start = slotStart + t
                        val end = if (action.lost) start + TRAVEL * 0.55 else start + TRAVEL
                        state.messages.add(AnimMessage(newID(), action.id, action.from, action.to, action.label, action.lost,
                            action.tone ?: (if (action.lost) AnimTone.WRONG else AnimTone.NORMAL), start, end, k + 1))
                        if (action.arriveNext && !action.lost) {
                            pendingNext.add(state.messages.size - 1 to start)
                        } else {
                            if (!action.lost) arrival[action.to] = maxOf(arrival[action.to] ?: 0.0, t + TRAVEL)
                            extent = maxOf(extent, end - slotStart)
                        }
                        lastAction[action.from] = t
                        clock[action.from] = t + GAP
                        extent = maxOf(extent, t + GAP)
                    }

                    is AnimAction.LaneEvent -> {
                        if (!checkLane(action.lane, line)) continue
                        val t = now(action.lane)
                        val (markKind, defaultTone) = when (action.kind) {
                            AnimLaneEventKind.DELIVER -> AnimMarkKind.DELIVER to AnimTone.DONE
                            AnimLaneEventKind.DISCARD -> AnimMarkKind.DISCARD to AnimTone.WRONG
                            AnimLaneEventKind.BUFFER -> AnimMarkKind.BUFFER to AnimTone.NEW
                            AnimLaneEventKind.NOTE -> AnimMarkKind.NOTE to AnimTone.NORMAL
                        }
                        state.marks.add(AnimLaneMark(newID(), action.lane, action.label, markKind, action.tone ?: defaultTone, slotStart + t, k + 1))
                        lastAction[action.lane] = t
                        clock[action.lane] = t + GAP
                        extent = maxOf(extent, t + GAP)
                    }

                    is AnimAction.Timer -> {
                        if (!checkLane(action.lane, line)) continue
                        when (action.op) {
                            AnimTimerOp.START -> {
                                val t = lastAction[action.lane] ?: now(action.lane)
                                state.timers.add(AnimTimerState(newID(), action.lane, action.label, slotStart + t, null, false))
                                state.marks.add(AnimLaneMark(newID(), action.lane, action.label, AnimMarkKind.TIMER_START, AnimTone.MUTED, slotStart + t, k + 1))
                                extent = maxOf(extent, t + GAP)
                            }
                            AnimTimerOp.STOP, AnimTimerOp.EXPIRE -> {
                                val i = state.timers.indexOfLast { it.lane == action.lane && it.label == action.label && it.end == null }
                                if (i < 0) { fail("timer ${action.label} on ${action.lane} is not running (start it first)"); continue }
                                val t = now(action.lane)
                                val expire = action.op == AnimTimerOp.EXPIRE
                                state.timers[i].end = slotStart + t
                                state.timers[i].expired = expire
                                state.marks.add(AnimLaneMark(newID(), action.lane, action.label,
                                    if (expire) AnimMarkKind.TIMER_EXPIRE else AnimMarkKind.TIMER_STOP,
                                    if (expire) AnimTone.WRONG else AnimTone.MUTED, slotStart + t, k + 1))
                                if (expire) {
                                    lastAction[action.lane] = t
                                    clock[action.lane] = t + GAP
                                }
                                extent = maxOf(extent, t + GAP)
                            }
                        }
                    }

                    is AnimAction.Slide -> {
                        val seq = state.seqs[action.name]
                        if (seq == null) { fail("no seq “${action.name}”"); continue }
                        val target = action.to ?: (seq.start + (action.by ?: 0))
                        if (target < seq.lower || target > seq.upper) { fail("slide: $target is outside ${seq.lower}..${seq.upper}"); continue }
                        seq.start = target
                    }

                    is AnimAction.Mark -> apply(action.target, state, kinds, fail) { action.tone }
                    is AnimAction.Unmark -> apply(action.target, state, kinds, fail) { AnimTone.NORMAL }

                    is AnimAction.SetValue -> {
                        val target = action.target
                        val cells = state.cells[target.name]
                        val lo = target.lower
                        val hi = target.upper
                        if (target.key == null && state.vars[target.name] != null) {
                            state.vars[target.name]!!.value = action.value
                        } else if (cells != null && lo != null && hi != null) {
                            if (lo < 0 || hi >= cells.size) { fail("${target.name} has ${cells.size} item(s), index $hi is out of range"); continue }
                            for (i in lo..hi) cells[i].value = action.value
                        } else {
                            fail("set: “${target.text}” is not a var or an array cell")
                        }
                    }

                    is AnimAction.Swap -> {
                        val cells = state.cells[action.a.name]
                        val i = action.a.lower
                        val j = action.b.lower
                        if (action.a.name != action.b.name || cells == null || i == null || j == null) { fail("swap: both cells must be in the same array"); continue }
                        if (i < 0 || j < 0 || i >= cells.size || j >= cells.size) { fail("swap: index out of range (${cells.size} items)"); continue }
                        val c = cells[i]; cells[i] = cells[j]; cells[j] = c
                    }

                    is AnimAction.Push, is AnimAction.Enqueue -> {
                        val isQueue = action is AnimAction.Enqueue
                        val name = if (action is AnimAction.Push) action.name else (action as AnimAction.Enqueue).name
                        val value = if (action is AnimAction.Push) action.value else (action as AnimAction.Enqueue).value
                        val cells = state.cells[name]
                        if (cells == null) { fail("no stack / queue “$name”"); continue }
                        val kind = kinds[name]
                        if (isQueue && kind is AnimElementKind.Stack) { fail("$name is a stack: use push / pop"); continue }
                        if (!isQueue && kind is AnimElementKind.Queue) { fail("$name is a queue: use enqueue / dequeue"); continue }
                        if (cells.size >= 40) { fail("$name: at most 40 items"); continue }
                        cells.add(AnimCell(newID(), value, AnimTone.NEW))
                    }
                    is AnimAction.Pop, is AnimAction.Dequeue -> {
                        val name = if (action is AnimAction.Pop) action.name else (action as AnimAction.Dequeue).name
                        val cells = state.cells[name]
                        if (cells == null) { fail("no stack / queue “$name”"); continue }
                        if (cells.isEmpty()) { fail("$name is empty"); continue }
                        if (action is AnimAction.Dequeue) cells.removeAt(0) else cells.removeAt(cells.size - 1)
                    }

                    is AnimAction.InsertAt -> fail(if (kinds[action.name] == null) "no element “${action.name}”" else "${action.name} does not support this action")
                    is AnimAction.InsertTree -> fail(if (kinds[action.name] == null) "no element “${action.name}”" else "${action.name} does not support this action")
                    is AnimAction.Remove -> fail(if (kinds[action.name] == null) "no element “${action.name}”" else "${action.name} does not support this action")
                    is AnimAction.Rotate -> fail(if (kinds[action.name] == null) "no element “${action.name}”" else "${action.name} does not support this action")
                    is AnimAction.Move -> fail(if (kinds[action.name] == null) "no element “${action.name}”" else "${action.name} does not support this action")
                    is AnimAction.Link -> fail(if (kinds[action.from.name] == null) "no element “${action.from.name}”" else "link is for lists")
                    is AnimAction.Write -> fail(sheetMiss(action.target, kinds))
                    is AnimAction.Rule -> fail(sheetMiss(action.target, kinds))
                    is AnimAction.Arrow -> fail(sheetMiss(action.target, kinds))
                    is AnimAction.Label -> fail(sheetMiss(action.target, kinds))
                }
            }
            val height = maxOf(extent, if (lanes.isEmpty()) 0.0 else GAP)
            slotStart += height + SLOT_PAD
            state.cursor = if (lanes.isEmpty()) 0.0 else slotStart
            list.add(state.deepCopy())
            // 新进来的格子 / 节点只在进来那一步是 new 色
            for (cells in state.cells.values) for (c in cells) if (c.tone == AnimTone.NEW) c.tone = AnimTone.NORMAL
            for (l in state.lists.values) for (n in l.nodes) if (n.tone == AnimTone.NEW) n.tone = AnimTone.NORMAL
            for (t in state.trees.values) for (n in t.nodes.values) if (n.tone == AnimTone.NEW) n.tone = AnimTone.NORMAL
        }
        // 最后一步里还挂着的 arrive=next：没有下一步了，就地到达
        for ((index, _) in pendingNext) {
            val last = list.last()
            val m = last.messages[index]
            m.end = m.start + TRAVEL
            slotStart = maxOf(slotStart, m.end + SLOT_PAD)
            last.cursor = slotStart
        }
        return Triple(list, if (lanes.isEmpty()) 0.0 else slotStart, errors)
    }

    private fun sheetMiss(t: AnimTarget, kinds: Map<String, AnimElementKind>) =
        if (kinds[t.name] == null) "no element “${t.name}”" else "${t.name} is not a sheet"

    /** `mark` / `unmark`：数组 / 栈 / 队列的格子、序号条的号、变量、消息（`as=` 名）、整个元素。 */
    private fun apply(target: AnimTarget, state: AnimSceneState, kinds: Map<String, AnimElementKind>, fail: (String) -> Unit, tone: (AnimTone) -> AnimTone) {
        val name = target.name
        val cells = state.cells[name]
        val seq = state.seqs[name]
        if (cells != null) {
            if (target.key != null && target.lower == null) { fail("${target.text}: indexes are numbers, like $name[2] or $name[1..3]"); return }
            val lo = target.lower
            val hi = target.upper
            if (lo != null && hi != null) {
                if (lo < 0 || hi >= cells.size) { fail("$name has ${cells.size} item(s), index $hi is out of range"); return }
                for (i in lo..hi) cells[i].tone = tone(cells[i].tone)
            } else {
                state.elementTones[name] = tone(state.elementTones[name] ?: AnimTone.NORMAL)
            }
        } else if (seq != null) {
            if (target.key != null && target.lower == null) { fail("${target.text}: sequence numbers are numbers, like $name[2..5]"); return }
            val lo = target.lower
            val hi = target.upper
            if (lo != null && hi != null) {
                if (lo < seq.lower || hi > seq.upper) { fail("$name runs ${seq.lower}..${seq.upper}, $lo..$hi is outside"); return }
                for (i in lo..hi) seq.tones[i] = tone(seq.tones[i] ?: AnimTone.NORMAL)
            } else {
                state.elementTones[name] = tone(state.elementTones[name] ?: AnimTone.NORMAL)
            }
        } else if (state.vars[name] != null) {
            if (target.key != null) { fail("$name is a var: no index"); return }
            val v = state.vars[name]!!
            v.tone = tone(v.tone)
        } else if (state.messages.indexOfLast { it.name == name } >= 0) {
            val m = state.messages[state.messages.indexOfLast { it.name == name }]
            m.tone = tone(m.tone)
        } else if (kinds[name] != null) {
            fail("$name cannot be marked")
        } else {
            fail("no element or message named “$name”")
        }
    }

    // —— 链表 / 树 / 图 / 二维表 / 竖式 / 指针（Mac AnimStructures.swift）——

    private fun initialStructure(element: AnimElement, state: AnimSceneState, newID: () -> Int) {
        when (val k = element.kind) {
            is AnimElementKind.LinkedList -> {
                val nodes = k.values.mapTo(ArrayList()) { AnimListNode(newID(), it, AnimTone.NORMAL, null) }
                for (i in 0 until nodes.size - 1) nodes[i].next = nodes[i + 1].id
                state.lists[element.name] = AnimListState(nodes)
            }
            is AnimElementKind.Tree -> {
                val tree = AnimTreeState()
                fun build(d: AnimTreeDecl?): Int? {
                    if (d == null) return null
                    val id = newID()
                    val kids = d.children.mapTo(ArrayList()) { build(it) }
                    tree.nodes[id] = AnimTreeNode(id, d.value, newID(), AnimTone.NORMAL, kids)
                    return id
                }
                tree.root = build(k.decl)
                state.trees[element.name] = tree
            }
            is AnimElementKind.Graph -> {
                state.graphs[element.name] = AnimGraphState(k.nodes, k.nodes.associateWithTo(LinkedHashMap()) { AnimGraphNode() },
                    k.edges.mapTo(ArrayList()) { AnimGraphEdge(it.a, it.b, it.directed, it.weight) })
            }
            is AnimElementKind.Grid -> {
                state.grids[element.name] = AnimGridState(k.values.mapTo(ArrayList()) { ArrayList(it) },
                    k.values.mapTo(ArrayList()) { r -> r.mapTo(ArrayList()) { AnimTone.NORMAL } }, k.rowLabels, k.colLabels)
            }
            is AnimElementKind.Sheet -> state.sheets[element.name] = AnimSheetState()
            else -> {}
        }
    }

    private fun initialPointer(element: AnimElement, state: AnimSceneState, kinds: Map<String, AnimElementKind>, errors: MutableList<AnimDiagnostic>) {
        val k = element.kind as? AnimElementKind.Pointer ?: return
        state.pointers[element.name] = null
        val target = k.target ?: return
        try {
            state.pointers[element.name] = resolvePointer(target, state, kinds)
        } catch (e: AnimParseError) {
            errors.add(AnimDiagnostic(element.line, e.message ?: ""))
        }
    }

    private fun resolvePointer(t: AnimTarget, state: AnimSceneState, kinds: Map<String, AnimElementKind>): AnimPointerTarget {
        val kind = kinds[t.name] ?: throw AnimParseError("no element “${t.name}” to point at")
        val key = t.key ?: throw AnimParseError("point at one cell or node, like ${t.name}[0]")
        when (kind) {
            is AnimElementKind.Arr, is AnimElementKind.Stack, is AnimElementKind.Queue -> {
                val i = t.lower
                if (i == null || t.lower != t.upper) throw AnimParseError("point at one index, like ${t.name}[2]")
                val count = state.cells[t.name]?.size ?: 0
                if (i < 0 || i > count) throw AnimParseError("${t.name} has $count item(s): index $i is out of range")
                return AnimPointerTarget.Index(t.name, i)
            }
            is AnimElementKind.LinkedList -> {
                val i = t.lower
                val list = state.lists[t.name]
                if (i == null || t.lower != t.upper || list == null) throw AnimParseError("point at one node, like ${t.name}[0]")
                if (i < 0 || i >= list.nodes.size) throw AnimParseError("${t.name} has ${list.nodes.size} node(s): $i is out of range")
                return AnimPointerTarget.Node(t.name, list.nodes[i].id)
            }
            is AnimElementKind.Tree -> {
                val id = state.trees[t.name]?.find(key) ?: throw AnimParseError("${t.name} has no node $key")
                return AnimPointerTarget.Node(t.name, id)
            }
            is AnimElementKind.Graph -> {
                if (state.graphs[t.name]?.nodes?.get(key) == null) throw AnimParseError("${t.name} has no node $key")
                return AnimPointerTarget.GraphNode(t.name, key)
            }
            is AnimElementKind.Grid -> {
                val r = t.gridRange
                val g = state.grids[t.name]
                if (r == null || r.first.count() != 1 || r.second.count() != 1 || g == null) throw AnimParseError("point at one cell, like ${t.name}[1,2]")
                if (r.first.first < 0 || r.first.first >= g.rows || r.second.first < 0 || r.second.first >= g.cols) throw AnimParseError("${t.name} is ${g.rows}x${g.cols}: cell out of range")
                return AnimPointerTarget.Cell(t.name, r.first.first, r.second.first)
            }
            else -> throw AnimParseError("a pointer can point at an array, stack, queue, list, tree, graph or grid")
        }
    }

    /** 这几种结构上的动作。返回 false = 不归这里管（交给通用的处理）。 */
    private fun applyStructure(action: AnimAction, state: AnimSceneState, kinds: Map<String, AnimElementKind>,
                               newID: () -> Int, fail: (String) -> Unit): Boolean {
        fun isStructure(name: String) = when (kinds[name]) {
            is AnimElementKind.LinkedList, is AnimElementKind.Tree, is AnimElementKind.Graph, is AnimElementKind.Grid,
            is AnimElementKind.Pointer, is AnimElementKind.Sheet -> true
            else -> false
        }
        try {
            when (action) {
                is AnimAction.Mark -> {
                    if (!isStructure(action.target.name)) return false
                    paint(action.target, state, kinds) { action.tone }
                }
                is AnimAction.Unmark -> {
                    if (!isStructure(action.target.name)) return false
                    paint(action.target, state, kinds) { AnimTone.NORMAL }
                }
                is AnimAction.SetValue -> {
                    if (!isStructure(action.target.name)) return false
                    setValue(action.target, action.value, state, kinds)
                }
                is AnimAction.Swap -> {
                    if (!isStructure(action.a.name)) return false
                    swapValues(action.a, action.b, state, kinds)
                }
                is AnimAction.InsertAt -> {
                    val name = action.name
                    val list = state.lists[name]
                    if (kinds[name] !is AnimElementKind.LinkedList || list == null) throw AnimParseError("insert NAME INDEX VALUE is for lists; “$name” is not a list")
                    val index = action.index
                    if (index < 0 || index > list.nodes.size) throw AnimParseError("$name has ${list.nodes.size} node(s): insert index $index is out of range (0…${list.nodes.size})")
                    if (list.nodes.size >= 16) throw AnimParseError("list: at most 16 nodes")
                    val id = newID()
                    val node = AnimListNode(id, action.value, AnimTone.NEW, if (index < list.nodes.size) list.nodes[index].id else null)
                    if (index > 0) {
                        node.next = list.nodes[index - 1].next
                        list.nodes[index - 1].next = id
                    }
                    list.nodes.add(index, node)
                }
                is AnimAction.InsertTree -> {
                    val name = action.name
                    val tree = state.trees[name]
                    if (kinds[name] !is AnimElementKind.Tree || tree == null) throw AnimParseError("“$name” is not a tree")
                    if (tree.nodes.size >= 40) throw AnimParseError("tree: at most 40 nodes")
                    val id = newID()
                    val node = AnimTreeNode(id, action.value, newID(), AnimTone.NEW, ArrayList())
                    val parentKey = action.parent
                    if (parentKey != null) {
                        val p = tree.find(parentKey) ?: throw AnimParseError("$name has no node $parentKey")
                        val side = action.side
                        val slot = if (side != null) {
                            val s = if (side == AnimSide.LEFT) 0 else 1
                            if (tree.child(p, s) != null) throw AnimParseError("$parentKey already has a ${side.raw} child")
                            s
                        } else {
                            tree.nodes[p]!!.children.size
                        }
                        tree.nodes[id] = node
                        tree.setChild(p, slot, id)
                    } else {
                        if (tree.root != null) throw AnimParseError("$name already has a root: use insert $name ${action.value} under PARENT [left|right]")
                        tree.nodes[id] = node
                        tree.root = id
                    }
                }
                is AnimAction.Remove -> {
                    val name = action.name
                    when (kinds[name]) {
                        is AnimElementKind.LinkedList -> {
                            val list = state.lists[name]
                            val i = action.key.toIntOrNull()
                            if (list == null || i == null) throw AnimParseError("remove LIST INDEX: the index is a number")
                            if (i < 0 || i >= list.nodes.size) throw AnimParseError("$name has ${list.nodes.size} node(s): $i is out of range")
                            val gone = list.nodes.removeAt(i)
                            for (n in list.nodes) if (n.next == gone.id) n.next = gone.next
                            dropPointers(listOf(gone.id), name, state)
                        }
                        is AnimElementKind.Tree -> {
                            val tree = state.trees[name]
                            val id = tree?.find(action.key) ?: throw AnimParseError("$name has no node ${action.key}")
                            val gone = tree.subtree(id)
                            val p = tree.parent(id)
                            if (p != null) tree.setChild(p.first, p.second, null) else tree.root = null
                            for (g in gone) tree.nodes.remove(g)
                            dropPointers(gone, name, state)
                        }
                        else -> return false
                    }
                }
                is AnimAction.Link -> {
                    val from = action.from
                    val list = state.lists[from.name]
                    if (kinds[from.name] !is AnimElementKind.LinkedList || list == null) throw AnimParseError("link is for lists; “${from.name}” is not a list")
                    val i = from.lower
                    if (i == null || from.lower != from.upper || i < 0 || i >= list.nodes.size) throw AnimParseError("link: ${from.text} is not a node of ${from.name}")
                    val to = action.to
                    if (to != null) {
                        if (to.name != from.name) throw AnimParseError("link within one list: ${from.name}[…] -> ${from.name}[…]")
                        val j = to.lower
                        if (j == null || to.lower != to.upper || j < 0 || j >= list.nodes.size) throw AnimParseError("link: ${to.text} is not a node of ${to.name}")
                        list.nodes[i].next = list.nodes[j].id
                    } else {
                        list.nodes[i].next = null
                    }
                }
                is AnimAction.Rotate -> {
                    val name = action.name
                    val tree = state.trees[name]
                    if (kinds[name] !is AnimElementKind.Tree || tree == null) throw AnimParseError("“$name” is not a tree")
                    val x = tree.find(action.key) ?: throw AnimParseError("$name has no node ${action.key}")
                    // 左旋：右孩子 y 上来，y 的左子树挂到 x 的右边；右旋对称
                    val up = if (action.side == AnimSide.LEFT) 1 else 0
                    val across = 1 - up
                    val y = tree.child(x, up) ?: throw AnimParseError("rotate ${action.side.raw} at ${action.key} needs a ${if (up == 1) "right" else "left"} child")
                    val parent = tree.parent(x)
                    tree.setChild(x, up, tree.child(y, across))
                    tree.setChild(y, across, x)
                    if (parent != null) tree.setChild(parent.first, parent.second, y) else tree.root = y
                }
                is AnimAction.Move -> {
                    if (kinds[action.name] !is AnimElementKind.Pointer) return false
                    state.pointers[action.name] = action.target?.let { resolvePointer(it, state, kinds) }
                }
                is AnimAction.Write -> {
                    val t = action.target
                    val sheet = sheetOf(t, state, kinds)
                    val r = t.gridRange
                    if (r == null || r.first.count() != 1 || r.second.count() != 1) throw AnimParseError("write starts at one cell, like ${t.name}[2,5]")
                    val row = r.first.first
                    val col = r.second.first
                    val glyphs = action.text.glyphs()
                    if (row < 0 || col < 0 || row >= 40 || col + glyphs.size > 60) throw AnimParseError("sheet: at most 40 rows and 60 columns")
                    for ((k, ch) in glyphs.withIndex()) {
                        if (ch == " ") continue
                        val rowMap = sheet.cells.getOrPut(row) { HashMap() }
                        rowMap[col + k] = AnimSheetCell(ch, rowMap[col + k]?.tone ?: AnimTone.NORMAL)
                    }
                }
                is AnimAction.Rule -> {
                    val t = action.target
                    val sheet = sheetOf(t, state, kinds)
                    val r = t.gridRange
                    if (r == null || r.first.count() != 1 || r.second.first < 0 || r.first.first < 0) throw AnimParseError("rule takes one row and a column range, like ${t.name}[2,5..8]")
                    sheet.rules.add(AnimSheetRule(r.first.first, r.second.first, r.second.last, action.above))
                }
                is AnimAction.Arrow -> {
                    val t = action.target
                    val sheet = sheetOf(t, state, kinds)
                    val r = t.gridRange
                    if (r == null || r.second.count() != 1 || r.first.count() < 2 || r.first.first < 0) throw AnimParseError("arrow takes a row range and one column, like ${t.name}[1..3,9]")
                    sheet.arrows.add(AnimSheetArrow(r.second.first, r.first.first, r.first.last))
                }
                is AnimAction.Label -> {
                    val t = action.target
                    val sheet = sheetOf(t, state, kinds)
                    val row = t.lower
                    if (row == null || t.lower != t.upper || row < 0 || row >= 40) throw AnimParseError("label takes one row, like ${t.name}[3]")
                    sheet.labels[row] = action.text
                }
                else -> return false
            }
        } catch (e: AnimParseError) {
            fail(e.message ?: "")
        }
        return true
    }

    private fun sheetOf(t: AnimTarget, state: AnimSceneState, kinds: Map<String, AnimElementKind>): AnimSheetState {
        val sheet = state.sheets[t.name]
        if (kinds[t.name] !is AnimElementKind.Sheet || sheet == null) throw AnimParseError("“${t.name}” is not a sheet (declare: sheet ${t.name})")
        return sheet
    }

    private fun dropPointers(ids: List<Int>, element: String, state: AnimSceneState) {
        for ((name, target) in state.pointers.entries.toList()) {
            if (target is AnimPointerTarget.Node && target.element == element && target.id in ids) state.pointers[name] = null
        }
    }

    private fun paint(t: AnimTarget, state: AnimSceneState, kinds: Map<String, AnimElementKind>, tone: (AnimTone) -> AnimTone) {
        val key = t.key
        if (key == null) {
            state.elementTones[t.name] = tone(state.elementTones[t.name] ?: AnimTone.NORMAL)
            return
        }
        when (kinds[t.name]) {
            is AnimElementKind.LinkedList -> {
                val list = state.lists[t.name]
                val lo = t.lower
                val hi = t.upper
                if (list == null || lo == null || hi == null) throw AnimParseError("${t.text}: list nodes are numbered, like ${t.name}[0] or ${t.name}[1..2]")
                if (lo < 0 || hi >= list.nodes.size) throw AnimParseError("${t.name} has ${list.nodes.size} node(s): ${t.text} is out of range")
                for (i in lo..hi) list.nodes[i].tone = tone(list.nodes[i].tone)
            }
            is AnimElementKind.Tree -> {
                val tree = state.trees[t.name]
                val id = tree?.find(key) ?: throw AnimParseError("${t.name} has no node $key")
                val n = tree.nodes[id]!!
                n.tone = tone(n.tone)
            }
            is AnimElementKind.Graph -> {
                val g = state.graphs[t.name] ?: return
                val node = g.nodes[key]
                if (node != null) {
                    node.tone = tone(node.tone)
                } else {
                    val e = g.edges.firstOrNull { it.matches(key) } ?: throw AnimParseError("${t.name} has no node or edge $key")
                    e.tone = tone(e.tone)
                }
            }
            is AnimElementKind.Sheet -> {
                val sh = state.sheets[t.name]
                val r = t.gridRange
                if (sh == null || r == null) throw AnimParseError("sheet cells look like ${t.name}[1,2] or ${t.name}[3,5..8]")
                for (i in r.first) for (j in r.second) sh.cells[i]?.get(j)?.let { it.tone = tone(it.tone) }
            }
            is AnimElementKind.Grid -> {
                val g = state.grids[t.name]
                val r = t.gridRange
                if (g == null || r == null) throw AnimParseError("grid cells look like ${t.name}[1,2] or ${t.name}[0..2,1]")
                if (r.first.first < 0 || r.first.last >= g.rows || r.second.first < 0 || r.second.last >= g.cols) {
                    throw AnimParseError("${t.name} is ${g.rows}x${g.cols}: ${t.text} is out of range")
                }
                for (i in r.first) for (j in r.second) g.tones[i][j] = tone(g.tones[i][j])
            }
            else -> throw AnimParseError("${t.name} cannot be marked with an index")
        }
    }

    private fun setValue(t: AnimTarget, value: String, state: AnimSceneState, kinds: Map<String, AnimElementKind>) {
        val key = t.key ?: throw AnimParseError("set needs a cell or node, like ${t.name}[0]")
        when (kinds[t.name]) {
            is AnimElementKind.LinkedList -> {
                val list = state.lists[t.name]
                val lo = t.lower
                val hi = t.upper
                if (list == null || lo == null || hi == null || lo < 0 || hi >= list.nodes.size) throw AnimParseError("${t.text} is not a node of ${t.name}")
                for (i in lo..hi) list.nodes[i].value = value
            }
            is AnimElementKind.Tree -> {
                val tree = state.trees[t.name]
                val id = tree?.find(key) ?: throw AnimParseError("${t.name} has no node $key")
                tree.nodes[id]!!.value = value
            }
            is AnimElementKind.Graph -> {
                val g = state.graphs[t.name] ?: return
                val node = g.nodes[key]
                if (node != null) {
                    node.label = value
                } else {
                    val e = g.edges.firstOrNull { it.matches(key) } ?: throw AnimParseError("${t.name} has no node or edge $key")
                    e.weight = value
                }
            }
            is AnimElementKind.Grid -> {
                val g = state.grids[t.name]
                val r = t.gridRange
                if (g == null || r == null || r.first.first < 0 || r.first.last >= g.rows || r.second.first < 0 || r.second.last >= g.cols) {
                    throw AnimParseError("${t.text} is not a cell of ${t.name}")
                }
                for (i in r.first) for (j in r.second) g.values[i][j] = value
            }
            is AnimElementKind.Sheet -> {
                val sh = state.sheets[t.name]
                val r = t.gridRange
                if (sh == null || r == null || r.first.first < 0 || r.second.first < 0) throw AnimParseError("sheet cells look like ${t.name}[1,2]")
                for ((k, ch) in value.glyphs().withIndex()) {
                    if (ch == " ") continue
                    sh.cells.getOrPut(r.first.first) { HashMap() }[r.second.first + k] = AnimSheetCell(ch)
                }
            }
            else -> throw AnimParseError("set: ${t.name} has no values")
        }
    }

    private fun swapValues(a: AnimTarget, b: AnimTarget, state: AnimSceneState, kinds: Map<String, AnimElementKind>) {
        when (kinds[a.name]) {
            is AnimElementKind.Tree -> {
                val tree = state.trees[a.name]
                val x = tree?.find(a.key ?: "")
                val y = tree?.find(b.key ?: "")
                if (tree == null || x == null || y == null) {
                    throw AnimParseError("${a.name} has no node ${if (tree?.find(a.key ?: "") == null) (a.key ?: "") else (b.key ?: "")}")
                }
                val nx = tree.nodes[x]!!
                val ny = tree.nodes[y]!!
                val v = nx.value
                val vid = nx.valueID
                nx.value = ny.value
                nx.valueID = ny.valueID
                ny.value = v
                ny.valueID = vid
            }
            is AnimElementKind.LinkedList -> {
                val list = state.lists[a.name]
                val i = a.lower
                val j = b.lower
                if (list == null || i == null || j == null || i < 0 || j < 0 || i >= list.nodes.size || j >= list.nodes.size) {
                    throw AnimParseError("swap: index out of range")
                }
                val v = list.nodes[i].value
                list.nodes[i].value = list.nodes[j].value
                list.nodes[j].value = v
            }
            else -> throw AnimParseError("swap works on arrays and trees (and list values)")
        }
    }
}
