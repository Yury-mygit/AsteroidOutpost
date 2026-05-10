package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** Laser beam slicing through an asteroid — diagonal beam over an irregular polygon. */
internal fun makeLaserIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
    val sizePx = UiTheme.dp(ctx, sizeDp)
    return IconDrawable(sizePx, tint) { canvas, b, c ->
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val left = b.left.toFloat(); val top = b.top.toFloat()
        fun px(u: Float) = left + u * w
        fun py(v: Float) = top  + v * h
        // Irregular asteroid silhouette in dim tint.
        val rockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            alpha = 130
            style = Paint.Style.FILL
        }
        val asteroid = Path().apply {
            moveTo(px(0.30f), py(0.42f))
            lineTo(px(0.46f), py(0.30f))
            lineTo(px(0.66f), py(0.34f))
            lineTo(px(0.80f), py(0.50f))
            lineTo(px(0.74f), py(0.70f))
            lineTo(px(0.56f), py(0.80f))
            lineTo(px(0.34f), py(0.74f))
            lineTo(px(0.22f), py(0.58f))
            close()
        }
        canvas.drawPath(asteroid, rockPaint)
        // Beam — thick diagonal from upper-left to lower-right, full alpha
        // so it visually cuts through the rock.
        val beam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = (h * 0.13f).coerceAtLeast(2.5f)
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(px(0.06f), py(0.08f), px(0.94f), py(0.92f), beam)
    }
}
