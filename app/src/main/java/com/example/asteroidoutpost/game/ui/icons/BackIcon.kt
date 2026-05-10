package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** Left-pointing chevron + stem — back-navigation icon for overlays. */
internal fun makeBackIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
    val sizePx = UiTheme.dp(ctx, sizeDp)
    return IconDrawable(sizePx, tint) { canvas, b, c ->
        val w = b.width().toFloat(); val h = b.height().toFloat()
        val left = b.left.toFloat(); val top = b.top.toFloat()
        fun px(u: Float) = left + u * w
        fun py(v: Float) = top  + v * h
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.STROKE
            strokeWidth = (h * 0.12f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Chevron head pointing left.
        val head = Path().apply {
            moveTo(px(0.45f), py(0.18f))
            lineTo(px(0.18f), py(0.50f))
            lineTo(px(0.45f), py(0.82f))
        }
        canvas.drawPath(head, stroke)
        // Horizontal stem from chevron tip to the right edge.
        canvas.drawLine(px(0.18f), py(0.50f), px(0.82f), py(0.50f), stroke)
    }
}
