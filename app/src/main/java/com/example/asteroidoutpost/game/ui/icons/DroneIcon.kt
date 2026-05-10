package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** Stylised interceptor-drone silhouette: triangular body pointing up with
 *  two short side wings and a centre cockpit dot. Reads as "small fighter"
 *  at button-tile size. */
internal fun makeDroneIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
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
        // Triangular fuselage pointing up.
        val body = Path().apply {
            moveTo(px(0.50f), py(0.10f))
            lineTo(px(0.66f), py(0.78f))
            lineTo(px(0.34f), py(0.78f))
            close()
        }
        canvas.drawPath(body, paint)
        // Two short side wings flaring out at mid-fuselage.
        val wingL = Path().apply {
            moveTo(px(0.40f), py(0.50f))
            lineTo(px(0.16f), py(0.70f))
            lineTo(px(0.16f), py(0.80f))
            lineTo(px(0.40f), py(0.65f))
            close()
        }
        val wingR = Path().apply {
            moveTo(px(0.60f), py(0.50f))
            lineTo(px(0.84f), py(0.70f))
            lineTo(px(0.84f), py(0.80f))
            lineTo(px(0.60f), py(0.65f))
            close()
        }
        canvas.drawPath(wingL, paint)
        canvas.drawPath(wingR, paint)
        // Cockpit dot.
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            alpha = 160
            style = Paint.Style.FILL
        }
        canvas.drawCircle(px(0.50f), py(0.40f), w * 0.06f, dotPaint)
    }
}
