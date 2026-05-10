package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Single unified overlay-root builder shared across all per-screen overlay
 * files. Each screen calls [makeOverlay] with [OverlayOpts] flags and gets
 * back an [Overlay] triple — `outer` (mount on the Activity root), `content`
 * (add cards / sections to it), and an optional `footer` for pinned
 * navigation. Layout shape is composed from the four orthogonal flags;
 * no per-screen layout boilerplate beyond the opts.
 *
 *   scrim       solid dark backdrop (modal feel) vs transparent (HUD-like)
 *   scrollable  wrap content in a ScrollView so long lists don't clip
 *   footer      pin a separate LinearLayout at the bottom (always-tappable)
 *   centred     non-scrollable content centred vertically vs top-anchored
 *
 * `outer` is always a FrameLayout. This keeps the contract uniform — any
 * screen can drop floating overlay children (e.g. a top-left back chevron)
 * directly onto `outer` without refactoring.
 */
internal data class OverlayOpts(
    val scrim: Boolean = true,
    val scrollable: Boolean = false,
    val footer: Boolean = false,
    val centred: Boolean = false,
)

internal data class Overlay(
    val outer: FrameLayout,
    val content: LinearLayout,
    val footer: LinearLayout?,
)

internal fun makeOverlay(context: Context, opts: OverlayOpts): Overlay {
    val padH = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_HORIZ)
    val padV = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_VERT)

    val outer = FrameLayout(context).apply {
        isClickable = true
        if (opts.scrim) UiHelpers.overlayBackground(this)
    }

    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = if (opts.centred) Gravity.CENTER
                  else Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Extra top padding leaves room for floating overlay children
        // (back chevron, ✕) and gives the title visual breathing room.
        setPadding(padH, padV * 3, padH, padV)
    }

    val footerLayout: LinearLayout? = if (opts.footer) {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padH, padV / 2, padH, padV)
        }
    } else null

    val body: View = if (opts.scrollable) {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        if (footerLayout != null) {
            // Vertical column: scroll (weight=1) takes the space above the
            // footer; footer pins to the bottom.
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                ))
                addView(footerLayout, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }
        } else scroll
    } else {
        // Non-scrollable: content fills the outer directly.
        content
    }

    outer.addView(body, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    ))

    return Overlay(outer, content, footerLayout)
}

/** Shared LayoutParams helper — full-width child stacked under the previous one. */
internal fun gapParams(ctx: Context, topMarginDp: Float): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = UiTheme.dp(ctx, topMarginDp) }

/**
 * Attaches a floating chevron-back tile to the bottom-right corner of
 * `overlay.outer` for back navigation. Same affordance across every
 * screen: thumb-reachable on portrait phones, never competes with content.
 * Tinted with the theme's text colour and laid on top of any footer.
 */
internal fun attachFloatingBackButton(
    context: Context,
    outer: FrameLayout,
    onBack: () -> Unit,
) {
    val sizePx = UiTheme.dp(context, 44f)
    val tile = UiHelpers.buildIconTile(
        context = context,
        icon    = com.example.asteroidoutpost.game.ui.icons.makeBackIcon(
            context, 28f, UiTheme.COL_TEXT,
        ),
        sideDp  = 44f,
        onClick = onBack,
    )
    val marginPx = UiTheme.dp(context, 12f)
    val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        setMargins(0, 0, marginPx, marginPx)
    }
    outer.addView(tile, params)
}
