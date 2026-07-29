package com.xvan.unireader.shared

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏内边距。
 *
 * **targetSdk 35+ 起 Android 强制 edge-to-edge**：Activity 默认画到状态栏/导航栏底下。不处理的话
 * 顶栏会压在时钟上——不只是难看，**那块区域的点击会被系统栏吃掉**，按钮看着在、按下去没反应
 * （模式1 的模式/笔按钮就是这么失灵的）。
 *
 * 模式2 的输入板不用这个：它整屏沉浸（`enterImmersive`），系统栏直接收掉。
 */
fun View.onSystemBarInsets(apply: (top: Int, bottom: Int) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        apply(bars.top, bars.bottom)
        insets
    }
}
