package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Mission detail screen — surfaces after the player picks a mission circle
 * in the Campaign graph. Shows the mission's name, difficulty pill, waves
 * count + reward stats, and the description (when one is set). Bottom
 * Start button drops into weapon-select.
 *
 * Same content as the cards in the (legacy) `buildMissionList`, but laid
 * out as a single full-screen detail view instead of one of many in a
 * scroll list.
 */
fun buildMissionDetail(
    context: Context,
    mission: MissionConfig,
    onStart: (MissionConfig) -> Unit,
    onBack:  () -> Unit,
): View {
    val overlay = makeOverlay(
        context,
        OverlayOpts(scrollable = true, footer = true, centred = false),
    )

    // Header row: number on left, difficulty pill on right.
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
        UiHelpers.buildCaption(context, "№ ${mission.id}"),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    header.addView(
        UiHelpers.buildPill(
            context,
            mission.difficulty,
            UiTheme.colorByDifficulty(mission.difficulty),
        ),
    )
    overlay.content.addView(header)

    // Mission name as the main heading.
    overlay.content.addView(
        UiHelpers.buildTitle(context, mission.name),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Stats — wave count + reward.
    val stats = "Волны: ${mission.waves.size}  •  Награда: +20 бонус"
    overlay.content.addView(
        UiHelpers.buildCaption(context, stats),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Description block — wrapped body text. MissionConfig.description may
    // be empty for legacy missions; in that case skip without an empty card.
    val desc = mission.description
    if (desc.isNotEmpty()) {
        overlay.content.addView(
            UiHelpers.buildBody(context, desc),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }

    // Footer: Start (primary) only — Back affordance lives as the floating
    // chevron in the bottom-right corner (consistent across all screens).
    val footer = overlay.footer!!
    val startBtn = UiHelpers.buildPrimaryButton(context, "Старт") { onStart(mission) }
    footer.addView(
        startBtn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
    )
    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}
