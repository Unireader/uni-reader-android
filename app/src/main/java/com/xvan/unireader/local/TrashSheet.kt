package com.xvan.unireader.local

import android.app.Activity
import android.app.AlertDialog
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import com.xvan.unireader.R
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import java.io.File

/**
 * 回收站面板（Mac「文件 › 回收站…」`TrashSheet` 的同款）：列出条目（标题 / 类型 / 删除时间 / 内容摘要），
 * 每条「恢复」/「彻底删除」，底下「清空回收站」。彻底删除与清空各问一次——回收站自己的删除没有回收站。
 *
 * 库从哪来由宿主给（[run]：阅读界面走那条 StoreQueue，书库页临时开一条可写连接），面板只管界面。
 * 恢复 / 删除完调 [onChanged]，宿主据此刷新列表。
 */
class TrashSheet(
    private val a: Activity,
    private val ws: File,
    /** 在库线程上干活、回主线程收尾 */
    private val run: (what: String, work: (LibraryStore) -> Any?, then: (Any?) -> Unit) -> Unit,
    private val onChanged: () -> Unit,
) {
    private var dlg: AlertDialog? = null
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    fun show() {
        render(Trash.scan(ws))
        dlg = Sheet(a).title(a.getString(R.string.trash_title)).content(list)
            .action(a.getString(R.string.trash_empty), dismiss = false) { confirmEmpty() }
            .action(a.getString(R.string.act_done), primary = true)
            .show()
    }

    private fun kindLabel(k: String): String = a.getString(
        when (k) {
            Trash.KIND_BOARD -> R.string.trash_kind_board
            Trash.KIND_LAYER -> R.string.trash_kind_layer
            Trash.KIND_ANNOTATIONS -> R.string.trash_kind_annotations
            Trash.KIND_EXERCISES -> R.string.trash_kind_exercises
            else -> R.string.trash_kind_document
        },
    )

    private fun render(entries: List<Trash.Entry>) {
        list.removeAllViews()
        if (entries.isEmpty()) {
            list.addView(Ui.body(a, a.getString(R.string.trash_empty_list)))
            return
        }
        list.addView(Ui.tip(a, a.getString(R.string.trash_hint)))
        for ((i, e) in entries.withIndex()) {
            if (i > 0) list.addView(Ui.divider(a))
            val m = e.manifest
            val col = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 6))
            }
            col.addView(Ui.title(a, m.title.ifEmpty { e.id }, 16f))
            val sub = buildString {
                append(kindLabel(m.kind))
                append("　")
                append(DateUtils.getRelativeTimeSpanString(m.deletedAt.time))
                if (m.counts.total > 0) { append("　"); append(a.getString(R.string.trash_items, m.counts.total)) }
            }
            col.addView(Ui.body(a, sub).apply { setPadding(0, Ui.dp(a, 2), 0, 0) })
            col.addView(
                LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(Ui.textButton(a, a.getString(R.string.trash_delete_forever)) { confirmPurge(e) })
                    addView(Ui.textButton(a, a.getString(R.string.trash_restore), primary = true) { restore(e) })
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            list.addView(col)
        }
    }

    private fun restore(e: Trash.Entry) {
        run("从回收站恢复《${e.manifest.title}》", { s -> Trash.restore(s, e) }) { r ->
            val msg = if (r is Trash.Restored) {
                if (r.mergedInto != null) a.getString(R.string.trash_merged, e.manifest.title)
                else a.getString(R.string.trash_restored, e.manifest.title)
            } else {
                a.getString(R.string.trash_failed)
            }
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
            render(Trash.scan(ws))
            onChanged()
        }
    }

    private fun confirmPurge(e: Trash.Entry) {
        Sheet(a).title(a.getString(R.string.trash_forever_confirm, e.manifest.title))
            .subtitle(a.getString(R.string.trash_forever_msg))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.trash_delete_forever), primary = true) {
                run("彻底删除回收站条目", { s -> Trash.purge(s, e) }) {
                    render(Trash.scan(ws))
                    onChanged()
                }
            }
            .show()
    }

    private fun confirmEmpty() {
        val entries = Trash.scan(ws)
        if (entries.isEmpty()) return
        Sheet(a).title(a.getString(R.string.trash_empty_confirm))
            .subtitle(a.getString(R.string.trash_forever_msg))
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.trash_empty), primary = true) {
                run("清空回收站 ${entries.size} 条", { s -> for (e in entries) Trash.purge(s, e) }) {
                    render(Trash.scan(ws))
                    onChanged()
                }
            }
            .show()
    }
}
