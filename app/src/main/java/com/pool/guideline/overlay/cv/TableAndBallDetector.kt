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
 * High-precision computer vision engine for 8-ball pool.
 * Detects the in-game target crosshair ring and reverses along the white guideline to find the cue ball and exact shot vector.
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
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 60 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val ballRadius = table.estimatedBallRadius

        // Step 2: Find the in-game Target Crosshair Ring (placed directly on the aimed ball)
        val targetRing = findTargetRing(pixels, width, height, table, ballRadius)

        var cueBall: BallData? = null
        var aimDirection = Vector2D.ZERO
        var hasValidAim = false

        if (targetRing != null) {
            // Step 3: Trace reverse white guideline ray from Target Ring to find the Cue Ball and shot vector
            val (cuePos, shotVector, valid) = traceAimLineFromTarget(pixels, width, height, targetRing, table, ballRadius)
            if (valid) {
                cueBall = BallData(center = cuePos, radius = ballRadius, type = BallType.CUE)
                aimDirection = shotVector
                hasValidAim = true
            }
        }

        // Step 4: Detect all Object Balls on the table felt
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetBalls = targetBalls,
            aimDirection = aimDirection,
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

    /**
     * Finds the in-game target crosshair ring (hollow circle of radius ~10px on the aimed ball).
     */
    private fun findTargetRing(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): Vector2D? {
        val startX = max(0, (table.xMin + ballRadius).toInt())
        val endX = min(width - 1, (table.xMax - ballRadius).toInt())
        val startY = max(0, (table.yMin + ballRadius).toInt())
        val endY = min(height - 1, (table.yMax - ballRadius).toInt())

        var bestRing: Vector2D? = null
        var maxRingScore = 0

        val step = 3
        val sampleAngles = 18
        val ringRadius = ballRadius * 1.0f

        for (y in startY until endY step step) {
            val rowOffset = y * width
            for (x in startX until endX step step) {
                val centerColor = pixels[rowOffset + x]
                val cr = (centerColor shr 16) and 0xFF
                val cg = (centerColor shr 8) and 0xFF
                val cb = centerColor and 0xFF

                // Ring center is an object ball (non-white center)
                if (cr > 225 && cg > 225 && cb > 225) continue

                var whitePerimeter = 0
                for (i in 0 until sampleAngles) {
                    val rad = (2.0 * Math.PI * i / sampleAngles).toFloat()
                    val px = (x + cos(rad) * ringRadius).toInt()
                    val py = (y + sin(rad) * ringRadius).toInt()
                    if (px in 0 until width && py in 0 until height) {
                        val color = pixels[py * width + px]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        if (r > 190 && g > 190 && b > 190 && abs(r - g) < 30 && abs(g - b) < 30) {
                            whitePerimeter++
                        }
                    }
                }

                if (whitePerimeter >= 9 && whitePerimeter > maxRingScore) {
                    maxRingScore = whitePerimeter
                    bestRing = Vector2D(x.toFloat(), y.toFloat())
                }
            }
        }

        return bestRing
    }

    private data class AimResult(
        val cuePos: Vector2D,
        val shotVector: Vector2D,
        val isValid: Boolean
    )

    /**
     * Traces reverse white guideline from the Target Ring to locate the Cue Ball and forward shot vector.
     */
    private fun traceAimLineFromTarget(
        pixels: IntArray,
        width: Int,
        height: Int,
        targetRing: Vector2D,
        table: TableBounds,
        ballRadius: Float
    ): AimResult {
        val numAngles = 180
        var bestAngle = 0f
        var maxWhiteness = 0

        for (i in 0 until numAngles) {
            val angle = (2.0 * Math.PI * i / numAngles).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            var whiteCount = 0
            var d = ballRadius * 1.5f
            while (d < width * 0.5f) {
                val px = (targetRing.x + cosA * d).toInt()
                val py = (targetRing.y + sinA * d).toInt()
                if (px in 0 until width && py in 0 until height) {
                    val color = pixels[py * width + px]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    if (r > 185 && g > 185 && b > 185) {
                        whiteCount++
                    }
                }
                d += 6.0f
            }

            if (whiteCount > maxWhiteness) {
                maxWhiteness = whiteCount
                bestAngle = angle
            }
        }

        if (maxWhiteness < 5) {
            return AimResult(Vector2D.ZERO, Vector2D.ZERO, false)
        }

        // Ray from targetRing along bestAngle points towards Cue Ball.
        // Trace along bestAngle to find where the solid white Cue Ball is located
        val cosBest = cos(bestAngle)
        val sinBest = sin(bestAngle)
        var cueCenter: Vector2D? = null

        var d = ballRadius * 2.0f
        while (d < width * 0.65f) {
            val cx = (targetRing.x + cosBest * d).toInt()
            val cy = (targetRing.y + sinBest * d).toInt()
            if (cx in 0 until width && cy in 0 until height) {
                val color = pixels[cy * width + cx]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                // Check for solid white ball disc
                if (r > 210 && g > 210 && b > 210) {
                    var discWhite = true
                    for (off in intArrayOf(-4, 4)) {
                        val c1 = if (cx + off in 0 until width) pixels[cy * width + (cx + off)] else 0
                        val c2 = if (cy + off in 0 until height) pixels[(cy + off) * width + cx] else 0
                        val r1 = (c1 shr 16) and 0xFF
                        val r2 = (c2 shr 16) and 0xFF
                        if (r1 < 180 || r2 < 180) {
                            discWhite = false
                            break
                        }
                    }
                    if (discWhite) {
                        cueCenter = Vector2D(cx.toFloat(), cy.toFloat())
                        break
                    }
                }
            }
            d += 4.0f
        }

        val cuePos = cueCenter ?: Vector2D(targetRing.x + cosBest * ballRadius * 8f, targetRing.y + sinBest * ballRadius * 8f)
        // Shot vector travels FROM Cue Ball TO Target Ring:
        val shotVector = (targetRing - cuePos).normalized()

        return AimResult(cuePos, shotVector, true)
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
        val startX = max(0, (table.xMin + ballRadius).toInt())
        val endX = min(width - 1, (table.xMax - ballRadius).toInt())
        val startY = max(0, (table.yMin + ballRadius).toInt())
        val endY = min(height - 1, (table.yMax - ballRadius).toInt())

        val gridStep = (ballRadius * 0.9f).toInt().coerceAtLeast(3)
        val cueCenter = cueBall?.center
        val minCueDistSq = (ballRadius * 1.8f) * (ballRadius * 1.8f)

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
                        if (pos.distanceSqTo(existing.center) < (ballRadius * 1.4f) * (ballRadius * 1.4f)) {
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
                (b > 95 && g > 80 && b > (r * 1.15f) && g > (r * 1.05f)) ||
                (g > 60 && g > (r * 1.15f) && g > (b * 1.02f)) ||
                (b > 75 && b > (r * 1.15f) && b > (g * 0.85f))
            }
        }
    }
}
