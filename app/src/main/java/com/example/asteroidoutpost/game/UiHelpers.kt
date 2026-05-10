package com.example.asteroidoutpost.game

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Programmatic UI helpers built on top of [UiTheme]. Used by [OverlayFactory]
 * and [com.example.asteroidoutpost.MainActivity] to produce a consistent
 * sci-fi look without XML drawables.
 *
 * Naming convention: build* returns a new View. style* mutates an existing view.
 */
object UiHelpers {

    // ---- Panels & cards ----------------------------------------------------

    /** Full-screen overlay background — near-opaque dark scrim. */
    fun overlayBackground(view: View) {
        view.setBackgroundColor(UiTheme.COL_OVERLAY_BG)
    }

    /** Style a view as a sci-fi panel (rounded, dark fill, faint border). */
    fun stylePanel(view: View, raised: Boolean = false) {
        val ctx = view.context
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(ctx, UiTheme.DP_PANEL_RADIUS).toFloat()
            setColor(if (raised) UiTheme.COL_PANEL_BG_HI else UiTheme.COL_PANEL_BG)
            setStroke(UiTheme.dp(ctx, UiTheme.DP_BORDER_WIDTH), UiTheme.COL_BORDER)
        }
        view.background = drawable
        val pad = UiTheme.dp(ctx, UiTheme.DP_PAD_CARD)
        view.setPadding(pad, pad, pad, pad)
    }

    /** Build a vertical card container with the panel look. Caller adds children. */
    fun buildCard(context: Context, raised: Boolean = false): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        stylePanel(card, raised)
        return card
    }

    // ---- Buttons -----------------------------------------------------------

    /** Solid-fill primary button (accent colour background, white text). */
    fun buildPrimaryButton(
        context: Context,
        label: String,
        accent: Int = UiTheme.COL_ACCENT_BLUE,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        textSize = UiTheme.SP_BODY
        setTextColor(UiTheme.COL_TEXT)
        isAllCaps = false
        background = filledButtonDrawable(context, accent)
        val padH = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_HORIZ)
        val padV = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_VERT)
        setPadding(padH, padV, padH, padV)
        minHeight = UiTheme.dp(context, UiTheme.DP_BUTTON_HEIGHT_PRIMARY)
        setOnClickListener { onClick() }
    }

    /** Outlined secondary button (transparent fill, accent border + text). */
    fun buildSecondaryButton(
        context: Context,
        label: String,
        accent: Int = UiTheme.COL_BORDER_HI,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        textSize = UiTheme.SP_BODY
        setTextColor(UiTheme.COL_TEXT)
        isAllCaps = false
        background = outlinedButtonDrawable(context, accent)
        val padH = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_HORIZ)
        val padV = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_VERT)
        setPadding(padH, padV, padH, padV)
        minHeight = UiTheme.dp(context, UiTheme.DP_BUTTON_HEIGHT_SECONDARY)
        setOnClickListener { onClick() }
    }

    // ---- Icon tiles --------------------------------------------------------

    /**
     * Square outlined-tile button rendered as a centred text glyph (e.g. "✕").
     * Caller sets the actual square dimensions via LayoutParams. Used for
     * close (✕) buttons in overlays and the in-game abort ✕.
     */
    fun buildGlyphTile(
        context: Context,
        glyph: String,
        textSize: Float = UiTheme.SP_HEADING,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = glyph
        this.textSize = textSize
        setTextColor(UiTheme.COL_TEXT_DIM)
        gravity = Gravity.CENTER
        isAllCaps = false
        background = iconTileBackground(context)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /**
     * Square outlined-tile button rendered around a Drawable icon. Caller
     * sets the actual square dimensions via LayoutParams; the icon is
     * centre-cropped within. Used for nav chevrons and (future) action
     * shortcuts.
     */
    fun buildIconTile(
        context: Context,
        icon: Drawable,
        sideDp: Float = 36f,
        onClick: () -> Unit,
    ): ImageView = ImageView(context).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.CENTER
        val side = UiTheme.dp(context, sideDp)
        minimumWidth  = side
        minimumHeight = side
        background = iconTileBackground(context)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun iconTileBackground(context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(context, UiTheme.DP_BUTTON_RADIUS).toFloat()
            setColor(UiTheme.COL_PANEL_BG)
            setStroke(UiTheme.dp(context, UiTheme.DP_BORDER_WIDTH), UiTheme.COL_BORDER)
        }

    /** Disabled-look variant — caller still sets isEnabled=false. */
    fun buildDisabledButton(context: Context, label: String): Button = Button(context).apply {
        text = label
        textSize = UiTheme.SP_BODY
        setTextColor(UiTheme.COL_TEXT_DISABLED)
        isAllCaps = false
        background = filledButtonDrawable(context, UiTheme.COL_PANEL_BG_HI)
        val padH = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_HORIZ)
        val padV = UiTheme.dp(context, UiTheme.DP_PAD_BUTTON_VERT)
        setPadding(padH, padV, padH, padV)
        minHeight = UiTheme.dp(context, UiTheme.DP_BUTTON_HEIGHT_PRIMARY)
        isEnabled = false
    }

    // ---- Texts -------------------------------------------------------------

    fun buildTitle(context: Context, text: String, accent: Int = UiTheme.COL_TEXT): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            textSize = UiTheme.SP_TITLE
            gravity = Gravity.CENTER_HORIZONTAL
        }

    fun buildHeading(context: Context, text: String, accent: Int = UiTheme.COL_TEXT): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            textSize = UiTheme.SP_HEADING
        }

    fun buildBody(context: Context, text: String, accent: Int = UiTheme.COL_TEXT_DIM): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            textSize = UiTheme.SP_BODY
        }

    fun buildCaption(context: Context, text: String, accent: Int = UiTheme.COL_TEXT_DIM): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(accent)
            textSize = UiTheme.SP_CAPTION
        }

    /** Coloured "pill" — small rounded label, e.g. for difficulty tags. */
    fun buildPill(context: Context, text: String, fill: Int): TextView = TextView(context).apply {
        this.text = text
        setTextColor(UiTheme.COL_TEXT)
        textSize = UiTheme.SP_CAPTION
        val pad = UiTheme.dp(context, UiTheme.DP_GAP_TIGHT)
        setPadding(pad * 2, pad, pad * 2, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL).toFloat()
            setColor(fill)
        }
    }

    // ---- Layout params helpers --------------------------------------------

    fun lpVertical(topMarginDp: Float = 0f, ctx: Context): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(ctx, topMarginDp) }

    // ---- Drawables ---------------------------------------------------------

    private fun filledButtonDrawable(context: Context, fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(context, UiTheme.DP_BUTTON_RADIUS).toFloat()
            setColor(fill)
        }

    private fun outlinedButtonDrawable(context: Context, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(context, UiTheme.DP_BUTTON_RADIUS).toFloat()
            setColor(0x00000000)
            setStroke(UiTheme.dp(context, UiTheme.DP_BORDER_WIDTH), stroke)
        }
}
