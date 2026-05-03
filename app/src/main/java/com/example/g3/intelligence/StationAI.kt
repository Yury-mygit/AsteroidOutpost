package com.example.g3.intelligence

import com.example.g3.ai.FlightConfig
import com.example.g3.ai.FlightPath
import com.example.g3.ai.Vec2
import com.example.g3.sim.CombatEvent
import com.example.g3.sim.ShipIntent
import com.example.g3.sim.ShipState
import com.example.g3.sim.SimulationWorld
import com.example.g3.sim.Team
import com.example.g3.sim.WorldObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rule-based station commander (MANUAL mode).
 *
 * Each ship in a strafing attack loops independently:
 *   APPROACH (NoseThrustToward) → FIRE_PASS (DriftPass) → APPROACH → …
 *
 * There is no holding pattern or queue — all assigned ships attack continuously.
 * The natural deceleration/reversal of NoseThrustToward creates the return loop.
 */
class StationAI(
    private var fleet: FleetRegistry,
    private val stationPos: Vec2,
    private val enemyTeam: Team = Team.ENEMY,
    private val controlZoneRadius: Float = 60f
) {
    var mode: StationMode = StationMode.MANUAL
    var onReport: ((String) -> Unit)? = null
    var onTasksEmpty: (() -> Unit)? = null
    /** 0 = Low, 1 = Medium, 2 = High */
    var aggressiveness: Int = 0

    // -------------------------------------------------------------------------
    // Strafing internals
    // -------------------------------------------------------------------------

    private enum class StrafingPhase { APPROACH, FIRE_PASS }

    private data class ShipStrafingState(
        var phase: StrafingPhase = StrafingPhase.APPROACH,
        var driftTime: Float = 0f,
        var lateralDriftPerp: Vec2 = Vec2.ZERO
    )

    // -------------------------------------------------------------------------
    // Task model
    // -------------------------------------------------------------------------

    private sealed interface TaskState {
        val unitKey: String
        val shipIds: Set<Int>

        data class StrafingAttack(
            override val unitKey: String,
            override val shipIds: Set<Int>,
            val targetId: Int,
            var targetPos: Vec2,
            val shipStates: MutableMap<Int, ShipStrafingState>,
            val interruptedPatrol: Patrolling? = null
        ) : TaskState

        data class Patrolling(
            override val unitKey: String,
            override val shipIds: Set<Int>,
            val targetCenter: Vec2,
            val targetRadius: Float,
            var paths: Map<Int, FlightPath> = emptyMap(),
            var distanceTraveled: Float = 0f
        ) : TaskState

        data class Defending(
            override val unitKey: String,
            override val shipIds: Set<Int>,
            val defensePositions: Map<Int, Vec2>
        ) : TaskState

        data class Returning(
            override val unitKey: String,
            override val shipIds: Set<Int>
        ) : TaskState
    }

    private val tasks = mutableMapOf<String, TaskState>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Synchronized
    fun receiveCommand(command: PlayerCommand, world: SimulationWorld) {
        when (command) {
            is PlayerCommand.SetMode -> {
                mode = command.mode
                onReport?.invoke(
                    if (mode == StationMode.AUTO) "Автономный режим активирован"
                    else "Ручное управление"
                )
            }
            is PlayerCommand.AttackTarget ->
                startStrafingAttack(command.unit, command.targetId, command.targetPos)
            is PlayerCommand.AttackNearest -> {
                val (enemyId, enemyPos) = nearestEnemy(world)
                    ?: run { onReport?.invoke("Враги не обнаружены"); return }
                startStrafingAttack(command.unit, enemyId, enemyPos)
                onReport?.invoke("Атакую ближайшую цель.")
            }
            is PlayerCommand.DefendStation -> {
                val ids = resolveIds(command.unit).ifEmpty {
                    onReport?.invoke("Юнит не найден"); return
                }
                val key = fleet.unitKey(command.unit)
                tasks[key] = TaskState.Defending(key, ids.toSet(), defensePositions(ids))
            }
            is PlayerCommand.ReturnHome -> {
                val ids = resolveIds(command.unit).ifEmpty { return }
                val key = fleet.unitKey(command.unit)
                tasks[key] = TaskState.Returning(key, ids.toSet())
            }
            is PlayerCommand.Patrol -> {
                val ids = resolveIds(command.unit).ifEmpty {
                    onReport?.invoke("Юнит не найден"); return
                }
                val key = fleet.unitKey(command.unit)
                tasks[key] = TaskState.Patrolling(key, ids.toSet(), command.targetCenter, command.targetRadius)
            }
        }
    }

    @Synchronized
    fun tick(dt: Float, world: SimulationWorld): Map<Int, ShipIntent> {
        if (dt <= 0f || tasks.isEmpty()) return emptyMap()
        val result = mutableMapOf<Int, ShipIntent>()
        val finished = mutableListOf<String>()
        val replacements = mutableMapOf<String, TaskState>()
        for ((key, task) in tasks) {
            val (intents, done, replacement) = processTask(task, dt, world)
            result.putAll(intents)
            if (done) finished += key
            if (replacement != null) replacements[key] = replacement
        }
        finished.forEach { tasks.remove(it) }
        replacements.forEach { (k, v) -> tasks[k] = v }
        if (tasks.isEmpty()) onTasksEmpty?.invoke()
        return result
    }

    @Synchronized
    fun onEvents(events: List<CombatEvent>, world: SimulationWorld) {
        val destroyed = events.filterIsInstance<CombatEvent.ObjectDestroyed>().map { it.targetId }.toSet()
        if (destroyed.isNotEmpty()) {
            for ((key, task) in tasks.toMap()) {
                if (task is TaskState.StrafingAttack && task.targetId in destroyed) {
                    val patrol = task.interruptedPatrol
                    val remainingEnemies = allEnemies(world).filter { it.first != task.targetId }
                    when {
                        patrol != null && (remainingEnemies.isEmpty() || aggressiveness == 0) -> {
                            onReport?.invoke("Угроза устранена. Возобновляю патруль.")
                            tasks[key] = patrol.copy(paths = emptyMap(), distanceTraveled = 0f)
                        }
                        patrol != null -> {
                            val next = remainingEnemies.minByOrNull { (_, pos) -> (pos - stationPos).length() }!!
                            tasks[key] = TaskState.StrafingAttack(
                                unitKey = key,
                                shipIds = task.shipIds,
                                targetId = next.first,
                                targetPos = next.second,
                                shipStates = task.shipIds.associateWith { ShipStrafingState() }.toMutableMap(),
                                interruptedPatrol = patrol
                            )
                            onReport?.invoke("Цель уничтожена. Атакую следующего.")
                        }
                        else -> {
                            onReport?.invoke("Цель уничтожена. Борта ${task.shipIds.map { it + 1 }.joinToString()} возвращаются.")
                            tasks[key] = TaskState.Returning(task.unitKey, task.shipIds)
                        }
                    }
                }
            }
        }

        // Enemy projectile fired → interrupt patrol
        val enemyFired = events.filterIsInstance<CombatEvent.ProjectileSpawned>()
        if (enemyFired.isNotEmpty()) {
            val enemyShipIds = world.shipSnapshot()
                .filter { it.team == enemyTeam && !it.combatStats.isDestroyed }
                .map { it.id }.toSet()
            if (enemyFired.any { it.projectile.ownerShipId in enemyShipIds }) {
                interruptPatrolWithAttack(world, "Огонь противника! Перехватываю.")
            }
        }
    }

    @Synchronized fun hasActiveTasks(): Boolean = tasks.isNotEmpty()
    @Synchronized fun clearAllTasks() { tasks.clear() }

    @Synchronized
    fun addShipToFleet(shipId: Int) {
        fleet = fleet.withShipInWing(0, shipId)
    }

    // -------------------------------------------------------------------------
    // Task dispatch
    // -------------------------------------------------------------------------

    private fun processTask(
        task: TaskState, dt: Float, world: SimulationWorld
    ): Triple<Map<Int, ShipIntent>, Boolean, TaskState?> = when (task) {
        is TaskState.StrafingAttack -> Triple(strafingAttackTick(task, dt, world), false, null)
        is TaskState.Patrolling     -> {
            val (intents, replacement) = patrolTick(task, dt, world)
            Triple(intents, false, replacement)
        }
        is TaskState.Defending      -> Triple(defendIntents(task, world), false, null)
        is TaskState.Returning      -> {
            val (intents, done) = returningIntents(task, world)
            Triple(intents, done, null)
        }
    }

    // -------------------------------------------------------------------------
    // Strafing attack — each ship loops independently
    // -------------------------------------------------------------------------

    private fun startStrafingAttack(unit: FleetUnit, targetId: Int, targetPos: Vec2) {
        val ids = resolveIds(unit).ifEmpty { onReport?.invoke("Юнит не найден"); return }
        val key = fleet.unitKey(unit)
        val shipStates = ids.associateWith { ShipStrafingState() }.toMutableMap()
        tasks[key] = TaskState.StrafingAttack(key, ids.toSet(), targetId, targetPos, shipStates)
    }

    private fun strafingAttackTick(
        task: TaskState.StrafingAttack,
        dt: Float,
        world: SimulationWorld
    ): Map<Int, ShipIntent> {
        val byId = world.shipSnapshot().associateBy { it.id }

        // Track target position
        findObjectPosition(world, task.targetId)?.let { task.targetPos = it }

        val result = mutableMapOf<Int, ShipIntent>()

        for (shipId in task.shipIds) {
            val ship  = byId[shipId] ?: continue
            val state = task.shipStates[shipId] ?: continue

            if (state.phase == StrafingPhase.FIRE_PASS) state.driftTime += dt

            val distToTarget = (ship.position - task.targetPos).length()

            when (state.phase) {
                StrafingPhase.APPROACH -> {
                    if (distToTarget <= WEAPON_RANGE) {
                        state.phase = StrafingPhase.FIRE_PASS
                        state.driftTime = 0f
                        state.lateralDriftPerp = lateralPerp(ship.velocity, ship.heading)
                    }
                }
                StrafingPhase.FIRE_PASS -> {
                    // Exit when ship is flying away and target is out of range
                    val movingAway = ship.velocity.dot(task.targetPos - ship.position) < 0f
                    if (movingAway && distToTarget > WEAPON_RANGE * EXIT_THRESHOLD) {
                        state.phase = StrafingPhase.APPROACH
                    }
                }
            }

            result[shipId] = when (state.phase) {
                StrafingPhase.APPROACH -> ShipIntent.NoseThrustToward(
                    targetId      = task.targetId,
                    targetPos     = task.targetPos,
                    weaponRange   = WEAPON_RANGE,
                    fireHalfAngle = FIRE_HALF_ANGLE,
                    desiredSpeed  = SHIP_SPEED
                )
                StrafingPhase.FIRE_PASS -> ShipIntent.DriftPass(
                    targetId         = task.targetId,
                    targetPos        = task.targetPos,
                    weaponRange      = WEAPON_RANGE,
                    fireHalfAngle    = FIRE_HALF_ANGLE,
                    lateralDriftPerp = state.lateralDriftPerp,
                    lateralDriftAmp  = LATERAL_DRIFT_AMP,
                    driftTime        = state.driftTime,
                    cruiseSpeed      = SHIP_SPEED
                )
            }
        }

        return result
    }

    private fun lateralPerp(velocity: Vec2, heading: Float): Vec2 {
        val dir = if (velocity.lengthSq() > EPSILON) velocity.normalize()
                  else Vec2(-sin(heading), cos(heading))
        return Vec2(-dir.y, dir.x)
    }

    // -------------------------------------------------------------------------
    // Patrol
    // -------------------------------------------------------------------------

    private fun patrolTick(
        task: TaskState.Patrolling, dt: Float, world: SimulationWorld
    ): Pair<Map<Int, ShipIntent>, TaskState?> {
        // Threat detection: enemy ship within range of any patrol ship
        val patrolShips = world.shipSnapshot().filter { it.id in task.shipIds }
        val enemies = allEnemies(world)
        val threat = enemies.firstOrNull { (_, pos) ->
            patrolShips.any { ship -> (pos - ship.position).length() <= threatRadius }
        }
        if (threat != null) {
            val replacement = TaskState.StrafingAttack(
                unitKey           = task.unitKey,
                shipIds           = task.shipIds,
                targetId          = threat.first,
                targetPos         = threat.second,
                shipStates        = task.shipIds.associateWith { ShipStrafingState() }.toMutableMap(),
                interruptedPatrol = task
            )
            onReport?.invoke("Угроза! Перехватываю противника.")
            return emptyMap<Int, ShipIntent>() to replacement
        }

        // Build circular orbit paths once — evenly distribute ships around the circle
        if (task.paths.isEmpty()) {
            val shipIdsSorted = task.shipIds.sorted()
            val n = shipIdsSorted.size.coerceAtLeast(1)
            task.paths = shipIdsSorted.mapIndexed { i, id ->
                val startAngle = 2f * PI.toFloat() * i / n
                val approachPos = task.targetCenter +
                    Vec2(cos(startAngle), sin(startAngle)) * (task.targetRadius + 1f)
                val seg = FlightPath.circleAroundStation(
                    task.targetCenter, task.targetRadius, approachPos, 2f * PI.toFloat()
                )
                id to FlightPath(listOf(seg))
            }.toMap()
        }

        val longest = task.paths.values.maxOfOrNull { it.totalLength } ?: 0f
        if (longest > 0f) task.distanceTraveled = (task.distanceTraveled + PATROL_SPEED * dt) % longest
        val patrolShipById = patrolShips.associateBy { it.id }
        val intents = task.shipIds.mapNotNull { id ->
            val path = task.paths[id] ?: return@mapNotNull null
            val ship = patrolShipById[id]
            val radialDist = if (ship != null)
                kotlin.math.abs((ship.position - task.targetCenter).length() - task.targetRadius)
            else 0f
            val speed = if (radialDist > ON_ORBIT_THRESHOLD) FlightConfig.MAX_SPEED else PATROL_SPEED
            id to ShipIntent.FollowPath(
                path         = path,
                distance     = task.distanceTraveled.coerceAtMost(path.totalLength),
                desiredSpeed = speed
            )
        }.toMap()
        return intents to null
    }

    private fun interruptPatrolWithAttack(world: SimulationWorld, report: String) {
        for ((key, task) in tasks.toMap()) {
            if (task !is TaskState.Patrolling) continue
            val (threatId, threatPos) = allEnemies(world)
                .minByOrNull { (_, pos) -> (pos - stationPos).length() } ?: continue
            tasks[key] = TaskState.StrafingAttack(
                unitKey           = key,
                shipIds           = task.shipIds,
                targetId          = threatId,
                targetPos         = threatPos,
                shipStates        = task.shipIds.associateWith { ShipStrafingState() }.toMutableMap(),
                interruptedPatrol = task
            )
            onReport?.invoke(report)
        }
    }

    // -------------------------------------------------------------------------
    // Defend
    // -------------------------------------------------------------------------

    private fun defendIntents(task: TaskState.Defending, world: SimulationWorld): Map<Int, ShipIntent> {
        val threat = allEnemies(world)
            .filter { (_, pos) -> (pos - stationPos).length() <= controlZoneRadius }
            .minByOrNull { (_, pos) -> (pos - stationPos).length() }
        return if (threat != null) {
            task.shipIds.associateWith {
                ShipIntent.AttackTarget(
                    targetId       = threat.first,
                    targetPos      = threat.second,
                    preferredRange = WEAPON_RANGE,
                    desiredSpeed   = SHIP_SPEED
                )
            }
        } else {
            task.defensePositions.mapValues { (_, pos) ->
                ShipIntent.MoveTo(point = pos, desiredSpeed = SHIP_SPEED, arriveRadius = 1f)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Return home
    // -------------------------------------------------------------------------

    private fun returningIntents(
        task: TaskState.Returning,
        world: SimulationWorld
    ): Pair<Map<Int, ShipIntent>, Boolean> {
        val byId = world.shipSnapshot().associateBy { it.id }
        val allHome = task.shipIds.all { id ->
            val ship = byId[id] ?: return@all true
            (ship.position - ship.homePosition).length() <= HOME_RADIUS &&
                ship.velocity.length() <= STOP_SPEED
        }
        return task.shipIds.associateWith { ShipIntent.ReturnHome(desiredSpeed = SHIP_SPEED) } to allHome
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun resolveIds(unit: FleetUnit): List<Int> = fleet.resolveUnit(unit)

    /** All non-destroyed enemy targets (WorldObjects + ShipStates) as (id, position) pairs. */
    private fun allEnemies(world: SimulationWorld): List<Pair<Int, Vec2>> {
        val fromObjects = world.worldObjectSnapshot()
            .filter { !it.combatStats.isDestroyed && it.team == enemyTeam }
            .map { it.id to it.position }
        val fromShips = world.shipSnapshot()
            .filter { !it.combatStats.isDestroyed && it.team == enemyTeam }
            .map { it.id to it.position }
        return fromObjects + fromShips
    }

    private fun nearestEnemy(world: SimulationWorld): Pair<Int, Vec2>? =
        allEnemies(world).minByOrNull { (_, pos) -> (pos - stationPos).length() }

    private fun findObjectPosition(world: SimulationWorld, id: Int): Vec2? =
        world.worldObjectSnapshot()
            .firstOrNull { it.id == id && !it.combatStats.isDestroyed }?.position
            ?: world.shipSnapshot()
                .firstOrNull { it.id == id && !it.combatStats.isDestroyed }?.position

    private fun defensePositions(shipIds: List<Int>): Map<Int, Vec2> {
        val n = shipIds.size.coerceAtLeast(1)
        return shipIds.mapIndexed { i, id ->
            val angle = (2.0 * Math.PI * i / n).toFloat()
            id to Vec2(stationPos.x + DEFENSE_RADIUS * cos(angle), stationPos.y + DEFENSE_RADIUS * sin(angle))
        }.toMap()
    }

    private val threatRadius: Float get() = when (aggressiveness) {
        0    -> 20f
        1    -> 40f
        else -> 60f
    }

    companion object {
        private const val SHIP_SPEED       = 14f
        private const val PATROL_SPEED     = 5f
        private const val ON_ORBIT_THRESHOLD = 2f
        private const val WEAPON_RANGE     = 35f
        private const val FIRE_HALF_ANGLE  = 0.175f  // ~10°, full cone 20°
        private const val EXIT_THRESHOLD   = 1.1f    // exit FIRE_PASS when dist > range * 1.1
        private const val LATERAL_DRIFT_AMP = 2.5f
        private const val DEFENSE_RADIUS   = 12f
        private const val HOME_RADIUS      = 0.6f
        private const val STOP_SPEED       = 0.5f
        private const val EPSILON          = 1e-5f
    }
}
