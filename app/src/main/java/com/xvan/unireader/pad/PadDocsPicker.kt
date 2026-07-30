package com.xvan.unireader.pad

import android.app.Activity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.xvan.unireader.R
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui

/**
 * 文档下拉（网页顶栏那个 select）。**只有模式2 有这个东西**：文档列表是 Mac 通过 `docs` 消息
 * 推过来的线格式概念；模式1 的书库来自工作区 SQLite，走 `LibraryActivity`。
 *
 * 所以它不跟其余面板一起住在 `shared/PadPanels.kt`——那边不许认识 [WireCodec]
 * （依赖方向单向：`pad`/`local` → `shared`）。
 *
 * 第一项固定是「跟随 Mac」（发 `selectDoc ""`）。外观走 [Sheet]（§9.6），选中态用一枚勾图标——
 * 系统的 `setSingleChoiceItems` 是一列单选圆点，与全 App 的语汇不搭。
 */
object PadDocsPicker {

    fun show(
        a: Activity,
        docs: List<WireCodec.DocEntry>,
        selected: String,
        following: Boolean,
        onSelect: (String) -> Unit,
    ) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(a).title("文档")
        lateinit var dialog: android.app.AlertDialog

        fun item(icon: Int, title: String, id: String, checked: Boolean) {
            val tick = ImageView(a).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(Ui.accent(a))
                // INVISIBLE 而不是 GONE：没勾的行也占同样宽度，标题才不会左右跳
                visibility = if (checked) View.VISIBLE else View.INVISIBLE
                layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20))
            }
            root.addView(
                PadPanels.iconRow(a, icon, title, trailing = tick) {
                    onSelect(id)
                    dialog.dismiss()
                },
            )
        }

        item(R.drawable.ic_sync, "跟随 Mac", "", following)
        for (d in docs) item(R.drawable.ic_doc, d.title, d.id, !following && d.id == selected)
        if (docs.isEmpty()) root.addView(Ui.body(a, "（还没收到 Mac 的文档列表）"))

        dialog = sheet.content(root).action("取消").show()
    }
}
