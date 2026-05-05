#include "engine_api.h"

#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#include "GltfLoader.h"
#include "VulkanContext.h"

#define LOG_TAG "stationcore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Internal structs
// ---------------------------------------------------------------------------
struct StationEngine {
    station::VulkanContext vulkan;

    // SPV shader bytes — stored until pipeline creation
    std::vector<uint32_t> vertSpv;
    std::vector<uint32_t> fragSpv;
    bool pipelineCreated = false;
};

struct StationMesh {
    uint32_t token = 0;
};

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
extern "C" StationEngine* station_engine_create() {
    auto* e = new StationEngine();
    if (!e->vulkan.initDevice()) {
        LOGE("station_engine_create: initDevice failed");
        delete e;
        return nullptr;
    }
    LOGI("Engine created");
    return e;
}

extern "C" void station_engine_destroy(StationEngine* e) {
    if (!e) return;
    e->vulkan.destroy();
    delete e;
    LOGI("Engine destroyed");
}

// ---------------------------------------------------------------------------
// Shaders
// ---------------------------------------------------------------------------
extern "C" void station_engine_set_shader(StationEngine* e,
                                          const char*    name,
                                          const uint8_t* spv,
                                          size_t         length) {
    if (!e || !spv || length == 0 || (length % 4) != 0) return;

    std::vector<uint32_t> words(length / 4);
    std::memcpy(words.data(), spv, length);

    if (std::string(name) == "vert") {
        e->vertSpv = std::move(words);
        LOGI("Vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "frag") {
        e->fragSpv = std::move(words);
        LOGI("Fragment shader set (%zu bytes)", length);
    } else {
        LOGE("set_shader: unknown name '%s'", name);
    }
}

// ---------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------
extern "C" void station_engine_surface_created(StationEngine* e,
                                               SurfaceHandle surface,
                                               int width, int height) {
    if (!e) return;
    if (!e->vulkan.createSurface(surface, width, height)) {
        LOGE("createSurface failed"); return;
    }
    // Create pipeline once — requires both shaders to be set
    if (!e->pipelineCreated) {
        if (e->vertSpv.empty() || e->fragSpv.empty()) {
            LOGE("Cannot create pipeline: shaders not set yet");
            return;
        }
        if (e->vulkan.createPipeline(e->vertSpv, e->fragSpv)) {
            e->pipelineCreated = true;
        } else {
            LOGE("createPipeline failed");
        }
    }
}

extern "C" void station_engine_surface_destroyed(StationEngine* e) {
    if (e) e->vulkan.destroySurface();
}

extern "C" void station_engine_surface_changed(StationEngine* e, int width, int height) {
    if (e) e->vulkan.createSurface(nullptr, width, height);
}

// ---------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------
extern "C" void station_engine_resume(StationEngine* e) { if (e) e->vulkan.setFocused(true); }
extern "C" void station_engine_pause(StationEngine* e)  { if (e) e->vulkan.setFocused(false); }

// ---------------------------------------------------------------------------
// Mesh
// ---------------------------------------------------------------------------
extern "C" StationMesh* station_engine_load_mesh(StationEngine* e,
                                                 const uint8_t* data, size_t length) {
    if (!e || !data || !length) return nullptr;
    station::MeshData meshData;
    if (!station::GltfLoader::loadFromMemory(data, length, meshData)) {
        LOGE("load_mesh: GltfLoader failed"); return nullptr;
    }
    uint32_t token = e->vulkan.uploadMesh(meshData);
    if (!token) { LOGE("load_mesh: uploadMesh failed"); return nullptr; }
    auto* mesh = new StationMesh();
    mesh->token = token;
    LOGI("Mesh loaded, token=%u", token);
    return mesh;
}

extern "C" StationMesh* station_engine_load_mesh_colored(StationEngine* e,
                                                         const uint8_t* data, size_t length,
                                                         float r, float g, float b) {
    if (!e || !data || !length) return nullptr;
    station::MeshData meshData;
    if (!station::GltfLoader::loadFromMemory(data, length, meshData)) {
        LOGE("load_mesh_colored: GltfLoader failed"); return nullptr;
    }
    for (auto& v : meshData.vertices) {
        v.color[0] = r;
        v.color[1] = g;
        v.color[2] = b;
        v.color[3] = 1.0f;  // opaque — see load_mesh_colored_alpha for translucent variant
    }
    uint32_t token = e->vulkan.uploadMesh(meshData);
    if (!token) { LOGE("load_mesh_colored: uploadMesh failed"); return nullptr; }
    auto* mesh = new StationMesh();
    mesh->token = token;
    LOGI("Mesh loaded (colored), token=%u", token);
    return mesh;
}

extern "C" StationMesh* station_engine_load_mesh_raw(StationEngine* e,
                                                     const float*    vertices,
                                                     int32_t         vertexCount,
                                                     const uint16_t* indices,
                                                     int32_t         indexCount) {
    if (!e || !vertices || !indices || vertexCount <= 0 || indexCount <= 0) return nullptr;
    station::MeshData meshData;
    meshData.vertices.resize((size_t)vertexCount);
    // Each vertex: 10 floats — pos(3) + RGBA(4) + normal(3).
    for (int32_t i = 0; i < vertexCount; ++i) {
        const float* src = vertices + (size_t)i * 10;
        station::Vertex& v = meshData.vertices[i];
        v.position[0] = src[0]; v.position[1] = src[1]; v.position[2] = src[2];
        v.color[0]    = src[3]; v.color[1]    = src[4]; v.color[2]    = src[5]; v.color[3] = src[6];
        v.normal[0]   = src[7]; v.normal[1]   = src[8]; v.normal[2]   = src[9];
    }
    meshData.indices.assign(indices, indices + indexCount);
    uint32_t token = e->vulkan.uploadMesh(meshData);
    if (!token) { LOGE("load_mesh_raw: uploadMesh failed"); return nullptr; }
    auto* mesh = new StationMesh();
    mesh->token = token;
    LOGI("Mesh loaded (raw), token=%u, %d verts, %d idx", token, vertexCount, indexCount);
    return mesh;
}

extern "C" void station_engine_unload_mesh(StationEngine* e, StationMesh* mesh) {
    if (!e || !mesh) return;
    e->vulkan.freeMesh(mesh->token);
    delete mesh;
}




// ---------------------------------------------------------------------------
// Scene
// ---------------------------------------------------------------------------
extern "C" void station_engine_begin_scene(StationEngine* e) {
    if (e) e->vulkan.beginScene();
}

extern "C" void station_engine_draw_mesh(StationEngine* e,
                                         StationMesh*   mesh,
                                         const float    modelMatrix[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawMesh(mesh->token, modelMatrix);
}

extern "C" void station_engine_draw_pickable_mesh(StationEngine* e,
                                                  StationMesh*   mesh,
                                                  int32_t        objectId,
                                                  const float    modelMatrix[16],
                                                  float          pickRadius) {
    if (!e || !mesh) return;
    e->vulkan.drawPickableMesh(mesh->token, objectId, modelMatrix, pickRadius);
}

extern "C" void station_engine_draw_billboard_mesh(StationEngine* e,
                                                   StationMesh*   mesh,
                                                   float          x,
                                                   float          y,
                                                   float          z,
                                                   float          scale) {
    if (!e || !mesh) return;
    e->vulkan.drawBillboardMesh(mesh->token, x, y, z, scale);
}

extern "C" void station_engine_draw_plasma_billboard(StationEngine* e,
                                                     StationMesh*   mesh,
                                                     float x, float y, float z,
                                                     float scaleH, float scaleV,
                                                     float r, float g, float b, float a) {
    if (!e || !mesh) return;
    e->vulkan.drawPlasmaBillboard(mesh->token, x, y, z, scaleH, scaleV, r, g, b, a);
}

extern "C" void station_engine_draw_translucent_mesh(StationEngine* e,
                                                     StationMesh*   mesh,
                                                     const float    modelMatrix[16],
                                                     int32_t        material) {
    if (!e || !mesh) return;
    e->vulkan.drawTranslucentMesh(mesh->token, modelMatrix, material);
}

extern "C" void station_engine_draw_object_frame_mesh(StationEngine* e,
                                                      StationMesh*   frameMesh,
                                                      StationMesh*   targetMesh,
                                                      const float    modelMatrix[16],
                                                      float          padding,
                                                      const float    tint[4]) {
    if (!e || !frameMesh || !targetMesh) return;
    e->vulkan.drawObjectFrameMesh(frameMesh->token, targetMesh->token, modelMatrix, padding, tint);
}

extern "C" void station_engine_draw_gameplay_frame_mesh(StationEngine* e,
                                                        StationMesh*   frameMesh,
                                                        const float    modelMatrix[16],
                                                        const float*   localPoints,
                                                        int32_t        pointCount,
                                                        float          padding,
                                                        float          lineWidth,
                                                        const float    tint[4]) {
    if (!e || !frameMesh || !localPoints || pointCount <= 0) return;
    e->vulkan.drawGameplayFrameMesh(frameMesh->token, modelMatrix, localPoints,
                                    pointCount, padding, lineWidth, tint);
}

extern "C" void station_engine_end_scene(StationEngine* e) {
    if (e) e->vulkan.endScene();
}

extern "C" int32_t station_engine_pick_object(StationEngine* e,
                                              float          screenX,
                                              float          screenY,
                                              int32_t        currentObjectId) {
    if (!e) return -1;
    return e->vulkan.pickObject(screenX, screenY, currentObjectId);
}

extern "C" bool station_engine_project_gameplay_bounds(StationEngine* e,
                                                       const float    modelMatrix[16],
                                                       const float*   localPoints,
                                                       int32_t        pointCount,
                                                       float          padding,
                                                       float          outBounds[7]) {
    if (!e || !modelMatrix || !localPoints || !outBounds || pointCount <= 0) return false;
    return e->vulkan.projectGameplayBounds(modelMatrix, localPoints, pointCount, padding, outBounds);
}

extern "C" bool station_engine_project_mesh_bounds(StationEngine* e,
                                                   StationMesh*   mesh,
                                                   const float    modelMatrix[16],
                                                   float          padding,
                                                   float          outBounds[7]) {
    if (!e || !mesh || !modelMatrix || !outBounds) return false;
    return e->vulkan.projectMeshBounds(mesh->token, modelMatrix, padding, outBounds);
}

// ---------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------
extern "C" void station_engine_orbit_camera(StationEngine* e, float dy, float dp) {
    if (e) e->vulkan.orbitCamera(dy, dp);
}
extern "C" void station_engine_roll_camera(StationEngine* e, float angle) {
    if (e) e->vulkan.rollCamera(angle);
}
extern "C" void station_engine_pan_camera(StationEngine* e, float dx, float dy) {
    if (e) e->vulkan.panCamera(dx, dy);
}
extern "C" void station_engine_zoom_camera(StationEngine* e, float factor) {
    if (e) e->vulkan.zoomCamera(factor);
}
extern "C" void station_engine_zoom_camera_at(StationEngine* e, float factor,
                                              float screenX, float screenY) {
    if (e) e->vulkan.zoomCameraAt(factor, screenX, screenY);
}
extern "C" void station_engine_reset_camera(StationEngine* e) {
    if (e) e->vulkan.resetCamera();
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------
extern "C" void station_engine_render_frame(StationEngine* e) {
    if (e) e->vulkan.renderFrame();
}
