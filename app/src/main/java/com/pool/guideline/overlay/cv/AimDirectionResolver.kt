package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.Vector2D
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
 *
 * Tier 1 (Primary): Geometric boundary probing against table bounds.
 * Tier 2 (Fallback): Wood vs felt texture sampling (used only when Tier 1 is ambiguous).
 * Tier 3 (Safety): Returns NULL to skip frame rather than guessing.
 */
object AimDirectionResolver {

    /**
     * Resolves forward shooting direction.
     *
     * Logic, in strict priority order:
     * 1. Project a probe point along +axisDir and -axisDir from the cue ball at a fixed distance
     *    (half the shorter table-bounds dimension).
     *    If exactly ONE probe lands inside tableBounds, return that direction immediately.
     * 2. If both or neither land inside (e.g. shots near center or close to a rail), fall back to
     *    wood vs felt texture sampling (R > 110 and R > B * 1.25 = wood stick; pick felt side).
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
        // Tier 1 (Primary): Geometric Boundary Probing (Half Shorter Dimension)
        // ------------------------------------------------------------------------
        val shorterDimension = min(tableBounds.width, tableBounds.height)
        val probeDistance = (shorterDimension * 0.50f).coerceAtLeast(ballRadius * 3f)

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

        // ------------------------------------------------------------------------
        // Tier 2 (Fallback Only): Wood vs Felt Texture Sampling
        // (Executed only when Tier 1 was inconclusive — both or neither inside)
        // ------------------------------------------------------------------------
        var woodScorePlus = 0
        var woodScoreMinus = 0
        val textureSampleDistances = intArrayOf(15, 30, 50, 75, 105, 140)

        for (d in textureSampleDistances) {
            // Sample along +u
            val plusX = (cueBallPos.x + u.x * d).toInt().coerceIn(0, width - 1)
            val plusY = (cueBallPos.y + u.y * d).toInt().coerceIn(0, height - 1)
            val colPlus = pixels[plusY * width + plusX]
            val rPlus = (colPlus shr 16) and 0xFF
            val bPlus = colPlus and 0xFF
            if (rPlus > 110 && rPlus > (bPlus * 1.25f)) {
                woodScorePlus++
            }

            // Sample along -u
            val minusX = (cueBallPos.x - u.x * d).toInt().coerceIn(0, width - 1)
            val minusY = (cueBallPos.y - u.y * d).toInt().coerceIn(0, height - 1)
            val colMinus = pixels[minusY * width + minusX]
            val rMinus = (colMinus shr 16) and 0xFF
            val bMinus = colMinus and 0xFF
            if (rMinus > 110 && rMinus > (bMinus * 1.25f)) {
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
        // Tier 3: Ambiguous Safety Net — Return NULL to safely skip frame
        // ------------------------------------------------------------------------
        return null
    }

    private fun isInsideBounds(pt: Vector2D, minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean {
        return pt.x in minX..maxX && pt.y in minY..maxY
    }
}
