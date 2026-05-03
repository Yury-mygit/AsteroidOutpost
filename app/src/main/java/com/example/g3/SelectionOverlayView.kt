package com.example.g3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class ScreenFrame(
    val objectId: Int,
    val bounds: RectF,
    val selected: Boolean,
    val enemy: Boolean,
    val partiallyVisible: Boolean
)

data class HealthBarData(
    val objectId: Int,
    val shieldFraction: Float,
    val hullFraction: Float
)

class SelectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barHeight = BAR_HEIGHT_DP * density
    private val barGap = BAR_GAP_DP * density
    private val barSpacing = BAR_SPACING_DP * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val frames = mutableListOf<ScreenFrame>()
    private val healthBars = mutableMapOf<Int, HealthBarData>()
    private var buildStationId: Int = -1
    private var buildProgress: Float = 0f

    fun setFramesAndHealthBars(nextFrames: List<ScreenFrame>, bars: List<HealthBarData>) {
        frames.clear()
        frames.addAll(nextFrames)
        healthBars.clear()
        bars.forEach { healthBars[it.objectId] = it }
        postInvalidateOnAnimation()
    }

    fun setBuildProgress(stationId: Int, progress: Float) {
        buildStationId = stationId
        buildProgress  = progress
        postInvalidateOnAnimation()
    }

    fun setFrames(nextFrames: List<ScreenFrame>) {
        frames.clear()
        frames.addAll(nextFrames)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        for (frame in frames) {
            val rect = RectF(frame.bounds)
            if (!rect.intersect(screen)) continue
            val data = healthBars[frame.objectId]
            if (data != null) drawHealthBars(canvas, frame.bounds, data)
            if (frame.objectId == buildStationId && buildStationId >= 0) {
                drawBuildProgress(canvas, frame.bounds)
            }
        }
    }

    private fun drawBuildProgress(canvas: Canvas, frameRect: RectF) {
        val totalHealth = 2 * barHeight + barSpacing
        val healthTop = frameRect.top - barGap - totalHealth
        val top = healthTop - barGap - BUILD_BAR_HEIGHT_DP * density
        val bottom = top + BUILD_BAR_HEIGHT_DP * density
        drawBar(canvas, frameRect.left, top, frameRect.right, bottom, buildProgress, BUILD_FILL_COLOR)
    }

    private fun drawHealthBars(canvas: Canvas, frameRect: RectF, data: HealthBarData) {
        val left = frameRect.left
        val right = frameRect.right
        val totalHeight = 2 * barHeight + barSpacing
        var top = frameRect.top - barGap - totalHeight

        drawBar(canvas, left, top, right, top + barHeight, data.shieldFraction, SHIELD_FILL_COLOR)
        top += barHeight + barSpacing
        drawBar(canvas, left, top, right, top + barHeight, data.hullFraction, hullFillColor(data.hullFraction))
    }

    private fun drawBar(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, fraction: Float, fillColor: Int) {
        bgPaint.color = BAR_BG_COLOR
        canvas.drawRect(left, top, right, bottom, bgPaint)
        val fillRight = left + (right - left) * fraction.coerceIn(0f, 1f)
        if (fillRight > left) {
            fillPaint.color = fillColor
            canvas.drawRect(left, top, fillRight, bottom, fillPaint)
        }
    }

    private companion object {
        const val BAR_HEIGHT_DP = 4f
        const val BAR_GAP_DP = 3f
        const val BAR_SPACING_DP = 1f
        const val BUILD_BAR_HEIGHT_DP = 5f

        val BAR_BG_COLOR = Color.argb(180, 0, 0, 0)
        val SHIELD_FILL_COLOR = Color.rgb(60, 190, 255)
        val BUILD_FILL_COLOR = Color.rgb(255, 200, 40)

        fun hullFillColor(fraction: Float) = when {
            fraction > 0.5f -> Color.rgb(70, 220, 90)
            fraction > 0.25f -> Color.rgb(255, 195, 0)
            else -> Color.rgb(255, 70, 55)
        }
    }
}
