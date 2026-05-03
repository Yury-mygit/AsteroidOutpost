package com.example.asteroidoutpost

import com.example.asteroidoutpost.ai.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Gameplay-owned simplified object geometry.
 *
 * Rendering meshes may be detailed or scaled oddly; flight, picking policy,
 * avoidance, and tactical logic should use these Kotlin-side shapes instead.
 */
sealed interface GameplayShape {
    /** Local-space radius that fully contains this simplified shape. */
    fun boundingRadius(): Float

    /** Local-space points used to build rectangular screen-space UI markers. */
    fun framePoints(): List<Vec2>
}

data class CircleShape(val radius: Float) : GameplayShape {
    override fun boundingRadius(): Float = radius

    override fun framePoints(): List<Vec2> {
        val segments = 20
        return List(segments) { index ->
            val angle = (index.toFloat() / segments.toFloat()) * 2f * PI.toFloat()
            Vec2(cos(angle) * radius, sin(angle) * radius)
        }
    }
}

data class PolygonShape(val points: List<Vec2>) : GameplayShape {
    override fun boundingRadius(): Float =
        points.fold(0f) { acc, point -> max(acc, point.length()) }

    override fun framePoints(): List<Vec2> = points
}
