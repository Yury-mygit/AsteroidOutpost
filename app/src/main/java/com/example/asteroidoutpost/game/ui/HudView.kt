package com.example.asteroidoutpost.game.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.asteroidoutpost.game.AbilityId
import com.example.asteroidoutpost.game.AbilitySlot
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme
import com.example.asteroidoutpost.game.ui.icons.IconDrawable
import com.example.asteroidoutpost.game.ui.icons.ShieldFillDrawable
import com.example.asteroidoutpost.game.ui.icons.makeDroneIcon
import com.example.asteroidoutpost.game.ui.icons.makeLaserIcon
import com.example.asteroidoutpost.game.ui.icons.makeRocketIcon
import com.example.asteroidoutpost.game.ui.icons.makeShieldIcon

/**
 * Owns every in-game HUD widget — top mission/wave/score/HP/energy panel,
 * the centred "Волна N" announce text, the action bar (shield + ability
 * buttons + abort ✕), and the buff indicator. Builds the views, applies
 * Drawables, mutates view state from `refresh*` calls, runs the small
 * UI animations (`pulseBaseDamage`, `announceWave`).
 *
 * Reads game state via the parameters passed to each `refresh*`; never
 * holds a reference to MainActivity or the game loop. The Activity ref
 * is only used for `runOnUiThread` (animations marshal to UI thread) and
 * as a `Context` for view construction / dp conversion.
 *
 * Construction: caller supplies `abilitySlots` (so build/refresh can index
 * by slot) and a `Callbacks` interface for tap events. The slot list is
 * shared by reference with MainActivity / MissionRunner — so when the
 * tick mutates `currentCd`, `refreshAllAbilities()` reads the up-to-date
 * value without extra plumbing.
 *
 * Mounting: caller creates the action-bar `LinearLayout` and appends
 * `shieldButton` + each `abilityButtons[i]` after invoking the
 * corresponding `build*` method. `hudPanel`, `waveAnnounceText` and
 * `buffIndicator` are built here and added to the activity root via
 * dedicated `build*` helpers.
 */
internal class HudView(
    private val activity: Activity,
    private val abilitySlots: List<AbilitySlot>,
    private val callbacks: Callbacks,
) {
    /** Tap callbacks — implemented by MainActivity (game-state gating + thread marshalling). */
    interface Callbacks {
        fun onShieldDown()
        fun onShieldUp()
        fun onAbilityTap(slotIndex: Int)
        fun onAbortMission()
    }

    // Owned views — built lazily by `build*`, exposed read-only so the
    // caller can mount them into the layout but not reassign references.
    lateinit var shieldButton: TextView
        private set
    lateinit var abilityButtons: List<TextView>
        private set
    lateinit var abortButton: TextView
        private set
    lateinit var buffIndicator: TextView
        private set
    lateinit var hudPanel: View
        private set
    lateinit var waveAnnounceText: TextView
        private set

    // Top-HUD text views — owned, mutated only via `refresh*`.
    private lateinit var hudMissionText: TextView
    private lateinit var hudWaveText:    TextView
    private lateinit var hudScoreText:   TextView
    private lateinit var hudHpText:      TextView
    private lateinit var hudEnergyText:  TextView

    // Internal Drawable refs retained so refresh* can mutate state without
    // reallocating (e.g. ShieldFillDrawable keeps its rounded-rect clip
    // path; IconDrawable keeps its sized canvas).
    private var shieldFillBg: ShieldFillDrawable? = null
    private var shieldIcon: IconDrawable? = null
    private val abilityIcons: MutableList<IconDrawable> = mutableListOf()

    // ---- Action-bar widgets -------------------------------------------------

    /**
     * Hold-to-recharge shield button. Background = vertical green/gray
     * fill (driven by `refreshShield`). Icon = V-shaped heater shield
     * silhouette. Touch listener routes ACTION_DOWN/UP to callbacks
     * so MainActivity can flip its `shieldRecharging` flag.
     */
    fun buildShieldButton(): TextView {
        val btn = TextView(activity).apply {
            // No text inside this button — the V-shield icon is the identity,
            // the green/gray fill ratio is the HP read. textSize=0 + empty
            // text removes the line metrics so the icon centres precisely.
            textSize = 0f
            text = ""
            gravity = Gravity.CENTER
            isAllCaps = false
            val pad = UiTheme.dp(activity, 3f)
            setPadding(pad * 2, pad, pad * 2, pad)
            val icon = makeShieldIcon(activity, 22f, UiTheme.COL_TEXT)
            shieldIcon = icon
            setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
            compoundDrawablePadding = 0
            // Background = vertical fill bar (full HP → all green; depleted
            // portion paints from the top in gray). Built once and retained;
            // refreshShield just calls setFraction.
            val fill = ShieldFillDrawable(
                cornerRadiusPx = UiTheme.dp(activity, UiTheme.DP_BUTTON_RADIUS).toFloat(),
                emptyColor     = UiTheme.COL_PANEL_BG_HI,
                fullColor      = UiTheme.COL_ACCENT_GREEN,
            )
            shieldFillBg = fill
            background = fill
            // Hold-to-recharge — touch listener instead of click. ACTION_UP
            // and ACTION_CANCEL both release. Returning false would let the
            // event bubble; we own the gesture so we return true.
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> callbacks.onShieldDown()
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> callbacks.onShieldUp()
                }
                true
            }
        }
        shieldButton = btn
        return btn
    }

    /**
     * Build all ability buttons in one call. Returns the immutable list
     * (also stored on `abilityButtons` for later refresh access).
     */
    fun buildAbilityButtons(): List<TextView> {
        val list = abilitySlots.indices.map { buildOneAbilityButton(it) }
        abilityButtons = list
        return list
    }

    private fun buildOneAbilityButton(slotIndex: Int): TextView {
        return TextView(activity).apply {
            // Default state is icon-only — textSize=0 collapses the line
            // metrics so the icon centres in the button. refreshAbility swaps
            // to text-mode (with SP_CAPTION) when a cooldown number needs to
            // show; the icon and text never share vertical space.
            textSize = 0f
            text = ""
            setTextColor(UiTheme.COL_TEXT)
            gravity = Gravity.CENTER
            isAllCaps = false
            val pad = UiTheme.dp(activity, 3f)
            setPadding(pad * 2, pad, pad * 2, pad)
            val ability = abilitySlots[slotIndex].ability
            val icon = when (ability.id) {
                AbilityId.ROCKET_STRIKE -> makeRocketIcon(activity, 22f, UiTheme.COL_TEXT)
                AbilityId.LASER_STRIKE  -> makeLaserIcon(activity, 22f, UiTheme.COL_TEXT)
                AbilityId.DRONES        -> makeDroneIcon(activity, 22f, UiTheme.COL_TEXT)
            }
            while (abilityIcons.size <= slotIndex) abilityIcons.add(icon)
            abilityIcons[slotIndex] = icon
            setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
            compoundDrawablePadding = 0
            setOnClickListener { callbacks.onAbilityTap(slotIndex) }
        }
    }

    /**
     * Small "✕" button that can be embedded in a HUD row or floated on
     * its own. `buildHudPanel` embeds it as the rightmost child of the
     * top row so it shares visibility with the HUD.
     */
    fun buildAbortButton(): TextView {
        val btn = UiHelpers.buildGlyphTile(activity, "✕") { callbacks.onAbortMission() }
        abortButton = btn
        return btn
    }

    /**
     * Buff indicator — small caption that appears under the HUD while a
     * buff (currently only ENERGY-asteroid main-weapon ×2) is active.
     * Hidden by default; `refreshBuff` toggles visibility + label.
     */
    fun buildBuffIndicator(): TextView {
        val view = TextView(activity).apply {
            text = ""
            setTextColor(UiTheme.COL_WARNING)
            textSize = UiTheme.SP_BODY
            visibility = View.GONE
        }
        buffIndicator = view
        return view
    }

    // ---- Top HUD panel ------------------------------------------------------

    /**
     * Top horizontal panel: left column (mission name + wave label) + right
     * column (score / HP / energy) + abort ✕ embedded as the rightmost
     * child. Background-less so it doesn't compete with gameplay; text
     * sizes 30% smaller than regular sci-fi typography for a glanceable
     * readout.
     */
    fun buildHudPanel(): View {
        val theme = UiTheme
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val hudScale = 0.7f

        // Left column: mission name (caption) + wave (heading).
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        hudMissionText = UiHelpers.buildCaption(activity, "")
            .apply { textSize = theme.SP_CAPTION * hudScale }
        hudWaveText = UiHelpers.buildHeading(activity, "")
            .apply { textSize = theme.SP_HEADING * hudScale }
        left.addView(hudMissionText)
        left.addView(hudWaveText)

        // Right column: score + HP + energy, right-aligned.
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        hudScoreText = UiHelpers.buildHeading(activity, "Score: 0")
            .apply { gravity = Gravity.END; textSize = theme.SP_HEADING * hudScale }
        hudHpText = UiHelpers.buildBody(activity, "HP: 100", theme.COL_TEXT)
            .apply { gravity = Gravity.END; textSize = theme.SP_BODY * hudScale }
        // Energy — fuel for active abilities. Cyan/blue accent so it reads
        // distinct from white HP (which animates red on damage).
        hudEnergyText = UiHelpers.buildBody(
            activity,
            "⚡ 100/100",
            theme.COL_ACCENT_BLUE,
        ).apply { gravity = Gravity.END; textSize = theme.SP_BODY * hudScale }
        right.addView(hudScoreText)
        right.addView(hudHpText)
        right.addView(hudEnergyText)

        panel.addView(
            left,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        panel.addView(
            right,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // Abort ✕ — embedded in the HUD row so it sits inside the (invisible)
        // panel contour rather than floating over the scene.
        val abortBtn = buildAbortButton().apply {
            textSize = theme.SP_HEADING * hudScale
        }
        val abortSize = theme.dp(activity, 36f)
        val abortLp = LinearLayout.LayoutParams(abortSize, abortSize).apply {
            marginStart = theme.dp(activity, theme.DP_GAP_TIGHT)
        }
        panel.addView(abortBtn, abortLp)

        hudPanel = panel
        return panel
    }

    /** Big centred "Волна N" / "Финальная волна" announce text — fades in/out. */
    fun buildWaveAnnounce(): TextView {
        val view = TextView(activity).apply {
            text = ""
            setTextColor(UiTheme.COL_ACCENT_BLUE)
            textSize = 44f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        waveAnnounceText = view
        return view
    }

    // ---- Refresh ------------------------------------------------------------

    /** Show/hide the entire top HUD panel (used on game-state transitions). */
    fun setHudVisible(visible: Boolean) {
        if (::hudPanel.isInitialized) {
            hudPanel.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun refreshScore(score: Int) {
        if (::hudScoreText.isInitialized) hudScoreText.text = "Score: $score"
    }

    fun refreshHp(hp: Int) {
        if (::hudHpText.isInitialized) hudHpText.text = "HP: $hp"
    }

    fun refreshEnergy(energy: Float, max: Float) {
        if (::hudEnergyText.isInitialized) {
            hudEnergyText.text = "⚡ ${energy.toInt()}/${max.toInt()}"
        }
    }

    fun refreshMissionLabel(name: String) {
        if (::hudMissionText.isInitialized) hudMissionText.text = name
    }

    fun refreshWaveLabel(text: String) {
        if (::hudWaveText.isInitialized) hudWaveText.text = text
    }

    /**
     * Shield button — shows current HP as the green/gray vertical split
     * of the background fill: full HP → all green, fully spent → all gray,
     * top descending toward the bottom in proportion to damage taken.
     * Icon tint stays white — readable on both halves of the bar.
     */
    fun refreshShield(shieldHp: Float, shieldMaxHp: Float) {
        val frac = (shieldHp / shieldMaxHp).coerceIn(0f, 1f)
        shieldFillBg?.setFraction(frac)
        shieldIcon?.setIconTint(UiTheme.COL_TEXT)
    }

    /**
     * Apply current ability slot state to its button. States:
     *  - COOLING            (currentCd > 0): dim panel fill, "${sec}с" caption
     *  - INSUFFICIENT-ENERGY (energy < cost): dim, icon-only, disabled
     *  - READY              (otherwise): blue accent, icon-only, enabled
     */
    fun refreshAbility(slotIndex: Int, energy: Float) {
        if (slotIndex !in abilitySlots.indices) return
        if (!::abilityButtons.isInitialized) return
        val slot  = abilitySlots[slotIndex]
        val btn   = abilityButtons[slotIndex]
        val icon  = abilityIcons.getOrNull(slotIndex)
        val a     = slot.ability
        val cooldownText: String?
        val bgFill: Int
        val tint:   Int
        val enabled: Boolean
        when {
            slot.currentCd > 0f -> {
                val sec = kotlin.math.ceil(slot.currentCd.toDouble()).toInt()
                    .coerceAtLeast(1)
                cooldownText = "${sec}с"
                bgFill  = UiTheme.COL_PANEL_BG_HI
                tint    = UiTheme.COL_TEXT_DISABLED
                enabled = false
            }
            energy < a.cost -> {
                cooldownText = null
                bgFill  = UiTheme.COL_PANEL_BG_HI
                tint    = UiTheme.COL_TEXT_DISABLED
                enabled = false
            }
            else -> {
                cooldownText = null
                bgFill  = UiTheme.COL_ACCENT_BLUE
                tint    = UiTheme.COL_TEXT
                enabled = true
            }
        }
        btn.background = solidPanelDrawable(bgFill)
        btn.setTextColor(tint)
        btn.isEnabled  = enabled
        icon?.setIconTint(tint)
        if (cooldownText != null) {
            btn.text     = cooldownText
            btn.textSize = UiTheme.SP_CAPTION
            btn.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        } else {
            btn.text     = ""
            btn.textSize = 0f
            btn.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        }
    }

    /** Refresh every ability button — called when energy crosses a threshold. */
    fun refreshAllAbilities(energy: Float) {
        abilitySlots.indices.forEach { refreshAbility(it, energy) }
    }

    /** Refresh the buff indicator to match the active buff (or hide it). */
    fun refreshBuff(activeBuffTimer: Float, activeBuffDamageMul: Float) {
        if (!::buffIndicator.isInitialized) return
        if (activeBuffTimer > 0f) {
            val sec = kotlin.math.ceil(activeBuffTimer.toDouble()).toInt().coerceAtLeast(1)
            buffIndicator.text       = "⚡ ×${"%.1f".format(activeBuffDamageMul)} урон  ${sec}с"
            buffIndicator.visibility = View.VISIBLE
        } else {
            buffIndicator.visibility = View.GONE
        }
    }

    // ---- UI animations ------------------------------------------------------

    /**
     * Quick red-flash + scale pulse on the HP readout. Triggered when the
     * platform takes damage. UI-thread call; if invoked from a worker
     * thread, marshal first.
     */
    fun pulseBaseDamage() {
        if (!::hudHpText.isInitialized) return
        hudHpText.setTextColor(UiTheme.COL_ACCENT_RED)
        hudHpText.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(80L)
            .withEndAction {
                hudHpText.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140L)
                    .withEndAction {
                        hudHpText.setTextColor(UiTheme.COL_TEXT)
                    }
                    .start()
            }
            .start()
    }

    /**
     * Big centred "Волна N" / "Финальная волна" announcement. Fades in,
     * holds 0.7s, fades out. Marshals to the UI thread so it's safe to
     * call from the tick.
     */
    fun announceWave(waveNum: Int, totalWaves: Int) {
        if (!::waveAnnounceText.isInitialized) return
        val text = if (waveNum == totalWaves) "Финальная волна" else "Волна $waveNum"
        activity.runOnUiThread {
            waveAnnounceText.text = text
            waveAnnounceText.alpha = 0f
            waveAnnounceText.visibility = View.VISIBLE
            waveAnnounceText.animate()
                .alpha(1f)
                .setDuration(180L)
                .withEndAction {
                    waveAnnounceText.postDelayed({
                        waveAnnounceText.animate()
                            .alpha(0f)
                            .setDuration(280L)
                            .withEndAction { waveAnnounceText.visibility = View.GONE }
                            .start()
                    }, 700L)
                }
                .start()
        }
    }

    /** Solid rounded-rect background for ability buttons (cooling/disabled/ready). */
    private fun solidPanelDrawable(fill: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiTheme.dp(activity, UiTheme.DP_BUTTON_RADIUS).toFloat()
            setColor(fill)
        }
}
