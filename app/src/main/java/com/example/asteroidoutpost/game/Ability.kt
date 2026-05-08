package com.example.asteroidoutpost.game

/**
 * Active abilities the player invokes during combat. Each costs energy and
 * has a cooldown; activation logic lives in MainActivity (effect dispatch
 * branches on `id`). Static metadata only — runtime state (current cooldown)
 * is held by MainActivity per slot.
 *
 * `needsTarget = true` means activation arms the ability and waits for a
 * follow-up screen interaction (e.g. drag a line for the laser). Instant
 * abilities apply on tap.
 */
enum class AbilityId { ROCKET_STRIKE, LASER_STRIKE }

data class Ability(
    val id: AbilityId,
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val cost: Float,
    val cooldownSec: Float,
    val needsTarget: Boolean = false,
)

object AbilityCatalog {

    val ROCKET_STRIKE = Ability(
        id           = AbilityId.ROCKET_STRIKE,
        displayName  = "Ракетный залп",
        shortLabel   = "РАКЕТЫ",
        description  = "3 самонаводящихся ракеты по самым опасным астероидам.",
        cost         = 30f,
        cooldownSec  = 8f,
        needsTarget  = false,
    )

    val LASER_STRIKE = Ability(
        id           = AbilityId.LASER_STRIKE,
        displayName  = "Лазерный удар",
        shortLabel   = "ЛАЗЕР",
        description  = "Проведите линию по экрану — урон всем астероидам на пути.",
        cost         = 50f,
        cooldownSec  = 18f,
        needsTarget  = true,
    )

    val ALL: List<Ability> = listOf(ROCKET_STRIKE, LASER_STRIKE)

    fun byId(id: AbilityId): Ability = ALL.first { it.id == id }
}
