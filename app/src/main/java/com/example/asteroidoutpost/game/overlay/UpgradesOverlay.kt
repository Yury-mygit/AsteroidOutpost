package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.GameProgress
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme
import com.example.asteroidoutpost.game.UpgradeType
import com.example.asteroidoutpost.game.Weapon
import com.example.asteroidoutpost.game.WeaponCatalog
import com.example.asteroidoutpost.game.WeaponId

/**
 * «Корабль» (formerly «База») — central screen for the player's ship setup.
 * Shows current metal, then a list of weapon-choice cards: tap any to make
 * it the central turret weapon. Selection persists via [onWeaponPick] which
 * the Activity routes through ProgressRepository.update {...}. Mission start
 * no longer asks for a weapon — it always uses the saved one.
 */
fun buildUpgrades(
    context: Context,
    progress: GameProgress,
    onPurchase: (UpgradeType, Int) -> Unit,
    onWeaponPick: (WeaponId) -> Unit,
    onBack: () -> Unit,
): View {
    val (outer, content, _) = makeOverlay(
        context,
        OverlayOpts(scrim = false, scrollable = true),
    )

    content.addView(UiHelpers.buildTitle(context, "Корабль"))
    content.addView(
        UiHelpers.buildHeading(context, "Металл: ${progress.metal}", UiTheme.COL_WARNING).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        },
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Weapon picker section.
    content.addView(
        UiHelpers.buildHeading(context, "Центральная турель"),
        gapParams(context, UiTheme.DP_GAP_WIDE),
    )
    content.addView(
        UiHelpers.buildBody(
            context,
            "Выбранное оружие используется во всех миссиях. Выбор сохраняется.",
            UiTheme.COL_TEXT_DIM,
        ),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    for (weapon in WeaponCatalog.ALL) {
        content.addView(
            buildWeaponPickerCard(
                context,
                weapon,
                selected = weapon.id == progress.selectedWeaponId,
                onPick   = onWeaponPick,
            ),
            gapParams(context, UiTheme.DP_GAP_NORMAL),
        )
    }

    attachFloatingBackButton(context, outer, onBack)
    return outer
}

private fun buildWeaponPickerCard(
    context: Context,
    weapon: Weapon,
    selected: Boolean,
    onPick: (WeaponId) -> Unit,
): View {
    val card = UiHelpers.buildCard(context, raised = selected)

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

    card.addView(
        UiHelpers.buildBody(context, weapon.description),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    val rps = if (weapon.fireIntervalSec > 0f) 1f / weapon.fireIntervalSec else 0f
    val rateLabel = if (rps >= 2f) "%.1f выстр/сек".format(rps)
                    else "1 выстрел в %.1f сек".format(weapon.fireIntervalSec)
    val dmgLabel  = "урон ×${"%.1f".format(weapon.damageMultiplier)}"
    val aoeLabel  = if (weapon.aoeRadius > 0f) "  •  AoE радиус ${"%.1f".format(weapon.aoeRadius)}" else ""
    card.addView(
        UiHelpers.buildCaption(context, "$rateLabel  •  $dmgLabel$aoeLabel"),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    val btn = if (selected) {
        UiHelpers.buildDisabledButton(context, "Выбрано")
    } else {
        UiHelpers.buildPrimaryButton(context, "Выбрать") { onPick(weapon.id) }
    }
    card.addView(
        btn,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL) },
    )
    return card
}
