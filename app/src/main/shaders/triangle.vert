#version 450

// UBO: camera matrices (updated per frame). E10.3 added prev_view +
// prev_proj for screen-space velocity in motion blur (E10.4 reads the
// velocity attachment). Outpost's camera is fixed so prev_view == view
// always; in g3 the orbit camera moves and the prev pair gives the
// camera-velocity component.
layout(set = 0, binding = 0) uniform Ubo {
    mat4 view;
    mat4 proj;
    mat4 prev_view;
    mat4 prev_proj;
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

// E10.3 — per-draw dynamic UBO (set 2). Holds the previous frame's model
// matrix for this draw call so the vertex shader can compute screen-space
// velocity. The render loop binds this set with a dynamic offset into a
// shared per-frame ring buffer; mesh / translucent / additive / textured
// draws each take their own slot, billboards / particles / frame meshes
// share the engine-init sentinel slot at offset 0 (identity prev_model)
// and the fragment shader writes zero velocity in their branches anyway.
layout(set = 2, binding = 0) uniform PerDraw {
    mat4 prev_model;
} pd;

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;     // RGBA per-vertex (E1.1)
layout(location = 2) in vec3 inNormal;
layout(location = 3) in vec2 inUV;        // texture coords (E8.1) — (0,0) for untextured meshes

layout(location = 0) out vec4 vColor;     // RGB → lit/tinted in fragment, A → output alpha
layout(location = 1) out vec3 vNormal;    // world-space normal
layout(location = 2) out vec3 vWorldPos;  // world-space position
layout(location = 3) out vec2 vLocalXZ;   // model-space X/Z — used for radial soft-fade (E2.1)
layout(location = 4) out vec2 vUV;        // texture coords passed through to fragment (E8.1)
layout(location = 5) out vec2 vVelocity;  // E10.3 — screen-space NDC velocity (curr - prev)
layout(location = 6) out float vNdcY;     // E17 — NDC.y (top=-1, bottom=+1) for star-pipeline bottom fade

void main() {
    vec4 worldPos = pc.model * vec4(inPosition, 1.0);
    vWorldPos     = worldPos.xyz;

    // Normal in world space (no non-uniform scale so transpose-inverse = model)
    vNormal = normalize(mat3(pc.model) * inNormal);

    vColor   = inColor;
    vLocalXZ = inPosition.xz;
    vUV      = inUV;

    vec4 currClip = ubo.proj * ubo.view * worldPos;
    gl_Position   = currClip;

    // E10.3 — compute screen-space NDC velocity. prev_clip uses the
    // previous frame's matrices end-to-end (camera + per-object). Divide
    // by w to project to NDC, take the difference, halve so the result
    // sits in [-1,+1] units rather than [-2,+2]. The fragment shader
    // either passes this through to outVelocity (mesh / additive /
    // translucent branches) or writes zero (frame / plasma branches
    // whose prev_model isn't meaningful).
    vec4 prevClip = ubo.prev_proj * ubo.prev_view * pd.prev_model * vec4(inPosition, 1.0);
    vec2 currNdc  = currClip.xy / max(currClip.w, 1e-4);
    vec2 prevNdc  = prevClip.xy / max(prevClip.w, 1e-4);
    vVelocity     = (currNdc - prevNdc) * 0.5;
    // E17 — pass NDC.y so star fragment branch can fade out in the lower
    // technical strip (HUD / action bar area). Vulkan projection flips Y,
    // so +1 = bottom of screen, -1 = top.
    vNdcY = currNdc.y;

    // E17 — star point size. Only the star pipeline binds POINT_LIST
    // topology; triangle/quad pipelines ignore gl_PointSize. 4px gives
    // a visible "twinkle dot" on high-DPI screens (1px is invisible).
    gl_PointSize = 4.0;
}
