package com.example.asteroidoutpost.game.combat

import com.example.asteroidoutpost.game.AsteroidType

/**
 * Falling target for the player's turrets. Owned and mutated by the tick
 * loop on `DraftTickThread`; passive data otherwise.
 *
 * `id` is stable across frames (unlike list index, which compacts on death)
 * so cross-frame references — priority lock, homing missile target — work
 * correctly.
 *
 * Per-instance effective values (`speed`, `half`, `platformDmg`) are derived
 * once at spawn from mission baseline × type multipliers and cached here, so
 * the tick doesn't recompute them every frame.
 */
internal data class Asteroid(
    // Stable identity for cross-frame references (priority target lock,
    // homing missile target). Asteroids cannot be referenced by list index
    // because the list compacts on death.
    val id: Long,
    val xPos: Float,
    // 3D-pivot Phase 1: Y is depth into the screen. Asteroids spawn far
    // (high yPos) and approach yPos = 0 in sync with their Z-fall — so
    // they visually grow as they get close, while the existing
    // X/Z-based collision against shield arch and platform top still
    // triggers at the right moment (Y reaches 0 simultaneously with Z
    // reaching PLATFORM_TOP_Z).
    var yPos: Float = 0f,
    var zPos: Float,
    var hp: Int,
    // Captured at spawn from `hp` so the HP-bar fill can read fraction =
    // hp / maxHp without recomputing from mission × type multipliers.
    val maxHp: Int = hp,
    var rotation: Float = 0f,         // current angle in radians
    val rotationSpeed: Float = 0f,    // radians per second; randomised on spawn
    val type: AsteroidType = AsteroidType.NORMAL,
    // Per-asteroid effective values, derived from mission baseline × type
    // multipliers at spawn. Cached so the tick doesn't recompute every frame.
    val speed: Float = 0f,            // units/sec along -Z (screen-down)
    // 3D-pivot Phase 1: rate of approach toward camera along -Y, set so
    // yPos reaches 0 simultaneously with zPos reaching PLATFORM_TOP_Z.
    val depthSpeed: Float = 0f,       // units/sec along -Y (toward camera)
    val half:  Float = DraftCombat.ASTEROID_HALF,
    val platformDmg: Int = DraftCombat.PLATFORM_DMG_PER_HIT,
    // Picked at spawn from the per-type mesh pool (NORMAL/FAST randomize
    // across two grey variants for visual diversity). 0 = engine fallback.
    val meshHandle: Long = 0L,
    // E10.3 — previous-frame z position and rotation for motion-vector
    // tracking. xPos doesn't change (asteroids fall straight down) so we
    // don't need a prevX; but the spin around Z DOES move screen-pixels
    // around the asteroid silhouette so we cache it here. Snapshotted
    // BEFORE applying the per-frame movement so the next frame's render
    // has the matrix-pair that produced the current visible motion.
    var prevZ: Float = zPos,
    var prevY: Float = yPos,
    var prevRotation: Float = rotation,
)
