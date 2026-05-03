package com.example.g3.sim

import com.example.g3.ai.FlightConfig
import com.example.g3.ai.Vec2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts high-level [ShipIntent] into low-level [ShipCommand].
 *
 * The controller decides desired movement and fire requests. It does not mutate
 * ship state, integrate physics, spawn projectiles, or build render objects.
 */
class ShipController(
    private val pathCatchUpRadius: Float = 1.5f,
    private val arrivalSlowRadiusMultiplier: Float = 3.0f,
    private val fireSectorHalfAngle: Float = 0.35f,
    private val maxFireRange: Float = 90f
) {
    fun commandFor(ship: ShipState, intent: ShipIntent): ShipCommand =
        when (intent) {
            ShipIntent.Idle -> idle(ship)
            is ShipIntent.MoveTo -> moveTo(
                ship = ship,
                point = intent.point,
                desiredSpeed = intent.desiredSpeed,
                arriveRadius = intent.arriveRadius,
                desiredAltitude = 0f
            )
            is ShipIntent.FollowPath -> followPath(ship, intent)
            is ShipIntent.AttackTarget -> attackTarget(ship, intent)
            is ShipIntent.NoseThrustToward -> noseThrust(ship, intent)
            is ShipIntent.DriftPass -> driftPass(ship, intent)
            is ShipIntent.ReturnHome -> moveTo(
                ship = ship,
                point = ship.homePosition,
                desiredSpeed = intent.desiredSpeed,
                arriveRadius = RETURN_HOME_ARRIVE_RADIUS,
                desiredAltitude = 0f,
                finalHeading = ship.homeHeading
            )
        }

    private fun idle(ship: ShipState): ShipCommand =
        ShipCommand(
            desiredVelocity = Vec2.ZERO,
            desiredSpeed = 0f,
            desiredHeading = ship.heading,
            desiredAltitude = ship.z
        )

    private fun moveTo(
        ship: ShipState,
        point: Vec2,
        desiredSpeed: Float,
        arriveRadius: Float,
        desiredAltitude: Float,
        finalHeading: Float? = null
    ): ShipCommand {
        val toPoint = point - ship.position
        val distance = toPoint.length()
        if (distance <= arriveRadius) {
            return ShipCommand(
                desiredVelocity = Vec2.ZERO,
                desiredSpeed = 0f,
                desiredHeading = finalHeading ?: ship.heading,
                desiredAltitude = desiredAltitude
            )
        }

        val slowRadius = (arriveRadius * arrivalSlowRadiusMultiplier).coerceAtLeast(arriveRadius)
        val speedScale = if (distance < slowRadius && slowRadius > 1e-5f) {
            (distance / slowRadius).coerceIn(0f, 1f)
        } else {
            1f
        }
        val direction = toPoint.normalize()
        val requestedSpeed = desiredSpeed.coerceAtLeast(0f)
        val remainingDistance = (distance - arriveRadius).coerceAtLeast(0f)
        val brakeLimitedSpeed = sqrt(2f * ship.maxAcceleration.coerceAtLeast(EPSILON) * remainingDistance)
        val speed = min(requestedSpeed * speedScale, brakeLimitedSpeed)
        return ShipCommand(
            desiredVelocity = direction * speed,
            desiredSpeed = speed,
            desiredHeading = ShipMath.headingFromDirection(direction),
            desiredAltitude = desiredAltitude
        )
    }

    private fun followPath(ship: ShipState, intent: ShipIntent.FollowPath): ShipCommand {
        if (intent.path.isFinished) return idle(ship)

        if (intent.distance >= intent.path.totalLength) {
            return moveTo(
                ship = ship,
                point = intent.path.positionAt(intent.path.totalLength),
                desiredSpeed = intent.desiredSpeed,
                arriveRadius = FlightConfig.ARRIVAL_STOP_RADIUS,
                desiredAltitude = 0f
            )
        }

        val pathPoint = intent.path.positionAt(intent.distance)
        val tangent = intent.path.tangentAt(intent.distance)
        val toPath = pathPoint - ship.position
        val direction = when {
            toPath.length() > pathCatchUpRadius -> toPath.normalize()
            tangent.lengthSq() > EPSILON -> tangent.normalize()
            else -> ShipMath.forwardFromHeading(ship.heading)
        }
        val speed = intent.desiredSpeed.coerceAtLeast(0f)
        return ShipCommand(
            desiredVelocity = direction * speed,
            desiredSpeed = speed,
            desiredHeading = ShipMath.headingFromDirection(direction),
            desiredAltitude = 0f
        )
    }

    private fun attackTarget(ship: ShipState, intent: ShipIntent.AttackTarget): ShipCommand {
        val toTarget = intent.targetPos - ship.position
        val distance = toTarget.length()
        val targetDirection = if (distance > EPSILON) {
            toTarget * (1f / distance)
        } else {
            ShipMath.forwardFromHeading(ship.heading)
        }

        val desiredVelocity = when {
            distance > intent.preferredRange -> targetDirection * intent.desiredSpeed.coerceAtLeast(0f)
            distance < intent.preferredRange * 0.7f -> -targetDirection * (intent.desiredSpeed.coerceAtLeast(0f) * 0.5f)
            else -> Vec2.ZERO
        }
        val fireRequest = if (distance <= intent.preferredRange && canFireAt(ship, targetDirection, distance)) {
            FireRequest(
                targetId = intent.targetId,
                targetPos = intent.targetPos,
                targetZ = 0f
            )
        } else {
            null
        }

        return ShipCommand(
            desiredVelocity = desiredVelocity,
            desiredSpeed = desiredVelocity.length(),
            desiredHeading = ShipMath.headingFromDirection(targetDirection),
            desiredAltitude = 0f,
            fireRequest = fireRequest
        )
    }

    /**
     * Engine thrust along nose axis only. Nose tracks target via maneuvering thrusters.
     * When the ship is flying away from the target, nose→target creates a natural
     * deceleration–reversal–reacceleration loop.
     */
    private fun noseThrust(ship: ShipState, intent: ShipIntent.NoseThrustToward): ShipCommand {
        val noseDir   = ShipMath.forwardFromHeading(ship.heading)
        val toTarget  = intent.targetPos - ship.position
        val distance  = toTarget.length()
        val targetDir = if (distance > EPSILON) toTarget * (1f / distance) else noseDir

        val fireRequest = if (distance <= intent.weaponRange &&
            noseDir.dot(targetDir) >= cos(intent.fireHalfAngle))
            FireRequest(targetId = intent.targetId, targetPos = intent.targetPos, targetZ = 0f)
        else null

        return ShipCommand(
            desiredVelocity = noseDir * intent.desiredSpeed,
            desiredSpeed    = intent.desiredSpeed,
            targetTracking  = intent.targetPos,
            fireRequest     = fireRequest
        )
    }

    /**
     * Newtonian drift pass: maintain current velocity (inertia), rotate nose toward
     * target via maneuvering thrusters, apply sinusoidal lateral and altitude drift.
     * Fires whenever target is within range and inside the fire cone.
     */
    private fun driftPass(ship: ShipState, intent: ShipIntent.DriftPass): ShipCommand {
        val currentVel = if (ship.velocity.lengthSq() > EPSILON) ship.velocity
                         else ShipMath.forwardFromHeading(ship.heading) * intent.cruiseSpeed

        val toTarget = intent.targetPos - ship.position
        val distance = toTarget.length()
        val targetDir = if (distance > EPSILON) toTarget * (1f / distance)
                        else ShipMath.forwardFromHeading(ship.heading)

        val fireRequest = if (distance <= intent.weaponRange) {
            val forward = ShipMath.forwardFromHeading(ship.heading)
            if (forward.dot(targetDir) >= cos(intent.fireHalfAngle))
                FireRequest(targetId = intent.targetId, targetPos = intent.targetPos, targetZ = 0f)
            else null
        } else null

        val lateralAccel = if (intent.lateralDriftPerp.lengthSq() > EPSILON)
            intent.lateralDriftPerp * (sin(intent.driftTime * DRIFT_FREQ) * intent.lateralDriftAmp)
        else Vec2.ZERO

        val desiredAltitude = sin(intent.driftTime * ALT_FREQ) * ALT_AMP

        return ShipCommand(
            desiredVelocity  = currentVel,
            desiredSpeed     = currentVel.length(),
            desiredAltitude  = desiredAltitude,
            fireRequest      = fireRequest,
            targetTracking   = intent.targetPos,
            lateralImpulse   = lateralAccel
        )
    }

    private fun canFireAt(ship: ShipState, targetDirection: Vec2, distance: Float): Boolean {
        if (distance > maxFireRange || targetDirection.lengthSq() <= EPSILON) return false
        val forward = ShipMath.forwardFromHeading(ship.heading)
        return forward.dot(targetDirection) >= cos(fireSectorHalfAngle)
    }

    private companion object {
        const val EPSILON = 1e-5f
        const val RETURN_HOME_ARRIVE_RADIUS = 0.35f
        const val DRIFT_FREQ = 2.0f   // lateral oscillation frequency (rad/s)
        const val ALT_FREQ   = 1.3f   // altitude oscillation frequency (rad/s)
        const val ALT_AMP    = 2.5f   // altitude oscillation amplitude (units)
    }
}
