package com.pool.guideline.overlay.physics

import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.TableBounds

/**
 * 8-Ball pool trajectory physics engine.
 * Computes direct ghost ball targeting, multi-cushion bank reflections, and 90-degree tangent deflection.
 */
class TrajectoryPhysicsEngine(
    var maxBounces: Int = 4
) {

    private data class RailIntersection(
        val hit: Boolean,
        val point: Vector2D = Vector2D.ZERO,
        val normal: Vector2D = Vector2D.ZERO,
        val distance: Float = Float.MAX_VALUE
    )

    fun computeTrajectory(
        cueBallPos: Vector2D,
        aimDirection: Vector2D,
        targetRingPos: Vector2D?,
        targetBalls: List<BallData>,
        tableBounds: TableBounds,
        ballRadius: Float = tableBounds.estimatedBallRadius
    ): TrajectoryResult {
        val dir = aimDirection.normalized()
        if (dir.lengthSq() < 1e-4f) return TrajectoryResult.EMPTY

        // If target ring is directly identified, snap ghost ball directly to target ring
        val ghostPos = targetRingPos ?: (cueBallPos + dir * 250f)
        val hasGhostBall = targetRingPos != null

        val cuePreImpactSegments = ArrayList<TrajectorySegment>()
        cuePreImpactSegments.add(TrajectorySegment(cueBallPos, ghostPos, isCushionBounce = false))

        // Step 1: Locate the target object ball
        var hitTargetBallPos = ghostPos + (dir * (ballRadius * 1.6f))
        if (hasGhostBall && targetBalls.isNotEmpty()) {
            var nearestDistSq = Float.MAX_VALUE
            for (b in targetBalls) {
                val dSq = ghostPos.distanceSqTo(b.center)
                if (dSq < (ballRadius * 3.0f) * (ballRadius * 3.0f) && dSq < nearestDistSq) {
                    nearestDistSq = dSq
                    hitTargetBallPos = b.center
                }
            }
        }

        // Normal: Target ball direction
        val targetNormal = (hitTargetBallPos - ghostPos).normalized()

        // Tangent: 90-degree cue deflection
        val dot = dir.dot(targetNormal)
        val cueDeflection = (dir - (targetNormal * dot)).normalized()

        // Step 2: Trace Object Ball Multi-Cushion Bank Path (Zigzag into pockets)
        val targetBallSegments = ArrayList<TrajectorySegment>()
        var bestPocket: Pocket? = null
        var bestPocketScore = 0f

        if (hasGhostBall) {
            var objOrigin = hitTargetBallPos
            var objDir = targetNormal
            val pockets = tableBounds.getPockets(ballRadius)

            for (bounce in 0..maxBounces) {
                val railHit = findRailIntersection(objOrigin, objDir, tableBounds, ballRadius)

                // Check pocket alignment
                for (p in pockets) {
                    val score = p.computeAlignmentScore(objOrigin, objDir)
                    if (score > bestPocketScore) {
                        bestPocketScore = score
                        bestPocket = p
                    }
                }

                if (railHit.hit && railHit.distance > 1.0f) {
                    targetBallSegments.add(TrajectorySegment(objOrigin, railHit.point, isCushionBounce = bounce > 0))
                    objOrigin = railHit.point
                    objDir = objDir.reflect(railHit.normal).normalized()
                } else {
                    val endPt = objOrigin + (objDir * 350.0f)
                    targetBallSegments.add(TrajectorySegment(objOrigin, endPt, isCushionBounce = bounce > 0))
                    break
                }
            }
        }

        // Step 3: Trace Cue Ball Post-Impact Multi-Cushion Deflection
        val cuePostImpactSegments = ArrayList<TrajectorySegment>()
        if (hasGhostBall && cueDeflection.lengthSq() > 0.1f) {
            var cueDefOrigin = ghostPos
            var cueDefDir = cueDeflection

            for (bounce in 0..2) {
                val railHit = findRailIntersection(cueDefOrigin, cueDefDir, tableBounds, ballRadius)
                if (railHit.hit && railHit.distance > 1.0f) {
                    cuePostImpactSegments.add(TrajectorySegment(cueDefOrigin, railHit.point, isCushionBounce = bounce > 0))
                    cueDefOrigin = railHit.point
                    cueDefDir = cueDefDir.reflect(railHit.normal).normalized()
                } else {
                    val endPt = cueDefOrigin + (cueDefDir * 250.0f)
                    cuePostImpactSegments.add(TrajectorySegment(cueDefOrigin, endPt, isCushionBounce = bounce > 0))
                    break
                }
            }
        }

        return TrajectoryResult(
            cuePathSegments = cuePreImpactSegments,
            hasGhostBall = hasGhostBall,
            ghostBallCenter = ghostPos,
            targetBallCenter = hitTargetBallPos,
            targetBallSegments = targetBallSegments,
            cuePostImpactSegments = cuePostImpactSegments,
            multiBallPaths = emptyList(),
            targetAngleRad = if (hasGhostBall) targetNormal.angle() else 0f,
            deflectionAngleRad = if (hasGhostBall) cueDeflection.angle() else 0f,
            bestPocket = bestPocket,
            pocketScore = bestPocketScore,
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
}
