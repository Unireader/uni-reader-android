package com.xvan.unireader.local.store

import android.util.Log
import com.xvan.unireader.shared.NOTE_ALWAYS
import com.xvan.unireader.shared.NOTE_HOVER
import com.xvan.unireader.shared.NOTE_TAP
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextRun
import com.xvan.unireader.shared.brushCode
import com.xvan.unireader.shared.brushName
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * `note.payload` 的 JSON 形态，对应 Mac 端 `InkModel.swift` / `TextNoteModel.swift` 的
 * `InkStrokePayload` / `TextNotePayload`。
 *
 * **一律持有原始 `JSONObject` 并在其上改**：payload 里可能有本端还不认识的键
 * （Mac 先加、安卓后跟；或反过来），整体重建会把它们抹掉——那就是「安卓打开过的工作区回到 Mac
 * 上笔记类型丢了」这种最难查的丢数据。改点数就只 `put("points", …)`，其余原样留着。
 */

// ---------- 笔迹（kind=2） ----------

class InkPayload(val raw: JSONObject) {

    val colorR: Double get() = color().optDouble("r", 24.0)
    val colorG: Double get() = color().optDouble("g", 90.0)
    val colorB: Double get() = color().optDouble("b", 210.0)
    val colorA: Double get() = color().optDouble("a", 0.95)
    val width: Double get() = raw.optDouble("width", 8.0)

    /** 笔型字符串（`ballpoint`/`fountain`/`marker`/`pencil`）；旧 payload 无此键 → ballpoint */
    val type: String get() = raw.optString("type").ifEmpty { "ballpoint" }

    /** 所属图层；旧 payload 无此键 → [LibInkLayer.DEFAULT_ID]（同 Mac 兜底） */
    val layerId: String get() = raw.optString("layerId").ifEmpty { LibInkLayer.DEFAULT_ID }

    /**
     * 所属草稿纸（`scratch_pad.id`）；**无此键 = 页内笔迹**（kind=2），同 Mac 的
     * `decodeIfPresent(padId)`。草稿纸笔迹（kind=4）缺这个键是坏数据（无处可归的孤儿），
     * 由读取侧丢弃——见 [LibraryStore.scratchStrokes]。
     */
    val padId: String? get() = raw.optString("padId").ifEmpty { null }

    private fun color(): JSONObject = raw.optJSONObject("color") ?: JSONObject()

    fun points(): List<Pt3> {
        val arr = raw.optJSONArray("points") ?: return emptyList()
        val out = ArrayList<Pt3>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            // 缺压感的点按 0.5 兜底（同 Mac 的 z 默认值）
            out.add(
                Pt3(
                    p.optDouble(0, 0.0).toFloat(),
                    p.optDouble(1, 0.0).toFloat(),
                    if (p.length() > 2) p.optDouble(2, 0.5).toFloat() else 0.5f,
                ),
            )
        }
        return out
    }

    /**
     * 转成渲染用的中立模型。`id`/`layerId` 挂在 [Stroke] 上：擦除时要靠 `note.id`
     * 一一映射删除（`ANDROID-STANDALONE-PLAN.md §6`），图层过滤要靠 layerId。
     * `padId` 一并带上（空串 = 页内笔迹）：草稿纸笔迹被局部擦除切段时全靠它才不会丢归属。
     */
    fun toStroke(id: String, page: Int): Stroke = Stroke(
        page = page.toLong(),
        pen = Pen(
            r = colorR.toInt().coerceIn(0, 255),
            g = colorG.toInt().coerceIn(0, 255),
            b = colorB.toInt().coerceIn(0, 255),
            a = colorA.toFloat(),
            w = width.toFloat(),
            brush = brushCode(type),
        ),
        pts = points(),
        id = id,
        layerId = layerId,
        padId = padId ?: "",
    )

    /** 只替换点集（局部擦除切段 / 框选平移后回写），其余键原样保留 */
    fun withPoints(pts: List<Pt3>): InkPayload {
        val arr = JSONArray()
        for (p in pts) arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.p.toDouble()))
        raw.put("points", arr)
        return this
    }

    /** 只改线宽（框选缩放后回写：笔宽 ×√(sx·sy)，调用方已 clamp），其余键原样保留 */
    fun withWidth(w: Double): InkPayload {
        raw.put("width", w)
        return this
    }

    /**
     * 只写/摘 `padId` 键（null = 摘掉），其余键原样保留——**原地改，不整体重建**
     * （本端不认识的键一个都不许丢，见文件头纪律）。
     */
    fun withPadId(padId: String?): InkPayload {
        if (padId == null) raw.remove("padId") else raw.put("padId", padId)
        return this
    }

    /**
     * 分页画板上的笔迹（board_item kind=1，v17）：所属那一页的 id（`board_page.id`），此时点是**页内坐标**。
     * 无此键 = 无限画布上的笔迹（点是画布坐标）。同 Mac `InkStrokePayload.page`。
     */
    val page: String? get() = raw.optString("page").ifEmpty { null }

    /** 只写/摘 `page` 键（null = 摘掉），其余键原样保留 */
    fun withPage(page: String?): InkPayload {
        if (page == null) raw.remove("page") else raw.put("page", page)
        return this
    }

    fun bytes(): ByteArray = raw.toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val TAG = "UniReader/Payload"

        fun parse(bytes: ByteArray): InkPayload? = try {
            InkPayload(JSONObject(String(bytes, StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "笔迹 payload 解析失败（${bytes.size}B），跳过该条", e)
            null
        }

        /**
         * **只读**快路径：读成 [Stroke] + payload 的 `page` 键（分页画板用，无此键 = null）。
         * 点集用 [InkPointsScan] 直接扫字符串，不建 `JSONArray`——整段交给 `JSONObject` 时每个点都是一个
         * 小数组对象，1640 条 / 16.8 万点的画板光解析就要 2.4s（2026-09-26 实测）。其余小字段（颜色 / 线宽 /
         * 笔型 / 图层 / padId / page）仍交给 `JSONObject`。形态认不出就退回 [parse] 整段解析，不丢数据。
         * 这里不产出 [InkPayload]，所以不可能拿「点集被摘掉的 payload」去回写。
         */
        fun readStroke(
            bytes: ByteArray, id: String, page: Int,
            blob: ByteArray? = null, blobValid: Boolean = false,
        ): Pair<Stroke, String?>? {
            val s = String(bytes, StandardCharsets.UTF_8)
            val span = InkPointsScan.span(s)
            // v18 规则（`../BINARY-INK-PLAN.md §3`，Mac `InkStrokePayload.read` 同一份）：
            //  1. 二进制有效（points_at == updated_at）→ 用二进制，JSON 点不解；
            //  2. 否则 JSON 里有点 → 用 JSON；3. 否则有二进制 → 用二进制（已清理兼容数据）；4. 都没有 → 空。
            if (blobValid && span != null) {
                val bp = InkPointsBlob.decode(blob)
                if (bp != null) {
                    try {
                        val rest = InkPayload(JSONObject(s.substring(0, span.first) + "[]" + s.substring(span.last + 1)))
                        return rest.toStroke(id, page).copy(pts = bp) to rest.page
                    } catch (_: Exception) {
                        // 退回下面的路径
                    }
                }
            }
            val r = readJsonStroke(s, span, bytes, id, page) ?: return null
            if (r.first.pts.isEmpty()) {
                InkPointsBlob.decode(blob)?.let { return r.first.copy(pts = it) to r.second }
            }
            return r
        }

        /** 只读 payload 里的 **JSON 点**（v18 迁移用：编成二进制补进 `points` 列）。解不出 → null */
        fun jsonPoints(bytes: ByteArray): List<Pt3>? {
            val s = String(bytes, StandardCharsets.UTF_8)
            val span = InkPointsScan.span(s)
            if (span != null) InkPointsScan.points(s, span.first, span.last)?.let { return it }
            return parse(bytes)?.points()
        }

        /**
         * 把 `"points":[…]` 摘成 `"points":[]`（已清理兼容数据时写库前用，`../BINARY-INK-PLAN.md §4`）。
         * 返回 (换过的 payload, 原来有没有点)；找不到 points 键 → null。只配方括号，不解数字。
         */
        fun stripPoints(bytes: ByteArray): Pair<ByteArray, Boolean>? {
            val s = String(bytes, StandardCharsets.UTF_8)
            val span = InkPointsScan.span(s) ?: return null
            val had = s.indexOf('[', span.first + 1).let { it in 0 until span.last }
            val rest = s.substring(0, span.first) + "[]" + s.substring(span.last + 1)
            return rest.toByteArray(StandardCharsets.UTF_8) to had
        }

        private fun readJsonStroke(
            s: String, span: IntRange?, bytes: ByteArray, id: String, page: Int,
        ): Pair<Stroke, String?>? {
            if (span != null) {
                val pts = InkPointsScan.points(s, span.first, span.last)
                if (pts != null) {
                    try {
                        val rest = InkPayload(JSONObject(s.substring(0, span.first) + "[]" + s.substring(span.last + 1)))
                        return rest.toStroke(id, page).copy(pts = pts) to rest.page
                    } catch (_: Exception) {
                        // 退回整段解析
                    }
                }
            }
            val p = parse(bytes) ?: return null
            return p.toStroke(id, page) to p.page
        }

        /** 新笔迹落库：键名与 Mac 端 `InkStrokePayload` 完全一致（color/width/type/points/layerId） */
        fun of(pen: Pen, pts: List<Pt3>, layerId: String, padId: String? = null): InkPayload {
            val o = JSONObject()
            o.put(
                "color",
                JSONObject()
                    .put("r", pen.r.toDouble())
                    .put("g", pen.g.toDouble())
                    .put("b", pen.b.toDouble())
                    .put("a", pen.a.toDouble()),
            )
            o.put("width", pen.w.toDouble())
            o.put("type", brushName(pen.brush))
            o.put("layerId", layerId)
            // 页内笔迹不写 padId（键不存在 = 不是草稿纸笔迹），保持既有 payload 逐字节不变（同 Mac）。
            if (padId != null) o.put("padId", padId)
            return InkPayload(o).withPoints(pts)
        }
    }
}

/**
 * 笔迹 payload 里 `"points":[[x,y,z],…]` 的手写扫描（只读快路径，见 [InkPayload.readStroke]）。
 * 数值仍用 `Double.parseDouble` 再转 Float，与 `JSONArray.optDouble(...).toFloat()` 逐位相同；
 * 缺压感的点按 0.5（同 [InkPayload.points]）。遇到认不出的字符返回 null，由调用方退回整段解析。
 */
internal object InkPointsScan {

    /** 顶层 `"points"` 键的值：`[` 与配对 `]` 的下标；找不到 = null */
    fun span(s: String): IntRange? {
        var k = s.indexOf("\"points\"")
        while (k >= 0) {
            if (k == 0 || s[k - 1] != '\\') {
                var i = k + 8
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == ':') {
                    i++
                    while (i < s.length && s[i].isWhitespace()) i++
                    if (i < s.length && s[i] == '[') {
                        var depth = 0
                        for (j in i until s.length) {
                            when (s[j]) {
                                '[' -> depth++
                                ']' -> { depth--; if (depth == 0) return i..j }
                                '"', '{', '}' -> return null   // 点集里不该有字符串 / 对象
                            }
                        }
                        return null
                    }
                }
            }
            k = s.indexOf("\"points\"", k + 1)
        }
        return null
    }

    /** 扫 `[open, close]` 之间的点集；形态不对 = null */
    fun points(s: String, open: Int, close: Int): List<Pt3>? {
        val out = ArrayList<Pt3>()
        val v = DoubleArray(3)
        var n = -1            // -1 = 不在某个点的方括号里；否则 = 这个点已读到几个数
        var i = open + 1
        while (i < close) {
            val c = s[i]
            when {
                c == '[' -> { if (n >= 0) return null; n = 0; i++ }
                c == ']' -> {
                    if (n < 0) return null
                    out.add(Pt3(if (n > 0) v[0].toFloat() else 0f, if (n > 1) v[1].toFloat() else 0f,
                        if (n > 2) v[2].toFloat() else 0.5f))
                    n = -1; i++
                }
                c == ',' || c.isWhitespace() -> i++
                c == '-' || c == '+' || c == '.' || c in '0'..'9' -> {
                    if (n < 0) return null
                    var j = i + 1
                    while (j < close) {
                        val d = s[j]
                        if (d in '0'..'9' || d == '.' || d == 'e' || d == 'E' || d == '-' || d == '+') j++ else break
                    }
                    // 别用 Kotlin 的 toDoubleOrNull：它每次先跑一遍正则校验形态，16.8 万点光这一步就一秒多
                    val x = try { java.lang.Double.parseDouble(s.substring(i, j)) } catch (_: NumberFormatException) { return null }
                    if (n < 3) v[n] = x
                    n++
                    i = j
                }
                else -> return null
            }
        }
        return if (n < 0) out else null
    }
}

/** 归一化点集的包围盒（作 `note` 的 anchor，同 Mac `InkStroke.normalizedBounds`）；空集为全 0 */
fun boundsOf(pts: List<Pt3>): DoubleArray {
    if (pts.isEmpty()) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    var minX = pts[0].x
    var maxX = pts[0].x
    var minY = pts[0].y
    var maxY = pts[0].y
    for (p in pts) {
        minX = min(minX, p.x); maxX = max(maxX, p.x)
        minY = min(minY, p.y); maxY = max(maxY, p.y)
    }
    return doubleArrayOf(
        minX.toDouble(), minY.toDouble(),
        (maxX - minX).toDouble(), (maxY - minY).toDouble(),
    )
}

// ---------- 文字注解（kind=0）与高亮（kind=3） ----------

/**
 * 首版只**渲染**已有的文字注解与高亮（新建高亮要先有文字选择，属下一版），但仍要能无损回写：
 * `quote`/`rects`/`color`/`type_id` 一个都不能在安卓这边丢（红线同上）。
 */
/**
 * 书签 payload（note kind=5，`../REQUIREMENTS.md §1.9`）：页/页内位置走 note 的列，
 * 这里只剩名字。键名与 Mac 端 `BookmarkPayload` 一致（`title`）。
 */
class BookmarkPayload(val raw: JSONObject) {

    val title: String get() = raw.optString("title")

    fun bytes(): ByteArray = raw.toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): BookmarkPayload? = try {
            BookmarkPayload(JSONObject(String(bytes, StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(InkPayload.TAG, "书签 payload 解析失败（${bytes.size}B），跳过该条", e)
            null
        }

        fun of(title: String): BookmarkPayload =
            BookmarkPayload(JSONObject().put("title", title))
    }
}

class TextNotePayload(val raw: JSONObject) {

    val quote: String get() = raw.optString("quote")
    val text: String get() = raw.optString("text")

    /** 笔记类型 id（JSON 键是 snake_case 的 `type_id`）；缺键/空 → null＝通用 */
    val typeId: String? get() = raw.optString("type_id").ifEmpty { null }

    /**
     * 展开方式（JSON 键 `display`，值是 Mac `NoteDisplay` 的小写串）：
     * `tap`=点击 `hover`=悬停 `always`=始终。缺键/未知值 → [NOTE_TAP]（零迁移，同 `type_id` 先例）。
     */
    val display: Int get() = when (raw.optString("display")) {
        "hover" -> NOTE_HOVER
        "always" -> NOTE_ALWAYS
        else -> NOTE_TAP
    }

    /** 只改展开方式（正文/锚点/引文一个不动） */
    fun withDisplay(d: Int): TextNotePayload {
        raw.put("display", displayKey(d))
        return this
    }

    /** 选区逐行归一化框 `[x, y, w, h]`（渲染精确高亮用） */
    fun rects(): List<DoubleArray> {
        val arr = raw.optJSONArray("rects") ?: return emptyList()
        val out = ArrayList<DoubleArray>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            out.add(
                doubleArrayOf(
                    r.optDouble(0, 0.0), r.optDouble(1, 0.0),
                    r.optDouble(2, 0.0), r.optDouble(3, 0.0),
                ),
            )
        }
        return out
    }

    /** 高亮/注解色（0~255 的 r/g/b + 0~1 的 a）；无 color 键返回 null（按类型色渲染） */
    fun color(): DoubleArray? {
        val c = raw.optJSONObject("color") ?: return null
        return doubleArrayOf(
            c.optDouble("r", 0.0), c.optDouble("g", 0.0),
            c.optDouble("b", 0.0), c.optDouble("a", 1.0),
        )
    }

    fun withText(s: String): TextNotePayload {
        raw.put("text", s)
        return this
    }

    /** 只替换逐行框（框选移动时随 anchor 一起平移），其余键原样保留 */
    fun withRects(rs: List<DoubleArray>): TextNotePayload {
        val arr = JSONArray()
        for (r in rs) arr.put(JSONArray().put(r[0]).put(r[1]).put(r[2]).put(r[3]))
        raw.put("rects", arr)
        return this
    }

    fun bytes(): ByteArray = raw.toString().toByteArray(StandardCharsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): TextNotePayload? = try {
            TextNotePayload(JSONObject(String(bytes, StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(InkPayload.TAG, "文字注解 payload 解析失败（${bytes.size}B），跳过该条", e)
            null
        }

        /** 新建点注解（无选区）：quote/rects 空、无 color，键集与 Mac 一致 */
        fun ofPointNote(text: String, typeId: String? = null, display: Int = NOTE_TAP): TextNotePayload {
            val o = JSONObject()
            o.put("quote", "")
            o.put("text", text)
            o.put("rects", JSONArray())
            if (typeId != null) o.put("type_id", typeId)
            o.put("display", displayKey(display))
            return TextNotePayload(o)
        }

        /** 展开方式 → payload 里的串（Mac `NoteDisplay.rawValue`，跨平台可读） */
        fun displayKey(d: Int): String = when (d) {
            NOTE_HOVER -> "hover"
            NOTE_ALWAYS -> "always"
            else -> "tap"
        }
    }
}

/**
 * `ocr_page.payload` 的 JSON 形态（跨平台契约，Mac `OCRPagePayload`）：
 * `{ w, h, runs:[{text, x, y, w, h, chars?}] }`，框是**页内归一化 0~1、左上原点**。
 *
 * 安卓**只读不写**：本机不跑 OCR，这张表里的东西一律是 Mac 写的（随离线镜像同步过来）。
 * 解析失败一律当作"这页没有文本层"——划字划不动比划出一堆乱码强。
 */
object OcrPagePayload {

    /** 解析一页；坏 payload 返回空表并记一笔（别让一页坏数据把整本的划字功能拖没） */
    fun parse(bytes: ByteArray): List<TextRun> = try {
        val o = JSONObject(String(bytes, StandardCharsets.UTF_8))
        val arr = o.optJSONArray("runs") ?: JSONArray()
        val out = ArrayList<TextRun>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val text = r.optString("text")
            if (text.isEmpty()) continue
            out.add(
                TextRun(
                    text = text,
                    x = r.optDouble("x", 0.0).toFloat(),
                    y = r.optDouble("y", 0.0).toFloat(),
                    w = r.optDouble("w", 0.0).toFloat(),
                    h = r.optDouble("h", 0.0).toFloat(),
                    chars = chars(r),
                ),
            )
        }
        out
    } catch (e: Exception) {
        Log.w(InkPayload.TAG, "OCR payload 解析失败（${bytes.size}B），这页当作没有文本层", e)
        emptyList()
    }

    /** 行内单字边界（可选键；缺了就是没有实测字位，选择回落权重近似） */
    private fun chars(r: JSONObject): List<Float>? {
        val a = r.optJSONArray("chars") ?: return null
        if (a.length() == 0) return null
        return (0 until a.length()).map { a.optDouble(it, 0.0).toFloat() }
    }
}

/** 供 [LibraryStore] 写入时统一取「现在」 */
internal fun nowIso(): String = Iso.string(Instant.now())
