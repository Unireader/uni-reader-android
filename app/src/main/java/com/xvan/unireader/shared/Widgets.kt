package com.xvan.unireader.shared

import android.widget.TextView

/**
 * 文本没变就别碰。HUD 那几个控件每帧都会被刷（滚动/缩放都触发），
 * 无脑 `setText` 等于每帧给十来个 TextView 各请求一次 measure/layout——白烧的一帧。
 */
fun TextView.setTextIfChanged(s: String) {
    if (text?.toString() != s) text = s
}
