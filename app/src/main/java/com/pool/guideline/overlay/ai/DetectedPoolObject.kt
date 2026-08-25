package com.pool.guideline.overlay.ai

import android.graphics.RectF
import com.pool.guideline.overlay.physics.Vector2D

enum class PoolClass(val id: Int, val label: String) {
    CUE_BALL(0, "cue_ball"),
    OBJECT_BALL(1, "object_ball"),
    CUE_STICK(2, "cue_stick"),
    TARGET_RING(3, "target_ring"),
    POCKET(4, "pocket");

    companion object {
        fun fromId(id: Int): PoolClass = entries.firstOrNull { it.id == id } ?: OBJECT_BALL
    }
}

data class DetectedPoolObject(
    val clazz: PoolClass,
    val confidence: Float,
    val boundingBox: RectF,
    val center: Vector2D,
    val radius: Float = 0f,
    val orientationAngleRad: Float = 0f
)
