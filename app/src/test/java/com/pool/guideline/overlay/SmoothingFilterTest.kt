package com.pool.guideline.overlay

import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.physics.Vector2D
import com.pool.guideline.overlay.ui.SmoothingFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class SmoothingFilterTest {

    private lateinit var filter: SmoothingFilter

    @Before
    fun setup() {
        filter = SmoothingFilter(alpha = 0.35f)
    }

    @Test
    fun `test position jitter smoothing converges over successive frames`() {
        val initialResult = TrajectoryResult(
            hasGhostBall = true,
            ghostBallCenter = Vector2D(100f, 100f),
            targetBallCenter = Vector2D(200f, 200f),
            targetAngleRad = 0.5f,
            deflectionAngleRad = -1.07f
        )

        // First frame initialization
        val frame1 = filter.smooth(initialResult)
        assertEquals(100f, frame1.ghostBallCenter.x, 0.01f)

        // Second frame with noisy jitter input (+10px jump)
        val noisyResult = initialResult.copy(ghostBallCenter = Vector2D(110f, 100f))
        val frame2 = filter.smooth(noisyResult)

        // With alpha = 0.35, smoothed x = 100 + 0.35 * (110 - 100) = 103.5f
        assertEquals(103.5f, frame2.ghostBallCenter.x, 0.01f)
    }

    @Test
    fun `test angle wrapping smoothing across boundary`() {
        val radPositive = (PI - 0.05).toFloat() // +3.09 rad (~177 deg)
        val radNegative = (-PI + 0.05).toFloat() // -3.09 rad (~ -177 deg)

        val frame1 = filter.smooth(
            TrajectoryResult(
                hasGhostBall = true,
                targetAngleRad = radPositive
            )
        )

        val frame2 = filter.smooth(
            TrajectoryResult(
                hasGhostBall = true,
                targetAngleRad = radNegative
            )
        )

        // The angle delta across boundary should smoothly transition near +/- PI without jumping to 0
        assertTrue("Smoothed angle should stay close to +/- PI", abs(frame2.targetAngleRad) > 2.8f)
    }
}
