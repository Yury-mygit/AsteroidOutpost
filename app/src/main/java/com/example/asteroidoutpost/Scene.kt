package com.example.asteroidoutpost

enum class HighlightStyle { THIN, BOLD }

data class HighlightMeshes(
    val thin: Long = 0L,
    val bold: Long = 0L,
    val enemyThin: Long = 0L,
    val enemyBold: Long = 0L
) {
    fun handleFor(style: HighlightStyle, enemy: Boolean): Long =
        when (style) {
            HighlightStyle.THIN -> if (enemy && enemyThin != 0L) enemyThin else thin
            HighlightStyle.BOLD -> if (enemy && enemyBold != 0L) enemyBold else bold
        }
}

/**
 * Scene owned entirely by Kotlin.
 * Engine knows nothing about objects or selection state — it just draws what it's told.
 *
 * SceneObject: a mesh handle + a 4x4 column-major model matrix.
 * The matrix is recomputed from position/rotationZ/scale for convenience.
 */
data class SceneObject(
    val id: Int,
    val meshHandle: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val rotationZ: Float = 0f,
    // Rotation around world Y axis (depth). For draft side-view scenes, this
    // rotates a quad (lying in X-Z) within the screen plane.
    val rotationY: Float = 0f,
    // 3D-pivot Phase: rotation around world X axis. Lets a model
    // (e.g. bullet with +Z forward) pitch up/down independently of yaw,
    // so a projectile travelling with vy ≠ 0 actually points along its
    // velocity vector instead of staying flat in the X-Z plane.
    val rotationX: Float = 0f,
    val scale: Float = 1f,
    // Non-uniform scale overrides. When NaN, fall back to `scale` (uniform).
    val scaleX: Float = Float.NaN,
    val scaleY: Float = Float.NaN,
    val scaleZ: Float = Float.NaN,
    val selected: Boolean = false,
    val highlightStyle: HighlightStyle = HighlightStyle.THIN,
    val highlightScale: Float = 1f,
    val pickRadius: Float = 0.9f,
    val framePadding: Float = 0.18f,
    // E3.1 — for translucent draws, picks a fragment-shader branch:
    // 0 = plain, 1 = nebula (FBM), 2 = hex grid. Ignored on opaque routes.
    val material: Int = 0,
    // E7 — for additive draws, RGBA tint passed through pc.plasmaColor
    // (rgb = colour, a = brightness scalar). Default white = no tint.
    // Ignored on opaque/translucent routes (they don't read plasmaColor).
    val tintR: Float = 1f,
    val tintG: Float = 1f,
    val tintB: Float = 1f,
    val tintA: Float = 1f,
    // E7.1 — additive sub-material (ADDITIVE_PLAIN / ADDITIVE_FIRE in
    // EngineJni). Picks fragment-shader branch for additive draws only;
    // ignored on opaque/translucent routes.
    val additiveMaterial: Int = 0,
    // E8.4 — texture handle from `loadTexture` / `loadTextureRaw`. Only
    // consumed by the textured route; ignored elsewhere. 0 = no texture
    // (default white set 1 covers the binding requirement).
    val textureHandle: Long = 0L,
    // E10.3 — previous frame's model matrix for this object, used by the
    // vertex shader to compute screen-space velocity for motion blur.
    // null = static / no prev tracking — engine treats prev = current,
    // producing zero velocity (correct for stationary geometry, first
    // frame, and any draw whose gameplay caller hasn't been wired up
    // for tracking yet). Moving gameplay objects (asteroids, bullets,
    // fireballs) populate this from their cached prev-frame state.
    val prevModelMatrix: FloatArray? = null,
) {
    /**
     * Column-major model matrix:
     * `translation * Rz(rotationZ) * Ry(rotationY) * Rx(rotationX) * S(per-axis)`.
     *
     * Rotations apply to the model in order Rx → Ry → Rz (model space first).
     * For projectiles fired in 3D, that means: pitch the +Z-forward bullet
     * to face vy (Rx), then yaw to face vx/vz (Ry). Roll (Rz) is unused for
     * projectiles; asteroids use it for in-plane spin.
     */
    fun modelMatrix(scaleOverride: Float = scale): FloatArray {
        val cz = kotlin.math.cos(rotationZ); val sz_ = kotlin.math.sin(rotationZ)
        val cy = kotlin.math.cos(rotationY); val sy_ = kotlin.math.sin(rotationY)
        val cx = kotlin.math.cos(rotationX); val sx_ = kotlin.math.sin(rotationX)
        val sX = if (scaleX.isNaN()) scaleOverride else scaleX
        val sY = if (scaleY.isNaN()) scaleOverride else scaleY
        val sZ = if (scaleZ.isNaN()) scaleOverride else scaleZ
        // Rz · Ry · Rx (column-major). Derivation:
        //   Ry · Rx =
        //     [cy   sy_·sx_   sy_·cx ]
        //     [0    cx       -sx_    ]
        //     [-sy_ cy_·sx_   cy·cx  ]
        //   Rz · (Ry · Rx) — premultiply by Rz on the left.
        // Each `col*` below is the rotated unit basis × per-axis scale.
        // col0 = Rz · Ry · Rx · (sX, 0, 0)  (pitch doesn't affect X axis)
        val c00 = cz * cy        * sX
        val c01 = sz_ * cy       * sX
        val c02 = -sy_           * sX
        // col1 = Rz · Ry · Rx · (0, sY, 0)
        val c10 = (cz * sy_ * sx_ - sz_ * cx) * sY
        val c11 = (sz_ * sy_ * sx_ + cz * cx) * sY
        val c12 = cy * sx_                    * sY
        // col2 = Rz · Ry · Rx · (0, 0, sZ)
        val c20 = (cz * sy_ * cx + sz_ * sx_) * sZ
        val c21 = (sz_ * sy_ * cx - cz * sx_) * sZ
        val c22 = cy * cx                     * sZ
        return floatArrayOf(
            c00, c01, c02, 0f,
            c10, c11, c12, 0f,
            c20, c21, c22, 0f,
            x,   y,   z,   1f,
        )
    }
}

/**
 * E5.1 — `r,g,b,a` is a per-billboard tint applied inside the plasma fragment
 * branch (no effect on the regular `billboards` list, which still uses the
 * non-plasma billboard pipeline). Default white preserves the E4 warm-flame
 * heat-ramp; non-white tints recolour individual flash events.
 *
 * E5.2 — `scaleH, scaleV` are screen-horizontal and screen-vertical half-sizes
 * for plasma billboards; equal values give a square billboard, unequal values
 * stretch the quad (streak bullets, flat shockwaves). The legacy `scale`
 * field (forwarded to the non-plasma billboard pipeline) stays uniform.
 */
data class BillboardDraw(
    val meshHandle: Long,
    val x: Float, val y: Float, val z: Float,
    val scale: Float,
    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val a: Float = 1f,
    val scaleV: Float = scale,
    // E11 — local Y-axis rotation (radians) applied before the billboard
    // camera-align transform. 0 keeps the legacy round-flash axis alignment;
    // non-zero is used for directional plasma meshes (muzzle cones).
    val rotation: Float = 0f,
    // E12 — per-bolt seed for the lightning sub-shader. >0 opts into the
    // electric-arc fragment branch and decorrelates noise between
    // simultaneous bolts; 0 keeps the legacy plasma flash heat-ramp.
    val lightningSeed: Float = 0f,
)

/**
 * E14 — laser beam. Dedicated engine pipeline; no mesh handle required.
 * The engine expands a view-aligned quad on the GPU between (startX, startY,
 * startZ) and (endX, endY, endZ) with perpendicular thickness `width` in
 * world units. `(r, g, b, a)` is the per-beam tint with `a` as brightness.
 */
data class BeamDraw(
    val startX: Float, val startY: Float, val startZ: Float,
    val endX:   Float, val endY:   Float, val endZ:   Float,
    val width:  Float,
    val r: Float = 1f, val g: Float = 1f, val b: Float = 1f, val a: Float = 1f,
)

/**
 * E20 — force-field shield draw. The unit hemisphere mesh is placed by
 * `(cx, cy, cz)` (world centre) and uniformly scaled by `radius`.
 * `impacts` is a flat 16-float array packed as 4 × (worldX, worldY,
 * worldZ, age) where age ∈ [0, 1] normalised (age ≥ 1.0 = empty slot,
 * shader skips it). At most 4 active impacts visible; the assembler
 * picks which ones to send when the game has > 4 live impacts.
 */
data class ForceFieldDraw(
    val meshHandle: Long,
    val cx: Float, val cy: Float, val cz: Float, val radius: Float,
    val impacts:    FloatArray,
)

/**
 * E9 — packed particle batch ready for the engine. `data` is `count * 8`
 * floats per particle (pos.xyz, size, rgba), `mesh` is the unit-quad mesh,
 * `texture` is the sampler-bound texture (0 for additive sparks). `mode`
 * picks pipeline (PARTICLE_ADDITIVE or PARTICLE_ALPHA_TEXTURED on
 * EngineJni). One batch per logical particle pool per frame.
 */
data class ParticleBatchKt(
    val meshHandle:    Long,
    val textureHandle: Long,
    val data:          FloatArray,
    val count:         Int,
    val mode:          Int,
)

/**
 * Submit the scene to the engine for rendering.
 * Call once per frame from the render loop.
 */
fun submitScene(
    engine: EngineJni,
    objects: List<SceneObject>,
    highlightMeshes: HighlightMeshes = HighlightMeshes(),
    billboards: List<BillboardDraw> = emptyList(),
    plasmaBillboards: List<BillboardDraw> = emptyList(),
    translucentObjects: List<SceneObject> = emptyList(),
    additiveObjects: List<SceneObject> = emptyList(),
    texturedObjects: List<SceneObject> = emptyList(),
    particleBatches: List<ParticleBatchKt> = emptyList(),
    beams: List<BeamDraw> = emptyList(),
    forceFields: List<ForceFieldDraw> = emptyList(),
) {
    engine.beginScene()
    for (obj in objects) {
        val modelMatrix = obj.modelMatrix()
        engine.drawPickableMesh(obj.meshHandle, obj.id, modelMatrix, obj.pickRadius * obj.scale,
                                obj.prevModelMatrix)
    }
    for (b in billboards) {
        engine.drawBillboardMesh(b.meshHandle, b.x, b.y, b.z, b.scale)
    }
    for (t in translucentObjects) {
        engine.drawTranslucentMesh(t.meshHandle, t.modelMatrix(), t.material, t.prevModelMatrix)
    }
    for (a in additiveObjects) {
        engine.drawAdditiveMesh(a.meshHandle, a.modelMatrix(),
                                a.tintR, a.tintG, a.tintB, a.tintA,
                                a.additiveMaterial, a.prevModelMatrix)
    }
    for (t in texturedObjects) {
        engine.drawTexturedMesh(t.meshHandle, t.textureHandle, t.modelMatrix(),
                                t.tintR, t.tintG, t.tintB, t.tintA,
                                t.prevModelMatrix)
    }
    for (beam in beams) {
        engine.drawLaserBeam(beam.startX, beam.startY, beam.startZ,
                             beam.endX,   beam.endY,   beam.endZ,
                             beam.width,
                             beam.r, beam.g, beam.b, beam.a)
    }
    for (ff in forceFields) {
        engine.drawForceField(ff.meshHandle, ff.cx, ff.cy, ff.cz, ff.radius, ff.impacts)
    }
    for (p in plasmaBillboards) {
        engine.drawPlasmaBillboard(p.meshHandle, p.x, p.y, p.z, p.scale, p.scaleV,
                                   p.r, p.g, p.b, p.a, p.rotation, p.lightningSeed)
    }
    for (pb in particleBatches) {
        engine.drawParticles(pb.meshHandle, pb.textureHandle, pb.data, pb.count, pb.mode)
    }
    engine.endScene()
}
