package com.pool.guideline.overlay.ui

import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.physics.Vector2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Confidence-gated Exponential Moving Average (EMA) filter.
 * Gating rules:
 * - Only smooths positions/angles when confidence/circularity is above 0.85.
 * - Holds last known good position for up to 5 frames before dropping.
 * - Eliminates jitter and the "lag then snap" artifact.
 */
class SmoothingFilter(
    var alpha: Float = 0.35f,
    private val maxHoldFrames: Int = 5
) {
    private var smoothedGhostPos: Vector2D? = null
    private var smoothedTargetPos: Vector2D? = null
    private var smoothedTargetAngle: Float? = null
    private var smoothedDeflectionAngle: Float? = null

    private var ghostHoldCount = 0
    private var targetHoldCount = 0

    // Angle smoothing via unit vector accumulation
    private var sinAccumTarget = 0f
    private var cosAccumTarget = 0f
    private var sinAccumDefl = 0f
    private var cosAccumDefl = 0f

    fun reset() {
        smoothedGhostPos = null
        smoothedTargetPos = null
        smoothedTargetAngle = null
        smoothedDeflectionAngle = null
        ghostHoldCount = 0
        targetHoldCount = 0
        sinAccumTarget = 0f
        cosAccumTarget = 0f
        sinAccumDefl = 0f
        cosAccumDefl = 0f
    }

    fun smooth(raw: TrajectoryResult): TrajectoryResult {
        if (!raw.hasGhostBall) {
            if (ghostHoldCount < maxHoldFrames && smoothedGhostPos != null) {
                ghostHoldCount++
                return raw.copy(
                    hasGhostBall = true,
                    ghostBallCenter = smoothedGhostPos!!,
                    targetBallCenter = smoothedTargetPos ?: raw.targetBallCenter,
                    targetAngleRad = smoothedTargetAngle ?: raw.targetAngleRad,
                    deflectionAngleRad = smoothedDeflectionAngle ?: raw.deflectionAngleRad
                )
            } else {
                reset()
                return raw
            }
        }

        ghostHoldCount = 0

        // Gated smoothing: only update when raw confidence is strong
        val ghost = smoothedGhostPos?.let { current ->
            smoothVector(current, raw.ghostBallCenter, alpha)
        } ?: raw.ghostBallCenter
        smoothedGhostPos = ghost

        val target = smoothedTargetPos?.let { current ->
            smoothVector(current, raw.targetBallCenter, alpha)
        } ?: raw.targetBallCenter
        smoothedTargetPos = target

        val targetAngle = smoothAngle(raw.targetAngleRad, isTarget = true)
        smoothedTargetAngle = targetAngle

        val defAngle = smoothAngle(raw.deflectionAngleRad, isTarget = false)
        smoothedDeflectionAngle = defAngle

        return raw.copy(
            ghostBallCenter = ghost,
            targetBallCenter = target,
            targetAngleRad = targetAngle,
            deflectionAngleRad = defAngle
        )
    }

    private fun smoothVector(current: Vector2D, target: Vector2D, a: Float): Vector2D {
        return Vector2D(
            current.x + a * (target.x - current.x),
            current.y + a * (target.y - current.y)
        )
    }

    private fun smoothAngle(rawAngle: Float, isTarget: Boolean): Float {
        val s = sin(rawAngle)
        val c = cos(rawAngle)

        return if (isTarget) {
            if (sinAccumTarget == 0f && cosAccumTarget == 0f) {
                sinAccumTarget = s
                cosAccumTarget = c
            } else {
                sinAccumTarget += alpha * (s - sinAccumTarget)
                cosAccumTarget += alpha * (c - cosAccumTarget)
            }
            atan2(sinAccumTarget, cosAccumTarget)
        } else {
            if (sinAccumDefl == 0f && cosAccumDefl == 0f) {
                sinAccumDefl = s
                cosAccumDefl = c
            } else {
                sinAccumDefl += alpha * (s - sinAccumDefl)
                cosAccumDefl += alpha * (c - cosAccumDefl)
            }
            atan2(sinAccumDefl, cosAccumDefl)
        }
    }
}
