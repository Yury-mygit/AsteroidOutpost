package com.example.g3

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
    val scale: Float = 1f,
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
    val isEnemy: Boolean = false
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

    /** Column-major model matrix: translation * rotationZ * uniformScale */
    fun modelMatrix(scaleOverride: Float = scale): FloatArray {
        val cos = kotlin.math.cos(rotationZ)
        val sin = kotlin.math.sin(rotationZ)
        // Column-major 4x4:
        // col0: [cos*s, sin*s, 0, 0]
        // col1: [-sin*s, cos*s, 0, 0]
        // col2: [0, 0, s, 0]
        // col3: [x, y, z, 1]
        val s = scaleOverride
        return floatArrayOf(
            cos * s,  sin * s, 0f, 0f,
            -sin * s, cos * s, 0f, 0f,
            0f,       0f,      s,  0f,
            x,    y,  z, 1f
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
    plasmaBillboards: List<BillboardDraw> = emptyList()
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
    for (p in plasmaBillboards) {
        engine.drawPlasmaBillboard(p.meshHandle, p.x, p.y, p.z, p.scale)
    }
    engine.endScene()
}
