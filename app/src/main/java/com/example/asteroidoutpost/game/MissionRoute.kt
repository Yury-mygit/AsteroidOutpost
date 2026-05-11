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
 * Hardcoded routes. Minimum slice — one corridor, used by mission 6.
 */
object MissionRoutes {

    /** First corridor — procedurally generated for a uniform-volume feel
     *  (handful of fixed unkillable test rocks + ~35 random fillers).
     *
     *  Y starts at 70 so the first asteroid materialises ~10 sec into the
     *  run (empty lead-in). Y range ends at 195; corridor [endY] = 215
     *  gives the trailing rocks time to fly past the ship before the win
     *  condition triggers (`shipDist >= endY AND asteroids empty`).
     *
     *  Three asteroids tagged hpOverride = 1_000_000 — turrets can't kill
     *  them in flight, guaranteed to crash into the shield. Used to test
     *  impact bloom + shield depletion. */
    val FIRST_CORRIDOR: MissionRoute = generateUniformCorridor(
        startY        = 70f,
        endY          = 195f,
        fillerCount   = 60,
        xRange        = -4.5f to 4.5f,
        zRange        = -2.0f to 6.0f,
        seed          = 4242L,
        unkillables   = emptyList(),
        corridorEndY  = 215f,
    )

    /**
     * Procedural corridor — uniform random (x, z) per asteroid, Y drawn
     * from a stratified grid + jitter so the spacing along the route
     * is roughly even but never perfectly periodic. Type is weighted
     * (60 % NORMAL, 25 % FAST, 15 % HEAVY).
     *
     * Reproducible by `seed` — same input always builds the same route.
     */
    private fun generateUniformCorridor(
        startY: Float,
        endY: Float,
        fillerCount: Int,
        xRange: Pair<Float, Float>,
        zRange: Pair<Float, Float>,
        seed: Long,
        unkillables: List<Triple<Float, Float, Float>>,
        corridorEndY: Float,
    ): MissionRoute {
        val rng = kotlin.random.Random(seed)
        val (xMin, xMax) = xRange
        val (zMin, zMax) = zRange
        // Stratified Y sampling — divide [startY, endY] into `fillerCount`
        // equal cells, pick one Y inside each (with jitter inside the cell).
        // Guarantees uniform-ish coverage without empty stretches.
        val cellY = (endY - startY) / fillerCount
        val fillers = (0 until fillerCount).map { i ->
            val yBase = startY + i * cellY
            val y     = yBase + rng.nextFloat() * cellY
            val x     = xMin + rng.nextFloat() * (xMax - xMin)
            val z     = zMin + rng.nextFloat() * (zMax - zMin)
            val type  = pickType(rng)
            AsteroidPlacement(x, y, z, type)
        }
        val unkillablePlacements = unkillables.map { (y, x, z) ->
            AsteroidPlacement(x, y, z, AsteroidType.HEAVY, hpOverride = 1_000_000)
        }
        // Merge + sort by absY ascending (MissionRunner walks a single cursor).
        val all = (fillers + unkillablePlacements).sortedBy { it.absY }
        return MissionRoute(endY = corridorEndY, asteroids = all)
    }

    private fun pickType(rng: kotlin.random.Random): AsteroidType {
        val r = rng.nextFloat()
        return when {
            r < 0.60f -> AsteroidType.NORMAL
            r < 0.85f -> AsteroidType.FAST
            else      -> AsteroidType.HEAVY
        }
    }
}
