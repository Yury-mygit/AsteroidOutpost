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
    // E9 — separate particle shaders (different vertex attribute layout
    // because of per-instance binding 1). Shared with the main pair until
    // the pipeline is built.
    std::vector<uint32_t> particleVertSpv;
    std::vector<uint32_t> particleFragSpv;
    // E10.1 — post-process shaders (fullscreen triangle + texture sample).
    // Optional from the engine's POV — if not uploaded, motion blur path
    // is skipped and the render flow falls back to scene-direct (handled
    // by VulkanContext::createPipeline detecting empty SPV).
    std::vector<uint32_t> postVertSpv;
    std::vector<uint32_t> postFragSpv;
    // E14 — dedicated beam pipeline shaders (own pipeline layout).
    std::vector<uint32_t> beamVertSpv;
    std::vector<uint32_t> beamFragSpv;
    // E20 — force-field shield pipeline (own pipeline layout).
    std::vector<uint32_t> forceFieldVertSpv;
    std::vector<uint32_t> forceFieldFragSpv;
    // E18 — fullscreen background nebula. Optional; falls back to no
    // background draw if shaders aren't uploaded.
    std::vector<uint32_t> backgroundVertSpv;
    std::vector<uint32_t> backgroundFragSpv;
    bool pipelineCreated = false;
};

struct StationMesh {
    uint32_t token = 0;
};

struct StationTexture {
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
    } else if (std::string(name) == "particle.vert") {
        e->particleVertSpv = std::move(words);
        LOGI("Particle vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "particle.frag") {
        e->particleFragSpv = std::move(words);
        LOGI("Particle fragment shader set (%zu bytes)", length);
    } else if (std::string(name) == "post.vert") {
        e->postVertSpv = std::move(words);
        LOGI("Post vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "post.frag") {
        e->postFragSpv = std::move(words);
        LOGI("Post fragment shader set (%zu bytes)", length);
    } else if (std::string(name) == "beam.vert") {
        e->beamVertSpv = std::move(words);
        LOGI("Beam vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "beam.frag") {
        e->beamFragSpv = std::move(words);
        LOGI("Beam fragment shader set (%zu bytes)", length);
    } else if (std::string(name) == "forcefield.vert") {
        e->forceFieldVertSpv = std::move(words);
        LOGI("Forcefield vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "forcefield.frag") {
        e->forceFieldFragSpv = std::move(words);
        LOGI("Forcefield fragment shader set (%zu bytes)", length);
    } else if (std::string(name) == "background.vert") {
        e->backgroundVertSpv = std::move(words);
        LOGI("Background vertex shader set (%zu bytes)", length);
    } else if (std::string(name) == "background.frag") {
        e->backgroundFragSpv = std::move(words);
        LOGI("Background fragment shader set (%zu bytes)", length);
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
    // Create pipeline once — requires main shader pair plus, optionally,
    // particle shader pair (E9). Particle pipelines are skipped if their
    // shaders weren't uploaded — the rest of the engine still works.
    if (!e->pipelineCreated) {
        if (e->vertSpv.empty() || e->fragSpv.empty()) {
            LOGE("Cannot create pipeline: shaders not set yet");
            return;
        }
        if (e->vulkan.createPipeline(e->vertSpv, e->fragSpv,
                                     e->particleVertSpv, e->particleFragSpv,
                                     e->postVertSpv, e->postFragSpv,
                                     e->beamVertSpv, e->beamFragSpv,
                                     e->backgroundVertSpv, e->backgroundFragSpv,
                                     e->forceFieldVertSpv, e->forceFieldFragSpv)) {
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

extern "C" StationMesh* station_engine_load_mesh_raw_uv(StationEngine* e,
                                                        const float*    vertices,
                                                        int32_t         vertexCount,
                                                        const uint16_t* indices,
                                                        int32_t         indexCount) {
    if (!e || !vertices || !indices || vertexCount <= 0 || indexCount <= 0) return nullptr;
    station::MeshData meshData;
    meshData.vertices.resize((size_t)vertexCount);
    // Each input vertex: 12 floats — pos(3) + RGBA(4) + normal(3) + uv(2).
    // The internal Vertex layout matches one-to-one (E8.1 widened it).
    for (int32_t i = 0; i < vertexCount; ++i) {
        const float* src = vertices + (size_t)i * 12;
        station::Vertex& v = meshData.vertices[i];
        v.position[0] = src[0];  v.position[1] = src[1];  v.position[2] = src[2];
        v.color[0]    = src[3];  v.color[1]    = src[4];  v.color[2]    = src[5];  v.color[3] = src[6];
        v.normal[0]   = src[7];  v.normal[1]   = src[8];  v.normal[2]   = src[9];
        v.uv[0]       = src[10]; v.uv[1]       = src[11];
    }
    meshData.indices.assign(indices, indices + indexCount);
    uint32_t token = e->vulkan.uploadMesh(meshData);
    if (!token) { LOGE("load_mesh_raw_uv: uploadMesh failed"); return nullptr; }
    auto* mesh = new StationMesh();
    mesh->token = token;
    LOGI("Mesh loaded (raw uv), token=%u, %d verts, %d idx", token, vertexCount, indexCount);
    return mesh;
}

extern "C" StationTexture* station_engine_load_texture(StationEngine* e,
                                                       const uint8_t* pngBytes,
                                                       size_t         length) {
    if (!e || !pngBytes || !length) return nullptr;
    uint32_t token = e->vulkan.uploadTexture(pngBytes, (uint32_t)length);
    if (!token) { LOGE("load_texture: uploadTexture failed"); return nullptr; }
    auto* tex = new StationTexture();
    tex->token = token;
    LOGI("Texture loaded, token=%u", token);
    return tex;
}

extern "C" void station_engine_unload_texture(StationEngine*  e,
                                              StationTexture* tex) {
    if (!e || !tex) return;
    e->vulkan.freeTexture(tex->token);
    delete tex;
}

extern "C" StationTexture* station_engine_load_texture_raw(StationEngine* e,
                                                           const uint8_t* rgba8,
                                                           int32_t        width,
                                                           int32_t        height) {
    if (!e || !rgba8 || width <= 0 || height <= 0) return nullptr;
    uint32_t token = e->vulkan.uploadTextureRaw(rgba8, (uint32_t)width, (uint32_t)height);
    if (!token) { LOGE("load_texture_raw: uploadTextureRaw failed"); return nullptr; }
    auto* tex = new StationTexture();
    tex->token = token;
    LOGI("Texture loaded (raw), token=%u, %dx%d", token, width, height);
    return tex;
}

extern "C" StationMesh* station_engine_load_mesh_raw(StationEngine* e,
                                                     const float*    vertices,
                                                     int32_t         vertexCount,
                                                     const uint16_t* indices,
                                                     int32_t         indexCount) {
    if (!e || !vertices || !indices || vertexCount <= 0 || indexCount <= 0) return nullptr;
    station::MeshData meshData;
    meshData.vertices.resize((size_t)vertexCount);
    // Each input vertex: 10 floats — pos(3) + RGBA(4) + normal(3). The internal
    // Vertex struct also has uv(2) since E8.1, but procedural callers don't
    // pass UVs (these are untextured procedural meshes — soft-disk nebulae,
    // shield dome, fireball sphere). uv is explicitly zeroed so the textured
    // fragment branch (E8.3+) gets a deterministic (0,0) lookup if the mesh
    // is ever bound textured (it shouldn't be, but defensively zero).
    // If procedural meshes ever need UVs, add a parallel `load_mesh_raw_uv`
    // (12 floats per vertex) rather than overloading this signature.
    for (int32_t i = 0; i < vertexCount; ++i) {
        const float* src = vertices + (size_t)i * 10;
        station::Vertex& v = meshData.vertices[i];
        v.position[0] = src[0]; v.position[1] = src[1]; v.position[2] = src[2];
        v.color[0]    = src[3]; v.color[1]    = src[4]; v.color[2]    = src[5]; v.color[3] = src[6];
        v.normal[0]   = src[7]; v.normal[1]   = src[8]; v.normal[2]   = src[9];
        v.uv[0]       = 0.0f;   v.uv[1]       = 0.0f;
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
                                         const float    modelMatrix[16],
                                         const float    prevModelMatrix[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawMesh(mesh->token, modelMatrix, prevModelMatrix);
}

extern "C" void station_engine_draw_pickable_mesh(StationEngine* e,
                                                  StationMesh*   mesh,
                                                  int32_t        objectId,
                                                  const float    modelMatrix[16],
                                                  float          pickRadius,
                                                  const float    prevModelMatrix[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawPickableMesh(mesh->token, objectId, modelMatrix, pickRadius, prevModelMatrix);
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
                                                     float r, float g, float b, float a,
                                                     float rotation,
                                                     float lightningSeed) {
    if (!e || !mesh) return;
    e->vulkan.drawPlasmaBillboard(mesh->token, x, y, z, scaleH, scaleV,
                                  r, g, b, a, rotation, lightningSeed);
}

extern "C" void station_engine_draw_textured_mesh(StationEngine* e,
                                                  StationMesh*    mesh,
                                                  StationTexture* texture,
                                                  const float     modelMatrix[16],
                                                  float r, float g, float b, float a,
                                                  const float     prevModelMatrix[16]) {
    if (!e || !mesh || !texture) return;
    e->vulkan.drawTexturedMesh(mesh->token, texture->token, modelMatrix,
                               r, g, b, a, prevModelMatrix);
}

extern "C" void station_engine_draw_particles(StationEngine*  e,
                                              StationMesh*    mesh,
                                              StationTexture* texture,
                                              const float*    instanceFloats,
                                              int32_t         count,
                                              int32_t         mode) {
    if (!e || !mesh || !instanceFloats || count <= 0) return;
    uint32_t texToken = texture ? texture->token : 0;
    e->vulkan.drawParticles(mesh->token, texToken, instanceFloats,
                            (uint32_t)count, mode);
}

extern "C" void station_engine_draw_translucent_mesh(StationEngine* e,
                                                     StationMesh*   mesh,
                                                     const float    modelMatrix[16],
                                                     int32_t        material,
                                                     const float    prevModelMatrix[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawTranslucentMesh(mesh->token, modelMatrix, material, prevModelMatrix);
}

extern "C" void station_engine_draw_additive_mesh(StationEngine* e,
                                                  StationMesh*   mesh,
                                                  const float    modelMatrix[16],
                                                  float r, float g, float b, float a,
                                                  int32_t material,
                                                  const float    prevModelMatrix[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawAdditiveMesh(mesh->token, modelMatrix, r, g, b, a, material, prevModelMatrix);
}

extern "C" void station_engine_draw_laser_beam(StationEngine* e,
                                               float startX, float startY, float startZ,
                                               float endX,   float endY,   float endZ,
                                               float width,
                                               float r, float g, float b, float a) {
    if (!e) return;
    e->vulkan.drawLaserBeam(startX, startY, startZ,
                            endX,   endY,   endZ,
                            width, r, g, b, a);
}

extern "C" void station_engine_draw_force_field(StationEngine* e,
                                                StationMesh*   mesh,
                                                float cx, float cy, float cz, float radius,
                                                const float    impacts[16]) {
    if (!e || !mesh) return;
    e->vulkan.drawForceField(mesh->token, cx, cy, cz, radius, impacts);
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
extern "C" void station_engine_set_camera_target(StationEngine* e, float x, float y, float z) {
    if (e) e->vulkan.setCameraTarget(x, y, z);
}

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------
extern "C" void station_engine_render_frame(StationEngine* e) {
    if (e) e->vulkan.renderFrame();
}
