package com.xvan.unireader.local

import android.content.Context
import org.json.JSONArray

/**
 * 抽屉「书库 → 文档」里各分组的**收起**状态（用户 2026-11-20：手机上列表太长、要能按分组折叠）。
 * 本机界面状态，按工作区绝对路径分键存在 prefs `libFolds`（同 [MdFolderState] 的理由：不进工作区的库）。
 *
 * 存的是**收起了的**分组（默认都展开，新出现的分组也是展开的），键 = [doc]`(分组名)`（`doc:` = 未分组那一段）。
 * 同日书库页去掉之前，画板 / 笔记 / 习题 / 网页几段也记在这里（`boards` 等键），现在没人读了，留在 prefs 里无害。
 */
object LibFoldState {
    private const val PREFS = "libFolds"

    fun doc(group: String) = com.xvan.unireader.shared.DocGroups.foldKey(group)

    fun load(ctx: Context, workspacePath: String): MutableSet<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(workspacePath, null) ?: return HashSet()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapTo(HashSet()) { a.optString(it) }.apply { remove("") }
        }.getOrDefault(HashSet())
    }

    fun save(ctx: Context, workspacePath: String, collapsed: Set<String>) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (collapsed.isEmpty()) e.remove(workspacePath) else e.putString(workspacePath, JSONArray(collapsed.sorted()).toString())
        e.apply()
    }
}
