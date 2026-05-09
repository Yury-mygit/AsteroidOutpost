#version 450

// E14 — Beam API. Vertex shader for the dedicated `drawLaserBeam` path.
// Generates a view-aligned quad covering the segment (start, end) without
// any vertex buffer: 6 vertices, gl_VertexIndex picks the corner. The quad
// is positioned in world space along the beam direction; its perpendicular
// is computed view-aligned (perpendicular to camera-forward), so the
// visible width stays constant regardless of the camera's pose. Works for
// arbitrary cameras — public-API contract.

layout(set = 0, binding = 0) uniform UBO {
    mat4 view;
    mat4 proj;
    mat4 prev_view;
    mat4 prev_proj;
} ubo;

layout(push_constant) uniform PC {
    vec3  start;        // beam emission point (world)
    vec3  end;          // beam termination point (world)
    vec4  color;        // RGBA — passed through to fragment
    float width;        // perpendicular thickness in world units
    float time;         // elapsed seconds for pulse animation
} pc;

layout(location = 0) out vec2 vUV;  // (u along beam 0..1, v perpendicular -1..+1)

void main() {
    int idx = gl_VertexIndex;
    // 6 verts forming 2 triangles. (u, v) per corner:
    //   0: (0,-1)  start, -perp
    //   1: (1,-1)  end,   -perp
    //   2: (1,+1)  end,   +perp
    //   3: (0,-1)  start, -perp  (start of triangle 2)
    //   4: (1,+1)  end,   +perp
    //   5: (0,+1)  start, +perp
    vec2 corner;
    if      (idx == 0) corner = vec2(0.0, -1.0);
    else if (idx == 1) corner = vec2(1.0, -1.0);
    else if (idx == 2) corner = vec2(1.0,  1.0);
    else if (idx == 3) corner = vec2(0.0, -1.0);
    else if (idx == 4) corner = vec2(1.0,  1.0);
    else               corner = vec2(0.0,  1.0);

    vec3 dir = pc.end - pc.start;
    float beamLen = length(dir);
    vec3 dirN = (beamLen > 1e-6) ? dir / beamLen : vec3(0.0, 0.0, 1.0);

    // Camera-forward in world space, derived from view's third row.
    // Standard view matrix: row 2 of the upper 3x3 holds -forward.
    vec3 viewFwd = -vec3(ubo.view[0][2], ubo.view[1][2], ubo.view[2][2]);
    // View-aligned perpendicular: orthogonal to beam AND to view direction
    // (so the quad always presents its broad face to the camera).
    vec3 perpRaw = cross(dirN, viewFwd);
    float pl = length(perpRaw);
    // Degenerate case: beam parallel to view direction — pick any orthogonal.
    vec3 perp = (pl > 1e-6) ? perpRaw / pl : vec3(1.0, 0.0, 0.0);

    vec3 worldPos = mix(pc.start, pc.end, corner.x) + perp * (pc.width * 0.5) * corner.y;
    gl_Position = ubo.proj * ubo.view * vec4(worldPos, 1.0);
    vUV = corner;
}
