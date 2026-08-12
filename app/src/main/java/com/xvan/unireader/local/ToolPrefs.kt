package com.xvan.unireader.local

import android.content.Context
import android.util.Log
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模式1 的工具状态持久化：**笔（含笔宽与当前选中的那支）、橡皮设置、夜间模式、锁缩放、双指滚动**。
 *
 * 模式2 不需要这个——它的笔架真源在 Mac，连上就整体推下来。模式1 没有 Mac，不存的话每次启动都
 * 回到基类那四支内置兜底笔，用户拖完笔宽一退出就白拖了。
 *
 * **设备级、不挂工作区**（同 Mac：`PenPreset` 存 UserDefaults，不进 `library.sqlite`）——
 * 笔不是工作区的数据，往共享库里塞是越界（`ANDROID-STANDALONE-PLAN.md §6` 的红线）。
 *
 * 存坏了/格式变了一律按「没存过」处理：工具状态丢了只是回到默认，不值得为它挡住开文档。
 */
object ToolPrefs {

    const val TAG = "UniReader/Tools"

    private const val PREFS = "tools"
    private const val KEY = "state"

    fun load(ctx: Context, canvas: PageCanvasView) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("pens")
            if (arr != null && arr.length() > 0) {
                val pens = ArrayList<Pen>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    pens.add(
                        Pen(
                            r = p.optInt("r", 24).coerceIn(0, 255),
                            g = p.optInt("g", 90).coerceIn(0, 255),
                            b = p.optInt("b", 210).coerceIn(0, 255),
                            a = p.optDouble("a", 0.95).toFloat().coerceIn(0f, 1f),
                            // 笔宽跟面板同一区间（2~40），手改坏了也不会画出一条看不见或糊满屏的笔
                            w = p.optDouble("w", 8.0).toFloat().coerceIn(2f, 40f),
                            brush = p.optInt("brush", 0).coerceIn(0, 3),
                        ),
                    )
                }
                if (pens.isNotEmpty()) canvas.setPens(pens, o.optInt("active", 0))
            }
            val er = o.optJSONObject("eraser")
            if (er != null) {
                canvas.setEraserLocal(
                    er.optDouble("size", 0.02).toFloat().coerceIn(0.005f, 0.12f),
                    if (er.optInt("mode", 1) == 0) 0 else 1,
                    er.optBoolean("ring", true),
                )
            }
            // 基类只给了 toggle（两模式都是按键切的），所以按目标值补一次差
            if (o.optBoolean("night", false) != canvas.night) canvas.toggleNight()
            if (o.optBoolean("zoomLocked", false) != canvas.zoomLocked) canvas.toggleZoomLock()
            if (o.optBoolean("twoFingerScroll", false) != canvas.twoFingerScroll) canvas.toggleTwoFingerScroll()
            Log.i(
                TAG,
                "工具状态已复原：笔 ${canvas.penList().size} 支 当前=${canvas.penIndex} " +
                    "夜间=${canvas.night} 锁缩放=${canvas.zoomLocked} 双指滚动=${canvas.twoFingerScroll}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "工具状态解析失败，按默认走", e)
        }
    }

    fun save(ctx: Context, canvas: PageCanvasView) {
        try {
            val pens = JSONArray()
            for (p in canvas.penList()) {
                pens.put(
                    JSONObject()
                        .put("r", p.r).put("g", p.g).put("b", p.b)
                        .put("a", p.a.toDouble()).put("w", p.w.toDouble()).put("brush", p.brush),
                )
            }
            val o = JSONObject()
                .put("pens", pens)
                .put("active", canvas.penIndex)
                .put(
                    "eraser",
                    JSONObject()
                        .put("size", canvas.eraserSize.toDouble())
                        .put("mode", canvas.eraserMode)
                        .put("ring", canvas.eraserRing),
                )
                .put("night", canvas.night)
                // 锁缩放 / 双指滚动：设一次用很久的**防误触偏好**，不存的话每次启动都要重新去点一遍
                .put("zoomLocked", canvas.zoomLocked)
                .put("twoFingerScroll", canvas.twoFingerScroll)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, o.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "工具状态写入失败（下次启动回默认）", e)
        }
    }
}
