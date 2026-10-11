package com.xvan.unireader.shared.exercise

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

/**
 * 习题的题干 / 选项 / 解析：Markdown + 公式画进普通 [TextView]（`../EXERCISE-PLAN.md §10`，用户选的「安卓自带控件画」）。
 *
 * 写法同 Mac 引擎（MCP 说明里的「Math in notes」）：`$…$` 行内、单独成行的 `$$…$$` 成块。Markwon 的公式插件只认
 * `$$`（行内也是 `$$…$$`），所以先把单个 `$` 包的改写成 `$$`（[normalize]），块公式拆成「`$$` / 内容 / `$$`」三行。
 * 公式在 Markwon 的后台线程里排版成图、排好再刷进文字（JLaTeXMath）。认不出的写法（JLaTeXMath 不支持的命令）
 * 原样显示源码，不让整段公式消失。
 *
 * 依赖只在这一个文件里（`io.noties.markwon:*`），别处都只调 [bind]。
 */
object MathText {

    /** 一种字号 + 颜色一份 Markwon（公式插件的字号 / 颜色是建的时候定死的）。 */
    private val cache = HashMap<String, Markwon>()

    /** 把 [md] 画进 [tv]（字号、颜色取 [tv] 当下的）。 */
    fun bind(tv: TextView, md: String) {
        val color = tv.currentTextColor
        val size = tv.textSize
        val key = "$size#$color"
        val m = cache.getOrPut(key) {
            Markwon.builder(tv.context)
                .usePlugin(MarkwonInlineParserPlugin.create())
                .usePlugin(
                    JLatexMathPlugin.create(size) { b ->
                        b.inlinesEnabled(true)
                        b.theme().textColor(color)
                        b.errorHandler { latex, _ -> RawText(latex, size, color) }
                    },
                )
                .build()
        }
        m.setMarkdown(tv, normalize(md))
        // Markwon 默认给文字挂上点链接的处理，文字视图就会把触摸吃掉——选项那一行点在正文上就选不中。
        // 题目里不会有要点的链接，摘掉（同时把 clickable / focusable 还原成 false）
        tv.movementMethod = null
        tv.isClickable = false
        tv.isLongClickable = false
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
