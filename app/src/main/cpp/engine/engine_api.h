#pragma once

#include <stddef.h>
#include <stdint.h>

typedef void* SurfaceHandle;
typedef struct StationEngine StationEngine;
typedef struct StationMesh   StationMesh;

#ifdef __cplusplus
extern "C" {
#endif

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

// Create engine instance. Platform-independent — no Android types.
StationEngine* station_engine_create();
void           station_engine_destroy(StationEngine* engine);

// ---------------------------------------------------------------------------
// Shaders — call before surface_created
// Kotlin reads .spv from assets, passes raw bytes here.
// name: "vert" or "frag"
// ---------------------------------------------------------------------------
void station_engine_set_shader(StationEngine* engine,
                               const char*    name,
                               const uint8_t* spv,
                               size_t         length);

// ---------------------------------------------------------------------------
// Surface
// ---------------------------------------------------------------------------
void station_engine_surface_created(StationEngine* engine, SurfaceHandle surface,
                                    int width, int height);
void station_engine_surface_destroyed(StationEngine* engine);
void station_engine_surface_changed(StationEngine* engine, int width, int height);

// ---------------------------------------------------------------------------
// Focus
// ---------------------------------------------------------------------------
void station_engine_resume(StationEngine* engine);
void station_engine_pause(StationEngine* engine);

// ---------------------------------------------------------------------------
// Mesh
// ---------------------------------------------------------------------------
StationMesh* station_engine_load_mesh(StationEngine* engine,
                                      const uint8_t* data, size_t length);
StationMesh* station_engine_load_mesh_colored(StationEngine* engine,
                                              const uint8_t* data, size_t length,
                                              float r, float g, float b);
void         station_engine_unload_mesh(StationEngine* engine, StationMesh* mesh);
void         station_engine_set_scene_mesh(StationEngine* engine, StationMesh* mesh);


// ---------------------------------------------------------------------------
// Scene — Kotlin owns the scene, engine just draws what it's told
// ---------------------------------------------------------------------------

// Clear the draw list for the next frame
void station_engine_begin_scene(StationEngine* engine);

// Queue one mesh instance with a model matrix (column-major float[16])
void station_engine_draw_mesh(StationEngine* engine,
                              StationMesh*   mesh,
                              const float    modelMatrix[16]);

// Submit the draw list — call once per frame after all draw_mesh calls
void station_engine_draw_pickable_mesh(StationEngine* engine,
                                       StationMesh*   mesh,
                                       int32_t        objectId,
                                       const float    modelMatrix[16],
                                       float          pickRadius);

void station_engine_draw_billboard_mesh(StationEngine* engine,
                                        StationMesh*   mesh,
                                        float          x,
                                        float          y,
                                        float          z,
                                        float          scale);

void station_engine_draw_plasma_billboard(StationEngine* engine,
                                          StationMesh*   mesh,
                                          float          x,
                                          float          y,
                                          float          z,
                                          float          scale);

void station_engine_draw_object_frame_mesh(StationEngine* engine,
                                           StationMesh*   frameMesh,
                                           StationMesh*   targetMesh,
                                           const float    modelMatrix[16],
                                           float          padding,
                                           const float    tint[4]);

void station_engine_draw_gameplay_frame_mesh(StationEngine* engine,
                                             StationMesh*   frameMesh,
                                             const float    modelMatrix[16],
                                             const float*   localPoints,
                                             int32_t        pointCount,
                                             float          padding,
                                             float          lineWidth,
                                             const float    tint[4]);

void station_engine_end_scene(StationEngine* engine);

int32_t station_engine_pick_object(StationEngine* engine,
                                   float          screenX,
                                   float          screenY,
                                   int32_t        currentObjectId);

bool station_engine_project_gameplay_bounds(StationEngine* engine,
                                            const float    modelMatrix[16],
                                            const float*   localPoints,
                                            int32_t        pointCount,
                                            float          padding,
                                            float          outBounds[7]);

bool station_engine_project_mesh_bounds(StationEngine* engine,
                                        StationMesh*   mesh,
                                        const float    modelMatrix[16],
                                        float          padding,
                                        float          outBounds[7]);

// ---------------------------------------------------------------------------
// Camera
// ---------------------------------------------------------------------------1
void station_engine_orbit_camera(StationEngine* engine, float deltaYaw, float deltaPitch);
void station_engine_roll_camera(StationEngine* engine, float deltaAngle);
void station_engine_pan_camera(StationEngine* engine, float dx, float dy);
void station_engine_zoom_camera(StationEngine* engine, float factor);
void station_engine_zoom_camera_at(StationEngine* engine,
                                   float factor,
                                   float screenX,
                                   float screenY);
void station_engine_reset_camera(StationEngine* engine);

// ---------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------
void station_engine_render_frame(StationEngine* engine);

#ifdef __cplusplus
}
#endif
