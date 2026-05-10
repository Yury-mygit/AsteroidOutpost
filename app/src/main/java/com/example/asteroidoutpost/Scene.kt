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
    /** Column-major model matrix: translation * Rz(rotationZ) * Ry(rotationY) * scale (per-axis). */
    fun modelMatrix(scaleOverride: Float = scale): FloatArray {
        val cz = kotlin.math.cos(rotationZ); val sz_ = kotlin.math.sin(rotationZ)
        val cy = kotlin.math.cos(rotationY); val sy_ = kotlin.math.sin(rotationY)
        val sx = if (scaleX.isNaN()) scaleOverride else scaleX
        val sy = if (scaleY.isNaN()) scaleOverride else scaleY
        val sz = if (scaleZ.isNaN()) scaleOverride else scaleZ
        // Rz * Ry =
        // [ cz*cy   -sz_   cz*sy_ ]
        // [ sz_*cy   cz    sz_*sy_]
        // [ -sy_    0     cy     ]
        return floatArrayOf(
            // col0 = Rz*Ry * (sx,0,0)
            cz * cy * sx,   sz_ * cy * sx,  -sy_ * sx,  0f,
            // col1 = Rz*Ry * (0,sy,0)
            -sz_ * sy,      cz * sy,        0f,         0f,
            // col2 = Rz*Ry * (0,0,sz)
            cz * sy_ * sz,  sz_ * sy_ * sz, cy * sz,    0f,
            // col3 = translation
            x,              y,              z,          1f
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
    for (p in plasmaBillboards) {
        engine.drawPlasmaBillboard(p.meshHandle, p.x, p.y, p.z, p.scale, p.scaleV,
                                   p.r, p.g, p.b, p.a, p.rotation, p.lightningSeed)
    }
    for (pb in particleBatches) {
        engine.drawParticles(pb.meshHandle, pb.textureHandle, pb.data, pb.count, pb.mode)
    }
    engine.endScene()
}
