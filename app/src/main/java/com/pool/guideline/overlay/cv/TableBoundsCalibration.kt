package com.pool.guideline.overlay.cv

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent Table Bounds Storage and Calibration Manager.
 * Stores bounds as normalized coordinate fractions (0.0..1.0) so that calibration
 * applies with 100% mathematical precision across all resolutions (Screen 1920x1080, CV 640x360).
 */
object TableBoundsCalibration {

    private const val PREFS_NAME = "pool_table_bounds_calibration"
    private const val KEY_CALIBRATED = "is_table_calibrated"
    private const val KEY_FRAC_X_MIN = "table_frac_x_min"
    private const val KEY_FRAC_Y_MIN = "table_frac_y_min"
    private const val KEY_FRAC_X_MAX = "table_frac_x_max"
    private const val KEY_FRAC_Y_MAX = "table_frac_y_max"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isCalibrated(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_CALIBRATED, false)
    }

    /**
     * Retrieves calibrated table bounds scaled to the given frame resolution.
     * Returns NULL if not yet calibrated by the user.
     */
    fun getTableBounds(context: Context, width: Int = 1000, height: Int = 600): TableBounds? {
        val prefs = getPrefs(context)
        val isCalibrated = prefs.getBoolean(KEY_CALIBRATED, false)
        if (!isCalibrated) {
            return null
        }

        val fracXMin = prefs.getFloat(KEY_FRAC_X_MIN, -1f)
        val fracYMin = prefs.getFloat(KEY_FRAC_Y_MIN, -1f)
        val fracXMax = prefs.getFloat(KEY_FRAC_X_MAX, -1f)
        val fracYMax = prefs.getFloat(KEY_FRAC_Y_MAX, -1f)

        if (fracXMin < 0f || fracYMin < 0f || fracXMax <= fracXMin || fracYMax <= fracYMin) {
            return null
        }

        val bounds = TableBounds(
            xMin = fracXMin * width,
            yMin = fracYMin * height,
            xMax = fracXMax * width,
            yMax = fracYMax * height
        )
        return if (bounds.isValid) bounds else null
    }

    /**
     * Persists normalized table bounds (fractions 0.0 .. 1.0).
     */
    fun saveTableBoundsNormalized(
        context: Context,
        fracXMin: Float,
        fracYMin: Float,
        fracXMax: Float,
        fracYMax: Float
    ) {
        getPrefs(context).edit()
            .putBoolean(KEY_CALIBRATED, true)
            .putFloat(KEY_FRAC_X_MIN, fracXMin)
            .putFloat(KEY_FRAC_Y_MIN, fracYMin)
            .putFloat(KEY_FRAC_X_MAX, fracXMax)
            .putFloat(KEY_FRAC_Y_MAX, fracYMax)
            .apply()
    }

    fun saveTableBounds(context: Context, bounds: TableBounds, referenceWidth: Float, referenceHeight: Float) {
        if (!bounds.isValid || referenceWidth <= 0f || referenceHeight <= 0f) return
        saveTableBoundsNormalized(
            context,
            bounds.xMin / referenceWidth,
            bounds.yMin / referenceHeight,
            bounds.xMax / referenceWidth,
            bounds.yMax / referenceHeight
        )
    }

    /**
     * Clears calibration, reverting state to uncalibrated.
     */
    fun clearTableBounds(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
