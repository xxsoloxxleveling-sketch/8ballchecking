package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.cv.TableBoundsCalibration

/**
 * Interactive calibration and debug settings activity.
 * Allows calibrating and persisting 4-corner table bounds, clearing calibration,
 * and toggling CV debug visualization.
 */
class CalibrationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F141C"))
            setPadding(48, 64, 48, 48)
        }

        val titleText = TextView(this).apply {
            text = "Mock Pool Table Calibration"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        layout.addView(titleText)

        val descText = TextView(this).apply {
            text = "Calibrate table bounds to enable precision raycasting and direction resolution."
            textSize = 13f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 8, 0, 24)
        }
        layout.addView(descText)

        val statusText = TextView(this).apply {
            val bounds = TableBoundsCalibration.getTableBounds(this@CalibrationActivity)
            text = if (bounds != null) {
                "STATUS: ✅ CALIBRATED\n[${bounds.xMin.toInt()}, ${bounds.yMin.toInt()} -> ${bounds.xMax.toInt()}, ${bounds.yMax.toInt()}]"
            } else {
                "STATUS: ⚠️ NOT CALIBRATED\n(Overlay will stay paused until calibrated)"
            }
            textSize = 15f
            setTextColor(if (bounds != null) Color.parseColor("#00E676") else Color.parseColor("#FFAB00"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)

        val calibrateBtn = Button(this).apply {
            text = "🎯 TAP HERE: CALIBRATE STANDARD TABLE"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(24, 32, 24, 32)
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
                statusText.text = "STATUS: ✅ CALIBRATED\n[${bounds.xMin.toInt()}, ${bounds.yMin.toInt()} -> ${bounds.xMax.toInt()}, ${bounds.yMax.toInt()}]"
                statusText.setTextColor(Color.parseColor("#00E676"))
                Toast.makeText(this@CalibrationActivity, "Table Bounds Calibrated & Saved!", Toast.LENGTH_SHORT).show()
            }
        }
        val calParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 24
        }
        layout.addView(calibrateBtn, calParams)

        val clearBtn = Button(this).apply {
            text = "🔄 RESET CALIBRATION"
            setBackgroundColor(Color.parseColor("#1A2230"))
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                TableBoundsCalibration.clearTableBounds(this@CalibrationActivity)
                statusText.text = "STATUS: ⚠️ NOT CALIBRATED\n(Overlay will stay paused until calibrated)"
                statusText.setTextColor(Color.parseColor("#FFAB00"))
                Toast.makeText(this@CalibrationActivity, "Calibration Reset!", Toast.LENGTH_SHORT).show()
            }
        }
        val clearParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 24
        }
        layout.addView(clearBtn, clearParams)

        val prefs = getSharedPreferences("pool_cv_prefs", Context.MODE_PRIVATE)

        val toggleDebugBtn = Button(this).apply {
            text = "DEBUG: Toggle Dual Directions (Green/Red)"
            setBackgroundColor(Color.parseColor("#1A2230"))
            setTextColor(Color.parseColor("#00B0FF"))
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                val current = prefs.getBoolean("debug_cv_mode", false)
                prefs.edit().putBoolean("debug_cv_mode", !current).apply()
                Toast.makeText(this@CalibrationActivity, "Debug CV Mode: ${if (!current) "ENABLED" else "DISABLED"}", Toast.LENGTH_SHORT).show()
            }
        }
        val debugParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 36
        }
        layout.addView(toggleDebugBtn, debugParams)

        val closeBtn = Button(this).apply {
            text = "✅ DONE / RETURN TO GAME"
            setBackgroundColor(Color.parseColor("#00B0FF"))
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(24, 32, 24, 32)
            setOnClickListener {
                finish()
            }
        }
        layout.addView(closeBtn)

        setContentView(layout)
    }
}
