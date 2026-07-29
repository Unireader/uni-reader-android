package com.xvan.unireader.local

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import com.xvan.unireader.local.store.LibInkLayer
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3

/**
 * 模式1（本地开工作区）的画布：`PageCanvasView` + 「提交给本机 SQLite」。
 *
 * 与 `pad/PadView` 是同一个基类的两个子类，几何/手势/命中判定/渲染完全共用；差别只在覆写的那几个
 * 钩子——一边编成线格式帧发给 Mac，一边落进 `library.sqlite`。
 *
 * **「乐观预览 + 真源回推」在这里天然退化成「真源就在进程内」**（`ANDROID-STANDALONE-PLAN.md §5.1`）：
 * 基类照旧本地画半笔、照旧等 `setStrokes` 回推后清掉它，只不过回推方从 Mac 变成了「落库后自己读回来」。
 */
class LocalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PageCanvasView(context, attrs) {

    companion object {
        const val TAG = "UniReader/Canvas"
    }

    /** 落库目标。未设置（还没打开文档）时所有提交都丢弃，不崩 */
    var store: LibraryStore? = null
    var documentId = ""

    /** 当前作画图层（新笔迹的 layerId）。默认层由 `LibraryStore.ensureDefaultLayer` 保证存在 */
    var activeLayerId = LibInkLayer.DEFAULT_ID

    /** 视口滚动（16ms 节流后的）：宿主据此攒进度，别在这里直接写库——一秒几十次 */
    var onProgress: ((page: Int, frac: Float) -> Unit)? = null

    /** 笔迹增删后（已落库并回推）通知宿主刷新计数之类 */
    var onInkChanged: (() -> Unit)? = null

    override fun onScrollReport(page: Int, frac: Float) {
        onProgress?.invoke(page, frac)
    }

    // ---------- 落笔 ----------

    // 一笔的点集在收笔时一次 INSERT（同 Mac 的增量落库；别每帧写库）
    private val pending = ArrayList<Pt3>()
    private var pendingPage = 0
    private var pendingPen: Pen? = null
    private var pendingLine = false

    override fun onInkBegin(page: Int, pen: Pen, pt: Pt3, line: Boolean) {
        pending.clear()
        pending.add(pt)
        pendingPage = page
        pendingPen = pen
        pendingLine = line
    }

    override fun onInkMove(pts: List<Pt3>) {
        if (pendingLine) {
            // 尺子笔：基类每帧只发**最新终点**（替换语义，见其 penMove 的 lineStroke 分支），
            // 这里跟着替换而不是追加，否则会攒成一串移动中的终点、连成一条歪笔迹。
            while (pending.size > 1) pending.removeAt(pending.size - 1)
            pts.lastOrNull()?.let { pending.add(it) }
        } else {
            pending.addAll(pts)
        }
    }

    override fun onInkEnd() {
        val s = store
        val pen = pendingPen
        if (s == null || pen == null || pending.isEmpty()) {
            pending.clear(); pendingPen = null
            return
        }
        val pts = ArrayList(pending)
        pending.clear()
        pendingPen = null
        val id = try {
            s.insertStroke(documentId, pendingPage, pen, pts, activeLayerId)
        } catch (e: Exception) {
            Log.e(TAG, "落笔写库失败（这一笔会丢）", e)
            null
        }
        Log.i(TAG, "落笔 page=$pendingPage 点数=${pts.size} layer=$activeLayerId id=${id?.take(8)}")
        // **必须 post**：此刻还在基类的 endPen 里，activePen 仍为 true，同步回推的话
        // setStrokes 不会清掉本地那半笔（它有意不清正在写的笔），于是和落库那条重影。
        post { reloadStrokes() }
    }

    // ---------- 擦除 ----------

    /**
     * 基类已经在本地把命中的笔迹删掉/切段了（乐观预览，与 Mac `eraseNear` 两模式一一对应）。
     * 这里做**权威落库**：按 `note.id` 比对差异，一段不剩的删掉，段数/点数变了的「删原条 + 插新段」。
     * 整批放一个事务——半途崩掉会留下切了一半的笔迹（§9.3）。
     */
    override fun onEraseEnd() {
        val s = store ?: return
        val local = strokes.filter { it.id.isNotEmpty() }.groupBy { it.id }
        try {
            val before = s.strokes(documentId)
            var deleted = 0
            var inserted = 0
            s.transaction {
                for (old in before) {
                    val segs = local[old.id]
                    if (segs == null) {
                        s.deleteNote(old.id)
                        deleted++
                        continue
                    }
                    // 点数没变 = 这条没被擦到（擦除只会减少点，不会持平）
                    if (segs.size == 1 && segs[0].pts.size == old.pts.size) continue
                    s.deleteNote(old.id)
                    deleted++
                    for (seg in segs) {
                        s.insertStroke(documentId, seg.page.toInt(), seg.pen, seg.pts, seg.layerId)
                        inserted++
                    }
                }
            }
            if (deleted > 0 || inserted > 0) {
                Log.i(TAG, "擦除落库：删 $deleted 条，插 $inserted 段")
                post { reloadStrokes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "擦除写库失败，回退到库里的状态", e)
            post { reloadStrokes() }   // 本地已经擦掉了，库里没删成 → 以库为准重读，别让两边不一致
        }
    }

    // ---------- 真源回推 ----------

    /** 从库里重读笔迹并回推（隐藏图层的不画，但数据一条不动） */
    fun reloadStrokes() {
        val s = store ?: return
        val hidden = s.inkLayers(documentId).filter { !it.visible }.map { it.id }.toSet()
        val all = s.strokes(documentId)
        setStrokes(if (hidden.isEmpty()) all else all.filter { it.layerId !in hidden })
        onInkChanged?.invoke()
    }

    // 擦除的 move 帧不落库（一笔擦完再算差异）；probe/hover/框选提交留给 M4/M6
    override fun onErase(page: Int, pts: List<Pt2>) = Unit
}
