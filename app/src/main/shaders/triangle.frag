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

void main() {
    float alpha = vColor.a * plasmaSoftFade();

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
