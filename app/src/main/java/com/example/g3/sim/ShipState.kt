package com.example.g3.sim

import com.example.g3.ai.FlightConfig
import com.example.g3.ai.Vec2

/**
 * Runtime simulation state for one ship.
 */
data class ShipState(
    val id: Int,
    val team: Team,
    val homePosition: Vec2,
    val homeHeading: Float,
    var position: Vec2 = homePosition,
    var z: Float = 0f,
    var velocity: Vec2 = Vec2.ZERO,
    var verticalVelocity: Float = 0f,
    var heading: Float = homeHeading,
    var maxSpeed: Float = FlightConfig.MAX_SPEED,
    var maxAcceleration: Float = FlightConfig.MAX_FORCE,
    var maxVerticalSpeed: Float = DEFAULT_MAX_VERTICAL_SPEED,
    var maxVerticalAcceleration: Float = DEFAULT_MAX_VERTICAL_ACCELERATION,
    var maxTurnRate: Float = DEFAULT_MAX_TURN_RATE,
    var targetId: Int? = null,
    var damagePerShot: Float = DEFAULT_DAMAGE_PER_SHOT,
    val combatStats: CombatStats = CombatStats.fighter()
) {
    companion object {
        const val DEFAULT_MAX_TURN_RATE = 2.8f
        const val DEFAULT_MAX_VERTICAL_SPEED = 7.0f
        const val DEFAULT_MAX_VERTICAL_ACCELERATION = 5.0f
        const val DEFAULT_DAMAGE_PER_SHOT = 20f
    }
}
