package com.autopilot.pro_gamer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class AutoTouchAccessibilityService : AccessibilityService() {

    enum class HudButton(val id: Int, val key: String) {
        FIRE(0, "fire"),
        LEFT_FIRE(1, "left_fire"),
        JUMP(2, "jump"),
        CROUCH(3, "crouch"),
        PRONE(4, "prone"),
        SCOPE(5, "scope"),
        GLOO_WALL(6, "gloo_wall"),
        MEDKIT(7, "medkit"),
        INHALER(8, "inhaler"),
        SPRINT(9, "sprint"),
        RELOAD(10, "reload"),
        SECONDARY_WEAPON(11, "secondary_weapon"),
        PRIMARY_WEAPON(12, "primary_weapon"),
        JOYSTICK(13, "joystick");

        companion object {
            fun fromId(id: Int): HudButton? = values().firstOrNull { it.id == id }
        }
    }

    data class HudControlPoint(
        val buttonId: Int,
        val normX: Float,
        val normY: Float,
        val radiusNorm: Float,
        val confidence: Float
    )

    data class WeaponRecoilProfile(
        val verticalKickRatio: Float,
        val sustainedPullRatio: Float,
        val horizontalDriftRatio: Float,
        val baseDurationMs: Long
    )

    companion object {
        private const val PREFS_NAME = "autopilot_hud_mappings"

        @Volatile
        var instance: AutoTouchAccessibilityService? = null
            private set

        @Volatile
        var nativeBridgeLoaded: Boolean = false
            private set

        private val hudCoordinates = ConcurrentHashMap<Int, HudControlPoint>()
        private val activePackageVersion = AtomicLong(0L)

        @Volatile
        private var currentTargetPackage: String = "default"

        init {
            try {
                System.loadLibrary("autopilot_engine")
                nativeBridgeLoaded = true
            } catch (_: UnsatisfiedLinkError) {
                nativeBridgeLoaded = false
            }
        }

        private val weaponProfiles = mapOf(
            0 to WeaponRecoilProfile(0.32f, 0.65f, 0.04f, 140L),
            1 to WeaponRecoilProfile(0.48f, 0.95f, 0.07f, 175L),
            2 to WeaponRecoilProfile(0.22f, 0.35f, 0.02f, 95L),
            3 to WeaponRecoilProfile(0.65f, 0.55f, 0.03f, 115L),
            4 to WeaponRecoilProfile(0.55f, 1.15f, 0.09f, 195L)
        )

        @JvmStatic
        fun isServiceConnected(): Boolean = instance != null

        @JvmStatic
        fun setTargetPackage(packageName: String) {
            currentTargetPackage = packageName.ifBlank { "default" }
            activePackageVersion.incrementAndGet()
            instance?.loadHudMappingsForPackage(currentTargetPackage)
        }

        @JvmStatic
        fun onNativeRegisterHudButton(
            buttonId: Int,
            normX: Float,
            normY: Float,
            radiusNorm: Float,
            confidence: Float
        ) {
            val clampedX = normX.coerceIn(0.02f, 0.98f)
            val clampedY = normY.coerceIn(0.02f, 0.98f)
            val clampedRadius = radiusNorm.coerceIn(0.015f, 0.12f)
            val existing = hudCoordinates[buttonId]
            if (existing == null || confidence >= existing.confidence * 0.85f) {
                val point = HudControlPoint(
                    buttonId = buttonId,
                    normX = clampedX,
                    normY = clampedY,
                    radiusNorm = clampedRadius,
                    confidence = confidence.coerceIn(0f, 1f)
                )
                hudCoordinates[buttonId] = point
                instance?.persistHudPoint(currentTargetPackage, point)
            }
        }

        @JvmStatic
        fun onNativeTapButton(buttonId: Int): Boolean {
            val service = instance ?: return false
            return service.tapHudButton(buttonId)
        }

        @JvmStatic
        fun onNativeExecuteUpwardDragShot(
            normStartX: Float,
            normStartY: Float,
            normDeltaY: Float,
            normCurveBiasX: Float,
            durationMs: Long
        ): Boolean {
            val service = instance ?: return false
            return service.executeUpwardDragShot(
                normStartX = normStartX,
                normStartY = normStartY,
                normDeltaY = normDeltaY,
                normCurveBiasX = normCurveBiasX,
                durationMs = durationMs
            )
        }

        @JvmStatic
        fun onNativeExecuteRecoilDrag(
            normStartX: Float,
            normStartY: Float,
            recoilStrength: Float,
            weaponClass: Int,
            durationMs: Long
        ): Boolean {
            val service = instance ?: return false
            return service.executeActiveRecoilControl(
                normStartX = normStartX,
                normStartY = normStartY,
                recoilStrength = recoilStrength,
                weaponClass = weaponClass,
                durationMs = durationMs
            )
        }

        @JvmStatic
        fun onNativeExecuteSwipe(
            normStartX: Float,
            normStartY: Float,
            normEndX: Float,
            normEndY: Float,
            durationMs: Long
        ): Boolean {
            val service = instance ?: return false
            return service.executeBezierSwipe(
                normStartX = normStartX,
                normStartY = normStartY,
                normEndX = normEndX,
                normEndY = normEndY,
                durationMs = durationMs
            )
        }

        @JvmStatic
        fun onNativeExecuteMultiTouchCombo(
            buttonIds: IntArray,
            moveDirX: Float,
            moveDirY: Float,
            aimDeltaX: Float,
            aimDeltaY: Float,
            recoilStrength: Float,
            weaponClass: Int
        ): Boolean {
            val service = instance ?: return false
            return service.executeSimultaneousCombo(
                buttonIds = buttonIds,
                moveDirX = moveDirX,
                moveDirY = moveDirY,
                aimDeltaX = aimDeltaX,
                aimDeltaY = aimDeltaY,
                recoilStrength = recoilStrength,
                weaponClass = weaponClass
            )
        }
    }

    private external fun nativeAttachTouchBridge()
    private external fun nativeDetachTouchBridge()

    private var touchThread: HandlerThread? = null
    private var touchHandler: Handler? = null
    private val isGestureInFlight = AtomicBoolean(false)
    private var lastGestureDispatchNs: Long = 0L

    private var screenWidth: Int = 2400
    private var screenHeight: Int = 1080

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        resolveScreenBounds()
        seedDefaultFreeFireHud()
        loadHudMappingsForPackage(currentTargetPackage)

        val thread = HandlerThread(
            "AutopilotTouchThread",
            Process.THREAD_PRIORITY_URGENT_DISPLAY
        )
        thread.start()
        touchThread = thread
        touchHandler = Handler(thread.looper)

        if (nativeBridgeLoaded) {
            try {
                nativeAttachTouchBridge()
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupTouchResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupTouchResources()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg == currentTargetPackage) {
            resolveScreenBounds()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                scanWindowHierarchyForHudHints()
            }
        }
    }

    override fun onInterrupt() {
        isGestureInFlight.set(false)
    }

    private fun cleanupTouchResources() {
        if (nativeBridgeLoaded) {
            try {
                nativeDetachTouchBridge()
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        instance = null
        try {
            touchThread?.quitSafely()
        } catch (_: Exception) {
        }
        touchThread = null
        touchHandler = null
        isGestureInFlight.set(false)
    }

    private fun resolveScreenBounds() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = max(1, bounds.width())
            screenHeight = max(1, bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = max(1, metrics.widthPixels)
            screenHeight = max(1, metrics.heightPixels)
        }
    }

    private fun seedDefaultFreeFireHud() {
        val defaults = listOf(
            HudControlPoint(HudButton.JOYSTICK.id, 0.16f, 0.75f, 0.08f, 0.35f),
            HudControlPoint(HudButton.SPRINT.id, 0.16f, 0.43f, 0.035f, 0.35f),
            HudControlPoint(HudButton.FIRE.id, 0.84f, 0.72f, 0.055f, 0.35f),
            HudControlPoint(HudButton.LEFT_FIRE.id, 0.12f, 0.28f, 0.04f, 0.35f),
            HudControlPoint(HudButton.SCOPE.id, 0.92f, 0.49f, 0.038f, 0.35f),
            HudControlPoint(HudButton.JUMP.id, 0.93f, 0.65f, 0.038f, 0.35f),
            HudControlPoint(HudButton.CROUCH.id, 0.87f, 0.88f, 0.038f, 0.35f),
            HudControlPoint(HudButton.PRONE.id, 0.79f, 0.89f, 0.035f, 0.35f),
            HudControlPoint(HudButton.GLOO_WALL.id, 0.73f, 0.76f, 0.04f, 0.35f),
            HudControlPoint(HudButton.MEDKIT.id, 0.65f, 0.89f, 0.036f, 0.35f),
            HudControlPoint(HudButton.INHALER.id, 0.61f, 0.77f, 0.032f, 0.35f),
            HudControlPoint(HudButton.RELOAD.id, 0.84f, 0.34f, 0.035f, 0.35f),
            HudControlPoint(HudButton.PRIMARY_WEAPON.id, 0.75f, 0.13f, 0.045f, 0.35f),
            HudControlPoint(HudButton.SECONDARY_WEAPON.id, 0.85f, 0.13f, 0.045f, 0.35f)
        )
        for (point in defaults) {
            hudCoordinates.putIfAbsent(point.buttonId, point)
        }
    }

    private fun loadHudMappingsForPackage(pkg: String) {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (button in HudButton.values()) {
            val prefix = "${pkg}_${button.key}"
            if (prefs.contains("${prefix}_x") && prefs.contains("${prefix}_y")) {
                val nx = prefs.getFloat("${prefix}_x", 0.5f)
                val ny = prefs.getFloat("${prefix}_y", 0.5f)
                val nr = prefs.getFloat("${prefix}_r", 0.04f)
                val conf = prefs.getFloat("${prefix}_c", 0.75f)
                hudCoordinates[button.id] = HudControlPoint(button.id, nx, ny, nr, conf)
            }
        }
    }

    private fun persistHudPoint(pkg: String, point: HudControlPoint) {
        val button = HudButton.fromId(point.buttonId) ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = "${pkg}_${button.key}"
        prefs.edit()
            .putFloat("${prefix}_x", point.normX)
            .putFloat("${prefix}_y", point.normY)
            .putFloat("${prefix}_r", point.radiusNorm)
            .putFloat("${prefix}_c", point.confidence)
            .apply()
    }

    private fun scanWindowHierarchyForHudHints() {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            traverseNodeTree(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo) {
        val desc = (node.contentDescription?.toString() ?: node.text?.toString() ?: "")
            .lowercase()
            .trim()
        if (desc.isNotEmpty()) {
            val matchedButton = matchLabelToHudButton(desc)
            if (matchedButton != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && screenWidth > 0 && screenHeight > 0) {
                    val normX = rect.exactCenterX() / screenWidth.toFloat()
                    val normY = rect.exactCenterY() / screenHeight.toFloat()
                    val radiusNorm = min(rect.width(), rect.height()) /
                        (2f * min(screenWidth, screenHeight).toFloat())
                    onNativeRegisterHudButton(
                        buttonId = matchedButton.id,
                        normX = normX,
                        normY = normY,
                        radiusNorm = radiusNorm,
                        confidence = 0.90f
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseNodeTree(child)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun matchLabelToHudButton(label: String): HudButton? {
        return when {
            label.contains("fire") || label.contains("shoot") -> HudButton.FIRE
            label.contains("jump") -> HudButton.JUMP
            label.contains("crouch") -> HudButton.CROUCH
            label.contains("prone") -> HudButton.PRONE
            label.contains("scope") || label.contains("aim") -> HudButton.SCOPE
            label.contains("gloo") || label.contains("shield") || label.contains("wall") ->
                HudButton.GLOO_WALL
            label.contains("medkit") || label.contains("heal") -> HudButton.MEDKIT
            label.contains("inhaler") -> HudButton.INHALER
            label.contains("sprint") || label.contains("run") -> HudButton.SPRINT
            label.contains("reload") -> HudButton.RELOAD
            else -> null
        }
    }

    fun tapHudButton(buttonId: Int): Boolean {
        val point = hudCoordinates[buttonId] ?: return false
        val rng = ThreadLocalRandom.current()
        val jitterRadiusPx = point.radiusNorm * min(screenWidth, screenHeight) * 0.32f
        val x = clampScreenX(
            point.normX * screenWidth + (rng.nextFloat() * 2f - 1f) * jitterRadiusPx
        )
        val y = clampScreenY(
            point.normY * screenHeight + (rng.nextFloat() * 2f - 1f) * jitterRadiusPx
        )
        val duration = randomizeDuration(34L, 12L)

        val path = Path().apply {
            moveTo(x, y)
            lineTo(
                clampScreenX(x + rng.nextFloat() * 2.4f - 1.2f),
                clampScreenY(y + rng.nextFloat() * 2.4f - 1.2f)
            )
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSafely(gesture)
    }

    fun executeUpwardDragShot(
        normStartX: Float,
        normStartY: Float,
        normDeltaY: Float,
        normCurveBiasX: Float,
        durationMs: Long
    ): Boolean {
        val firePoint = hudCoordinates[HudButton.FIRE.id]
        val baseX = if (normStartX > 0f) normStartX else (firePoint?.normX ?: 0.84f)
        val baseY = if (normStartY > 0f) normStartY else (firePoint?.normY ?: 0.72f)

        val rng = ThreadLocalRandom.current()
        val startX = clampScreenX(baseX * screenWidth + rng.nextFloat() * 6f - 3f)
        val startY = clampScreenY(baseY * screenHeight + rng.nextFloat() * 6f - 3f)

        val upwardPx = abs(normDeltaY).coerceIn(0.08f, 0.34f) * screenHeight
        val lateralPx = normCurveBiasX.coerceIn(-0.14f, 0.14f) * screenWidth

        val endX = clampScreenX(startX + lateralPx)
        val endY = clampScreenY(startY - upwardPx)

        val ctrl1X = clampScreenX(startX + lateralPx * 0.25f + (rng.nextFloat() * 10f - 5f))
        val ctrl1Y = clampScreenY(startY - upwardPx * 0.42f)
        val ctrl2X = clampScreenX(startX + lateralPx * 0.78f + (rng.nextFloat() * 8f - 4f))
        val ctrl2Y = clampScreenY(startY - upwardPx * 0.86f)

        val path = buildCubicBezierPath(
            x0 = startX,
            y0 = startY,
            x1 = ctrl1X,
            y1 = ctrl1Y,
            x2 = ctrl2X,
            y2 = ctrl2Y,
            x3 = endX,
            y3 = endY,
            segments = 14,
            microNoisePx = 1.6f
        )

        val naturalDuration = randomizeDuration(durationMs.coerceIn(55L, 180L), 10L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, naturalDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSafely(gesture)
    }

    fun executeActiveRecoilControl(
        normStartX: Float,
        normStartY: Float,
        recoilStrength: Float,
        weaponClass: Int,
        durationMs: Long
    ): Boolean {
        val profile = weaponProfiles[weaponClass] ?: weaponProfiles[1]!!
        val rng = ThreadLocalRandom.current()

        val anchorX = if (normStartX > 0f) normStartX else 0.72f
        val anchorY = if (normStartY > 0f) normStartY else 0.48f

        val startX = clampScreenX(anchorX * screenWidth + (rng.nextFloat() * 4f - 2f))
        val startY = clampScreenY(anchorY * screenHeight + (rng.nextFloat() * 4f - 2f))

        val clampedStrength = recoilStrength.coerceIn(0.1f, 2.5f)
        val totalPullDownPx = (0.045f * profile.sustainedPullRatio * clampedStrength) * screenHeight
        val initialKickPx = totalPullDownPx * profile.verticalKickRatio
        val swayPx = (profile.horizontalDriftRatio * clampedStrength * screenWidth) *
            (if (rng.nextBoolean()) 1f else -1f)

        val endX = clampScreenX(startX + swayPx)
        val endY = clampScreenY(startY + totalPullDownPx)

        val ctrl1X = clampScreenX(startX + swayPx * 0.15f)
        val ctrl1Y = clampScreenY(startY + initialKickPx)
        val ctrl2X = clampScreenX(startX + swayPx * 0.65f)
        val ctrl2Y = clampScreenY(startY + totalPullDownPx * 0.75f)

        val path = buildCubicBezierPath(
            x0 = startX,
            y0 = startY,
            x1 = ctrl1X,
            y1 = ctrl1Y,
            x2 = ctrl2X,
            y2 = ctrl2Y,
            x3 = endX,
            y3 = endY,
            segments = 12,
            microNoisePx = 1.2f
        )

        val targetDuration = if (durationMs > 0L) durationMs else profile.baseDurationMs
        val naturalDuration = randomizeDuration(targetDuration.coerceIn(50L, 260L), 14L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, naturalDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSafely(gesture)
    }

    fun executeBezierSwipe(
        normStartX: Float,
        normStartY: Float,
        normEndX: Float,
        normEndY: Float,
        durationMs: Long
    ): Boolean {
        val rng = ThreadLocalRandom.current()
        val x0 = clampScreenX(normStartX * screenWidth)
        val y0 = clampScreenY(normStartY * screenHeight)
        val x3 = clampScreenX(normEndX * screenWidth)
        val y3 = clampScreenY(normEndY * screenHeight)

        val midOffsetX = (rng.nextFloat() * 2f - 1f) * (screenWidth * 0.018f)
        val midOffsetY = (rng.nextFloat() * 2f - 1f) * (screenHeight * 0.018f)

        val x1 = clampScreenX(x0 + (x3 - x0) * 0.33f + midOffsetX)
        val y1 = clampScreenY(y0 + (y3 - y0) * 0.33f + midOffsetY)
        val x2 = clampScreenX(x0 + (x3 - x0) * 0.67f - midOffsetX * 0.5f)
        val y2 = clampScreenY(y0 + (y3 - y0) * 0.67f - midOffsetY * 0.5f)

        val path = buildCubicBezierPath(x0, y0, x1, y1, x2, y2, x3, y3, 12, 1.4f)
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            randomizeDuration(durationMs.coerceIn(45L, 450L), 12L)
        )
        return dispatchGestureSafely(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun executeSimultaneousCombo(
        buttonIds: IntArray,
        moveDirX: Float,
        moveDirY: Float,
        aimDeltaX: Float,
        aimDeltaY: Float,
        recoilStrength: Float,
        weaponClass: Int
    ): Boolean {
        val builder = GestureDescription.Builder()
        val rng = ThreadLocalRandom.current()
        var strokeCount = 0

        if (abs(moveDirX) > 0.05f || abs(moveDirY) > 0.05f) {
            val joy = hudCoordinates[HudButton.JOYSTICK.id]
            val jx = (joy?.normX ?: 0.16f) * screenWidth
            val jy = (joy?.normY ?: 0.75f) * screenHeight
            val radiusPx = (joy?.radiusNorm ?: 0.08f) * min(screenWidth, screenHeight)

            val endX = clampScreenX(jx + moveDirX.coerceIn(-1f, 1f) * radiusPx)
            val endY = clampScreenY(jy + moveDirY.coerceIn(-1f, 1f) * radiusPx)

            val joyPath = buildCubicBezierPath(
                x0 = clampScreenX(jx),
                y0 = clampScreenY(jy),
                x1 = clampScreenX(jx + (endX - jx) * 0.4f + rng.nextFloat() * 4f - 2f),
                y1 = clampScreenY(jy + (endY - jy) * 0.4f + rng.nextFloat() * 4f - 2f),
                x2 = clampScreenX(jx + (endX - jx) * 0.8f),
                y2 = clampScreenY(jy + (endY - jy) * 0.8f),
                x3 = endX,
                y3 = endY,
                segments = 8,
                microNoisePx = 1.1f
            )
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    joyPath,
                    0L,
                    randomizeDuration(130L, 15L)
                )
            )
            strokeCount++
        }

        if (abs(aimDeltaX) > 0.005f || abs(aimDeltaY) > 0.005f || recoilStrength > 0.05f) {
            val containsFire = buttonIds.contains(HudButton.FIRE.id)
            val firePoint = hudCoordinates[HudButton.FIRE.id]
            val startNormX = if (containsFire) (firePoint?.normX ?: 0.84f) else 0.72f
            val startNormY = if (containsFire) (firePoint?.normY ?: 0.72f) else 0.48f

            val startX = clampScreenX(startNormX * screenWidth + rng.nextFloat() * 4f - 2f)
            val startY = clampScreenY(startNormY * screenHeight + rng.nextFloat() * 4f - 2f)

            val profile = weaponProfiles[weaponClass] ?: weaponProfiles[1]!!
            val recoilPullY = if (recoilStrength > 0.05f && aimDeltaY >= 0f) {
                0.04f * profile.sustainedPullRatio * recoilStrength * screenHeight
            } else {
                0f
            }

            val endX = clampScreenX(startX + aimDeltaX.coerceIn(-0.3f, 0.3f) * screenWidth)
            val endY = clampScreenY(startY + aimDeltaY.coerceIn(-0.35f, 0.35f) * screenHeight + recoilPullY)

            val ctrl1X = clampScreenX(startX + (endX - startX) * 0.35f)
            val ctrl1Y = clampScreenY(startY + (endY - startY) * 0.42f)
            val ctrl2X = clampScreenX(startX + (endX - startX) * 0.78f)
            val ctrl2Y = clampScreenY(startY + (endY - startY) * 0.85f)

            val aimPath = buildCubicBezierPath(
                x0 = startX,
                y0 = startY,
                x1 = ctrl1X,
                y1 = ctrl1Y,
                x2 = ctrl2X,
                y2 = ctrl2Y,
                x3 = endX,
                y3 = endY,
                segments = 12,
                microNoisePx = 1.4f
            )

            val startOffset = rng.nextLong(2L, 9L)
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    aimPath,
                    startOffset,
                    randomizeDuration(110L, 14L)
                )
            )
            strokeCount++
        }

        for (btnId in buttonIds) {
            if (strokeCount >= 8) break
            if (btnId == HudButton.JOYSTICK.id) continue
            if (btnId == HudButton.FIRE.id &&
                (abs(aimDeltaX) > 0.005f || abs(aimDeltaY) > 0.005f)
            ) {
                continue
            }

            val pt = hudCoordinates[btnId] ?: continue
            val jitter = pt.radiusNorm * min(screenWidth, screenHeight) * 0.25f
            val tx = clampScreenX(pt.normX * screenWidth + (rng.nextFloat() * 2f - 1f) * jitter)
            val ty = clampScreenY(pt.normY * screenHeight + (rng.nextFloat() * 2f - 1f) * jitter)

            val tapPath = Path().apply {
                moveTo(tx, ty)
                lineTo(clampScreenX(tx + 1f), clampScreenY(ty + 1f))
            }
            val staggerStart = rng.nextLong(0L, 18L)
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    tapPath,
                    staggerStart,
                    randomizeDuration(32L, 8L)
                )
            )
            strokeCount++
        }

        if (strokeCount == 0) return false
        return dispatchGestureSafely(builder.build())
    }

    private fun buildCubicBezierPath(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        segments: Int,
        microNoisePx: Float
    ): Path {
        val path = Path()
        path.moveTo(x0, y0)
        val rng = ThreadLocalRandom.current()

        for (step in 1..segments) {
            val t = step.toFloat() / segments.toFloat()
            val invT = 1f - t

            val bx = invT.pow(3) * x0 +
                3f * invT.pow(2) * t * x1 +
                3f * invT * t.pow(2) * x2 +
                t.pow(3) * x3

            val by = invT.pow(3) * y0 +
                3f * invT.pow(2) * t * y1 +
                3f * invT * t.pow(2) * y2 +
                t.pow(3) * y3

            val edgeDamp = if (step == segments) 0f else 1f
            val nx = clampScreenX(bx + (rng.nextFloat() * 2f - 1f) * microNoisePx * edgeDamp)
            val ny = clampScreenY(by + (rng.nextFloat() * 2f - 1f) * microNoisePx * edgeDamp)
            path.lineTo(nx, ny)
        }
        return path
    }

    private fun dispatchGestureSafely(gesture: GestureDescription): Boolean {
        val nowNs = System.nanoTime()
        if (isGestureInFlight.get() && (nowNs - lastGestureDispatchNs) < 38_000_000L) {
            return false
        }

        val handler = touchHandler ?: return false
        isGestureInFlight.set(true)
        lastGestureDispatchNs = nowNs

        val dispatched = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        isGestureInFlight.set(false)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        isGestureInFlight.set(false)
                    }
                },
                handler
            )
        } catch (_: Exception) {
            false
        }

        if (!dispatched) {
            isGestureInFlight.set(false)
        }
        return dispatched
    }

    private fun randomizeDuration(baseMs: Long, maxJitterMs: Long): Long {
        val jitter = ThreadLocalRandom.current().nextLong(-maxJitterMs, maxJitterMs + 1L)
        return max(16L, baseMs + jitter)
    }

    private fun clampScreenX(x: Float): Float {
        return x.coerceIn(1f, (screenWidth - 1).toFloat())
    }

    private fun clampScreenY(y: Float): Float {
        return y.coerceIn(1f, (screenHeight - 1).toFloat())
    }
}
