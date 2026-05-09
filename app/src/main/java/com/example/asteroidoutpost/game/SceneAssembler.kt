package com.example.asteroidoutpost.game

import com.example.asteroidoutpost.BeamDraw
import com.example.asteroidoutpost.BillboardDraw
import com.example.asteroidoutpost.EngineJni
import com.example.asteroidoutpost.ParticleBatchKt
import com.example.asteroidoutpost.SceneObject
import com.example.asteroidoutpost.game.combat.Asteroid
import com.example.asteroidoutpost.game.combat.Beam
import com.example.asteroidoutpost.game.combat.DraftCombat
import com.example.asteroidoutpost.game.combat.Fireball
import com.example.asteroidoutpost.game.combat.Flash
import com.example.asteroidoutpost.game.combat.Particle
import com.example.asteroidoutpost.game.combat.Projectile
import com.example.asteroidoutpost.game.combat.WeaponEffect
import com.example.asteroidoutpost.game.combat.packParticles

/**
 * Game → engine adapter. Reads game-side state (asteroids, projectiles,
 * effects, particles) and produces immutable `SceneFrame` snapshots the
 * engine can consume. The engine itself is unaware of any of these game
 * types — it sees only `SceneObject` / `BillboardDraw` / `BeamDraw` /
 * `ParticleBatchKt` lists.
 *
 * Anything game-specific that needs to be rendered passes through this
 * layer. Reusing the engine for a different game (e.g. g3 ships) means
 * writing a different `SceneAssembler`; the engine stays unchanged.
 *
 * Construction: all mutable game lists are passed by reference; mesh /
 * texture handles are baked in (set once after engine init). `assemble()`
 * is called per frame with the small set of scalar state that changes
 * per tick (turret angle, reload progress, shield HP).
 */

/**
 * One frame's worth of draw commands, ready for the engine to consume.
 * Everything is immutable; producing a frame allocates fresh lists each
 * call. Size of these lists is bounded by gameplay (bullet/asteroid/etc.
 * counts), so allocation cost is small.
 */
internal data class SceneFrame(
    val scene: List<SceneObject>,
    val plasmaBillboards: List<BillboardDraw>,
    val translucentObjects: List<SceneObject>,
    val additiveObjects: List<SceneObject>,
    val beams: List<BeamDraw>,
    val particleBatches: List<ParticleBatchKt>,
)

internal class SceneAssembler(
    // ---- Game-state references (read-only here; mutated by tick / VfxSpawner) ----
    private val asteroids: List<Asteroid>,
    private val effects: List<WeaponEffect>,
    private val flashes: List<Flash>,
    private val fireballs: List<Fireball>,
    private val sparkParticles: List<Particle>,
    private val smokeParticles: List<Particle>,
    private val debrisParticles: List<Particle>,

    // ---- World layout (immutable after init) ----
    private val turretXs: FloatArray,
    private val sideTurretAngles: FloatArray,  // mutated in place by tick — read each frame
    private val nebulaeTranslucent: List<SceneObject>,

    // ---- Mesh / texture handles (set once at engine init) ----
    private val quadGreyHandle: Long,
    private val quadFlashHandle: Long,
    private val quadMeshHandle: Long,
    private val quadHpBgHandle: Long,
    private val quadHpFgHandle: Long,
    private val centralBaseMeshHandle: Long,
    private val centralBarrelMeshHandle: Long,
    private val sideBaseMeshHandle: Long,
    private val sideBarrelMeshHandle: Long,
    private val laserInstallMeshHandle: Long,
    private val rocketSiloMeshHandle: Long,
    private val asteroidMeshGrey1: Long,
    private val domeMembraneHandle: Long,
    private val fireballMeshHandle: Long,
    private val particleQuadHandle: Long,
    private val smokeTextureHandle: Long,
    private val debrisTextureHandle: Long,
) {
    /**
     * Build one frame's draw commands. Per-frame scalars are passed in;
     * everything else is read from the references baked at construction.
     *
     * @param reloadProgress ∈ [0, 1] — reload-bar fill ratio (1 = ready).
     * @param centralTurretAngle radians (atan2(dx, dz) convention,
     *        clamped to weapon arc by the tick before this is called).
     * @param shieldHp current shield HP (0 hides the arch).
     */
    fun assemble(
        reloadProgress: Float,
        centralTurretAngle: Float,
        shieldHp: Float,
    ): SceneFrame {
        val reloadHalfW     = DraftCombat.RELOAD_BAR_HALF_W
        val reloadFillHalfW = reloadHalfW * reloadProgress
        // Fill is anchored to the LEFT edge of the backing so it grows L→R.
        val reloadFillCenterX = -reloadHalfW * (1f - reloadProgress)

        val opaque = listOf(
            // Platform — full width, 5% screen height, pinned to bottom.
            SceneObject(
                id         = 100,
                meshHandle = quadGreyHandle,
                x          = 0f, y = 0f, z = -1.215f,
                scaleX     = 2.47f,
                scaleY     = 1f,
                scaleZ     = 0.275f,
            ),
            // Reload bar — backing (dim grey, full width).
            SceneObject(
                id         = 107,
                meshHandle = quadGreyHandle,
                x          = 0f, y = 0f, z = DraftCombat.RELOAD_BAR_Z,
                scaleX     = reloadHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.RELOAD_BAR_HALF_THICK,
            ),
            // Reload bar — fill (yellow, width = progress * full). Nudged
            // slightly forward in Y so it passes the LESS depth test against
            // the backing (both share the y=0 plane otherwise, and the regular
            // scene pipeline rejects equal-depth fragments).
            SceneObject(
                id         = 108,
                meshHandle = quadFlashHandle,
                x          = reloadFillCenterX, y = -0.01f, z = DraftCombat.RELOAD_BAR_Z,
                scaleX     = reloadFillHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.RELOAD_BAR_HALF_THICK,
            ),
            // Central turret — split into static base + rotating barrel.
            // Base sits on the platform (no rotation); the housing+barrel
            // mesh has its origin at the pivot atop the base and rotates
            // via SceneObject.rotationY around its own model origin, so
            // the offset trick used for the legacy quad isn't needed.
            SceneObject(
                id         = 109,
                meshHandle = centralBaseMeshHandle,
                x          = DraftCombat.CENTRAL_TURRET_X,
                y          = 0f,
                z          = DraftCombat.PLATFORM_TOP_Z,
                scale      = 1f,
            ),
            SceneObject(
                id         = 119,
                meshHandle = centralBarrelMeshHandle,
                x          = DraftCombat.CENTRAL_TURRET_X,
                y          = 0f,
                z          = DraftCombat.CENTRAL_TURRET_BASE_Z,
                rotationY  = centralTurretAngle,
                scale      = 1f,
            ),
            // Side turret 0 — base + tracking barrel.
            SceneObject(
                id         = 110,
                meshHandle = sideBaseMeshHandle,
                x          = turretXs[0], y = 0f, z = DraftCombat.PLATFORM_TOP_Z,
                scale      = 1f,
            ),
            SceneObject(
                id         = 120,
                meshHandle = sideBarrelMeshHandle,
                x          = turretXs[0], y = 0f, z = DraftCombat.TURRET_TOP_Z,
                rotationY  = sideTurretAngles[0],
                scale      = 1f,
            ),
            // Side turret 1.
            SceneObject(
                id         = 111,
                meshHandle = sideBaseMeshHandle,
                x          = turretXs[1], y = 0f, z = DraftCombat.PLATFORM_TOP_Z,
                scale      = 1f,
            ),
            SceneObject(
                id         = 121,
                meshHandle = sideBarrelMeshHandle,
                x          = turretXs[1], y = 0f, z = DraftCombat.TURRET_TOP_Z,
                rotationY  = sideTurretAngles[1],
                scale      = 1f,
            ),
            // Laser installation — small dome with a vertical barrel between
            // the central turret and the right side turret. Single static
            // mesh, mesh authored in world units so scale=1.
            SceneObject(
                id         = 131,
                meshHandle = laserInstallMeshHandle,
                x          = DraftCombat.LASER_INSTALL_X,
                y          = 0f,
                z          = DraftCombat.PLATFORM_TOP_Z,
                scale      = 1f,
            ),
            // Rocket silo — open hatch on the LEFT side of the central
            // turret. Rockets emerge from its launch opening (see
            // launchRocketStrike). Static, no rotation.
            SceneObject(
                id         = 132,
                meshHandle = rocketSiloMeshHandle,
                x          = DraftCombat.ROCKET_SILO_X,
                y          = 0f,
                z          = DraftCombat.PLATFORM_TOP_Z,
                scale      = 1f,
            ),
        ) + asteroids.mapIndexed { i, a ->
            // Per-asteroid mesh chosen at spawn (5 distinct .glbs across 5 types
            // + grey variant pool). Roughly unit bbox; scale by `half` so FAST
            // asteroids look small and HEAVY ones look chunky.
            // E10.3 — build prev_model from prevZ + prevRotation cached at the
            // top of the asteroid movement step. xPos doesn't change so we
            // reuse it for both matrices; the prev SceneObject is a temporary
            // we only ever ask `modelMatrix()` of.
            val prev = SceneObject(
                id         = 200 + i,
                meshHandle = 0L,
                x          = a.xPos, y = 0f, z = a.prevZ,
                rotationZ  = a.prevRotation,
                scale      = a.half,
            ).modelMatrix()
            SceneObject(
                id              = 200 + i,
                meshHandle      = if (a.meshHandle != 0L) a.meshHandle else asteroidMeshGrey1,
                x               = a.xPos, y = 0f, z = a.zPos,
                rotationZ       = a.rotation,
                scale           = a.half,
                prevModelMatrix = prev,
            )
        } + effects.filterIsInstance<Projectile>().mapIndexed { i, b ->
            // Projectile model — long axis aligned with velocity. Y-rotation =
            // atan2(vx, vz) maps the model's local +Z to the velocity vector,
            // plus BULLET_MODEL_YAW_OFFSET so we can correct if the .glb's
            // forward axis turns out not to be +Z.
            // E10.3 — prev_model from prevX/prevZ; rotation is constant for
            // a projectile (fixed velocity vector), so reuse current rotationY.
            val mesh   = if (b.meshHandle != 0L) b.meshHandle else quadMeshHandle
            val rotY   = kotlin.math.atan2(b.vx, b.vz) + b.modelYawOffset
            val bScale = b.modelScale
            val prev   = SceneObject(
                id         = 300 + i,
                meshHandle = 0L,
                x          = b.prevX, y = 0f, z = b.prevZ,
                rotationY  = rotY,
                scale      = bScale,
            ).modelMatrix()
            SceneObject(
                id              = 300 + i,
                meshHandle      = mesh,
                x               = b.x, y = 0f, z = b.z,
                rotationY       = rotY,
                scale           = bScale,
                prevModelMatrix = prev,
            )
        } + buildHpBars(asteroids, quadHpBgHandle, quadHpFgHandle)

        // Flash VFX: muzzle flash, bullet trails, asteroid hit, AoE rings, ENERGY-buff
        // pickup. Routed through the additive plasma pipeline (E2.1) so they read as
        // soft circular glows that brighten what's behind them — instead of square
        // yellow placeholders sitting on the dark background. E5.1 — per-flash tint
        // forwarded to the plasma fragment branch via BillboardDraw → drawPlasmaBillboard.
        val flashBillboards = flashes.map { f ->
            val t  = 1f - (f.life / f.maxLife)
            val k  = 0.6f + t * 0.8f
            val s  = f.halfMax  * k
            val sV = f.halfMaxV * k
            // E11 — flashes that specify their own mesh (e.g. muzzle cones)
            // route through it; round flashes fall back to the standard
            // quadFlashHandle. Rotation is plumbed straight through; default
            // 0 leaves quads axis-aligned as before.
            val mesh = if (f.meshHandle != 0L) f.meshHandle else quadFlashHandle
            BillboardDraw(mesh, f.x, 0f, f.z, s, f.tintR, f.tintG, f.tintB, f.tintA,
                          scaleV = sV,
                          rotation = f.rotation, lightningSeed = f.lightningSeed)
        }

        val translucent = nebulaeTranslucent + buildShieldDome(shieldHp, domeMembraneHandle)

        // E9 — pack each particle pool into the engine's instance-buffer
        // layout once per frame and ship a single batch per pool. The
        // engine concatenates batches per pipeline at renderFrame and
        // does one instanced draw per batch. Skip pools when their
        // resources aren't loaded so missing assets degrade gracefully.
        val particleBatches = ArrayList<ParticleBatchKt>(3)
        if (particleQuadHandle != 0L && sparkParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = 0L,
                data          = packParticles(sparkParticles),
                count         = sparkParticles.size,
                mode          = EngineJni.PARTICLE_ADDITIVE,
            ))
        }
        if (particleQuadHandle != 0L && smokeTextureHandle != 0L && smokeParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = smokeTextureHandle,
                data          = packParticles(smokeParticles),
                count         = smokeParticles.size,
                mode          = EngineJni.PARTICLE_ALPHA_TEXTURED,
            ))
        }
        if (particleQuadHandle != 0L && debrisTextureHandle != 0L && debrisParticles.isNotEmpty()) {
            particleBatches.add(ParticleBatchKt(
                meshHandle    = particleQuadHandle,
                textureHandle = debrisTextureHandle,
                data          = packParticles(debrisParticles),
                count         = debrisParticles.size,
                mode          = EngineJni.PARTICLE_ALPHA_TEXTURED,
            ))
        }

        // E14 — beams come from active Beam effects in the unified `effects`
        // list. Each Beam.tick() recomputes its endpoints; we just map them
        // to BeamDraws here. Multiple beams (different sources/targets) all
        // render simultaneously through the engine's beam pipeline.
        val beams = effects.filterIsInstance<Beam>().map { beam ->
            BeamDraw(
                startX = beam.startPos.x, startY = beam.startPos.y, startZ = beam.startPos.z,
                endX   = beam.endPos.x,   endY   = beam.endPos.y,   endZ   = beam.endPos.z,
                width  = beam.width,
                r = beam.color[0], g = beam.color[1], b = beam.color[2], a = beam.color[3],
            )
        }

        // E7.1 — 3D fireball explosions. Y-axis-aligned UV-sphere through the
        // additive pipeline with the fire-material branch. Three curves on
        // t = age/maxLife give the explosion a real shape:
        //   • scale: ease-out quadratic 0.4 → 1.4 × baseRadius (fast initial
        //     blast, asymptotic settle — shockwaves decelerate as they
        //     expand, mirrored in the curve `1 - (1-t)²`).
        //   • colour: lerp FIREBALL_TINT_START → FIREBALL_TINT_END (forge
        //     orange → dying-ember red) so the ball visibly cools.
        //   • brightness: sqrt(1-t) — holds longer than linear at the start
        //     so the colour shift remains readable; pinches off near end.
        // Depth-test on / write off (E7) means the fireball is occluded by
        // closer opaque geometry (asteroids, turrets) but multiple
        // overlapping fireballs accumulate through additive blend.
        val fireballAdditive = if (fireballs.isEmpty() || fireballMeshHandle == 0L) emptyList<SceneObject>()
        else {
            val tStart = DraftCombat.FIREBALL_TINT_START
            val tEnd   = DraftCombat.FIREBALL_TINT_END
            fireballs.mapIndexed { i, fb ->
                val t = (1f - fb.life / fb.maxLife).coerceIn(0f, 1f)
                val u = 1f - t
                val scaleCurve = 1f - u * u                       // ease-out quad
                val s = fb.baseRadius * (0.4f + scaleCurve * 1.0f)
                val tintR = tStart[0] + (tEnd[0] - tStart[0]) * t
                val tintG = tStart[1] + (tEnd[1] - tStart[1]) * t
                val tintB = tStart[2] + (tEnd[2] - tStart[2]) * t
                val brightness = kotlin.math.sqrt(u) * fb.intensity
                // E10.3 — same scale curve evaluated against prevLife.
                // Fireballs don't translate so prev_model differs only in
                // scale; the velocity attachment captures the radial
                // expansion as outward motion vectors per surface element.
                val prevT = (1f - fb.prevLife / fb.maxLife).coerceIn(0f, 1f)
                val prevU = 1f - prevT
                val prevScaleCurve = 1f - prevU * prevU
                val prevS = fb.baseRadius * (0.4f + prevScaleCurve * 1.0f)
                val prev = SceneObject(
                    id         = 800 + i,
                    meshHandle = 0L,
                    x          = fb.x, y = 0f, z = fb.z,
                    scale      = prevS,
                ).modelMatrix()
                SceneObject(
                    id               = 800 + i,
                    meshHandle       = fireballMeshHandle,
                    x                = fb.x, y = 0f, z = fb.z,
                    scale            = s,
                    tintR            = tintR, tintG = tintG, tintB = tintB, tintA = brightness,
                    additiveMaterial = EngineJni.ADDITIVE_FIRE,
                    prevModelMatrix  = prev,
                )
            }
        }

        return SceneFrame(
            scene              = opaque,
            plasmaBillboards   = flashBillboards,
            translucentObjects = translucent,
            additiveObjects    = fireballAdditive,
            beams              = beams,
            particleBatches    = particleBatches,
        )
    }
}

/**
 * HP-bar over each damaged asteroid. Two flat quads (background + green
 * fill) appended to the opaque scene list. Hidden when hp == maxHp so a
 * fresh asteroid doesn't carry visual clutter; hidden when hp <= 0 so
 * dead asteroids don't render a bar in their last frame before cull. The
 * fill is anchored to the bar's left edge so it shrinks rightward as HP
 * drops (same pattern as the central-turret reload bar). Fill is nudged
 * -Y by 0.01 to pass the LESS depth test against the background quad
 * sharing the same screen position.
 */
internal fun buildHpBars(
    asteroids: List<Asteroid>,
    bgHandle: Long,
    fgHandle: Long,
): List<SceneObject> {
    if (asteroids.isEmpty() || bgHandle == 0L || fgHandle == 0L) return emptyList()
    val out = ArrayList<SceneObject>()
    asteroids.forEachIndexed { i, a ->
        if (a.hp <= 0 || a.hp >= a.maxHp) return@forEachIndexed
        val frac      = (a.hp.toFloat() / a.maxHp.toFloat()).coerceIn(0f, 1f)
        val barCx     = a.xPos
        val barCz     = a.zPos + a.half + DraftCombat.HP_BAR_PADDING
        val barHalfW  = a.half * DraftCombat.HP_BAR_HALF_W_MUL
        val fillHalfW = barHalfW * frac
        val fillCx    = barCx - barHalfW * (1f - frac)
        out.add(SceneObject(
            id         = 400 + i * 2,
            meshHandle = bgHandle,
            x          = barCx, y = 0f, z = barCz,
            scaleX     = barHalfW,
            scaleY     = 1f,
            scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
        ))
        out.add(SceneObject(
            id         = 401 + i * 2,
            meshHandle = fgHandle,
            x          = fillCx, y = -0.01f, z = barCz,
            scaleX     = fillHalfW,
            scaleY     = 1f,
            scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
        ))
    }
    return out
}

/**
 * Permanent shield arch (M9) — a wide flat ellipse band spanning the
 * platform width, drawn through the translucent pipeline whenever the
 * shield has any HP left. Returns empty when the shield is broken or the
 * arch mesh failed to load (translucent pass simply skips the dome).
 *
 * Arch mesh is pre-scaled in world units (see `buildShieldArchMesh`), so
 * we only translate to the platform top. Plain translucent material — no
 * hex/nebula overlay; the shape itself is the read. The arch is lifted by
 * `SHIELD_ARCH_LIFT_FRAC × halfH` so the ends hover above the platform
 * instead of sitting on it.
 */
internal fun buildShieldDome(shieldHp: Float, archHandle: Long): List<SceneObject> {
    if (shieldHp <= 0f) return emptyList()
    if (archHandle == 0L) return emptyList()
    val baseZ = DraftCombat.PLATFORM_TOP_Z +
        DraftCombat.SHIELD_ARCH_LIFT_FRAC * DraftCombat.SHIELD_ARCH_HALF_H
    return listOf(
        SceneObject(
            id         = 700,
            meshHandle = archHandle,
            x          = 0f, y = -0.05f, z = baseZ,
            scale      = 1f,
        ),
    )
}
