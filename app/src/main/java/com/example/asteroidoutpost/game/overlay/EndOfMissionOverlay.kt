package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * End-of-mission overlay (win / lose). Title in the result accent (green
 * for win, red for lose), optional subtitle (mission name), a stats panel
 * with label/value rows, an optional motivational line for losses, and
 * a vertical column of buttons — first one rendered as primary in the
 * accent colour, the rest as secondary outlined.
 */
fun buildEndOfMission(
    context: Context,
    title: String,
    subtitle: String? = null,
    stats: List<Pair<String, String>> = emptyList(),
    motivation: String? = null,
    accent: Int = UiTheme.COL_ACCENT_BLUE,
    buttons: List<Pair<String, () -> Unit>>,
): View {
    val (outer, root, _) = makeOverlay(context, OverlayOpts(centred = true))

    // Title in the chosen accent (green for win, red for lose, etc.).
    root.addView(UiHelpers.buildTitle(context, title, accent))

    if (subtitle != null) {
        root.addView(
            UiHelpers.buildHeading(context, subtitle).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            gapParams(context, UiTheme.DP_GAP_TIGHT),
        )
    }

    // Stats as a small panel with label/value rows.
    if (stats.isNotEmpty()) {
        val panel = UiHelpers.buildCard(context, raised = true)
        for ((label, value) in stats) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(
                UiHelpers.buildBody(context, label, UiTheme.COL_TEXT_DIM),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                UiHelpers.buildBody(context, value, UiTheme.COL_TEXT),
            )
            panel.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_TIGHT) },
            )
        }
        root.addView(panel, gapParams(context, UiTheme.DP_GAP_NORMAL))
    }

    if (motivation != null) {
        root.addView(
            UiHelpers.buildBody(context, motivation, UiTheme.COL_WARNING).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }

    // First button is primary (in accent colour), rest are secondary.
    buttons.forEachIndexed { index, (label, onClick) ->
        val btn = if (index == 0) {
            UiHelpers.buildPrimaryButton(context, label, accent, onClick)
        } else {
            UiHelpers.buildSecondaryButton(context, label, onClick = onClick)
        }
        val gap = if (index == 0) UiTheme.DP_GAP_WIDE else UiTheme.DP_GAP_NORMAL
        root.addView(btn, gapParams(context, gap))
    }
    return outer
}
