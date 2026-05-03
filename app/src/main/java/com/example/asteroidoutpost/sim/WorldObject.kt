package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.ai.Vec2

enum class WorldObjectType { STATION, FIGHTER }

data class WorldObject(
    val id: Int,
    val team: Team,
    val objectType: WorldObjectType,
    val position: Vec2,
    val z: Float = 0f,
    val heading: Float = 0f,
    val combatStats: CombatStats
)
