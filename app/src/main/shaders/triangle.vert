#version 450

// UBO: camera matrices (updated per frame)
layout(set = 0, binding = 0) uniform Ubo {
    mat4 view;
    mat4 proj;
} ubo;

// Push constant: model matrix + tint flags + plasma color + time (100 bytes).
// `tint` is shader-mode flags (E2.1 plasma soft-fade .x, E3.1 nebula .y,
// E3.1 hex .z) — NOT colour. `plasmaColor` (E5.1) carries per-billboard
// tint for plasma flashes; default (1,1,1,1) keeps the E4 look. `time`
// (E6) is elapsed seconds for animated procedural effects.
layout(push_constant) uniform PushConst {
    mat4 model;
    vec4 tint;
    vec4 plasmaColor;
    float time;
    float textureMode;  // E8.3 — 1.0 ⇒ fragment samples uTex at vUV (lit branch)
} pc;

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;     // RGBA per-vertex (E1.1)
layout(location = 2) in vec3 inNormal;
layout(location = 3) in vec2 inUV;        // texture coords (E8.1) — (0,0) for untextured meshes

layout(location = 0) out vec4 vColor;     // RGB → lit/tinted in fragment, A → output alpha
layout(location = 1) out vec3 vNormal;    // world-space normal
layout(location = 2) out vec3 vWorldPos;  // world-space position
layout(location = 3) out vec2 vLocalXZ;   // model-space X/Z — used for radial soft-fade (E2.1)
layout(location = 4) out vec2 vUV;        // texture coords passed through to fragment (E8.1)

void main() {
    vec4 worldPos = pc.model * vec4(inPosition, 1.0);
    vWorldPos     = worldPos.xyz;

    // Normal in world space (no non-uniform scale so transpose-inverse = model)
    vNormal = normalize(mat3(pc.model) * inNormal);

    vColor   = inColor;
    vLocalXZ = inPosition.xz;
    vUV      = inUV;

    gl_Position = ubo.proj * ubo.view * worldPos;
}
