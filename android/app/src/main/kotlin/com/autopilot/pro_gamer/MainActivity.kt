package com.autopilot.pro_gamer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL = "com.autopilot.pro_gamer/engine"
        private const val EVENT_CHANNEL = "com.autopilot.pro_gamer/engine_state"
        private const val REQUEST_MEDIA_PROJECTION = 9001
        private const val REQUEST_POST_NOTIFICATIONS = 9002
    }

    private val appListExecutor = Executors.newSingleThreadExecutor()
    private var eventSink: EventChannel.EventSink? = null
    private var pendingStartResult: MethodChannel.Result? = null
    private var pendingPackageName: String? = null
    private var pendingAppName: String? = null
    private var pendingDirective: String? = null
    private var isReceiverRegistered = false

    private val engineStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ScreenCaptureService.ACTION_ENGINE_STATE_CHANGED) {
                val running = intent.getBooleanExtra(ScreenCaptureService.EXTRA_RUNNING, false)
                val message = intent.getStringExtra(ScreenCaptureService.EXTRA_MESSAGE) ?: ""
                runOnUiThread {
                    eventSink?.success(
                        mapOf(
                            "running" to running,
                            "message" to message
                        )
                    )
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            METHOD_CHANNEL
        ).setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            when (call.method) {
                "getInstalledApps" -> handleGetInstalledApps(result)
                "checkPermissions" -> result.success(buildPermissionMap())
                "requestOverlayPermission" -> {
                    openOverlayPermissionSettings()
                    result.success(null)
                }
                "openAccessibilitySettings" -> {
                    openAccessibilitySettingsScreen()
                    result.success(null)
                }
                "requestNotificationPermission" -> {
                    requestNotificationPermission()
                    result.success(null)
                }
                "isEngineRunning" -> result.success(ScreenCaptureService.isRunning)
                "startEngine" -> handleStartEngine(call, result)
                "stopEngine" -> {
                    stopAllEngineServices()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            EVENT_CHANNEL
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
                registerStateReceiver()
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
                unregisterStateReceiver()
            }
        })
    }

    override fun onDestroy() {
        unregisterStateReceiver()
        appListExecutor.shutdown()
        super.onDestroy()
    }

    private fun registerStateReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(ScreenCaptureService.ACTION_ENGINE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(engineStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(engineStateReceiver, filter)
        }
        isReceiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(engineStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        isReceiverRegistered = false
    }

    private fun handleGetInstalledApps(result: MethodChannel.Result) {
        appListExecutor.execute {
            try {
                val pm = packageManager
                val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }

                val appPayload = ArrayList<Map<String, Any>>()
                for (appInfo in installedApps) {
                    if (appInfo.packageName == packageName) continue
                    val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) ?: continue
                    if (launchIntent.component == null) continue

                    val label = pm.getApplicationLabel(appInfo).toString().trim()
                    if (label.isEmpty()) continue

                    val isGame = isApplicationGame(appInfo)
                    val iconBytes = drawableToPngBytes(pm.getApplicationIcon(appInfo))

                    appPayload.add(
                        mapOf(
                            "appName" to label,
                            "packageName" to appInfo.packageName,
                            "icon" to iconBytes,
                            "isGame" to isGame
                        )
                    )
                }

                appPayload.sortWith(
                    compareByDescending<Map<String, Any>> { it["isGame"] as Boolean }
                        .thenBy { (it["appName"] as String).lowercase() }
                )

                runOnUiThread {
                    result.success(appPayload)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    result.error("APP_QUERY_FAILED", e.message, null)
                }
            }
        }
    }

    private fun isApplicationGame(appInfo: ApplicationInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            appInfo.category == ApplicationInfo.CATEGORY_GAME
        ) {
            return true
        }
        @Suppress("DEPRECATION")
        return (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    private fun drawableToPngBytes(drawable: Drawable): ByteArray {
        val targetSize = 96
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, targetSize, targetSize, true)
        } else {
            val created = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(created)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            created
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    private fun buildPermissionMap(): Map<String, Boolean> {
        val overlayGranted = Settings.canDrawOverlays(this)
        val accessibilityGranted = isAccessibilityServiceEnabled()
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return mapOf(
            "overlay" to overlayGranted,
            "accessibility" to accessibilityGranted,
            "notification" to notificationGranted,
            "usageStats" to true
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        )
        if (enabled != 1) return false

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedPrefix = "$packageName/"
        return enabledServices.split(':').any { it.startsWith(expectedPrefix, ignoreCase = true) }
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openAccessibilitySettingsScreen() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun handleStartEngine(call: MethodCall, result: MethodChannel.Result) {
        val targetPackage = call.argument<String>("packageName")?.trim().orEmpty()
        val targetAppName = call.argument<String>("appName")?.trim().orEmpty()
        val directive = call.argument<String>("directive")?.trim().orEmpty()

        if (targetPackage.isEmpty()) {
            result.error("INVALID_PACKAGE", "Target packageName is required.", null)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            result.error("MISSING_OVERLAY", "Overlay permission is required.", null)
            return
        }

        if (pendingStartResult != null) {
            result.error("START_IN_PROGRESS", "Engine start request already pending.", null)
            return
        }

        pendingStartResult = result
        pendingPackageName = targetPackage
        pendingAppName = targetAppName.ifEmpty { targetPackage }
        pendingDirective = directive

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        val callbackResult = pendingStartResult
        val targetPackage = pendingPackageName
        val targetAppName = pendingAppName ?: targetPackage.orEmpty()
        val directive = pendingDirective.orEmpty()

        pendingStartResult = null
        pendingPackageName = null
        pendingAppName = null
        pendingDirective = null

        if (resultCode != Activity.RESULT_OK || data == null || targetPackage.isNullOrEmpty()) {
            callbackResult?.success(false)
            eventSink?.success(
                mapOf(
                    "running" to false,
                    "message" to "Screen capture permission was declined."
                )
            )
            return
        }

        val captureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_ENGINE
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_PACKAGE_NAME, targetPackage)
            putExtra(ScreenCaptureService.EXTRA_APP_NAME, targetAppName)
            putExtra(ScreenCaptureService.EXTRA_DIRECTIVE, directive)
        }
        ContextCompat.startForegroundService(this, captureIntent)

        val dotIntent = Intent(this, FloatingDotService::class.java)
        startService(dotIntent)

        val launchSucceeded = launchTargetGame(targetPackage)
        callbackResult?.success(true)
        eventSink?.success(
            mapOf(
                "running" to true,
                "message" to if (launchSucceeded) {
                    "Autopilot active for $targetAppName"
                } else {
                    "Autopilot active, unable to auto-open $targetAppName"
                }
            )
        )
    }

    private fun launchTargetGame(targetPackage: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return false
        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        return try {
            startActivity(launchIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopAllEngineServices() {
        val stopCaptureIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_ENGINE
        }
        startService(stopCaptureIntent)
        stopService(Intent(this, FloatingDotService::class.java))
    }
}
