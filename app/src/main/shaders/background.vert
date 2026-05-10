#version 450

// E18 — fullscreen background nebula vertex shader.
// Emits a single triangle covering NDC (-1..+1)² with one extra unit on each
// side so it certainly clips to the full screen. No vertex buffer required —
// the engine binds an empty input layout and issues vkCmdDraw(3, 1, 0, 0).
// Output `vUV` is in [0, 1] across the screen for the fragment shader to
// sample its noise field in screen space.

layout(location = 0) out vec2 vUV;

void main() {
    // Three vertices forming a triangle that covers the screen in NDC:
    //   (-1, -1) bottom-left, (3, -1) bottom-far-right, (-1, 3) top-far-left.
    // The triangle is twice the screen size so its hypotenuse is well outside
    // the view — the rasterizer just clips to the screen rectangle.
    vec2 positions[3] = vec2[](
        vec2(-1.0, -1.0),
        vec2( 3.0, -1.0),
        vec2(-1.0,  3.0)
    );
    vec2 pos = positions[gl_VertexIndex];
    // z = 0.99999 puts the background just shy of the far plane so it never
    // wins LESS depth-test against any scene geometry; w = 1 keeps perspective
    // divide identity. The renderer disables depth write for this pipeline so
    // subsequent opaque draws still establish their own depth normally.
    gl_Position = vec4(pos, 0.99999, 1.0);
    // Map (-1..+1) → (0..1) for screen-space UV.
    vUV = pos * 0.5 + 0.5;
}
