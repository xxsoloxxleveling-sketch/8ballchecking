package com.pool.guideline.overlay.cv

import android.graphics.Color
import com.pool.guideline.overlay.physics.Vector2D
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class TableFeltPreset {
    AUTO,
    CYAN_TOURNAMENT,
    CLASSIC_GREEN,
    ROYAL_BLUE,
    MIDNIGHT_RED,
    CUSTOM_CALIBRATED
}

data class RawContourData(
    val points: List<Vector2D> = emptyList(),
    val center: Vector2D,
    val radius: Float,
    val area: Float = 0f,
    val circularity: Float = 1f,
    val isAccepted: Boolean = true
)

data class DetectionResult(
    val tableBounds: TableBounds = TableBounds.EMPTY,
    val cueBall: BallData? = null,
    val targetRingPos: Vector2D? = null,
    val targetBalls: List<BallData> = emptyList(),
    val rawContours: List<RawContourData> = emptyList(),
    val aimDirection: Vector2D = Vector2D.ZERO,
    val hasValidAim: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

/**
 * Robust ring-correlation computer vision engine for 8-ball clone.
 * Directly detects the in-game target crosshair ring on the target ball and traces the shooting vector.
 */
class TableAndBallDetector(
    var feltPreset: TableFeltPreset = TableFeltPreset.AUTO
) {

    var calibratedHue: Float = 195f
    var calibratedSat: Float = 0.65f
    var calibratedVal: Float = 0.75f

    var hueTolerance: Float = 25f
    var satTolerance: Float = 0.40f
    var valTolerance: Float = 0.40f

    private var pixelBuffer: IntArray = IntArray(0)
    private var grayBuffer: IntArray = IntArray(0)
    private var cachedTableBounds = TableBounds.EMPTY
    private var tableDetectInterval = 0

    // Pre-allocated ring sample angles (16 points around radius R)
    private val ringAngles = FloatArray(16) { (2.0 * Math.PI * it / 16).toFloat() }
    private val cosAngles = FloatArray(16)
    private val sinAngles = FloatArray(16)

    init {
        for (i in 0 until 16) {
            cosAngles[i] = cos(ringAngles[i])
            sinAngles[i] = sin(ringAngles[i])
        }
    }

    fun calibrateFeltFromRgb(r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        calibratedHue = hsv[0]
        calibratedSat = hsv[1]
        calibratedVal = hsv[2]
        feltPreset = TableFeltPreset.CUSTOM_CALIBRATED
        cachedTableBounds = TableBounds.EMPTY
    }

    fun processFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): DetectionResult {
        val totalPixels = width * height
        if (pixelBuffer.size != totalPixels) {
            pixelBuffer = IntArray(totalPixels)
            grayBuffer = IntArray(totalPixels)
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
                    pixelBuffer[destIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    grayBuffer[destIdx] = (r * 77 + g * 150 + b * 29) shr 8
                    destIdx++
                } else {
                    pixelBuffer[destIdx] = 0xFF000000.toInt()
                    grayBuffer[destIdx] = 0
                    destIdx++
                }
            }
        }

        return processIntPixels(pixelBuffer, grayBuffer, width, height)
    }

    private fun processIntPixels(pixels: IntArray, gray: IntArray, width: Int, height: Int): DetectionResult {
        // Step 1: Table Bounds
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 60 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        val tMinX = max(0, (table.xMin + ballRadius).toInt())
        val tMaxX = min(width - 1, (table.xMax - ballRadius).toInt())
        val tMinY = max(0, (table.yMin + ballRadius).toInt())
        val tMaxY = min(height - 1, (table.yMax - ballRadius).toInt())

        // Step 2: In-Game Target Crosshair Ring Detection (Circular Ring Correlation)
        var bestTargetRing: Vector2D? = null
        var bestRingScore = -1e9f

        val step = 2
        for (y in (tMinY + 10) until (tMaxY - 10) step step) {
            val rowOffset = y * width
            for (x in (tMinX + 10) until (tMaxX - 10) step step) {
                val centerVal = gray[rowOffset + x]
                if (centerVal > 235) continue // Exclude pure white cue ball center

                var perimeterSum = 0
                for (i in 0 until 16) {
                    val px = (x + cosAngles[i] * ballRadius).toInt()
                    val py = (y + sinAngles[i] * ballRadius).toInt()
                    if (px in 0 until width && py in 0 until height) {
                        perimeterSum += gray[py * width + px]
                    }
                }

                val ringMean = perimeterSum / 16f
                if (ringMean > 155f) {
                    val score = ringMean - (centerVal * 0.45f)
                    if (score > bestRingScore) {
                        bestRingScore = score
                        bestTargetRing = Vector2D(x.toFloat(), y.toFloat())
                    }
                }
            }
        }

        var cueBall: BallData? = null
        var aimDir = Vector2D.ZERO
        var hasValidAim = false

        if (bestTargetRing != null) {
            // Step 3: Scan radial rays from target ring to find the shooting axis
            val rx = bestTargetRing.x
            val ry = bestTargetRing.y

            var bestRayScore = -1
            var bestRayAngle = 0f

            val numAngles = 180
            for (i in 0 until numAngles) {
                val rad = (2.0 * Math.PI * i / numAngles).toFloat()
                val ux = cos(rad)
                val uy = sin(rad)

                var raySum = 0
                var d = ballRadius * 1.5f
                while (d < width * 0.55f) {
                    val px = (rx + ux * d).toInt()
                    val py = (ry + uy * d).toInt()
                    if (px in tMinX..tMaxX && py in tMinY..tMaxY) {
                        if (gray[py * width + px] > 175) {
                            raySum++
                        }
                    }
                    d += 5.0f
                }

                if (raySum > bestRayScore) {
                    bestRayScore = raySum
                    bestRayAngle = rad
                }
            }

            if (bestRayScore >= 3) {
                // Direction from target ring along ray points BACKWARDS towards Cue Ball
                val ux = cos(bestRayAngle)
                val uy = sin(bestRayAngle)

                // Locate solid white Cue Ball along this ray
                var cuePos: Vector2D? = null
                var d = ballRadius * 2.5f
                while (d < width * 0.70f) {
                    val px = (rx + ux * d).toInt()
                    val py = (ry + uy * d).toInt()
                    if (px in tMinX..tMaxX && py in tMinY..tMaxY) {
                        val cVal = gray[py * width + px]
                        if (cVal > 205) {
                            // Verify disc
                            val pyUp = max(0, py - 3)
                            val pyDown = min(height - 1, py + 3)
                            if (gray[pyUp * width + px] > 180 && gray[pyDown * width + px] > 180) {
                                cuePos = Vector2D(px.toFloat(), py.toFloat())
                                break
                            }
                        }
                    }
                    d += 4.0f
                }

                val finalCue = cuePos ?: Vector2D(rx + ux * ballRadius * 10f, ry + uy * ballRadius * 10f)
                cueBall = BallData(center = finalCue, radius = ballRadius, type = BallType.CUE)
                aimDir = (bestTargetRing - finalCue).normalized()
                hasValidAim = true
            }
        }

        val targetBalls = if (bestTargetRing != null) {
            listOf(BallData(center = bestTargetRing, radius = ballRadius, type = BallType.OBJECT_SOLID))
        } else emptyList()

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetRingPos = bestTargetRing,
            targetBalls = targetBalls,
            rawContours = emptyList(),
            aimDirection = aimDir,
            hasValidAim = hasValidAim,
            frameWidth = width,
            frameHeight = height
        )
    }

    private fun detectTableBounds(pixels: IntArray, width: Int, height: Int): TableBounds {
        val midY = (height * 0.58f).toInt().coerceIn(0, height - 1)
        val midX = (width * 0.50f).toInt().coerceIn(0, width - 1)

        var xMin = -1
        var xMax = -1
        for (x in 0 until width) {
            val color = pixels[midY * width + x]
            if (isFeltColor(color)) {
                if (xMin == -1) xMin = x
                xMax = x
            }
        }

        var yMin = -1
        var yMax = -1
        for (y in 0 until height) {
            val color = pixels[y * width + midX]
            if (isFeltColor(color)) {
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

    private fun isFeltColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        return when (feltPreset) {
            TableFeltPreset.CUSTOM_CALIBRATED -> {
                val hsv = FloatArray(3)
                Color.RGBToHSV(r, g, b, hsv)
                val hDiff = abs(hsv[0] - calibratedHue).let { min(it, 360f - it) }
                val sDiff = abs(hsv[1] - calibratedSat)
                val vDiff = abs(hsv[2] - calibratedVal)
                hDiff <= hueTolerance && sDiff <= satTolerance && vDiff <= valTolerance
            }
            TableFeltPreset.CYAN_TOURNAMENT -> {
                b > 90 && g > 75 && b > (r * 1.15f) && g > (r * 1.05f)
            }
            TableFeltPreset.CLASSIC_GREEN -> {
                g > 60 && g > (r * 1.2f) && g > (b * 1.05f)
            }
            TableFeltPreset.ROYAL_BLUE -> {
                b > 80 && b > (r * 1.2f) && b > (g * 0.85f)
            }
            TableFeltPreset.MIDNIGHT_RED -> {
                r > 80 && r > (g * 1.3f) && r > (b * 1.3f)
            }
            TableFeltPreset.AUTO -> {
                (b > 90 && g > 75 && b > (r * 1.12f) && g > (r * 1.02f)) ||
                (g > 60 && g > (r * 1.15f) && g > (b * 1.02f)) ||
                (b > 75 && b > (r * 1.15f) && b > (g * 0.85f))
            }
        }
    }
}
