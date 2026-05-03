# cpp/engine/

Portable Vulkan engine. No Android-specific code except in `platform/`.
Namespace: `station`. All public access goes through `engine_api.h` (C API).

## Public boundary

**`engine_api.h`** — the only file that should be included outside this folder.
Defines opaque types `StationEngine*` and `StationMesh*`.

## Files

| File | Purpose |
|---|---|
| `engine_api.h/cpp` | Clean C public API. All extern "C" functions. |
| `VulkanContext.h/cpp` | Full Vulkan lifecycle. Holds device, swapchain, mesh/star/system/plasma/frame pipelines, mesh pool, camera. Surface received as `void*` nativeHandle. |
| `RenderResources.h/cpp` | Render pass, framebuffers, depth buffer, command pool, sync objects. |
| `Mesh.h/cpp` | `Vertex` struct (position+color+normal), `MeshData`, `MeshGpu` (GPU buffers). |
| `ShipMesh.h/cpp` | Hardcoded fallback ship mesh with computed smooth normals. |
| `Camera.h/cpp` | Quaternion orbit camera. orbit/pan/roll/zoom/reset. Initial state: target={8,28,0}, radius=52, pitch=1.05 rad. zoom() moves target along forward (dolly), not radius. Projection supports dynamic far clip from current scene bounds. |
| `GltfLoader.h/cpp` | Loads GLTF/GLB from memory bytes. Reads POSITION, NORMAL, COLOR_0. |

## Subdirectories

| Dir | Purpose |
|---|---|
| `math/` | `Mat4.h` (column-major), `Quat.h` (quaternion). Header-only. |
| `platform/` | `Platform.h` + `PlatformAndroid.cpp` — VkSurface creation from ANativeWindow. |
| `thirdparty/` | `tiny_gltf.h`, `json.hpp`, `stb_image.h`, `stb_image_write.h`. Do not modify. |

## Vertex layout (location binding)

| location | attribute | format |
|---|---|---|
| 0 | position | VEC3 float |
| 1 | color | VEC3 float |
| 2 | normal | VEC3 float |

## UBO layout (binding 0)

```glsl
layout(set=0, binding=0) uniform Ubo {
    mat4 view;
    mat4 proj;
};
```

## Push constants

```glsl
// vertex shader:
layout(push_constant) uniform PushConst { mat4 model; };   // 64 bytes

// fragment shader:
layout(push_constant) uniform PushConst {
    layout(offset = 64) vec4 tint;  // 16 bytes
};
// stageFlags = VERTEX | FRAGMENT
```

Tint пробрасывается в шейдер, но текущий фрагментный шейдер определяет рамки и плазму по вершинному цвету.
`station_engine_load_mesh_colored(r,g,b)` — перекрашивает вершины в CPU до GPU upload.

## Mesh pool
`VulkanContext` holds a fixed pool of 64 `MeshGpu` slots.
`uploadMesh()` returns a token (1-based index). `0` = failure.
Stars are a separate `MeshGpu m_starMesh` outside the pool.

## Scene API (draw list)
Engine does NOT decide what to draw. Kotlin submits a draw list each frame:
```
beginScene()                                           → clears m_drawList
drawMesh(token, mat4)                                  → appends DrawCommand
drawPickableMesh(token, id, mat4, radius)              → drawMesh + stores pick record
drawBillboardMesh(token, x,y,z, scale)                 → camera-facing DrawCommand
drawPlasmaBillboard(token, x,y,z, scale)               → additive-blend camera-facing DrawCommand
drawObjectFrameMesh(frameToken, targetToken, mat4,     → bounds-attached system frame
                    padding, tint[4])
endScene()                                             → marks list ready
renderFrame()                                          → issues vkCmdDrawIndexed per entry
```

`drawGameplayFrameMesh(frameToken, mat4, points, pointCount, padding, lineWidth, tint[4])` submits a rectangular system frame from Kotlin gameplay shape points.

Gameplay frames are rectangular UI markers. Native projects the submitted Kotlin gameplay points, computes their screen-space bounds, and places the existing frame mesh around those bounds. The marker is not a contour renderer.

Plasma billboards are rendered through a separate additive-blend pipeline. Kotlin currently uses them for attack projectiles and explosion flashes.

Before uploading the UBO each frame, `VulkanContext` computes `zFar` from the submitted scene bounds and clamps it to a sane range. This keeps distant stations inside the frustum without permanently widening the depth range.

## Camera API
```cpp
void Camera::orbit(float deltaYaw, float deltaPitch)  // orbit around target
void Camera::roll(float deltaAngle)                   // rotate around forward axis
void Camera::pan(float dx, float dy)                  // translate target in screen plane
void Camera::zoom(float factor)                       // dolly: move target along forward
void Camera::zoomAt(float factor, float sx, float sy) // dolly around a screen point
void Camera::reset()                                  // restore initial state
```

## Picking

Picking is approximate and id-based. Kotlin assigns object ids and submits pickable meshes each frame. Native stores object center + radius, projects them with the current camera, sorts hits front-to-back, and returns the next id after the current selection. `-1` means no selected object.
