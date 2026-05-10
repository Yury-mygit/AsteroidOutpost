package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.asteroidoutpost.game.MissionConfig
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Campaign view — vertical zigzag of numbered colour-coded circles, one per
 * mission. Each circle is a clickable button: tap reveals the mission detail
 * screen (with description + Start). Difficulty is the only signal on the
 * graph itself — no labels, no extra chrome — so the player reads the
 * progression at a glance: easy/green → harder/orange/red.
 *
 * The zigzag (alternating left/right alignment per row) gives the graph
 * organic shape without needing a custom drawing surface for connector
 * lines; just regular Android views in a vertical LinearLayout.
 */
fun buildCampaign(
    context: Context,
    missions: List<MissionConfig>,
    onPick: (MissionConfig) -> Unit,
    onBack: () -> Unit,
): View {
    val overlay = makeOverlay(
        context,
        OverlayOpts(scrollable = true, centred = false),
    )
    overlay.content.addView(UiHelpers.buildTitle(context, "Кампания"))

    val gapDp = UiTheme.DP_GAP_WIDE
    for ((index, mission) in missions.withIndex()) {
        val rowGravity = if (index % 2 == 0) Gravity.START else Gravity.END
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = rowGravity or Gravity.CENTER_VERTICAL
        }
        row.addView(buildMissionCircle(context, mission, onPick))
        overlay.content.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = UiTheme.dp(context, gapDp) },
        )
    }

    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}

/** A round colour-coded button that shows just the mission's number. */
private fun buildMissionCircle(
    context: Context,
    mission: MissionConfig,
    onPick: (MissionConfig) -> Unit,
): View {
    val sizePx = UiTheme.dp(context, 78f)
    val color  = UiTheme.colorByDifficulty(mission.difficulty)

    // FrameLayout wrapper so the number TextView centres regardless of
    // whether the circle background changes size in future iterations.
    val tile = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            // Subtle dark border so circles read against any backdrop.
            setStroke(UiTheme.dp(context, 2f), UiTheme.COL_BORDER)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onPick(mission) }
    }
    val number = TextView(context).apply {
        text = mission.id.toString()
        setTextColor(UiTheme.COL_TEXT)
        textSize = UiTheme.SP_HEADING * 1.4f
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER }
    }
    tile.addView(number)
    return tile
}
