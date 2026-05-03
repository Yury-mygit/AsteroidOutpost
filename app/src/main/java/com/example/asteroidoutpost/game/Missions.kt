package com.example.asteroidoutpost.game

/**
 * Hardcoded mission catalogue. Numbers come from ROADMAP.md fase B.
 * Edit here to retune balance.
 */
object Missions {
    val ALL: List<MissionConfig> = listOf(
        MissionConfig(
            id          = 1,
            name        = "Учебная тревога",
            description = "Лёгкая атака малых астероидов.",
            difficulty  = "Лёгкая",
            waves       = List(2) { WaveConfig(asteroidCount = 5, spawnIntervalSec = 3.0f) },
            asteroidHp    = 50,
            asteroidSpeed = 0.8f,
            baseHp        = 100,
        ),
        MissionConfig(
            id          = 2,
            name        = "Метеоритный поток",
            description = "Больше астероидов, выше скорость падения.",
            difficulty  = "Средняя",
            waves       = List(3) { WaveConfig(asteroidCount = 8, spawnIntervalSec = 2.0f) },
            asteroidHp    = 80,
            asteroidSpeed = 1.2f,
            baseHp        = 100,
        ),
        MissionConfig(
            id          = 3,
            name        = "Тяжёлые астероиды",
            description = "Прочные астероиды, база получает серьёзный урон.",
            difficulty  = "Высокая",
            waves       = List(4) { WaveConfig(asteroidCount = 10, spawnIntervalSec = 1.5f) },
            asteroidHp    = 150,
            asteroidSpeed = 1.5f,
            baseHp        = 120,
        ),
    )
}
