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
 * High-performance continuous line raycaster for 8-ball clone.
 * Detects the continuous in-game aiming guideline and determines exact cue ball, target ball, and shot vector.
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
    private var cachedTableBounds = TableBounds.EMPTY
    private var tableDetectInterval = 0

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

        return processIntPixels(pixelBuffer, width, height)
    }

    private fun processIntPixels(pixels: IntArray, width: Int, height: Int): DetectionResult {
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 60 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        val tMinX = max(0, (table.xMin + 4).toInt())
        val tMaxX = min(width - 1, (table.xMax - 4).toInt())
        val tMinY = max(0, (table.yMin + 4).toInt())
        val tMaxY = min(height - 1, (table.yMax - 4).toInt())

        // Step 1: Scan for the continuous white in-game aim guideline
        var bestOriginX = 0
        var bestOriginY = 0
        var bestAngleRad = 0f
        var maxLineWhiteCount = 0

        val gridStep = 8
        val angleStepDeg = 4
        val numAngles = 360 / angleStepDeg

        for (y in (tMinY + 15) until (tMaxY - 15) step gridStep) {
            for (x in (tMinX + 15) until (tMaxX - 15) step gridStep) {
                for (a in 0 until numAngles) {
                    val rad = (a * angleStepDeg * Math.PI / 180.0).toFloat()
                    val cosA = cos(rad)
                    val sinA = sin(rad)

                    var wCount = 0
                    var d = 10.0f
                    while (d < 180.0f) {
                        val px = (x + cosA * d).toInt()
                        val py = (y + sinA * d).toInt()
                        if (px in tMinX..tMaxX && py in tMinY..tMaxY) {
                            val color = pixels[py * width + px]
                            val r = (color shr 16) and 0xFF
                            val g = (color shr 8) and 0xFF
                            val b = color and 0xFF
                            if (r > 190 && g > 190 && b > 190 && abs(r - g) < 25 && abs(g - b) < 25) {
                                wCount++
                            }
                        }
                        d += 4.0f
                    }

                    if (wCount > maxLineWhiteCount) {
                        maxLineWhiteCount = wCount
                        bestOriginX = x
                        bestOriginY = y
                        bestAngleRad = rad
                    }
                }
            }
        }

        if (maxLineWhiteCount < 12) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Step 2: Trace backwards and forwards along the axis to find the Cue Ball and Target Ball
        val ux = cos(bestAngleRad)
        val uy = sin(bestAngleRad)

        var tMin = 0
        var tMax = 0

        var t = -250
        while (t < 250) {
            val px = (bestOriginX + ux * t).toInt()
            val py = (bestOriginY + uy * t).toInt()
            if (px in tMinX..tMaxX && py in tMinY..tMaxY) {
                val color = pixels[py * width + px]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                if (r > 185 && g > 185 && b > 185 && abs(r - g) < 25 && abs(g - b) < 25) {
                    if (t < tMin) tMin = t
                    if (t > tMax) tMax = t
                }
            }
            t += 4
        }

        val pt1 = Vector2D(bestOriginX + ux * tMin, bestOriginY + uy * tMin)
        val pt2 = Vector2D(bestOriginX + ux * tMax, bestOriginY + uy * tMax)

        // Check for cue stick texture behind pt1 vs pt2
        var stick1 = 0
        var stick2 = 0
        val sampleDists = intArrayOf(18, 35, 60, 90)

        for (sd in sampleDists) {
            val p1x = (pt1.x - ux * sd).toInt().coerceIn(0, width - 1)
            val p1y = (pt1.y - uy * sd).toInt().coerceIn(0, height - 1)
            val col1 = pixels[p1y * width + p1x]
            val r1 = (col1 shr 16) and 0xFF
            val b1 = col1 and 0xFF
            if (r1 > 110 && r1 > b1 * 1.2f) stick1++

            val p2x = (pt2.x + ux * sd).toInt().coerceIn(0, width - 1)
            val p2y = (pt2.y + uy * sd).toInt().coerceIn(0, height - 1)
            val col2 = pixels[p2y * width + p2x]
            val r2 = (col2 shr 16) and 0xFF
            val b2 = col2 and 0xFF
            if (r2 > 110 && r2 > b2 * 1.2f) stick2++
        }

        val (cuePos, targetPos, shotDir) = if (stick1 >= stick2) {
            Triple(pt1, pt2, Vector2D(ux, uy))
        } else {
            Triple(pt2, pt1, Vector2D(-ux, -uy))
        }

        val cueBall = BallData(center = cuePos, radius = ballRadius, type = BallType.CUE)
        val targetBalls = listOf(BallData(center = targetPos, radius = ballRadius, type = BallType.OBJECT_SOLID))

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetRingPos = targetPos,
            targetBalls = targetBalls,
            rawContours = emptyList(),
            aimDirection = shotDir,
            hasValidAim = true,
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
