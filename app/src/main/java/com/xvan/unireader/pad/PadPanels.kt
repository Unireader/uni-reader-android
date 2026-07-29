package com.xvan.unireader.pad

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 各弹层面板（对应网页的 PenStat / LayerStat / TextNoteEditor / 页码输入 / 文档下拉）。
 * 网页那边是锚定在胶囊上的毛玻璃 popover，这里退化成系统对话框——平板上单手可达，
 * 且不用自己处理外点关闭/焦点/输入法。行为（可改什么、怎么上行）与网页一一对应。
 */
object PadPanels {

    private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun swatch(a: Activity, color: Int): View = View(a).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(a.dp(1), Color.argb(115, 255, 255, 255))
        }
    }

    private fun label(a: Activity, s: String, size: Float = 14f): TextView =
        TextView(a).apply { text = s; textSize = size }

    private fun column(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(4))
    }

    private fun penColor(p: WireCodec.Pen) =
        Color.argb((p.a * 255).roundToInt().coerceIn(0, 255), p.r, p.g, p.b)

    // ---------- 笔宽 + 橡皮设置（网页 PenStat 弹层） ----------

    /**
     * 笔宽 2~40（改动即时本地生效，`onPenset` 由调用方防抖上行 penset）；
     * 橡皮整笔/局部 + 直径（页宽 %）+ 尺寸圆环开关（防抖上行 eraser，与 Mac PenRack 橡皮 popover 双向同步）。
     */
    fun showPenPanel(
        a: Activity,
        pad: PadView,
        onPenset: () -> Unit,
        onEraser: () -> Unit,
    ) {
        val root = column(a)
        root.addView(label(a, "笔宽", 13f).apply { setTextColor(Color.GRAY) })
        pad.penList().forEachIndexed { i, pen ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                swatch(a, penColor(pen)),
                LinearLayout.LayoutParams(a.dp(14), a.dp(14)).apply { marginEnd = a.dp(8) },
            )
            row.addView(
                label(a, PadConst.brushLabel(WireCodec.brushName(pen.brush)), 13f),
                LinearLayout.LayoutParams(a.dp(64), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            val value = label(a, "${pen.w.roundToInt()}", 13f)
            val bar = SeekBar(a).apply {
                min = 2
                max = 40
                progress = pen.w.roundToInt().coerceIn(2, 40)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        value.text = "$v"
                        pad.setPenWidth(i, v.toFloat())
                        onPenset()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(a.dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(row)
        }

        root.addView(label(a, "橡皮", 13f).apply {
            setTextColor(Color.GRAY)
            setPadding(0, a.dp(10), 0, 0)
        })

        // 整笔 / 局部 + 圆环开关
        val modeRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lateinit var wholeBtn: Button
        lateinit var partBtn: Button
        fun syncModeBtns() {   // 当前档打勾（不用 disabled 表示选中，那读起来像"不可用"）
            wholeBtn.text = if (pad.eraserMode == 0) "整笔 ✓" else "整笔"
            partBtn.text = if (pad.eraserMode == 1) "局部 ✓" else "局部"
        }
        wholeBtn = Button(a)
        partBtn = Button(a)
        wholeBtn.setOnClickListener {
            pad.setEraserLocal(pad.eraserSize, 0, pad.eraserRing); syncModeBtns(); onEraser()
        }
        partBtn.setOnClickListener {
            pad.setEraserLocal(pad.eraserSize, 1, pad.eraserRing); syncModeBtns(); onEraser()
        }
        syncModeBtns()
        modeRow.addView(wholeBtn)
        modeRow.addView(partBtn)
        modeRow.addView(CheckBox(a).apply {
            text = "圆环"
            isChecked = pad.eraserRing
            setOnCheckedChangeListener { _, on ->
                pad.setEraserLocal(pad.eraserSize, pad.eraserMode, on); onEraser()
            }
        })
        root.addView(modeRow)

        // 直径 = 归一化半径 × 200（%），1.0~12.0 步进 0.5 → SeekBar 用 2~24 的半步整数
        val dRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pct = (pad.eraserSize * 2000).roundToInt() / 10f
        val dVal = label(a, "$pct%", 13f)
        dRow.addView(
            label(a, "直径", 13f),
            LinearLayout.LayoutParams(a.dp(48), LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        dRow.addView(
            SeekBar(a).apply {
                min = 2
                max = 24
                progress = (pct * 2).roundToInt().coerceIn(2, 24)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val p = v / 2f
                        dVal.text = "$p%"
                        pad.setEraserLocal(p / 200f, pad.eraserMode, pad.eraserRing)
                        onEraser()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        dRow.addView(dVal, LinearLayout.LayoutParams(a.dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(dRow)

        AlertDialog.Builder(a)
            .setTitle("笔 / 橡皮")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("完成", null)
            .show()
    }

    // ---------- 图层（网页 LayerStat 弹层） ----------

    /**
     * 切换作画图层 / 显示隐藏 / 新建——三者都只是「请求」，图层的增删改全部由 Mac 判定，
     * 发完不本地抢改列表，等 Mac 广播 layers 回权威状态。
     */
    fun showLayerPanel(
        a: Activity,
        layers: List<WireCodec.Layer>,
        activeIdx: Int,
        onSelect: (Int) -> Unit,
        onToggleVisible: (Int, Boolean) -> Unit,
        onAdd: () -> Unit,
    ) {
        val root = column(a)
        if (layers.isEmpty()) {
            root.addView(label(a, "（还没收到 Mac 的图层表）", 13f).apply { setTextColor(Color.GRAY) })
        }
        val dlg = AlertDialog.Builder(a).setTitle("图层").setPositiveButton("完成", null)
        lateinit var dialog: AlertDialog
        layers.forEachIndexed { i, l ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, a.dp(4), 0, a.dp(4))
                isClickable = true
                setOnClickListener { onSelect(i); dialog.dismiss() }
            }
            row.addView(Button(a).apply {
                text = if (l.visible) "👁" else "🚫"
                setOnClickListener { onToggleVisible(i, !l.visible); dialog.dismiss() }
            })
            row.addView(
                swatch(a, Color.rgb(l.r, l.g, l.b)),
                LinearLayout.LayoutParams(a.dp(14), a.dp(14)).apply {
                    marginStart = a.dp(8); marginEnd = a.dp(8)
                },
            )
            row.addView(label(a, if (i == activeIdx) "● ${l.name}" else l.name))
            root.addView(row)
        }
        root.addView(Button(a).apply {
            text = "＋ 新建图层"
            setOnClickListener { onAdd(); dialog.dismiss() }
        })
        dialog = dlg.setView(ScrollView(a).apply { addView(root) }).create()
        dialog.show()
    }

    // ---------- 文字笔记编辑器 ----------

    /** 空文本保存 = 删除（与 Mac「空 upsert 即删除」语义一致）；新建时不显示删除按钮 */
    fun showNoteEditor(
        a: Activity,
        text: String,
        isNew: Boolean,
        onSave: (String) -> Unit,
        onDelete: () -> Unit,
    ) {
        val edit = EditText(a).apply {
            setText(text)
            hint = "输入笔记内容…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setSelection(text.length)
        }
        val b = AlertDialog.Builder(a)
            .setTitle(if (isNew) "新建笔记" else "编辑笔记")
            .setView(column(a).apply { addView(edit) })
            .setPositiveButton("保存") { _, _ -> onSave(edit.text.toString().trim()) }
            .setNegativeButton("取消", null)
        if (!isNew) b.setNeutralButton("删除") { _, _ -> onDelete() }
        b.show()
    }

    // ---------- 跳页 ----------

    fun showGotoPage(a: Activity, pageCount: Int, onGo: (Int) -> Unit) {
        val edit = EditText(a).apply {
            hint = if (pageCount > 0) "1 ~ $pageCount" else "页码"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(a)
            .setTitle("跳转到页码")
            .setView(column(a).apply { addView(edit) })
            .setPositiveButton("跳转") { _, _ ->
                edit.text.toString().trim().toIntOrNull()?.let { onGo(it) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 文档选择（网页顶栏下拉） ----------

    /** 第一项固定是「跟随 Mac」（发 selectDoc "" ） */
    fun showDocsPicker(
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
