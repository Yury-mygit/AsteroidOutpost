package com.example.asteroidoutpost.game.ui.icons

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Vertical fill bar inside the shield button: bottom = remaining HP
 * (green), top = depleted HP (gray). The split point descends as HP
 * drains, rises as the player recharges. Both halves clip to the same
 * rounded rectangle so the button silhouette stays clean.
 */
internal class ShieldFillDrawable(
    private val cornerRadiusPx: Float,
    private val emptyColor: Int,
    private val fullColor: Int,
) : Drawable() {
    private var fraction: Float = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val rectF = RectF()
    fun setFraction(f: Float) {
        val nf = f.coerceIn(0f, 1f)
        if (nf != fraction) { fraction = nf; invalidateSelf() }
    }
    override fun draw(canvas: Canvas) {
        rectF.set(bounds)
        clipPath.reset()
        clipPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx,
            Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        val splitY = bounds.top + (1f - fraction) * bounds.height()
        if (splitY > bounds.top) {
            paint.color = emptyColor
            canvas.drawRect(rectF.left, rectF.top, rectF.right, splitY, paint)
        }
        if (splitY < bounds.bottom) {
            paint.color = fullColor
            canvas.drawRect(rectF.left, splitY, rectF.right, rectF.bottom, paint)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { /* fixed */ }
    override fun setColorFilter(cf: ColorFilter?) { /* not used */ }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
