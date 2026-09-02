package com.xvan.unireader.shared

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xvan.unireader.R
import kotlin.math.roundToInt

/**
 * 全 App 共用的设计系统（`ANDROID-STANDALONE-PLAN.md §4`）。
 *
 * **为什么是手搓而不是 Material 3 组件库**：四个 Activity 都继承 `android.app.Activity`
 * （不是 `AppCompatActivity`），而 `com.google.android.material` 既是一条新依赖、本机 gradle
 * 缓存里也没有——离线构建会当场断。所以走「框架主题 + `res/` 色板 + 这里的构件」，
 * 零新依赖，深浅色由 `values-night` 自动切。
 *
 * 三条硬规矩（对应用户对 UI 的一贯要求）：
 * 1. **扁平**：不许渐变、不许高光、不许投影。层次只靠 `surface` → `surface_container` → `outline`。
 * 2. **原生**：点按反馈一律用系统 [RippleDrawable]，弹层用系统 `PopupMenu`/`AlertDialog`，
 *    不自绘"看起来像原生"的东西。
 * 3. **颜色只走语义名**（[col]）。代码里再出现 `0xFF…` 的字面色，深色模式下就是一块亮斑。
 */
object Ui {

    // —— 尺寸 ——
    /** 图标按钮的**触摸**尺寸。48 是系统无障碍下限，别为了排得下调小；排不下就收进溢出菜单 */
    /**
     * 图标按钮的触摸目标与图标尺寸。
     *
     * **2026-09-02 用户要求整体收 15%**（48→41 / 22→19），此前是 48dp 的系统无障碍下限。
     * 之所以敢动：这个 App 的图标按钮全部密排在顶栏/浮条上，48dp 一排下来视觉上"胖得占地方"，
     * 而实际使用是**笔**或手指点在平板上、目标之间没有别的可点物，41dp 仍远大于指尖接触面。
     * 再往下就别调了——40dp 以下开始能感到点不准。改这两个数**全 App 的图标按钮一起变**
     * （顶栏、草稿纸浮条、参考窗、抽屉…都走 `iconButton`），这正是"整体缩小"的意思。
     */
    const val TOUCH = 41
    const val ICON = 19
    const val RADIUS = 12
    const val PILL = 999

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).roundToInt()
    fun dpf(c: Context, v: Float): Float = v * c.resources.displayMetrics.density

    // —— 颜色 ——

    /** 按语义名取色，自动跟随系统深浅色（见 `res/values{,-night}/colors.xml`） */
    fun col(c: Context, id: Int): Int = c.resources.getColor(id, c.theme)

    fun surface(c: Context) = col(c, R.color.surface)
    fun container(c: Context) = col(c, R.color.surface_container)
    fun onSurface(c: Context) = col(c, R.color.on_surface)
    fun onVariant(c: Context) = col(c, R.color.on_surface_variant)
    fun outline(c: Context) = col(c, R.color.outline)
    fun accent(c: Context) = col(c, R.color.accent)
    fun barOn(c: Context) = col(c, R.color.bar_on)

    /** 涟漪色：取前景色压到 ~12% 透明度，深浅两套都不用另配 */
    private fun ripple(c: Context, on: Int) = (on and 0x00FFFFFF) or 0x1F000000

    // —— 形状 ——

    fun round(color: Int, radiusDp: Int, c: Context, strokeColor: Int? = null) =
        GradientDrawable().apply {
            cornerRadius = dpf(c, radiusDp.toFloat())
            setColor(color)
            if (strokeColor != null) setStroke(dp(c, 1), strokeColor)
        }

    /**
     * 系统涟漪包一层底。[content] 为 null 时只有涟漪（透明按钮），
     * [mask] 决定涟漪的形状——不给 mask 的话涟漪会漫出圆角，看着像方块。
     */
    fun rippleOver(c: Context, content: android.graphics.drawable.Drawable?, radiusDp: Int, on: Int) =
        RippleDrawable(
            ColorStateList.valueOf(ripple(c, on)),
            content,
            round(Color.WHITE, radiusDp, c),
        )

    // —— 构件 ——

    /**
     * 图标按钮。[active] 为真时上一层 accent 底色 + accent 图标——这是"当前开着"的唯一表达，
     * 不再靠"尺子✓"这种给文案加勾（全图标之后没地方加勾了）。
     */
    fun iconButton(
        c: Context,
        icon: Int,
        desc: String,
        tint: Int = onSurface(c),
        onClick: () -> Unit,
    ): ImageButton = ImageButton(c).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(c, (TOUCH - ICON) / 2)
        setPadding(pad, pad, pad, pad)
        background = rippleOver(c, null, RADIUS, tint)
        contentDescription = desc          // 无障碍：全图标之后这是唯一的文字线索
        layoutParams = ViewGroup.LayoutParams(dp(c, TOUCH), dp(c, TOUCH))
        setOnClickListener { onClick() }
    }

    /**
     * 换背景但**保住内边距**。
     *
     * `View.setBackground()` 会调一次 `Drawable.getPadding()`，返回 true 就拿它**覆盖掉
     * View 自己的 padding**——`InsetDrawable` 正是这种（它把 inset 当 padding 报出去）。
     * 图标按钮的内容框全靠那 13dp padding 定死在 22dp；框子一被改大，`FIT_CENTER`
     * 当场把 24dp 的图标放大到新框里。
     *
     * 而且**回不来**：取消激活时换上的 `RippleDrawable` 的 `getPadding()` 返回 false，
     * View 不还原，于是胀完就一直胀着。2026-08 模拟器实测：尺子点一下 18.5 → 36.0 画布单位
     * （≈1.94 倍），再关掉仍是 34.9——顶栏上「点过的键比没点过的大一圈」就是这么来的。
     *
     * 所以**凡是给已经设过 padding 的 View 换背景，一律走这里**，别直接 `background =`。
     */
    fun View.setBackgroundKeepPadding(d: Drawable?) {
        val l = paddingLeft
        val t = paddingTop
        val r = paddingRight
        val b = paddingBottom
        background = d
        setPadding(l, t, r, b)
    }

    /** 切换图标按钮的开关态（底色 + 图标色一起变，见 [iconButton]） */
    fun ImageButton.setActive(active: Boolean, on: Int, accent: Int) {
        val c = context
        imageTintList = ColorStateList.valueOf(if (active) accent else on)
        val bg =
            if (active) rippleOver(c, round(col(c, R.color.accent_container), RADIUS, c), RADIUS, accent)
            else rippleOver(c, null, RADIUS, on)
        // 激活底色横向内缩 3dp：按钮本身是 48dp 满格排的（顶栏/草稿纸浮条都没有间距），
        // 两个相邻开关同时激活时底色会连成一整片、分不清是几个键。触摸区不变，只缩底色。
        // 必须走 setBackgroundKeepPadding：InsetDrawable 会把 inset 当 padding 顶掉按钮自己的
        // 13dp，图标当场胀成约 1.9 倍且再也缩不回去（详见那个方法的注释）。
        setBackgroundKeepPadding(if (active) InsetDrawable(bg, dp(c, 3), 0, dp(c, 3), 0) else bg)
    }

    /** Pen → ARGB（顶栏「切换笔」图标染色等「按笔色显示」的场合共用这一处换算） */
    fun penArgb(p: Pen): Int = Color.argb((p.a * 255f).roundToInt().coerceIn(0, 255), p.r, p.g, p.b)

    /** 文字按钮：填充式（主操作）或描边式（次要操作），扁平、圆角 12 */
    fun button(c: Context, label: String, filled: Boolean = false, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setTextColor(if (filled) col(c, R.color.on_accent) else onSurface(c))
            val h = dp(c, 14)
            setPadding(dp(c, 20), h, dp(c, 20), h)
            val face =
                if (filled) round(accent(c), RADIUS, c)
                else round(Color.TRANSPARENT, RADIUS, c, outline(c))
            background = rippleOver(c, face, RADIUS, if (filled) col(c, R.color.on_accent) else onSurface(c))
            setOnClickListener { onClick() }
        }

    /**
     * 弹层右下角那种**无边框**文字按钮。系统 `AlertDialog` 的按钮是全大写 + 主题色，
     * 这里统一成：主操作 accent、次操作次要色，都带涟漪，触摸高度仍够 48dp。
     */
    fun textButton(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = dp(c, TOUCH)
            setTextColor(if (primary) accent(c) else onVariant(c))
            setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 12))
            background = rippleOver(c, null, RADIUS, if (primary) accent(c) else onSurface(c))
            setOnClickListener { onClick() }
        }

    /** 弹层里的分组小标题（「笔宽」「橡皮」这种） */
    fun groupTitle(c: Context, s: String, top: Int = 16): TextView = TextView(c).apply {
        text = s
        textSize = 12f
        setTextColor(onVariant(c))
        setPadding(0, dp(c, top), 0, dp(c, 6))
    }

    /** 分组小标题：全大写太洋气，这里就是普通次要色小字 + 上方留白 */
    fun sectionTitle(c: Context, s: String): TextView = TextView(c).apply {
        text = s
        textSize = 13f
        setTextColor(onVariant(c))
        setPadding(0, dp(c, 24), 0, dp(c, 8))
    }

    fun title(c: Context, s: String, size: Float = 22f): TextView = TextView(c).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(onSurface(c))
    }

    fun body(c: Context, s: String, variant: Boolean = true): TextView = TextView(c).apply {
        text = s
        textSize = 14f
        setTextColor(if (variant) onVariant(c) else onSurface(c))
        setLineSpacing(dpf(c, 4f), 1f)
    }

    /**
     * 一句话提示：ⓘ + 一行小字。
     *
     * 界面上那些「为什么点不了 / 这一步要注意什么」的话**只配一行**——从前它们是三四行灰色
     * 长段落（权限说明、单写者警告、目录浏览器的口径），用户扫一眼就跳过，等于白写。
     * 规矩：正文一句话说完；真要展开的背景知识挂在 [onClick] 里，点了才弹。
     */
    fun tip(c: Context, text: String, onClick: (() -> Unit)? = null): LinearLayout =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            val v = dp(c, 8)
            setPadding(dp(c, 2), v, dp(c, 2), v)
            addView(
                ImageView(c).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = ColorStateList.valueOf(onVariant(c))
                },
                LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)).apply {
                    marginEnd = dp(c, 8)
                    topMargin = dp(c, 1)          // 与首行文字的视觉基线对齐
                },
            )
            addView(
                body(c, text).apply { textSize = 12f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                background = rippleOver(c, null, RADIUS, onSurface(c))
                setOnClickListener { onClick() }
            }
        }

    /** 卡片：一块 `surface_container` 圆角，无投影（投影是拟物，明令禁止） */
    fun card(c: Context, radiusDp: Int = 16): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(container(c), radiusDp, c)
        val p = dp(c, 16)
        setPadding(p, p, p, p)
    }

    /** 可点的整行（书库一条书、最近打开一条）：整行涟漪，不是行里塞个小按钮 */
    fun row(c: Context, onClick: (() -> Unit)? = null): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(c, 14)
        setPadding(p, p, p, p)
        if (onClick != null) {
            isClickable = true
            isFocusable = true
            background = rippleOver(c, null, RADIUS, onSurface(c))
            setOnClickListener { onClick() }
        }
    }

    /** 1px 分隔线。列表里用它而不是给每行画边框——边框会在圆角处露出接缝 */
    fun divider(c: Context): View = View(c).apply {
        setBackgroundColor(outline(c))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    fun spacer(c: Context, h: Int): View = View(c).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, h))
    }
}
