package com.xvan.unireader.shared

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
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
        setTextColor(0xFFE6EDF3.toInt())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(0xD9161B22.toInt())
            setStroke(dp(1), 0xFF30363D.toInt())
        }
    }
}

/**
 * 文本没变就别碰。HUD 那几个控件每帧都会被刷（滚动/缩放都触发），
 * 无脑 `setText` 等于每帧给十来个 TextView 各请求一次 measure/layout——白烧的一帧。
 */
fun TextView.setTextIfChanged(s: String) {
    if (text?.toString() != s) text = s
}
