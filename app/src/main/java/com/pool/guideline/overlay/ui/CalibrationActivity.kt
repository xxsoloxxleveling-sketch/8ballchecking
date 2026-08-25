package com.pool.guideline.overlay.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Minimal calibration and debug settings activity.
 * Allows live felt HSV calibration, toggling CV debug visualization, and adjusting smoothing sensitivity.
 */
class CalibrationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "8-Ball CV Calibration & Settings"
            textSize = 20f
        }
        layout.addView(titleText)

        val prefs = getSharedPreferences("pool_cv_prefs", Context.MODE_PRIVATE)

        val recalibrateBtn = Button(this).apply {
            text = "Auto-Calibrate Table Felt HSV"
            setOnClickListener {
                prefs.edit().putBoolean("force_recalibrate", true).apply()
                Toast.makeText(this@CalibrationActivity, "Table felt will auto-sample on next frame", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(recalibrateBtn)

        val toggleDebugBtn = Button(this).apply {
            text = "Toggle CV Debug Overlay (Contours/Scores)"
            setOnClickListener {
                val current = prefs.getBoolean("debug_cv_mode", false)
                prefs.edit().putBoolean("debug_cv_mode", !current).apply()
                Toast.makeText(this@CalibrationActivity, "Debug CV Mode: ${if (!current) "ENABLED" else "DISABLED"}", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(toggleDebugBtn)

        setContentView(layout)
    }
}
