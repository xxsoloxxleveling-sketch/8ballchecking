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
 * Computer vision engine calibrated for 8-ball pool gameplay.
 * Identifies the cue ball, cue stick, in-game aim guideline, and object balls.
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

        // Step 2: Detect all white ball clusters inside the table
        val whiteClusters = findWhiteBallClusters(pixels, width, height, table, ballRadius)

        // Step 3: Find the true Cue Ball and Aim Vector by checking for cue stick or aim line
        var cueBall: BallData? = null
        var aimDirection = Vector2D.ZERO
        var hasValidAim = false
        var maxAimScore = 0f

        for (cluster in whiteClusters) {
            val (aimDir, score) = detectAimFromBall(pixels, width, height, cluster, ballRadius, table)
            if (score > maxAimScore) {
                maxAimScore = score
                cueBall = BallData(center = cluster, radius = ballRadius, type = BallType.CUE)
                aimDirection = aimDir
                hasValidAim = score > 35f
            }
        }

        // If no stick was detected on white clusters, pick largest cluster as cue ball
        if (cueBall == null && whiteClusters.isNotEmpty()) {
            cueBall = BallData(center = whiteClusters[0], radius = ballRadius, type = BallType.CUE)
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

    /**
     * Calibrates the exact inner cushion boundaries of the pool table felt.
     */
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
     * Finds all white circular candidate clusters on the table.
     */
    private fun findWhiteBallClusters(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): List<Vector2D> {
        val startX = max(0, (table.xMin + ballRadius).toInt())
        val endX = min(width - 1, (table.xMax - ballRadius).toInt())
        val startY = max(0, (table.yMin + ballRadius).toInt())
        val endY = min(height - 1, (table.yMax - ballRadius).toInt())

        val clusters = ArrayList<Vector2D>()
        val clusterPoints = ArrayList<Int>()

        val step = 2
        val clusterDistSq = (ballRadius * 1.3f) * (ballRadius * 1.3f)

        for (y in startY until endY step step) {
            val rowOffset = y * width
            for (x in startX until endX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                if (r > 200 && g > 200 && b > 200 && abs(r - g) < 30 && abs(g - b) < 30) {
                    var added = false
                    for (i in clusters.indices) {
                        val c = clusters[i]
                        val dSq = (x - c.x) * (x - c.x) + (y - c.y) * (y - c.y)
                        if (dSq < clusterDistSq) {
                            val count = clusterPoints[i]
                            val newX = (c.x * count + x) / (count + 1)
                            val newY = (c.y * count + y) / (count + 1)
                            clusters[i] = Vector2D(newX, newY)
                            clusterPoints[i] = count + 1
                            added = true
                            break
                        }
                    }
                    if (!added) {
                        clusters.add(Vector2D(x.toFloat(), y.toFloat()))
                        clusterPoints.add(1)
                    }
                }
            }
        }

        val minPixels = (Math.PI * ballRadius * ballRadius) / (step * step) * 0.10f
        val validClusters = ArrayList<Vector2D>()
        for (i in clusters.indices) {
            if (clusterPoints[i] >= minPixels) {
                validClusters.add(clusters[i])
            }
        }

        return validClusters
    }

    /**
     * Scans radial rays from a ball to detect the cue stick and in-game aiming guideline.
     */
    private fun detectAimFromBall(
        pixels: IntArray,
        width: Int,
        height: Int,
        ballPos: Vector2D,
        ballRadius: Float,
        table: TableBounds
    ): Pair<Vector2D, Float> {
        val numSamples = 90
        var bestScore = 0f
        var bestShotVector = Vector2D.ZERO

        for (i in 0 until numSamples) {
            val angle = (2.0 * Math.PI * i / numSamples).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            // Method A: Check for in-game White Guideline forward along (cosA, sinA)
            val f1X = (ballPos.x + cosA * ballRadius * 2.0f).toInt()
            val f1Y = (ballPos.y + sinA * ballRadius * 2.0f).toInt()
            val f2X = (ballPos.x + cosA * ballRadius * 4.0f).toInt()
            val f2Y = (ballPos.y + sinA * ballRadius * 4.0f).toInt()

            if (f1X in 0 until width && f1Y in 0 until height &&
                f2X in 0 until width && f2Y in 0 until height) {

                val c1 = pixels[f1Y * width + f1X]
                val c2 = pixels[f2Y * width + f2X]

                if (isWhitePixel(c1) && isWhitePixel(c2)) {
                    return Pair(Vector2D(cosA, sinA), 100f)
                }
            }

            // Method B: Check for Cue Stick behind the ball along (cosA, sinA)
            // Stick extends backward for multiple radii
            var stickContrastSum = 0f
            for (mult in floatArrayOf(1.8f, 2.8f, 4.2f, 6.0f)) {
                val sx = (ballPos.x + cosA * ballRadius * mult).toInt()
                val sy = (ballPos.y + sinA * ballRadius * mult).toInt()
                if (sx in 0 until width && sy in 0 until height) {
                    val color = pixels[sy * width + sx]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF

                    // Contrast distance from standard cyan felt (r=65, g=145, b=195)
                    val feltDelta = ((r - 65) * (r - 65) + (g - 145) * (g - 145) + (b - 195) * (b - 195)).toFloat()
                    if (feltDelta > 1200f) {
                        stickContrastSum += 25f
                    }
                }
            }

            if (stickContrastSum > bestScore) {
                bestScore = stickContrastSum
                // Shot travels OPPOSITE to cue stick!
                bestShotVector = Vector2D(-cosA, -sinA)
            }
        }

        return Pair(bestShotVector, bestScore)
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

    private fun isWhitePixel(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r > 200 && g > 200 && b > 200
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
