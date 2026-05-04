package com.example.asteroidoutpost

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.sqrt

class EngineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    val engine = EngineJni()
    var onSurfaceReady: (() -> Unit)? = null
    var onTap: ((Float, Float) -> Unit)? = null
    var onScreenFrames: ((List<ScreenFrame>) -> Unit)? = null
    var onCameraOrbited: ((yaw: Float, pitch: Float) -> Unit)? = null
    var onCameraRolled:  ((Float) -> Unit)? = null
    var onCameraReset:   (() -> Unit)? = null

    // Set by MainActivity button: true = orbit, false = pan
    var rotateMode: Boolean = false

    // Scene owned by Kotlin, submitted each frame
    @Volatile
    var scene: List<SceneObject> = emptyList()

    @Volatile
    var highlightMeshes: HighlightMeshes = HighlightMeshes()

    @Volatile
    var billboards: List<BillboardDraw> = emptyList()

    @Volatile
    var plasmaBillboards: List<BillboardDraw> = emptyList()

    /**
     * E1.2 — translucent SceneObjects rendered through the alpha-blend
     * pipeline. Mesh's per-vertex alpha controls transparency. Used for soft
     * nebulae / domes / fade VFX.
     */
    @Volatile
    var translucentObjects: List<SceneObject> = emptyList()

    private var renderThread: RenderThread? = null
    @Volatile
    private var pendingScreenFrames: List<ScreenFrame> = emptyList()
    private val screenFramePostPending = AtomicBoolean(false)

    // Single-finger state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private var downTimeMs = 0L
    private var movedSinceDown = false
    private var isTouching = false

    // Two-finger state
    private var lastPinchDist = 0f
    private var lastPinchAngle = 0f
    private var lastMidX = 0f
    private var lastMidY = 0f
    private var isPinching = false

    // Dead-zone: ignore gestures starting within 10% of height from top/bottom edge
    private var singleFingerInDeadZone = false
    private var twoFingerInDeadZone    = false

    companion object {
        private const val ORBIT_SENSITIVITY  = 0.005f
        private const val ROLL_SENSITIVITY   = 1.0f
        private const val MIN_PINCH_DIST     = 10f
        private const val TAP_SLOP_PX        = 18f
        private const val TAP_TIMEOUT_MS     = 220L
        private const val DEAD_ZONE_FRACTION = 0.10f
    }

    private fun inDeadZone(y: Float) = y < height * DEAD_ZONE_FRACTION ||
                                       y > height * (1f - DEAD_ZONE_FRACTION)

    init { holder.addCallback(this) }

    fun initialize() {
        if (!engine.isCreated) engine.create()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!engine.isCreated) return
        engine.surfaceCreated(holder.surface, holder.surfaceFrame.width(), holder.surfaceFrame.height())
        startRenderThread()
        onSurfaceReady?.invoke()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        engine.surfaceChanged(width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopRenderThread()
        engine.surfaceDestroyed()
    }

    fun onResume() = engine.resume()
    fun onPause() = engine.pause()
    fun onDestroyView() {
        stopRenderThread()
        engine.destroy()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) handleTwoFinger(event) else handleSingleFinger(event)
        return true
    }

    private fun handleSingleFinger(event: MotionEvent) {
        if (isPinching) {
            isPinching = false
            lastTouchX = event.x
            lastTouchY = event.y
            isTouching = true
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                singleFingerInDeadZone = inDeadZone(event.y)
                lastTouchX = event.x
                lastTouchY = event.y
                downTouchX = event.x
                downTouchY = event.y
                downTimeMs = event.eventTime
                movedSinceDown = false
                isTouching = true
            }

            MotionEvent.ACTION_MOVE -> if (isTouching && !singleFingerInDeadZone) {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                val totalDx = event.x - downTouchX
                val totalDy = event.y - downTouchY
                if ((totalDx * totalDx + totalDy * totalDy) > TAP_SLOP_PX * TAP_SLOP_PX) {
                    movedSinceDown = true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                engine.panCamera(dx, dy)
            }

            MotionEvent.ACTION_UP -> {
                val dt = event.eventTime - downTimeMs
                val dx = event.x - downTouchX
                val dy = event.y - downTouchY
                val tapDistanceOk = (dx * dx + dy * dy) <= TAP_SLOP_PX * TAP_SLOP_PX
                if (isTouching && !movedSinceDown && tapDistanceOk && dt <= TAP_TIMEOUT_MS) {
                    onTap?.invoke(event.x, event.y)
                }
                isTouching = false
                singleFingerInDeadZone = false
            }

            MotionEvent.ACTION_CANCEL -> { isTouching = false; singleFingerInDeadZone = false }
        }
    }

    private fun twoFingerDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun twoFingerAngle(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return atan2(dy, dx)
    }

    private fun twoFingerMidX(e: MotionEvent) = (e.getX(0) + e.getX(1)) * 0.5f
    private fun twoFingerMidY(e: MotionEvent) = (e.getY(0) + e.getY(1)) * 0.5f

    private fun handleTwoFinger(event: MotionEvent) {
        val dist  = twoFingerDist(event)
        val angle = twoFingerAngle(event)
        val midX  = twoFingerMidX(event)
        val midY  = twoFingerMidY(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                twoFingerInDeadZone = inDeadZone(event.getY(0)) || inDeadZone(event.getY(1))
                lastPinchDist  = dist
                lastPinchAngle = angle
                lastMidX       = midX
                lastMidY       = midY
                isPinching = true
                isTouching = false
            }

            MotionEvent.ACTION_MOVE -> if (isPinching && !twoFingerInDeadZone) {
                // Zoom (dolly forward/backward)
                if (dist > MIN_PINCH_DIST && lastPinchDist > MIN_PINCH_DIST) {
                    engine.zoomCameraAt(lastPinchDist / dist, midX, midY)
                }

                // Roll (rotate around screen axis)
                val deltaAngle = angle - lastPinchAngle
                val wrappedDelta = when {
                    deltaAngle >  Math.PI.toFloat() -> deltaAngle - 2f * Math.PI.toFloat()
                    deltaAngle < -Math.PI.toFloat() -> deltaAngle + 2f * Math.PI.toFloat()
                    else -> deltaAngle
                }
                if (kotlin.math.abs(wrappedDelta) < 0.3f) {
                    val rollDelta = -wrappedDelta * ROLL_SENSITIVITY
                    engine.rollCamera(rollDelta)
                    onCameraRolled?.invoke(rollDelta)
                }

                // Orbit (midpoint translation → yaw/pitch around focus)
                val dMidX = midX - lastMidX
                val dMidY = midY - lastMidY
                val orbitYaw   = -dMidX * ORBIT_SENSITIVITY
                val orbitPitch = -dMidY * ORBIT_SENSITIVITY
                engine.orbitCamera(orbitYaw, orbitPitch)
                onCameraOrbited?.invoke(orbitYaw, orbitPitch)

                lastPinchDist  = dist
                lastPinchAngle = angle
                lastMidX       = midX
                lastMidY       = midY
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> { isPinching = false; twoFingerInDeadZone = false }
        }
    }

    private fun startRenderThread() {
        stopRenderThread()
        RenderThread(this).also {
            renderThread = it
            it.start()
        }
    }

    private fun stopRenderThread() {
        renderThread?.let {
            it.quit()
            it.join()
        }
        renderThread = null
    }

    fun submitCurrentScene() {
        submitScene(engine, scene, highlightMeshes, billboards, plasmaBillboards, translucentObjects)
    }

    private fun collectScreenFrames(objects: List<SceneObject>): List<ScreenFrame> {
        val result = ArrayList<ScreenFrame>(objects.size)
        for (obj in objects) {
            val bounds = engine.projectMeshBounds(
                obj.meshHandle,
                obj.modelMatrix(),
                obj.framePadding
            ) ?: continue
            result.add(
                ScreenFrame(
                    objectId = obj.id,
                    bounds = RectF(bounds[2], bounds[3], bounds[4], bounds[5]),
                    selected = obj.selected,
                    enemy = obj.isEnemy,
                    partiallyVisible = bounds[1] > 0.5f
                )
            )
        }
        return result
    }

    private fun publishScreenFrames(frames: List<ScreenFrame>) {
        pendingScreenFrames = frames
        if (!screenFramePostPending.compareAndSet(false, true)) return

        post {
            val latestFrames = pendingScreenFrames
            screenFramePostPending.set(false)
            onScreenFrames?.invoke(latestFrames)
        }
    }

    private class RenderThread(private val engineView: EngineView) : Thread("RenderThread") {
        private val engine get() = engineView.engine
        @Volatile private var running = true

        override fun run() {
            while (running) {
                val currentScene = engineView.scene
                submitScene(engine, currentScene, engineView.highlightMeshes, engineView.billboards, engineView.plasmaBillboards, engineView.translucentObjects)
                engine.renderFrame()
                engineView.publishScreenFrames(engineView.collectScreenFrames(currentScene))
                try {
                    sleep(1)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        fun quit() {
            running = false
            interrupt()
        }
    }
}
