package com.example.asteroidoutpost.game.combat

/**
 * Lightweight 3D vector for closure-based source/target positions on
 * weapon effects. Y is depth (always 0 for game objects in Outpost,
 * non-zero in g3 where ships live in 3D); using Vec3 throughout means
 * the same Beam/Projectile types port to g3 without API changes.
 */
internal data class Vec3(val x: Float, val y: Float, val z: Float)

/** Flight phase for HomingRocketBehavior — see class doc for transitions. */
internal enum class RocketPhase { ASCENDING, FLYING }

/**
 * Per-tick context that all `WeaponEffect`s receive. Exposes the bits of
 * world state effects need (live asteroids for collision, VFX spawner for
 * impact/jet/trail flashes) without coupling them to MainActivity. The
 * tick loop owns a single `WeaponEffectContext` instance and threads it
 * through every `e.tick(dt, ctx)` call.
 *
 * Helpers (`steerProjectileTowards`, `applySplashDamage`) are top-level
 * pure functions — behaviours pull `ctx.asteroids` and pass it in.
 */
internal interface WeaponEffectContext {
    val asteroids: List<Asteroid>
    val vfx: VfxSpawner
}

/**
 * Umbrella for time-bounded combat effects — projectiles, beams, future
 * shockwaves / EMP pulses / cones. The tick loop owns a single
 * `effects: MutableList<WeaponEffect>` and dispatches `tick(dt, ctx)` per
 * frame; effects that return true are consumed and removed. SceneAssembler
 * queries `effects` by concrete type to compose draw calls (Projectile →
 * SceneObject in `scene`, Beam → BeamDraw in `beams`).
 */
internal interface WeaponEffect {
    /** True = consumed; remove from active list. */
    fun tick(dt: Float, ctx: WeaponEffectContext): Boolean
}

/**
 * Per-projectile strategy. Concrete behaviours own steering and impact
 * logic; the projectile's tick delegates to it without branching on flag
 * fields. Behaviours are pure objects — all world access goes through
 * the `ctx` parameter, no captured state.
 */
internal interface ProjectileBehavior {
    /** Per-tick state update (steering, accel, phase). Default = no-op. */
    fun tick(p: Projectile, dt: Float, ctx: WeaponEffectContext) {}
    /** Called on collision. Returns true if the projectile is consumed. */
    fun onImpact(p: Projectile, hit: Asteroid, ctx: WeaponEffectContext): Boolean
}

/**
 * Discrete projectile — flies along (vx, vz) from a muzzle, collides
 * with asteroids, single onImpact, then removed. Carries a
 * `behaviour: ProjectileBehavior` strategy for steering / impact variation
 * (plain bullet, heavy shell, homing rocket).
 */
internal class Projectile(
    var x: Float, var z: Float,
    var vx: Float, var vz: Float,
    val damage: Int,
    val halfW: Float = DraftCombat.BULLET_HALF_W,
    val halfH: Float = DraftCombat.BULLET_HALF_H,
    // Render mesh. 0 falls back to the red quad on the engine side.
    val meshHandle: Long = 0L,
    // Per-projectile render scale. Default = halfH × BULLET_MODEL_SCALE_MUL
    // matches legacy .glb-bullet sizing; procedural meshes authored in
    // world units pass `1f` to render at native scale.
    val modelScale: Float = halfH * DraftCombat.BULLET_MODEL_SCALE_MUL,
    // Per-projectile yaw offset applied to atan2(vx, vz) when rendering.
    // Default = legacy −π/2 for the +X-forward .glb bullet meshes;
    // procedural meshes authored with +Z forward pass `0f`.
    val modelYawOffset: Float = DraftCombat.BULLET_MODEL_YAW_OFFSET,
    // 3D-pivot Phase 2/3: Y is depth into the screen. Projectiles fire
    // toward the asteroid's full 3D position, then travel along a 3D
    // velocity. Default Y = 0 / vy = 0 keeps legacy 2D-only callers
    // (test cases, future rules-engine) working unchanged.
    var y: Float = 0f,
    var vy: Float = 0f,
    // E10.3 — previous-frame position for motion-vector tracking.
    var prevX: Float = x, var prevY: Float = y, var prevZ: Float = z,
    val behaviour: ProjectileBehavior,
) : WeaponEffect {
    override fun tick(dt: Float, ctx: WeaponEffectContext): Boolean {
        prevX = x; prevY = y; prevZ = z
        behaviour.tick(this, dt, ctx)
        x += vx * dt; y += vy * dt; z += vz * dt
        if (z > DraftCombat.SCREEN_TOP_Z + 1f ||
            z < DraftCombat.SCREEN_BOTTOM_Z - 1f ||
            x < -DraftCombat.SCREEN_HALF_W - 1f ||
            x >  DraftCombat.SCREEN_HALF_W + 1f ||
            y < -2f ||                                // passed the camera plane
            y >  DraftCombat.ASTEROID_SPAWN_Y_DEPTH + 2f) return true
        // 3D-AABB collision against the first live asteroid we touch.
        for (a in ctx.asteroids) {
            if (a.hp <= 0) continue
            if (kotlin.math.abs(x - a.xPos) < a.half + halfW &&
                kotlin.math.abs(y - a.yPos) < a.half + halfH &&
                kotlin.math.abs(z - a.zPos) < a.half + halfH) {
                return behaviour.onImpact(this, a, ctx)
            }
        }
        return false
    }
}

/**
 * Continuous laser-style beam — finite duration, source attached to a
 * (possibly moving) origin via `source` closure, aim re-evaluated each
 * tick via `aimSelector` so beam follows the current priority target.
 * Each tick: ray-cast from source toward aim; first asteroid that
 * intersects the line takes DPS × dt damage (no piercing). The beam's
 * visible endpoint is the hit asteroid — or aim's position if no
 * obstruction. Effect is consumed when duration expires or aim is null
 * (no target available).
 */
internal class Beam(
    val source: () -> Vec3,
    val aimSelector: () -> Asteroid?,
    durationSec: Float,
    val dps: Float,
    val width: Float,
    val color: FloatArray,        // length 4: r, g, b, a
    val pad: Float = DraftCombat.LASER_BEAM_PAD,
    // Optional engagement gate — when the master target exists but is
    // out of THIS beam's reach (arc / range), the beam ticks down its
    // duration without rendering or applying damage. Default = always
    // engageable. Used by the laser to limit firing to its 95% arc
    // while still tracking whatever the central turret has locked.
    val canEngage: (Asteroid) -> Boolean = { true },
) : WeaponEffect {
    var remaining: Float = durationSec
    var dmgAccum: Float = 0f
    // Endpoints for SceneAssembler to read after each tick. Updated in
    // tick(); SceneAssembler reads them when composing the BeamDraw. When
    // canEngage gates the beam off this frame, both equal source so
    // the beam renders zero-length (i.e. invisible).
    var startPos: Vec3 = source()
    var endPos:   Vec3 = source()

    override fun tick(dt: Float, ctx: WeaponEffectContext): Boolean {
        remaining -= dt
        if (remaining <= 0f) return true
        val target = aimSelector() ?: return true
        val src = source()
        startPos = src
        if (!canEngage(target)) {
            // Out of arc this frame — hide beam, skip damage, keep
            // counting down the duration. Beam will reappear next
            // frame if the target re-enters the engagement zone.
            endPos = src
            dmgAccum = 0f       // don't carry over fractional damage from idle frames
            return false
        }
        // 3D-pivot Phase 2/3: ray-cast in 3D. Beam direction includes
        // Y so it actually points at the asteroid's current depth, not
        // at the X/Z projection on the deck plane.
        val dx = target.xPos - src.x
        val dy = target.yPos - src.y
        val dz = target.zPos - src.z
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3f) {
            endPos = src
            return false
        }
        val nx = dx / len
        val ny = dy / len
        val nz = dz / len
        // Find the asteroid closest to the source along the aim line in 3D.
        var bestT = Float.MAX_VALUE
        var bestAst: Asteroid? = null
        for (a in ctx.asteroids) {
            if (a.hp <= 0) continue
            val rx = a.xPos - src.x
            val ry = a.yPos - src.y
            val rz = a.zPos - src.z
            val t  = rx * nx + ry * ny + rz * nz
            if (t < 0f) continue
            val px = src.x + nx * t
            val py = src.y + ny * t
            val pz = src.z + nz * t
            val hx = a.xPos - px
            val hy = a.yPos - py
            val hz = a.zPos - pz
            val r  = a.half + pad
            if (hx * hx + hy * hy + hz * hz <= r * r && t < bestT) {
                bestT = t
                bestAst = a
            }
        }
        // Fractional damage accumulator → integer hp tick.
        if (bestAst != null) {
            dmgAccum += dps * dt
            val whole = dmgAccum.toInt()
            if (whole > 0) {
                bestAst.hp -= whole
                dmgAccum -= whole.toFloat()
            }
        }
        val beamLen = if (bestAst != null) bestT else len
        endPos = Vec3(
            src.x + nx * beamLen,
            src.y + ny * beamLen,
            src.z + nz * beamLen,
        )
        return false
    }
}

// ---- Concrete projectile behaviours ---------------------------------------

/** Plain single-target bullet — no steering, small hit flash on impact. */
internal class PlainBulletBehavior : ProjectileBehavior {
    override fun onImpact(p: Projectile, hit: Asteroid, ctx: WeaponEffectContext): Boolean {
        hit.hp -= p.damage
        ctx.vfx.spawnHitFlash(hit.xPos, hit.yPos, hit.zPos, p.halfW)
        return true
    }
}

/**
 * Heavy cannon shell — straight flight, AoE on impact. Direct-hit
 * target takes full `p.damage`, splash neighbours within `aoeRadius`
 * take `aoeDamage`, fireball + sparks at the impact site.
 */
internal class HeavyShellBehavior(
    val aoeRadius: Float,
    val aoeDamage: Int,
) : ProjectileBehavior {
    override fun onImpact(p: Projectile, hit: Asteroid, ctx: WeaponEffectContext): Boolean {
        hit.hp -= p.damage
        applySplashDamage(ctx.asteroids, hit.xPos, hit.yPos, hit.zPos, aoeRadius, aoeDamage, hit)
        ctx.vfx.spawnExplosion(hit.xPos, hit.yPos, hit.zPos, aoeRadius)
        return true
    }
}

/**
 * Homing rocket — two-phase flight:
 *
 *   ASCENDING  Spring-launched straight up at constant ASCENT_SPEED,
 *              no homing, no boost. The rocket clears the silo by
 *              `ascentHeight` (= 2× rocket length) before the engine
 *              ignites. The silo's launch queue checks this phase to
 *              know when the next rocket may be released.
 *
 *   FLYING     Engine on. Boost-accelerates along current heading up
 *              to `cruiseSpeed`, simultaneously steers toward
 *              `targetId` clamped by `turnRate`. If the target died
 *              in flight, the rocket coasts on its current heading
 *              and detonates on the first thing it collides with.
 *
 * On impact (either phase): full damage + AoE splash + fireball.
 */
internal class HomingRocketBehavior(
    val targetId: Long,
    val turnRate: Float,
    val aoeRadius: Float,
    val aoeDamage: Int,
    val cruiseSpeed: Float,
    val boostAccel: Float,
    val ascentHeight: Float,
    val ascentSpeed: Float,
    val launchZ: Float,
) : ProjectileBehavior {
    var phase: RocketPhase = RocketPhase.ASCENDING
    // Per-instance trail/jet jitter so salvos don't pulse in lock-step.
    private var trailTimer: Float =
        (Math.random().toFloat() * DraftCombat.ROCKET_TRAIL_INTERVAL)
    private var jetTimer: Float =
        (Math.random().toFloat() * DraftCombat.ROCKET_JET_INTERVAL)

    override fun tick(p: Projectile, dt: Float, ctx: WeaponEffectContext) {
        when (phase) {
            RocketPhase.ASCENDING -> {
                // Constant straight-up rise — spring push only, engine
                // is OFF, so no smoke trail and no reactive jet emit
                // this phase. The rocket reads as inert ordnance still
                // riding the spring's momentum. Y stays at the silo
                // (y=0); ascent is purely along Z.
                p.vx = 0f
                p.vy = 0f
                p.vz = ascentSpeed
                if (p.z - launchZ >= ascentHeight) {
                    phase = RocketPhase.FLYING
                    ctx.vfx.spawnRocketIgnition(p.x, p.y, p.z)
                }
            }
            RocketPhase.FLYING -> {
                // 3D-pivot Phase 2/3: boost + steer in 3D so the rocket
                // climbs in Y to chase asteroids that are still up the
                // depth column.
                val curSpeed = kotlin.math.sqrt(
                    p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
                if (curSpeed < cruiseSpeed && curSpeed > 1e-4f) {
                    val nx = p.vx / curSpeed
                    val ny = p.vy / curSpeed
                    val nz = p.vz / curSpeed
                    val newSpeed =
                        (curSpeed + boostAccel * dt).coerceAtMost(cruiseSpeed)
                    p.vx = nx * newSpeed
                    p.vy = ny * newSpeed
                    p.vz = nz * newSpeed
                }
                val target = ctx.asteroids.firstOrNull { it.id == targetId && it.hp > 0 }
                if (target != null) steerProjectileTowards(p, target, turnRate, dt)
                jetTimer -= dt
                while (jetTimer <= 0f) {
                    jetTimer += DraftCombat.ROCKET_JET_INTERVAL
                    ctx.vfx.spawnRocketJet(p.x, p.y, p.z, p.vx, p.vy, p.vz)
                }
                trailTimer -= dt
                while (trailTimer <= 0f) {
                    trailTimer += DraftCombat.ROCKET_TRAIL_INTERVAL
                    ctx.vfx.spawnRocketTrail(p.x, p.y, p.z, p.vx, p.vy, p.vz)
                }
            }
        }
    }
    override fun onImpact(p: Projectile, hit: Asteroid, ctx: WeaponEffectContext): Boolean {
        hit.hp -= p.damage
        applySplashDamage(ctx.asteroids, hit.xPos, hit.yPos, hit.zPos, aoeRadius, aoeDamage, hit)
        ctx.vfx.spawnExplosion(hit.xPos, hit.yPos, hit.zPos, aoeRadius)
        return true
    }
}

// ---- Helpers --------------------------------------------------------------

/**
 * Rotates `(p.vx, p.vz)` toward `target`, clamped by `turnRate * dt` per
 * tick. Speed is preserved (purely angular correction). No-op if the
 * missile is essentially on top of the target or has zero speed. Pure
 * helper, no behaviour state.
 */
internal fun steerProjectileTowards(
    p: Projectile, target: Asteroid,
    turnRate: Float, dt: Float,
) {
    // 3D-pivot Phase 2/3: steer in 3D — rotate the velocity vector
    // around the perpendicular axis between current heading and desired
    // heading, clamped by `turnRate * dt`. Speed preserved.
    val tdx = target.xPos - p.x
    val tdy = target.yPos - p.y
    val tdz = target.zPos - p.z
    val tlen = kotlin.math.sqrt(tdx * tdx + tdy * tdy + tdz * tdz)
    if (tlen < 1e-3f) return
    val speed = kotlin.math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz)
    if (speed < 1e-4f) return

    val cx = p.vx / speed; val cy = p.vy / speed; val cz = p.vz / speed
    val dx = tdx / tlen;  val dy = tdy / tlen;  val dz = tdz / tlen
    val cosAng = (cx * dx + cy * dy + cz * dz).coerceIn(-1f, 1f)
    val angle  = kotlin.math.acos(cosAng)
    if (angle < 1e-4f) return
    val maxStep = turnRate * dt
    val step    = kotlin.math.min(angle, maxStep)
    // Slerp from current to desired direction by `step`.
    val sinA = kotlin.math.sin(angle)
    val a = kotlin.math.sin(angle - step) / sinA
    val b = kotlin.math.sin(step)         / sinA
    p.vx = (cx * a + dx * b) * speed
    p.vy = (cy * a + dy * b) * speed
    p.vz = (cz * a + dz * b) * speed
}

/**
 * Apply splash damage to every live asteroid within `radius` of
 * `(cx, cy, cz)` (3D), excluding `centre` (the asteroid that absorbed
 * the direct impact above). Used by AoE-class behaviours after the
 * direct hit's full damage has already landed on `centre`.
 */
internal fun applySplashDamage(
    asteroids: List<Asteroid>,
    cx: Float, cy: Float, cz: Float, radius: Float,
    damage: Int, centre: Asteroid?,
) {
    if (radius <= 0f || damage <= 0) return
    val r2 = radius * radius
    for (a in asteroids) {
        if (a === centre || a.hp <= 0) continue
        val dx = a.xPos - cx
        val dy = a.yPos - cy
        val dz = a.zPos - cz
        val d2 = dx * dx + dy * dy + dz * dz
        if (d2 > 1e-6f && d2 <= r2) a.hp -= damage
    }
}
