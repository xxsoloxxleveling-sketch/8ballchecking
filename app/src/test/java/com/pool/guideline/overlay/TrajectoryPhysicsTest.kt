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

class TrajectoryPhysicsTest {

    private lateinit var physicsEngine: TrajectoryPhysicsEngine
    private val standardTable = TableBounds(xMin = 50f, yMin = 50f, xMax = 950f, yMax = 500f)
    private val ballRadius = 20.0f

    @Before
    fun setup() {
        physicsEngine = TrajectoryPhysicsEngine(maxBounces = 4)
    }

    @Test
    fun `test direct head-on ghost ball collision`() {
        val cuePos = Vector2D(200f, 250f)
        val aimDir = Vector2D(1f, 0f)
        val targetBall = BallData(Vector2D(500f, 250f), ballRadius, BallType.OBJECT_SOLID)
        val targetRing = Vector2D(500f - 2 * ballRadius, 250f)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetRingPos = targetRing,
            targetBalls = listOf(targetBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertTrue("Ghost ball collision should be detected", result.hasGhostBall)

        val expectedGhostX = 500f - (ballRadius * 2.0f) // 460f
        assertEquals(expectedGhostX, result.ghostBallCenter.x, 0.01f)
        assertEquals(250f, result.ghostBallCenter.y, 0.01f)

        val distToTarget = result.ghostBallCenter.distanceTo(result.targetBallCenter)
        assertEquals("Distance between ghost ball and target ball center must equal 2R", 2 * ballRadius, distToTarget, 0.01f)
    }

    @Test
    fun `test glancing cut shot and 90-degree deflection orthogonality`() {
        val cuePos = Vector2D(200f, 200f)
        val aimDir = Vector2D(1f, 0f)
        val targetBall = BallData(Vector2D(400f, 224f), ballRadius, BallType.OBJECT_SOLID)
        val targetRing = Vector2D(368f, 200f)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetRingPos = targetRing,
            targetBalls = listOf(targetBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertTrue(result.hasGhostBall)

        // Verify Deflection Orthogonality: target normal · cue deflection == 0
        val targetNormal = Vector2D.fromAngle(result.targetAngleRad)
        val cueDeflection = Vector2D.fromAngle(result.deflectionAngleRad)
        val dotProduct = targetNormal.dot(cueDeflection)

        assertEquals("Target vector and Cue Deflection vector must be strictly perpendicular (dot product == 0)", 0.0f, dotProduct, 1e-4f)
    }

    @Test
    fun `test multi-cushion target ball bank shot zigzag`() {
        val cuePos = Vector2D(200f, 250f)
        val aimDir = Vector2D(1f, -0.3f).normalized()
        val targetBall = BallData(Vector2D(500f, 200f), ballRadius, BallType.OBJECT_SOLID)
        val targetRing = Vector2D(465f, 212f)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetRingPos = targetRing,
            targetBalls = listOf(targetBall),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertTrue(result.hasGhostBall)
        assertTrue("Object ball should have multiple cushion bank segments", result.targetBallSegments.isNotEmpty())
    }

    @Test
    fun `test balls behind cue line are ignored`() {
        val cuePos = Vector2D(400f, 250f)
        val aimDir = Vector2D(1f, 0f)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetRingPos = null,
            targetBalls = emptyList(),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        assertFalse("When target ring is null, hasGhostBall is false", result.hasGhostBall)
        assertTrue(result.cuePathSegments.isNotEmpty())
    }

    @Test
    fun `test cushion reflection boundaries are inset by ball radius R`() {
        val cuePos = Vector2D(200f, 250f)
        val aimDir = Vector2D(0f, -1f)

        val result = physicsEngine.computeTrajectory(
            cueBallPos = cuePos,
            aimDirection = aimDir,
            targetRingPos = null,
            targetBalls = emptyList(),
            tableBounds = standardTable,
            ballRadius = ballRadius
        )

        val firstBounce = result.cuePathSegments[0]
        assertEquals("Rail bounce point must be inset by ball radius R from felt boundary", standardTable.yMin + ballRadius, firstBounce.end.y, 0.01f)
    }
}
