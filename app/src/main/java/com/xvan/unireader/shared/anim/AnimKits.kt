package com.xvan.unireader.shared.anim

import java.util.Locale

/*
 * 套件（`kit 名字 参数…`）：解析前展开成普通 anim 文字。逐行照抄 Mac `Sources/Anim/AnimKits.swift`
 * ——生成的每一行文字、说明的中英两份都要与 Mac 逐字相同（`AnimVectorsTest` 比对展开后的整段文字）。
 */
object AnimKits {
    val NAMES = listOf("stopwait", "gbn", "sr", "bubble", "selection", "insertion", "quick", "crc",
        "preorder", "inorder", "postorder", "levelorder", "bfs", "dfs", "dijkstra")

    class Kit(val title: String, val lines: List<String>)

    class Expanded(val text: String, val lineMap: List<Int>, val errors: List<AnimDiagnostic>)

    /** 展开整块文字里的 kit 行：展开后的文字、每一行对应原文第几行（1 起）、kit 本身的错误。 */
    fun expand(source: String): Expanded {
        val lines = source.split("\n")
        if (!lines.any { isKitLine(it) }) return Expanded(source, (1..maxOf(lines.size, 1)).toList(), emptyList())
        val out = ArrayList<String>()
        val map = ArrayList<Int>()
        val errors = ArrayList<AnimDiagnostic>()
        var hasTitle = false
        var inScene = false
        for ((i, raw) in lines.withIndex()) {
            val lineNo = i + 1
            val head = runCatching { AnimText.tokenize(raw) }.getOrNull()?.firstOrNull()?.lowercase()
            if (head == "title") hasTitle = true
            if (head == "scene") inScene = true
            if (head != "kit") {
                out.add(raw); map.add(lineNo)
                continue
            }
            try {
                val kit = generate(raw.trimWs().drop(3))
                if (!hasTitle && !inScene) {
                    out.add("title " + kit.title); map.add(lineNo)
                    hasTitle = true
                }
                for (l in kit.lines) { out.add(l); map.add(lineNo) }
            } catch (e: AnimParseError) {
                errors.add(AnimDiagnostic(lineNo, "kit: " + (e.message ?: "")))
            }
        }
        return Expanded(out.joinToString("\n"), map, errors)
    }

    private fun isKitLine(s: String) = s.trimWs().lowercase().startsWith("kit ")

    // —— 参数 ——

    class Args {
        val values = LinkedHashMap<String, String>()
        private val used = HashSet<String>()

        fun string(k: String): String? { used.add(k); return values[k] }

        fun int(k: String, d: Int, range: IntRange): Int {
            val s = string(k) ?: return d
            val v = s.toIntOrNull()
            if (v == null || v !in range) throw AnimParseError("$k= takes a whole number in ${range.first}…${range.last}")
            return v
        }

        fun ints(k: String): MutableSet<Int> {
            val s = string(k) ?: return LinkedHashSet()
            val out = LinkedHashSet<Int>()
            for (part in s.trim('[', ']').split(",")) {
                if (part.trimWs().isEmpty()) continue
                out.add(part.trimWs().toIntOrNull() ?: throw AnimParseError("$k= takes numbers like 2 or 2,5"))
            }
            return out
        }

        fun checkUnused(allowed: List<String>) {
            for (k in values.keys) {
                if (k !in used && k != "lang") throw AnimParseError("unknown option “$k=” (allowed: ${allowed.joinToString(", ")})")
            }
        }
    }

    /** `名字 k=v k=[…] k=(…) k="…"`：值可以带括号（里面有空格也行）或双引号。 */
    fun parseArgs(s: String): Pair<String, Args> {
        var chars = s.trimWs()
        var inQuote = false
        for ((i, c) in chars.withIndex()) {
            if (c == '"') inQuote = !inQuote
            if (c == '#' && !inQuote) { chars = chars.substring(0, i); break }
        }
        var i = 0
        fun skipSpace() { while (i < chars.length && chars[i].isWhitespace()) i += 1 }
        fun word(): String {
            val w = StringBuilder()
            while (i < chars.length && !chars[i].isWhitespace() && chars[i] != '=') { w.append(chars[i]); i += 1 }
            return w.toString()
        }
        skipSpace()
        val name = word().lowercase()
        if (name.isEmpty()) throw AnimParseError("usage: kit NAME options… (${NAMES.joinToString(", ")})")
        val args = Args()
        while (true) {
            skipSpace()
            if (i >= chars.length) break
            val key = word().lowercase()
            if (i >= chars.length || chars[i] != '=' || key.isEmpty()) throw AnimParseError("options look like key=value (got “$key”)")
            i += 1
            val value = StringBuilder()
            if (i < chars.length && chars[i] == '"') {
                i += 1
                while (i < chars.length && chars[i] != '"') { value.append(chars[i]); i += 1 }
                if (i >= chars.length) throw AnimParseError("unclosed quote")
                i += 1
            } else if (i < chars.length && (chars[i] == '[' || chars[i] == '(')) {
                val open = chars[i]
                val close = if (open == '[') ']' else ')'
                var depth = 0
                while (i < chars.length) {
                    val c = chars[i]
                    value.append(c)
                    i += 1
                    if (c == open) depth += 1
                    if (c == close) { depth -= 1; if (depth == 0) break }
                }
                if (depth != 0) throw AnimParseError("unbalanced $open in $key=")
            } else {
                while (i < chars.length && !chars[i].isWhitespace()) { value.append(chars[i]); i += 1 }
            }
            args.values[key] = value.toString()
        }
        return name to args
    }

    fun generate(text: String): Kit {
        val (name, args) = parseArgs(text)
        val zh = when (val l = args.values["lang"]?.lowercase()) {
            "zh" -> true
            "en" -> false
            null -> Locale.getDefault().language == "zh"
            else -> throw AnimParseError("lang= takes zh or en, not $l")
        }
        val w = Words(zh)
        return when (name) {
            "stopwait", "gbn", "sr" -> protocolKit(name, args, w)
            "bubble", "selection", "insertion", "quick" -> sortKit(name, args, w)
            "crc" -> crcKit(args, w)
            "preorder", "inorder", "postorder", "levelorder" -> traverseKit(name, args, w)
            "bfs", "dfs" -> searchKit(name, args, w)
            "dijkstra" -> dijkstraKit(args, w)
            else -> throw AnimParseError("unknown kit “$name” (${NAMES.joinToString(", ")})")
        }
    }

    // —— 文字 ——

    /** 套件生成的说明文字：中英两份。 */
    class Words(val zh: Boolean) {
        fun t(z: String, e: String) = if (zh) z else e
        val sep get() = if (zh) "；" else "; "
        val listSep get() = if (zh) "、" else ", "

        /** 连号写成 2~5，否则 2、4、7。 */
        fun list(xs: List<Int>, label: (Int) -> String = { "$it" }): String {
            if (xs.size <= 2 || !xs.zipWithNext().all { (a, b) -> b == a + 1 }) return xs.joinToString(listSep) { label(it) }
            return label(xs.first()) + "~" + label(xs.last())
        }
    }

    fun q(s: String) = "\"" + s.replace("\"", "'") + "\""

    private fun step(caption: String, actions: List<String>): List<String> =
        listOf("step " + caption.replace("#", "＃")) + actions.map { "  $it" }

    // —— 可靠传输：停等 / 回退 N 步 / 选择重传 ——

    private fun protocolKit(kind: String, a: Args, L: Words): Kit {
        val n = a.int("frames", if (kind == "stopwait") 3 else 6, 1..12)
        val w = if (kind == "stopwait") 1 else a.int("window", 4, 1..8)
        var mod: Int? = null
        if (a.values["mod"] != null) mod = a.int("mod", 8, 2..16)
        mod?.let { m ->
            if (kind == "gbn" && w > m - 1) throw AnimParseError("go-back-N needs window ≤ mod − 1 (${m - 1})")
            if (kind == "sr" && w > m / 2) throw AnimParseError("selective repeat needs window ≤ mod / 2 (${m / 2})")
            if (kind == "stopwait" && m < 2) throw AnimParseError("mod must be at least 2")
        }
        val lose = a.ints("lose")
        val loseAck = a.ints("loseack")
        val timeout = a.int("timeout", if (kind == "stopwait") 1 else 2, 1..4)
        a.checkUnused(listOf("frames", "window", "lose", "loseack", "timeout", "mod", "lang"))
        for (f in lose + loseAck) if (f >= n) throw AnimParseError("frame $f does not exist (frames=$n: 0…${n - 1})")

        val S = L.t("发送方", "Sender")
        val R = L.t("接收方", "Receiver")
        val expectedName = L.t("期望", "expected")
        fun lbl(f: Int): String = (mod?.let { f % it } ?: f).toString()
        val modOpt = mod?.let { " mod=$it" } ?: ""

        val lines = mutableListOf("lanes $S $R", "seq SW 0..${n - 1} window=$w at=$S$modOpt")
        if (kind == "sr") lines.add("seq RW 0..${n - 1} window=$w at=$R$modOpt") else lines.add("var $expectedName = 0")

        var base = 0
        var next = 0
        var round = 0
        val sentRound = HashMap<Int, Int>()
        val ackedSR = HashSet<Int>()
        var expected = 0
        var rcvBase = 0
        val received = HashSet<Int>()
        val inflight = ArrayList<Pair<Int, Boolean>>()
        val acksInflight = ArrayList<Pair<Int, Boolean>>()
        val running = HashSet<String>()
        var gbnTimer: String? = null

        while (round < 40) {
            // —— 发送方 ——
            val acts = ArrayList<String>()
            val caps = ArrayList<String>()
            val oldBase = base
            val got = acksInflight.filter { !it.second }.map { it.first }
            for (ackN in got) {
                if (kind == "sr") {
                    ackedSR.add(ackN)
                    if (running.remove("T$ackN")) acts.add("timer $S ${q("T" + lbl(ackN))} stop")
                } else {
                    base = maxOf(base, ackN + 1)
                }
            }
            if (kind == "sr") {
                for (f in got) acts.add("mark SW[$f] done")
                while (base in ackedSR) base += 1
            }
            acksInflight.clear()
            if (got.isNotEmpty()) caps.add(L.t("收到 ", "got ") + got.joinToString(L.listSep) { "ACK " + lbl(it) })
            if (base != oldBase) {
                if (kind != "sr") acts.add("mark SW[$oldBase..${base - 1}] done")
                if (kind == "stopwait" && running.remove("T$oldBase")) acts.add("timer $S ${q("T" + lbl(oldBase))} stop")
                if (kind == "gbn") {
                    gbnTimer?.let { t ->
                        acts.add("timer $S ${q(t)} stop")
                        running.remove(t)
                        gbnTimer = null
                        if (base < next) {
                            val nt = "T" + lbl(base)
                            acts.add("timer $S ${q(nt)} start")
                            running.add(nt)
                            gbnTimer = nt
                        }
                    }
                }
                if (base < n) {
                    acts.add("slide SW to=$base")
                    caps.add(L.t("窗口滑到 ", "window slides to ") + (if (w == 1) lbl(base) else lbl(base) + "~" + lbl(minOf(base + w, n) - 1)))
                } else {
                    caps.add(L.t("全部帧已确认", "every frame is acknowledged"))
                }
            }
            // 超时
            val overdue = (base until next).filter { f ->
                !(kind == "sr" && f in ackedSR) && (sentRound[f]?.let { it + timeout <= round } ?: false)
            }
            var toSend: List<Int> = emptyList()
            if (overdue.isNotEmpty()) {
                if (kind == "gbn") {
                    val t = gbnTimer ?: ("T" + lbl(base))
                    acts.add("timer $S ${q(t)} expire")
                    running.remove(t)
                    gbnTimer = null
                    toSend = (base until next).toList()
                    acts.add("mark SW[$base..${next - 1}] active")
                    caps.add(L.t("帧 ${lbl(overdue[0])} 超时：从 ${lbl(base)} 起全部重发", "frame ${lbl(overdue[0])} times out: resend everything from ${lbl(base)}"))
                } else {
                    for (f in overdue) {
                        acts.add("timer $S ${q("T" + lbl(f))} expire")
                        running.remove("T$f")
                        acts.add("mark SW[$f] active")
                    }
                    toSend = overdue
                    caps.add(L.t("帧 ${L.list(overdue, ::lbl)} 超时，只重发它", "frame ${L.list(overdue, ::lbl)} times out: resend only that")
                        + (if (overdue.size > 1) L.t("们", "") else ""))
                }
            }
            val fresh = ArrayList<Int>()
            while (next < base + w && next < n) {
                fresh.add(next)
                next += 1
            }
            if (fresh.isNotEmpty()) caps.add(L.t("发出 ", "send ") + L.list(fresh, ::lbl))
            for (f in toSend + fresh) {
                val lost = lose.remove(f)
                acts.add("send $S -> $R ${q(lbl(f))}" + if (lost) " lost" else "")
                sentRound[f] = round
                inflight.add(f to lost)
                if (kind == "gbn") {
                    if (gbnTimer == null) {
                        val t = "T" + lbl(base)
                        acts.add("timer $S ${q(t)} start")
                        running.add(t)
                        gbnTimer = t
                    }
                } else {
                    acts.add("timer $S ${q("T" + lbl(f))} start")
                    running.add("T$f")
                }
            }
            if (acts.isNotEmpty()) lines += step(caps.joinToString(L.sep), acts)

            // —— 接收方 ——
            val racts = ArrayList<String>()
            val rcaps = ArrayList<String>()
            val acks = ArrayList<Int>()
            val delivered = ArrayList<Int>()
            val discarded = ArrayList<Int>()
            val buffered = ArrayList<Int>()
            val duplicate = ArrayList<Int>()
            for ((f, lost) in inflight) {
                if (lost) continue
                if (kind == "sr") {
                    if (f >= rcvBase && f < rcvBase + w) {
                        if (f !in received) {
                            received.add(f)
                            if (f != rcvBase) {
                                racts.add("buffer $R ${q(lbl(f))}")
                                racts.add("mark RW[$f] active")
                                buffered.add(f)
                            }
                        } else {
                            duplicate.add(f)
                            racts.add("note $R ${q(L.t("重复 ", "dup ") + lbl(f))} state=muted")
                        }
                        val run = ArrayList<Int>()
                        while (rcvBase in received) { run.add(rcvBase); rcvBase += 1 }
                        if (run.isNotEmpty()) {
                            racts.add("deliver $R ${q(run.joinToString(" ") { lbl(it) })}")
                            racts.add("unmark RW[${run.first()}..${run.last()}]")
                            if (rcvBase < n) racts.add("slide RW to=$rcvBase")
                            delivered += run
                        }
                    } else if (f < rcvBase) {
                        duplicate.add(f)
                        racts.add("note $R ${q(L.t("重复 ", "dup ") + lbl(f))} state=muted")
                    }
                    acks.add(f)
                } else {
                    if (f == expected) {
                        racts.add("deliver $R ${q(lbl(f))}")
                        delivered.add(f)
                        expected += 1
                        acks.add(f)
                    } else {
                        racts.add("discard $R ${q(lbl(f))}")
                        if (f < expected) duplicate.add(f) else discarded.add(f)
                        if (expected > 0) acks.add(expected - 1)
                    }
                }
            }
            if (kind != "sr" && delivered.isNotEmpty()) racts.add("set $expectedName = ${lbl(expected)}")
            inflight.clear()
            for (ackN in acks) {
                val lost = loseAck.remove(ackN)
                racts.add("send $R -> $S ${q("ACK " + lbl(ackN))}" + if (lost) " lost" else "")
                acksInflight.add(ackN to lost)
            }
            if (delivered.isNotEmpty()) rcaps.add(L.t("交付 ", "deliver ") + L.list(delivered, ::lbl))
            if (buffered.isNotEmpty()) rcaps.add(L.t("缓存 ", "buffer ") + L.list(buffered, ::lbl))
            if (discarded.isNotEmpty()) rcaps.add(L.t("${L.list(discarded, ::lbl)} 失序，丢弃", "${L.list(discarded, ::lbl)} out of order, discarded"))
            if (duplicate.isNotEmpty()) rcaps.add(L.t("${L.list(duplicate, ::lbl)} 是重复帧", "${L.list(duplicate, ::lbl)} is a duplicate"))
            if (acks.isNotEmpty()) {
                rcaps.add(L.t("回 ", "reply ") + acks.joinToString(L.listSep) { "ACK " + lbl(it) }
                    + if (kind == "gbn" && acks.toSet().size < acks.size) L.t("（重复确认）", " (duplicate ACKs)") else "")
            }
            if (racts.isNotEmpty()) lines += step(L.t("接收方：", "Receiver: ") + rcaps.joinToString(L.sep), racts)

            round += 1
            if (base >= n && acksInflight.isEmpty() && inflight.isEmpty()) break
        }
        if (base < n) throw AnimParseError("did not finish in 40 rounds")
        val title = when (kind) {
            "stopwait" -> L.t("停等协议", "Stop-and-wait")
            "gbn" -> L.t("回退 N 步（窗口 $w）", "Go-back-N (window $w)")
            else -> L.t("选择重传（窗口 $w）", "Selective repeat (window $w)")
        }
        return Kit(title, lines)
    }

    // —— 排序 ——

    private fun sortKit(kind: String, a: Args, L: Words): Kit {
        val raw = a.string("a") ?: throw AnimParseError("usage: kit $kind A=[5, 1, 4, 2]")
        a.checkUnused(listOf("A", "lang"))
        val v = AnimText.parseList(raw).toMutableList()
        if (v.size < 2 || v.size > 8) throw AnimParseError("sort 2 to 8 values")
        val numeric = v.all { it.toDoubleOrNull() != null }
        fun gt(x: String, y: String): Boolean = if (numeric) x.toDouble() > y.toDouble() else x > y
        val n = v.size
        val lines = mutableListOf("array A = [${v.joinToString(", ")}]")
        fun swapIn(i: Int, j: Int) { val t = v[i]; v[i] = v[j]; v[j] = t }
        val title: String

        when (kind) {
            "bubble" -> {
                title = L.t("冒泡排序", "Bubble sort")
                lines.add("pointer j -> A[0]")
                var p = 0
                while (p < n - 1) {
                    var swapped = false
                    for (j in 0 until n - 1 - p) {
                        val acts = if (j > 0) mutableListOf("unmark A[${j - 1}]") else mutableListOf()
                        acts += listOf("mark A[$j..${j + 1}] active", "move j -> A[$j]")
                        val cap: String
                        if (gt(v[j], v[j + 1])) {
                            cap = L.t("${v[j]} > ${v[j + 1]}，交换", "${v[j]} > ${v[j + 1]}: swap")
                            acts.add("swap A[$j] A[${j + 1}]")
                            swapIn(j, j + 1)
                            swapped = true
                        } else {
                            cap = L.t("${v[j]} ≤ ${v[j + 1]}，不动", "${v[j]} ≤ ${v[j + 1]}: keep")
                        }
                        if (j == n - 2 - p) {
                            acts += listOf("unmark A[$j]", "mark A[${j + 1}] done")
                            lines += step(L.t("第 ${p + 1} 趟：", "Pass ${p + 1}: ") + cap + L.sep + L.t("${v[j + 1]} 到达最终位置", "${v[j + 1]} is in place"), acts)
                        } else {
                            lines += step(L.t("第 ${p + 1} 趟：", "Pass ${p + 1}: ") + cap, acts)
                        }
                    }
                    p += 1
                    if (!swapped) {
                        lines += step(L.t("这一趟没有交换，已经有序", "No swaps in this pass: sorted"), listOf("mark A done", "move j -> null"))
                        break
                    }
                }
                if (p == n - 1) lines += step(L.t("排序完成", "Sorted"), listOf("mark A done", "move j -> null"))
            }
            "selection" -> {
                title = L.t("简单选择排序", "Selection sort")
                lines += listOf("pointer i -> A[0]", "pointer j", "pointer min -> A[0]")
                for (i in 0 until n - 1) {
                    var m = i
                    lines += step(L.t("第 ${i + 1} 轮：从 A[$i] 起找最小值，先记 min = ${v[i]}", "Round ${i + 1}: find the minimum from A[$i]; min = ${v[i]} so far"),
                        listOf("move i -> A[$i]", "move min -> A[$i]", "mark A[$i] active"))
                    for (j in i + 1 until n) {
                        val acts = mutableListOf("move j -> A[$j]")
                        val cap: String
                        if (gt(v[m], v[j])) {
                            cap = L.t("${v[j]} < ${v[m]}，min 指向它", "${v[j]} < ${v[m]}: it becomes min")
                            acts += listOf("unmark A[$m]", "mark A[$j] active", "move min -> A[$j]")
                            m = j
                        } else {
                            cap = L.t("${v[j]} ≥ ${v[m]}，min 不变", "${v[j]} ≥ ${v[m]}: min stays")
                        }
                        lines += step(cap, acts)
                    }
                    val acts = mutableListOf("move j -> null", "unmark A[$m]")
                    if (m != i) { acts.add("swap A[$i] A[$m]"); swapIn(i, m) }
                    acts += listOf("mark A[$i] done")
                    lines += step(if (m != i) L.t("交换 A[$i] 和 A[$m]，${v[i]} 就位", "swap A[$i] and A[$m]: ${v[i]} is in place")
                    else L.t("最小值就在 A[$i]，不用交换", "the minimum is already at A[$i]"), acts)
                }
                lines += step(L.t("排序完成", "Sorted"), listOf("mark A done", "move i -> null", "move min -> null"))
            }
            "insertion" -> {
                title = L.t("直接插入排序", "Insertion sort")
                lines += listOf("pointer key")
                for (i in 1 until n) {
                    val key = v[i]
                    lines += step(L.t("取出 A[$i] = $key，往前找位置", "take A[$i] = $key and look for its place"),
                        listOf("move key -> A[$i]", "mark A[$i] active"))
                    var j = i - 1
                    while (j >= 0 && gt(v[j], key)) {
                        lines += step(L.t("${v[j]} > $key，${v[j]} 后移", "${v[j]} > $key: shift ${v[j]} right"),
                            listOf("swap A[$j] A[${j + 1}]", "move key -> A[$j]"))
                        swapIn(j, j + 1)
                        j -= 1
                    }
                    lines += step(if (j >= 0) L.t("${v[j]} ≤ $key，$key 插在这里；前 ${i + 1} 个有序", "${v[j]} ≤ $key: $key goes here; the first ${i + 1} are sorted")
                    else L.t("到头了，$key 放在最前；前 ${i + 1} 个有序", "reached the front: $key goes first; the first ${i + 1} are sorted"),
                        listOf("unmark A[${j + 1}]"))
                }
                lines += step(L.t("排序完成", "Sorted"), listOf("mark A done", "move key -> null"))
            }
            else -> {   // quick（Lomuto 划分：枢轴取最后一个）
                title = L.t("快速排序（枢轴取末尾）", "Quicksort (last element as pivot)")
                lines += listOf("pointer i", "pointer j")
                val stack = mutableListOf(0 to n - 1)
                while (stack.isNotEmpty()) {
                    val (lo, hi) = stack.removeAt(stack.size - 1)
                    if (lo >= hi) {
                        if (lo == hi) lines += step(L.t("A[$lo] 只有一个元素，已就位", "A[$lo] alone: in place"), listOf("mark A[$lo] done"))
                        continue
                    }
                    val pivot = v[hi]
                    var i = lo - 1
                    lines += step(L.t("划分 A[$lo..$hi]，枢轴 = $pivot", "partition A[$lo..$hi] around pivot $pivot"),
                        listOf("mark A[$hi] active", "move i -> null", "move j -> A[$lo]"))
                    for (j in lo until hi) {
                        val acts = mutableListOf("move j -> A[$j]")
                        val cap: String
                        if (gt(pivot, v[j])) {
                            i += 1
                            acts.add("move i -> A[$i]")
                            if (i != j) { acts.add("swap A[$i] A[$j]"); swapIn(i, j) }
                            cap = L.t("${v[i]} < $pivot：i 前进${if (i != j) "，交换 A[$i] 和 A[$j]" else ""}", "${v[i]} < $pivot: advance i${if (i != j) ", swap A[$i] and A[$j]" else ""}")
                        } else {
                            cap = L.t("${v[j]} ≥ $pivot：跳过", "${v[j]} ≥ $pivot: skip")
                        }
                        lines += step(cap, acts)
                    }
                    val p = i + 1
                    val acts = mutableListOf("move j -> null", "move i -> null")
                    if (p != hi) { acts.add("swap A[$p] A[$hi]"); swapIn(p, hi) }
                    acts.add("mark A[$p] done")
                    lines += step(L.t("枢轴 $pivot 放到 A[$p]，左边都比它小、右边都不小", "pivot $pivot goes to A[$p]: smaller on the left, the rest on the right"), acts)
                    stack.add(p + 1 to hi)
                    stack.add(lo to p - 1)
                }
                lines += step(L.t("排序完成", "Sorted"), listOf("mark A done"))
            }
        }
        return Kit(title, lines)
    }

    // —— CRC（竖式模 2 除，排法同教材）——

    private fun crcKit(a: Args, L: Words): Kit {
        val data = a.string("data")
        val gen = a.string("gen")
        if (data == null || gen == null) throw AnimParseError("usage: kit crc data=101001 gen=1101")
        a.checkUnused(listOf("data", "gen", "lang"))
        if (data.isEmpty() || !data.all { it == '0' || it == '1' } || data.length > 16) throw AnimParseError("data= is 1 to 16 bits of 0 / 1")
        if (gen.length < 2 || gen.length > 8 || !gen.all { it == '0' || it == '1' } || gen.first() != '1') {
            throw AnimParseError("gen= is 2 to 8 bits starting with 1 (the generator polynomial)")
        }
        val n = data.length
        val r = gen.length - 1
        val g = gen.map { if (it == '1') 1 else 0 }
        val m = (data.map { if (it == '1') 1 else 0 } + List(r) { 0 }).toMutableList()
        val S = L.t("竖式", "work")
        val d0 = r + 2
        val zeros = "0".repeat(r)
        fun bits(from: Int, to: Int): String = (from..to).joinToString("") { m[it].toString() }
        val lines = mutableListOf(
            "sheet $S",
            "write $S[1,0] ${q(gen)}",
            "write $S[1,${r + 1}] \")\"",
            "write $S[1,$d0] ${q(data + zeros)}",
            "rule $S[1,$d0..${d0 + n + r - 1}] above",
            "mark $S[1,${d0 + n}..${d0 + n + r - 1}] new",
            "label $S[0] ${q(L.t("← 商 Q", "← quotient Q"))}",
            "label $S[1] ${q(L.t("← 被除数：M 后补 $r 个 0", "← dividend: M followed by $r zeros"))}",
            "text ${q(L.t("除数 $gen 写在左边，从被除数最高位起，每次对齐除数做一次模 2 减（异或）", "Divisor $gen on the left; from the leftmost bit, align it and subtract modulo 2 (XOR) each time"))}",
        )
        val poly = polynomial(gen)
        lines += step(L.t("G = $gen 对应多项式 $poly，最高次幂是 $r，所以 r = $r：校验码 R 有 $r 位（= G 的位数 ${gen.length} − 1）",
            "G = $gen is the polynomial $poly; its highest power is $r, so r = $r: the check bits R are $r bits (= ${gen.length} bits of G − 1)"),
            listOf("mark $S[1,0..$r] active",
                "say " + q(L.t("从左到右每一位对应 x 的 $r 次、${r - 1} 次……0 次，是 1 的位就有这一项", "From left to right the bits stand for the powers $r, ${r - 1}, …, 0 of x; a 1 means that term is present"))))
        val twoR = "2" + r.toString().map { SUPERSCRIPT.getValue(it) }.joinToString("")
        lines += step(L.t("在 M = $data 后补 r = $r 个 0（相当于 M × $twoR），得到被除数 ${data + zeros}", "append r = $r zeros to M = $data (that is M × $twoR): the dividend is ${data + zeros}"),
            listOf("unmark $S[1,0..$r]", "mark $S[1,${d0 + n}..${d0 + n + r - 1}] active",
                "say " + q(L.t("补出来的 $r 位留给余数：最后把余数填进这 $r 位，整个帧就能被 G 整除", "The $r new bits are room for the remainder: filling them with it makes the whole frame divisible by G"))))
        var prevWindow: Pair<Int, Int>? = null
        var firstStep = true
        for (k in 0 until n) {
            val windowRow = if (k == 0) 1 else 1 + 2 * k
            val col = d0 + k
            val window = bits(k, k + r)
            val qbit = m[k]
            val acts = ArrayList<String>()
            if (firstStep) {
                acts.add("unmark $S[1,${d0 + n}..${d0 + n + r - 1}]")
                firstStep = false
            } else prevWindow?.let { (pr, pc) -> acts.add("unmark $S[$pr,$pc..${pc + r}]") }
            acts.add("mark $S[$windowRow,$col..${col + r}] active")
            acts.add("write $S[0,${col + r}] ${q(qbit.toString())}")
            acts.add("write $S[${2 + 2 * k},$col] ${q(if (qbit == 1) gen else "0$zeros")}")
            acts.add("rule $S[${2 + 2 * k},$col..${col + r}]")
            val sub = if (qbit == 1) g else List(r + 1) { 0 }
            val before = (k..k + r).map { m[it] }
            if (qbit == 1) for (i in 0..r) m[k + i] = m[k + i] xor g[i]
            val after = bits(k, k + r)
            acts.add("write $S[${3 + 2 * k},${col + 1}] ${q(after.drop(1))}")
            val perBit = before.zip(sub).joinToString("  ") { (x, y) -> "$x⊕$y=${x xor y}" }
            acts.add("say " + q(L.t("逐位异或（相同得 0、不同得 1）：", "bit by bit (same → 0, different → 1): ") + perBit
                + L.t("。首位一定变成 0，所以去掉它，只留后 $r 位", ". The leading bit always becomes 0, so drop it and keep the last $r")))
            var cap = if (qbit == 1)
                L.t("取 ${r + 1} 位 $window，首位是 1，够除：商上 1，减（异或）$gen，得 $after",
                    "take ${r + 1} bits $window: leading 1, so G fits: quotient bit 1, subtract (XOR) $gen → $after")
            else
                L.t("取 ${r + 1} 位 $window，首位是 0，不够除：商上 0，减（异或）0$zeros，不变",
                    "take ${r + 1} bits $window: leading 0, so G does not fit: quotient bit 0, XOR 0$zeros changes nothing")
            if (k < n - 1) {
                val down = m[k + r + 1]
                acts.add("arrow $S[1..${3 + 2 * k},${col + r + 1}]")
                acts.add("write $S[${3 + 2 * k},${col + r + 1}] ${q(down.toString())}")
                cap += L.t("；落下被除数的下一位 $down，凑够 ${r + 1} 位", "; bring down the next dividend bit $down to make ${r + 1} bits again")
            }
            lines += step(L.t("第 ${k + 1} 次：", "Step ${k + 1}: ") + cap, acts)
            prevWindow = windowRow to col
        }
        val rem = bits(n, m.size - 1)
        val remRow = 3 + 2 * (n - 1)
        val (pr, pc) = prevWindow!!
        lines += step(L.t("被除数的每一位都用完了，最后剩下的 $r 位就是余数 R = $rem（不足 $r 位也要补齐）；把它接在 M 后面，发送 $data$rem",
            "every dividend bit is used: the last $r bits are the remainder R = $rem (always keep $r bits); append it to M and send $data$rem"),
            listOf("unmark $S[$pr,$pc..${pc + r}]",
                "mark $S[$remRow,${d0 + n}..${d0 + n + r - 1}] done",
                "label $S[$remRow] ${q(L.t("← 余数 R，作为 FCS", "← remainder R, the FCS"))}",
                "say " + q(L.t("接收方用收到的整个帧除以 $gen，余数为 0 就认为没有出错", "The receiver divides the whole frame by $gen; remainder 0 means no error detected"))))
        return Kit(L.t("CRC 循环冗余检验", "CRC") + "：$data / $gen", lines)
    }

    private val SUPERSCRIPT = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')

    /** 1101 → x³ + x² + 1。 */
    fun polynomial(gen: String): String =
        gen.withIndex().mapNotNull { (i, c) ->
            if (c != '1') null else {
                val p = gen.length - 1 - i
                if (p == 0) "1" else if (p == 1) "x" else "x" + p.toString().map { SUPERSCRIPT.getValue(it) }.joinToString("")
            }
        }.joinToString(" + ")

    // —— 树的遍历 ——

    private fun traverseKit(kind: String, a: Args, L: Words): Kit {
        val raw = a.string("t") ?: throw AnimParseError("usage: kit $kind T=(8 (3 1 6) (10 _ 14))")
        a.checkUnused(listOf("T", "lang"))
        val tree = AnimText.parseTree(raw) ?: throw AnimParseError("the tree is empty")
        val values = ArrayList<String>()
        fun collect(d: AnimTreeDecl?) {
            if (d == null) return
            values.add(d.value); d.children.forEach { collect(it) }
        }
        collect(tree)
        if (values.toSet().size != values.size) throw AnimParseError("node values must be different (nodes are found by value)")
        val order = ArrayList<String>()
        fun visit(d: AnimTreeDecl?) {
            if (d == null) return
            when (kind) {
                "preorder" -> { order.add(d.value); d.children.forEach { visit(it) } }
                "postorder" -> { d.children.forEach { visit(it) }; order.add(d.value) }
                else -> {
                    if (d.children.size > 2) throw AnimParseError("in-order needs a binary tree")
                    visit(d.children.firstOrNull()); order.add(d.value); visit(if (d.children.size > 1) d.children[1] else null)
                }
            }
        }
        if (kind == "levelorder") {
            val queue = ArrayDeque<AnimTreeDecl>().apply { add(tree) }
            while (queue.isNotEmpty()) {
                val d = queue.removeFirst()
                order.add(d.value)
                d.children.filterNotNull().forEach { queue.add(it) }
            }
        } else {
            visit(tree)
        }
        val out = L.t("序列", "output")
        val lines = mutableListOf("tree T = $raw", "queue $out = []", "pointer p")
        val rule = when (kind) {
            "preorder" -> L.t("先序：根、左、右", "pre-order: root, left, right")
            "inorder" -> L.t("中序：左、根、右", "in-order: left, root, right")
            "postorder" -> L.t("后序：左、右、根", "post-order: left, right, root")
            else -> L.t("层序：一层一层从左到右", "level order: level by level, left to right")
        }
        var prev: String? = null
        for ((k, v) in order.withIndex()) {
            val acts = mutableListOf("move p -> T[$v]", "mark T[$v] active", "enqueue $out $v")
            prev?.let { acts.add(0, "mark T[$it] done") }
            lines += step(if (k == 0) rule + L.sep + L.t("访问 $v", "visit $v") else L.t("访问 $v", "visit $v"), acts)
            prev = v
        }
        lines += step(L.t("遍历结束：", "Done: ") + order.joinToString(" "), listOf("mark T[$prev] done", "move p -> null"))
        val title = when (kind) {
            "preorder" -> L.t("先序遍历", "Pre-order traversal")
            "inorder" -> L.t("中序遍历", "In-order traversal")
            "postorder" -> L.t("后序遍历", "Post-order traversal")
            else -> L.t("层序遍历", "Level-order traversal")
        }
        return Kit(title, lines)
    }

    // —— 图：BFS / DFS / Dijkstra ——

    private class GraphArg(val spec: String, val nodes: List<String>, val edges: List<AnimGraphEdgeDecl>, val from: String)

    private fun graphArg(a: Args, kind: String): GraphArg {
        val spec = a.string("g") ?: throw AnimParseError("usage: kit $kind G=\"A-B, A-C, B-D\" from=A")
        val (nodes, edges) = AnimText.parseGraph(spec)
        if (nodes.size > 16) throw AnimParseError("graph: at most 16 nodes")
        val from = a.string("from") ?: nodes[0]
        if (from !in nodes) throw AnimParseError("from=$from: no such node")
        return GraphArg(spec, nodes, edges, from)
    }

    private class Neighbor(val v: String, val key: String, val w: String?)

    /** 邻居：按边的声明顺序；无向边两头都算，有向边只算出边。 */
    private fun neighbors(u: String, edges: List<AnimGraphEdgeDecl>): List<Neighbor> = edges.mapNotNull { e ->
        when {
            e.a == u -> Neighbor(e.b, if (e.directed) "${e.a}->${e.b}" else "${e.a}-${e.b}", e.weight)
            e.b == u && !e.directed -> Neighbor(e.a, "${e.a}-${e.b}", e.weight)
            else -> null
        }
    }

    private fun searchKit(kind: String, a: Args, L: Words): Kit {
        val g = graphArg(a, kind)
        a.checkUnused(listOf("G", "from", "lang"))
        val out = L.t("访问序列", "order")
        val lines = mutableListOf("graph G = ${g.spec}", "queue $out = []")
        if (kind == "bfs") {
            lines.add("queue Q = []")
            val seen = hashSetOf(g.from)
            val queue = ArrayDeque(listOf(g.from))
            val order = ArrayList<String>()
            lines += step(L.t("从 ${g.from} 出发：${g.from} 入队", "start at ${g.from}: enqueue it"), listOf("mark G[${g.from}] active", "enqueue Q ${g.from}"))
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                order.add(u)
                val acts = mutableListOf("dequeue Q", "mark G[$u] done", "enqueue $out $u")
                val added = ArrayList<String>()
                for (nb in neighbors(u, g.edges)) {
                    if (nb.v in seen) continue
                    seen.add(nb.v); queue.add(nb.v); added.add(nb.v)
                    acts += listOf("mark G[${nb.key}] active", "mark G[${nb.v}] active", "enqueue Q ${nb.v}")
                }
                lines += step(L.t("$u 出队并访问", "dequeue and visit $u") + L.sep
                    + (if (added.isEmpty()) L.t("没有新的邻居", "no new neighbors") else L.t("邻居 ${added.joinToString(L.listSep)} 入队", "enqueue neighbors ${added.joinToString(L.listSep)}")), acts)
            }
            lines += step(L.t("队列空了，遍历结束：", "queue empty, done: ") + order.joinToString(" "), emptyList())
            return Kit(L.t("广度优先搜索（从 ${g.from} 出发）", "Breadth-first search from ${g.from}"), lines)
        }
        lines.add("stack S = []")
        val visited = HashSet<String>()
        val order = ArrayList<String>()
        fun dfs(u: String, via: String?) {
            visited.add(u)
            order.add(u)
            val acts = mutableListOf("push S $u", "mark G[$u] active", "enqueue $out $u")
            via?.let { acts.add(0, "mark G[$it] active") }
            lines += step(L.t("访问 $u", "visit $u"), acts)
            for (nb in neighbors(u, g.edges)) if (nb.v !in visited) dfs(nb.v, nb.key)
            lines += step(L.t("$u 的邻居都访问过了，回退", "all neighbors of $u visited: back up"), listOf("pop S", "mark G[$u] done"))
        }
        dfs(g.from, null)
        lines += step(L.t("遍历结束：", "done: ") + order.joinToString(" "), emptyList())
        return Kit(L.t("深度优先搜索（从 ${g.from} 出发）", "Depth-first search from ${g.from}"), lines)
    }

    /** Swift `x == x.rounded() ? String(Int(x)) : String(x)`。 */
    private fun fmt(x: Double): String = if (!x.isInfinite() && x == kotlin.math.floor(x)) x.toLong().toString() else x.toString()

    private fun dijkstraKit(a: Args, L: Words): Kit {
        val g = graphArg(a, "dijkstra")
        a.checkUnused(listOf("G", "from", "lang"))
        for (e in g.edges) {
            val x = e.weight?.toDoubleOrNull()
            if (x == null || x < 0) throw AnimParseError("every edge needs a weight ≥ 0, like A-B:4 (${e.a}-${e.b})")
        }
        val dist = hashMapOf(g.from to 0.0)
        val prev = HashMap<String, String>()
        val done = HashSet<String>()
        val lines = mutableListOf("graph G = ${g.spec}")
        val acts = g.nodes.map { "set G[$it] = " + q(if (it == g.from) "0" else "∞") }.toMutableList()
        acts.add("mark G[${g.from}] active")
        lines += step(L.t("起点 ${g.from} 的距离为 0，其余为 ∞", "distance 0 at ${g.from}, ∞ elsewhere"), acts)
        while (true) {
            val u = g.nodes.filter { it !in done && dist[it] != null }.minByOrNull { dist.getValue(it) } ?: break
            done.add(u)
            val sel = mutableListOf("mark G[$u] done")
            prev[u]?.let { sel.add("mark G[$it] done") }
            lines += step(L.t("未确定的点里 $u 最近（${fmt(dist.getValue(u))}），确定它", "$u is the closest unfinished node (${fmt(dist.getValue(u))}): finalize it"), sel)
            val relax = ArrayList<String>()
            val caps = ArrayList<String>()
            for (nb in neighbors(u, g.edges)) {
                if (nb.v in done) continue
                val nd = dist.getValue(u) + nb.w!!.toDouble()
                val old = dist[nb.v]
                if (old == null || nd < old) {
                    prev[nb.v]?.let { relax.add("unmark G[$it]") }
                    caps.add("${nb.v}：${old?.let { fmt(it) } ?: "∞"} → ${fmt(nd)}")
                    dist[nb.v] = nd
                    prev[nb.v] = nb.key
                    relax += listOf("mark G[${nb.key}] active", "set G[${nb.v}] = " + q(fmt(nd)), "mark G[${nb.v}] active")
                }
            }
            if (relax.isNotEmpty()) lines += step(L.t("经 $u 更新：", "relax through $u: ") + caps.joinToString(L.listSep), relax)
        }
        val unreachable = g.nodes.filter { dist[it] == null }
        lines += step(L.t("全部确定。", "all finalized. ") + (if (unreachable.isEmpty()) "" else L.t("${unreachable.joinToString(L.listSep)} 不可达", "unreachable: ${unreachable.joinToString(", ")}")),
            emptyList())
        return Kit(L.t("Dijkstra 最短路径（从 ${g.from} 出发）", "Dijkstra shortest paths from ${g.from}"), lines)
    }
}
