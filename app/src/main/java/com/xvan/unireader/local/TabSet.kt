package com.xvan.unireader.local

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模式1 的**标签页组**持久化：每个工作区各记一组「开着哪几篇、当前是第几篇」。
 *
 * 口径（2026-08-05 用户拍板，见 `ANDROID-STANDALONE-PLAN.md §13`）：**标签页属于工作区**。
 * 切到另一个工作区时整组换掉，切回来原样恢复——所以这里按工作区**绝对路径**分键存，
 * 而不是全局一条列表。
 *
 * 只存 `document.id` 与当前下标，**不存进度**：读到第几页/缩放多少是文档自己的属性，
 * 真源在 `library.sqlite` 的 `document` 四列上（Mac 端同一套），存两份必然分叉。
 *
 * 也**不进工作区的库**：这是本机这台设备「开着哪几个标签页」的界面状态，往共享库里塞会
 * 跟着 `.unrd` 搬到 Mac 上去（同 [ToolPrefs] 的理由，§6 的红线）。
 *
 * 存坏了一律按「没有标签页」处理：大不了重新点一次，不值得为它挡住开工作区。
 */
object TabSet {

    const val TAG = "UniReader/Tabs"

    private const val PREFS = "tabs"
    private const val KEY_DOCS = "docs"
    private const val KEY_ACTIVE = "active"

    /** @param docIds 标签页顺序（与栏上从左到右一致）；[active] 是其中的下标 */
    class Saved(val docIds: List<String>, val active: Int)

    fun load(ctx: Context, workspacePath: String): Saved {
        val raw = prefs(ctx).getString(workspacePath, null) ?: return Saved(emptyList(), 0)
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray(KEY_DOCS) ?: JSONArray()
            val ids = (0 until arr.length()).mapNotNull { arr.optString(it, null)?.takeIf(String::isNotEmpty) }
            Saved(ids, o.optInt(KEY_ACTIVE, 0).coerceIn(0, maxOf(0, ids.size - 1)))
        } catch (e: Exception) {
            Log.w(TAG, "标签页组解析失败，按空处理：$workspacePath", e)
            Saved(emptyList(), 0)
        }
    }

    fun save(ctx: Context, workspacePath: String, docIds: List<String>, active: Int) {
        val e = prefs(ctx).edit()
        if (docIds.isEmpty()) {
            // 一个都不剩就把这条删掉，别在 prefs 里攒一堆空壳（工作区可能早就不在了）
            e.remove(workspacePath).apply()
            return
        }
        val o = JSONObject()
            .put(KEY_DOCS, JSONArray(docIds))
            .put(KEY_ACTIVE, active.coerceIn(0, docIds.size - 1))
        e.putString(workspacePath, o.toString()).apply()
    }

    /** 这个工作区开着的文档 id（书库列表用它标「已打开」） */
    fun openDocIds(ctx: Context, workspacePath: String): Set<String> =
        load(ctx, workspacePath).docIds.toSet()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
