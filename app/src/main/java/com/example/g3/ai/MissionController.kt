package com.example.g3.ai

import kotlin.math.PI

data class AgentState(val id: Int, val position: Vec2, val heading: Float)

enum class FlightPhase {
    FORMING_UP, DEPART, ORBIT_ENEMY, TRANSIT, ORBIT_ALLY, RETURN_HOME, DONE
}

/**
 * Orchestrates a full fly-around mission:
 *   FORMING_UP → DEPART → ORBIT_ENEMY → TRANSIT → ORBIT_ALLY → RETURN_HOME → DONE
 *
 * Uses ShipAgent physics (independent of SimulationWorld).
 * Call update(dt) each tick; read positions via toPositions().
 */
class MissionController(
    initialPositions: List<Vec2>,
    private val homePositions: List<Vec2> = initialPositions
) {
    private val agents     = initialPositions.map { ShipAgent(it) }
    private val agentCount = agents.size

    private var phase      = FlightPhase.FORMING_UP
    private var phasePath: FlightPath? = null
    private var anchorDist = 0f
    private var anchorPos  = centroid(initialPositions)
    private var anchorDir  = Vec2(0f, 1f)
    private var slotAssignment = IntArray(agentCount) { it }
    private var phaseTimer = 0f

    /** Returns true when the mission is complete. */
    fun update(dt: Float): Boolean {
        if (phase == FlightPhase.DONE) return true
        phaseTimer += dt
        updateAnchor(dt)
        updateAgents(dt)
        if (shouldTransition()) transition()
        return phase == FlightPhase.DONE
    }

    fun toPositions(): List<AgentState> =
        agents.mapIndexed { i, a -> AgentState(i, a.position, a.heading) }

    // ---------------------------------------------------------------------------

    private fun updateAnchor(dt: Float) {
        val path = phasePath ?: return
        val speed = when (phase) {
            FlightPhase.ORBIT_ENEMY, FlightPhase.ORBIT_ALLY -> FlightConfig.ORBIT_SPEED
            else -> FlightConfig.ANCHOR_SPEED
        }
        anchorDist = (anchorDist + speed * dt).coerceAtMost(path.totalLength)
        anchorPos  = path.positionAt(anchorDist)
        val t = path.tangentAt(anchorDist)
        if (t.lengthSq() > 1e-6f) anchorDir = t
    }

    private fun updateAgents(dt: Float) {
        val neighborPositions = agents.map { it.position }

        if (phase == FlightPhase.RETURN_HOME) {
            agents.forEachIndexed { i, agent ->
                val home = homePositions.getOrElse(i) { anchorPos }
                val arrive = agent.arrive(home, desiredSpeed = FlightConfig.ANCHOR_SPEED)
                val sep    = agent.separation(neighborPositions.filterIndexed { j, _ -> j != i })
                agent.addForce(arrive * 0.7f + sep * 0.3f)
                agent.update(dt)
            }
            return
        }

        val slots = FormationDef.allSlots(anchorPos, anchorDir)
        slotAssignment = SlotAssigner.assign(agents.map { it.position }, slots)

        val speed = if (phase == FlightPhase.FORMING_UP) FlightConfig.FORM_UP_WING_SPEED
                    else FlightConfig.ANCHOR_SPEED

        agents.forEachIndexed { i, agent ->
            val slotIdx = slotAssignment[i]
            val target  = if (slotIdx >= 0) slots[slotIdx] else anchorPos
            val arrive  = agent.arrive(target, desiredSpeed = speed)
            val sep     = agent.separation(neighborPositions.filterIndexed { j, _ -> j != i })
            agent.addForce(arrive * 0.7f + sep * 0.3f)
            agent.update(dt)
        }
    }

    private fun shouldTransition(): Boolean = when (phase) {
        FlightPhase.FORMING_UP -> {
            val slots = FormationDef.allSlots(anchorPos, anchorDir)
            val allNear = agents.indices.all { i ->
                val s = slotAssignment[i]
                s < 0 || agents[i].isNear(slots[s], FlightConfig.ARRIVAL_SLOW_RADIUS)
            }
            allNear || phaseTimer > FORM_UP_TIMEOUT
        }
        FlightPhase.DEPART, FlightPhase.TRANSIT,
        FlightPhase.ORBIT_ENEMY, FlightPhase.ORBIT_ALLY -> {
            val path = phasePath ?: return true
            anchorDist >= path.totalLength - 0.1f
        }
        FlightPhase.RETURN_HOME -> agents.indices.all { i ->
            val home = homePositions.getOrElse(i) { agents[i].position }
            agents[i].isNear(home, FlightConfig.ARRIVAL_STOP_RADIUS * 3) && agents[i].speed() < 1f
        }
        FlightPhase.DONE -> true
    }

    private fun transition() {
        phaseTimer = 0f
        when (phase) {
            FlightPhase.FORMING_UP  -> enterDepart()
            FlightPhase.DEPART      -> enterOrbitEnemy()
            FlightPhase.ORBIT_ENEMY -> enterTransit()
            FlightPhase.TRANSIT     -> enterOrbitAlly()
            FlightPhase.ORBIT_ALLY  -> enterReturnHome()
            FlightPhase.RETURN_HOME -> phase = FlightPhase.DONE
            FlightPhase.DONE        -> {}
        }
    }

    private fun enterDepart() {
        val dest = FlightConfig.ENEMY_STATION + Vec2(0f, -(FlightConfig.ENEMY_GROUP_ORBIT_RADIUS + 8f))
        phasePath  = FlightPath(listOf(LinearSegment(anchorPos, dest)))
        anchorDist = 0f
        phase      = FlightPhase.DEPART
    }

    private fun enterOrbitEnemy() {
        val seg = FlightPath.circleAroundStation(
            FlightConfig.ENEMY_STATION,
            FlightConfig.ENEMY_GROUP_ORBIT_RADIUS,
            anchorPos,
            2f * PI.toFloat()
        )
        phasePath  = FlightPath(listOf(seg))
        anchorDist = 0f
        phase      = FlightPhase.ORBIT_ENEMY
    }

    private fun enterTransit() {
        val dest = FlightConfig.ALLY_STATION + Vec2(0f, FlightConfig.ORBIT_RADIUS + 12f)
        phasePath  = FlightPath(listOf(LinearSegment(anchorPos, dest)))
        anchorDist = 0f
        phase      = FlightPhase.TRANSIT
    }

    private fun enterOrbitAlly() {
        val seg = FlightPath.circleAroundStation(
            FlightConfig.ALLY_STATION,
            FlightConfig.ORBIT_RADIUS,
            anchorPos,
            2f * PI.toFloat()
        )
        phasePath  = FlightPath(listOf(seg))
        anchorDist = 0f
        phase      = FlightPhase.ORBIT_ALLY
    }

    private fun enterReturnHome() {
        phasePath = null
        anchorDir = Vec2(0f, 1f)
        phase     = FlightPhase.RETURN_HOME
    }

    companion object {
        private const val FORM_UP_TIMEOUT = 8f

        private fun centroid(positions: List<Vec2>): Vec2 {
            if (positions.isEmpty()) return Vec2.ZERO
            val sx = positions.sumOf { it.x.toDouble() }.toFloat()
            val sy = positions.sumOf { it.y.toDouble() }.toFloat()
            return Vec2(sx / positions.size, sy / positions.size)
        }
    }
}
