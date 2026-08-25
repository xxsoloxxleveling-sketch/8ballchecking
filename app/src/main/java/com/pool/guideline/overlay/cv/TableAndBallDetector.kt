package com.pool.guideline.overlay.cv

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
 * Identifies the collinear in-game aim guideline and cue stick axis for sub-pixel shot vector extraction.
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

        // Step 2: Extract all white / high-brightness clusters inside the felt area
        val clusters = findWhiteClusters(pixels, width, height, table, ballRadius)

        // Step 3: Find the collinear aim guideline / cue stick line
        val (cuePos, aimDir, isValid) = solveAimLine(pixels, width, height, clusters, table, ballRadius)

        // Step 4: Detect all Object Balls on the table felt
        val cueBall = if (cuePos != null) BallData(center = cuePos, radius = ballRadius, type = BallType.CUE) else null
        val targetBalls = detectObjectBalls(pixels, width, height, table, cueBall, ballRadius)

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetBalls = targetBalls,
            aimDirection = aimDir,
            hasValidAim = isValid,
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

    private fun findWhiteClusters(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableBounds,
        ballRadius: Float
    ): List<Vector2D> {
        val startX = max(0, (table.xMin + 5).toInt())
        val endX = min(width - 1, (table.xMax - 5).toInt())
        val startY = max(0, (table.yMin + 5).toInt())
        val endY = min(height - 1, (table.yMax - 5).toInt())

        val clusters = ArrayList<Vector2D>()
        val counts = ArrayList<Int>()

        val step = 2
        val clusterDistSq = (ballRadius * 1.2f) * (ballRadius * 1.2f)

        for (y in startY until endY step step) {
            val rowOffset = y * width
            for (x in startX until endX step step) {
                val color = pixels[rowOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                if (r > 195 && g > 195 && b > 195 && abs(r - g) < 30 && abs(g - b) < 30) {
                    var added = false
                    for (i in clusters.indices) {
                        val c = clusters[i]
                        val dSq = (x - c.x) * (x - c.x) + (y - c.y) * (y - c.y)
                        if (dSq < clusterDistSq) {
                            val cnt = counts[i]
                            clusters[i] = Vector2D((c.x * cnt + x) / (cnt + 1), (c.y * cnt + y) / (cnt + 1))
                            counts[i] = cnt + 1
                            added = true
                            break
                        }
                    }
                    if (!added) {
                        clusters.add(Vector2D(x.toFloat(), y.toFloat()))
                        counts.add(1)
                    }
                }
            }
        }

        val result = ArrayList<Vector2D>()
        for (i in clusters.indices) {
            if (counts[i] >= 6) {
                result.add(clusters[i])
            }
        }
        return result
    }

    private data class AimLineSolution(
        val cuePos: Vector2D?,
        val aimDir: Vector2D,
        val isValid: Boolean
    )

    private fun solveAimLine(
        pixels: IntArray,
        width: Int,
        height: Int,
        clusters: List<Vector2D>,
        table: TableBounds,
        ballRadius: Float
    ): AimLineSolution {
        if (clusters.size < 2) return AimLineSolution(null, Vector2D.ZERO, false)

        var bestLine: List<Vector2D>? = null
        var maxCollinear = 0

        for (i in clusters.indices) {
            for (j in (i + 1) until clusters.size) {
                val p1 = clusters[i]
                val p2 = clusters[j]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < 20f || dist > width * 0.7f) continue

                val ux = dx / dist
                val uy = dy / dist

                val collinear = ArrayList<Vector2D>()
                collinear.add(p1)
                collinear.add(p2)

                for (k in clusters.indices) {
                    if (k == i || k == j) continue
                    val pk = clusters[k]
                    val perpDist = abs((pk.x - p1.x) * uy - (pk.y - p1.y) * ux)
                    if (perpDist < 5.5f) {
                        collinear.add(pk)
                    }
                }

                if (collinear.size > maxCollinear) {
                    maxCollinear = collinear.size
                    bestLine = collinear
                }
            }
        }

        if (bestLine == null || maxCollinear < 3) {
            return AimLineSolution(clusters.firstOrNull(), Vector2D.ZERO, false)
        }

        // Sort collinear dots along the axis
        val p0 = bestLine[0]
        val pLast = bestLine[bestLine.size - 1]
        val axis = (pLast - p0).normalized()

        val sorted = bestLine.sortedBy { (it.x - p0.x) * axis.x + (it.y - p0.y) * axis.y }
        val endA = sorted.first()
        val endB = sorted.last()

        // Distinguish cue stick end vs target ball end by checking texture along the ray
        val checkDist = (ballRadius * 2.5f).toInt()
        val aX = (endA.x - axis.x * checkDist).toInt().coerceIn(0, width - 1)
        val aY = (endA.y - axis.y * checkDist).toInt().coerceIn(0, height - 1)
        val bX = (endB.x + axis.x * checkDist).toInt().coerceIn(0, width - 1)
        val bY = (endB.y + axis.y * checkDist).toInt().coerceIn(0, height - 1)

        val colorA = pixels[aY * width + aX]
        val colorB = pixels[bY * width + bX]

        val rA = (colorA shr 16) and 0xFF
        val bA = colorA and 0xFF
        val rB = (colorB shr 16) and 0xFF
        val bB = colorB and 0xFF

        // Wood / stick texture has strong red-over-blue contrast compared to cyan felt
        val stickAtA = rA > (bA * 1.25f) && rA > 100
        val stickAtB = rB > (bB * 1.25f) && rB > 100

        val (cuePos, shotDir) = if (stickAtA || (!stickAtB && (endA.y > endB.y))) {
            Pair(endA, axis)
        } else {
            Pair(endB, -axis)
        }

        return AimLineSolution(cuePos, shotDir, true)
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
