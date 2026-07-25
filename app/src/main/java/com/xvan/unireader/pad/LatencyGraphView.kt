package com.xvan.unireader.pad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 延迟曲线悬浮窗（右上角，~200×90px 半透明底）：
 * 最近 60 个 rtt 折线（绿）+ e2e 采样第二线（橙）+ 当前值文本。
 * 显隐由设置弹窗开关控制（SharedPreferences 持久化，默认开）。
 */
class LatencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object { const val CAP = 60 }

    private val rtts = ArrayList<Float>()
    private val e2es = ArrayList<Float>()
    private var curRtt = -1f
    private var curE2e = -1f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0161B22.toInt() }
    private val rttPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3FB950.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val e2ePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF0883E.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22f
    }
    private val path = Path()

    fun addRtt(v: Float) {
        curRtt = v
        push(rtts, v)
    }

    fun addE2e(v: Float) {
        curE2e = v
        push(e2es, v)
    }

    private fun push(list: ArrayList<Float>, v: Float) {
        list.add(v)
        while (list.size > CAP) list.removeAt(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

        // 曲线区：底部留文本高度
        val graphH = h - 30f
        val maxV = max(100f, max(rtts.maxOrNull() ?: 0f, e2es.maxOrNull() ?: 0f))
        drawLine(canvas, rtts, maxV, graphH, rttPaint)
        drawLine(canvas, e2es, maxV, graphH, e2ePaint)

        val rttStr = if (curRtt >= 0) "rtt ${curRtt.toInt()}ms" else "rtt —"
        val e2eStr = if (curE2e >= 0) "e2e ${curE2e.toInt()}ms" else "e2e —"
        canvas.drawText("$rttStr  $e2eStr", 10f, h - 8f, textPaint)
    }

    /** 折线：最新点在右，纵轴 0~maxV 映射到曲线区（y 向下） */
    private fun drawLine(canvas: Canvas, data: List<Float>, maxV: Float, graphH: Float, paint: Paint) {
        if (data.size < 2) return
        val w = width.toFloat()
        path.reset()
        for (i in data.indices) {
            val x = w * i / (CAP - 1)
            val y = graphH * (1f - data[i] / maxV)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
