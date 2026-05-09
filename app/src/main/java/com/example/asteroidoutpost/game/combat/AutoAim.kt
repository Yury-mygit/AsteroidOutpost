package com.example.asteroidoutpost.game.combat

import com.example.asteroidoutpost.game.AsteroidType
import com.example.asteroidoutpost.game.WeaponId

/**
 * Pure targeting helpers for the central turret, side turrets, and ability
 * spawn points. Stateless — every function takes its inputs explicitly so
 * the same logic ports between Outpost and a future g3 reuse without any
 * MainActivity coupling.
 *
 * State-mutating target selection (`centralTargetId` write, sticky-lock
 * release) lives in MainActivity and composes these helpers.
 */

/**
 * Pick an asteroid type from a weight map. Empty / zero-sum maps fall back
 * to NORMAL. Weights don't need to be normalised — the function rescales
 * with a single uniform random draw.
 */
internal fun pickAsteroidType(weights: Map<AsteroidType, Float>): AsteroidType {
    if (weights.isEmpty()) return AsteroidType.NORMAL
    var total = 0f
    for (w in weights.values) if (w > 0f) total += w
    if (total <= 0f) return AsteroidType.NORMAL
    var roll = Math.random().toFloat() * total
    for ((type, w) in weights) {
        if (w <= 0f) continue
        roll -= w
        if (roll <= 0f) return type
    }
    return AsteroidType.NORMAL  // fallback for floating-point edge cases
}

/**
 * True if the given asteroid sits within ±halfArcRad of straight-up
 * (vertical = 0 rad in our atan2(dx, dz) convention) seen from the
 * source point (sx, sz). Targets at or below source-Z are out of arc
 * by definition (a turret can't engage something that's level-with or
 * behind itself in this side-view).
 */
internal fun isWithinArc(a: Asteroid, sx: Float, sz: Float, halfArcRad: Float): Boolean {
    val dx = a.xPos - sx
    val dz = a.zPos - sz
    if (dz <= 0f) return false
    val ang = kotlin.math.atan2(dx, dz)
    return kotlin.math.abs(ang) <= halfArcRad
}

/** Half-arc of the currently mounted central-turret weapon. */
internal fun centralWeaponHalfArc(weaponId: WeaponId): Float = when (weaponId) {
    WeaponId.HEAVY_CANNON -> DraftCombat.ARC_CENTRAL_CANNON_HALF_RAD
    WeaponId.AUTOMATIC    -> DraftCombat.ARC_CENTRAL_MG_HALF_RAD
}

/**
 * Closest live asteroid to (sx, sz) constrained to an arc cone. Used by
 * side turrets so they don't twist past their physical arc to engage an
 * asteroid sweeping the flank.
 */
internal fun nearestAsteroidInArc(
    asteroids: List<Asteroid>,
    sx: Float, sz: Float,
    halfArcRad: Float,
): Asteroid? {
    var best: Asteroid? = null
    var bestDist = Float.POSITIVE_INFINITY
    for (a in asteroids) {
        if (a.hp <= 0) continue
        if (!isWithinArc(a, sx, sz, halfArcRad)) continue
        val dx = a.xPos - sx
        val dz = a.zPos - sz
        val d2 = dx * dx + dz * dz
        if (d2 < bestDist) { bestDist = d2; best = a }
    }
    return best
}

/** Highest-current-HP live asteroid within an arc cone from (sx, sz). */
internal fun bestHpTargetInArc(
    asteroids: List<Asteroid>,
    sx: Float, sz: Float,
    halfArcRad: Float,
): Asteroid? {
    var best: Asteroid? = null
    var bestHp = Int.MIN_VALUE
    var bestD2 = Float.POSITIVE_INFINITY
    for (a in asteroids) {
        if (a.hp <= 0) continue
        if (!isWithinArc(a, sx, sz, halfArcRad)) continue
        val dx = a.xPos - sx
        val dz = a.zPos - sz
        val d2 = dx * dx + dz * dz
        if (a.hp > bestHp || (a.hp == bestHp && d2 < bestD2)) {
            bestHp = a.hp
            bestD2 = d2
            best   = a
        }
    }
    return best
}

/**
 * Closest live asteroid within `DraftCombat.TAP_PICK_RADIUS` of the given
 * world point, or null if the tap landed in empty space.
 */
internal fun pickAsteroidAt(
    asteroids: List<Asteroid>,
    wx: Float, wz: Float,
): Asteroid? {
    var best: Asteroid? = null
    var bestD2 = Float.POSITIVE_INFINITY
    val r2 = DraftCombat.TAP_PICK_RADIUS * DraftCombat.TAP_PICK_RADIUS
    for (a in asteroids) {
        if (a.hp <= 0) continue
        val dx = a.xPos - wx
        val dz = a.zPos - wz
        val d2 = dx * dx + dz * dz
        if (d2 <= r2 && d2 < bestD2) { bestD2 = d2; best = a }
    }
    return best
}
