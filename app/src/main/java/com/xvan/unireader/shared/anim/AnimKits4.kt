package com.xvan.unireader.shared.anim

/*
 * 套件第四批（计算机网络的首部与分组）：header（IPv4 / IPv6 / TCP / UDP 首部格式，可带 hex= 逐字段解析）、
 * encap（逐层封装 / 拆封）、frag（IP 分片）。逐行照抄 Mac `Sources/Anim/AnimKits4.swift`
 * ——生成的每一行文字、说明的中英两份都要逐字相同（`AnimVectorsTest`）。
 * Swift 的 Int 是 64 位，这里取字段值一律用 Long（序号、确认号是 32 位无符号，Int 会溢出成负数）。
 */
internal object AnimKits4 {
    val NAMES = listOf("header", "encap", "frag")

    fun generate(name: String, a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit? = when (name) {
        "header" -> headerKit(a, L)
        "encap" -> encapKit(a, L)
        "frag" -> fragKit(a, L)
        else -> null
    }

    private fun s4(caption: String, actions: List<String>): List<String> =
        listOf("step " + caption.replace("#", "＃")) + actions.map { "  $it" }

    private fun q(s: String) = AnimKits.q(s)

    private fun choice(a: AnimKits.Args, k: String, options: List<String>, d: String): String {
        val v = a.string(k)?.lowercase() ?: return d
        if (v !in options) throw AnimParseError("$k= takes ${options.joinToString(" / ")}")
        return v
    }

    // —— 公用 ——

    fun hex2(b: Int): String = Integer.toString(b, 16).uppercase().padStart(2, '0')

    fun hexN(v: Long, digits: Int): String = "0x" + java.lang.Long.toString(v, 16).uppercase().padStart(digits, '0')

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun parseHex(s: String): List<Int> {
        val t = s.replace("0x", "").replace("0X", "").filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (t.isEmpty() || t.length % 2 != 0 || !t.all { isHex(it) }) {
            throw AnimParseError("hex= takes bytes in hexadecimal, like \"45 00 00 3c\" (two digits per byte)")
        }
        return (0 until t.length / 2).map { t.substring(2 * it, 2 * it + 2).toInt(16) }
    }

    fun bitValue(bytes: List<Int>, offset: Int, count: Int): Long {
        var v = 0L
        for (k in 0 until count) {
            val bit = offset + k
            v = (v shl 1) or ((bytes[bit / 8] shr (7 - bit % 8)) and 1).toLong()
        }
        return v
    }

    private fun bin(v: Long, width: Int): String = java.lang.Long.toBinaryString(v).padStart(width, '0')

    fun dotted(bytes: List<Int>, at: Int): String = (0 until 4).joinToString(".") { bytes[at + it].toString() }

    /** IPv6 地址的标准写法（RFC 5952），同 Swift `ipv6Text`。 */
    fun ipv6Text(bytes: List<Int>, at: Int): String {
        val groups = (0 until 8).map { (bytes[at + 2 * it] shl 8) or bytes[at + 2 * it + 1] }
        var bestStart = -1
        var bestLen = 0
        var k = 0
        while (k < 8) {
            if (groups[k] == 0) {
                var e = k
                while (e < 8 && groups[e] == 0) e += 1
                if (e - k > bestLen && e - k >= 2) { bestStart = k; bestLen = e - k }
                k = e
            } else {
                k += 1
            }
        }
        val text = groups.map { Integer.toString(it, 16) }
        if (bestStart < 0) return text.joinToString(":")
        return text.subList(0, bestStart).joinToString(":") + "::" + text.subList(bestStart + bestLen, 8).joinToString(":")
    }

    fun onesSum(bytes: List<Int>, count: Int): Int {
        var sum = 0
        var i = 0
        while (i < count) {
            sum += (bytes[i] shl 8) or (if (i + 1 < count) bytes[i + 1] else 0)
            sum = (sum and 0xFFFF) + (sum shr 16)
            i += 2
        }
        return sum
    }

    private fun portName(p: Long, L: AnimKits.Words): String? = when (p) {
        20L -> L.t("FTP 数据", "FTP data")
        21L -> "FTP"
        22L -> "SSH"
        23L -> "TELNET"
        25L -> "SMTP"
        53L -> "DNS"
        67L -> L.t("DHCP 服务器", "DHCP server")
        68L -> L.t("DHCP 客户", "DHCP client")
        69L -> "TFTP"
        80L -> "HTTP"
        110L -> "POP3"
        143L -> "IMAP"
        161L -> "SNMP"
        443L -> "HTTPS"
        else -> null
    }

    private fun protocolName(p: Long): String? = when (p) {
        0L -> "Hop-by-Hop"
        1L -> "ICMP"
        2L -> "IGMP"
        6L -> "TCP"
        17L -> "UDP"
        41L -> "IPv6"
        43L -> "Routing"
        44L -> "Fragment"
        50L -> "ESP"
        58L -> "ICMPv6"
        59L -> "No Next Header"
        60L -> "Destination Options"
        89L -> "OSPF"
        else -> null
    }

    // —— 首部格式 ——

    private class HField(val zh: String, val en: String, val bits: Int, val variable: Boolean = false, val dz: String, val de: String)

    private fun headerFields(proto: String): MutableList<HField> = when (proto) {
        "ipv6" -> mutableListOf(
            HField("版本", "Version", 4, dz = "版本（4 位）：IPv6 为 6", de = "Version (4 bits): 6 for IPv6"),
            HField("通信量类", "Traffic Class", 8, dz = "通信量类（8 位）：区分数据报的类别或优先级，相当于 IPv4 的区分服务",
                de = "Traffic Class (8 bits): class or priority, like the DS field of IPv4"),
            HField("流标号", "Flow Label", 20, dz = "流标号（20 位）：同一个「流」（如一路实时音视频）的数据报标同一个流标号，路由器可以按流处理",
                de = "Flow Label (20 bits): datagrams of one flow (say a live video stream) carry the same label so routers can treat them alike"),
            HField("有效载荷长度", "Payload Length", 16, dz = "有效载荷长度（16 位）：基本首部后面的字节数（扩展首部 + 数据），不含 40 字节的基本首部，最大 65535",
                de = "Payload Length (16 bits): bytes after the base header (extension headers + data), not counting the 40-byte base header; at most 65535"),
            HField("下一个首部", "Next Header", 8, dz = "下一个首部（8 位）：没有扩展首部时相当于 IPv4 的协议字段（6 TCP、17 UDP、58 ICMPv6）；有扩展首部时指出第一个扩展首部的类型",
                de = "Next Header (8 bits): with no extension header it is IPv4's Protocol (6 TCP, 17 UDP, 58 ICMPv6); otherwise the type of the first extension header"),
            HField("跳数限制", "Hop Limit", 8, dz = "跳数限制（8 位）：相当于 IPv4 的 TTL，每经过一个路由器减 1，减到 0 就丢弃",
                de = "Hop Limit (8 bits): IPv4's TTL; each router subtracts 1 and drops the datagram at 0"),
            HField("源地址", "Source Address", 128, dz = "源地址（128 位）：发送方的 IPv6 地址", de = "Source Address (128 bits): the sender's IPv6 address"),
            HField("目的地址", "Destination Address", 128, dz = "目的地址（128 位）：接收方的 IPv6 地址", de = "Destination Address (128 bits): the receiver's IPv6 address"),
        )
        "tcp" -> {
            val f = mutableListOf(
                HField("源端口", "Source Port", 16, dz = "源端口（16 位）：发送方的应用进程", de = "Source Port (16 bits): the sending process"),
                HField("目的端口", "Destination Port", 16, dz = "目的端口（16 位）：交给接收方的哪个应用进程（80 HTTP、443 HTTPS、25 SMTP……）",
                    de = "Destination Port (16 bits): which process at the receiver gets the data (80 HTTP, 443 HTTPS, 25 SMTP …)"),
                HField("序号", "Sequence Number", 32, dz = "序号（32 位）：TCP 给字节流的每个字节编号，这里是本报文段数据第一个字节的序号",
                    de = "Sequence Number (32 bits): TCP numbers every byte of the stream; this is the number of the first data byte in this segment"),
                HField("确认号", "Acknowledgment Number", 32, dz = "确认号（32 位）：期望收到对方下一个报文段数据第一个字节的序号；确认号为 N 表示到 N − 1 为止都已正确收到（ACK = 1 时才有效）",
                    de = "Acknowledgment Number (32 bits): the next byte expected from the other side; N means everything up to N − 1 arrived (valid only when ACK = 1)"),
                HField("数据偏移", "Data Offset", 4, dz = "数据偏移（4 位）：其实就是首部长度，单位 4 字节；最小 5（20 字节），最大 15（60 字节）",
                    de = "Data Offset (4 bits): the header length in 4-byte units; 5 (20 bytes) to 15 (60 bytes)"),
                HField("保留", "Reserved", 6, dz = "保留（6 位）：留给以后用，目前为 0", de = "Reserved (6 bits): for future use, 0 for now"),
            )
            for (flag in listOf("URG", "ACK", "PSH", "RST", "SYN", "FIN")) f.add(HField(flag, flag, 1, dz = "", de = ""))
            f += listOf(
                HField("窗口", "Window", 16, dz = "窗口（16 位）：发送本报文段一方的接收窗口——从确认号算起，还允许对方发多少字节（流量控制）",
                    de = "Window (16 bits): the sender's receive window — how many bytes, counted from the acknowledgment number, the other side may still send (flow control)"),
                HField("检验和", "Checksum", 16, dz = "检验和（16 位）：检验首部和数据，计算时在前面加 12 字节的伪首部",
                    de = "Checksum (16 bits): covers the header and the data, computed with a 12-byte pseudo header in front"),
                HField("紧急指针", "Urgent Pointer", 16, dz = "紧急指针（16 位）：URG = 1 时才有意义，指出紧急数据有多少字节（紧急数据放在数据的最前面）",
                    de = "Urgent Pointer (16 bits): meaningful only when URG = 1; how many bytes of urgent data lead the data"),
            )
            f
        }
        "udp" -> mutableListOf(
            HField("源端口", "Source Port", 16, dz = "源端口（16 位）：需要对方回信时用，不需要时可以全 0", de = "Source Port (16 bits): for replies; may be all 0 when none is needed"),
            HField("目的端口", "Destination Port", 16, dz = "目的端口（16 位）：交给接收方的哪个应用进程（53 DNS、67/68 DHCP、69 TFTP……）",
                de = "Destination Port (16 bits): which process at the receiver gets the data (53 DNS, 67/68 DHCP, 69 TFTP …)"),
            HField("长度", "Length", 16, dz = "长度（16 位）：整个用户数据报（首部 + 数据）的字节数，最小 8", de = "Length (16 bits): bytes in the whole datagram (header + data), at least 8"),
            HField("检验和", "Checksum", 16, dz = "检验和（16 位）：检验首部和数据（前面加 12 字节伪首部）；不用时全 0",
                de = "Checksum (16 bits): covers header and data (with a 12-byte pseudo header); all 0 when unused"),
        )
        else -> mutableListOf(
            HField("版本", "Version", 4, dz = "版本（4 位）：IP 的版本，IPv4 为 4", de = "Version (4 bits): the IP version, 4 for IPv4"),
            HField("首部长度", "IHL", 4, dz = "首部长度（4 位）：单位是 4 字节；最小 5（20 字节，没有选项），最大 15（60 字节）",
                de = "IHL, header length (4 bits): in 4-byte units; 5 (20 bytes, no options) to 15 (60 bytes)"),
            HField("区分服务", "DS", 8, dz = "区分服务（8 位）：希望得到哪种服务（优先级等），一般不用", de = "Differentiated Services (8 bits): the kind of service wanted (priority …), seldom used"),
            HField("总长度", "Total Length", 16, dz = "总长度（16 位）：首部 + 数据的总长，单位字节，最大 65535；在以太网上不能超过 MTU 1500，否则要分片",
                de = "Total Length (16 bits): header + data in bytes, at most 65535; on Ethernet it must fit the 1500-byte MTU or be fragmented"),
            HField("标识", "Identification", 16, dz = "标识（16 位）：每发一个数据报加 1；分片时各片标识相同，接收方据此把同一数据报的片重组起来",
                de = "Identification (16 bits): a counter per datagram; all fragments of one datagram share it so the receiver can reassemble them"),
            HField("标志", "Flags", 3, dz = "标志（3 位）：最高位保留；中间位 DF = 1 表示不许分片；最低位 MF = 1 表示后面还有分片",
                de = "Flags (3 bits): the first is reserved; DF = 1 means do not fragment; MF = 1 means more fragments follow"),
            HField("片偏移", "Fragment Offset", 13, dz = "片偏移（13 位）：本片数据在原数据报里从第几个字节开始，单位 8 字节——所以除最后一片外，每片的数据长度都是 8 的倍数",
                de = "Fragment Offset (13 bits): where this fragment's data starts in the original datagram, in 8-byte units — so every fragment but the last carries a multiple of 8 bytes"),
            HField("生存时间", "TTL", 8, dz = "生存时间 TTL（8 位）：每经过一个路由器减 1，减到 0 就丢弃，防止数据报在网络里无限兜圈子",
                de = "Time to Live (8 bits): each router subtracts 1 and drops the datagram at 0, so it cannot loop forever"),
            HField("协议", "Protocol", 8, dz = "协议（8 位）：数据部分交给哪个协议：1 ICMP、2 IGMP、6 TCP、17 UDP、89 OSPF",
                de = "Protocol (8 bits): who gets the data: 1 ICMP, 2 IGMP, 6 TCP, 17 UDP, 89 OSPF"),
            HField("首部检验和", "Header Checksum", 16, dz = "首部检验和（16 位）：只检验首部、不检验数据；每经过一个路由器都要重算（TTL 变了）",
                de = "Header Checksum (16 bits): covers the header only, not the data; recomputed at every router (the TTL changes)"),
            HField("源地址", "Source Address", 32, dz = "源地址（32 位）：发送方的 IP 地址", de = "Source Address (32 bits): the sender's IP address"),
            HField("目的地址", "Destination Address", 32, dz = "目的地址（32 位）：接收方的 IP 地址", de = "Destination Address (32 bits): the receiver's IP address"),
        )
    }

    private fun headerKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val proto = choice(a, "proto", listOf("ipv4", "ipv6", "tcp", "udp"), "ipv4")
        val hexText = a.string("hex")
        a.checkUnused(listOf("proto", "hex", "lang"))
        val fields = headerFields(proto)
        val pname = when (proto) {
            "ipv6" -> "IPv6"
            "tcp" -> "TCP"
            "udp" -> "UDP"
            else -> "IPv4"
        }
        val H = L.t(pname + "首部", pname)
        val fixedBytes = if (proto == "ipv6") 40 else if (proto == "udp") 8 else 20
        var bytes: List<Int> = emptyList()
        var headerBytes = fixedBytes
        if (hexText != null) {
            bytes = parseHex(hexText)
            if (bytes.size < fixedBytes) {
                throw AnimParseError("hex= has ${bytes.size} byte(s); a${if (proto == "ipv4" || proto == "ipv6") "n" else ""} $pname header needs at least $fixedBytes")
            }
            if (proto == "ipv4" || proto == "tcp") {
                val hl = if (proto == "ipv4") bytes[0] and 0x0F else bytes[12] shr 4
                if (hl < 5) throw AnimParseError("the header length field is $hl (× 4 = ${hl * 4} bytes); it must be at least 5")
                headerBytes = hl * 4
                if (bytes.size < headerBytes) throw AnimParseError("the header length field says $headerBytes bytes but hex= has only ${bytes.size}")
            }
            if (proto == "ipv4" && bytes[0] shr 4 != 4) throw AnimParseError("the first 4 bits are ${bytes[0] shr 4}, not 4: this is not an IPv4 header")
            if (proto == "ipv6" && bytes[0] shr 4 != 6) throw AnimParseError("the first 4 bits are ${bytes[0] shr 4}, not 6: this is not an IPv6 header")
        }
        val options = headerBytes - fixedBytes
        if (proto == "ipv4" || proto == "tcp") {
            val ozh = if (proto == "ipv4") "可选字段（0~40 字节）：用于排错、测量、安全等，很少使用；长度不是 4 字节的整数倍时用填充补齐"
            else "选项（0~40 字节）：最大报文段长度 MSS、窗口扩大、时间戳、选择确认 SACK 等；不是 4 字节的整数倍时用填充补齐"
            val oen = if (proto == "ipv4") "Options (0–40 bytes): debugging, measurement, security …, rarely used; padded to a multiple of 4 bytes"
            else "Options (0–40 bytes): maximum segment size (MSS), window scale, timestamps, selective ACK …; padded to a multiple of 4 bytes"
            if (hexText == null) {
                fields.add(HField(if (proto == "ipv4") "可选字段（长度可变）" else "选项（长度可变）", "Options (variable)", 24, true, ozh, oen))
                fields.add(HField("填充", "Padding", 8, true, ozh, oen))
            } else if (options > 0) {
                fields.add(HField(if (proto == "ipv4") "可选字段" else "选项", "Options", options * 8, dz = ozh, de = oen))
            }
        }
        val lines = mutableListOf("bits $H = " + fields.joinToString(", ") { (if (L.zh) it.zh else it.en) + ":" + (if (it.variable) "*" else "") + it.bits })
        if (bytes.isNotEmpty()) {
            for (row in 0 until headerBytes / 4) {
                lines.add("label $H[$row] " + q((0 until 4).joinToString(" ") { hex2(bytes[row * 4 + it]) }))
            }
        }
        val flagStart = 6
        val flagEnd = 11
        var prev: List<Int> = emptyList()
        fun ranges(idx: List<Int>): List<String> {
            val out = ArrayList<String>()
            var k = 0
            while (k < idx.size) {
                var e = k
                while (e + 1 < idx.size && idx[e + 1] == idx[e] + 1) e += 1
                out.add(if (e == k) "${idx[k]}" else "${idx[k]}..${idx[e]}")
                k = e + 1
            }
            return out
        }
        fun focus(idx: List<Int>): List<String> {
            val acts = ranges(prev.filter { it !in idx }).map { "unmark $H[$it]" }.toMutableList()
            acts += ranges(idx).map { "mark $H[$it] active" }
            prev = idx
            return acts
        }
        if (bytes.isEmpty()) {
            var i = 0
            while (i < fields.size) {
                if (proto == "tcp" && i == flagStart) {
                    val cap = L.t("6 个控制位（各 1 位）：URG 紧急指针有效；ACK 确认号有效（连接建立后都是 1）；PSH 尽快交付；RST 连接出错，释放后重连；SYN 同步，建立连接（握手的前两次）；FIN 释放连接",
                        "6 control bits (1 bit each): URG urgent pointer valid; ACK acknowledgment valid (1 after the connection is set up); PSH push to the application; RST reset the connection; SYN synchronize, open a connection; FIN finish, close it")
                    lines += s4(cap, focus((flagStart..flagEnd).toList()))
                    i = flagEnd + 1
                    continue
                }
                if (fields[i].variable) {
                    lines += s4(L.t(fields[i].dz, fields[i].de), focus((i until fields.size).toList()))
                    break
                }
                lines += s4(L.t(fields[i].dz, fields[i].de), focus(listOf(i)))
                i += 1
            }
            val summary = when (proto) {
                "ipv6" -> L.t("基本首部固定 40 字节。和 IPv4 比：去掉了首部长度、标识 / 标志 / 片偏移（分片只在源点做，放进分片扩展首部）、首部检验和；选项改成扩展首部",
                    "The base header is always 40 bytes. Compared with IPv4: no header length, no identification / flags / fragment offset (only the source fragments, in a Fragment extension header), no header checksum; options became extension headers")
                "udp" -> L.t("UDP 首部只有 8 字节：无连接、尽最大努力交付、面向报文（应用交下来的报文不拆分、不合并）",
                    "The UDP header is only 8 bytes: connectionless, best-effort, message-oriented (each application message is sent as is)")
                "tcp" -> L.t("固定部分 20 字节（前 5 行）+ 选项 0~40 字节：TCP 首部 20~60 字节，长度看数据偏移",
                    "Fixed part 20 bytes (the first 5 rows) + options 0–40 bytes: a TCP header is 20–60 bytes; the data offset tells which")
                else -> L.t("固定部分 20 字节（前 5 行）+ 可选字段 0~40 字节：IP 首部 20~60 字节，长度看首部长度字段",
                    "Fixed part 20 bytes (the first 5 rows) + options 0–40 bytes: an IP header is 20–60 bytes; the IHL field tells which")
            }
            val acts = ranges(prev).map { "unmark $H[$it]" }.toMutableList()
            val fixedCount = fields.count { !it.variable }
            acts.add("mark $H[0..${fixedCount - 1}] done")
            lines += s4(summary, acts)
            return AnimKits.Kit(L.t("$pname 首部格式", "$pname header format"), lines)
        }

        // 从字节里逐字段取值
        val offsets = ArrayList<Int>()
        var o = 0
        for (f in fields) { offsets.add(o); o += f.bits }
        fun v(i: Int): Long = bitValue(bytes, offsets[i], fields[i].bits)
        fun b(i: Int): String = bin(v(i), fields[i].bits)
        fun setv(i: Int, value: String) = "set $H[$i] = " + q(value)
        fun port(p: Long): String = portName(p, L)?.let { L.t("$p（$it）", "$p ($it)") } ?: p.toString()
        val rest = bytes.size - headerBytes
        var final: String
        when (proto) {
            "ipv4" -> {
                val ihl = v(1)
                val total = v(3)
                val flags = v(5)
                val off = v(6)
                val ttl = v(7)
                val p = v(8)
                val ck = v(9)
                val pn = protocolName(p)
                lines += s4(L.t("版本 = ${b(0)} = ${v(0)}：IPv4", "Version = ${b(0)} = ${v(0)}: IPv4"), focus(listOf(0)) + setv(0, v(0).toString()))
                lines += s4(L.t("首部长度 = ${b(1)} = $ihl，单位 4 字节：首部 $ihl × 4 = ${ihl * 4} 字节" + (if (ihl > 5) "，其中可选字段 $options 字节" else "，没有可选字段"),
                    "IHL = ${b(1)} = $ihl in 4-byte units: the header is $ihl × 4 = ${ihl * 4} bytes" + (if (ihl > 5) ", $options of them options" else ", no options")),
                    focus(listOf(1)) + setv(1, ihl.toString()))
                lines += s4(L.t("区分服务 = ${hexN(v(2), 2)}", "Differentiated Services = ${hexN(v(2), 2)}"), focus(listOf(2)) + setv(2, hexN(v(2), 2)))
                lines += s4(L.t("总长度 = ${hexN(total, 4)} = $total 字节：数据部分 $total − ${ihl * 4} = ${total - ihl * 4} 字节",
                    "Total Length = ${hexN(total, 4)} = $total bytes: the data is $total − ${ihl * 4} = ${total - ihl * 4} bytes"),
                    focus(listOf(3)) + setv(3, total.toString()))
                lines += s4(L.t("标识 = ${hexN(v(4), 4)} = ${v(4)}", "Identification = ${hexN(v(4), 4)} = ${v(4)}"), focus(listOf(4)) + setv(4, v(4).toString()))
                val df = (flags shr 1) and 1L
                val mf = flags and 1L
                lines += s4(L.t("标志 = ${b(5)}：DF = $df（${if (df == 1L) "不许分片" else "允许分片"}），MF = $mf（${if (mf == 1L) "后面还有分片" else "后面没有分片了"}）",
                    "Flags = ${b(5)}: DF = $df (${if (df == 1L) "do not fragment" else "may fragment"}), MF = $mf (${if (mf == 1L) "more fragments follow" else "no more fragments"})"),
                    focus(listOf(5)) + setv(5, b(5)))
                lines += s4(L.t("片偏移 = $off，单位 8 字节：本片数据从原数据报的第 $off × 8 = ${off * 8} 字节开始" + (if (off == 0L && mf == 0L) "；MF = 0 且片偏移 = 0，说明没有分片" else ""),
                    "Fragment Offset = $off in 8-byte units: this data starts at byte $off × 8 = ${off * 8} of the original" + (if (off == 0L && mf == 0L) "; MF = 0 and offset 0: not fragmented" else "")),
                    focus(listOf(6)) + setv(6, off.toString()))
                lines += s4(L.t("生存时间 = ${hexN(ttl, 2)} = $ttl：最多还能经过 $ttl 个路由器", "TTL = ${hexN(ttl, 2)} = $ttl: at most $ttl more routers"), focus(listOf(7)) + setv(7, ttl.toString()))
                lines += s4(L.t("协议 = $p" + (pn?.let { "：数据部分交给 $it" } ?: ""), "Protocol = $p" + (pn?.let { ": the data goes to $it" } ?: "")),
                    focus(listOf(8)) + setv(8, pn?.let { "$p $it" } ?: p.toString()))
                lines += s4(L.t("首部检验和 = ${hexN(ck, 4)}", "Header Checksum = ${hexN(ck, 4)}"), focus(listOf(9)) + setv(9, hexN(ck, 4)))
                val src = (12 until 16).joinToString(" ") { hex2(bytes[it]) }
                val dst = (16 until 20).joinToString(" ") { hex2(bytes[it]) }
                lines += s4(L.t("源地址 = $src = ${dotted(bytes, 12)}", "Source Address = $src = ${dotted(bytes, 12)}"), focus(listOf(10)) + setv(10, dotted(bytes, 12)))
                lines += s4(L.t("目的地址 = $dst = ${dotted(bytes, 16)}", "Destination Address = $dst = ${dotted(bytes, 16)}"), focus(listOf(11)) + setv(11, dotted(bytes, 16)))
                if (options > 0) {
                    lines += s4(L.t("可选字段 $options 字节", "Options: $options bytes"), focus(listOf(12)) + setv(12, (20 until headerBytes).joinToString(" ") { hex2(bytes[it]) }))
                }
                val sum = onesSum(bytes, headerBytes)
                val ok = sum == 0xFFFF
                lines += s4(L.t("验算检验和：首部 ${headerBytes / 2} 个 16 位字相加（进位加回最低位）= ${hexN(sum.toLong(), 4)}，取反 = ${hexN((0xFFFF - sum).toLong(), 4)}" + (if (ok) "，为 0：首部没有差错" else "，不为 0：首部有差错，丢弃"),
                    "Check: the ${headerBytes / 2} 16-bit words summed with end-around carry = ${hexN(sum.toLong(), 4)}, complemented = ${hexN((0xFFFF - sum).toLong(), 4)}" + (if (ok) ": 0, the header is intact" else ": not 0, the header is corrupted and dropped")),
                    ranges(prev.filter { it != 9 }).map { "unmark $H[$it]" } + "mark $H[9] ${if (ok) "done" else "wrong"}")
                prev = emptyList()
                final = L.t("首部 ${ihl * 4} 字节 + 数据 ${total - ihl * 4} 字节 = 总长度 $total 字节", "Header ${ihl * 4} bytes + data ${total - ihl * 4} bytes = total length $total bytes") +
                    (pn?.let { L.t("，数据交给 $it", ", data for $it") } ?: "")
            }
            "ipv6" -> {
                val tc = v(1)
                val fl = v(2)
                val plen = v(3)
                val nh = v(4)
                val hop = v(5)
                val nn = protocolName(nh)
                lines += s4(L.t("版本 = ${b(0)} = 6：IPv6", "Version = ${b(0)} = 6: IPv6"), focus(listOf(0)) + setv(0, "6"))
                lines += s4(L.t("通信量类 = ${hexN(tc, 2)}（跨了第 1、2 两个字节）", "Traffic Class = ${hexN(tc, 2)} (spans bytes 1 and 2)"), focus(listOf(1)) + setv(1, hexN(tc, 2)))
                lines += s4(L.t("流标号 = ${hexN(fl, 5)}" + (if (fl == 0L) "：0 表示不属于任何流" else ""), "Flow Label = ${hexN(fl, 5)}" + (if (fl == 0L) ": 0 means no flow" else "")),
                    focus(listOf(2)) + setv(2, hexN(fl, 5)))
                lines += s4(L.t("有效载荷长度 = ${hexN(plen, 4)} = $plen 字节：整个数据报 40 + $plen = ${40 + plen} 字节",
                    "Payload Length = ${hexN(plen, 4)} = $plen bytes: the whole datagram is 40 + $plen = ${40 + plen} bytes"),
                    focus(listOf(3)) + setv(3, plen.toString()))
                lines += s4(L.t("下一个首部 = $nh" + (nn?.let { "：$it" } ?: ""), "Next Header = $nh" + (nn?.let { ": $it" } ?: "")),
                    focus(listOf(4)) + setv(4, nn?.let { "$nh $it" } ?: nh.toString()))
                lines += s4(L.t("跳数限制 = $hop", "Hop Limit = $hop"), focus(listOf(5)) + setv(5, hop.toString()))
                lines += s4(L.t("源地址 = ${ipv6Text(bytes, 8)}（每组去掉前导 0，最长的一串全 0 组写成 ::）",
                    "Source Address = ${ipv6Text(bytes, 8)} (leading zeros dropped, the longest run of zero groups written ::)"),
                    focus(listOf(6)) + setv(6, ipv6Text(bytes, 8)))
                lines += s4(L.t("目的地址 = ${ipv6Text(bytes, 24)}", "Destination Address = ${ipv6Text(bytes, 24)}"), focus(listOf(7)) + setv(7, ipv6Text(bytes, 24)))
                final = L.t("基本首部 40 字节 + 有效载荷 $plen 字节", "Base header 40 bytes + payload $plen bytes") + (nn?.let { L.t("，下一个是 $it", ", next: $it") } ?: "")
            }
            "tcp" -> {
                val sp = v(0)
                val dp = v(1)
                val seq = v(2)
                val ack = v(3)
                val doff = v(4)
                val win = v(12)
                val ck = v(13)
                val urg = v(14)
                lines += s4(L.t("源端口 = ${hexN(sp, 4)} = ${port(sp)}", "Source Port = ${hexN(sp, 4)} = ${port(sp)}"), focus(listOf(0)) + setv(0, sp.toString()))
                lines += s4(L.t("目的端口 = ${hexN(dp, 4)} = ${port(dp)}", "Destination Port = ${hexN(dp, 4)} = ${port(dp)}"), focus(listOf(1)) + setv(1, dp.toString()))
                lines += s4(L.t("序号 = ${hexN(seq, 8)} = $seq：本报文段数据的第一个字节编号是 $seq",
                    "Sequence Number = ${hexN(seq, 8)} = $seq: the first data byte here is byte $seq"),
                    focus(listOf(2)) + setv(2, seq.toString()))
                val ackOn = ((bytes[13] shr 4) and 1) == 1
                lines += s4(L.t("确认号 = ${hexN(ack, 8)} = $ack" + (if (ackOn) "：期望对方下一个字节是 $ack" else "（ACK = 0，确认号无效）"),
                    "Acknowledgment Number = ${hexN(ack, 8)} = $ack" + (if (ackOn) ": expecting byte $ack next" else " (ACK = 0: not valid)")),
                    focus(listOf(3)) + setv(3, ack.toString()))
                lines += s4(L.t("数据偏移 = ${b(4)} = $doff：首部 $doff × 4 = ${doff * 4} 字节", "Data Offset = ${b(4)} = $doff: the header is $doff × 4 = ${doff * 4} bytes"),
                    focus(listOf(4)) + setv(4, doff.toString()))
                lines += s4(L.t("保留 = ${b(5)}", "Reserved = ${b(5)}"), focus(listOf(5)) + setv(5, b(5)))
                val names = listOf("URG", "ACK", "PSH", "RST", "SYN", "FIN")
                val on = (0 until 6).filter { v(flagStart + it) == 1L }.map { names[it] }
                val meaning = ArrayList<String>()
                fun has(n: String) = n in on
                if (has("SYN") && !has("ACK")) meaning.add(L.t("连接请求（第一次握手）", "connection request (first handshake)"))
                if (has("SYN") && has("ACK")) meaning.add(L.t("同意建立连接（第二次握手）", "connection accepted (second handshake)"))
                if (has("FIN")) meaning.add(L.t("数据发完了，请求释放连接", "done sending, asking to close"))
                if (has("RST")) meaning.add(L.t("连接出错，要重置", "resetting the connection"))
                if (has("URG")) meaning.add(L.t("有紧急数据", "urgent data present"))
                val flagText = (if (on.isEmpty()) L.t("全为 0", "all 0") else on.joinToString(L.listSep) { "$it = 1" }) +
                    (if (meaning.isEmpty()) "" else L.t("：", ": ") + meaning.joinToString(L.sep))
                val bitsText = (flagStart..flagEnd).joinToString("") { v(it).toString() }
                lines += s4(L.t("控制位 $bitsText：", "Control bits $bitsText: ") + flagText,
                    focus((flagStart..flagEnd).toList()) + (flagStart..flagEnd).map { setv(it, v(it).toString()) })
                lines += s4(L.t("窗口 = ${hexN(win, 4)} = $win：对方还可以发 $win 字节", "Window = ${hexN(win, 4)} = $win: the other side may send $win more bytes"),
                    focus(listOf(12)) + setv(12, win.toString()))
                lines += s4(L.t("检验和 = ${hexN(ck, 4)}（验算要加伪首部，这里只有 TCP 首部，不验算）", "Checksum = ${hexN(ck, 4)} (checking needs the pseudo header, not given here)"),
                    focus(listOf(13)) + setv(13, hexN(ck, 4)))
                lines += s4(L.t("紧急指针 = $urg" + (if (has("URG")) "：前 $urg 字节是紧急数据" else "（URG = 0，不用看）"), "Urgent Pointer = $urg" + (if (has("URG")) ": the first $urg bytes are urgent" else " (URG = 0: ignored)")),
                    focus(listOf(14)) + setv(14, urg.toString()))
                if (options > 0) {
                    lines += s4(L.t("选项 $options 字节", "Options: $options bytes"), focus(listOf(15)) + setv(15, (20 until headerBytes).joinToString(" ") { hex2(bytes[it]) }))
                }
                final = L.t("首部 ${doff * 4} 字节", "Header ${doff * 4} bytes") + (if (rest > 0) L.t("，后面 $rest 字节是数据", ", followed by $rest data bytes") else "")
            }
            else -> {
                val sp = v(0)
                val dp = v(1)
                val len = v(2)
                val ck = v(3)
                lines += s4(L.t("源端口 = ${hexN(sp, 4)} = ${port(sp)}", "Source Port = ${hexN(sp, 4)} = ${port(sp)}"), focus(listOf(0)) + setv(0, sp.toString()))
                lines += s4(L.t("目的端口 = ${hexN(dp, 4)} = ${port(dp)}", "Destination Port = ${hexN(dp, 4)} = ${port(dp)}"), focus(listOf(1)) + setv(1, dp.toString()))
                lines += s4(L.t("长度 = ${hexN(len, 4)} = $len 字节：数据 $len − 8 = ${len - 8} 字节", "Length = ${hexN(len, 4)} = $len bytes: $len − 8 = ${len - 8} data bytes"),
                    focus(listOf(2)) + setv(2, len.toString()))
                lines += s4(L.t("检验和 = ${hexN(ck, 4)}" + (if (ck == 0L) "：全 0，没有使用检验和" else ""), "Checksum = ${hexN(ck, 4)}" + (if (ck == 0L) ": all 0, not used" else "")),
                    focus(listOf(3)) + setv(3, hexN(ck, 4)))
                final = L.t("首部 8 字节 + 数据 ${len - 8} 字节 = $len 字节", "Header 8 bytes + data ${len - 8} bytes = $len bytes")
            }
        }
        if (proto != "ipv4" && proto != "tcp" && rest > 0) final += L.t("；hex 里后面 $rest 字节是数据", "; the remaining $rest bytes of hex= are data")
        if (proto == "ipv4" && rest > 0) final += L.t("；hex 里后面 $rest 字节是数据部分", "; the remaining $rest bytes of hex= are the data")
        val acts = ranges(prev).map { "unmark $H[$it]" }.toMutableList()
        acts.add("mark $H[0..${fields.size - 1}] done")
        if (proto == "ipv4" && onesSum(bytes, headerBytes) != 0xFFFF) acts.add("mark $H[9] wrong")
        lines += s4(final, acts)
        return AnimKits.Kit(L.t("解析 $pname 首部", "Decoding an $pname header"), lines)
    }

    // —— 逐层封装 / 拆封 ——

    private fun encapKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val transport = choice(a, "transport", listOf("tcp", "udp"), "tcp")
        val net = choice(a, "net", listOf("ipv4", "ipv6"), "ipv4")
        val mode = choice(a, "mode", listOf("both", "encap"), "both")
        val data = a.int("data", 100, 1..65000)
        a.checkUnused(listOf("data", "transport", "net", "mode", "lang"))
        val tH = if (transport == "tcp") 20 else 8
        val nH = if (net == "ipv4") 20 else 40
        val T = if (transport == "tcp") "TCP" else "UDP"
        val N = if (net == "ipv4") "IPv4" else "IPv6"
        val P = L.t("分组", "packet")
        val seg = if (transport == "tcp") L.t("TCP 报文段", "TCP segment") else L.t("UDP 用户数据报", "UDP datagram")
        val dgram = L.t("IP 数据报", "IP datagram")
        val ipLen = data + tH + nH
        val pad = maxOf(46 - ipLen, 0)
        val frame = 14 + ipLen + pad + 4
        val lines = mutableListOf("packet $P unit=B = " + L.t("应用数据", "data") + ":$data")
        lines += s4(L.t("应用层：要发的数据（报文）$data 字节", "Application layer: $data bytes of data (a message)"), listOf("mark $P[0] done"))
        lines += s4(L.t("运输层：加上 $tH 字节的 $T 首部（源端口、目的端口……），成为$seg，共 ${data + tH} 字节",
            "Transport layer: a $tH-byte $T header (source and destination ports …) makes a $seg of ${data + tH} bytes"),
            listOf("insert $P 0 " + q(T + L.t("首部", " header") + ":$tH"), "group $P[0..1] " + q("$seg ${data + tH} B")))
        val protoField = if (net == "ipv4") L.t("协议字段", "Protocol") else L.t("下一个首部字段", "Next Header")
        val pnum = if (transport == "tcp") 6 else 17
        lines += s4(L.t("网络层：加上 $nH 字节的 $N 首部（源 IP、目的 IP，$protoField = $pnum），成为 IP 数据报，共 $ipLen 字节",
            "Network layer: a $nH-byte $N header (source and destination IP, $protoField = $pnum) makes an IP datagram of $ipLen bytes"),
            listOf("insert $P 0 " + q(N + L.t("首部", " header") + ":$nH"), "group $P[0..2] " + q("$dgram $ipLen B")))
        val link = mutableListOf("insert $P 0 " + q(L.t("以太网首部", "Ethernet header") + ":14"))
        val etype = if (net == "ipv4") "0x0800 = IPv4" else "0x86DD = IPv6"
        var cap = L.t("数据链路层：加上 14 字节的首部（目的 MAC 6 + 源 MAC 6 + 类型 2，类型 $etype）和 4 字节的尾部 FCS，成为以太网帧",
            "Link layer: a 14-byte header (destination MAC 6 + source MAC 6 + type 2, type $etype) and a 4-byte FCS trailer make an Ethernet frame")
        var last = 4
        if (pad > 0) {
            link.add("insert $P 4 " + q(L.t("填充", "pad") + ":$pad"))
            last = 5
            cap += L.t("；数据部分只有 $ipLen 字节，不足 46 字节，补 $pad 字节填充，使帧长达到最短 64 字节", "; the payload is only $ipLen bytes, under 46, so $pad bytes of padding bring the frame to the 64-byte minimum")
        }
        link += listOf("insert $P $last " + q("FCS:4"), "group $P[0..$last] " + q(L.t("以太网帧", "Ethernet frame") + " $frame B"))
        cap += L.t("，共 $frame 字节", ", $frame bytes in all")
        if (ipLen > 1500) cap += L.t("。注意：IP 数据报 $ipLen 字节超过以太网 MTU 1500，实际要先分片（kit frag）", ". Note: the $ipLen-byte datagram exceeds Ethernet's 1500-byte MTU and would have to be fragmented first (kit frag)")
        lines += s4(cap, link)
        lines += s4(L.t("物理层：前面再加 8 字节前同步码（7 字节前同步码 + 1 字节帧开始定界符），作为比特流发出去；它不算在帧长里",
            "Physical layer: an 8-byte preamble (7 bytes + a 1-byte start frame delimiter) goes in front and the bits are sent; it does not count in the frame length"),
            listOf("insert $P 0 " + q(L.t("前同步码", "preamble") + ":8"), "mark $P[0] muted"))
        if (mode == "both") {
            lines += s4(L.t("接收方物理层：去掉前同步码，把帧交给数据链路层", "Receiver, physical layer: strip the preamble and pass the frame up"), listOf("remove $P 0"))
            val unlink = mutableListOf("remove $P $last")
            if (pad > 0) unlink.add("remove $P 4")
            unlink.add("remove $P 0")
            lines += s4(L.t("数据链路层：用 FCS 检验，没有差错就去掉首部和尾部${if (pad > 0) "（连同填充，靠 IP 首部里的长度知道哪些是填充）" else ""}，按类型字段把数据交给 $N",
                "Link layer: check the FCS, strip header and trailer${if (pad > 0) " (and the padding — the IP length field tells where it starts)" else ""}, hand the data to $N as the type field says"),
                unlink)
            lines += s4(L.t("网络层：看目的地址是不是自己，去掉 $N 首部，按$protoField（$pnum）交给 $T",
                "Network layer: check the destination address, strip the $N header, hand the data to $T as $protoField ($pnum) says"),
                listOf("remove $P 0"))
            lines += s4(L.t("运输层：去掉 $T 首部，按目的端口交给应用进程", "Transport layer: strip the $T header and hand the data to the process on the destination port"),
                listOf("remove $P 0"))
            lines += s4(L.t("应用层收到原来的 $data 字节：每层只看、只去掉自己那一层加的首部", "The application gets the original $data bytes: each layer reads and strips only its own header"),
                listOf("mark $P[0] done"))
        }
        return AnimKits.Kit(L.t("逐层封装" + (if (mode == "both") "与拆封" else "") + "（$T / $N / 以太网）",
            (if (mode == "both") "Encapsulation and decapsulation" else "Encapsulation") + " ($T / $N / Ethernet)"), lines)
    }

    // —— IP 分片 ——

    private fun fragKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit frag total=4000 mtu=1500 [header=20] [df=0|1]   (or data=3980 instead of total=)"
        val header = a.int("header", 20, 20..60)
        if (header % 4 != 0) throw AnimParseError("header= is a multiple of 4 (20…60)")
        val mtu = a.int("mtu", 1500, 68..65535)
        val df = a.int("df", 0, 0..1)
        val total: Int = if (a.values["total"] != null) {
            if (a.values["data"] != null) throw AnimParseError("give total= or data=, not both")
            a.int("total", 0, (header + 1)..65535)
        } else if (a.values["data"] != null) {
            a.int("data", 0, 1..(65535 - header)) + header
        } else {
            throw AnimParseError(usage)
        }
        val id = a.int("id", -1, 0..65535)
        a.checkUnused(listOf("total", "data", "mtu", "header", "df", "id", "lang"))
        if (mtu < header + 8) throw AnimParseError("mtu= must leave room for at least 8 data bytes after the $header-byte header")
        val data = total - header
        val maxData = (mtu - header) / 8 * 8
        val count = if (data <= mtu - header) 1 else (data + maxData - 1) / maxData
        if (df == 0 && total > mtu && count > 8) throw AnimParseError("that makes $count fragments; at most 8 (use a larger mtu=)")
        val D = L.t("数据报", "datagram")
        val F = L.t("分片", "fragments")
        val hName = L.t("首部", "header")
        val dName = L.t("数据", "data")
        val lines = mutableListOf("packet $D unit=B = $hName:$header, $dName:$data")
        val idText = if (id >= 0) L.t("，标识 $id", ", identification $id") else ""
        val title = L.t("IP 分片：总长度 $total，MTU $mtu", "IP fragmentation: total length $total, MTU $mtu")
        if (total <= mtu) {
            lines += s4(L.t("总长度 $total ≤ MTU $mtu：不需要分片，原样发出（MF = 0，片偏移 = 0）", "Total length $total ≤ MTU $mtu: no fragmentation needed (MF = 0, offset 0)"),
                listOf("mark $D done"))
            return AnimKits.Kit(title, lines)
        }
        if (df == 1) {
            lines += s4(L.t("总长度 $total 超过 MTU $mtu，需要分片", "Total length $total exceeds the MTU $mtu: it must be fragmented"), listOf("mark $D[1] active"))
            lines += s4(L.t("但 DF = 1，不许分片：路由器丢弃这个数据报，并向源点发 ICMP 差错报告（终点不可达：需要分片但 DF 置 1）",
                "But DF = 1 forbids it: the router drops the datagram and sends the source an ICMP error (destination unreachable: fragmentation needed and DF set)"),
                listOf("mark $D wrong"))
            return AnimKits.Kit(title, lines)
        }
        val rows = (1..count).map { L.t("片$it", "F$it") }
        lines.add("grid $F rows=[${rows.joinToString(", ")}] cols=[${L.t("总长度, 数据长度, MF, DF, 片偏移", "Total, Data, MF, DF, Offset")}] = ${count}x5")
        lines += s4(L.t("每片也要带 $header 字节首部，一片最多放 $mtu − $header = ${mtu - header} 字节数据；片偏移以 8 字节为单位，所以每片（最后一片除外）的数据长度要取 8 的倍数：⌊${mtu - header} / 8⌋ × 8 = $maxData",
            "Each fragment carries its own $header-byte header, leaving $mtu − $header = ${mtu - header} bytes for data; offsets count 8-byte units, so every fragment but the last carries a multiple of 8: ⌊${mtu - header} / 8⌋ × 8 = $maxData"),
            listOf("mark $D[1] active"))
        var start = 0
        for (k in 0 until count) {
            val n = minOf(maxData, data - start)
            val isLast = k == count - 1
            val acts = ArrayList<String>()
            val at = 2 * k
            if (k > 0) acts += "unmark $F[${k - 1},0..4]"
            if (isLast) {
                acts += listOf("set $D[${at + 1}] = " + q("$dName:$n"), "unmark $D[${at + 1}]")
            } else {
                acts += listOf("set $D[${at + 1}] = " + q("$dName:$n"), "unmark $D[${at + 1}]",
                    "insert $D ${at + 2} " + q("$hName:$header"), "insert $D ${at + 3} " + q("$dName:${data - start - n}"), "mark $D[${at + 3}] active")
            }
            acts += listOf("group $D[$at..${at + 1}] " + q(L.t("片${k + 1}：字节 $start~${start + n - 1}", "F${k + 1}: bytes $start–${start + n - 1}")),
                "set $F[$k,0] = ${n + header}", "set $F[$k,1] = $n", "set $F[$k,2] = ${if (isLast) 0 else 1}", "set $F[$k,3] = 0", "set $F[$k,4] = ${start / 8}",
                "mark $F[$k,0..4] active")
            val cap = L.t("片${k + 1}：数据第 $start~${start + n - 1} 字节，共 $n 字节，总长度 $n + $header = ${n + header}；片偏移 = $start / 8 = ${start / 8}；MF = ${if (isLast) "0（最后一片）" else "1（后面还有）"}",
                "F${k + 1}: data bytes $start–${start + n - 1}, $n bytes, total length $n + $header = ${n + header}; offset = $start / 8 = ${start / 8}; MF = ${if (isLast) "0 (last)" else "1 (more follow)"}")
            lines += s4(cap, acts)
            start += n
        }
        lines += s4(L.t("共 $count 片：各片的标识都与原数据报相同$idText，DF = 0；只有最后一片 MF = 0。终点按标识把片收齐、按片偏移排好，重组成原来的 $total 字节",
            "$count fragments: all keep the original identification$idText, DF = 0; only the last has MF = 0. The destination collects them by identification, orders them by offset and rebuilds the $total bytes"),
            listOf("unmark $F[${count - 1},0..4]", "mark $F[0..${count - 1},4] done"))
        return AnimKits.Kit(title, lines)
    }
}
