package com.xvan.unireader.shared.anim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * ```anim 跨端向量：主仓库 `spike/anim-vectors.txt`（Mac `spike/anim-vectors.swift` 用参照实现生成）。
 * 每一组：同一段文字在本端跑一遍，展开后的文字、标题、步数、报错原文、时序图长度、每个场景每一步的状态摘要都要与 Mac 逐字相同。
 * 摘要的拼法（[digest]）与 Swift 那份逐字对应——改一边两边一起改。
 */
class AnimVectorsTest {

    private fun vectors(): File {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null) {
            val f = File(d, "spike/anim-vectors.txt")
            if (f.exists()) return f
            d = d.parentFile
        }
        error("找不到 spike/anim-vectors.txt（从 ${System.getProperty("user.dir")} 往上）")
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else {
                out.append(c); i += 1
            }
        }
        return out.toString()
    }

    private fun f3(x: Double) = String.format(Locale.ROOT, "%.3f", x)

    /** 同 Swift `digest`。 */
    private fun digest(s: AnimSceneState): String {
        val parts = ArrayList<String>()
        parts.add("cap=" + s.caption)
        if (s.says.isNotEmpty()) parts.add("says=" + s.says.joinToString("|"))
        for (name in s.cells.keys.sorted()) parts.add("cells:$name=" + s.cells[name]!!.joinToString(",") { "${it.value}:${it.tone.raw}" })
        for (name in s.vars.keys.sorted()) parts.add("var:$name=${s.vars[name]!!.value}:${s.vars[name]!!.tone.raw}")
        for (name in s.seqs.keys.sorted()) {
            val q = s.seqs[name]!!
            parts.add("seq:$name=${q.start},${q.window}," + q.tones.keys.sorted().joinToString(";") { "$it:${q.tones[it]!!.raw}" })
        }
        for (name in s.elementTones.keys.sorted()) parts.add("et:$name=${s.elementTones[name]!!.raw}")
        for (name in s.lists.keys.sorted()) {
            val l = s.lists[name]!!
            parts.add("list:$name=" + l.nodes.joinToString(",") { n -> "${n.value}:${n.tone.raw}:" + (n.next?.let { l.index(it) }?.toString() ?: "-") })
        }
        for (name in s.trees.keys.sorted()) {
            val t = s.trees[name]!!
            fun ser(id: Int?): String {
                val n = id?.let { t.nodes[it] } ?: return "_"
                return "${n.value}:${n.tone.raw}(" + n.children.joinToString(" ") { ser(it) } + ")"
            }
            parts.add("tree:$name=" + ser(t.root))
        }
        for (name in s.graphs.keys.sorted()) {
            val g = s.graphs[name]!!
            val nodes = g.order.joinToString(";") { "$it:${g.nodes[it]!!.tone.raw}:${g.nodes[it]!!.label}" }
            val edges = g.edges.joinToString(";") { "${it.a}${if (it.directed) ">" else "-"}${it.b}:${it.weight ?: ""}:${it.tone.raw}" }
            parts.add("graph:$name=$nodes/$edges")
        }
        for (name in s.grids.keys.sorted()) {
            val g = s.grids[name]!!
            parts.add("grid:$name=" + (0 until g.rows).joinToString(";") { i -> (0 until g.cols).joinToString(",") { j -> "${g.values[i][j]}:${g.tones[i][j].raw}" } })
        }
        for (name in s.sheets.keys.sorted()) {
            val sh = s.sheets[name]!!
            val cells = ArrayList<String>()
            for (r in sh.cells.keys.sorted()) for (c in sh.cells[r]!!.keys.sorted()) cells.add("$r,$c=${sh.cells[r]!![c]!!.char}:${sh.cells[r]!![c]!!.tone.raw}")
            val rules = sh.rules.joinToString(";") { "${it.row}:${it.from}:${it.to}:${if (it.above) 1 else 0}" }
            val arrows = sh.arrows.joinToString(";") { "${it.col}:${it.fromRow}:${it.toRow}" }
            val labels = sh.labels.keys.sorted().joinToString(";") { "$it=${sh.labels[it]}" }
            parts.add("sheet:$name=" + cells.joinToString(";") + "/" + rules + "/" + arrows + "/" + labels)
        }
        for (name in s.bits.keys.sorted()) {
            val b = s.bits[name]!!
            parts.add("bits:$name=" + b.values.indices.joinToString(";") { "${b.values[it]}:${b.tones[it].raw}" } +
                "/" + b.labels.keys.sorted().joinToString(";") { "$it=${b.labels[it]}" })
        }
        for (name in s.packets.keys.sorted()) {
            val p = s.packets[name]!!
            val groups = p.groups.joinToString(";") { "${p.index(it.from)?.toString() ?: "?"}..${p.index(it.to)?.toString() ?: "?"}:${it.label}" }
            parts.add("packet:$name=" + p.segments.joinToString(";") { "${it.name}:${it.length}:${it.tone.raw}" } + "/" + groups)
        }
        for (name in s.pointers.keys.sorted()) {
            val desc = when (val p = s.pointers[name]) {
                null -> "null"
                is AnimPointerTarget.Index -> "idx(${p.element},${p.i})"
                is AnimPointerTarget.Node -> {
                    val li = s.lists[p.element]?.index(p.id)
                    val tn = s.trees[p.element]?.nodes?.get(p.id)
                    if (li != null) "lnode(${p.element},$li)" else if (tn != null) "tnode(${p.element},${tn.value})" else "node(${p.element},?)"
                }
                is AnimPointerTarget.GraphNode -> "gn(${p.element},${p.name})"
                is AnimPointerTarget.Cell -> "cell(${p.element},${p.row},${p.col})"
            }
            parts.add("ptr:$name=$desc")
        }
        for (m in s.messages) parts.add("msg:${m.from}>${m.to}:${m.label}:${if (m.lost) 1 else 0}:${m.tone.raw}:${f3(m.start)}:${f3(m.end)}:${m.step}")
        for (k in s.marks) {
            val kind = when (k.kind) {
                AnimMarkKind.DELIVER -> "deliver"
                AnimMarkKind.DISCARD -> "discard"
                AnimMarkKind.BUFFER -> "buffer"
                AnimMarkKind.NOTE -> "note"
                AnimMarkKind.TIMER_START -> "tstart"
                AnimMarkKind.TIMER_STOP -> "tstop"
                AnimMarkKind.TIMER_EXPIRE -> "texpire"
            }
            parts.add("mark:${k.lane}:${k.label}:$kind:${k.tone.raw}:${f3(k.time)}:${k.step}")
        }
        for (t in s.timers) parts.add("timer:${t.lane}:${t.label}:${f3(t.start)}:${t.end?.let { f3(it) } ?: "-"}:${if (t.expired) 1 else 0}")
        parts.add("cursor=${f3(s.cursor)}")
        return parts.joinToString("\n")
    }

    /** 新几种声明的解析结果（同 Swift `decl`）：只为这几种写。 */
    private fun decl(e: AnimElement): String? = when (val k = e.kind) {
        is AnimElementKind.Tree -> if (k.dir != AnimDirection.DOWN) "tree dir=${k.dir.raw}" else null
        is AnimElementKind.Graph -> if (k.layout == AnimGraphLayout.LAYERS)
            "graph layers dir=${k.dir.raw} ranks=" + k.ranks.joinToString("|") { it.joinToString(",") } else null
        is AnimElementKind.Bits -> "bits w=${k.width} f=" + k.fields.joinToString(",") { "${if (it.variable) "*" else ""}${it.name}:${it.bits}=${it.value}" }
        is AnimElementKind.Packet -> "packet unit=${k.unit} segs=" + k.segments.joinToString(",") { "${it.name}:${it.length}" }
        else -> null
    }

    /** 把本端跑一遍的结果写成与向量文件同样的几行。 */
    private fun render(name: String, src: String): List<String> {
        val run = AnimEngine.run(src)
        val out = ArrayList<String>()
        out.add("== $name")
        out.add("src ${esc(src)}")
        out.add("exp ${esc(AnimKits.expand(src).text)}")
        out.add("title ${esc(run.script.title ?: "-")}")
        out.add("steps ${run.stepCount} scenes ${run.script.scenes.size}")
        for ((i, scene) in run.script.scenes.withIndex()) {
            for (e in scene.elements) decl(e)?.let { out.add("el $i ${e.name} ${esc(it)}") }
        }
        for (e in run.errors) out.add("err ${e.line} ${esc(e.message)}")
        for ((i, len) in run.timelineLength.withIndex()) out.add("tl $i ${f3(len)}")
        for (i in run.states.indices) for (k in 0 until run.stepCount) out.add("st $i $k ${esc(digest(run.state(i, k)))}")
        return out
    }

    @Test
    fun allVectorsMatchMac() {
        val lines = vectors().readLines().filter { !it.startsWith("#") }
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (l in lines) {
            if (l.startsWith("== ") && cur.isNotEmpty()) { groups.add(cur); cur = ArrayList() }
            cur.add(l)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        assertTrue("向量太少：${groups.size}", groups.size >= 40)
        for (g in groups) {
            val name = g[0].removePrefix("== ")
            val src = unesc(g[1].removePrefix("src "))
            val mine = render(name, src)
            for (i in 0 until maxOf(g.size, mine.size)) {
                assertEquals("组「$name」第 ${i + 1} 行", g.getOrNull(i), mine.getOrNull(i))
            }
        }
    }

    @Test
    fun polynomialAndRanges() {
        assertEquals("x³ + x² + 1", AnimKits.polynomial("1101"))
        assertEquals("x⁴ + x + 1", AnimKits.polynomial("10011"))
        assertEquals(2 to 5, AnimText.parseRange("2..5"))
        assertEquals(listOf("set", "G[A->B]", "=", "9"), AnimText.tokenize("set G[A->B] = 9"))
    }
}
