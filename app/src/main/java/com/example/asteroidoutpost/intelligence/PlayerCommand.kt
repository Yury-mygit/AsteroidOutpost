package com.example.asteroidoutpost.intelligence

import com.example.asteroidoutpost.ai.Vec2

enum class StationMode { MANUAL, AUTO }

sealed interface PlayerCommand {
    data class AttackTarget(val unit: FleetUnit, val targetId: Int, val targetPos: Vec2) : PlayerCommand
    data class AttackNearest(val unit: FleetUnit) : PlayerCommand
    data class DefendStation(val unit: FleetUnit) : PlayerCommand
    data class ReturnHome(val unit: FleetUnit) : PlayerCommand
    data class Patrol(val unit: FleetUnit, val targetCenter: Vec2, val targetRadius: Float) : PlayerCommand
    data class SetMode(val mode: StationMode) : PlayerCommand
}
