package com.xvan.unireader.local

import android.content.Context
import android.util.AttributeSet
import com.xvan.unireader.shared.PageCanvasView

/**
 * 模式1（本地开工作区）的画布：`PageCanvasView` + 「提交给本机」。
 *
 * 与 `pad/PadView` 是同一个基类的两个子类，几何/手势/渲染完全共用；差别只在覆写的那几个钩子——
 * 一边编成线格式帧发给 Mac，一边落进 `library.sqlite`。
 *
 * M2（阅读）只需要滚动上报这一个钩子：模式2 里它让 Mac 跟随，模式1 里它是**阅读进度**。
 * 笔迹/擦除/框选/注解的钩子留给 M3~M5——届时这里接 `LibraryStore`，
 * 「本地先画、等真源回推再清」那套代码一行都不用改，因为在模式1 里真源就在同一个进程内。
 */
class LocalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PageCanvasView(context, attrs) {

    /** 视口滚动（16ms 节流后的）：宿主据此攒进度，别在这里直接写库——一秒几十次 */
    var onProgress: ((page: Int, frac: Float) -> Unit)? = null

    override fun onScrollReport(page: Int, frac: Float) {
        onProgress?.invoke(page, frac)
    }
}
