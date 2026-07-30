package com.xvan.unireader.pad

import android.app.Activity
import android.app.AlertDialog

/**
 * 文档下拉（网页顶栏那个 select）。**只有模式2 有这个东西**：文档列表是 Mac 通过 `docs` 消息
 * 推过来的线格式概念；模式1 的书库来自工作区 SQLite，走 `LibraryActivity`。
 *
 * 所以它不跟其余面板一起住在 `shared/PadPanels.kt`——那边不许认识 [WireCodec]
 * （依赖方向单向：`pad`/`local` → `shared`）。
 *
 * 第一项固定是「跟随 Mac」（发 `selectDoc ""`）。
 */
object PadDocsPicker {

    fun show(
        a: Activity,
        docs: List<WireCodec.DocEntry>,
        selected: String,
        following: Boolean,
        onSelect: (String) -> Unit,
    ) {
        val titles = ArrayList<String>()
        val ids = ArrayList<String>()
        titles.add("⟳ 跟随 Mac")
        ids.add("")
        for (d in docs) { titles.add(d.title); ids.add(d.id) }
        val checked = if (following) 0 else ids.indexOf(selected).coerceAtLeast(0)
        AlertDialog.Builder(a)
            .setTitle("文档")
            .setSingleChoiceItems(titles.toTypedArray(), checked) { dlg, which ->
                onSelect(ids[which])
                dlg.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
