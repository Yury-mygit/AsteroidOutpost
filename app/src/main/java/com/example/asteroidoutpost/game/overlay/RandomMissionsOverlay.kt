package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Random missions tab — placeholder for now. Procedural mission generator
 * isn't implemented yet; this surface just informs the player and offers
 * a back button. Wire real generation later when the campaign side is
 * locked down.
 */
fun buildRandomMissions(
    context: Context,
    onBack: () -> Unit,
): View {
    val overlay = makeOverlay(context, OverlayOpts(centred = true))
    overlay.content.addView(UiHelpers.buildTitle(context, "Случайные миссии"))
    overlay.content.addView(
        UiHelpers.buildBody(
            context,
            "Раздел в разработке. Скоро здесь появится генератор " +
            "случайных миссий — выбирайте сложность, биом и состав волн.",
        ),
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )
    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}
