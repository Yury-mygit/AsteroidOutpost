#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>

#include "engine/engine_api.h"

#define LOG_TAG "stationcore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeCreate(JNIEnv* /*env*/, jobject /*thiz*/) {
    return reinterpret_cast<jlong>(station_engine_create());
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDestroy(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    station_engine_destroy(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeSetShader(JNIEnv* env, jobject /*thiz*/,
                                              jlong handle, jstring name, jbyteArray spv) {
    if (!spv) return;
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    jsize  len   = env->GetArrayLength(spv);
    jbyte* bytes = env->GetByteArrayElements(spv, nullptr);
    if (bytes) {
        station_engine_set_shader(reinterpret_cast<StationEngine*>(handle),
                                  nameStr,
                                  reinterpret_cast<const uint8_t*>(bytes),
                                  static_cast<size_t>(len));
        env->ReleaseByteArrayElements(spv, bytes, JNI_ABORT);
    }
    env->ReleaseStringUTFChars(name, nameStr);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeSurfaceCreated(JNIEnv* env, jobject /*thiz*/,
                                                   jlong handle, jobject surface,
                                                   jint width, jint height) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    station_engine_surface_created(reinterpret_cast<StationEngine*>(handle),
                                   win, (int)width, (int)height);
    ANativeWindow_release(win);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeSurfaceDestroyed(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jlong handle) {
    station_engine_surface_destroyed(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeSurfaceChanged(JNIEnv* /*env*/, jobject /*thiz*/,
                                                   jlong handle, jint w, jint h) {
    station_engine_surface_changed(reinterpret_cast<StationEngine*>(handle), w, h);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeResume(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    station_engine_resume(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativePause(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    station_engine_pause(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT jlong JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeLoadMesh(JNIEnv* env, jobject /*thiz*/,
                                             jlong handle, jbyteArray data) {
    if (!data) return 0L;
    jsize  len   = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0L;
    StationMesh* mesh = station_engine_load_mesh(
            reinterpret_cast<StationEngine*>(handle),
            reinterpret_cast<const uint8_t*>(bytes), (size_t)len);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return reinterpret_cast<jlong>(mesh);
}

JNIEXPORT jlong JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeLoadMeshColored(JNIEnv* env, jobject /*thiz*/,
                                                     jlong handle, jbyteArray data,
                                                     jfloat r, jfloat g, jfloat b) {
    if (!data) return 0L;
    jsize  len   = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return 0L;
    StationMesh* mesh = station_engine_load_mesh_colored(
            reinterpret_cast<StationEngine*>(handle),
            reinterpret_cast<const uint8_t*>(bytes), (size_t)len,
            r, g, b);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return reinterpret_cast<jlong>(mesh);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeUnloadMesh(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jlong engineHandle, jlong meshHandle) {
    station_engine_unload_mesh(reinterpret_cast<StationEngine*>(engineHandle),
                               reinterpret_cast<StationMesh*>(meshHandle));
}

JNIEXPORT jlong JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeLoadMeshRaw(JNIEnv* env, jobject /*thiz*/,
                                                  jlong handle,
                                                  jfloatArray vertices,
                                                  jshortArray indices) {
    if (!vertices || !indices) return 0L;
    jsize vlen = env->GetArrayLength(vertices);
    jsize ilen = env->GetArrayLength(indices);
    // 10 floats per vertex (pos3 + rgba4 + normal3); reject malformed lengths early.
    if (vlen <= 0 || ilen <= 0 || (vlen % 10) != 0) return 0L;
    int32_t vertexCount = vlen / 10;

    jfloat* vBytes = env->GetFloatArrayElements(vertices, nullptr);
    jshort* iBytes = env->GetShortArrayElements(indices, nullptr);
    if (!vBytes || !iBytes) {
        if (vBytes) env->ReleaseFloatArrayElements(vertices, vBytes, JNI_ABORT);
        if (iBytes) env->ReleaseShortArrayElements(indices, iBytes, JNI_ABORT);
        return 0L;
    }
    StationMesh* mesh = station_engine_load_mesh_raw(
            reinterpret_cast<StationEngine*>(handle),
            reinterpret_cast<const float*>(vBytes), vertexCount,
            reinterpret_cast<const uint16_t*>(iBytes), ilen);
    env->ReleaseFloatArrayElements(vertices, vBytes, JNI_ABORT);
    env->ReleaseShortArrayElements(indices, iBytes, JNI_ABORT);
    return reinterpret_cast<jlong>(mesh);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeBeginScene(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    station_engine_begin_scene(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawMesh(JNIEnv* env, jobject /*thiz*/,
                                             jlong engineHandle, jlong meshHandle,
                                             jfloatArray modelMatrix) {
    if (!modelMatrix) return;
    jfloat* m = env->GetFloatArrayElements(modelMatrix, nullptr);
    if (!m) return;
    station_engine_draw_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            reinterpret_cast<const float*>(m)
    );
    env->ReleaseFloatArrayElements(modelMatrix, m, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawPickableMesh(JNIEnv* env, jobject /*thiz*/,
                                                     jlong engineHandle, jlong meshHandle,
                                                     jint objectId, jfloatArray modelMatrix,
                                                     jfloat pickRadius) {
    if (!modelMatrix) return;
    jfloat* m = env->GetFloatArrayElements(modelMatrix, nullptr);
    if (!m) return;
    station_engine_draw_pickable_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            objectId,
            reinterpret_cast<const float*>(m),
            pickRadius
    );
    env->ReleaseFloatArrayElements(modelMatrix, m, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawBillboardMesh(JNIEnv* /*env*/, jobject /*thiz*/,
                                                      jlong engineHandle, jlong meshHandle,
                                                      jfloat x, jfloat y, jfloat z,
                                                      jfloat scale) {
    station_engine_draw_billboard_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            x, y, z, scale
    );
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawPlasmaBillboard(JNIEnv* /*env*/, jobject /*thiz*/,
                                                        jlong engineHandle, jlong meshHandle,
                                                        jfloat x, jfloat y, jfloat z,
                                                        jfloat scale) {
    station_engine_draw_plasma_billboard(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            x, y, z, scale
    );
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawTranslucentMesh(JNIEnv* env, jobject /*thiz*/,
                                                        jlong engineHandle, jlong meshHandle,
                                                        jfloatArray modelMatrix) {
    if (!modelMatrix) return;
    jfloat* m = env->GetFloatArrayElements(modelMatrix, nullptr);
    if (!m) return;
    station_engine_draw_translucent_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            reinterpret_cast<const float*>(m)
    );
    env->ReleaseFloatArrayElements(modelMatrix, m, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawObjectFrameMesh(JNIEnv* env, jobject /*thiz*/,
                                                        jlong engineHandle, jlong frameMeshHandle,
                                                        jlong targetMeshHandle,
                                                        jfloatArray modelMatrix,
                                                        jfloat padding,
                                                        jfloatArray tint) {
    if (!modelMatrix || !tint) return;
    jfloat* m = env->GetFloatArrayElements(modelMatrix, nullptr);
    jfloat* t = env->GetFloatArrayElements(tint, nullptr);
    if (!m || !t) {
        if (m) env->ReleaseFloatArrayElements(modelMatrix, m, JNI_ABORT);
        if (t) env->ReleaseFloatArrayElements(tint, t, JNI_ABORT);
        return;
    }
    station_engine_draw_object_frame_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(frameMeshHandle),
            reinterpret_cast<StationMesh*>(targetMeshHandle),
            reinterpret_cast<const float*>(m),
            padding,
            reinterpret_cast<const float*>(t)
    );
    env->ReleaseFloatArrayElements(modelMatrix, m, JNI_ABORT);
    env->ReleaseFloatArrayElements(tint, t, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeDrawGameplayFrameMesh(JNIEnv* env, jobject /*thiz*/,
                                                          jlong engineHandle, jlong frameMeshHandle,
                                                          jfloatArray modelMatrix,
                                                          jfloatArray localPoints,
                                                          jint pointCount,
                                                          jfloat padding,
                                                          jfloat lineWidth,
                                                          jfloatArray tint) {
    if (!modelMatrix || !localPoints || !tint || pointCount <= 0) return;
    jfloat* mm = env->GetFloatArrayElements(modelMatrix, nullptr);
    jfloat* points = env->GetFloatArrayElements(localPoints, nullptr);
    jfloat* t = env->GetFloatArrayElements(tint, nullptr);
    if (!mm || !points || !t) {
        if (mm) env->ReleaseFloatArrayElements(modelMatrix, mm, JNI_ABORT);
        if (points) env->ReleaseFloatArrayElements(localPoints, points, JNI_ABORT);
        if (t) env->ReleaseFloatArrayElements(tint, t, JNI_ABORT);
        return;
    }

    station_engine_draw_gameplay_frame_mesh(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(frameMeshHandle),
            mm,
            points,
            pointCount,
            padding,
            lineWidth,
            t);

    env->ReleaseFloatArrayElements(modelMatrix, mm, JNI_ABORT);
    env->ReleaseFloatArrayElements(localPoints, points, JNI_ABORT);
    env->ReleaseFloatArrayElements(tint, t, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeEndScene(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    station_engine_end_scene(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT jint JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativePickObject(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jlong handle, jfloat x, jfloat y,
                                               jint currentObjectId) {
    return station_engine_pick_object(reinterpret_cast<StationEngine*>(handle),
                                      x, y, currentObjectId);
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeProjectGameplayBounds(JNIEnv* env, jobject /*thiz*/,
                                                          jlong engineHandle,
                                                          jfloatArray modelMatrix,
                                                          jfloatArray localPoints,
                                                          jint pointCount,
                                                          jfloat padding) {
    if (!modelMatrix || !localPoints || pointCount <= 0) return nullptr;
    jfloat* mm = env->GetFloatArrayElements(modelMatrix, nullptr);
    jfloat* points = env->GetFloatArrayElements(localPoints, nullptr);
    if (!mm || !points) {
        if (mm) env->ReleaseFloatArrayElements(modelMatrix, mm, JNI_ABORT);
        if (points) env->ReleaseFloatArrayElements(localPoints, points, JNI_ABORT);
        return nullptr;
    }

    float bounds[7]{};
    const bool visible = station_engine_project_gameplay_bounds(
            reinterpret_cast<StationEngine*>(engineHandle),
            mm,
            points,
            pointCount,
            padding,
            bounds);

    env->ReleaseFloatArrayElements(modelMatrix, mm, JNI_ABORT);
    env->ReleaseFloatArrayElements(localPoints, points, JNI_ABORT);
    if (!visible) return nullptr;

    jfloatArray result = env->NewFloatArray(7);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, 7, bounds);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeProjectMeshBounds(JNIEnv* env, jobject /*thiz*/,
                                                      jlong engineHandle,
                                                      jlong meshHandle,
                                                      jfloatArray modelMatrix,
                                                      jfloat padding) {
    if (!modelMatrix || meshHandle == 0) return nullptr;
    jfloat* mm = env->GetFloatArrayElements(modelMatrix, nullptr);
    if (!mm) return nullptr;

    float bounds[7]{};
    const bool visible = station_engine_project_mesh_bounds(
            reinterpret_cast<StationEngine*>(engineHandle),
            reinterpret_cast<StationMesh*>(meshHandle),
            mm,
            padding,
            bounds);

    env->ReleaseFloatArrayElements(modelMatrix, mm, JNI_ABORT);
    if (!visible) return nullptr;

    jfloatArray result = env->NewFloatArray(7);
    if (!result) return nullptr;
    env->SetFloatArrayRegion(result, 0, 7, bounds);
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeOrbitCamera(JNIEnv* /*env*/, jobject /*thiz*/,
                                                jlong handle, jfloat dy, jfloat dp) {
    station_engine_orbit_camera(reinterpret_cast<StationEngine*>(handle), dy, dp);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeRollCamera(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jlong handle, jfloat angle) {
    station_engine_roll_camera(reinterpret_cast<StationEngine*>(handle), angle);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativePanCamera(JNIEnv* /*env*/, jobject /*thiz*/,
                                              jlong handle, jfloat dx, jfloat dy) {
    station_engine_pan_camera(reinterpret_cast<StationEngine*>(handle), dx, dy);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeZoomCamera(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jlong handle, jfloat factor) {
    station_engine_zoom_camera(reinterpret_cast<StationEngine*>(handle), factor);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeZoomCameraAt(JNIEnv* /*env*/, jobject /*thiz*/,
                                                 jlong handle, jfloat factor,
                                                 jfloat screenX, jfloat screenY) {
    station_engine_zoom_camera_at(reinterpret_cast<StationEngine*>(handle),
                                  factor, screenX, screenY);
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeResetCamera(JNIEnv* /*env*/, jobject /*thiz*/,
                                                jlong handle) {
    station_engine_reset_camera(reinterpret_cast<StationEngine*>(handle));
}

JNIEXPORT void JNICALL
Java_com_example_asteroidoutpost_EngineJni_nativeRenderFrame(JNIEnv* /*env*/, jobject /*thiz*/,
                                                jlong handle) {
    station_engine_render_frame(reinterpret_cast<StationEngine*>(handle));
}

} // extern "C"
