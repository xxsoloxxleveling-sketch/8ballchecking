#ifndef POOL_CV_ENGINE_H
#define POOL_CV_ENGINE_H

#include <jni.h>
#include <cmath>
#include <vector>
#include <android/log.h>

#define LOG_TAG "PoolNativeCV"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace pool {

struct Vec2D {
    float x;
    float y;

    Vec2D() : x(0.0f), y(0.0f) {}
    Vec2D(float x_, float y_) : x(x_), y(y_) {}

    inline Vec2D operator+(const Vec2D& o) const { return Vec2D(x + o.x, y + o.y); }
    inline Vec2D operator-(const Vec2D& o) const { return Vec2D(x - o.x, y - o.y); }
    inline Vec2D operator*(float s) const { return Vec2D(x * s, y * s); }
    inline Vec2D operator/(float s) const { return Vec2D(x / s, y / s); }

    inline float dot(const Vec2D& o) const { return x * o.x + y * o.y; }
    inline float cross(const Vec2D& o) const { return x * o.y - y * o.x; }
    inline float lengthSq() const { return x * x + y * y; }
    inline float length() const { return std::sqrt(lengthSq()); }

    inline Vec2D normalized() const {
        float len = length();
        if (len > 1e-6f) {
            return Vec2D(x / len, y / len);
        }
        return Vec2D(0.0f, 0.0f);
    }
};

struct TableRect {
    float xMin;
    float yMin;
    float xMax;
    float yMax;

    bool isValid() const {
        return (xMax > xMin + 50.0f) && (yMax > yMin + 50.0f);
    }
};

struct Ball2D {
    Vec2D center;
    float radius;
    int id;
};

struct RailHit {
    bool hit;
    Vec2D hitPoint;
    Vec2D normal;
    float distance;
};

struct GhostBallHit {
    bool hasCollision;
    Vec2D ghostBallPos;
    Vec2D targetBallPos;
    Vec2D targetNormal;     // Direction of object ball
    Vec2D cueDeflection;    // 90-degree tangent deflection of cue ball
    float distanceToHit;
    int targetBallIndex;
};

struct TrajectorySegment {
    Vec2D start;
    Vec2D end;
    bool isCushionBounce;
};

struct CompleteTrajectory {
    std::vector<TrajectorySegment> cuePathSegments;
    bool hasGhostBall;
    Vec2D ghostBallCenter;
    Vec2D targetBallCenter;
    Vec2D targetPathEnd;
    Vec2D cueDeflectionEnd;
    float targetAngleRad;
    float deflectionAngleRad;
};

// Math & Physics algorithms
bool checkRayCircleCollision(
    const Vec2D& rayOrigin,
    const Vec2D& rayDir,
    const Vec2D& circleCenter,
    float combinedRadius,
    float& outHitDistance,
    Vec2D& outGhostCenter
);

RailHit findFirstRailIntersection(
    const Vec2D& rayOrigin,
    const Vec2D& rayDir,
    const TableRect& table,
    float ballRadius
);

CompleteTrajectory computeFullTrajectory(
    const Vec2D& cueCenter,
    const Vec2D& aimDirection,
    const std::vector<Ball2D>& targetBalls,
    const TableRect& table,
    float ballRadius,
    int maxBounces
);

} // namespace pool

#endif // POOL_CV_ENGINE_H
