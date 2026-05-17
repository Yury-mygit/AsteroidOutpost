package com.example.asteroidoutpost.game

/**
 * Hardcoded mission catalogue. Style3 rebuild: the campaign is now five
 * route-mode corridors — the ship cruises forward at SHIP_CRUISE_SPEED
 * through a pre-placed asteroid field. Each mission onboards one new
 * asteroid type, mirroring the old wave-based curriculum.
 *
 * Curriculum:
 *  1. Тренировочный коридор  — only NORMAL, sparse. Learn tap-priority + fire.
 *  2. Сектор «Россыпь»       — adds FAST. Learn to swing turret quickly.
 *  3. Тяжёлый рукав          — adds HEAVY (+ENERGY rare). First useful shield.
 *  4. Минное поле            — adds EXPLOSIVE in clusters. AoE chain combos.
 *  5. Глубокий транзит       — all five types together, long corridor at full pressure.
 *
 * Missions 7-8 are combat-mission prototypes — surfaced through the
 * «Случайные» events overlay, not part of the campaign graph.
 */
object Missions {
    val ALL: List<MissionConfig> = listOf(
        // -------------------------------------------------------------------
        // Mission 1 — onboarding corridor. Only NORMAL placements, low
        // density. Player learns tap-to-priority, hold-to-fire, and how
        // the ship cruises forward on its own.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 1,
            name        = "Тренировочный коридор",
            description = "Короткий полёт по чистому маршруту. Касайтесь астероидов, чтобы взять их в приоритет, удерживайте палец для огня.",
            difficulty  = "Лёгкая",
            waves       = emptyList(),
            asteroidHp    = 50,
            asteroidSpeed = 0f,
            baseHp        = 100,
            route       = MissionRoutes.CAMPAIGN_1,
        ),

        // -------------------------------------------------------------------
        // Mission 2 — introduces FAST. Player has to swing the turret
        // quickly between slow rocks and small fast ones.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 2,
            name        = "Сектор «Россыпь»",
            description = "В коридоре мелькают мелкие быстрые астероиды. Учитесь резко переключать цели.",
            difficulty  = "Лёгкая",
            waves       = emptyList(),
            asteroidHp    = 60,
            asteroidSpeed = 0f,
            baseHp        = 100,
            route       = MissionRoutes.CAMPAIGN_2,
        ),

        // -------------------------------------------------------------------
        // Mission 3 — introduces HEAVY (chunky, slow, hits twice as hard).
        // Sprinkles ENERGY so the player finds reasons to use the shield
        // and the buff drops.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 3,
            name        = "Тяжёлый рукав",
            description = "Крупные глыбы трудно сбить с курса. Включайте щит, если не успеваете расстрелять. Ловите энергетические бонусы.",
            difficulty  = "Средняя",
            waves       = emptyList(),
            asteroidHp    = 80,
            asteroidSpeed = 0f,
            baseHp        = 110,
            route       = MissionRoutes.CAMPAIGN_3,
        ),

        // -------------------------------------------------------------------
        // Mission 4 — introduces EXPLOSIVE clusters. The generator stays
        // uniform, but EXPLOSIVE share is high enough that you'll often
        // see two or three in a row — ripe for AoE chain reactions.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 4,
            name        = "Минное поле",
            description = "Взрывоопасные астероиды детонируют соседей. Ловите моменты для цепной реакции.",
            difficulty  = "Средняя",
            waves       = emptyList(),
            asteroidHp    = 100,
            asteroidSpeed = 0f,
            baseHp        = 110,
            route       = MissionRoutes.CAMPAIGN_4,
        ),

        // -------------------------------------------------------------------
        // Mission 5 — graduation corridor. All five types, longest route,
        // densest field. Player needs central + side turrets + shield +
        // buff timing to keep up.
        // -------------------------------------------------------------------
        MissionConfig(
            id          = 5,
            name        = "Глубокий транзит",
            description = "Длинный коридор сквозь плотный пояс. Все типы препятствий, максимальное давление. Финальный экзамен.",
            difficulty  = "Высокая",
            waves       = emptyList(),
            asteroidHp    = 120,
            asteroidSpeed = 0f,
            baseHp        = 130,
            route       = MissionRoutes.CAMPAIGN_5,
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
