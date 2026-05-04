#version 450

// UBO: camera matrices (updated per frame)
layout(set = 0, binding = 0) uniform Ubo {
    mat4 view;
    mat4 proj;
} ubo;

// Push constant: model matrix + tint color (80 bytes)
layout(push_constant) uniform PushConst {
    mat4 model;
    vec4 tint;
} pc;

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;     // RGBA per-vertex (E1.1)
layout(location = 2) in vec3 inNormal;

layout(location = 0) out vec4 vColor;     // RGB → lit/tinted in fragment, A → output alpha
layout(location = 1) out vec3 vNormal;    // world-space normal
layout(location = 2) out vec3 vWorldPos;  // world-space position

void main() {
    vec4 worldPos = pc.model * vec4(inPosition, 1.0);
    vWorldPos     = worldPos.xyz;

    // Normal in world space (no non-uniform scale so transpose-inverse = model)
    vNormal = normalize(mat3(pc.model) * inNormal);

    vColor = inColor;

    gl_Position = ubo.proj * ubo.view * worldPos;
}
