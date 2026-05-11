package com.example.asteroidoutpost.game.combat

/**
 * E20 — one active force-field impact. Spawned when an asteroid intersects
 * the shield's hemisphere; carries the contact point (world space) and a
 * countdown timer that the forcefield fragment shader uses to drive its
 * local-bump intensity.
 *
 * The impact world-position should be the asteroid's centre at the moment
 * the dist-to-ship-centre crossed the shield radius — a small inaccuracy
 * (we capture it the first frame the centre is INSIDE, not the exact
 * crossing) but visually invisible since the bump is a soft Gaussian.
 *
 * `life` ticks DOWN from [DraftCombat.SHIELD_IMPACT_LIFE_SEC]. The
 * normalised age (1 - life / max) is what the shader actually uses.
 */
internal class ShieldImpact(
    var x: Float, var y: Float, var z: Float,
    var life: Float,
)
