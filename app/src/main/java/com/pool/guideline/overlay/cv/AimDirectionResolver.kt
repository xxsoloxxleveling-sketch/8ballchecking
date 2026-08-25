package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.Vector2D
import kotlin.math.max
import kotlin.math.min

data class ResolvedAim(
    val forwardDir: Vector2D,
    val backwardDir: Vector2D,
    val isFlipped: Boolean = false,
    val resolutionMethod: String = "boundary"
)

/**
 * Boundary-Based Polarity Resolver.
 * Resolves the true forward shooting direction from an unoriented collinear axis line.
 */
object AimDirectionResolver {

    /**
     * Resolves forward shooting direction.
     *
     * Logic, in priority order:
     * 1. Sample wood vs felt texture along candidate directions (R > 105 and R > B * 1.2 = wood stick; pick felt side).
     * 2. Project probe points along +axisDir and -axisDir at probe distance D.
     *    If exactly ONE probe lands inside tableBounds, return that direction.
     * 3. If still ambiguous, return NULL to safely skip frame rendering.
     */
    fun resolveForwardDirection(
        cueBallPos: Vector2D,
        axisDir: Vector2D,
        tableBounds: TableBounds,
        pixels: IntArray,
        width: Int,
        height: Int
    ): ResolvedAim? {
        val u = axisDir.normalized()
        if (u.lengthSq() < 1e-4f) return null

        val ballRadius = tableBounds.estimatedBallRadius

        // Define playable boundary margins
        val innerMinX = tableBounds.xMin + ballRadius * 0.5f
        val innerMaxX = tableBounds.xMax - ballRadius * 0.5f
        val innerMinY = tableBounds.yMin + ballRadius * 0.5f
        val innerMaxY = tableBounds.yMax - ballRadius * 0.5f

        // ------------------------------------------------------------------------
        // Priority 1: Wood vs Felt Texture Sampling
        // ------------------------------------------------------------------------
        var woodScorePlus = 0
        var woodScoreMinus = 0
        val textureSampleDistances = intArrayOf(12, 25, 45, 70, 100, 135)

        for (d in textureSampleDistances) {
            // Sample along +u
            val plusX = (cueBallPos.x + u.x * d).toInt().coerceIn(0, width - 1)
            val plusY = (cueBallPos.y + u.y * d).toInt().coerceIn(0, height - 1)
            val colPlus = pixels[plusY * width + plusX]
            val rPlus = (colPlus shr 16) and 0xFF
            val gPlus = (colPlus shr 8) and 0xFF
            val bPlus = colPlus and 0xFF
            if (rPlus > 105 && rPlus > bPlus * 1.2f && (gPlus > bPlus || rPlus > gPlus * 1.15f)) {
                woodScorePlus++
            }

            // Sample along -u
            val minusX = (cueBallPos.x - u.x * d).toInt().coerceIn(0, width - 1)
            val minusY = (cueBallPos.y - u.y * d).toInt().coerceIn(0, height - 1)
            val colMinus = pixels[minusY * width + minusX]
            val rMinus = (colMinus shr 16) and 0xFF
            val gMinus = (colMinus shr 8) and 0xFF
            val bMinus = colMinus and 0xFF
            if (rMinus > 105 && rMinus > bMinus * 1.2f && (gMinus > bMinus || rMinus > gMinus * 1.15f)) {
                woodScoreMinus++
            }
        }

        if (woodScoreMinus >= 1 && woodScoreMinus > woodScorePlus) {
            // Stick is in -u direction, forward aim is +u
            return ResolvedAim(
                forwardDir = u,
                backwardDir = -u,
                isFlipped = false,
                resolutionMethod = "texture_wood_minus"
            )
        } else if (woodScorePlus >= 1 && woodScorePlus > woodScoreMinus) {
            // Stick is in +u direction, forward aim is -u
            return ResolvedAim(
                forwardDir = -u,
                backwardDir = u,
                isFlipped = true,
                resolutionMethod = "texture_wood_plus"
            )
        }

        // ------------------------------------------------------------------------
        // Priority 2: Geometric Boundary Probing
        // ------------------------------------------------------------------------
        val probeDistances = floatArrayOf(
            tableBounds.width * 0.40f,
            tableBounds.width * 0.60f,
            max(tableBounds.width, tableBounds.height) * 0.70f
        )

        for (probeDistance in probeDistances) {
            val probePlus = cueBallPos + (u * probeDistance)
            val probeMinus = cueBallPos - (u * probeDistance)

            val plusInside = isInsideBounds(probePlus, innerMinX, innerMaxX, innerMinY, innerMaxY)
            val minusInside = isInsideBounds(probeMinus, innerMinX, innerMaxX, innerMinY, innerMaxY)

            if (plusInside && !minusInside) {
                return ResolvedAim(
                    forwardDir = u,
                    backwardDir = -u,
                    isFlipped = false,
                    resolutionMethod = "boundary_probe_plus"
                )
            } else if (!plusInside && minusInside) {
                return ResolvedAim(
                    forwardDir = -u,
                    backwardDir = u,
                    isFlipped = true,
                    resolutionMethod = "boundary_probe_minus"
                )
            }
        }

        // ------------------------------------------------------------------------
        // Priority 3: Fallback / Ambiguous Safety Net
        // ------------------------------------------------------------------------
        return null
    }

    private fun isInsideBounds(pt: Vector2D, minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean {
        return pt.x in minX..maxX && pt.y in minY..maxY
    }
}
