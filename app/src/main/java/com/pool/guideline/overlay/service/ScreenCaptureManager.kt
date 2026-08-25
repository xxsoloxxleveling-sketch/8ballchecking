package com.pool.guideline.overlay.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import com.pool.guideline.overlay.cv.TableAndBallDetector
import com.pool.guideline.overlay.cv.TableFeltPreset
import com.pool.guideline.overlay.physics.TrajectoryPhysicsEngine
import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.physics.Vector2D
import com.pool.guideline.overlay.ui.OverlayCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages screen capturing via MediaProjection & ImageReader at downsampled resolution (50% scale),
 * executing real-time CV ball detection and trajectory physics in a dedicated coroutine.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val overlayView: OverlayCanvasView
) {
    private val tag = "ScreenCaptureMgr"

    // Downscaled working resolution for 60fps CV processing
    private val downsampleFactor = 0.5f
    private var processWidth = 960
    private var processHeight = 540
    private var screenDensity = 1

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val detector = TableAndBallDetector(TableFeltPreset.AUTO)
    private val physicsEngine = TrajectoryPhysicsEngine(maxBounces = 3)

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRunning = AtomicBoolean(false)
    private var processingFrame = AtomicBoolean(false)

    fun startCapture(metrics: DisplayMetrics) {
        if (isRunning.getAndSet(true)) return

        screenDensity = metrics.densityDpi
        processWidth = ((metrics.widthPixels * downsampleFactor).toInt() / 16) * 16
        processHeight = ((metrics.heightPixels * downsampleFactor).toInt() / 16) * 16

        overlayView.coordScaleX = metrics.widthPixels.toFloat() / processWidth.toFloat()
        overlayView.coordScaleY = metrics.heightPixels.toFloat() / processHeight.toFloat()

        Log.i(tag, "Starting ScreenCapture: Screen=${metrics.widthPixels}x${metrics.heightPixels}, Process=${processWidth}x${processHeight}")

        imageReader = ImageReader.newInstance(
            processWidth,
            processHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PoolOverlayCapture",
            processWidth,
            processHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!isRunning.get()) return@setOnImageAvailableListener

            // Frame throttling: Drop backed up frames if previous frame is still processing
            if (processingFrame.compareAndSet(false, true)) {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    scope.launch {
                        try {
                            processImageFrame(image)
                        } finally {
                            image.close()
                            processingFrame.set(false)
                        }
                    }
                } else {
                    processingFrame.set(false)
                }
            } else {
                // Drop image to avoid buffer queue overflow
                reader.acquireLatestImage()?.close()
            }
        }, null)
    }

    private fun processImageFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        // Zero-allocation frame extraction & detection
        val detection = detector.processFrame(
            buffer = buffer,
            width = image.width,
            height = image.height,
            rowStride = rowStride,
            pixelStride = pixelStride
        )

        if (detection.tableBounds.isValid && detection.cueBall != null && detection.hasValidAim) {
            val trajectory = physicsEngine.computeTrajectory(
                cueBallPos = detection.cueBall.center,
                aimDirection = detection.aimDirection,
                targetBalls = detection.targetBalls,
                tableBounds = detection.tableBounds,
                ballRadius = detection.tableBounds.estimatedBallRadius
            )
            overlayView.updateTrajectory(trajectory, detection.tableBounds)
        } else if (detection.tableBounds.isValid) {
            // Still update table bounds for debug rendering even if cue isn't aiming
            overlayView.updateTrajectory(TrajectoryResult.EMPTY, detection.tableBounds)
        } else {
            overlayView.updateTrajectory(TrajectoryResult.EMPTY, detection.tableBounds)
        }
    }

    fun setFeltPreset(preset: TableFeltPreset) {
        detector.feltPreset = preset
    }

    fun setMaxBounces(bounces: Int) {
        physicsEngine.maxBounces = bounces
    }

    fun stopCapture() {
        isRunning.set(false)
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error during capture teardown: ${e.message}")
        }
    }
}
