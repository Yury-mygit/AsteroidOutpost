package com.example.asteroidoutpost.game

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Builds full-screen overlays in the Outpost sci-fi style. All concrete colours,
 * sizes, paddings and button looks come from [UiTheme] / [UiHelpers] — keep
 * this file free of magic numbers.
 *
 * Public API surface (used by MainActivity):
 *   build(ctx, title, btn, onClick)            -> simple title+body+button overlay
 *   setBody(view, body)                        -> update the body of a build() overlay
 *   addButton(view, label, onClick)            -> append a secondary button
 *   buildMissionList(ctx, missions, ...)       -> mission select with cards
 *   buildEndOfMission(ctx, title, body, btns)  -> win / lose result screen
 *   buildUpgrades(ctx, progress, ...)          -> upgrades screen
 */
object OverlayFactory {

    private const val TAG_BODY = "overlay-body"

    // ---------------------------------------------------------------------
    // Simple title + body + primary button overlay (used for the main menu).
    // ---------------------------------------------------------------------
    fun build(
        context: Context,
        title: String,
        buttonText: String,
        onClick: () -> Unit,
    ): View {
        val root = makeOverlayRoot(context, centred = true)
        root.addView(UiHelpers.buildTitle(context, title))
        root.addView(UiHelpers.buildBody(context, "").apply {
            tag = TAG_BODY
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
        }, gapParams(context, UiTheme.DP_GAP_NORMAL))
        root.addView(
            UiHelpers.buildPrimaryButton(context, buttonText, onClick = onClick),
            gapParams(context, UiTheme.DP_GAP_WIDE),
        )
        return root
    }

    fun setBody(overlay: View, body: String) {
        val view = overlay.findViewWithTag<TextView>(TAG_BODY) ?: return
        view.text = body
        view.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Append a secondary (outlined) button to a build()-style overlay. */
    fun addButton(overlay: View, text: String, onClick: () -> Unit) {
        val ll = overlay as? LinearLayout ?: return
        ll.addView(
            UiHelpers.buildSecondaryButton(ll.context, text, onClick = onClick),
            gapParams(ll.context, UiTheme.DP_GAP_NORMAL),
        )
    }

    // ---------------------------------------------------------------------
    // End-of-mission overlay (win / lose).
    // ---------------------------------------------------------------------
    fun buildEndOfMission(
        context: Context,
        title: String,
        subtitle: String? = null,
        stats: List<Pair<String, String>> = emptyList(),
        motivation: String? = null,
        accent: Int = UiTheme.COL_ACCENT_BLUE,
        buttons: List<Pair<String, () -> Unit>>,
    ): View {
        val root = makeOverlayRoot(context, centred = true)

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
        return root
    }

    // ---------------------------------------------------------------------
    // Mission select.
    // ---------------------------------------------------------------------
    fun buildMissionList(
        context: Context,
        missions: List<MissionConfig>,
        onStart: (MissionConfig) -> Unit,
        onBack:  () -> Unit,
    ): View {
        val (outer, content) = makeScrollableOverlayRoot(context)
        content.addView(UiHelpers.buildTitle(context, "Выбор миссии"))
        for (mission in missions) {
            content.addView(
                buildMissionCard(context, mission, onStart),
                gapParams(context, UiTheme.DP_GAP_NORMAL),
            )
        }
        content.addView(
            UiHelpers.buildSecondaryButton(context, "Назад", onClick = onBack),
            gapParams(context, UiTheme.DP_GAP_WIDE),
        )
        return outer
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
        // Description.
        card.addView(
            UiHelpers.buildBody(context, mission.description),
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

    // ---------------------------------------------------------------------
    // Weapon select (between mission select and game start).
    // ---------------------------------------------------------------------
    fun buildWeaponSelect(
        context: Context,
        weapons: List<Weapon>,
        currentWeaponId: WeaponId,
        onChoose: (Weapon) -> Unit,
        onBack:   () -> Unit,
    ): View {
        val (outer, content) = makeScrollableOverlayRoot(context)
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
        content.addView(
            UiHelpers.buildSecondaryButton(context, "Назад", onClick = onBack),
            gapParams(context, UiTheme.DP_GAP_WIDE),
        )
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

    // ---------------------------------------------------------------------
    // Upgrades.
    // ---------------------------------------------------------------------
    fun buildUpgrades(
        context: Context,
        progress: GameProgress,
        onPurchase: (UpgradeType, Int) -> Unit,
        onBack: () -> Unit,
    ): View {
        val (outer, content) = makeScrollableOverlayRoot(context)
        content.addView(UiHelpers.buildTitle(context, "Улучшения"))
        content.addView(
            UiHelpers.buildHeading(context, "Металл: ${progress.metal}", UiTheme.COL_WARNING).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
            gapParams(context, UiTheme.DP_GAP_TIGHT),
        )
        for (type in UpgradeType.values()) {
            content.addView(
                buildUpgradeCard(context, type, progress, onPurchase),
                gapParams(context, UiTheme.DP_GAP_NORMAL),
            )
        }
        content.addView(
            UiHelpers.buildSecondaryButton(context, "Назад", onClick = onBack),
            gapParams(context, UiTheme.DP_GAP_WIDE),
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
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
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

    // ---------------------------------------------------------------------
    // Internal helpers.
    // ---------------------------------------------------------------------

    private fun makeOverlayRoot(context: Context, centred: Boolean): LinearLayout {
        val padH = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_HORIZ)
        val padV = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_VERT)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (centred) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isClickable = true   // swallow taps so they don't reach the engine
            setPadding(padH, padV * 3, padH, padV)
            UiHelpers.overlayBackground(this)
        }
    }

    /**
     * Scrollable overlay root for screens whose content can overflow the
     * viewport (mission list, weapon list, upgrades). Returns the outer
     * ScrollView (add to the root view) and the inner LinearLayout (add
     * children to it). The outer view holds the dark scrim background and
     * swallows taps; the inner layout has the standard overlay padding.
     */
    private data class ScrollableRoot(val outer: View, val content: LinearLayout)

    private fun makeScrollableOverlayRoot(context: Context): ScrollableRoot {
        val padH = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_HORIZ)
        val padV = UiTheme.dp(context, UiTheme.DP_PAD_OVERLAY_VERT)
        val scroll = ScrollView(context).apply {
            isClickable = true
            isFillViewport = true   // background fills even when content is short
            UiHelpers.overlayBackground(this)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(padH, padV * 3, padH, padV)
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ScrollableRoot(scroll, content)
    }

    private fun gapParams(ctx: Context, topMarginDp: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = UiTheme.dp(ctx, topMarginDp) }
}
