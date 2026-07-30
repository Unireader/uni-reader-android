package com.xvan.unireader.local

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import com.xvan.unireader.local.store.LibInkLayer
import com.xvan.unireader.local.store.LibraryStore
import com.xvan.unireader.local.store.NoteAnchor
import com.xvan.unireader.local.store.StoreQueue
import com.xvan.unireader.shared.InkEdit
import com.xvan.unireader.shared.PageCanvasView
import com.xvan.unireader.shared.Pen
import com.xvan.unireader.shared.Pt2
import com.xvan.unireader.shared.Pt3
import com.xvan.unireader.shared.Stroke
import com.xvan.unireader.shared.TextFill
import com.xvan.unireader.shared.TextNote
import kotlin.math.max
import kotlin.math.min

/**
 * 模式1（本地开工作区）的画布：`PageCanvasView` + 「提交给本机 SQLite」。
 *
 * 与 `pad/PadView` 是同一个基类的两个子类，几何/手势/命中判定/渲染完全共用；差别只在覆写的那几个
 * 钩子——一边编成线格式帧发给 Mac，一边落进 `library.sqlite`。
 *
 * **「乐观预览 + 真源回推」在这里天然退化成「真源就在进程内」**（`ANDROID-STANDALONE-PLAN.md §5.1`）：
 * 基类照旧本地画半笔、照旧等 `setStrokes` 回推后清掉它，只不过回推方从 Mac 变成了「落库后自己读回来」。
 *
 * **落库全在 [StoreQueue] 的独占线程上**（§9.5）：这个类里一行同步 I/O 都没有，写完接着排一次重读，
 * 结果回到主线程再 `apply*`。因此回推**必然晚于本次手势**——与模式2 等 Mac 广播回来是同一种时序，
 * 中间那段时间画面靠基类的乐观预览撑着。
 */
class LocalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PageCanvasView(context, attrs) {

    /** 落库目标（独占库的那条队列）。未设置（还没打开文档）时所有提交都丢弃，不崩 */
    var store: StoreQueue? = null
    var documentId = ""

    /** 当前作画图层（新笔迹的 layerId）。默认层由 `LibraryStore.ensureDefaultLayer` 保证存在 */
    var activeLayerId = LibInkLayer.DEFAULT_ID

    /** 视口滚动（16ms 节流后的）：宿主据此攒进度，别在这里直接写库——一秒几十次 */
    var onProgress: ((page: Int, frac: Float) -> Unit)? = null

    /** 笔迹增删后（已落库并回推）通知宿主刷新计数之类 */
    var onInkChanged: (() -> Unit)? = null

    /** 文字笔记模式下点页面：宿主开编辑器（isNew=false 是点中了已有笔记，同模式2） */
    var onNoteEditor: ((id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean) -> Unit)? = null

    /**
     * 长按呼盘的**本地判定**（M6）。模式2 里这段在 Mac 上跑，平板只画；模式1 自己判——
     * 画盘的代码仍是基类那一份，两模式看到的是同一个盘（见 [RadialController]）。
     */
    private val radialCtl = RadialController(this)

    override fun onScrollReport(page: Int, frac: Float) {
        onProgress?.invoke(page, frac)
    }

    override fun onOpenNoteEditor(
        id: String, page: Int, nx: Float, ny: Float, text: String, isNew: Boolean,
    ) {
        onNoteEditor?.invoke(id, page, nx, ny, text, isNew)
    }

    override fun onDetachedFromWindow() {
        radialCtl.cancel()   // 定时器挂在 View 上，界面没了得收掉
        super.onDetachedFromWindow()
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
        radialCtl.begin(page, pt.x, pt.y)   // 笔记模式下 ink 流兼作长按探针（同 Mac 的 beginLongPressWatch）
    }

    override fun onInkMove(pts: List<Pt3>) {
        val last = pts.lastOrNull()
        if (radialCtl.active) {
            // 盘开着：这些点是在选扇区、不是笔迹（基类此时也已停止本地作画）
            last?.let { radialCtl.move(it.x, it.y) }
            return
        }
        if (pendingLine) {
            // 尺子笔：基类每帧只发**最新终点**（替换语义，见其 penMove 的 lineStroke 分支），
            // 这里跟着替换而不是追加，否则会攒成一串移动中的终点、连成一条歪笔迹。
            while (pending.size > 1) pending.removeAt(pending.size - 1)
            pts.lastOrNull()?.let { pending.add(it) }
        } else {
            pending.addAll(pts)
        }
        last?.let { radialCtl.move(it.x, it.y) }   // 位移够大 = 用户在画，撤销长按候选
    }

    override fun onInkEnd() {
        if (radialCtl.end()) {
            // 这一笔被长按吃掉了（呼出了盘）：它是一次选择，不是笔迹，一个字都不落库
            pending.clear()
            pendingPen = null
            return
        }
        val q = store
        val pen = pendingPen
        if (q == null || pen == null || pending.isEmpty()) {
            pending.clear(); pendingPen = null
            return
        }
        val pts = ArrayList(pending)
        val page = pendingPage
        val layer = activeLayerId
        pending.clear()
        pendingPen = null
        // 写完顺手把真源读齐，同一个作业里做完——**写失败也照读**：那一笔于是自己从屏幕上消失，
        // 比留着一条只存在于内存里的笔迹假装存住了强。
        // 回推天然晚一拍（走队列 → 回主线程），此刻基类还在 endPen 里、activePen 仍为 true，
        // 那半笔照旧由活体层画着；等回推到达时 activePen 已经落下，setStrokes 会清掉它，不重影。
        q.submit(
            "落笔 page=$page 点数=${pts.size}",
            { s ->
                runCatching { s.insertStroke(documentId, page, pen, pts, layer) }
                    .onSuccess { Log.i(TAG, "落笔 page=$page 点数=${pts.size} layer=$layer id=${it?.take(8)}") }
                    .onFailure { Log.e(TAG, "落笔写库失败（这一笔会丢）", it) }
                inkSnapshot(s)
            },
            { applyStrokes(it.all, it.hidden) },
        )
    }

    // ---------- 擦除 ----------

    /**
     * 基类已经在本地把命中的笔迹删掉/切段了（乐观预览，与 Mac `eraseNear` 两模式一一对应）。
     * 这里把**擦完的期望状态**整份交给队列去对齐库（差异算法与「为什么头一段要沿用原 id」
     * 见 [LibraryStore.reconcileStrokes]）。
     *
     * `Stroke` 是不可变的、切段也是新建对象，所以这份快照可以安全地跨线程递给队列；
     * 但**必须在这里就 `groupBy` 定格**——基类的 `strokes` 数组随后还会被回推整体换掉。
     *
     * 写失败（事务回滚）也照样重读：本地已经擦掉了、库里没删成，以库为准把笔迹恢复出来，
     * 总比两边不一致强。
     */
    override fun onEraseEnd() {
        val q = store ?: return
        val local = strokes.filter { it.id.isNotEmpty() }.groupBy { it.id }
        q.submit(
            "擦除落库",
            { s ->
                val d = runCatching { s.reconcileStrokes(documentId, local) }
                    .onSuccess {
                        if (it.changed) {
                            Log.i(TAG, "擦除落库：删 ${it.deleted} 条，改 ${it.updated} 条，插 ${it.inserted} 段")
                        }
                    }
                    .onFailure { Log.e(TAG, "擦除写库失败，回退到库里的状态", it) }
                    .getOrNull()
                // 改了要回推真源；写失败也要（本地已经擦掉了，得从库里把它恢复出来）。
                // 只有「一条都没动」才跳过——擦到空处是常事，不值得为它整篇重读一遍笔迹。
                if (d != null && !d.changed) null else inkSnapshot(s)
            },
            { snap -> snap?.let { applyStrokes(it.all, it.hidden) } },
        )
    }

    // ---------- 真源回推 ----------

    /**
     * 队列线程一趟读齐的回推快照。**主线程只 apply，不碰库**——所以这两个类里放的是读好的结果，
     * 而不是 `LibraryStore` 的句柄（把句柄漏回主线程就等于把独占权还回去了，见 [StoreQueue]）。
     */
    private class InkSnapshot(val all: List<Stroke>, val hidden: Set<String>)
    private class NoteSnapshot(val notes: List<TextNote>, val fills: List<TextFill>)

    private fun inkSnapshot(s: LibraryStore) = InkSnapshot(
        s.strokes(documentId),
        s.inkLayers(documentId).filter { !it.visible }.map { it.id }.toSet(),
    )

    private fun noteSnapshot(s: LibraryStore) =
        NoteSnapshot(s.textNotes(documentId), s.textFills(documentId))

    /** 从库里重读笔迹并回推（隐藏图层的不画，但数据一条不动） */
    fun reloadStrokes() {
        val q = store ?: return
        q.submit("重读笔迹", { inkSnapshot(it) }, { applyStrokes(it.all, it.hidden) })
    }

    /**
     * 回推一批**已经读好**的笔迹。打开文档时走这条：那时 `store` 还在后台线程手里，
     * 笔迹和图层可见性是和页尺寸表一起在后台读出来的（§9.5——首屏不能有读库 I/O）。
     */
    fun applyStrokes(all: List<Stroke>, hiddenLayerIds: Set<String>) {
        val shown = if (hiddenLayerIds.isEmpty()) all else all.filter { it.layerId !in hiddenLayerIds }
        // 「笔迹少了几笔」这类问题不打点就只能靠猜：可见/总数分开记，一眼看出是没读到还是被图层滤掉了
        Log.i(TAG, "回推笔迹 可见=${shown.size}/${all.size} 隐藏图层=${hiddenLayerIds.size}")
        setStrokes(shown)
        onInkChanged?.invoke()
    }

    // ---------- 框选移动 ----------

    /**
     * 框选移动的**权威执行**（M4）：与 Mac `AppModel.applyLassoMove` 逐条对齐——
     * 只动锚定页、`dx/dy` 全零不动、退化成线/点的框（宽或高为 0）不动、命中口径是「任一点落框内」、
     * 平移走 [InkEdit.translated]（逐点 clamp）。
     *
     * 钩子只给 `box` 不给下标，是因为模式2 要把它发给 Mac 复判（不信任客户端的本地判定）。
     * 模式1 的真源就在进程内，所以照同一口径拿 `strokes` 重判一次——它已按可见图层过滤过
     * （见 `applyStrokes`），与 Mac 的 `vis.contains(layerId)` 同效：**隐藏图层的笔迹不会被移走**，
     * 否则用户会移动到自己看不见的东西。
     *
     * 文字注解那一半（M5 补齐）：命中口径同样照抄 Mac——**框与 anchor 相交**，或框**含住 anchor
     * 中心点**。点注解的 anchor 是零尺寸，相交那一支恒假（空矩形不与任何矩形相交），实际生效的是
     * 中心点那一支，正好等于基类本地预览用的「锚点落框」；选区注解才走相交。
     */
    override fun onLassoMoveCommit(page: Int, box: FloatArray, dx: Float, dy: Float) {
        val q = store ?: return
        if (dx == 0f && dy == 0f) return
        val x0 = min(box[0], box[2]); val x1 = max(box[0], box[2])
        val y0 = min(box[1], box[3]); val y1 = max(box[1], box[3])
        if (x1 - x0 <= 0f || y1 - y0 <= 0f) return
        // 笔迹这半边在主线程按内存里的 `strokes` 定格（`Stroke` 不可变，可以安全递给队列线程）；
        // 注解那半边的权威命中要用**库里的 anchor**（含宽高），所以放进作业里读——内存中的 `notes`
        // 只有落点一个坐标，拿它判的话选区注解永远只在左上角那一个点上才算命中。
        val hits = strokes.filter { st ->
            st.id.isNotEmpty() && st.page.toInt() == page &&
                st.pts.any { it.x in x0..x1 && it.y in y0..y1 }
        }
        q.submit(
            "框选移动落库",
            { s ->
                val noteHits = runCatching {
                    s.noteAnchors(documentId).filter { it.page == page && anchorHit(it, x0, y0, x1, y1) }
                }.onFailure { Log.e(TAG, "读注解 anchor 失败，本次只移笔迹", it) }
                    .getOrDefault(emptyList())
                if (hits.isNotEmpty() || noteHits.isNotEmpty()) {
                    runCatching {
                        // 整批一个事务：半途崩掉会留下「一半笔迹移了、一半没移」的画面（同擦除的理由，§9.3）
                        s.transaction {
                            for (h in hits) s.updateStrokePoints(h.id, InkEdit.translated(h.pts, dx, dy))
                            for (n in noteHits) s.translateTextNote(n.id, dx, dy)
                        }
                        Log.i(
                            TAG,
                            "框选移动落库：笔迹 ${hits.size} 条，注解 ${noteHits.size} 条 " +
                                "dx=${"%.4f".format(dx)} dy=${"%.4f".format(dy)}",
                        )
                    }.onFailure { Log.e(TAG, "框选移动写库失败，回退到库里的状态", it) }
                }
                // 一条都没命中也要回推：预览偏移得靠回推才归位（见下），写失败同理
                inkSnapshot(s) to if (noteHits.isEmpty()) null else noteSnapshot(s)
            },
            { (ink, notes) ->
                // 回推后基类会自己清掉预览偏移（setStrokes 里 lassoCommitted → clearLasso），
                // 与模式2 「等 Mac 广播回来才归位」同构。
                applyStrokes(ink.all, ink.hidden)
                notes?.let { applyNotes(it.notes, it.fills) }
            },
        )
    }

    /** 框 ∩ anchor ≠ ∅，或框含住 anchor 中心（Mac `rect.intersects(n.anchor) || rect.contains(mid)`） */
    private fun anchorHit(a: NoteAnchor, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val intersects = a.w > 0f && a.h > 0f &&
            a.x < x1 && a.x + a.w > x0 && a.y < y1 && a.y + a.h > y0
        val midIn = (a.x + a.w / 2f) in x0..x1 && (a.y + a.h / 2f) in y0..y1
        return intersects || midIn
    }

    // 擦除的 move 帧不落库（一笔擦完再算差异）
    override fun onErase(page: Int, pts: List<Pt2>) = Unit

    // ---------- 长按探针（擦除/翻页模式；笔记模式走 ink 那条流） ----------
    //
    // 与模式2 上行给 Mac 的是同一批钩子、同一个时机，只是判定方从 Mac 换成了进程内的
    // [RadialController]。擦除模式下呼盘前已经擦掉的那一段照旧落库（同 Mac：erase 消息先到先应用）。

    override fun onProbeBegin(page: Int, nx: Float, ny: Float) = radialCtl.begin(page, nx, ny)

    override fun onProbeMove(pts: List<Pt2>) {
        pts.lastOrNull()?.let { radialCtl.move(it.x, it.y) }
    }

    override fun onProbeEnd() {
        radialCtl.end()
    }

    // ---------- 文字注解（kind=0） ----------

    /**
     * 新建/编辑一条文字注解。语义在 [LibraryStore.upsertTextNote] 里与 Mac 逐条对齐：
     * 已有的**只改正文**（Mac 建的选区注解不会被退化成点注解），空文本等价删除。
     *
     * 基类已经乐观更新了本地 `notes`，这里落库后照旧整表重读回推——真源在库里，
     * 内存镜像与库分叉的话，「编辑完看着变了、重开又变回去」这种问题最难查。
     */
    override fun onNoteUpsert(id: String, page: Int, nx: Float, ny: Float, text: String) {
        val q = store ?: return
        q.submit(
            "文字注解落库 page=$page",
            { s ->
                runCatching { s.upsertTextNote(documentId, id, page, nx, ny, text) }
                    .onSuccess { Log.i(TAG, "文字注解落库 page=$page id=${id.take(8)} 字数=${text.length}") }
                    .onFailure { Log.e(TAG, "文字注解写库失败", it) }
                noteSnapshot(s)
            },
            { applyNotes(it.notes, it.fills) },
        )
    }

    override fun onNoteDelete(id: String, page: Int, nx: Float, ny: Float) {
        val q = store ?: return
        q.submit(
            "文字注解删除 page=$page",
            { s ->
                runCatching { s.deleteNote(id) }
                    .onSuccess { Log.i(TAG, "文字注解删除 page=$page id=${id.take(8)}") }
                    .onFailure { Log.e(TAG, "文字注解删除失败", it) }
                noteSnapshot(s)
            },
            { applyNotes(it.notes, it.fills) },
        )
    }

    /** 从库里重读文字注解与铺色并回推（同 [reloadStrokes]，只是换一张表的两种 kind） */
    fun reloadNotes() {
        val q = store ?: return
        q.submit("重读文字注解", { noteSnapshot(it) }, { applyNotes(it.notes, it.fills) })
    }

    /**
     * 回推一批**已经读好**的注解与铺色。打开文档时走这条：那时 `store` 还在后台线程手里（§9.5）。
     */
    fun applyNotes(list: List<TextNote>, fills: List<TextFill>) {
        Log.i(TAG, "回推文字注解 ${list.size} 条，铺色 ${fills.size} 片")
        setNotes(list)
        setTextFills(fills)
    }
}
