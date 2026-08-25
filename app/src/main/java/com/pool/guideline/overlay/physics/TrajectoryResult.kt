package com.pool.guideline.overlay.physics

data class TrajectorySegment(
    val start: Vector2D,
    val end: Vector2D,
    val isCushionBounce: Boolean = false,
    val color: Int = 0xFFFFFFFF.toInt()
)

data class BallTrajectoryPath(
    val ballIndex: Int,
    val ballColor: Int,
    val segments: List<TrajectorySegment> = emptyList(),
    val endingPocket: Pocket? = null
)

data class TrajectoryResult(
    // 1. Cue ball path up to impact
    val cuePathSegments: List<TrajectorySegment> = emptyList(),

    // 2. Ghost ball impact data
    val hasGhostBall: Boolean = false,
    val ghostBallCenter: Vector2D = Vector2D.ZERO,
    val targetBallCenter: Vector2D = Vector2D.ZERO,

    // 3. Object ball multi-cushion bank trajectory (zigzag to pocket)
    val targetBallSegments: List<TrajectorySegment> = emptyList(),

    // 4. Cue ball post-impact multi-cushion deflection trajectory
    val cuePostImpactSegments: List<TrajectorySegment> = emptyList(),

    // 5. Chain combo collision (if target ball hits another ball)
    val secondaryBallSegments: List<TrajectorySegment> = emptyList(),

    // 6. Break shot / Multi-ball simulation paths
    val multiBallPaths: List<BallTrajectoryPath> = emptyList(),

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
