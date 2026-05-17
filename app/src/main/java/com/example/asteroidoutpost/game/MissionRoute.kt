package com.example.asteroidoutpost.game

/**
 * One fixed asteroid placement along a route. Placed once at mission load,
 * never randomly re-generated.
 *
 * @param x      world-X (lateral position)
 * @param absY   absolute Y along the route — the ship spawns this asteroid
 *               when its travelled distance crosses `absY - ROUTE_SPAWN_DEPTH`.
 * @param z      world-Z (altitude relative to the deck plane)
 * @param type   asteroid type — drives HP / speed / mesh / damage.
 */
data class AsteroidPlacement(
    val x: Float,
    val absY: Float,
    val z: Float,
    val type: AsteroidType,
    /** Optional HP override. `null` = use `mission.asteroidHp * type.hpMul`
     *  (default). Set to a huge value (e.g. 1_000_000) to make a placement
     *  effectively unkillable — guarantees it reaches the shield for
     *  testing the impact response. */
    val hpOverride: Int? = null,
)

/**
 * Pre-authored corridor of asteroids. A mission with `route != null` runs in
 * "tunnel mode": the ship glides forward at SHIP_CRUISE_SPEED through the
 * placements in `asteroids` (sorted by `absY` ascending), and the mission
 * ends when `shipPosY >= endY` and no live asteroids remain.
 *
 * @param endY       distance (world units) the ship must travel.
 * @param asteroids  placements — keep sorted by absY ascending; the
 *                   MissionRunner walks through with a single cursor.
 */
data class MissionRoute(
    val endY: Float,
    val asteroids: List<AsteroidPlacement>,
)

/**
 * Campaign corridors. Five tunnels of growing length / density, each
 * onboarding one new asteroid type so the player meets the bestiary in
 * stages (same curriculum as the old wave-based campaign, just delivered
 * as a forward flight instead of "defend the platform").
 *
 *  1. NORMAL only        — learn tap-priority + manual fire.
 *  2. + FAST             — learn to swing the turret quickly.
 *  3. + HEAVY (+ENERGY)  — first useful shield moment.
 *  4. + EXPLOSIVE        — AoE chain reactions.
 *  5. all five types     — graduation, long corridor at full density.
 */
object MissionRoutes {

    val CAMPAIGN_1: MissionRoute = generateUniformCorridor(
        startY        = 40f,
        endY          = 95f,
        corridorEndY  = 110f,
        fillerCount   = 16,
        xRange        = -3.5f to 3.5f,
        zRange        = -1.5f to 5.0f,
        seed          = 1001L,
        typeWeights   = mapOf(
            AsteroidType.NORMAL to 1.0f,
        ),
    )

    val CAMPAIGN_2: MissionRoute = generateUniformCorridor(
        startY        = 50f,
        endY          = 130f,
        corridorEndY  = 145f,
        fillerCount   = 30,
        xRange        = -4.0f to 4.0f,
        zRange        = -1.5f to 5.5f,
        seed          = 1002L,
        typeWeights   = mapOf(
            AsteroidType.NORMAL to 0.55f,
            AsteroidType.FAST   to 0.45f,
        ),
    )

    val CAMPAIGN_3: MissionRoute = generateUniformCorridor(
        startY        = 55f,
        endY          = 155f,
        corridorEndY  = 170f,
        fillerCount   = 42,
        xRange        = -4.2f to 4.2f,
        zRange        = -2.0f to 5.5f,
        seed          = 1003L,
        typeWeights   = mapOf(
            AsteroidType.NORMAL to 0.50f,
            AsteroidType.HEAVY  to 0.40f,
            AsteroidType.ENERGY to 0.10f,
        ),
    )

    val CAMPAIGN_4: MissionRoute = generateUniformCorridor(
        startY        = 60f,
        endY          = 175f,
        corridorEndY  = 195f,
        fillerCount   = 55,
        xRange        = -4.3f to 4.3f,
        zRange        = -2.0f to 6.0f,
        seed          = 1004L,
        typeWeights   = mapOf(
            AsteroidType.NORMAL    to 0.40f,
            AsteroidType.EXPLOSIVE to 0.40f,
            AsteroidType.FAST      to 0.15f,
            AsteroidType.ENERGY    to 0.05f,
        ),
    )

    val CAMPAIGN_5: MissionRoute = generateUniformCorridor(
        startY        = 70f,
        endY          = 215f,
        corridorEndY  = 235f,
        fillerCount   = 80,
        xRange        = -4.5f to 4.5f,
        zRange        = -2.0f to 6.0f,
        seed          = 1005L,
        typeWeights   = mapOf(
            AsteroidType.NORMAL    to 0.25f,
            AsteroidType.FAST      to 0.20f,
            AsteroidType.HEAVY     to 0.25f,
            AsteroidType.EXPLOSIVE to 0.25f,
            AsteroidType.ENERGY    to 0.05f,
        ),
    )

    /**
     * Procedural corridor — uniform random (x, z) per asteroid, Y drawn
     * from a stratified grid + jitter so the spacing along the route
     * is roughly even but never perfectly periodic. Type sampled from
     * [typeWeights] (need not sum to 1; normalised here).
     *
     * Reproducible by `seed` — same input always builds the same route.
     */
    private fun generateUniformCorridor(
        startY: Float,
        endY: Float,
        corridorEndY: Float,
        fillerCount: Int,
        xRange: Pair<Float, Float>,
        zRange: Pair<Float, Float>,
        seed: Long,
        typeWeights: Map<AsteroidType, Float>,
    ): MissionRoute {
        val rng = kotlin.random.Random(seed)
        val (xMin, xMax) = xRange
        val (zMin, zMax) = zRange
        // Pre-flatten the weights into parallel arrays + a cumulative
        // sum so each sample is a single binary search.
        val totalWeight = typeWeights.values.sum().coerceAtLeast(1e-6f)
        val types       = typeWeights.keys.toTypedArray()
        val cumulative  = FloatArray(types.size).also { acc ->
            var run = 0f
            typeWeights.values.forEachIndexed { i, w ->
                run += w / totalWeight
                acc[i] = run
            }
        }
        // Stratified Y sampling — divide [startY, endY] into `fillerCount`
        // equal cells, pick one Y inside each (with jitter inside the cell).
        // Guarantees uniform-ish coverage without empty stretches.
        val cellY = (endY - startY) / fillerCount
        val fillers = (0 until fillerCount).map { i ->
            val yBase = startY + i * cellY
            val y     = yBase + rng.nextFloat() * cellY
            val x     = xMin + rng.nextFloat() * (xMax - xMin)
            val z     = zMin + rng.nextFloat() * (zMax - zMin)
            val type  = pickType(rng, types, cumulative)
            AsteroidPlacement(x, y, z, type)
        }
        return MissionRoute(endY = corridorEndY, asteroids = fillers)
    }

    private fun pickType(
        rng: kotlin.random.Random,
        types: Array<AsteroidType>,
        cumulative: FloatArray,
    ): AsteroidType {
        val r = rng.nextFloat()
        for (i in cumulative.indices) if (r < cumulative[i]) return types[i]
        return types.last()
    }
}
