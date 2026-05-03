package com.example.asteroidoutpost.game

import android.content.Context

/**
 * Persists [GameProgress] across app launches via SharedPreferences.
 *
 * Uses an Outpost-specific pref file so the existing g3 settings (handled by
 * SettingsActivity) stay untouched.
 */
class ProgressRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(): GameProgress = GameProgress(
        metal                    = prefs.getInt(KEY_METAL, 0),
        robotDamageLevel         = prefs.getInt(KEY_LVL_ROBOT_DMG, 1),
        baseHpLevel              = prefs.getInt(KEY_LVL_BASE_HP, 1),
        turretDamageLevel        = prefs.getInt(KEY_LVL_TURRET_DMG, 1),
        highestMissionUnlocked   = prefs.getInt(KEY_HIGHEST_MISSION, 0),
    )

    fun save(progress: GameProgress) {
        prefs.edit()
            .putInt(KEY_METAL,            progress.metal)
            .putInt(KEY_LVL_ROBOT_DMG,    progress.robotDamageLevel)
            .putInt(KEY_LVL_BASE_HP,      progress.baseHpLevel)
            .putInt(KEY_LVL_TURRET_DMG,   progress.turretDamageLevel)
            .putInt(KEY_HIGHEST_MISSION,  progress.highestMissionUnlocked)
            .apply()
    }

    private companion object {
        const val PREF_FILE          = "outpost_progress"
        const val KEY_METAL          = "metal"
        const val KEY_LVL_ROBOT_DMG  = "lvl_robot_dmg"
        const val KEY_LVL_BASE_HP    = "lvl_base_hp"
        const val KEY_LVL_TURRET_DMG = "lvl_turret_dmg"
        const val KEY_HIGHEST_MISSION = "highest_mission"
    }
}
