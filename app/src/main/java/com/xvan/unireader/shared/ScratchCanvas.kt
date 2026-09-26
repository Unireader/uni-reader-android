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
 * 模式2 将来接线协议，流式钩子（[onStrokeBegin]/[onStrokeMove]/[onEraseAt]）已经留好。
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
     * @param inkTool 笔落下写字（笔记模式）；false = 平移（翻页/框选/文字笔记模式在纸上
     *   都退化为平移——画布没有「页」也没有页内框选/文字注解，同 web 的退化分支）
     * @param eraseTool 笔落下擦除
     * @param eraserSize 页宽归一化的橡皮半径；画布半径 = eraserSize × [ScratchGeom.ERASER_REF_W]
     */
    class Tools(
        val inkTool: Boolean,
        val eraseTool: Boolean,
        val pen: Pen,
        val eraserSize: Float,
        val eraserMode: Int,     // 0=整笔 1=局部
        val eraserRing: Boolean,
        val rulerOn: Boolean,
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

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val ink = InkRenderer(density)
    private val overlays = PadOverlays(density)

    // ---- 纸样与笔迹（宿主注入） ----
    private var bgColor = Color.WHITE
    private var inkColor = Color.BLACK   // 底纹/提示的墨色：由纸色明度推，不跟系统深浅外观走（§4.2 🔴）
    private var pattern = "dots"
    private val strokes = ArrayList<Stroke>()

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

    /** 空白纸的引导标题（宿主可换：画板笔记说「空白画板」）；null = 草稿纸的默认说法 */
    var emptyHintTitle: String? = null

    /**
     * 真源回推：整表替换（正在写的这一笔不受影响——它还没进 [strokes]）。
     *
     * @param keep 真源里**还没有**的乐观笔迹（`ackRel` 还没追上），原样接在末尾——不然「活体层已清、
     *   回推还没到」之间会露出空窗，肉眼就是上一笔闪一下。判据在 `PadScratch.applyStrokes`。
     */
    fun setStrokes(list: List<Stroke>, keep: List<Stroke> = emptyList()) {
        strokes.clear()
        strokes.addAll(list)
        strokes.addAll(keep)
        clampViewport()
        invalidate()
    }

    /** 按本地 id 找一条已落画布的笔迹（乐观笔迹认领用；找不到 = 已被本地擦除/切段） */
    fun strokeById(id: String): Stroke? = strokes.firstOrNull { it.id == id }

    /** 收笔后宿主生成的带 id 笔迹并进画布（乐观落地，同 `LocalCanvasView.onInkEnd` 的套路） */
    fun addCommitted(s: Stroke) {
        strokes.add(s)
        invalidate()
    }

    /** 落库失败撤销乐观落地（宁可这一笔消失，也不假装存住了） */
    fun removeStroke(id: String) {
        if (strokes.removeAll { it.id == id }) invalidate()
    }

    /** 打开一张纸：丢掉上一张的全部状态（含活体半笔与几何缓存）并回中 */
    fun openSession() {
        strokes.clear()
        clearLive()
        ink.clearCache()
        placed = false
        recenter()
    }

    /** 回中 = 画布原点回视口正中 + zoom 复位 1（「打开后从该处显示」的落点） */
    fun recenter() {
        if (width > 0 && height > 0) {
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
        val f = ScratchGeom.fit(contentBounds(), viewWdp(), viewHdp())
        if (f == null) {
            recenter()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!placed) {
            if (w > 0 && h > 0) recenter()   // 首次拿到真实尺寸才回中（之前算的都是假的）
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
        val base = ScratchGeom.contentBounds(strokes, livePts.ifEmpty { null }, pageUnderRect)
        if (pics.isEmpty()) return base
        var x0 = base?.get(0) ?: Float.MAX_VALUE
        var y0 = base?.get(1) ?: Float.MAX_VALUE
        var x1 = if (base != null) base[0] + base[2] else -Float.MAX_VALUE
        var y1 = if (base != null) base[1] + base[3] else -Float.MAX_VALUE
        for (p in pics) {
            x0 = min(x0, p.x); y0 = min(y0, p.y)
            x1 = max(x1, p.x + p.w); y1 = max(y1, p.y + p.h)
        }
        return floatArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    private fun clampViewport() {
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
    private var penKind = 0        // 0=无 1=落墨 2=擦除 3=平移 4=minimap 拖动
    private var penX = 0f
    private var penY = 0f

    private class Finger(var x: Float, var y: Float)
    private val touches = HashMap<Int, Finger>()
    private val touchOrder = ArrayList<Int>()
    private var panId = -1
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var pinching = false
    private var pinchD0 = 0f
    private var pinchZ0 = 1f
    private var pinchMx = 0f   // 上一帧两指中点（dp）——双指整体挪动 = 平移，见 [pinchMove]
    private var pinchMy = 0f
    private var miniDrag = false   // 手指正在 minimap 上点/拖（panId = 那根手指）

    private val palmPx = dp(PadConst.PALM)
    private val miniRect = RectF()

    /** 橡皮圆环（视口 px 坐标；ringR 是 px 半径） */
    private var ringOn = false
    private var ringX = 0f
    private var ringY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) {
                    fingerDown(e.getPointerId(0), e.getX(0), e.getY(0), e.getTouchMajor(0))
                } else {
                    stylusDown(e, 0)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                if (e.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER) {
                    fingerDown(e.getPointerId(idx), e.getX(idx), e.getY(idx), e.getTouchMajor(idx))
                } else if (!penActive) {
                    stylusDown(e, idx)   // 笔优先（stylusDown 内清掉手指状态）
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penActive) {
                    val pi = e.findPointerIndex(penId)
                    if (pi >= 0) {
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
                        panId >= 0 && !twoFingerScroll -> {
                            val pi = e.findPointerIndex(panId)
                            if (pi >= 0) panTo(e.getX(pi), e.getY(pi))
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (penActive && id == penId) stylusUp()
                else endTouch(id)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (penActive) {
                    stylusUp()
                }
                touches.clear(); touchOrder.clear()
                pinching = false; panId = -1; miniDrag = false
            }
        }
        return true
    }

    // ---- 手指：单指平移、双指捏合（作用在草稿纸视口上，与页内同款只是目标不同） ----

    private fun fingerDown(id: Int, x: Float, y: Float, touchMajor: Float) {
        if (penActive) return            // 笔在写 → 忽略手掌/手指
        if (touchMajor > palmPx) return  // 大面积接触（手掌）忽略
        touches[id] = Finger(x, y)
        if (id !in touchOrder) touchOrder.add(id)
        if (touchOrder.size >= 2) {
            val a = touches[touchOrder[0]] ?: return
            val b = touches[touchOrder[1]] ?: return
            pinching = true
            miniDrag = false   // 第二指落下：minimap 拖动让给捏合
            pinchD0 = max(dp(40f), hypot(a.x - b.x, a.y - b.y))   // 下限避免起手过近灵敏度爆炸
            pinchZ0 = zoom
            pinchMx = (a.x + b.x) / 2f / density
            pinchMy = (a.y + b.y) / 2f / density
            panId = -1
        } else if (minimapOn && hasContent() && inMinimap(x, y)) {
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
            touchOrder.isEmpty() -> panId = -1
            else -> {
                val a = touches[touchOrder[0]] ?: return
                val b = touches[touchOrder[1]] ?: return
                pinching = true
                pinchD0 = max(dp(40f), hypot(a.x - b.x, a.y - b.y))
                pinchZ0 = zoom
                pinchMx = (a.x + b.x) / 2f / density
                pinchMy = (a.y + b.y) / 2f / density
                panId = -1
            }
        }
    }

    /** 单指平移：屏幕拖多少视口移多少（注意方向：手指右拖 = 看左边的内容，origin 减） */
    private fun panTo(x: Float, y: Float) {
        ox += (lastPanX - x) / density / zoom
        oy += (lastPanY - y) / density / zoom
        lastPanX = x; lastPanY = y
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    /** 双指捏合：以两指中点为锚缩放，中点移动跟着平移（与页内 pinchMove 同款跟手） */
    private fun pinchMove() {
        val a = touches[touchOrder[0]] ?: return
        val b = touches[touchOrder[1]] ?: return
        val d = hypot(a.x - b.x, a.y - b.y)
        val mx = (a.x + b.x) / 2f / density
        val my = (a.y + b.y) / 2f / density
        val v = ScratchGeom.zoomAt(ox, oy, zoom, (pinchZ0 * d / pinchD0) / zoom, mx, my)
        ox = v[0]; oy = v[1]; zoom = v[2]
        // 中点整体挪动 = 平移。缩放锚点只保证「中点底下那一点不动」，两指齐挪时 factor≈1、
        // zoomAt 原地返回，光靠它双指是拖不动纸的——双指滚动模式下就等于纸钉死了。
        ox -= (mx - pinchMx) / zoom
        oy -= (my - pinchMy) / zoom
        pinchMx = mx
        pinchMy = my
        clampViewport()
        invalidate()
        onViewportChanged?.invoke()
    }

    // ---- 笔：落墨 / 擦除；翻页·框选·文字笔记模式退化为平移；minimap 可点可拖 ----

    private fun stylusDown(e: MotionEvent, idx: Int) {
        val x = e.getX(idx)
        val y = e.getY(idx)
        // 笔优先：清掉进行中的手指平移/捏合
        touches.clear(); touchOrder.clear(); pinching = false; panId = -1; miniDrag = false
        penActive = true
        penId = e.getPointerId(idx)
        penX = x; penY = y
        if (minimapOn && hasContent() && inMinimap(x, y)) {
            penKind = 4
            miniJump(x, y)
            return
        }
        val t = curTools()
        val c = toCanvas(x, y)
        when {
            t.eraseTool -> {
                penKind = 2
                eraseAt(c[0], c[1])
                onEraseAt?.invoke(c[0], c[1])
                if (t.eraserRing) { ringX = x; ringY = y; ringOn = true }
            }
            t.inkTool -> {
                penKind = 1
                livePen = t.pen
                liveLine = t.rulerOn   // 尺子按落笔那一刻锁进这一笔（同 PageCanvasView）
                livePts.clear()
                livePts.add(Pt3(c[0], c[1], e.getPressure(idx)))
                linePress = livePts[0].p   // 尺子笔的峰值压感起点，见 stylusMove 的尺子分支
                onStrokeBegin?.invoke(t.pen, livePts[0], liveLine)
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
            3 -> {
                ox += (penX - x) / density / zoom
                oy += (penY - y) / density / zoom
                penX = x; penY = y
                clampViewport()
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
        var changed = false
        if (t.eraserMode == 0) {
            var i = strokes.size - 1
            while (i >= 0) {
                val s = strokes[i]
                val hit = s.pts.any { val dx = it.x - cx; val dy = it.y - cy; dx * dx + dy * dy <= r2 }
                if (hit) { strokes.removeAt(i); changed = true }
                i--
            }
        } else {
            val out = ArrayList<Stroke>(strokes.size)
            for (s in strokes) {
                // 草稿纸笔迹 page 恒 0（splitStroke 的页过滤因此恒过，坐标系无关）
                val segs = InkEdit.splitStroke(s, cx, cy, 0, r2)
                if (segs.size == 1 && segs[0] === s) out.add(s) else { out.addAll(segs); changed = true }
            }
            if (changed) {
                strokes.clear()
                strokes.addAll(out)
            }
        }
        if (changed) invalidate()
    }

    // ---- 悬停：擦除模式下显示橡皮尺寸圆环（同页内 hover 的口径） ----

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
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
        strokes.isNotEmpty() || livePts.isNotEmpty() || pageUnderRect != null || pics.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        drawPattern(canvas)
        drawPageUnder(canvas)   // 底纹之上、笔迹之下（页图只是参照物，墨永远在最上面）
        drawPics(canvas)        // 画板笔记的图：同样在笔迹之下（笔迹永远能写在图上）
        // 视口外的笔迹裁掉（画布是无界的一大坨，不裁就是每帧把整张纸重画一遍；同 web 的 boxHits）
        val z = zoom
        val x0 = ox
        val y0 = oy
        val x1 = ox + viewWdp() / z
        val y1 = oy + viewHdp() / z
        ink.deferRebuild = pinching   // 捏合中别重建几何，canvas 缩放顶一拍（同页内）
        for (s in strokes) {
            if (!boxHits(s, x0, y0, x1, y1)) continue
            ink.drawScratchStroke(canvas, s, ox, oy, z)
        }
        livePen?.let { ink.drawScratchLive(canvas, it, livePts, ox, oy, z) }
        // 空白纸的引导（有笔迹后自动消失；同 Mac 的 emptyHint）
        if (!hasContent()) drawEmptyHint(canvas)
        // 橡皮尺寸圆环：半径 = eraserSize × 800 × zoom（三端同一条换算）
        if (ringOn) {
            val t = curTools()
            overlays.drawEraserRing(canvas, ringX, ringY, t.eraserSize * ScratchGeom.ERASER_REF_W * z * density)
        }
        if (minimapOn && hasContent()) drawMinimap(canvas)
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

    /** 粗筛：这条笔迹的包围盒与可视画布矩形有没有交集（逐点算一遍比重画便宜得多，同 web boxHits） */
    private fun boxHits(s: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        var a0 = Float.MAX_VALUE; var b0 = Float.MAX_VALUE
        var a1 = -Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
        for (p in s.pts) {
            if (p.x < a0) a0 = p.x
            if (p.x > a1) a1 = p.x
            if (p.y < b0) b0 = p.y
            if (p.y > b1) b1 = p.y
        }
        val m = s.pen.w + 4   // 线宽余量，免得贴边的粗笔被切掉（同 web）
        return a1 + m >= x0 && a0 - m <= x1 && b1 + m >= y0 && b0 - m <= y1
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
        for (s in strokes) {
            if (s.pts.size < 2) continue
            miniPath.reset()
            miniPath.moveTo(mx(s.pts[0].x), my(s.pts[0].y))
            for (i in 1 until s.pts.size) miniPath.lineTo(mx(s.pts[i].x), my(s.pts[i].y))
            c.drawPath(miniPath, miniPaint)
        }
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
