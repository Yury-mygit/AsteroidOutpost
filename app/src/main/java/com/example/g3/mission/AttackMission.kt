package com.example.g3.mission

import com.example.g3.ai.Vec2
import com.example.g3.sim.CombatEvent
import com.example.g3.sim.ShipIntent
import com.example.g3.sim.SimulationWorld
import com.example.g3.sim.WorldObject

/**
 * Attack mission expressed as high-level intents.
 *
 * Movement, turning, projectile spawning, cooldowns, hits, and explosions are
 * handled by SimulationWorld components.
 */
class AttackMission(
    attackerIds: Collection<Int>,
    private val targetId: Int,
    private val targetPos: Vec2,
    private val desiredSpeed: Float = 14f,
    private val preferredRange: Float = 35f,
    private val shotsPerAttacker: Int = 2
) : Mission {
    private val attackerIds = attackerIds.toSortedSet()
    private val shotsFiredByShip = this.attackerIds.associateWith { 0 }.toMutableMap()

    override fun intents(world: SimulationWorld): Map<Int, ShipIntent> {
        val shipsById = world.shipSnapshot().associateBy { it.id }
        val targetGone = isTargetDestroyed(world)
        return attackerIds.mapNotNull { shipId ->
            val ship = shipsById[shipId] ?: return@mapNotNull null
            val intent = when {
                targetGone -> ShipIntent.ReturnHome(desiredSpeed = desiredSpeed)
                (shotsFiredByShip[shipId] ?: 0) < shotsPerAttacker -> ShipIntent.AttackTarget(
                    targetId = targetId,
                    targetPos = targetPos,
                    preferredRange = preferredRange,
                    desiredSpeed = desiredSpeed
                )
                else -> ShipIntent.ReturnHome(desiredSpeed = desiredSpeed)
            }
            ship.id to intent
        }.toMap()
    }

    override fun onEvents(events: List<CombatEvent>) {
        for (event in events) {
            if (event is CombatEvent.ProjectileSpawned) {
                val shipId = event.projectile.ownerShipId
                if (shipId in shotsFiredByShip) {
                    shotsFiredByShip[shipId] = (shotsFiredByShip[shipId] ?: 0) + 1
                }
            }
        }
    }

    override fun isDone(world: SimulationWorld): Boolean {
        if (world.projectileSnapshot().isNotEmpty() || world.explosionSnapshot().isNotEmpty()) {
            return false
        }
        val targetGone = isTargetDestroyed(world)
        val shipsById = world.shipSnapshot().associateBy { it.id }
        return attackerIds.all { shipId ->
            val ship = shipsById[shipId] ?: return@all true
            val doneAttacking = targetGone || (shotsFiredByShip[shipId] ?: 0) >= shotsPerAttacker
            doneAttacking &&
                (ship.position - ship.homePosition).length() <= HOME_RADIUS &&
                ship.velocity.length() <= STOP_SPEED
        }
    }

    private fun isTargetDestroyed(world: SimulationWorld): Boolean =
        world.worldObjectSnapshot().find { it.id == targetId }?.combatStats?.isDestroyed == true

    private companion object {
        const val HOME_RADIUS = 0.6f
        const val STOP_SPEED = 0.5f
    }
}
