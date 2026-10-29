package com.xvan.unireader.shared.exercise

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * 习题的题干 / 选项 / 解析：Markdown + 公式画进普通 [TextView]（`../EXERCISE-PLAN.md §10`，用户选的「安卓自带控件画」）。
 *
 * 写法同 Mac 引擎（MCP 说明里的「Math in notes」）：`$…$` 行内、单独成行的 `$$…$$` 成块。Markwon 的公式插件只认
 * `$$`（行内也是 `$$…$$`），所以先把单个 `$` 包的改写成 `$$`（[normalize]），块公式拆成「`$$` / 内容 / `$$`」三行。
 * 公式在 Markwon 的后台线程里排版成图、排好再刷进文字（JLaTeXMath）。认不出的写法（JLaTeXMath 不支持的命令）
 * 原样显示源码，不让整段公式消失。
 *
 * 依赖只在这一个文件里（`io.noties.markwon:*`），别处都只调 [bind]（文字视图）/ [spanned]（自己排版画到画布上，
 * 笔记气泡用，见 `shared/NoteBubbleText`）。
 */
object MathText {

    /**
     * 公式一律在**同一条**后台线程上排。Markwon 默认用一个线程池并发排，而 JLaTeXMath 头一次用时的静态初始化
     * 不是线程安全的——同一屏有两个公式时会抢着初始化，输的那个排失败、回落成源码（模拟器上一屏两条公式复现）。
     */
    private val formulaThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "UniReader-latex").apply { isDaemon = true }
    }

    private fun rawFallback(latex: String, e: Throwable?, size: Float, color: Int): Drawable {
        android.util.Log.w("UniReader/Math", "公式排不出来，显示源码：$latex", e)
        return RawText(latex, size, color)
    }

    /** 一种字号 + 颜色一份 Markwon（公式插件的字号 / 颜色是建的时候定死的）。 */
    private val cache = HashMap<String, Markwon>()

    private fun markwon(ctx: Context, size: Float, color: Int): Markwon = cache.getOrPut("$size#$color") {
        Markwon.builder(ctx.applicationContext)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(size) { b ->
                    b.inlinesEnabled(true)
                    b.theme().textColor(color)
                    b.errorHandler { latex, e -> rawFallback(latex, e, size, color) }
                    b.executorService(formulaThread)
                },
            )
            .build()
    }

    /** 把 [md] 画进 [tv]（字号、颜色取 [tv] 当下的）。 */
    fun bind(tv: TextView, md: String) {
        val m = markwon(tv.context, tv.textSize, tv.currentTextColor)
        m.setMarkdown(tv, normalize(md))
        // Markwon 默认给文字挂上点链接的处理，文字视图就会把触摸吃掉——选项那一行点在正文上就选不中。
        // 题目里不会有要点的链接，摘掉（同时把 clickable / focusable 还原成 false）
        tv.movementMethod = null
        tv.isClickable = false
        tv.isLongClickable = false
    }

    /**
     * Markdown 笔记的阅读视图（`local/MdNoteController`）：同 [bind]，但**链接要能点**——点了交给 [onLink]
     * （`uniwiki:` / `uniimg:` / 网址都在宿主那边分流）。每次现建一份 Markwon：链接回调是每个视图自己的。
     */
    fun bindNote(tv: TextView, md: String, onLink: (String) -> Unit) {
        val size = tv.textSize
        val color = tv.currentTextColor
        Markwon.builder(tv.context.applicationContext)
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(
                JLatexMathPlugin.create(size) { b ->
                    b.inlinesEnabled(true)
                    b.theme().textColor(color)
                    b.errorHandler { latex, e -> rawFallback(latex, e, size, color) }
                    b.executorService(formulaThread)
                },
            )
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { _, link -> onLink(link) }
                }
            })
            .build()
            .setMarkdown(tv, normalize(md))
    }

    /**
     * 不经文字视图、自己排版时用：解析成带样式的文字，公式先占位、在后台排好后经 [onFormulaReady] 通知
     * （宿主据此重排重画——公式图的尺寸是排好才知道的）。[widthPx] = 排版宽（块公式按它缩放）。
     * 调用方要握着返回值别重建：重建会把排好的公式图丢掉重新排一遍。
     */
    fun spanned(ctx: Context, md: String, size: Float, color: Int, widthPx: Int, onFormulaReady: () -> Unit): Spanned {
        val sp = markwon(ctx, size, color).toMarkdown(normalize(md))
        val cb = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) = onFormulaReady()
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
            override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
        }
        for (span in sp.getSpans(0, sp.length, AsyncDrawableSpan::class.java)) {
            val d = span.drawable
            d.initWithKnownDimensions(widthPx, size)
            d.setCallback2(cb)   // 挂上回调才开始排（Markwon 的约定：没人看就不排）
        }
        return sp
    }

    /**
     * Mac 的写法 → Markwon 认的写法：
     * - 一行只有 `$$…$$`（块公式写在一行里）→ 拆成 `$$` / 内容 / `$$` 三行；
     * - 行内单个 `$…$` → `$$…$$`（`\$` 是字面的美元符，不算）；找不到配对的 `$` 原样留着。
     */
    fun normalize(src: String): String {
        val out = StringBuilder()
        val lines = src.split('\n')
        for ((n, raw) in lines.withIndex()) {
            if (n > 0) out.append('\n')
            val t = raw.trim()
            if (t.length > 4 && t.startsWith("$$") && t.endsWith("$$") && !t.substring(2, t.length - 2).contains("$$")) {
                out.append("$$\n").append(t.substring(2, t.length - 2).trim()).append("\n$$")
                continue
            }
            out.append(inline(raw))
        }
        return out.toString()
    }

    private fun inline(line: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { sb.append(c).append(line[i + 1]); i += 2; continue }
            if (c != '$') { sb.append(c); i++; continue }
            if (i + 1 < line.length && line[i + 1] == '$') {
                // 已经是 `$$…$$`：原样抄到配对的 `$$`
                val end = line.indexOf("$$", i + 2)
                if (end < 0) { sb.append(line.substring(i)); break }
                sb.append(line, i, end + 2)
                i = end + 2
                continue
            }
            // 单个 `$`：找下一个没被转义、也不是 `$$` 开头的 `$`
            var j = i + 1
            var close = -1
            while (j < line.length) {
                if (line[j] == '\\') { j += 2; continue }
                if (line[j] == '$') { close = j; break }
                j++
            }
            if (close < 0 || close == i + 1) { sb.append(line.substring(i)); break }
            sb.append("$$").append(line, i + 1, close).append("$$")
            i = close + 1
        }
        return sb.toString()
    }

    /** 公式排不出来时的替身：把源码当普通文字画出来。 */
    private class RawText(private val text: String, size: Float, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
        }
        private val w = paint.measureText(text).toInt() + 2
        private val h = (paint.fontMetrics.descent - paint.fontMetrics.ascent).toInt() + 2

        init { setBounds(0, 0, w, h) }

        override fun draw(canvas: Canvas) {
            canvas.drawText(text, bounds.left.toFloat(), bounds.top - paint.fontMetrics.ascent, paint)
        }

        override fun getIntrinsicWidth() = w
        override fun getIntrinsicHeight() = h
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
