#include "pool_cv_engine.h"
#include <algorithm>
#include <limits>
#include <cstring>

namespace pool {

bool checkRayCircleCollision(
    const Vec2D& rayOrigin,
    const Vec2D& rayDir,
    const Vec2D& circleCenter,
    float combinedRadius,
    float& outHitDistance,
    Vec2D& outGhostCenter
) {
    Vec2D v = circleCenter - rayOrigin;
    float tProj = v.dot(rayDir);

    // Ball is behind the ray origin
    if (tProj <= 0.0f) {
        return false;
    }

    float vLenSq = v.lengthSq();
    float dSq = vLenSq - (tProj * tProj);
    float combRadSq = combinedRadius * combinedRadius;

    // Ray does not intersect the enlarged circle
    if (dSq > combRadSq) {
        return false;
    }

    // Distance along ray to ghost ball center
    float offset = std::sqrt(std::max(0.0f, combRadSq - dSq));
    float tHit = tProj - offset;

    if (tHit < 0.0f) {
        return false;
    }

    outHitDistance = tHit;
    outGhostCenter = rayOrigin + (rayDir * tHit);
    return true;
}

RailHit findFirstRailIntersection(
    const Vec2D& rayOrigin,
    const Vec2D& rayDir,
    const TableRect& table,
    float ballRadius
) {
    RailHit result;
    result.hit = false;
    result.distance = std::numeric_limits<float>::max();

    if (!table.isValid()) {
        return result;
    }

    float leftBound = table.xMin + ballRadius;
    float rightBound = table.xMax - ballRadius;
    float topBound = table.yMin + ballRadius;
    float bottomBound = table.yMax - ballRadius;

    // Check vertical rails
    if (rayDir.x > 1e-6f) {
        float t = (rightBound - rayOrigin.x) / rayDir.x;
        if (t > 0.0f && t < result.distance) {
            float hitY = rayOrigin.y + t * rayDir.y;
            if (hitY >= topBound - 1.0f && hitY <= bottomBound + 1.0f) {
                result.hit = true;
                result.distance = t;
                result.hitPoint = Vec2D(rightBound, hitY);
                result.normal = Vec2D(-1.0f, 0.0f);
            }
        }
    } else if (rayDir.x < -1e-6f) {
        float t = (leftBound - rayOrigin.x) / rayDir.x;
        if (t > 0.0f && t < result.distance) {
            float hitY = rayOrigin.y + t * rayDir.y;
            if (hitY >= topBound - 1.0f && hitY <= bottomBound + 1.0f) {
                result.hit = true;
                result.distance = t;
                result.hitPoint = Vec2D(leftBound, hitY);
                result.normal = Vec2D(1.0f, 0.0f);
            }
        }
    }

    // Check horizontal rails
    if (rayDir.y > 1e-6f) {
        float t = (bottomBound - rayOrigin.y) / rayDir.y;
        if (t > 0.0f && t < result.distance) {
            float hitX = rayOrigin.x + t * rayDir.x;
            if (hitX >= leftBound - 1.0f && hitX <= rightBound + 1.0f) {
                result.hit = true;
                result.distance = t;
                result.hitPoint = Vec2D(hitX, bottomBound);
                result.normal = Vec2D(0.0f, -1.0f);
            }
        }
    } else if (rayDir.y < -1e-6f) {
        float t = (topBound - rayOrigin.y) / rayDir.y;
        if (t > 0.0f && t < result.distance) {
            float hitX = rayOrigin.x + t * rayDir.x;
            if (hitX >= leftBound - 1.0f && hitX <= rightBound + 1.0f) {
                result.hit = true;
                result.distance = t;
                result.hitPoint = Vec2D(hitX, topBound);
                result.normal = Vec2D(0.0f, 1.0f);
            }
        }
    }

    return result;
}

CompleteTrajectory computeFullTrajectory(
    const Vec2D& cueCenter,
    const Vec2D& aimDirection,
    const std::vector<Ball2D>& targetBalls,
    const TableRect& table,
    float ballRadius,
    int maxBounces
) {
    CompleteTrajectory trajectory;
    trajectory.hasGhostBall = false;

    Vec2D currentOrigin = cueCenter;
    Vec2D currentDir = aimDirection.normalized();
    float combinedRadius = ballRadius * 2.0f;

    for (int bounce = 0; bounce <= maxBounces; ++bounce) {
        // Step A: Find closest ball collision along current ray
        bool ballHitFound = false;
        float closestBallDist = std::numeric_limits<float>::max();
        Vec2D closestGhostCenter;
        Vec2D targetBallCenter;

        for (const auto& ball : targetBalls) {
            float hitDist = 0.0f;
            Vec2D ghostCenter;
            if (checkRayCircleCollision(currentOrigin, currentDir, ball.center, combinedRadius, hitDist, ghostCenter)) {
                if (hitDist > 1.0f && hitDist < closestBallDist) {
                    closestBallDist = hitDist;
                    closestGhostCenter = ghostCenter;
                    targetBallCenter = ball.center;
                    ballHitFound = true;
                }
            }
        }

        // Step B: Find rail intersection
        RailHit railHit = findFirstRailIntersection(currentOrigin, currentDir, table, ballRadius);

        // Check if ball collision happens before hitting cushion
        if (ballHitFound && (!railHit.hit || closestBallDist < railHit.distance)) {
            // Reached Ghost Ball!
            TrajectorySegment seg;
            seg.start = currentOrigin;
            seg.end = closestGhostCenter;
            seg.isCushionBounce = false;
            trajectory.cuePathSegments.push_back(seg);

            trajectory.hasGhostBall = true;
            trajectory.ghostBallCenter = closestGhostCenter;
            trajectory.targetBallCenter = targetBallCenter;

            // Target ball normal direction: from ghost ball to object ball
            Vec2D targetNorm = (targetBallCenter - closestGhostCenter).normalized();
            trajectory.targetAngleRad = std::atan2(targetNorm.y, targetNorm.x);
            trajectory.targetPathEnd = targetBallCenter + (targetNorm * 450.0f);

            // 90-degree Deflection Tangent for cue ball:
            // t = u - (u . n) * n
            Vec2D deflectionDir = currentDir - (targetNorm * currentDir.dot(targetNorm));
            deflectionDir = deflectionDir.normalized();
            trajectory.deflectionAngleRad = std::atan2(deflectionDir.y, deflectionDir.x);
            trajectory.cueDeflectionEnd = closestGhostCenter + (deflectionDir * 200.0f);

            break; // Finished trajectory at object ball
        }

        // Hit cushion rail
        if (railHit.hit && railHit.distance > 1.0f) {
            TrajectorySegment seg;
            seg.start = currentOrigin;
            seg.end = railHit.hitPoint;
            seg.isCushionBounce = true;
            trajectory.cuePathSegments.push_back(seg);

            // Reflect ray: u_refl = u - 2 * (u . n) * n
            Vec2D reflDir = currentDir - (railHit.normal * (2.0f * currentDir.dot(railHit.normal)));
            currentOrigin = railHit.hitPoint;
            currentDir = reflDir.normalized();
        } else {
            // Ray exits without hitting anything, extend forward
            TrajectorySegment seg;
            seg.start = currentOrigin;
            seg.end = currentOrigin + (currentDir * 500.0f);
            seg.isCushionBounce = false;
            trajectory.cuePathSegments.push_back(seg);
            break;
        }
    }

    return trajectory;
}

} // namespace pool

// ============================================================================
// JNI Exports for NativeCvBridge
// ============================================================================

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_pool_guideline_overlay_cv_NativeCvBridge_computeTrajectoryNative(
    JNIEnv* env,
    jobject /* this */,
    jfloat cueX,
    jfloat cueY,
    jfloat aimDirX,
    jfloat aimDirY,
    jfloatArray targetBallsFlat,
    jfloat tableXMin,
    jfloat tableYMin,
    jfloat tableXMax,
    jfloat tableYMax,
    jfloat ballRadius,
    jint maxBounces
) {
    using namespace pool;

    Vec2D cuePos(cueX, cueY);
    Vec2D aimDir(aimDirX, aimDirY);
    TableRect table{tableXMin, tableYMin, tableXMax, tableYMax};

    std::vector<Ball2D> balls;
    if (targetBallsFlat != nullptr) {
        jsize len = env->GetArrayLength(targetBallsFlat);
        jfloat* ballData = env->GetFloatArrayElements(targetBallsFlat, nullptr);
        if (ballData != nullptr) {
            for (int i = 0; i + 1 < len; i += 2) {
                Ball2D b;
                b.center = Vec2D(ballData[i], ballData[i + 1]);
                b.radius = ballRadius;
                b.id = i / 2;
                balls.push_back(b);
            }
            env->ReleaseFloatArrayElements(targetBallsFlat, ballData, JNI_ABORT);
        }
    }

    CompleteTrajectory traj = computeFullTrajectory(cuePos, aimDir, balls, table, ballRadius, maxBounces);

    // Serialization format:
    // [0]: segment count S
    // [1 .. 4*S]: S * (startX, startY, endX, endY)
    // Next: hasGhostBall (1.0 or 0.0)
    // If hasGhostBall:
    //   ghostX, ghostY, targetX, targetY, targetEndX, targetEndY, cueDefEndX, cueDefEndY, targetAngle, defAngle
    int segCount = static_cast<int>(traj.cuePathSegments.size());
    int totalSize = 1 + (segCount * 4) + 1 + (traj.hasGhostBall ? 10 : 0);

    std::vector<float> outputData(totalSize, 0.0f);
    int idx = 0;
    outputData[idx++] = static_cast<float>(segCount);

    for (const auto& seg : traj.cuePathSegments) {
        outputData[idx++] = seg.start.x;
        outputData[idx++] = seg.start.y;
        outputData[idx++] = seg.end.x;
        outputData[idx++] = seg.end.y;
    }

    outputData[idx++] = traj.hasGhostBall ? 1.0f : 0.0f;
    if (traj.hasGhostBall) {
        outputData[idx++] = traj.ghostBallCenter.x;
        outputData[idx++] = traj.ghostBallCenter.y;
        outputData[idx++] = traj.targetBallCenter.x;
        outputData[idx++] = traj.targetBallCenter.y;
        outputData[idx++] = traj.targetPathEnd.x;
        outputData[idx++] = traj.targetPathEnd.y;
        outputData[idx++] = traj.cueDeflectionEnd.x;
        outputData[idx++] = traj.cueDeflectionEnd.y;
        outputData[idx++] = traj.targetAngleRad;
        outputData[idx++] = traj.deflectionAngleRad;
    }

    jfloatArray result = env->NewFloatArray(totalSize);
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, totalSize, outputData.data());
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_pool_guideline_overlay_cv_NativeCvBridge_isNativeLoaded(
    JNIEnv* /* env */,
    jobject /* this */
) {
    return JNI_TRUE;
}

} // extern "C"
