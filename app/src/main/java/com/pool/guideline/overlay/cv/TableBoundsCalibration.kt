package com.pool.guideline.overlay.cv

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent Table Bounds Storage and Calibration Manager.
 * Returns NULL if the user has not explicitly calibrated the table bounds,
 * ensuring no silently incorrect default rects are used.
 */
object TableBoundsCalibration {

    private const val PREFS_NAME = "pool_table_bounds_calibration"
    private const val KEY_CALIBRATED = "is_table_calibrated"
    private const val KEY_X_MIN = "table_x_min"
    private const val KEY_Y_MIN = "table_y_min"
    private const val KEY_X_MAX = "table_x_max"
    private const val KEY_Y_MAX = "table_y_max"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves calibrated table bounds from persistent storage.
     * Returns NULL if not yet calibrated by the user.
     */
    fun getTableBounds(context: Context): TableBounds? {
        val prefs = getPrefs(context)
        val isCalibrated = prefs.getBoolean(KEY_CALIBRATED, false)
        if (!isCalibrated) {
            return null
        }

        val xMin = prefs.getFloat(KEY_X_MIN, -1f)
        val yMin = prefs.getFloat(KEY_Y_MIN, -1f)
        val xMax = prefs.getFloat(KEY_X_MAX, -1f)
        val yMax = prefs.getFloat(KEY_Y_MAX, -1f)

        if (xMin < 0f || yMin < 0f || xMax <= xMin || yMax <= yMin) {
            return null
        }

        val bounds = TableBounds(xMin = xMin, yMin = yMin, xMax = xMax, yMax = yMax)
        return if (bounds.isValid) bounds else null
    }

    /**
     * Persists user-calibrated 4-boundary table coordinates.
     */
    fun saveTableBounds(context: Context, bounds: TableBounds) {
        if (!bounds.isValid) return
        getPrefs(context).edit()
            .putBoolean(KEY_CALIBRATED, true)
            .putFloat(KEY_X_MIN, bounds.xMin)
            .putFloat(KEY_Y_MIN, bounds.yMin)
            .putFloat(KEY_X_MAX, bounds.xMax)
            .putFloat(KEY_Y_MAX, bounds.yMax)
            .apply()
    }

    /**
     * Clears calibration, reverting state to uncalibrated (getTableBounds returns null).
     */
    fun clearTableBounds(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
