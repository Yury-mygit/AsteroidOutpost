#version 450

// E10.1 — passthrough post-process. Samples the scene's offscreen colour
// at the fragment's UV and writes it straight to the swapchain. After
// E10.2 (velocity attachment) and E10.3 (prev-frame matrices) land, this
// turns into the motion-blur fragment in E10.4 — for now it just keeps
// the visual identical to pre-E10.

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D sceneColor;

void main() {
    outColor = texture(sceneColor, vUV);
}
