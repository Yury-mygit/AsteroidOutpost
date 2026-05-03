package com.example.g3

import android.view.Surface

class EngineJni {

    companion object {
        init { System.loadLibrary("stationcore") }
    }

    private var engineHandle: Long = 0L

    val isCreated: Boolean get() = engineHandle != 0L

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------
    fun create() {
        check(engineHandle == 0L) { "Engine already created" }
        engineHandle = nativeCreate()
        check(engineHandle != 0L) { "Native engine creation failed" }
    }

    fun destroy() {
        if (engineHandle != 0L) { nativeDestroy(engineHandle); engineHandle = 0L }
    }

    // ---------------------------------------------------------------------------
    // Shaders — Kotlin reads .spv bytes, engine knows nothing about files
    // ---------------------------------------------------------------------------
    fun setShader(name: String, spv: ByteArray) {
        if (engineHandle != 0L) nativeSetShader(engineHandle, name, spv)
    }

    // ---------------------------------------------------------------------------
    // Surface
    // ---------------------------------------------------------------------------
    fun surfaceCreated(surface: Surface, width: Int, height: Int) {
        if (engineHandle != 0L) nativeSurfaceCreated(engineHandle, surface, width, height)
    }
    fun surfaceDestroyed() { if (engineHandle != 0L) nativeSurfaceDestroyed(engineHandle) }
    fun surfaceChanged(w: Int, h: Int) { if (engineHandle != 0L) nativeSurfaceChanged(engineHandle, w, h) }

    // ---------------------------------------------------------------------------
    // Focus
    // ---------------------------------------------------------------------------
    fun resume() { if (engineHandle != 0L) nativeResume(engineHandle) }
    fun pause()  { if (engineHandle != 0L) nativePause(engineHandle) }

    // ---------------------------------------------------------------------------
    // Mesh loading — returns opaque handle, 0 = failure
    // ---------------------------------------------------------------------------
    fun loadMesh(data: ByteArray): Long {
        if (engineHandle == 0L) return 0L
        return nativeLoadMesh(engineHandle, data)
    }

    fun loadMeshColored(data: ByteArray, r: Float, g: Float, b: Float): Long {
        if (engineHandle == 0L) return 0L
        return nativeLoadMeshColored(engineHandle, data, r, g, b)
    }

    fun unloadMesh(meshHandle: Long) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeUnloadMesh(engineHandle, meshHandle)
    }

    // ---------------------------------------------------------------------------
    // Scene — Kotlin owns the scene, submits draw calls each frame
    // ---------------------------------------------------------------------------
    fun beginScene() { if (engineHandle != 0L) nativeBeginScene(engineHandle) }

    fun drawMesh(meshHandle: Long, modelMatrix: FloatArray) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawMesh(engineHandle, meshHandle, modelMatrix)
    }

    fun drawPickableMesh(
        meshHandle: Long,
        objectId: Int,
        modelMatrix: FloatArray,
        pickRadius: Float
    ) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawPickableMesh(engineHandle, meshHandle, objectId, modelMatrix, pickRadius)
    }

    fun drawBillboardMesh(meshHandle: Long, x: Float, y: Float, z: Float, scale: Float) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawBillboardMesh(engineHandle, meshHandle, x, y, z, scale)
    }

    fun drawPlasmaBillboard(meshHandle: Long, x: Float, y: Float, z: Float, scale: Float) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawPlasmaBillboard(engineHandle, meshHandle, x, y, z, scale)
    }

    fun drawObjectFrameMesh(
        frameMeshHandle: Long,
        targetMeshHandle: Long,
        modelMatrix: FloatArray,
        padding: Float,
        tint: FloatArray
    ) {
        if (engineHandle != 0L && frameMeshHandle != 0L && targetMeshHandle != 0L)
            nativeDrawObjectFrameMesh(engineHandle, frameMeshHandle, targetMeshHandle, modelMatrix, padding, tint)
    }

    fun drawGameplayFrameMesh(
        frameMeshHandle: Long,
        modelMatrix: FloatArray,
        localPoints: FloatArray,
        padding: Float,
        lineWidth: Float,
        tint: FloatArray
    ) {
        if (engineHandle != 0L && frameMeshHandle != 0L && localPoints.size >= 9)
            nativeDrawGameplayFrameMesh(engineHandle, frameMeshHandle, modelMatrix, localPoints, localPoints.size / 3, padding, lineWidth, tint)
    }

    fun projectGameplayBounds(
        modelMatrix: FloatArray,
        localPoints: FloatArray,
        padding: Float
    ): FloatArray? {
        if (engineHandle == 0L || localPoints.size < 9) return null
        return nativeProjectGameplayBounds(
            engineHandle,
            modelMatrix,
            localPoints,
            localPoints.size / 3,
            padding
        )
    }

    fun projectMeshBounds(
        meshHandle: Long,
        modelMatrix: FloatArray,
        padding: Float
    ): FloatArray? {
        if (engineHandle == 0L || meshHandle == 0L) return null
        return nativeProjectMeshBounds(engineHandle, meshHandle, modelMatrix, padding)
    }

    fun endScene() { if (engineHandle != 0L) nativeEndScene(engineHandle) }

    // ---------------------------------------------------------------------------
    // Camera
    // ---------------------------------------------------------------------------
    fun orbitCamera(deltaYaw: Float, deltaPitch: Float) {
        if (engineHandle != 0L) nativeOrbitCamera(engineHandle, deltaYaw, deltaPitch)
    }
    fun rollCamera(angle: Float) {
        if (engineHandle != 0L) nativeRollCamera(engineHandle, angle)
    }
    fun panCamera(dx: Float, dy: Float) {
        if (engineHandle != 0L) nativePanCamera(engineHandle, dx, dy)
    }
    fun zoomCamera(factor: Float) {
        if (engineHandle != 0L) nativeZoomCamera(engineHandle, factor)
    }
    fun zoomCameraAt(factor: Float, screenX: Float, screenY: Float) {
        if (engineHandle != 0L) nativeZoomCameraAt(engineHandle, factor, screenX, screenY)
    }
    fun resetCamera() {
        if (engineHandle != 0L) nativeResetCamera(engineHandle)
    }

    // ---------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------
    fun renderFrame() { if (engineHandle != 0L) nativeRenderFrame(engineHandle) }

    fun pickObject(x: Float, y: Float, currentObjectId: Int): Int {
        if (engineHandle == 0L) return -1
        return nativePickObject(engineHandle, x, y, currentObjectId)
    }

    // ---------------------------------------------------------------------------
    // Native declarations
    // ---------------------------------------------------------------------------
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetShader(handle: Long, name: String, spv: ByteArray)
    private external fun nativeSurfaceCreated(handle: Long, surface: Surface, width: Int, height: Int)
    private external fun nativeSurfaceDestroyed(handle: Long)
    private external fun nativeSurfaceChanged(handle: Long, width: Int, height: Int)
    private external fun nativeResume(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeLoadMesh(handle: Long, data: ByteArray): Long
    private external fun nativeLoadMeshColored(handle: Long, data: ByteArray, r: Float, g: Float, b: Float): Long
    private external fun nativeUnloadMesh(engineHandle: Long, meshHandle: Long)
    private external fun nativeBeginScene(handle: Long)
    private external fun nativeDrawMesh(engineHandle: Long, meshHandle: Long, modelMatrix: FloatArray)
    private external fun nativeDrawPickableMesh(
        engineHandle: Long,
        meshHandle: Long,
        objectId: Int,
        modelMatrix: FloatArray,
        pickRadius: Float
    )
    private external fun nativeDrawBillboardMesh(
        engineHandle: Long,
        meshHandle: Long,
        x: Float, y: Float, z: Float, scale: Float
    )
    private external fun nativeDrawPlasmaBillboard(
        engineHandle: Long,
        meshHandle: Long,
        x: Float, y: Float, z: Float, scale: Float
    )
    private external fun nativeDrawObjectFrameMesh(
        engineHandle: Long,
        frameMeshHandle: Long,
        targetMeshHandle: Long,
        modelMatrix: FloatArray,
        padding: Float,
        tint: FloatArray
    )
    private external fun nativeDrawGameplayFrameMesh(
        engineHandle: Long,
        frameMeshHandle: Long,
        modelMatrix: FloatArray,
        localPoints: FloatArray,
        pointCount: Int,
        padding: Float,
        lineWidth: Float,
        tint: FloatArray
    )
    private external fun nativeEndScene(handle: Long)
    private external fun nativeOrbitCamera(handle: Long, deltaYaw: Float, deltaPitch: Float)
    private external fun nativeRollCamera(handle: Long, angle: Float)
    private external fun nativePanCamera(handle: Long, dx: Float, dy: Float)
    private external fun nativeZoomCamera(handle: Long, factor: Float)
    private external fun nativeZoomCameraAt(handle: Long, factor: Float, screenX: Float, screenY: Float)
    private external fun nativeResetCamera(handle: Long)
    private external fun nativeRenderFrame(handle: Long)
    private external fun nativePickObject(handle: Long, x: Float, y: Float, currentObjectId: Int): Int
    private external fun nativeProjectGameplayBounds(
        handle: Long,
        modelMatrix: FloatArray,
        localPoints: FloatArray,
        pointCount: Int,
        padding: Float
    ): FloatArray?
    private external fun nativeProjectMeshBounds(
        handle: Long,
        meshHandle: Long,
        modelMatrix: FloatArray,
        padding: Float
    ): FloatArray?
}
