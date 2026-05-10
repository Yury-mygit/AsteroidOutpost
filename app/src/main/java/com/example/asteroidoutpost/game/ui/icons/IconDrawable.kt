package com.example.asteroidoutpost.game.ui.icons

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Tinted vector icon for action-bar buttons. The shield/ability buttons in
 * `HudView` and the back chevron in `UpgradesOverlay` all share the same
 * pattern: a fixed pixel size + runtime-tintable Path-based artwork. The
 * concrete artwork lives in sibling files (`ShieldIcon.kt`, `RocketIcon.kt`,
 * etc.) — each `make<Name>Icon(ctx, sizeDp, tint)` factory hands this base
 * class a `drawer: (Canvas, Rect, Int) -> Unit` lambda that knows how to
 * paint that one silhouette.
 *
 * `setIconTint` lets the same drawable track button-state changes (active
 * → white, disabled → dim) without reallocating.
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
