#version 450

// E20 — Force-field shield fragment stage. Two visual layers stacked:
//
//   1. Baseline rim glow (always on while shield HP > 0). Standard fresnel
//      term — bright at grazing angles (silhouette edge), invisible head-on.
//      Tuned VERY low (baseAlpha 0.08) so it reads as "barely there".
//
//   2. Impact bloom (per-impact, fades over SHIELD_IMPACT_LIFE_SEC). For
//      each of up to 4 active impacts, a Gaussian-falloff bright spot at
//      the impact's world position. Brightness ∝ (1 - age)^2, radius
//      grows slightly with age (the ripple expanding outward).
//
// Output is ADD-blended (ONE/ONE) — RGB summed onto framebuffer.
// View-space normal/pos comes in as varyings from the vert stage; the
// UBO descriptor (binding 0, set 0) is VERTEX-only — fragment cannot
// access it.

layout(location = 0) in vec3 vWorldPos;
layout(location = 1) in vec3 vViewNormal;
layout(location = 2) in vec3 vViewPos;

layout(push_constant) uniform PC {
    vec4 centerRadius;
    vec4 impacts[4];
} pc;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outVelocity;

void main() {
    outVelocity = vec2(0.0);

    // Camera in view space sits at origin → V = -normalize(viewPos).
    vec3 vN = normalize(vViewNormal);
    vec3 vV = normalize(-vViewPos);

    // Very faint baseline tint over the whole dome surface — gives the
    // player enough cue to perceive WHERE the shield is, without painting
    // a bright cyan halo across the view. Total peak intensity ~0.022
    // (cyan) — pixel value adds ~5 to a 255-level framebuffer at the
    // brightest fragment. Barely above noise.
    float fres = pow(max(0.0, 1.0 - dot(vN, vV)), 2.0);
    const vec3 baseColor = vec3(0.40, 0.70, 1.00);
    vec3 base = baseColor * (0.008 + 0.014 * fres);

    // Impact blooms. Loop over 4 slots; slots with age >= 1.0 are expired
    // and skipped (the CPU passes age = 1.0 as a sentinel for "no impact
    // in this slot"). At an impact the visible region is a Gaussian-
    // falloff disc on the sphere surface centred at the contact point.
    float bump = 0.0;
    const vec3 impactColor = vec3(0.70, 0.95, 1.00);
    for (int i = 0; i < 4; ++i) {
        float age = pc.impacts[i].w;
        if (age >= 1.0) continue;
        float d = distance(vWorldPos, pc.impacts[i].xyz);
        // Radius starts at ~half the shield radius then expands as the
        // ripple ages.
        float r = 0.55 + 0.40 * age;
        float t = d / r;
        // Smooth fade with (1-age)^1.5 so the peak holds slightly longer.
        float fade = pow(1.0 - age, 1.5);
        bump += exp(-t * t * 2.5) * fade;
    }

    // Additive output. Baseline + bloom; bloom multiplier picked so the
    // impact reads as a bright sci-fi flash even against bright sky.
    vec3 finalColor = base + impactColor * bump * 2.2;
    outColor = vec4(finalColor, 1.0);
}
