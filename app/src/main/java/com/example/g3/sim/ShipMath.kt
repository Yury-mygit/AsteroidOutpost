package com.example.g3.sim

import com.example.g3.ai.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared conversion between scene-space directions and ship heading.
 *
 * SceneObject.modelMatrix() maps local +Y (ship nose) to world
 * (-sin(rotationZ), cos(rotationZ)), so heading must be derived with
 * atan2(-x, y), not atan2(x, y).
 */
object ShipMath {
    fun headingFromDirection(direction: Vec2): Float =
        atan2(-direction.x, direction.y)

    fun headingFromVelocity(velocity: Vec2): Float =
        headingFromDirection(velocity)

    fun forwardFromHeading(heading: Float): Vec2 =
        Vec2(-sin(heading), cos(heading))
}
