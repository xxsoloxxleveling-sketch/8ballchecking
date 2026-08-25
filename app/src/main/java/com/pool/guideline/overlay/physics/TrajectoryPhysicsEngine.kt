package com.pool.guideline.overlay.physics

import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.NativeCvBridge
import com.pool.guideline.overlay.cv.TableBounds
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * High-performance 8-ball pool physics and raycasting engine.
 * Calculates multi-bounce cushion reflections, ghost ball impact position,
 * object ball path, and 90-degree cue ball deflection vector.
 */
class TrajectoryPhysicsEngine(
    var maxBounces: Int = 3,
    var targetPathLength: Float = 450.0f,
    var deflectionPathLength: Float = 200.0f
) {

    private data class RailIntersection(
        val hit: Boolean,
        val point: Vector2D = Vector2D.ZERO,
        val normal: Vector2D = Vector2D.ZERO,
        val distance: Float = Float.MAX_VALUE
    )

    /**
     * Computes the complete trajectory from cue ball to ghost ball and cushions.
     */
    fun computeTrajectory(
        cueBallPos: Vector2D,
        aimDirection: Vector2D,
        targetBalls: List<BallData>,
        tableBounds: TableBounds,
        ballRadius: Float = tableBounds.estimatedBallRadius
    ): TrajectoryResult {
        val dir = aimDirection.normalized()
        if (dir.lengthSq() < 1e-4f) return TrajectoryResult.EMPTY

        // Optional JNI Native Acceleration
        if (NativeCvBridge.hasNativeAcceleration() && targetBalls.isNotEmpty()) {
            val flatBalls = FloatArray(targetBalls.size * 2)
            for (i in targetBalls.indices) {
                flatBalls[i * 2] = targetBalls[i].center.x
                flatBalls[i * 2 + 1] = targetBalls[i].center.y
            }

            val nativeOut = NativeCvBridge.computeTrajectoryNative(
                cueBallPos.x,
                cueBallPos.y,
                dir.x,
                dir.y,
                flatBalls,
                tableBounds.xMin,
                tableBounds.yMin,
                tableBounds.xMax,
                tableBounds.yMax,
                ballRadius,
                maxBounces
            )

            if (nativeOut != null && nativeOut.isNotEmpty()) {
                val parsed = parseNativeOutput(nativeOut, ballRadius, tableBounds)
                if (parsed != null) return parsed
            }
        }

        // Pure Kotlin / Zero Allocation Fallback Engine
        return computeTrajectoryKotlin(cueBallPos, dir, targetBalls, tableBounds, ballRadius)
    }

    private fun computeTrajectoryKotlin(
        cueBallPos: Vector2D,
        aimDirection: Vector2D,
        targetBalls: List<BallData>,
        tableBounds: TableBounds,
        ballRadius: Float
    ): TrajectoryResult {
        val cuePath = ArrayList<TrajectorySegment>(maxBounces + 1)
        var currentOrigin = cueBallPos
        var currentDir = aimDirection
        val combinedRadius = ballRadius * 2.0f
        val combinedRadiusSq = combinedRadius * combinedRadius

        var hasGhostBall = false
        var ghostBallPos = Vector2D.ZERO
        var hitTargetBallPos = Vector2D.ZERO
        var targetNormal = Vector2D.ZERO
        var cueDeflection = Vector2D.ZERO

        for (bounce in 0..maxBounces) {
            // Step 1: Find closest ball intersection along current ray
            var closestBallDist = Float.MAX_VALUE
            var candidateGhostPos = Vector2D.ZERO
            var candidateTargetBall = Vector2D.ZERO
            var ballFound = false

            for (ball in targetBalls) {
                val v = ball.center - currentOrigin
                val tProj = v.dot(currentDir)

                // Ball must be strictly in front of ray origin
                if (tProj <= 0.0f) continue

                val vLenSq = v.lengthSq()
                val dSq = vLenSq - (tProj * tProj)

                // Ray does not intersect enlarged circle of radius 2R
                if (dSq > combinedRadiusSq) continue

                val offset = sqrt(max(0.0f, combinedRadiusSq - dSq))
                val tHit = tProj - offset

                if (tHit > 1.0f && tHit < closestBallDist) {
                    closestBallDist = tHit
                    candidateGhostPos = currentOrigin + (currentDir * tHit)
                    candidateTargetBall = ball.center
                    ballFound = true
                }
            }

            // Step 2: Find cushion rail intersection with radius inset R
            val railHit = findRailIntersection(currentOrigin, currentDir, tableBounds, ballRadius)

            // Step 3: Compare ball collision vs cushion collision
            if (ballFound && (!railHit.hit || closestBallDist < railHit.distance)) {
                cuePath.add(TrajectorySegment(currentOrigin, candidateGhostPos, isCushionBounce = false))
                hasGhostBall = true
                ghostBallPos = candidateGhostPos
                hitTargetBallPos = candidateTargetBall

                // Target ball normal direction: from ghost ball center to object ball center
                targetNormal = (hitTargetBallPos - ghostBallPos).normalized()

                // 90-degree tangent deflection rule: t = u - (u . n) * n
                val dot = currentDir.dot(targetNormal)
                val deflectionDir = (currentDir - (targetNormal * dot)).normalized()
                cueDeflection = deflectionDir

                break // Terminate at object ball collision
            }

            if (railHit.hit && railHit.distance > 1.0f) {
                cuePath.add(TrajectorySegment(currentOrigin, railHit.point, isCushionBounce = true))
                // Reflect ray across rail normal
                val reflDir = currentDir.reflect(railHit.normal).normalized()
                currentOrigin = railHit.point
                currentDir = reflDir
            } else {
                // Ray exits without hitting rails, extend outward
                val endPoint = currentOrigin + (currentDir * 500.0f)
                cuePath.add(TrajectorySegment(currentOrigin, endPoint, isCushionBounce = false))
                break
            }
        }

        val targetPathEnd = if (hasGhostBall) {
            hitTargetBallPos + (targetNormal * targetPathLength)
        } else Vector2D.ZERO

        val cueDeflectionEnd = if (hasGhostBall) {
            ghostBallPos + (cueDeflection * deflectionPathLength)
        } else Vector2D.ZERO

        val targetAngle = if (hasGhostBall) targetNormal.angle() else 0f
        val defAngle = if (hasGhostBall) cueDeflection.angle() else 0f

        // Best pocket targeting calculation
        var bestPocket: Pocket? = null
        var bestScore = 0f
        if (hasGhostBall && tableBounds.isValid) {
            val pockets = tableBounds.getPockets(ballRadius)
            for (p in pockets) {
                val score = p.computeAlignmentScore(hitTargetBallPos, targetNormal)
                if (score > bestScore) {
                    bestScore = score
                    bestPocket = p
                }
            }
        }

        return TrajectoryResult(
            cuePathSegments = cuePath,
            hasGhostBall = hasGhostBall,
            ghostBallCenter = ghostBallPos,
            targetBallCenter = hitTargetBallPos,
            targetPathStart = hitTargetBallPos,
            targetPathEnd = targetPathEnd,
            cueDeflectionStart = ghostBallPos,
            cueDeflectionEnd = cueDeflectionEnd,
            targetAngleRad = targetAngle,
            deflectionAngleRad = defAngle,
            bestPocket = bestPocket,
            pocketScore = bestScore,
            ballRadius = ballRadius
        )
    }

    private fun findRailIntersection(
        origin: Vector2D,
        dir: Vector2D,
        table: TableBounds,
        ballRadius: Float
    ): RailIntersection {
        if (!table.isValid) return RailIntersection(false)

        val leftBound = table.xMin + ballRadius
        val rightBound = table.xMax - ballRadius
        val topBound = table.yMin + ballRadius
        val bottomBound = table.yMax - ballRadius

        var minT = Float.MAX_VALUE
        var hitPoint = Vector2D.ZERO
        var hitNormal = Vector2D.ZERO
        var hit = false

        // Vertical rails
        if (dir.x > 1e-6f) {
            val t = (rightBound - origin.x) / dir.x
            if (t > 0f && t < minT) {
                val hitY = origin.y + t * dir.y
                if (hitY in (topBound - 1f)..(bottomBound + 1f)) {
                    minT = t
                    hitPoint = Vector2D(rightBound, hitY)
                    hitNormal = Vector2D(-1f, 0f)
                    hit = true
                }
            }
        } else if (dir.x < -1e-6f) {
            val t = (leftBound - origin.x) / dir.x
            if (t > 0f && t < minT) {
                val hitY = origin.y + t * dir.y
                if (hitY in (topBound - 1f)..(bottomBound + 1f)) {
                    minT = t
                    hitPoint = Vector2D(leftBound, hitY)
                    hitNormal = Vector2D(1f, 0f)
                    hit = true
                }
            }
        }

        // Horizontal rails
        if (dir.y > 1e-6f) {
            val t = (bottomBound - origin.y) / dir.y
            if (t > 0f && t < minT) {
                val hitX = origin.x + t * dir.x
                if (hitX in (leftBound - 1f)..(rightBound + 1f)) {
                    minT = t
                    hitPoint = Vector2D(hitX, bottomBound)
                    hitNormal = Vector2D(0f, -1f)
                    hit = true
                }
            }
        } else if (dir.y < -1e-6f) {
            val t = (topBound - origin.y) / dir.y
            if (t > 0f && t < minT) {
                val hitX = origin.x + t * dir.x
                if (hitX in (leftBound - 1f)..(rightBound + 1f)) {
                    minT = t
                    hitPoint = Vector2D(hitX, topBound)
                    hitNormal = Vector2D(0f, 1f)
                    hit = true
                }
            }
        }

        return RailIntersection(hit, hitPoint, hitNormal, minT)
    }

    private fun parseNativeOutput(
        data: FloatArray,
        ballRadius: Float,
        tableBounds: TableBounds
    ): TrajectoryResult? {
        try {
            if (data.isEmpty()) return null
            var idx = 0
            val segCount = data[idx++].toInt()
            val segments = ArrayList<TrajectorySegment>(segCount)

            for (s in 0 until segCount) {
                val sx = data[idx++]
                val sy = data[idx++]
                val ex = data[idx++]
                val ey = data[idx++]
                segments.add(TrajectorySegment(Vector2D(sx, sy), Vector2D(ex, ey), s > 0))
            }

            val hasGhost = data[idx++] > 0.5f
            if (!hasGhost) {
                return TrajectoryResult(
                    cuePathSegments = segments,
                    ballRadius = ballRadius
                )
            }

            val ghostX = data[idx++]
            val ghostY = data[idx++]
            val targetX = data[idx++]
            val targetY = data[idx++]
            val targetEndX = data[idx++]
            val targetEndY = data[idx++]
            val cueDefEndX = data[idx++]
            val cueDefEndY = data[idx++]
            val targetAngle = data[idx++]
            val defAngle = data[idx++]

            val ghostPos = Vector2D(ghostX, ghostY)
            val targetPos = Vector2D(targetX, targetY)
            val targetHeading = (targetPos - ghostPos).normalized()

            var bestPocket: Pocket? = null
            var bestScore = 0f
            if (tableBounds.isValid) {
                val pockets = tableBounds.getPockets(ballRadius)
                for (p in pockets) {
                    val score = p.computeAlignmentScore(targetPos, targetHeading)
                    if (score > bestScore) {
                        bestScore = score
                        bestPocket = p
                    }
                }
            }

            return TrajectoryResult(
                cuePathSegments = segments,
                hasGhostBall = true,
                ghostBallCenter = ghostPos,
                targetBallCenter = targetPos,
                targetPathStart = targetPos,
                targetPathEnd = Vector2D(targetEndX, targetEndY),
                cueDeflectionStart = ghostPos,
                cueDeflectionEnd = Vector2D(cueDefEndX, cueDefEndY),
                targetAngleRad = targetAngle,
                deflectionAngleRad = defAngle,
                bestPocket = bestPocket,
                pocketScore = bestScore,
                ballRadius = ballRadius
            )
        } catch (e: Exception) {
            return null
        }
    }
}
