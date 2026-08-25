package com.pool.guideline.overlay.cv

import android.graphics.Color
import com.pool.guideline.overlay.physics.Vector2D
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
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
    val points: List<Vector2D>,
    val center: Vector2D,
    val radius: Float,
    val area: Float,
    val circularity: Float,
    val isAccepted: Boolean
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
 * Robust contour-based computer vision engine for 8-ball pool.
 * Implements:
 * 1. Live Felt HSV Color Calibration
 * 2. Morphological Cleanup (Open & Close)
 * 3. 4*PI*Area / Perimeter^2 Circularity Filtering
 * 4. High-brightness Cue Ball & Target Ball Association
 */
class TableAndBallDetector(
    var feltPreset: TableFeltPreset = TableFeltPreset.AUTO
) {

    // Calibration settings (HSV space in [0..360, 0..1, 0..1])
    var calibratedHue: Float = 195f
    var calibratedSat: Float = 0.65f
    var calibratedVal: Float = 0.75f

    var hueTolerance: Float = 25f
    var satTolerance: Float = 0.40f
    var valTolerance: Float = 0.40f

    private var pixelBuffer: IntArray = IntArray(0)
    private var binaryMask: BooleanArray = BooleanArray(0)
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
            binaryMask = BooleanArray(totalPixels)
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
        // Step 1: Detect / Cache Table Bounds
        if (!cachedTableBounds.isValid || (++tableDetectInterval % 45 == 0)) {
            cachedTableBounds = detectTableBounds(pixels, width, height)
        }

        val table = cachedTableBounds
        val expectedRadius = table.estimatedBallRadius
        val expectedArea = Math.PI.toFloat() * expectedRadius * expectedRadius

        // Step 2: Build Non-Felt Binary Mask inside table boundaries
        val tMinX = max(0, (table.xMin + expectedRadius * 0.5f).toInt())
        val tMaxX = min(width - 1, (table.xMax - expectedRadius * 0.5f).toInt())
        val tMinY = max(0, (table.yMin + expectedRadius * 0.5f).toInt())
        val tMaxY = min(height - 1, (table.yMax - expectedRadius * 0.5f).toInt())

        binaryMask.fill(false)
        for (y in tMinY..tMaxY) {
            val rowOffset = y * width
            for (x in tMinX..tMaxX) {
                val color = pixels[rowOffset + x]
                if (!isFeltColor(color)) {
                    binaryMask[rowOffset + x] = true
                }
            }
        }

        // Step 3: Morphological Cleanup (Remove 1px noise & connect ball segments)
        val cleanedMask = applyMorphology(binaryMask, width, height, tMinX, tMinY, tMaxX, tMaxY)

        // Step 4: Contour Extraction & Circularity Filtering
        val (acceptedBalls, rawContours) = extractBallContours(
            pixels,
            cleanedMask,
            width,
            height,
            tMinX,
            tMinY,
            tMaxX,
            tMaxY,
            expectedRadius,
            expectedArea
        )

        // Step 5: Identify Cue Ball (Highest brightness & lowest saturation)
        var cueBall: BallData? = null
        val targetBalls = ArrayList<BallData>()

        if (acceptedBalls.isNotEmpty()) {
            val sortedByBrightness = acceptedBalls.sortedByDescending { it.brightness }
            cueBall = sortedByBrightness.first().copy(type = BallType.CUE)
            for (i in 1 until sortedByBrightness.size) {
                targetBalls.add(sortedByBrightness[i].copy(type = BallType.OBJECT_SOLID))
            }
        }

        // Step 6: Detect in-game aim guideline / target ring
        var targetRing: Vector2D? = null
        var aimDir = Vector2D.ZERO
        var hasValidAim = false

        if (cueBall != null) {
            val (ring, dir, valid) = detectAimFromCue(pixels, width, height, cueBall.center, targetBalls, expectedRadius)
            targetRing = ring
            aimDir = dir
            hasValidAim = valid
        }

        return DetectionResult(
            tableBounds = table,
            cueBall = cueBall,
            targetRingPos = targetRing,
            targetBalls = targetBalls,
            rawContours = rawContours,
            aimDirection = aimDir,
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

    private fun applyMorphology(
        mask: BooleanArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): BooleanArray {
        val out = BooleanArray(mask.size)
        // 3x3 Morphological Open (Erosion followed by Dilation)
        for (y in (y0 + 1) until y1) {
            val rowOffset = y * width
            for (x in (x0 + 1) until x1) {
                val idx = rowOffset + x
                if (mask[idx]) {
                    // Check 4-neighborhood
                    val hasNeighbors = mask[idx - 1] && mask[idx + 1] && mask[idx - width] && mask[idx + width]
                    out[idx] = hasNeighbors
                }
            }
        }
        return out
    }

    private fun extractBallContours(
        pixels: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        expectedRadius: Float,
        expectedArea: Float
    ): Pair<List<BallData>, List<RawContourData>> {
        val acceptedBalls = ArrayList<BallData>()
        val rawContours = ArrayList<RawContourData>()
        val visited = BooleanArray(mask.size)

        val minArea = expectedArea * 0.40f
        val maxArea = expectedArea * 3.2f

        for (y in y0..y1 step 2) {
            val rowOffset = y * width
            for (x in x0..x1 step 2) {
                val startIdx = rowOffset + x
                if (mask[startIdx] && !visited[startIdx]) {
                    // BFS Flood fill connected component
                    var sumX = 0f
                    var sumY = 0f
                    var areaCount = 0
                    var perimeterCount = 0

                    val queueX = IntArray(1200)
                    val queueY = IntArray(1200)
                    var qHead = 0
                    var qTail = 0

                    queueX[qTail] = x
                    queueY[qTail] = y
                    qTail++
                    visited[startIdx] = true

                    val contourPoints = ArrayList<Vector2D>()

                    while (qHead < qTail && qTail < 1190) {
                        val cx = queueX[qHead]
                        val cy = queueY[qHead]
                        qHead++

                        sumX += cx
                        sumY += cy
                        areaCount++

                        var isBoundary = false
                        for (dy in -1..1) {
                            for (dx in -1..1) {
                                if (dx == 0 && dy == 0) continue
                                val nx = cx + dx
                                val ny = cy + dy
                                if (nx in x0..x1 && ny in y0..y1) {
                                    val nIdx = ny * width + nx
                                    if (mask[nIdx]) {
                                        if (!visited[nIdx] && qTail < 1190) {
                                            visited[nIdx] = true
                                            queueX[qTail] = nx
                                            queueY[qTail] = ny
                                            qTail++
                                        }
                                    } else {
                                        isBoundary = true
                                    }
                                } else {
                                    isBoundary = true
                                }
                            }
                        }

                        if (isBoundary) {
                            perimeterCount++
                            if (contourPoints.size < 60) {
                                contourPoints.add(Vector2D(cx.toFloat(), cy.toFloat()))
                            }
                        }
                    }

                    if (areaCount >= 8) {
                        val centerX = sumX / areaCount
                        val centerY = sumY / areaCount
                        val centerPos = Vector2D(centerX, centerY)

                        // Circularity = 4 * PI * Area / Perimeter^2
                        val P = max(1f, perimeterCount.toFloat())
                        val circularity = ((4.0 * Math.PI * areaCount) / (P * P)).toFloat().coerceIn(0f, 1.0f)

                        // Min enclosing circle radius
                        var maxR = 0f
                        for (i in 0 until qTail) {
                            val dx = queueX[i] - centerX
                            val dy = queueY[i] - centerY
                            val d = sqrt(dx * dx + dy * dy)
                            if (d > maxR) maxR = d
                        }

                        val isAcceptedArea = areaCount.toFloat() in minArea..maxArea
                        val isAcceptedRadius = maxR in (expectedRadius * 0.55f)..(expectedRadius * 1.7f)
                        val isAcceptedCircularity = circularity >= 0.70f

                        val isAccepted = isAcceptedArea && isAcceptedRadius && isAcceptedCircularity

                        rawContours.add(
                            RawContourData(
                                points = contourPoints,
                                center = centerPos,
                                radius = maxR,
                                area = areaCount.toFloat(),
                                circularity = circularity,
                                isAccepted = isAccepted
                            )
                        )

                        if (isAccepted) {
                            // Compute internal brightness and color
                            var bSum = 0f
                            var sampleCount = 0
                            for (i in 0 until qTail step 2) {
                                val px = queueX[i]
                                val py = queueY[i]
                                val col = pixels[py * width + px]
                                val r = (col shr 16) and 0xFF
                                val g = (col shr 8) and 0xFF
                                val b = col and 0xFF
                                bSum += (r * 0.299f + g * 0.587f + b * 0.114f)
                                sampleCount++
                            }
                            val avgBrightness = if (sampleCount > 0) bSum / sampleCount else 0f

                            acceptedBalls.add(
                                BallData(
                                    center = centerPos,
                                    radius = expectedRadius,
                                    type = BallType.OBJECT_SOLID,
                                    confidence = circularity,
                                    circularity = circularity,
                                    brightness = avgBrightness
                                )
                            )
                        }
                    }
                }
            }
        }

        return Pair(acceptedBalls, rawContours)
    }

    private data class CueAimResult(
        val targetRing: Vector2D?,
        val aimDir: Vector2D,
        val isValid: Boolean
    )

    private fun detectAimFromCue(
        pixels: IntArray,
        width: Int,
        height: Int,
        cueCenter: Vector2D,
        targetBalls: List<BallData>,
        ballRadius: Float
    ): CueAimResult {
        val numAngles = 120
        var bestAngle = 0f
        var maxAimScore = 0f
        var bestRing: Vector2D? = null

        for (i in 0 until numAngles) {
            val angle = (2.0 * Math.PI * i / numAngles).toFloat()
            val cosA = cos(angle)
            val sinA = sin(angle)

            // Trace forward for in-game white guideline
            var whiteCount = 0
            var d = ballRadius * 1.5f
            while (d < width * 0.55f) {
                val px = (cueCenter.x + cosA * d).toInt()
                val py = (cueCenter.y + sinA * d).toInt()
                if (px in 0 until width && py in 0 until height) {
                    val color = pixels[py * width + px]
                    val r = (color shr 16) and 0xFF
                    val g = (color shr 8) and 0xFF
                    val b = color and 0xFF
                    if (r > 185 && g > 185 && b > 185) {
                        whiteCount++
                    }
                }
                d += 5.0f
            }

            if (whiteCount > maxAimScore) {
                maxAimScore = whiteCount.toFloat()
                bestAngle = angle
            }
        }

        if (maxAimScore >= 4f) {
            val dir = Vector2D(cos(bestAngle), sin(bestAngle))
            // Find target ball along this direction
            for (b in targetBalls) {
                val toBall = b.center - cueCenter
                val proj = toBall.dot(dir)
                if (proj > 0f) {
                    val perpSq = toBall.lengthSq() - (proj * proj)
                    if (perpSq < (ballRadius * 1.5f) * (ballRadius * 1.5f)) {
                        val ringPos = cueCenter + (dir * (proj - ballRadius * 1.8f))
                        return CueAimResult(ringPos, dir, true)
                    }
                }
            }
            return CueAimResult(cueCenter + dir * 250f, dir, true)
        }

        return CueAimResult(null, Vector2D.ZERO, false)
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
