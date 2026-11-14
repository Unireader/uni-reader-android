package com.xvan.unireader.shared

import android.app.Activity
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
            }
        }
        box.addView(View(a), LinearLayout.LayoutParams(-1, Ui.dp(a, 48)))
    }
}
