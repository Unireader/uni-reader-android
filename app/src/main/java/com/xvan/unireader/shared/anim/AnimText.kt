package com.xvan.unireader.shared.anim

/*
 * 笔记里 ```anim 分步演示块的文字 → 场景描述。逐条照抄 Mac `Sources/Anim/AnimText.swift`（参照实现，方案 `../docs/ANIM-BLOCK-PLAN.md`）。
 * 🔴 同一算法两份实现：解析 / 套件 / 执行改一边必须同步另一边，跨端向量 `../spike/anim-vectors.txt`（Mac `spike/anim-vectors.swift` 生成，
 * 本端 `AnimVectorsTest` 逐条比对：展开后的文字、报错原文、每一步的状态摘要）。纯 Kotlin，不认识 Android。
 */

/** 某一行的问题。行号从 1 起，按块里的文字算（不含围栏行）。 */
data class AnimDiagnostic(val line: Int, val message: String)

/** 语义颜色状态：不写色值，画的时候映射到语义色。 */
enum class AnimTone(val raw: String) {
    NORMAL("normal"), ACTIVE("active"), DONE("done"), WRONG("wrong"), MUTED("muted"), NEW("new");

    companion object {
        fun of(s: String): AnimTone? = entries.firstOrNull { it.raw == s }
    }
}

/** 动作的目标：方括号里原样记在 [key]，按元素种类到执行时再解释（同 Mac `AnimTarget`）。 */
data class AnimTarget(val name: String, val key: String? = null) {
    val lower: Int? get() = key?.let { AnimText.parseRange(it) }?.first
    val upper: Int? get() = key?.let { AnimText.parseRange(it) }?.second

    /** 二维表 / 竖式：`r,c`、`r1..r2,c1..c2`。 */
    val gridRange: Pair<IntRange, IntRange>?
        get() {
            val parts = key?.split(",") ?: return null
            if (parts.size != 2) return null
            val r = AnimText.parseRange(parts[0].trimWs()) ?: return null
            val c = AnimText.parseRange(parts[1].trimWs()) ?: return null
            if (r.first > r.second || c.first > c.second) return null
            return (r.first..r.second) to (c.first..c.second)
        }

    val text: String get() = key?.let { "$name[$it]" } ?: name
}

/** 树的括号写法：`(值 孩子…)`，孩子是值、`_`（null）或子树。 */
data class AnimTreeDecl(val value: String, val children: List<AnimTreeDecl?>)

data class AnimGraphEdgeDecl(val a: String, val b: String, val directed: Boolean, val weight: String?)

enum class AnimGraphLayout { CIRCLE, LAYERS }

/** 树 / 分层图往哪边长（同 Swift `AnimDirection`）。 */
enum class AnimDirection(val raw: String) {
    DOWN("down"), UP("up"), RIGHT("right"), LEFT("left");

    val horizontal: Boolean get() = this == RIGHT || this == LEFT

    companion object {
        fun of(s: String): AnimDirection? = entries.firstOrNull { it.raw == s }
    }
}

/** 首部格式图（`bits`）的一个字段（同 Swift `AnimBitField`）。 */
data class AnimBitField(val name: String, val bits: Int, val variable: Boolean, val value: String)

/** 分组图（`packet`）的一段。 */
data class AnimSegmentDecl(val name: String, val length: String)

sealed class AnimElementKind {
    data class Lanes(val names: List<String>) : AnimElementKind()
    data class Seq(val lower: Int, val upper: Int, val window: Int, val modulo: Int?, val lane: String?) : AnimElementKind()
    data class Arr(val values: List<String>) : AnimElementKind()
    data class Stack(val values: List<String>) : AnimElementKind()
    data class Queue(val values: List<String>) : AnimElementKind()
    data class Variable(val value: String) : AnimElementKind()
    data class Text(val text: String) : AnimElementKind()
    data class LinkedList(val values: List<String>) : AnimElementKind()
    data class Tree(val decl: AnimTreeDecl?, val dir: AnimDirection = AnimDirection.DOWN) : AnimElementKind()
    /** `ranks` 非空 = 手动分层（`layers=[[…],[…]]`）。 */
    data class Graph(val nodes: List<String>, val edges: List<AnimGraphEdgeDecl>, val layout: AnimGraphLayout,
                     val dir: AnimDirection = AnimDirection.RIGHT, val ranks: List<List<String>> = emptyList()) : AnimElementKind()
    data class Pointer(val target: AnimTarget?) : AnimElementKind()
    data class Grid(val values: List<List<String>>, val rowLabels: List<String>, val colLabels: List<String>) : AnimElementKind()
    /** 竖式（草算纸）。 */
    data object Sheet : AnimElementKind()
    /** 首部格式图：每行 `width` 位。 */
    data class Bits(val fields: List<AnimBitField>, val width: Int) : AnimElementKind()
    /** 分组（封装）图。 */
    data class Packet(val segments: List<AnimSegmentDecl>, val unit: String) : AnimElementKind()
}

data class AnimElement(val name: String, val kind: AnimElementKind, val line: Int)

enum class AnimLaneEventKind { DELIVER, DISCARD, BUFFER, NOTE }
enum class AnimTimerOp { START, STOP, EXPIRE }
enum class AnimSide(val raw: String) { LEFT("left"), RIGHT("right") }

sealed class AnimAction {
    data class Mark(val target: AnimTarget, val tone: AnimTone) : AnimAction()
    data class Unmark(val target: AnimTarget) : AnimAction()
    data class SetValue(val target: AnimTarget, val value: String) : AnimAction()
    data class Swap(val a: AnimTarget, val b: AnimTarget) : AnimAction()
    data class Push(val name: String, val value: String) : AnimAction()
    data class Pop(val name: String) : AnimAction()
    data class Enqueue(val name: String, val value: String) : AnimAction()
    data class Dequeue(val name: String) : AnimAction()
    data class Say(val text: String) : AnimAction()
    data class Send(val from: String, val to: String, val label: String, val id: String?, val lost: Boolean,
                    val tone: AnimTone?, val arriveNext: Boolean) : AnimAction()
    data class Slide(val name: String, val to: Int?, val by: Int?) : AnimAction()
    data class Timer(val lane: String, val label: String, val op: AnimTimerOp) : AnimAction()
    data class LaneEvent(val lane: String, val label: String, val kind: AnimLaneEventKind, val tone: AnimTone?) : AnimAction()
    data class InsertAt(val name: String, val index: Int, val value: String) : AnimAction()
    data class InsertTree(val name: String, val value: String, val parent: String?, val side: AnimSide?) : AnimAction()
    /** 链表：key 是下标；树：key 是节点的值（执行时按元素种类解释）。 */
    data class Remove(val name: String, val key: String) : AnimAction()
    data class Link(val from: AnimTarget, val to: AnimTarget?) : AnimAction()
    data class Rotate(val name: String, val key: String, val side: AnimSide) : AnimAction()
    data class Move(val name: String, val target: AnimTarget?) : AnimAction()
    data class Write(val target: AnimTarget, val text: String) : AnimAction()
    data class Rule(val target: AnimTarget, val above: Boolean) : AnimAction()
    data class Arrow(val target: AnimTarget) : AnimAction()
    data class Label(val target: AnimTarget, val text: String) : AnimAction()
    /** 分组图：在 `P[i..j]` 下方画括号。 */
    data class Group(val target: AnimTarget, val text: String) : AnimAction()
}

class AnimStepDecl(val caption: String, val line: Int) {
    val actions = mutableListOf<Pair<Int, AnimAction>>()
}

class AnimSceneDecl(var title: String? = null) {
    val elements = mutableListOf<AnimElement>()
    /** 写在第一个 `step` 之前的动作：第 0 步就有的内容。 */
    val setup = mutableListOf<AnimStepDecl>()
    /** 排版：每一行是几个元素名（`row` 横排；其余一个元素一行）。 */
    val rows = mutableListOf<List<String>>()
    val steps = mutableListOf<AnimStepDecl>()
}

class AnimScript {
    var title: String? = null
    val scenes = mutableListOf<AnimSceneDecl>()
    val errors = mutableListOf<AnimDiagnostic>()

    /** 步数（含第 0 步）：各场景里最多的那个。 */
    val stepCount: Int get() = (scenes.maxOfOrNull { it.steps.size } ?: 0) + 1
}

class AnimParseError(message: String) : Exception(message)

/** Swift `trimmingCharacters(in: .whitespaces)`：只去空格类，不去换行。 */
internal fun String.trimWs(): String = trim { it == ' ' || it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }

/** 按「字」拆（同 Swift 遍历 Character；这里按码点，够用于竖式的数字与常用字）。 */
internal fun String.glyphs(): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val n = Character.charCount(cp)
        out.add(substring(i, i + n))
        i += n
    }
    return out
}

object AnimText {
    const val LANGUAGE = "anim"

    // —— 解析 ——

    fun parse(source: String): AnimScript {
        val script = AnimScript()
        var scene: AnimSceneDecl? = null
        var explicitScenes = false
        val textCounter = intArrayOf(0)
        // 套件（`kit …`）先展开成普通文字；展开出来的行都记在 kit 那一行上
        val expanded = AnimKits.expand(source)
        script.errors += expanded.errors
        val lines = expanded.text.split("\n")

        fun error(line: Int, message: String) { script.errors.add(AnimDiagnostic(line, message)) }
        fun closeScene() {
            scene?.let { script.scenes.add(it) }
            scene = null
        }

        for ((offset, raw) in lines.withIndex()) {
            val lineNo = if (offset < expanded.lineMap.size) expanded.lineMap[offset] else offset + 1
            val tokens = try {
                tokenize(raw)
            } catch (e: AnimParseError) {
                error(lineNo, e.message ?: "")
                continue
            }
            val head = tokens.firstOrNull() ?: continue
            val keyword = head.lowercase()
            val rest = tokens.drop(1)

            when (keyword) {
                "title" -> {
                    val text = rest.joinToString(" ")
                    if (scene == null && script.scenes.isEmpty()) script.title = text
                    else if (scene != null && scene!!.title == null) scene!!.title = text
                    continue
                }
                "size" -> continue
                "scene" -> {
                    val s = scene
                    if (s != null && !explicitScenes && (s.elements.isNotEmpty() || s.steps.isNotEmpty())) {
                        error(lineNo, "`scene` must come before any element or step (or put everything inside scenes)")
                    }
                    if (explicitScenes || scene != null) closeScene()
                    explicitScenes = true
                    scene = AnimSceneDecl(if (rest.isEmpty()) null else rest.joinToString(" "))
                    continue
                }
            }

            if (scene == null) scene = AnimSceneDecl()
            val sc = scene!!

            if (keyword == "step") {
                sc.steps.add(AnimStepDecl(rest.joinToString(" "), lineNo))
                continue
            }

            if (sc.steps.isEmpty()) {
                // 声明区
                try {
                    if (keyword in listOf("write", "rule", "arrow", "label", "mark", "unmark", "group")) {
                        val action = parseAction(keyword, rest)
                        if (sc.setup.isEmpty()) sc.setup.add(AnimStepDecl("", lineNo))
                        sc.setup[0].actions.add(lineNo to action)
                    } else if (keyword == "row") {
                        declareRow(rest, sc)
                    } else {
                        val element = parseElement(keyword, rest, lineNo, textCounter)
                        if (sc.elements.any { it.name == element.name }) throw AnimParseError("“${element.name}” is already declared")
                        if (element.kind is AnimElementKind.Lanes && sc.elements.any { it.kind is AnimElementKind.Lanes }) {
                            throw AnimParseError("only one `lanes` per scene")
                        }
                        sc.elements.add(element)
                        val k = element.kind
                        if (k is AnimElementKind.Pointer) {
                            // 指针画在它指的东西旁边，不单独占一行
                        } else if (k is AnimElementKind.Seq && k.lane != null) {
                            val lane = k.lane
                            if (!sc.elements.any { (it.kind as? AnimElementKind.Lanes)?.names?.contains(lane) == true }) {
                                sc.elements.removeAt(sc.elements.size - 1)
                                throw AnimParseError("seq at=$lane: no such lane (declare `lanes` first)")
                            }
                        } else {
                            sc.rows.add(listOf(element.name))
                        }
                    }
                } catch (e: AnimParseError) {
                    error(lineNo, e.message ?: "")
                }
            } else {
                try {
                    val action = parseAction(keyword, rest)
                    sc.steps.last().actions.add(lineNo to action)
                } catch (e: AnimParseError) {
                    error(lineNo, e.message ?: "")
                }
            }
        }
        closeScene()
        if (script.scenes.isEmpty() || script.scenes.all { it.elements.isEmpty() }) {
            script.errors.add(AnimDiagnostic(1, "nothing to show: declare at least one element (lanes, seq, array, stack, queue, var, text)"))
        }
        return script
    }

    private fun declareRow(names: List<String>, scene: AnimSceneDecl) {
        if (names.size < 2) throw AnimParseError("`row` needs two or more element names")
        for (name in names) {
            if (!scene.rows.any { it == listOf(name) }) throw AnimParseError("row: “$name” is not a declared element on its own row")
        }
        val first = scene.rows.indexOfFirst { it == listOf(names[0]) }
        scene.rows.removeAll { it.size == 1 && it[0] in names }
        scene.rows.add(minOf(first, scene.rows.size), names)
    }

    // —— 元素 ——

    private fun parseElement(keyword: String, rest: List<String>, line: Int, textCounter: IntArray): AnimElement {
        when (keyword) {
            "lanes" -> {
                val names = rest.filter { it != "|" }
                if (names.size < 2) throw AnimParseError("`lanes` needs two or more names")
                if (names.toSet().size != names.size) throw AnimParseError("lane names must differ")
                return AnimElement("lanes", AnimElementKind.Lanes(names), line)
            }
            "seq" -> {
                if (rest.size < 2) throw AnimParseError("usage: seq NAME 0..7 window=4 [mod=8] [at=LANE]")
                val name = checkedName(rest[0])
                val range = parseRange(rest[1])
                if (range == null || range.second < range.first) throw AnimParseError("seq range must look like 0..7")
                val (lo, hi) = range
                if (hi - lo >= 64) throw AnimParseError("seq: at most 64 numbers")
                val opts = options(rest.drop(2), setOf("window", "mod", "at"))
                val w = opts["window"]?.toIntOrNull()
                if (w == null || w < 1) throw AnimParseError("seq needs window=N (N ≥ 1)")
                var modulo: Int? = null
                opts["mod"]?.let { m ->
                    val v = m.toIntOrNull()
                    if (v == null || v < 2) throw AnimParseError("mod must be a number ≥ 2")
                    modulo = v
                }
                return AnimElement(name, AnimElementKind.Seq(lo, hi, w, modulo, opts["at"]), line)
            }
            "array", "stack", "queue" -> {
                if (rest.isEmpty()) throw AnimParseError("usage: $keyword NAME = [a, b, c]")
                val name = checkedName(rest[0])
                var values: List<String> = emptyList()
                if (rest.size > 1) {
                    if (rest[1] != "=") throw AnimParseError("usage: $keyword NAME = [a, b, c]")
                    values = parseList(rest.drop(2).joinToString(" "))
                }
                if (values.size > 40) throw AnimParseError("$keyword: at most 40 items")
                val kind = when (keyword) {
                    "array" -> AnimElementKind.Arr(values)
                    "stack" -> AnimElementKind.Stack(values)
                    else -> AnimElementKind.Queue(values)
                }
                return AnimElement(name, kind, line)
            }
            "var" -> {
                if (rest.size < 3 || rest[1] != "=") throw AnimParseError("usage: var NAME = VALUE")
                return AnimElement(checkedName(rest[0]), AnimElementKind.Variable(rest.drop(2).joinToString(" ")), line)
            }
            "text" -> {
                if (rest.isEmpty()) throw AnimParseError("usage: text \"…\"")
                textCounter[0] += 1
                return AnimElement("#text${textCounter[0]}", AnimElementKind.Text(rest.joinToString(" ")), line)
            }
            "list" -> {
                if (rest.isEmpty()) throw AnimParseError("usage: list NAME = a -> b -> c")
                val name = checkedName(rest[0])
                val values = ArrayList<String>()
                if (rest.size > 1) {
                    if (rest[1] != "=") throw AnimParseError("usage: list NAME = a -> b -> c")
                    val words = rest.drop(2)
                    for ((i, w) in words.withIndex()) {
                        if (i % 2 == 1) {
                            if (w != "->") throw AnimParseError("list items are joined by ->, like a -> b -> c")
                        } else {
                            if (w == "->") throw AnimParseError("list items are joined by ->, like a -> b -> c")
                            values.add(w)
                        }
                    }
                    if (words.lastOrNull() == "->") throw AnimParseError("list ends with ->")
                }
                if (values.size > 16) throw AnimParseError("list: at most 16 nodes")
                return AnimElement(name, AnimElementKind.LinkedList(values), line)
            }
            "tree" -> {
                val usage = "usage: tree NAME [dir=down|up|right|left] = (root (left …) (right …))"
                if (rest.isEmpty()) throw AnimParseError(usage)
                val name = checkedName(rest[0])
                val eq = rest.indexOf("=").let { if (it < 0) rest.size else it }
                val opts = options(rest.subList(1, eq), setOf("dir"))
                val dir = direction(opts["dir"], AnimDirection.DOWN)
                var tree: AnimTreeDecl? = null
                if (eq < rest.size) tree = parseTree(rest.drop(eq + 1).joinToString(" "))
                return AnimElement(name, AnimElementKind.Tree(tree, dir), line)
            }
            "graph" -> {
                val usage = "usage: graph NAME [layout=circle|layers] [dir=right|left|down|up] [layers=[[A], [B, C]]] = A-B:4, B-C, A->C"
                val eq = rest.indexOf("=")
                if (rest.size < 3 || eq < 0) throw AnimParseError(usage)
                val name = checkedName(rest[0])
                // layers=[[…], […]] 里可以有空格：先整段取出来，剩下的照常按 key=value 解析
                var head = rest.subList(1, eq).joinToString(" ")
                var ranks: List<List<String>> = emptyList()
                val at = head.indexOf("layers=[")
                if (at >= 0) {
                    var depth = 0
                    var end = head.length
                    var i = at + 7
                    while (i < head.length) {
                        if (head[i] == '[') depth += 1
                        if (head[i] == ']') { depth -= 1; if (depth == 0) { end = i + 1; break } }
                        i += 1
                    }
                    if (depth != 0) throw AnimParseError("layers= looks like [[A], [B, C], [D]]")
                    ranks = parseNestedList(head.substring(at + 7, end))
                    head = head.removeRange(at, end)
                }
                val words = head.split(' ').filter { it.isNotEmpty() }
                val opts = options(words, setOf("layout", "dir"))
                var layout = if (ranks.isEmpty()) AnimGraphLayout.CIRCLE else AnimGraphLayout.LAYERS
                opts["layout"]?.let { l ->
                    val v = when (l.lowercase()) {
                        "circle" -> AnimGraphLayout.CIRCLE
                        "layers" -> AnimGraphLayout.LAYERS
                        else -> throw AnimParseError("layout= takes circle or layers")
                    }
                    if (ranks.isNotEmpty() && v != AnimGraphLayout.LAYERS) throw AnimParseError("layers=[…] needs layout=layers")
                    layout = v
                }
                val dir = direction(opts["dir"], AnimDirection.RIGHT)
                if (opts["dir"] != null && layout != AnimGraphLayout.LAYERS) throw AnimParseError("dir= only works with layout=layers")
                val (parsedNodes, edges) = parseGraph(rest.drop(eq + 1).joinToString(" "))
                val nodes = parsedNodes.toMutableList()
                if (ranks.isNotEmpty()) {
                    val seen = HashSet<String>()
                    for (rank in ranks) {
                        if (rank.isEmpty()) throw AnimParseError("layers=: a layer is empty")
                        for (n in rank) {
                            if (n.isEmpty() || n.any { it in "[]=\"#|:-> " }) throw AnimParseError("layers=: bad node name “$n”")
                            if (!seen.add(n)) throw AnimParseError("layers=: $n is listed twice")
                            if (n !in nodes) nodes.add(n)
                        }
                    }
                    nodes.firstOrNull { it !in seen }?.let { throw AnimParseError("layers=: $it is not in any layer") }
                }
                if (nodes.size > 16) throw AnimParseError("graph: at most 16 nodes")
                return AnimElement(name, AnimElementKind.Graph(nodes, edges, layout, dir, ranks), line)
            }
            "bits" -> {
                val usage = "usage: bits NAME [width=32] = 版本:4, 首部长度:4, 总长度:16, 选项:*"
                val eq = rest.indexOf("=")
                if (rest.size < 3 || eq < 0) throw AnimParseError(usage)
                val name = checkedName(rest[0])
                val opts = options(rest.subList(1, eq), setOf("width"))
                var width = 32
                opts["width"]?.let { v ->
                    val n = v.toIntOrNull()
                    if (n == null || n !in 4..64) throw AnimParseError("width= takes 4…64 (bits per row)")
                    width = n
                }
                val fields = parseBitFields(rest.drop(eq + 1).joinToString(" "), width)
                return AnimElement(name, AnimElementKind.Bits(fields, width), line)
            }
            "packet" -> {
                val usage = "usage: packet NAME [unit=B] = 首部:20, 数据:100   (or just: packet NAME)"
                if (rest.isEmpty()) throw AnimParseError(usage)
                val name = checkedName(rest[0])
                val eq = rest.indexOf("=").let { if (it < 0) rest.size else it }
                val opts = options(rest.subList(1, eq), setOf("unit"))
                val segments = ArrayList<AnimSegmentDecl>()
                if (eq < rest.size) {
                    for (part in rest.drop(eq + 1).joinToString(" ").split(",")) {
                        val t = part.trimWs()
                        if (t.isEmpty()) continue
                        segments.add(parseSegment(t))
                    }
                }
                if (segments.size > 16) throw AnimParseError("packet: at most 16 parts")
                return AnimElement(name, AnimElementKind.Packet(segments, opts["unit"] ?: ""), line)
            }
            "sheet" -> {
                if (rest.size != 1) throw AnimParseError("usage: sheet NAME  (then write / rule / arrow / label)")
                return AnimElement(checkedName(rest[0]), AnimElementKind.Sheet, line)
            }
            "pointer" -> {
                if (!(rest.size == 1 || (rest.size == 3 && rest[1] == "->"))) throw AnimParseError("usage: pointer NAME -> A[2]  (or just: pointer NAME)")
                val name = checkedName(rest[0])
                val target = if (rest.size == 3 && rest[2].lowercase() != "null") parseTarget(rest[2]) else null
                return AnimElement(name, AnimElementKind.Pointer(target), line)
            }
            "grid" -> {
                val eq = rest.indexOf("=")
                if (rest.size < 3 || eq < 0) throw AnimParseError("usage: grid NAME [rows=[…]] [cols=[…]] = 3x4  (or = [[a, b], [c, d]])")
                val name = checkedName(rest[0])
                val labels = parseGridLabels(rest.subList(1, eq).joinToString(" "))
                val body = rest.drop(eq + 1).joinToString(" ").trimWs()
                var values: List<List<String>>
                if (body.startsWith("[")) {
                    values = parseNestedList(body)
                } else {
                    val dims = body.lowercase().split("x")
                    val r = dims.getOrNull(0)?.trimWs()?.toIntOrNull()
                    val c = dims.getOrNull(1)?.trimWs()?.toIntOrNull()
                    if (dims.size != 2 || r == null || c == null || r < 1 || c < 1) throw AnimParseError("grid size looks like 3x4")
                    values = List(r) { List(c) { "" } }
                }
                val cols = values.maxOfOrNull { it.size } ?: 0
                if (values.isEmpty() || cols <= 0) throw AnimParseError("grid is empty")
                if (values.size > 20 || cols > 20) throw AnimParseError("grid: at most 20x20")
                values = values.map { it + List(cols - it.size) { "" } }
                if (labels.first.isNotEmpty() && labels.first.size != values.size) throw AnimParseError("rows=[…] has ${labels.first.size} labels for ${values.size} rows")
                if (labels.second.isNotEmpty() && labels.second.size != cols) throw AnimParseError("cols=[…] has ${labels.second.size} labels for $cols columns")
                return AnimElement(name, AnimElementKind.Grid(values, labels.first, labels.second), line)
            }
            else -> throw AnimParseError("unknown element “$keyword” (lanes, seq, array, stack, queue, var, text, list, tree, graph, pointer, grid, sheet, bits, packet, row; actions go after a `step` line)")
        }
    }

    // —— 动作 ——

    private const val TONE_LIST = "normal, active, done, wrong, muted, new"

    private fun tone(s: String): AnimTone = AnimTone.of(s.lowercase()) ?: throw AnimParseError("unknown state “$s”: $TONE_LIST")

    private fun parseAction(keyword: String, rest: List<String>): AnimAction {
        when (keyword) {
            "mark" -> {
                if (rest.size != 2) throw AnimParseError("usage: mark TARGET $TONE_LIST")
                val t = AnimTone.of(rest[1].lowercase()) ?: throw AnimParseError("unknown state “${rest[1]}”: $TONE_LIST")
                return AnimAction.Mark(parseTarget(rest[0]), t)
            }
            "unmark" -> {
                if (rest.size != 1) throw AnimParseError("usage: unmark TARGET")
                return AnimAction.Unmark(parseTarget(rest[0]))
            }
            "set" -> {
                if (rest.size < 3 || rest[1] != "=") throw AnimParseError("usage: set TARGET = VALUE")
                return AnimAction.SetValue(parseTarget(rest[0]), rest.drop(2).joinToString(" "))
            }
            "swap" -> {
                if (rest.size != 2) throw AnimParseError("usage: swap A[i] A[j]")
                val a = parseTarget(rest[0])
                val b = parseTarget(rest[1])
                if (a.key == null || b.key == null || a.name != b.name || a.lower != a.upper || b.lower != b.upper) {
                    throw AnimParseError("swap takes two single cells of one element, like A[0] A[3] or T[8] T[3]")
                }
                return AnimAction.Swap(a, b)
            }
            "insert" -> {
                if (rest.size == 3) rest[1].toIntOrNull()?.let { return AnimAction.InsertAt(rest[0], it, rest[2]) }
                if (rest.size == 2) return AnimAction.InsertTree(rest[0], rest[1], null, null)
                if (!((rest.size == 4 || rest.size == 5) && rest[2].lowercase() == "under")) {
                    throw AnimParseError("usage: insert LIST INDEX VALUE  ·  insert TREE VALUE under PARENT [left|right]")
                }
                var side: AnimSide? = null
                if (rest.size == 5) side = AnimSide.entries.firstOrNull { it.raw == rest[4].lowercase() } ?: throw AnimParseError("side is left or right")
                return AnimAction.InsertTree(rest[0], rest[1], rest[3], side)
            }
            "remove" -> {
                if (rest.size != 2) throw AnimParseError("usage: remove LIST INDEX  ·  remove TREE VALUE")
                return AnimAction.Remove(rest[0], rest[1])
            }
            "link" -> {
                if (rest.size != 3 || rest[1] != "->") throw AnimParseError("usage: link L[i] -> L[j]  (or -> null)")
                val from = parseTarget(rest[0])
                val to = if (rest[2].lowercase() == "null") null else parseTarget(rest[2])
                if (from.key == null) throw AnimParseError("link needs a node, like L[1]")
                return AnimAction.Link(from, to)
            }
            "rotate" -> {
                val side = if (rest.size == 3) AnimSide.entries.firstOrNull { it.raw == rest[2].lowercase() } else null
                if (rest.size != 3 || side == null) throw AnimParseError("usage: rotate TREE VALUE left|right")
                return AnimAction.Rotate(rest[0], rest[1], side)
            }
            "move" -> {
                if (rest.size != 3 || rest[1] != "->") throw AnimParseError("usage: move POINTER -> A[3]  (or -> null)")
                return AnimAction.Move(rest[0], if (rest[2].lowercase() == "null") null else parseTarget(rest[2]))
            }
            "write", "label" -> {
                if (rest.size != 2) throw AnimParseError(if (keyword == "write") "usage: write S[row,col] \"text\"" else "usage: label S[row] \"text\"")
                val t = parseTarget(rest[0])
                return if (keyword == "write") AnimAction.Write(t, rest[1]) else AnimAction.Label(t, rest[1])
            }
            "rule" -> {
                if (!(rest.size == 1 || (rest.size == 2 && rest[1].lowercase() == "above"))) throw AnimParseError("usage: rule S[row,col1..col2] [above]")
                return AnimAction.Rule(parseTarget(rest[0]), rest.size == 2)
            }
            "arrow" -> {
                if (rest.size != 1) throw AnimParseError("usage: arrow S[row1..row2,col]")
                return AnimAction.Arrow(parseTarget(rest[0]))
            }
            "group" -> {
                if (rest.size != 2) throw AnimParseError("usage: group P[1..3] \"IP datagram\"")
                val t = parseTarget(rest[0])
                if (t.key == null) throw AnimParseError("group takes a range of parts, like ${t.name}[1..3]")
                return AnimAction.Group(t, rest[1])
            }
            "push", "enqueue" -> {
                if (rest.size < 2) throw AnimParseError("usage: $keyword NAME VALUE")
                val value = rest.drop(1).joinToString(" ")
                return if (keyword == "push") AnimAction.Push(rest[0], value) else AnimAction.Enqueue(rest[0], value)
            }
            "pop", "dequeue" -> {
                if (rest.size != 1) throw AnimParseError("usage: $keyword NAME")
                return if (keyword == "pop") AnimAction.Pop(rest[0]) else AnimAction.Dequeue(rest[0])
            }
            "say" -> {
                if (rest.isEmpty()) throw AnimParseError("usage: say \"…\"")
                return AnimAction.Say(rest.joinToString(" "))
            }
            "send" -> {
                if (rest.size < 4 || rest[1] != "->") throw AnimParseError("usage: send A -> B \"label\" [as=NAME] [lost] [state=…] [arrive=next]")
                var lost = false
                val flags = ArrayList<String>()
                for (word in rest.drop(4)) {
                    if (word.lowercase() == "lost") lost = true else flags.add(word)
                }
                val opts = options(flags, setOf("as", "state", "arrive"))
                val t = opts["state"]?.let { tone(it) }
                opts["arrive"]?.let { if (it != "next") throw AnimParseError("arrive= only takes next") }
                opts["as"]?.let { checkedName(it) }
                return AnimAction.Send(rest[0], rest[2], rest[3], opts["as"], lost, t, opts["arrive"] == "next")
            }
            "slide" -> {
                if (rest.size != 2) throw AnimParseError("usage: slide SEQ to=N | by=N")
                val opts = options(listOf(rest[1]), setOf("to", "by"))
                opts["to"]?.let { v ->
                    val n = v.toIntOrNull() ?: throw AnimParseError("to= takes a number")
                    return AnimAction.Slide(rest[0], n, null)
                }
                val n = opts["by"]?.toIntOrNull() ?: throw AnimParseError("by= takes a number")
                return AnimAction.Slide(rest[0], null, n)
            }
            "timer" -> {
                val op = if (rest.size == 3) when (rest[2].lowercase()) {
                    "start" -> AnimTimerOp.START
                    "stop" -> AnimTimerOp.STOP
                    "expire" -> AnimTimerOp.EXPIRE
                    else -> null
                } else null
                if (op == null) throw AnimParseError("usage: timer LANE \"T0\" start|stop|expire")
                return AnimAction.Timer(rest[0], rest[1], op)
            }
            "deliver", "discard", "buffer", "note" -> {
                if (rest.size < 2) throw AnimParseError("usage: $keyword LANE \"label\"" + (if (keyword == "note") " [state=…]" else ""))
                var t: AnimTone? = null
                if (rest.size > 2) {
                    val opts = options(rest.drop(2), setOf("state"))
                    opts["state"]?.let { t = tone(it) }
                }
                val kind = when (keyword) {
                    "deliver" -> AnimLaneEventKind.DELIVER
                    "discard" -> AnimLaneEventKind.DISCARD
                    "buffer" -> AnimLaneEventKind.BUFFER
                    else -> AnimLaneEventKind.NOTE
                }
                return AnimAction.LaneEvent(rest[0], rest[1], kind, t)
            }
            else -> throw AnimParseError("unknown action “$keyword” (mark, unmark, set, swap, push, pop, enqueue, dequeue, insert, remove, link, rotate, move, write, rule, arrow, label, group, say, send, slide, timer, deliver, discard, buffer, note)")
        }
    }

    // —— 词法 ——

    /** 按空白切词；双引号里的是一个词（去掉引号）；引号外的 `#` 起是注释；方括号外的 `->` 单独成词（同 Swift）。 */
    fun tokenize(line: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuote = false
        var quoted = false
        var bracket = 0
        var i = 0
        fun flush() {
            if (current.isNotEmpty() || quoted) tokens.add(current.toString())
            current.setLength(0)
            quoted = false
        }
        while (i < line.length) {
            val c = line[i]
            if (inQuote) {
                if (c == '"') inQuote = false else current.append(c)
            } else if (c == '"') {
                inQuote = true
                quoted = true
            } else if (c == '#' && current.isEmpty() && !quoted) {
                break
            } else if (c.isWhitespace()) {
                flush()
            } else if (c == '[' || c == ']') {
                bracket = maxOf(0, bracket + if (c == '[') 1 else -1)
                current.append(c)
            } else if (c == '-' && bracket == 0 && i + 1 < line.length && line[i + 1] == '>') {
                flush()
                tokens.add("->")
                i += 1
            } else if (c == '=' && current.isEmpty() && !quoted) {
                tokens.add("=")
            } else if (c == '|' && current.isEmpty()) {
                tokens.add("|")
            } else {
                current.append(c)
            }
            i += 1
        }
        if (inQuote) throw AnimParseError("unclosed quote")
        flush()
        return tokens
    }

    private fun options(words: List<String>, allowed: Set<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (word in words) {
            val eq = word.indexOf('=')
            if (eq <= 0) throw AnimParseError("unexpected “$word”")
            val key = word.substring(0, eq).lowercase()
            val value = word.substring(eq + 1)
            if (key !in allowed) throw AnimParseError("unknown option “$key=” (allowed: ${allowed.sorted().joinToString(", ")})")
            if (value.isEmpty()) throw AnimParseError("$key= needs a value")
            out[key] = value
        }
        return out
    }

    fun parseRange(s: String): Pair<Int, Int>? {
        val parts = s.split("..")
        if (parts.size == 1) return parts[0].toIntOrNull()?.let { it to it }
        if (parts.size != 2) return null
        val a = parts[0].toIntOrNull() ?: return null
        val b = parts[1].toIntOrNull() ?: return null
        return a to b
    }

    fun parseTarget(s: String): AnimTarget {
        val open = s.indexOf('[')
        if (open < 0) return AnimTarget(checkedName(s))
        if (!s.endsWith("]") || open == 0) throw AnimParseError("bad target “$s” (use A, A[2], A[1..3], T[8], G[A-B] or D[1,2])")
        val name = s.substring(0, open)
        val inner = s.substring(open + 1, s.length - 1).trimWs()
        if (inner.isEmpty() || inner.contains("[")) throw AnimParseError("bad index in “$s”")
        parseRange(inner)?.let { if (it.second < it.first) throw AnimParseError("bad range in “$s” (low..high)") }
        return AnimTarget(checkedName(name), inner)
    }

    /** 树：`(8 (3 1 6) (10 _ 14))`、单个值、`_`（空树）。 */
    fun parseTree(s: String): AnimTreeDecl? {
        val tokens = ArrayList<String>()
        val word = StringBuilder()
        for (c in s) {
            if (c == '(' || c == ')') {
                if (word.isNotEmpty()) { tokens.add(word.toString()); word.setLength(0) }
                tokens.add(c.toString())
            } else if (c.isWhitespace() || c == ',') {
                if (word.isNotEmpty()) { tokens.add(word.toString()); word.setLength(0) }
            } else {
                word.append(c)
            }
        }
        if (word.isNotEmpty()) tokens.add(word.toString())
        var i = 0
        var count = 0
        fun item(): AnimTreeDecl? {
            if (i >= tokens.size) throw AnimParseError("tree: unexpected end (missing “)”?)")
            val t = tokens[i]
            i += 1
            if (t == ")") throw AnimParseError("tree: unexpected “)”")
            if (t == "_") return null
            if (t != "(") {
                count += 1
                return AnimTreeDecl(t, emptyList())
            }
            if (i >= tokens.size || tokens[i] == "(" || tokens[i] == ")" || tokens[i] == "_") throw AnimParseError("tree: “(” must be followed by the node's value")
            val value = tokens[i]
            i += 1
            count += 1
            val children = ArrayList<AnimTreeDecl?>()
            while (i < tokens.size && tokens[i] != ")") children.add(item())
            if (i >= tokens.size) throw AnimParseError("tree: missing “)”")
            i += 1
            while (children.isNotEmpty() && children.last() == null) children.removeAt(children.size - 1)
            return AnimTreeDecl(value, children)
        }
        if (tokens.isEmpty()) return null
        val root = item()
        if (i != tokens.size) throw AnimParseError("tree: extra text after the root's “)”")
        if (count > 40) throw AnimParseError("tree: at most 40 nodes")
        return root
    }

    /** 图：逗号分隔的 `A-B`、`A->B`、`A-B:4`、单独的 `D`。 */
    fun parseGraph(s: String): Pair<List<String>, List<AnimGraphEdgeDecl>> {
        val nodes = ArrayList<String>()
        val edges = ArrayList<AnimGraphEdgeDecl>()
        fun node(n: String) {
            val name = n.trimWs()
            if (name.isEmpty() || name.any { it in "[]=\"#|:-> " }) throw AnimParseError("graph: bad node name “$n”")
            if (name !in nodes) nodes.add(name)
        }
        for (raw in s.split(",")) {
            var item = raw.trimWs()
            if (item.isEmpty()) continue
            var weight: String? = null
            val colon = item.indexOf(':')
            if (colon >= 0) {
                weight = item.substring(colon + 1).trimWs()
                item = item.substring(0, colon).trimWs()
                if (weight.isEmpty()) throw AnimParseError("graph: “:” needs a weight after it")
            }
            val directed = item.contains("->")
            val parts = if (directed) item.split("->") else item.split("-")
            if (parts.size == 1) {
                if (weight != null) throw AnimParseError("graph: a weight belongs to an edge")
                node(parts[0])
                continue
            }
            if (parts.size != 2) throw AnimParseError("graph: “$item” is not an edge (A-B or A->B)")
            node(parts[0]); node(parts[1])
            val a = parts[0].trimWs()
            val b = parts[1].trimWs()
            if (a == b) throw AnimParseError("graph: an edge needs two different nodes")
            edges.add(AnimGraphEdgeDecl(a, b, directed, weight))
        }
        if (nodes.isEmpty()) throw AnimParseError("graph has no nodes")
        return nodes to edges
    }

    /** `[[a, b], [c, d]]`。 */
    fun parseNestedList(s: String): List<List<String>> {
        val t = s.trimWs()
        if (!t.startsWith("[") || !t.endsWith("]")) throw AnimParseError("rows look like [[a, b], [c, d]]")
        val inner = t.substring(1, t.length - 1)
        val rows = ArrayList<List<String>>()
        var depth = 0
        val current = StringBuilder()
        for (c in inner) {
            if (c == '[') {
                depth += 1
                if (depth == 1) { current.setLength(0); continue }
            }
            if (c == ']') {
                depth -= 1
                if (depth == 0) { rows.add(parseList("[$current]")); continue }
            }
            if (depth >= 1) current.append(c)
            if (depth > 1 || depth < 0) throw AnimParseError("rows look like [[a, b], [c, d]]")
        }
        if (depth != 0) throw AnimParseError("unbalanced [ ] in grid")
        return rows
    }

    private fun parseGridLabels(s: String): Pair<List<String>, List<String>> {
        var rows: List<String> = emptyList()
        var cols: List<String> = emptyList()
        var rest = s.trimWs()
        while (rest.isNotEmpty()) {
            val eq = rest.indexOf('=')
            val close = rest.indexOf(']')
            if (eq < 0 || close < 0) throw AnimParseError("grid options look like rows=[a, b] cols=[x, y]")
            val key = rest.substring(0, eq).trimWs().lowercase()
            val list = parseList(rest.substring(eq + 1, close + 1))
            when (key) {
                "rows" -> rows = list
                "cols" -> cols = list
                else -> throw AnimParseError("unknown grid option “$key=” (rows, cols)")
            }
            rest = rest.substring(close + 1).trimWs()
        }
        return rows to cols
    }

    /** `[a, b, "c d"]`：逗号分隔，项两边空白去掉；项里的双引号去掉。 */
    fun parseList(s: String): List<String> {
        val t = s.trimWs()
        if (!t.startsWith("[") || !t.endsWith("]")) throw AnimParseError("a list looks like [a, b, c]")
        val inner = t.substring(1, t.length - 1).trimWs()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { it.trimWs().trim('"') }
    }

    private fun direction(s: String?, d: AnimDirection): AnimDirection {
        if (s == null) return d
        return AnimDirection.of(s.lowercase()) ?: throw AnimParseError("dir= takes down, up, right or left")
    }

    /** 首部字段：`名字:位数`、`名字:位数=值`、`名字:*`、`名字:*24`（同 Swift `parseBitFields`）。 */
    fun parseBitFields(s: String, width: Int): List<AnimBitField> {
        val fields = ArrayList<AnimBitField>()
        var offset = 0
        for (raw in s.split(",")) {
            val item = raw.trimWs()
            if (item.isEmpty()) continue
            val colon = item.lastIndexOf(':')
            if (colon <= 0) throw AnimParseError("bits: a field looks like 名字:4 (got “$item”)")
            val name = item.substring(0, colon).trimWs()
            var spec = item.substring(colon + 1).trimWs()
            var value = ""
            val eq = spec.indexOf('=')
            if (eq >= 0) {
                value = spec.substring(eq + 1).trimWs()
                spec = spec.substring(0, eq).trimWs()
            }
            val variable = spec.startsWith("*")
            if (variable) spec = spec.substring(1)
            val n: Int = if (variable && spec.isEmpty()) {
                width - offset % width
            } else {
                val v = spec.toIntOrNull()
                if (v == null || v < 1) throw AnimParseError("bits: “$item”: the size is a number of bits (or * for variable length)")
                v
            }
            fields.add(AnimBitField(name, n, variable, value))
            offset += n
        }
        if (fields.isEmpty()) throw AnimParseError("bits has no fields")
        if (fields.size > 40) throw AnimParseError("bits: at most 40 fields")
        if ((offset + width - 1) / width > 20) throw AnimParseError("bits: at most 20 rows of $width bits")
        return fields
    }

    /** 分组图的一段：`名字:长度` 或只有名字。 */
    fun parseSegment(s: String): AnimSegmentDecl {
        val t = s.trimWs().trim('"')
        if (t.isEmpty()) throw AnimParseError("packet: empty part")
        val colon = t.lastIndexOf(':')
        if (colon > 0) return AnimSegmentDecl(t.substring(0, colon).trimWs(), t.substring(colon + 1).trimWs())
        return AnimSegmentDecl(t, "")
    }

    private fun checkedName(s: String): String {
        if (s.isEmpty() || s.any { it in "[]=\"#|" || it.isWhitespace() } || s == "->") throw AnimParseError("bad name “$s”")
        return s
    }
}
