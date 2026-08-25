package com.pool.guideline.overlay.physics

data class TrajectorySegment(
    val start: Vector2D,
    val end: Vector2D,
    val isCushionBounce: Boolean = false
)

data class TrajectoryResult(
    val cuePathSegments: List<TrajectorySegment> = emptyList(),
    val hasGhostBall: Boolean = false,
    val ghostBallCenter: Vector2D = Vector2D.ZERO,
    val targetBallCenter: Vector2D = Vector2D.ZERO,
    val targetPathStart: Vector2D = Vector2D.ZERO,
    val targetPathEnd: Vector2D = Vector2D.ZERO,
    val cueDeflectionStart: Vector2D = Vector2D.ZERO,
    val cueDeflectionEnd: Vector2D = Vector2D.ZERO,
    val targetAngleRad: Float = 0f,
    val deflectionAngleRad: Float = 0f,
    val bestPocket: Pocket? = null,
    val pocketScore: Float = 0f,
    val ballRadius: Float = 20f
) {
    companion object {
        val EMPTY = TrajectoryResult()
    }
}
