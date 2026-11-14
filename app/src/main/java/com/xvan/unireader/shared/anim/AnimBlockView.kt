package com.xvan.unireader.shared.anim

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.xvan.unireader.R
import com.xvan.unireader.shared.Ui
import kotlin.math.ceil

/**
 * Markdown 笔记阅读态里的 ```anim 分步演示（`../docs/ANIM-BLOCK-PLAN.md`，Mac 是 `Sources/Anim/AnimBlockView.swift`）：
 * 画布 + 底下一行控制（上一步 / 播放·暂停 / 下一步 / 进度条 / 步号）。点画布 = 下一步。步与步之间补间 [AnimPainter.TRANSITION_MS]，
 * 拖进度条直接跳、不补间；自动播放每步停 [HOLD_MS]。块宽最多 720dp（同 Mac），高度与步无关，播放时不跳行。
 */
@SuppressLint("ViewConstructor")
class AnimBlockView(context: Context) : LinearLayout(context) {
    /** 画布换算：dp × [SCALE]。演示正文是 12（同 Mac 的点），平板笔记正文是 16sp，原样画汉字偏小（用户 2026-11-14）——
     *  整块（格子、线、字）一起放大，比例不变；逻辑宽度按放大后折算，块照旧不超出笔记栏。 */
    private val density = resources.displayMetrics.density * SCALE
    private val text2d = AnimText2D(density)
    private var palette = AnimPalette(context)

    private var source = ""
    private var run: AnimRun = AnimEngine.run("")
    private var layout: AnimLayout? = null
    private var errorLines: List<String> = emptyList()
    private var errorHeight = 0f
    private val frames = HashMap<Int, AnimFrame>()

    private var step = 0
    private var fromStep: Int? = null
    private var t = 0f
    private var playing = false
    private var animator: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val advance = Runnable { onPlayTick() }

    private val canvasView = CanvasView(context)
    private val prev = Ui.textButton(context, context.getString(R.string.anim_prev)) { stopPlayback(); go(step - 1, true) }
    private val play = Ui.textButton(context, context.getString(R.string.anim_play), primary = true) { togglePlay() }
    private val next = Ui.textButton(context, context.getString(R.string.anim_next)) { stopPlayback(); go(step + 1, true) }
    private val seek = SeekBar(context)
    private val counter = TextView(context).apply {
        textSize = 13f
        setTextColor(Ui.onSurface(context))
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 8), 0)
    }

    init {
        orientation = VERTICAL
        addView(canvasView, LayoutParams(-1, -2))
        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prev)
            addView(play)
            addView(next)
            addView(seek, LayoutParams(0, -2, 1f))
            addView(counter)
        }
        addView(bar, LayoutParams(-1, -2))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) { stopPlayback(); go(value, false) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        refreshControls()
    }

    fun setSource(text: String) {
        if (text == source) return
        source = text
        run = AnimEngine.run(text)
        layout = null
        frames.clear()
        stopPlayback()
        animator?.cancel()
        fromStep = null
        step = step.coerceIn(0, lastStep)
        refreshControls()
        canvasView.requestLayout()
        canvasView.invalidate()
    }

    private val lastStep get() = maxOf(run.stepCount - 1, 0)

    /** 按可用宽度（dp，最多 720）排一次；宽度没变不重排。 */
    private fun ensureLayout(widthPx: Int): AnimLayout? {
        if (widthPx <= 0) return layout
        val w = minOf(widthPx / density, MAX_WIDTH).toInt().toFloat()
        if (layout?.width == w) return layout
        val l = AnimLayout(run, w, text2d)
        layout = l
        frames.clear()
        val lines = run.errors.take(MAX_ERROR_LINES).map { context.getString(R.string.anim_line_error, it.line, it.message) }.toMutableList()
        if (run.errors.size > MAX_ERROR_LINES) lines.add("…")
        errorLines = lines
        errorHeight = if (lines.isEmpty()) 0f else text2d.measure(lines.joinToString("\n"), AnimMetrics.SMALL, false, w - 2 * AnimMetrics.PAD).h + 8
        return l
    }

    private fun frame(k: Int): AnimFrame? {
        frames[k]?.let { return it }
        val f = layout?.frame(k) ?: return null
        frames[k] = f
        return f
    }

    // —— 走步 ——

    private fun go(target: Int, animated: Boolean) {
        val k = target.coerceIn(0, lastStep)
        if (k == step && fromStep == null) { refreshControls(); return }
        animator?.cancel()
        if (animated && k != step) {
            fromStep = step
            t = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = AnimPainter.TRANSITION_MS
                interpolator = LinearInterpolator()
                addUpdateListener {
                    t = AnimPainter.ease(it.animatedValue as Float)
                    canvasView.invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (animator === animation) {
                            fromStep = null
                            canvasView.invalidate()
                            if (playing) handler.postDelayed(advance, HOLD_MS)
                        }
                    }
                })
                start()
            }
        } else {
            fromStep = null
        }
        step = k
        refreshControls()
        canvasView.invalidate()
    }

    private fun togglePlay() {
        if (playing) { stopPlayback(); return }
        playing = true
        if (step >= lastStep) go(0, false)
        handler.postDelayed(advance, 250)
        refreshControls()
    }

    private fun onPlayTick() {
        if (!playing) return
        if (step >= lastStep) { stopPlayback(); return }
        go(step + 1, true)
    }

    private fun stopPlayback() {
        handler.removeCallbacks(advance)
        if (!playing) return
        playing = false
        refreshControls()
    }

    private fun refreshControls() {
        val last = lastStep
        seek.max = maxOf(last, 1)
        seek.progress = step
        seek.isEnabled = last > 0
        prev.isEnabled = step > 0
        next.isEnabled = step < last
        play.isEnabled = last > 0
        prev.alpha = if (prev.isEnabled) 1f else 0.4f
        next.alpha = if (next.isEnabled) 1f else 0.4f
        play.text = context.getString(if (playing) R.string.anim_pause else R.string.anim_play)
        counter.text = context.getString(R.string.anim_step_count, step, last)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playing = false
        handler.removeCallbacks(advance)
        animator?.cancel()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        palette = AnimPalette(context)
        canvasView.invalidate()
    }

    /** 画布：高 = 布局高 + 错误行，宽按父视图；点一下 = 下一步。 */
    private inner class CanvasView(c: Context) : View(c) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val l = ensureLayout(w)
            val h = if (l == null) 0 else ceil((l.height + errorHeight) * density).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            val l = ensureLayout(width) ?: return
            val target = frame(step) ?: return
            val from = fromStep?.let { frame(it) }
            canvas.save()
            canvas.scale(density, density)
            if (from != null) AnimPainter.draw(canvas, from, target, t, palette, text2d, density)
            else AnimPainter.draw(canvas, target, null, 0f, palette, text2d, density)
            if (errorLines.isNotEmpty()) {
                val r = ARect(AnimMetrics.PAD, l.height, l.width - 2 * AnimMetrics.PAD, errorHeight)
                AnimPainter.draw(canvas, AnimFrame(ASize(l.width, errorHeight),
                    listOf(AnimItem("errors", AnimShape.Text(errorLines.joinToString("\n"), r, AnimMetrics.SMALL, false, AnimRole.TextC(AnimTone.WRONG), AnimAlign.LEFT, false)))),
                    null, 0f, palette, text2d, density)
            }
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    if (lastStep > 0) { stopPlayback(); go(step + 1, true) }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    companion object {
        /** 整块放大倍数：12 × 1.3 ≈ 16，与笔记正文差不多大。 */
        const val SCALE = 1.3f
        const val MAX_WIDTH = 720f
        const val HOLD_MS = 1400L
        const val MAX_ERROR_LINES = 4
    }
}
