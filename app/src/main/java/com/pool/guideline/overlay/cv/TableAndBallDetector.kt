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
 * High-precision collinear guideline & ball detection engine for 8-ball clone.
 * Detects the continuous chain of white dots connecting Cue Stick, Cue Ball, and Target Ring.
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
        val clusterDistSq = (ballRadius * 1.1f) * (ballRadius * 1.1f)

        val step = 2
        for (y in tMinY..tMaxY step step) {
            val rowOffset = y * width
            for (x in tMinX..tMaxX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                if (r > 195 && g > 195 && b > 195 && abs(r - g) < 25 && abs(g - b) < 25) {
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

        // Step 2: Filter clusters with >= 6 points
        val validCenters = ArrayList<Vector2D>()
        for (i in clusters.indices) {
            if (clusterCounts[i] >= 6) {
                validCenters.add(clusters[i])
            }
        }

        if (validCenters.size < 2) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Step 3: Find the dominant collinear line of dots (The in-game aim guideline + cue axis)
        var bestCollinear: List<Vector2D>? = null
        var maxCollinearCount = 0

        for (i in validCenters.indices) {
            for (j in (i + 1) until validCenters.size) {
                val p1 = validCenters[i]
                val p2 = validCenters[j]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 25f || dist > width * 0.75f) continue

                val ux = dx / dist
                val uy = dy / dist

                val collinear = ArrayList<Vector2D>()
                collinear.add(p1)
                collinear.add(p2)

                for (k in validCenters.indices) {
                    if (k != i && k != j) {
                        val pk = validCenters[k]
                        val perpDist = abs((pk.x - p1.x) * uy - (pk.y - p1.y) * ux)
                        if (perpDist < 6.0f) {
                            collinear.add(pk)
                        }
                    }
                }

                if (collinear.size > maxCollinearCount) {
                    maxCollinearCount = collinear.size
                    bestCollinear = collinear
                }
            }
        }

        if (bestCollinear == null || maxCollinearCount < 3) {
            return DetectionResult(tableBounds = table, frameWidth = width, frameHeight = height)
        }

        // Step 4: Sort collinear dots along axis
        val p0 = bestCollinear[0]
        val pLast = bestCollinear[bestCollinear.size - 1]
        val lineVec = pLast - p0
        val axis = lineVec.normalized()

        val sorted = bestCollinear.sortedBy { (it.x - p0.x) * axis.x + (it.y - p0.y) * axis.y }
        val end1 = sorted.first()
        val end2 = sorted.last()

        // Check for cue stick texture behind end1 vs end2
        var stickScore1 = 0
        var stickScore2 = 0
        val checkDistances = intArrayOf(15, 30, 50, 75)

        for (d in checkDistances) {
            val p1x = (end1.x - axis.x * d).toInt().coerceIn(0, width - 1)
            val p1y = (end1.y - axis.y * d).toInt().coerceIn(0, height - 1)
            val col1 = pixels[p1y * width + p1x]
            val r1 = (col1 shr 16) and 0xFF
            val b1 = col1 and 0xFF
            if (r1 > 110 && r1 > b1 * 1.2f) stickScore1++

            val p2x = (end2.x + axis.x * d).toInt().coerceIn(0, width - 1)
            val p2y = (end2.y + axis.y * d).toInt().coerceIn(0, height - 1)
            val col2 = pixels[p2y * width + p2x]
            val r2 = (col2 shr 16) and 0xFF
            val b2 = col2 and 0xFF
            if (r2 > 110 && r2 > b2 * 1.2f) stickScore2++
        }

        val (cuePos, targetPos, shotDir) = if (stickScore1 >= stickScore2) {
            Triple(end1, end2, axis)
        } else {
            Triple(end2, end1, -axis)
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
