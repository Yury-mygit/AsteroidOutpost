package com.example.asteroidoutpost

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Draws a small XYZ axis indicator that rotates with the camera.
 * Call [setRotationMatrix] with a column-major 4x4 matrix each time
 * the camera orientation changes.
 */
class AxisIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val rotMat = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(100, 0, 0, 0)
        style  = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface  = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    fun setRotationMatrix(m: FloatArray) {
        m.copyInto(rotMat, 0, 0, 16)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx  = width  * 0.5f
        val cy  = height * 0.5f
        val len = minOf(width, height) * 0.36f
        val sw  = minOf(width, height) * 0.065f   // stroke width
        val ts  = minOf(width, height) * 0.20f    // text size

        canvas.drawCircle(cx, cy, minOf(width, height) * 0.48f, bgPaint)

        // Column-major 4×4: world axis i → cam-space = column i
        // X=col0 (m[0],m[1],m[2]), Y=col1 (m[4],m[5],m[6]), Z=col2 (m[8],m[9],m[10])
        // Screen: right=+camX, down=−camY (flip Y for Android)
        data class Axis(val sx: Float, val sy: Float, val depth: Float, val label: String, val hue: Int)

        val axes = listOf(
            Axis(rotMat[0],  -rotMat[1],  rotMat[2],  "X", Color.rgb(255, 80,  80)),
            Axis(rotMat[4],  -rotMat[5],  rotMat[6],  "Y", Color.rgb(80,  220, 80)),
            Axis(rotMat[8],  -rotMat[9],  rotMat[10], "Z", Color.rgb(100, 160, 255))
        )

        // Draw back-facing axes first so front-facing appear on top
        for (axis in axes.sortedByDescending { it.depth }) {
            val alpha = if (axis.depth > 0f) 90 else 255
            val ex = cx + axis.sx * len
            val ey = cy + axis.sy * len

            linePaint.color = axis.hue
            linePaint.alpha = alpha
            linePaint.strokeWidth = sw
            canvas.drawLine(cx, cy, ex, ey, linePaint)

            textPaint.color = axis.hue
            textPaint.alpha = alpha
            textPaint.textSize = ts
            canvas.drawText(axis.label, ex, ey - sw * 0.3f, textPaint)
        }
    }
}
