package com.xvan.unireader.shared

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.anim.AnimBlockView
import com.xvan.unireader.shared.exercise.MathText

/**
 * Markdown 笔记的**阅读态**排版（两模式共用）：把正文按 [MdDisplay.segments] 切段，逐段摆进 [box]——
 * 文字段走 Markwon + 公式（[MathText.bindNote]），```anim 块画成能逐步播放的演示，单独一行的 `![[图]]` 由宿主给一张图的视图。
 *
 * 宿主只管两件事：图从哪来（[image]：模式1 读本机附件，模式2 按 Mac 给的 sha 走 `/image?h=`）、
 * 点了链接怎么办（[onLink]：参数是显示用链接，`uniwiki:` / `uniimg:` 前缀见 [MdDisplay]）。
 */
object MdRender {

    fun into(
        a: Activity,
        box: LinearLayout,
        text: String,
        image: (target: String) -> View,
        onLink: (String) -> Unit,
        emptyText: String = a.getString(R.string.md_empty),
    ) {
        box.removeAllViews()
        if (text.isBlank()) {
            box.addView(Ui.body(a, emptyText), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = Ui.dp(a, 16)
            })
            return
        }
        seen.clear()   // 同样标题的 callout 按出现次序区分（折叠状态的键）
        for (seg in MdDisplay.segments(text)) {
            when (seg) {
                is MdDisplay.Segment.Text -> box.addView(
                    TextView(a).apply {
                        textSize = 16f
                        setTextColor(Ui.onSurface(a))
                        setLineSpacing(0f, 1.25f)
                        setTextIsSelectable(false)
                        MathText.bindNote(this, seg.md) { link -> onLink(link) }
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 8) },
                )
                // 分步演示：能逐步播放的画布（同 Mac 的 ```anim 块）
                is MdDisplay.Segment.Anim -> box.addView(
                    AnimBlockView(a).apply { setSource(seg.source) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 12) },
                )
                // 图按原像素大小显示、宽过正文栏才缩（小图拉满一栏会糊）
                is MdDisplay.Segment.Image -> box.addView(image(seg.target), LinearLayout.LayoutParams(-2, -2).apply {
                    topMargin = Ui.dp(a, 8)
                })
                is MdDisplay.Segment.Callout -> box.addView(
                    callout(a, seg, onLink, occurrence(seg, seen)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 10) },
                )
            }
        }
        box.addView(View(a), LinearLayout.LayoutParams(-1, Ui.dp(a, 48)))
    }

    // —— callout（`> [!type] 标题`，`../docs/MARKDOWN-NOTES-PLAN.md §23`）——

    /**
     * 可折叠的 callout（`[!type]-` 默认折叠、`[!type]+` 默认展开）这次进程里被点过的：键 = 类型 + 标题 + 同样标题的第几个，
     * 值 = 折叠着。只是显示状态，不写正文；模式2 跟着 Mac 每次改字重排时不丢。
     */
    private val folds = HashMap<String, Boolean>()

    private fun occurrence(seg: MdDisplay.Segment.Callout, seen: HashMap<String, Int>): String {
        val base = seg.typeText + "|" + seg.title
        val n = seen.getOrDefault(base, 0)
        seen[base] = n + 1
        return "$n|$base"
    }

    private val seen = HashMap<String, Int>()

    private fun colorRes(name: String): Int = when (name) {
        "cyan" -> R.color.callout_cyan
        "green" -> R.color.callout_green
        "orange" -> R.color.callout_orange
        "red" -> R.color.callout_red
        "purple" -> R.color.callout_purple
        "gray" -> R.color.callout_gray
        else -> R.color.callout_blue
    }

    /** 一个 callout：类型色淡底 + 左边类型色竖条（同 Mac），粗体类型色标题；能折叠的点标题收起 / 展开正文。 */
    private fun callout(a: Activity, seg: MdDisplay.Segment.Callout, onLink: (String) -> Unit, key: String): View {
        val color = Ui.col(a, colorRes(MdDisplay.Callouts.colorName(seg.typeText)))
        val boxView = CalloutBox(a, color)
        val title = seg.title.ifEmpty { MdDisplay.Callouts.defaultTitle(seg.typeText) }
        val titleView = TextView(a).apply {
            textSize = 16f
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.2f)
        }
        val bodyView = TextView(a).apply {
            textSize = 16f
            setTextColor(Ui.onSurface(a))
            setLineSpacing(0f, 1.25f)
            setTextIsSelectable(false)
            visibility = if (seg.body.isBlank()) View.GONE else View.VISIBLE
        }
        if (seg.body.isNotBlank()) MathText.bindNote(bodyView, seg.body) { link -> onLink(link) }
        val foldable = seg.fold != null && seg.body.isNotBlank()
        fun show() {
            val collapsed = foldable && (folds[key] ?: (seg.fold == '-'))
            // 折叠符：系统里展开 / 收起的通用写法（▸ 收着、▾ 展开着）
            val chevron = if (!foldable) "" else if (collapsed) "▸ " else "▾ "
            MathText.bindNote(titleView, chevron + "**" + title + "**") { link -> onLink(link) }
            bodyView.visibility = if (collapsed || seg.body.isBlank()) View.GONE else View.VISIBLE
        }
        show()
        if (foldable) {
            titleView.setOnClickListener {
                folds[key] = !(folds[key] ?: (seg.fold == '-'))
                show()
            }
        }
        boxView.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        boxView.addView(bodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(a, 4) })
        return boxView
    }

    /** callout 的框：类型色 10% 的底 + 左边 3dp 的类型色竖条（扁平，不加圆角阴影）。 */
    private class CalloutBox(c: Context, private val color: Int) : LinearLayout(c) {
        private val paint = Paint()
        private val bar = Ui.dpf(c, 3f)

        init {
            orientation = VERTICAL
            setWillNotDraw(false)
            setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 8))
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = (color and 0x00FFFFFF) or (0x1A shl 24)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = color
            canvas.drawRect(0f, 0f, bar, height.toFloat(), paint)
            super.onDraw(canvas)
        }
    }
}
