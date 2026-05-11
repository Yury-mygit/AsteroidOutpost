package com.example.asteroidoutpost.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import java.util.concurrent.atomic.AtomicReference

/**
 * Overlay drawing corner-bracket frames around asteroids. Two colour
 * conventions in use (filled in by the caller per-frame):
 *   - Green: the asteroid the player has priority-locked (tap-select).
 *   - Red:   threats — asteroids on a course to hit shield/hull, the
 *            ones auto-aim turrets will engage.
 *
 * Corner-bracket style: four L-shapes in the corners of the bounding
 * rect, with empty space in the middle of each edge. Reads as a HUD
 * targeting reticle rather than a continuous box.
 *
 * Thread model: tick thread pushes a snapshot via [update]; UI thread
 * reads the AtomicReference in [onDraw]. View forwards no touches.
 */
internal class SelectionFrameView(context: Context) : View(context) {

    data class Frame(
        val sx: Float,
        val sy: Float,
        val radiusPx: Float,
        val color: Int,
    )

    private val snapshot = AtomicReference<List<Frame>>(emptyList())

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1.4f, resources.displayMetrics)
        strokeCap = Paint.Cap.SQUARE
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 3.2f, resources.displayMetrics)
        strokeCap = Paint.Cap.SQUARE
    }
    // Minimum empty span between brackets along each edge (px). When the
    // asteroid silhouette shrinks, brackets shorten so the gap stays at
    // least this size — otherwise the four L's merge into a continuous
    // box and the reticle look is lost.
    private val minGapPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
    private val minCornerPx: Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics)

    init {
        isClickable = false
        isFocusable = false
    }

    /** Push frame snapshot. Empty list clears the overlay. */
    fun update(frames: List<Frame>) {
        snapshot.set(frames)
        postInvalidate()
    }

    fun clear() = update(emptyList())

    override fun onDraw(canvas: Canvas) {
        val data = snapshot.get()
        if (data.isEmpty()) return
        for (f in data) {
            // Glow stroke (semi-transparent same colour) reads as a soft
            // halo so the bracket stays legible against bright nebulae.
            glow.color = (f.color and 0x00FFFFFF) or 0x55000000
            stroke.color = f.color
            drawCornerBrackets(canvas, f.sx, f.sy, f.radiusPx, glow)
            drawCornerBrackets(canvas, f.sx, f.sy, f.radiusPx, stroke)
        }
    }

    /**
     * Four L-brackets in the corners of a square bounding box centred on
     * (cx, cy) with half-side = radiusPx. Arm length scales with radius
     * but the centre-edge gap is floored at `minGapPx` so far-away
     * asteroids still read as four separate corners rather than a solid
     * outline.
     */
    private fun drawCornerBrackets(
        canvas: Canvas, cx: Float, cy: Float, radiusPx: Float, paint: Paint,
    ) {
        val left   = cx - radiusPx
        val top    = cy - radiusPx
        val right  = cx + radiusPx
        val bottom = cy + radiusPx
        // Target: ~45% of the half-side becomes bracket arm, ~55% becomes
        // gap — but never let the gap fall below minGapPx. Floor the arm
        // to minCornerPx so the L stays drawn even on tiny silhouettes.
        val gap = (radiusPx * 0.55f).coerceAtLeast(minGapPx)
        val cornerLen = (radiusPx - gap).coerceAtLeast(minCornerPx)
        // Top-left
        canvas.drawLine(left, top, left + cornerLen, top, paint)
        canvas.drawLine(left, top, left, top + cornerLen, paint)
        // Top-right
        canvas.drawLine(right - cornerLen, top, right, top, paint)
        canvas.drawLine(right, top, right, top + cornerLen, paint)
        // Bottom-left
        canvas.drawLine(left, bottom - cornerLen, left, bottom, paint)
        canvas.drawLine(left, bottom, left + cornerLen, bottom, paint)
        // Bottom-right
        canvas.drawLine(right, bottom - cornerLen, right, bottom, paint)
        canvas.drawLine(right - cornerLen, bottom, right, bottom, paint)
    }
}
