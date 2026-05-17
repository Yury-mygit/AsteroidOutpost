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
    // 3D-pivot Phase 1 — Y is depth into the screen. Asteroids spawn at
    // ASTEROID_SPAWN_Y_DEPTH (ahead of the ship in depth) and close to
    // yPos = 0 (ship plane) in sync with their Z-fall, so they visually
    // grow as they approach the camera. depthSpeed = speed × Y/Z fall
    // ratio so Y reaches 0 at the same moment Z reaches PLATFORM_TOP_Z,
    // and existing X/Z collision against shield/platform fires
    // correctly.
    //
    // Z spawn is intentionally LOWER than SCREEN_TOP_Z — under the
    // tilted 3D-pivot camera (fovY=28°), spawning at Z=SCREEN_TOP_Z
    // with non-zero Y puts the asteroid above the vertical FOV cone
    // and it's invisible. ASTEROID_SPAWN_Z and ASTEROID_SPAWN_Y_DEPTH
    // are tuned together so the spawn point sits comfortably inside the
    // frustum at all expected Y depths.
    // Radical 3D — asteroids approach the ship head-on with both depth-Y
    // descent (10 units) and forward-Z motion (12.94 units), so the
    // trajectory direction roughly aligns with the inverse camera-look
    // axis. Result on screen: asteroid grows 2.9× from spawn to impact
    // (vs 1.9× for pure horizontal) and traverses only half the screen
    // vertically (vs the full screen) — reads as "flying head-on, growing"
    // instead of "falling from above". Y/Z = 0.77 sits between the steep
    // 1.46 head-on optimum and the flat 0 horizontal extreme.
    const val ASTEROID_SPAWN_Y_DEPTH: Float = 10f
    const val ASTEROID_SPAWN_Z:       Float = 15f
    // Second spawn echelon — higher AND deeper than the first, picked 50/50
    // per spawn. Same destination (xPos, 0, PLATFORM_TOP_Z), so depthSpeed
    // is recomputed per-asteroid from its actual yFall/zFall. Reads as a
    // second tier of incoming rocks behind and above the first.
    const val ASTEROID_SPAWN_Y_DEPTH_2: Float = 16f
    const val ASTEROID_SPAWN_Z_2:       Float = 26f
    /** Farthest Y a projectile can travel before despawn. Must clear the
     *  most-distant spawn zone — bumped to ASTEROID_SPAWN_Y_DEPTH_2 + 2. */
    const val ASTEROID_MAX_SPAWN_Y_DEPTH: Float = ASTEROID_SPAWN_Y_DEPTH_2

    // Route mode (a.k.a. tunnel mode) — the ship glides forward along the
    // camera-forward axis so asteroids appear to come from the depth (centre
    // of screen, growing toward camera) instead of "from below" like the
    // pure-Y motion would give. Forward direction is fixed by the style3
    // camera tilt: pitch = π/2 + CAMERA_TILT_RAD. With CAMERA_TILT_RAD = 0
    // the camera looks straight along world +Y (no extra rotation), the
    // ship sits on the Y axis at origin and the route is just a list of
    // Y-positions with optional X/Z offsets — no axis-mixing needed.
    //
    // ⚠ When CAMERA_TILT_RAD in MainActivity changes, update these two
    // constants too — they're hand-baked, not derived at runtime.
    const val SHIP_CRUISE_SPEED:        Float = 3.0f
    const val ROUTE_FORWARD_Y:          Float = 1.0f          // cos(0)
    const val ROUTE_FORWARD_Z:          Float = 0.0f          // sin(0)
    /** How far ahead of the ship (in route-distance units) a placement is
     *  materialised. Generous (40) so the player sees obstacles ~13 sec
     *  in advance at SHIP_CRUISE_SPEED = 3, has time to plan priorities,
     *  and turrets aren't time-pressured into rapid-fire panic. */
    const val ROUTE_SPAWN_DEPTH:        Float = 40f
    /** Asteroid is despawned when its yPos drops below this. Set so the
     *  asteroid stays visible flying past the ship (camera is at world
     *  Y ≈ -11 with zNear = 0.5, so anything at yPos < -10.5 is behind
     *  the near plane). -7 keeps the asteroid on screen for ~2.3 sec
     *  past the ship at SHIP_CRUISE_SPEED = 3 — a clear "zoomed past you"
     *  beat instead of vanishing in the middle of the screen. */
    const val ROUTE_PASS_BY_THRESHOLD:  Float = -7f
    /** Max engagement range (world units, measured as forward distance
     *  from ship). Auto-fire turrets / rockets / laser ignore asteroids
     *  beyond this even if they're on a collision course — keeps the
     *  ship from blasting things half a corridor away. */
    const val WEAPON_ENGAGEMENT_RANGE:  Float = 30f
    const val ASTEROID_HALF:     Float = 0.1235f
    const val DAMAGE_PER_HIT:    Int   = 10
    const val ASTEROID_SPEED:    Float = 1.0f   // units/sec downward
    const val ASTEROID_HP_INIT:  Int   = 100
    const val SPAWN_INTERVAL_SEC:Float = 3.0f
    const val SCREEN_BOTTOM_Z:   Float = -1.49f
    const val SCREEN_HALF_W:     Float = 2.47f
    const val PLATFORM_TOP_Z:    Float = -0.94f // matches platform z + halfH
    /** Bottom of the ship hull mesh in world Z. Asteroids whose Z is
     *  ENTIRELY below this just fly under the ship — they shouldn't
     *  count as a hull hit (they're physically passing beneath). */
    const val HULL_BOTTOM_Z:     Float = -1.49f
    const val PLATFORM_HP_INIT:  Int   = 100
    const val PLATFORM_DMG_PER_HIT: Int = 20
    const val TURRET_HALF:       Float = 0.10f  // legacy — bbox of old square; kept for back-compat with existing collision math
    const val TURRET_DMG:        Int   = 5      // half of DAMAGE_PER_HIT (legacy)
    // Central turret — main weapon. Now split into a static base sitting on
    // the deck and a rotating housing+barrel that pivots at base-top.
    // The bow gun sits on the centerline near the prow tip; side turrets
    // flank it aft. CENTRAL_BASE_Z is where the static base mesh is
    // mounted on the deck; CENTRAL_TURRET_BASE_Z is the pivot Z (top of
    // the base, where the barrel rotates around). Rotating mesh extends
    // forward (+Z) from the pivot.
    const val CENTRAL_TURRET_X:        Float = 0f
    const val CENTRAL_BASE_Z:          Float = -1.04f
    // Static base (collar between deck and turret pivot). Sized up vs
    // legacy quad-platform values so the turret reads clearly against
    // the dark hull plating.
    const val CENTRAL_BASE_HEIGHT:     Float = 0.04f
    const val CENTRAL_BASE_HALF_W:     Float = 0.20f
    const val CENTRAL_TURRET_BASE_Z:   Float = CENTRAL_BASE_Z + CENTRAL_BASE_HEIGHT
    /** Y-offset (forward of ship centre) where the CENTRAL turret pivot
     *  sits. Negative = behind ship centre. Concept «Вид 3» layout puts
     *  the central forward of the side pair so they stagger visually. */
    const val CENTRAL_TURRET_Y_OFFSET: Float = -0.35f
    /** Y-offset for the SIDE turret pivots. Further back than central
     *  so the trio reads as a chevron formation, not a single line. */
    const val SIDE_TURRET_Y_OFFSET:    Float = -0.9f
    const val CENTRAL_TURRET_HALF_W:   Float = 0.15f  // legacy — = housing half-W
    const val CENTRAL_TURRET_HALF_H:   Float = 0.30f  // half of total rotating-part length
    // Rotating housing + barrel + muzzle ring (origin at pivot, +Z forward).
    const val CENTRAL_HOUSING_HALF_W:  Float = 0.15f
    const val CENTRAL_HOUSING_LENGTH:  Float = 0.20f
    const val CENTRAL_BARREL_HALF_W:   Float = 0.045f
    const val CENTRAL_BARREL_LENGTH:   Float = 0.34f
    const val CENTRAL_MUZZLE_HALF_W:   Float = 0.065f
    const val CENTRAL_MUZZLE_LENGTH:   Float = 0.06f

    // Side turret base + rotating part (smaller scale — housing≈0.09,
    // barrel≈0.10, muzzle≈0.04 → total ≈ 0.23 ≈ old TURRET_HALF*2). Both
    // side turrets share the same Z (amidships); they only differ in X
    // (port vs starboard, see `MissionRunner.turretXs`).
    const val SIDE_BASE_Z:             Float = -1.24f
    const val SIDE_BASE_HEIGHT:        Float = 0.04f
    // Side turret pivot — top of the static base. Rotating mesh extends
    // forward (+Z) from here; barrel tip is at TURRET_TOP_Z + SIDE_TOTAL_LEN.
    const val TURRET_TOP_Z:            Float = SIDE_BASE_Z + SIDE_BASE_HEIGHT
    const val SIDE_BASE_HALF_W:        Float = 0.30f
    const val SIDE_HOUSING_HALF_W:     Float = 0.220f
    const val SIDE_HOUSING_LENGTH:     Float = 0.20f
    const val SIDE_BARREL_HALF_W:      Float = 0.070f
    const val SIDE_BARREL_LENGTH:      Float = 0.20f
    const val SIDE_MUZZLE_HALF_W:      Float = 0.110f
    const val SIDE_MUZZLE_LENGTH:      Float = 0.08f
    const val SIDE_TOTAL_LEN: Float =
        SIDE_HOUSING_LENGTH + SIDE_BARREL_LENGTH + SIDE_MUZZLE_LENGTH

    // .glb-loaded side cannon metrics (Turret_Side_Cannon.glb). Pivot
    // origin lives at amburazura height inside the body (model file Y=0.13
    // above the body's bottom face); barrel extends from pivot along model
    // -Z for 0.45 m to the muzzle tip. After Rx(+π/2) in SceneAssembler:
    // model -Z → world +Y, so the rotated barrel's tip is `SIDE_CANNON_GLTF_LENGTH`
    // away from pivot along (-sin(yaw), cos(yaw)) in the XY plane, with Z
    // constant at SIDE_CANNON_GLTF_PIVOT_Z. SceneAssembler reads
    // SIDE_TURRET_AMBRAZURA_Z directly when placing the cannon SceneObject;
    // the values here must match the artist-authored .glb topology.
    const val SIDE_TURRET_AMBRAZURA_Z: Float = 0.13f
    const val SIDE_CANNON_GLTF_PIVOT_Z: Float = PLATFORM_TOP_Z + SIDE_TURRET_AMBRAZURA_Z
    const val SIDE_CANNON_GLTF_LENGTH: Float = 0.45f

    // ------------------------------------------------------------------
    // Enemy ship (combat mission prototype). One adversary that holds
    // station ahead of the player and lobs bolts. Spawned by
    // MissionConfig.enemyShipDelaySec; ticked as a special AsteroidType
    // (ENEMY_SHIP) so it benefits from existing auto-aim / tap-pick /
    // damage flow without a parallel entity system.
    // ------------------------------------------------------------------
    /** World-Y offset the enemy ship maintains ahead of the player. */
    const val ENEMY_SHIP_LEAD_DISTANCE: Float = 20f
    /** World-Z the enemy ship floats at (above platform line). */
    const val ENEMY_SHIP_Z: Float = 3.5f
    /** Seconds between bolt shots fired at the player. */
    const val ENEMY_SHIP_FIRE_INTERVAL_SEC: Float = 1.5f
    /** Damage per bolt impact (shield or hull, before shield-recharge mod). */
    const val ENEMY_BOLT_DAMAGE: Int = 30
    /** Bolt travel speed (world units / sec). Slow enough to read as a
     *  guided shot and give the player reaction time. */
    const val ENEMY_BOLT_SPEED: Float = 12f
    /** Render scale for the enemy ship asteroid relative to its `half` —
     *  meshes loaded for asteroids are unit-bbox, half × this gives the
     *  on-screen size. Bumped above 1.0 so the enemy reads as a ship,
     *  not a chunky rock. */
    const val ENEMY_SHIP_MESH_SCALE_MUL: Float = 1.2f

    // Laser installation — small dome on the deck, just starboard of
    // the centerline between the two side turrets. Static (no
    // rotation), aimed by the beam's source/aim closures.
    const val LASER_INSTALL_X: Float = 0.25f
    const val LASER_INSTALL_Z: Float = -1.20f
    // Rocket silo — open hatch with a dark launch tube, mirrors the
    // laser dome on the port side between the side turrets. Rockets
    // emerge from MUZZLE_OFFSET above the silo opening.
    const val ROCKET_SILO_X:             Float = -0.25f
    const val ROCKET_SILO_Z:             Float = -1.20f
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

    // Рельсотрон tracer beam — drawn through the beam pipeline from the
    // muzzle (Projectile.originXYZ) to the current projectile position.
    // Width is the world-space half-thickness of the beam quad; tint is
    // forwarded to beam.frag as pc.color. Premultiplied alpha is handled
    // by the shader (additive ONE/ONE).
    const val RAILGUN_TRAIL_HALF_W: Float = 0.06f
    val RAILGUN_TRAIL_TINT       = floatArrayOf(0.30f, 0.70f, 1.00f, 0.90f)
    /** Distance forward of the muzzle along the firing direction where
     *  the tracer beam STARTS. Keeps the trail visibly clear of the
     *  central cannon / ship hull instead of pasting it onto the barrel. */
    const val RAILGUN_TRAIL_FORWARD_GAP: Float = 0.9f
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
    // All arcs set to π (effectively no arc limit) — every weapon
    // reaches every target the auto-aimer picks, including extreme-X /
    // low-Z asteroids that previously fell outside the firing cone.
    const val ARC_CENTRAL_CANNON_HALF_RAD: Float = 3.1416f
    const val ARC_CENTRAL_MG_HALF_RAD:     Float = 3.1416f
    const val ARC_SIDE_CANNON_HALF_RAD:    Float = 3.1416f
    const val ARC_SIDE_MG_HALF_RAD:        Float = 3.1416f
    const val ARC_LASER_HALF_RAD:          Float = 3.1416f
    const val ARC_ROCKET_HALF_RAD:         Float = 3.1416f
    // Spark emitter parameters for the "shield is recharging" VFX.
    // RATE = sparks/sec; tangential SPEED so they skim along the
    // hemisphere surface before drag stalls them; cyan tint matching
    // the shield material.
    const val SHIELD_RECHARGE_SPARK_RATE:     Float = 90f
    const val SHIELD_RECHARGE_SPARK_LIFE_MIN: Float = 0.10f
    const val SHIELD_RECHARGE_SPARK_LIFE_MAX: Float = 0.22f
    const val SHIELD_RECHARGE_SPARK_SIZE_MIN: Float = 0.025f
    const val SHIELD_RECHARGE_SPARK_SIZE_MAX: Float = 0.045f
    const val SHIELD_RECHARGE_SPARK_SPEED_MIN: Float = 0.4f
    const val SHIELD_RECHARGE_SPARK_SPEED_MAX: Float = 1.0f
    const val SHIELD_RECHARGE_SPARK_DRAG:     Float = 4f
    val SHIELD_RECHARGE_SPARK_TINT = floatArrayOf(0.55f, 0.85f, 1.00f)
    // E20 — force-field hemisphere replaces the legacy superellipse arch.
    //   * Radius is "comfortably bigger than the ship", so the contact
    //     point sits visibly off the hull — not touching the deck.
    //   * Impact life is short (≈ 1/3 sec) so the impact bloom feels like
    //     a quick zap rather than lingering smoke.
    //   * Up to 4 simultaneous impacts; older ones get pushed out.
    const val SHIELD_HEMISPHERE_RADIUS:  Float = 2.3f    // E-variant — bigger dome, properly enclosing the ship's nose
    const val SHIELD_HEMISPHERE_STACKS:  Int   = 8       // vertex rings pole → equator
    const val SHIELD_HEMISPHERE_SLICES:  Int   = 24      // segments around polar axis
    /** Shield centre offset in WORLD Z (screen-vertical up). Shifts the
     *  hemisphere up on screen so it visually centres on/above the ship
     *  instead of hanging in the lower half of the viewport. Collision
     *  uses the same offset — the field really is up there. */
    const val SHIELD_CENTER_Z:           Float = 0.4f
    const val SHIELD_IMPACT_LIFE_SEC:    Float = 0.6f
    const val SHIELD_MAX_ACTIVE_IMPACTS: Int   = 4
    // Vertical lift of the whole arch as a fraction of halfH — the ends
    // detach from the platform and the band reads as a hovering barrier.
    const val SHIELD_ARCH_LIFT_FRAC: Float = 0.05f
    // E19 — drones ability. Spawn N interceptor drones around the ship that
    // autonomously target nearest asteroid, fly toward it, fire a continuous
    // laser beam when within attack range, switch targets on kill. Drones
    // and their lasers expire after DRONE_LIFETIME_SEC.
    const val DRONE_COUNT:            Int   = 4
    const val DRONE_LIFETIME_SEC:     Float = 10f
    // Interceptor-feel tuning — light spacecraft with thrust-based
    // physics. Drone applies a constant THRUST acceleration toward its
    // target each tick; its velocity integrates over time. Cap at
    // DRONE_SPEED. Inertia gives natural "accelerate from rest → cruise
    // → overshoot past target → reverse thrust slows it → loops back"
    // behaviour without explicit braking logic.
    //
    // Tuning math: DRONE_SPEED / DRONE_THRUST = time-to-cruise from
    // standstill. 10 / 12 ≈ 0.83 s ramp-up. A 180° flip (full speed
    // forward → full speed back) takes 2× that ≈ 1.7 s of opposing
    // thrust — visible momentum without feeling sluggish.
    const val DRONE_SPEED:            Float = 10.0f  // world units / sec (hard cap)
    const val DRONE_THRUST:           Float = 12.0f  // m/s² toward target
    const val DRONE_ATTACK_RANGE:     Float = 4.0f   // beam fires when target within this
    /** Distance from target the drone tries to maintain — orbit radius
     *  around the asteroid so it doesn't penetrate the model. Plus a
     *  per-drone angular offset around the target keeps drones from
     *  stacking on the same point. */
    const val DRONE_KEEP_DISTANCE:    Float = 0.9f
    /** Yaw offset applied to the drone mesh — `ship.gltf` is authored with
     *  forward axis along +X (legacy convention used in the g3 top-down
     *  scene); style3 rotationY assumes +Z forward, so we subtract π/2 to
     *  align nose with velocity. */
    const val DRONE_MESH_YAW_OFFSET:  Float = -1.5707963f   // -π/2
    /** Pitch (rotationX) applied to the drone mesh. The g3 ship.gltf has
     *  ±Y wings and ±Z thickness — under g3's top-down camera that's
     *  correct, but in style3 (camera roughly behind-and-above the ship,
     *  world Y = depth, world Z = vertical-screen) the wings would point
     *  into the screen and the fighter reads as a thin sideways stick.
     *  Rolling +π/2 around the model nose (its own +X axis, which is what
     *  Rx is BEFORE the yaw is applied in our T·Rz·Ry·Rx chain) brings
     *  the wings out of the depth axis into the horizontal XZ plane and
     *  the top of the fuselage toward the camera. */
    const val DRONE_MESH_PITCH_OFFSET: Float = 1.5707963f   // +π/2
    const val DRONE_LASER_DPS:        Float = 28f    // 4 drones × 28 DPS = 112 → kills NORMAL in ~1s
    const val DRONE_LASER_WIDTH:      Float = 0.012f
    val       DRONE_LASER_COLOR              = floatArrayOf(0.65f, 1.00f, 0.55f, 0.95f)  // green
    const val DRONE_SPAWN_SPREAD:     Float = 0.4f   // tight xz cluster — drones launch from a bay under the ship
    const val DRONE_SPAWN_Y:          Float = 0.6f   // well under hull (hull bottom at +0.10) — visible "from below" launch
    const val DRONE_MESH_SCALE:       Float = 0.18f  // size on screen

    // M5 — special asteroid death effects.
    const val EXPLOSIVE_AOE_RADIUS:  Float = 0.5f   // same as heavy cannon AoE
    const val EXPLOSIVE_AOE_DAMAGE:  Int   = 30     // splash damage to neighbours
    const val ENERGY_BUFF_DURATION:  Float = 5.0f   // seconds
    const val ENERGY_BUFF_DAMAGE_MUL:Float = 2.0f   // central turret ×2 damage
}
