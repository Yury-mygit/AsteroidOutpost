package com.example.asteroidoutpost.game.combat

import kotlin.math.pow

/**
 * Owns the VFX side of combat events — turret muzzle blast, projectile hit
 * flash, asteroid death debris+smoke, AoE fireball, rocket ignition/jet/trail,
 * shield-recharge sparks. Every method appends to the shared mutable lists
 * passed in at construction; nothing here reads or mutates game state
 * (asteroid HP, projectile flight, etc.).
 *
 * Constructed once after engine setup (mesh handles must already be loaded —
 * `muzzleConeMeshHandle`, `quadFlashHandle`). Lives for the Activity lifetime;
 * the lists it holds are the same instances ticked by `tickParticles` and
 * read by `buildScene` each frame.
 *
 * `shieldRechargeSparkAccum` is the only piece of mutable state owned here:
 * a fractional accumulator that keeps integer-spawn counts framerate-
 * independent for `emitShieldRechargeSparks`.
 */
internal class VfxSpawner(
    private val flashes: MutableList<Flash>,
    private val fireballs: MutableList<Fireball>,
    private val sparkParticles: MutableList<Particle>,
    private val smokeParticles: MutableList<Particle>,
    private val debrisParticles: MutableList<Particle>,
    private val muzzleConeMeshHandle: Long,
    private val quadFlashHandle: Long,
) {
    private var shieldRechargeSparkAccum: Float = 0f

    /**
     * E9 — AoE spark fan. Spawns SPARK_AOE_COUNT_* particles around
     * (cx, cz) on the gameplay plane (y=0), randomised over the X-Z plane
     * with no preferred direction. Tinted by `tintRgb` so callers can
     * recolour per-event (default forge-orange aligns with fireball).
     */
    fun spawnSparkBurst(cx: Float, cy: Float, cz: Float, tintRgb: FloatArray = DraftCombat.FIREBALL_TINT_START) {
        val n = (DraftCombat.SPARK_AOE_COUNT_MIN..DraftCombat.SPARK_AOE_COUNT_MAX).random()
        for (i in 0 until n) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.SPARK_AOE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_AOE_SPEED_MAX - DraftCombat.SPARK_AOE_SPEED_MIN)
            val life = DraftCombat.SPARK_AOE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_AOE_LIFE_MAX - DraftCombat.SPARK_AOE_LIFE_MIN)
            val size = DraftCombat.SPARK_AOE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_AOE_SIZE_MAX - DraftCombat.SPARK_AOE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = cy, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tintRgb[0], g = tintRgb[1], b = tintRgb[2], a = 1.4f,
                drag = DraftCombat.SPARK_AOE_DRAG,
            ))
        }
    }

    /**
     * E10.4-trails / E11 — directional muzzle blast as 3 plasma cones spaced
     * 120° apart around the muzzle. Each cone uses the procedural muzzle-cone
     * mesh (30° wedge fan) routed through the plasma pipeline with per-flash
     * rotation, so the wedges point along their respective world directions
     * with the full plasma look (FBM turbulence + heat ramp + soft fade).
     *
     * `dirX, dirZ` is the bullet's unit velocity; `bulletHalfW` scales the
     * blast so a chunkier projectile pops bigger than a slim one. Each cone
     * starts at the muzzle (origin = tip) and flares outward by `mainSize`
     * along its rotation, producing a tri-fork shape rooted at the barrel
     * exit. The plasma soft-fade in the fragment shader handles the alpha
     * gradient from bright tip to transparent rim.
     */
    fun spawnMuzzleBlast(
        cx: Float, cy: Float, cz: Float,
        dirX: Float, dirZ: Float,
        bulletHalfW: Float,
        tint: FloatArray,
    ) {
        // Reference: BULLET_HALF_W = 0.04 maps to sizeMul ~1.0; clamps stop
        // very-small / very-large bullets from collapsing or overflowing.
        val sizeMul  = (bulletHalfW / DraftCombat.BULLET_HALF_W).coerceIn(0.5f, 2.0f)
        val mainSize = DraftCombat.MUZZLE_FLASH_HALF * sizeMul
        val life     = DraftCombat.MUZZLE_FLASH_LIFE

        // Forward angle in the engine's rotation convention: rotation=0 leaves
        // the cone's local +Z pointing screen-up (= world +Z under the fixed
        // pitch=π/2 camera). To rotate forward to (dirX, dirZ), the angle is
        // atan2(dirX, dirZ): atan2(0, 1) = 0 (no rotation, points up),
        // atan2(1, 0) = π/2 (points right), etc.
        val forwardAngle = kotlin.math.atan2(dirX, dirZ)
        val twoThirdsPi  = (2.0 * Math.PI / 3.0).toFloat()

        fun add(rot: Float) {
            flashes.add(Flash(
                x = cx, y = cy, z = cz,
                life = life, maxLife = life,
                halfMax = mainSize,
                tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3],
                meshHandle = muzzleConeMeshHandle,
                rotation = rot,
            ))
        }

        // Three cones at 0°, +120°, -120° from forward. All rooted at the
        // muzzle position (cone tip = local origin); the rotation parameter
        // points each wedge's apex outward along its share of the trefoil.
        add(forwardAngle)
        add(forwardAngle + twoThirdsPi)
        add(forwardAngle - twoThirdsPi)
    }

    /**
     * E12 — railgun muzzle effect. Replaces spawnMuzzleBlast for the central
     * HEAVY_CANNON (concept-renamed to "Рельсотрон"). Visualises an
     * electromagnetic launcher firing: a single bright cyan-white core flash
     * at the barrel mouth + 5-7 procedural electric arc bolts radiating
     * around the perpendicular-to-barrel direction (= "discharges between
     * the rails"). Each bolt routes through the new lightning sub-shader
     * (E12) — the per-bolt `lightningSeed` decorrelates the FBM noise field
     * so simultaneous bolts look distinct, not stamped from the same mould.
     *
     * Side turrets and the Автомат keep using `spawnMuzzleBlast` (warm cone
     * trefoil). The asymmetry is deliberate: the railgun's discharge profile
     * is a unique read for the player's primary weapon.
     */
    fun spawnRailgunMuzzle(
        cx: Float, cy: Float, cz: Float,
        dirX: Float, dirZ: Float,
        bulletHalfW: Float,
    ) {
        val sizeMul = (bulletHalfW / DraftCombat.BULLET_HALF_W).coerceIn(0.5f, 2.0f)

        // Bright ice-white core flash at the muzzle. Standard plasma path
        // (no lightningSeed → legacy heat-ramp flash) but with cyan-white
        // tint and high brightness scalar — reads as the barrel-mouth pop.
        val coreT = DraftCombat.FLASH_TINT_RAILGUN_CORE
        flashes.add(Flash(
            x = cx, y = cy, z = cz,
            life = DraftCombat.RAILGUN_CORE_LIFE,
            maxLife = DraftCombat.RAILGUN_CORE_LIFE,
            halfMax = DraftCombat.RAILGUN_CORE_HALF * sizeMul,
            tintR = coreT[0], tintG = coreT[1], tintB = coreT[2], tintA = coreT[3],
            // round flash → falls back to quadFlashHandle in buildScene
        ))

        // Forward / perpendicular angles. Engine convention: rotation=0 means
        // local +Z = screen-up; barrel direction (dirX, dirZ) maps to angle
        // atan2(dirX, dirZ). Perpendicular to that is +π/2.
        val forwardAngle = kotlin.math.atan2(dirX, dirZ)
        val perpAngle    = forwardAngle + (Math.PI.toFloat() * 0.5f)

        val nBolts = (DraftCombat.RAILGUN_BOLT_COUNT_MIN..DraftCombat.RAILGUN_BOLT_COUNT_MAX).random()
        val boltT  = DraftCombat.FLASH_TINT_RAILGUN_BOLT
        val offsetMul = bulletHalfW.coerceAtLeast(0.04f)
        for (i in 0 until nBolts) {
            // Rotation: perpendicular-to-barrel ± uniform spread. Symmetric
            // around perpAngle so bolts radiate to both sides of the barrel.
            val spread = (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.RAILGUN_BOLT_SPREAD_RAD
            val rot = perpAngle + spread
            // Slight offset along the barrel direction (root each bolt at a
            // different point along the rail length) so the cluster doesn't
            // all originate from a single pixel.
            val barrelOffset = (Math.random().toFloat() - 0.5f) *
                               DraftCombat.RAILGUN_BOLT_BARREL_OFFSET_FRAC *
                               offsetMul * 4f
            val bx = cx + dirX * barrelOffset
            val bz = cz + dirZ * barrelOffset
            // Per-bolt size + life jitter for visual variety. Length scaled
            // by sizeMul so an upgraded chunkier bullet pops bigger arcs.
            val halfMax = DraftCombat.RAILGUN_BOLT_HALF_MIN +
                          Math.random().toFloat() *
                          (DraftCombat.RAILGUN_BOLT_HALF_MAX - DraftCombat.RAILGUN_BOLT_HALF_MIN)
            val life = DraftCombat.RAILGUN_BOLT_LIFE_MIN +
                       Math.random().toFloat() *
                       (DraftCombat.RAILGUN_BOLT_LIFE_MAX - DraftCombat.RAILGUN_BOLT_LIFE_MIN)
            // Per-bolt seed >= 0.5 so the shader's tint.y >= 0.5 gate trips
            // and tint.z reads as a meaningful seed (not collapsed near 0).
            val seed = 1f + Math.random().toFloat() * 999f
            flashes.add(Flash(
                x = bx, y = cy, z = bz,
                life = life, maxLife = life,
                halfMax = halfMax * sizeMul,
                tintR = boltT[0], tintG = boltT[1], tintB = boltT[2], tintA = boltT[3],
                // Reuse quadFlashHandle (unit X-Z square) — the bolt visual
                // lives entirely in the fragment shader, the mesh is just
                // the canvas. No new mesh asset needed.
                meshHandle = quadFlashHandle,
                rotation = rot,
                lightningSeed = seed,
            ))
        }
    }

    /**
     * E12 — railgun-flavoured muzzle sparks. Same E9 path as spawnMuzzleSparks
     * but cyan-tinted (electromagnetic discharge feel) instead of warm
     * yellow. Spawned alongside spawnRailgunMuzzle for the central railgun.
     */
    fun spawnRailgunSparks(cx: Float, cy: Float, cz: Float, vx: Float, vz: Float) {
        val baseTheta = kotlin.math.atan2(vz, vx)
        val n = (DraftCombat.SPARK_MUZZLE_COUNT_MIN..DraftCombat.SPARK_MUZZLE_COUNT_MAX).random()
        val tint = DraftCombat.SPARK_TINT_RAILGUN
        for (i in 0 until n) {
            val theta = baseTheta + (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.SPARK_MUZZLE_CONE_RAD
            val sp = DraftCombat.SPARK_MUZZLE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SPEED_MAX - DraftCombat.SPARK_MUZZLE_SPEED_MIN)
            val life = DraftCombat.SPARK_MUZZLE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_LIFE_MAX - DraftCombat.SPARK_MUZZLE_LIFE_MIN)
            val size = DraftCombat.SPARK_MUZZLE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SIZE_MAX - DraftCombat.SPARK_MUZZLE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = cy, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tint[0], g = tint[1], b = tint[2], a = 1.8f,
                drag = DraftCombat.SPARK_MUZZLE_DRAG,
            ))
        }
    }

    /**
     * Muzzle micro-sparks fired in a tight cone around the bullet velocity
     * vector. `(vx, vz)` is the bullet's screen-space velocity (already
     * normalised inside this helper). 3-5 short sparks per shot read as
     * gunpowder kick.
     */
    fun spawnMuzzleSparks(cx: Float, cy: Float, cz: Float, vx: Float, vz: Float) {
        val baseTheta = kotlin.math.atan2(vz, vx)
        val n = (DraftCombat.SPARK_MUZZLE_COUNT_MIN..DraftCombat.SPARK_MUZZLE_COUNT_MAX).random()
        val tint = DraftCombat.FLASH_TINT_MUZZLE
        for (i in 0 until n) {
            val theta = baseTheta + (Math.random().toFloat() - 0.5f) * 2f * DraftCombat.SPARK_MUZZLE_CONE_RAD
            val sp = DraftCombat.SPARK_MUZZLE_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SPEED_MAX - DraftCombat.SPARK_MUZZLE_SPEED_MIN)
            val life = DraftCombat.SPARK_MUZZLE_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_LIFE_MAX - DraftCombat.SPARK_MUZZLE_LIFE_MIN)
            val size = DraftCombat.SPARK_MUZZLE_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SPARK_MUZZLE_SIZE_MAX - DraftCombat.SPARK_MUZZLE_SIZE_MIN)
            sparkParticles.add(Particle(
                x = cx, y = cy, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = tint[0], g = tint[1], b = tint[2], a = 1.6f,
                drag = DraftCombat.SPARK_MUZZLE_DRAG,
            ))
        }
    }

    /**
     * E9 — Asteroid death VFX. Spawns:
     *   • 4-8 textured chunks (alpha pipeline, debris texture) flying
     *     outward with a touch of -Z gravity → settle visually.
     *   • 3-5 textured smoke puffs (alpha pipeline, smoke texture) drifting
     *     slowly outward → linger after debris is gone.
     * `colorTint` lets callers recolour (e.g., HEAVY death = darker tint,
     * EXPLOSIVE = warmer); defaults to a neutral asteroid tone.
     */
    fun spawnAsteroidDeathFX(
        cx: Float, cy: Float, cz: Float,
        colorTint: FloatArray = floatArrayOf(0.95f, 0.92f, 0.88f),
    ) {
        val nDebris = (DraftCombat.DEBRIS_COUNT_MIN..DraftCombat.DEBRIS_COUNT_MAX).random()
        for (i in 0 until nDebris) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.DEBRIS_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.DEBRIS_SPEED_MAX - DraftCombat.DEBRIS_SPEED_MIN)
            val life = DraftCombat.DEBRIS_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.DEBRIS_LIFE_MAX - DraftCombat.DEBRIS_LIFE_MIN)
            val size = DraftCombat.DEBRIS_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.DEBRIS_SIZE_MAX - DraftCombat.DEBRIS_SIZE_MIN)
            debrisParticles.add(Particle(
                x = cx, y = cy, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = colorTint[0], g = colorTint[1], b = colorTint[2], a = 1.0f,
                drag = DraftCombat.DEBRIS_DRAG,
                gravity = DraftCombat.DEBRIS_GRAVITY,
            ))
        }
        val nSmoke = (DraftCombat.SMOKE_DEATH_COUNT_MIN..DraftCombat.SMOKE_DEATH_COUNT_MAX).random()
        for (i in 0 until nSmoke) {
            val theta = (Math.random() * 2.0 * Math.PI).toFloat()
            val sp = DraftCombat.SMOKE_DEATH_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_SPEED_MAX - DraftCombat.SMOKE_DEATH_SPEED_MIN)
            val life = DraftCombat.SMOKE_DEATH_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_LIFE_MAX - DraftCombat.SMOKE_DEATH_LIFE_MIN)
            val size = DraftCombat.SMOKE_DEATH_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SMOKE_DEATH_SIZE_MAX - DraftCombat.SMOKE_DEATH_SIZE_MIN)
            smokeParticles.add(Particle(
                x = cx, y = cy, z = cz,
                vx = kotlin.math.cos(theta) * sp,
                vy = 0f,
                vz = kotlin.math.sin(theta) * sp,
                age = 0f, life = life, size = size,
                r = 0.85f, g = 0.82f, b = 0.78f, a = 0.65f,
                drag = DraftCombat.SMOKE_DEATH_DRAG,
            ))
        }
    }

    /**
     * Continuous spark emitter that runs while the player holds the recharge
     * button. Spawns sparks at random points on the force-field hemisphere
     * surface with tangential velocity so they appear to skim across the
     * shield before drag fades them out. Uses the existing E9 additive-
     * spark pool (cyan tint).
     */
    fun emitShieldRechargeSparks(dt: Float) {
        shieldRechargeSparkAccum += DraftCombat.SHIELD_RECHARGE_SPARK_RATE * dt
        val n = shieldRechargeSparkAccum.toInt()
        if (n <= 0) return
        shieldRechargeSparkAccum -= n.toFloat()
        val r  = DraftCombat.SHIELD_HEMISPHERE_RADIUS
        val cz = DraftCombat.SHIELD_CENTER_Z
        val tint = DraftCombat.SHIELD_RECHARGE_SPARK_TINT
        repeat(n) {
            // Uniform point on the FRONT hemisphere (y >= 0) of the shield.
            // theta = polar angle from +Y pole, phi = azimuth around it.
            // cos(theta) ~ U[0,1] for uniform sampling.
            val cosTheta = Math.random().toFloat()
            val sinTheta = kotlin.math.sqrt((1f - cosTheta * cosTheta).coerceAtLeast(0f))
            val phi = (Math.random() * 2.0 * Math.PI).toFloat()
            val cosPhi = kotlin.math.cos(phi)
            val sinPhi = kotlin.math.sin(phi)
            // Outward normal (unit, on hemisphere) and world position.
            val nx = sinTheta * cosPhi
            val ny = cosTheta
            val nz = sinTheta * sinPhi
            val sx = r * nx
            val sy = r * ny
            val sz = r * nz + cz
            // Tangent in tangent plane: rotate (nx, nz) by 90° around y axis.
            // Independent of sign of choice — works as long as it's
            // perpendicular to the outward normal.
            val txAxis = -nz
            val tyAxis = 0f
            val tzAxis =  nx
            val tlen = kotlin.math.sqrt(txAxis * txAxis + tzAxis * tzAxis).coerceAtLeast(1e-6f)
            val tx = txAxis / tlen
            val tz = tzAxis / tlen
            val sp = DraftCombat.SHIELD_RECHARGE_SPARK_SPEED_MIN +
                     Math.random().toFloat() * (DraftCombat.SHIELD_RECHARGE_SPARK_SPEED_MAX -
                                                 DraftCombat.SHIELD_RECHARGE_SPARK_SPEED_MIN)
            val dir = if (Math.random() < 0.5) 1f else -1f
            val life = DraftCombat.SHIELD_RECHARGE_SPARK_LIFE_MIN +
                       Math.random().toFloat() * (DraftCombat.SHIELD_RECHARGE_SPARK_LIFE_MAX -
                                                   DraftCombat.SHIELD_RECHARGE_SPARK_LIFE_MIN)
            val size = DraftCombat.SHIELD_RECHARGE_SPARK_SIZE_MIN +
                       Math.random().toFloat() * (DraftCombat.SHIELD_RECHARGE_SPARK_SIZE_MAX -
                                                   DraftCombat.SHIELD_RECHARGE_SPARK_SIZE_MIN)
            sparkParticles.add(Particle(
                x = sx, y = sy, z = sz,
                vx = tx * sp * dir,
                vy = tyAxis,
                vz = tz * sp * dir,
                age = 0f, life = life, size = size,
                r = tint[0], g = tint[1], b = tint[2], a = 1.5f,
                drag = DraftCombat.SHIELD_RECHARGE_SPARK_DRAG,
            ))
        }
    }

    /**
     * Tints + sizes the small "warm orange spark" that punctuates direct
     * (non-AoE) projectile hits. Sized by the projectile's half-width so
     * a chunky cannon shell pops bigger than an automatic round.
     */
    fun spawnHitFlash(x: Float, y: Float, z: Float, halfW: Float) {
        val tint = DraftCombat.FLASH_TINT_HIT
        flashes.add(Flash(
            x = x, y = y, z = z,
            life = DraftCombat.HIT_FLASH_LIFE,
            maxLife = DraftCombat.HIT_FLASH_LIFE,
            halfMax = halfW * DraftCombat.HIT_FLASH_SIZE_MUL,
            tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3],
        ))
    }

    /**
     * One-shot bright burst spawned at engine ignition (ASCENDING → FLYING
     * transition). Visually punctuates the moment the engine kicks in.
     * Larger and brighter than per-frame jet pulses; standard plasma flash
     * with warm-orange tint and a short fade.
     */
    fun spawnRocketIgnition(x: Float, y: Float, z: Float) {
        val tint = DraftCombat.FLASH_TINT_MUZZLE
        flashes.add(Flash(
            x = x, y = y, z = z,
            life    = DraftCombat.ROCKET_IGNITION_LIFE,
            maxLife = DraftCombat.ROCKET_IGNITION_LIFE,
            halfMax = DraftCombat.ROCKET_IGNITION_HALF,
            tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3] * 1.4f,
        ))
    }

    /**
     * Per-tick reactive-jet pulse behind the rocket nozzle. Short life +
     * tight emission interval so the pulses overlap into a flame tongue.
     * Position is offset backward along the rocket's reverse velocity
     * vector by ~half a body length, so the flame sits at the engine bell
     * regardless of orientation.
     */
    fun spawnRocketJet(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float) {
        val speed = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        val nx = if (speed > 1e-4f) vx / speed else 0f
        val ny = if (speed > 1e-4f) vy / speed else 0f
        val nz = if (speed > 1e-4f) vz / speed else 1f
        val off = DraftCombat.ROCKET_BODY_LENGTH *
                  DraftCombat.ROCKET_JET_TAIL_OFFSET_FRAC
        val tint = DraftCombat.FLASH_TINT_MUZZLE
        flashes.add(Flash(
            x = x - nx * off, y = y - ny * off, z = z - nz * off,
            life    = DraftCombat.ROCKET_JET_LIFE,
            maxLife = DraftCombat.ROCKET_JET_LIFE,
            halfMax = DraftCombat.ROCKET_JET_HALF,
            tintR = tint[0], tintG = tint[1], tintB = tint[2], tintA = tint[3],
        ))
    }

    /**
     * Smoke puff behind a rocket — drifts backward along the reverse-
     * velocity vector, fades over its lifetime via the existing E9 alpha-
     * textured smoke pool. Tints slightly cooler / more grey than asteroid-
     * death smoke so a rocket trail reads as exhaust, not debris.
     */
    fun spawnRocketTrail(
        rocketX: Float, rocketY: Float, rocketZ: Float,
        vx: Float, vy: Float, vz: Float,
    ) {
        val speed = kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
        val nx = if (speed > 1e-4f) vx / speed else 0f
        val ny = if (speed > 1e-4f) vy / speed else 0f
        val nz = if (speed > 1e-4f) vz / speed else 1f
        val drift = DraftCombat.ROCKET_TRAIL_DRIFT
        val rng = Math.random().toFloat()
        val life = DraftCombat.ROCKET_TRAIL_LIFE_MIN +
                   rng * (DraftCombat.ROCKET_TRAIL_LIFE_MAX -
                          DraftCombat.ROCKET_TRAIL_LIFE_MIN)
        val size = DraftCombat.ROCKET_TRAIL_SIZE_MIN +
                   Math.random().toFloat() *
                       (DraftCombat.ROCKET_TRAIL_SIZE_MAX -
                        DraftCombat.ROCKET_TRAIL_SIZE_MIN)
        smokeParticles.add(Particle(
            x = rocketX, y = rocketY, z = rocketZ,
            vx = -nx * drift, vy = -ny * drift, vz = -nz * drift,
            age = 0f, life = life, size = size,
            r = 0.78f, g = 0.78f, b = 0.80f, a = 0.55f,
            drag = DraftCombat.ROCKET_TRAIL_DRAG,
        ))
    }

    /**
     * Spring-launch dust puff at the silo opening — radial cloud of smoke
     * particles drifting outward, simulating displaced air/dust as the
     * rocket pops out of the tube. Distinct from `spawnMuzzleBlast` (which
     * is the gunshot trefoil cone) — rockets aren't gunshots; they're
     * mechanically ejected, then the engine ignites mid-flight.
     */
    fun spawnRocketLaunchPuff(x: Float, y: Float, z: Float) {
        repeat(6) {
            val angle = (Math.random() * 2.0 * Math.PI).toFloat()
            val speed = 0.5f + Math.random().toFloat() * 0.7f
            val vx = kotlin.math.cos(angle) * speed
            val vz = kotlin.math.sin(angle) * speed
            val life = 0.45f + Math.random().toFloat() * 0.30f
            val size = 0.07f + Math.random().toFloat() * 0.05f
            smokeParticles.add(Particle(
                x = x, y = y, z = z,
                vx = vx, vy = 0f, vz = vz,
                age = 0f, life = life, size = size,
                r = 0.80f, g = 0.78f, b = 0.75f, a = 0.55f,
                drag = 3.5f,
            ))
        }
    }

    /**
     * Spawn an explosion at (cx, cz) sized to `radius`. 3D fireball mesh
     * through the additive pipeline with the fire-material shader (E7.1).
     * The Y-axis-aligned UV-sphere gives a true volumetric look with
     * Fresnel-soft silhouette, heat ramp from white-hot core to orange edge,
     * and animated FBM turbulence. Plus a complementary radial spark fan.
     */
    fun spawnExplosion(cx: Float, cy: Float, cz: Float, radius: Float) {
        fireballs.add(Fireball(
            x = cx, y = cy, z = cz,
            life = DraftCombat.FIREBALL_LIFE_SEC,
            maxLife = DraftCombat.FIREBALL_LIFE_SEC,
            baseRadius = radius,
        ))
        // E9 — radial spark fan complementing the 3D fireball. Reads as
        // ejecta flying out of the blast core; sparks fade fast (≤0.55s)
        // so they punctuate the moment without obscuring the fireball.
        spawnSparkBurst(cx, cy, cz)
    }
}
