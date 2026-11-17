package com.xvan.unireader.shared.anim

/*
 * 套件第五批（IP 地址分类与 CIDR）：ipclass、ipaddr、supernet、subnet、lpm。
 * 逐行照抄 Mac `Sources/Anim/AnimKits5.swift`——生成的每一行文字、说明的中英两份都要逐字相同（`AnimVectorsTest`）。
 * 地址一律用 Long（Swift 的 Int 是 64 位；Kotlin 的 Int 放不下 255.x.x.x）。
 */
internal object AnimKits5 {
    val NAMES = listOf("ipclass", "ipaddr", "supernet", "subnet", "lpm")

    fun generate(name: String, a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit? = when (name) {
        "ipclass" -> ipclassKit(a, L)
        "ipaddr" -> ipaddrKit(a, L)
        "supernet" -> supernetKit(a, L)
        "subnet" -> subnetKit(a, L)
        "lpm" -> lpmKit(a, L)
        else -> null
    }

    private fun s5(caption: String, actions: List<String>): List<String> =
        listOf("step " + caption.replace("#", "＃")) + actions.map { "  $it" }

    private fun q(s: String) = AnimKits.q(s)

    private val SUPERSCRIPT = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')

    private fun sup(n: Int): String = n.toString().map { SUPERSCRIPT.getValue(it) }.joinToString("")

    private fun binary(v: Long, width: Int): String = java.lang.Long.toBinaryString(v).padStart(width, '0')

    private fun isDigits(s: String) = s.all { it in '0'..'9' }

    // —— 公用 ——

    fun parseIPv4(s: String): Long {
        val parts = s.trimWs().split(".")
        if (parts.size != 4) throw AnimParseError("“$s” is not an IPv4 address like 192.168.1.10")
        var v = 0L
        for (p in parts) {
            val b = p.toIntOrNull()
            if (p.isEmpty() || p.length > 3 || !isDigits(p) || b == null || b > 255) throw AnimParseError("“$s” is not an IPv4 address like 192.168.1.10")
            v = v * 256 + b
        }
        return v
    }

    fun parseCIDR(s: String): Pair<Long, Int?> {
        val t = s.trimWs()
        val slash = t.indexOf('/')
        if (slash < 0) return parseIPv4(t) to null
        val lenText = t.substring(slash + 1)
        val n = lenText.toIntOrNull()
        if (n == null || n !in 0..32 || !isDigits(lenText)) throw AnimParseError("“$s”: the prefix length after / is 0…32")
        return parseIPv4(t.substring(0, slash)) to n
    }

    fun ipMask(n: Int): Long = if (n == 0) 0L else (0xFFFFFFFFL shl (32 - n)) and 0xFFFFFFFFL

    fun ipText(v: Long): String = listOf(24, 16, 8, 0).joinToString(".") { ((v shr it) and 255L).toString() }

    fun maskLength(m: Long): Int {
        for (n in 0..32) if (ipMask(n) == m) return n
        throw AnimParseError("${ipText(m)} is not a valid mask (the 1s must be contiguous from the left)")
    }

    fun ipBinary(v: Long, upTo: Int = 32): String {
        val out = StringBuilder()
        for (c in 0 until 35) {
            if (c % 9 == 8) {
                out.append(if ((c / 9 + 1) * 8 < upTo) "." else " ")
            } else {
                val p = c - c / 9
                out.append(if (p < upTo) ((v shr (31 - p)) and 1L).toString() else " ")
            }
        }
        return out.toString().trimEnd(' ')
    }

    fun ipCol(p: Int): Int = p + p / 8

    fun ipColRanges(a: Int, b: Int): List<String> {
        val out = ArrayList<String>()
        var p = a
        while (p < b) {
            val end = minOf(b, (p / 8 + 1) * 8)
            out.add(if (end - 1 == p) "${ipCol(p)}" else "${ipCol(p)}..${ipCol(end - 1)}")
            p = end
        }
        return out
    }

    fun ipClass(v: Long): Pair<String, Int> {
        val b = v shr 24
        return when {
            b < 128 -> "A" to 1
            b < 192 -> "B" to 2
            b < 224 -> "C" to 3
            b < 240 -> "D" to 4
            else -> "E" to 4
        }
    }

    fun ipSpecial(v: Long, L: AnimKits.Words): String {
        val b0 = v shr 24
        val b1 = (v shr 16) and 255L
        if (b0 == 10L || (b0 == 172L && b1 in 16L..31L) || (b0 == 192L && b1 == 168L)) {
            return L.t("；这是私有地址（10.0.0.0/8、172.16.0.0/12、192.168.0.0/16），只在内部网络用，路由器不转发到因特网",
                "; this is a private address (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16): internal only, not routed on the Internet")
        }
        if (b0 == 127L) return L.t("；127 开头是环回地址，用来测试本机", "; 127.x.x.x is loopback, for testing this host")
        if (v == 0L) return L.t("；0.0.0.0 表示「本网络上的本主机」", "; 0.0.0.0 means \"this host on this network\"")
        if (v == 0xFFFFFFFFL) return L.t("；255.255.255.255 是本网络上的广播", "; 255.255.255.255 is the broadcast on this network")
        return ""
    }

    // —— 五类地址 ——

    private fun ipclassKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        a.checkUnused(listOf("lang"))
        val net = L.t("网络号", "network")
        val host = L.t("主机号", "host")
        val cls = listOf(
            Triple("A", "0:1, $net:7, $host:24",
                L.t("A 类：第 1 位是 0，网络号 8 位（含这 1 位），主机号 24 位；第一个字节 1~126（0 和 127 另有用途），默认掩码 255.0.0.0；可指派网络 2⁷ − 2 = 126 个，每个网络最多 2²⁴ − 2 = 16777214 台主机",
                    "Class A: first bit 0, an 8-bit network number (that bit included), 24-bit host; first byte 1–126 (0 and 127 are special), default mask 255.0.0.0; 2⁷ − 2 = 126 networks of up to 2²⁴ − 2 = 16777214 hosts")),
            Triple("B", "10:2, $net:14, $host:16",
                L.t("B 类：前 2 位是 10，网络号 16 位，主机号 16 位；第一个字节 128~191，默认掩码 255.255.0.0；可指派网络 2¹⁴ − 1 = 16383 个（128.0 不指派），每个网络最多 2¹⁶ − 2 = 65534 台主机",
                    "Class B: first bits 10, 16-bit network, 16-bit host; first byte 128–191, default mask 255.255.0.0; 2¹⁴ − 1 = 16383 networks (128.0 unassigned) of up to 2¹⁶ − 2 = 65534 hosts")),
            Triple("C", "110:3, $net:21, $host:8",
                L.t("C 类：前 3 位是 110，网络号 24 位，主机号 8 位；第一个字节 192~223，默认掩码 255.255.255.0；可指派网络 2²¹ − 1 = 2097151 个（192.0.0 不指派），每个网络最多 2⁸ − 2 = 254 台主机",
                    "Class C: first bits 110, 24-bit network, 8-bit host; first byte 192–223, default mask 255.255.255.0; 2²¹ − 1 = 2097151 networks (192.0.0 unassigned) of up to 2⁸ − 2 = 254 hosts")),
            Triple("D", "1110:4, ${L.t("多播组地址", "multicast group")}:28",
                L.t("D 类：前 4 位是 1110，第一个字节 224~239，是多播地址，不分网络号和主机号", "Class D: first bits 1110, first byte 224–239: multicast, no network / host split")),
            Triple("E", "1111:4, ${L.t("保留", "reserved")}:28",
                L.t("E 类：前 4 位是 1111，第一个字节 240~255，保留今后使用", "Class E: first bits 1111, first byte 240–255: reserved for future use")),
        )
        fun name(c: String) = L.t("${c}类", "Class$c")
        val lines = cls.map { "bits ${name(it.first)} = ${it.second}" }.toMutableList()
        var prev: String? = null
        for ((c, _, cap) in cls) {
            val acts = prev?.let { mutableListOf("unmark $it") } ?: mutableListOf()
            acts += listOf("mark ${name(c)} active", "mark ${name(c)}[0] new")
            lines += s5(cap, acts)
            prev = name(c)
        }
        lines += s5(L.t("特殊地址：主机号全 0 是网络地址、全 1 是广播地址，都不能分给主机；127.x.x.x 是环回地址；0.0.0.0 表示本网络上的本主机；255.255.255.255 是本网络广播。私有地址：10.0.0.0/8、172.16.0.0/12、192.168.0.0/16，路由器不转发到因特网",
            "Special addresses: host part all 0 = the network address, all 1 = broadcast, neither goes to a host; 127.x.x.x is loopback; 0.0.0.0 is this host on this network; 255.255.255.255 broadcasts on this network. Private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, never routed on the Internet"),
            listOf("unmark ${name("E")}") + cls.map { "mark ${name(it.first)}[0] active" })
        return AnimKits.Kit(L.t("IP 地址的五类（分类编址）", "The five classes of IPv4 addresses"), lines)
    }

    // —— 一个地址 ——

    private fun ipaddrKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit ipaddr ip=192.168.1.10/24   (or ip=… mask=255.255.255.0, or just ip=… for its class)"
        val raw = a.string("ip") ?: throw AnimParseError(usage)
        val maskText = a.string("mask")
        a.checkUnused(listOf("ip", "mask", "lang"))
        val (addr, slash) = parseCIDR(raw)
        var prefix = slash
        if (maskText != null) {
            if (slash != null) throw AnimParseError("give /n or mask=, not both")
            prefix = maskLength(parseIPv4(maskText))
        }
        val (cls, lead) = ipClass(addr)
        val classful = prefix == null
        if (classful) {
            when (cls) {
                "A" -> prefix = 8
                "B" -> prefix = 16
                "C" -> prefix = 24
            }
        }
        val S = L.t("计算", "work")
        val H = L.t("结构", "layout")
        val vN = L.t("网络地址", "network")
        val vB = L.t("广播地址", "broadcast")
        val vC = L.t("可用主机", "hosts")
        val net = L.t("网络号", "network")
        val host = L.t("主机号", "host")
        val pre = L.t("网络前缀", "prefix")
        val fields: String
        var netFields: List<Int> = emptyList()
        var hostField = -1
        if (classful) {
            when (cls) {
                "A" -> { fields = "0:1, $net:7, $host:24"; netFields = listOf(0, 1); hostField = 2 }
                "B" -> { fields = "10:2, $net:14, $host:16"; netFields = listOf(0, 1); hostField = 2 }
                "C" -> { fields = "110:3, $net:21, $host:8"; netFields = listOf(0, 1); hostField = 2 }
                "D" -> fields = "1110:4, ${L.t("多播组地址", "multicast group")}:28"
                else -> fields = "1111:4, ${L.t("保留", "reserved")}:28"
            }
        } else {
            val n = prefix!!
            if (n == 0) { fields = "$host:32"; hostField = 0 } else if (n == 32) { fields = "$pre:32"; netFields = listOf(0) } else {
                fields = "$pre:$n, $host:${32 - n}"; netFields = listOf(0); hostField = 1
            }
        }
        val lines = mutableListOf("bits $H = $fields", "sheet $S")
        if (prefix != null) lines += listOf("var $vN = ?", "var $vB = ?", "var $vC = ?", "row $vN $vB")
        val bytes = listOf(24, 16, 8, 0).map { (addr shr it) and 255L }
        lines += s5(L.t("把 4 个字节都写成 8 位二进制：", "Write each of the 4 bytes as 8 bits: ") + bytes.joinToString(L.t("，", ", ")) { "$it = ${binary(it, 8)}" },
            listOf("write $S[0,0] " + q(ipBinary(addr)), "label $S[0] " + q(L.t("IP 地址 ", "address ") + ipText(addr))))
        val leadBits = binary(addr shr (32 - lead), lead)
        val range = when (cls) {
            "A" -> "0~127"
            "B" -> "128~191"
            "C" -> "192~223"
            "D" -> "224~239"
            else -> "240~255"
        }
        var cap = L.t("第一个字节 ${bytes[0]} = ${binary(bytes[0], 8)}：前 $lead 位是 $leadBits → $cls 类（第一个字节 $range）",
            "First byte ${bytes[0]} = ${binary(bytes[0], 8)}: the leading bits $leadBits → class $cls (first byte $range)")
        var acts = mutableListOf("mark $S[0,0..${lead - 1}] active", "mark $H[0] active")
        if (classful) {
            cap += when (cls) {
                "A", "B", "C" -> L.t("，默认掩码 ${ipText(ipMask(prefix!!))}：网络号 $prefix 位、主机号 ${32 - prefix!!} 位",
                    ", default mask ${ipText(ipMask(prefix!!))}: $prefix-bit network, ${32 - prefix!!}-bit host")
                "D" -> L.t("，是多播地址，不分网络号和主机号", ": a multicast address, no network / host split")
                else -> L.t("，保留今后使用", ": reserved for future use")
            }
        } else {
            cap += L.t("；但这里给了 /$prefix（CIDR，无分类编址）：网络前缀按 /$prefix 算，与类别无关",
                "; but /$prefix is given (CIDR, classless): the prefix decides, not the class")
        }
        val n = prefix
        if (n == null) {
            lines += s5(cap + ipSpecial(addr, L), acts)
            return AnimKits.Kit(L.t("IP 地址 ${ipText(addr)} 的类别", "The class of ${ipText(addr)}"), lines)
        }
        lines += s5(cap, acts)
        val h = 32 - n
        val m = ipMask(n)
        val netAddr = addr and m
        val bc = netAddr or (0xFFFFFFFFL xor m)
        acts = mutableListOf("unmark $S[0,0..${lead - 1}]", "unmark $H[0]", "write $S[1,0] " + q(ipBinary(m)),
            "label $S[1] " + q(L.t("掩码 ", "mask ") + "${ipText(m)}（/$n）"))
        acts += ipColRanges(0, n).map { "mark $S[1,$it] active" }
        acts += netFields.map { "mark $H[$it] active" }
        lines += s5(L.t("/$n：前 $n 位是 1、后 $h 位是 0 → 子网掩码 ${ipText(m)}", "/$n: $n ones then $h zeros → mask ${ipText(m)}"), acts)
        acts = ipColRanges(0, n).map { "unmark $S[1,$it]" }.toMutableList()
        acts += listOf("rule $S[1,0..34]", "write $S[2,0] " + q(ipBinary(netAddr)), "label $S[2] " + q(L.t("网络地址 ", "network ") + ipText(netAddr)),
            "set $vN = ${ipText(netAddr)}/$n", "mark $vN active")
        acts += ipColRanges(0, n).map { "mark $S[2,$it] active" }
        lines += s5(L.t("IP 地址与掩码逐位相与：前 $n 位照抄，后 $h 位全变成 0 → 网络地址 ${ipText(netAddr)}",
            "AND the address with the mask bit by bit: the first $n bits stay, the last $h become 0 → network ${ipText(netAddr)}"), acts)
        acts = mutableListOf("unmark $vN", "write $S[3,0] " + q(ipBinary(bc)), "label $S[3] " + q(L.t("广播地址 ", "broadcast ") + ipText(bc)),
            "set $vB = ${ipText(bc)}", "mark $vB active")
        acts += ipColRanges(n, 32).map { "mark $S[3,$it] done" }
        if (hostField >= 0) acts += netFields.map { "unmark $H[$it]" } + "mark $H[$hostField] done"
        lines += s5(L.t("网络前缀不变，主机号 $h 位全写成 1 → 广播地址 ${ipText(bc)}", "Keep the prefix, set all $h host bits to 1 → broadcast ${ipText(bc)}"), acts)
        var hc: String
        val count: String
        when (h) {
            0 -> {
                count = "1"
                hc = L.t("/32：只有这一个地址（常用于指向一台主机的路由）", "/32: just this one address (a host route)")
            }
            1 -> {
                count = "2"
                hc = L.t("/31：只有 2 个地址，用在点对点链路上两端各一个", "/31: only 2 addresses, one for each end of a point-to-point link")
            }
            else -> {
                val c = (1L shl h) - 2
                count = c.toString()
                hc = L.t("主机号 $h 位：2${sup(h)} − 2 = $c 个可用地址（去掉全 0 的网络地址和全 1 的广播地址），${ipText(netAddr + 1)} ~ ${ipText(bc - 1)}",
                    "$h host bits: 2${sup(h)} − 2 = $c usable addresses (minus the all-0 network and all-1 broadcast), ${ipText(netAddr + 1)} – ${ipText(bc - 1)}")
                if (addr == netAddr) hc += L.t("；${ipText(addr)} 本身就是网络地址，不能分给主机", "; ${ipText(addr)} itself is the network address and cannot go to a host")
                if (addr == bc) hc += L.t("；${ipText(addr)} 本身就是广播地址，不能分给主机", "; ${ipText(addr)} itself is the broadcast address and cannot go to a host")
            }
        }
        acts = mutableListOf("unmark $vB", "set $vC = $count", "mark $vC done")
        if (hostField >= 0) acts.add("set $H[$hostField] = " + q(L.t("$count 台", "$count hosts")))
        lines += s5(hc + ipSpecial(addr, L), acts)
        return AnimKits.Kit(L.t("${ipText(addr)}/$n：网络地址与广播地址", "${ipText(addr)}/$n: network and broadcast"), lines)
    }

    // —— 路由聚合 ——

    private fun cidrList(s: String, k: String, min: Int, max: Int): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        for (part in s.trim('[', ']').split(",")) {
            val t = part.trimWs()
            if (t.isEmpty()) continue
            val (v, n) = parseCIDR(t)
            if (n == null) throw AnimParseError("$k=: “$t” needs a prefix length, like $t/24")
            if (v and ipMask(n) != v) throw AnimParseError("$k=: $t is not a network address (host bits must be 0; did you mean ${ipText(v and ipMask(n))}/$n?)")
            out.add(v to n)
        }
        if (out.size < min || out.size > max) throw AnimParseError("$k= takes $min…$max networks like 172.18.129.0/24, 172.18.130.0/24")
        return out
    }

    private fun supernetKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val raw = a.string("nets") ?: throw AnimParseError("usage: kit supernet nets=172.18.129.0/24,172.18.130.0/24,172.18.132.0/24")
        a.checkUnused(listOf("nets", "lang"))
        val nets = cidrList(raw, "nets", 2, 8)
        val S = L.t("聚合", "aggregate")
        val minN = nets.minOf { it.second }
        var common = 0
        while (common < minN && nets.all { ((it.first shr (31 - common)) and 1L) == ((nets[0].first shr (31 - common)) and 1L) }) common += 1
        val lines = mutableListOf("sheet $S")
        var acts = ArrayList<String>()
        for ((i, p) in nets.withIndex()) acts += listOf("write $S[$i,0] " + q(ipBinary(p.first)), "label $S[$i] " + q("${ipText(p.first)}/${p.second}"))
        lines += s5(L.t("把要聚合的 ${nets.size} 个网络写成二进制，上下对齐", "Write the ${nets.size} networks in binary, one under another"), acts)
        acts = ArrayList()
        for (i in nets.indices) {
            acts += ipColRanges(0, common).map { "mark $S[$i,$it] done" }
            if (common < minN) acts.add("mark $S[$i,${ipCol(common)}] wrong")
        }
        lines += s5(if (common < minN)
            L.t("从左往右逐位比：前 $common 位都相同，第 ${common + 1} 位开始不同", "Compare bit by bit from the left: the first $common bits agree, bit ${common + 1} differs")
        else L.t("从左往右逐位比：前 $common 位都相同，再往后就超出最短的网络前缀 /$minN 了", "Compare bit by bit from the left: the first $common bits agree, and the shortest prefix is /$minN"),
            acts)
        val agg = nets[0].first and ipMask(common)
        val r = nets.size
        val spans = nets.map { it.first to it.first + (1L shl (32 - it.second)) }.sortedBy { it.first }
        var covered = 0L
        var end = -1L
        for ((lo, hi) in spans) {
            if (lo >= end) { covered += hi - lo; end = hi } else if (hi > end) { covered += hi - end; end = hi }
        }
        val size = 1L shl (32 - common)
        var cap = L.t("共同前缀 $common 位、后面补 0 → 聚合成 ${ipText(agg)}/$common（掩码 ${ipText(ipMask(common))}），路由表里一条代替 ${nets.size} 条",
            "Keep the $common common bits and fill with 0 → ${ipText(agg)}/$common (mask ${ipText(ipMask(common))}): one route instead of ${nets.size}")
        cap += if (size > covered) {
            L.t("；注意 /$common 共 $size 个地址，原来这几个网络合计 $covered 个，多包含了 ${size - covered} 个不在原网络里的地址",
                "; note the /$common holds $size addresses but the networks only $covered: ${size - covered} extra addresses are included")
        } else {
            L.t("；正好覆盖原来这几个网络", "; it covers exactly these networks")
        }
        acts = arrayListOf("rule $S[${r - 1},0..34]", "write $S[$r,0] " + q(ipBinary(agg)), "label $S[$r] " + q(L.t("聚合 ", "aggregate ") + "${ipText(agg)}/$common"))
        acts += ipColRanges(0, common).map { "mark $S[$r,$it] active" }
        lines += s5(cap, acts)
        return AnimKits.Kit(L.t("路由聚合（构成超网）", "Route aggregation (supernetting)"), lines)
    }

    // —— 划分子网 ——

    private fun subnetKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit subnet net=192.168.1.0/24 n=4   (or hosts=50 instead of n=)"
        val raw = a.string("net") ?: throw AnimParseError(usage)
        val (v, pOpt) = parseCIDR(raw)
        val p = pOpt ?: throw AnimParseError("net= needs a prefix length, like $raw/24")
        if (v and ipMask(p) != v) throw AnimParseError("net=: $raw is not a network address (host bits must be 0; did you mean ${ipText(v and ipMask(p))}/$p?)")
        val hasN = a.values["n"] != null
        val hasH = a.values["hosts"] != null
        if (hasN == hasH) throw AnimParseError(if (hasN) "give n= or hosts=, not both" else usage)
        var k: Int
        var h: Int
        val want: Int
        if (hasN) {
            want = a.int("n", 2, 2..16)
            k = 0
            while ((1 shl k) < want) k += 1
            h = 32 - p - k
        } else {
            want = a.int("hosts", 2, 1..16_777_214)
            h = 2
            while ((1L shl h) - 2 < want) h += 1
            k = 32 - p - h
        }
        a.checkUnused(listOf("net", "n", "hosts", "lang"))
        if (k < 1 || h < 2) throw AnimParseError("/$p has only ${32 - p} host bits: not enough to split that way")
        if (k > 4) throw AnimParseError("that makes ${1L shl k} subnets; at most 16")
        val np = p + k
        val m = ipMask(np)
        val S = L.t("子网", "subnets")
        val H = L.t("结构", "layout")
        val lines = mutableListOf("bits $H = ${L.t("网络前缀", "prefix")}:$p, ${L.t("子网号", "subnet")}:$k, ${L.t("主机号", "host")}:$h", "sheet $S")
        val usable = (1L shl h) - 2
        val cap0 = if (hasN) L.t("要分 $want 个子网：2${sup(k)} = ${1 shl k} ≥ $want，从主机号借 $k 位当子网号",
            "$want subnets needed: 2${sup(k)} = ${1 shl k} ≥ $want, so borrow $k host bits for the subnet number")
        else L.t("每个子网要放 $want 台主机：2${sup(h)} − 2 = $usable ≥ $want，主机号留 $h 位，剩下 $k 位当子网号 → 2${sup(k)} = ${1 shl k} 个子网",
            "$want hosts per subnet: 2${sup(h)} − 2 = $usable ≥ $want, so keep $h host bits and use the other $k for the subnet → 2${sup(k)} = ${1 shl k} subnets")
        lines += s5(cap0 + L.t("；新前缀 /$np，掩码 ${ipText(m)}，每个子网可用 2${sup(h)} − 2 = $usable 个地址",
            "; new prefix /$np, mask ${ipText(m)}, 2${sup(h)} − 2 = $usable usable addresses each"),
            listOf("mark $H[1] active", "set $H[1] = " + q(L.t("$k 位", "$k bits")), "set $H[2] = " + q(L.t("$h 位", "$h bits"))))
        for (i in 0 until (1 shl k)) {
            val sn = v or (i.toLong() shl h)
            val bc = sn or ((1L shl h) - 1)
            val acts = mutableListOf("write $S[$i,0] " + q(ipBinary(sn)), "label $S[$i] " + q(L.t("子网${i + 1} ", "subnet ${i + 1} ") + "${ipText(sn)}/$np"))
            acts += ipColRanges(p, np).map { "mark $S[$i,$it] active" }
            if (i > 0) acts += ipColRanges(p, np).map { "mark $S[${i - 1},$it] done" }
            lines += s5(L.t("子网${i + 1}：子网号 ${binary(i.toLong(), k)} → 网络 ${ipText(sn)}/$np，可用 ${ipText(sn + 1)} ~ ${ipText(bc - 1)}，广播 ${ipText(bc)}",
                "Subnet ${i + 1}: subnet bits ${binary(i.toLong(), k)} → ${ipText(sn)}/$np, hosts ${ipText(sn + 1)} – ${ipText(bc - 1)}, broadcast ${ipText(bc)}"), acts)
        }
        var end = L.t("共 ${1 shl k} 个子网，每个 ${1L shl h} 个地址；按 CIDR，子网号全 0、全 1 的子网也能用（老教材按 RFC 950 不用这两个，那样只剩 ${(1 shl k) - 2} 个）",
            "${1 shl k} subnets of ${1L shl h} addresses; under CIDR the all-0 and all-1 subnets are usable too (older textbooks, following RFC 950, drop them, leaving ${(1 shl k) - 2})")
        if (hasN && (1 shl k) > want) end += L.t("；只要 $want 个，用前 $want 个即可", "; only $want are needed, use the first $want")
        lines += s5(end, ipColRanges(p, np).map { "mark $S[${(1 shl k) - 1},$it] done" } + "unmark $H[1]")
        return AnimKits.Kit(L.t("划分子网：${ipText(v)}/$p", "Subnetting ${ipText(v)}/$p"), lines)
    }

    // —— 最长前缀匹配 ——

    private class Route(val v: Long, val n: Int, val hop: String)

    private fun lpmKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit lpm dst=192.168.1.37 routes=\"192.168.1.0/24 R1, 192.168.1.32/27 R2, 0.0.0.0/0 R3\""
        val d = a.string("dst")
        val raw = a.string("routes")
        if (d == null || raw == null) throw AnimParseError(usage)
        a.checkUnused(listOf("dst", "routes", "lang"))
        val dst = parseIPv4(d)
        val routes = ArrayList<Route>()
        for (part in raw.split(",")) {
            val words = part.split(' ').filter { it.isNotEmpty() }
            val first = words.firstOrNull() ?: continue
            val (v, nOpt) = parseCIDR(first)
            val n = nOpt ?: throw AnimParseError("routes=: “$first” needs a prefix length, like $first/24")
            if (v and ipMask(n) != v) throw AnimParseError("routes=: $first is not a network address (did you mean ${ipText(v and ipMask(n))}/$n?)")
            routes.add(Route(v, n, words.drop(1).joinToString(" ")))
        }
        if (routes.size !in 1..8) throw AnimParseError("routes= takes 1…8 entries like 192.168.1.0/24 R1")
        val S = L.t("匹配", "match")
        val lines = mutableListOf("sheet $S")
        lines += s5(L.t("把目的地址 ${ipText(dst)} 写成二进制，下面逐条拿路由表的前缀来比", "Write the destination ${ipText(dst)} in binary and compare each route's prefix with it"),
            listOf("write $S[0,0] " + q(ipBinary(dst)), "label $S[0] " + q(L.t("目的地址 ", "destination ") + ipText(dst)), "rule $S[0,0..34]"))
        val matched = ArrayList<Int>()
        for ((i, r) in routes.withIndex()) {
            val row = i + 1
            val hop = if (r.hop.isEmpty()) "" else " → ${r.hop}"
            val acts = mutableListOf("label $S[$row] " + q("${ipText(r.v)}/${r.n}$hop"))
            if (r.n > 0) acts.add(0, "write $S[$row,0] " + q(ipBinary(r.v, r.n)))
            var diff = -1
            for (p in 0 until r.n) {
                if (((dst shr (31 - p)) and 1L) != ((r.v shr (31 - p)) and 1L)) { diff = p; break }
            }
            val cap: String
            if (diff < 0) {
                matched.add(i)
                acts += ipColRanges(0, r.n).map { "mark $S[$row,$it] done" }
                cap = if (r.n == 0) L.t("默认路由 0.0.0.0/0：前缀 0 位，任何地址都匹配", "Default route 0.0.0.0/0: a 0-bit prefix matches everything")
                else L.t("${ipText(r.v)}/${r.n}：目的地址的前 ${r.n} 位与它完全相同 → 匹配", "${ipText(r.v)}/${r.n}: the first ${r.n} bits of the destination are the same → match")
            } else {
                acts += ipColRanges(0, diff).map { "mark $S[$row,$it] done" }
                acts.add("mark $S[$row,${ipCol(diff)}] wrong")
                if (diff + 1 < r.n) acts += ipColRanges(diff + 1, r.n).map { "mark $S[$row,$it] muted" }
                cap = L.t("${ipText(r.v)}/${r.n}：第 ${diff + 1} 位不同 → 不匹配", "${ipText(r.v)}/${r.n}: bit ${diff + 1} differs → no match")
            }
            lines += s5(cap, acts)
        }
        val best = matched.maxByOrNull { routes[it].n }
        if (best != null) {
            val r = routes[best]
            val list = matched.joinToString(L.listSep) { "/${routes[it].n}" }
            val acts = ipColRanges(0, r.n).map { "mark $S[${best + 1},$it] active" }.toMutableList()
            acts.add("mark $S[0,0..34] active")
            lines += s5(L.t("匹配的有 $list，取前缀最长的 ${ipText(r.v)}/${r.n}", "Matches: $list; the longest prefix wins: ${ipText(r.v)}/${r.n}") +
                (if (r.hop.isEmpty()) "" else L.t(" → 下一跳 ${r.hop}", " → next hop ${r.hop}")), acts)
        } else {
            lines += s5(L.t("一条都不匹配（也没有默认路由）→ 丢弃，向源点发 ICMP 终点不可达", "Nothing matches (and there is no default route) → drop it and send the source an ICMP destination unreachable"),
                listOf("mark $S[0,0..34] wrong"))
        }
        return AnimKits.Kit(L.t("最长前缀匹配：目的地址 ${ipText(dst)}", "Longest prefix match for ${ipText(dst)}"), lines)
    }
}
