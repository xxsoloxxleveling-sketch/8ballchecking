package com.pool.guideline.overlay.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.TableAndBallDetector
import com.pool.guideline.overlay.cv.TableFeltPreset
import com.pool.guideline.overlay.physics.TrajectoryPhysicsEngine
import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.ui.OverlayCanvasView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance 60 FPS screen capture & trajectory overlay manager.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val overlayView: OverlayCanvasView
) {
    private val tag = "ScreenCaptureMgr"

    private var processWidth = 640
    private var processHeight = 360

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    private val detector = TableAndBallDetector(TableFeltPreset.AUTO)
    private val physicsEngine = TrajectoryPhysicsEngine(maxBounces = 4)

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var isRunning = AtomicBoolean(false)
    private var processingFrame = AtomicBoolean(false)

    fun startCapture(screenWidth: Int, screenHeight: Int, densityDpi: Int) {
        if (isRunning.getAndSet(true)) return

        val sWidth = if (screenWidth > 0) screenWidth else 1920
        val sHeight = if (screenHeight > 0) screenHeight else 1080
        val density = if (densityDpi > 0) densityDpi else 320

        val scale = 640f / sWidth.toFloat()
        processWidth = 640
        processHeight = ((sHeight * scale).toInt() / 16) * 16

        overlayView.coordScaleX = sWidth.toFloat() / processWidth.toFloat()
        overlayView.coordScaleY = sHeight.toFloat() / processHeight.toFloat()

        Log.i(tag, "ScreenCapture 60FPS Init: Screen=${sWidth}x${sHeight}, CV=${processWidth}x${processHeight}")

        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(tag, "MediaProjection stopped")
                stopCapture()
            }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(
            processWidth,
            processHeight,
            PixelFormat.RGBA_8888,
            2
        )

        handlerThread = HandlerThread("PoolImageReaderThread", Thread.MAX_PRIORITY).apply { start() }
        val workerHandler = Handler(handlerThread!!.looper)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PoolOverlayCapture",
            processWidth,
            processHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            workerHandler
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            if (!isRunning.get()) return@setOnImageAvailableListener

            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            if (processingFrame.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        processImageFrame(image)
                    } catch (t: Throwable) {
                        Log.e(tag, "CV error: ${t.message}")
                    } finally {
                        image.close()
                        processingFrame.set(false)
                    }
                }
            } else {
                image.close()
            }
        }, workerHandler)
    }

    private fun processImageFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val detection = detector.processFrame(
            buffer = buffer,
            width = image.width,
            height = image.height,
            rowStride = rowStride,
            pixelStride = pixelStride
        )

        val allAcceptedBalls = ArrayList<BallData>()
        detection.cueBall?.let { allAcceptedBalls.add(it) }
        allAcceptedBalls.addAll(detection.targetBalls)

        if (detection.tableBounds.isValid && detection.cueBall != null && detection.hasValidAim) {
            val trajectory = physicsEngine.computeTrajectory(
                cueBallPos = detection.cueBall.center,
                aimDirection = detection.aimDirection,
                targetRingPos = detection.targetRingPos,
                targetBalls = detection.targetBalls,
                tableBounds = detection.tableBounds,
                ballRadius = detection.tableBounds.estimatedBallRadius
            )
            overlayView.updateTrajectory(trajectory, detection.tableBounds, detection.rawContours, allAcceptedBalls)
        } else {
            overlayView.updateTrajectory(TrajectoryResult.EMPTY, detection.tableBounds, detection.rawContours, allAcceptedBalls)
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
            handlerThread?.quitSafely()
            handlerThread = null
            mediaProjection.stop()
        } catch (e: Exception) {
            Log.e(tag, "Teardown error: ${e.message}")
        }
    }
}
