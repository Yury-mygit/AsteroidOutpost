package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Mission hub — first screen after «Миссии». Two big primary buttons:
 * Campaign (story missions in a graph) and Random missions (procedural,
 * placeholder for now). Tiny screen — no scroll, no card chrome.
 */
fun buildMissionHub(
    context: Context,
    onCampaign: () -> Unit,
    onRandom:   () -> Unit,
    onBack:     () -> Unit,
): View {
    val overlay = makeOverlay(context, OverlayOpts(centred = false))
    overlay.content.addView(UiHelpers.buildTitle(context, "Выбор миссии"))

    val campaignBtn = UiHelpers.buildPrimaryButton(context, "Кампания", onClick = onCampaign)
    overlay.content.addView(
        campaignBtn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_WIDE) },
    )

    val randomBtn = UiHelpers.buildPrimaryButton(context, "Случайные миссии", onClick = onRandom)
    overlay.content.addView(
        randomBtn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL) },
    )

    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}
