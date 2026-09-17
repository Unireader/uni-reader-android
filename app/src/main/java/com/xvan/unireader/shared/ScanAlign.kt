package com.xvan.unireader.shared

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

/**
 * 扫描页对齐（`../SCAN-ALIGN-PLAN.md`）的**读端**纯逻辑：每页变换 + 参数表解码 + 显示身份。
 * 纯 Kotlin、不认识 android.*，`ScanAlignTest` 在 JVM 上直接跑。
 *
 * 🔴 **与 Mac `Sources/App/ScanAlign.swift` 的 `PageAlign` / `ScanAlignTable` 同款算法两份实现**：
 * §2 的变换公式、§3 的 payload 解码规则与戳的算法，改一边必须同步另一边——两端差一个符号，
 * 同一条笔迹在两端就落在不同位置（而且是「整页微微转了一点」那种说不清的偏）。
 *
 * **测量只在 Mac 做**（要跑像素统计），所以这里没有编码器、没有 `ScanAlignDetector`/`Solver`：
 * 安卓只读库里 Mac 写好的那一行。
 */

/**
 * 一页的对齐参数 + 所在文档的目标页宽。坐标一律「显示空间、左上原点、y 向下、pt」。
 *
 * - 原始显示空间：尺寸 `(sw, sh)`（effective box 按 `/Rotate` 转正后的样子，即模式1 `PdfSource.displaySize` 算的那个）；
 * - 对齐显示空间：尺寸 `(w, sh)`。开着对齐时，所有页内归一化坐标都相对它。
 */
data class PageAlign(
    /** 原图里文字行的斜率角（弧度，y 向下时「往右往下斜」为正） */
    val rot: Double,
    val dx: Double,
    val dy: Double,
    val sw: Double,
    val sh: Double,
    /** 文档统一的目标页宽（[ScanAlignTable.width]） */
    val w: Double,
) {
    /** 原始 → 对齐（方案 §2.2 正变换），返回 `[x, y]` */
    fun toAligned(x: Double, y: Double): DoubleArray {
        val c = cos(rot)
        val s = sin(rot)
        val u = x - sw / 2
        val v = y - sh / 2
        return doubleArrayOf(c * u + s * v + w / 2 + dx, -s * u + c * v + sh / 2 + dy)
    }

    /** 对齐 → 原始（方案 §2.2 逆变换），返回 `[x, y]` */
    fun toRaw(x: Double, y: Double): DoubleArray {
        val c = cos(rot)
        val s = sin(rot)
        val u = x - w / 2 - dx
        val v = y - sh / 2 - dy
        return doubleArrayOf(c * u - s * v + sw / 2, s * u + c * v + sh / 2)
    }

    /**
     * `android.graphics.Matrix`（y 向下）前两行的六个系数 `[c, s, tx, −s, c, ty]`，与 [toAligned] 逐式等价
     * （方案 §2.2 末尾那组，`setValues` 时后面补 `0, 0, 1`）。放在这里而不是出图处现算，是为了让
     * JVM 单测能直接核对「系数 ≡ 公式」——`Matrix` 本身在 JVM 单测里是空壳。
     */
    fun matrixValues(): DoubleArray {
        val c = cos(rot)
        val s = sin(rot)
        return doubleArrayOf(
            c, s, w / 2 + dx - c * sw / 2 - s * sh / 2,
            -s, c, sh / 2 + dy + s * sw / 2 - c * sh / 2,
        )
    }
}

/**
 * 一份文件的对齐参数表（`page_align.payload` 解出来的样子）。
 *
 * [payload] 是**落库的原字节**——[stamp] 按它算，**不重新编码**（Mac 写的数字格式本端未必逐字复现，
 * 重编码会让同一份参数两端算出两个戳，页图缓存键就对不上了）。
 */
class ScanAlignTable private constructor(
    val width: Double,
    val pages: List<Page>,
    private val raw: ByteArray,
) {
    /** 每页一组 `[rot, dx, dy, sw, sh]` */
    data class Page(val rot: Double, val dx: Double, val dy: Double, val sw: Double, val sh: Double)

    /** `SHA-256(payload)` 的前 8 个十六进制小写字符（方案 §3.1） */
    val stamp: String = stamp(raw)

    val pageCount: Int get() = pages.size

    /** 原字节的副本（别让调用方改到表里那份） */
    val payload: ByteArray get() = raw.copyOf()

    fun page(i: Int): PageAlign? {
        val p = pages.getOrNull(i) ?: return null
        return PageAlign(p.rot, p.dx, p.dy, p.sw, p.sh, width)
    }

    /** 同 Mac：两张表相等 = payload 逐字节相等 */
    override fun equals(other: Any?): Boolean = other is ScanAlignTable && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    companion object {
        const val FORMAT_VERSION = 1

        /**
         * 从库里的 payload 解出来。格式版本不认识、页数对不上、数值不合法 → null（按没开对齐处理）。
         * [pageCount] 是 `page_align.page_count` 那一列（与 Mac `decode(_:pageCount:)` 同一个参数）。
         */
        fun decode(data: ByteArray, pageCount: Int): ScanAlignTable? {
            val p = parse(data) ?: return null
            if (p.second.size != pageCount) return null
            return ScanAlignTable(p.first, p.second, data.copyOf())
        }

        fun stamp(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).take(4).joinToString("") { "%02x".format(it) }

        /** 显示身份（缓存键，方案 §3.1）：没开 = 内容 hash；开着 = `hash~a戳` */
        fun displayKey(contentHash: String, table: ScanAlignTable?): String =
            if (table == null || contentHash.isEmpty()) contentHash else contentHash + "~a" + table.stamp

        /**
         * 逐条对齐 Mac `ScanAlignTable.parse`：
         * - 顶层是对象，`v` 截断取整后 == 1（Swift 是 `NSNumber.intValue`，`1.9` 也算 1）；
         * - `w` > 0 且有限；
         * - `pages` 是「数组的数组」且**每个元素都是数**（Swift 的 `as? [[NSNumber]]`，混进一个
         *   `null`/字符串整张表作废）；每行至少 5 个、前 5 个有限、`sw`/`sh` > 0。
         *
         * 布尔当 1/0 收：`JSONSerialization` 把 `true`/`false` 解成 `NSNumber`，上面那道强转会放行，
         * 这里照抄，免得同一份（本不该出现的）payload 两端一个认一个不认。
         */
        private fun parse(data: ByteArray): Pair<Double, List<Page>>? {
            val obj = MiniJson.parse(data) as? Map<*, *> ?: return null
            val v = num(obj["v"]) ?: return null
            if (v.toLong() != FORMAT_VERSION.toLong()) return null
            val w = num(obj["w"]) ?: return null
            if (!(w > 0) || !w.isFinite()) return null
            val arr = obj["pages"] as? List<*> ?: return null
            val pages = ArrayList<Page>(arr.size)
            for (row in arr) {
                val r = row as? List<*> ?: return null
                val nums = r.map { num(it) ?: return null }
                if (nums.size < 5) return null
                if (!nums.take(5).all { it.isFinite() } || !(nums[3] > 0) || !(nums[4] > 0)) return null
                pages.add(Page(nums[0], nums[1], nums[2], nums[3], nums[4]))
            }
            return w to pages
        }

        private fun num(x: Any?): Double? = when (x) {
            is Double -> x
            is Boolean -> if (x) 1.0 else 0.0
            else -> null
        }
    }
}

/**
 * 够用就好的严格 JSON 解析（RFC 8259 语法）：对象 → `Map`，数组 → `List`，数 → `Double`，
 * 另有 `String` / `Boolean` / `null`。
 *
 * **为什么不用 `org.json`**：它在 JVM 单测里是 android.jar 的空壳（调用即抛 `Stub!`），
 * 而解码规则恰恰是最需要在 JVM 上钉死的跨端契约。只给 [ScanAlignTable] 用，别拿去解大文件。
 */
internal object MiniJson {

    private class Bad : RuntimeException()

    /** 解不了（语法错、非法 UTF-8、尾部有垃圾）→ null */
    fun parse(data: ByteArray): Any? = try {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
        val p = P(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) null else v
    } catch (e: Bad) {
        null
    } catch (e: java.nio.charset.CharacterCodingException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    private class P(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw Bad()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> number()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad()
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw Bad()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw Bad()
                i++
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) throw Bad()
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw Bad()
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                if (i >= s.length) throw Bad()
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw Bad()
                }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++ // "
            while (true) {
                if (i >= s.length) throw Bad()
                val ch = s[i++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (i >= s.length) throw Bad()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad()
                                val hex = s.substring(i, i + 4)
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Bad()
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw Bad()
                        }
                    }
                    ch.code < 0x20 -> throw Bad()
                    else -> sb.append(ch)
                }
            }
        }

        /** `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?` */
        private fun number(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) throw Bad()
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i].isAsciiDigit()) i++
            } else {
                throw Bad()
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || !s[i].isAsciiDigit()) throw Bad()
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || !s[i].isAsciiDigit()) throw Bad()
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            return s.substring(start, i).toDouble()
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
