package com.example.g3.sim

data class CombatStats(
    val maxShield: Float,
    val maxHull: Float,
    val damagePerShot: Float = 0f,
    var shield: Float = maxShield,
    var hull: Float = maxHull
) {
    val isDestroyed: Boolean get() = hull <= 0f

    fun applyDamage(damage: Float): DamageResult {
        val shieldDamage = minOf(damage, shield)
        val overflow = damage - shieldDamage
        val hullDamage = minOf(overflow, hull)
        shield -= shieldDamage
        hull -= hullDamage
        return DamageResult(
            shieldDamage = shieldDamage,
            hullDamage = hullDamage,
            destroyed = hull <= 0f
        )
    }

    companion object {
        fun fighter() = CombatStats(maxShield = 300f, maxHull = 50f, damagePerShot = 20f)
        fun station() = CombatStats(maxShield = 20000f, maxHull = 1000f, damagePerShot = 0f)
    }
}

data class DamageResult(
    val shieldDamage: Float,
    val hullDamage: Float,
    val destroyed: Boolean
)
