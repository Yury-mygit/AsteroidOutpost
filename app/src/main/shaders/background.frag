#version 450

// E18 — fullscreen background nebula. Computes FBM noise in screen-space
// (anchored to UV with a slow time drift), maps the result to a multi-tint
// gradient so the backdrop has both deep cosmic black and soft cloud
// patches without any holes. Output goes straight to outColor; outVelocity
// is forced to zero so the post-process motion-blur pass treats the
// background as static. The engine renders this triangle FIRST in the
// frame, before stars and scene meshes, so depth read/write isn't needed.

layout(location = 0) in vec2 vUV;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outVelocity;

// Push constants — only `time` is read here. The rest of the layout is
// shared with the scene pipeline (PushConstantData in C++) so the engine
// can push the same struct without branching.
layout(push_constant) uniform PushConst {
    layout(offset = 96) float time;
} pc;

// FBM helpers — pared down for fragment-shader cost. Three octaves are
// enough at the low base frequency we sample at; the fourth octave adds
// pixel-scale grain that's invisible against the soft nebula tints
// anyway. Single shared `R` rotation matrix; fbm3 is the only variant.
float hash21(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}
float vnoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash21(i + vec2(0.0, 0.0)), hash21(i + vec2(1.0, 0.0)), u.x),
        mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x),
        u.y
    );
}
float fbm3(vec2 p) {
    const mat2 R = mat2(0.766, -0.643, 0.643, 0.766);
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        v += amp * vnoise2(p);
        p = R * p * 2.13;
        amp *= 0.55;
    }
    return v;
}

void main() {
    // Aspect-corrected sample coords so noise blobs stay round on
    // portrait viewports. Low base frequency for big galaxy-scale features.
    vec2 sampleUV = vUV * vec2(0.45, 1.0) * 0.9;

    // Slow time drift — barely perceptible gas circulation.
    vec2 drift = vec2(pc.time * 0.010, pc.time * 0.007);
    vec2 base = sampleUV + drift;

    // Two-pass FBM for SHAPE — single warp + main = 2 fbm calls.
    // Domain warp turns straight FBM blobs into curls and tendrils that
    // read as gas; without it the cells of value noise are visible as
    // square clusters.
    vec2 w = vec2(fbm3(base), fbm3(base + vec2(5.3, 2.7)));
    float n = fbm3(base + w * 0.4);

    // Slow zone-tinting field — second cheap fbm picks WHICH colour
    // dominates locally, slowly varying across the screen. Different
    // offset so it doesn't correlate with the shape field.
    float zone = fbm3(sampleUV * 0.55 + vec2(13.4, 7.1) + drift * 0.6);

    // Total cost: 4 fbm3 calls × 3 octaves = 12 vnoise2 per pixel
    // (vs 40 in v3 — ~70% less GPU work).

    vec3 cBlack   = vec3(0.010, 0.014, 0.028);
    vec3 cPurple  = vec3(0.32, 0.13, 0.58);
    vec3 cTeal    = vec3(0.08, 0.42, 0.52);
    vec3 cCrimson = vec3(0.55, 0.15, 0.22);

    // Pick a tint by walking the zone field across the three colours.
    // smoothsteps overlap so adjacent zones blend at their edges instead
    // of a hard colour switch.
    vec3 tint = cPurple;
    tint = mix(tint, cTeal,    smoothstep(0.35, 0.55, zone));
    tint = mix(tint, cCrimson, smoothstep(0.55, 0.75, zone));

    // Cloud SHAPE — narrow smoothstep (0.40 → 0.60) for crisp cloud
    // edges instead of soft full-range gradient. Below 0.40 = empty
    // black space; above 0.60 = full tint; middle band = the visible
    // wispy edge of a cloud. This is what gives the "structured cloud"
    // look (vs the smeared gradient of v3 wide smoothsteps).
    float cloudMask = smoothstep(0.40, 0.60, n);
    vec3 col = mix(cBlack, tint, cloudMask);

    // Inner-cloud detail — multiply the coarser shape against a SHIFTED
    // subset of itself to add wispy structure inside cloud bodies.
    // Free of new fbm calls — reuses `n` and `w` to derive variation.
    float wisp = 0.7 + 0.3 * (n + w.x * 0.5);
    col *= wisp;

    // Soft radial vignette for depth perception.
    vec2 centred = vUV - 0.5;
    float vignette = 1.0 - smoothstep(0.45, 1.15, length(centred * vec2(1.7, 1.0)));
    col *= 0.65 + 0.35 * vignette;

    // Bottom fade — keep the technical strip cleanly black for HUD.
    float bottomFade = smoothstep(0.05, 0.30, vUV.y);
    col *= bottomFade;

    outColor    = vec4(col, 1.0);
    outVelocity = vec2(0.0);
}
