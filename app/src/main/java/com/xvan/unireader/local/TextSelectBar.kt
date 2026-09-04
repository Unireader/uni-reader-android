package com.xvan.unireader.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.xvan.unireader.shared.PadConst
import com.xvan.unireader.shared.TextSelection
import com.xvan.unireader.shared.Ui
import com.xvan.unireader.shared.Ui.setBackgroundKeepPadding

/**
 * 划字之后浮出来的那条动作条：**四色高亮 + 批注 + 复制**。
 *
 * 对应 Mac 的阅读区右键菜单（`ReaderSurface.readerContextMenu` 的选区分支：Add Note / Highlight
 * 子菜单 / Copy）——平板上没有右键，选完直接把这三件事摆出来。
 *
 * **贴屏幕底部居中，不跟着选区跑**：跟着选区的话，一边拖一边它就在指头底下乱窜，
 * 还会挡住正在划的那几行；而这条只在松手之后才有用。
 */
class TextSelectBar(private val ctx: Context) {

    /** 点了某个荧光色 */
    var onHighlight: (IntArray) -> Unit = {}
    /** 点了「批注」 */
    var onAnnotate: () -> Unit = {}

    private var selection: TextSelection? = null

    /**
     * 这条浮条的视图。**由宿主自己 addView 定 z 序**（同 `ScratchController.barView` 的做法）——
     * 这里 `bringToFront` 的话它会盖到抽屉上面去。
     */
    val view: LinearLayout by lazy { build() }

    /** 宿主用的布局参数：贴底居中 */
    fun layoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(ctx, 24)
        }

    fun show(sel: TextSelection?) {
        selection = sel
        view.visibility = if (sel == null || sel.isEmpty) View.GONE else View.VISIBLE
    }

    fun hide() = show(null)

    private fun build(): LinearLayout = LinearLayout(ctx).apply {
        visibility = View.GONE
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Ui.dp(ctx, 8)
        setPadding(pad, pad, pad, pad)
        setBackgroundKeepPadding(Ui.round(Ui.container(ctx), 14, ctx, Ui.outline(ctx)))
        for ((name, rgb) in PadConst.HIGHLIGHT_PALETTE) addView(swatch(name, rgb))
        addView(Ui.textButton(ctx, "批注", primary = true) { onAnnotate() })
        addView(Ui.textButton(ctx, "复制") { copy() })
    }

    /** 一枚荧光色：**就画成那个颜色的圆点**，不写色名——四个色块一眼比四个字快 */
    private fun swatch(name: String, rgb: IntArray): View = View(ctx).apply {
        val d = Ui.dp(ctx, 28)
        layoutParams = LinearLayout.LayoutParams(d, d).apply { marginEnd = Ui.dp(ctx, 6) }
        background = Ui.round(Color.rgb(rgb[0], rgb[1], rgb[2]), 14, ctx, Ui.outline(ctx))
        contentDescription = "高亮：$name"
        setOnClickListener { onHighlight(rgb) }
    }

    private fun copy() {
        val text = selection?.text.orEmpty()
        if (text.isEmpty()) return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("UniReader", text))
        Toast.makeText(ctx, "已复制 ${text.length} 字", Toast.LENGTH_SHORT).show()
    }
}
