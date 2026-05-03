# cpp/android/

JNI bridge between Kotlin and the C engine API.

## Files

### EngineJni.cpp

Implements all `Java_com_example_g3_EngineJni_native*` functions.
Each function maps 1:1 to a `station_engine_*` call in `engine_api.h`.

| JNI method | Engine API |
|---|---|
| `nativeCreate` | `station_engine_create` |
| `nativeDestroy` | `station_engine_destroy` |
| `nativeSetShader` | `station_engine_set_shader` |
| `nativeSurfaceCreated` | `station_engine_surface_created` |
| `nativeSurfaceDestroyed` | `station_engine_surface_destroyed` |
| `nativeSurfaceChanged` | `station_engine_surface_changed` |
| `nativeResume/Pause` | `station_engine_resume/pause` |
| `nativeLoadMesh` | `station_engine_load_mesh` |
| `nativeLoadMeshColored(handle, data, r, g, b)` | `station_engine_load_mesh_colored` |
| `nativeUnloadMesh` | `station_engine_unload_mesh` |
| `nativeBeginScene` | `station_engine_begin_scene` |
| `nativeDrawMesh` | `station_engine_draw_mesh` |
| `nativeDrawPickableMesh` | `station_engine_draw_pickable_mesh` |
| `nativeDrawBillboardMesh` | `station_engine_draw_billboard_mesh` |
| `nativeDrawPlasmaBillboard` | `station_engine_draw_plasma_billboard` |
| `nativeDrawObjectFrameMesh(engineHandle, frameMeshHandle, targetMeshHandle, modelMatrix, padding, tint[4])` | `station_engine_draw_object_frame_mesh` |
| `nativeDrawGameplayFrameMesh(engineHandle, frameMeshHandle, modelMatrix, localPoints, pointCount, padding, lineWidth, tint[4])` | `station_engine_draw_gameplay_frame_mesh` |
| `nativeEndScene` | `station_engine_end_scene` |
| `nativePickObject` | `station_engine_pick_object` |
| `nativeProjectGameplayBounds` | `station_engine_project_gameplay_bounds` |
| `nativeProjectMeshBounds` | `station_engine_project_mesh_bounds` |
| `nativeOrbitCamera` | `station_engine_orbit_camera` |
| `nativeRollCamera` | `station_engine_roll_camera` |
| `nativePanCamera` | `station_engine_pan_camera` |
| `nativeZoomCamera` | `station_engine_zoom_camera` |
| `nativeZoomCameraAt` | `station_engine_zoom_camera_at` |
| `nativeResetCamera` | `station_engine_reset_camera` |
| `nativeRenderFrame` | `station_engine_render_frame` |

## Rule

When adding a new engine API function, add the corresponding JNI function here and add an `external fun` declaration in `EngineJni.kt`.
