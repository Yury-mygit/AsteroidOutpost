package com.example.asteroidoutpost.game

/**
 * Active abilities the player invokes during combat. Each costs energy and
 * has a cooldown; activation logic lives in MainActivity (effect dispatch
 * branches on `id`). Static metadata only — runtime state (current cooldown)
 * is held by MainActivity per slot. All abilities are instant: the actual
 * fire is marshalled onto the tick thread to keep list mutations atomic.
 */
enum class AbilityId { ROCKET_STRIKE, LASER_STRIKE }

data class Ability(
    val id: AbilityId,
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val cost: Float,
    val cooldownSec: Float,
)

object AbilityCatalog {

    val ROCKET_STRIKE = Ability(
        id           = AbilityId.ROCKET_STRIKE,
        displayName  = "Ракетный залп",
        shortLabel   = "РАКЕТЫ",
        description  = "3 самонаводящихся ракеты по самым опасным астероидам.",
        cost         = 30f,
        cooldownSec  = 8f,
    )

    val LASER_STRIKE = Ability(
        id           = AbilityId.LASER_STRIKE,
        displayName  = "Лазерный луч",
        shortLabel   = "ЛАЗЕР",
        description  = "5 секунд непрерывного луча из лазерной установки. Бьёт цель центральной турели; любой астероид на пути блокирует луч и получает урон вместо неё.",
        cost         = 50f,
        cooldownSec  = 18f,
    )

    val ALL: List<Ability> = listOf(ROCKET_STRIKE, LASER_STRIKE)

    fun byId(id: AbilityId): Ability = ALL.first { it.id == id }
}
