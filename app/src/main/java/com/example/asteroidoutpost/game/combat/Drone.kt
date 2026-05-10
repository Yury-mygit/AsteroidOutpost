package com.example.asteroidoutpost.game.combat

/**
 * Autonomous interceptor drone — spawned by the DRONES ability. Carries its
 * own continuous laser beam (a [Beam] in the effects list whose source/aim
 * closures point back at this drone), its own target tracking (sticky until
 * the asteroid dies, then re-pick nearest), and its own lifetime. The
 * MissionRunner ticks each drone per frame to update position, pick a new
 * target on kill, and rotate its mesh to face the velocity vector.
 *
 * The drone is NOT a [WeaponEffect] — its lifetime and the laser's lifetime
 * are coupled but the drone owns position/AI state while the [Beam]
 * (created at spawn time, kept in `effects`) handles the actual damage
 * application. When the drone's `lifeRemaining` hits zero we mark it for
 * removal; the linked Beam expires on its own duration timer.
 */
internal class Drone(
    var x: Float, var y: Float, var z: Float,
    var vx: Float = 0f, var vy: Float = 0f, var vz: Float = 0f,
    var lifeRemaining: Float = DraftCombat.DRONE_LIFETIME_SEC,
    /** Sticky lock onto current target asteroid id; -1 = no target. */
    var targetId: Long = -1L,
    /** rotationY for the mesh (radians) — tracks velocity direction so the
     *  interceptor reads as flying along its heading. */
    var heading: Float = 0f,
    /** Index in the swarm — determines this drone's orbit-angle offset around
     *  the target so multiple drones don't pile up on a single asteroid. */
    var swarmIndex: Int = 0,
)
