package com.example.asteroidoutpost.game.combat

/**
 * Pure pool utilities for the E9 particle system. The mission tick advances
 * pools each frame via `tickParticles`, then packs them via `packParticles`
 * into the wire format the engine expects (8 floats per instance).
 *
 * Particle motion model:
 *  - Drag (per-second exponent) → velocity damps each frame.
 *  - Gravity → constant -Z acceleration so particles fall toward platform.
 *  - Age curve `sqrt(1-t)` for alpha fade — matches fireball brightness curve.
 */

/**
 * Advance every particle in `pool` by `dt`. Drag damps velocity, gravity
 * pulls along -Z (positive value = pulls particles "down" toward platform),
 * dead particles (age >= life) are removed in-place.
 */
internal fun tickParticles(pool: MutableList<Particle>, dt: Float) {
    if (pool.isEmpty()) return
    val it = pool.iterator()
    while (it.hasNext()) {
        val p = it.next()
        p.age += dt
        if (p.age >= p.life) { it.remove(); continue }
        // Drag (per-second) applied per-frame.
        val dragMul = (1f - p.drag * dt).coerceAtLeast(0f)
        p.vx *= dragMul
        p.vy *= dragMul
        p.vz *= dragMul
        // Gravity acts along -Z (pulling toward bottom of screen).
        p.vz -= p.gravity * dt
        p.x  += p.vx * dt
        p.y  += p.vy * dt
        p.z  += p.vz * dt
    }
}

/**
 * E9 — pack a particle pool into the FloatArray layout the engine expects:
 * 8 floats per particle (pos.xyz, size, rgba). Alpha is the stored
 * `r/g/b/a.a × age-fade` so the engine just multiplies it in. The fade
 * curve is `sqrt(1-t)` — same shape used for fireball brightness (E7.1
 * polish), keeps things in family.
 */
internal fun packParticles(pool: List<Particle>): FloatArray {
    if (pool.isEmpty()) return FloatArray(0)
    val out = FloatArray(pool.size * 8)
    var w = 0
    for (p in pool) {
        val u    = (1f - p.age / p.life).coerceIn(0f, 1f)
        val fade = kotlin.math.sqrt(u)
        out[w++] = p.x; out[w++] = p.y; out[w++] = p.z
        out[w++] = p.size
        out[w++] = p.r; out[w++] = p.g; out[w++] = p.b
        out[w++] = p.a * fade
    }
    return out
}
