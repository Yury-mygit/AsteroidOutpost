package com.example.g3.ai

/**
 * All tunable constants for the flight mission system.
 * 1 world unit = 10 m. Fighter length = 10 m = 1 WU.
 */
object FlightConfig {

    // ---------------------------------------------------------------------------
    // Physics
    // ---------------------------------------------------------------------------
    /** 400 km/h = 111.2 m/s = 11.12 WU/s */
    const val MAX_SPEED = 11.12f

    /** Max steering acceleration (WU/s²). High enough to keep formation at mission speed. */
    const val MAX_FORCE = 4.0f

    /** Ships start braking when this close to target slot. */
    const val ARRIVAL_SLOW_RADIUS = 4.0f

    /** Ships are considered "arrived" within this distance. */
    const val ARRIVAL_STOP_RADIUS = 0.4f

    // ---------------------------------------------------------------------------
    // Formation — wedge (V-shape)
    // Local frame: X = right relative to direction of travel, Y = forward.
    // Slot 0 is the leader at the tip.
    // ---------------------------------------------------------------------------
    val WEDGE_OFFSETS = listOf(
        Vec2( 0.0f,  0.0f),   // 0: leader
        Vec2(-2.5f, -2.5f),   // 1: left flank
        Vec2( 2.5f, -2.5f),   // 2: right flank
        Vec2(-5.0f, -5.0f),   // 3: left rear
        Vec2( 5.0f, -5.0f)    // 4: right rear
    )

    /** Min distance between ships before separation force kicks in. */
    const val SEPARATION_RADIUS = 2.0f

    // ---------------------------------------------------------------------------
    // Mission geometry
    // ---------------------------------------------------------------------------
    val ENEMY_STATION  = Vec2(0f, 150f)
    val ALLY_STATION   = Vec2(0f,  -2f)
    val ENEMY_GROUP_CENTER = Vec2(30f, 120f)

    /** Radius of circular orbit around each station. */
    const val ORBIT_RADIUS = 10.0f

    /** Wide orbit that encloses the enemy station and fighter group. */
    const val ENEMY_GROUP_ORBIT_RADIUS = 24.0f

    /** Leader moves slowly while the squad forms up behind it. */
    const val FORM_UP_LEADER_SPEED = MAX_SPEED * 0.35f

    /** Wingmen may fly fast while taking free V-slots behind the leader. */
    const val FORM_UP_WING_SPEED = MAX_SPEED

    /** Formation anchor speed during straight cruise (fraction of MAX_SPEED). */
    const val ANCHOR_SPEED = MAX_SPEED * 0.85f

    /** Formation anchor speed during orbit. */
    const val ORBIT_SPEED  = MAX_SPEED * 0.6f
}
