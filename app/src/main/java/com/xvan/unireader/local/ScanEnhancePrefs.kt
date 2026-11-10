package com.xvan.unireader.local

import android.content.Context

/**
 * 扫描页增强的开关：**按文档（内容哈希）记在本机**（同 Mac：不进库、不同步——它只是这台设备上怎么显示）。
 * 参数用默认那一套（Mac 设置页里的滑块本端没做）。
 */
object ScanEnhancePrefs {
    private const val PREFS = "scanEnhance"
    private const val KEY_DOCS = "docs"

    private fun docs(c: Context): Set<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DOCS, emptySet()).orEmpty()

    fun isOn(c: Context, contentHash: String): Boolean = contentHash.isNotEmpty() && contentHash in docs(c)

    /** 切换，返回切换后的状态。 */
    fun toggle(c: Context, contentHash: String): Boolean {
        if (contentHash.isEmpty()) return false
        val set = HashSet(docs(c))
        val on = set.add(contentHash)
        if (!on) set.remove(contentHash)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_DOCS, set).apply()
        return on
    }
}
