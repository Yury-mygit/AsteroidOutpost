package com.example.asteroidoutpost.game.overlay

import android.content.Context
import android.view.View
import com.example.asteroidoutpost.game.DebugLabelMode
import com.example.asteroidoutpost.game.DebugSettings
import com.example.asteroidoutpost.game.UiHelpers
import com.example.asteroidoutpost.game.UiTheme

/**
 * Settings overlay. Currently surfaces debug toggles — the master
 * "режим дебага" switch and the per-asteroid label mode picker. Real
 * gameplay-affecting settings (language, difficulty, etc.) land here when
 * they exist. `onSettingsChanged` is invoked synchronously after each
 * toggle change so the caller can apply the new state immediately
 * (axes overlay visibility, label refresh, etc.) without waiting for the
 * user to navigate back.
 */
fun buildSettings(
    context: Context,
    debugSettings: DebugSettings,
    onSettingsChanged: () -> Unit,
    onBack: () -> Unit,
): View {
    val overlay = makeOverlay(context, OverlayOpts(scrollable = true))
    overlay.content.addView(UiHelpers.buildTitle(context, "Настройки"))

    overlay.content.addView(
        UiHelpers.buildHeading(context, "Режим дебага"),
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )
    overlay.content.addView(
        UiHelpers.buildSegmentedPicker(
            context = context,
            options = listOf("Выкл", "Вкл"),
            initialIndex = if (debugSettings.enabled) 1 else 0,
            onChange = { idx ->
                debugSettings.enabled = (idx == 1)
                onSettingsChanged()
            },
        ),
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )

    overlay.content.addView(
        UiHelpers.buildHeading(context, "Подписи у астероидов"),
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )
    overlay.content.addView(
        UiHelpers.buildCaption(
            context,
            "Работает только при включённом режиме дебага.",
        ),
        gapParams(context, UiTheme.DP_GAP_TIGHT),
    )
    overlay.content.addView(
        UiHelpers.buildSegmentedPicker(
            context = context,
            options = listOf("Координаты", "Расстояние", "Нет"),
            initialIndex = when (debugSettings.labelMode) {
                DebugLabelMode.COORDS -> 0
                DebugLabelMode.DISTANCE -> 1
                DebugLabelMode.NONE -> 2
            },
            onChange = { idx ->
                debugSettings.labelMode = when (idx) {
                    0 -> DebugLabelMode.COORDS
                    1 -> DebugLabelMode.DISTANCE
                    else -> DebugLabelMode.NONE
                }
                onSettingsChanged()
            },
        ),
        gapParams(context, UiTheme.DP_GAP_NORMAL),
    )

    attachFloatingBackButton(context, overlay.outer, onBack)
    return overlay.outer
}
