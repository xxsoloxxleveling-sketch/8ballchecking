package com.pool.guideline.overlay.cv

import android.util.Log

object NativeCvBridge {
    private const val TAG = "NativeCvBridge"
    private var isLoaded = false

    init {
        try {
            System.loadLibrary("pool_cv_engine")
            isLoaded = isNativeLoaded()
            Log.i(TAG, "Native C++ OpenCV & Physics library loaded successfully. isLoaded=$isLoaded")
        } catch (t: Throwable) {
            Log.w(TAG, "Native library pool_cv_engine not loaded, using Kotlin engine: ${t.message}")
            isLoaded = false
        }
    }

    fun hasNativeAcceleration(): Boolean = isLoaded

    external fun isNativeLoaded(): Boolean

    external fun computeTrajectoryNative(
        cueX: Float,
        cueY: Float,
        aimDirX: Float,
        aimDirY: Float,
        targetBallsFlat: FloatArray?,
        tableXMin: Float,
        tableYMin: Float,
        tableXMax: Float,
        tableYMax: Float,
        ballRadius: Float,
        maxBounces: Int
    ): FloatArray?
}
