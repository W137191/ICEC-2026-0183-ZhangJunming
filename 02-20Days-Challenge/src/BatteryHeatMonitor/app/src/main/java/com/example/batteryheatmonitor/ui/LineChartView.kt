package com.example.batteryheatmonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.batteryheatmonitor.model.Sample

/**
 * 轻量自定义折线图：同时画「电量%」与「机身温度℃」两条曲线。
 * 不引入第三方图表库，纯 Canvas 绘制，保证工程零额外依赖、一次编译通过。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val batteryPaint = Paint().apply {
        color = Color.parseColor("#4FC3F7"); strokeWidth = 3f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val tempPaint = Paint().apply {
        color = Color.parseColor("#FF7043"); strokeWidth = 3f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val axisPaint = Paint().apply { color = Color.parseColor("#8A97A8"); strokeWidth = 1f }
    private val textPaint = Paint().apply { color = Color.parseColor("#8A97A8"); textSize = 24f }

    private var samples: List<Sample> = emptyList()

    fun setSamples(list: List<Sample>) {
        samples = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val padL = 10f; val padR = 10f; val padT = 30f; val padB = 10f
        val plotW = w - padL - padR; val plotH = h - padT - padB

        for (i in 0..4) {
            val y = padT + plotH * i / 4f
            canvas.drawLine(padL, y, w - padR, y, axisPaint)
        }

        if (samples.size < 2) {
            canvas.drawText("采样≥2条后显示曲线", padL + 8f, padT + plotH / 2f, textPaint)
            return
        }

        val maxMin = samples.maxOf { it.elapsedMin }.toFloat().coerceAtLeast(1f)
        val maxTemp = samples.maxOf { it.tempC }.coerceAtLeast(40f)
        val minTemp = samples.minOf { it.tempC }.coerceAtMost(20f)
        val tempRange = (maxTemp - minTemp).coerceAtLeast(5f)

        drawSeries(canvas, padL, plotW, padT, plotH, maxMin, batteryPaint) { i ->
            val s = samples[i]
            padL + plotW * (s.elapsedMin.toFloat() / maxMin) to
                    padT + plotH * (1f - s.batteryPct / 100f)
        }
        drawSeries(canvas, padL, plotW, padT, plotH, maxMin, tempPaint) { i ->
            val s = samples[i]
            padL + plotW * (s.elapsedMin.toFloat() / maxMin) to
                    padT + plotH * (1f - (s.tempC - minTemp) / tempRange)
        }

        canvas.drawText("电量%", padL + 4f, 22f, batteryPaint)
        canvas.drawText("温度℃", padL + 100f, 22f, tempPaint)
    }

    private fun drawSeries(
        canvas: Canvas,
        padL: Float, plotW: Float, padT: Float, plotH: Float,
        maxMin: Float, paint: Paint, pt: (Int) -> Pair<Float, Float>
    ) {
        val path = Path()
        for (i in samples.indices) {
            val (x, y) = pt(i)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
