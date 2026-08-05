package com.xvan.unireader.shared

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.roundToInt


/**
 * 两模式共用的小控件。
 *
 * 状态胶囊（对应网页的 PenStat / LayerStat）：左下角一颗药丸，既显示当前状态又是弹层入口。
 * 模式1 与模式2 用同一份样式与同一套文案格式——手感一致是本方案的原则（§5.1），
 * 各写一份迟早会长歪成「两个 App」。
 */
fun capsule(a: Activity): TextView {
    fun dp(v: Int) = (v * a.resources.displayMetrics.density).roundToInt()
    return TextView(a).apply {
        textSize = 13f
        // 颜色走语义名（跟随系统深浅色，见 shared/Ui.kt）：胶囊浮在页面上，与顶栏同一套底色，
        // 写死深色的话浅色模式下就是页面上贴了两块黑斑
        setTextColor(Ui.col(a, R.color.bar_on))
        setPadding(dp(14), dp(7), dp(14), dp(7))
        val face = GradientDrawable().apply {
            cornerRadius = dp(Ui.PILL).toFloat()
            setColor(Ui.col(a, R.color.bar_scrim))
            setStroke(dp(1), Ui.col(a, R.color.bar_outline))
        }
        background = Ui.rippleOver(a, face, Ui.PILL, Ui.col(a, R.color.bar_on))
        isClickable = true
    }
}

/**
 * 文本没变就别碰。HUD 那几个控件每帧都会被刷（滚动/缩放都触发），
 * 无脑 `setText` 等于每帧给十来个 TextView 各请求一次 measure/layout——白烧的一帧。
 */
fun TextView.setTextIfChanged(s: String) {
    if (text?.toString() != s) text = s
}

/**
 * 笔胶囊前的色块，对应网页 PenStat 的 `<span class="sw">`。传 `null` 清空（非笔记模式/无当前笔）。
 * 复用同一个 GradientDrawable 换色而非重建，理由同 setTextIfChanged——HUD 每帧都会被刷一次。
 */
fun TextView.setPenSwatch(color: Int?) {
    if (color == null) {
        if (compoundDrawablesRelative[0] != null) setCompoundDrawablesRelative(null, null, null, null)
        return
    }
    val size = (10 * resources.displayMetrics.density).roundToInt()
    val existing = compoundDrawablesRelative[0] as? GradientDrawable
    if (existing != null) {
        existing.setColor(color)
    } else {
        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(size, size)
            setColor(color)
            setBounds(0, 0, size, size)
        }
        setCompoundDrawablesRelative(dot, null, null, null)
        compoundDrawablePadding = (6 * resources.displayMetrics.density).roundToInt()
    }
}
