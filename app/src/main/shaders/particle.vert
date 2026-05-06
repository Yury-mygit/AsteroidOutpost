#version 450

// E9 — particle vertex shader. Camera-facing billboards driven by
// per-instance position/size/color. Same UBO and push-constant layout as
// the main triangle shaders so the existing pipeline layout works
// unchanged. Reads binding 1 (input rate INSTANCE).

layout(set = 0, binding = 0) uniform Ubo {
    mat4 view;
    mat4 proj;
} ubo;

layout(push_constant) uniform PushConst {
    mat4  model;        // unused for particles, kept for layout match
    vec4  tint;
    vec4  plasmaColor;  // overall system tint, multiplied with per-particle vColor
    float time;
    float textureMode;  // unused for particles
} pc;

// Binding 0 — unit quad (corners at ±1 in X/Z, the engine's standard
// X-Z-plane primitive). Position is the only attribute used here; rgba/
// normal/uv slots stay so existing meshes still compile under the same
// shared vertex layout if someone ever feeds the particle pipeline a
// regular mesh.
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;     // unused
layout(location = 2) in vec3 inNormal;    // unused
layout(location = 3) in vec2 inUV;        // unused

// Binding 1 — per-instance.
layout(location = 4) in vec4 instPosSize;  // xyz = world position, w = world-space half-size
layout(location = 5) in vec4 instColor;    // RGBA — RGB tint, A = alpha (used in alpha pipeline)

layout(location = 0) out vec4 vColor;
layout(location = 1) out vec3 vNormal;     // unused — particle.frag does its own shading
layout(location = 2) out vec3 vWorldPos;
layout(location = 3) out vec2 vLocalXZ;    // local quad coord (-1..+1) — soft-fade radius
layout(location = 4) out vec2 vUV;         // texture UV (0..1) — texture sampling for alpha-textured

void main() {
    // Extract camera right/up from the view matrix. The view matrix's
    // first three columns are the camera basis vectors transposed
    // (row 0 = right, row 1 = up, row 2 = -forward in OpenGL convention,
    // matching how station::Camera::view() is built). Standard billboard
    // construction: world position = instance origin + (qx*right + qy*up)
    // * size. Quads in this engine live in the X-Z plane (model.y = 0),
    // so we map quad.x → camera right and quad.z → camera up.
    vec3 cameraRight = vec3(ubo.view[0][0], ubo.view[1][0], ubo.view[2][0]);
    vec3 cameraUp    = vec3(ubo.view[0][1], ubo.view[1][1], ubo.view[2][1]);

    // The unit quad is x in [-1,1], z in [-1,1], y=0 (E5.2 confirmed quad
    // corners ±1 after the billboard matrix swap). Mirror the same X→right
    // / Z→up mapping here so the particle is screen-aligned just like
    // billboardMatrix produces for plasma flashes.
    vec3 worldPos = instPosSize.xyz
                  + (inPosition.x * cameraRight + inPosition.z * cameraUp) * instPosSize.w;

    vColor    = instColor;
    vNormal   = vec3(0.0, 0.0, 1.0);  // unused
    vWorldPos = worldPos;
    vLocalXZ  = inPosition.xz;
    // Map the quad's local ±1 into 0..1 UV space. Sprite atlases later
    // can shift/scale this from per-instance attrs; for now full quad.
    vUV       = inPosition.xz * 0.5 + 0.5;

    gl_Position = ubo.proj * ubo.view * vec4(worldPos, 1.0);
}
