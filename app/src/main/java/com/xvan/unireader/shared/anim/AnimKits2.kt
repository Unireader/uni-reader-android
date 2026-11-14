package com.xvan.unireader.shared.anim

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/*
 * 套件第二批（408 四门课）：page / disk / cache / base / kmp / hash / heap / bsearch / tcp。
 * 逐行照抄 Mac `Sources/Anim/AnimKits2.swift`——生成的每一行文字、说明的中英两份都要逐字相同（`AnimVectorsTest`）。
 */
internal object AnimKits2 {
    val NAMES = listOf("page", "disk", "cache", "base", "kmp", "hash", "heap", "bsearch", "tcp")

    fun generate(name: String, a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit? = when (name) {
        "page" -> pageKit(a, L)
        "disk" -> diskKit(a, L)
        "cache" -> cacheKit(a, L)
        "base" -> baseKit(a, L)
        "kmp" -> kmpKit(a, L)
        "hash" -> hashKit(a, L)
        "heap" -> heapKit(a, L)
        "bsearch" -> bsearchKit(a, L)
        "tcp" -> tcpKit(a, L)
        else -> null
    }

    // —— 公用 ——

    private fun intList(a: AnimKits.Args, k: String, usage: String): List<Int> {
        val s = a.string(k) ?: throw AnimParseError(usage)
        val out = ArrayList<Int>()
        for (part in s.trim('[', ']').split(",")) {
            val t = part.trimWs()
            if (t.isEmpty()) continue
            out.add(t.toIntOrNull() ?: throw AnimParseError("$k= takes whole numbers like 7,0,1"))
        }
        if (out.isEmpty()) throw AnimParseError(usage)
        return out
    }

    private fun choice(a: AnimKits.Args, k: String, options: List<String>, d: String): String {
        val v = a.string(k)?.lowercase() ?: return d
        if (v !in options) throw AnimParseError("$k= takes ${options.joinToString(" / ")}")
        return v
    }

    /** 两位小数、去掉多余的 0（同 Swift `num`；四舍五入按「0.5 远离 0」，别用 Kotlin 的 round——那是银行家舍入）。 */
    fun num(x: Double): String {
        val r = floor(x * 100 + 0.5) / 100
        if (r == floor(r)) return r.toLong().toString()
        var s = String.format(Locale.ROOT, "%.2f", r)
        while (s.endsWith("0")) s = s.dropLast(1)
        return s
    }

    private fun s(caption: String, actions: List<String>): List<String> =
        listOf("step " + caption.replace("#", "＃")) + actions.map { "  $it" }

    private fun q(s: String) = AnimKits.q(s)

    // —— 页面置换 ——

    private fun pageKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val alg = choice(a, "alg", listOf("fifo", "lru", "opt", "clock"), "lru")
        val refs = intList(a, "refs", "usage: kit page alg=lru refs=7,0,1,2,0,3 frames=3")
        val f = a.int("frames", 3, 1..6)
        a.checkUnused(listOf("alg", "refs", "frames", "lang"))
        if (refs.size > 20) throw AnimParseError("refs: at most 20 accesses")
        val n = refs.size
        val rows = (1..f).map { L.t("块$it", "F$it") } + listOf(L.t("缺页", "fault"))
        val order = L.t(if (alg == "lru") "使用次序(旧→新)" else "调入次序(旧→新)", if (alg == "lru") "use(old→new)" else "loaded(old→new)")
        val faultsName = L.t("缺页次数", "faults")
        val handName = L.t("指针", "hand")
        val lines = mutableListOf("grid P rows=[${rows.joinToString(", ")}] cols=[${refs.joinToString(", ")}] = ${f + 1}x$n", "var $faultsName = 0")
        if (alg == "fifo" || alg == "lru") lines.add("var $order = -")
        if (alg == "clock") {
            lines.add("pointer $handName")
            lines.add("text " + q(L.t("格子里是「页号(访问位)」；指针指着下一次从哪一块开始找", "Cells show page(use bit); the hand marks where the next search starts")))
        }
        val frames = arrayOfNulls<Int>(f)
        val use = IntArray(f)
        val loaded = ArrayList<Int>()
        var hand = 0
        var faults = 0
        val rule = when (alg) {
            "fifo" -> L.t("FIFO：换出最早调入的页", "FIFO: evict the page loaded earliest")
            "lru" -> L.t("LRU：换出最久没有使用的页", "LRU: evict the page unused for the longest time")
            "opt" -> L.t("OPT：换出以后最晚才用到（或不再用到）的页", "OPT: evict the page used farthest in the future (or never again)")
            else -> L.t("CLOCK：指针转着找访问位为 0 的页，路过的访问位 1 清成 0", "CLOCK: the hand looks for use bit 0, clearing 1s it passes")
        }
        fun cell(i: Int): String {
            val p = frames[i] ?: return ""
            return if (alg == "clock") "$p(${use[i]})" else "$p"
        }
        for ((k, p) in refs.withIndex()) {
            val acts = ArrayList<String>()
            if (k > 0) acts.add("unmark P[0..$f,${k - 1}]")
            var cap = L.t("第 ${k + 1} 次访问 $p：", "access #${k + 1}, page $p: ")
            val hitSlot = frames.indexOfFirst { it == p }
            if (hitSlot >= 0) {
                if (alg == "lru") { loaded.removeAll { it == p }; loaded.add(p) }
                if (alg == "clock") use[hitSlot] = 1
                cap += L.t("已在块${hitSlot + 1}，命中", "already in F${hitSlot + 1}: hit") +
                    (if (alg == "lru") L.t("，它变成最近使用的", "; it becomes the most recent") else "") +
                    (if (alg == "clock") L.t("，访问位置 1", "; set its use bit") else "")
                for (i in 0 until f) if (frames[i] != null) acts.add("set P[$i,$k] = ${cell(i)}")
                acts.add("mark P[$hitSlot,$k] done")
            } else {
                faults += 1
                val slot: Int
                val free = frames.indexOfFirst { it == null }
                if (free >= 0) {
                    slot = free
                    cap += L.t("不在内存，缺页；块${free + 1}空着，直接调入", "not in memory: page fault; F${free + 1} is free, load it")
                    if (alg == "clock") hand = (free + 1) % f
                } else {
                    when (alg) {
                        "fifo", "lru" -> {
                            val victim = loaded[0]
                            slot = frames.indexOfFirst { it == victim }
                            loaded.removeAt(0)
                            cap += if (alg == "fifo") L.t("不在内存，缺页；内存满，换出最早调入的 $victim", "not in memory: fault; memory full, evict $victim, loaded earliest")
                            else L.t("不在内存，缺页；内存满，换出最久没用的 $victim", "not in memory: fault; memory full, evict $victim, unused the longest")
                        }
                        "opt" -> {
                            val notes = ArrayList<String>()
                            var best = 0
                            var bestNext = -1
                            for (i in 0 until f) {
                                val page = frames[i]!!
                                val nextUse = (k + 1 until n).firstOrNull { refs[it] == page }
                                val dist = nextUse ?: Int.MAX_VALUE
                                notes.add(nextUse?.let { L.t("$page 第 ${it + 1} 次再用", "$page next at #${it + 1}") } ?: L.t("$page 不再用到", "$page never again"))
                                if (dist > bestNext) { bestNext = dist; best = i }
                            }
                            slot = best
                            cap += L.t("不在内存，缺页；往后看：", "not in memory: fault; looking ahead: ") + notes.joinToString(L.listSep) +
                                L.t("，换出 ${frames[best]}", "; evict ${frames[best]}")
                        }
                        else -> {
                            val path = ArrayList<String>()
                            while (use[hand] == 1) {
                                path.add(L.t("块${hand + 1}访问位 1→0", "F${hand + 1} use 1→0"))
                                use[hand] = 0
                                hand = (hand + 1) % f
                            }
                            slot = hand
                            path.add(L.t("块${hand + 1}访问位 0，换出 ${frames[hand]}", "F${hand + 1} use 0: evict ${frames[hand]}"))
                            hand = (hand + 1) % f
                            cap += L.t("不在内存，缺页；指针扫过：", "not in memory: fault; the hand sweeps: ") + path.joinToString(L.listSep)
                        }
                    }
                }
                frames[slot] = p
                use[slot] = 1
                if (alg == "fifo" || alg == "lru") loaded.add(p)
                for (i in 0 until f) if (frames[i] != null) acts.add("set P[$i,$k] = ${cell(i)}")
                acts += listOf("mark P[$slot,$k] active", "set P[$f,$k] = √", "mark P[$f,$k] wrong", "set $faultsName = $faults")
            }
            if (alg == "fifo" || alg == "lru") acts.add("set $order = ${loaded.joinToString(" ")}")
            if (alg == "clock") acts.add("move $handName -> P[$hand,$k]")
            lines += s(if (k == 0) rule + L.sep + cap else cap, acts)
        }
        lines += s(L.t("共 $n 次访问，缺页 $faults 次，缺页率 $faults/$n = ${num(faults * 100.0 / n)}%",
            "$n accesses, $faults page faults: fault rate $faults/$n = ${num(faults * 100.0 / n)}%"),
            listOf("unmark P[0..$f,${n - 1}]") + (if (alg == "clock") listOf("move $handName -> null") else emptyList()))
        val title = when (alg) {
            "fifo" -> L.t("页面置换：FIFO（$f 个内存块）", "Page replacement: FIFO ($f frames)")
            "lru" -> L.t("页面置换：LRU（$f 个内存块）", "Page replacement: LRU ($f frames)")
            "opt" -> L.t("页面置换：OPT 最佳置换（$f 个内存块）", "Page replacement: OPT ($f frames)")
            else -> L.t("页面置换：CLOCK 时钟（$f 个内存块）", "Page replacement: CLOCK ($f frames)")
        }
        return AnimKits.Kit(title, lines)
    }

    // —— 磁盘调度 ——

    private class Visit(val track: Int, val isEnd: Boolean, val why: String)

    private fun diskKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val alg = choice(a, "alg", listOf("fcfs", "sstf", "scan", "cscan", "look", "clook"), "sstf")
        val start = a.int("start", 100, 0..9999)
        val reqs = intList(a, "requests", "usage: kit disk alg=sstf start=100 requests=55,58,39,18,90")
        val up = choice(a, "dir", listOf("up", "down"), "up") == "up"
        val maxTrack = a.int("max", 199, 1..9999)
        a.checkUnused(listOf("alg", "start", "requests", "dir", "max", "lang"))
        if (reqs.size > 16) throw AnimParseError("requests: at most 16")
        if (!reqs.all { it in 0..maxTrack } || start > maxTrack) throw AnimParseError("tracks must be 0…$maxTrack")
        val visits = ArrayList<Visit>()
        val pending = reqs.toMutableList()
        var head = start
        val dirWord = if (up) L.t("磁道号增大", "increasing") else L.t("磁道号减小", "decreasing")
        val upWord = if (up) "up" else "down"
        when (alg) {
            "fcfs" -> for (t in reqs) visits.add(Visit(t, false, L.t("按请求到达的先后", "in arrival order")))
            "sstf" -> while (pending.isNotEmpty()) {
                val best = pending.minWith(compareBy<Int>({ abs(it - head) }, { it }))
                visits.add(Visit(best, false, L.t("离 $head 最近的是 $best", "$best is closest to $head")))
                pending.remove(best); head = best
            }
            else -> {
                val ahead = pending.filter { if (up) it >= start else it <= start }.let { l -> if (up) l.sorted() else l.sortedDescending() }
                val behind = pending.filter { if (up) it < start else it > start }
                for (t in ahead) visits.add(Visit(t, false, L.t("沿${dirWord}方向继续", "keep moving $upWord")))
                val end = if (up) maxTrack else 0
                val other = if (up) 0 else maxTrack
                val turned = if (up) behind.sortedDescending() else behind.sorted()
                val again = if (up) behind.sorted() else behind.sortedDescending()
                when (alg) {
                    "scan" -> {
                        val lastPos = visits.lastOrNull()?.track
                        if (lastPos != end && (lastPos ?: start) != end) {
                            visits.add(Visit(end, true, L.t("SCAN 要走到这一头的尽头 $end 才掉头", "SCAN runs to the end, $end, before turning")))
                        }
                        for (t in turned) visits.add(Visit(t, false, L.t("掉头，沿反方向依次访问", "turn around and serve the rest")))
                    }
                    "look" -> for (t in turned) visits.add(Visit(t, false, L.t("这一头没有请求了，LOOK 直接掉头", "no more requests this way: LOOK turns around")))
                    "cscan" -> if (behind.isNotEmpty()) {
                        if (visits.lastOrNull()?.track != end) visits.add(Visit(end, true, L.t("C-SCAN 先走到尽头 $end", "C-SCAN runs to the end, $end")))
                        visits.add(Visit(other, true, L.t("立刻返回另一头 $other，返回途中不服务（这段移动也计入）", "jump back to $other without serving (counted in the distance)")))
                        for (t in again) visits.add(Visit(t, false, L.t("从头再沿${dirWord}方向访问", "serve again moving $upWord")))
                    }
                    else -> for ((i, t) in again.withIndex()) {
                        visits.add(Visit(t, false, if (i == 0) L.t("C-LOOK 直接跳到另一头最远的请求 $t（这段移动也计入）", "C-LOOK jumps to the farthest request, $t (counted)")
                        else L.t("沿${dirWord}方向继续", "keep moving $upWord")))
                    }
                }
            }
        }
        val tracks = (reqs + listOf(start) + visits.map { it.track }).toSortedSet().toList()
        val name = L.t("磁道", "track")
        val headName = L.t("磁头", "head")
        val distName = L.t("移动距离", "distance")
        val seqName = L.t("访问序列", "order")
        val lines = mutableListOf("array $name = [${tracks.joinToString(", ")}]", "pointer $headName -> $name[${tracks.indexOf(start)}]",
            "var $distName = 0", "queue $seqName = []")
        val rule = when (alg) {
            "fcfs" -> L.t("FCFS 先来先服务", "FCFS: first come, first served")
            "sstf" -> L.t("SSTF 最短寻找时间优先：每次去最近的请求", "SSTF: always the closest request")
            "scan" -> L.t("SCAN 电梯：沿${dirWord}方向走到尽头再掉头", "SCAN: move $upWord to the end, then turn")
            "look" -> L.t("LOOK：沿${dirWord}方向，到最远的请求就掉头", "LOOK: move $upWord to the last request, then turn")
            "cscan" -> L.t("C-SCAN 循环扫描：只沿${dirWord}方向服务，到尽头后返回另一头", "C-SCAN: serve moving $upWord only, then jump back")
            else -> L.t("C-LOOK：只沿${dirWord}方向服务，到最远的请求后跳回另一头最远的请求", "C-LOOK: serve one way, then jump to the farthest request")
        }
        lines += s(rule + L.sep + L.t("磁头在 $start", "the head is at $start"), listOf("mark $name[${tracks.indexOf(start)}] muted"))
        var pos = start
        var total = 0
        var prevIndex: Int? = null
        for (v in visits) {
            val d = abs(v.track - pos)
            total += d
            val idx = tracks.indexOf(v.track)
            val acts = ArrayList<String>()
            prevIndex?.let { acts.add("unmark $name[$it]") }
            acts += listOf("move $headName -> $name[$idx]", "set $distName = $total")
            if (v.isEnd) {
                acts.add("mark $name[$idx] active")
                prevIndex = idx
            } else {
                acts += listOf("mark $name[$idx] done", "enqueue $seqName ${v.track}")
                prevIndex = null
            }
            lines += s(v.why + L.sep + L.t("$pos → ${v.track}，移动 $d，累计 $total", "$pos → ${v.track}: move $d, total $total"), acts)
            pos = v.track
        }
        lines += s(L.t("总移动 $total 个磁道，平均寻道长度 $total/${reqs.size} = ${num(total.toDouble() / reqs.size)}",
            "total $total tracks; average seek length $total/${reqs.size} = ${num(total.toDouble() / reqs.size)}"),
            prevIndex?.let { listOf("unmark $name[$it]") } ?: emptyList())
        val upper = when (alg) { "fcfs" -> "FCFS"; "sstf" -> "SSTF"; "scan" -> "SCAN"; "look" -> "LOOK"; "cscan" -> "C-SCAN"; else -> "C-LOOK" }
        return AnimKits.Kit(L.t("磁盘调度：$upper（从 $start 开始）", "Disk scheduling: $upper (from $start)"), lines)
    }

    // —— Cache 映射与替换 ——

    private fun cacheKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val map = choice(a, "map", listOf("direct", "full", "set"), "direct")
        val linesN = a.int("lines", 4, 1..16)
        val ways = if (map == "set") a.int("ways", 2, 1..16) else if (map == "full") linesN else 1
        val blocks = intList(a, "blocks", "usage: kit cache map=direct lines=4 blocks=0,4,8,0")
        val rep = choice(a, "replace", listOf("lru", "fifo"), "lru")
        a.checkUnused(listOf("map", "lines", "ways", "blocks", "replace", "lang"))
        if (blocks.size > 20) throw AnimParseError("blocks: at most 20 accesses")
        if (linesN % ways != 0) throw AnimParseError("lines must be a multiple of ways")
        if (!blocks.all { it >= 0 }) throw AnimParseError("block numbers are ≥ 0")
        val sets = linesN / ways
        val rowLabels = (0 until linesN).map { i -> if (map == "set") L.t("${i / ways}组${i % ways}路", "s${i / ways}w${i % ways}") else L.t("行$i", "L$i") }
        val tagName = L.t("标记", "tag")
        val blockName = L.t("块", "block")
        val hitName = L.t("命中", "hits")
        val missName = L.t("缺失", "misses")
        val lines = mutableListOf("grid C rows=[${rowLabels.joinToString(", ")}] cols=[$tagName, $blockName] = ${linesN}x2", "var $hitName = 0", "var $missName = 0")
        val tags = arrayOfNulls<Int>(linesN)
        val stamp = IntArray(linesN)
        var hits = 0
        var misses = 0
        val rule = when (map) {
            "direct" -> L.t("直接映射：块 b 只能放第 b mod $linesN 行，标记 = b / $linesN", "direct mapped: block b goes to line b mod $linesN; tag = b / $linesN")
            "full" -> L.t("全相联：块可以放任意一行，标记就是块号", "fully associative: any line; the tag is the block number")
            else -> L.t("$ways 路组相联（$sets 组）：块 b 放第 b mod $sets 组的任意一路，标记 = b / $sets", "$ways-way set associative ($sets sets): block b goes to set b mod $sets; tag = b / $sets")
        }
        var prevMarked: Int? = null
        for ((k, b) in blocks.withIndex()) {
            val set = b % sets
            val tag = b / sets
            val range = (set * ways) until (set * ways + ways)
            val acts = ArrayList<String>()
            prevMarked?.let { acts.add("unmark C[$it,0..1]") }
            var cap = L.t("第 ${k + 1} 次访问块 $b：", "access #${k + 1}, block $b: ")
            cap += when (map) {
                "direct" -> L.t("$b mod $linesN = $set → 行$set，标记 $tag", "$b mod $linesN = $set → line $set, tag $tag")
                "full" -> L.t("标记 $tag，查所有行", "tag $tag, check every line")
                else -> L.t("$b mod $sets = $set → 第 $set 组，标记 $tag", "$b mod $sets = $set → set $set, tag $tag")
            }
            val hitLine = range.firstOrNull { tags[it] == tag }
            if (hitLine != null) {
                hits += 1
                if (rep == "lru") stamp[hitLine] = k + 1
                cap += L.t("；${rowLabels[hitLine]} 的标记相同，命中", "; ${rowLabels[hitLine]} has the same tag: hit")
                acts += listOf("mark C[$hitLine,0..1] done", "set $hitName = $hits")
                prevMarked = hitLine
            } else {
                misses += 1
                val free = range.firstOrNull { tags[it] == null }
                val line: Int
                if (free != null) {
                    line = free
                    cap += L.t("；没有相同标记，缺失；${rowLabels[free]} 空着，调入", "; no matching tag: miss; ${rowLabels[free]} is empty, load it")
                } else {
                    line = range.minBy { stamp[it] }
                    val old = tags[line]!! * sets + set
                    cap += L.t("；没有相同标记，缺失；", "; no matching tag: miss; ") +
                        (if (rep == "lru") L.t("换出最久没用的块 $old（${rowLabels[line]}）", "evict block $old, unused longest (${rowLabels[line]})")
                        else L.t("换出最早调入的块 $old（${rowLabels[line]}）", "evict block $old, loaded earliest (${rowLabels[line]})"))
                }
                tags[line] = tag
                stamp[line] = k + 1
                acts += listOf("set C[$line,0] = $tag", "set C[$line,1] = $b", "mark C[$line,0..1] active", "set $missName = $misses")
                prevMarked = line
            }
            lines += s(if (k == 0) rule + L.sep + cap else cap, acts)
        }
        val n = blocks.size
        lines += s(L.t("命中 $hits 次、缺失 $misses 次，命中率 $hits/$n = ${num(hits * 100.0 / n)}%", "$hits hits, $misses misses: hit rate $hits/$n = ${num(hits * 100.0 / n)}%"),
            prevMarked?.let { listOf("unmark C[$it,0..1]") } ?: emptyList())
        val kind = when (map) {
            "direct" -> L.t("直接映射", "direct mapped")
            "full" -> L.t("全相联", "fully associative")
            else -> L.t("$ways 路组相联", "$ways-way set associative")
        }
        return AnimKits.Kit(L.t("Cache：$kind，$linesN 行", "Cache: $kind, $linesN lines") +
            (if (map == "direct") "" else L.t("，${if (rep == "lru") "LRU" else "FIFO"} 替换", ", ${rep.uppercase()} replacement")), lines)
    }

    // —— 进制转换 ——

    private fun baseKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val n: Int = a.string("n")?.toIntOrNull()?.takeIf { it in 0..1_000_000_000 }
            ?: throw AnimParseError("usage: kit base n=156 to=2  (n: 0…1000000000)")
        val base = a.int("to", 2, 2..16)
        a.checkUnused(listOf("n", "to", "lang"))
        val digits = "0123456789ABCDEF"
        val S = L.t("竖式", "work")
        val bw = base.toString().length
        val width = n.toString().length
        val col0 = bw + 1
        val remCol = col0 + width + 1
        val lines = mutableListOf("sheet $S", "write $S[0,0] ${q(base.toString())}", "write $S[0,$bw] \"|\"",
            "write $S[0,${col0 + width - n.toString().length}] ${q(n.toString())}",
            "label $S[0] ${q(L.t("← 右边一列是余数", "← the right column holds the remainders"))}")
        var cur = n
        var row = 0
        val rems = StringBuilder()
        if (n == 0) lines += s(L.t("0 的任何进制写法都是 0", "0 is 0 in any base"), emptyList())
        while (cur > 0) {
            val q2 = cur / base
            val r = cur % base
            rems.append(digits[r])
            val acts = mutableListOf("mark $S[$row,$col0..${col0 + width - 1}] active", "write $S[$row,$remCol] ${q(digits[r].toString())}")
            if (row > 0) acts.add(0, "unmark $S[${row - 1},$col0..${col0 + width - 1}]")
            if (q2 > 0) {
                acts += listOf("write $S[${row + 1},0] ${q(base.toString())}", "write $S[${row + 1},$bw] \"|\"",
                    "write $S[${row + 1},${col0 + width - q2.toString().length}] ${q(q2.toString())}")
            } else {
                acts.add("write $S[${row + 1},${col0 + width - 1}] \"0\"")
            }
            acts.add("rule $S[$row,$col0..${col0 + width - 1}]")
            lines += s(L.t("$cur ÷ $base = $q2 余 ${digits[r]}", "$cur ÷ $base = $q2 remainder ${digits[r]}") +
                (if (q2 == 0) L.t("；商为 0，停止", "; the quotient is 0: stop") else ""), acts)
            cur = q2
            row += 1
        }
        val result = if (n == 0) "0" else rems.reverse().toString()
        lines += s(L.t("余数从下往上读：($n)₁₀ = ($result)${subscriptDigits(base)}", "read the remainders bottom to top: ($n)₁₀ = ($result)${subscriptDigits(base)}"),
            if (row > 0) listOf("unmark $S[${row - 1},$col0..${col0 + width - 1}]", "mark $S[0..${row - 1},$remCol] done") else emptyList())
        return AnimKits.Kit(L.t("进制转换：$n 转 $base 进制（除基取余）", "Base conversion: $n to base $base"), lines)
    }

    fun subscriptDigits(n: Int): String {
        val sub = "₀₁₂₃₄₅₆₇₈₉"
        return n.toString().map { sub[it - '0'] }.joinToString("")
    }

    // —— KMP next / nextval（下标从 1 开始）——

    private fun kmpKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val p = a.string("p")
        if (p == null || p.isEmpty() || p.length > 16 || p.any { it.isWhitespace() || it in "[]=\"#|," }) {
            throw AnimParseError("usage: kit kmp P=ababaa  (1 to 16 characters, no spaces or commas)")
        }
        val withVal = choice(a, "nextval", listOf("yes", "no"), "yes") == "yes"
        a.checkUnused(listOf("P", "nextval", "lang"))
        val c = p.map { it.toString() }
        val n = c.size
        val rowNames = listOf(L.t("模式串", "P"), "next") + (if (withVal) listOf("nextval") else emptyList())
        val emptyRow = ", [" + List(n) { "" }.joinToString(", ") + "]"
        val lines = mutableListOf("grid K rows=[${rowNames.joinToString(", ")}] cols=[${(1..n).joinToString(", ")}] = [[${c.joinToString(", ")}]${emptyRow.repeat(rowNames.size - 1)}]",
            "pointer i -> K[0,0]", "pointer j")
        val next = IntArray(n + 1)
        lines += s(L.t("next[1] = 0（第一位就失配：模式串右移、主串指针后移）；i = 1，j = 0", "next[1] = 0 (a mismatch at the first character moves on); i = 1, j = 0"),
            listOf("set K[1,0] = 0", "mark K[1,0] done"))
        var i = 1
        var j = 0
        while (i < n) {
            val acts = ArrayList<String>()
            if (j == 0 || c[i - 1] == c[j - 1]) {
                val why = if (j == 0) L.t("j = 0", "j = 0") else L.t("P[$i] = P[$j] = ${c[i - 1]}", "P[$i] = P[$j] = ${c[i - 1]}")
                i += 1; j += 1
                next[i] = j
                acts += listOf("move i -> K[0,${i - 1}]", "move j -> K[0,${j - 1}]", "set K[1,${i - 1}] = $j", "mark K[1,${i - 1}] done")
                lines += s(why + L.t("：i、j 都后移，next[$i] = $j", ": move i and j on; next[$i] = $j"), acts)
            } else {
                val old = j
                j = next[j]
                acts.add(if (j == 0) "move j -> null" else "move j -> K[0,${j - 1}]")
                lines += s(L.t("P[$i] = ${c[i - 1]} ≠ P[$old] = ${c[old - 1]}：j 回退到 next[$old] = $j", "P[$i] = ${c[i - 1]} ≠ P[$old] = ${c[old - 1]}: j falls back to next[$old] = $j"), acts)
            }
        }
        if (withVal) {
            val nextval = IntArray(n + 1)
            lines += s(L.t("nextval：若 P[j] = P[next[j]]，失配后跳过去也必然失配，就继承 nextval[next[j]]；否则 nextval[j] = next[j]",
                "nextval: if P[j] = P[next[j]], that jump would fail again, so take nextval[next[j]]; else nextval[j] = next[j]"),
                listOf("move i -> null", "move j -> null", "set K[2,0] = 0", "mark K[2,0] done"))
            for (jj in 2..n) {
                val k = next[jj]
                val acts = mutableListOf("move j -> K[0,${jj - 1}]")
                val cap: String
                if (k > 0 && c[jj - 1] == c[k - 1]) {
                    nextval[jj] = nextval[k]
                    cap = L.t("P[$jj] = P[next[$jj]] = P[$k] = ${c[jj - 1]}：nextval[$jj] = nextval[$k] = ${nextval[jj]}",
                        "P[$jj] = P[$k] = ${c[jj - 1]}: nextval[$jj] = nextval[$k] = ${nextval[jj]}")
                } else {
                    nextval[jj] = k
                    cap = if (k == 0) L.t("next[$jj] = 0：nextval[$jj] = 0", "next[$jj] = 0: nextval[$jj] = 0")
                    else L.t("P[$jj] = ${c[jj - 1]} ≠ P[$k] = ${c[k - 1]}：nextval[$jj] = next[$jj] = $k", "P[$jj] = ${c[jj - 1]} ≠ P[$k] = ${c[k - 1]}: nextval[$jj] = next[$jj] = $k")
                }
                acts += listOf("set K[2,${jj - 1}] = ${nextval[jj]}", "mark K[2,${jj - 1}] done")
                lines += s(cap, acts)
            }
            val nx = (1..n).joinToString(" ") { next[it].toString() }
            val nv = (1..n).joinToString(" ") { nextval[it].toString() }
            lines += s(L.t("完成：next = $nx；nextval = $nv", "done: next = $nx; nextval = $nv"), listOf("move j -> null"))
        } else {
            val nx = (1..n).joinToString(" ") { next[it].toString() }
            lines += s(L.t("完成：next = $nx", "done: next = $nx"), listOf("move i -> null", "move j -> null"))
        }
        return AnimKits.Kit(L.t("KMP：模式串 $p 的 next${if (withVal) " / nextval" else ""} 数组", "KMP: next${if (withVal) " / nextval" else ""} of $p"), lines)
    }

    // —— 散列表 ——

    private fun hashKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val keys = intList(a, "keys", "usage: kit hash keys=19,14,23,1,68 size=13 [p=13] [probe=linear]")
        val m = a.int("size", 13, 2..20)
        fun isPrime(x: Int) = x >= 2 && (2 until x).all { x % it != 0 }
        val p = a.int("p", (2..m).lastOrNull { isPrime(it) } ?: m, 1..m)
        val probe = choice(a, "probe", listOf("linear", "quadratic"), "linear")
        a.checkUnused(listOf("keys", "size", "p", "probe", "lang"))
        if (keys.size > m || keys.size > 16) throw AnimParseError("keys: at most ${minOf(m, 16)} (the table has $m cells)")
        if (!keys.all { it >= 0 }) throw AnimParseError("keys are ≥ 0")
        val keyRow = L.t("关键字", "key")
        val cmpRow = L.t("比较次数", "probes")
        val lines = mutableListOf("grid H rows=[$keyRow, $cmpRow] cols=[${(0 until m).joinToString(", ")}] = 2x$m")
        val table = arrayOfNulls<Int>(m)
        var total = 0
        val rule = if (probe == "linear") L.t("H(key) = key mod $p；冲突就线性探测：依次看下一格（到表尾回到 0）", "H(key) = key mod $p; on a collision probe linearly: the next cell, wrapping to 0")
        else L.t("H(key) = key mod $p；冲突就平方探测：依次加 1², -1², 2², -2²…（对 $m 取模）", "H(key) = key mod $p; on a collision probe quadratically: +1², −1², +2², −2²… (mod $m)")
        var prevMarks = ArrayList<Int>()
        for ((k, key) in keys.withIndex()) {
            val h = key % p
            val acts = prevMarks.map { "unmark H[0..1,$it]" }.toMutableList()
            prevMarks = ArrayList()
            val tried = ArrayList<Int>()
            var addr: Int? = null
            var step = 0
            val limit = if (probe == "linear") m else 2 * m
            while (step < limit) {
                val off = if (probe == "linear") step else {
                    val i = (step + 1) / 2
                    if (step == 0) 0 else if (step % 2 == 1) i * i else -i * i
                }
                val cand = ((h + off) % m + m) % m
                tried.add(cand)
                if (table[cand] == null) { addr = cand; break }
                step += 1
            }
            val at = addr ?: throw AnimParseError("$key cannot be placed ($probe probing found no free cell)")
            table[at] = key
            val probes = tried.size
            total += probes
            for (c in tried.dropLast(1)) { acts.add("mark H[0,$c] wrong"); prevMarks.add(c) }
            acts += listOf("set H[0,$at] = $key", "set H[1,$at] = $probes", "mark H[0..1,$at] active")
            prevMarks.add(at)
            var cap = L.t("$key mod $p = $h", "$key mod $p = $h")
            if (tried.size == 1) {
                cap += L.t("：第 $h 格空，放入（比较 1 次）", ": cell $h is free, put it there (1 probe)")
            } else {
                val collide = tried.dropLast(1).joinToString(L.listSep) { L.t("$it 被 ${table[it]} 占", "$it holds ${table[it]}") }
                cap += L.t("：冲突（$collide），放进第 $at 格（比较 $probes 次）", ": collision ($collide); put it in cell $at ($probes probes)")
            }
            lines += s(if (k == 0) rule + L.sep + cap else cap, acts)
        }
        val n = keys.size
        lines += s(L.t("查找成功的平均查找长度 ASL = 比较次数之和 / 关键字个数 = $total/$n = ${num(total.toDouble() / n)}",
            "successful average search length ASL = total probes / keys = $total/$n = ${num(total.toDouble() / n)}"),
            prevMarks.map { "unmark H[0..1,$it]" })
        return AnimKits.Kit(L.t("散列表：除留余数法 mod $p，${if (probe == "linear") "线性探测" else "平方探测"}", "Hash table: mod $p, $probe probing"), lines)
    }

    // —— 建堆 / 堆排序 ——

    private fun heapKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val raw = a.string("a") ?: throw AnimParseError("usage: kit heap A=[53, 17, 78, 9, 45] [type=max] [sort=no]")
        val isMax = choice(a, "type", listOf("max", "min"), "max") == "max"
        val sort = choice(a, "sort", listOf("yes", "no"), "no") == "yes"
        a.checkUnused(listOf("A", "type", "sort", "lang"))
        val v = AnimText.parseList(raw).toMutableList()
        if (v.size < 2 || v.size > 15) throw AnimParseError("heap: 2 to 15 values")
        if (v.toSet().size != v.size) throw AnimParseError("values must be different (tree nodes are found by value)")
        val numeric = v.all { it.toDoubleOrNull() != null }
        fun better(x: String, y: String): Boolean =
            if (numeric) (if (isMax) x.toDouble() > y.toDouble() else x.toDouble() < y.toDouble()) else (if (isMax) x > y else x < y)
        val n = v.size
        fun treeDecl(i: Int): String {
            val l = 2 * i
            val r = 2 * i + 1
            if (l > n) return v[i - 1]
            return "(${v[i - 1]} ${treeDecl(l)}${if (r <= n) " " + treeDecl(r) else ""})"
        }
        val lines = mutableListOf("tree T = ${treeDecl(1)}", "array A = [${v.joinToString(", ")}]")
        val kind = if (isMax) L.t("大根堆", "max-heap") else L.t("小根堆", "min-heap")
        var carry: List<String> = emptyList()
        fun emit(cap: String, acts: List<String>) {
            lines += s(cap, carry + acts)
            carry = emptyList()
        }
        val bigger = if (isMax) L.t("较大", "larger") else L.t("较小", "smaller")
        fun swapV(x: Int, y: Int) { val t = v[x]; v[x] = v[y]; v[y] = t }
        fun sift(start: Int, size: Int, intro: String?) {
            var i = start
            var first = true
            while (2 * i <= size) {
                var c = 2 * i
                if (c + 1 <= size && better(v[c], v[c - 1])) c += 1
                val x = v[i - 1]
                val y = v[c - 1]
                val acts = mutableListOf("mark T[$x] active", "mark A[${i - 1}] active", "mark T[$y] active", "mark A[${c - 1}] active")
                val lead = if (first) (intro?.let { it + L.sep } ?: "") else ""
                first = false
                if (better(y, x)) {
                    acts += listOf("swap T[$x] T[$y]", "swap A[${i - 1}] A[${c - 1}]")
                    swapV(i - 1, c - 1)
                    emit(lead + L.t("A[$i] = $x，孩子里${bigger}的是 $y：交换，继续往下比", "A[$i] = $x; its $bigger child is $y: swap and keep going down"), acts)
                    carry = listOf("unmark T[$x]", "unmark T[$y]", "unmark A[${i - 1}]", "unmark A[${c - 1}]")
                    i = c
                } else {
                    emit(lead + L.t("A[$i] = $x，孩子里${bigger}的是 $y：不用换，这棵子树已经是堆", "A[$i] = $x; its $bigger child is $y: no swap, this subtree is a heap"), acts)
                    carry = listOf("unmark T[$x]", "unmark T[$y]", "unmark A[${i - 1}]", "unmark A[${c - 1}]")
                    return
                }
            }
            if (first && intro != null) emit(intro + L.sep + L.t("A[$start] 没有孩子，不用调整", "A[$start] has no children"), emptyList())
        }
        for (i in n / 2 downTo 1) {
            sift(i, n, if (i == n / 2) L.t("建$kind：从最后一个非叶结点 A[⌊$n/2⌋] = A[$i] 开始往前逐个向下调整", "build a $kind: sift down from the last internal node A[⌊$n/2⌋] = A[$i] backwards")
            else L.t("调整 A[$i]", "sift down A[$i]"))
        }
        emit(L.t("建堆完成：A = ${v.joinToString(" ")}", "heap built: A = ${v.joinToString(" ")}"), emptyList())
        if (sort) {
            for (end in n downTo 2) {
                val top = v[0]
                val last = v[end - 1]
                swapV(0, end - 1)
                emit(L.t("堆顶 $top 与堆尾 A[$end] = $last 交换，$top 就位，移出堆", "swap the top $top with the last A[$end] = $last; $top is in place"),
                    listOf("swap T[$top] T[$last]", "swap A[0] A[${end - 1}]", "remove T $top", "mark A[${end - 1}] done"))
                if (end - 1 >= 2) sift(1, end - 1, L.t("对新的堆顶向下调整", "sift the new top down"))
            }
            emit(L.t("排序完成：A = ${v.joinToString(" ")}", "sorted: A = ${v.joinToString(" ")}"), listOf("mark A done"))
        }
        return AnimKits.Kit(L.t(if (sort) "堆排序（$kind）" else "建$kind", if (sort) "Heap sort ($kind)" else "Build a $kind"), lines)
    }

    // —— 折半查找 ——

    private fun bsearchKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val raw = a.string("a")
        val keyRaw = if (raw != null) a.string("key") else null
        if (raw == null || keyRaw == null) throw AnimParseError("usage: kit bsearch A=[7, 10, 13, 16, 19, 29] key=19")
        a.checkUnused(listOf("A", "key", "lang"))
        val v = AnimText.parseList(raw)
        if (v.isEmpty() || v.size > 20) throw AnimParseError("bsearch: 1 to 20 values")
        val numeric = v.all { it.toDoubleOrNull() != null } && keyRaw.toDoubleOrNull() != null
        fun less(x: String, y: String) = if (numeric) x.toDouble() < y.toDouble() else x < y
        for (i in 1 until v.size) if (less(v[i], v[i - 1])) throw AnimParseError("A must be sorted ascending")
        val cmpName = L.t("比较次数", "comparisons")
        val lines = mutableListOf("array A = [${v.joinToString(", ")}]", "pointer low -> A[0]", "pointer high -> A[${v.size - 1}]", "pointer mid", "var $cmpName = 0")
        var low = 0
        var high = v.size - 1
        var count = 0
        val title = L.t("折半查找 $keyRaw", "Binary search for $keyRaw")
        while (low <= high) {
            val mid = (low + high) / 2
            count += 1
            val acts = mutableListOf("move mid -> A[$mid]", "mark A[$mid] active", "set $cmpName = $count")
            val head = L.t("low = $low，high = $high，mid = ⌊($low+$high)/2⌋ = $mid：", "low = $low, high = $high, mid = ⌊($low+$high)/2⌋ = $mid: ")
            if (v[mid] == keyRaw || (numeric && v[mid].toDouble() == keyRaw.toDouble())) {
                acts.add("mark A[$mid] done")
                lines += s(head + L.t("A[$mid] = ${v[mid]} 等于 $keyRaw，找到（比较 $count 次）", "A[$mid] = ${v[mid]} equals $keyRaw: found ($count comparisons)"), acts)
                return AnimKits.Kit(title, lines)
            }
            if (less(v[mid], keyRaw)) {
                acts += listOf("mark A[$low..$mid] muted", "move low -> A[${mid + 1}]")
                lines += s(head + L.t("A[$mid] = ${v[mid]} < $keyRaw，在右半边找：low = mid + 1 = ${mid + 1}", "A[$mid] = ${v[mid]} < $keyRaw: search the right half, low = mid + 1 = ${mid + 1}"), acts)
                low = mid + 1
            } else {
                acts += listOf("mark A[$mid..$high] muted", if (mid - 1 >= 0) "move high -> A[${mid - 1}]" else "move high -> null")
                lines += s(head + L.t("A[$mid] = ${v[mid]} > $keyRaw，在左半边找：high = mid - 1 = ${mid - 1}", "A[$mid] = ${v[mid]} > $keyRaw: search the left half, high = mid - 1 = ${mid - 1}"), acts)
                high = mid - 1
            }
        }
        lines += s(L.t("low = $low > high = $high：查找失败（比较 $count 次）", "low = $low > high = $high: not found ($count comparisons)"), listOf("move mid -> null"))
        return AnimKits.Kit(title, lines)
    }

    // —— TCP 拥塞控制 ——

    private fun tcpKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val ss0 = a.int("ssthresh", 16, 1..128)
        var ss = ss0
        val rounds = a.int("rounds", 16, 2..20)
        val timeouts = a.ints("timeout")
        val dups = a.ints("dup")
        a.checkUnused(listOf("ssthresh", "rounds", "timeout", "dup", "lang"))
        for (r in timeouts + dups) if (r < 1 || r >= rounds) throw AnimParseError("timeout= / dup= take round numbers 1…${rounds - 1}")
        if (timeouts.any { it in dups }) throw AnimParseError("a round can have either a timeout or 3 duplicate ACKs, not both")
        val now = L.t("当前", "now")
        val lines = mutableListOf("grid W rows=[cwnd] cols=[${(1..rounds).joinToString(", ")}] = 1x$rounds",
            "var ssthresh = $ss", "var cwnd = 1", "pointer $now",
            "text " + q(L.t("蓝：慢开始（每轮翻倍）　绿：拥塞避免（每轮加 1）　红：这一轮后出现超时或 3 个重复确认",
                "blue: slow start (doubles)   green: congestion avoidance (+1)   red: a timeout or 3 duplicate ACKs after this round")))
        var cwnd = 1
        for (t in 1..rounds) {
            val slow = cwnd < ss
            val acts = mutableListOf("set W[0,${t - 1}] = $cwnd", "move $now -> W[0,${t - 1}]", "set cwnd = $cwnd")
            var cap = L.t("第 $t 轮：cwnd = $cwnd", "round $t: cwnd = $cwnd")
            if (t in timeouts) {
                val newSS = maxOf(cwnd / 2, 1)
                acts += listOf("mark W[0,${t - 1}] wrong", "set ssthresh = $newSS")
                cap += L.t("；这一轮后超时：ssthresh = cwnd / 2 = $newSS，cwnd 回到 1，重新慢开始", "; then a timeout: ssthresh = cwnd / 2 = $newSS, cwnd back to 1, slow start again")
                ss = newSS; cwnd = 1
            } else if (t in dups) {
                val newSS = maxOf(cwnd / 2, 1)
                acts += listOf("mark W[0,${t - 1}] wrong", "set ssthresh = $newSS")
                cap += L.t("；收到 3 个重复确认：快重传，ssthresh = cwnd / 2 = $newSS，快恢复令 cwnd = ssthresh = $newSS，进入拥塞避免",
                    "; 3 duplicate ACKs: fast retransmit, ssthresh = cwnd / 2 = $newSS, fast recovery sets cwnd = $newSS, congestion avoidance")
                ss = newSS; cwnd = newSS
            } else if (slow) {
                acts.add("mark W[0,${t - 1}] active")
                val nxt = minOf(cwnd * 2, ss)
                cap += L.t("，小于 ssthresh = $ss，慢开始：下一轮翻倍", " < ssthresh = $ss: slow start, double next round") +
                    (if (nxt < cwnd * 2) L.t("（但不超过 ssthresh，下一轮 = $nxt）", " (capped at ssthresh: $nxt)") else "")
                cwnd = nxt
            } else {
                acts.add("mark W[0,${t - 1}] done")
                cap += L.t("，不小于 ssthresh = $ss，拥塞避免：下一轮加 1", " ≥ ssthresh = $ss: congestion avoidance, +1 next round")
                cwnd += 1
            }
            lines += s(cap, acts)
        }
        lines += s(L.t("共 $rounds 轮", "$rounds rounds"), listOf("move $now -> null"))
        return AnimKits.Kit(L.t("TCP 拥塞控制（初始 ssthresh = $ss0）", "TCP congestion control (initial ssthresh $ss0)"), lines)
    }
}
