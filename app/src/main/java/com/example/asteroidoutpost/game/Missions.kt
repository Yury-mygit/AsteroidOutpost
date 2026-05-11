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

        // -------------------------------------------------------------------
        // Mission 6 — first route/tunnel-mode mission. Ship glides forward
        // at constant speed through a pre-placed corridor of asteroids.
        // `waves` is unused; `route` drives the spawn/win logic.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 6,
            name        = "Маршрут: первый коридор",
            description = "Корабль идёт вперёд по заданному маршруту. Расстреливайте астероиды по курсу. Победа — дойти до конца коридора.",
            difficulty  = "Средняя",
            waves       = emptyList(),
            asteroidHp    = 70,
            asteroidSpeed = 0f,  // route asteroids don't fall — they hold altitude
            baseHp        = 110,
            route       = MissionRoutes.FIRST_CORRIDOR,
        ),

        // -------------------------------------------------------------------
        // Mission 7 — combat-mission prototype. Ship cruises forward
        // without any asteroid spawns; 10 sec after start an enemy ship
        // appears 20 units ahead and tracks the player at that offset,
        // firing one bolt every 1.5 sec. Win — kill the enemy. Lose —
        // standard ship-destroyed condition.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 7,
            name        = "Бой: одиночный перехватчик",
            description = "Враждебный корабль перехватывает наш курс. Он держит дистанцию и обстреливает из одной турели. Уничтожьте перехватчик.",
            difficulty  = "Средняя",
            waves       = emptyList(),
            asteroidHp    = 200,    // baseline; ENEMY_SHIP hpMul=4.0 → effective 800 HP
            asteroidSpeed = 0f,
            baseHp        = 130,
            enemyShipSpawns = listOf(
                EnemyShipSpawn(delaySec = 10f, xOffset = 0f),
            ),
        ),

        // -------------------------------------------------------------------
        // Mission 8 — combat-mission with three enemies. Same baseline HP
        // per ship, but they arrive in a staggered line (port flank →
        // centre → starboard flank) so the player splits attention. Tap
        // priority is your friend: focus rockets/drones/laser on one,
        // let turrets chew the others.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 8,
            name        = "Бой: три перехватчика",
            description = "Три вражеских корабля атакуют звеном. Каждый стреляет независимо. Выбирайте приоритетную цель — ракеты, дроны и лазер бьют по ней.",
            difficulty  = "Сложная",
            waves       = emptyList(),
            asteroidHp    = 150,    // a bit less than mission 7 since the player faces three at once → ~600 HP each
            asteroidSpeed = 0f,
            baseHp        = 160,
            enemyShipSpawns = listOf(
                EnemyShipSpawn(delaySec = 10f, xOffset = -2.0f),
                EnemyShipSpawn(delaySec = 13f, xOffset =  0f),
                EnemyShipSpawn(delaySec = 16f, xOffset =  2.0f),
            ),
        ),
    )
}
