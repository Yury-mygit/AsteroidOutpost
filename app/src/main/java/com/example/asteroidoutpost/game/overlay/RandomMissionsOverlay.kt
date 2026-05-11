package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.Missions
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Random missions tab — currently a hand-picked list of one-shot events,
 * not a real procedural generator. Each event card opens the standard
 * MissionDetail screen via [onMissionTap].
 *
 * The first event we expose is mission 6 («Маршрут: первый коридор») —
 * the tunnel-mode prototype. Tagged "Событие" + "Однократно" so the
 * player reads it as a special non-repeating run rather than another
 * campaign entry. (Persistent "already played" tracking comes later;
 * for now the card always appears.)
 */
fun buildRandomMissions(
    context: Context,
    onMissionTap: (MissionConfig) -> Unit,
    onBack: () -> Unit,
): View {
    val overlay = makeOverlay(context, OverlayOpts(scrollable = true))
    overlay.content.addView(UiHelpers.buildTitle(context, "Случайные миссии"))
    overlay.content.addView(
        UiHelpers.buildBody(
            context,
            "Особые события вне сюжетной кампании. Каждое — один заход.",
            UiTheme.COL_TEXT_DIM,
        ),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Currently surfaced events: mission 6 (tunnel corridor — one-shot)
    // and mission 7 (combat prototype — repeatable). Mission 7's pill is
    // "Повторяемое" instead of "Однократно" since the player can re-run
    // the fight as practice.
    val eventIds = listOf(
        6 to "Однократно",
        7 to "Повторяемое",
        8 to "Повторяемое",
    )
    var anyShown = false
    for ((id, repeatTag) in eventIds) {
        val event = Missions.ALL.firstOrNull { it.id == id } ?: continue
        overlay.content.addView(
            buildEventCard(context, event, repeatTag, onMissionTap),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
        anyShown = true
    }
    if (!anyShown) {
        overlay.content.addView(
            UiHelpers.buildBody(
                context,
                "Сейчас событий нет. Загляните позже.",
                UiTheme.COL_TEXT_DIM,
            ),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }

    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}

private fun buildEventCard(
    context: Context,
    mission: MissionConfig,
    repeatTag: String,
    onTap: (MissionConfig) -> Unit,
): View {
    val card = UiHelpers.buildCard(context, raised = true)

    // Header row: «Событие» tag (left), «Однократно» tag (right).
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
        UiHelpers.buildPill(context, "Событие", UiTheme.COL_ACCENT_BLUE),
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { weight = 0f },
    )
    header.addView(
        View(context),
        LinearLayout.LayoutParams(0, 1, 1f),
    )
    header.addView(
        UiHelpers.buildPill(context, repeatTag, UiTheme.COL_WARNING),
    )
    card.addView(header)

    card.addView(
        UiHelpers.buildHeading(context, mission.name),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    card.addView(
        UiHelpers.buildBody(context, mission.description),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    val startBtn = UiHelpers.buildPrimaryButton(context, "Открыть") { onTap(mission) }
    card.addView(
        startBtn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL) },
    )
    return card
}
