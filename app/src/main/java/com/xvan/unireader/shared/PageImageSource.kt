package com.xvan.unireader.shared

import android.graphics.Bitmap

/**
 * 「这一页的图从哪来」的注入口（`ANDROID-STANDALONE-PLAN.md §5.1`）。
 *
 * 两种模式只换这个实现：
 * - 模式2 输入板 → `pad/PageFetcher`：HTTP 从 Mac 取 `GET /page.png?i=N`；
 * - 模式1 独立版 → `local/PdfSource`：本机 Pdfium 渲染。
 *
 * 几何、滚动、缩放、笔迹绘制那一整套代码因此不必知道自己跑在哪种模式下。
 */
interface PageImageSource {

    /**
     * 请求某页的位图。**回调可能在后台线程**，调用方自己切回主线程。
     * 取不到（页越界/文件坏/已换文档）回调 null，别让调用方等一个永不到来的位图。
     *
     * @param widthPx 期望的位图宽度（像素）。实现可自行归档到档位以复用缓存；
     *   模式2 从 Mac 取的是整页 PNG，宽度由 Mac 定，此参数被忽略。
     */
    fun request(page: Int, widthPx: Int, cb: (Bitmap?) -> Unit)

    /** 换文档/退出：丢弃在途请求与缓存（在途回调此后一律按 null 处理） */
    fun clear()
}
