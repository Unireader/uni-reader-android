package com.xvan.unireader.shared

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R

/**
 * 新建画板笔记（`../BOARD-NOTE-PLAN.md §9.4`）：先选模式——无限画布 / 分页；分页再选页面大小（含横竖）、
 * 背景模板、初始页数（1~100）。模式建好之后不再转换（同 Mac `NewBoardSheetController`）。
 *
 * 两模式共用这一份界面，只差「按下创建之后做什么」：模式1 写库（`board_note` + `board_page`），
 * 模式2 编 `boardAdd` 帧发给 Mac——那是宿主的事，这里只交出 [Spec]（**不认识 WireCodec，也不碰库**）。
 * 上次选的记在本机（下次新建默认同样的设置，同 Mac 的 `newBoard.*`）。
 */
object NewBoardSheet {

    /** 用户选好的新建参数。[paged] = false 时其余字段无意义 */
    class Spec(val paged: Boolean, val w: Float, val h: Float, val template: Int, val count: Int)

    private const val PREFS = "new_board"

    /** 背景模板的显示名（下标 = [BoardPaging] 的 T_*） */
    fun templateLabels(c: Context): List<String> = listOf(
        c.getString(R.string.board_tpl_blank),
        c.getString(R.string.board_tpl_lined),
        c.getString(R.string.board_tpl_grid),
        c.getString(R.string.board_tpl_dots),
        c.getString(R.string.board_tpl_cornell),
        c.getString(R.string.board_tpl_two_column),
    )

    /** 页面尺寸预设的显示名（下标 = [BoardPaging] 的 SIZE_*） */
    fun sizeLabels(c: Context): List<String> = listOf(
        c.getString(R.string.board_size_a4),
        c.getString(R.string.board_size_a5),
        c.getString(R.string.board_size_letter),
        c.getString(R.string.board_size_screen),
    )

    /** 这台设备屏幕的逻辑尺寸（dp）——「当前屏幕」预设用 */
    fun screenDp(c: Context): FloatArray {
        val dm = c.resources.displayMetrics
        return floatArrayOf(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
    }

    fun show(a: Activity, onCreate: (Spec) -> Unit) {
        val prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var paged = prefs.getInt("mode", 0) == 1
        var size = prefs.getInt("size", BoardPaging.SIZE_A4).coerceIn(0, 3)
        var landscape = prefs.getInt("orient", 0) == 1
        var template = prefs.getInt("template", BoardPaging.T_BLANK).coerceIn(0, BoardPaging.TEMPLATE_RAW.size - 1)
        var count = 1

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val pagedBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        // 模式：分段控件两格
        root.addView(Ui.groupTitle(a, a.getString(R.string.board_mode), top = 0))
        val modeRow = segRow(a)
        lateinit var syncMode: () -> Unit
        val infBtn = PadPanels.segButton(a, a.getString(R.string.board_mode_infinite)) { paged = false; syncMode() }
        val pagedBtn = PadPanels.segButton(a, a.getString(R.string.board_mode_paged)) { paged = true; syncMode() }
        modeRow.addView(infBtn, segLp(a, last = false))
        modeRow.addView(pagedBtn, segLp(a, last = true))
        root.addView(modeRow)

        // 页面大小：四个预设一排 + 横竖一排
        pagedBox.addView(Ui.groupTitle(a, a.getString(R.string.board_page_size)))
        val sizeRow = segRow(a)
        val sizeBtns = ArrayList<TextView>()
        lateinit var syncSize: () -> Unit
        sizeLabels(a).forEachIndexed { i, label ->
            val b = PadPanels.segButton(a, label) { size = i; syncSize() }
            sizeBtns.add(b)
            sizeRow.addView(b, segLp(a, last = i == 3))
        }
        pagedBox.addView(sizeRow)
        val orientRow = segRow(a).apply { setPadding(0, Ui.dp(a, 8), 0, 0) }
        val portBtn = PadPanels.segButton(a, a.getString(R.string.board_portrait)) { landscape = false; syncSize() }
        val landBtn = PadPanels.segButton(a, a.getString(R.string.board_landscape)) { landscape = true; syncSize() }
        orientRow.addView(portBtn, segLp(a, last = false))
        orientRow.addView(landBtn, segLp(a, last = true))
        pagedBox.addView(orientRow)
        syncSize = {
            sizeBtns.forEachIndexed { i, b -> PadPanels.setSegActive(a, b, i == size) }
            PadPanels.setSegActive(a, portBtn, !landscape)
            PadPanels.setSegActive(a, landBtn, landscape)
        }

        // 背景：六个模板（整行可点，当前项打勾）
        pagedBox.addView(Ui.groupTitle(a, a.getString(R.string.board_background)))
        val tplRows = ArrayList<View>()
        lateinit var syncTpl: () -> Unit
        templateLabels(a).forEachIndexed { i, label ->
            val r = optionRow(a, label) { template = i; syncTpl() }
            tplRows.add(r)
            pagedBox.addView(r)
        }
        syncTpl = { tplRows.forEachIndexed { i, r -> setChecked(r, i == template) } }

        // 初始页数：− 数字 +（1~100）
        pagedBox.addView(Ui.groupTitle(a, a.getString(R.string.board_page_count)))
        val countRow = segRow(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val countBox = PadPanels.inputBox(a, "1").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setText("1")
        }
        fun readCount(): Int =
            (countBox.text.toString().trim().toIntOrNull() ?: 1).coerceIn(BoardPaging.MIN_PAGES, BoardPaging.MAX_PAGES)
        fun setCount(n: Int) {
            count = n.coerceIn(BoardPaging.MIN_PAGES, BoardPaging.MAX_PAGES)
            countBox.setText(count.toString())
            countBox.setSelection(countBox.text.length)
        }
        countRow.addView(Ui.button(a, "−") { setCount(readCount() - 1) })
        countRow.addView(countBox, LinearLayout.LayoutParams(Ui.dp(a, 88), -2).apply {
            marginStart = Ui.dp(a, 8); marginEnd = Ui.dp(a, 8)
        })
        countRow.addView(Ui.button(a, "+") { setCount(readCount() + 1) })
        pagedBox.addView(countRow)

        root.addView(pagedBox)
        syncMode = {
            PadPanels.setSegActive(a, infBtn, !paged)
            PadPanels.setSegActive(a, pagedBtn, paged)
            pagedBox.visibility = if (paged) View.VISIBLE else View.GONE
        }
        syncMode()
        syncSize()
        syncTpl()

        Sheet(a).title(a.getString(R.string.board_new))
            .content(root)
            .action(a.getString(R.string.common_cancel))
            .action(a.getString(R.string.board_create), primary = true) {
                count = readCount()
                prefs.edit()
                    .putInt("mode", if (paged) 1 else 0)
                    .putInt("size", size)
                    .putInt("orient", if (landscape) 1 else 0)
                    .putInt("template", template)
                    .apply()
                val scr = screenDp(a)
                val wh = BoardPaging.pageSize(size, landscape, scr[0], scr[1])
                onCreate(Spec(paged, wh[0], wh[1], template, count))
            }
            .show()
    }

    // ---------- 小零件（同纸样面板 / 笔面板的现成做法） ----------

    private fun segRow(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun segLp(a: Activity, last: Boolean) = LinearLayout.LayoutParams(0, -2, 1f).apply {
        if (!last) marginEnd = Ui.dp(a, 8)
    }

    /** 整行可点、当前项右侧打勾的一行（勾由 [setChecked] 切） */
    internal fun optionRow(a: Activity, label: String, onClick: () -> Unit): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            setPadding(Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10))
            setOnClickListener { onClick() }
            addView(
                TextView(a).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Ui.onSurface(a))
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                ImageView(a).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = ColorStateList.valueOf(Ui.accent(a))
                    visibility = View.INVISIBLE
                },
                LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)),
            )
        }

    internal fun setChecked(row: View, on: Boolean) {
        val g = row as? LinearLayout ?: return
        g.getChildAt(g.childCount - 1)?.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }
}
