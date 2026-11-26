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

        private val defaultProfile = WeaponRecoilProfile(0.48f, 0.95f, 0.07f, 175L)

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
