package com.example.asteroidoutpost.game

import com.example.asteroidoutpost.BeamDraw
import com.example.asteroidoutpost.BillboardDraw
import com.example.asteroidoutpost.EngineJni
import com.example.asteroidoutpost.ForceFieldDraw
import com.example.asteroidoutpost.ParticleBatchKt
import com.example.asteroidoutpost.SceneObject
import com.example.asteroidoutpost.game.combat.Asteroid
import com.example.asteroidoutpost.game.combat.Beam
import com.example.asteroidoutpost.game.combat.DraftCombat
import com.example.asteroidoutpost.game.combat.Drone
import com.example.asteroidoutpost.game.combat.Fireball
import com.example.asteroidoutpost.game.combat.Flash
import com.example.asteroidoutpost.game.combat.Particle
import com.example.asteroidoutpost.game.combat.Projectile
import com.example.asteroidoutpost.game.combat.ShieldImpact
import com.example.asteroidoutpost.game.combat.WeaponEffect
import com.example.asteroidoutpost.game.combat.packParticles
import com.example.asteroidoutpost.game.content.TURRET_BASE_Y_HEIGHT
import com.example.asteroidoutpost.game.content.TURRET_CANNON_HALF_THICK
import com.example.asteroidoutpost.game.content.TURRET_TOWER_Y_HEIGHT

/**
 * Asteroid SceneObject IDs are computed as [ASTEROID_PICK_ID_BASE] + the
 * asteroid's stable `Long` id. The engine's pickable buffer (one int per
 * pixel) returns this id when the player taps an asteroid; the touch
 * handler decodes it via `pickedSceneId - ASTEROID_PICK_ID_BASE`. The
 * base sits well above all other static IDs (platform = 100, projectiles
 * = 300+i, HP-bars = 400/401+, fireballs = 800+i, nebulae = 2000+i) so
 * decoding is unambiguous.
 */
internal const val ASTEROID_PICK_ID_BASE: Int = 100_000

private const val PI_OVER_2: Float = 1.5707964f

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
    val forceFields: List<ForceFieldDraw>,
)

internal class SceneAssembler(
    // ---- Game-state references (read-only here; mutated by tick / VfxSpawner) ----
    private val asteroids: List<Asteroid>,
    private val effects: List<WeaponEffect>,
    private val drones: List<Drone>,
    private val flashes: List<Flash>,
    private val fireballs: List<Fireball>,
    private val shieldImpacts: List<ShieldImpact>,
    private val sparkParticles: List<Particle>,
    private val smokeParticles: List<Particle>,
    private val debrisParticles: List<Particle>,

    // ---- World layout (immutable after init) ----
    private val turretXs: FloatArray,
    private val sideTurretAngles: FloatArray,  // mutated in place by tick — read each frame
    private val nebulaeTranslucent: List<SceneObject>,

    // ---- Mesh / texture handles (set once at engine init) ----
    private val quadGreyHandle: Long,
    private val shipHullMeshHandle: Long,
    private val quadFlashHandle: Long,
    private val quadMeshHandle: Long,
    private val quadHpBgHandle: Long,
    private val quadHpFgHandle: Long,
    private val centralBaseMeshHandle: Long,
    private val centralTowerMeshHandle: Long,
    private val centralCannonMeshHandle: Long,
    private val sideBaseMeshHandle: Long,
    private val sideTowerMeshHandle: Long,
    private val sideCannonMeshHandle: Long,
    private val sideBodyGltfMeshHandle: Long,
    private val sideCannonGltfMeshHandle: Long,
    private val laserInstallMeshHandle: Long,
    private val rocketSiloMeshHandle: Long,
    private val asteroidMeshGrey1: Long,
    private val droneMeshHandle: Long,
    private val shieldHemisphereHandle: Long,
    private val fireballMeshHandle: Long,
    private val particleQuadHandle: Long,
    private val smokeTextureHandle: Long,
    private val debrisTextureHandle: Long,
) {
    // ---- Motion-blur prev-state for ship-attached objects ----
    // The engine's velocity buffer is `currClip - prevClip`. SceneObjects
    // that don't set `prevModelMatrix` get a sentinel identity, so when the
    // ship moves along +Y the velocity reads as ≈ shipPosY (huge) for hull/
    // turrets/dome/silo — the post-pass then dilates and blurs them into a
    // visibly trembling smear. We track prev-frame ship state here and
    // build accurate prev_model matrices so velocity for stationary
    // ship-attached parts comes out ≈ 0.
    private var hasPrevShipState: Boolean = false
    private var prevShipPosY: Float = 0f
    private var prevCentralTurretAngle: Float = 0f
    private val prevSideTurretAnglesArr: FloatArray = FloatArray(2)
    /**
     * Build one frame's draw commands. Per-frame scalars are passed in;
     * everything else is read from the references baked at construction.
     *
     * @param centralTurretAngle radians (atan2(dx, dz) convention,
     *        clamped to weapon arc by the tick before this is called).
     * @param shieldHp current shield HP (0 hides the arch).
     */
    fun assemble(
        centralTurretAngle: Float,
        shieldHp: Float,
        shipPosY: Float,
    ): SceneFrame {
        // Snapshot prev-state for ship-attached motion-blur prev_model.
        // First frame: prev = current (avoids one-frame velocity flash on
        // mission entry). Subsequent frames use the values stashed at the
        // end of the previous assemble().
        val pShipPosY = if (hasPrevShipState) prevShipPosY else shipPosY
        val pCentralAngle = if (hasPrevShipState) prevCentralTurretAngle else centralTurretAngle
        val pSide0 = if (hasPrevShipState) prevSideTurretAnglesArr[0] else sideTurretAngles[0]
        val pSide1 = if (hasPrevShipState) prevSideTurretAnglesArr[1] else sideTurretAngles[1]

        val opaque = listOf(
            // Ship hull — replaces the legacy gray quad platform. Mesh is
            // authored in world units (X half = 2.47, Z half = 0.275),
            // so the SceneObject just translates it onto the platform
            // position with scale = 1.
            // Ship hull — mesh was authored for the OLD top-down camera
            // (extruded along world Y = depth-axis). The new sideways
            // camera needs it laid flat: rotationX = -π/2 swaps so the
            // deck face is at world Z = PLATFORM_TOP_Z and the hull body
            // extends downward in Z. Mesh-local Z (stern→bow) becomes
            // world Y (back→forward of ship centre). Without this fix
            // the hull's overlay details (deck stripes, panel seams) all
            // sit at the same world Y as the deck face → Z-fight shimmer.
            SceneObject(
                id         = 100,
                meshHandle = shipHullMeshHandle,
                x          = 0f, y = shipPosY, z = DraftCombat.PLATFORM_TOP_Z,
                rotationX  = -1.5707964f,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(0f, pShipPosY, DraftCombat.PLATFORM_TOP_Z, rotX = -1.5707964f),
            ),
            // Central turret — split into static base + rotating barrel.
            // Base sits on the platform (no rotation); the housing+barrel
            // mesh has its origin at the pivot atop the base and rotates
            // via SceneObject.rotationY around its own model origin, so
            // the offset trick used for the legacy quad isn't needed.
            //
            // Phase 4a: bases are 3D extruded chamfered prisms — mesh-local
            // Y goes from yBottom=0 down to yTop=-TURRET_BASE_Y_HEIGHT
            // (camera-near = more negative Y). Base sits at world y=-0.02
            // so its bottom hovers a hair above the hull plane (no LESS
            // z-fight). The rotating barrel mesh extends mesh-local
            // y=0..-TURRET_BARREL_Y_HEIGHT and sits on the base top in
            // world space, so its SceneObject y = base_y - base_height,
            // less an extra 0.003 lift toward the camera so the barrel
            // wall's bottom edge isn't exactly coplanar with the base top
            // face (otherwise the rotating barrel sweeps an LESS-rejected
            // ring across the base top — reads as a heat-shimmer artifact).
            // Central platform — laid flat: rotationX=-π/2 makes the
            // mesh's Y-height axis stand vertically in world Z, and the
            // mesh's Z-length axis lies along world +Y (forward).
            // Y offset −0.35 puts the central turret slightly back from
            // ship-centre so it doesn't clump with the side turrets in
            // a single line.
            SceneObject(
                id         = 109,
                meshHandle = centralBaseMeshHandle,
                x          = DraftCombat.CENTRAL_TURRET_X,
                y          = shipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                z          = DraftCombat.PLATFORM_TOP_Z + 0.003f,
                rotationX  = -1.5707964f,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    DraftCombat.CENTRAL_TURRET_X,
                    pShipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                    DraftCombat.PLATFORM_TOP_Z + 0.003f,
                    rotX = -1.5707964f,
                ),
            ),
            // Central tower — yaw around world Z. Z = base top + 5 mm
            // anti-Z-fight nudge so the tower's bottom face isn't
            // coplanar with the base's top face (would cause the
            // shimmering "smoke" artifact the user reported).
            SceneObject(
                id         = 119,
                meshHandle = centralTowerMeshHandle,
                x          = DraftCombat.CENTRAL_TURRET_X,
                y          = shipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                z          = DraftCombat.PLATFORM_TOP_Z + TURRET_BASE_Y_HEIGHT + 0.005f,
                rotationX  = -1.5707964f,
                rotationZ  = centralTurretAngle,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    DraftCombat.CENTRAL_TURRET_X,
                    pShipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                    DraftCombat.PLATFORM_TOP_Z + TURRET_BASE_Y_HEIGHT + 0.005f,
                    rotX = -1.5707964f, rotZ = pCentralAngle,
                ),
            ),
            // Central cannon — sits on tower top with the same 5 mm
            // nudge against the tower's top face.
            SceneObject(
                id         = 129,
                meshHandle = centralCannonMeshHandle,
                x          = DraftCombat.CENTRAL_TURRET_X,
                y          = shipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                z          = DraftCombat.PLATFORM_TOP_Z + TURRET_BASE_Y_HEIGHT + TURRET_TOWER_Y_HEIGHT + TURRET_CANNON_HALF_THICK + 0.015f,
                rotationX  = -1.5707964f,
                rotationZ  = centralTurretAngle,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    DraftCombat.CENTRAL_TURRET_X,
                    pShipPosY + DraftCombat.CENTRAL_TURRET_Y_OFFSET,
                    DraftCombat.PLATFORM_TOP_Z + TURRET_BASE_Y_HEIGHT + TURRET_TOWER_Y_HEIGHT + TURRET_CANNON_HALF_THICK + 0.015f,
                    rotX = -1.5707964f, rotZ = pCentralAngle,
                ),
            ),
            // Side turrets — .glb-loaded Body + Cannon. Authored in standard
            // gltf convention (+Y up, -Z forward), so Rx(+π/2) maps model
            // Y-up → world Z-up. Body origin = centre of base bottom, sits
            // exactly on PLATFORM_TOP_Z. Cannon origin = yaw pivot at
            // amburazura height; rotationZ takes the targeting angle.
            // No coplanar seams inside either model → no nudges, no Z-fight.
            // Y offset −0.9 (further back than central) so the trio stagger
            // as front-centre + back-flanks instead of clumping in one line.
            SceneObject(
                id         = 110,
                meshHandle = sideBodyGltfMeshHandle,
                x          = turretXs[0], y = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET, z = DraftCombat.PLATFORM_TOP_Z,
                rotationX  = PI_OVER_2,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    turretXs[0], pShipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET, DraftCombat.PLATFORM_TOP_Z,
                    rotX = PI_OVER_2,
                ),
            ),
            SceneObject(
                id         = 130,
                meshHandle = sideCannonGltfMeshHandle,
                x          = turretXs[0],
                y          = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET,
                z          = DraftCombat.SIDE_CANNON_GLTF_PIVOT_Z,
                rotationX  = PI_OVER_2,
                rotationZ  = sideTurretAngles[0],
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    turretXs[0],
                    pShipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET,
                    DraftCombat.SIDE_CANNON_GLTF_PIVOT_Z,
                    rotX = PI_OVER_2, rotZ = pSide0,
                ),
            ),
            SceneObject(
                id         = 111,
                meshHandle = sideBodyGltfMeshHandle,
                x          = turretXs[1], y = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET, z = DraftCombat.PLATFORM_TOP_Z,
                rotationX  = PI_OVER_2,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    turretXs[1], pShipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET, DraftCombat.PLATFORM_TOP_Z,
                    rotX = PI_OVER_2,
                ),
            ),
            SceneObject(
                id         = 131,
                meshHandle = sideCannonGltfMeshHandle,
                x          = turretXs[1],
                y          = shipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET,
                z          = DraftCombat.SIDE_CANNON_GLTF_PIVOT_Z,
                rotationX  = PI_OVER_2,
                rotationZ  = sideTurretAngles[1],
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    turretXs[1],
                    pShipPosY + DraftCombat.SIDE_TURRET_Y_OFFSET,
                    DraftCombat.SIDE_CANNON_GLTF_PIVOT_Z,
                    rotX = PI_OVER_2, rotZ = pSide1,
                ),
            ),
            // Laser installation — small dome amidships, just starboard of
            // the centerline between the side turrets. Static.
            SceneObject(
                id         = 131,
                meshHandle = laserInstallMeshHandle,
                x          = DraftCombat.LASER_INSTALL_X,
                y          = shipPosY - 0.02f,
                z          = DraftCombat.LASER_INSTALL_Z,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    DraftCombat.LASER_INSTALL_X, pShipPosY - 0.02f, DraftCombat.LASER_INSTALL_Z,
                ),
            ),
            // Rocket silo — port mirror of the laser dome on the same
            // amidships row. Rockets emerge from its launch opening.
            SceneObject(
                id         = 132,
                meshHandle = rocketSiloMeshHandle,
                x          = DraftCombat.ROCKET_SILO_X,
                y          = shipPosY - 0.02f,
                z          = DraftCombat.ROCKET_SILO_Z,
                scale      = 1f,
                prevModelMatrix = shipAttachedPrev(
                    DraftCombat.ROCKET_SILO_X, pShipPosY - 0.02f, DraftCombat.ROCKET_SILO_Z,
                ),
            ),
        ) + asteroids.mapIndexed { i, a ->
            // Per-asteroid mesh chosen at spawn (5 distinct .glbs across 5 types
            // + grey variant pool). Roughly unit bbox; scale by `half` so FAST
            // asteroids look small and HEAVY ones look chunky.
            //
            // 3D-pivot Phase 2/3: SceneObject.id uses ASTEROID_PICK_ID_BASE +
            // a.id.toInt() (stable across frames) instead of the
            // list-index-based 200+i. The engine's pickable buffer
            // returns this id when the player taps an asteroid; we
            // decode the asteroid.id back from it on the touch side.
            //
            // E10.3 — prev_model from prevZ/prevY/prevRotation cached at
            // the top of the asteroid movement step.
            val sceneId = ASTEROID_PICK_ID_BASE + a.id.toInt()
            val prev = SceneObject(
                id         = sceneId,
                meshHandle = 0L,
                x          = a.xPos, y = a.prevY, z = a.prevZ,
                rotationZ  = a.prevRotation,
                scale      = a.half,
            ).modelMatrix()
            SceneObject(
                id              = sceneId,
                meshHandle      = if (a.meshHandle != 0L) a.meshHandle else asteroidMeshGrey1,
                x               = a.xPos, y = a.yPos, z = a.zPos,
                rotationZ       = a.rotation,
                scale           = a.half,
                prevModelMatrix = prev,
            )
        } + effects.filterIsInstance<Projectile>().mapIndexed { i, b ->
            // Projectile orientation in 3D — full Euler angles so the
            // bullet's long axis points along the actual velocity
            // vector, not just its horizontal projection. Two model
            // conventions, two formulas:
            //
            //   +Z-forward (procedural rocket, modelYawOffset == 0):
            //     decompose with Rz · Ry · Rx applied to (0,0,1).
            //     yaw  Ry = atan2(vx, vz)              — horizontal heading
            //     pitch Rx = -atan2(vy, sqrt(vx²+vz²)) — nose up/down
            //
            //   +X-forward (legacy .glb bullets, modelYawOffset == -π/2):
            //     Rx around world-X is a no-op for the +X axis, so
            //     can't use it for pitch. Instead decompose into yaw
            //     (Ry) + roll-as-pitch (Rz): rotate +X first to the
            //     correct (x, y) direction in the xy-plane via Rz,
            //     then rotate around Y to add the vz component.
            //     yaw  Ry = atan2(-vz, sqrt(vx²+vy²))
            //     roll Rz = atan2(vy, vx)
            //
            // E10.3 — prev_model reuses the current angles since
            // velocity is effectively constant tick-to-tick.
            val mesh   = if (b.meshHandle != 0L) b.meshHandle else quadMeshHandle
            val bScale = b.modelScale
            val rotX: Float; val rotY: Float; val rotZ: Float
            if (b.modelYawOffset == 0f) {
                val horiz = kotlin.math.sqrt(b.vx * b.vx + b.vz * b.vz)
                rotX = -kotlin.math.atan2(b.vy, horiz)
                rotY = kotlin.math.atan2(b.vx, b.vz)
                rotZ = 0f
            } else {
                val xyMag = kotlin.math.sqrt(b.vx * b.vx + b.vy * b.vy)
                rotX = 0f
                rotY = kotlin.math.atan2(-b.vz, xyMag)
                rotZ = kotlin.math.atan2(b.vy, b.vx)
            }
            val prev   = SceneObject(
                id         = 300 + i,
                meshHandle = 0L,
                x          = b.prevX, y = b.prevY, z = b.prevZ,
                rotationX  = rotX,
                rotationY  = rotY,
                rotationZ  = rotZ,
                scale      = bScale,
            ).modelMatrix()
            SceneObject(
                id              = 300 + i,
                meshHandle      = mesh,
                x               = b.x, y = b.y, z = b.z,
                rotationX       = rotX,
                rotationY       = rotY,
                rotationZ       = rotZ,
                scale           = bScale,
                prevModelMatrix = prev,
            )
        } + drones.mapIndexed { i, d ->
            // E19 — render each drone as an oriented opaque mesh. The g3
            // ship.gltf is authored with +X = nose, ±Y = wings, ±Z =
            // top/bottom of fuselage. Under g3's top-down camera that
            // reads correctly via rotationZ = heading; in style3 (camera
            // behind-and-above the ship, world Y = depth, world Z =
            // vertical screen) we need TWO rotations baked into the model
            // matrix:
            //   - rotationX = +π/2  rolls the model around its own +X
            //                       axis (the nose) so the wings move
            //                       from world ±Y (into the screen) to
            //                       world ±X (horizontal across screen).
            //                       Without this the fighter reads as a
            //                       thin vertical stick — wings edge-on.
            //   - rotationY = heading - π/2  yaws the post-roll model
            //                       so the +X nose aligns with the
            //                       drone's flight direction.
            // (Matrix order in Scene.kt is T·Rz·Ry·Rx, so Rx is applied
            // first in model space — pure roll — and Ry follows.)
            SceneObject(
                id         = 600 + i,
                meshHandle = droneMeshHandle,
                x          = d.x, y = d.y, z = d.z,
                rotationX  = DraftCombat.DRONE_MESH_PITCH_OFFSET,
                rotationY  = d.heading + DraftCombat.DRONE_MESH_YAW_OFFSET,
                scale      = DraftCombat.DRONE_MESH_SCALE,
            )
        } + buildHpBars(asteroids, quadHpBgHandle, quadHpFgHandle)

        // Flash VFX: muzzle flash, bullet trails, asteroid hit, AoE rings, ENERGY-buff
        // pickup. Routed through the additive plasma pipeline (E2.1) so they read as
        // soft circular glows that brighten what's behind them — instead of square
        // yellow placeholders sitting on the dark background. E5.1 — per-flash tint
        // forwarded to the plasma fragment branch via BillboardDraw → drawPlasmaBillboard.
        // Enemy bolts — render as small additive plasma billboards
        // (warm-red glow) so they read as energy shots rather than metal
        // slugs. Each bolt is one moving billboard; no mesh trail yet.
        val enemyBoltBillboards = effects.filterIsInstance<com.example.asteroidoutpost.game.combat.EnemyBolt>().map { b ->
            BillboardDraw(
                quadFlashHandle, b.x, b.y, b.z, 0.18f,
                1.0f, 0.45f, 0.30f, 1.0f,
                scaleV = 0.18f, rotation = 0f, lightningSeed = 0f,
            )
        }

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
            BillboardDraw(mesh, f.x, f.y, f.z, s, f.tintR, f.tintG, f.tintB, f.tintA,
                          scaleV = sV,
                          rotation = f.rotation, lightningSeed = f.lightningSeed)
        }

        // E20 — shield goes through the dedicated forcefield pipeline
        // (built below in forceFields). Translucent route is nebulae only.
        val translucent = nebulaeTranslucent

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
                    x          = fb.x, y = fb.y, z = fb.z,
                    scale      = prevS,
                ).modelMatrix()
                SceneObject(
                    id               = 800 + i,
                    meshHandle       = fireballMeshHandle,
                    x                = fb.x, y = fb.y, z = fb.z,
                    scale            = s,
                    tintR            = tintR, tintG = tintG, tintB = tintB, tintA = brightness,
                    additiveMaterial = EngineJni.ADDITIVE_FIRE,
                    prevModelMatrix  = prev,
                )
            }
        }

        // E20 — build force-field draw. Only emitted when shieldHp > 0.
        // Centred at ship origin; uniform radius scale. Impacts packed
        // into a 16-float array; up to 4 most-recent active impacts go
        // through, the rest get sentinel age = 1.0 so the shader's "skip
        // slot" branch ignores them.
        val forceFields = if (shieldHp > 0f && shieldHemisphereHandle != 0L) {
            val r = DraftCombat.SHIELD_HEMISPHERE_RADIUS
            val impactsBuf = FloatArray(16) { 0f }
            // Default all slots to "empty" sentinel (age = 1.0 in w).
            for (s in 0 until 4) impactsBuf[s * 4 + 3] = 1f
            val n = minOf(shieldImpacts.size, 4)
            val maxLife = DraftCombat.SHIELD_IMPACT_LIFE_SEC
            for (i in 0 until n) {
                val si = shieldImpacts[shieldImpacts.size - n + i]
                val o = i * 4
                impactsBuf[o]     = si.x
                impactsBuf[o + 1] = si.y
                impactsBuf[o + 2] = si.z
                impactsBuf[o + 3] = 1f - (si.life / maxLife).coerceIn(0f, 1f)
            }
            listOf(ForceFieldDraw(
                meshHandle = shieldHemisphereHandle,
                cx = 0f, cy = shipPosY, cz = DraftCombat.SHIELD_CENTER_Z,
                radius = r,
                impacts = impactsBuf,
            ))
        } else emptyList()

        // Stash current ship-attached state so next assemble() can build
        // prev_model matrices from it. Without this, velocity buffer for
        // hull/turrets/dome/silo reads as ≈ shipPosY (huge) once the ship
        // starts cruising, and motion-blur dilation smears them into a
        // visibly trembling shimmer.
        prevShipPosY = shipPosY
        prevCentralTurretAngle = centralTurretAngle
        prevSideTurretAnglesArr[0] = sideTurretAngles[0]
        prevSideTurretAnglesArr[1] = sideTurretAngles[1]
        hasPrevShipState = true

        return SceneFrame(
            scene              = opaque,
            plasmaBillboards   = flashBillboards + enemyBoltBillboards,
            translucentObjects = translucent,
            additiveObjects    = fireballAdditive,
            beams              = beams,
            particleBatches    = particleBatches,
            forceFields        = forceFields,
        )
    }

    /**
     * Build a model matrix for a ship-attached SceneObject's prev frame.
     * Reuses `SceneObject.modelMatrix()` to guarantee the exact same
     * composition (T·Rz·Ry·Rx) as the live SceneObject. Allocations are
     * cheap (one transient SceneObject + one FloatArray per ship part per
     * frame, ~10 ship parts).
     */
    private fun shipAttachedPrev(
        x: Float, y: Float, z: Float,
        rotX: Float = 0f, rotY: Float = 0f, rotZ: Float = 0f,
    ): FloatArray = SceneObject(
        id = 0, meshHandle = 0L,
        x = x, y = y, z = z,
        rotationX = rotX, rotationY = rotY, rotationZ = rotZ,
        scale = 1f,
    ).modelMatrix()

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
        if (a.hp <= 0) return@forEachIndexed
        val hasShield   = a.shieldHpMax > 0
        // Shielded targets (enemy ships) always show both bars so the
        // player can read structure-vs-shield split at a glance. Plain
        // asteroids keep legacy behaviour — HP bar hidden at full.
        val showHpBar     = hasShield || a.hp < a.maxHp
        val showShieldBar = hasShield
        if (!showHpBar && !showShieldBar) return@forEachIndexed

        val barCx     = a.xPos
        val barCy     = a.yPos
        val baseZ     = a.zPos + a.half + DraftCombat.HP_BAR_PADDING
        val barHalfW  = a.half * DraftCombat.HP_BAR_HALF_W_MUL

        // HP bar (green, original). Hidden if at max but a shield bar
        // is shown — we still draw the bg track to anchor the shield
        // bar above it visually.
        if (showHpBar) {
            val frac      = (a.hp.toFloat() / a.maxHp.toFloat()).coerceIn(0f, 1f)
            val fillHalfW = barHalfW * frac
            val fillCx    = barCx - barHalfW * (1f - frac)
            out.add(SceneObject(
                id         = 400 + i * 4,
                meshHandle = bgHandle,
                x          = barCx, y = barCy, z = baseZ,
                scaleX     = barHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
            ))
            out.add(SceneObject(
                id         = 401 + i * 4,
                meshHandle = fgHandle,
                x          = fillCx, y = barCy - 0.01f, z = baseZ,
                scaleX     = fillHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
            ))
        }

        // Shield bar (cyan) stacked above the HP bar. Offset by ~2.5×
        // bar thickness in Z so it doesn't overlap. Uses the same fg
        // quad — colour tint is implicit by mesh choice (cyan-tinted
        // mesh would be ideal; for prototype the green-tinted mesh
        // still reads as a different bar by position).
        if (showShieldBar) {
            val sFrac     = (a.shieldHp.toFloat() / a.shieldHpMax.toFloat()).coerceIn(0f, 1f)
            val sFillHalf = barHalfW * sFrac
            val sFillCx   = barCx - barHalfW * (1f - sFrac)
            val shieldZ   = baseZ + DraftCombat.HP_BAR_HALF_THICK * 2.5f
            out.add(SceneObject(
                id         = 402 + i * 4,
                meshHandle = bgHandle,
                x          = barCx, y = barCy, z = shieldZ,
                scaleX     = barHalfW,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
            ))
            out.add(SceneObject(
                id         = 403 + i * 4,
                meshHandle = fgHandle,
                x          = sFillCx, y = barCy - 0.01f, z = shieldZ,
                scaleX     = sFillHalf,
                scaleY     = 1f,
                scaleZ     = DraftCombat.HP_BAR_HALF_THICK,
                // Cyan tint baked into per-vertex colour via tint RGBA
                // overrides — engine's textured route consumes plasmaColor.
                tintR = 0.30f, tintG = 0.80f, tintB = 1.00f, tintA = 1.0f,
            ))
        }
    }
    return out
}

