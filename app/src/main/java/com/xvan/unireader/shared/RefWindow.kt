package com.xvan.unireader.shared

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 参考窗：浮在阅读区之上的**只读** PDF 小窗（方案 `../../../../../../../REF-WINDOW-PLAN.md`）。
 *
 * 一句话定义：**打开另一本书摆在旁边对照，打开时定位到那本书的阅读进度**。
 * 没有笔迹、没有批注、没有选笔盘——用户 2026-08-30：「小窗没有任何附加功能」。
 *
 * 两模式共用，差异全在 [Host]：文档从哪来、页图从哪来、元信息从哪来。
 *  · 模式1 → 本机库 + 第二个 `PdfSource`；
 *  · 模式2 → Mac 的 `library` 镜像 + `PageFetcher(d=)` + `/docmeta?d=`。
 *
 * 🔴 **只读靠两件事**：① 页流固定在 [MODE_PAGE]（翻页模式，笔不落墨）；
 * ② 不覆写 `PageCanvasView` 的任何提交钩子——它们的默认实现就是空的，于是「提交给谁」这个
 * 问题在参考窗里根本不存在。这也是安卓端能直接复用整套页流的原因。
 *
 * 🔴 **位置/尺寸/看的哪本都是本端私有**（`SharedPreferences`），不落库、不上线。
 */
class RefWindow(private val ctx: Context, private val host: Host) {

    interface Host {
        /** 弹出「看哪本」的选择器（两模式各用自己的 Sheet 风格），选中回调库文档 id */
        fun refPickDoc(cb: (String) -> Unit)
        /** 打开某文档：主线程回调页尺寸表 + 进度 + 取图源；取不到给 null */
        fun refOpen(id: String, cb: (Info?) -> Unit)
        /** 关掉小窗：宿主该释放它给过的取图源（模式1 是第二个 Pdfium 实例，别吊着文件） */
        fun refRelease()
    }

    /** 一本参考文档的全部所需。`pages` = 逐页显示尺寸（pt），与页内笔迹同口径。 */
    data class Info(
        val title: String,
        val pages: List<Pair<Float, Float>>,
        val readPage: Int,
        val readFrac: Float,
        val source: PageImageSource,
    )

    private companion object {
        const val PREFS = "refwin"
        const val MIN_W = 200      // dp
        const val MIN_H = 180
        const val EDGE = 12        // 尺寸热区厚度
        const val BAR = 36
    }

    /** 挂到 Activity 根布局（`match_parent`）。空白处不消费触摸，阅读区照常。 */
    val view = FrameLayout(ctx)

    private val panel = FrameLayout(ctx)
    private val content = LinearLayout(ctx)
    private val canvas = RefCanvas(ctx)
    private val titleView = TextView(ctx)
    private val pageView = TextView(ctx)
    private val bubble: ImageButton
    private var docId = ""
    private var seedPage = 0
    private var seedFrac = 0f
    var isOpen = false
        private set
    private var collapsed = false

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 只读页流。**唯一覆写的钩子是页码回报**——其余（落笔/擦除/框选/长按）一律留默认空实现。 */
    private inner class RefCanvas(c: Context) : PageCanvasView(c) {
        override fun onScrollReport(page: Int, frac: Float) {
            pageView.text = if (canvasPageCount > 0) "${page + 1} / $canvasPageCount" else ""
        }
    }

    private var canvasPageCount = 0

    init {
        view.layoutParams = FrameLayout.LayoutParams(-1, -1)

        titleView.apply {
            textSize = 13f
            setTextColor(Ui.onSurface(ctx))
            maxLines = 1                                  // 🔴 绝不换行：一换行整条标题栏就被撑高
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(Ui.dp(ctx, 6), 0, Ui.dp(ctx, 4), 0)
            setOnClickListener { host.refPickDoc { id -> load(id) } }
        }
        pageView.apply {
            textSize = 12f
            setTextColor(Ui.onVariant(ctx))
            maxLines = 1
            setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.container(ctx))
            addView(Ui.iconButton(ctx, R.drawable.ic_book, "选择文档") {
                host.refPickDoc { id -> load(id) }
            })
            addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pageView)
            addView(Ui.iconButton(ctx, R.drawable.ic_scope, "回到进度") { rewind() })
            addView(Ui.iconButton(ctx, R.drawable.ic_chevron_down, "收起") { setCollapsed(true) })
            addView(Ui.iconButton(ctx, R.drawable.ic_close, "关闭") { close() })
        }
        attachDrag(bar)

        content.apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(ctx, BAR)))
            addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        panel.setBackgroundColor(Ui.surface(ctx))
        panel.addView(content, FrameLayout.LayoutParams(-1, -1))
        canvas.setMode(MODE_PAGE)        // 只读：笔在小窗里只翻页，不落墨
        canvas.setBarHeight(0f)          // 小窗里没有全局顶栏要让

        val w = prefs.getInt("w", 0).takeIf { it >= Ui.dp(ctx, MIN_W) }
            ?: min(Ui.dp(ctx, 420), (ctx.resources.displayMetrics.widthPixels * 0.45f).roundToInt())
        val h = prefs.getInt("h", 0).takeIf { it >= Ui.dp(ctx, MIN_H) }
            ?: (ctx.resources.displayMetrics.heightPixels * 0.55f).roundToInt()
        view.addView(panel, FrameLayout.LayoutParams(w, h, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = prefs.getInt("mx", Ui.dp(ctx, 12))
            bottomMargin = prefs.getInt("my", Ui.dp(ctx, 12))
        })

        // 尺寸手柄：左边 / 上边两条透明热区，叠在面板自己身上（右下角固定不动，同 Mac 与网页端；
        // 不画图标——系统窗口本来就是「边缘可拖、不画东西」）。
        addEdge(Ui.dp(ctx, EDGE), -1, Gravity.START, horizontal = true)
        addEdge(-1, Ui.dp(ctx, EDGE), Gravity.TOP, horizontal = false)

        bubble = Ui.iconButton(ctx, R.drawable.ic_doc, "参考窗") { setCollapsed(false) }.apply {
            background = Ui.rippleOver(ctx, Ui.round(Ui.container(ctx), 999, ctx), 999, Ui.onSurface(ctx))
        }
        view.addView(bubble, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = Ui.dp(ctx, 16); bottomMargin = Ui.dp(ctx, 16)
        })
        view.visibility = View.GONE
        apply()
    }

    // ---------- 开关 ----------

    fun toggle() { if (isOpen) close() else open() }

    fun open() {
        isOpen = true; collapsed = false
        view.visibility = View.VISIBLE
        apply()
        if (docId.isEmpty()) prefs.getString("doc", "")?.takeIf { it.isNotEmpty() }?.let { load(it) }
    }

    fun close() {
        isOpen = false
        view.visibility = View.GONE
        canvas.imageSource = null
        host.refRelease()          // 🔴 当场放掉取图源：模式1 那是第二个 Pdfium 实例（吊着文件）
    }

    private fun setCollapsed(v: Boolean) { collapsed = v; apply() }

    private fun apply() {
        panel.visibility = if (isOpen && !collapsed) View.VISIBLE else View.GONE
        bubble.visibility = if (isOpen && collapsed) View.VISIBLE else View.GONE
    }

    // ---------- 换书 / 定位 ----------

    private fun load(id: String) {
        if (id.isEmpty()) return
        host.refOpen(id) { info ->
            if (info == null) return@refOpen
            docId = id
            prefs.edit().putString("doc", id).apply()
            titleView.text = info.title
            seedPage = info.readPage
            seedFrac = info.readFrac
            canvasPageCount = info.pages.size
            canvas.imageSource = info.source
            canvas.setPages(info.pages.size, info.pages, true)
            // 🔴 定位必须等首次布局：`setPages` 之后视图往往还没量到真实宽度，那会儿滚过去
            // 等于滚到页顶（`PageCanvasView.onFirstGeometry` 注释里记的就是这笔账）。
            canvas.onFirstGeometry = { canvas.scrollToPageFrac(seedPage, seedFrac) }
            canvas.scrollToPageFrac(seedPage, seedFrac)
        }
    }

    private fun rewind() { canvas.scrollToPageFrac(seedPage, seedFrac) }

    // ---------- 摆位与改尺寸 ----------

    private fun attachDrag(bar: View) {
        var dx = 0f; var dy = 0f; var mx = 0; var my = 0
        bar.setOnTouchListener { _, e ->
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; mx = lp.rightMargin; my = lp.bottomMargin; true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 右下角为原点：手往左 = 右边距变大
                    lp.rightMargin = clampMargin(mx - (e.rawX - dx).roundToInt(), view.width - lp.width)
                    lp.bottomMargin = clampMargin(my - (e.rawY - dy).roundToInt(), view.height - lp.height)
                    panel.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("mx", lp.rightMargin).putInt("my", lp.bottomMargin).apply(); true
                }
                else -> false
            }
        }
    }

    private fun clampMargin(v: Int, limit: Int) = max(0, min(v, max(0, limit)))

    private fun addEdge(w: Int, h: Int, gravity: Int, horizontal: Boolean) {
        val edge = View(ctx)
        var dx = 0f; var dy = 0f; var w0 = 0; var h0 = 0
        edge.setOnTouchListener { _, e ->
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; w0 = lp.width; h0 = lp.height; true }
                MotionEvent.ACTION_MOVE -> {
                    // 往左上拖 = 变大（右下角固定不动）
                    if (horizontal) lp.width = max(Ui.dp(ctx, MIN_W), (w0 - (e.rawX - dx)).roundToInt())
                    else lp.height = max(Ui.dp(ctx, MIN_H), (h0 - (e.rawY - dy)).roundToInt())
                    panel.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("w", lp.width).putInt("h", lp.height).apply(); true
                }
                else -> false
            }
        }
        panel.addView(edge, FrameLayout.LayoutParams(w, h, gravity))
    }
}
