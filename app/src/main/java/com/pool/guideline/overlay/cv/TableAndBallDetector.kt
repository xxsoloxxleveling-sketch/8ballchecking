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
    CYAN_TOURNAMENT,   // Miniclip 8-Ball Pool default cyan/blue felt
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
 * High-performance, zero-allocation computer vision engine for table and ball detection.
 * Calibrated specifically for mobile 8-Ball Pool games.
 */
class TableAndBallDetector(
    var feltPreset: TableFeltPreset = TableFeltPreset.AUTO
) {

    private var pixelBuffer: IntArray = IntArray(0)
    private var cachedTableBounds = TableBounds.EMPTY
    private var tableDetectInterval = 0
    private var lastValidAimDirection = Vector2D(1.0f, 0.0f)

    /**
     * Processes a direct RGBA_8888 ByteBuffer from ImageReader with zero intermediate byte array allocations.
     */
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

    fun processBitmap(bitmap: Bitmap): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        if (pixelBuffer.size != totalPixels) {
            pixelBuffer = IntArray(totalPixels)
        }

        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)
        return processIntPixels(pixelBuffer, width, height)
    }

    private fun processIntPixels(pixels: IntArray, width: Int, height: Int): DetectionResult {
        // Step 1: Detect Table Bounds
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 20 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        // Step 2: Detect Cue Ball (white sphere)
        val cueBall = detectCueBall(pixels, width, height, table, ballRadius)

        // Step 3: Detect Object Balls
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        // Step 4: Detect Aim Vector
        var aimVector = lastValidAimDirection
        var hasValidAim = false

        if (cueBall != null) {
            val aim = detectAimDirection(pixels, width, height, cueBall.center, ballRadius, table, targetBalls)
            if (aim.lengthSq() > 0.1f) {
                aimVector = aim.normalized()
                lastValidAimDirection = aimVector
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

        val boundingW = maxX - minX
        val boundingH = maxY - minY

        if (feltPixelCount > (width * height / 40) && boundingW > width * 0.35f && boundingH > height * 0.25f) {
            val paddingX = (boundingW * 0.015f)
            val paddingY = (boundingH * 0.015f)
            return TableBounds(
                xMin = minX.toFloat() + paddingX,
                yMin = minY.toFloat() + paddingY,
                xMax = maxX.toFloat() - paddingX,
                yMax = maxY.toFloat() - paddingY
            )
        }

        // Standard 8-ball pool centered table cloth layout
        val defaultMarginX = width * 0.11f
        val defaultMarginY = height * 0.21f
        return TableBounds(
            xMin = defaultMarginX,
            yMin = defaultMarginY,
            xMax = width - defaultMarginX,
            yMax = height - defaultMarginY
        )
    }

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

                // White cue ball pixels
                if (r > 195 && g > 195 && b > 195 && abs(r - g) < 30 && abs(g - b) < 30) {
                    sumX += x
                    sumY += y
                    whiteCount++
                }
            }
        }

        val expectedPixels = (Math.PI * ballRadius * ballRadius) / (step * step)
        if (whiteCount >= expectedPixels * 0.15f) {
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

        val gridStep = (ballRadius * 1.1f).toInt().coerceAtLeast(4)
        val cueCenter = cueBall?.center
        val minCueDistSq = (ballRadius * 2.1f) * (ballRadius * 2.1f)

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

    private fun detectAimDirection(
        pixels: IntArray,
        width: Int,
        height: Int,
        cueCenter: Vector2D,
        ballRadius: Float,
        table: TableBounds,
        targetBalls: List<BallData>
    ): Vector2D {
        val sampleRadius1 = ballRadius * 2.0f
        val sampleRadius2 = ballRadius * 3.5f
        val numSamples = 90
        var bestContrast = 0.0f
        var bestAngle = 0.0f

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

                // Bright aim guideline detected
                if (isWhitePixel(c1) && isWhitePixel(c2)) {
                    return Vector2D(cosA, sinA)
                }

                val b1 = getPixelBrightness(c1)
                val b2 = getPixelBrightness(c2)
                val avgBrightness = (b1 + b2) / 2.0f

                if (avgBrightness > 185f || avgBrightness < 45f) {
                    val contrast = abs(avgBrightness - 110f)
                    if (contrast > bestContrast) {
                        bestContrast = contrast
                        bestAngle = angle
                    }
                }
            }
        }

        if (bestContrast > 40f) {
            return Vector2D(cos(bestAngle), sin(bestAngle))
        }

        // Fallback: If balls exist, point towards closest ball
        if (targetBalls.isNotEmpty()) {
            var closestBall = targetBalls[0]
            var minDistSq = cueCenter.distanceSqTo(closestBall.center)
            for (b in targetBalls) {
                val d = cueCenter.distanceSqTo(b.center)
                if (d < minDistSq) {
                    minDistSq = d
                    closestBall = b
                }
            }
            return (closestBall.center - cueCenter).normalized()
        }

        return lastValidAimDirection
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
                b > 100 && g > 85 && b > (r * 1.3f) && g > (r * 1.1f)
            }
            TableFeltPreset.CLASSIC_GREEN -> {
                g > 65 && g > (r * 1.3f) && g > (b * 1.1f)
            }
            TableFeltPreset.ROYAL_BLUE -> {
                b > 80 && b > (r * 1.3f) && b > (g * 0.9f)
            }
            TableFeltPreset.MIDNIGHT_RED -> {
                r > 90 && r > (g * 1.4f) && r > (b * 1.4f)
            }
            TableFeltPreset.AUTO -> {
                (b > 95 && g > 80 && b > (r * 1.25f) && g > (r * 1.1f)) ||
                (g > 65 && g > (r * 1.2f) && g > (b * 1.05f)) ||
                (b > 80 && b > (r * 1.25f) && b > (g * 0.85f))
            }
        }
    }
}
