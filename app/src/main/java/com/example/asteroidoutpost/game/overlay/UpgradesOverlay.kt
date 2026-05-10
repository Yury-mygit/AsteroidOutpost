package com.example.asteroidoutpost.game.overlay

import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.asteroidoutpost.game.GameProgress
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme
import com.example.asteroidoutpost.game.UpgradeCatalog
import com.example.asteroidoutpost.game.UpgradeType
import com.example.asteroidoutpost.game.ui.icons.makeBackIcon

/**
 * "База" — base upgrades view. Transparent root (no scrim) so the player
 * keeps seeing the actual base behind the upgrade cards; a top-left back
 * chevron replaces the bottom "Назад" button. Each upgrade track gets its
 * own card showing current level, next-level preview value, cost and a
 * primary action button.
 */
fun buildUpgrades(
    context: Context,
    progress: GameProgress,
    onPurchase: (UpgradeType, Int) -> Unit,
    onBack: () -> Unit,
): View {
    // База is a "construction view" preview — we want the player to keep
    // seeing the actual base behind the upgrade cards, not a dimmed
    // scrim. The top-left back chevron is added as a floating sibling
    // of the scroll inside `outer` (a FrameLayout).
    val (outer, content, _) = makeOverlay(
        context,
        OverlayOpts(scrim = false, scrollable = true),
    )

    content.addView(UiHelpers.buildTitle(context, "База"))
    content.addView(
        UiHelpers.buildHeading(context, "Металл: ${progress.metal}", UiTheme.COL_WARNING).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        },
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    // Upgrade cards intentionally hidden — the construction-view UI is
    // being rethought (player will tap base objects to bring up
    // contextual cards). Keeping `buildUpgradeCard` + `onPurchase` wired
    // so the data layer doesn't bit-rot while the new flow is designed.

    // Top-left back chevron — replaces the bottom "Назад" button.
    // Outlined-tile look matches HUD's abort ✕ and the menu close
    // button; tap returns to the caller.
    outer.addView(
        UiHelpers.buildIconTile(
            context,
            makeBackIcon(context, 22f, UiTheme.COL_TEXT_DIM),
            onClick = onBack,
        ),
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply {
            val side = UiTheme.dp(context, 12f)
            val topM = UiTheme.dp(context, 16f)
            setMargins(side, topM, 0, 0)
        },
    )
    return outer
}

private fun buildUpgradeCard(
    context: Context,
    type: UpgradeType,
    progress: GameProgress,
    onPurchase: (UpgradeType, Int) -> Unit,
): View {
    val level  = UpgradeCatalog.levelOf(progress, type)
    val cost   = UpgradeCatalog.costToNext(type, level)
    val maxed  = cost == null
    val canBuy = !maxed && progress.metal >= cost!!

    val card = UiHelpers.buildCard(context)

    // Top row: coloured icon-square placeholder (left) + name + level badge (right).
    val top = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val iconSize = UiTheme.dp(context, 36f)
    val iconColour = upgradeIconColour(type)
    val icon = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(context, UiTheme.DP_BUTTON_RADIUS).toFloat()
            setColor(iconColour)
        }
    }
    top.addView(
        icon,
        LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = UiTheme.dp(context, UiTheme.DP_GAP_NORMAL)
        },
    )
    top.addView(
        UiHelpers.buildHeading(context, UpgradeCatalog.displayName(type)),
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    top.addView(
        UiHelpers.buildPill(
            context,
            if (maxed) "MAX" else "Ур. $level/${UpgradeCatalog.MAX_LEVEL}",
            if (maxed) UiTheme.COL_ACCENT_GREEN else UiTheme.COL_PANEL_BG_HI,
        ),
    )
    card.addView(top)

    // Big value-transition line: "15 → 22"
    val transition = if (maxed)
        UpgradeCatalog.previewValue(type, level)
    else
        "${UpgradeCatalog.previewValue(type, level)}   →   ${UpgradeCatalog.previewValue(type, level + 1)}"
    card.addView(
        UiHelpers.buildHeading(context, transition).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        },
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )

    // Effect caption.
    card.addView(
        UiHelpers.buildCaption(context, UpgradeCatalog.effectDescription(type)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        },
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )

    // Cost line — coloured warning when not affordable.
    if (!maxed) {
        val costColor = if (canBuy) UiTheme.COL_TEXT_DIM else UiTheme.COL_WARNING
        card.addView(
            UiHelpers.buildBody(context, "Цена: $cost металла", costColor).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            gapParams(context, UiTheme.DP_GAP_TIGHT),
        )
    }

    // Action button — full-width primary / disabled / max.
    val btn = when {
        maxed  -> UiHelpers.buildDisabledButton(context, "Максимум")
        canBuy -> UiHelpers.buildPrimaryButton(
            context, "Улучшить", UiTheme.COL_ACCENT_BLUE,
        ) { onPurchase(type, cost!!) }
        else   -> UiHelpers.buildDisabledButton(context, "Недостаточно металла")
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

private fun upgradeIconColour(type: UpgradeType): Int = when (type) {
    UpgradeType.MAIN_WEAPON_DAMAGE -> UiTheme.COL_ACCENT_RED
    UpgradeType.BASE_HP            -> UiTheme.COL_TEXT_DIM
    UpgradeType.SIDE_TURRET_DAMAGE -> UiTheme.COL_ACCENT_BLUE
}
