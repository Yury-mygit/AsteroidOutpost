package com.example.g3.ai

import com.example.g3.sim.ShipMath

/**
 * A single ship with Newtonian physics and steering behaviours.
 *
 * Each tick the owner calls one or more steering methods, accumulates
 * the results via [addForce], then calls [update] to integrate.
 *
 * [heading] is in radians and maps directly to SceneObject.rotationZ:
 *   rotationZ = 0   → ship faces +Y (forward in scene)
 *   rotationZ = π/2 → ship faces -X
 * SceneObject.modelMatrix() maps local +Y to world (-sin(rotationZ), cos(rotationZ)).
 * Therefore heading conversion goes through ShipMath.headingFromVelocity().
 */
class ShipAgent(
    initialPosition: Vec2,
    val maxSpeed: Float = FlightConfig.MAX_SPEED,
    val maxForce: Float = FlightConfig.MAX_FORCE
) {
    var position: Vec2 = initialPosition
    var velocity: Vec2 = Vec2.ZERO
    var heading:  Float = 0f          // radians → rotationZ

    private var accumulated: Vec2 = Vec2.ZERO

    // ---------------------------------------------------------------------------
    // Steering behaviour functions — each returns a force Vec2, not yet applied
    // ---------------------------------------------------------------------------

    /** Steer toward [target] at [desiredSpeed], capped by this agent's max speed. */
    fun seek(target: Vec2, desiredSpeed: Float = maxSpeed): Vec2 {
        val speed = desiredSpeed.coerceIn(0f, maxSpeed)
        val desired = (target - position).normalize() * speed
        return (desired - velocity).clampLength(maxForce)
    }

    /**
     * Steer toward [target], decelerating within [slowRadius] so the ship
     * stops smoothly rather than overshooting.
     */
    fun arrive(
        target: Vec2,
        slowRadius: Float = FlightConfig.ARRIVAL_SLOW_RADIUS,
        stopRadius: Float = FlightConfig.ARRIVAL_STOP_RADIUS,
        desiredSpeed: Float = maxSpeed
    ): Vec2 {
        val toTarget = target - position
        val dist = toTarget.length()
        if (dist < stopRadius) return brakeForce()
        val cappedSpeed = desiredSpeed.coerceIn(0f, maxSpeed)
        val speed = when {
            dist < slowRadius -> cappedSpeed * (dist / slowRadius)
            else              -> cappedSpeed
        }
        val desired = toTarget.normalize() * speed
        return (desired - velocity).clampLength(maxForce)
    }

    /**
     * Push away from nearby ships to avoid crowding.
     * [neighborPositions] — positions of all other agents.
     */
    fun separation(
        neighborPositions: List<Vec2>,
        minDist: Float = FlightConfig.SEPARATION_RADIUS
    ): Vec2 {
        var force = Vec2.ZERO
        for (other in neighborPositions) {
            val diff = position - other
            val dist = diff.length()
            if (dist in 1e-4f..minDist) {
                force = force + diff.normalize() * ((minDist - dist) / minDist)
            }
        }
        return force.clampLength(maxForce)
    }

    // ---------------------------------------------------------------------------
    // Force accumulation and integration
    // ---------------------------------------------------------------------------

    /** Accumulate a steering force for this tick. */
    fun addForce(force: Vec2) {
        accumulated = (accumulated + force).clampLength(maxForce)
    }

    /**
     * Integrate physics for one time step [dt] (seconds).
     * Resets accumulated force afterward.
     */
    fun update(dt: Float) {
        velocity    = (velocity + accumulated * dt).clampLength(maxSpeed)
        position    = position + velocity * dt
        if (velocity.lengthSq() > 0.01f * 0.01f) {
            heading = ShipMath.headingFromVelocity(velocity)
        }
        accumulated = Vec2.ZERO
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    fun isNear(target: Vec2, threshold: Float = FlightConfig.ARRIVAL_STOP_RADIUS) =
        (position - target).length() < threshold

    fun speed() = velocity.length()

    private fun brakeForce() = (-velocity).clampLength(maxForce)
}
