package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** Stylized rocket silhouette pointing up — bullet body, two side fins, exhaust flame. */
internal fun makeRocketIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
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
        // Body + nose — bullet shape pointing up.
        val body = Path().apply {
            moveTo(px(0.50f), py(0.06f))
            lineTo(px(0.62f), py(0.28f))
            lineTo(px(0.62f), py(0.74f))
            lineTo(px(0.38f), py(0.74f))
            lineTo(px(0.38f), py(0.28f))
            close()
        }
        canvas.drawPath(body, paint)
        // Side fins flaring out at the base.
        val finL = Path().apply {
            moveTo(px(0.38f), py(0.55f))
            lineTo(px(0.18f), py(0.84f))
            lineTo(px(0.38f), py(0.84f))
            close()
        }
        val finR = Path().apply {
            moveTo(px(0.62f), py(0.55f))
            lineTo(px(0.82f), py(0.84f))
            lineTo(px(0.62f), py(0.84f))
            close()
        }
        canvas.drawPath(finL, paint)
        canvas.drawPath(finR, paint)
        // Exhaust flame — semi-transparent triangle below the body.
        val flame = Path().apply {
            moveTo(px(0.42f), py(0.84f))
            lineTo(px(0.50f), py(0.98f))
            lineTo(px(0.58f), py(0.84f))
            close()
        }
        val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            alpha = 150
            style = Paint.Style.FILL
        }
        canvas.drawPath(flame, flamePaint)
    }
}
