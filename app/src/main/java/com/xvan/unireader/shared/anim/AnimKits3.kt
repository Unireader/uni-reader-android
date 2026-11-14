package com.xvan.unireader.shared.anim

import kotlin.math.abs
import kotlin.math.max

/*
 * 套件第三批（组成原理「定点数的乘除运算」）：mul（原码一位乘 / Booth）、div（恢复余数 / 原码加减交替 / 补码加减交替）。
 * 逐行照抄 Mac `Sources/Anim/AnimKits3.swift`——生成的每一行文字、说明的中英两份都要逐字相同（`AnimVectorsTest`）。
 */
internal object AnimKits3 {
    val NAMES = listOf("mul", "div")

    fun generate(name: String, a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit? = when (name) {
        "mul" -> mulKit(a, L)
        "div" -> divKit(a, L)
        else -> null
    }

    // —— 公用 ——

    private fun choice(a: AnimKits.Args, k: String, options: List<String>, d: String): String {
        val v = a.string(k)?.lowercase() ?: return d
        if (v !in options) throw AnimParseError("$k= takes ${options.joinToString(" / ")}")
        return v
    }

    private class Frac(val neg: Boolean, val bits: String, val isInt: Boolean)

    /** 定点小数 `-0.1101` / `0.1011` / `.101`（也认 `+`、`−`）；`allowInt` 时也认定点整数 `-1101`。 */
    private fun fraction(a: AnimKits.Args, k: String, usage: String, allowInt: Boolean): Frac {
        var s = a.string(k) ?: throw AnimParseError(usage)
        var neg = false
        if (s.startsWith("-") || s.startsWith("−")) { neg = true; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        var isInt = false
        s = if (s.startsWith("0.")) s.substring(2) else if (s.startsWith(".")) s.substring(1) else if (allowInt && !s.contains(".")) { isInt = true; s } else ""
        if (s.isEmpty() || s.length > 8 || !s.all { it == '0' || it == '1' }) {
            throw AnimParseError(if (allowInt) "$k= is a binary fraction like -0.1101 or a binary integer like -1101 (1 to 8 bits)"
            else "$k= is a binary fraction like -0.1101 (1 to 8 bits after the point)")
        }
        return Frac(neg, s, isInt)
    }

    /** 无符号位串，左补 0 到 width 位。 */
    fun binary(u: Int, width: Int): String = u.toString(2).padStart(width, '0')

    /** 定点位串：小数点前 intBits 位、后 n 位；v 按 2ⁿ 缩放，负数取补码。 */
    fun fixed(v: Int, intBits: Int, n: Int, sep: String = "."): String {
        val m = 1 shl (intBits + n)
        val s = binary(((v % m) + m) % m, intBits + n)
        return s.take(intBits) + sep + s.takeLast(n)
    }

    /** 真值：-0.1101。 */
    fun trueValue(v: Int, n: Int, sep: String = "."): String =
        (if (v < 0) "-" else "") + (if (sep == ",") binary(abs(v), n) else fixed(abs(v), 1, n))

    private val SUPERSCRIPT = mapOf('0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹')

    private fun superscriptMinus(n: Int): String = "⁻" + n.toString().map { SUPERSCRIPT.getValue(it) }.joinToString("")

    private fun sub(n: Int) = AnimKits2.subscriptDigits(n)

    private fun s3(caption: String, actions: List<String>): List<String> =
        listOf("step " + caption.replace("#", "＃")) + actions.map { "  $it" }

    private fun q(s: String) = AnimKits.q(s)

    /** 商位串：第一位后面加小数点（0、0.1、0.10 …）。 */
    private fun quotientText(bits: List<Int>): String {
        val s = bits.joinToString("")
        return if (bits.size > 1) s.take(1) + "." + s.drop(1) else s
    }

    /** 小数在右边补 0、整数在左边补 0，数值都不变。 */
    private fun padBits(f: Frac, n: Int): Int =
        (if (f.isInt) "0".repeat(n - f.bits.length) + f.bits else f.bits + "0".repeat(n - f.bits.length)).toInt(2)

    // —— 乘法 ——

    private fun mulKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit mul method=orig|booth x=-0.1101 y=0.1011"
        val method = choice(a, "method", listOf("orig", "booth"), "orig")
        val x = fraction(a, "x", usage, true)
        val y = fraction(a, "y", usage, true)
        a.checkUnused(listOf("method", "x", "y", "lang"))
        if (x.isInt != y.isInt) throw AnimParseError("x= and y= must both be fractions (0.1101) or both integers (1101)")
        val n = max(x.bits.length, y.bits.length)
        val xm = padBits(x, n)
        val ym = padBits(y, n)
        val sep = if (x.isInt) "," else "."
        val S = L.t("竖式", "work")
        val xText = (if (x.neg) "-" else "") + trueValue(xm, n, sep)
        val yText = (if (y.neg) "-" else "") + trueValue(ym, n, sep)
        if (method == "orig") {
            return AnimKits.Kit(L.t("原码一位乘法：x = $xText，y = $yText", "Sign-magnitude multiplication: x = $xText, y = $yText"),
                origMul(x.neg, xm, y.neg, ym, n, sep, S, L))
        }
        return AnimKits.Kit(L.t("补码一位乘法（Booth）：x = $xText，y = $yText", "Booth multiplication: x = $xText, y = $yText"),
            boothMul(if (x.neg) -xm else xm, if (y.neg) -ym else ym, n, sep, S, L))
    }

    private fun origMul(xNeg: Boolean, xm: Int, yNeg: Boolean, ym: Int, n: Int, sep: String, S: String, L: AnimKits.Words): List<String> {
        fun fx(v: Int, i: Int, m: Int) = fixed(v, i, m, sep)
        val xs = if (xNeg) 1 else 0
        val ys = if (yNeg) 1 else 0
        val sign = xs xor ys
        val wA = n + 2
        val h1 = L.t("部分积", "A")
        val h2 = L.t("乘数", "Q")
        val c1 = 1
        val c2 = c1 + max(wA, h1.length) + 2
        val lines = mutableListOf(
            "text " + q(L.t("|x| = ${fx(xm, 1, n)}　|y| = ${fx(ym, 1, n)}　乘积的符号 = xs ⊕ ys = $xs ⊕ $ys = $sign",
                "|x| = ${fx(xm, 1, n)}   |y| = ${fx(ym, 1, n)}   sign of the product = xs ⊕ ys = $xs ⊕ $ys = $sign")),
            "text " + q(L.t("符号位单独算，数值部分用绝对值：看乘数最低位，1 加 |x|、0 加 0，然后部分积和乘数一起右移一位，共 $n 次",
                "A: partial product, Q: multiplier. The sign is handled separately; work with magnitudes: look at the lowest multiplier bit, add |x| for 1 or 0 for 0, then shift product and multiplier right together; $n rounds")),
            "sheet $S",
            "write ${S}[0,$c1] ${q(h1)}", "write ${S}[0,$c2] ${q(h2)}",
            "write ${S}[1,$c1] ${q(fx(0, 1, n))}", "write ${S}[1,$c2] ${q(binary(ym, n))}",
            "label ${S}[1] ${q(L.t("初始：部分积 0，乘数 |y|", "start: product 0, multiplier |y|"))}",
        )
        var A = 0
        var Q = ym
        var row = 1
        for (i in 0 until n) {
            val bit = Q and 1
            val yi = "y" + sub(n - i)
            val add = if (bit == 1) xm else 0
            A += add
            lines += s3(L.t("第 ${i + 1} 次：乘数最低位 $yi = $bit，部分积加 ${if (bit == 1) "|x|" else "0"}，得 ${fx(A, 1, n)}",
                "round ${i + 1}: the lowest multiplier bit $yi = $bit, so add ${if (bit == 1) "|x|" else "0"} to the product: ${fx(A, 1, n)}")
                + (if (A shr n == 1) (if (sep == ",") L.t("（逗号前的 1 是进位）", " (the 1 before the comma is a carry)") else L.t("（小数点前的 1 是进位）", " (the 1 before the point is a carry)")) else ""),
                listOf("mark ${S}[$row,${c2 + n - 1}] active",
                    "write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fx(add, 1, n))}",
                    "label ${S}[${row + 1}] ${q(if (bit == 1) "+|x|" else "+0")}",
                    "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                    "write ${S}[${row + 2},$c1] ${q(fx(A, 1, n))}"))
            val low = A and 1
            Q = (Q shr 1) or (low shl (n - 1))
            A = A shr 1
            lines += s3(L.t("部分积和乘数一起右移一位：部分积最低位 $low 移进乘数最高位，乘数最低位 $yi 用过了、移出去",
                "shift product and multiplier right together: the product's lowest bit $low moves into the multiplier's top, the used bit $yi drops out"),
                listOf("unmark ${S}[$row,${c2 + n - 1}]",
                    "write ${S}[${row + 3},$c1] ${q(fx(A, 1, n))}", "write ${S}[${row + 3},$c2] ${q(binary(Q, n))}",
                    "label ${S}[${row + 3}] ${q(L.t("右移一位", "shift right"))}"))
            row += 3
        }
        val P = (A shl n) or Q
        val value = if (sign == 1) -P else P
        lines += s3(L.t("$n 次做完：部分积接上乘数寄存器就是 |x|·|y| = ${fx(P, 1, 2 * n)}；配上符号位 $sign，[x·y]原 = $sign$sep${binary(P, 2 * n)}，x·y = ${trueValue(value, 2 * n, sep)}",
            "after $n rounds the product followed by the multiplier register is |x|·|y| = ${fx(P, 1, 2 * n)}; with sign $sign: [x·y] sign-magnitude = $sign$sep${binary(P, 2 * n)}, x·y = ${trueValue(value, 2 * n, sep)}"),
            listOf("mark ${S}[$row,${c1 + 2}..${c1 + wA - 1}] done", "mark ${S}[$row,$c2..${c2 + n - 1}] done",
                "label ${S}[$row] ${q(L.t("← 乘积的数值部分", "← the product's magnitude"))}"))
        return lines
    }

    private fun boothMul(X: Int, Y: Int, n: Int, sep: String, S: String, L: AnimKits.Words): List<String> {
        fun fx(v: Int, i: Int, m: Int) = fixed(v, i, m, sep)
        val wA = n + 3
        val h1 = L.t("部分积", "A")
        val h2 = L.t("乘数", "Q")
        val h3 = L.t("附加位", "E")
        val c1 = 1
        val c2 = c1 + max(wA, h1.length) + 2
        val cE = c2 + max(n + 1, h2.length) + 1
        val m = 1 shl (n + 1)
        var Q = ((Y % m) + m) % m
        var E = 0
        var A = 0
        var row = 1
        val lines = mutableListOf(
            "text " + q(L.t("[x]补 = ${fx(X, 2, n)}　[−x]补 = ${fx(-X, 2, n)}　[y]补 = ${fx(Y, 1, n)}",
                "[x]₂c = ${fx(X, 2, n)}   [−x]₂c = ${fx(-X, 2, n)}   [y]₂c = ${fx(Y, 1, n)}")),
            "text " + q(L.t("看乘数最低位 yᵢ 和附加位 yᵢ₊₁：00 / 11 不加，01 加 [x]补，10 加 [−x]补；部分积双符号位、算术右移；共加 ${n + 1} 次、移 $n 次，最后一次只加不移",
                "A: partial product, Q: multiplier, E: extra bit. Look at the lowest multiplier bit yᵢ and the extra bit yᵢ₊₁: 00 / 11 add nothing, 01 adds [x], 10 adds [−x]; the product has two sign bits and shifts arithmetically; ${n + 1} additions, $n shifts, the last addition is not shifted")),
            "sheet $S",
            "write ${S}[0,$c1] ${q(h1)}", "write ${S}[0,$c2] ${q(h2)}", "write ${S}[0,$cE] ${q(h3)}",
            "write ${S}[1,$c1] ${q(fx(0, 2, n))}", "write ${S}[1,$c2] ${q(binary(Q, n + 1))}", "write ${S}[1,$cE] \"0\"",
            "label ${S}[1] ${q(L.t("初始：部分积 0，乘数 [y]补，附加位 0", "start: product 0, multiplier [y], extra bit 0"))}",
        )
        val xTC = L.t("[x]补", "[x]")
        val negTC = L.t("[−x]补", "[−x]")
        val marked = mutableListOf<String>()
        fun unmarkAll(): List<String> { val u = marked.map { "unmark $it" }; marked.clear(); return u }
        for (k in 0..n) {
            val pair = "${Q and 1}$E"
            val pairName = "y" + sub(n - k) + "y" + sub(n - k + 1)
            val addV = if (pair == "01") X else if (pair == "10") -X else 0
            val adds = pair == "01" || pair == "10"
            val acts = unmarkAll().toMutableList()
            acts += listOf("mark ${S}[$row,${c2 + n}] active", "mark ${S}[$row,$cE] active")
            marked += listOf("${S}[$row,${c2 + n}]", "${S}[$row,$cE]")
            var cap = L.t("第 ${k + 1} 次：$pairName = $pair，", "round ${k + 1}: $pairName = $pair: ")
            if (adds) {
                A += addV
                acts += listOf("write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fx(addV, 2, n))}",
                    "label ${S}[${row + 1}] ${q("+" + (if (pair == "01") xTC else negTC))}",
                    "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                    "write ${S}[${row + 2},$c1] ${q(fx(A, 2, n))}")
                cap += L.t("加 ${if (pair == "01") xTC else negTC} = ${fx(addV, 2, n)}，得 ${fx(A, 2, n)}",
                    "add ${if (pair == "01") xTC else negTC} = ${fx(addV, 2, n)}, giving ${fx(A, 2, n)}")
                row += 2
            } else {
                cap += L.t("两位相同，不加", "the bits are equal: add nothing")
            }
            if (k == n) {
                lines += s3(cap + L.t("；这是第 ${n + 1} 次，只加不移", "; this is addition ${n + 1}: no shift after it"), acts)
                break
            }
            val low = A and 1
            val top = if (A < 0) 1 else 0
            E = Q and 1
            Q = (Q shr 1) or (low shl n)
            A = A shr 1
            val shiftActs = listOf("write ${S}[${row + 1},$c1] ${q(fx(A, 2, n))}", "write ${S}[${row + 1},$c2] ${q(binary(Q, n + 1))}",
                "write ${S}[${row + 1},$cE] ${q(E.toString())}", "label ${S}[${row + 1}] ${q(L.t("算术右移一位", "arithmetic shift right"))}")
            val shiftCap = L.t("部分积、乘数、附加位一起算术右移一位（最高位补符号 $top）：部分积最低位 $low 移进乘数最高位，乘数最低位移进附加位",
                "arithmetic shift right (the top is refilled with the sign $top): the product's lowest bit $low moves into the multiplier, the multiplier's lowest bit into the extra bit")
            if (adds) {
                lines += s3(cap, acts)
                lines += s3(shiftCap, unmarkAll() + shiftActs)
            } else {
                lines += s3(cap + L.sep + shiftCap, acts + shiftActs)
            }
            row += 1
        }
        val P = A * (1 shl n) + (Q shr 1)
        lines += s3(L.t("部分积（去掉一位符号）接上乘数寄存器的前 $n 位就是 [x·y]补 = ${fx(P, 1, 2 * n)}（乘数最后一位是 y 的符号位，舍去），x·y = ${trueValue(P, 2 * n, sep)}",
            "the product (dropping one sign bit) followed by the first $n multiplier bits is [x·y]₂c = ${fx(P, 1, 2 * n)} (the last multiplier bit is y's sign, dropped); x·y = ${trueValue(P, 2 * n, sep)}"),
            unmarkAll() + listOf("write ${S}[$row,$c2] ${q(binary(Q shr 1, n))}",
                "mark ${S}[$row,${c1 + 1}..${c1 + wA - 1}] done", "mark ${S}[$row,$c2..${c2 + n - 1}] done",
                "label ${S}[$row] ${q(L.t("← [x·y]补", "← [x·y]₂c"))}"))
        return lines
    }

    // —— 除法 ——

    private fun divKit(a: AnimKits.Args, L: AnimKits.Words): AnimKits.Kit {
        val usage = "usage: kit div method=restore|nonrestore|comp x=0.1011 y=0.1101"
        val method = choice(a, "method", listOf("restore", "nonrestore", "comp"), "nonrestore")
        val x = fraction(a, "x", usage, false)
        val y = fraction(a, "y", usage, false)
        a.checkUnused(listOf("method", "x", "y", "lang"))
        val n = max(x.bits.length, y.bits.length)
        if (method == "restore" && n > 6) throw AnimParseError("method=restore: at most 6 bits after the point")
        val xm = padBits(x, n)
        val ym = padBits(y, n)
        if (ym <= 0) throw AnimParseError("y must not be 0")
        if (xm >= ym) throw AnimParseError("fixed-point fraction division needs |x| < |y| (otherwise the quotient overflows)")
        val S = L.t("竖式", "work")
        val xText = (if (x.neg) "-" else "") + fixed(xm, 1, n)
        val yText = (if (y.neg) "-" else "") + fixed(ym, 1, n)
        val name = when (method) {
            "restore" -> L.t("原码恢复余数法", "Restoring division")
            "nonrestore" -> L.t("原码加减交替法（不恢复余数）", "Non-restoring division (sign-magnitude)")
            else -> L.t("补码加减交替法", "Two's-complement non-restoring division")
        }
        val lines = if (method == "comp") compDiv(if (x.neg) -xm else xm, if (y.neg) -ym else ym, n, S, L)
        else origDiv(method == "restore", x.neg, xm, y.neg, ym, n, S, L)
        return AnimKits.Kit(name + L.t("：x = $xText，y = $yText", ": x = $xText, y = $yText"), lines)
    }

    private class DivHead(val lines: List<String>, val c1: Int, val c2: Int, val wA: Int)

    private fun divHeader(n: Int, S: String, L: AnimKits.Words): DivHead {
        val wA = n + 3
        val h1 = L.t("余数", "A")
        val h2 = L.t("商", "Q")
        val c1 = 1
        val c2 = c1 + max(wA, h1.length) + 2
        return DivHead(listOf("sheet $S", "write ${S}[0,$c1] ${q(h1)}", "write ${S}[0,$c2] ${q(h2)}"), c1, c2, wA)
    }

    private fun origDiv(restore: Boolean, xNeg: Boolean, xm: Int, yNeg: Boolean, ym: Int, n: Int, S: String, L: AnimKits.Words): List<String> {
        val xs = if (xNeg) 1 else 0
        val ys = if (yNeg) 1 else 0
        val sign = xs xor ys
        val head = divHeader(n, S, L)
        val c1 = head.c1
        val c2 = head.c2
        val wA = head.wA
        val subName = L.t("+[−|y|]补", "+[−|y|]")
        val addName = "+|y|"
        val lines = mutableListOf(
            "text " + q(L.t("|x| = ${fixed(xm, 1, n)}　|y| = ${fixed(ym, 1, n)}　[−|y|]补 = ${fixed(-ym, 2, n)}　商的符号 = xs ⊕ ys = $xs ⊕ $ys = $sign",
                "|x| = ${fixed(xm, 1, n)}   |y| = ${fixed(ym, 1, n)}   [−|y|] = ${fixed(-ym, 2, n)}   sign of the quotient = xs ⊕ ys = $xs ⊕ $ys = $sign")),
            "text " + q(if (restore)
                L.t("恢复余数法：每次减 |y|（加 [−|y|]补）；余数为正上商 1，为负上商 0 并加回 |y| 恢复；然后左移一位，共上商 ${n + 1} 位",
                    "A: remainder, Q: quotient. Restoring: subtract |y| each time; a positive remainder gives quotient bit 1, a negative one gives 0 and |y| is added back; then shift left; ${n + 1} quotient bits")
            else
                L.t("加减交替法：余数为正上商 1、左移一位减 |y|；为负上商 0、左移一位加 |y|；共加 ${n + 1} 次、移 $n 次，最后余数为负再加 |y|",
                    "A: remainder, Q: quotient. Non-restoring: a positive remainder gives 1, shift left and subtract |y|; a negative one gives 0, shift left and add |y|; ${n + 1} additions, $n shifts, fix a negative final remainder by adding |y|")),
        )
        lines += head.lines
        lines += listOf("write ${S}[1,$c1] ${q(fixed(xm, 2, n))}", "label ${S}[1] ${q(L.t("被除数 |x|", "dividend |x|"))}")
        var R = xm
        var row = 1
        var sub = true
        val qbits = mutableListOf<Int>()
        val marked = mutableListOf<String>()
        fun unmarkAll(): List<String> { val u = marked.map { "unmark $it" }; marked.clear(); return u }
        for (i in 0..n) {
            val addV = if (sub) -ym else ym
            R += addV
            val qb = if (R >= 0) 1 else 0
            qbits += qb
            val signCells = "${S}[${row + 2},$c1..${c1 + 1}]"
            val acts = unmarkAll().toMutableList()
            acts += listOf("write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fixed(addV, 2, n))}",
                "label ${S}[${row + 1}] ${q(if (sub) subName else addName)}",
                "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                "write ${S}[${row + 2},$c1] ${q(fixed(R, 2, n))}",
                "mark $signCells active",
                "write ${S}[${row + 2},$c2] ${q(quotientText(qbits))}",
                "label ${S}[${row + 2}] ${q(if (qb == 1) L.t("正，上商 1", "≥ 0: bit 1") else L.t("负，上商 0", "< 0: bit 0"))}")
            marked += signCells
            val did = if (sub) L.t("减 |y|（加 [−|y|]补）", "subtract |y| (add [−|y|])") else L.t("加 |y|", "add |y|")
            var cap = L.t("第 ${i + 1} 次：$did，余数 ${fixed(R, 2, n)}，符号位 ${if (R < 0) 1 else 0}，", "round ${i + 1}: $did: remainder ${fixed(R, 2, n)}, sign ${if (R < 0) 1 else 0}, ") +
                (if (qb == 1) L.t("为正，上商 1", "positive: quotient bit 1") else L.t("为负，上商 0", "negative: quotient bit 0"))
            if (i == 0) cap += L.t("（第一位商是 0，说明 |x| < |y|，不溢出）", " (the first bit is 0: |x| < |y|, no overflow)")
            lines += s3(cap, acts)
            row += 2
            if (restore && qb == 0) {
                R += ym
                lines += s3(L.t("余数为负，减多了：加回 |y|，恢复成 ${fixed(R, 2, n)}", "the remainder is negative, so add |y| back: restored to ${fixed(R, 2, n)}"),
                    unmarkAll() + listOf("write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fixed(ym, 2, n))}",
                        "label ${S}[${row + 1}] ${q(L.t("+|y| 恢复", "+|y| restore"))}",
                        "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                        "write ${S}[${row + 2},$c1] ${q(fixed(R, 2, n))}"))
                row += 2
            }
            if (i == n) break
            R *= 2
            val next = if (restore) L.t("下一次再减 |y|", "next: subtract |y| again")
            else if (qb == 1) L.t("商 1，下一次减 |y|", "bit 1: next subtract |y|") else L.t("商 0，下一次加 |y|", "bit 0: next add |y|")
            lines += s3(L.t("余数左移一位：${fixed(R, 2, n)}；", "shift the remainder left: ${fixed(R, 2, n)}; ") + next,
                unmarkAll() + listOf("write ${S}[${row + 1},$c1] ${q(fixed(R, 2, n))}", "label ${S}[${row + 1}] ${q(L.t("左移一位", "shift left"))}"))
            row += 1
            sub = restore || qb == 1
        }
        if (!restore && R < 0) {
            R += ym
            lines += s3(L.t("最后的余数为负，加 |y| 恢复成正余数 ${fixed(R, 2, n)}（商不变）", "the final remainder is negative: add |y| to make it ${fixed(R, 2, n)} (the quotient stays)"),
                unmarkAll() + listOf("write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fixed(ym, 2, n))}",
                    "label ${S}[${row + 1}] ${q(L.t("+|y| 恢复余数", "+|y| fix remainder"))}",
                    "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                    "write ${S}[${row + 2},$c1] ${q(fixed(R, 2, n))}"))
            row += 2
        }
        val qm = qbits.fold(0) { acc, b -> acc * 2 + b }
        val qv = if (sign == 1) -qm else qm
        val rv = if (xs == 1) -R else R
        val qFrac = binary(qm, n)
        lines += s3(L.t("商的第一位 0 换成符号位 $sign：[x÷y]原 = $sign.$qFrac，x ÷ y = ${trueValue(qv, n)}；余数与被除数同号：${trueValue(rv, n)} × 2${superscriptMinus(n)}",
            "replace the first quotient bit 0 by the sign $sign: [x÷y] sign-magnitude = $sign.$qFrac, x ÷ y = ${trueValue(qv, n)}; the remainder takes the dividend's sign: ${trueValue(rv, n)} × 2${superscriptMinus(n)}"),
            unmarkAll() + listOf("write ${S}[$row,$c2] ${q(quotientText(qbits))}",
                "mark ${S}[$row,${c2 + 2}..${c2 + n + 1}] done", "mark ${S}[$row,${c1 + 3}..${c1 + wA - 1}] done",
                "label ${S}[$row] ${q(L.t("← 余数 × 2${superscriptMinus(n)}、商", "← remainder × 2${superscriptMinus(n)}, quotient"))}"))
        return lines
    }

    private fun compDiv(X: Int, Y: Int, n: Int, S: String, L: AnimKits.Words): List<String> {
        val head = divHeader(n, S, L)
        val c1 = head.c1
        val c2 = head.c2
        val wA = head.wA
        fun same(a: Int, b: Int) = (a < 0) == (b < 0)
        val yTC = L.t("[y]补", "[y]")
        val negTC = L.t("[−y]补", "[−y]")
        val lines = mutableListOf(
            "text " + q(L.t("[x]补 = ${fixed(X, 2, n)}　[y]补 = ${fixed(Y, 2, n)}　[−y]补 = ${fixed(-Y, 2, n)}",
                "[x]₂c = ${fixed(X, 2, n)}   [y]₂c = ${fixed(Y, 2, n)}   [−y]₂c = ${fixed(-Y, 2, n)}")),
            "text " + q(L.t("符号位参与运算。第一次：x、y 同号减 y、异号加 y；之后余数与 y 同号上商 1、左移减 y，异号上商 0、左移加 y；上 $n 位商后末位恒置 1",
                "A: remainder, Q: quotient. Sign bits take part. First: subtract y if x and y have the same sign, else add y; then a remainder with y's sign gives 1, shift left and subtract y, otherwise 0, shift left and add y; after $n bits the last bit is set to 1")),
        )
        lines += head.lines
        lines += listOf("write ${S}[1,$c1] ${q(fixed(X, 2, n))}", "label ${S}[1] ${q(L.t("被除数 [x]补", "dividend [x]"))}")
        var R = X
        var row = 1
        var sub = same(X, Y)
        val qbits = mutableListOf<Int>()
        val marked = mutableListOf<String>()
        fun unmarkAll(): List<String> { val u = marked.map { "unmark $it" }; marked.clear(); return u }
        for (i in 0..n) {
            val addV = if (sub) -Y else Y
            R += addV
            val acts = unmarkAll().toMutableList()
            acts += listOf("write ${S}[${row + 1},0] \"+\"", "write ${S}[${row + 1},$c1] ${q(fixed(addV, 2, n))}",
                "label ${S}[${row + 1}] ${q("+" + (if (sub) negTC else yTC))}",
                "rule ${S}[${row + 1},$c1..${c1 + wA - 1}]",
                "write ${S}[${row + 2},$c1] ${q(fixed(R, 2, n))}")
            val did = if (sub) L.t("减 y（加 [−y]补）", "subtract y (add [−y])") else L.t("加 y（加 [y]补）", "add y (add [y])")
            var cap = if (i == 0)
                L.t("x 与 y ${if (same(X, Y)) "同号" else "异号"}，先$did，余数 ${fixed(R, 2, n)}", "x and y have ${if (same(X, Y)) "the same sign" else "different signs"}: $did first: ${fixed(R, 2, n)}")
            else
                L.t("第 ${i + 1} 次：$did，余数 ${fixed(R, 2, n)}", "round ${i + 1}: $did: ${fixed(R, 2, n)}")
            if (i < n) {
                val qb = if (same(R, Y)) 1 else 0
                qbits += qb
                val signCells = "${S}[${row + 2},$c1..${c1 + 1}]"
                acts += listOf("mark $signCells active", "write ${S}[${row + 2},$c2] ${q(quotientText(qbits))}",
                    "label ${S}[${row + 2}] ${q(if (qb == 1) L.t("与 y 同号，上商 1", "same sign as y: 1") else L.t("与 y 异号，上商 0", "sign differs from y: 0"))}")
                marked += signCells
                cap += L.t("，与 y ${if (qb == 1) "同号，上商 1" else "异号，上商 0"}", if (qb == 1) ", same sign as y: quotient bit 1" else ", sign differs from y: quotient bit 0")
                lines += s3(cap, acts)
                row += 2
                R *= 2
                sub = qb == 1
                lines += s3(L.t("余数左移一位：${fixed(R, 2, n)}；", "shift the remainder left: ${fixed(R, 2, n)}; ") +
                    (if (qb == 1) L.t("商 1，下一次减 y", "bit 1: next subtract y") else L.t("商 0，下一次加 y", "bit 0: next add y")),
                    unmarkAll() + listOf("write ${S}[${row + 1},$c1] ${q(fixed(R, 2, n))}", "label ${S}[${row + 1}] ${q(L.t("左移一位", "shift left"))}"))
                row += 1
            } else {
                lines += s3(cap + L.t("；已经上了 $n 位商，这次的结果就是余数", "; $n quotient bits are done, this is the remainder"), acts)
                row += 2
            }
        }
        qbits += 1
        val u = qbits.fold(0) { acc, b -> acc * 2 + b }
        val qv = if (u >= (1 shl n)) u - (1 shl (n + 1)) else u
        val qText = quotientText(qbits)
        lines += s3(L.t("商的末位恒置 1：[x÷y]补 = $qText，x ÷ y = ${trueValue(qv, n)}；余数 [r]补 = ${fixed(R, 1, n)} × 2${superscriptMinus(n)}，即 ${trueValue(R, n)} × 2${superscriptMinus(n)}",
            "set the last quotient bit to 1: [x÷y]₂c = $qText, x ÷ y = ${trueValue(qv, n)}; remainder [r]₂c = ${fixed(R, 1, n)} × 2${superscriptMinus(n)}, that is ${trueValue(R, n)} × 2${superscriptMinus(n)}"),
            listOf("write ${S}[$row,$c2] ${q(qText)}", "mark ${S}[$row,$c2..${c2 + n + 1}] done", "mark ${S}[$row,${c1 + 1}..${c1 + wA - 1}] done",
                "label ${S}[$row] ${q(L.t("← 余数 × 2${superscriptMinus(n)}、商（末位恒置 1）", "← remainder × 2${superscriptMinus(n)}, quotient (last bit 1)"))}"))
        return lines
    }
}
