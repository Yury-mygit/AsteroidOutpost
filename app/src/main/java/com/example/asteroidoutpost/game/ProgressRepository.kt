package com.example.asteroidoutpost.game

import android.content.Context

/**
 * Persists [GameProgress] across app launches via SharedPreferences.
 *
 * Uses an Outpost-specific pref file so the existing g3 settings (handled by
 * SettingsActivity) stay untouched.
 *
 * The pref-file name is intentionally bumped to `_v2` in M4: the upgrade
 * tracks were renamed (robot/turret → main_weapon/side_turret), and the
 * project hasn't shipped, so we discard the old file rather than migrating.
 * Pre-M4 builds keep their data isolated under "outpost_progress".
 */
class ProgressRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun load(): GameProgress = GameProgress(
        metal                    = prefs.getInt(KEY_METAL, 0),
        mainWeaponDamageLevel    = prefs.getInt(KEY_LVL_MAIN_WEAPON_DMG, 1),
        baseHpLevel              = prefs.getInt(KEY_LVL_BASE_HP, 1),
        sideTurretDamageLevel    = prefs.getInt(KEY_LVL_SIDE_TURRET_DMG, 1),
        highestMissionUnlocked   = prefs.getInt(KEY_HIGHEST_MISSION, 0),
    )

    fun save(progress: GameProgress) {
        prefs.edit()
            .putInt(KEY_METAL,                progress.metal)
            .putInt(KEY_LVL_MAIN_WEAPON_DMG,  progress.mainWeaponDamageLevel)
            .putInt(KEY_LVL_BASE_HP,          progress.baseHpLevel)
            .putInt(KEY_LVL_SIDE_TURRET_DMG,  progress.sideTurretDamageLevel)
            .putInt(KEY_HIGHEST_MISSION,      progress.highestMissionUnlocked)
            .apply()
    }

    private companion object {
        const val PREF_FILE                = "outpost_progress_v2"
        const val KEY_METAL                = "metal"
        const val KEY_LVL_MAIN_WEAPON_DMG  = "lvl_main_weapon_dmg"
        const val KEY_LVL_BASE_HP          = "lvl_base_hp"
        const val KEY_LVL_SIDE_TURRET_DMG  = "lvl_side_turret_dmg"
        const val KEY_HIGHEST_MISSION      = "highest_mission"
    }
}
