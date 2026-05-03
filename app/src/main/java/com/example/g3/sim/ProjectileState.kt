package com.example.g3.sim

import com.example.g3.ai.Vec2

data class ProjectileState(
    val id: Int,
    val ownerShipId: Int,
    val targetId: Int?,
    var position: Vec2,
    var z: Float,
    var velocity: Vec2,
    var timeLeft: Float,
    val damage: Float = 0f
)
