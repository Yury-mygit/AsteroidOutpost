package com.example.g3.sim

import com.example.g3.ai.FlightPath
import com.example.g3.ai.Vec2

/**
 * High-level ship intent produced by missions or AI.
 *
 * Intents describe what a ship should try to do. They must not directly mutate
 * ship state, spawn projectiles, or build render objects.
 */
sealed interface ShipIntent {
    data object Idle : ShipIntent

    data class MoveTo(
        val point: Vec2,
        val desiredSpeed: Float,
        val arriveRadius: Float
    ) : ShipIntent

    data class FollowPath(
        val path: FlightPath,
        val distance: Float,
        val desiredSpeed: Float
    ) : ShipIntent

    data class AttackTarget(
        val targetId: Int,
        val targetPos: Vec2,
        val preferredRange: Float,
        val desiredSpeed: Float
    ) : ShipIntent

    data class ReturnHome(
        val desiredSpeed: Float
    ) : ShipIntent

    /**
     * Engine thrust strictly along the nose axis. Nose tracks [targetPos] via
     * maneuvering thrusters. Fires when target is in cone and range.
     *
     * When the ship is flying away from the target, nose points toward it and the
     * engine decelerates the ship, then re-accelerates it back — creating the
     * natural return loop without a predefined arc.
     */
    data class NoseThrustToward(
        val targetId: Int,
        val targetPos: Vec2,
        val weaponRange: Float = 35f,
        val fireHalfAngle: Float = 0.175f,
        val desiredSpeed: Float = 14f
    ) : ShipIntent

    /**
     * Newtonian drift pass: ship coasts on inertia while maneuvering thrusters
     * rotate the nose toward the target. Fires when target is in cone and range.
     * Lateral and altitude oscillation make the trajectory non-linear.
     */
    data class DriftPass(
        val targetId: Int,
        val targetPos: Vec2,
        val weaponRange: Float = 35f,
        val fireHalfAngle: Float = 0.175f,
        /** Unit vector perpendicular to approach direction — direction of lateral drift. */
        val lateralDriftPerp: Vec2 = Vec2.ZERO,
        /** Amplitude of sinusoidal lateral drift acceleration (units/s²). */
        val lateralDriftAmp: Float = 2.5f,
        /** Accumulated time in this pass phase, used for sinusoidal oscillations. */
        val driftTime: Float = 0f,
        val cruiseSpeed: Float = 14f
    ) : ShipIntent
}
