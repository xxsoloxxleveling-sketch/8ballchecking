package com.pool.guideline.overlay.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.cv.TableBoundsCalibration

/**
 * Interactive calibration and debug settings activity.
 * Allows calibrating and persisting 4-corner table bounds, clearing calibration,
 * and toggling CV debug visualization.
 */
class CalibrationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "Mock Pool Table Calibration & Settings"
            textSize = 20f
        }
        layout.addView(titleText)

        val statusText = TextView(this).apply {
            val bounds = TableBoundsCalibration.getTableBounds(this@CalibrationActivity)
            text = if (bounds != null) {
                "Status: Calibrated [${bounds.xMin.toInt()}, ${bounds.yMin.toInt()} -> ${bounds.xMax.toInt()}, ${bounds.yMax.toInt()}]"
            } else {
                "Status: NOT CALIBRATED (Overlay will skip until calibrated)"
            }
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        layout.addView(statusText)

        val calibrateBtn = Button(this).apply {
            text = "Set Standard Table Bounds"
            setOnClickListener {
                val displayMetrics = resources.displayMetrics
                val w = displayMetrics.widthPixels.toFloat()
                val h = displayMetrics.heightPixels.toFloat()
                val bounds = TableBounds(
                    xMin = w * 0.1270f,
                    yMin = h * 0.2922f,
                    xMax = w * 0.8721f,
                    yMax = h * 0.8703f
                )
                TableBoundsCalibration.saveTableBounds(this@CalibrationActivity, bounds)
                statusText.text = "Status: Calibrated [${bounds.xMin.toInt()}, ${bounds.yMin.toInt()} -> ${bounds.xMax.toInt()}, ${bounds.yMax.toInt()}]"
                Toast.makeText(this@CalibrationActivity, "Table Bounds Saved!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(calibrateBtn)

        val clearBtn = Button(this).apply {
            text = "Clear Calibration (Reset to Uncalibrated)"
            setOnClickListener {
                TableBoundsCalibration.clearTableBounds(this@CalibrationActivity)
                statusText.text = "Status: NOT CALIBRATED (Overlay will skip until calibrated)"
                Toast.makeText(this@CalibrationActivity, "Calibration Cleared!", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(clearBtn)

        val prefs = getSharedPreferences("pool_cv_prefs", Context.MODE_PRIVATE)

        val toggleDebugBtn = Button(this).apply {
            text = "Toggle CV Debug Overlay (Dual Directions / Rays)"
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
