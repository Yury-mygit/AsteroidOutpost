#version 450

// E10.1 — fullscreen-triangle post-process vertex shader. No vertex buffer
// bound; we generate three vertices that cover the screen (in NDC) from
// gl_VertexIndex alone. Standard trick: use the bottom two bits of the
// index to pick (0,0), (2,0), (0,2) in UV space, then UV*2-1 → NDC.
// Triangle 0 covers the left and bottom of the screen; vertex 2 sits at
// (-1, +3) which makes the triangle span the whole framebuffer.

layout(location = 0) out vec2 vUV;

void main() {
    vUV = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
}
