package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.Vector2D

enum class BallType {
    CUE,
    OBJECT_SOLID,
    OBJECT_STRIPE,
    EIGHT_BALL,
    UNKNOWN
}

data class BallData(
    val center: Vector2D,
    val radius: Float,
    val type: BallType = BallType.UNKNOWN,
    val confidence: Float = 1.0f,
    val circularity: Float = 1.0f,
    val brightness: Float = 0.0f
)
