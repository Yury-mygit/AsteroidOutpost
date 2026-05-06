#version 450

// E9 — particle fragment shader. Two render variants share this code:
//   • additive sparks (ONE/ONE blend, depth-test off): heat-ramp from
//     warm core to dim edge × per-instance vColor + soft radial fade.
//     Ignores uTex (binds the engine's default-white anyway).
//   • alpha-textured (SRC_ALPHA / ONE_MINUS_SRC_ALPHA, depth read-only):
//     samples uTex at vUV, multiplies by per-instance vColor (alpha is
//     the per-particle fade), no heat-ramp.
//
// Variant is picked via `pc.textureMode`: 0 → additive shading branch,
// >= 0.5 → textured branch. The flag was originally introduced for E8.3
// drawTexturedMesh and we reuse it here because semantics line up exactly
// (sample uTex when set, otherwise procedural). Doing it this way avoids
// adding yet another push-constant float just for particles.

layout(location = 0) in vec4 vColor;
layout(location = 1) in vec3 vNormal;     // unused
layout(location = 2) in vec3 vWorldPos;
layout(location = 3) in vec2 vLocalXZ;
layout(location = 4) in vec2 vUV;

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PushConst {
    layout(offset = 64)  vec4  tint;
    layout(offset = 80)  vec4  plasmaColor;
    layout(offset = 96)  float time;
    layout(offset = 100) float textureMode;
} pc;

layout(set = 1, binding = 0) uniform sampler2D uTex;

void main() {
    // Radial soft-fade from the quad centre — same trick as plasma
    // billboards (E2.1). length(vLocalXZ) is 0 at centre, 1 at edge,
    // sqrt(2) at corners. smoothstep(0.4, 1.0) keeps a bright core and
    // tapers to zero at the edge — corners go fully transparent so the
    // particle reads as a circular blob, not a square.
    float r = length(vLocalXZ);
    float fade = 1.0 - smoothstep(0.4, 1.0, r);

    if (pc.textureMode >= 0.5) {
        // Alpha-textured branch (smoke / debris). Sample uTex; multiply by
        // per-instance colour. Per-instance alpha + the radial fade combine
        // so a particle softens at edges even when its texture has hard
        // boundaries — useful for debris chunks.
        vec4 sampled = texture(uTex, vUV);
        outColor = vec4(sampled.rgb * vColor.rgb, sampled.a * vColor.a * fade);
        return;
    }

    // Additive branch (sparks / embers). Heat-ramp warm-white core →
    // orange edge mirrors the plasma flash look (E4) but driven by
    // length(vLocalXZ) so each particle reads as a tiny spherical glow.
    // Multiplied by per-instance vColor so different events tint their
    // sparks differently.
    vec3 hot  = vec3(1.0, 0.95, 0.70);
    vec3 cool = vec3(1.0, 0.40, 0.08);
    vec3 spark = mix(hot, cool, smoothstep(0.0, 0.7, r));

    // Premultiplied: ONE/ONE blend ignores source alpha, so visible
    // contribution is RGB × fade. Per-instance vColor.a doubles as a
    // brightness scalar (typically holds age-fade in CPU code).
    vec3 rgb = spark * vColor.rgb * fade * vColor.a;
    outColor = vec4(rgb, fade);
}
