#version 450

layout(location = 0) in vec3 vColor;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec3 vWorldPos;

layout(location = 0) out vec4 outColor;

layout(push_constant) uniform PushConst {
    layout(offset = 64) vec4 tint;
} pc;

void main() {
    // Frame mesh vertices bypass lighting; color is baked into vertex data.
    // Allied = green (0,1,0), enemy = red-ish (1, ~0.38, ~0.34).
    bool isFrame = (vColor.g > 0.95 && vColor.r < 0.05 && vColor.b < 0.20)
                || (vColor.r > 0.90 && vColor.g < 0.50);
    if (isFrame) {
        outColor = vec4(vColor, 1.0);
        return;
    }

    // Plasma bolt: vivid cyan, rendered via additive-blend pipeline — output emissive unlit.
    bool isPlasma = (vColor.b > 0.88 && vColor.g > 0.78 && vColor.r < 0.25);
    if (isPlasma) {
        outColor = vec4(vColor.r * 0.6 + 0.15, vColor.g * 1.15, vColor.b, 1.0);
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

    // Combine
    vec3 lighting = ambient
                  + vColor * (diff + fill)
                  + rimColor * rim;

    outColor = vec4(lighting, 1.0);
}
