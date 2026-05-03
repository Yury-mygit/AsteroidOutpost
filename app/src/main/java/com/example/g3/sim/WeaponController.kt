package com.example.g3.sim

import com.example.g3.ai.Vec2
import kotlin.math.PI
import kotlin.math.sin

/**
 * Owns weapon cooldowns, projectile spawning, projectile hits, and explosion state.
 */
class WeaponController(
    private val projectileSpeed: Float = 28f,
    private val projectileLife: Float = 2.0f,
    private val fireCooldown: Float = 0.35f,
    private val muzzleOffset: Float = 0.7f,
    private val hitRadius: Float = 4.0f,
    private val explosionDuration: Float = 0.5f,
    private val maxExplosionScale: Float = 4.0f
) {
    private val cooldownByShipId = mutableMapOf<Int, Float>()
    private var nextProjectileId = 1
    private var nextExplosionId = 1

    fun update(
        ships: List<ShipState>,
        commands: Map<Int, ShipCommand>,
        projectiles: MutableList<ProjectileState>,
        explosions: MutableList<ExplosionState>,
        targetPositions: Map<Int, Vec2>,
        dt: Float
    ): List<CombatEvent> {
        if (dt <= 0f) return emptyList()

        val events = mutableListOf<CombatEvent>()
        tickCooldowns(dt)
        tickExplosions(explosions, dt)
        spawnRequestedProjectiles(ships, commands, projectiles, events)
        tickProjectiles(projectiles, explosions, targetPositions, dt, events)
        return events
    }

    fun reset() {
        cooldownByShipId.clear()
        nextProjectileId = 1
        nextExplosionId = 1
    }

    private fun tickCooldowns(dt: Float) {
        for ((shipId, cooldown) in cooldownByShipId.toMap()) {
            val next = cooldown - dt
            if (next <= 0f) cooldownByShipId.remove(shipId)
            else cooldownByShipId[shipId] = next
        }
    }

    private fun spawnRequestedProjectiles(
        ships: List<ShipState>,
        commands: Map<Int, ShipCommand>,
        projectiles: MutableList<ProjectileState>,
        events: MutableList<CombatEvent>
    ) {
        val shipsById = ships.associateBy { it.id }
        for ((shipId, command) in commands) {
            val request = command.fireRequest ?: continue
            if ((cooldownByShipId[shipId] ?: 0f) > 0f) continue
            val ship = shipsById[shipId] ?: continue
            val toTarget = request.targetPos - ship.position
            val direction = if (toTarget.lengthSq() > EPSILON) {
                toTarget.normalize()
            } else {
                ShipMath.forwardFromHeading(ship.heading)
            }
            val start = ship.position + ShipMath.forwardFromHeading(ship.heading) * muzzleOffset
            val projectile = ProjectileState(
                id = nextProjectileId++,
                ownerShipId = ship.id,
                targetId = request.targetId,
                position = start,
                z = ship.z,
                velocity = direction * projectileSpeed,
                timeLeft = projectileLife,
                damage = ship.damagePerShot
            )
            projectiles += projectile
            cooldownByShipId[shipId] = fireCooldown
            events += CombatEvent.ProjectileSpawned(projectile)
        }
    }

    private fun tickProjectiles(
        projectiles: MutableList<ProjectileState>,
        explosions: MutableList<ExplosionState>,
        targetPositions: Map<Int, Vec2>,
        dt: Float,
        events: MutableList<CombatEvent>
    ) {
        val iter = projectiles.iterator()
        while (iter.hasNext()) {
            val projectile = iter.next()
            val previousPosition = projectile.position
            projectile.position = projectile.position + projectile.velocity * dt
            projectile.timeLeft -= dt

            val targetId = projectile.targetId
            val targetPosition = if (targetId != null) targetPositions[targetId] else null
            val hit = targetPosition != null && hasHitTarget(previousPosition, projectile.position, targetPosition)
            if (hit) {
                iter.remove()
                events += CombatEvent.ProjectileHit(projectile.id, targetId, projectile.damage)
                spawnExplosion(targetPosition, projectile.z, explosions, events)
            } else if (projectile.timeLeft <= 0f) {
                iter.remove()
            }
        }
    }

    private fun hasHitTarget(previous: Vec2, current: Vec2, target: Vec2): Boolean {
        val before = target - previous
        val after = target - current
        val crossedTargetPlane = before.dot(after) <= 0f
        val withinHitRadius = after.lengthSq() <= hitRadius * hitRadius
        return crossedTargetPlane || withinHitRadius
    }

    private fun spawnExplosion(
        position: Vec2,
        z: Float,
        explosions: MutableList<ExplosionState>,
        events: MutableList<CombatEvent>
    ) {
        explosions += ExplosionState(
            id = nextExplosionId++,
            position = position,
            z = z,
            timeLeft = explosionDuration,
            duration = explosionDuration,
            maxScale = maxExplosionScale
        )
        events += CombatEvent.ExplosionSpawned(position, z)
    }

    private fun tickExplosions(explosions: MutableList<ExplosionState>, dt: Float) {
        val iter = explosions.iterator()
        while (iter.hasNext()) {
            val explosion = iter.next()
            explosion.timeLeft -= dt
            if (explosion.timeLeft <= 0f) iter.remove()
        }
    }

    companion object {
        fun explosionScale(explosion: ExplosionState): Float =
            explosion.maxScale * sin(explosion.progress.coerceIn(0f, 1f) * PI.toFloat()).coerceAtLeast(0f)

        private const val EPSILON = 1e-5f
    }
}
