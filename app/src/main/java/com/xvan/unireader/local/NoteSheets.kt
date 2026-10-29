package com.xvan.unireader.local

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.local.store.NoteMarks
import com.xvan.unireader.local.store.NoteTypeDef
import com.xvan.unireader.local.store.Palette
import com.xvan.unireader.shared.HL_BOX
import com.xvan.unireader.shared.HL_FILL
import com.xvan.unireader.shared.HL_UNDERLINE
import com.xvan.unireader.shared.NOTE_ALWAYS
import com.xvan.unireader.shared.NOTE_HOVER
import com.xvan.unireader.shared.NOTE_TAP
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.PadPanels
import com.xvan.unireader.shared.Sheet
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.Ui.setActive
import com.xvan.unireader.shared.Ui.setBackgroundKeepPadding
import java.util.UUID

/**
 * 模式1 的笔记相关弹层（对齐 Mac `Sources/Window/Sheets/NoteSheets.swift` + 高亮气泡）：
 *
 * - [showEditor] 文字笔记编辑器：类型 / 原文 / 正文 / 标记（跟随类型色 + 四色 + 三种画法，只有选区注解有）/ 展开方式；
 * - [showHighlight] 点中高亮弹的面板：原文、页码、换色、换画法、转批注、删除（Mac `HighlightPopoverController`）；
 * - [showTypeManager] / [showTypeEdit] 笔记类型管理（名称 + 8 色 + 16 图标，Mac `NoteTypeManagerController`）。
 *
 * 模式2 的编辑器仍是 `PadPanels.showNoteEditor`（真源在 Mac，线格式里没有类型 / 颜色 / 画法）。
 * 输入框、分段格这些零件借 `PadPanels` 的，两边长得一样。
 */
object NoteSheets {

    class EditorCfg(
        val isNew: Boolean,
        val text: String,
        val quote: String,
        /** 有选区行框（选区注解 / 高亮转来的）才有「标记」那一行，同 Mac `hasRects` */
        val hasRects: Boolean,
        val display: Int,
        val typeId: String?,
        val color: IntArray?,
        val style: Int,
        val types: List<NoteTypeDef>,
        val onSave: (text: String, display: Int, marks: NoteMarks) -> Unit,
        val onDelete: (() -> Unit)?,
        /** 「管理类型…」：宿主弹管理面板，改完把新列表交回来（编辑器跟着刷新类型菜单） */
        val onManageTypes: (done: (List<NoteTypeDef>) -> Unit) -> Unit,
    )

    private val STYLES = listOf(
        Triple(HL_FILL, R.drawable.ic_hl_fill, R.string.hl_fill),
        Triple(HL_UNDERLINE, R.drawable.ic_hl_underline, R.string.hl_underline),
        Triple(HL_BOX, R.drawable.ic_hl_box, R.string.hl_box),
    )

    private val GENERAL_RGB = intArrayOf(142, 142, 147)   // Mac `NoteType.general` 的 gray

    // ---------- 文字笔记编辑器 ----------

    fun showEditor(a: Activity, cfg: EditorCfg) {
        var types = cfg.types
        var typeId = cfg.typeId?.takeIf { NoteTypeDef.resolve(it, types) != null }
        var color = cfg.color
        var style = cfg.style
        var display = cfg.display

        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

        // 类型：一行「类型」+ 当前类型（色点 + 图标 + 名称），点开选；同 Mac 标题栏右边那个下拉
        val typeChip = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            val p = Ui.dp(a, 8)
            setPadding(p, p, p, p)
            background = Ui.rippleOver(a, Ui.round(Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a)), Ui.RADIUS, Ui.onSurface(a))
        }
        lateinit var refreshMarks: () -> Unit
        fun refreshChip() {
            typeChip.removeAllViews()
            val t = NoteTypeDef.resolve(typeId, types)
            typeChip.addView(dot(a, t?.rgb ?: GENERAL_RGB, false, 12))
            typeChip.addView(
                typeIcon(a, t?.iconName ?: NoteTypeDef.ICONS[0]),
                LinearLayout.LayoutParams(Ui.dp(a, 18), Ui.dp(a, 18)).apply { marginStart = Ui.dp(a, 8) },
            )
            typeChip.addView(
                TextView(a).apply {
                    text = t?.name ?: a.getString(R.string.note_general)
                    textSize = 15f
                    setTextColor(Ui.onSurface(a))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 4), 0)
                },
            )
            typeChip.addView(
                ImageView(a).apply {
                    setImageResource(R.drawable.ic_chevron_down)
                    imageTintList = android.content.res.ColorStateList.valueOf(Ui.onSurface(a))
                },
                LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14)),
            )
        }
        typeChip.setOnClickListener {
            pickType(a, types, typeId, onPick = { id ->
                typeId = id
                refreshChip()
                refreshMarks()
            }, onManage = {
                cfg.onManageTypes { newTypes ->
                    types = newTypes
                    if (NoteTypeDef.resolve(typeId, types) == null) typeId = null
                    refreshChip()
                    refreshMarks()
                }
            })
        }
        root.addView(
            PadPanels.row(a).apply {
                addView(label(a, a.getString(R.string.note_type)), LinearLayout.LayoutParams(0, -2, 1f))
                addView(typeChip)
            },
        )

        if (cfg.quote.isNotEmpty()) {
            root.addView(
                TextView(a).apply {
                    text = cfg.quote
                    textSize = 14f
                    setTypeface(typeface, Typeface.ITALIC)
                    setTextColor(Ui.onSurface(a))
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    val p = Ui.dp(a, 10)
                    setPadding(p, p, p, p)
                    background = Ui.round(Ui.container(a), 8, a)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8) },
            )
        }

        val edit = PadPanels.inputBox(a, a.getString(R.string.note_hint)).apply {
            setSingleLine(false)
            setText(cfg.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setSelection(cfg.text.length)
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 10) })

        // 标记：跟随类型色那一枚 + 高亮四色，右边三种画法（同 Mac「Mark」一行）
        val dots = ArrayList<Pair<IntArray?, View>>()
        val styleBtns = ArrayList<Pair<Int, ImageButton>>()
        val markRow = PadPanels.row(a)
        // 画法三颗键单独一行：跟色点挤一行时窄屏（手机竖屏）放不下，最右那颗会被裁掉
        val styleRow = PadPanels.row(a)
        refreshMarks = {
            markRow.removeAllViews()
            styleRow.removeAllViews()
            dots.clear()
            markRow.addView(label(a, a.getString(R.string.note_mark)), LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = Ui.dp(a, 10)
            })
            val typeRgb = NoteTypeDef.resolve(typeId, types)?.rgb ?: PadConst.FILL.NOTE_RGB
            val choices = listOf<IntArray?>(null) + PadConst.HIGHLIGHT_PALETTE.map { it.second }
            for (c in choices) {
                val v = dot(a, c ?: typeRgb, sameRgb(c, color), 26)
                v.contentDescription = if (c == null) a.getString(R.string.note_type_color) else null
                v.setOnClickListener { color = c; refreshMarks() }
                dots.add(c to v)
                markRow.addView(v)
            }
            for ((st, b) in styleBtns) {
                b.setActive(st == style, Ui.onSurface(a), Ui.accent(a))
                styleRow.addView(b)
            }
        }
        if (cfg.hasRects) {
            for ((st, icon, desc) in STYLES) {
                styleBtns.add(st to Ui.iconButton(a, icon, a.getString(desc)) { style = st; refreshMarks() })
            }
            root.addView(markRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 6) })
            root.addView(styleRow, LinearLayout.LayoutParams(-1, -2))
        }

        root.addView(Ui.groupTitle(a, a.getString(R.string.note_show)))
        root.addView(displaySeg(a, display) { display = it })

        refreshChip()
        refreshMarks()

        val sheet = Sheet(a).title(a.getString(if (cfg.isNew) R.string.note_new else R.string.note_edit)).content(root)
        cfg.onDelete?.let { del -> sheet.action(a.getString(R.string.act_delete)) { del() } }
        sheet.action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_save), primary = true) {
                cfg.onSave(
                    edit.text.toString().trim(), display,
                    NoteMarks(typeId, if (cfg.hasRects) color else cfg.color, if (cfg.hasRects) style else cfg.style),
                )
            }
            .show()
    }

    /** 选类型：通用恒为第一项，最后一项「管理类型…」（同 Mac `rebuildTypeMenu`） */
    private fun pickType(
        a: Activity, types: List<NoteTypeDef>, current: String?,
        onPick: (String?) -> Unit, onManage: () -> Unit,
    ) {
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: android.app.AlertDialog
        fun add(id: String?, name: String, rgb: IntArray, icon: String) {
            val on = if (id == null) current == null else id.equals(current, ignoreCase = true)
            list.addView(
                typeRow(a, rgb, icon, name, trailing = if (on) check(a) else null) { onPick(id); dialog.dismiss() },
            )
        }
        add(null, a.getString(R.string.note_general), GENERAL_RGB, NoteTypeDef.ICONS[0])
        for (t in types) add(t.id, t.name, t.rgb, t.iconName)
        list.addView(
            PadPanels.iconRow(a, R.drawable.ic_more, a.getString(R.string.note_manage_types)) {
                dialog.dismiss()
                onManage()
            },
        )
        dialog = Sheet(a).title(a.getString(R.string.note_type)).content(list)
            .action(a.getString(R.string.act_cancel)).show()
    }

    private fun displaySeg(a: Activity, initial: Int, onPick: (Int) -> Unit): LinearLayout {
        var mode = initial
        val segRow = PadPanels.row(a)
        val cells = ArrayList<TextView>(3)
        lateinit var sync: () -> Unit
        val labels = listOf(
            NOTE_TAP to R.string.note_show_tap, NOTE_HOVER to R.string.note_show_hover,
            NOTE_ALWAYS to R.string.note_show_always,
        )
        for ((i, pair) in labels.withIndex()) {
            val cell = PadPanels.segButton(a, a.getString(pair.second)) { mode = pair.first; onPick(mode); sync() }
            cells.add(cell)
            segRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < 2) marginEnd = Ui.dp(a, 8) })
        }
        sync = { for ((i, c) in cells.withIndex()) PadPanels.setSegActive(a, c, labels[i].first == mode) }
        sync()
        return segRow
    }

    // ---------- 高亮操作面板 ----------

    /**
     * 点中一条高亮：原文、页码、换色（四色点）、换画法、转批注、删除（Mac `HighlightPopoverController`）。
     * 换色 / 换画法**点了就生效**（同 Mac 气泡里点色点），面板留着可以接着换。
     */
    fun showHighlight(
        a: Activity, quote: String, page: Int, rgb: IntArray, style: Int,
        onColor: (IntArray) -> Unit, onStyle: (Int) -> Unit, onAddNote: () -> Unit, onDelete: () -> Unit,
    ) {
        var cur = rgb
        var st = style
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (quote.isNotEmpty()) {
            root.addView(
                TextView(a).apply {
                    text = quote
                    textSize = 14f
                    setTypeface(typeface, Typeface.ITALIC)
                    setTextColor(Ui.onSurface(a))
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        val row = PadPanels.row(a)
        val styleRow = PadPanels.row(a)
        val styleBtns = ArrayList<Pair<Int, ImageButton>>()
        lateinit var sync: () -> Unit
        sync = {
            row.removeAllViews()
            styleRow.removeAllViews()
            for ((_, c) in PadConst.HIGHLIGHT_PALETTE) {
                val v = dot(a, c, sameRgb(c, cur), 26)
                v.setOnClickListener {
                    if (!sameRgb(c, cur)) { cur = c; onColor(c); sync() }
                }
                row.addView(v)
            }
            for ((s, b) in styleBtns) {
                b.setActive(s == st, Ui.onSurface(a), Ui.accent(a))
                styleRow.addView(b)
            }
        }
        for ((s, icon, desc) in STYLES) {
            styleBtns.add(s to Ui.iconButton(a, icon, a.getString(desc)) {
                if (s != st) { st = s; onStyle(s); sync() }
            })
        }
        sync()
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8) })
        root.addView(styleRow, LinearLayout.LayoutParams(-1, -2))
        Sheet(a).title(a.getString(R.string.hl_title))
            .subtitle(a.getString(R.string.hl_page, page + 1))
            .content(root)
            .action(a.getString(R.string.hl_delete)) { onDelete() }
            .action(a.getString(R.string.hl_add_note)) { onAddNote() }
            .action(a.getString(R.string.act_done), primary = true)
            .show()
    }

    // ---------- 图片笔记 ----------

    /**
     * 看大图（Mac `ImageViewerController`）：图按可用宽显示，下面「编辑… / 删除 / 关闭」。
     * [bitmap] 为 null = 图不在（只给一句话 + 编辑 / 删除，好把这条笔记处理掉）。
     */
    fun showImageViewer(
        a: Activity, bitmap: android.graphics.Bitmap?, sourceLabel: String,
        onEdit: () -> Unit, onDelete: () -> Unit,
    ) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (bitmap != null) {
            root.addView(
                ImageView(a).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        } else {
            root.addView(Ui.body(a, a.getString(R.string.img_missing)))
        }
        val s = Sheet(a).size(720, 0.8f).title(a.getString(R.string.img_title))
        if (sourceLabel.isNotEmpty()) s.subtitle(sourceLabel)
        s.content(root)
            .action(a.getString(R.string.act_delete)) { onDelete() }
            .action(a.getString(R.string.img_view_edit)) { onEdit() }
            .action(a.getString(R.string.act_done), primary = true)
            .show()
    }

    /** 图片笔记编辑器（Mac `ImageNoteEditorController`）：缩略图 + 说明 + 展开方式；删除在左 */
    fun showImageEditor(
        a: Activity, thumb: android.graphics.Bitmap?, caption: String, display: Int,
        onSave: (caption: String, display: Int) -> Unit, onDelete: () -> Unit,
    ) {
        var mode = display
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        if (thumb != null) {
            root.addView(
                ImageView(a).apply {
                    setImageBitmap(thumb)
                    adjustViewBounds = true
                    maxHeight = Ui.dp(a, 180)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        val edit = PadPanels.inputBox(a, a.getString(R.string.img_caption_hint)).apply {
            setSingleLine(false)
            setText(caption)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setSelection(caption.length)
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 10) })
        root.addView(Ui.groupTitle(a, a.getString(R.string.note_show)))
        root.addView(displaySeg(a, display) { mode = it })
        Sheet(a).title(a.getString(R.string.img_title)).content(root)
            .action(a.getString(R.string.act_delete)) { onDelete() }
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_save), primary = true) { onSave(edit.text.toString().trim(), mode) }
            .show()
    }

    // ---------- 笔记类型管理 ----------

    /**
     * 类型列表 + 新建 / 编辑 / 删除（Mac `NoteTypeManagerController`）。每改一次就 [onChange] 整表交出去落库。
     * [usage] 删除前问「有几条笔记在用」（要查库，所以是异步回调）。
     */
    fun showTypeManager(
        a: Activity, initial: List<NoteTypeDef>,
        usage: (typeId: String, done: (Int) -> Unit) -> Unit,
        onChange: (List<NoteTypeDef>) -> Unit,
    ) {
        var types = initial
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lateinit var rebuild: () -> Unit
        fun commit(next: List<NoteTypeDef>) { types = next; onChange(next); rebuild() }
        rebuild = {
            list.removeAllViews()
            if (types.isEmpty()) list.addView(Ui.body(a, a.getString(R.string.type_none)))
            for (t in types) {
                val tail = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Ui.iconButton(a, R.drawable.ic_pen, a.getString(R.string.type_edit)) {
                        showTypeEdit(a, t, isNew = false) { saved -> commit(types.map { if (it.id == saved.id) saved else it }) }
                    })
                    addView(Ui.iconButton(a, R.drawable.ic_delete, a.getString(R.string.act_delete)) {
                        usage(t.id) { n ->
                            val s = Sheet(a).title(a.getString(R.string.type_delete_confirm, t.name))
                            if (n > 0) s.subtitle(a.getString(R.string.type_delete_msg, n))
                            s.action(a.getString(R.string.act_cancel))
                                .action(a.getString(R.string.act_delete), primary = true) {
                                    commit(types.filter { it.id != t.id })
                                }
                                .show()
                        }
                    })
                }
                list.addView(typeRow(a, t.rgb, t.iconName, t.name, trailing = tail, onClick = null))
            }
        }
        rebuild()
        Sheet(a).title(a.getString(R.string.type_manage)).content(list)
            .action(a.getString(R.string.type_new), dismiss = false) {
                val draft = NoteTypeDef(UUID.randomUUID().toString().uppercase(), "", Palette.keys[0], NoteTypeDef.ICONS[0])
                showTypeEdit(a, draft, isNew = true) { saved -> commit(types + saved) }
            }
            .action(a.getString(R.string.act_done), primary = true)
            .show()
    }

    /** 单个类型的新建 / 编辑：名称 + 8 色 + 16 图标网格；名称空着不让存（Mac `NoteTypeEditController`） */
    fun showTypeEdit(a: Activity, draft: NoteTypeDef, isNew: Boolean, onDone: (NoteTypeDef) -> Unit) {
        var colorKey = draft.colorKey.takeIf { it in Palette.keys } ?: Palette.keys[0]
        var icon = NoteTypeDef.icon(draft.iconName)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val name = PadPanels.inputBox(a, a.getString(R.string.type_name_hint)).apply {
            setText(draft.name)
            setSelection(text.length)
        }
        root.addView(name)
        val colors = PadPanels.row(a)
        // 16 个图标两行各 8 个，每格按宽度均分（固定格宽在手机竖屏上最右一列会被裁）
        val iconBtns = ArrayList<ImageButton>(NoteTypeDef.ICONS.size)
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        lateinit var sync: () -> Unit
        sync = {
            colors.removeAllViews()
            for (k in Palette.keys) {
                val v = dot(a, Palette.rgb(k), k == colorKey, 26)
                v.setOnClickListener { colorKey = k; sync() }
                colors.addView(v)
            }
            for ((i, b) in iconBtns.withIndex()) b.setActive(NoteTypeDef.ICONS[i] == icon, Ui.onSurface(a), Ui.accent(a))
        }
        for (chunk in NoteTypeDef.ICONS.chunked(8)) {
            val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (sym in chunk) {
                val b = Ui.iconButton(a, NoteTypeIcons.drawableFor(sym), sym) { icon = sym; sync() }
                iconBtns.add(b)
                // 格子均分、按钮本身保持方形居中（激活底色是方的，拉宽了会变长方形）
                r.addView(
                    android.widget.FrameLayout(a).apply {
                        addView(b, android.widget.FrameLayout.LayoutParams(Ui.dp(a, Ui.TOUCH), Ui.dp(a, Ui.TOUCH), Gravity.CENTER))
                    },
                    LinearLayout.LayoutParams(0, Ui.dp(a, Ui.TOUCH), 1f),
                )
            }
            grid.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(colors, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 10) })
        root.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 6) })
        sync()
        Sheet(a).title(a.getString(if (isNew) R.string.type_new else R.string.type_edit)).content(root)
            .action(a.getString(R.string.act_cancel))
            .action(a.getString(R.string.act_save), primary = true, dismiss = false) { d ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) return@action   // 名称为空禁存（同 Mac），弹层留着
                d.dismiss()
                onDone(draft.copy(name = n, colorKey = colorKey, iconName = icon))
            }
            .show()
    }

    // ---------- 零件 ----------

    private fun label(a: Activity, s: String) = TextView(a).apply {
        text = s
        textSize = 15f
        setTextColor(Ui.onSurface(a))
    }

    /** 一行类型：色点 + 图标 + 名称 + 尾部（勾 / 编辑删除键）。[onClick] 为 null = 行本身不可点 */
    private fun typeRow(
        a: Activity, rgb: IntArray, icon: String, name: String, trailing: View?, onClick: (() -> Unit)?,
    ): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val p = Ui.dp(a, 8)
        setPadding(p, if (onClick != null) Ui.dp(a, 12) else Ui.dp(a, 2), p, if (onClick != null) Ui.dp(a, 12) else Ui.dp(a, 2))
        if (onClick != null) {
            isClickable = true
            background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
            setOnClickListener { onClick() }
        }
        addView(dot(a, rgb, false, 12))
        addView(typeIcon(a, icon), LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20)).apply {
            marginStart = Ui.dp(a, 10)
            marginEnd = Ui.dp(a, 10)
        })
        addView(
            TextView(a).apply {
                text = name
                textSize = 15f
                setTextColor(Ui.onSurface(a))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (trailing != null) addView(trailing)
    }

    private fun check(a: Activity) = ImageView(a).apply {
        setImageResource(R.drawable.ic_check)
        imageTintList = android.content.res.ColorStateList.valueOf(Ui.accent(a))
        layoutParams = LinearLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20))
    }

    private fun typeIcon(a: Activity, symbol: String) = ImageView(a).apply {
        setImageResource(NoteTypeIcons.drawableFor(NoteTypeDef.icon(symbol)))
        imageTintList = android.content.res.ColorStateList.valueOf(Ui.onSurface(a))
    }

    /** 一枚色点：扁平圆；选中时描一圈 accent（同 Mac `SwatchButton.selectedRing`） */
    private fun dot(a: Activity, rgb: IntArray, selected: Boolean, sizeDp: Int): View = View(a).apply {
        val d = Ui.dp(a, sizeDp)
        layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = Ui.dp(a, 8) }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(rgb[0], rgb[1], rgb[2]))
            if (selected) setStroke(Ui.dp(a, 2), Ui.accent(a)) else setStroke(Ui.dp(a, 1), Ui.outline(a))
        }
        isClickable = true
    }

    private fun sameRgb(x: IntArray?, y: IntArray?): Boolean =
        if (x == null || y == null) x == null && y == null else x[0] == y[0] && x[1] == y[1] && x[2] == y[2]
}
