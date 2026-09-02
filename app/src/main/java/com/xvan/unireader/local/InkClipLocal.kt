package com.xvan.unireader.local

import com.xvan.unireader.shared.InkEdit
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke

/**
 * 模式1（离线独立）的**笔迹剪贴板**。进程级、纯内存：模式1 本机就是真源，没有 Mac 那份系统剪贴板
 * 可用；进程级是为了跨标签页/跨文档粘贴（模式1 的多标签共用一个进程）。
 *
 * 内容一律存**页内归一化**坐标 + 源页纵横比（页高/页宽），与 Mac `InkClipboard` 的 `space=page`
 * 同一个口径——将来若要与 Mac 互通剪贴板（走线格式或文件），换算规则已经对齐好了。
 */
object InkClipLocal {

    private var items: List<Stroke> = emptyList()

    /** 源页的页高/页宽。跨页粘贴时目标页纵横比不同，位置按它折算才不走形。 */
    private var aspect: Float = 1.4142f

    val isEmpty: Boolean get() = items.isEmpty()

    fun put(strokes: List<Stroke>, sourceAspect: Float) {
        // 存的是不可变对象的引用（`Stroke` 是 data class 且点集不可变），不必深拷
        items = ArrayList(strokes)
        aspect = if (sourceAspect > 0f) sourceAspect else 1.4142f
    }

    fun clear() {
        items = emptyList()
    }

    /**
     * 取一份摆到目标位置的**新**笔迹（每条换新 id：粘出来的是新条目，与源共用 id 会被落库对账
     * 当成「同一条改了内容」——同一篇文档里粘一次就把源覆盖了，同 Mac `InkClipboard.read` 的理由）。
     *
     * - [page]/[cx]/[cy]：目标页与落点（页内归一化，内容包围盒**中心**对齐到它）；
     * - [targetAspect]：目标页的页高/页宽，与源页不同则纵向按比例折算（横向天然同尺度：x 都是页宽的倍数）；
     * - [xMargin]：可写的页边余量（画板模式；0 = 不出本页）。位移**先夹再整体平移** = 刚性，
     *   撞上页边只是停住、不会被逐点摁扁（同 Mac 与本端框选移动的既有纪律）。
     * - [layerId]：落到目标文档的当前作画图层——源图层多半不存在于这一篇（跨文档粘贴）。
     */
    fun take(
        page: Int, cx: Float, cy: Float, targetAspect: Float, xMargin: Float, layerId: String,
    ): List<Stroke> {
        if (items.isEmpty()) return emptyList()
        val ta = if (targetAspect > 0f) targetAspect else 1.4142f
        // 纵向折算：归一化 y 相对的是**页高**，两页纵横比不同则同一个 y 对应的物理长度不同。
        val ky = aspect / ta
        val scaled = items.map { st -> st.copy(pts = st.pts.map { Pt3(it.x, it.y * ky, it.p) }) }
        val b = InkEdit.bounds(scaled) ?: return emptyList()
        val d = InkEdit.fitTranslation(
            cx - (b[0] + b[2]) / 2f, cy - (b[1] + b[3]) / 2f, b, xMargin,
        )
        return scaled.map { st ->
            st.copy(
                page = page.toLong(),
                pts = InkEdit.translated(st.pts, d[0], d[1], xMargin),
                id = "",            // 空 id = 还没落库；调用方落库时生成
                layerId = layerId,
                padId = "",
            )
        }
    }
}
