package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.ai.Vec2
import kotlin.math.PI
import kotlin.math.abs

/**
 * Applies low-level movement commands to [ShipState].
 *
 * Missions and autopilots describe desired movement. This class owns
 * acceleration, velocity, turn-rate, and altitude integration for ships.
 */
class ShipMotor {
    fun update(ship: ShipState, command: ShipCommand, dt: Float) {
        if (dt <= 0f) return

        val desiredVelocity = command.desiredVelocity()
            .clampLength(ship.maxSpeed)
        ship.velocity = moveVectorToward(
            current = ship.velocity,
            target = desiredVelocity,
            maxDelta = ship.maxAcceleration * dt
        ).clampLength(ship.maxSpeed)
        if (command.lateralImpulse.lengthSq() > EPSILON) {
            ship.velocity = (ship.velocity + command.lateralImpulse * dt).clampLength(ship.maxSpeed)
        }
        ship.position = ship.position + ship.velocity * dt

        updateAltitude(ship, command, dt)
        updateHeading(ship, command, dt)
    }

    private fun ShipCommand.desiredVelocity(): Vec2 {
        val commandVelocity = desiredVelocity
        if (commandVelocity.lengthSq() > EPSILON) {
            val speed = if (desiredSpeed > 0f) desiredSpeed else commandVelocity.length()
            return commandVelocity.normalize() * speed
        }

        if (desiredSpeed > 0f) {
            val heading = desiredHeading ?: 0f
            return ShipMath.forwardFromHeading(heading) * desiredSpeed
        }

        return Vec2.ZERO
    }

    private fun updateAltitude(ship: ShipState, command: ShipCommand, dt: Float) {
        val dz = command.desiredAltitude - ship.z
        val desiredVerticalVelocity = (dz / dt)
            .coerceIn(-ship.maxVerticalSpeed, ship.maxVerticalSpeed)
        ship.verticalVelocity = moveFloatToward(
            current = ship.verticalVelocity,
            target = desiredVerticalVelocity,
            maxDelta = ship.maxVerticalAcceleration * dt
        ).coerceIn(-ship.maxVerticalSpeed, ship.maxVerticalSpeed)
        ship.z += ship.verticalVelocity * dt

        val remaining = command.desiredAltitude - ship.z
        if (dz * remaining <= 0f ||
            (abs(remaining) < ALTITUDE_SNAP_EPSILON && abs(ship.verticalVelocity) < ALTITUDE_SNAP_EPSILON)
        ) {
            ship.z = command.desiredAltitude
            ship.verticalVelocity = 0f
        }
    }

    private fun updateHeading(ship: ShipState, command: ShipCommand, dt: Float) {
        val targetHeading = when {
            command.targetTracking != null -> {
                val toTarget = command.targetTracking - ship.position
                if (toTarget.lengthSq() > EPSILON) ShipMath.headingFromDirection(toTarget.normalize())
                else ship.heading
            }
            command.desiredHeading != null -> command.desiredHeading
            ship.velocity.lengthSq() > EPSILON -> ShipMath.headingFromVelocity(ship.velocity)
            else -> ship.heading
        }
        ship.heading = rotateToward(
            current = ship.heading,
            target = targetHeading,
            maxDelta = ship.maxTurnRate * dt
        )
    }

    private fun moveVectorToward(current: Vec2, target: Vec2, maxDelta: Float): Vec2 {
        val delta = target - current
        val distance = delta.length()
        if (distance <= maxDelta || distance < EPSILON) return target
        return current + delta * (maxDelta / distance)
    }

    private fun moveFloatToward(current: Float, target: Float, maxDelta: Float): Float {
        val delta = target - current
        return when {
            abs(delta) <= maxDelta -> target
            delta > 0f -> current + maxDelta
            else -> current - maxDelta
        }
    }

    private fun rotateToward(current: Float, target: Float, maxDelta: Float): Float {
        val delta = shortestAngleDelta(current, target)
        return normalizeAngle(current + delta.coerceIn(-maxDelta, maxDelta))
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = normalizeAngle(to) - normalizeAngle(from)
        val twoPi = (2f * PI).toFloat()
        if (delta > PI.toFloat()) delta -= twoPi
        if (delta < -PI.toFloat()) delta += twoPi
        return delta
    }

    private fun normalizeAngle(angle: Float): Float {
        var result = angle
        val twoPi = (2f * PI).toFloat()
        while (result > PI.toFloat()) result -= twoPi
        while (result <= -PI.toFloat()) result += twoPi
        return result
    }

    private companion object {
        const val EPSILON = 1e-5f
        const val ALTITUDE_SNAP_EPSILON = 1e-3f
    }
}
