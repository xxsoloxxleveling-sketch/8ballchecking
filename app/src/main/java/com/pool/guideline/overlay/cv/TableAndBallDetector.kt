package com.pool.guideline.overlay.cv

import android.graphics.Bitmap
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
 * Specifically tuned for full-screen table bounds and precise aiming line detection.
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
        // Step 1: Detect / Refresh Table Bounds
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 30 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        // Step 2: Detect Cue Ball
        val cueBall = detectCueBall(pixels, width, height, table, ballRadius)

        // Step 3: Detect Object Balls
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        // Step 4: Detect Active Aiming Vector
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
     * Accurately determines the full rectangular table boundaries.
     */
    private fun detectTableBounds(pixels: IntArray, width: Int, height: Int): TableBounds {
        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var feltPixelCount = 0

        val step = 4
        for (y in 0 until height step step) {
            val rowOffset = y * width
            for (x in 0 until width step step) {
                val color = pixels[rowOffset + x]
                if (isFeltColor(color)) {
                    feltPixelCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        val detectedWidth = maxX - minX
        val detectedHeight = maxY - minY

        // Pool tables have a standard 2:1 aspect ratio across ~74% of the screen width
        if (detectedWidth > width * 0.65f && detectedHeight > height * 0.45f) {
            val paddingX = detectedWidth * 0.015f
            val paddingY = detectedHeight * 0.015f
            return TableBounds(
                xMin = minX.toFloat() + paddingX,
                yMin = minY.toFloat() + paddingY,
                xMax = maxX.toFloat() - paddingX,
                yMax = maxY.toFloat() - paddingY
            )
        }

        // Exact calibrated 8-Ball Pool felt boundaries on widescreen landscape mobile displays
        return TableBounds(
            xMin = width * 0.125f,
            yMin = height * 0.235f,
            xMax = width * 0.875f,
            yMax = height * 0.865f
        )
    }

    /**
     * Detects the white cue ball.
     */
    private fun detectCueBall(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): BallData? {
        var sumX = 0f
        var sumY = 0f
        var whiteCount = 0

        val startX = max(0, table.xMin.toInt())
        val endX = min(width - 1, table.xMax.toInt())
        val startY = max(0, table.yMin.toInt())
        val endY = min(height - 1, table.yMax.toInt())

        val step = 2
        for (y in startY until endY step step) {
            val rowOffset = y * width
            for (x in startX until endX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // White cue ball pixels (high brightness across RGB)
                if (r > 190 && g > 190 && b > 190 && abs(r - g) < 35 && abs(g - b) < 35) {
                    sumX += x
                    sumY += y
                    whiteCount++
                }
            }
        }

        val expectedPixels = (Math.PI * ballRadius * ballRadius) / (step * step)
        if (whiteCount >= expectedPixels * 0.12f) {
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
     * Detects object balls on the table.
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
        val startX = max(0, table.xMin.toInt() + ballRadius.toInt())
        val endX = min(width - 1, table.xMax.toInt() - ballRadius.toInt())
        val startY = max(0, table.yMin.toInt() + ballRadius.toInt())
        val endY = min(height - 1, table.yMax.toInt() - ballRadius.toInt())

        val gridStep = (ballRadius * 1.0f).toInt().coerceAtLeast(3)
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
     * Detects the active cue stick aiming line.
     * Only returns valid=true when the player is actively aiming!
     */
    private fun detectActiveAim(
        pixels: IntArray,
        width: Int,
        height: Int,
        cueCenter: Vector2D,
        ballRadius: Float
    ): Pair<Vector2D, Boolean> {
        val sampleRadius1 = ballRadius * 1.8f
        val sampleRadius2 = ballRadius * 3.2f
        val numSamples = 90
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

            if (x1 in 0 until width && y1 in 0 until height &&
                x2 in 0 until width && y2 in 0 until height) {

                val c1 = pixels[y1 * width + x1]
                val c2 = pixels[y2 * width + x2]

                // 1. Direct White Aim Guideline Check
                if (isWhitePixel(c1) && isWhitePixel(c2)) {
                    return Pair(Vector2D(cosA, sinA), true)
                }

                // 2. Cue Stick / High Contrast Line Check
                val b1 = getPixelBrightness(c1)
                val b2 = getPixelBrightness(c2)
                val avgB = (b1 + b2) / 2.0f

                // Stick contrast against felt
                if (avgB > 185f || avgB < 40f) {
                    val score = abs(avgB - 110f)
                    if (score > maxAimScore) {
                        maxAimScore = score
                        bestAimAngle = angle
                    }
                }
            }
        }

        // Strict threshold: Only activate when stick / aim line is clearly detected
        if (maxAimScore > 55f) {
            return Pair(Vector2D(cos(bestAimAngle), sin(bestAimAngle)), true)
        }

        return Pair(Vector2D.ZERO, false)
    }

    private fun isWhitePixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 205 && g > 205 && b > 205
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
                b > 85 && g > 75 && b > (r * 1.15f) && g > (r * 1.05f)
            }
            TableFeltPreset.CLASSIC_GREEN -> {
                g > 60 && g > (r * 1.2f) && g > (b * 1.05f)
            }
            TableFeltPreset.ROYAL_BLUE -> {
                b > 75 && b > (r * 1.2f) && b > (g * 0.85f)
            }
            TableFeltPreset.MIDNIGHT_RED -> {
                r > 80 && r > (g * 1.3f) && r > (b * 1.3f)
            }
            TableFeltPreset.AUTO -> {
                // Generous multi-hue felt classifier
                (b > 80 && g > 70 && b > (r * 1.12f) && g > (r * 1.02f)) ||
                (g > 60 && g > (r * 1.15f) && g > (b * 1.02f)) ||
                (b > 75 && b > (r * 1.15f) && b > (g * 0.85f))
            }
        }
    }
}
