package com.pool.guideline.overlay.cv

import android.graphics.Color
import com.pool.guideline.overlay.physics.Vector2D
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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
 * High-Precision Cue-Anchored Aiming Engine for Mock Pool.
 * Features a dual-ended Wood-Texture Polarity Discriminator to ensure the aim vector
 * always shoots forward into the table felt and never backward along the cue stick.
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

        // Step 1: Collect bright white pixels
        val clusters = ArrayList<Vector2D>()
        val clusterCounts = ArrayList<Int>()
        val clusterDistSq = (ballRadius * 1.0f) * (ballRadius * 1.0f)

        val step = 2
        for (y in tMinY..tMaxY step step) {
            val rowOffset = y * width
            for (x in tMinX..tMaxX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                if (r > 208 && g > 208 && b > 208 && abs(r - g) < 25 && abs(g - b) < 25) {
                    var added = false
                    for (i in clusters.indices) {
                        val c = clusters[i]
                        val dSq = (x - c.x) * (x - c.x) + (y - c.y) * (y - c.y)
                        if (dSq < clusterDistSq) {
                            val cnt = clusterCounts[i]
                            clusters[i] = Vector2D((c.x * cnt + x) / (cnt + 1), (c.y * cnt + y) / (cnt + 1))
                            clusterCounts[i] = cnt + 1
                            added = true
                            break
                        }
                    }
                    if (!added) {
                        clusters.add(Vector2D(x.toFloat(), y.toFloat()))
                        clusterCounts.add(1)
                    }
                }
            }
        }

        if (clusters.size < 2) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Step 2: Identify Cue Ball Candidates (Solid White 2D Disc)
        val cueCandidates = ArrayList<Vector2D>()
        val radInt = (ballRadius * 0.65f).toInt().coerceAtLeast(3)

        for (c in clusters) {
            val cx = c.x.toInt().coerceIn(0, width - 1)
            val cy = c.y.toInt().coerceIn(0, height - 1)

            var whiteHits = 0
            var totalSamples = 0
            for (dy in -radInt..radInt step 2) {
                for (dx in -radInt..radInt step 2) {
                    if (dx * dx + dy * dy <= radInt * radInt) {
                        totalSamples++
                        val px = (cx + dx).coerceIn(0, width - 1)
                        val py = (cy + dy).coerceIn(0, height - 1)
                        val color = pixels[py * width + px]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        if (r > 195 && g > 195 && b > 195) {
                            whiteHits++
                        }
                    }
                }
            }

            if (totalSamples > 0 && (whiteHits.toFloat() / totalSamples.toFloat()) >= 0.40f) {
                cueCandidates.add(c)
            }
        }

        if (cueCandidates.isEmpty()) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Step 3: Find the Collinear Aiming Axis Passing through the Cue Ball
        var bestCue: Vector2D? = null
        var bestTarget: Vector2D? = null
        var bestShotDir = Vector2D.ZERO
        var maxInliers = 0

        for (cue in cueCandidates) {
            for (p in clusters) {
                val dx = p.x - cue.x
                val dy = p.y - cue.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < ballRadius * 1.5f || dist > width * 0.75f) continue

                val ux = dx / dist
                val uy = dy / dist

                val inliers = ArrayList<Vector2D>()
                inliers.add(cue)
                inliers.add(p)

                for (other in clusters) {
                    if (other !== cue && other !== p) {
                        val perpDist = abs((other.x - cue.x) * uy - (other.y - cue.y) * ux)
                        if (perpDist < 6.0f) {
                            inliers.add(other)
                        }
                    }
                }

                if (inliers.size > maxInliers) {
                    maxInliers = inliers.size
                    bestCue = cue

                    // Step 4: Wood-Texture Polarity Discriminator
                    // Check texture behind -u vs ahead of +u to establish true forward shot vector
                    var stickScoreForward = 0
                    var stickScoreBackward = 0
                    val sampleDists = intArrayOf(20, 45, 75, 110, 150)

                    for (d in sampleDists) {
                        val fwdX = (cue.x + ux * d).toInt().coerceIn(0, width - 1)
                        val fwdY = (cue.y + uy * d).toInt().coerceIn(0, height - 1)
                        val colFwd = pixels[fwdY * width + fwdX]
                        val rFwd = (colFwd shr 16) and 0xFF
                        val bFwd = colFwd and 0xFF
                        if (rFwd > 110 && rFwd > bFwd * 1.25f) stickScoreForward++

                        val bwdX = (cue.x - ux * d).toInt().coerceIn(0, width - 1)
                        val bwdY = (cue.y - uy * d).toInt().coerceIn(0, height - 1)
                        val colBwd = pixels[bwdY * width + bwdX]
                        val rBwd = (colBwd shr 16) and 0xFF
                        val bBwd = colBwd and 0xFF
                        if (rBwd > 110 && rBwd > bBwd * 1.25f) stickScoreBackward++
                    }

                    // True shot vector points AWAY from the stick
                    val trueDir = if (stickScoreForward > stickScoreBackward) {
                        Vector2D(-ux, -uy)
                    } else {
                        Vector2D(ux, uy)
                    }

                    // Sort inliers along trueDir (forward direction)
                    val forwardInliers = inliers.filter {
                        val proj = (it.x - cue.x) * trueDir.x + (it.y - cue.y) * trueDir.y
                        proj > ballRadius * 0.8f
                    }.sortedBy { (it.x - cue.x) * trueDir.x + (it.y - cue.y) * trueDir.y }

                    bestTarget = if (forwardInliers.isNotEmpty()) {
                        forwardInliers.last()
                    } else {
                        cue + (trueDir * (ballRadius * 12f))
                    }
                    bestShotDir = trueDir
                }
            }
        }

        if (bestCue == null || bestTarget == null || maxInliers < 3) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Clamp target inside table boundaries
        val clampedTargetX = bestTarget.x.coerceIn(table.xMin + ballRadius, table.xMax - ballRadius)
        val clampedTargetY = bestTarget.y.coerceIn(table.yMin + ballRadius, table.yMax - ballRadius)
        val finalTarget = Vector2D(clampedTargetX, clampedTargetY)

        val cueBall = BallData(center = bestCue, radius = ballRadius, type = BallType.CUE)
        val targetBalls = listOf(BallData(center = finalTarget, radius = ballRadius, type = BallType.OBJECT_SOLID))

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetRingPos = finalTarget,
            targetBalls = targetBalls,
            rawContours = emptyList(),
            aimDirection = bestShotDir,
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
