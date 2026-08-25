package com.pool.guideline.overlay.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pool.guideline.overlay.MainActivity
import com.pool.guideline.overlay.R
import com.pool.guideline.overlay.cv.TableBoundsCalibration
import com.pool.guideline.overlay.cv.TableFeltPreset
import com.pool.guideline.overlay.ui.CalibrationActivity
import com.pool.guideline.overlay.ui.OverlayCanvasView

/**
 * Foreground Service running the floating transparent overlay and hosting the MediaProjection pipeline.
 * Adheres to Android 14+ (API 34/35) foregroundServiceType="mediaProjection" requirements.
 */
class OverlayService : Service() {

    private val tag = "OverlayService"
    private var windowManager: WindowManager? = null
    private var overlayView: OverlayCanvasView? = null
    private var captureManager: ScreenCaptureManager? = null

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "pool_overlay_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pool.guideline.overlay.START"
        const val ACTION_STOP = "com.pool.guideline.overlay.STOP"
        const val ACTION_UPDATE_CONFIG = "com.pool.guideline.overlay.UPDATE_CONFIG"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_FELT_PRESET = "extra_felt_preset"
        const val EXTRA_MAX_BOUNCES = "extra_max_bounces"
        const val EXTRA_SHOW_DEBUG = "extra_show_debug"
        const val EXTRA_SMOOTHING_ALPHA = "extra_smoothing_alpha"

        var isRunning = false
            private set

        var instance: OverlayService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(tag, "OverlayService onCreate - Starting Foreground immediately")
        createNotificationChannel()
        startAsForegroundService()
        initOverlayView()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startScreenCapture(resultCode, resultData)
                } else {
                    Log.e(tag, "Invalid MediaProjection token received in onStartCommand")
                }
            }
            ACTION_UPDATE_CONFIG -> {
                val presetName = intent.getStringExtra(EXTRA_FELT_PRESET)
                val maxBounces = intent.getIntExtra(EXTRA_MAX_BOUNCES, 3)
                val showDebug = intent.getBooleanExtra(EXTRA_SHOW_DEBUG, true)
                val alpha = intent.getFloatExtra(EXTRA_SMOOTHING_ALPHA, 0.35f)

                if (presetName != null) {
                    val preset = runCatching { TableFeltPreset.valueOf(presetName) }.getOrDefault(TableFeltPreset.AUTO)
                    captureManager?.setFeltPreset(preset)
                }
                captureManager?.setMaxBounces(maxBounces)
                overlayView?.showDebugBounds = showDebug
                overlayView?.setSmoothingAlpha(alpha)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startAsForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initOverlayView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayCanvasView(this).apply {
            showDebugBounds = true
            showFps = true
        }

        val isCalibrated = TableBoundsCalibration.getTableBounds(this) != null

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            // If not calibrated, allow touch events so tapping prompt opens calibration
            flags = if (!isCalibrated) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }

            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }

        windowManager?.addView(overlayView, layoutParams)
    }

    fun setOverlayTouchable(touchable: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = overlayView ?: return@post
            val wm = windowManager ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            val targetFlags = if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }

            if (params.flags != targetFlags) {
                params.flags = targetFlags
                try {
                    wm.updateViewLayout(view, params)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to update view layout: ${e.message}")
                }
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mediaProjection: MediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val (screenWidth, screenHeight) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            if (bounds != null && bounds.width() > 0) {
                Pair(bounds.width(), bounds.height())
            } else {
                Pair(1920, 1080)
            }
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }

        val densityDpi = resources.displayMetrics.densityDpi

        captureManager?.stopCapture()
        overlayView?.let { view ->
            captureManager = ScreenCaptureManager(this, mediaProjection, view).apply {
                startCapture(screenWidth, screenHeight, densityDpi)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Pool AI Guideline Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active transparent pool guideline overlay"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pendingMain = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val calibrateIntent = Intent(this, CalibrationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingCalibrate = PendingIntent.getActivity(
            this,
            2,
            calibrateIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Pool Guideline Active")
            .setContentText("Real-time AI trajectory overlay is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingMain)
            .addAction(android.R.drawable.ic_menu_edit, "Calibrate Table", pendingCalibrate)
            .addAction(android.R.drawable.ic_delete, "Stop Overlay", pendingStop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(tag, "OverlayService onDestroy - Cleaning up resources")
        isRunning = false
        instance = null

        captureManager?.stopCapture()
        captureManager = null

        overlayView?.let { view ->
            windowManager?.removeView(view)
            overlayView = null
        }
    }
}
