package com.pool.guideline.overlay.ui

import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.physics.Vector2D
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Exponential Moving Average (EMA) filter for angles and 2D vectors.
 * Eliminates single-frame jitter and micro-stutter while keeping trajectory lines responsive.
 */
class SmoothingFilter(
    var alpha: Float = 0.35f
) {
    private var smoothedGhostPos: Vector2D? = null
    private var smoothedTargetPos: Vector2D? = null
    private var smoothedTargetAngle: Float? = null
    private var smoothedDeflectionAngle: Float? = null

    // Angle smoothing via unit vector accumulation (prevents -PI / +PI discontinuity artifacts)
    private var sinAccumTarget = 0f
    private var cosAccumTarget = 0f
    private var sinAccumDefl = 0f
    private var cosAccumDefl = 0f

    fun reset() {
        smoothedGhostPos = null
        smoothedTargetPos = null
        smoothedTargetAngle = null
        smoothedDeflectionAngle = null
        sinAccumTarget = 0f
        cosAccumTarget = 0f
        sinAccumDefl = 0f
        cosAccumDefl = 0f
    }

    fun smooth(raw: TrajectoryResult): TrajectoryResult {
        if (!raw.hasGhostBall) {
            reset()
            return raw
        }

        // Smooth Ghost Ball Position
        val ghost = smoothedGhostPos?.let { current ->
            smoothVector(current, raw.ghostBallCenter, alpha)
        } ?: raw.ghostBallCenter
        smoothedGhostPos = ghost

        // Smooth Target Ball Position
        val target = smoothedTargetPos?.let { current ->
            smoothVector(current, raw.targetBallCenter, alpha)
        } ?: raw.targetBallCenter
        smoothedTargetPos = target

        // Smooth Target Heading Angle
        val targetAngle = smoothAngle(raw.targetAngleRad, isTarget = true)

        // Smooth Deflection Heading Angle
        val defAngle = smoothAngle(raw.deflectionAngleRad, isTarget = false)

        val targetDir = Vector2D.fromAngle(targetAngle)
        val defDir = Vector2D.fromAngle(defAngle)

        val targetEnd = target + (targetDir * 450.0f)
        val defEnd = ghost + (defDir * 200.0f)

        return raw.copy(
            ghostBallCenter = ghost,
            targetBallCenter = target,
            targetPathStart = target,
            targetPathEnd = targetEnd,
            cueDeflectionStart = ghost,
            cueDeflectionEnd = defEnd,
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
