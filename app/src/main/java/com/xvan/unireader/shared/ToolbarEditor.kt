package com.xvan.unireader.shared

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.max

/**
 * 工具栏编辑面板（长按任意一组的把手 / 长按 ⋯ / ⋯ 里「编辑工具栏…」打开）。
 *
 * 一组一块：组名（点它改名）+「顶栏 / 浮动」切换 + 上移下移删除，下面是这一组的按钮；最后一块是「不在工具栏里的」，
 * 它们显示在 ⋯ 菜单里。**长按一颗按钮拖到别的块里**就挪过去（落点决定排在哪），拖进最后一块 = 收进 ⋯。
 * 每改一步立刻存下并在屏幕上生效（[Toolbox.setLayout]），「完成」只是关掉面板。
 */
class ToolbarEditor(private val a: Activity, private val box: Toolbox) {

    private var work = box.layout.copy()
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: AlertDialog? = null

    fun show() {
        rebuild()
        dialog = Sheet(a)
            .title(a.getString(R.string.tools_edit_title))
            .subtitle(a.getString(R.string.tools_edit_tip))
            .size(760, 0.78f)
            .content(body)
            .action(a.getString(R.string.tools_edit_new_group), dismiss = false) { addGroup() }
            .action(a.getString(R.string.tools_edit_reset), dismiss = false) { commit(box.defaultLayout()) }
            .action(a.getString(R.string.tools_edit_done), primary = true)
            .show()
    }

    private fun commit(l: ToolLayout) {
        work = l
        box.setLayout(l.copy())
        rebuild()
    }

    private fun rebuild() {
        body.removeAllViews()
        val registered = box.metas().map { it.key }.toSet()
        for ((i, g) in work.groups.withIndex()) {
            body.addView(header(g, i))
            body.addView(flow(g, g.keys.filter { it in registered }), flowLp())
        }
        body.addView(Ui.groupTitle(a, a.getString(R.string.tools_edit_hidden)))
        val hidden = box.metas().map { it.key }.filter { work.groupOf(it) == null }
        body.addView(flow(null, hidden), flowLp())
    }

    private fun flowLp() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 6) }

    /** 组头：名字（点了改名）/ 顶栏·浮动 / 上移 / 下移 / 删除 */
    private fun header(g: ToolLayout.Group, i: Int): View = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, Ui.dp(a, 14), 0, 0)
        val on = Ui.onSurface(a)
        addView(
            Ui.title(a, box.groupName(g), 16f).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { rename(g) }
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            Ui.textButton(
                a,
                a.getString(if (g.docked) R.string.tools_edit_docked else R.string.tools_edit_floating),
            ) {
                edit { l -> l.groups.first { it.id == g.id }.docked = !g.docked }
            },
        )
        // 固定：可拖动 → 固定在左 → 固定在右 → 可拖动（固定的不显示把手、不能拖；并在顶栏里时排两头不滚动）
        addView(
            Ui.textButton(
                a,
                a.getString(
                    when (g.pin) {
                        ToolLayout.PIN_LEFT -> R.string.tools_edit_pin_left
                        ToolLayout.PIN_RIGHT -> R.string.tools_edit_pin_right
                        else -> R.string.tools_edit_pin_none
                    },
                ),
            ) {
                edit { l -> l.groups.first { it.id == g.id }.pin = (g.pin + 1) % 3 }
            },
        )
        addView(Ui.iconButton(a, R.drawable.ic_chevron_up, a.getString(R.string.tools_edit_up), on) {
            if (i > 0) edit { l -> l.groups.add(i - 1, l.groups.removeAt(i)) }
        }.apply { alpha = if (i > 0) 1f else 0.3f })
        addView(Ui.iconButton(a, R.drawable.ic_chevron_down, a.getString(R.string.tools_edit_down), on) {
            if (i < work.groups.size - 1) edit { l -> l.groups.add(i + 1, l.groups.removeAt(i)) }
        }.apply { alpha = if (i < work.groups.size - 1) 1f else 0.3f })
        addView(Ui.iconButton(a, R.drawable.ic_delete, a.getString(R.string.tools_edit_delete), on) {
            edit { l -> l.groups.removeAll { it.id == g.id } }   // 组里的按钮随之收进 ⋯（仍算「见过」）
        })
    }

    private fun edit(f: (ToolLayout) -> Unit) {
        val l = work.copy()
        f(l)
        commit(l)
    }

    private fun addGroup() {
        edit { l ->
            val n = l.groups.count { it.id.startsWith("u") } + 1
            l.groups.add(
                ToolLayout.Group(
                    "u" + System.currentTimeMillis(), a.getString(R.string.tools_group_new, n),
                    mutableListOf(), docked = false,
                ),
            )
        }
    }

    private fun rename(g: ToolLayout.Group) {
        val input = EditText(a).apply {
            setText(box.groupName(g))
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        Sheet(a).title(a.getString(R.string.tools_edit_rename)).content(input)
            .action(a.getString(R.string.tools_edit_cancel))
            .action(a.getString(R.string.tools_edit_ok), primary = true) {
                val s = input.text.toString().trim()
                edit { l -> l.groups.first { it.id == g.id }.name = s }
            }
            .show()
    }

    // ---------- 按钮块 + 拖放 ----------

    /** 一块按钮（[g] = null 是「不在工具栏里」那一块）。长按按钮开始拖；整块都接得住 */
    private fun flow(g: ToolLayout.Group?, keys: List<String>): View {
        val f = FlowLayout(a, Ui.dp(a, 6))
        val idle = Ui.round(Ui.container(a), 12, a)
        val hot = Ui.round(Ui.col(a, R.color.accent_container), 12, a)
        f.background = idle
        val p = Ui.dp(a, 8)
        f.setPadding(p, p, p, p)
        f.minimumHeight = Ui.dp(a, 64)
        for (k in keys) f.addView(chip(k))
        if (keys.isEmpty()) {
            f.addView(Ui.body(a, a.getString(R.string.tools_edit_drop_here)).apply {
                setPadding(Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14))
                tag = HINT
            })
        }
        f.setOnDragListener { _, e ->
            val key = e.localState as? String ?: return@setOnDragListener false
            when (e.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> { f.background = hot; true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { f.background = idle; true }
                DragEvent.ACTION_DROP -> {
                    f.background = idle
                    val at = f.indexAt(e.x, e.y, skip = key)
                    // 下标按「本块里本模式看得见的按钮」算，换成组里真正的下标（组里可能还有另一个模式的 key）
                    val visible = keys.filter { it != key }
                    edit { l ->
                        val tg = g?.let { gg -> l.groups.first { it.id == gg.id } }
                        val real = if (tg == null) 0 else {
                            // 插到落点右边那颗（本块里看得见的）前面；落在最后 = 排到组尾
                            val anchor = visible.getOrNull(at)
                            val rest = tg.keys.filter { it != key }
                            if (anchor == null) rest.size else rest.indexOf(anchor)
                        }
                        l.move(key, tg, real)
                    }
                    true
                }
                else -> true
            }
        }
        return f
    }

    /** 一颗按钮：图标（读数没有图标就显示「Aa」）+ 名字。长按开始拖 */
    private fun chip(key: String): View {
        val meta = box.metas().first { it.key == key }
        return LinearLayout(a).apply {
            tag = key
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(Ui.surface(a), 10, a, Ui.outline(a))
            val p = Ui.dp(a, 6)
            setPadding(p, p, p, p)
            val icon = box.iconOf(key)
            if (icon != null) {
                addView(ImageView(a).apply {
                    setImageDrawable(icon)
                    imageTintList = ColorStateList.valueOf(Ui.onSurface(a))
                }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
            } else {
                addView(TextView(a).apply {
                    text = "Aa"
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Ui.onSurface(a))
                }, LinearLayout.LayoutParams(Ui.dp(a, 24), Ui.dp(a, 24)))
            }
            addView(TextView(a).apply {
                text = meta.title
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTextColor(Ui.onSurface(a))
            }, LinearLayout.LayoutParams(Ui.dp(a, 64), -2).apply { topMargin = Ui.dp(a, 3) })
            setOnLongClickListener { v ->
                val clip = ClipData(ClipDescription("tool", arrayOf(MIME)), ClipData.Item(key))
                v.startDragAndDrop(clip, View.DragShadowBuilder(v), key, 0)
                true
            }
        }
    }

    /** 自动换行的一块（按钮一行排不下就折到下一行） */
    private class FlowLayout(c: Context, private val gap: Int) : ViewGroup(c) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxW = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + ch.measuredWidth > maxW) {
                    x = 0; y += lineH + gap; lineH = 0
                }
                x += ch.measuredWidth + gap
                lineH = max(lineH, ch.measuredHeight)
            }
            val h = max(suggestedMinimumHeight, y + lineH + paddingTop + paddingBottom)
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), resolveSize(h, heightSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxW = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineH = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (x > 0 && x + ch.measuredWidth > maxW) {
                    x = 0; y += lineH + gap; lineH = 0
                }
                ch.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + ch.measuredWidth, paddingTop + y + ch.measuredHeight)
                x += ch.measuredWidth + gap
                lineH = max(lineH, ch.measuredHeight)
            }
        }

        /** 落点 (x,y) 对应插到第几颗之前（只数按钮，不数「拖到这里」那行提示、也不数正被拖的那颗 [skip]） */
        fun indexAt(x: Float, y: Float, skip: String): Int {
            var n = 0
            for (i in 0 until childCount) {
                val ch = getChildAt(i)
                if (ch.tag == HINT || ch.tag == skip) continue
                val before = y < ch.top || (y <= ch.bottom && x < (ch.left + ch.right) / 2f)
                if (before) return n
                n++
            }
            return n
        }
    }

    private companion object {
        const val MIME = "application/x-unireader-tool"
        const val HINT = "hint"
    }
}
