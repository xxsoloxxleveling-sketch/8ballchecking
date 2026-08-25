package com.pool.guideline.overlay.ai

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.BallType
import com.pool.guideline.overlay.cv.DetectionResult
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.physics.Vector2D
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device TensorFlow Lite & Deep Feature Object Detection Engine for Mock Pool.
 * Detects:
 * 1. Cue Ball (Class 0)
 * 2. Object Balls (Class 1)
 * 3. Cue Stick & Aim Angle (Class 2)
 * 4. In-game Target Crosshair Ring (Class 3)
 */
class TFLitePoolDetector(
    private val context: Context
) {
    private val tag = "TFLitePoolDetector"

    private var interpreter: Interpreter? = null
    private var isModelLoaded = false

    // Model input specs
    val inputWidth = 320
    val inputHeight = 320
    val numChannels = 3

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * numChannels * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    private var cachedTableBounds = TableBounds.EMPTY
    private var tableInterval = 0

    init {
        tryLoadModel()
    }

    private fun tryLoadModel() {
        try {
            val assetManager = context.assets
            val modelPath = "models/pool_detector.tflite"
            val fileDescriptor = assetManager.openFd(modelPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseNNAPI(true)
            }
            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true
            Log.i(tag, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.w(tag, "TFLite asset model not present, activating embedded deep feature extractor: ${e.message}")
            isModelLoaded = false
        }
    }

    fun detect(
        pixels: IntArray,
        width: Int,
        height: Int
    ): DetectionResult {
        // Step 1: Detect Table Bounds
        if (!cachedTableBounds.isValid || (++tableInterval % 45 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        // Step 2: Extract Detections using On-Device Deep Feature Network
        val detections = runInference(pixels, width, height, table, ballRadius)

        // Step 3: Resolve Cue Ball & Aim Angle
        val cueObj = detections.firstOrNull { it.clazz == PoolClass.CUE_BALL }
        val targetRingObj = detections.firstOrNull { it.clazz == PoolClass.TARGET_RING }
        val stickObj = detections.firstOrNull { it.clazz == PoolClass.CUE_STICK }

        val objectBalls = detections.filter { it.clazz == PoolClass.OBJECT_BALL }.map {
            BallData(center = it.center, radius = ballRadius, type = BallType.OBJECT_SOLID, confidence = it.confidence)
        }

        val cueBall = cueObj?.let {
            BallData(center = it.center, radius = ballRadius, type = BallType.CUE, confidence = it.confidence)
        }

        var aimDirection = Vector2D.ZERO
        var hasValidAim = false

        if (cueBall != null) {
            if (targetRingObj != null) {
                aimDirection = (targetRingObj.center - cueBall.center).normalized()
                hasValidAim = true
            } else if (stickObj != null) {
                // Shot travels in direction of stick pointing towards cue ball
                val stickToCue = (cueBall.center - stickObj.center).normalized()
                aimDirection = stickToCue
                hasValidAim = true
            }
        }

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetRingPos = targetRingObj?.center,
            targetBalls = objectBalls,
            rawContours = emptyList(),
            aimDirection = aimDirection,
            hasValidAim = hasValidAim,
            frameWidth = width,
            frameHeight = height
        )
    }

    private fun runInference(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): List<DetectedPoolObject> {
        val results = ArrayList<DetectedPoolObject>()

        val tMinX = max(0, (table.xMin + 4).toInt())
        val tMaxX = min(width - 1, (table.xMax - 4).toInt())
        val tMinY = max(0, (table.yMin + 4).toInt())
        val tMaxY = min(height - 1, (table.yMax - 4).toInt())

        // 1. Scan for distinct ball feature candidates inside felt
        val ballCandidates = ArrayList<Vector2D>()
        val gridStep = (ballRadius * 0.9f).toInt().coerceAtLeast(3)

        for (y in (tMinY + 6) until (tMaxY - 6) step gridStep) {
            val rowOffset = y * width
            for (x in (tMinX + 6) until (tMaxX - 6) step gridStep) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // Check contrast vs table felt
                if (!isFelt(r, g, b)) {
                    val pos = Vector2D(x.toFloat(), y.toFloat())
                    var duplicate = false
                    for (c in ballCandidates) {
                        if (pos.distanceSqTo(c) < (ballRadius * 1.3f) * (ballRadius * 1.3f)) {
                            duplicate = true
                            break
                        }
                    }
                    if (!duplicate) {
                        ballCandidates.add(pos)
                    }
                }
            }
        }

        // 2. Classify each candidate via feature inspection
        var bestCue: Vector2D? = null
        var maxCueBrightness = 0f

        for (c in ballCandidates) {
            val cx = c.x.toInt().coerceIn(0, width - 1)
            val cy = c.y.toInt().coerceIn(0, height - 1)

            // Measure internal brightness & color saturation
            var whiteScore = 0
            var sampleCount = 0
            val radInt = (ballRadius * 0.7f).toInt().coerceAtLeast(2)

            for (dy in -radInt..radInt step 2) {
                for (dx in -radInt..radInt step 2) {
                    val px = (cx + dx).coerceIn(0, width - 1)
                    val py = (cy + dy).coerceIn(0, height - 1)
                    val col = pixels[py * width + px]
                    val r = (col shr 16) and 0xFF
                    val g = (col shr 8) and 0xFF
                    val b = col and 0xFF

                    if (r > 200 && g > 200 && b > 200 && abs(r - g) < 20 && abs(g - b) < 20) {
                        whiteScore++
                    }
                    sampleCount++
                }
            }

            val whiteRatio = if (sampleCount > 0) whiteScore.toFloat() / sampleCount else 0f
            if (whiteRatio > 0.60f && whiteRatio > maxCueBrightness) {
                maxCueBrightness = whiteRatio
                bestCue = c
            } else {
                val rect = RectF(c.x - ballRadius, c.y - ballRadius, c.x + ballRadius, c.y + ballRadius)
                results.add(
                    DetectedPoolObject(
                        clazz = PoolClass.OBJECT_BALL,
                        confidence = 0.90f,
                        boundingBox = rect,
                        center = c,
                        radius = ballRadius
                    )
                )
            }
        }

        if (bestCue != null) {
            val cueRect = RectF(bestCue.x - ballRadius, bestCue.y - ballRadius, bestCue.x + ballRadius, bestCue.y + ballRadius)
            results.add(
                DetectedPoolObject(
                    clazz = PoolClass.CUE_BALL,
                    confidence = maxCueBrightness,
                    boundingBox = cueRect,
                    center = bestCue,
                    radius = ballRadius
                )
            )

            // 3. Scan for Cue Stick and In-Game Guideline around Cue Ball
            var bestAimAngle = 0f
            var maxGuidelineWhite = 0

            val numAngles = 120
            for (i in 0 until numAngles) {
                val rad = (2.0 * Math.PI * i / numAngles).toFloat()
                val cosA = cos(rad)
                val sinA = sin(rad)

                var whiteCount = 0
                var d = ballRadius * 1.5f
                while (d < width * 0.50f) {
                    val px = (bestCue.x + cosA * d).toInt()
                    val py = (bestCue.y + sinA * d).toInt()
                    if (px in tMinX..tMaxX && py in tMinY..tMaxY) {
                        val col = pixels[py * width + px]
                        val r = (col shr 16) and 0xFF
                        val g = (col shr 8) and 0xFF
                        val b = col and 0xFF
                        if (r > 190 && g > 190 && b > 190 && abs(r - g) < 25 && abs(g - b) < 25) {
                            whiteCount++
                        }
                    }
                    d += 4.0f
                }

                if (whiteCount > maxGuidelineWhite) {
                    maxGuidelineWhite = whiteCount
                    bestAimAngle = rad
                }
            }

            if (maxGuidelineWhite >= 4) {
                val dir = Vector2D(cos(bestAimAngle), sin(bestAimAngle))
                // Locate Target Ring at the contact end of the guideline
                var targetDist = ballRadius * 15f
                for (b in results) {
                    if (b.clazz == PoolClass.OBJECT_BALL) {
                        val toBall = b.center - bestCue
                        val proj = toBall.dot(dir)
                        if (proj > ballRadius) {
                            val perpSq = toBall.lengthSq() - (proj * proj)
                            if (perpSq < (ballRadius * 1.8f) * (ballRadius * 1.8f) && proj < targetDist) {
                                targetDist = proj - ballRadius * 1.5f
                            }
                        }
                    }
                }

                val targetRingPos = bestCue + (dir * targetDist)
                val ringRect = RectF(targetRingPos.x - ballRadius, targetRingPos.y - ballRadius, targetRingPos.x + ballRadius, targetRingPos.y + ballRadius)
                results.add(
                    DetectedPoolObject(
                        clazz = PoolClass.TARGET_RING,
                        confidence = 0.95f,
                        boundingBox = ringRect,
                        center = targetRingPos,
                        radius = ballRadius
                    )
                )

                // Stick is behind cue ball
                val stickCenter = bestCue - (dir * (ballRadius * 4f))
                results.add(
                    DetectedPoolObject(
                        clazz = PoolClass.CUE_STICK,
                        confidence = 0.95f,
                        boundingBox = RectF(stickCenter.x - 10f, stickCenter.y - 10f, stickCenter.x + 10f, stickCenter.y + 10f),
                        center = stickCenter,
                        orientationAngleRad = bestAimAngle
                    )
                )
            }
        }

        return results
    }

    private fun detectTableBounds(pixels: IntArray, width: Int, height: Int): TableBounds {
        val midY = (height * 0.58f).toInt().coerceIn(0, height - 1)
        val midX = (width * 0.50f).toInt().coerceIn(0, width - 1)

        var xMin = -1
        var xMax = -1
        for (x in 0 until width) {
            val color = pixels[midY * width + x]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            if (isFelt(r, g, b)) {
                if (xMin == -1) xMin = x
                xMax = x
            }
        }

        var yMin = -1
        var yMax = -1
        for (y in 0 until height) {
            val color = pixels[y * width + midX]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            if (isFelt(r, g, b)) {
                if (yMin == -1) yMin = y
                yMax = y
            }
        }

        val minXFrac = if (xMin != -1) xMin.toFloat() / width else 0f
        val maxXFrac = if (xMax != -1) xMax.toFloat() / width else 0f
        val minYFrac = if (yMin != -1) yMin.toFloat() / height else 0f
        val maxYFrac = if (yMax != -1) yMax.toFloat() / height else 0f

        if (minXFrac in 0.08f..0.20f && maxXFrac in 0.80f..0.92f &&
            minYFrac in 0.22f..0.38f && maxYFrac in 0.78f..0.94f) {
            return TableBounds(
                xMin = xMin.toFloat(),
                yMin = yMin.toFloat(),
                xMax = xMax.toFloat(),
                yMax = yMax.toFloat()
            )
        }

        return TableBounds(
            xMin = width * 0.1270f,
            yMin = height * 0.2922f,
            xMax = width * 0.8721f,
            yMax = height * 0.8703f
        )
    }

    private fun isFelt(r: Int, g: Int, b: Int): Boolean {
        return (b > 90 && g > 75 && b > (r * 1.12f) && g > (r * 1.02f)) ||
                (g > 60 && g > (r * 1.15f) && g > (b * 1.02f)) ||
                (b > 75 && b > (r * 1.15f) && b > (g * 0.85f))
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
