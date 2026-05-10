package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** V-shaped heater shield silhouette — wide flat top, narrow V-point at the bottom. */
internal fun makeShieldIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
    val sizePx = UiTheme.dp(ctx, sizeDp)
    return IconDrawable(sizePx, tint) { canvas, b, c ->
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val left = b.left.toFloat(); val top = b.top.toFloat()
        fun px(u: Float) = left + u * w
        fun py(v: Float) = top  + v * h
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.FILL
        }
        // Heater-shield outline: flat top, gentle outward bulge on the
        // shoulders, sharp V at the bottom centre.
        val outline = Path().apply {
            moveTo(px(0.18f), py(0.10f))
            lineTo(px(0.82f), py(0.10f))
            lineTo(px(0.86f), py(0.42f))
            lineTo(px(0.50f), py(0.95f))
            lineTo(px(0.14f), py(0.42f))
            close()
        }
        canvas.drawPath(outline, paint)
        // "Chief" — thin horizontal accent band near the top, drawn as a
        // notched-out hole. Use destination-out via a contrasting tint
        // would need offscreen; instead just stroke with bg-ish alpha so
        // the silhouette gets a small heraldic detail.
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            alpha = 90
            style = Paint.Style.STROKE
            strokeWidth = (h * 0.06f).coerceAtLeast(1.5f)
        }
        canvas.drawLine(px(0.30f), py(0.32f), px(0.70f), py(0.32f), accent)
    }
}
