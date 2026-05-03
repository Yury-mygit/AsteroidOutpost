package com.example.asteroidoutpost.sim

import com.example.asteroidoutpost.ai.Vec2

sealed interface CombatEvent {
    data class ProjectileSpawned(val projectile: ProjectileState) : CombatEvent
    data class ProjectileHit(val projectileId: Int, val targetId: Int?, val damage: Float) : CombatEvent
    data class ExplosionSpawned(val position: Vec2, val z: Float) : CombatEvent
    data class DamageApplied(val targetId: Int, val shieldDamage: Float, val hullDamage: Float) : CombatEvent
    data class ObjectDestroyed(val targetId: Int, val position: Vec2, val z: Float) : CombatEvent
}
