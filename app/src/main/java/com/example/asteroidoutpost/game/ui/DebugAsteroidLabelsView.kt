package com.example.asteroidoutpost.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import java.util.concurrent.atomic.AtomicReference

/**
 * Debug-only overlay drawing one short text label per asteroid at its
 * projected on-screen position. Labels show world (x, y, z) so we can
 * see where asteroids actually live as they fly.
 *
 * Thread model:
 *  - Owner (tick thread) computes projected pixel positions + label
 *    strings off-screen and pushes a snapshot via [update].
 *  - UI thread reads the snapshot in [onDraw] (atomic reference).
 *  - View is transparent and forwards no touch events.
 */
internal class DebugAsteroidLabelsView(context: Context) : View(context) {

    data class Label(val sx: Float, val sy: Float, val text: String)

    private val labels = AtomicReference<List<Label>>(emptyList())

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
        color = 0xCCFFFFFF.toInt()
        typeface = Typeface.MONOSPACE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = paint.textSize
        color = 0xAA000000.toInt()
        typeface = Typeface.MONOSPACE
    }

    init {
        // Don't intercept touches; engine surface below handles them.
        isClickable = false
        isFocusable = false
    }

    /** Push a new snapshot of projected labels. Safe to call from any thread. */
    fun update(snapshot: List<Label>) {
        labels.set(snapshot)
        postInvalidate()
    }

    /** Clear all labels (call when leaving Playing state). */
    fun clear() = update(emptyList())

    override fun onDraw(canvas: Canvas) {
        val data = labels.get()
        for (l in data) {
            canvas.drawText(l.text, l.sx + 1f, l.sy + 1f, shadow)
            canvas.drawText(l.text, l.sx, l.sy, paint)
        }
    }
}
