package com.example.g3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

data class CameraJoystickCommand(
    val mode: CameraJoystickMode,
    val x: Float,
    val y: Float,
    val roll: Float,
    val zoom: Float = 0f
)

enum class CameraJoystickMode {
    PAN,
    ORBIT,
    RESET
}

class CameraJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onCommand: ((CameraJoystickCommand) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 9, 18, 24)
        style = Paint.Style.FILL
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 60, 190, 175)
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 60, 190, 175)
        style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 92, 255, 200)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 170, 235, 230)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 230, 255, 250)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dimIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 230, 255, 250)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 230, 255, 250)
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        letterSpacing = 0f
    }
    private val dimTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 230, 255, 250)
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        letterSpacing = 0f
    }

    private var activeZone = JoystickZone.NONE
    private var touchMode = TouchMode.NONE
    private val tempPath = Path()
    private var commandLoopRunning = false
    private var resetLongPressTriggered = false
    private val resetLongPress = Runnable {
        if (touchMode == TouchMode.ORBIT_ARMED && activeZone == JoystickZone.CENTER) {
            resetLongPressTriggered = true
            onCommand?.invoke(CameraJoystickCommand(CameraJoystickMode.RESET, 0f, 0f, roll = 0f))
            invalidate()
        }
    }
    private val commandLoop = object : Runnable {
        override fun run() {
            emitCurrentCommand()
            if (commandLoopRunning) postOnAnimation(this)
        }
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (168f * density).toInt()
        val width = resolveSize(desired, widthMeasureSpec)
        val height = resolveSize(desired, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeZone = zoneAt(event.x, event.y)
                resetLongPressTriggered = false
                touchMode = when (activeZone) {
                    JoystickZone.CENTER -> TouchMode.ORBIT_ARMED
                    JoystickZone.NONE -> TouchMode.NONE
                    else -> TouchMode.PAN
                }
                if (touchMode == TouchMode.PAN) startCommandLoop()
                if (touchMode == TouchMode.ORBIT_ARMED) {
                    postDelayed(resetLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                invalidate()
                return activeZone != JoystickZone.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.NONE) return false
                activeZone = zoneAt(event.x, event.y)
                if (touchMode == TouchMode.ORBIT_ARMED && activeZone != JoystickZone.CENTER) {
                    removeCallbacks(resetLongPress)
                    touchMode = TouchMode.ORBIT
                    startCommandLoop()
                } else if (touchMode == TouchMode.PAN) {
                    startCommandLoop()
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                stopCommandLoop()
                removeCallbacks(resetLongPress)
                activeZone = JoystickZone.NONE
                touchMode = TouchMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = size * 0.49f
        val centerRadius = size * 0.17f
        val zoneRadius = size * 0.125f
        val zoneOffset = size * 0.325f
        val rollRadius = zoneRadius * 0.76f
        val rollOffset = zoneOffset * 0.74f

        canvas.drawCircle(cx, cy, outerRadius, outerPaint)

        drawZone(canvas, JoystickZone.UP, cx, cy - zoneOffset, zoneRadius, false)
        drawZone(canvas, JoystickZone.DOWN, cx, cy + zoneOffset, zoneRadius, false)
        drawZone(canvas, JoystickZone.LEFT, cx - zoneOffset, cy, zoneRadius, false)
        drawZone(canvas, JoystickZone.RIGHT, cx + zoneOffset, cy, zoneRadius, false)
        drawRollZone(canvas, JoystickZone.ROLL_CCW, cx - rollOffset, cy - rollOffset, rollRadius, true)
        drawRollZone(canvas, JoystickZone.ROLL_CW, cx + rollOffset, cy - rollOffset, rollRadius, true)
        drawDollyZone(canvas, JoystickZone.DOLLY_BACK, cx - rollOffset, cy + rollOffset, rollRadius, true)
        drawDollyZone(canvas, JoystickZone.DOLLY_FORWARD, cx + rollOffset, cy + rollOffset, rollRadius, true)

        val centerFill = if (activeZone == JoystickZone.CENTER || touchMode == TouchMode.ORBIT) activePaint else zonePaint
        canvas.drawCircle(cx, cy, centerRadius, centerFill)
        canvas.drawCircle(cx, cy, centerRadius, strokePaint)
        val centerText = when {
            resetLongPressTriggered -> "RST"
            touchMode == TouchMode.ORBIT -> "ORB"
            else -> "PAN"
        }
        canvas.drawText(centerText, cx, cy + 3.5f * density, textPaint)

        drawDirectionIcon(canvas, JoystickZone.UP, cx, cy - zoneOffset, zoneRadius, false)
        drawDirectionIcon(canvas, JoystickZone.DOWN, cx, cy + zoneOffset, zoneRadius, false)
        drawDirectionIcon(canvas, JoystickZone.LEFT, cx - zoneOffset, cy, zoneRadius, false)
        drawDirectionIcon(canvas, JoystickZone.RIGHT, cx + zoneOffset, cy, zoneRadius, false)
        drawRollIcon(canvas, cx - rollOffset, cy - rollOffset, false, rollRadius, true)
        drawRollIcon(canvas, cx + rollOffset, cy - rollOffset, true, rollRadius, true)
        drawDollyIcon(canvas, cx - rollOffset, cy + rollOffset, false, rollRadius, true)
        drawDollyIcon(canvas, cx + rollOffset, cy + rollOffset, true, rollRadius, true)
    }

    private val isOrbitMode get() = touchMode == TouchMode.ORBIT || touchMode == TouchMode.ORBIT_ARMED

    private fun fillPaint(zone: JoystickZone, dimInOrbit: Boolean): Paint {
        if (activeZone == zone) return activePaint
        return if (dimInOrbit && isOrbitMode) dimPaint else zonePaint
    }

    private fun resolveIconPaint(dimInOrbit: Boolean): Paint =
        if (dimInOrbit && isOrbitMode) dimIconPaint else iconPaint

    private fun drawZone(canvas: Canvas, zone: JoystickZone, cx: Float, cy: Float, radius: Float, dimInOrbit: Boolean) {
        canvas.drawCircle(cx, cy, radius, fillPaint(zone, dimInOrbit))
        canvas.drawCircle(cx, cy, radius, strokePaint)
    }

    private fun drawRollZone(canvas: Canvas, zone: JoystickZone, cx: Float, cy: Float, radius: Float, dimInOrbit: Boolean) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, fillPaint(zone, dimInOrbit))
        canvas.drawRoundRect(rect, 6f * density, 6f * density, strokePaint)
    }

    private fun drawDollyZone(canvas: Canvas, zone: JoystickZone, cx: Float, cy: Float, radius: Float, dimInOrbit: Boolean) {
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, fillPaint(zone, dimInOrbit))
        canvas.drawRoundRect(rect, 6f * density, 6f * density, strokePaint)
    }

    private fun drawDirectionIcon(canvas: Canvas, zone: JoystickZone, cx: Float, cy: Float, radius: Float, dimInOrbit: Boolean) {
        val angle = when (zone) {
            JoystickZone.UP -> -PI.toFloat() / 2f
            JoystickZone.DOWN -> PI.toFloat() / 2f
            JoystickZone.LEFT -> PI.toFloat()
            JoystickZone.RIGHT -> 0f
            else -> 0f
        }
        val tipX = cx + cos(angle) * radius * 0.42f
        val tipY = cy + sin(angle) * radius * 0.42f
        val baseX = cx - cos(angle) * radius * 0.32f
        val baseY = cy - sin(angle) * radius * 0.32f
        val sideAngle = angle + PI.toFloat() / 2f
        val wing = radius * 0.25f

        tempPath.reset()
        tempPath.moveTo(tipX, tipY)
        tempPath.lineTo(baseX + cos(sideAngle) * wing, baseY + sin(sideAngle) * wing)
        tempPath.moveTo(tipX, tipY)
        tempPath.lineTo(baseX - cos(sideAngle) * wing, baseY - sin(sideAngle) * wing)
        canvas.drawPath(tempPath, resolveIconPaint(dimInOrbit))
    }

    private fun drawRollIcon(canvas: Canvas, cx: Float, cy: Float, clockwise: Boolean, radius: Float, dimInOrbit: Boolean) {
        val rect = RectF(cx - radius * 0.48f, cy - radius * 0.48f, cx + radius * 0.48f, cy + radius * 0.48f)
        val start = if (clockwise) -40f else 220f
        val sweep = if (clockwise) 250f else -250f
        canvas.drawArc(rect, start, sweep, false, resolveIconPaint(dimInOrbit))

        val angle = Math.toRadians((start + sweep).toDouble()).toFloat()
        val tipX = cx + cos(angle) * radius * 0.48f
        val tipY = cy + sin(angle) * radius * 0.48f
        val dir = angle + if (clockwise) PI.toFloat() * 0.74f else -PI.toFloat() * 0.74f
        tempPath.reset()
        tempPath.moveTo(tipX, tipY)
        tempPath.lineTo(tipX + cos(dir) * radius * 0.24f, tipY + sin(dir) * radius * 0.24f)
        tempPath.moveTo(tipX, tipY)
        tempPath.lineTo(tipX + cos(dir - PI.toFloat() * 0.35f) * radius * 0.24f, tipY + sin(dir - PI.toFloat() * 0.35f) * radius * 0.24f)
        canvas.drawPath(tempPath, resolveIconPaint(dimInOrbit))
    }

    private fun drawDollyIcon(canvas: Canvas, cx: Float, cy: Float, forward: Boolean, radius: Float, dimInOrbit: Boolean) {
        val arrow = if (forward) -1f else 1f
        val label = if (forward) "+" else "-"
        val stemTop = cy + arrow * radius * 0.42f
        val stemBottom = cy - arrow * radius * 0.22f

        val ip = resolveIconPaint(dimInOrbit)
        tempPath.reset()
        tempPath.moveTo(cx, stemBottom)
        tempPath.lineTo(cx, stemTop)
        tempPath.moveTo(cx, stemTop)
        tempPath.lineTo(cx - radius * 0.20f, stemTop - arrow * radius * 0.20f)
        tempPath.moveTo(cx, stemTop)
        tempPath.lineTo(cx + radius * 0.20f, stemTop - arrow * radius * 0.20f)
        canvas.drawPath(tempPath, ip)
        val tp = if (dimInOrbit && isOrbitMode) dimTextPaint else textPaint
        canvas.drawText(label, cx, cy + radius * 0.55f, tp)
    }

    private fun zoneAt(x: Float, y: Float): JoystickZone {
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val dx = x - cx
        val dy = y - cy
        val distance = hypot(dx, dy)
        if (distance > size * 0.50f) return JoystickZone.NONE
        if (distance <= size * 0.20f) return JoystickZone.CENTER

        val angle = atan2(dy, dx)
        return when {
            dy < -size * 0.12f && dx < -size * 0.12f -> JoystickZone.ROLL_CCW
            dy < -size * 0.12f && dx > size * 0.12f -> JoystickZone.ROLL_CW
            dy > size * 0.12f && dx < -size * 0.12f -> JoystickZone.DOLLY_BACK
            dy > size * 0.12f && dx > size * 0.12f -> JoystickZone.DOLLY_FORWARD
            angle >= -PI.toFloat() * 0.25f && angle < PI.toFloat() * 0.25f -> JoystickZone.RIGHT
            angle >= PI.toFloat() * 0.25f && angle < PI.toFloat() * 0.75f -> JoystickZone.DOWN
            angle <= -PI.toFloat() * 0.25f && angle > -PI.toFloat() * 0.75f -> JoystickZone.UP
            else -> JoystickZone.LEFT
        }
    }

    private fun startCommandLoop() {
        if (commandLoopRunning) return
        commandLoopRunning = true
        postOnAnimation(commandLoop)
    }

    private fun stopCommandLoop() {
        commandLoopRunning = false
        removeCallbacks(commandLoop)
    }

    private fun emitCurrentCommand() {
        val command = when (touchMode) {
            TouchMode.PAN -> panCommand()
            TouchMode.ORBIT -> orbitCommand()
            else -> null
        }
        if (command != null) onCommand?.invoke(command)
    }

    private fun panCommand(): CameraJoystickCommand? = when (activeZone) {
        JoystickZone.LEFT -> CameraJoystickCommand(CameraJoystickMode.PAN, 1f, 0f, 0f)
        JoystickZone.RIGHT -> CameraJoystickCommand(CameraJoystickMode.PAN, -1f, 0f, 0f)
        JoystickZone.UP -> CameraJoystickCommand(CameraJoystickMode.PAN, 0f, 1f, 0f)
        JoystickZone.DOWN -> CameraJoystickCommand(CameraJoystickMode.PAN, 0f, -1f, 0f)
        JoystickZone.DOLLY_BACK -> CameraJoystickCommand(CameraJoystickMode.PAN, 0f, 0f, 0f, -1f)
        JoystickZone.DOLLY_FORWARD -> CameraJoystickCommand(CameraJoystickMode.PAN, 0f, 0f, 0f, 1f)
        JoystickZone.ROLL_CCW -> CameraJoystickCommand(CameraJoystickMode.ORBIT, 0f, 0f, -1f)
        JoystickZone.ROLL_CW -> CameraJoystickCommand(CameraJoystickMode.ORBIT, 0f, 0f, 1f)
        else -> null
    }

    private fun orbitCommand(): CameraJoystickCommand? {
        val (x, y, roll) = when (activeZone) {
            JoystickZone.LEFT -> Triple(1f, 0f, 0f)
            JoystickZone.RIGHT -> Triple(-1f, 0f, 0f)
            JoystickZone.UP -> Triple(0f, 1f, 0f)
            JoystickZone.DOWN -> Triple(0f, -1f, 0f)
            else -> Triple(0f, 0f, 0f)
        }
        if (x == 0f && y == 0f && roll == 0f) return null
        return CameraJoystickCommand(CameraJoystickMode.ORBIT, x, y, roll)
    }

    private enum class JoystickZone {
        NONE,
        CENTER,
        UP,
        DOWN,
        LEFT,
        RIGHT,
        ROLL_CCW,
        ROLL_CW,
        DOLLY_BACK,
        DOLLY_FORWARD
    }

    private enum class TouchMode {
        NONE,
        PAN,
        ORBIT_ARMED,
        ORBIT
    }
}
