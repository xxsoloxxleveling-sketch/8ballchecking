package com.pool.guideline.overlay.physics

import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.NativeCvBridge
import com.pool.guideline.overlay.cv.TableBounds
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Advanced 8-ball pool physics and raycasting engine.
 * Supports:
 * - Multi-bounce object ball bank shots (zigzagging across cushions into pockets)
 * - Cue ball post-impact multi-cushion deflection paths
 * - Chain collision / combo shot propagation (ball-to-ball)
 * - 90-degree tangent deflection rule
 */
class TrajectoryPhysicsEngine(
    var maxBounces: Int = 4,
    var targetPathLength: Float = 1200.0f,
    var deflectionPathLength: Float = 600.0f
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
        targetBalls: List<BallData>,
        tableBounds: TableBounds,
        ballRadius: Float = tableBounds.estimatedBallRadius
    ): TrajectoryResult {
        val dir = aimDirection.normalized()
        if (dir.lengthSq() < 1e-4f) return TrajectoryResult.EMPTY

        return computeFullTrajectoryWithBanks(cueBallPos, dir, targetBalls, tableBounds, ballRadius)
    }

    private fun computeFullTrajectoryWithBanks(
        cueBallPos: Vector2D,
        aimDirection: Vector2D,
        targetBalls: List<BallData>,
        tableBounds: TableBounds,
        ballRadius: Float
    ): TrajectoryResult {
        val cuePreImpactSegments = ArrayList<TrajectorySegment>(maxBounces + 1)
        var currentOrigin = cueBallPos
        var currentDir = aimDirection
        val combinedRadius = ballRadius * 2.0f
        val combinedRadiusSq = combinedRadius * combinedRadius

        var hasGhostBall = false
        var ghostBallPos = Vector2D.ZERO
        var hitTargetBallPos = Vector2D.ZERO
        var hitTargetBallIndex = -1
        var targetNormal = Vector2D.ZERO
        var cueDeflection = Vector2D.ZERO

        // Step 1: Trace Cue Ball path until it hits the first object ball (or max cushion bounces)
        for (bounce in 0..maxBounces) {
            var closestBallDist = Float.MAX_VALUE
            var candidateGhostPos = Vector2D.ZERO
            var candidateTargetBall = Vector2D.ZERO
            var candidateIndex = -1
            var ballFound = false

            for (i in targetBalls.indices) {
                val ball = targetBalls[i]
                val v = ball.center - currentOrigin
                val tProj = v.dot(currentDir)

                if (tProj <= 0.0f) continue

                val vLenSq = v.lengthSq()
                val dSq = vLenSq - (tProj * tProj)

                if (dSq > combinedRadiusSq) continue

                val offset = sqrt(max(0.0f, combinedRadiusSq - dSq))
                val tHit = tProj - offset

                if (tHit > 1.0f && tHit < closestBallDist) {
                    closestBallDist = tHit
                    candidateGhostPos = currentOrigin + (currentDir * tHit)
                    candidateTargetBall = ball.center
                    candidateIndex = i
                    ballFound = true
                }
            }

            val railHit = findRailIntersection(currentOrigin, currentDir, tableBounds, ballRadius)

            if (ballFound && (!railHit.hit || closestBallDist < railHit.distance)) {
                cuePreImpactSegments.add(TrajectorySegment(currentOrigin, candidateGhostPos, isCushionBounce = bounce > 0))
                hasGhostBall = true
                ghostBallPos = candidateGhostPos
                hitTargetBallPos = candidateTargetBall
                hitTargetBallIndex = candidateIndex

                // Normal vector: Target ball trajectory direction
                targetNormal = (hitTargetBallPos - ghostBallPos).normalized()

                // Tangent vector: 90-degree cue deflection
                val dot = currentDir.dot(targetNormal)
                cueDeflection = (currentDir - (targetNormal * dot)).normalized()
                break
            }

            if (railHit.hit && railHit.distance > 1.0f) {
                cuePreImpactSegments.add(TrajectorySegment(currentOrigin, railHit.point, isCushionBounce = true))
                val reflDir = currentDir.reflect(railHit.normal).normalized()
                currentOrigin = railHit.point
                currentDir = reflDir
            } else {
                val endPoint = currentOrigin + (currentDir * 500.0f)
                cuePreImpactSegments.add(TrajectorySegment(currentOrigin, endPoint, isCushionBounce = false))
                break
            }
        }

        // Step 2: Trace Object Ball Multi-Cushion Bank Path (Zigzag into pockets - Image 1)
        val targetBallSegments = ArrayList<TrajectorySegment>()
        var bestPocket: Pocket? = null
        var bestPocketScore = 0f

        if (hasGhostBall) {
            var objOrigin = hitTargetBallPos
            var objDir = targetNormal
            val pockets = tableBounds.getPockets(ballRadius)

            for (bounce in 0..maxBounces) {
                val railHit = findRailIntersection(objOrigin, objDir, tableBounds, ballRadius)

                // Check pocket alignment along this segment
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

        // Step 3: Trace Cue Ball Post-Impact Multi-Cushion Deflection (Image 2)
        val cuePostImpactSegments = ArrayList<TrajectorySegment>()
        if (hasGhostBall && cueDeflection.lengthSq() > 0.1f) {
            var cueDefOrigin = ghostBallPos
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

        // Step 4: Multi-Ball Break Shot / Propagation Simulation (Image 3)
        val multiBallPaths = ArrayList<BallTrajectoryPath>()
        if (hasGhostBall && targetBalls.size >= 3) {
            for (i in targetBalls.indices) {
                if (i == hitTargetBallIndex) continue
                val ball = targetBalls[i]
                val distToTarget = ball.center.distanceTo(hitTargetBallPos)
                // If ball is close to target ball (cluster/rack), propagate impulse
                if (distToTarget < combinedRadius * 1.8f) {
                    val impulseDir = (ball.center - hitTargetBallPos).normalized()
                    val pathSegs = ArrayList<TrajectorySegment>()
                    val railHit = findRailIntersection(ball.center, impulseDir, tableBounds, ballRadius)
                    if (railHit.hit) {
                        pathSegs.add(TrajectorySegment(ball.center, railHit.point, isCushionBounce = false))
                        val refl = impulseDir.reflect(railHit.normal).normalized()
                        pathSegs.add(TrajectorySegment(railHit.point, railHit.point + (refl * 200f), isCushionBounce = true))
                    } else {
                        pathSegs.add(TrajectorySegment(ball.center, ball.center + (impulseDir * 300f), isCushionBounce = false))
                    }
                    multiBallPaths.add(BallTrajectoryPath(i, getBallColor(i), pathSegs))
                }
            }
        }

        return TrajectoryResult(
            cuePathSegments = cuePreImpactSegments,
            hasGhostBall = hasGhostBall,
            ghostBallCenter = ghostBallPos,
            targetBallCenter = hitTargetBallPos,
            targetBallSegments = targetBallSegments,
            cuePostImpactSegments = cuePostImpactSegments,
            multiBallPaths = multiBallPaths,
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

    private fun getBallColor(index: Int): Int {
        val colors = intArrayOf(
            0xFFFFD600.toInt(), // 1/9 Yellow
            0xFF0091EA.toInt(), // 2/10 Blue
            0xFFFF1744.toInt(), // 3/11 Red
            0xFFAA00FF.toInt(), // 4/12 Purple
            0xFFFF6D00.toInt(), // 5/13 Orange
            0xFF00E676.toInt(), // 6/14 Green
            0xFF8D6E63.toInt(), // 7/15 Brown
            0xFF212121.toInt()  // 8 Black
        )
        return colors[index % colors.size]
    }
}
