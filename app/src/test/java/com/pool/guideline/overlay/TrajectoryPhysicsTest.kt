package com.pool.guideline.overlay

import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.BallType
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.physics.TrajectoryPhysicsEngine
import com.pool.guideline.overlay.physics.Vector2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class TrajectoryPhysicsTest {

    private lateinit var physicsEngine: TrajectoryPhysicsEngine
    private val standardTable = TableBounds(xMin = 50f, yMin = 50f, xMax = 950f, yMax = 500f)
    private val ballRadius = 20.0f

    @Before
    fun setup() {
        physicsEngine = TrajectoryPhysicsEngine(maxBounces = 3)
    }

    @Test
    fun `test direct head-on ghost ball collision`() {
        val cuePos = Vector2D(200f, 250f)
        val aimDir = Vector2D(1f, 0f) // Pointing right along +X
        val targetBall = BallData(Vector2D(500f, 250f), ballRadius, BallType.OBJECT_SOLID)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetBalls = listOf(targetBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertTrue("Ghost ball collision should be detected", result.hasGhostBall)

        // For head-on collision, ghost ball should be at (targetX - 2R, targetY)
        val expectedGhostX = 500f - (ballRadius * 2.0f) // 460f
        assertEquals(expectedGhostX, result.ghostBallCenter.x, 0.01f)
        assertEquals(250f, result.ghostBallCenter.y, 0.01f)

        // Distance from ghost ball to target ball must be exactly 2R
        val distToTarget = result.ghostBallCenter.distanceTo(result.targetBallCenter)
        assertEquals("Distance between ghost ball and target ball center must equal 2R", 2 * ballRadius, distToTarget, 0.01f)
    }

    @Test
    fun `test glancing cut shot ghost ball distance and 90-degree deflection orthogonality`() {
        val cuePos = Vector2D(200f, 200f)
        val aimDir = Vector2D(1f, 0f) // Pointing right
        // Ball offset by 1.2R in Y (perpendicular offset d = 24 < 2R = 40)
        val targetBall = BallData(Vector2D(400f, 224f), ballRadius, BallType.OBJECT_SOLID)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetBalls = listOf(targetBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertTrue(result.hasGhostBall)

        // Check exact 2R spacing
        val distToTarget = result.ghostBallCenter.distanceTo(result.targetBallCenter)
        assertEquals(2 * ballRadius, distToTarget, 0.01f)

        // Verify Deflection Orthogonality: dot product of target normal and deflection direction must equal 0.0
        val targetNormal = (result.targetBallCenter - result.ghostBallCenter).normalized()
        val cueDeflection = (result.cueDeflectionEnd - result.cueDeflectionStart).normalized()
        val dotProduct = targetNormal.dot(cueDeflection)

        assertEquals("Target vector and Cue Deflection vector must be strictly perpendicular (dot product == 0)", 0.0f, dotProduct, 1e-4f)
    }

    @Test
    fun `test balls behind cue line are ignored`() {
        val cuePos = Vector2D(400f, 250f)
        val aimDir = Vector2D(1f, 0f) // Pointing right
        // Ball is at X = 200 (behind cue ball)
        val behindBall = BallData(Vector2D(200f, 250f), ballRadius, BallType.OBJECT_SOLID)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetBalls = listOf(behindBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        // Should not hit the ball behind it, but instead hit the right rail
        assertFalse("Balls behind the cue stick aim direction must be ignored", result.hasGhostBall)
        assertTrue(result.cuePathSegments.isNotEmpty())
    }

    @Test
    fun `test cushion reflection boundaries are inset by ball radius R`() {
        val cuePos = Vector2D(200f, 250f)
        val aimDir = Vector2D(0f, -1f) // Pointing straight up towards top rail (yMin = 50)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetBalls = emptyList(),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        val firstBounce = result.cuePathSegments[0]
        // Top rail bounce point must be at yMin + R = 50 + 20 = 70f
        assertEquals("Rail bounce point must be inset by ball radius R from felt boundary", standardTable.yMin + ballRadius, firstBounce.end.y, 0.01f)
    }

    @Test
    fun `test multi-bounce bank reflection angles`() {
        val cuePos = Vector2D(200f, 250f)
        // Aim diagonally up-right: (1, -1) normalized
        val aimDir = Vector2D(1f, -1f).normalized()

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetBalls = emptyList(),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        // Multiple cushion bounces should be calculated
        assertTrue("Multi-bounce trajectory should contain cushion bounces", result.cuePathSegments.size > 1)
        val firstSeg = result.cuePathSegments[0]
        val secondSeg = result.cuePathSegments[1]

        assertEquals(firstSeg.end.x, secondSeg.start.x, 0.01f)
        assertEquals(firstSeg.end.y, secondSeg.start.y, 0.01f)
    }
}
