package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme
import com.example.asteroidoutpost.game.Weapon
import com.example.asteroidoutpost.game.WeaponId

/**
 * Pre-mission weapon picker. Scrollable list of `Weapon` cards; the
 * currently equipped one is highlighted with a "Выбрано" pill but its
 * card is still tappable (re-confirms the same weapon).
 */
fun buildWeaponSelect(
    context: Context,
    weapons: List<Weapon>,
    currentWeaponId: WeaponId,
    onChoose: (Weapon) -> Unit,
    onBack:   () -> Unit,
): View {
    val (outer, content, _) = makeOverlay(context, OverlayOpts(scrollable = true))
    content.addView(UiHelpers.buildTitle(context, "Выбор оружия"))
    content.addView(
        UiHelpers.buildBody(
            context,
            "Главное оружие центральной турели на эту миссию.",
            UiTheme.COL_TEXT_DIM,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL },
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    for (weapon in weapons) {
        content.addView(
            buildWeaponCard(context, weapon, weapon.id == currentWeaponId, onChoose),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }
    attachFloatingBackButton(context, outer, onBack)
    return outer
}

private fun buildWeaponCard(
    context: Context,
    weapon: Weapon,
    selected: Boolean,
    onChoose: (Weapon) -> Unit,
): View {
    val card = UiHelpers.buildCard(context, raised = selected)

    // Header: weapon name (left) + "Выбрано" pill if active.
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(
        UiHelpers.buildHeading(context, weapon.displayName),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    if (selected) {
        header.addView(UiHelpers.buildPill(context, "Выбрано", UiTheme.COL_ACCENT_GREEN))
    }
    card.addView(header)

    // Description.
    card.addView(
        UiHelpers.buildBody(context, weapon.description),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    // Stats line — fire rate, damage feel, AoE if present.
    val rps = if (weapon.fireIntervalSec > 0f) 1f / weapon.fireIntervalSec else 0f
    val rateLabel = if (rps >= 2f) "%.1f выстр/сек".format(rps) else "1 выстрел в %.1f сек".format(weapon.fireIntervalSec)
    val dmgLabel  = "урон ×${"%.1f".format(weapon.damageMultiplier)}"
    val aoeLabel  = if (weapon.aoeRadius > 0f) "  •  AoE радиус ${"%.1f".format(weapon.aoeRadius)}" else ""
    card.addView(
        UiHelpers.buildCaption(context, "$rateLabel  •  $dmgLabel$aoeLabel"),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Choose button — always enabled. Tapping any weapon's button starts
    // the mission with that weapon, including the one already marked as
    // "Выбрано" (the pill is purely an indicator of the prior selection).
    val btn = UiHelpers.buildPrimaryButton(context, "Выбрать") { onChoose(weapon) }
    card.addView(
        btn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL) },
    )
    return card
}
