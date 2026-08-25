package com.pool.guideline.overlay.physics

enum class PocketType {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT
}

data class Pocket(
    val type: PocketType,
    val position: Vector2D,
    val captureRadius: Float
) {
    /**
     * Checks if a target trajectory heading from ballPos towards aimAngle aligns with this pocket.
     * Returns an alignment score between 0.0 (misaligned) and 1.0 (perfectly on target).
     */
    fun computeAlignmentScore(ballPos: Vector2D, targetHeading: Vector2D): Float {
        val toPocket = (position - ballPos).normalized()
        val dot = targetHeading.dot(toPocket)
        if (dot <= 0.85f) return 0.0f

        // Convert [0.85, 1.0] -> [0.0, 1.0]
        return ((dot - 0.85f) / 0.15f).coerceIn(0.0f, 1.0f)
    }
}
