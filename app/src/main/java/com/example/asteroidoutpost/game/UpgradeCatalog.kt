package com.example.asteroidoutpost.game

/**
 * The three meta-progression upgrades. Each has 3 levels (starting at 1).
 *
 * Robot damage and turret damage REPLACE the bullet damage at runtime
 * (level 1 == current default). Base HP gives an additive bonus on top of
 * the mission's baseHp, so missions that are inherently harder still show
 * differentiation while upgrades scale the player up.
 */
enum class UpgradeType { ROBOT_DAMAGE, BASE_HP, TURRET_DAMAGE }

object UpgradeCatalog {

    const val MAX_LEVEL = 3

    /** Effective gameplay value at a given level (1..MAX_LEVEL). */
    fun robotDamageAt(level: Int): Int = when (level.coerceIn(1, MAX_LEVEL)) {
        1 -> 10; 2 -> 15; else -> 22
    }

    fun turretDamageAt(level: Int): Int = when (level.coerceIn(1, MAX_LEVEL)) {
        1 -> 5; 2 -> 8; else -> 12
    }

    /** Base HP bonus added to mission.baseHp. Level 1 contributes nothing. */
    fun baseHpBonusAt(level: Int): Int = when (level.coerceIn(1, MAX_LEVEL)) {
        1 -> 0; 2 -> 50; else -> 120
    }

    /**
     * Cost (metal) to upgrade FROM the given current level to the next.
     * Returns null if the upgrade is already at max level.
     */
    fun costToNext(type: UpgradeType, currentLevel: Int): Int? {
        if (currentLevel >= MAX_LEVEL) return null
        return when (type) {
            UpgradeType.ROBOT_DAMAGE  -> intArrayOf(20, 40, 80)[currentLevel - 1]
            UpgradeType.BASE_HP       -> intArrayOf(25, 50, 100)[currentLevel - 1]
            UpgradeType.TURRET_DAMAGE -> intArrayOf(20, 40, 80)[currentLevel - 1]
        }
    }

    /** Human-readable name for the upgrades screen. */
    fun displayName(type: UpgradeType): String = when (type) {
        UpgradeType.ROBOT_DAMAGE  -> "Урон робота"
        UpgradeType.BASE_HP       -> "Прочность базы"
        UpgradeType.TURRET_DAMAGE -> "Урон турелей"
    }

    /** Short effect description for the upgrades screen. */
    fun effectDescription(type: UpgradeType): String = when (type) {
        UpgradeType.ROBOT_DAMAGE  -> "Робот наносит больше урона астероидам."
        UpgradeType.BASE_HP       -> "База выдерживает больше попаданий."
        UpgradeType.TURRET_DAMAGE -> "Турели быстрее уничтожают астероиды."
    }

    /** Effective value preview at a given level — for UI display. */
    fun previewValue(type: UpgradeType, level: Int): String = when (type) {
        UpgradeType.ROBOT_DAMAGE  -> "Урон: ${robotDamageAt(level)}"
        UpgradeType.BASE_HP       -> "Бонус HP: +${baseHpBonusAt(level)}"
        UpgradeType.TURRET_DAMAGE -> "Урон: ${turretDamageAt(level)}"
    }

    /** Get current level of a given upgrade from progress. */
    fun levelOf(progress: GameProgress, type: UpgradeType): Int = when (type) {
        UpgradeType.ROBOT_DAMAGE  -> progress.robotDamageLevel
        UpgradeType.BASE_HP       -> progress.baseHpLevel
        UpgradeType.TURRET_DAMAGE -> progress.turretDamageLevel
    }

    /** Return progress with the given upgrade incremented by 1 level. Caller checks affordability. */
    fun applyPurchase(progress: GameProgress, type: UpgradeType, cost: Int): GameProgress = when (type) {
        UpgradeType.ROBOT_DAMAGE  -> progress.copy(metal = progress.metal - cost, robotDamageLevel  = progress.robotDamageLevel + 1)
        UpgradeType.BASE_HP       -> progress.copy(metal = progress.metal - cost, baseHpLevel       = progress.baseHpLevel + 1)
        UpgradeType.TURRET_DAMAGE -> progress.copy(metal = progress.metal - cost, turretDamageLevel = progress.turretDamageLevel + 1)
    }
}
