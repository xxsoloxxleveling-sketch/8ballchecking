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
import com.pool.guideline.overlay.ai.TFLitePoolDetector
import com.pool.guideline.overlay.cv.BallData
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
 * 60 FPS screen capture pipeline driven by on-device TensorFlow Lite / Deep Feature AI Detection.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val overlayView: OverlayCanvasView
) {
    private val tag = "ScreenCaptureMgr"

    // 60fps working resolution (640-pixel width)
    private var processWidth = 640
    private var processHeight = 360

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    private val tfliteDetector = TFLitePoolDetector(context)
    private val physicsEngine = TrajectoryPhysicsEngine(maxBounces = 4)

    private var pixelBuffer: IntArray = IntArray(0)

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

        Log.i(tag, "ScreenCapture AI Init: Screen=${sWidth}x${sHeight}, Processing=${processWidth}x${processHeight}")

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

        handlerThread = HandlerThread("PoolAIImageReaderThread").apply { start() }
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

            val image = reader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener

            if (processingFrame.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        processImageFrame(image)
                    } catch (t: Throwable) {
                        Log.e(tag, "AI processing error: ${t.message}")
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

        val width = image.width
        val height = image.height
        val total = width * height

        if (pixelBuffer.size != total) {
            pixelBuffer = IntArray(total)
        }

        buffer.position(0)
        var destIdx = 0
        val bufferLimit = buffer.limit()

        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + (x * pixelStride)
                if (offset + 2 < bufferLimit) {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    pixelBuffer[destIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    pixelBuffer[destIdx++] = 0xFF000000.toInt()
                }
            }
        }

        val detection = tfliteDetector.detect(pixelBuffer, width, height)

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
        // AI model handles table skin variants automatically
    }

    fun setMaxBounces(bounces: Int) {
        physicsEngine.maxBounces = bounces
    }

    fun stopCapture() {
        isRunning.set(false)
        try {
            tfliteDetector.close()
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
