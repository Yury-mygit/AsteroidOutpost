#version 450

// Post-pass — pure passthrough from offscreen scene colour to swapchain.
//
// History: E10.4 introduced 5×5 velocity-dilation + 8-tap weighted motion
// blur to keep sub-pixel bullet trails legible at 60Hz. Side effect (E23):
// the dilation step leaked high-magnitude velocity from passing asteroids
// into adjacent static ship-attached pixels (hull, turrets, dome, silo),
// rendering them as a fine continuous shimmer that no amount of mesh /
// prev_model fixes could remove. On the high-refresh OLED phones the game
// targets, bullets and other fast objects read fine without dilation
// blur, so motion blur was retired entirely. The full E10.4 algorithm
// (dilation + weighted blur + length clamp) lives in git history if it
// needs to come back; reviving it cleanly requires per-object dilation
// boundaries or a stencil mask so static pixels don't sample neighbours.
//
// Pipeline still exists in the engine (VulkanContext `post`), because the
// scene pass writes to offscreen colour + velocity attachments and a
// separate pass is needed to copy colour to the swapchain. We just don't
// do anything to the colour anymore. Velocity attachment is bound but
// unused; descriptor layout retained for ABI stability with the C++ side.

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D sceneColor;
layout(set = 0, binding = 1) uniform sampler2D sceneVelocity;  // unused — kept for descriptor-layout ABI

void main() {
    outColor = texture(sceneColor, vUV);
}
