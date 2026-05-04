package com.example.asteroidoutpost.game

/**
 * Catalogue of central-turret weapons. Side turrets are not selectable —
 * they keep the legacy fixed parameters in `DraftCombat`.
 *
 * Final per-shot damage is `effectiveMainWeaponDamage * damageMultiplier`,
 * where `effectiveMainWeaponDamage` comes from the upgrade level (M4 will
 * rename the upgrade track itself). The multiplier lets weapons differ in
 * damage feel without duplicating the upgrade ladder per weapon.
 *
 * `aoeRadius` and `aoeDamageMultiplier` are reserved for M2.2 (heavy cannon);
 * `aoeRadius == 0f` means a single-target weapon.
 */
enum class WeaponId { AUTOMATIC, HEAVY_CANNON }

data class Weapon(
    val id: WeaponId,
    val displayName: String,
    val description: String,
    val fireIntervalSec: Float,
    val damageMultiplier: Float,
    val projectileSpeed: Float,
    val projectileHalfW: Float,
    val projectileHalfH: Float,
    val aoeRadius: Float = 0f,
    val aoeDamageMultiplier: Float = 0f,
)

object WeaponCatalog {

    val AUTOMATIC = Weapon(
        id = WeaponId.AUTOMATIC,
        displayName     = "Автомат",
        description     = "Частая стрельба, низкий урон. Хорош против малых астероидов.",
        fireIntervalSec = 0.15f,
        damageMultiplier = 1.0f,
        projectileSpeed = 25f,
        projectileHalfW = 0.04f,
        projectileHalfH = 0.18f,
    )

    /**
     * Heavy cannon — slow, hard-hitting, AoE. Roughly 7× the cooldown of the
     * automatic gun and 3× per-shot damage; AoE deals 60% of weapon damage to
     * other asteroids within `aoeRadius`. Chunky projectile, slower flight so
     * the player can read the trajectory before it lands.
     */
    val HEAVY_CANNON = Weapon(
        id = WeaponId.HEAVY_CANNON,
        displayName     = "Тяжёлая пушка",
        description     = "Редкий мощный выстрел, урон по области. Хороша против групп и крупных астероидов.",
        fireIntervalSec = 1.0f,
        damageMultiplier = 3.0f,
        projectileSpeed = 18f,
        projectileHalfW = 0.10f,
        projectileHalfH = 0.18f,
        aoeRadius           = 0.5f,
        aoeDamageMultiplier = 0.6f,
    )

    val ALL: List<Weapon> = listOf(AUTOMATIC, HEAVY_CANNON)

    fun byId(id: WeaponId): Weapon = ALL.first { it.id == id }
}
