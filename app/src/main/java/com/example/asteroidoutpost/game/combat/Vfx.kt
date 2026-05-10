package com.example.asteroidoutpost.game.combat

/**
 * Short-lived plasma billboard event — muzzle flash, hit flash, asteroid
 * death sparkle, shield absorb, etc. Spawned by VFX helpers, ticked by the
 * mission loop, drained when `life <= 0`.
 *
 * Default values match a small per-asteroid-death flash; bigger events
 * (AoE rings, railgun core / bolts, muzzle cones, laser bolts) override
 * the relevant fields.
 */
internal data class Flash(
    val x: Float, val z: Float,
    // 3D-pivot Phase 2/3: optional Y depth so flashes spawned at the
    // asteroid's actual position (mid-flight hits, AoE explosions) sit
    // on the asteroid, not on the deck plane. Default 0 keeps muzzle /
    // shield-recharge / death-flash callers (which spawn at Y=0)
    // unchanged.
    val y: Float = 0f,
    var life: Float, val maxLife: Float,
    // Peak half-size at flash midpoint. Default = small per-asteroid death
    // flash; AoE impacts spawn larger flashes sized to the explosion radius.
    val halfMax: Float = DraftCombat.FLASH_HALF,
    // M8.6 — vertical (screen-up) half-size for non-uniform plasma
    // billboards. Default = halfMax (uniform — all existing flashes
    // unchanged). Laser bolts use halfMax = segment-length/2 and a
    // small halfMaxV for the thin streak look.
    val halfMaxV: Float = halfMax,
    // E5.1 — per-flash tint applied inside the plasma fragment branch.
    // Default white preserves the E4 warm-flame look; non-white recolours
    // by event (cyan ENERGY pickup, blue shield absorb, orange-red AoE).
    val tintR: Float = 1f, val tintG: Float = 1f, val tintB: Float = 1f, val tintA: Float = 1f,
    // E11 — optional non-quad mesh for directional flashes (muzzle cones).
    // 0 = engine fallback to quadFlashHandle in buildScene mapping.
    val meshHandle: Long = 0L,
    // E11 — local Y-axis rotation in radians, applied before billboard
    // alignment. 0 keeps the plasma quad axis-aligned (legacy round flash);
    // muzzle cones set this to atan2(dirX, dirZ) so the wedge points along
    // its world direction.
    val rotation: Float = 0f,
    // E12 — per-bolt seed for the lightning sub-shader. >0 routes the
    // flash through the electric-arc fragment branch (used by the
    // railgun muzzle stack); 0 keeps the legacy plasma heat-ramp flash.
    val lightningSeed: Float = 0f,
)

/**
 * E7.1 — 3D fireball explosion. Spawned by AoE-class events (heavy cannon
 * splash, EXPLOSIVE asteroid death) instead of a flat plasma billboard.
 * Renders as a Y-axis-aligned UV-sphere through the additive pipeline
 * with the fire-material shader branch (`abs(vNormal.y)` Fresnel + heat
 * ramp + FBM turbulence). `baseRadius` matches the AoE damage radius.
 *
 * The scene generator drives three curves over life (t = age/maxLife):
 *  - scale: ease-out quadratic (fast start, asymptotic at end)
 *  - colour: lerp FIREBALL_TINT_START → FIREBALL_TINT_END
 *  - brightness: sqrt(1-t) (holds longer initially so the colour shift
 *    stays visible as the ball cools)
 *
 * `intensity` is a per-event volume knob: future callers (smaller pops,
 * bigger climactic blasts) can pass <1 or >1 to scale the whole curve
 * without touching the constants.
 */
internal data class Fireball(
    val x: Float, val z: Float,
    // 3D-pivot Phase 2/3 — see Flash.y above for the same rationale.
    val y: Float = 0f,
    var life: Float, val maxLife: Float,
    val baseRadius: Float,
    val intensity: Float = 1f,
    // E10.3 — previous-frame `life` value. Fireballs don't translate but
    // their scale curve advances each tick (ease-out quad on age = 1 -
    // life/maxLife), so the rendered model matrix grows between frames.
    // Snapshotting prevLife at end-of-tick lets buildScene reconstruct
    // last frame's scale and feed motion blur a real prev_model.
    var prevLife: Float = life,
)

/**
 * E9 — particle. Lives in Kotlin (matches "Kotlin owns scene"
 * architecture); ticked here, packed into a FloatArray once per frame
 * and shipped to the engine in one batched JNI call. `mode` chooses
 * pipeline (additive sparks vs alpha-textured smoke/debris) and
 * picks the texture forwarded to the draw call.
 *
 * Shape (rotation, deformation) is procedural in shaders for additive
 * mode and texture-driven for alpha mode — the runtime only owns
 * position, velocity, age, size, colour.
 */
internal data class Particle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var age: Float, val life: Float,
    val size: Float,
    val r: Float, val g: Float, val b: Float, val a: Float,
    // Optional drag-style velocity damping: vx *= (1 - dragPerSec * dt).
    // 0 = no damping (sparks fly straight). Used for debris that
    // settles and smoke that slows.
    val drag: Float = 0f,
    // Optional vertical (Z) gravity in screen-space — positive pulls
    // particles "down" (toward platform). Sparks drift, debris falls.
    val gravity: Float = 0f,
)
