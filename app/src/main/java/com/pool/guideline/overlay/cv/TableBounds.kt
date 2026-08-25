package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.Pocket
import com.pool.guideline.overlay.physics.PocketType
import com.pool.guideline.overlay.physics.Vector2D

data class TableBounds(
    val xMin: Float = 0f,
    val yMin: Float = 0f,
    val xMax: Float = 0f,
    val yMax: Float = 0f
) {
    val width: Float get() = (xMax - xMin).coerceAtLeast(0f)
    val height: Float get() = (yMax - yMin).coerceAtLeast(0f)
    val isValid: Boolean get() = width > 100f && height > 100f

    /**
     * Estimated ball radius calibrated to standard pool table geometry (~ TableWidth / 45).
     */
    val estimatedBallRadius: Float get() = if (isValid) (width / 45.0f).coerceIn(12f, 40f) else 20f

    /**
     * Returns the 6 standard pocket locations.
     */
    fun getPockets(ballRadius: Float): List<Pocket> {
        if (!isValid) return emptyList()

        val pocketRadius = ballRadius * 1.6f
        val xMid = (xMin + xMax) / 2.0f

        return listOf(
            Pocket(PocketType.TOP_LEFT, Vector2D(xMin, yMin), pocketRadius),
            Pocket(PocketType.TOP_CENTER, Vector2D(xMid, yMin), pocketRadius),
            Pocket(PocketType.TOP_RIGHT, Vector2D(xMax, yMin), pocketRadius),
            Pocket(PocketType.BOTTOM_LEFT, Vector2D(xMin, yMax), pocketRadius),
            Pocket(PocketType.BOTTOM_CENTER, Vector2D(xMid, yMax), pocketRadius),
            Pocket(PocketType.BOTTOM_RIGHT, Vector2D(xMax, yMax), pocketRadius)
        )
    }

    companion object {
        val EMPTY = TableBounds()
    }
}
