package com.example.asteroidoutpost.game.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.example.asteroidoutpost.game.UiTheme

/**
 * Tinted vector icon for the action-bar buttons. Replaces the textual
 * labels (ЩИТ / РАКЕТЫ / ЛАЗЕР) — the silhouette communicates the ability,
 * a small caption below the icon carries dynamic state (shield HP, ability
 * cooldown). Tint can be retuned via `setIconTint` so the same drawable
 * tracks button state changes (active = white, disabled = dim) without
 * reallocating.
 */
internal class IconDrawable(
    private val sizePx: Int,
    initialTint: Int,
    private val drawer: (Canvas, Rect, Int) -> Unit,
) : Drawable() {
    private var tint: Int = initialTint
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun draw(canvas: Canvas) {
        drawer(canvas, bounds, tint)
    }
    override fun setAlpha(alpha: Int) { /* tint controls alpha */ }
    override fun setColorFilter(cf: ColorFilter?) { /* not used */ }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    fun setIconTint(c: Int) {
        if (c != tint) { tint = c; invalidateSelf() }
    }
}

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
