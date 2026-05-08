package com.example.asteroidoutpost.game

/**
 * Hardcoded mission catalogue. M6 rebuild: 5 missions, each onboarding the
 * player to one new mechanic in turn. Numbers are reasonable starting points;
 * fine-tuning the difficulty curve is deferred.
 *
 * Curriculum (idea.txt task 11):
 *  1. Учебная тревога  — aim & manual fire (NORMAL only).
 *  2. Быстрые цели     — FAST joins; learn to swing the turret.
 *  3. Тяжёлая угроза   — HEAVY joins; first useful shield moment + ENERGY rare.
 *  4. Взрывная цепочка — EXPLOSIVE clusters; learn the AoE combo.
 *  5. Проверка базы    — all types together at full pressure.
 */
object Missions {
    val ALL: List<MissionConfig> = listOf(
        // -------------------------------------------------------------------
        // Mission 1 — onboarding for aim + hold-to-fire. Slow, sparse, all
        // normal asteroids. The player just gets used to the new controls.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 1,
            name        = "Учебная тревога",
            description = "Прицеливайтесь касанием и удерживайте палец, чтобы стрелять. Обычные цели, низкая скорость.",
            difficulty  = "Лёгкая",
            waves       = List(2) {
                WaveConfig(asteroidCount = 7, spawnIntervalSec = 2.6f)
            },
            asteroidHp    = 50,
            asteroidSpeed = 0.8f,
            baseHp        = 100,
        ),

        // -------------------------------------------------------------------
        // Mission 2 — introduces FAST. Player has to swing the turret quickly.
        // Each wave dials up the FAST share so the lesson lands gradually.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 2,
            name        = "Быстрые цели",
            description = "Появились малые быстрые астероиды. Учитесь резко менять направление огня.",
            difficulty  = "Лёгкая",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 10, spawnIntervalSec = 2.0f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.7f,
                        AsteroidType.FAST   to 0.3f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 12, spawnIntervalSec = 1.8f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.5f,
                        AsteroidType.FAST   to 0.5f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 14, spawnIntervalSec = 1.6f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.3f,
                        AsteroidType.FAST   to 0.7f,
                    ),
                ),
            ),
            asteroidHp    = 60,
            asteroidSpeed = 1.0f,
            baseHp        = 100,
        ),

        // -------------------------------------------------------------------
        // Mission 3 — introduces HEAVY (chunky, slow, hits twice as hard).
        // Last wave sprinkles ENERGY so the player tries the buff and finds a
        // genuine reason to use the shield against incoming HEAVY hits.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 3,
            name        = "Тяжёлая угроза",
            description = "Крупные тяжёлые астероиды бьют по базе вдвое сильнее. Используйте щит, когда станет жарко.",
            difficulty  = "Средняя",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 12, spawnIntervalSec = 1.8f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.7f,
                        AsteroidType.HEAVY  to 0.3f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 14, spawnIntervalSec = 1.7f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.5f,
                        AsteroidType.HEAVY  to 0.5f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 16, spawnIntervalSec = 1.5f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.3f,
                        AsteroidType.HEAVY  to 0.6f,
                        AsteroidType.ENERGY to 0.1f,
                    ),
                ),
            ),
            asteroidHp    = 80,
            asteroidSpeed = 1.0f,
            baseHp        = 110,
        ),

        // -------------------------------------------------------------------
        // Mission 4 — introduces EXPLOSIVE in dense clusters. Spawn interval
        // drops so neighbours stay close on screen long enough for the chain
        // reaction to feel rewarding.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 4,
            name        = "Взрывная цепочка",
            description = "Взрывоопасные астероиды бьют соседей при уничтожении. Ловите моменты для комбо.",
            difficulty  = "Средняя",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 14, spawnIntervalSec = 1.4f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.6f,
                        AsteroidType.EXPLOSIVE to 0.3f,
                        AsteroidType.FAST      to 0.1f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 16, spawnIntervalSec = 1.3f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.5f,
                        AsteroidType.EXPLOSIVE to 0.4f,
                        AsteroidType.ENERGY    to 0.1f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 18, spawnIntervalSec = 1.2f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.35f,
                        AsteroidType.EXPLOSIVE to 0.5f,
                        AsteroidType.FAST      to 0.1f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
            ),
            asteroidHp    = 100,
            asteroidSpeed = 1.1f,
            baseHp        = 110,
        ),

        // -------------------------------------------------------------------
        // Mission 5 — graduation. All five types together, dense waves, harder
        // numbers. Player needs main weapon + side turrets + shield + buff
        // timing to keep up.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 5,
            name        = "Проверка базы",
            description = "Все типы астероидов, плотные волны. Удержите базу под максимальным давлением.",
            difficulty  = "Высокая",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 18, spawnIntervalSec = 1.3f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.4f,
                        AsteroidType.FAST      to 0.25f,
                        AsteroidType.HEAVY     to 0.2f,
                        AsteroidType.EXPLOSIVE to 0.1f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 20, spawnIntervalSec = 1.2f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.3f,
                        AsteroidType.FAST      to 0.2f,
                        AsteroidType.HEAVY     to 0.3f,
                        AsteroidType.EXPLOSIVE to 0.15f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 22, spawnIntervalSec = 1.1f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.2f,
                        AsteroidType.FAST      to 0.2f,
                        AsteroidType.HEAVY     to 0.25f,
                        AsteroidType.EXPLOSIVE to 0.3f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 24, spawnIntervalSec = 1.0f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.15f,
                        AsteroidType.FAST      to 0.25f,
                        AsteroidType.HEAVY     to 0.3f,
                        AsteroidType.EXPLOSIVE to 0.25f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
            ),
            asteroidHp    = 120,
            asteroidSpeed = 1.4f,
            baseHp        = 130,
        ),
    )
}
