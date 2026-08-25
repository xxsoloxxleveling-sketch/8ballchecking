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
 * Boundary & Texture Polarity Resolver.
 * Resolves the true forward shooting direction from an unoriented collinear axis line.
 *
 * Tier 1 (Primary): Geometric boundary probing against calibrated table bounds.
 * Tier 2 (Fallback): Wood/cue shaft texture sampling (rejects the stick side, returns opposite).
 * Tier 3 (Safety): Returns NULL to skip frame rather than guessing.
 */
object AimDirectionResolver {

    /**
     * Resolves the true forward shooting direction.
     *
     * Logic, in strict priority order:
     * 1. Project probe points along +axisDir and -axisDir at fixed distance (half shorter dimension).
     *    If exactly ONE probe lands inside tableBounds, return that direction immediately.
     * 2. If Tier 1 is ambiguous (both inside / near-rail ties), sample multi-point texture along +u and -u.
     *    - If +u contains cue stick/wood texture, REJECT +u and return -u as forward aim.
     *    - If -u contains cue stick/wood texture, REJECT -u and return +u as forward aim.
     * 3. If still ambiguous, return NULL to safely skip frame.
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
        // Tier 1 (Primary): Geometric Boundary Probing
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
        // Tier 2 (Fallback): Wood/Cue Shaft Texture Sampling
        // Sample multiple points along both directions and reject the cue stick side.
        // ------------------------------------------------------------------------
        var woodScorePlus = 0
        var woodScoreMinus = 0

        // Multi-point sampling at increasing distances from cue ball center
        val sampleDistances = intArrayOf(
            (ballRadius * 1.6f).toInt(),
            (ballRadius * 2.5f).toInt(),
            (ballRadius * 3.8f).toInt(),
            (ballRadius * 5.2f).toInt(),
            (ballRadius * 7.0f).toInt(),
            (ballRadius * 9.0f).toInt()
        )

        for (d in sampleDistances) {
            // Sample along +u
            val plusX = (cueBallPos.x + u.x * d).toInt().coerceIn(0, width - 1)
            val plusY = (cueBallPos.y + u.y * d).toInt().coerceIn(0, height - 1)
            val colPlus = pixels[plusY * width + plusX]
            if (isWoodOrStickColor(colPlus)) {
                woodScorePlus++
            }

            // Sample along -u
            val minusX = (cueBallPos.x - u.x * d).toInt().coerceIn(0, width - 1)
            val minusY = (cueBallPos.y - u.y * d).toInt().coerceIn(0, height - 1)
            val colMinus = pixels[minusY * width + minusX]
            if (isWoodOrStickColor(colMinus)) {
                woodScoreMinus++
            }
        }

        // REJECT the stick side, return the OPPOSITE candidate as forward direction
        if (woodScorePlus > woodScoreMinus && woodScorePlus >= 1) {
            // Wood/Stick detected along +u -> Forward aim is -u
            return ResolvedAim(
                forwardDir = -u,
                backwardDir = u,
                isFlipped = true,
                resolutionMethod = "texture_reject_plus"
            )
        } else if (woodScoreMinus > woodScorePlus && woodScoreMinus >= 1) {
            // Wood/Stick detected along -u -> Forward aim is +u
            return ResolvedAim(
                forwardDir = u,
                backwardDir = -u,
                isFlipped = false,
                resolutionMethod = "texture_reject_minus"
            )
        }

        // ------------------------------------------------------------------------
        // Tier 3: Ambiguous Safety Net — Return NULL to safely skip frame
        // ------------------------------------------------------------------------
        return null
    }

    /**
     * Checks if a pixel color matches cue stick wood / shaft texture (R > 110 and R > B * 1.15).
     */
    fun isWoodOrStickColor(colorInt: Int): Boolean {
        val r = (colorInt shr 16) and 0xFF
        val g = (colorInt shr 8) and 0xFF
        val b = colorInt and 0xFF
        // Wood / tan shaft / brown finish (distinctly warmer and higher red than cyan/blue felt)
        return (r > 110 && r > (b * 1.15f)) || (r > 130 && r > (g * 1.05f))
    }

    private fun isInsideBounds(pt: Vector2D, minX: Float, maxX: Float, minY: Float, maxY: Float): Boolean {
        return pt.x in minX..maxX && pt.y in minY..maxY
    }
}
