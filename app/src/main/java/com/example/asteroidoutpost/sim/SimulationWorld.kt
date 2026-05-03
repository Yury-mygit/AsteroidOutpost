package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.ai.Vec2

/**
 * Runtime owner for ship, projectile, and explosion simulation state.
 *
 * This class is deliberately independent from Android UI and rendering. It
 * accepts per-ship intents, runs autopilot/motor/weapons in a fixed order, and
 * exposes snapshot copies for scene conversion.
 */
class SimulationWorld(
    initialShips: List<ShipState>,
    initialWorldObjects: List<WorldObject> = emptyList(),
    private val shipController: ShipController = ShipController(),
    private val shipMotor: ShipMotor = ShipMotor(),
    private val weaponController: WeaponController = WeaponController()
) {
    private val ships = initialShips.map { it.copy() }.toMutableList()
    private val worldObjects = initialWorldObjects.map { it.copy() }.toMutableList()
    private val projectiles = mutableListOf<ProjectileState>()
    private val explosions = mutableListOf<ExplosionState>()
    private var lastCommands: Map<Int, ShipCommand> = emptyMap()
    private var nextDestructionExplosionId = -1

    companion object {
        private const val DESTRUCTION_DURATION = 1.2f
        private const val DESTRUCTION_MAX_SCALE = 10f
    }

    fun update(
        dt: Float,
        intents: Map<Int, ShipIntent> = emptyMap(),
        targetPositions: Map<Int, Vec2> = emptyMap()
    ): List<CombatEvent> {
        if (dt <= 0f) return emptyList()

        val activeShips = ships.filter { !it.combatStats.isDestroyed }
        val commands = activeShips.associate { ship ->
            ship.id to shipController.commandFor(ship, intents[ship.id] ?: ShipIntent.Idle)
        }
        lastCommands = commands

        for (ship in activeShips) {
            shipMotor.update(ship, commands.getValue(ship.id), dt)
        }

        val worldObjectPositions = worldObjects
            .filter { !it.combatStats.isDestroyed }
            .associate { it.id to it.position }
        val simulationTargets = targetPositions + ships.associate { it.id to it.position } + worldObjectPositions
        val weaponEvents = weaponController.update(
            ships = ships,
            commands = commands,
            projectiles = projectiles,
            explosions = explosions,
            targetPositions = simulationTargets,
            dt = dt
        )
        val damageEvents = applyHitDamage(weaponEvents)
        return weaponEvents + damageEvents
    }

    fun shipSnapshot(): List<ShipState> =
        ships.map { it.copy() }

    /** Overwrite ship positions and headings directly (bypasses motor physics). */
    fun teleportShips(positions: Map<Int, Pair<Vec2, Float>>) {
        for (ship in ships) {
            val (pos, hdg) = positions[ship.id] ?: continue
            ship.position = pos
            ship.heading  = hdg
            ship.velocity = Vec2.ZERO
        }
    }

    fun worldObjectSnapshot(): List<WorldObject> =
        worldObjects.map { it.copy() }

    fun projectileSnapshot(): List<ProjectileState> =
        projectiles.map { it.copy() }

    fun explosionSnapshot(): List<ExplosionState> =
        explosions.map { it.copy() }

    fun commandSnapshot(): Map<Int, ShipCommand> =
        lastCommands.toMap()

    private fun applyHitDamage(events: List<CombatEvent>): List<CombatEvent> {
        val extra = mutableListOf<CombatEvent>()
        for (event in events) {
            if (event !is CombatEvent.ProjectileHit) continue
            val targetId = event.targetId ?: continue

            val obj = worldObjects.find { it.id == targetId && !it.combatStats.isDestroyed }
            if (obj != null) {
                val result = obj.combatStats.applyDamage(event.damage)
                extra += CombatEvent.DamageApplied(targetId, result.shieldDamage, result.hullDamage)
                if (result.destroyed) {
                    extra += CombatEvent.ObjectDestroyed(targetId, obj.position, obj.z)
                    explosions += ExplosionState(
                        id = nextDestructionExplosionId--,
                        position = obj.position,
                        z = obj.z,
                        timeLeft = DESTRUCTION_DURATION,
                        duration = DESTRUCTION_DURATION,
                        maxScale = DESTRUCTION_MAX_SCALE
                    )
                }
                continue
            }

            val ship = ships.find { it.id == targetId && !it.combatStats.isDestroyed }
            if (ship != null) {
                val result = ship.combatStats.applyDamage(event.damage)
                extra += CombatEvent.DamageApplied(targetId, result.shieldDamage, result.hullDamage)
                if (result.destroyed) {
                    extra += CombatEvent.ObjectDestroyed(targetId, ship.position, ship.z)
                    explosions += ExplosionState(
                        id = nextDestructionExplosionId--,
                        position = ship.position,
                        z = ship.z,
                        timeLeft = DESTRUCTION_DURATION,
                        duration = DESTRUCTION_DURATION,
                        maxScale = DESTRUCTION_MAX_SCALE
                    )
                }
            }
        }
        return extra
    }

    fun addShip(ship: ShipState) {
        ships.add(ship.copy())
    }

    fun replaceShips(nextShips: List<ShipState>) {
        ships.clear()
        ships += nextShips.map { it.copy() }
        projectiles.clear()
        explosions.clear()
        lastCommands = emptyMap()
        weaponController.reset()
    }
}
