#version 450

// E20 — Force-field shield vertex stage. Hemisphere mesh in unit form;
// vertex shader places it via `center + radius * inPos`. Push constants
// stay small (80 bytes) — well under any minimum-guarantee limit.
//
// The scene UBO descriptor (binding 0, set 0) is declared with VERTEX
// stage only — so view-space transforms have to happen here. We pass
// the view-space normal and view-space position to the fragment shader
// as plain varyings.

layout(set = 0, binding = 0) uniform UBO {
    mat4 view;
    mat4 proj;
    mat4 prev_view;
    mat4 prev_proj;
} ubo;

layout(push_constant) uniform PC {
    vec4 centerRadius;   // xyz = world centre, w = radius
    vec4 impacts[4];     // xyz = world position, w = age normalised [0..1] (>=1 = expired)
} pc;

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;     // unused — mesh ships white per-vertex
layout(location = 2) in vec3 inNormal;
layout(location = 3) in vec2 inUV;        // unused

layout(location = 0) out vec3 vWorldPos;
layout(location = 1) out vec3 vViewNormal;
layout(location = 2) out vec3 vViewPos;

void main() {
    vec3 wp = pc.centerRadius.xyz + pc.centerRadius.w * inPos;
    vWorldPos = wp;
    vViewNormal = (ubo.view * vec4(inNormal, 0.0)).xyz;
    vec4 vp = ubo.view * vec4(wp, 1.0);
    vViewPos = vp.xyz;
    gl_Position = ubo.proj * vp;
}
