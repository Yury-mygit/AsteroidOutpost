package com.example.asteroidoutpost.game.combat

/**
 * Tunables for combat — projectile / asteroid / turret / shield / ability / VFX numbers.
 *
 * Pure data: any constant that gameplay or scene-assembly code reads lives here.
 * Touch this file to retune balance / VFX feel; no logic should live in this object.
 *
 * `const val` for primitives, plain `val` for `FloatArray`s (Kotlin disallows
 * `const FloatArray`). Visibility is `internal` so the constants stay
 * module-private — when we eventually split the engine into its own Gradle
 * module, gameplay-tunables remain in the game module without changing.
 */
internal object DraftCombat {
    // Side-turret fire rate. The side turrets are cannon-style (heavy
    // bullet + AoE) since the muzzle-blast / motion-blur rework, so
    // this is paced like the central heavy cannon (1 shot/sec) rather
    // than the original M1 machine-gun cadence (~6.7 shots/sec).
    const val FIRE_INTERVAL_SEC: Float = 1.0f
    // Side-turret bullet specs (cannon-style). Mirrors the central
    // HEAVY_CANNON weapon's projectile parameters; differences in damage
    // scaling are handled by SIDE_DAMAGE_MUL applied to the upgrade
    // ladder's effectiveTurretDamage.
    const val SIDE_BULLET_SPEED:    Float = 18f
    const val SIDE_BULLET_HALF_W:   Float = 0.065f
    const val SIDE_BULLET_HALF_H:   Float = 0.117f
    const val SIDE_DAMAGE_MUL:      Float = 3f
    const val SIDE_AOE_RADIUS:      Float = 0.5f
    const val SIDE_AOE_DAMAGE_MUL:  Float = 0.6f
    const val BULLET_SPEED:      Float = 25f     // world units per second
    const val BULLET_HALF_W:     Float = 0.04f   // ~1.6% screen width
    const val BULLET_HALF_H:     Float = 0.18f   // ~3.3% screen height
    const val SCREEN_TOP_Z:      Float = 9.49f
    const val ASTEROID_HALF:     Float = 0.1235f
    const val DAMAGE_PER_HIT:    Int   = 10
    const val ASTEROID_SPEED:    Float = 1.0f   // units/sec downward
    const val ASTEROID_HP_INIT:  Int   = 100
    const val SPAWN_INTERVAL_SEC:Float = 3.0f
    const val SCREEN_BOTTOM_Z:   Float = -1.49f
    const val SCREEN_HALF_W:     Float = 2.47f
    const val PLATFORM_TOP_Z:    Float = -0.94f // matches platform z + halfH
    const val PLATFORM_HP_INIT:  Int   = 100
    const val PLATFORM_DMG_PER_HIT: Int = 20
    const val TURRET_HALF:       Float = 0.10f  // legacy — bbox of old square; kept for back-compat with existing collision math
    // Side turret pivot — top of the static base. Rotating mesh extends
    // upward from here; barrel tip is at TURRET_TOP_Z + SIDE_TOTAL_LEN.
    const val TURRET_TOP_Z:      Float = -0.90f
    const val TURRET_DMG:        Int   = 5      // half of DAMAGE_PER_HIT (legacy)
    // Central turret — main weapon. Now split into a static base sitting on
    // the platform and a rotating housing+barrel that pivots at base-top.
    // CENTRAL_TURRET_BASE_Z is the pivot location (base-top), and the
    // rotating mesh extends from the pivot upward along +Z (forward in
    // model space) by CENTRAL_HOUSING_LENGTH + CENTRAL_BARREL_LENGTH +
    // CENTRAL_MUZZLE_LENGTH = 2 × CENTRAL_TURRET_HALF_H so the muzzle Z
    // formula `BASE_Z + 2 × HALF_H` keeps working for bullet spawn.
    const val CENTRAL_TURRET_X:        Float = 0f
    const val CENTRAL_TURRET_BASE_Z:   Float = -0.90f // platform top + CENTRAL_BASE_HEIGHT
    const val CENTRAL_TURRET_HALF_W:   Float = 0.10f  // legacy — = housing half-W
    const val CENTRAL_TURRET_HALF_H:   Float = 0.30f  // half of total rotating-part length

    // Static base (collar between platform top and turret pivot).
    const val CENTRAL_BASE_HEIGHT:     Float = 0.04f
    const val CENTRAL_BASE_HALF_W:     Float = 0.13f
    // Rotating housing + barrel + muzzle ring (origin at pivot, +Z forward).
    const val CENTRAL_HOUSING_HALF_W:  Float = 0.10f
    const val CENTRAL_HOUSING_LENGTH:  Float = 0.18f
    const val CENTRAL_BARREL_HALF_W:   Float = 0.035f
    const val CENTRAL_BARREL_LENGTH:   Float = 0.36f
    const val CENTRAL_MUZZLE_HALF_W:   Float = 0.050f
    const val CENTRAL_MUZZLE_LENGTH:   Float = 0.06f

    // Side turret base + rotating part (smaller scale — housing≈0.09,
    // barrel≈0.10, muzzle≈0.04 → total ≈ 0.23 ≈ old TURRET_HALF*2).
    const val SIDE_BASE_HEIGHT:        Float = 0.04f
    const val SIDE_BASE_HALF_W:        Float = 0.10f
    const val SIDE_HOUSING_HALF_W:     Float = 0.075f
    const val SIDE_HOUSING_LENGTH:     Float = 0.09f
    const val SIDE_BARREL_HALF_W:      Float = 0.025f
    const val SIDE_BARREL_LENGTH:      Float = 0.10f
    const val SIDE_MUZZLE_HALF_W:      Float = 0.040f
    const val SIDE_MUZZLE_LENGTH:      Float = 0.04f
    const val SIDE_TOTAL_LEN: Float =
        SIDE_HOUSING_LENGTH + SIDE_BARREL_LENGTH + SIDE_MUZZLE_LENGTH

    // Laser installation — small ground-telescope dome between the
    // central turret and the right side turret. Static (no rotation).
    const val LASER_INSTALL_X: Float = 0.9f
    // Rocket silo — open hatch with a dark launch tube, mirrors the
    // laser installation on the LEFT side of the central turret.
    // Rockets emerge from MUZZLE_OFFSET above the platform top (centre
    // of the silo opening).
    const val ROCKET_SILO_X:             Float = -0.9f
    const val ROCKET_SILO_MUZZLE_OFFSET: Float =  0.13f
    // Aim-alignment threshold for the central turret. The turret only
    // fires once it's rotated essentially onto the target angle (within
    // ~1.15°). The exponential rotation has a long asymptotic tail, so a
    // loose threshold (e.g. 5°) visibly fires off-aim on big swings —
    // especially with the railgun's 1-sec cooldown, where one off-target
    // shot is very noticeable.
    const val AIM_ALIGN_THRESHOLD_RAD: Float = 0.02f
    // Generous radius around a finger tap for asteroid hit-testing in
    // world units. Asteroids smaller than this still take the full radius
    // (FAST half ≈ 0.21 — too thin for fingertips otherwise).
    const val TAP_PICK_RADIUS:        Float = 0.6f
    // HP-bar over each damaged asteroid (Kotlin-side scene assembly, no
    // engine work). Bar width = asteroid.half * 2 * HP_BAR_HALF_W_MUL;
    // sits HP_BAR_PADDING above the asteroid silhouette.
    const val HP_BAR_HALF_W_MUL:      Float = 0.8f
    const val HP_BAR_HALF_THICK:      Float = 0.04f
    const val HP_BAR_PADDING:         Float = 0.18f
    // Energy (M8.3) — pool size and passive regen rate. Tuned so a single
    // 30-cost rocket strike (M8.5) refills in 3 sec; a 50-cost laser
    // strike refills in 5. Will become per-run effective values once
    // metal-funded base upgrades land.
    const val ENERGY_MAX:             Float = 100f
    const val ENERGY_REGEN_PER_SEC:   Float = 10f
    // Rocket strike (M8.5). Three homing missiles spawned from the
    // central turret muzzle, each tracking one of the top-N most
    // dangerous asteroids. ROCKET_TURN_RATE_RAD_PER_SEC = 4.0 means a
    // missile can flip a full 180° in ~0.78 sec — fast enough to
    // chase moving FAST asteroids, slow enough to look like guided
    // ordnance, not perfect tracking.
    const val ROCKET_COUNT:                Int   = 3
    const val ROCKET_DAMAGE_MUL:           Float = 4f
    const val ROCKET_AOE_RADIUS:           Float = 0.4f
    const val ROCKET_AOE_DAMAGE_MUL:       Float = 0.6f
    // Boost-phase tuning. Rocket emerges from the silo at LAUNCH_SPEED
    // (slow, looks like a missile clearing the tube), accelerates at
    // BOOST_ACCEL until reaching CRUISE_SPEED, then holds steady. The
    // accel curve gives a clear "ignite → burn → cruise" read instead
    // of "instantly at top speed" arcade feel.
    const val ROCKET_CRUISE_SPEED:         Float = 18f
    const val ROCKET_BOOST_ACCEL:          Float = 30f   // m/s² along facing
    const val ROCKET_HALF_W:               Float = 0.07f
    const val ROCKET_HALF_H:               Float = 0.13f
    const val ROCKET_TURN_RATE_RAD_PER_SEC: Float = 4f
    // Procedural rocket mesh — authored origin-at-centre with body axis
    // along +Z, total length = ROCKET_BODY_LENGTH (vertices span ±LENGTH/2).
    // Spring-launch sequence: rocket spawns with its base at the silo
    // opening (centre = silo Z + LENGTH/2), rises straight up at
    // ASCENT_SPEED, ignites engine after travelling ASCENT_HEIGHT.
    const val ROCKET_BODY_LENGTH:          Float = 0.30f
    const val ROCKET_ASCENT_HEIGHT:        Float = ROCKET_BODY_LENGTH * 2f
    // Slow rise on the spring push — visibly readable as "rocket
    // emerging from tube", not an instant pop. 0.60 / 1.6 ≈ 0.38 sec.
    const val ROCKET_ASCENT_SPEED:         Float = 1.6f
    // Engine ignition burst — one-shot bright plasma flash on the
    // ASCENDING→FLYING transition. Brighter than per-frame jet pulses,
    // quick fade so it doesn't linger.
    const val ROCKET_IGNITION_HALF:        Float = 0.18f
    const val ROCKET_IGNITION_LIFE:        Float = 0.20f
    // Engine jet — continuous reactive plume behind the rocket while
    // FLYING. Many small short-lived plasma flashes give a "flame
    // tongue" feel: short LIFE so they fade quickly, small INTERVAL so
    // they overlap and read as continuous, OFFSET behind rocket centre
    // along the reverse-velocity vector so the flame is at the nozzle.
    const val ROCKET_JET_INTERVAL:         Float = 0.02f
    const val ROCKET_JET_LIFE:             Float = 0.07f
    const val ROCKET_JET_HALF:             Float = 0.055f
    const val ROCKET_JET_TAIL_OFFSET_FRAC: Float = 0.45f  // × ROCKET_BODY_LENGTH
    // Smoke trail emitted from the rocket's tail during flight.
    // INTERVAL = sec between puffs; LIFE/SIZE = randomised per puff.
    // DRIFT = backward drift along the rocket's reverse-velocity vector
    // so the trail leaves a slight curl behind. DRAG damps motion.
    const val ROCKET_TRAIL_INTERVAL:       Float = 0.025f
    const val ROCKET_TRAIL_LIFE_MIN:       Float = 0.5f
    const val ROCKET_TRAIL_LIFE_MAX:       Float = 0.9f
    const val ROCKET_TRAIL_SIZE_MIN:       Float = 0.06f
    const val ROCKET_TRAIL_SIZE_MAX:       Float = 0.10f
    const val ROCKET_TRAIL_DRIFT:          Float = 0.5f
    const val ROCKET_TRAIL_DRAG:           Float = 1.2f
    // Continuous laser beam — fires from the dome of the laser
    // installation, locks onto the central-turret target, blocked by
    // the first intervening asteroid (no piercing). DPS × duration =
    // 250 total damage budget if it stays on one target the whole time.
    const val LASER_BEAM_DURATION_SEC: Float = 5.0f
    const val LASER_BEAM_DPS:          Float = 50.0f
    const val LASER_BEAM_PAD:          Float = 0.06f  // line-to-asteroid hit radius
    // Z offset from PLATFORM_TOP_Z to the dome's apex — beam emerges
    // here. = laser-installation base height + dome radius.
    const val LASER_DOME_TOP_OFFSET:   Float = 0.192f
    // E14 — beam perpendicular thickness in world units. Tuned for a
    // thin "laser" feel.
    const val LASER_BEAM_WIDTH:        Float = 0.10f
    // Beam tint — cool cyan-white. Alpha doubles as overall brightness
    // for the additive blend (>1 = HDR-ish bloom).
    val LASER_TINT = floatArrayOf(0.85f, 0.95f, 1.00f, 1.40f)
    // Yaw correction applied to Bullet.glb / Bullet_Heavy.glb when oriented
    // along the velocity vector. atan2(vx, vz) aligns the model's local +Z
    // with the flight direction; the bullet .glbs are authored with their
    // long axis along +X (bbox [0.02..0.72] in X, ±0.18 in Y/Z), so we
    // rotate by -PI/2 so that +X (rest pose nose direction) maps onto +Z
    // (velocity-aligned forward axis).
    const val BULLET_MODEL_YAW_OFFSET: Float = -1.5707963f
    // Uniform scale for the bullet model. The .glb is ~0.7 units long;
    // we want it roughly the size of the previous quad placeholder
    // (≈0.36 units long when scaled by halfH=0.18). 2× brings it visually
    // on par with the trail/muzzle flash so the projectile is readable
    // alongside its VFX instead of vanishing into the additive haze.
    const val BULLET_MODEL_SCALE_MUL: Float = 2.0f
    const val WAVE_BREAK_SEC:    Float = 2.0f
    const val FLASH_LIFE_SEC:    Float = 0.25f
    const val FLASH_HALF:        Float = 0.20f
    // E7.1 — 3D fireball explosion (AoE hits, EXPLOSIVE asteroid deaths).
    // Longer than the regular flash because it's a more substantial event
    // and the additive sphere needs time for the FBM turbulence to read
    // as fire instead of a static blob.
    const val FIREBALL_LIFE_SEC: Float = 0.50f
    // M7.1 VFX — turret muzzle, projectile hit flash, AoE ring.
    const val MUZZLE_FLASH_LIFE: Float = 0.08f
    const val MUZZLE_FLASH_HALF: Float = 0.39f  // 3× of pre-E11 0.13 for cone-shape blast
    // E10.4-trails — direct (non-AoE) hit flash. Sized as
    // `bullet.halfW * HIT_FLASH_SIZE_MUL`, so cannon bullets (halfW≈0.065)
    // get a 0.20-half flash and machine-gun bullets (halfW≈0.04) get a
    // 0.12-half flash without any per-weapon flag. AoE bullets skip
    // this and run spawnExplosion (fireball + spark burst) instead.
    const val HIT_FLASH_LIFE:    Float = 0.12f
    const val HIT_FLASH_SIZE_MUL:Float = 3.0f
    // E5.1 — per-event flash tints (RGBA), multiplied into the plasma
    // fragment heat-ramp. RGB channels recolour the warm-flame baseline;
    // alpha is an overall brightness scalar (>1 = boost). White (default)
    // keeps the E4 look. Tunable; non-const because Kotlin disallows
    // const FloatArray in companion objects.
    val FLASH_TINT_MUZZLE     = floatArrayOf(1.00f, 0.95f, 0.70f, 1.00f)  // warm white-yellow
    val FLASH_TINT_HIT        = floatArrayOf(1.00f, 0.75f, 0.30f, 1.00f)  // warm orange impact
    val FLASH_TINT_ENERGY     = floatArrayOf(0.45f, 0.85f, 1.00f, 1.10f)  // cyan electric, slightly brighter
    val FLASH_TINT_DEATH      = floatArrayOf(1.00f, 0.85f, 0.40f, 1.00f)  // warm yellow burst
    val FLASH_TINT_SHIELD     = floatArrayOf(0.35f, 0.75f, 1.00f, 1.00f)  // blue shield deflection
    // E12 — railgun muzzle stack. Cyan-white core flash (bright Gaussian
    // pop in the barrel mouth) + cluster of procedural electric arcs
    // perpendicular to the barrel direction (the "discharge between the
    // rails" visual cue). Per-bolt parameters live here so the visual
    // can be retuned without touching gameplay code.
    const val RAILGUN_CORE_LIFE:  Float = 0.10f   // core flash lifetime (sec)
    const val RAILGUN_CORE_HALF:  Float = 0.4125f // core flash peak half-size (E12 −25%)
    val FLASH_TINT_RAILGUN_CORE = floatArrayOf(0.85f, 0.95f, 1.00f, 1.80f)  // ice-white, very bright
    const val RAILGUN_BOLT_LIFE_MIN: Float = 0.08f
    const val RAILGUN_BOLT_LIFE_MAX: Float = 0.16f
    const val RAILGUN_BOLT_HALF_MIN: Float = 0.3375f  // E12 −25%
    const val RAILGUN_BOLT_HALF_MAX: Float = 0.6375f  // E12 −25%
    const val RAILGUN_BOLT_COUNT_MIN:Int   = 5
    const val RAILGUN_BOLT_COUNT_MAX:Int   = 7
    // Spread (radians) around perpendicular-to-barrel direction. ±50°
    // gives a visible fan that still reads as "discharges between rails"
    // rather than radiating in all directions. Bias slightly forward
    // is unnecessary — symmetry around the perpendicular is the desired
    // railgun aesthetic.
    const val RAILGUN_BOLT_SPREAD_RAD: Float = 0.87f  // ~50°
    // Offset along the barrel direction so individual bolts root at
    // different points along the muzzle's "rail length", not all at the
    // exact muzzle tip. Tiny offset proportional to bolt size.
    const val RAILGUN_BOLT_BARREL_OFFSET_FRAC: Float = 0.18f
    val FLASH_TINT_RAILGUN_BOLT = floatArrayOf(0.90f, 0.95f, 1.00f, 1.40f)  // cool blue-white, bright
    // Cyan railgun-spark tint for the E9 muzzle-spark burst — replaces
    // the warm muzzle tint when firing the railgun. Slightly brighter
    // than the regular muzzle sparks for the "energetic discharge" read.
    val SPARK_TINT_RAILGUN     = floatArrayOf(0.55f, 0.90f, 1.00f)
    // E7.1 polish — fireball colour curve. Lerp start → end over life
    // gives a "hot fresh blast → cooling embers" read instead of a
    // single static orange. Brightness is handled separately via the
    // pc.plasmaColor.a scalar in buildScene.
    val FIREBALL_TINT_START   = floatArrayOf(1.00f, 0.65f, 0.20f)  // saturated forge-orange
    val FIREBALL_TINT_END     = floatArrayOf(0.90f, 0.18f, 0.05f)  // deep dying-ember red

    // E9 — particle balance. AoE sparks fan out fast and dim quickly;
    // muzzle micro-sparks are short-lived punctuation; asteroid-death
    // debris falls under mild gravity and asteroid-death smoke lingers.
    // Tunable in one place so density/speed feel can be retuned without
    // hunting through spawn sites.
    const val SPARK_AOE_COUNT_MIN:    Int   = 50
    const val SPARK_AOE_COUNT_MAX:    Int   = 70
    const val SPARK_AOE_SPEED_MIN:    Float = 1.6f
    const val SPARK_AOE_SPEED_MAX:    Float = 3.4f
    const val SPARK_AOE_LIFE_MIN:     Float = 0.25f
    const val SPARK_AOE_LIFE_MAX:     Float = 0.55f
    const val SPARK_AOE_SIZE_MIN:     Float = 0.04f
    const val SPARK_AOE_SIZE_MAX:     Float = 0.09f
    const val SPARK_AOE_DRAG:         Float = 1.5f

    const val SPARK_MUZZLE_COUNT_MIN: Int   = 3
    const val SPARK_MUZZLE_COUNT_MAX: Int   = 5
    const val SPARK_MUZZLE_SPEED_MIN: Float = 0.8f
    const val SPARK_MUZZLE_SPEED_MAX: Float = 1.6f
    const val SPARK_MUZZLE_LIFE_MIN:  Float = 0.08f
    const val SPARK_MUZZLE_LIFE_MAX:  Float = 0.16f
    const val SPARK_MUZZLE_SIZE_MIN:  Float = 0.03f
    const val SPARK_MUZZLE_SIZE_MAX:  Float = 0.06f
    const val SPARK_MUZZLE_DRAG:      Float = 2.5f
    // Cone half-angle around the bullet velocity vector so the muzzle
    // sparks shoot mostly forward, not omnidirectional.
    const val SPARK_MUZZLE_CONE_RAD:  Float = 0.7f  // ~40°

    const val DEBRIS_COUNT_MIN:       Int   = 4
    const val DEBRIS_COUNT_MAX:       Int   = 8
    const val DEBRIS_SPEED_MIN:       Float = 0.4f
    const val DEBRIS_SPEED_MAX:       Float = 1.1f
    const val DEBRIS_LIFE_MIN:        Float = 0.50f
    const val DEBRIS_LIFE_MAX:        Float = 0.90f
    const val DEBRIS_SIZE_MIN:        Float = 0.07f
    const val DEBRIS_SIZE_MAX:        Float = 0.15f
    const val DEBRIS_GRAVITY:         Float = 1.2f   // -Z accel
    const val DEBRIS_DRAG:            Float = 0.6f

    const val SMOKE_DEATH_COUNT_MIN:  Int   = 3
    const val SMOKE_DEATH_COUNT_MAX:  Int   = 5
    const val SMOKE_DEATH_SPEED_MIN:  Float = 0.15f
    const val SMOKE_DEATH_SPEED_MAX:  Float = 0.45f
    const val SMOKE_DEATH_LIFE_MIN:   Float = 0.55f
    const val SMOKE_DEATH_LIFE_MAX:   Float = 0.95f
    const val SMOKE_DEATH_SIZE_MIN:   Float = 0.18f
    const val SMOKE_DEATH_SIZE_MAX:   Float = 0.32f
    const val SMOKE_DEATH_DRAG:       Float = 0.8f
    // Reload bar — strip on the lower part of the platform (the upper part
    // is overlapped by the ЩИТ button overlay, which composites on top of
    // the engine surface, so a bar placed there gets hidden). Anchored
    // horizontally under the central turret. Fill width = readiness.
    const val RELOAD_BAR_HALF_W:        Float = 0.40f
    const val RELOAD_BAR_Z:             Float = -0.30f
    const val RELOAD_BAR_HALF_THICK:    Float = 0.04f
    // Shield ability — base protection. Single charge with cooldown.
    // Shield (M9 redesign) — permanent HP-based barrier. Recharge is
    // hold-to-fill: every second the player holds the shield button,
    // SHIELD_RECHARGE_ENERGY_PER_SEC energy drains and SHIELD_RECHARGE_HP_PER_SEC
    // shield-HP is restored (clamped to SHIELD_MAX_HP). 4× ratio means
    // a full energy bar (100) buys 400 shield-HP — close to a full
    // refill from empty.
    const val SHIELD_MAX_HP:                Float = 500f
    const val SHIELD_RECHARGE_ENERGY_PER_SEC: Float = 50f
    const val SHIELD_RECHARGE_HP_PER_SEC:     Float = 200f
    // Damage soaked while the recharge button is held — incoming damage
    // is reduced to MUL × full damage (= 20% reduction at 0.80f).
    const val SHIELD_RECHARGE_DAMAGE_MUL:     Float = 0.80f
    // ---- Per-weapon firing arcs ---------------------------------------
    // Half-arc radius (radians from straight-up = 0). Computed as
    // (percentage × 180°) / 2 → percentage × π / 2. A target is
    // considered engageable by this weapon when |atan2(dx, dz)| ≤ HALF.
    // 90% = ±81° (1.4137 rad); 80% = ±72°; 70% = ±63°; 95% = ±85.5°.
    const val ARC_CENTRAL_CANNON_HALF_RAD: Float = 1.4137f  // 90% — Рельсотрон
    const val ARC_CENTRAL_MG_HALF_RAD:     Float = 1.2566f  // 80% — Автомат
    const val ARC_SIDE_CANNON_HALF_RAD:    Float = 1.2566f  // 80% — current side turret
    const val ARC_SIDE_MG_HALF_RAD:        Float = 1.0996f  // 70% — future side MG, unused
    const val ARC_LASER_HALF_RAD:          Float = 1.4923f  // 95% — laser dome
    const val ARC_ROCKET_HALF_RAD:         Float = 1.4923f  // 95% — rocket silo
    // Spark emitter parameters for the "shield is recharging" VFX.
    // RATE = sparks/sec; tangential SPEED so they skim along the arch
    // before drag stalls them; cyan tint matching the shield material.
    const val SHIELD_RECHARGE_SPARK_RATE:     Float = 90f
    const val SHIELD_RECHARGE_SPARK_LIFE_MIN: Float = 0.10f
    const val SHIELD_RECHARGE_SPARK_LIFE_MAX: Float = 0.22f
    const val SHIELD_RECHARGE_SPARK_SIZE_MIN: Float = 0.025f
    const val SHIELD_RECHARGE_SPARK_SIZE_MAX: Float = 0.045f
    const val SHIELD_RECHARGE_SPARK_SPEED_MIN: Float = 0.4f
    const val SHIELD_RECHARGE_SPARK_SPEED_MAX: Float = 1.0f
    const val SHIELD_RECHARGE_SPARK_DRAG:     Float = 4f
    val SHIELD_RECHARGE_SPARK_TINT = floatArrayOf(0.55f, 0.85f, 1.00f)
    // Arch geometry — wide flat ellipse over the full platform width.
    // halfW ≈ screen-half-width; halfH controls how high the arch
    // peaks above the platform top.
    const val SHIELD_ARCH_HALF_W:    Float = 2.40f
    const val SHIELD_ARCH_HALF_H:    Float = 1.00f
    const val SHIELD_ARCH_THICKNESS: Float = 0.06f
    const val SHIELD_ARCH_PEAK_ALPHA: Float = 0.85f
    // Superellipse exponent for the arch profile: |x/a|^n + |z/b|^n = 1.
    // n=2 is the legacy half-ellipse; n>2 flattens the top and sharpens
    // the shoulders so the sides drop more vertically.
    const val SHIELD_ARCH_SHARPNESS: Float = 4.0f
    // Vertical lift of the whole arch as a fraction of halfH — the ends
    // detach from the platform and the band reads as a hovering barrier.
    const val SHIELD_ARCH_LIFT_FRAC: Float = 0.05f
    // M5 — special asteroid death effects.
    const val EXPLOSIVE_AOE_RADIUS:  Float = 0.5f   // same as heavy cannon AoE
    const val EXPLOSIVE_AOE_DAMAGE:  Int   = 30     // splash damage to neighbours
    const val ENERGY_BUFF_DURATION:  Float = 5.0f   // seconds
    const val ENERGY_BUFF_DAMAGE_MUL:Float = 2.0f   // central turret ×2 damage
}
