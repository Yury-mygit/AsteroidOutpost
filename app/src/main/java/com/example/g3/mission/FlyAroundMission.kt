package com.example.g3.mission

import com.example.g3.ai.FlightConfig
import com.example.g3.ai.FlightPath
import com.example.g3.ai.Vec2
import com.example.g3.sim.ShipIntent
import com.example.g3.sim.SimulationWorld

/**
 * Fly-around mission expressed as path-follow and return-home intents.
 */
class FlyAroundMission(
    shipIds: Collection<Int>,
    private val targetCenter: Vec2,
    private val targetRadius: Float,
    private val desiredSpeed: Float = FlightConfig.ANCHOR_SPEED
) : Mission {
    private val shipIds = shipIds.toSortedSet()
    private var paths: Map<Int, FlightPath> = emptyMap()
    private var distanceTraveled = 0f
    private var returningHome = false

    override fun beforeUpdate(dt: Float, world: SimulationWorld) {
        if (dt <= 0f || returningHome) return

        ensurePaths(world)
        val longestPath = paths.values.maxOfOrNull { it.totalLength } ?: 0f
        distanceTraveled = (distanceTraveled + desiredSpeed * dt).coerceAtMost(longestPath)
        if (distanceTraveled >= longestPath) {
            returningHome = true
        }
    }

    override fun intents(world: SimulationWorld): Map<Int, ShipIntent> {
        ensurePaths(world)
        return shipIds.mapNotNull { shipId ->
            val path = paths[shipId] ?: return@mapNotNull null
            val intent = if (returningHome) {
                ShipIntent.ReturnHome(desiredSpeed = desiredSpeed)
            } else {
                ShipIntent.FollowPath(
                    path = path,
                    distance = distanceTraveled.coerceAtMost(path.totalLength),
                    desiredSpeed = desiredSpeed
                )
            }
            shipId to intent
        }.toMap()
    }

    override fun isDone(world: SimulationWorld): Boolean {
        if (!returningHome) return false
        val shipsById = world.shipSnapshot().associateBy { it.id }
        return shipIds.all { shipId ->
            val ship = shipsById[shipId] ?: return@all true
            (ship.position - ship.homePosition).length() <= HOME_RADIUS &&
                ship.velocity.length() <= STOP_SPEED
        }
    }

    private fun ensurePaths(world: SimulationWorld) {
        if (paths.isNotEmpty()) return
        val shipsById = world.shipSnapshot().associateBy { it.id }
        paths = shipIds.mapNotNull { shipId ->
            val ship = shipsById[shipId] ?: return@mapNotNull null
            shipId to FlightPath.tangentFlyAround(targetCenter, targetRadius, ship.position)
        }.toMap()
        if (paths.isEmpty()) returningHome = true
    }

    private companion object {
        const val HOME_RADIUS = 0.6f
        const val STOP_SPEED = 0.5f
    }
}
