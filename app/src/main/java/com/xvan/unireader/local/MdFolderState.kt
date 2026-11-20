package com.xvan.unireader.local

import android.content.Context
import org.json.JSONArray

/**
 * Markdown 笔记目录的**展开**状态（用户 2026-11-19：书库页与阅读界面抽屉里的笔记按目录折叠、**默认收起**、记住状态）。
 * 本机界面状态，按工作区绝对路径分键存在 prefs `mdFolders`（同 [TabSet] 的理由：不进工作区的库）。
 * 书库页与抽屉共用一份：在哪边展开的，另一边也是展开的。存的是展开了的目录（Notes/ 内相对路径），不在里面 = 收起。
 */
object MdFolderState {
    private const val PREFS = "mdFolders"

    fun load(ctx: Context, workspacePath: String): MutableSet<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(workspacePath, null) ?: return HashSet()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapTo(HashSet()) { a.optString(it) }.apply { remove("") }
        }.getOrDefault(HashSet())
    }

    fun save(ctx: Context, workspacePath: String, expanded: Set<String>) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (expanded.isEmpty()) e.remove(workspacePath) else e.putString(workspacePath, JSONArray(expanded.sorted()).toString())
        e.apply()
    }
}
