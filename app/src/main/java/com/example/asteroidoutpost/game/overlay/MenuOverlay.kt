package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme
import com.example.asteroidoutpost.game.ui.icons.makeSettingsIcon

/**
 * Main menu overlay: title at top, a body line in the middle (mutated
 * via [setMenuBody]) and a horizontal row of equal-width primary buttons
 * pinned to the bottom of the screen. Optional top-right "✕" closes the
 * app. Additional buttons can be appended to the bottom row via
 * [addMenuButton].
 */

private const val TAG_BODY        = "overlay-body"
private const val TAG_BUTTON_ROW  = "overlay-button-row"
// Bottom row: fixed widths (so "Миссии" and "База" sit visually equal),
// wider gap between them, lifted off the bottom edge of the screen.
private const val DP_MENU_BUTTON_W      = 140f
private const val DP_MENU_BUTTON_GAP    = 32f
private const val DP_MENU_BUTTON_BOTTOM = 12f    // hugging the bottom edge of the screen

fun buildMenu(
    context: Context,
    title: String,
    buttonText: String,
    onClose: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onClick: () -> Unit,
): View {
    // Transparent root — the live base scene shows through behind the
    // title and bottom button row, same construction-view feel as «База».
    val (outer, content, _) = makeOverlay(
        context,
        OverlayOpts(scrim = false, centred = false),
    )
    // Top row: optional close (left) + optional settings gear (right).
    // Always rendered together as a horizontal row at the top so neither
    // overlaps the title centre.
    if (onClose != null || onSettings != null) {
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tileSize = UiTheme.dp(context, 36f)
        if (onClose != null) {
            topRow.addView(
                UiHelpers.buildGlyphTile(context, "✕", onClick = onClose),
                LinearLayout.LayoutParams(tileSize, tileSize),
            )
        } else {
            topRow.addView(View(context), LinearLayout.LayoutParams(tileSize, tileSize))
        }
        // Flex spacer between the two tiles.
        topRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (onSettings != null) {
            val gear = makeSettingsIcon(context, sizeDp = 24f, tint = UiTheme.COL_TEXT)
            topRow.addView(
                UiHelpers.buildIconTile(context, gear, sideDp = 36f, onClick = onSettings),
                LinearLayout.LayoutParams(tileSize, tileSize),
            )
        } else {
            topRow.addView(View(context), LinearLayout.LayoutParams(tileSize, tileSize))
        }
        content.addView(topRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }
    content.addView(UiHelpers.buildTitle(context, title))
    content.addView(UiHelpers.buildBody(context, "").apply {
        tag = TAG_BODY
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }, gapParams(context, UiTheme.DP_GAP_NORMAL))
    // Flex spacer — pushes the button row to the bottom of the overlay.
    content.addView(View(context), LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
    ))
    val buttonRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        tag = TAG_BUTTON_ROW
    }
    buttonRow.addView(
        UiHelpers.buildPrimaryButton(context, buttonText, onClick = onClick),
        menuButtonRowParams(context, first = true),
    )
    content.addView(buttonRow, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = UiTheme.dp(context, DP_MENU_BUTTON_BOTTOM)
    })
    return outer
}

fun setMenuBody(overlay: View, body: String) {
    val view = overlay.findViewWithTag<TextView>(TAG_BODY) ?: return
    view.text = body
    view.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
}

/**
 * Append another primary button to the menu's bottom button row. All
 * buttons in the menu row share the same blue accent and fixed width
 * so they read as a uniform pair.
 */
fun addMenuButton(overlay: View, text: String, onClick: () -> Unit) {
    val row = overlay.findViewWithTag<LinearLayout>(TAG_BUTTON_ROW) ?: return
    row.addView(
        UiHelpers.buildPrimaryButton(overlay.context, text, onClick = onClick),
        menuButtonRowParams(overlay.context, first = false),
    )
}

private fun menuButtonRowParams(
    context: Context,
    first: Boolean,
): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
    UiTheme.dp(context, DP_MENU_BUTTON_W),
    LinearLayout.LayoutParams.WRAP_CONTENT,
).apply {
    if (!first) marginStart = UiTheme.dp(context, DP_MENU_BUTTON_GAP)
}
