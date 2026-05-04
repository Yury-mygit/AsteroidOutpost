package com.example.asteroidoutpost.game

/**
 * Hardcoded mission catalogue. Numbers come from ROADMAP.md fase B; M5 added
 * `typeWeights` per wave to mix in special asteroid types for testing. M6 will
 * rebuild the campaign properly to teach each type one at a time — until then
 * mission 1 stays a clean onboarding (all NORMAL) and missions 2/3 sprinkle in
 * FAST/HEAVY/EXPLOSIVE/ENERGY so the new mechanics are exercisable.
 */
object Missions {
    private val ENERGY_SPRINKLE: Map<AsteroidType, Float> = mapOf(
        AsteroidType.NORMAL to 0.95f,
        AsteroidType.ENERGY to 0.05f,
    )

    val ALL: List<MissionConfig> = listOf(
        MissionConfig(
            id          = 1,
            name        = "Учебная тревога",
            description = "Лёгкая атака малых астероидов.",
            difficulty  = "Лёгкая",
            waves       = List(2) {
                WaveConfig(asteroidCount = 5, spawnIntervalSec = 3.0f)
            },
            asteroidHp    = 50,
            asteroidSpeed = 0.8f,
            baseHp        = 100,
        ),
        MissionConfig(
            id          = 2,
            name        = "Метеоритный поток",
            description = "Больше астероидов, появляются быстрые цели.",
            difficulty  = "Средняя",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 8, spawnIntervalSec = 2.0f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.7f,
                        AsteroidType.FAST   to 0.25f,
                        AsteroidType.ENERGY to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 8, spawnIntervalSec = 2.0f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.55f,
                        AsteroidType.FAST   to 0.4f,
                        AsteroidType.ENERGY to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 8, spawnIntervalSec = 2.0f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.5f,
                        AsteroidType.FAST   to 0.45f,
                        AsteroidType.ENERGY to 0.05f,
                    ),
                ),
            ),
            asteroidHp    = 80,
            asteroidSpeed = 1.2f,
            baseHp        = 100,
        ),
        MissionConfig(
            id          = 3,
            name        = "Тяжёлые астероиды",
            description = "Прочные и взрывоопасные цели, база под серьёзным давлением.",
            difficulty  = "Высокая",
            waves       = listOf(
                WaveConfig(
                    asteroidCount = 10, spawnIntervalSec = 1.5f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL to 0.5f,
                        AsteroidType.HEAVY  to 0.3f,
                        AsteroidType.FAST   to 0.15f,
                        AsteroidType.ENERGY to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 10, spawnIntervalSec = 1.5f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.35f,
                        AsteroidType.HEAVY     to 0.3f,
                        AsteroidType.EXPLOSIVE to 0.2f,
                        AsteroidType.FAST      to 0.1f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 10, spawnIntervalSec = 1.5f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.3f,
                        AsteroidType.HEAVY     to 0.3f,
                        AsteroidType.EXPLOSIVE to 0.25f,
                        AsteroidType.FAST      to 0.1f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
                WaveConfig(
                    asteroidCount = 10, spawnIntervalSec = 1.5f,
                    typeWeights = mapOf(
                        AsteroidType.NORMAL    to 0.2f,
                        AsteroidType.HEAVY     to 0.35f,
                        AsteroidType.EXPLOSIVE to 0.3f,
                        AsteroidType.FAST      to 0.1f,
                        AsteroidType.ENERGY    to 0.05f,
                    ),
                ),
            ),
            asteroidHp    = 150,
            asteroidSpeed = 1.5f,
            baseHp        = 120,
        ),
    )
}
