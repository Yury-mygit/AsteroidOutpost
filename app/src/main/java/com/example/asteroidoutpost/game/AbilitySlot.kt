package com.example.asteroidoutpost.game

/**
 * Ability framework (M8.4). Each slot pairs a static `Ability` descriptor
 * with its runtime cooldown. Slots are created once and never resized;
 * `cdUiLast` throttles the per-second countdown text refresh.
 */
internal data class AbilitySlot(
    val ability: Ability,
    var currentCd: Float = 0f,
    var cdUiLast: Int = -1,
)
