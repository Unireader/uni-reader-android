package com.xvan.unireader.shared.exercise

import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 题目上的高亮与可选中的正文（`../docs/EXERCISE-PLAN.md §32`，同 Mac `AgentMarkdownView.highlights` / `selectionMenu`）。
 *
 * 正文由 [MathText.bindBlocks] 画进一个竖向 LinearLayout：Markdown 段是 [TextView]，代码块是别的视图（不算）。
 * 高亮的位置按「各文字段按先后用换行接起来」的那串字数（同 Mac `plainText`），找 `text` 的第 `nth` 处。
 */
object ExMarks {

    /** 本模块加的底色（重上一遍时只摘这一种，Markwon 自己的样式不动）。 */
    class MarkSpan(color: Int) : BackgroundColorSpan(color)

    /** 一处要上底色的字。 */
    data class Hl(val text: String, val nth: Int, val color: Int)

    fun textViews(box: LinearLayout): List<TextView> =
        (0 until box.childCount).mapNotNull { box.getChildAt(it) as? TextView }

    /** 各文字段接起来的那串字（高亮的位置按它数）。 */
    fun plain(box: LinearLayout): String = textViews(box).joinToString("\n") { it.text.toString() }

    /** 这一段在 [plain] 里从第几个字起。 */
    fun offset(box: LinearLayout, tv: TextView): Int {
        var n = 0
        for (t in textViews(box)) {
            if (t === tv) return n
            n += t.text.length + 1
        }
        return -1
    }

    /** 同 Mac `ExerciseMark.locate`：[text] 在 [plain] 里第 [nth] 处（可重叠地数）；找不到 → null。 */
    fun locate(text: String, nth: Int, plain: String): IntRange? {
        if (text.isEmpty()) return null
        var from = 0
        var k = 0
        while (from < plain.length) {
            val i = plain.indexOf(text, from)
            if (i < 0) return null
            if (k == nth) return i until i + text.length
            k++
            from = i + 1
        }
        return null
    }

    /** 同 Mac `ExerciseMark.make`：[plain] 里 [start]..[end] 记成一处高亮；只选了空白 / 越界 → null。 */
    fun make(field: String, start: Int, end: Int, plain: String): ExMarkView? {
        if (start < 0 || end <= start || end > plain.length) return null
        val text = plain.substring(start, end)
        if (text.isBlank()) return null
        var nth = 0
        var from = 0
        while (true) {
            val i = plain.indexOf(text, from)
            if (i < 0 || i >= start) break
            nth++
            from = i + 1
        }
        return ExMarkView(field, text, nth)
    }

    /** 给 [box] 里的文字重上底色（先摘掉上一次加的）。 */
    fun apply(box: LinearLayout, list: List<Hl>) {
        val tvs = textViews(box)
        val plain = tvs.joinToString("\n") { it.text.toString() }
        for (tv in tvs) {
            val sp = tv.text as? Spannable ?: continue
            for (old in sp.getSpans(0, sp.length, MarkSpan::class.java)) sp.removeSpan(old)
        }
        for (h in list) {
            val r = locate(h.text, h.nth, plain) ?: continue
            var start = 0
            for (tv in tvs) {
                val len = tv.text.length
                val lo = maxOf(r.first, start)
                val hi = minOf(r.last + 1, start + len)
                if (hi > lo) (tv.text as? Spannable)?.setSpan(MarkSpan(h.color), lo - start, hi - start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                start += len + 1
            }
        }
        for (tv in tvs) tv.invalidate()
    }

    /** 选中一段文字后多给的菜单项：标题 + 点了做什么。 */
    class Item(val title: String, val run: () -> Unit)

    /**
     * 让 [box] 里的文字能不能选中。[menu] 给了 = 能选（判完的题）：选中后浮出来的菜单多几项（[menu] 按选中的范围现算，
     * 范围是 [plain] 里的位置）；没给 = 不能选（作答时点选项正文就是点选，别让文字把触摸吃掉）。
     */
    fun setSelectable(box: LinearLayout, menu: ((start: Int, end: Int) -> List<Item>)?) {
        for (tv in textViews(box)) {
            if (menu == null) {
                if (tv.isTextSelectable) tv.setTextIsSelectable(false)
                tv.customSelectionActionModeCallback = null
                tv.movementMethod = null
                tv.isClickable = false
                tv.isLongClickable = false
                tv.isFocusable = false
                continue
            }
            if (!tv.isTextSelectable) tv.setTextIsSelectable(true)
            tv.customSelectionActionModeCallback = object : ActionMode.Callback {
                private var items: List<Item> = emptyList()
                private fun fill(m: Menu) {
                    for (i in items.indices) m.removeItem(ID_BASE + i)
                    val base = offset(box, tv)
                    val s = minOf(tv.selectionStart, tv.selectionEnd)
                    val e = maxOf(tv.selectionStart, tv.selectionEnd)
                    items = if (base < 0 || s < 0 || e <= s) emptyList() else menu(base + s, base + e)
                    for ((i, it) in items.withIndex()) m.add(Menu.NONE, ID_BASE + i, i, it.title)
                }
                override fun onCreateActionMode(mode: ActionMode, m: Menu): Boolean { fill(m); return true }
                override fun onPrepareActionMode(mode: ActionMode, m: Menu): Boolean { fill(m); return true }
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    val it = items.getOrNull(item.itemId - ID_BASE) ?: return false
                    mode.finish()
                    it.run()
                    return true
                }
                override fun onDestroyActionMode(mode: ActionMode) {}
            }
        }
    }

    private const val ID_BASE = 0x7E0
}
