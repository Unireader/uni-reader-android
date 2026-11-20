package com.xvan.unireader.shared

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.xvan.unireader.R

/**
 * 标签页总览（用户 2026-11-20：手机上标签栏连一个标签都显示不全，要「浏览器那种页面卡片」）。
 *
 * 手机 / 窄分屏上 [DocTabsBar] 只显示当前那篇 + 标签数，点它们打开这里：整屏一格一格的卡片，
 * 每张 = 类型图标 + 标题 + 关闭键 + 一张缩略图（宿主截的那一篇最后的样子，没截过就是类型图标）。
 * 点卡片切过去、× 关掉、右上「+」再开一篇。两模式共用（模式2 的标签关不了，[closable] 恒假）。
 *
 * 覆盖层第一次显示时才挂到 Activity 的 `android.R.id.content` 上，盖住全部界面（含抽屉）；
 * 返回键由宿主转给 [DocTabsBar.closeOverview]。
 */
class TabOverview(private val a: Activity) {

    var onSelect: (Int) -> Unit = {}
    var onClose: (Int) -> Unit = {}
    var onAdd: () -> Unit = {}
    var closable: (Int) -> Boolean = { true }
    var thumbOf: (Int) -> Bitmap? = { null }

    private var tabs: List<DocTabsBar.TabInfo> = emptyList()
    private var active = -1

    private val titleView = Ui.title(a, "", 18f).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private val grid = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 16))
    }

    private val scroll = ScrollView(a).apply {
        isFillViewport = true
        addView(grid, FrameLayout.LayoutParams(-1, -2))
    }

    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        isClickable = true   // 挡住下面的画布
        setBackgroundColor(Ui.col(a, R.color.surface_dim))
        addView(
            LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(a, 16), Ui.dp(a, 6), Ui.dp(a, 6), Ui.dp(a, 6))
                addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    Ui.iconButton(a, R.drawable.ic_plus, a.getString(R.string.tabs_new)) {
                        close()
                        onAdd()
                    },
                )
                addView(Ui.textButton(a, a.getString(R.string.tabs_done), primary = true) { close() })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        onSystemInsets { left, top, right, bottom -> setPadding(left, top, right, bottom) }
    }

    val isOpen: Boolean get() = view.visibility == View.VISIBLE

    fun show(list: List<DocTabsBar.TabInfo>, activeIndex: Int) {
        if (view.parent == null) {
            a.findViewById<ViewGroup>(android.R.id.content)
                .addView(view, FrameLayout.LayoutParams(-1, -1))
            ViewCompat.requestApplyInsets(view)
        }
        view.visibility = View.VISIBLE
        view.bringToFront()
        update(list, activeIndex)
        // 当前那张滚进视野（标签多了它可能在下面）
        scroll.post {
            val row = grid.getChildAt(activeIndex / columns()) ?: return@post
            scroll.scrollTo(0, (row.top - Ui.dp(a, 12)).coerceAtLeast(0))
        }
    }

    /** 开着时标签变了（关掉一张、宿主换了标题）：整格重排 */
    fun update(list: List<DocTabsBar.TabInfo>, activeIndex: Int) {
        tabs = list
        active = activeIndex
        if (!isOpen) return
        titleView.text = a.getString(R.string.tabs_count, list.size)
        grid.removeAllViews()
        if (list.isEmpty()) {
            grid.addView(Ui.body(a, a.getString(R.string.tabs_none)).apply { setPadding(Ui.dp(a, 4), Ui.dp(a, 24), 0, 0) })
            return
        }
        val cols = columns()
        val gap = Ui.dp(a, 12)
        for (start in list.indices step cols) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val i = start + c
                val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { if (c > 0) marginStart = gap }
                row.addView(if (i < list.size) card(i, list[i]) else View(a), lp)
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        }
    }

    fun close() {
        if (isOpen) closedAt = android.os.SystemClock.uptimeMillis()
        view.visibility = View.GONE
    }

    /** 上次收起的时刻：收起后的头一两帧屏幕上还是总览，这时别截缩略图（[DocTabsBar.overviewOnScreen]） */
    var closedAt = 0L
        private set

    /** 两列；横屏手机 / 宽一点的分屏放三列 */
    private fun columns(): Int = if (a.resources.configuration.screenWidthDp >= 560) 3 else 2

    private fun card(i: Int, t: DocTabsBar.TabInfo): View {
        val on = i == active
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rippleOver(a, Ui.round(Ui.surface(a), Ui.RADIUS, a), Ui.RADIUS, Ui.onSurface(a))
            // 边框画在前景上：画在底色里会被铺满整宽的缩略图盖掉左、右、下三条边（2026-11-20 手机上实测）
            foreground = Ui.round(android.graphics.Color.TRANSPARENT, Ui.RADIUS, a).apply {
                if (on) setStroke(Ui.dp(a, 2), Ui.accent(a)) else setStroke(Ui.dp(a, 1), Ui.outline(a))
            }
            clipToOutline = true
            isClickable = true
            contentDescription = t.title
            setOnClickListener {
                close()
                onSelect(i)
            }
        }
        val fg = if (on) Ui.accent(a) else Ui.onSurface(a)
        // 头：图标 + 标题（+ 一行小字）+ ×
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(a, 10), Ui.dp(a, 6), if (closable(i)) 0 else Ui.dp(a, 10), Ui.dp(a, 6))
            addView(
                ImageView(a).apply {
                    setImageResource(t.icon)
                    imageTintList = ColorStateList.valueOf(fg)
                },
                LinearLayout.LayoutParams(Ui.dp(a, 16), Ui.dp(a, 16)).apply { marginEnd = Ui.dp(a, 8) },
            )
            addView(
                LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        // 两行：同一套书的标题常常只差末尾几个字（「…试题册」/「…解析册」），一行在两列卡片里分不出来
                        TextView(a).apply {
                            text = t.title
                            textSize = 13f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setTextColor(fg)
                        },
                    )
                    if (t.subtitle.isNotEmpty()) {
                        addView(
                            TextView(a).apply {
                                text = t.subtitle
                                textSize = 11f
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                setTextColor(Ui.onVariant(a))
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            if (closable(i)) {
                addView(
                    ImageButton(a).apply {
                        setImageResource(R.drawable.ic_close)
                        imageTintList = ColorStateList.valueOf(Ui.onVariant(a))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        val p = Ui.dp(a, 11)
                        setPadding(p, p, p, p)
                        background = Ui.rippleOver(a, null, Ui.PILL, Ui.onSurface(a))
                        contentDescription = a.getString(R.string.tabs_close)
                        setOnClickListener { onClose(i) }
                    },
                    LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)),
                )
            }
        }
        card.addView(head, LinearLayout.LayoutParams(-1, -2))
        card.addView(View(a).apply { setBackgroundColor(Ui.outline(a)) }, LinearLayout.LayoutParams(-1, 1))
        // 缩略图：宿主截的那一篇最后的样子（上对齐、按宽铺满）；没截过 = 大一号的类型图标
        val pic = AspectFrame(a, 1.25f).apply { setBackgroundColor(Ui.col(a, R.color.surface_dim)) }
        val bmp = thumbOf(i)
        if (bmp != null && !bmp.isRecycled) {
            pic.addView(TopFitImageView(a).apply { setImageBitmap(bmp) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            pic.addView(
                ImageView(a).apply {
                    setImageResource(t.icon)
                    imageTintList = ColorStateList.valueOf(Ui.onVariant(a))
                    alpha = 0.6f
                },
                FrameLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40), Gravity.CENTER),
            )
        }
        card.addView(pic, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    /** 高 = 宽 × [ratio] 的方框（卡片缩略图的固定比例） */
    private class AspectFrame(c: Context, private val ratio: Float) : FrameLayout(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = (w * ratio).toInt()
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    /** 按宽铺满、上对齐（截图的上半截最有用；居中裁会把标题那几行裁掉） */
    private class TopFitImageView(c: Context) : ImageView(c) {
        init {
            scaleType = ScaleType.MATRIX
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            fit()
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            fit()
        }

        private fun fit() {
            val d = drawable ?: return
            if (width <= 0 || d.intrinsicWidth <= 0) return
            val s = width.toFloat() / d.intrinsicWidth
            imageMatrix = Matrix().apply { setScale(s, s) }
        }
    }
}
