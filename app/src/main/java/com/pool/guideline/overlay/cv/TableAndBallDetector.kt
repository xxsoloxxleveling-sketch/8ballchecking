package com.pool.guideline.overlay.cv

import android.content.Context
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

data class AxisFitResult(
    val cueBallPos: Vector2D,
    val axisDir: Vector2D // Pure unit vector, unoriented
)

data class DetectionResult(
    val tableBounds: TableBounds = TableBounds.EMPTY,
    val isTableCalibrated: Boolean = true,
    val cueBall: BallData? = null,
    val targetRingPos: Vector2D? = null,
    val targetBalls: List<BallData> = emptyList(),
    val rawContours: List<RawContourData> = emptyList(),
    val aimDirection: Vector2D = Vector2D.ZERO,
    val debugBackwardDirection: Vector2D = Vector2D.ZERO,
    val hasValidAim: Boolean = false,
    val resolutionMethod: String = "",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0
)

/**
 * High-Precision Computer Vision Engine for Mock Pool.
 * Uses persistent calibration, unoriented collinear axis fitting, and boundary-based polarity resolution.
 */
class TableAndBallDetector(
    private val context: Context,
    var feltPreset: TableFeltPreset = TableFeltPreset.AUTO
) {

    var calibratedHue: Float = 195f
    var calibratedSat: Float = 0.65f
    var calibratedVal: Float = 0.75f

    var hueTolerance: Float = 25f
    var satTolerance: Float = 0.40f
    var valTolerance: Float = 0.40f

    private var pixelBuffer: IntArray = IntArray(0)

    fun calibrateFeltFromRgb(r: Int, g: Int, b: Int) {
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        calibratedHue = hsv[0]
        calibratedSat = hsv[1]
        calibratedVal = hsv[2]
        feltPreset = TableFeltPreset.CUSTOM_CALIBRATED
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

    fun processIntPixels(pixels: IntArray, width: Int, height: Int): DetectionResult {
        // Step 1: Check Explicit Table Calibration scaled to this frame resolution
        val table = TableBoundsCalibration.getTableBounds(context, width, height)
        if (table == null || !table.isValid) {
            return DetectionResult(
                tableBounds = TableBounds.EMPTY,
                isTableCalibrated = false,
                frameWidth = width,
                frameHeight = height
            )
        }

        val ballRadius = table.estimatedBallRadius

        val tMinX = max(0, (table.xMin + 2).toInt())
        val tMaxX = min(width - 1, (table.xMax - 2).toInt())
        val tMinY = max(0, (table.yMin + 2).toInt())
        val tMaxY = min(height - 1, (table.yMax - 2).toInt())

        // Step 2: Collect bright white guideline and ball pixels
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
            return DetectionResult(tableBounds = table, isTableCalibrated = true, frameWidth = width, frameHeight = height)
        }

        // Step 3: Locate Cue Ball Candidate (Solid White Disc)
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
            return DetectionResult(tableBounds = table, isTableCalibrated = true, frameWidth = width, frameHeight = height)
        }

        // Step 4: Fit Unoriented Collinear Axis for Each Cue Candidate
        var bestFit: AxisFitResult? = null
        var bestFitInliers: List<Vector2D> = emptyList()
        var maxInliersCount = 0

        for (cue in cueCandidates) {
            val fit = fitCollinearAxis(clusters, cue, width * 0.75f, ballRadius)
            if (fit != null) {
                // Collect inliers for this fit
                val inliers = clusters.filter {
                    val perpDist = abs((it.x - cue.x) * fit.axisDir.y - (it.y - cue.y) * fit.axisDir.x)
                    perpDist < 6.0f
                }
                if (inliers.size > maxInliersCount) {
                    maxInliersCount = inliers.size
                    bestFit = fit
                    bestFitInliers = inliers
                }
            }
        }

        if (bestFit == null || maxInliersCount < 3) {
            return DetectionResult(tableBounds = table, isTableCalibrated = true, frameWidth = width, frameHeight = height)
        }

        // Step 5: Resolve True Forward Aim Polarity
        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = bestFit.cueBallPos,
            axisDir = bestFit.axisDir,
            tableBounds = table,
            pixels = pixels,
            width = width,
            height = height
        ) ?: return DetectionResult(tableBounds = table, isTableCalibrated = true, frameWidth = width, frameHeight = height)

        // Step 6: Find Resolved Target Endpoint along Forward Ray
        val forwardInliers = bestFitInliers.filter {
            val proj = (it.x - bestFit.cueBallPos.x) * resolved.forwardDir.x + (it.y - bestFit.cueBallPos.y) * resolved.forwardDir.y
            proj > ballRadius * 0.8f
        }.sortedBy { (it.x - bestFit.cueBallPos.x) * resolved.forwardDir.x + (it.y - bestFit.cueBallPos.y) * resolved.forwardDir.y }

        val targetPos = if (forwardInliers.isNotEmpty()) {
            forwardInliers.last()
        } else {
            bestFit.cueBallPos + (resolved.forwardDir * (ballRadius * 12f))
        }

        // Step 7: Validate Target Endpoint (Strictly within table boundaries)
        val validTarget = isValidTarget(targetPos, table, ballRadius)
        if (!validTarget) {
            return DetectionResult(tableBounds = table, isTableCalibrated = true, frameWidth = width, frameHeight = height)
        }

        val cueBall = BallData(center = bestFit.cueBallPos, radius = ballRadius, type = BallType.CUE)
        val targetBalls = listOf(BallData(center = targetPos, radius = ballRadius, type = BallType.OBJECT_SOLID))

        return DetectionResult(
            tableBounds = table,
            isTableCalibrated = true,
            cueBall = cueBall,
            targetRingPos = targetPos,
            targetBalls = targetBalls,
            rawContours = emptyList(),
            aimDirection = resolved.forwardDir,
            debugBackwardDirection = resolved.backwardDir,
            hasValidAim = true,
            resolutionMethod = resolved.resolutionMethod,
            frameWidth = width,
            frameHeight = height
        )
    }

    /**
     * Pure unoriented collinear line-fitting function.
     * Returns ONLY the physical axis line (unit vector), with NO direction decision.
     */
    fun fitCollinearAxis(
        clusters: List<Vector2D>,
        cueBallPos: Vector2D,
        maxDist: Float,
        ballRadius: Float
    ): AxisFitResult? {
        var bestDir = Vector2D.ZERO
        var maxInliers = 0

        for (p in clusters) {
            val dx = p.x - cueBallPos.x
            val dy = p.y - cueBallPos.y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < ballRadius * 1.5f || dist > maxDist) continue

            val ux = dx / dist
            val uy = dy / dist

            var inliers = 0
            for (other in clusters) {
                val perpDist = abs((other.x - cueBallPos.x) * uy - (other.y - cueBallPos.y) * ux)
                if (perpDist < 6.0f) {
                    inliers++
                }
            }

            if (inliers > maxInliers) {
                maxInliers = inliers
                bestDir = Vector2D(ux, uy)
            }
        }

        return if (maxInliers >= 3) {
            AxisFitResult(cueBallPos = cueBallPos, axisDir = bestDir.normalized())
        } else {
            null
        }
    }

    /**
     * Validates that the RESOLVED TARGET ENDPOINT resides strictly inside table bounds.
     * NOTE: This explicitly validates the target point, NOT the cue ball position.
     */
    fun isValidTarget(target: Vector2D, tableBounds: TableBounds, ballRadius: Float): Boolean {
        if (!tableBounds.isValid) return false
        val left = tableBounds.xMin + ballRadius * 0.5f
        val right = tableBounds.xMax - ballRadius * 0.5f
        val top = tableBounds.yMin + ballRadius * 0.5f
        val bottom = tableBounds.yMax - ballRadius * 0.5f
        return target.x in left..right && target.y in top..bottom
    }
}
