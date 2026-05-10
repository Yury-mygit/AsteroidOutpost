package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Mission select overlay: scrollable list of mission cards above a fixed
 * footer with the "Назад" button. The footer stays one tap away regardless
 * of how many cards are loaded.
 */
fun buildMissionList(
    context: Context,
    missions: List<MissionConfig>,
    onStart: (MissionConfig) -> Unit,
    onBack:  () -> Unit,
): View {
    val overlay = makeOverlay(context, OverlayOpts(scrollable = true, footer = true))
    overlay.content.addView(UiHelpers.buildTitle(context, "Выбор миссии"))
    for (mission in missions) {
        overlay.content.addView(
            buildMissionCard(context, mission, onStart),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }
    overlay.footer!!.addView(
        UiHelpers.buildSecondaryButton(context, "Назад", onClick = onBack),
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    return overlay.outer
}

private fun buildMissionCard(
    context: Context,
    mission: MissionConfig,
    onStart: (MissionConfig) -> Unit,
): View {
    val card = UiHelpers.buildCard(context)

    // Header row: "Миссия N" caption (left) + difficulty pill (right).
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
        UiHelpers.buildCaption(context, "Миссия ${mission.id}"),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    header.addView(
        UiHelpers.buildPill(
            context,
            mission.difficulty,
            UiTheme.colorByDifficulty(mission.difficulty),
        ),
    )
    card.addView(header)

    // Mission name as the main heading.
    card.addView(
        UiHelpers.buildHeading(context, mission.name),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    // Stats row: waves count + reward.
    val stats = "Волны: ${mission.waves.size}  •  Награда: +20 бонус"
    card.addView(
        UiHelpers.buildCaption(context, stats),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    // Full-width primary Start button.
    val startBtn = UiHelpers.buildPrimaryButton(context, "Старт") { onStart(mission) }
    card.addView(
        startBtn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL) },
    )
    return card
}
