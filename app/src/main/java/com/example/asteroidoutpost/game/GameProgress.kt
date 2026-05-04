package com.example.asteroidoutpost.game

/**
 * Persistent player progress, saved across app launches via SharedPreferences.
 *
 * Three upgrade tracks under the new combat system (M4 rename):
 * - mainWeaponDamageLevel — central turret base damage (formerly "robot").
 * - baseHpLevel — additive bonus to mission baseHp.
 * - sideTurretDamageLevel — automatic side turrets' damage (~50% of central).
 *
 * highestMissionUnlocked is reserved for future progression gating.
 */
data class GameProgress(
    val metal: Int = 0,
    val mainWeaponDamageLevel:  Int = 1,
    val baseHpLevel:            Int = 1,
    val sideTurretDamageLevel:  Int = 1,
    val highestMissionUnlocked: Int = 0,
)
