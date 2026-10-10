package com.xvan.unireader.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 草稿纸的**无限画布**：纸色 + 底纹 + 原点十字 + 画布坐标笔迹 + 平移/双指捏合 +
 * 软边界 + 回中/适应内容 + minimap + 笔落墨/橡皮。
 * 对应 Mac `ScratchPadView`/`ScratchCanvasLayers` 与 web `scratch.ts`（同一设备形态的答案）。
 *
 * 坐标系契约（handoff §1，弄错全盘皆错）：画布坐标 = **dp**（逻辑点），原点 = 纸创建那一刻的
 * 视口中心，x 右 y 下，**可负无界**。`MotionEvent.getX()` 是物理像素——触点进来先除 density，
 * 渲染乘回去（`toCanvas`/`onDraw` 里各自只有一处换算）。换算只有两条：
 * `screen = (canvas − origin) × zoom`、`canvas = origin + screen / zoom`。
 *
 * **只管几何/输入/渲染，不碰数据从哪来**（同 `PageCanvasView` 的分工）：数据进出全走回调——
 * 模式1 由 `local/ScratchController` 接 `LibraryStore`（收笔一次 INSERT + 擦除收尾 reconcile）；
 * 模式2 接线协议（流式钩子 [onStrokeBegin]/[onStrokeMove]/[onEraseAt]、框选提交 [onLassoEdit]）。
 *
 * 视口（origin/zoom）是本端私有的：**不落库、不上线**（三端各自独立缩放滚动），打开一律回中。
 * 夜间模式**不反色**：纸色是自己画的，夜间滤镜只在 `PageCanvasView` 的页图层上，天然排除。
 */
class ScratchCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /**
     * 当前工具快照（宿主从阅读画布现抄）。**每次落笔现取**——纸开着时改笔/改橡皮即时生效，
     * 不需要额外的同步动作。
     *
     * @param inkTool 笔落下写字（笔记模式）；都为 false = 平移（翻页/文字笔记模式在纸上
     *   退化为平移——画布没有「页」也没有文字注解，同 web 的退化分支）
     * @param eraseTool 笔落下擦除
     * @param eraserSize 页宽归一化的橡皮半径；画布半径 = eraserSize × [ScratchGeom.ERASER_REF_W]
     * @param lassoTool 笔落下框选（圈选 / 拖框内移动 / 拖手柄缩放，只作用于笔迹，见「框选」一节）
     */
    class Tools(
        val inkTool: Boolean,
        val eraseTool: Boolean,
        val pen: Pen,
        val eraserSize: Float,
        val eraserMode: Int,     // 0=整笔 1=局部
        val eraserRing: Boolean,
        val rulerOn: Boolean,
        val lassoTool: Boolean = false,
        val relativeInk: Boolean = false,   // 相对粗细：落笔时按画布当前缩放折算笔宽（同 PageCanvasView.relativeInkWidth）
    )

    /** 宿主给：当前工具快照（返回 null = 还没接上，笔落下只平移不崩） */
    var tools: (() -> Tools?)? = null

    // ---- 提交口（回调 = 两模式的唯一差别；模式1 落库 / 模式2 上线） ----

    /** 一笔写完（整笔，画布坐标 + 压感）。模式1 的一次 INSERT 用这条 */
    var onStrokeEnd: ((pen: Pen, pts: List<Pt3>) -> Unit)? = null

    /** 流式钩子（模式2 上线用）：begin/move 逐批给点；模式1 不用，留空即可 */
    var onStrokeBegin: ((pen: Pen, pt: Pt3, line: Boolean) -> Unit)? = null
    var onStrokeMove: ((pts: List<Pt3>) -> Unit)? = null

    /** 擦除途经点（画布坐标）。本地乐观擦除画布自己做，这个钩子是给模式2 上线的 */
    var onEraseAt: ((x: Float, y: Float) -> Unit)? = null

    /** 一次擦除手势收尾：期望状态（带 id 的全量快照，切段沿用原 id）。模式1 据此 reconcile 落库 */
    var onEraseFinish: ((strokes: List<Stroke>) -> Unit)? = null

    /** 视口变了（平移/缩放/回中/适应/minimap 跳转）——宿主刷新缩放读数之类 */
    var onViewportChanged: (() -> Unit)? = null

    /**
     * 一次框选移动 / 缩放已经在本地生效（画布坐标）。[poly] 是**变换前**的选区多边形（真源拿它复判命中），
     * [scale] = `[ax, ay, sx, sy]`（缩放）或 null（移动，用 [dx]/[dy]）；[before]/[after] 是被改的那几条的新旧版本
     * （id 不变）。模式1 据此落库，模式2 发 `lassoMove` / `lassoScale`（`../PROTOCOL.md §4.4`）。
     */
    class LassoEdit(
        val poly: FloatArray,
        val dx: Float,
        val dy: Float,
        val scale: FloatArray?,
        val before: List<Stroke>,
        val after: List<Stroke>,
    )

    var onLassoEdit: ((LassoEdit) -> Unit)? = null

    /** 有没有选中集变了（顶栏据此灰掉 / 亮起剪切与复制） */
    var onLassoSelChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val ink = InkRenderer(density)
    private val overlays = PadOverlays(density)

    // ---- 纸样与笔迹（宿主注入） ----
    private var bgColor = Color.WHITE
    private var inkColor = Color.BLACK   // 底纹/提示的墨色：由纸色明度推，不跟系统深浅外观走（§4.2 🔴）
    private var pattern = "dots"
    private val strokes = ArrayList<Stroke>()

    /**
     * 笔迹的分块位图缓存（拖动时只贴图，见 [ScratchTiles]）。🔴 [strokes] 的每一处增删都要经
     * [strokesChanged] 告诉它（以及内容包围盒 / minimap 骨架那两份缓存），漏一处就是那一处改了看不见。
     */
    private val tiles = ScratchTiles(density, strokes) { postInvalidateOnAnimation() }

    /** [strokes] 已经改好：通知块缓存 + 作废包围盒 / minimap 骨架 */
    private fun strokesChanged(removed: List<Stroke>, added: List<Stroke>) {
        tiles.changed(removed, added)
        strokeBoundsOk = false
        miniSkelOk = false
    }

    // 笔迹全集的包围盒（软边界每次平移都要，逐点扫一遍全集太贵）
    private var strokeBoundsCache: FloatArray? = null
    private var strokeBoundsOk = false

    private fun strokeBounds(): FloatArray? {
        if (!strokeBoundsOk) {
            strokeBoundsCache = ScratchGeom.contentBounds(strokes)
            strokeBoundsOk = true
        }
        return strokeBoundsCache
    }

    // ---- 视口（origin = 视口左上角对应的画布坐标 dp） ----
    private var ox = 0f
    private var oy = 0f
    private var zoom = 1f
    private var placed = false   // 已按真实尺寸回过中（尺寸没到之前 recenter 无意义）

    var minimapOn = true
        set(v) {
            field = v
            invalidate()
        }

    /**
     * 双指滚动模式（防误触）：单指划动不平移这张纸，滚动与缩放一律双指。
     * 宿主把页内那块画布的同名开关抄过来（两处必须一致：用户开的是一个模式，不是两个）。
     * minimap 上的点/拖不受影响——那是明确指着一个小窗口按下去的。
     */
    var twoFingerScroll = false

    /**
     * 锁定水平滚动（同页内 `PageCanvasView.hLocked`，宿主把那边的开关抄过来）：单指 / 双指 / 笔拖平移与
     * 松手惯性都不带横向分量；缩放时以两指中点为锚的横向重锚照旧（不然放大时画面会横向乱跳）。
     */
    var hLocked = false

    /** 兜底工具（宿主还没接上时笔落下只平移，不崩也不乱画） */
    private val fallbackTools = Tools(
        inkTool = false, eraseTool = false, pen = PageCanvasView.FALLBACK_PENS[0],
        eraserSize = PageCanvasView.ERASE_R_FALLBACK, eraserMode = 1, eraserRing = true, rulerOn = false,
    )

    private fun curTools() = tools?.invoke() ?: fallbackTools

    // ---------- 宿主注入 ----------

    /** 纸样：bg 是自由 CSS rgba 串（解析失败兜纯白），pattern 未知取值兜 dots（同 Mac） */
    fun setPaper(bgCss: String, pattern: String) {
        val c = ScratchGeom.parseCssRgba(bgCss)
        bgColor = if (c != null) Color.argb(c[3], c[0], c[1], c[2]) else Color.WHITE
        inkColor = if (c == null || ScratchGeom.inkIsDark(c[0], c[1], c[2])) Color.BLACK else Color.WHITE
        this.pattern = if (pattern == "plain" || pattern == "grid") pattern else "dots"
        invalidate()
    }

    // ---------- 页面底图（v10；契约见 ../PROTOCOL.md §4.4 与 [ScratchGeom.PAGE_REF_W]） ----------
    //
    // 「这张纸挂在哪一页」以前只有页面上那枚图钉知道；开了这个开关，那一页就垫在纸下面当参照。
    // 位置/大小由宿主按契约算好（[setPageUnder] 收画布坐标矩形），**图从哪来**照旧走
    // [PageImageSource]（模式1 = 本机 Pdfium / 模式2 = HTTP 找 Mac 要），本类不知道自己在哪种模式下。
    // 层序：纸色 → 底纹 → **页图** → 笔迹（页图只是参照物，墨永远在最上面）。
    // 夜间不反色：夜间滤镜只挂在 `PageCanvasView` 的页图层上，这层天然排除（同纸色/底纹）。

    /** 宿主注入的页图来源（两模式各自的实现，同 `PageCanvasView`） */
    var pageSource: PageImageSource? = null

    private var pageUnderIndex = -1
    private var pageUnderRect: FloatArray? = null
    private var pageUnderBmp: Bitmap? = null
    private var pageReqW = 0        // 已经按多宽请求过（跨档才重取，否则捏合每帧都在发请求）
    private var pageFails = 0       // 这一页连续取不到几次了（见 ensurePageBitmap 的有限重试）

    /**
     * 设置/撤掉页面底图。[rect] = 画布坐标 `[x,y,w,h]`（宿主用 [ScratchGeom.pageRect] 算），
     * null 或 [page] < 0 = 不垫页。换页/换纸都从这里进，位图按需异步取。
     */
    fun setPageUnder(page: Int, rect: FloatArray?) {
        val changed = page != pageUnderIndex
        pageUnderIndex = if (rect == null) -1 else page
        pageUnderRect = if (pageUnderIndex < 0) null else rect
        if (changed || pageUnderRect == null) {
            pageUnderBmp = null
            pageReqW = 0
            pageFails = 0
        }
        clampViewport()
        invalidate()
    }

    /**
     * 页图像素宽：按当前缩放折进 [PageWidths] 的**共用档位**（不另立一套——阅读画布刚看过的
     * 那一页多半就在同一档的缓存里，另立档位等于每张纸都重渲/重下一份大图）。
     */
    private fun pageStepWidthPx(): Int = PageWidths.snap((ScratchGeom.PAGE_REF_W * zoom * density).toInt())

    /**
     * 按需取页图（onDraw 里发现该换档就发一次请求；回调可能在后台线程，post 回主线程再上屏）。
     * 取不到（模式2 掉包/模式1 页坏）**有限次重试**：一直不重试的话，纸上会永远糊着一块占位白；
     * 无限重试则等于每帧一次网络请求。
     */
    private fun ensurePageBitmap() {
        val idx = pageUnderIndex
        val src = pageSource
        if (idx < 0 || src == null) return
        val w = pageStepWidthPx()
        if (w == pageReqW) return   // 这一档已经取到/正在途中
        pageReqW = w
        src.request(idx, w) { bmp ->
            post {
                // 排队期间可能已经换纸/换页/关了底图——判据一律现读
                if (pageUnderIndex != idx) return@post
                if (bmp != null) {
                    pageUnderBmp = bmp
                    pageFails = 0
                    invalidate()
                } else if (pageFails < MAX_PAGE_RETRY) {
                    pageFails++
                    pageReqW = 0        // 放行下一帧再试一次
                }
            }
        }
    }

    // ---------- 画板笔记的图片（v16；契约见 ../PROTOCOL.md §4.8 / ../BOARD-NOTE-PLAN.md §2.3） ----------
    //
    // 只有画板笔记有图（草稿纸没有）。位置由宿主给画布坐标矩形（左上原点），**图从哪来**走 [picSource]
    // （模式1 = 本机 `Images/<sha>.<ext>`，模式2 = HTTP 找 Mac 要 `/image?h=`），本类不知道自己在哪种模式下。
    // 层序：纸色 → 底纹 →（页面底图）→ **图片** → 笔迹（笔迹永远能写在图上）。叠放序 = 列表顺序。
    // 本轮两端都只显示不编辑：图片不参与擦除 / 落笔命中。

    /** 画板上的一张图。[key] = 取图的键（图片内容的 sha256），同一张图出现几次只取一次 */
    class BoardPic(val id: String, val key: String, val x: Float, val y: Float, val w: Float, val h: Float)

    /** 图片来源（两模式各自实现）。回调可以在后台线程，取不到给 null */
    fun interface BoardPicSource {
        fun load(key: String, cb: (Bitmap?) -> Unit)
    }

    var picSource: BoardPicSource? = null

    private var pics = listOf<BoardPic>()
    private val picBmps = HashMap<String, Bitmap>()
    private val picPending = HashSet<String>()
    private val picFails = HashMap<String, Int>()

    /**
     * 换一整份图片列表（全量镜像）。已经取到的位图按 key 留着——同一张图挪了位置不必重取；
     * 列表里不再出现的 key 放掉。**[openSession] 不清图片**：图片与笔迹是两条消息，先后没有保证，
     * 由宿主在离开画板时显式传空表。
     */
    fun setPics(list: List<BoardPic>) {
        pics = list
        val keys = list.map { it.key }.toHashSet()
        picBmps.keys.retainAll(keys)
        picFails.keys.retainAll(keys)
        clampViewport()
        invalidate()
    }

    /** 按需取图：只取可见的、没在途的、失败没超过上限的（无限重试 = 每帧一次请求） */
    private fun ensurePic(key: String) {
        val src = picSource ?: return
        if (picBmps.containsKey(key) || key in picPending) return
        if ((picFails[key] ?: 0) >= MAX_PAGE_RETRY) return
        picPending.add(key)
        src.load(key) { bmp ->
            post {
                picPending.remove(key)
                if (pics.none { it.key == key }) return@post   // 排队期间这张图已经不在了
                if (bmp != null) {
                    picBmps[key] = bmp
                    picFails.remove(key)
                    invalidate()
                } else {
                    picFails[key] = (picFails[key] ?: 0) + 1
                    invalidate()   // 下一帧再试（有上限）
                }
            }
        }
    }

    // ---------- 分页画板（v17；契约见 [BoardPaging] / ../BOARD-NOTE-PLAN.md §9） ----------
    //
    // 有页 = 分页模式：页竖排、水平居中于画布 x = 0（第 i 页 = (-W/2, i×(H+24), W, H)），每页纸色底 + 背景模板，
    // 页外是界面底色；视口竖向滚动、横向夹在页宽内，打开时按页宽适配停在第一页顶；到底后继续上拉一段 → 加一页。
    // 笔迹 / 图片照旧是**画布坐标**（页内坐标只在落库时出现，由模式1 的宿主换算；模式2 Mac 已换算好）。
    // 没有页 = 原来的无限画布，下面这些一概不生效。

    private var pageLayout: BoardPaging.Layout? = null
    private var pageTemplates = IntArray(0)
    private val shapeCache = HashMap<String, BoardPaging.Shape>()
    private var pagePlaced = false   // 这一篇（分页）已经按页宽适配过了

    /** 是不是分页画板（有页） */
    val paged: Boolean get() = (pageLayout?.count ?: 0) > 0

    /** 页数（不是分页 = 0） */
    val pageCount: Int get() = pageLayout?.count ?: 0

    /** 到底后继续上拉超过一段距离（一次手势只触发一次）——宿主在末尾加一页 */
    var onPullAddPage: (() -> Unit)? = null

    /** 上拉过程中底部那行提示（宿主给本地化文案；null = 不提示） */
    var pullHintText: String? = null

    private var pullOver = 0f     // 到底之后又往上拉了多少（dp）
    private var pullFired = false // 这次手势已经加过一页了

    /**
     * 换一份页（全量）：[templates] 每页一个背景模板（[BoardPaging] 的 T_*），空 = 不是分页画板。
     * [replace] = 换了一篇画板（模式2 由 Mac 切过去）：按新一篇的页宽重新适配、停在第一页顶。
     * 分页 ↔ 无限之间切换也重新摆放；同一篇里加页 / 改背景 / 改尺寸只夹一下视口（同 Mac）。
     * 例外：这一篇刚按存过的位置复位、用户还没动过（[restoreHold]）——页晚于位置到达（模式2 两条消息
     * 先后没有保证），不摆回首页顶，只按新到的页重夹那份位置。
     */
    fun setPages(w: Float, h: Float, templates: IntArray, replace: Boolean = false) {
        val wasPaged = paged
        pageLayout = if (templates.isEmpty() || w <= 1f || h <= 1f) null else BoardPaging.Layout(w, h, templates.size)
        pageTemplates = templates.copyOf()
        if ((replace || wasPaged != paged) && restoreHold == null) pagePlaced = false
        if (width > 0 && height > 0) {
            when {
                paged && !pagePlaced -> placePageTop(0)
                !paged && wasPaged -> recenterView()
            }
        }
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    /** 视口中心所在的页（0 起；工具条页码、「改当前页背景」都用它） */
    fun currentPageIndex(): Int {
        val l = pageLayout ?: return 0
        return l.indexForY(oy + viewHdp() / (2 * zoom))
    }

    /** 分页：第 [i] 页页顶、按页宽适配（缩放上限 2，同 Mac `pageTop`）——宿主的「跳页」，算用户动了视口 */
    fun placeAtPageTop(i: Int) {
        takeOverViewport()
        placePageTop(i)
    }

    // ---------- 分页画板同步滚动（模式2 与 Mac，`../PROTOCOL.md §4.8`「分页画板同步滚动」） ----------

    /**
     * 同步位置：锚线（页顶该摆的那条线，视图顶下 [PAGED_TOP]，与 Mac 的 `topInset + 56` 同义）落在画布的 y，
     * 折成「第几页 + 在这一页（页高 + 页缝）里的比例」——页与页之间连续，顶上留白时比例为负。不是分页 = null。
     */
    fun pageAnchor(): Pair<Int, Float>? {
        val l = pageLayout ?: return null
        if (width <= 0 || height <= 0 || zoom <= 0f) return null
        val p = (oy + PAGED_TOP / zoom) / l.stride
        val page = floor(p).toInt().coerceIn(0, max(0, l.count - 1))
        return page to (p - page)
    }

    /** 正在按对端的位置挪视口（宿主据此不回发，防回环） */
    var followApplying = false
        private set

    /** 尺寸还没到时收到的对端位置（见 [followPageAnchor]） */
    private var pendingFollow: Pair<Int, Float>? = null

    /**
     * 跟到对端的位置（只动竖向，缩放与横向不变）。用户正碰着画布（手指 / 笔）或惯性还在滑时不跟，返回 false
     * （同 PDF「书写中忽略 viewport」）。不算用户动过视口（[viewportTouched] 不变）。
     */
    fun followPageAnchor(page: Int, frac: Float): Boolean {
        val l = pageLayout ?: return false
        if (width <= 0 || height <= 0) {
            // 第一次显示画板时常见：画布刚从 GONE 变可见、还没布局。记下，尺寸一到再跟（onSizeChanged）
            pendingFollow = page to frac
            return true
        }
        if (penActive || touches.isNotEmpty() || momentumOn) return false
        if (!pagePlaced) placePageTop(0)   // 还没按页宽适配过：先适配，缩放才对
        restoreHold = null
        oy = (page + frac) * l.stride - PAGED_TOP / zoom
        placed = true
        pagePlaced = true
        clampViewport()
        invalidate()
        followApplying = true
        try {
            onViewportChanged?.invoke()
        } finally {
            followApplying = false
        }
        return true
    }

    /** [placeAtPageTop] 的本体；打开时的默认摆放也走这里（那不算用户动过） */
    private fun placePageTop(i: Int) {
        val l = pageLayout ?: return
        if (width <= 0 || height <= 0) return
        restoreHold = null
        val vw = viewWdp()
        val z = ((vw - 48f) / l.width).coerceIn(ScratchGeom.MIN_ZOOM, 2f)
        val k = i.coerceIn(0, max(0, l.count - 1))
        android.util.Log.i("UniReader/BoardSync", "画布摆到第${k}页页顶（${Throwable().stackTrace.drop(1).take(3).joinToString(" ← ") { it.methodName }}）")
        zoom = z
        ox = 0f - vw / (2 * z)
        oy = l.originY(k) - PAGED_TOP / z
        placed = true
        pagePlaced = true
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    /**
     * 分页视口夹取（同 Mac `clamped`）：页比视口窄时水平居中，否则夹在页宽 ± 边距内；
     * 竖向上面让出工具条那段、下面多留一段（上拉加页的提示就在那里）。
     */
    private fun clampPaged(b: FloatArray) {
        val z = max(zoom, 0.0001f)
        val visW = viewWdp() / z
        val visH = viewHdp() / z
        val margin = PAGED_MARGIN / z
        val top = PAGED_TOP / z
        if (b[2] + 2 * margin <= visW) {
            ox = b[0] + b[2] / 2 - visW / 2
        } else {
            ox = ox.coerceIn(b[0] - margin, b[0] + b[2] + margin - visW)
        }
        val minY = b[1] - top
        val maxY = max(minY, b[1] + b[3] + margin * 2 - visH)
        oy = oy.coerceIn(minY, maxY)
    }

    /**
     * 平移了一下（[wantY] = 没夹之前想去的 oy，[beforeY] = 平移前的 oy）：到底后继续上拉就累计，
     * 往回推就退账；超过 [PULL_THRESHOLD] 加一页（一次手势一页）。
     */
    private fun notePull(wantY: Float, beforeY: Float) {
        if (!paged) return
        val over = (wantY - oy) * zoom
        if (over > 0.01f) {
            pullOver += over
        } else if (wantY < beforeY) {
            pullOver = max(0f, pullOver - (beforeY - wantY) * zoom)
        }
        if (!pullFired && pullOver >= PULL_THRESHOLD) {
            pullFired = true
            onPullAddPage?.invoke()
        }
        invalidate()
    }

    /** 手势结束：上拉的账清零（下一次手势重新计） */
    private fun endPull() {
        if (pullOver == 0f && !pullFired) return
        pullOver = 0f
        pullFired = false
        invalidate()
    }

    /** 当前全部笔迹（只读；宿主判断「某页上有没有内容」用） */
    fun strokeList(): List<Stroke> = strokes

    /** 当前全部图片（只读） */
    fun picList(): List<BoardPic> = pics

    /** 空白纸的引导标题（宿主可换：画板笔记说「空白画板」）；null = 草稿纸的默认说法 */
    var emptyHintTitle: String? = null

    /**
     * 真源回推：整表替换（正在写的这一笔不受影响——它还没进 [strokes]）。
     *
     * @param keep 真源里**还没有**的乐观笔迹（`ackRel` 还没追上），原样接在末尾——不然「活体层已清、
     *   回推还没到」之间会露出空窗，肉眼就是上一笔闪一下。判据在 `PadScratch.applyStrokes`。
     */
    fun setStrokes(list: List<Stroke>, keep: List<Stroke> = emptyList()) {
        // 框选拖动中被整表换掉：拿出来的那几条已经不在新表里的位置上了，这次拖动作废（选中集下面按多边形重判）
        if (lassoDrag != null) abortLassoDrag()
        val next = ArrayList<Stroke>(list.size + keep.size)
        next.addAll(list)
        next.addAll(keep)
        // 与现有的比出增删（按内容：模式2 每次回推都是新解码的对象）。整篇没变 = 块一张都不用重画
        val removed: List<Stroke>
        val added: List<Stroke>
        if (strokes.isEmpty() || next.isEmpty()) {
            removed = ArrayList(strokes)
            added = next
        } else {
            val before = HashSet<Stroke>(strokes)
            val after = HashSet<Stroke>(next)
            removed = strokes.filter { it !in after }
            added = next.filter { it !in before }
        }
        if (android.os.SystemClock.uptimeMillis() - lastCommitAt < 3000) {
            val back = if (lastCommitBefore.isEmpty()) 0 else next.count { it in lastCommitBefore }
            lassoLog("提交后 ${android.os.SystemClock.uptimeMillis() - lastCommitAt}ms 收到整表：" +
                "${next.size} 条，删 ${removed.size} 加 ${added.size}，其中提交前的旧位置 $back 条")
        }
        strokes.clear()
        strokes.addAll(next)
        strokesChanged(removed, added)
        refreshLassoSelection()
        clampViewport()
        invalidate()
    }

    /**
     * 真源回推：**追加**这几条（模式2 的 `scratchStrokesAppend`，同页内 `PageCanvasView.appendStrokes`）。
     * 追加本来就知道加了哪几条，不用像 [setStrokes] 那样拿整表比增删。
     *
     * @param settled 同时退场的乐观笔迹（`ackRel` 已追上，判据在 `PadScratch.appendStrokes`），按对象同一性摘掉；
     *   追加与退场在同一次操作里，屏幕上恰好一条
     */
    fun appendStrokes(list: List<Stroke>, settled: List<Stroke> = emptyList()) {
        // 框选拖动中摘乐观笔：同 setStrokes，拿出来的那几条位置作废，这次拖动作废
        if (settled.isNotEmpty() && lassoDrag != null) abortLassoDrag()
        if (settled.isNotEmpty()) strokes.removeAll { s -> settled.any { it === s } }
        strokes.addAll(list)
        strokesChanged(settled, list)   // 🔴 每一处增删都要经它，否则分块位图不重画、改了看不见
        refreshLassoSelection()
        clampViewport()
        invalidate()
    }

    /** 按本地 id 找一条已落画布的笔迹（乐观笔迹认领用；找不到 = 已被本地擦除/切段） */
    fun strokeById(id: String): Stroke? = strokes.firstOrNull { it.id == id }

    /** 收笔后宿主生成的带 id 笔迹并进画布（乐观落地，同 `LocalCanvasView.onInkEnd` 的套路） */
    fun addCommitted(s: Stroke) {
        strokes.add(s)
        strokesChanged(emptyList(), listOf(s))
        invalidate()
    }

    /** 落库失败撤销乐观落地（宁可这一笔消失，也不假装存住了） */
    fun removeStroke(id: String) {
        val gone = strokes.filter { it.id == id }
        if (gone.isEmpty()) return
        strokes.removeAll { it.id == id }
        strokesChanged(gone, emptyList())
        refreshLassoSelection()
        invalidate()
    }

    /**
     * 当前视口（原点 x/y + 缩放，画布坐标）：画板笔记「记住上次滚动位置」存这个。
     * 复位过、用户还没动过时给复位的那份原值（此刻屏幕上的可能是内容没到齐时夹过的，见 [restoreHold]）。
     */
    fun currentViewport(): FloatArray = restoreHold?.copyOf() ?: floatArrayOf(ox, oy, zoom)

    /** 已存过的视口，尺寸还没到之前先记着（见 [openSession]/[onSizeChanged]） */
    private var pendingRestore: FloatArray? = null

    /**
     * 复位到的原始视口：用户动视口之前，每次夹取都从它重新算（[clampViewport]）。
     * 复位那一刻页和笔迹往往还没到（模式2 的 `boardPages` / `scratchStrokes` 与位置是几条消息、先后没有保证；
     * 模式1 的笔迹是复位之后才在队列上读进来的），按空纸夹会把存过的位置拽回原点附近 / 首页顶，
     * 内容到齐后也回不去了。手 / 笔一落下（或宿主调回中、适应内容、跳页）就放手，此后照常夹。
     */
    private var restoreHold: FloatArray? = null

    /**
     * 这一篇打开以来用户动过视口没有（手 / 笔落下、回中、适应内容、跳页；[openSession] 清零）。
     * 模式2 只在动过之后才把位置回传给 Mac——只是打开看一眼不该改库里存的位置；晚到的位置也只在没动过时才复位。
     */
    var viewportTouched = false
        private set

    /** 用户接手视口：放掉复位压着的那份，记一笔「动过」 */
    private fun takeOverViewport() {
        restoreHold = null
        viewportTouched = true
    }

    /**
     * 打开一张纸：丢掉上一张的全部状态（含活体半笔与几何缓存）。
     * [restore] = 存过的视口（原点 x/y + 缩放，画布坐标；null 或 zoom<=0 = 没存过）——
     * 画板笔记「记住上次滚动位置」用，草稿纸仍传 null（打开一律回中/首页顶）。
     */
    fun openSession(restore: FloatArray? = null) {
        clearLasso()
        strokes.clear()
        tiles.reset()
        strokeBoundsOk = false
        miniSkelOk = false
        clearLive()
        ink.clearCache()
        placed = false
        pagePlaced = false
        endPull()
        restoreHold = null
        viewportTouched = false
        pendingFollow = null
        pendingRestore = restore?.takeIf { it.size == 3 && it[2] > 0f }
        applyPendingRestoreOrDefault()
    }

    /**
     * 存过的位置比打开晚到（模式2：Mac 的 `boardViewport` 跟在 `boards` 后面，而纸是 `scratchpads` 打开的）：
     * 这一篇打开以来用户还没动过视口才复位，动过了以用户为准。zoom<=0 = 没存过，不动。
     */
    fun restoreViewport(v: FloatArray) {
        if (viewportTouched || v.size != 3 || v[2] <= 0f) return
        pendingRestore = v.copyOf()
        applyPendingRestoreOrDefault()
    }

    /** [pendingRestore] 存在就直接摆过去（分页/无限画布同一套画布坐标，不必分叉）；否则走老规矩 */
    private fun applyPendingRestoreOrDefault() {
        val r = pendingRestore
        if (r == null) {
            if (paged) placePageTop(0) else recenterView()
            return
        }
        if (width <= 0 || height <= 0) return   // 尺寸还没到，onSizeChanged 里再试
        pendingRestore = null
        restoreHold = r
        placed = true; pagePlaced = true
        clampViewport()   // 从 restoreHold 取值再夹
        invalidate()
        onViewportChanged?.invoke()
    }

    /**
     * 回中 = 画布原点回视口正中 + zoom 复位 1（「打开后从该处显示」的落点）。
     * 分页画板：回到当前页页顶（按页宽适配，同 Mac）。
     */
    fun recenter() {
        takeOverViewport()
        recenterView()
    }

    /** [recenter] 的本体；打开时的默认摆放也走这里（那不算用户动过） */
    private fun recenterView() {
        if (paged) {
            placePageTop(currentPageIndex())
            return
        }
        if (width > 0 && height > 0) {
            restoreHold = null
            zoom = 1f
            val c = ScratchGeom.centeredOrigin(viewWdp(), viewHdp())
            ox = c[0]; oy = c[1]
            placed = true
            invalidate()
        }
        onViewportChanged?.invoke()
    }

    /** 适应内容 = 全部笔迹包围盒（留边距）装进视口；空纸退化为回中 */
    fun fitContent() {
        takeOverViewport()
        if (paged) {   // 分页：适配页宽、停在当前页（同 Mac）
            placePageTop(currentPageIndex())
            return
        }
        val f = ScratchGeom.fit(contentBounds(), viewWdp(), viewHdp())
        if (f == null) {
            recenterView()
            return
        }
        ox = f[0]; oy = f[1]; zoom = f[2]
        placed = true
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    fun zoomPct(): Int = (zoom * 100).roundToInt()

    private fun viewWdp() = width / density
    private fun viewHdp() = height / density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tiles.release()   // 停后台出图线程；回到窗口后按需重开
        fingerGate.detach()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!placed || (paged && !pagePlaced)) {
            // 首次拿到真实尺寸才回中 / 按页宽适配 / 复位存过的视口（之前算的都是假的）
            if (w > 0 && h > 0) {
                applyPendingRestoreOrDefault()
                pendingFollow?.let { (p, f) -> pendingFollow = null; followPageAnchor(p, f) }
            }
            return
        }
        // 尺寸变化（旋转/分屏）：保持视口中心对应的画布点不动（同 Mac 的 onChange(of: geo.size)）
        if (oldw > 0 && oldh > 0) {
            ox += (oldw - w) / density / (2 * zoom)
            oy += (oldh - h) / density / (2 * zoom)
            clampViewport()
        }
        invalidate()
    }

    // ---------- 视口维护 ----------

    /** 内容包围盒（软边界 / 适应内容 / minimap）= 笔迹 ∪ 页面底图 ∪ 图片（同 Mac 画板的口径） */
    private fun contentBounds(): FloatArray? {
        // 分页画板：全部页也算内容（同 Mac contentBounds ∪ boardLayout.bounds）——页是「纸」，不是空白
        val under = pageUnderRect ?: pageLayout?.bounds()
        val base = strokeBounds()
        val extra = if (livePts.isEmpty() && under == null) null
            else ScratchGeom.contentBounds(emptyList(), livePts.ifEmpty { null }, under)
        if (pics.isEmpty() && extra == null) return base
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (b in arrayOf(base, extra)) {
            if (b == null) continue
            x0 = min(x0, b[0]); y0 = min(y0, b[1])
            x1 = max(x1, b[0] + b[2]); y1 = max(y1, b[1] + b[3])
        }
        for (p in pics) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x + p.w); y1 = max(y1, p.y + p.h)
        }
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private fun clampViewport() {
        // 复位过、用户还没动：每次都从原值重新夹（页 / 笔迹陆续到了，夹的范围才放宽到原值够得着）
        restoreHold?.let { ox = it[0]; oy = it[1]; zoom = it[2] }
        val pb = pageLayout?.bounds()
        if (pb != null) {
            if (width > 0 && height > 0) clampPaged(pb)
            return
        }
        val c = ScratchGeom.clampOrigin(ox, oy, zoom, contentBounds(), viewWdp(), viewHdp())
        ox = c[0]; oy = c[1]
    }

    /** 触点（物理 px）→ 画布坐标（dp）：先除 density 再除 zoom（handoff §1 的 dp 坑就在这一步） */
    private val tmpC = FloatArray(2)
    private fun toCanvas(x: Float, y: Float): FloatArray {
        tmpC[0] = ox + x / density / zoom
        tmpC[1] = oy + y / density / zoom
        return tmpC
    }

    // ---------- 活体笔迹 ----------

    private var livePen: Pen? = null
    private val livePts = ArrayList<Pt3>()
    private var liveLine = false   // 尺子：整笔替换为「首点 → 45° 吸附终点」（落笔时锁定）
    private var linePress = 0f     // 尺子笔这一笔的峰值压感（两点直线恒宽，见 stylusMove 的尺子分支）
    private val snapOut = FloatArray(2)

    private fun clearLive() {
        livePen = null
        livePts.clear()
        liveLine = false
        linePress = 0f
    }

    // ---------- 输入 ----------

    private var penActive = false
    private var penId = -1
    private var penKind = 0        // 0=无 1=落墨 2=擦除 3=平移 4=minimap 拖动 5=框选
    private var penX = 0f
    private var penY = 0f

    private class Finger(var x: Float, var y: Float)
    private val touches = HashMap<Int, Finger>()
    private val touchOrder = ArrayList<Int>()
    private var panId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var pinching = false
    private var pinchLastD = 0f   // 上一帧两指间距（px）——缩放按帧增量算，见 PinchSplit
    private var pinchMx = 0f   // 上一帧两指中点（dp）——双指整体挪动 = 平移，见 [pinchMove]
    private var pinchMy = 0f
    private val pinchSplit = PinchSplit(density)

    // 防误触（同页内 PageCanvasView）：笔靠近时不认手指；手指手势判成误触时视口还原到它开始之前
    private val penNear = PenProximity()
    private var gestureT0 = 0L
    private var gestureOx0 = 0f
    private var gestureOy0 = 0f
    private var gestureZoom0 = 1f
    private var gestureX0 = 0f            // 这次手指手势第一根手指落下的位置（px），误触判「划了多远」用
    private var gestureY0 = 0f
    private var gestureWritePaused = false   // 手指落下时笔刚用过：单指不滚动（双指照常）
    private var lastFingerDownT = 0L   // 上一根手指落下的时间（不管认没认）：③ 的双指放行用
    private var zoneHeldId = -1        // 被屏蔽区挡下、还按着的那根手指：第二根很快落下就补回来
    private val fingerGate = FingerGate(this, penNear)   // 此刻手指能不能用 → 顶栏小手键（同页内）
    private var miniDrag = false   // 手指正在 minimap 上点/拖（panId = 那根手指）

    // —— 分页画板的松手惯性（2026-09-26 用户报「分页画板滚动没有惯性」）——
    // 手感与页内阅读区同一套（`PageCanvasView.startMomentum`）：速度按 0.7/0.3 平滑，松手后 0.94^(dt/16) 衰减，
    // 碰到夹取边界那一轴停。单位 = 画布 dp / ms。惯性滑到底**不算**上拉加页（加页只认手指 / 笔还在拖的那段）。
    private var velX = 0f
    private var velY = 0f
    private var lastMoveT = 0L
    private var momentumOn = false

    /** 拖动了一下（画布 dp）：记速度，给松手惯性用 */
    private fun trackVelocity(dx: Float, dy: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = now - lastMoveT
        lastMoveT = now
        if (dt in 1..99) { velX = 0.7f * velX + 0.3f * (dx / dt); velY = 0.7f * velY + 0.3f * (dy / dt) }
        else { velX = 0f; velY = 0f }
        if (hLocked) velX = 0f   // 横向锁死：甩出去的那一下也不许带横向分量
    }

    private fun cancelMomentum() {
        momentumOn = false
        velX = 0f; velY = 0f
        lastMoveT = android.os.SystemClock.uptimeMillis()
    }

    /** 松手：分页画板按松手速度继续滑（无限画布不甩，维持原样） */
    private fun startMomentum() {
        if (!paged || android.os.SystemClock.uptimeMillis() - lastMoveT > 80) { cancelMomentum(); return }
        if (hypot(velX * zoom, velY * zoom) < 0.05f * density) { cancelMomentum(); return }
        momentumOn = true
        var last = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                if (!momentumOn) return
                val now = android.os.SystemClock.uptimeMillis()
                val dt = min(50f, (now - last).toFloat())
                last = now
                val wantX = ox + velX * dt
                val wantY = oy + velY * dt
                ox = wantX; oy = wantY
                clampViewport()
                if (abs(ox - wantX) > 0.001f) velX = 0f   // 碰边那一轴停
                if (abs(oy - wantY) > 0.001f) velY = 0f
                val decay = 0.94f.pow(dt / 16f)
                velX *= decay; velY *= decay
                invalidate()
                onViewportChanged?.invoke()
                if (hypot(velX * zoom, velY * zoom) > 0.02f * density) postOnAnimation(this) else momentumOn = false
            }
        }
        postOnAnimation(step)
    }

    private val palmPx = dp(PadConst.PALM)
    private val miniRect = RectF()

    /** 橡皮圆环（视口 px 坐标；ringR 是 px 半径） */
    private var ringOn = false
    private var ringX = 0f
    private var ringY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMomentum()   // 手 / 笔一落下就接住正在滑的纸
                takeOverViewport()   // 此后视口归用户：复位的那份不再压着、晚到的位置也不再复位
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    if (fingerAllowed(e, 0)) fingerDown(e.getPointerId(0), e.getX(0), e.getY(0), e.getTouchMajor(0))
                } else {
                    penNear.onPen(e.getX(0), e.getY(0))
                    stylusDown(e, 0)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    if (fingerAllowed(e, idx)) {
                        adoptZoneHeld(e)   // 双指放行：先前被屏蔽区挡下的那根补回来（同页内）
                        fingerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx), e.getTouchMajor(idx))
                    }
                } else if (!penActive) {
                    penNear.onPen(e.getX(idx), e.getY(idx))
                    rejectFingerGesture(Guard.PEN_WRITING)   // 手指手势进行中笔落下：那多半是手掌先着了屏
                    stylusDown(e, idx)   // 笔优先（stylusDown 内清掉手指状态）
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penActive) {
                    val pi = e.findPointerIndex(penId)
                    if (pi >= 0) {
                        penNear.onPen(e.getX(pi), e.getY(pi))
                        // 展开历史点（Android 按 batch 投递，不展开 = 采样率腰斩，同 PageCanvasView）
                        for (h in 0 until e.historySize) stylusMove(e, pi, h)
                        stylusMove(e, pi, -1)
                    }
                } else {
                    for (i in 0 until e.pointerCount) {
                        touches[e.getPointerId(i)]?.let { it.x = e.getX(i); it.y = e.getY(i) }
                    }
                    when {
                        miniDrag -> {
                            val pi = e.findPointerIndex(panId)
                            if (pi >= 0) miniJump(e.getX(pi), e.getY(pi))
                        }
                        pinching && touchOrder.size >= 2 -> pinchMove()
                        // 双指滚动模式：单指划动不平移（防误触，同页内 PageCanvasView.panMove）
                        // 写字时暂停单指滚动（手指落下那一刻定，见 PenProximity.writePaused）：同上
                        panId >= 0 && !twoFingerScroll && !gestureWritePaused -> {
                            val pi = e.findPointerIndex(panId)
                            if (pi >= 0) panTo(e.getX(pi), e.getY(pi))
                        }
                        // 单指被挡下（双指滚动模式 / 刚写过字）：划过死区才提示，轻点不打扰
                        panId >= 0 -> {
                            val pi = e.findPointerIndex(panId)
                            if (pi >= 0 && hypot(e.getX(pi) - gestureX0, e.getY(pi) - gestureY0) > dp(PadConst.DEAD)) {
                                fingerGate.show(if (twoFingerScroll) Guard.TWO_FINGER else Guard.WRITE_PAUSE)
                            }
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == zoneHeldId) zoneHeldId = -1
                if (penActive && id == penId) { penNear.onPen(); stylusUp() }
                else if (id in touches && PenProximity.canceled(e)) rejectFingerGesture(Guard.REJECTED)
                else endTouch(id)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                zoneHeldId = -1
                if (penActive) penNear.onPen()
                else if (touches.isNotEmpty() && PenProximity.canceled(e)) {
                    rejectFingerGesture(Guard.REJECTED)
                    endPull()
                    fingerGate.release()   // 先判误触（show）再收尾，顺序反了会一直灰着（同页内）
                    fingerGate.update()
                    return true
                }
                fingerGate.release()   // 手全部离屏：「不能用」再算一会儿就恢复
                pinchSplit.logEnd("画布")
                // 松手前是不是在平移（单指 / 双指滚动 / 笔在翻页·框选模式下拖）：是就甩出去
                // （双指滚动模式下两指先后抬起，最后一指抬起时已不在 pinching：交给 startMomentum 的「80ms 内还在动」判断）
                val wasPanning = e.actionMasked == MotionEvent.ACTION_UP &&
                    ((!penActive && !miniDrag) || (penActive && penKind == 3))
                val suspect = wasPanning && flingSuspect()   // 要在清掉触点之前判
                if (penActive) {
                    stylusUp()
                }
                touches.clear(); touchOrder.clear()
                pinching = false; panId = -1; miniDrag = false
                endPull()   // 一次手势结束：上拉加页的账清零
                if (wasPanning && !suspect) startMomentum() else cancelMomentum()
            }
        }
        fingerGate.update()   // 笔落下 / 抬起、手指被挡都可能改「手指能不能用」
        return true
    }

    // ---- 手指：单指平移、双指捏合（作用在草稿纸视口上，与页内同款只是目标不同） ----

    /** 这根手指认不认（同页内 PageCanvasView.fingerAllowed） */
    private fun fingerAllowed(e: MotionEvent, idx: Int): Boolean {
        if (penActive) {
            PenProximity.logFinger("画布", e, idx, density, Guard.PEN_WRITING.log)
            fingerGate.show(Guard.PEN_WRITING)   // 写字时压上来的手掌也要算「不能用」（同页内）
            return false
        }
        // ③ 只挡单根手指：与上一根手指落下间隔不到 PAIR_MS = 主动双指操作，放行（同页内）
        val now = android.os.SystemClock.uptimeMillis()
        val pair = now - lastFingerDownT < PenProximity.PAIR_MS && (touches.isNotEmpty() || zoneHeldId >= 0)
        lastFingerDownT = now
        val why = when {
            penNear.near() -> Guard.PEN_NEAR
            e.getTouchMajor(idx) > palmPx -> Guard.BIG_CONTACT
            !pair && penNear.inPalmZone(e.getX(idx), e.getY(idx), density) -> Guard.PALM_ZONE
            else -> null
        }
        PenProximity.logFinger("画布", e, idx, density, why?.log)
        if (why == Guard.PALM_ZONE) zoneHeldId = e.getPointerId(idx)
        why?.let { fingerGate.show(it) }   // 顶栏小手变灰，直到手全部离屏（同页内）
        return why == null
    }

    /** ③ 双指放行时，把先前被屏蔽区挡下、还按在屏上的那根手指补进手势，并撤掉小手的灰（同页内） */
    private fun adoptZoneHeld(e: MotionEvent) {
        val id = zoneHeldId
        if (id < 0) return
        zoneHeldId = -1
        val pi = e.findPointerIndex(id)
        if (pi < 0) return
        android.util.Log.i(PenProximity.TAG, "画布 两指几乎同时落下，屏蔽区放行")
        fingerGate.clear()
        fingerDown(id, e.getX(pi), e.getY(pi), 0f)
    }

    /** 单指松手前这一下是不是短促的误触划动（同页内 fingerFling）：是就不甩惯性。笔拖动不在此列 */
    private fun flingSuspect(): Boolean {
        if (penActive || touchOrder.size != 1) return false
        val dur = android.os.SystemClock.uptimeMillis() - gestureT0
        val travel = hypot(lastPanX - gestureX0, lastPanY - gestureY0)
        // 没划过死区 = 轻点，本来就没有速度可甩；别拿它记一条「判为误触」（页内 fingerFling 只在过了死区后才调）
        if (travel < dp(PadConst.DEAD)) return false
        if (!penNear.flingSuspect(dur, travel, density)) return false
        android.util.Log.i(PenProximity.TAG, "画布短促划动（${dur}ms ${"%.0f".format(travel / density)}dp）判为误触，不甩惯性")
        return true
    }

    /**
     * 进行中的手指手势判成误触：立即结束、不甩惯性；开始不到 [PenProximity.REVERT_MS] 就把视口还原；
     * 小手一直灰到手全部离屏（同页内）。
     */
    private fun rejectFingerGesture(g: Guard) {
        if (touches.isEmpty()) return
        val young = android.os.SystemClock.uptimeMillis() - gestureT0 < PenProximity.REVERT_MS
        val moved = ox != gestureOx0 || oy != gestureOy0 || zoom != gestureZoom0
        android.util.Log.i(PenProximity.TAG, "画布手指手势判为误触（${g.log}）" + if (young && moved) "，视口还原" else "")
        fingerGate.show(g)
        pinchSplit.logEnd("画布·误触")
        touches.clear(); touchOrder.clear(); pinching = false; panId = -1; miniDrag = false
        cancelMomentum()
        if (young && moved) {
            ox = gestureOx0; oy = gestureOy0; zoom = gestureZoom0
            clampViewport()
            invalidate()
            onViewportChanged?.invoke()
        }
    }

    private fun fingerDown(id: Int, x: Float, y: Float, touchMajor: Float) {
        if (penActive) return            // 笔在写 → 忽略手掌/手指
        if (touchMajor > palmPx) return  // 大面积接触（手掌）忽略
        if (touchOrder.isEmpty()) {   // 一次新手势的第一根手指：记下起点，误触时还原到这里
            gestureT0 = android.os.SystemClock.uptimeMillis()
            gestureOx0 = ox; gestureOy0 = oy; gestureZoom0 = zoom
            gestureX0 = x; gestureY0 = y
            gestureWritePaused = penNear.writePaused()
            pinchSplit.reset()
        }
        touches[id] = Finger(x, y)
        if (id !in touchOrder) touchOrder.add(id)
        if (touchOrder.size >= 2) {
            val a = touches[touchOrder[0]] ?: return
            val b = touches[touchOrder[1]] ?: return
            pinching = true
            miniDrag = false   // 第二指落下：minimap 拖动让给捏合
            pinchLastD = hypot(a.x - b.x, a.y - b.y)
            pinchMx = (a.x + b.x) / 2f / density
            pinchMy = (a.y + b.y) / 2f / density
            panId = -1
        } else if (showMinimap() && inMinimap(x, y)) {
            miniDrag = true
            panId = id
            miniJump(x, y)
        } else {
            panId = id
            lastPanX = x; lastPanY = y
        }
    }

    private fun endTouch(id: Int) {
        if (id !in touches) return
        touches.remove(id)
        touchOrder.remove(id)
        if (miniDrag && id == panId) { miniDrag = false; panId = -1 }
        pinching = false
        when {
            touchOrder.size == 1 -> {
                panId = touchOrder[0]
                val t = touches[panId]!!
                lastPanX = t.x; lastPanY = t.y
            }
            touchOrder.isEmpty() -> { panId = -1; pinchSplit.logEnd("画布") }
            else -> {
                val a = touches[touchOrder[0]] ?: return
                val b = touches[touchOrder[1]] ?: return
                pinching = true
                pinchLastD = hypot(a.x - b.x, a.y - b.y)
                pinchMx = (a.x + b.x) / 2f / density
                pinchMy = (a.y + b.y) / 2f / density
                panId = -1
            }
        }
    }

    /** 单指平移：屏幕拖多少视口移多少（注意方向：手指右拖 = 看左边的内容，origin 减） */
    private fun panTo(x: Float, y: Float) {
        val beforeY = oy
        val dx = (lastPanX - x) / density / zoom
        val dy = (lastPanY - y) / density / zoom
        if (!hLocked) ox += dx
        oy += dy
        trackVelocity(dx, dy)
        val wantY = oy
        lastPanX = x; lastPanY = y
        clampViewport()
        notePull(wantY, beforeY)   // 分页：到底后继续上拉 → 加页（无限画布直接返回）
        invalidate()
        onViewportChanged?.invoke()
    }

    /**
     * 双指：以两指中点为锚缩放，中点移动跟着平移（与页内 pinchMove 同款跟手）。
     * 缩放按帧由 [PinchSplit] 拆出来：双指滚动时手指自然并拢的那点间距变化不算缩放。
     */
    private fun pinchMove() {
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val d = hypot(a.x - b.x, a.y - b.y)
        val mx = (a.x + b.x) / 2f / density
        val my = (a.y + b.y) / 2f / density
        val beforeY = oy
        val f = pinchSplit.factor(pinchLastD, d, (mx - pinchMx) * density, (my - pinchMy) * density)
        pinchLastD = d
        val v = ScratchGeom.zoomAt(ox, oy, zoom, f, mx, my)
        ox = v[0]; oy = v[1]; zoom = v[2]
        // 中点整体挪动 = 平移。缩放锚点只保证「中点底下那一点不动」，两指齐挪时 factor≈1、
        // zoomAt 原地返回，光靠它双指是拖不动纸的——双指滚动模式下就等于纸钉死了。
        if (!hLocked) ox -= (mx - pinchMx) / zoom
        oy -= (my - pinchMy) / zoom
        trackVelocity(-(mx - pinchMx) / zoom, -(my - pinchMy) / zoom)   // 双指滚动松手也有惯性
        pinchMx = mx
        pinchMy = my
        val wantY = oy
        clampViewport()
        notePull(wantY, beforeY)   // 双指滚动模式下拖到底也能加页
        invalidate()
        onViewportChanged?.invoke()
    }

    // ---- 笔：落墨 / 擦除 / 框选；翻页·文字笔记模式退化为平移；minimap 可点可拖 ----

    private fun stylusDown(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        // 笔优先：清掉进行中的手指平移/捏合
        touches.clear(); touchOrder.clear(); pinching = false; panId = -1; miniDrag = false
        penActive = true
        penId = e.getPointerId(idx)
        penX = x; penY = y
        if (showMinimap() && inMinimap(x, y)) {
            penKind = 4
            miniJump(x, y)
            return
        }
        val t = curTools()
        if (!t.lassoTool && hasSel) clearLasso()   // 换了工具：选中集作废（同页内切模式）
        val c = toCanvas(x, y)
        when {
            t.lassoTool -> {
                penKind = 5
                lassoDown(x, y)
            }
            t.eraseTool -> {
                penKind = 2
                eraseAt(c[0], c[1])
                onEraseAt?.invoke(c[0], c[1])
                if (t.eraserRing) { ringX = x; ringY = y; ringOn = true }
            }
            t.inkTool -> {
                penKind = 1
                val pen = if (t.relativeInk && zoom > 0.01f) t.pen.copy(w = t.pen.w / zoom) else t.pen
                livePen = pen
                liveLine = t.rulerOn   // 尺子按落笔那一刻锁进这一笔（同 PageCanvasView）
                livePts.clear()
                livePts.add(Pt3(c[0], c[1], e.getPressure(idx)))
                linePress = livePts[0].p   // 尺子笔的峰值压感起点，见 stylusMove 的尺子分支
                onStrokeBegin?.invoke(pen, livePts[0], liveLine)
            }
            else -> penKind = 3   // 平移
        }
        invalidate()
    }

    private fun stylusMove(e: MotionEvent, pi: Int, h: Int) {
        val historical = h >= 0
        val x = if (historical) e.getHistoricalX(pi, h) else e.getX(pi)
        val y = if (historical) e.getHistoricalY(pi, h) else e.getY(pi)
        val p = if (historical) e.getHistoricalPressure(pi, h) else e.getPressure(pi)
        when (penKind) {
            4 -> miniJump(x, y)
            5 -> lassoMove(x, y)
            3 -> {
                val beforeY = oy
                val dx = (penX - x) / density / zoom
                val dy = (penY - y) / density / zoom
                if (!hLocked) ox += dx
                oy += dy
                if (!historical) trackVelocity(dx, dy)   // 历史点同一时刻投递，只按实时点记速度
                val wantY = oy
                penX = x; penY = y
                clampViewport()
                notePull(wantY, beforeY)
                invalidate()
                onViewportChanged?.invoke()
            }
            1 -> {
                val c = toCanvas(x, y)
                if (liveLine && livePts.isNotEmpty()) {
                    // 尺子：画布是等比坐标系 → aspect=1（页内那套传页纵横比是两轴尺度不同）。
                    // 压感取这一笔的峰值、两端同值（理由见 PageCanvasView 的同款分支：终点每帧
                    // 被替换，抬笔前最后一个采样几乎没压力，整条线会缩成头发丝）。
                    val a0 = livePts[0]
                    PadConst.rulerSnap(a0.x, a0.y, c[0], c[1], 1f, snapOut)
                    if (p > linePress) linePress = p
                    val pt = Pt3(snapOut[0], snapOut[1], linePress)
                    livePts.clear()
                    livePts.add(Pt3(a0.x, a0.y, linePress))
                    livePts.add(pt)
                    onStrokeMove?.invoke(listOf(pt))   // 替换语义：只给最新终点（同页内尺子分支）
                } else {
                    val pt = Pt3(c[0], c[1], p)
                    livePts.add(pt)
                    onStrokeMove?.invoke(listOf(pt))
                }
                invalidate()
            }
            2 -> {
                val c = toCanvas(x, y)
                eraseAt(c[0], c[1])
                onEraseAt?.invoke(c[0], c[1])
                if (ringOn) { ringX = x; ringY = y }
                invalidate()
            }
        }
    }

    private fun stylusUp() {
        when (penKind) {
            1 -> {
                val pen = livePen
                if (pen != null && livePts.isNotEmpty()) {
                    onStrokeEnd?.invoke(pen, ArrayList(livePts))
                }
                clearLive()
            }
            2 -> onEraseFinish?.invoke(ArrayList(strokes))
            3 -> endPull()   // 笔拖着平移的那次手势结束：上拉加页的账清零
            5 -> lassoUp()
        }
        penActive = false
        penId = -1
        penKind = 0
        ringOn = false
        invalidate()
    }

    /**
     * 本地乐观擦除（与 web `padEraseLocal` / 页内 `eraseHit` 同算法）：
     * 整笔 = 任一点命中即删整条；局部 = [InkEdit.splitStroke] 切段（自动继承 id/padId）。
     * 半径换算 = eraserSize × 800（三端同一个数，见 [ScratchGeom.ERASER_REF_W]）。
     */
    private fun eraseAt(cx: Float, cy: Float) {
        val t = curTools()
        val r = t.eraserSize * ScratchGeom.ERASER_REF_W
        val r2 = r * r
        val removed = ArrayList<Stroke>()
        val added = ArrayList<Stroke>()
        if (t.eraserMode == 0) {
            var i = strokes.size - 1
            while (i >= 0) {
                val s = strokes[i]
                // 先按包围盒粗筛（盒子已缓存），离得远的整条不必逐点算
                if (!near(s, cx, cy, r)) { i--; continue }
                val hit = s.pts.any { val dx = it.x - cx; val dy = it.y - cy; dx * dx + dy * dy <= r2 }
                if (hit) { strokes.removeAt(i); removed.add(s) }
                i--
            }
        } else {
            val out = ArrayList<Stroke>(strokes.size)
            for (s in strokes) {
                if (!near(s, cx, cy, r)) { out.add(s); continue }
                // 草稿纸笔迹 page 恒 0（splitStroke 的页过滤因此恒过，坐标系无关）
                val segs = InkEdit.splitStroke(s, cx, cy, 0, r2)
                if (segs.size == 1 && segs[0] === s) out.add(s) else { out.addAll(segs); removed.add(s); added.addAll(segs) }
            }
            if (removed.isNotEmpty()) {
                strokes.clear()
                strokes.addAll(out)
            }
        }
        if (removed.isNotEmpty()) {
            strokesChanged(removed, added)
            invalidate()
        }
    }

    // ---- 框选（笔：拖空白 = 自由圈选 / 拖选中框内 = 移动 / 拖手柄 = 缩放；只作用于笔迹） ----
    //
    // 命中规则与 Mac 本机 `ScratchPadNSView.finishLassoSelect`、平板上行 `lassoMove`（Mac `AppModel+Scratch`）
    // 同一口径：笔迹任一点落在多边形内。变换算法同 Mac `InkEdit.canvasTranslated/canvasScaled`（画布不夹取，
    // 线宽 ×√(sx·sy) 夹 0.5…40）。两模式一样**本地立刻生效**（选中那几条换成新版本），再经 [onLassoEdit]
    // 交给宿主：模式1 落库，模式2 发给 Mac 复判执行、回推 `scratchStrokes` 为准。
    // 选中集按**多边形**记（不按下标 / id：模式2 的笔迹没有 id）；表一换就按多边形重判（[refreshLassoSelection]），
    // 变换提交后多边形跟着变换，于是回推回来的新位置照样选得中。

    private val lassoDeadPx = dp(PadConst.LASSO_DEAD)
    private var selPoly: FloatArray? = null          // 选区多边形（画布坐标，扁平 x,y…）
    private var selIdx = IntArray(0)                  // 命中的笔迹在 [strokes] 里的下标（随 [selPoly] 重判）
    private var hasSel = false
        set(v) {
            if (field != v) { field = v; onLassoSelChanged?.invoke(v) }
        }
    private val selBox = FloatArray(4)               // 命中笔迹的联合包围盒（画布坐标 x0,y0,x1,y1）
    private var lassoMode = 0                        // 0=未越过死区 1=圈选 2=移动 3=缩放
    private var lassoMoved = false
    private var lassoDownX = 0f                      // 落笔点（视口 px）
    private var lassoDownY = 0f
    private val lassoPath = ArrayList<Pt2>()         // 圈选路径（画布坐标）
    private var lassoHandle = -1                     // 缩放拖的哪个手柄（0..7 = tl,tr,bl,br,t,b,l,r）
    private var lassoAx = 0f                         // 缩放锚点（对侧手柄，画布坐标）
    private var lassoAy = 0f
    private var lassoSx = 1f
    private var lassoSy = 1f
    private var lassoDx = 0f                         // 移动位移（画布坐标）
    private var lassoDy = 0f
    private val lassoOpp = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6)   // 对侧手柄（同 PageCanvasView）

    /** 拖动中从 [strokes] 里拿出来的那几条（下标升序 + 原对象）：单独画在变换后的位置，松手放回 */
    private class LassoDrag(val idx: IntArray, val orig: List<Stroke>)
    private var lassoDrag: LassoDrag? = null

    private val boxTmp = FloatArray(4)
    private val handleTmp = FloatArray(16)
    private var viewPts = FloatArray(256)

    fun hasLassoSelection(): Boolean = hasSel

    /** 选区多边形（画布坐标）：剪切 / 复制交给真源复判用 */
    fun lassoPolygon(): FloatArray? = selPoly?.copyOf()

    /** 选中的笔迹（模式1 剪贴板用） */
    fun lassoSelectedStrokes(): List<Stroke> = selIdx.map { strokes[it] }

    /** 视口正中的画布坐标（粘贴落点：平板没有指针，同页内 `requestClipPaste`） */
    fun viewportCenterCanvas(): FloatArray = floatArrayOf(ox + viewWdp() / zoom / 2f, oy + viewHdp() / zoom / 2f)

    /** 放弃选中集（换工具 / 换纸 / 宿主要求）。没东西可清时什么都不做——宿主每次刷新顶栏都可以调 */
    fun clearLasso() {
        if (selPoly == null && !hasSel && lassoDrag == null && lassoPath.isEmpty() && lassoMode == 0) return
        if (lassoDrag != null) abortLassoDrag()
        selPoly = null
        selIdx = IntArray(0)
        hasSel = false
        lassoMode = 0
        lassoMoved = false
        lassoPath.clear()
        invalidate()
    }

    /** 本地删掉选中的笔迹（剪切的乐观一半），返回删掉的那几条；选中集随之清空 */
    fun deleteLassoSelection(): List<Stroke> {
        if (!hasSel) return emptyList()
        val gone = selIdx.map { strokes[it] }
        for (i in selIdx.sortedDescending()) strokes.removeAt(i)
        strokesChanged(gone, emptyList())
        clearLasso()
        return gone
    }

    /** 表换过了：按多边形重判选中集（拖动中不动它——那几条此刻不在表里） */
    private fun refreshLassoSelection() {
        val poly = selPoly ?: return
        if (lassoDrag != null) return
        hitTest(poly)
    }

    /**
     * 按多边形算命中与包围盒；零命中 = 没有选中集。先按包围盒粗筛（笔迹盒子由块缓存按对象缓存着），
     * 盒子外的点不做射线判定——笔迹多时逐点逐边算是几百万次运算。
     */
    private fun hitTest(poly: FloatArray) {
        val t0 = android.os.SystemClock.uptimeMillis()
        var px0 = Float.POSITIVE_INFINITY; var py0 = Float.POSITIVE_INFINITY
        var px1 = Float.NEGATIVE_INFINITY; var py1 = Float.NEGATIVE_INFINITY
        for (i in poly.indices step 2) {
            px0 = min(px0, poly[i]); px1 = max(px1, poly[i])
            py0 = min(py0, poly[i + 1]); py1 = max(py1, poly[i + 1])
        }
        val hits = ArrayList<Int>()
        var x0 = Float.POSITIVE_INFINITY; var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
        var tested = 0
        for (i in strokes.indices) {
            val s = strokes[i]
            val b = tiles.box(s)
            if (b[2] < px0 || b[0] > px1 || b[3] < py0 || b[1] > py1) continue
            tested++
            if (s.pts.none { it.x >= px0 && it.x <= px1 && it.y >= py0 && it.y <= py1 &&
                    InkEdit.pointInPolygon(it.x, it.y, poly) }) continue
            hits.add(i)
            for (p in s.pts) { x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y) }
        }
        lassoLog("框选判定 ${android.os.SystemClock.uptimeMillis() - t0}ms：全集 ${strokes.size} 条、" +
            "粗筛后 $tested 条、命中 ${hits.size} 条，多边形 ${poly.size / 2} 点")
        if (hits.isEmpty()) {
            selPoly = null
            selIdx = IntArray(0)
            hasSel = false
            return
        }
        selPoly = poly
        selIdx = hits.toIntArray()
        selBox[0] = x0; selBox[1] = y0; selBox[2] = x1; selBox[3] = y1
        hasSel = true
    }

    private fun vx(cx: Float) = (cx - ox) * zoom * density
    private fun vy(cy: Float) = (cy - oy) * zoom * density

    /** 选中框（视口 px，外扩 6dp、至少 16dp，**不含拖动中的变换**），同 PageCanvasView.lassoViewBox */
    private fun selViewBox(out: FloatArray): Boolean {
        if (!hasSel) return false
        val pad = dp(6f)
        val x0 = vx(selBox[0]); val y0 = vy(selBox[1])
        out[0] = x0 - pad
        out[1] = y0 - pad
        out[2] = max(vx(selBox[2]) - x0 + pad * 2, dp(16f))
        out[3] = max(vy(selBox[3]) - y0 + pad * 2, dp(16f))
        return true
    }

    private fun handlePts(box: FloatArray, out: FloatArray) {
        val x = box[0]; val y = box[1]; val w = box[2]; val h = box[3]
        val xs = floatArrayOf(x, x + w, x, x + w, x + w / 2, x + w / 2, x, x + w)
        val ys = floatArrayOf(y, y, y + h, y + h, y, y + h, y + h / 2, y + h / 2)
        for (i in 0 until 8) { out[i * 2] = xs[i]; out[i * 2 + 1] = ys[i] }
    }

    private fun boxContains(x: Float, y: Float): Boolean {
        if (!selViewBox(boxTmp)) return false
        val g = dp(8f)
        return x >= boxTmp[0] - g && x <= boxTmp[0] + boxTmp[2] + g && y >= boxTmp[1] - g && y <= boxTmp[1] + boxTmp[3] + g
    }

    private fun lassoDown(x: Float, y: Float) {
        lassoDownX = x; lassoDownY = y
        lassoMoved = false
        lassoMode = 0
        lassoHandle = -1
        lassoDx = 0f; lassoDy = 0f
        lassoSx = 1f; lassoSy = 1f
    }

    /** 越过死区后判一次形态：落点命中手柄（≤10dp）→ 缩放；落在选中框内 → 移动；否则重新圈选 */
    private fun lassoMove(x: Float, y: Float) {
        if (!lassoMoved) {
            if (hypot(x - lassoDownX, y - lassoDownY) < lassoDeadPx) return
            lassoMoved = true
            var m = 1
            if (selViewBox(boxTmp)) {
                handlePts(boxTmp, handleTmp)
                var hit = -1
                for (i in 0 until 8) {
                    if (hypot(lassoDownX - handleTmp[i * 2], lassoDownY - handleTmp[i * 2 + 1]) <= dp(10f)) { hit = i; break }
                }
                if (hit >= 0) {
                    m = 3
                    lassoHandle = hit
                    val o = lassoOpp[hit]
                    val a = toCanvas(handleTmp[o * 2], handleTmp[o * 2 + 1])
                    lassoAx = a[0]; lassoAy = a[1]
                } else if (boxContains(lassoDownX, lassoDownY)) m = 2
            }
            lassoMode = m
            if (m == 1) {
                selPoly = null
                selIdx = IntArray(0)
                hasSel = false
                lassoPath.clear()
                val c = toCanvas(lassoDownX, lassoDownY)
                lassoPath.add(Pt2(c[0], c[1]))
            } else beginLassoDrag()
        }
        when (lassoMode) {
            1 -> {
                // ≥3dp 抽稀（更密的点对多边形命中无增益）
                val last = lassoPath.last()
                if (hypot(x - vx(last.x), y - vy(last.y)) >= dp(3f)) {
                    val c = toCanvas(x, y)
                    lassoPath.add(Pt2(c[0], c[1]))
                }
            }
            2 -> {
                lassoDx = (x - lassoDownX) / density / zoom
                lassoDy = (y - lassoDownY) / density / zoom
            }
            3 -> updateLassoScale(x, y)
        }
        invalidate()
    }

    /**
     * 缩放：被拖手柄当前位置 / 原「手柄→锚点」向量（视口空间，同 PageCanvasView.updateLassoScale）。
     * 角手柄等比（取变化更大的一轴；平板没有 Shift），边中点单轴；夹 0.05…20。
     */
    private fun updateLassoScale(x: Float, y: Float) {
        val h = lassoHandle
        if (h < 0 || !selViewBox(boxTmp)) return
        handlePts(boxTmp, handleTmp)
        val aVx = vx(lassoAx); val aVy = vy(lassoAy)
        val denomX = handleTmp[h * 2] - aVx
        val denomY = handleTmp[h * 2 + 1] - aVy
        var sx = 1f; var sy = 1f
        when (h) {
            4, 5 -> if (abs(denomY) > 1f) sy = (y - aVy) / denomY
            6, 7 -> if (abs(denomX) > 1f) sx = (x - aVx) / denomX
            else -> if (abs(denomX) > 1f && abs(denomY) > 1f) {
                sx = (x - aVx) / denomX; sy = (y - aVy) / denomY
                val s = if (abs(sx - 1) >= abs(sy - 1)) sx else sy
                sx = s; sy = s
            }
        }
        lassoSx = sx.coerceIn(0.05f, 20f)
        lassoSy = sy.coerceIn(0.05f, 20f)
    }

    /** 开始拖：选中那几条从 [strokes] 里拿出来（块缓存随之去掉它们），拖动中由 [drawLassoDrag] 画 */
    private fun beginLassoDrag() {
        if (!hasSel) return
        val t0 = android.os.SystemClock.uptimeMillis()
        val idx = selIdx.sortedArray()
        val orig = idx.map { strokes[it] }
        for (i in idx.indices.reversed()) strokes.removeAt(idx[i])
        strokesChanged(orig, emptyList())
        lassoDrag = LassoDrag(idx, orig)
        lassoLog("开始拖动（${if (lassoMode == 3) "缩放" else "移动"}）${orig.size} 条，拿出来 ${android.os.SystemClock.uptimeMillis() - t0}ms")
    }

    /** 把拿出来的放回原位（[list] 与 [LassoDrag.idx] 一一对应，升序插回 = 原来的叠放次序） */
    private fun putBack(d: LassoDrag, list: List<Stroke>) {
        for (k in d.idx.indices) strokes.add(min(d.idx[k], strokes.size), list[k])
        strokesChanged(emptyList(), list)
    }

    /** 拖动作废：原样放回 */
    private fun abortLassoDrag() {
        val d = lassoDrag ?: return
        lassoDrag = null
        putBack(d, d.orig)
        lassoMode = 0
        lassoMoved = false
    }

    private fun movedStroke(s: Stroke, dx: Float, dy: Float): Stroke =
        s.copy(pts = s.pts.map { Pt3(it.x + dx, it.y + dy, it.p) })

    private fun scaledStroke(s: Stroke, ax: Float, ay: Float, sx: Float, sy: Float): Stroke =
        s.copy(
            pts = s.pts.map { Pt3(ax + (it.x - ax) * sx, ay + (it.y - ay) * sy, it.p) },
            pen = s.pen.copy(w = (s.pen.w * kotlin.math.sqrt(sx * sy)).coerceIn(0.5f, 40f)),
        )

    private fun transformPoly(poly: FloatArray, scale: Boolean): FloatArray {
        val out = poly.copyOf()
        for (i in out.indices step 2) {
            if (scale) {
                out[i] = lassoAx + (out[i] - lassoAx) * lassoSx
                out[i + 1] = lassoAy + (out[i + 1] - lassoAy) * lassoSy
            } else {
                out[i] += lassoDx
                out[i + 1] += lassoDy
            }
        }
        return out
    }

    /** 松手：点一下（没越过死区）且在框外 = 清选中；圈选 = 本地判定；移动 / 缩放 = 本地生效 + 交给宿主 */
    private fun lassoUp() {
        if (!lassoMoved || lassoMode == 0) {
            if (hasSel && !boxContains(lassoDownX, lassoDownY)) clearLasso()
        } else if (lassoMode == 1) {
            if (lassoPath.size >= 3) {
                val poly = FloatArray(lassoPath.size * 2)
                for (i in lassoPath.indices) { poly[i * 2] = lassoPath[i].x; poly[i * 2 + 1] = lassoPath[i].y }
                hitTest(poly)
            }
        } else {
            commitLassoDrag()
        }
        lassoMode = 0
        lassoMoved = false
        lassoHandle = -1
        lassoPath.clear()
        invalidate()
    }

    private fun commitLassoDrag() {
        val d = lassoDrag ?: return
        lassoDrag = null
        val poly = selPoly
        val isScale = lassoMode == 3
        val noop = if (isScale) lassoSx == 1f && lassoSy == 1f else lassoDx == 0f && lassoDy == 0f
        if (poly == null || noop) { putBack(d, d.orig); return }
        val t0 = android.os.SystemClock.uptimeMillis()
        val after = d.orig.map {
            if (isScale) scaledStroke(it, lassoAx, lassoAy, lassoSx, lassoSy) else movedStroke(it, lassoDx, lassoDy)
        }
        putBack(d, after)
        lastCommitAt = android.os.SystemClock.uptimeMillis()
        lastCommitBefore = HashSet(d.orig)
        lassoLog("松手提交（${if (isScale) "缩放" else "移动"}）${after.size} 条，放回 ${lastCommitAt - t0}ms")
        // 选中集跟着走：多边形同一个变换（点在多边形内的关系在仿射变换下保持），下标就是放回的位置
        hitTest(transformPoly(poly, isScale))
        onLassoEdit?.invoke(
            LassoEdit(
                poly = poly, dx = lassoDx, dy = lassoDy,
                scale = if (isScale) floatArrayOf(lassoAx, lassoAy, lassoSx, lassoSy) else null,
                before = d.orig, after = after,
            ),
        )
    }

    private fun ensureViewPts(n: Int): FloatArray {
        if (viewPts.size < n) viewPts = FloatArray(n * 2)
        return viewPts
    }

    /**
     * 拖动中的那几条：画在变换后的位置。一律用**已缓存的几何**（`drawScratchStroke` 按笔迹内容缓存轮廓）——
     * 移动 = 挪原点；缩放 = 画布按锚点缩放（只是预览，线宽跟着画布走；松手后按 √(sx·sy) 规则重建）。
     * 不能每帧 `drawScratchLive` 现建轮廓：选中几百条时一帧就是几百毫秒。
     */
    private fun drawLassoDrag(c: Canvas) {
        val d = lassoDrag ?: return
        val t0 = android.os.SystemClock.uptimeMillis()
        val z = zoom
        if (lassoMode == 3) {
            c.save()
            c.scale(lassoSx, lassoSy, vx(lassoAx), vy(lassoAy))
            for (s in d.orig) ink.drawScratchStroke(c, s, ox, oy, z)
            c.restore()
        } else {
            for (s in d.orig) ink.drawScratchStroke(c, s, ox - lassoDx, oy - lassoDy, z)
        }
        val ms = android.os.SystemClock.uptimeMillis() - t0
        if (ms > 16) lassoLog("拖动预览一帧 ${ms}ms（${d.orig.size} 条）")
    }

    // ---- 框选排障打点（logcat 标签 UniReader/Lasso） ----
    private fun lassoLog(msg: String) = android.util.Log.i("UniReader/Lasso", msg)

    /** 最近一次提交：回推里若又出现提交前的那几条（旧位置），说明回推比提交旧——闪烁的直接证据 */
    private var lastCommitAt = 0L
    private var lastCommitBefore: Set<Stroke> = emptySet()

    /** 圈选虚线 + 选中光晕 + 高亮框与 8 手柄（拖动中跟着变换预览；同 PageCanvasView.drawLassoOverlay 的画法） */
    private fun drawLassoOverlay(c: Canvas) {
        val t0 = android.os.SystemClock.uptimeMillis()
        drawLassoOverlayInner(c)
        val ms = android.os.SystemClock.uptimeMillis() - t0
        if (ms > 16) lassoLog("选中光晕 / 框一帧 ${ms}ms（选中 ${selIdx.size} 条）")
    }

    private fun drawLassoOverlayInner(c: Canvas) {
        if (lassoMode == 1 && lassoPath.size >= 2) {
            val buf = ensureViewPts(lassoPath.size * 2)
            for (i in lassoPath.indices) { buf[i * 2] = vx(lassoPath[i].x); buf[i * 2 + 1] = vy(lassoPath[i].y) }
            overlays.drawLassoPath(c, buf, lassoPath.size)
        }
        if (!hasSel || !selViewBox(boxTmp)) return
        val drag = lassoDrag
        val scaling = drag != null && lassoMode == 3
        val gdx = if (drag != null && !scaling) lassoDx * zoom * density else 0f
        val gdy = if (drag != null && !scaling) lassoDy * zoom * density else 0f
        val aVx = vx(lassoAx); val aVy = vy(lassoAy)
        fun gx(v: Float) = if (scaling) aVx + (v - aVx) * lassoSx else v + gdx
        fun gy(v: Float) = if (scaling) aVy + (v - aVy) * lassoSy else v + gdy
        // 光晕：拖动中画那几条的变换后位置，平时画表里的
        val sel = drag?.orig ?: selIdx.map { strokes[it] }
        for (s in sel) {
            val wPx = (s.pen.w * zoom + 5f) * density
            if (s.pts.size == 1) {
                overlays.drawLassoHaloDot(c, gx(vx(s.pts[0].x)), gy(vy(s.pts[0].y)), wPx / 2f)
                continue
            }
            val buf = ensureViewPts(s.pts.size * 2)
            for (j in s.pts.indices) { buf[j * 2] = gx(vx(s.pts[j].x)); buf[j * 2 + 1] = gy(vy(s.pts[j].y)) }
            overlays.drawLassoHalo(c, buf, s.pts.size, wPx)
        }
        val bx = boxTmp[0]; val by = boxTmp[1]; val bw = boxTmp[2]; val bh = boxTmp[3]
        val xs = floatArrayOf(gx(bx), gx(bx + bw), gx(bx), gx(bx + bw))
        val ys = floatArrayOf(gy(by), gy(by), gy(by + bh), gy(by + bh))
        overlays.drawLassoSelection(c, xs.min(), ys.min(), xs.max(), ys.max())
        handlePts(boxTmp, handleTmp)
        for (i in 0 until 8) { handleTmp[i * 2] = gx(handleTmp[i * 2]); handleTmp[i * 2 + 1] = gy(handleTmp[i * 2 + 1]) }
        overlays.drawLassoHandles(c, handleTmp)
    }

    // ---- 悬停：擦除模式下显示橡皮尺寸圆环（同页内 hover 的口径） ----

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        penNear.onHover(e)
        // 手指手势进行中笔进了悬停范围：那几根"手指"多半是手侧面（同页内）
        if (e.actionMasked != MotionEvent.ACTION_HOVER_EXIT && touches.isNotEmpty() && penNear.near()) {
            rejectFingerGesture(Guard.PEN_NEAR)
        }
        fingerGate.update()
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val t = curTools()
                if (!penActive && t.eraseTool && t.eraserRing) {
                    ringX = e.getX(0); ringY = e.getY(0); ringOn = true
                    invalidate()
                } else if (ringOn && !penActive) {
                    ringOn = false
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (ringOn && !penActive) { ringOn = false; invalidate() }
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // ---------- 绘制 ----------

    private val patternPaint = Paint()
    // 页面底图：白底/描边一支，位图一支（位图那支要双线性过滤，缩放档之间不能糊成马赛克）
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pageBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pageDst = RectF()
    private val miniPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val miniPath = Path()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun hasContent() =
        strokes.isNotEmpty() || livePts.isNotEmpty() || pageUnderRect != null || pics.isNotEmpty() || paged ||
            lassoDrag != null   // 全选着拖：表里暂时是空的，别冒出「空白纸」提示

    /** minimap：分页画板没有（有页码，同 Mac） */
    private fun showMinimap() = minimapOn && hasContent() && !paged

    override fun onDraw(canvas: Canvas) {
        if (paged) {
            // 分页：页外 = 界面底色，每页纸色底 + 背景模板（无限画布的点阵 / 原点十字不画）
            canvas.drawColor(voidColor)
            drawPages(canvas)
        } else {
            canvas.drawColor(bgColor)
            drawPattern(canvas)
        }
        drawPageUnder(canvas)   // 底纹之上、笔迹之下（页图只是参照物，墨永远在最上面）
        drawPics(canvas)        // 画板笔记的图：同样在笔迹之下（笔迹永远能写在图上）
        // 成形笔迹走分块位图（拖动时只贴图）；缺块的地方才直接画
        val z = zoom
        ink.deferRebuild = pinching   // 捏合中别重建几何，canvas 缩放顶一拍（同页内）
        tiles.draw(canvas, ox, oy, z, width, height, pinching, drawDirect)
        drawLassoDrag(canvas)   // 框选拖动中：被拿出来的那几条画在变换后的位置
        livePen?.let { ink.drawScratchLive(canvas, it, livePts, ox, oy, z) }
        drawLassoOverlay(canvas)
        // 空白纸的引导（有笔迹后自动消失；同 Mac 的 emptyHint）
        if (!hasContent()) drawEmptyHint(canvas)
        // 橡皮尺寸圆环：半径 = eraserSize × 800 × zoom（三端同一条换算）
        if (ringOn) {
            val t = curTools()
            overlays.drawEraserRing(canvas, ringX, ringY, t.eraserSize * ScratchGeom.ERASER_REF_W * z * density)
        }
        if (showMinimap()) drawMinimap(canvas)
        if (paged) drawPullHint(canvas)
    }

    /**
     * 页面底图：把这张纸**锚定的那一页**垫在纸上当参照（几何契约见 [ScratchGeom.pageRect]）。
     * 图还没取到时先铺一块白 + 描边占位（免得开了开关却什么都没有、以为开关坏了）；
     * 描边是必需的——白页压白纸看不出页边在哪（Mac 样张里就是靠它才分得出来）。
     */
    private fun drawPageUnder(c: Canvas) {
        val r = pageUnderRect ?: return
        ensurePageBitmap()
        val z = zoom
        val left = (r[0] - ox) * z * density
        val top = (r[1] - oy) * z * density
        val right = left + r[2] * z * density
        val bottom = top + r[3] * z * density
        if (right < 0 || bottom < 0 || left > width || top > height) return   // 整块在视口外
        pagePaint.style = Paint.Style.FILL
        pagePaint.color = Color.WHITE
        c.drawRect(left, top, right, bottom, pagePaint)
        val bmp = pageUnderBmp
        if (bmp != null && !bmp.isRecycled) {
            pageDst.set(left, top, right, bottom)
            c.drawBitmap(bmp, null, pageDst, pageBmpPaint)
        }
        pagePaint.style = Paint.Style.STROKE
        pagePaint.strokeWidth = 1f
        pagePaint.color = withAlpha(inkColor, 0.3f)
        c.drawRect(left, top, right, bottom, pagePaint)
    }

    /**
     * 画板笔记的图片层：按列表顺序叠放（先画的在下）。图还没取到时先画一个淡框占位
     * （免得看起来像图丢了）；视口外的整张跳过，也不去取。
     */
    private fun drawPics(c: Canvas) {
        if (pics.isEmpty()) return
        val z = zoom
        for (p in pics) {
            val left = (p.x - ox) * z * density
            val top = (p.y - oy) * z * density
            val right = left + p.w * z * density
            val bottom = top + p.h * z * density
            if (right < 0 || bottom < 0 || left > width || top > height) continue
            val bmp = picBmps[p.key]
            if (bmp != null && !bmp.isRecycled) {
                pageDst.set(left, top, right, bottom)
                c.drawBitmap(bmp, null, pageDst, pageBmpPaint)
            } else {
                ensurePic(p.key)
                pagePaint.style = Paint.Style.STROKE
                pagePaint.strokeWidth = 1f
                pagePaint.color = withAlpha(inkColor, 0.25f)
                c.drawRect(left, top, right, bottom, pagePaint)
            }
        }
    }

    // ---------- 分页画板：页面 + 背景模板（同 Mac `BoardPagesCALayer`） ----------

    /** 页外颜色 = 界面底色（与模式1 阅读区外围同一个语义色） */
    private val voidColor by lazy { Ui.col(context, com.xvan.unireader.R.color.surface_dim) }
    private val pageFill = Paint()
    private val tmplPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tmplBuf = FloatArray(256)

    private fun ensureBuf(n: Int): FloatArray {
        if (tmplBuf.size < n) tmplBuf = FloatArray(max(n, tmplBuf.size * 2))
        return tmplBuf
    }

    /**
     * 画视口里那几页：纸色底 → 模板线 / 点（裁在页内）→ 一圈淡描边。模板几何按「模板@宽×高」缓存
     * （整本同尺寸，几种模板就几份）。线宽细 1 / 粗 1.5 画布点随缩放（有下限，缩小时不至于看不见），
     * 颜色由纸色明度推：细 α0.14、粗 α0.30、点 α0.30（三端契约 §9.3）。
     */
    private fun drawPages(c: Canvas) {
        val l = pageLayout ?: return
        val z = zoom
        val s = z * density
        val first = l.indexForY(oy)
        val last = l.indexForY(oy + viewHdp() / z)
        for (i in first..max(first, last)) {
            if (i < 0 || i >= l.count) continue
            val left = (l.originX() - ox) * s
            val top = (l.originY(i) - oy) * s
            val right = left + l.width * s
            val bottom = top + l.height * s
            if (right < 0 || bottom < 0 || left > width || top > height) continue
            pageFill.style = Paint.Style.FILL
            pageFill.color = bgColor
            c.drawRect(left, top, right, bottom, pageFill)
            val t = pageTemplates.getOrElse(i) { BoardPaging.T_BLANK }
            if (t != BoardPaging.T_BLANK) {
                val key = "$t@${l.width}x${l.height}"
                val shape = shapeCache.getOrPut(key) { BoardPaging.shape(t, l.width, l.height) }
                c.save()
                c.clipRect(left, top, right, bottom)
                tmplPaint.style = Paint.Style.STROKE
                tmplPaint.strokeCap = Paint.Cap.BUTT
                if (shape.thin.isNotEmpty()) {
                    tmplPaint.strokeWidth = max(0.5f, 1f * z) * density
                    tmplPaint.color = withAlpha(inkColor, 0.14f)
                    drawSegs(c, shape.thin, left, top, s)
                }
                if (shape.bold.isNotEmpty()) {
                    tmplPaint.strokeWidth = max(0.75f, 1.5f * z) * density
                    tmplPaint.color = withAlpha(inkColor, 0.30f)
                    drawSegs(c, shape.bold, left, top, s)
                }
                if (shape.dots.isNotEmpty()) {
                    // 方点（同 Mac addRect / 草稿纸点阵的理由：一页上千个点，方点最省）
                    tmplPaint.strokeWidth = max(1f, BoardPaging.DOT_SIZE * z) * density
                    tmplPaint.strokeCap = Paint.Cap.SQUARE
                    tmplPaint.color = withAlpha(inkColor, 0.30f)
                    val src = shape.dots
                    val buf = ensureBuf(src.size)
                    var k = 0
                    while (k < src.size) {
                        buf[k] = left + src[k] * s
                        buf[k + 1] = top + src[k + 1] * s
                        k += 2
                    }
                    c.drawPoints(buf, 0, src.size, tmplPaint)
                }
                c.restore()
            }
            // 页边一圈淡描边（白页压浅色底也分得出页在哪；同 Mac 灰 0.5 α0.35）
            pageFill.style = Paint.Style.STROKE
            pageFill.strokeWidth = density
            pageFill.color = Color.argb(89, 128, 128, 128)
            val h = density / 2f
            c.drawRect(left + h, top + h, right - h, bottom - h, pageFill)
        }
    }

    private fun drawSegs(c: Canvas, src: FloatArray, left: Float, top: Float, s: Float) {
        val buf = ensureBuf(src.size)
        var k = 0
        while (k < src.size) {
            buf[k] = left + src[k] * s
            buf[k + 1] = top + src[k + 1] * s
            buf[k + 2] = left + src[k + 2] * s
            buf[k + 3] = top + src[k + 3] * s
            k += 4
        }
        c.drawLines(buf, 0, src.size, tmplPaint)
    }

    /** 上拉加页的提示：拉过一小段才出现，加完这一页就收（同 Mac 的 pullHint，画在底部页外那段） */
    private fun drawPullHint(c: Canvas) {
        val text = pullHintText ?: return
        if (pullFired || pullOver <= PULL_HINT_AFTER) return
        hintPaint.color = Ui.onSurface(context)
        hintPaint.isFakeBoldText = false
        hintPaint.textSize = dp(14f)
        c.drawText(text, width / 2f, height - dp(22f), hintPaint)
    }

    /**
     * 直接画成形笔迹（块还没出来时的退路，也就是改之前的画法）：[r] = 只画这片屏幕矩形，null = 整个视口。
     * 视口外的笔迹按缓存的包围盒裁掉（同 web 的 boxHits）。
     */
    private val drawDirect: (Canvas, RectF?) -> Unit = { c, r ->
        val z = zoom
        val k = z * density
        val x0: Float; val y0: Float; val x1: Float; val y1: Float
        if (r != null) {
            c.save()
            c.clipRect(r)
            x0 = ox + r.left / k; y0 = oy + r.top / k
            x1 = ox + r.right / k; y1 = oy + r.bottom / k
        } else {
            x0 = ox; y0 = oy
            x1 = ox + viewWdp() / z; y1 = oy + viewHdp() / z
        }
        for (s in strokes) {
            val b = tiles.box(s)
            if (b[2] < x0 || b[0] > x1 || b[3] < y0 || b[1] > y1) continue
            ink.drawScratchStroke(c, s, ox, oy, z)
        }
        if (r != null) c.restore()
    }

    /** 擦除粗筛：橡皮圆与这条笔迹的包围盒（已含线宽余量）挨不挨得上 */
    private fun near(s: Stroke, cx: Float, cy: Float, r: Float): Boolean {
        val b = tiles.box(s)
        return cx >= b[0] - r && cx <= b[2] + r && cy >= b[1] - r && cy <= b[3] + r
    }

    /**
     * 底纹（点阵 / 小格）+ 原点十字。无限画布的**定位参照**：纯白纸平移时看不出自己在动。
     * 数值全部是三端契约（[ScratchGeom]，handoff §4.2 的表）；墨色由纸色明度推，不跟系统外观走。
     */
    private fun drawPattern(c: Canvas) {
        if (pattern == "plain") return   // 纯色纸：连原点十字都不画
        val z = zoom
        val st = ScratchGeom.gridStep(z)
        val stepPx = st * z * density
        if (stepPx <= 0f) return
        val W = width.toFloat()
        val H = height.toFloat()
        val x0 = floor(ox / st) * st
        val y0 = floor(oy / st) * st
        val cols = (W / stepPx).toInt() + 2
        val rows = (H / stepPx).toInt() + 2
        if (cols <= 0 || rows <= 0 || cols * rows > ScratchGeom.GRID_MAX_CELLS) return   // 安全阀（同 Mac/web）
        if (pattern == "grid") {
            // 小格：横竖各一组细线。线比点更「有格子感」但也更抢戏，故比点阵再淡一档（0.085）
            patternPaint.style = Paint.Style.STROKE
            patternPaint.strokeWidth = 1f
            patternPaint.color = withAlpha(inkColor, ScratchGeom.GRID_ALPHA)
            miniPath.reset()
            for (i in 0..cols) {
                val x = (x0 + i * st - ox) * z * density
                miniPath.moveTo(x, 0f); miniPath.lineTo(x, H)
            }
            for (j in 0..rows) {
                val y = (y0 + j * st - oy) * z * density
                miniPath.moveTo(0f, y); miniPath.lineTo(W, y)
            }
            c.drawPath(miniPath, patternPaint)
        } else {
            // 方点不用圆点：1.5~3px 上两者肉眼无差，drawRect 比 drawCircle 便宜得多——
            // 这层每帧平移都要重画，一屏上万个点，圆点的代价是白花的（同 Mac/web）
            val d = ScratchGeom.dotSize(z) * density
            val half = d / 2f
            patternPaint.style = Paint.Style.FILL
            patternPaint.color = withAlpha(inkColor, ScratchGeom.DOT_ALPHA)
            for (i in 0..cols) {
                val x = (x0 + i * st - ox) * z * density
                for (j in 0..rows) {
                    val y = (y0 + j * st - oy) * z * density
                    c.drawRect(x - half, y - half, x + half, y + half, patternPaint)
                }
            }
        }
        // 原点十字（画布 0,0）＝ 这张纸创建的位置，也是「回中」的落点
        val gx = -ox * z * density
        val gy = -oy * z * density
        val r = dp(ScratchGeom.CROSS_HALF)
        if (gx > -40 && gx < W + 40 && gy > -40 && gy < H + 40) {
            patternPaint.style = Paint.Style.STROKE
            patternPaint.strokeWidth = 1f
            patternPaint.color = withAlpha(inkColor, ScratchGeom.CROSS_ALPHA)
            c.drawLine(gx - r, gy, gx + r, gy, patternPaint)
            c.drawLine(gx, gy - r, gx, gy + r, patternPaint)
        }
    }

    private fun withAlpha(color: Int, a: Float): Int =
        Color.argb((a * 255f).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun drawEmptyHint(c: Canvas) {
        hintPaint.color = withAlpha(inkColor, 0.28f)
        hintPaint.isFakeBoldText = true
        hintPaint.textSize = dp(17f)
        val cx = width / 2f
        val cy = height / 2f
        c.drawText(emptyHintTitle ?: "空白草稿纸", cx, cy - dp(8f), hintPaint)
        hintPaint.isFakeBoldText = false
        hintPaint.textSize = dp(13f)
        c.drawText("用笔书写 · 单指平移 · 双指捏合缩放", cx, cy + dp(16f), hintPaint)
    }

    // ---------- minimap（右下角小窗：笔迹骨架线 + 视口框，点/拖即跳） ----------

    private fun layoutMinimap() {
        val w = dp(MINI_W)
        val h = dp(MINI_H)
        val m = dp(MINI_PAD)
        miniRect.set(width - m - w, height - m - h, width - m, height - m)
    }

    private fun inMinimap(x: Float, y: Float): Boolean {
        layoutMinimap()
        return miniRect.contains(x, y)
    }

    /** minimap 的等比映射：内容 ∪ 视口外扩 8%，面板内留 inset 内边距（内容与视口框都不许贴边） */
    private class MiniFit(val wx: Float, val wy: Float, val s: Float, val ox: Float, val oy: Float)

    private fun miniFit(): MiniFit {
        val z = zoom
        val visW = viewWdp() / z
        val visH = viewHdp() / z
        var x0 = ox
        var y0 = oy
        var x1 = ox + visW
        var y1 = oy + visH
        contentBounds()?.let { b ->
            x0 = min(x0, b[0]); y0 = min(y0, b[1])
            x1 = max(x1, b[0] + b[2]); y1 = max(y1, b[1] + b[3])
        }
        val padX = (x1 - x0) * 0.08f
        val padY = (y1 - y0) * 0.08f
        x0 -= padX; y0 -= padY; x1 += padX; y1 += padY
        val ww = max(1f, x1 - x0)
        val wh = max(1f, y1 - y0)
        val inset = MINI_INSET
        val iw = max(1f, MINI_W - inset * 2)
        val ih = max(1f, MINI_H - inset * 2)
        val s = min(iw / ww, ih / wh)
        return MiniFit(x0, y0, s, inset + (iw - ww * s) / 2, inset + (ih - wh * s) / 2)
    }

    /** 点/拖 minimap → 视口中心跳到对应画布位置（同 web `padMiniJump` / Mac `onJump`） */
    private fun miniJump(x: Float, y: Float) {
        layoutMinimap()
        val f = miniFit()
        if (f.s <= 0f) return
        val cx = f.wx + (x - miniRect.left - dp(f.ox)) / (f.s * density)
        val cy = f.wy + (y - miniRect.top - dp(f.oy)) / (f.s * density)
        ox = cx - viewWdp() / (2 * zoom)
        oy = cy - viewHdp() / (2 * zoom)
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    // minimap 的笔迹骨架：画布坐标下的一整条折线，笔迹变了才重建；画的时候按当前映射缩放过去
    // （以前每帧逐点重新拼，笔迹多了光这一块就要几十毫秒）
    private val miniSkel = Path()
    private var miniSkelOk = false

    private fun buildMiniSkel() {
        miniSkel.reset()
        val b = strokeBounds() ?: return
        // 抽稀：相邻点近于「内容铺满小窗时的半个像素」就跳过（小窗里本来就分不出来）
        val tol = max(b[2], b[3]) / 400f
        val tol2 = tol * tol
        for (s in strokes) {
            val pts = s.pts
            if (pts.size < 2) continue
            var lx = pts[0].x
            var ly = pts[0].y
            miniSkel.moveTo(lx, ly)
            for (i in 1 until pts.size) {
                val p = pts[i]
                val dx = p.x - lx
                val dy = p.y - ly
                if (i == pts.size - 1 || dx * dx + dy * dy >= tol2) {
                    miniSkel.lineTo(p.x, p.y)
                    lx = p.x; ly = p.y
                }
            }
        }
    }

    private fun drawMinimap(c: Canvas) {
        layoutMinimap()
        val f = miniFit()
        val cr = dp(10f)
        // 面板：深色圆角底（纸色是浅色系，深底上白骨架线任何纸色下都可读，同 web 的选择）
        miniPaint.style = Paint.Style.FILL
        miniPaint.color = Color.argb(184, 20, 23, 28)
        c.drawRoundRect(miniRect, cr, cr, miniPaint)
        c.save()
        c.clipRect(miniRect)
        fun mx(x: Float) = miniRect.left + dp(f.ox) + (x - f.wx) * f.s * density
        fun my(y: Float) = miniRect.top + dp(f.oy) + (y - f.wy) * f.s * density
        // 页面底图：只画一个淡框（缩略图里塞整页图既贵又看不清，框足以说明「页在这儿」；同 Mac/web）
        pageUnderRect?.let { pr ->
            val l = mx(pr[0])
            val t = my(pr[1])
            val rr = mx(pr[0] + pr[2])
            val bb = my(pr[1] + pr[3])
            miniPaint.style = Paint.Style.FILL
            miniPaint.color = Color.argb(26, 255, 255, 255)
            c.drawRect(l, t, rr, bb, miniPaint)
            miniPaint.style = Paint.Style.STROKE
            miniPaint.strokeWidth = 1f
            miniPaint.color = Color.argb(115, 255, 255, 255)
            c.drawRect(l, t, rr, bb, miniPaint)
        }
        // 画板笔记的图：同样只画淡框（同页面底图的理由）
        for (p in pics) {
            val l = mx(p.x)
            val t = my(p.y)
            val rr = mx(p.x + p.w)
            val bb = my(p.y + p.h)
            miniPaint.style = Paint.Style.FILL
            miniPaint.color = Color.argb(26, 255, 255, 255)
            c.drawRect(l, t, rr, bb, miniPaint)
            miniPaint.style = Paint.Style.STROKE
            miniPaint.strokeWidth = 1f
            miniPaint.color = Color.argb(115, 255, 255, 255)
            c.drawRect(l, t, rr, bb, miniPaint)
        }
        // 骨架线即可（minimap 不必还原笔型/压感，1px 折线最省也最清楚）
        miniPaint.style = Paint.Style.STROKE
        miniPaint.strokeWidth = 1f
        miniPaint.color = Color.argb(179, 255, 255, 255)
        if (!miniSkelOk) { buildMiniSkel(); miniSkelOk = true }
        val ks = f.s * density
        c.save()
        c.translate(mx(0f), my(0f))
        c.scale(ks, ks)
        miniPaint.strokeWidth = 0f   // 细线：缩放后仍是 1 像素
        c.drawPath(miniSkel, miniPaint)
        c.restore()
        // 当前视口框：淡填充 + 细描边——重实线会比笔迹还抢戏（Mac 样张踩过的坑）
        val z = zoom
        val vx = mx(ox)
        val vy = my(oy)
        val vw = max(dp(6f), viewWdp() / z * f.s * density)
        val vh = max(dp(6f), viewHdp() / z * f.s * density)
        val accent = Ui.accent(context)
        miniPaint.style = Paint.Style.FILL
        miniPaint.color = withAlpha(accent, 0.10f)
        c.drawRect(vx, vy, vx + vw, vy + vh, miniPaint)
        miniPaint.style = Paint.Style.STROKE
        miniPaint.strokeWidth = 1f
        miniPaint.color = withAlpha(accent, 0.85f)
        c.drawRect(vx, vy, vx + vw, vy + vh, miniPaint)
        c.restore()
        miniPaint.style = Paint.Style.STROKE
        miniPaint.strokeWidth = 1f
        miniPaint.color = Color.argb(56, 255, 255, 255)
        c.drawRoundRect(miniRect, cr, cr, miniPaint)
    }

    companion object {
        // minimap 尺寸（dp；同 web 的 150×108 —— 同一个设备形态上已经做过的答案）
        const val MINI_W = 150f
        const val MINI_H = 108f
        const val MINI_PAD = 12f
        /** 面板内边距：缩略内容与视口框都不许贴到圆角边框上（同 Mac 的 inset=9） */
        const val MINI_INSET = 9f

        /** 分页：页顶上方让出的那段（dp，给浮在顶上的工具条；同 Mac 的 topInset + 56 里那 56） */
        const val PAGED_TOP = 56f

        /** 分页：页两侧 / 末页之下的边距（dp；末页之下留两倍，上拉提示就在那里） */
        const val PAGED_MARGIN = 24f

        /** 到底后继续上拉多远（dp）加一页（同 Mac pullThreshold 110 屏幕点） */
        const val PULL_THRESHOLD = 110f

        /** 拉过多远才出提示（dp；同 Mac 的 12） */
        const val PULL_HINT_AFTER = 12f

        /** 页面底图取不到时的重试次数上限（无限重试 = 每帧一次网络请求） */
        const val MAX_PAGE_RETRY = 3

        /** 画板图片解码的长边上限（px）：照片原图动辄 4000px+，整张解进内存就是几十 MB 一张 */
        const val PIC_MAX_SIDE = 2048

        /**
         * 画板图片解码（两模式共用：模式1 读本机文件、模式2 读 HTTP 回来的字节）。
         * 先只读尺寸，按 2 的幂降采样到长边不超过 [PIC_MAX_SIDE]。解不开返回 null。
         */
        fun decodePic(bytes: ByteArray): Bitmap? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var sample = 1
            while (max(o.outWidth, o.outHeight) / sample > PIC_MAX_SIDE) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            return try {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (e: OutOfMemoryError) {
                null
            }
        }
    }
}
