package com.example.asteroidoutpost.game

/**
 * One wave of asteroids inside a mission. The wave spawns [asteroidCount]
 * asteroids one by one with [spawnIntervalSec] between each. The wave is
 * considered done when every asteroid it spawned is gone (destroyed by
 * bullets or having reached the platform).
 *
 * [typeWeights] — relative spawn weights per `AsteroidType`. Empty map means
 * "all NORMAL". Weights don't have to sum to 1; the spawner normalises them.
 */
data class WaveConfig(
    val asteroidCount: Int,
    val spawnIntervalSec: Float,
    val typeWeights: Map<AsteroidType, Float> = emptyMap(),
)

/**
 * One playable mission. All numbers needed to drive a full run.
 *
 * Mode selection:
 *  - `route == null` (default) — wave-based: asteroids spawn from
 *    [waves] at random X, ship is stationary, win when all waves cleared.
 *  - `route != null` — tunnel mode: [waves] is ignored, the ship glides
 *    forward through the pre-placed [MissionRoute.asteroids] and wins
 *    when it reaches [MissionRoute.endY].
 */
data class MissionConfig(
    val id: Int,
    val name: String,
    val description: String,
    val difficulty: String,
    val waves: List<WaveConfig>,
    val asteroidHp: Int,
    val asteroidSpeed: Float,
    val baseHp: Int,
    val route: MissionRoute? = null,
    /** Debug — when true, central and side turrets hold fire for the
     *  whole mission. Lets the player observe shield/impact behaviour
     *  without weapons clearing the asteroid field first. */
    val weaponsDisabled: Boolean = false,
    /**
     * Combat-mission spawn list. Each entry describes one enemy ship —
     * delay (sec from mission start) and lateral offset (xPos). All
     * spawned ships hold station 20 units ahead of the player (yPos =
     * shipPosY + ENEMY_SHIP_LEAD_DISTANCE) and fire independently on
     * their own cooldown. `null` → standard route/wave mission with no
     * enemy ships. Win condition shifts to "all entries spawned AND
     * no enemy ships alive".
     */
    val enemyShipSpawns: List<EnemyShipSpawn>? = null,
)

/**
 * One enemy ship's spawn descriptor inside a combat mission. `delaySec`
 * counts from mission start; `xOffset` is the lateral position (world X,
 * 0 = centred). Multiple ships in a single mission read as a formation
 * spread along X.
 */
data class EnemyShipSpawn(
    val delaySec: Float,
    val xOffset: Float = 0f,
)
