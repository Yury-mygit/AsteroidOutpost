package com.example.asteroidoutpost.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/**
 * Debug-only HUD overlay — draws a small 2D coordinate-axes gizmo: a set of
 * coloured arrows radiating from an origin near the bottom-left of the
 * tile, with a one-line caption in the upper-left.
 *
 * Generic over axis count and direction: the screen-space gizmo passes two
 * axes ([+1, 0] and [0, -1]); the projected-world gizmo passes three axes
 * with directions baked from the camera basis.
 *
 * Pure debug. No game state is read.
 *
 * @param caption short header drawn in the corner (e.g. "МИР" / "ЭКР").
 * @param axes    list of (label, [dx, dy], colour) tuples. `dy` follows
 *                Canvas convention: positive = down on screen. Direction
 *                magnitude scales the arrow length relative to the longest
 *                axis so unit vectors fully extend and shorter projections
 *                appear visually compressed.
 */
internal class DebugAxesView(
    context: Context,
    private val caption: String,
    private val axes: List<Axis>,
) : View(context) {

    data class Axis(val label: String, val dx: Float, val dy: Float, val color: Int)

    private val strokePx: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics)
    private val labelSp: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    private val captionSp: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
    private val arrowHeadPx: Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = strokePx
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = labelSp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = captionSp
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFFB0B0B0.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        // Origin: bottom-left area of the tile so axes can fan into the
        // upper-right quadrant. Most projected directions in style3 are
        // either +right, +up, or +down so this corner gives the most
        // breathing room for arrows.
        val cx = width * 0.30f
        val cy = height * 0.65f
        val baseLen = minOf(width, height) * 0.55f
        val head = arrowHeadPx

        // Caption — upper-left of the tile.
        canvas.drawText(caption, 4f, captionSp + 2f, captionPaint)

        for (axis in axes) {
            drawArrow(canvas, cx, cy, axis.dx * baseLen, axis.dy * baseLen,
                      head, axis.color, axis.label)
        }
    }

    private fun drawArrow(
        canvas: Canvas,
        cx: Float, cy: Float,
        ex: Float, ey: Float,
        headSize: Float,
        color: Int,
        label: String,
    ) {
        paint.color = color
        val tipX = cx + ex
        val tipY = cy + ey
        canvas.drawLine(cx, cy, tipX, tipY, paint)

        val len = kotlin.math.sqrt(ex * ex + ey * ey)
        if (len > 1e-3f) {
            val nx = ex / len
            val ny = ey / len
            // perpendicular (Canvas-handed)
            val px = -ny
            val py =  nx
            val backX = tipX - nx * headSize
            val backY = tipY - ny * headSize
            val w = headSize * 0.55f
            canvas.drawLine(tipX, tipY, backX + px * w, backY + py * w, paint)
            canvas.drawLine(tipX, tipY, backX - px * w, backY - py * w, paint)

            labelPaint.color = color
            canvas.drawText(label, tipX + nx * headSize * 0.6f,
                            tipY + ny * headSize * 0.6f + labelSp * 0.35f, labelPaint)
        }
    }
}
