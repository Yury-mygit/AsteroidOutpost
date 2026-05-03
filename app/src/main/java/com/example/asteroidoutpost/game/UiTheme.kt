package com.example.asteroidoutpost.game

import android.content.Context

/**
 * Single source of truth for the Outpost UI look ("clean sci-fi arcade").
 *
 * Five-colour palette + a small set of paddings, corner radii and text sizes.
 * All sizes are stored in dp / sp; helpers convert to pixels using a Context.
 *
 * Touch this file to retune the look — overlays should never hard-code values.
 */
object UiTheme {

    // ---- Colours (ARGB) ----------------------------------------------------

    /** Backgrounds. */
    const val COL_OVERLAY_BG  = 0xE5050A12.toInt()  // near-black, near-opaque (overlays)
    const val COL_PANEL_BG    = 0xFF111A29.toInt()  // graphite (HUD / cards)
    const val COL_PANEL_BG_HI = 0xFF1B2740.toInt()  // slightly brighter (raised cards)

    /** Accents. */
    const val COL_ACCENT_RED   = 0xFFE5494D.toInt()   // player / damage / lose
    const val COL_ACCENT_BLUE  = 0xFF4CC1FF.toInt()   // turrets / win
    const val COL_ACCENT_GREEN = 0xFF66D67A.toInt()   // success / easy difficulty
    const val COL_WARNING      = 0xFFFFC85A.toInt()   // amber — warnings, costs you can't afford

    /** Text. */
    const val COL_TEXT          = 0xFFFFFFFF.toInt()  // primary white
    const val COL_TEXT_DIM      = 0xFFB7C0D2.toInt()  // secondary
    const val COL_TEXT_DISABLED = 0xFF5F6A7E.toInt()  // disabled / max

    /** Soft borders / dividers. */
    const val COL_BORDER       = 0x33FFFFFF.toInt()
    const val COL_BORDER_HI    = 0x66FFFFFF.toInt()

    // ---- Geometry (dp / sp) ------------------------------------------------

    const val DP_PANEL_RADIUS   = 18f
    const val DP_CARD_RADIUS    = 14f
    const val DP_BUTTON_RADIUS  = 12f

    const val DP_BORDER_WIDTH   = 1f

    const val DP_PAD_OVERLAY_HORIZ = 24f
    const val DP_PAD_OVERLAY_VERT  = 32f
    const val DP_PAD_CARD          = 16f
    const val DP_PAD_BUTTON_VERT   = 12f
    const val DP_PAD_BUTTON_HORIZ  = 18f

    const val DP_GAP_TIGHT  = 6f
    const val DP_GAP_NORMAL = 12f
    const val DP_GAP_WIDE   = 20f

    const val DP_BUTTON_HEIGHT_PRIMARY   = 52f
    const val DP_BUTTON_HEIGHT_SECONDARY = 40f

    const val SP_TITLE   = 28f
    const val SP_HEADING = 20f
    const val SP_BODY    = 16f
    const val SP_CAPTION = 13f

    // ---- Conversions -------------------------------------------------------

    fun dp(context: Context, dp: Float): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    fun colorByDifficulty(label: String): Int = when (label.lowercase()) {
        "лёгкая", "легкая" -> COL_ACCENT_GREEN
        "средняя"          -> COL_WARNING
        "высокая"          -> COL_ACCENT_RED
        else               -> COL_TEXT_DIM
    }
}
