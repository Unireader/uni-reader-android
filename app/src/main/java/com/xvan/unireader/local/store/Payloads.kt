package com.xvan.unireader.local.store

import android.util.Log
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
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
    )

    /** 只替换点集（局部擦除切段 / 框选平移后回写），其余键原样保留 */
    fun withPoints(pts: List<Pt3>): InkPayload {
        val arr = JSONArray()
        for (p in pts) arr.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()).put(p.p.toDouble()))
        raw.put("points", arr)
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

        /** 新笔迹落库：键名与 Mac 端 `InkStrokePayload` 完全一致（color/width/type/points/layerId） */
        fun of(pen: Pen, pts: List<Pt3>, layerId: String): InkPayload {
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
            return InkPayload(o).withPoints(pts)
        }
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
class TextNotePayload(val raw: JSONObject) {

    val quote: String get() = raw.optString("quote")
    val text: String get() = raw.optString("text")

    /** 笔记类型 id（JSON 键是 snake_case 的 `type_id`）；缺键/空 → null＝通用 */
    val typeId: String? get() = raw.optString("type_id").ifEmpty { null }

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
        fun ofPointNote(text: String, typeId: String? = null): TextNotePayload {
            val o = JSONObject()
            o.put("quote", "")
            o.put("text", text)
            o.put("rects", JSONArray())
            if (typeId != null) o.put("type_id", typeId)
            return TextNotePayload(o)
        }
    }
}

/** 供 [LibraryStore] 写入时统一取「现在」 */
internal fun nowIso(): String = Iso.string(Instant.now())
