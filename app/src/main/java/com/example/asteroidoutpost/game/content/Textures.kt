package com.example.asteroidoutpost.game.content

import com.example.asteroidoutpost.EngineJni

/**
 * Procedural textures generated in Kotlin and uploaded as RGBA8 via
 * `engine.loadTextureRaw(...)`. No PNG round-trip — the maths runs once
 * at engine setup and the resulting bytes go straight into a `Texture`.
 *
 * Both textures are sized for the alpha-blend particle pool (E9 smoke +
 * debris); colour choices and falloff curves are Outpost design
 * decisions, not engine concerns.
 */

/**
 * E9 — procedural smoke puff texture (RGBA8, 64×64). Soft Gaussian-ish
 * radial falloff modulated by 2-octave value noise so the puff has
 * wispy structure instead of being a flat circle. Light gray RGB with
 * a subtle cool tint reads as exhaust/dust against the dark space
 * background. Transparent at the edges so multiple puffs overlap
 * cleanly through SRC_ALPHA blending.
 */
internal fun generateSmokeTexture(engine: EngineJni): Long {
    val W = 64; val H = 64
    val px = ByteArray(W * H * 4)
    val cx = W * 0.5f; val cy = H * 0.5f
    val maxR = W * 0.5f
    fun hash01(ix: Int, iy: Int): Float {
        val v = kotlin.math.sin(ix * 127.1f + iy * 311.7f) * 43758.547f
        return v - kotlin.math.floor(v)
    }
    fun valueNoise(x: Float, y: Float): Float {
        val ix = kotlin.math.floor(x).toInt()
        val iy = kotlin.math.floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)
        val a = hash01(ix,     iy)
        val b = hash01(ix + 1, iy)
        val c = hash01(ix,     iy + 1)
        val d = hash01(ix + 1, iy + 1)
        return (a * (1 - ux) + b * ux) * (1 - uy) +
               (c * (1 - ux) + d * ux) * uy
    }
    for (y in 0 until H) for (x in 0 until W) {
        val dx = (x + 0.5f) - cx
        val dy = (y + 0.5f) - cy
        val d = kotlin.math.sqrt(dx * dx + dy * dy) / maxR
        val falloff = (1f - d * d).coerceAtLeast(0f)
        val u = x.toFloat() / W * 6f
        val v = y.toFloat() / H * 6f
        val n = valueNoise(u, v) * 0.6f + valueNoise(u * 2.1f, v * 2.1f) * 0.4f
        val alphaF = (falloff * (0.5f + n * 0.7f)).coerceIn(0f, 1f)
        val gray = (0.55f + n * 0.20f).coerceIn(0f, 1f)
        val off = (y * W + x) * 4
        px[off + 0] = (gray * 0.85f * 255f).toInt().toByte()
        px[off + 1] = (gray * 0.83f * 255f).toInt().toByte()
        px[off + 2] = (gray * 0.78f * 255f).toInt().toByte()
        px[off + 3] = (alphaF * 255f).toInt().coerceIn(0, 255).toByte()
    }
    return engine.loadTextureRaw(px, W, H)
}

/**
 * E9 — procedural asteroid-chunk debris texture (RGBA8, 64×64). An
 * irregular polygonal silhouette (radius perturbed by two sine
 * harmonics so it reads as a bumpy rock instead of a circle), warm
 * gray fill with a top-left light gradient (matches the engine's
 * primary light direction in `triangle.frag`). Transparent outside
 * with a 1-pixel AA edge so chunks blend cleanly when overlapping.
 */
internal fun generateDebrisTexture(engine: EngineJni): Long {
    val W = 64; val H = 64
    val px = ByteArray(W * H * 4)
    val cx = W * 0.5f; val cy = H * 0.5f
    fun radiusAtAngle(theta: Float): Float {
        val base = W * 0.42f
        val warp = kotlin.math.sin(theta * 5f) * 0.10f +
                   kotlin.math.sin(theta * 7f + 1.3f) * 0.07f
        return base * (1f + warp)
    }
    for (y in 0 until H) for (x in 0 until W) {
        val dx = (x + 0.5f) - cx
        val dy = (y + 0.5f) - cy
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        val theta = kotlin.math.atan2(dy, dx)
        val rAtAngle = radiusAtAngle(theta)
        val off = (y * W + x) * 4
        // Top-left light: brighter when (dx, dy) is small.
        val light = (((-dx / rAtAngle) * 0.3f + (-dy / rAtAngle) * 0.3f + 0.6f)).coerceIn(0.35f, 1f)
        val r = (0.55f * light * 255f).toInt().coerceIn(0, 255).toByte()
        val g = (0.48f * light * 255f).toInt().coerceIn(0, 255).toByte()
        val b = (0.43f * light * 255f).toInt().coerceIn(0, 255).toByte()
        when {
            d < rAtAngle - 1f -> {
                px[off + 0] = r; px[off + 1] = g; px[off + 2] = b; px[off + 3] = 255.toByte()
            }
            d < rAtAngle + 1f -> {
                val t = ((rAtAngle + 1f - d) * 0.5f).coerceIn(0f, 1f)
                px[off + 0] = r; px[off + 1] = g; px[off + 2] = b
                px[off + 3] = (t * 255f).toInt().toByte()
            }
            else -> {
                px[off + 0] = 0; px[off + 1] = 0; px[off + 2] = 0; px[off + 3] = 0
            }
        }
    }
    return engine.loadTextureRaw(px, W, H)
}
