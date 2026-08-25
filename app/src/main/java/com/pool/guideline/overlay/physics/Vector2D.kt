package com.pool.guideline.overlay.physics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance 2D Vector representation for geometric calculations.
 */
data class Vector2D(val x: Float = 0f, val y: Float = 0f) {

    operator fun plus(other: Vector2D): Vector2D = Vector2D(x + other.x, y + other.y)
    operator fun minus(other: Vector2D): Vector2D = Vector2D(x - other.x, y - other.y)
    operator fun times(scalar: Float): Vector2D = Vector2D(x * scalar, y * scalar)
    operator fun div(scalar: Float): Vector2D = if (scalar != 0f) Vector2D(x / scalar, y / scalar) else ZERO

    fun dot(other: Vector2D): Float = x * other.x + y * other.y

    fun cross(other: Vector2D): Float = x * other.y - y * other.x

    fun lengthSq(): Float = x * x + y * y

    fun length(): Float = sqrt(lengthSq())

    fun distanceTo(other: Vector2D): Float = (this - other).length()

    fun distanceSqTo(other: Vector2D): Float = (this - other).lengthSq()

    fun normalized(): Vector2D {
        val len = length()
        return if (len > 1e-6f) Vector2D(x / len, y / len) else ZERO
    }

    /**
     * Reflects this vector across a surface normal vector (which must be normalized).
     * Formula: v_refl = v - 2 * (v . n) * n
     */
    fun reflect(normal: Vector2D): Vector2D {
        val d = 2.0f * this.dot(normal)
        return this - (normal * d)
    }

    /**
     * Returns a perpendicular vector (rotated 90 degrees counter-clockwise).
     */
    fun perpendicularCCW(): Vector2D = Vector2D(-y, x)

    /**
     * Returns a perpendicular vector (rotated 90 degrees clockwise).
     */
    fun perpendicularCW(): Vector2D = Vector2D(y, -x)

    /**
     * Calculates angle in radians relative to the positive X axis [-PI, PI].
     */
    fun angle(): Float = atan2(y, x)

    companion object {
        val ZERO = Vector2D(0f, 0f)
        val UNIT_X = Vector2D(1f, 0f)
        val UNIT_Y = Vector2D(0f, 1f)

        fun fromAngle(radians: Float, length: Float = 1.0f): Vector2D {
            return Vector2D(cos(radians) * length, sin(radians) * length)
        }
    }
}
