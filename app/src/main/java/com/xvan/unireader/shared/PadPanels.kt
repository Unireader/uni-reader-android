package com.xvan.unireader.shared

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.roundToInt

/**
 * 各弹层面板（对应网页的 PenStat / LayerStat / TextNoteEditor / 页码输入）。
 * 网页那边是锚定在胶囊上的毛玻璃 popover，这里退化成系统对话框——平板上单手可达，
 * 且不用自己处理外点关闭/焦点/输入法。行为（可改什么）与网页一一对应。
 *
 * 外观统一走 [Sheet]（§9.6）：圆角卡片 + 语义色，深浅色自动跟随。**别再直接 `AlertDialog.Builder`**
 * ——那样出来的是框架默认的直角白底 + 全大写按钮，与其余界面对不上。
 *
 * **两种模式共用这一份**（`ANDROID-STANDALONE-PLAN.md §5.1`）：面板只描述「用户改了什么」，
 * 通过回调交给宿主——模式2 编成线格式帧发给 Mac 等权威回推，模式1 直接落 SQLite。
 * 图层的接口一律**按下标**（`onSelect(i)`/`onToggleVisible(i, v)`），因为线格式就是按下标发的；
 * 模式1 那边自己拿下标去 `LibInkLayer` 列表换 id。
 *
 * 文档下拉不在这儿——「在已打开的几篇之间切」现在是 [DocTabsBar]（2026-08-28 起两模式共用），
 * 「开一篇还没打开的」是 [ReaderDrawer] 的书库页。
 */
object PadPanels {

    internal fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun swatch(a: Activity, color: Int): View = View(a).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            // 描边用 outline 而不是半透明白：浅色主题下白描边在浅色底上等于没有
            setStroke(a.dp(1), Ui.outline(a))
        }
    }

    private fun label(a: Activity, s: String, size: Float = 14f): TextView =
        TextView(a).apply {
            text = s
            textSize = size
            setTextColor(Ui.onSurface(a))
        }

    private fun penColor(p: Pen) =
        Color.argb((p.a * 255).roundToInt().coerceIn(0, 255), p.r, p.g, p.b)

    /** 一行控件的通用容器（横排、垂直居中、上下留一点气口） */
    private fun row(a: Activity): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, a.dp(4), 0, a.dp(4))
    }

    // ---------- 笔宽 + 橡皮设置（网页 PenStat 弹层） ----------

    /**
     * 笔宽 2~40（改动即时本地生效）；橡皮整笔/局部 + 直径（页宽 %）+ 尺寸圆环开关。
     *
     * @param onPenset 笔宽变了。模式2 用来防抖上行 `penset`；模式1 只有本地状态，传空即可。
     * @param onEraser 橡皮设置变了。模式2 防抖上行 `eraser`（与 Mac PenRack 的橡皮 popover 双向同步）。
     */
    fun showPenPanel(
        a: Activity,
        pad: PageCanvasView,
        onPenset: () -> Unit = {},
        onEraser: () -> Unit = {},
    ) {
        val sheet = Sheet(a).title("笔 / 橡皮")
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Ui.groupTitle(a, "笔宽", top = 0))
        pad.penList().forEachIndexed { i, pen ->
            val r = row(a)
            r.addView(
                swatch(a, penColor(pen)),
                LinearLayout.LayoutParams(a.dp(14), a.dp(14)).apply { marginEnd = a.dp(10) },
            )
            r.addView(
                label(a, PadConst.brushLabel(brushName(pen.brush)), 13f),
                LinearLayout.LayoutParams(a.dp(60), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            val value = label(a, "${pen.w.roundToInt()}", 13f).apply {
                gravity = Gravity.END
                setTextColor(Ui.onVariant(a))
            }
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
            r.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(value, LinearLayout.LayoutParams(a.dp(30), LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(r)
        }

        root.addView(Ui.groupTitle(a, "橡皮"))

        // 整笔 / 局部：做成分段控件（选中那格 accent 底），不再靠给文案加"✓"
        val segRow = row(a)
        lateinit var sync: () -> Unit
        val whole = segButton(a, "整笔") {
            pad.setEraserLocal(pad.eraserSize, 0, pad.eraserRing); sync(); onEraser()
        }
        val part = segButton(a, "局部") {
            pad.setEraserLocal(pad.eraserSize, 1, pad.eraserRing); sync(); onEraser()
        }
        sync = {
            setSegActive(a, whole, pad.eraserMode == 0)
            setSegActive(a, part, pad.eraserMode == 1)
        }
        sync()
        segRow.addView(whole, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(8) })
        segRow.addView(part, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = a.dp(12) })
        segRow.addView(
            CheckBox(a).apply {
                text = "圆环"
                setTextColor(Ui.onSurface(a))
                isChecked = pad.eraserRing
                setOnCheckedChangeListener { _, on ->
                    pad.setEraserLocal(pad.eraserSize, pad.eraserMode, on); onEraser()
                }
            },
        )
        root.addView(segRow)

        // 直径 = 归一化半径 × 200（%），1.0~12.0 步进 0.5 → SeekBar 用 2~24 的半步整数
        val dRow = row(a)
        val pct = (pad.eraserSize * 2000).roundToInt() / 10f
        val dVal = label(a, "$pct%", 13f).apply {
            gravity = Gravity.END
            setTextColor(Ui.onVariant(a))
        }
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
        dRow.addView(dVal, LinearLayout.LayoutParams(a.dp(46), LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(dRow)

        sheet.content(root).action("完成", primary = true).show()
    }

    /** 分段控件的一格（选中态由 [setSegActive] 切） */
    private fun segButton(a: Activity, s: String, onClick: () -> Unit): TextView =
        TextView(a).apply {
            text = s
            textSize = 14f
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(0, a.dp(10), 0, a.dp(10))
            setOnClickListener { onClick() }
        }

    private fun setSegActive(a: Activity, v: TextView, active: Boolean) {
        v.setTextColor(if (active) Ui.accent(a) else Ui.onSurface(a))
        val face =
            if (active) Ui.round(Ui.col(a, R.color.accent_container), Ui.RADIUS, a)
            else Ui.round(Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
        v.background = Ui.rippleOver(a, face, Ui.RADIUS, Ui.onSurface(a))
    }

    // ---------- 图层（网页 LayerStat 弹层） ----------

    /**
     * 切换作画图层 / 显示隐藏 / 新建。三个回调都只表达「用户点了什么」：
     * - 模式2：编帧上行，**不本地抢改列表**，等 Mac 广播 `layers` 回权威状态；
     * - 模式1：宿主自己写 `ink_layer` 表再重读（真源就在进程内）。
     */
    fun showLayerPanel(
        a: Activity,
        layers: List<Layer>,
        activeIdx: Int,
        onSelect: (Int) -> Unit,
        onToggleVisible: (Int, Boolean) -> Unit,
        onAdd: () -> Unit,
        /** 空表时的说明。两模式的「为什么空」不是一回事，所以由宿主给文案 */
        emptyHint: String = "（还没有图层）",
    ) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sheet = Sheet(a).title("图层")
        if (layers.isEmpty()) root.addView(Ui.body(a, emptyHint))
        lateinit var dialog: android.app.AlertDialog
        layers.forEachIndexed { i, l ->
            val r = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
                setPadding(a.dp(6), a.dp(6), a.dp(6), a.dp(6))
                setOnClickListener { onSelect(i); dialog.dismiss() }
            }
            // 当前作画层：色点前面一枚 accent 的实心点，比在名字前拼「● 」可靠（名字可能很长被挤掉）
            r.addView(
                View(a).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (i == activeIdx) Ui.accent(a) else Color.TRANSPARENT)
                    }
                },
                LinearLayout.LayoutParams(a.dp(6), a.dp(6)).apply { marginEnd = a.dp(8) },
            )
            r.addView(
                swatch(a, Color.rgb(l.r, l.g, l.b)),
                LinearLayout.LayoutParams(a.dp(14), a.dp(14)).apply { marginEnd = a.dp(10) },
            )
            r.addView(
                label(a, l.name),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            // 眼睛图标代替原来的 👁/🚫 emoji 按钮：emoji 在不同 ROM 上大小与基线都不一样
            r.addView(
                Ui.iconButton(
                    a,
                    if (l.visible) R.drawable.ic_eye else R.drawable.ic_eye_off,
                    if (l.visible) "隐藏图层 ${l.name}" else "显示图层 ${l.name}",
                    if (l.visible) Ui.onSurface(a) else Ui.onVariant(a),
                ) { onToggleVisible(i, !l.visible); dialog.dismiss() },
            )
            root.addView(r)
        }
        root.addView(
            Ui.button(a, "＋ 新建图层") { onAdd(); dialog.dismiss() }
                .apply {
                    (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(-1, -2))
                        .also { layoutParams = it.apply { topMargin = a.dp(10) } }
                },
        )
        dialog = sheet.content(root).action("完成", primary = true).show()
    }

    // ---------- 文字笔记编辑器 ----------

    /**
     * 空文本保存 = 删除（与 Mac「空 upsert 即删除」语义一致）；新建时不显示删除按钮。
     * 「展开方式」分段 = 这条笔记自己的属性（0=点击 1=悬浮 2=始终），随保存一起上行/落库。
     */
    fun showNoteEditor(
        a: Activity,
        text: String,
        isNew: Boolean,
        display: Int,
        onSave: (String, Int) -> Unit,
        onDelete: () -> Unit,
    ) {
        val edit = inputBox(a, "输入笔记内容…").apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setSelection(text.length)
        }
        var mode = display
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(edit)
        root.addView(Ui.groupTitle(a, "展开方式"))
        val segRow = row(a)
        val cells = ArrayList<TextView>(3)
        lateinit var sync: () -> Unit
        for ((i, name) in listOf("点击", "悬浮", "始终").withIndex()) {
            val cell = segButton(a, name) { mode = i; sync() }
            cells.add(cell)
            segRow.addView(
                cell,
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < 2) marginEnd = a.dp(8) },
            )
        }
        sync = { for (i in cells.indices) setSegActive(a, cells[i], mode == i) }
        sync()
        root.addView(segRow)

        val sheet = Sheet(a).title(if (isNew) "新建笔记" else "编辑笔记").content(root)
        if (!isNew) sheet.action("删除") { onDelete() }
        sheet.action("取消")
            .action("保存", primary = true) { onSave(edit.text.toString().trim(), mode) }
            .show()
    }

    // ---------- 跳页 ----------

    fun showGotoPage(a: Activity, pageCount: Int, onGo: (Int) -> Unit) {
        val edit = inputBox(a, if (pageCount > 0) "1 ~ $pageCount" else "页码").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        Sheet(a).title("跳转到页码")
            .content(edit)
            .action("取消")
            .action("跳转", primary = true) {
                edit.text.toString().trim().toIntOrNull()?.let { onGo(it) }
            }
            .show()
    }

    /**
     * 点页面上那面书签缎带弹的小菜单（`../REQUIREMENTS.md §1.9`）。
     * 只有改名/删除——书签点开没有内容可展示（跳转从目录去），页面上这一枚的用处是
     * 「一眼看出这一处标过」+ 就地改名/取消。两模式共用（真源是谁由回调决定）。
     */
    fun showBookmarkMenu(
        a: Activity,
        title: String,
        page: Int,
        onRename: (String) -> Unit,
        onDelete: () -> Unit,
    ) {
        Sheet(a)
            .title(title)
            .subtitle("第 ${page + 1} 页")
            .action("取消")
            .action("重命名") {
                val edit = inputBox(a, "书签名字").apply {
                    setText(title)
                    setSelection(text.length)
                }
                Sheet(a).title("重命名")
                    .content(edit)
                    .action("取消")
                    .action("保存", primary = true) {
                        val t = edit.text.toString().trim()
                        if (t.isNotEmpty()) onRename(t)
                    }
                    .show()
            }
            .action("删除", primary = true) { onDelete() }
            .show()
    }

    /**
     * 输入框：框架默认的 `EditText` 是一条下划线（Holo 遗风），换成圆角描边框。
     * 焦点态不另做——系统会用主题的 accent 给光标与选区上色，够了。
     */
    internal fun inputBox(a: Activity, hintText: String): EditText = EditText(a).apply {
        hint = hintText
        textSize = 15f
        setSingleLine()
        setTextColor(Ui.onSurface(a))
        setHintTextColor(Ui.onVariant(a))
        background = Ui.round(Color.TRANSPARENT, Ui.RADIUS, a, Ui.outline(a))
        setPadding(a.dp(12), a.dp(12), a.dp(12), a.dp(12))
    }

    /** 图标 + 文字的一行（目录浏览器/文档列表用） */
    internal fun iconRow(
        a: Activity,
        icon: Int,
        text: String,
        tint: Int = Ui.onSurface(a),
        trailing: View? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
        setPadding(a.dp(8), a.dp(12), a.dp(8), a.dp(12))
        setOnClickListener { onClick() }
        addView(
            ImageView(a).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
            },
            LinearLayout.LayoutParams(a.dp(20), a.dp(20)).apply { marginEnd = a.dp(12) },
        )
        addView(
            TextView(a).apply {
                this.text = text
                textSize = 15f
                setTextColor(Ui.onSurface(a))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (trailing != null) addView(trailing)
    }

    /**
     * 图标 + 主行 + 灰色次行的一行：主行是名字，次行是「它到底是哪一个」（路径 / IP）。
     * 启动页的「最近打开 / 扫描结果 / 存储卷」和模式2 连接弹窗的「历史设备」共用这一份——
     * 同一种东西在两处长得不一样，是这个项目已经修过一轮的老毛病。
     */
    internal fun twoLineRow(
        a: Activity,
        icon: Int,
        tint: Int,
        line1: String,
        line2: String,
        trailing: View? = null,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        background = Ui.rippleOver(a, null, Ui.RADIUS, Ui.onSurface(a))
        setPadding(a.dp(8), a.dp(10), a.dp(8), a.dp(10))
        setOnClickListener { onClick() }
        addView(
            ImageView(a).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(tint)
            },
            LinearLayout.LayoutParams(a.dp(20), a.dp(20)).apply { marginEnd = a.dp(12) },
        )
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.title(a, line1, 15f))
                addView(
                    Ui.body(a, line2).apply {
                        textSize = 12f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    },
                )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (trailing != null) addView(trailing)
    }
}
