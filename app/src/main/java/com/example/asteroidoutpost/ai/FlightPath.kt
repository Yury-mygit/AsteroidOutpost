package com.example.asteroidoutpost.ai

import kotlin.math.*

// ---------------------------------------------------------------------------
// Path segments
// ---------------------------------------------------------------------------

sealed class PathSegment {
    /** Arc length of this segment in world units. */
    abstract val length: Float

    /** World position at normalized parameter t ∈ [0, 1]. */
    abstract fun position(t: Float): Vec2

    /** Unit tangent (direction of travel) at t ∈ [0, 1]. */
    abstract fun tangent(t: Float): Vec2
}

/**
 * Straight line from [from] to [to].
 */
class LinearSegment(val from: Vec2, val to: Vec2) : PathSegment() {

    override val length: Float = (to - from).length()

    override fun position(t: Float) = from + (to - from) * t.coerceIn(0f, 1f)

    override fun tangent(t: Float): Vec2 =
        if (length > 1e-5f) (to - from).normalize() else Vec2.ZERO
}

/**
 * Circular arc around [center] with [radius].
 *
 * [startAngle]  — entry angle in radians (standard math: 0 = +X, π/2 = +Y).
 * [sweepAngle]  — total angular span in radians.
 *                 Positive = counter-clockwise, negative = clockwise.
 */
class CircularSegment(
    val center:     Vec2,
    val radius:     Float,
    val startAngle: Float,
    val sweepAngle: Float
) : PathSegment() {

    override val length: Float = abs(sweepAngle) * radius

    override fun position(t: Float): Vec2 {
        val angle = startAngle + sweepAngle * t.coerceIn(0f, 1f)
        return center + Vec2(cos(angle), sin(angle)) * radius
    }

    override fun tangent(t: Float): Vec2 {
        val angle = startAngle + sweepAngle * t.coerceIn(0f, 1f)
        // Derivative of position w.r.t. angle, normalised, scaled by sign of sweep
        return if (sweepAngle >= 0f)
            Vec2(-sin(angle),  cos(angle))   // CCW
        else
            Vec2( sin(angle), -cos(angle))   // CW
    }
}

// ---------------------------------------------------------------------------
// Composite path
// ---------------------------------------------------------------------------

/**
 * A sequence of [PathSegment]s treated as one continuous path.
 *
 * Navigation is done by total distance traveled rather than per-segment t,
 * so the caller only needs to track one float and advance it each tick:
 *
 *     distanceTraveled += speed * dt
 *     val pos = path.positionAt(distanceTraveled)
 *     val dir = path.tangentAt(distanceTraveled)
 */
class FlightPath(val segments: List<PathSegment>) {

    val totalLength: Float = segments.sumOf { it.length.toDouble() }.toFloat()

    val isFinished: Boolean get() = totalLength < 1e-5f

    /** World position at [dist] world units from the path start. */
    fun positionAt(dist: Float): Vec2 = resolve(dist) { seg, t -> seg.position(t) }

    /** Unit tangent at [dist] world units from the path start. */
    fun tangentAt(dist: Float): Vec2  = resolve(dist) { seg, t -> seg.tangent(t) }

    // ---------------------------------------------------------------------------
    private inline fun <R> resolve(dist: Float, block: (PathSegment, Float) -> R): R {
        var remaining = dist.coerceIn(0f, totalLength)
        for (seg in segments) {
            if (remaining <= seg.length || seg === segments.last()) {
                val t = if (seg.length > 1e-5f) remaining / seg.length else 1f
                return block(seg, t)
            }
            remaining -= seg.length
        }
        // Fallback: end of last segment
        val last = segments.last()
        return block(last, 1f)
    }

    companion object {
        /**
         * Builds a closed fly-around path from [start] around a circle centered at [target].
         *
         * The path is:
         * 1. straight tangent from start to the right-side tangent point,
         * 2. circular arc around the far side of the target circle,
         * 3. straight tangent back to start from the left-side tangent point.
         */
        fun tangentFlyAround(target: Vec2, radius: Float, start: Vec2): FlightPath {
            val safeRadius = radius.coerceAtLeast(0.5f)
            val fromCenter = start - target
            val distance = fromCenter.length()
            if (distance <= safeRadius + 1e-3f) {
                val fallbackStart = target + directionOrFallback(fromCenter, Vec2(0f, -1f)) * (safeRadius * 2.5f)
                return tangentFlyAround(target, safeRadius, fallbackStart)
            }

            val baseAngle = atan2(fromCenter.y, fromCenter.x)
            val tangentOffset = acos((safeRadius / distance).coerceIn(-1f, 1f))
            val tangentA = pointOnCircle(target, safeRadius, baseAngle + tangentOffset)
            val tangentB = pointOnCircle(target, safeRadius, baseAngle - tangentOffset)

            val approachDir = (target - start).normalize()
            val aSide = cross(approachDir, tangentA - start)
            val rightTangent = if (aSide < 0f) tangentA else tangentB
            val leftTangent = if (aSide < 0f) tangentB else tangentA

            val rightAngle = atan2(rightTangent.y - target.y, rightTangent.x - target.x)
            val leftAngle = atan2(leftTangent.y - target.y, leftTangent.x - target.x)
            val entryDir = (rightTangent - start).normalize()
            val ccwTangent = Vec2(-sin(rightAngle), cos(rightAngle))
            val sweep = if (ccwTangent.dot(entryDir) >= 0f) {
                positiveSweep(rightAngle, leftAngle)
            } else {
                negativeSweep(rightAngle, leftAngle)
            }

            return FlightPath(
                listOf(
                    LinearSegment(start, rightTangent),
                    CircularSegment(target, safeRadius, rightAngle, sweep),
                    LinearSegment(leftTangent, start)
                )
            )
        }

        /**
         * Builds a [CircularSegment] whose entry tangent connects smoothly
         * from [approachDir] (unit vector pointing toward the station).
         *
         * The orbit starts at the point where the approach vector meets the
         * circle, and sweeps [sweepAngle] radians (positive = CCW).
         */
        fun circleAroundStation(
            station:     Vec2,
            radius:      Float,
            approachPos: Vec2,
            sweepAngle:  Float = 2f * PI.toFloat()
        ): CircularSegment {
            // Entry point: closest point on the circle to approachPos
            val toCenter = (station - approachPos)
            val entryDir = if (toCenter.length() > 1e-5f) toCenter.normalize() else Vec2(0f, -1f)
            val startAngle = atan2((-entryDir).y, (-entryDir).x)
            return CircularSegment(station, radius, startAngle, sweepAngle)
        }

        private fun pointOnCircle(center: Vec2, radius: Float, angle: Float): Vec2 =
            center + Vec2(cos(angle), sin(angle)) * radius

        private fun positiveSweep(from: Float, to: Float): Float {
            var sweep = to - from
            while (sweep < 0f) sweep += 2f * PI.toFloat()
            return sweep
        }

        private fun negativeSweep(from: Float, to: Float): Float {
            var sweep = to - from
            while (sweep > 0f) sweep -= 2f * PI.toFloat()
            return sweep
        }

        private fun cross(a: Vec2, b: Vec2): Float = a.x * b.y - a.y * b.x

        private fun directionOrFallback(direction: Vec2, fallback: Vec2): Vec2 =
            if (direction.lengthSq() > 1e-5f) direction.normalize() else fallback
    }
}
