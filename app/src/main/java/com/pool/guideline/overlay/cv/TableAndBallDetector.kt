package com.pool.guideline.overlay.cv

import android.graphics.Bitmap
import android.graphics.Color
import com.pool.guideline.overlay.physics.Vector2D
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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

    // Persistent scratch buffers to prevent GC allocations during frame processing
    private var pixelBuffer: IntArray = IntArray(0)

    // Cached state across frames for temporal stability
    private var cachedTableBounds = TableBounds.EMPTY
    private var tableDetectInterval = 0

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

        // Direct buffer extraction into persistent integer array (RGB extraction)
        buffer.rewind()
        var destIdx = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + (x * pixelStride)
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                pixelBuffer[destIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return processIntPixels(pixelBuffer, width, height)
    }

    /**
     * Processes standard Bitmap input (used in tests and fallback pipelines).
     */
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
        // Step 1: Detect / Refresh Table Bounds (every 30 frames or when invalid)
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 30 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        if (!table.isValid) {
            return DetectionResult(
                tableBounds = TableBounds.EMPTY,
                frameWidth = width,
                frameHeight = height
            )
        }

        val ballRadius = table.estimatedBallRadius

        // Step 2: Detect Cue Ball (bright white cluster with dark outline in felt area)
        val cueBall = detectCueBall(pixels, width, height, table, ballRadius)

        // Step 3: Detect Target / Object Balls
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        // Step 4: Detect Aim Vector (following white guide line or cue stick)
        var aimVector = Vector2D.ZERO
        var hasValidAim = false

        if (cueBall != null) {
            val aim = detectAimDirection(pixels, width, height, cueBall.center, ballRadius, table)
            if (aim.lengthSq() > 0.1f) {
                aimVector = aim.normalized()
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
     * Detects table felt boundaries by scanning for dominant pool felt color regions.
     */
    private fun detectTableBounds(pixels: IntArray, width: Int, height: Int): TableBounds {
        var minX = width
        var maxX = 0
        var minY = height
        var maxY = 0
        var feltPixelCount = 0

        // Subsample for fast detection (stride of 4)
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

        // Verify minimum aspect ratio and area for pool table (standard ~ 2:1 aspect ratio)
        if (feltPixelCount > (width * height / 35) && boundingW > width * 0.4f && boundingH > height * 0.3f) {
            // Inset slightly to isolate pure playable cloth (excluding pocket radius)
            val paddingX = (boundingW * 0.02f)
            val paddingY = (boundingH * 0.02f)
            return TableBounds(
                xMin = minX.toFloat() + paddingX,
                yMin = minY.toFloat() + paddingY,
                xMax = maxX.toFloat() - paddingX,
                yMax = maxY.toFloat() - paddingY
            )
        }

        // Default fallback to center table area (common 8-ball pool layout)
        val defaultMarginX = width * 0.12f
        val defaultMarginY = height * 0.22f
        return TableBounds(
            xMin = defaultMarginX,
            yMin = defaultMarginY,
            xMax = width - defaultMarginX,
            yMax = height - defaultMarginY
        )
    }

    /**
     * Detects the white cue ball inside the table boundaries.
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

                // White cue ball criteria: High luminance across all channels
                if (r > 205 && g > 205 && b > 205 && abs(r - g) < 25 && abs(g - b) < 25) {
                    sumX += x
                    sumY += y
                    whiteCount++
                }
            }
        }

        val expectedPixels = (Math.PI * ballRadius * ballRadius) / (step * step)
        if (whiteCount >= expectedPixels * 0.25f) {
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
     * Detects object balls on the table playing area.
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

        val gridStep = (ballRadius * 1.2f).toInt().coerceAtLeast(5)
        val cueCenter = cueBall?.center
        val minCueDistSq = (ballRadius * 2.2f) * (ballRadius * 2.2f)

        for (y in startY until endY step gridStep) {
            val rowOffset = y * width
            for (x in startX until endX step gridStep) {
                val color = pixels[rowOffset + x]
                // Non-felt pixel cluster inside the table
                if (!isFeltColor(color)) {
                    val pos = Vector2D(x.toFloat(), y.toFloat())
                    if (cueCenter != null && pos.distanceSqTo(cueCenter) < minCueDistSq) {
                        continue
                    }

                    // Check if already covered by an existing detected ball
                    var isDuplicate = false
                    for (existing in balls) {
                        if (pos.distanceSqTo(existing.center) < (ballRadius * 1.6f) * (ballRadius * 1.6f)) {
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
     * Detects the aiming direction vector from the cue ball along the game's white aim guideline or cue stick.
     */
    private fun detectAimDirection(
        pixels: IntArray,
        width: Int,
        height: Int,
        cueCenter: Vector2D,
        ballRadius: Float,
        table: TableBounds
    ): Vector2D {
        // Multi-ring radial sampling around cue ball to detect the bright aiming line
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

                val isWhiteLine1 = isWhitePixel(c1)
                val isWhiteLine2 = isWhitePixel(c2)

                // White aim guideline detected extending outward
                if (isWhiteLine1 && isWhiteLine2) {
                    return Vector2D(cosA, sinA)
                }

                // Contrast check for cue stick or dark-bordered line
                val b1 = getPixelBrightness(c1)
                val b2 = getPixelBrightness(c2)
                val avgBrightness = (b1 + b2) / 2.0f

                if (avgBrightness > 190f || avgBrightness < 35f) {
                    val contrast = abs(avgBrightness - 110f)
                    if (contrast > bestContrast) {
                        bestContrast = contrast
                        bestAngle = angle
                    }
                }
            }
        }

        if (bestContrast > 50f) {
            return Vector2D(cos(bestAngle), sin(bestAngle))
        }

        // Default aim pointing forward (+X) if no stick line active
        return Vector2D(1.0f, 0.0f)
    }

    private fun isWhitePixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 210 && g > 210 && b > 210
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
                // Cyan / Turquoise felt (Miniclip 8 Ball Pool default)
                b > 120 && g > 100 && b > (r * 1.5f) && g > (r * 1.2f)
            }
            TableFeltPreset.CLASSIC_GREEN -> {
                // Classic Green pool felt
                g > 65 && g > (r * 1.3f) && g > (b * 1.1f)
            }
            TableFeltPreset.ROYAL_BLUE -> {
                // Royal / Deep Blue pool felt
                b > 80 && b > (r * 1.3f) && b > (g * 0.9f)
            }
            TableFeltPreset.MIDNIGHT_RED -> {
                // Burgundy / Red pool felt
                r > 90 && r > (g * 1.4f) && r > (b * 1.4f)
            }
            TableFeltPreset.AUTO -> {
                // Robust multi-hue table felt auto-detection
                // 1. Cyan/Blue felt: B > 120 and G > 100 with B > 1.4*R
                (b > 110 && g > 90 && b > (r * 1.35f) && g > (r * 1.15f)) ||
                // 2. Green felt: G > 70 with G > 1.25*R and G > 1.05*B
                (g > 70 && g > (r * 1.25f) && g > (b * 1.05f)) ||
                // 3. Deep Blue felt
                (b > 85 && b > (r * 1.3f) && b > (g * 0.9f))
            }
        }
    }
}
