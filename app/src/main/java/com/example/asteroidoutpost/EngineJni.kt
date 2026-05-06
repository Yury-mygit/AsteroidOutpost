package com.example.asteroidoutpost

import android.view.Surface

class EngineJni {

    companion object {
        init { System.loadLibrary("stationcore") }

        // E3.1 — material flags for translucent draws. Fragment shader branches
        // on these: PLAIN passes per-vertex alpha straight through; NEBULA
        // multiplies alpha by 3-octave value-noise from world position; HEX
        // multiplies alpha by a procedural hex-grid pattern from local X/Z.
        const val MATERIAL_PLAIN  = 0
        const val MATERIAL_NEBULA = 1
        const val MATERIAL_HEX    = 2

        // E7.1 — sub-materials for the additive mesh pipeline. Plain = simple
        // pass-through (vColor.rgb * pc.plasmaColor * vColor.a, premultiplied).
        // Fire = Fresnel-soft-edge sphere with heat ramp + FBM turbulence,
        // designed for 3D fireball explosions on this project's fixed
        // pitch=π/2 camera.
        const val ADDITIVE_PLAIN = 0
        const val ADDITIVE_FIRE  = 1

        // E9 — particle render modes. ADDITIVE = ONE/ONE blend, depth-test
        // off, fragment shader paints a heat-ramp + soft-fade per particle
        // (sparks/embers). ALPHA_TEXTURED = SRC_ALPHA blend, depth-test
        // read-only, fragment samples uTex modulated by per-instance
        // colour (smoke/debris). Per-instance stride is fixed at 8 floats:
        // pos.x, pos.y, pos.z, size, r, g, b, a.
        const val PARTICLE_ADDITIVE       = 0
        const val PARTICLE_ALPHA_TEXTURED = 1
        const val PARTICLE_FLOAT_STRIDE   = 8
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

    /**
     * E8.3 — upload a PNG asset as a GPU texture. Returns 0 on failure
     * (decode error, pool full, GPU upload error — see logcat). Decoded
     * via stb_image; caller passes raw PNG bytes from Android assets.
     * Lifetime tied to the engine — `unloadTexture` returns the slot.
     */
    fun loadTexture(data: ByteArray): Long {
        if (engineHandle == 0L || data.isEmpty()) return 0L
        return nativeLoadTexture(engineHandle, data)
    }

    /**
     * E8.4 — upload a texture from raw RGBA8 bytes. `data.size` must equal
     * `width * height * 4`. Use this for procedurally generated textures
     * (no PNG round-trip needed). Returns 0 on failure.
     */
    fun loadTextureRaw(data: ByteArray, width: Int, height: Int): Long {
        if (engineHandle == 0L || width <= 0 || height <= 0) return 0L
        if (data.size != width * height * 4) return 0L
        return nativeLoadTextureRaw(engineHandle, data, width, height)
    }

    fun unloadTexture(textureHandle: Long) {
        if (engineHandle != 0L && textureHandle != 0L)
            nativeUnloadTexture(engineHandle, textureHandle)
    }

    /**
     * E1.3 — upload a procedural mesh from raw vertex + index arrays.
     * Each vertex is 10 floats: `pos(3) + RGBA(4) + normal(3)`. Indices are
     * 16-bit (Kotlin Short maps to uint16 in C). Returns 0 on failure.
     */
    fun loadMeshRaw(vertices: FloatArray, indices: ShortArray): Long {
        if (engineHandle == 0L) return 0L
        if (vertices.isEmpty() || indices.isEmpty() || vertices.size % 10 != 0) return 0L
        return nativeLoadMeshRaw(engineHandle, vertices, indices)
    }

    /**
     * E8.4 — same as `loadMeshRaw` but each vertex is 12 floats including
     * UV at the end: `pos(3) + RGBA(4) + normal(3) + uv(2)`. Use this for
     * UV-mapped procedural meshes (textured quads, sprite billboards).
     */
    fun loadMeshRawUV(vertices: FloatArray, indices: ShortArray): Long {
        if (engineHandle == 0L) return 0L
        if (vertices.isEmpty() || indices.isEmpty() || vertices.size % 12 != 0) return 0L
        return nativeLoadMeshRawUV(engineHandle, vertices, indices)
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

    /**
     * E5.1 — `r,g,b,a` is a per-billboard tint multiplied into the plasma
     * fragment heat-ramp. Default white preserves the E4 warm-flame look.
     * E5.2 — `scaleH, scaleV` are screen-horizontal and screen-vertical
     * half-sizes; pass equal values for a square billboard, or stretch
     * one axis for streak/shockwave-style effects.
     */
    fun drawPlasmaBillboard(meshHandle: Long, x: Float, y: Float, z: Float,
                            scaleH: Float, scaleV: Float,
                            r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawPlasmaBillboard(engineHandle, meshHandle, x, y, z, scaleH, scaleV, r, g, b, a)
    }

    /**
     * E9 — submit a batch of particles. `instanceData` is `count * 8` floats
     * laid out per particle as `pos.x, pos.y, pos.z, size, r, g, b, a`.
     * `mode` is `PARTICLE_ADDITIVE` (sparks/embers — texture optional, used
     * for set 1 layout only) or `PARTICLE_ALPHA_TEXTURED` (smoke/debris —
     * texture required and sampled). Engine drops particles past
     * kMaxParticles (4096) per pipeline.
     */
    fun drawParticles(meshHandle: Long, textureHandle: Long,
                      instanceData: FloatArray, count: Int, mode: Int) {
        if (engineHandle == 0L || meshHandle == 0L || count <= 0) return
        if (instanceData.size < count * PARTICLE_FLOAT_STRIDE) return
        nativeDrawParticles(engineHandle, meshHandle, textureHandle,
                            instanceData, count, mode)
    }

    /**
     * E8.3 — draw an opaque mesh with a sampled texture. Mesh must have UVs
     * (TEXCOORD_0 from glTF, or default (0,0) from procedural meshes — in
     * which case the texture lookup degenerates to a single texel). Texture
     * comes from `loadTexture(ByteArray)`. (r,g,b,a) tints the sampled
     * colour multiplicatively; default white = no tint.
     */
    fun drawTexturedMesh(meshHandle: Long, textureHandle: Long, modelMatrix: FloatArray,
                         r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f) {
        if (engineHandle != 0L && meshHandle != 0L && textureHandle != 0L)
            nativeDrawTexturedMesh(engineHandle, meshHandle, textureHandle, modelMatrix, r, g, b, a)
    }

    /**
     * E1.2 — submit a draw call on the translucent (alpha-blend) pipeline.
     * The mesh's per-vertex alpha controls transparency. Use this for soft
     * nebulae, shield domes, fade-out VFX — anything where the mesh has
     * varying alpha across its vertices.
     */
    fun drawTranslucentMesh(meshHandle: Long, modelMatrix: FloatArray, material: Int = MATERIAL_PLAIN) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawTranslucentMesh(engineHandle, meshHandle, modelMatrix, material)
    }

    /**
     * E7 — submit a draw call on the additive (ONE/ONE) mesh pipeline.
     * Per-vertex alpha controls glow falloff; (r,g,b,a) tints the result
     * (rgb = colour, a = brightness scalar). `material` picks a fragment
     * shader branch: ADDITIVE_PLAIN passes per-vertex colour through,
     * ADDITIVE_FIRE renders a fireball-style sphere (heat-ramp + FBM
     * turbulence + Fresnel-like edge soft-fade). Used for fireballs,
     * plasma laser beams, electric arcs — anything emissive built from
     * real geometry.
     */
    fun drawAdditiveMesh(meshHandle: Long, modelMatrix: FloatArray,
                         r: Float = 1f, g: Float = 1f, b: Float = 1f, a: Float = 1f,
                         material: Int = ADDITIVE_PLAIN) {
        if (engineHandle != 0L && meshHandle != 0L)
            nativeDrawAdditiveMesh(engineHandle, meshHandle, modelMatrix, r, g, b, a, material)
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
    private external fun nativeLoadMeshRaw(handle: Long, vertices: FloatArray, indices: ShortArray): Long
    private external fun nativeLoadMeshRawUV(handle: Long, vertices: FloatArray, indices: ShortArray): Long
    private external fun nativeLoadTexture(handle: Long, data: ByteArray): Long
    private external fun nativeLoadTextureRaw(handle: Long, data: ByteArray, width: Int, height: Int): Long
    private external fun nativeUnloadTexture(engineHandle: Long, textureHandle: Long)
    private external fun nativeDrawParticles(
        engineHandle: Long,
        meshHandle: Long,
        textureHandle: Long,
        instanceData: FloatArray,
        count: Int, mode: Int,
    )
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
        x: Float, y: Float, z: Float,
        scaleH: Float, scaleV: Float,
        r: Float, g: Float, b: Float, a: Float
    )
    private external fun nativeDrawTranslucentMesh(
        engineHandle: Long,
        meshHandle: Long,
        modelMatrix: FloatArray,
        material: Int,
    )
    private external fun nativeDrawTexturedMesh(
        engineHandle: Long,
        meshHandle: Long,
        textureHandle: Long,
        modelMatrix: FloatArray,
        r: Float, g: Float, b: Float, a: Float,
    )
    private external fun nativeDrawAdditiveMesh(
        engineHandle: Long,
        meshHandle: Long,
        modelMatrix: FloatArray,
        r: Float, g: Float, b: Float, a: Float,
        material: Int,
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
