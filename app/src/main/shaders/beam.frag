#version 450

// E14 — Beam fragment. Tight Gaussian core perpendicular to the beam axis
// (bright thin centerline) plus a softer halo. Sharp endpoints — only the
// last 2% of beam length on each side fades to invisible (vs. plasma's
// 20% smoothstep). Gentle pulse along length for a "live" feel.
// Premultiplied alpha for ONE/ONE additive blend. Velocity output is zero
// (consistent with other VFX overlays, matches colour-write-mask = 0 on
// the velocity attachment for non-opaque pipelines).

layout(location = 0) in vec2 vUV;             // x: 0..1 along beam, y: -1..+1 perpendicular
layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outVelocity;

layout(push_constant) uniform PC {
    vec3  start;
    vec3  end;
    vec4  color;
    float width;
    float time;
} pc;

void main() {
    outVelocity = vec2(0.0);
    float u = vUV.x;
    float v = vUV.y;

    // Gaussian core — the visible thin centerline. Coefficient 24 puts the
    // 1/e half-width at ~0.20 of the quad width (good "thin laser" feel).
    float core = exp(-v * v * 24.0);
    // Wider exponential halo for the soft glow around the core.
    float halo = exp(-abs(v) * 3.0) * 0.3;

    // Sharp endpoints. The 2% fade window stays well below the perceptible
    // "abrupt" threshold while avoiding aliased hard edges on the quad ends.
    float endFade = smoothstep(0.0, 0.02, u) *
                    (1.0 - smoothstep(0.98, 1.0, u));

    // Subtle pulse along the beam — period ~0.7s, amplitude ±15%.
    float pulse = 0.85 + 0.15 * sin(pc.time * 9.0 + u * 6.28);

    float intensity = (core + halo) * pulse * endFade * pc.color.a;
    outColor = vec4(pc.color.rgb * intensity, intensity);
}
