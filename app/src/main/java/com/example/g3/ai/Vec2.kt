package com.example.g3.ai

import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin

data class Vec2(val x: Float, val y: Float) {

    operator fun plus(o: Vec2)   = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2)  = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s,   y * s)
    operator fun unaryMinus()    = Vec2(-x, -y)

    fun lengthSq() = x * x + y * y
    fun length()   = sqrt(lengthSq())

    fun normalize(): Vec2 {
        val len = length()
        return if (len > 1e-5f) Vec2(x / len, y / len) else ZERO
    }

    fun dot(o: Vec2) = x * o.x + y * o.y

    /** Rotate counter-clockwise by [angle] radians. */
    fun rotate(angle: Float): Vec2 {
        val c = cos(angle); val s = sin(angle)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** Clamp vector length to [max], preserving direction. */
    fun clampLength(max: Float): Vec2 {
        val len = length()
        return if (len > max) normalize() * max else this
    }

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}
