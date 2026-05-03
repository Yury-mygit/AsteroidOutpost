package com.example.asteroidoutpost.game

/**
 * Persistent player progress, saved across app launches via SharedPreferences.
 *
 * Phase A: only `metal` is used. Upgrade levels and unlocked-mission index
 * will be added in phases D and C respectively — declared here as defaults
 * so the persistence layer doesn't have to be revisited.
 */
data class GameProgress(
    val metal: Int = 0,
    val robotDamageLevel:   Int = 1,
    val baseHpLevel:        Int = 1,
    val turretDamageLevel:  Int = 1,
    val highestMissionUnlocked: Int = 0,
)
