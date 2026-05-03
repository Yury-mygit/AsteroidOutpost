# cpp/

Native C++ layer. Contains CMakeLists.txt and two subdirectories.

## CMakeLists.txt

Builds `libstationcore.so`. Source list:
- `android/EngineJni.cpp` — JNI entry point
- `engine/engine_api.cpp` — public C API
- `engine/platform/PlatformAndroid.cpp` — Vulkan surface creation
- `engine/VulkanContext.cpp`, `RenderResources.cpp`, `Mesh.cpp`, `ShipMesh.cpp`, `Camera.cpp`, `GltfLoader.cpp`

No `game-activity` dependency. Links: `android`, `log`, `vulkan`.

## Subdirectories

| Dir | Purpose |
|---|---|
| `android/` | JNI bridge — translates Java calls to C API |
| `engine/` | Portable Vulkan engine — knows nothing about Android UI |