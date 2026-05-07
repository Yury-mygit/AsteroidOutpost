#version 450

layout(location = 0) in vec4 vColor;     // RGBA — A passes through to outColor
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec3 vWorldPos;
layout(location = 3) in vec2 vLocalXZ;   // model-space X/Z — used for radial soft-fade (E2.1)
layout(location = 4) in vec2 vUV;        // texture coords (E8.1) — sampled by E8.3+ textured branch

layout(location = 0) out vec4 outColor;
// E10.2 — second colour attachment: screen-space velocity in NDC units
// (RG16F). Placeholder zero for now — real per-object velocity needs
// prev_model push-const + view/proj history (E10.3). Writing zero keeps
// the velocity buffer well-defined for the post pass (E10.4 motion blur
// reads zeros as "static" → no blur, matching pre-E10 behaviour).
layout(location = 1) out vec2 outVelocity;

// Push constant: model matrix (vert-only) at offset 0, then tint flags,
// plasmaColor, and time visible here. Keep these layouts in lockstep with
// triangle.vert and the C++ PushConstantData struct in VulkanContext.cpp.
layout(push_constant) uniform PushConst {
    layout(offset = 64) vec4 tint;
    layout(offset = 80) vec4 plasmaColor;
    layout(offset = 96) float time;
    layout(offset = 100) float textureMode;  // E8.3 — sample uTex when ≥0.5
} pc;

// E8.3 — combined image sampler for textured opaque draws (drawTexturedMesh).
// Bound at set=1 binding=0; default 1×1 white at frame start, replaced
// per-draw by the textured render-loop. All seven pipelines share
// pc layout so this declaration is universally available.
layout(set = 1, binding = 0) uniform sampler2D uTex;

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
    // E6 — slow drift over time so nebulae feel alive instead of frozen.
    // Drift speed kept low (~0.04 sample units/sec) so the motion reads as
    // ambient gas circulation, not visibly flowing.
    vec2 drift = vec2(pc.time * 0.04, pc.time * 0.025);
    vec2 base = vWorldPos.xz * 0.9 + drift;
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
    // E10.2 — velocity placeholder; written once at the top so the many
    // early-return branches below all leave outVelocity well-defined.
    outVelocity = vec2(0.0);

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

    // E4 — plasma flash: unlit emissive output for additive-blend billboards
    // tagged with pc.tint.x (= flashes in this project — muzzle, trail, AoE,
    // hit, ENERGY pickup). Three things happen here:
    //   1) Premultiply `alpha` into RGB. The plasma pipeline blends ONE/ONE
    //      (VulkanContext.cpp), so source alpha never reaches the framebuffer
    //      — without this multiply, plasmaSoftFade() was a visual no-op and
    //      flashes read as boxy yellow rectangles.
    //   2) Radial heat ramp (warm white-yellow core → orange edge) gives a
    //      flame look without per-billboard colour data.
    //   3) FBM turbulence breaks up the uniform disc into wispy structure;
    //      sampled in world space so adjacent flashes see different slices
    //      and don't repeat the same pattern.
    // E5.1 — `pc.plasmaColor.rgb` is multiplied into the heat-ramp result so
    // each flash type can carry its own tint (cyan ENERGY pickup, blue impact
    // spark, orange-red AoE), and `pc.plasmaColor.a` is an overall brightness
    // scalar. Default (1,1,1,1) at the Kotlin layer leaves E4 unchanged.
    if (pc.tint.x >= 0.5) {
        vec3 hot  = vec3(1.0, 0.95, 0.70);   // warm white-yellow core
        vec3 cool = vec3(1.0, 0.40, 0.08);   // orange flame edge
        float r = length(vLocalXZ);
        vec3 fireColor = mix(hot, cool, smoothstep(0.0, 0.7, r));
        // E6 — animate the noise field so flames flicker over time.
        // Two perpendicular drift speeds keep the flow from feeling 1-D.
        // Coefficients (1.4, 0.9) give visible motion within a flash's
        // ~0.15-0.5 sec life without making it look like it's racing.
        vec2 fireWarp = vec2(pc.time * 1.4, pc.time * 0.9);
        float n = fbm4(vWorldPos.xz * 8.0 + fireWarp);
        float fire = 0.55 + n * 0.95;        // ~[0.55, 1.5] turbulence
        outColor = vec4(fireColor * pc.plasmaColor.rgb * fire * alpha * pc.plasmaColor.a, alpha);
        return;
    }

    // E7 — additive 3D mesh (ONE/ONE blend). Engine sets pc.tint.w when
    // binding the additive pipeline: 1.0 = plain, 2.0 = fire. Mesh authors
    // put A=1 at glow centres and A=0 at edges for soft falloff;
    // pc.plasmaColor is the per-draw tint (rgb = colour, a = brightness
    // scalar). Output is premultiplied — the ONE/ONE blend ignores source
    // alpha, so the visible contribution is RGB*A. Multiplying alpha into
    // RGB embeds the falloff into the additive contribution; without it,
    // the blend would punch a uniformly bright mesh shape into the
    // framebuffer.

    // E7.1 — fire material (tint.w ≈ 2.0). Designed for spherical fireball
    // meshes on this project's fixed pitch=π/2 camera (camera looks along
    // -Y in world space — see Camera::reset). `abs(vNormal.y)` becomes a
    // Fresnel-like "facing camera" factor: 1 at the apparent disc centre
    // (normals along ±Y), 0 at the silhouette edge (normals perpendicular
    // to view). Result: a bright hot core that fades to nothing at the
    // outline, plus a heat-ramp from white-yellow centre to orange edge,
    // plus FBM turbulence for shimmer — same vocabulary as the plasma
    // billboard branch but on real 3D geometry. NOTE: the abs(vNormal.y)
    // simplification is project-specific; reusing this on a moving camera
    // would need real view direction via UBO.
    if (pc.tint.w >= 1.5) {
        float facing = clamp(abs(vNormal.y), 0.0, 1.0);
        // Heat ramp: hot core (white-yellow, facing≈1) → cool edge (orange,
        // facing≈0). `1 - facing` makes ramp parameter 0 at centre, 1 at edge.
        vec3 hot  = vec3(1.0, 0.95, 0.70);
        vec3 cool = vec3(1.0, 0.40, 0.08);
        vec3 fireColor = mix(hot, cool, smoothstep(0.0, 1.0, 1.0 - facing));
        // FBM turbulence for shimmer (reuse fbm4 from nebula material).
        // Sample world-space XZ so adjacent fireballs see different slices.
        // Drift slightly slower than plasma flashes so a longer-lived
        // fireball reads as continuous fire instead of racing noise.
        vec2 fireWarp = vec2(pc.time * 1.0, pc.time * 0.7);
        float n = fbm4(vWorldPos.xz * 6.0 + fireWarp);
        float fire = 0.55 + n * 0.95;            // ~[0.55, 1.5]
        // Fresnel falloff embedded into the additive contribution.
        // smoothstep gives a soft edge instead of a hard cutoff.
        float edgeFalloff = smoothstep(0.0, 0.55, facing);
        float a = vColor.a * edgeFalloff * pc.plasmaColor.a;
        outColor = vec4(fireColor * pc.plasmaColor.rgb * fire * a, a);
        return;
    }
    if (pc.tint.w >= 0.5) {
        vec3 rgb = vColor.rgb * pc.plasmaColor.rgb * vColor.a * pc.plasmaColor.a;
        outColor = vec4(rgb, vColor.a);
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

    // E8.3 — albedo source: textured draws sample uTex at vUV and use
    // pc.plasmaColor.rgb as a multiplicative tint; untextured draws fall
    // back to vColor.rgb (the per-vertex / load-time tint) which preserves
    // the original M7-era look.
    vec3 albedo;
    if (pc.textureMode >= 0.5) {
        vec4 sampled = texture(uTex, vUV);
        albedo = sampled.rgb * pc.plasmaColor.rgb;
    } else {
        albedo = vColor.rgb;
    }

    // Combine — RGB lit, alpha passes through from vertex (1.0 for opaque meshes,
    // <1.0 for translucent meshes that use the alpha-blend pipeline).
    vec3 lighting = ambient
                  + albedo * (diff + fill)
                  + rimColor * rim;

    outColor = vec4(lighting, alpha);
}
