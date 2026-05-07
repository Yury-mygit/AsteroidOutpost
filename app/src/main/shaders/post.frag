#version 450

// E10.4 — motion blur post-process. Replaces the E10.1 passthrough with
// a 5×5 velocity-dilation step followed by an 8-tap weighted blur along
// the dilated velocity direction. Velocity comes from the second colour
// attachment of the scene pass (R16G16_SFLOAT, E10.2) populated by
// triangle.vert / triangle.frag with real per-object motion vectors as
// of E10.3, with overlay pipelines (plasma / particles / etc.) writing
// no velocity since the E10.4 colorWriteMask fix so they don't clobber
// underlying moving-object motion vectors.
//
// Three techniques stacked:
//
// 1. Velocity dilation. Naive sampling along per-pixel velocity makes
//    small fast objects (bullets ~5px wide moving ~30px/frame) wash out:
//    only one sample lands on the bullet, the other seven on background,
//    → 12% bullet colour ≈ ghostly fade and frame-to-frame instability
//    perceived as flicker. Dilation looks for the max-magnitude velocity
//    in a 5×5 neighbourhood (≈ ±5px on a 1080p screen) so a static pixel
//    adjacent to a fast bullet picks up the bullet's velocity for blur,
//    extending the bullet's "blur halo" into surrounding pixels. The
//    bullet now reads as a continuous streak across a wider screen area
//    instead of a sub-pixel dot.
//
// 2. Weighted blur. Even with dilation, samples landing on static
//    background still dilute the bullet colour (1 of 8 = 12% for a
//    centred bullet, ~32% for an off-axis adjacent pixel). Weighting
//    moving samples higher than static samples concentrates contribution
//    on actual motion-related pixels, bringing bullet streak visibility
//    up to ~50-60% while keeping static surrounds intact.
//
// 3. Length-clamp. Stops freak per-frame motion (transient prev_model
//    glitch, very fast object near camera) from tearing up half the
//    screen with a several-screen-wide blur.

layout(location = 0) in vec2 vUV;
layout(location = 0) out vec4 outColor;

layout(set = 0, binding = 0) uniform sampler2D sceneColor;
layout(set = 0, binding = 1) uniform sampler2D sceneVelocity;

const int   kMotionSamples = 8;
const float kMaxBlur       = 0.05;   // clamp blur at 5% of screen — well above 60Hz gameplay max
const float kIntensity     = 1.5;    // visual gain on velocity (1.0 = physical shutter, 1.5 reads cleaner at 60Hz)
const float kEpsilon       = 1.0e-4; // length threshold for "moving" classification
const float kStaticWeight  = 0.2;    // contribution of static samples relative to moving (1.0)
const int   kDilationRadius = 2;     // 5×5 neighbourhood (= ±2 texels)

void main() {
    vec2 texel = 1.0 / vec2(textureSize(sceneVelocity, 0));

    // ---- Velocity dilation ------------------------------------------------
    // Find max-magnitude velocity in a 5×5 neighbourhood. Static pixels
    // adjacent to a moving object pick up that object's velocity, so the
    // motion blur extends naturally into surrounding pixels instead of
    // being confined to the small mesh footprint.
    vec2 vMax = vec2(0.0);
    float lenMax2 = 0.0;
    for (int dy = -kDilationRadius; dy <= kDilationRadius; ++dy) {
        for (int dx = -kDilationRadius; dx <= kDilationRadius; ++dx) {
            vec2 sv = texture(sceneVelocity, vUV + vec2(dx, dy) * texel).rg;
            float l2 = dot(sv, sv);
            if (l2 > lenMax2) {
                lenMax2 = l2;
                vMax = sv;
            }
        }
    }

    vec2 v = vMax * kIntensity;
    float vlen = length(v);

    // Fast path for fully static regions (most of the screen on most frames).
    // Skips the 16 sample fetches below; bandwidth saver since the scene's
    // largely-static under fixed camera in Outpost.
    if (vlen < kEpsilon) {
        outColor = texture(sceneColor, vUV);
        return;
    }

    if (vlen > kMaxBlur) v *= kMaxBlur / vlen;

    // ---- Weighted 8-tap blur ---------------------------------------------
    // Symmetric blur centred on vUV, samples at vUV + v * t for
    // t ∈ [-0.5, +0.5). Each sample's velocity tells us if it sits on a
    // moving object — those contribute fully (weight 1.0), static
    // samples contribute kStaticWeight = 0.2 so they don't dilute the
    // moving-object colour as much as a uniform average would.
    vec3 sumColor = vec3(0.0);
    float sumWeight = 0.0;
    for (int i = 0; i < kMotionSamples; ++i) {
        float t = (float(i) + 0.5) / float(kMotionSamples) - 0.5;
        vec2 sampleUV = vUV + v * t;
        vec3 c = texture(sceneColor, sampleUV).rgb;
        vec2 sv = texture(sceneVelocity, sampleUV).rg;
        float w = (dot(sv, sv) > kEpsilon * kEpsilon) ? 1.0 : kStaticWeight;
        sumColor += c * w;
        sumWeight += w;
    }
    outColor = vec4(sumColor / sumWeight, 1.0);
}
