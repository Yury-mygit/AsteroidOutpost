package com.example.asteroidoutpost.game.ui.icons

import android.content.Context
import android.graphics.Paint
import android.graphics.Path
import com.example.asteroidoutpost.game.UiTheme

/** Gear / cog — settings icon. 8 teeth around a hollow center. */
internal fun makeSettingsIcon(ctx: Context, sizeDp: Float, tint: Int): IconDrawable {
    val sizePx = UiTheme.dp(ctx, sizeDp)
    return IconDrawable(sizePx, tint) { canvas, b, c ->
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val rOuter = b.width() * 0.45f          // tooth tip radius
        val rInner = rOuter * 0.72f             // tooth base / outer disk radius
        val rHole  = rOuter * 0.30f             // inner hollow radius
        val toothHalfArc = (Math.PI / 16f).toFloat()    // tooth angular half-width
        val nTeeth = 8

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            style = Paint.Style.FILL
        }
        // Build the gear silhouette: outer ring alternating between tooth-tip
        // arcs (rOuter, ±toothHalfArc) and gap arcs (rInner, between teeth).
        val path = Path()
        for (i in 0 until nTeeth) {
            val center = (i * 2.0 * Math.PI / nTeeth).toFloat()
            val tipA   = center - toothHalfArc
            val tipB   = center + toothHalfArc
            val gapEnd = center + (Math.PI / nTeeth).toFloat()
            // Tooth: rOuter at tipA, rOuter at tipB, then rInner sweeping to gapEnd.
            val xA = cx + rOuter * kotlin.math.cos(tipA)
            val yA = cy + rOuter * kotlin.math.sin(tipA)
            if (i == 0) path.moveTo(xA, yA) else path.lineTo(xA, yA)
            val xB = cx + rOuter * kotlin.math.cos(tipB)
            val yB = cy + rOuter * kotlin.math.sin(tipB)
            path.lineTo(xB, yB)
            val xC = cx + rInner * kotlin.math.cos(tipB)
            val yC = cy + rInner * kotlin.math.sin(tipB)
            path.lineTo(xC, yC)
            val xD = cx + rInner * kotlin.math.cos(gapEnd)
            val yD = cy + rInner * kotlin.math.sin(gapEnd)
            path.lineTo(xD, yD)
            val tipANext = center + (2.0 * Math.PI / nTeeth).toFloat() - toothHalfArc
            val xE = cx + rInner * kotlin.math.cos(tipANext)
            val yE = cy + rInner * kotlin.math.sin(tipANext)
            path.lineTo(xE, yE)
        }
        path.close()
        // Subtract the hollow center via FILL_TYPE alternation.
        path.fillType = Path.FillType.EVEN_ODD
        path.addCircle(cx, cy, rHole, Path.Direction.CCW)
        canvas.drawPath(path, fill)
    }
}
