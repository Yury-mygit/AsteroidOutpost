package com.example.asteroidoutpost.game

import android.content.Context
import android.content.SharedPreferences

/**
 * What the per-asteroid debug label shows when debug mode is on.
 */
enum class DebugLabelMode { COORDS, DISTANCE, NONE }

/**
 * User-visible debug toggles. Persisted in their own SharedPreferences
 * file (separate from gameplay progress) so flipping them around doesn't
 * touch the metal/upgrade store. Read each frame by the debug overlays;
 * no listeners needed — the values are cheap to fetch and SharedPreferences
 * is backed by an in-memory map after first load.
 */
class DebugSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Master debug switch. When false, all debug overlays hide. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** What to show next to each asteroid (only matters when [enabled]). */
    var labelMode: DebugLabelMode
        get() {
            val name = prefs.getString(KEY_LABEL_MODE, DebugLabelMode.COORDS.name)
            return runCatching { DebugLabelMode.valueOf(name!!) }
                .getOrDefault(DebugLabelMode.COORDS)
        }
        set(value) { prefs.edit().putString(KEY_LABEL_MODE, value.name).apply() }

    companion object {
        private const val PREFS_NAME = "debug_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LABEL_MODE = "label_mode"
    }
}
