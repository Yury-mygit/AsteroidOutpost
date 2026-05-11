package com.example.asteroidoutpost.game

/**
 * Asteroid variants introduced in M5. Each type modifies the baseline mission
 * numbers (`asteroidHp`, `asteroidSpeed`, `ASTEROID_HALF`, `PLATFORM_DMG_PER_HIT`)
 * via the multipliers below. On-death side effects (EXPLOSIVE → AoE damage,
 * ENERGY → main-weapon buff) live in the tick handler — kept out of this enum
 * so the data stays purely descriptive.
 *
 *  - NORMAL    — baseline.
 *  - FAST      — small, low HP, fast; reaches the base quickly if ignored.
 *  - HEAVY     — large, lots of HP, slow; deals double damage on impact.
 *  - EXPLOSIVE — baseline stats; on death damages neighbouring asteroids.
 *  - ENERGY    — rare; on death triggers a temporary main-weapon damage buff.
 */
enum class AsteroidType(
    val hpMul: Float,
    val speedMul: Float,
    val halfMul: Float,
    val platformDmgMul: Float,
) {
    NORMAL    (1.0f, 1.0f, 1.0f, 1.0f),
    FAST      (0.4f, 2.0f, 0.7f, 1.0f),
    HEAVY     (3.0f, 0.5f, 1.5f, 2.0f),
    EXPLOSIVE (1.0f, 1.0f, 1.0f, 1.0f),
    ENERGY    (0.6f, 0.8f, 1.0f, 1.0f),
    // ENEMY_SHIP — special non-asteroid combat target. Spawned by a
    // combat mission (mission.enemyShipDelaySec != null) at y=shipPosY+20;
    // movement and firing handled in MissionRunner.tick (NOT by the
    // standard linear-fall asteroid update). HP/half multipliers below
    // are applied to mission baseline as for any other type, but the
    // mission's combat entry should set asteroidHp big enough to feel
    // like a ship rather than a rock (~400).
    ENEMY_SHIP(4.0f, 0.0f, 3.0f, 3.0f),
}
