package com.example.asteroidoutpost.game

/**
 * Catalogue of central-turret weapons. Side turrets are not selectable —
 * they keep the legacy fixed parameters in `DraftCombat`.
 *
 * Final per-shot damage is `effectiveMainWeaponDamage * damageMultiplier`,
 * where `effectiveMainWeaponDamage` comes from the upgrade level (M4 will
 * rename the upgrade track itself). The multiplier lets weapons differ in
 * damage feel without duplicating the upgrade ladder per weapon.
 *
 * `aoeRadius` and `aoeDamageMultiplier` are reserved for M2.2 (heavy cannon);
 * `aoeRadius == 0f` means a single-target weapon.
 */
enum class WeaponId { AUTOMATIC, HEAVY_CANNON, RAILGUN }

data class Weapon(
    val id: WeaponId,
    val displayName: String,
    val description: String,
    val fireIntervalSec: Float,
    val damageMultiplier: Float,
    val projectileSpeed: Float,
    val projectileHalfW: Float,
    val projectileHalfH: Float,
    val aoeRadius: Float = 0f,
    val aoeDamageMultiplier: Float = 0f,
)

object WeaponCatalog {

    val AUTOMATIC = Weapon(
        id = WeaponId.AUTOMATIC,
        displayName     = "Автомат",
        description     = "Частая стрельба, низкий урон. Хорош против малых астероидов.",
        fireIntervalSec = 0.15f,
        damageMultiplier = 1.0f,
        projectileSpeed = 25f,
        projectileHalfW = 0.0173f,
        projectileHalfH = 0.078f,
    )

    /**
     * Пушка — медленный фугасный снаряд с AoE-разлётом осколков. Чунки-снаряд,
     * медленный полёт (игрок видит траекторию до удара). На дуле — тёплый
     * cone-trefoil flash (spawnMuzzleBlast), как у Автомата, только крупнее
     * за счёт более толстого projectileHalfW. Эффективен против групп и
     * крупных астероидов за счёт area-of-effect.
     *
     * Enum id остался HEAVY_CANNON — историческое имя, переименование enum
     * раскатилось бы по всему MissionRunner/AutoAim/SceneAssembler без
     * выгоды; displayName/description делают пользовательскую переименовку.
     */
    val HEAVY_CANNON = Weapon(
        id = WeaponId.HEAVY_CANNON,
        displayName     = "Пушка",
        description     = "Фугасный снаряд с осколочным разлётом. Бьёт по области — удобен против групп и крупных астероидов.",
        fireIntervalSec = 1.0f,
        damageMultiplier = 3.0f,
        projectileSpeed = 18f,
        projectileHalfW = 0.065f,
        projectileHalfH = 0.117f,
        aoeRadius           = 0.5f,
        aoeDamageMultiplier = 0.6f,
    )

    /**
     * Рельсотрон — электромагнитный пуск. Тонкий snарад летит в 4× быстрее
     * чем у Пушки (72 vs 18 ед/с), пробивает цель насквозь (без AoE), за
     * собой оставляет яркий синий след через beam-pipeline. Медленная
     * перезарядка (2 с) и высокий per-shot урон (5×) — снайперское оружие
     * против одиночных HEAVY/ENEMY_SHIP. На дуле — lightning-разряды
     * (spawnRailgunMuzzle), оригинальный «рельсотронный» визуал.
     */
    val RAILGUN = Weapon(
        id = WeaponId.RAILGUN,
        displayName     = "Рельсотрон",
        description     = "Электромагнитный пуск. Тонкий снаряд летит вчетверо быстрее обычного, оставляя яркий синий след. Высокий урон по одной цели, медленная перезарядка.",
        fireIntervalSec = 2.0f,
        damageMultiplier = 5.0f,
        projectileSpeed = 108f,
        projectileHalfW = 0.0173f,
        projectileHalfH = 0.10f,
    )

    val ALL: List<Weapon> = listOf(AUTOMATIC, HEAVY_CANNON, RAILGUN)

    fun byId(id: WeaponId): Weapon = ALL.first { it.id == id }
}
