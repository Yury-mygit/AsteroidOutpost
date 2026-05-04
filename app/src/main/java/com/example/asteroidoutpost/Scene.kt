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

private const val ENEMY_SELECTED_EXTRA_PADDING = 0.035f

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
    val gameplayShape: GameplayShape = CircleShape(pickRadius),
    val frameZMin: Float = 0f,
    val frameZMax: Float = 0f,
    val orbitRadiusMultiplier: Float = 2f,
    val orbitMargin: Float = 0f,
    val isEnemy: Boolean = false,
    // E3.1 — for translucent draws, picks a fragment-shader branch:
    // 0 = plain, 1 = nebula (FBM), 2 = hex grid. Ignored on opaque routes.
    val material: Int = 0,
) {
    fun orbitRadius(): Float =
        gameplayShape.boundingRadius() * scale * orbitRadiusMultiplier + orbitMargin

    fun framePointArray(): FloatArray {
        val points = gameplayShape.framePoints()
        val zValues = if (kotlin.math.abs(frameZMax - frameZMin) > 1e-4f) {
            floatArrayOf(frameZMin, frameZMax)
        } else {
            floatArrayOf(frameZMin)
        }
        val result = FloatArray(points.size * zValues.size * 3)
        var out = 0
        for (z in zValues) {
            for (point in points) {
                result[out++] = point.x
                result[out++] = point.y
                result[out++] = z
            }
        }
        return result
    }

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

data class BillboardDraw(
    val meshHandle: Long,
    val x: Float, val y: Float, val z: Float,
    val scale: Float
)

/**
 * Submit the scene to the engine for rendering.
 * Call once per frame from the render loop.
 */
private val ALLIED_FRAME_TINT  = floatArrayOf(0f, 0f, 0f, 0f)          // vertex green
private val ENEMY_FRAME_TINT   = floatArrayOf(1.0f, 0.38f, 0.34f, 1.0f) // red override

fun submitScene(
    engine: EngineJni,
    objects: List<SceneObject>,
    highlightMeshes: HighlightMeshes = HighlightMeshes(),
    billboards: List<BillboardDraw> = emptyList(),
    plasmaBillboards: List<BillboardDraw> = emptyList(),
    translucentObjects: List<SceneObject> = emptyList(),
) {
    engine.beginScene()
    for (obj in objects) {
        val modelMatrix = obj.modelMatrix()
        engine.drawPickableMesh(obj.meshHandle, obj.id, modelMatrix, obj.pickRadius * obj.scale)

        val frameHandle = highlightMeshes.handleFor(obj.highlightStyle, obj.isEnemy)
        if (frameHandle != 0L) {
            val tint = if (obj.isEnemy) ENEMY_FRAME_TINT else ALLIED_FRAME_TINT
            val lineWidth = if (obj.selected) 2.0f else 1.0f
            engine.drawGameplayFrameMesh(
                frameHandle, modelMatrix, obj.framePointArray(), obj.framePadding, lineWidth, tint
            )
        }
    }
    for (b in billboards) {
        engine.drawBillboardMesh(b.meshHandle, b.x, b.y, b.z, b.scale)
    }
    for (t in translucentObjects) {
        engine.drawTranslucentMesh(t.meshHandle, t.modelMatrix(), t.material)
    }
    for (p in plasmaBillboards) {
        engine.drawPlasmaBillboard(p.meshHandle, p.x, p.y, p.z, p.scale)
    }
    engine.endScene()
}
