package com.example.g3.sim

import com.example.g3.ai.Vec2

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
