package com.pool.guideline.overlay.cv

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
    MIDNIGHT_RED
}

data class DetectionResult(
    val tableBounds: TableBounds = TableBounds.EMPTY,
    val cueBall: BallData? = null,
    val targetBalls: List<BallData> = emptyList(),
    val aimDirection: Vector2D = Vector2D.ZERO,
    val hasValidAim: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

/**
 * High-performance, zero-allocation computer vision engine for 8-ball pool.
 * Calibrated specifically to match Miniclip 8-Ball Pool felt, ball radii, and aiming guidelines.
 */
class TableAndBallDetector(
    var feltPreset: TableFeltPreset = TableFeltPreset.AUTO
) {

    private var pixelBuffer: IntArray = IntArray(0)
    private var cachedTableBounds = TableBounds.EMPTY
    private var tableDetectInterval = 0

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
        // Step 1: Detect or Cache Table Bounds
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 45 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        // Step 2: Detect Cue Ball strictly inside the table boundaries
        val cueBall = detectCueBall(pixels, width, height, table, ballRadius)

        // Step 3: Detect Object Balls strictly inside the table boundaries
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        // Step 4: Detect Aim Vector from cue ball
        var aimVector = Vector2D.ZERO
        var hasValidAim = false

        if (cueBall != null) {
            val (aim, valid) = detectActiveAim(pixels, width, height, cueBall.center, ballRadius)
            if (valid) {
                aimVector = aim
                hasValidAim = true
            }
        }

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetBalls = targetBalls,
            aimDirection = aimVector,
            hasValidAim = hasValidAim,
            frameWidth = width,
            frameHeight = height
        )
    }

    /**
     * Finds the exact inner cushion rail boundaries of the cyan pool table felt.
     */
    private fun detectTableBounds(pixels: IntArray, width: Int, height: Int): TableBounds {
        val midY = (height * 0.58f).toInt().coerceIn(0, height - 1)
        val midX = (width * 0.50f).toInt().coerceIn(0, width - 1)

        // Scan horizontally at midY
        var xMin = -1
        var xMax = -1
        for (x in 0 until width) {
            val color = pixels[midY * width + x]
            if (isFeltColor(color)) {
                if (xMin == -1) xMin = x
                xMax = x
            }
        }

        // Scan vertically at midX
        var yMin = -1
        var yMax = -1
        for (y in 0 until height) {
            val color = pixels[y * width + midX]
            if (isFeltColor(color)) {
                if (yMin == -1) yMin = y
                yMax = y
            }
        }

        // Validate scanned coordinates
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

        // Calibrated 8-Ball Pool table felt boundaries on standard mobile landscape screens
        return TableBounds(
            xMin = width * 0.1270f,
            yMin = height * 0.2922f,
            xMax = width * 0.8721f,
            yMax = height * 0.8703f
        )
    }

    /**
     * Detects the white cue ball strictly inside the table boundaries.
     */
    private fun detectCueBall(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): BallData? {
        val startX = max(0, (table.xMin + ballRadius).toInt())
        val endX = min(width - 1, (table.xMax - ballRadius).toInt())
        val startY = max(0, (table.yMin + ballRadius).toInt())
        val endY = min(height - 1, (table.yMax - ballRadius).toInt())

        var sumX = 0f
        var sumY = 0f
        var whiteCount = 0

        val step = 2
        for (y in startY until endY step step) {
            val rowOffset = y * width
            for (x in startX until endX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // White cue ball pixels: high brightness and balanced RGB
                if (r > 205 && g > 205 && b > 205 && abs(r - g) < 25 && abs(g - b) < 25) {
                    sumX += x
                    sumY += y
                    whiteCount++
                }
            }
        }

        val expectedPixels = (Math.PI * ballRadius * ballRadius) / (step * step)
        if (whiteCount >= expectedPixels * 0.10f) {
            val centerX = sumX / whiteCount
            val centerY = sumY / whiteCount
            return BallData(
                center = Vector2D(centerX, centerY),
                radius = ballRadius,
                type = BallType.CUE,
                confidence = 0.95f
            )
        }

        return null
    }

    /**
     * Detects object balls on the table felt.
     */
    private fun detectObjectBalls(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        cueBall: BallData?,
        ballRadius: Float
    ): List<BallData> {
        val balls = ArrayList<BallData>()
        val startX = max(0, (table.xMin + ballRadius).toInt())
        val endX = min(width - 1, (table.xMax - ballRadius).toInt())
        val startY = max(0, (table.yMin + ballRadius).toInt())
        val endY = min(height - 1, (table.yMax - ballRadius).toInt())

        val gridStep = (ballRadius * 0.9f).toInt().coerceAtLeast(3)
        val cueCenter = cueBall?.center
        val minCueDistSq = (ballRadius * 2.0f) * (ballRadius * 2.0f)

        for (y in startY until endY step gridStep) {
            val rowOffset = y * width
            for (x in startX until endX step gridStep) {
                val color = pixels[rowOffset + x]
                if (!isFeltColor(color)) {
                    val pos = Vector2D(x.toFloat(), y.toFloat())
                    if (cueCenter != null && pos.distanceSqTo(cueCenter) < minCueDistSq) {
                        continue
                    }

                    var isDuplicate = false
                    for (existing in balls) {
                        if (pos.distanceSqTo(existing.center) < (ballRadius * 1.5f) * (ballRadius * 1.5f)) {
                            isDuplicate = true
                            break
                        }
                    }

                    if (!isDuplicate) {
                        balls.add(BallData(center = pos, radius = ballRadius, type = BallType.OBJECT_SOLID))
                    }
                }
            }
        }

        return balls
    }

    /**
     * Detects active aiming line extending from the cue ball.
     */
    private fun detectActiveAim(
        pixels: IntArray,
        width: Int,
        height: Int,
        cueCenter: Vector2D,
        ballRadius: Float
    ): Pair<Vector2D, Boolean> {
        val sampleRadius1 = ballRadius * 1.8f
        val sampleRadius2 = ballRadius * 3.5f
        val sampleRadius3 = ballRadius * 5.5f
        val numSamples = 120
        var maxAimScore = 0.0f
        var bestAimAngle = 0.0f

        for (i in 0 until numSamples) {
            val angle = (2.0 * Math.PI * i / numSamples).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            val x1 = (cueCenter.x + cosA * sampleRadius1).toInt()
            val y1 = (cueCenter.y + sinA * sampleRadius1).toInt()
            val x2 = (cueCenter.x + cosA * sampleRadius2).toInt()
            val y2 = (cueCenter.y + sinA * sampleRadius2).toInt()
            val x3 = (cueCenter.x + cosA * sampleRadius3).toInt()
            val y3 = (cueCenter.y + sinA * sampleRadius3).toInt()

            if (x1 in 0 until width && y1 in 0 until height &&
                x2 in 0 until width && y2 in 0 until height) {

                val c1 = pixels[y1 * width + x1]
                val c2 = pixels[y2 * width + x2]
                val c3 = if (x3 in 0 until width && y3 in 0 until height) pixels[y3 * width + x3] else 0

                // 1. Direct White Aim Guideline Check
                if (isWhitePixel(c1) && isWhitePixel(c2)) {
                    return Pair(Vector2D(cosA, sinA), true)
                }

                // 2. High contrast stick / ray detector
                val b1 = getPixelBrightness(c1)
                val b2 = getPixelBrightness(c2)
                val b3 = if (c3 != 0) getPixelBrightness(c3) else b2
                val avgB = (b1 + b2 + b3) / 3.0f

                if (avgB > 180f || avgB < 40f) {
                    val score = abs(avgB - 110f)
                    if (score > maxAimScore) {
                        maxAimScore = score
                        bestAimAngle = angle
                    }
                }
            }
        }

        if (maxAimScore > 50f) {
            return Pair(Vector2D(cos(bestAimAngle), sin(bestAimAngle)), true)
        }

        return Pair(Vector2D.ZERO, false)
    }

    private fun isWhitePixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 200 && g > 200 && b > 200
    }

    private fun getPixelBrightness(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 0.299f + g * 0.587f + b * 0.114f)
    }

    private fun isFeltColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        return when (feltPreset) {
            TableFeltPreset.CYAN_TOURNAMENT -> {
                b > 100 && g > 80 && b > (r * 1.18f) && g > (r * 1.05f)
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
                // Calibrated cyan/blue/green felt detector
                (b > 100 && g > 80 && b > (r * 1.18f) && g > (r * 1.05f)) ||
                (g > 60 && g > (r * 1.15f) && g > (b * 1.02f)) ||
                (b > 75 && b > (r * 1.15f) && b > (g * 0.85f))
            }
        }
    }
}
