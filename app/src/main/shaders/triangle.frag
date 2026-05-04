#version 450

layout(location = 0) in vec4 vColor;     // RGBA — A passes through to outColor
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec3 vWorldPos;
layout(location = 3) in vec2 vLocalXZ;   // model-space X/Z — used for radial soft-fade (E2.1)

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PushConst {
    layout(offset = 64) vec4 tint;
} pc;

// E2.1 — radial soft-fade for plasma billboards.
// Engine sets pc.tint.x = 1.0 when binding the plasma pipeline; all other
// pipelines leave it at 0. Plasma billboards in this project all use
// quad.gltf, whose corners are at ±1 in X-Z, so length(vLocalXZ) is 0 at
// the centre, 1 at the mid-edge, √2 at the corners. Fade from full alpha
// at r<0.4 to zero at r=1.0 → glow inscribes the quad and corners go dark.
float plasmaSoftFade() {
    if (pc.tint.x < 0.5) return 1.0;
    float r = length(vLocalXZ);
    return 1.0 - smoothstep(0.4, 1.0, r);
}

// E3.2 — value-noise FBM for the NEBULA material. Four octaves rotated
// against each other (~40° + non-power-of-2 frequency step) so the
// cell-grid of one octave doesn't line up with the next; without that
// rotation the visible base grid bleeds through after contrast boost
// and the nebulae read as square tiles. Sampled from world-space X/Z so
// the pattern is stable across frames and each nebula gets its own
// slice of the noise field.
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
float fbm4(vec2 p) {
    // Per-octave rotation + non-integer frequency step ensures cells don't
    // align across octaves → no visible grid in the composite noise.
    const mat2 R = mat2(0.766, -0.643, 0.643, 0.766);  // ~40° rotation
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise2(p);
        p = R * p * 2.13;
        amp *= 0.55;
    }
    return v;
}
float nebulaAlphaMod() {
    if (pc.tint.y < 0.5) return 1.0;
    vec2 base = vWorldPos.xz * 0.9;
    // Domain warp — perturb the sample point with a second FBM lookup so
    // residual rectangular cell clusters get smeared into curls and
    // tendrils. The cost is 3× FBM per fragment but it's the cleanest fix
    // for value-noise grid artifacts and the warped flow lines also read
    // as natural cosmic-cloud structure.
    vec2 warp = vec2(fbm4(base), fbm4(base + vec2(5.3, 2.7)));
    float n = fbm4(base + warp * 0.5);
    return smoothstep(0.20, 0.85, n);
}

// E3.3 — hex-grid alpha modulation for the HEX (shield-dome) material.
// Sampled in model-space X/Z so the pattern is anchored to the dome and
// doesn't shift as the camera or scale changes. Returns ~`baseFill` in
// hex-cell interiors and 1.0 along the cell borders, so the shader emphasises
// the hex grid lines on top of whatever per-vertex alpha the mesh defines.
//
// Hex tile lookup based on Inigo Quilez's snippet: pick the closer of two
// candidate hex centres (offset by half a row), then measure distance to
// the nearest cell edge in canonical hex space.
vec4 hexTile(vec2 p) {
    const vec2 s = vec2(1.7320508, 1.0);  // (sqrt(3), 1)
    vec4 hC = floor(vec4(p, p - vec2(0.5, 1.0)) / s.xyxy) + 0.5;
    vec4 h  = vec4(p - hC.xy * s, p - (hC.zw + 0.5) * s);
    return dot(h.xy, h.xy) < dot(h.zw, h.zw)
         ? vec4(h.xy, hC.xy)
         : vec4(h.zw, hC.zw + 0.5);
}
float hexEdgeDist(vec2 p) {
    p = abs(p);
    return 0.866025 - max(p.x * 0.866025 + p.y * 0.5, p.y);
}
float hexAlphaMod() {
    if (pc.tint.z < 0.5) return 1.0;
    // ~6 hex cells across the dome's local diameter (vLocalXZ ranges ±1).
    // Slightly fewer + bigger cells than v1 so each hex reads cleaner.
    vec4 hex = hexTile(vLocalXZ * 6.0);
    float d = hexEdgeDist(hex.xy);
    // Wide soft falloff (0.10 → 0.55) and high base (0.85) → hex grid is a
    // subtle structural hint over the dome surface, not a bright wireframe.
    // Lines lift the alpha ~15% along cell borders; cell interiors stay at
    // 85% so the dome reads as one continuous force-field membrane.
    float line  = 1.0 - smoothstep(0.10, 0.55, d);
    float base  = 0.85;
    return base + (1.0 - base) * line;
}

void main() {
    float alpha = vColor.a * plasmaSoftFade() * nebulaAlphaMod() * hexAlphaMod();

    // Frame mesh vertices bypass lighting; color is baked into vertex data.
    // Allied = green (0,1,0), enemy = red-ish (1, ~0.38, ~0.34).
    bool isFrame = (vColor.g > 0.95 && vColor.r < 0.05 && vColor.b < 0.20)
                || (vColor.r > 0.90 && vColor.g < 0.50);
    if (isFrame) {
        outColor = vec4(vColor.rgb, alpha);
        return;
    }

    // Plasma bolt: vivid cyan, rendered via additive-blend pipeline — output emissive unlit.
    bool isPlasma = (vColor.b > 0.88 && vColor.g > 0.78 && vColor.r < 0.25);
    if (isPlasma) {
        outColor = vec4(vColor.r * 0.6 + 0.15, vColor.g * 1.15, vColor.b, alpha);
        return;
    }

    vec3 N = normalize(vNormal);

    // Primary light — top-right, warm white (sun / star)
    vec3 lightDir = normalize(vec3(0.4, 0.6, 0.8));
    float diff = max(dot(N, lightDir), 0.0);

    // Secondary fill light — bottom-left, cool blue (space ambient)
    vec3 fillDir = normalize(vec3(-0.3, -0.4, 0.2));
    float fill = max(dot(N, fillDir), 0.0) * 0.25;

    // Ambient — deep space, very dark blue
    vec3 ambient = vec3(0.04, 0.05, 0.10);

    // Rim light — subtle blue edge glow (sci-fi feel)
    // approximated as contribution from back-facing relative to main light
    float rim = pow(1.0 - max(dot(N, lightDir), 0.0), 3.0) * 0.15;
    vec3 rimColor = vec3(0.2, 0.4, 0.8);

    // Combine — RGB lit, alpha passes through from vertex (1.0 for opaque meshes,
    // <1.0 for translucent meshes that use the alpha-blend pipeline).
    vec3 lighting = ambient
                  + vColor.rgb * (diff + fill)
                  + rimColor * rim;

    outColor = vec4(lighting, alpha);
}
