package com.autopilot.pro_gamer

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_ENGINE = "com.autopilot.pro_gamer.START_ENGINE"
        const val ACTION_STOP_ENGINE = "com.autopilot.pro_gamer.STOP_ENGINE"
        const val ACTION_ENGINE_STATE_CHANGED = "com.autopilot.pro_gamer.ENGINE_STATE_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_DIRECTIVE = "directive"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "autopilot_engine_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set

        init {
            try {
                System.loadLibrary("autopilot_engine")
            } catch (_: UnsatisfiedLinkError) {
            }
        }
    }

    private external fun nativeInitEngine(width: Int, height: Int, targetPackage: String, directive: String)
    private external fun nativeProcessFrame(buffer: java.nio.ByteBuffer, width: Int, height: Int, pixelStride: Int, rowStride: Int, timestampNs: Long)
    private external fun nativeStopEngine()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ENGINE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
                val directive = intent.getStringExtra(EXTRA_DIRECTIVE) ?: ""

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundServiceNotification()
                    startCapture(resultCode, resultData, targetPackage, directive)
                }
            }
            ACTION_STOP_ENGINE -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pro-Gamer AI Engine",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pro-Gamer AI Active")
            .setContentText("Autonomous co-pilot engine is running.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startCapture(resultCode: Int, resultData: Intent, targetPackage: String, directive: String) {
        if (isRunning) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = (metrics.widthPixels / 2).coerceAtLeast(360)
        val height = (metrics.heightPixels / 2).coerceAtLeast(640)
        val density = metrics.densityDpi

        AutoTouchAccessibilityService.setTargetPackage(targetPackage)
        try {
            nativeInitEngine(width, height, targetPackage, directive)
        } catch (_: UnsatisfiedLinkError) {
        }

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                nativeProcessFrame(
                    plane.buffer,
                    image.width,
                    image.height,
                    plane.pixelStride,
                    plane.rowStride,
                    image.timestamp
                )
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, captureHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutopilotDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        isRunning = true
        broadcastState(true, "Engine started")
    }

    private fun stopCapture() {
        if (!isRunning) return
        isRunning = false
        try {
            nativeStopEngine()
        } catch (_: UnsatisfiedLinkError) {
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        broadcastState(false, "Engine stopped")
    }

    private fun broadcastState(running: Boolean, message: String) {
        val intent = Intent(ACTION_ENGINE_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
