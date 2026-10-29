package com.xvan.unireader.shared

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.xvan.unireader.shared.exercise.MathText
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 笔记气泡的正文：**按 Markdown 渲染**（含 `$…$` / `$$…$$` 公式），对齐 Mac 气泡的只读 Markdown 引擎
 * （2026-09-13 起 Mac 是渲染后的样子，从前本端画的是源码，会露出 `**`、`#`）。两模式共用。
 *
 * 排版在一个**固定的参考字号**（[REF_FS]）下做，画的时候整体缩放到气泡字号：气泡宽和字号都与页宽成正比
 * （[NoteBubbleGeom]），所以「宽 ÷ 字号」不随缩放变，捏合缩放时同一份排版结果一直能用，不用每帧重排；
 * 公式插件的字号也是建的时候定死的，这样一种字号一份就够。
 *
 * 缓存按「正文 + 宽字比 + 最多行数」记；公式在后台排好后旧排版作废（公式图尺寸那时才知道），
 * 通知所有登记过的画布重画（[register]）。
 */
object NoteBubbleText {
    /** 参考字号（px）：排版都在这个字号下做 */
    const val REF_FS = 40f
    /** 正文颜色（气泡底色是固定的浅纸色，不跟深浅色走，见 `PadOverlays.drawNoteBubble`） */
    private val TEXT_COLOR = Color.rgb(31, 31, 33)
    /** 行距：Mac 气泡是字体行高 + 0.25×字号（`NoteBubble.Metrics.lineSpacing`） */
    private const val LINE_ADD = 0.25f

    private class Entry(val spanned: Spanned, val refW: Int, var layout: StaticLayout?)

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 200
    }
    private val views = WeakHashMap<View, Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = REF_FS
        color = TEXT_COLOR
    }

    /** 画气泡的画布登记一下：公式排好时要叫它重画 */
    fun register(v: View) { views[v] = Unit }

    private fun key(text: String, refW: Int, maxLines: Int) = "$refW|$maxLines|$text"

    /** 按参考字号排好的版（[contentW] / [fs] 是屏上的排版宽与字号） */
    fun layout(ctx: Context, text: String, contentW: Float, fs: Float, maxLines: Int): StaticLayout {
        val refW = (contentW / fs * REF_FS).roundToInt().coerceAtLeast(1)
        val k = key(text, refW, maxLines)
        val e = cache.getOrPut(k) {
            Entry(MathText.spanned(ctx, text, REF_FS, TEXT_COLOR, refW) { onFormula(k) }, refW, null)
        }
        e.layout?.let { return it }
        // 先不限行排一遍；真超了才按行数截 + 省略号。一开始就挂省略号的话，排满一整行宽的块公式
        // （它是一整块替换文字）会被当成「这一行放不下」整个换成「…」
        var l = build(e.spanned, refW, null)
        if (l.lineCount > maxLines) l = build(e.spanned, refW, maxLines)
        e.layout = l
        return l
    }

    private fun build(sp: Spanned, w: Int, maxLines: Int?): StaticLayout {
        val b = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(REF_FS * LINE_ADD, 1f)
            .setIncludePad(false)
        if (maxLines != null) b.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
        return b.build()
    }

    /** 排完占多高（屏上 px） */
    fun height(ctx: Context, text: String, contentW: Float, fs: Float, maxLines: Int): Float =
        layout(ctx, text, contentW, fs, maxLines).height * fs / REF_FS

    /** 把正文画到 (x, y)（屏上 px，正文左上角），超出 [clipH] 的部分裁掉 */
    fun draw(c: Canvas, l: StaticLayout, x: Float, y: Float, fs: Float, clipH: Float) {
        val s = fs / REF_FS
        c.save()
        c.translate(x, y)
        c.clipRect(0f, 0f, l.width * s, clipH)
        c.scale(s, s)
        l.draw(c)
        c.restore()
    }

    /** 公式排好了：这一条的旧排版作废（同一份带样式的文字留着，公式图在里面），叫画布重画 */
    private fun onFormula(k: String) {
        main.post {
            cache[k]?.layout = null
            for (v in views.keys) v.invalidate()
        }
    }
}
