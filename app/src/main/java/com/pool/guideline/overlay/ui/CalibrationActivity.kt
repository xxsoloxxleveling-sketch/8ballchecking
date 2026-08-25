package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pool.guideline.overlay.cv.TableBoundsCalibration

/**
 * Interactive Manual Table Bounds Calibration Activity.
 * Provides live margin sliders (Left, Right, Top, Bottom), instant presets (Tablet 16:10, Mobile 16:9, Fullscreen),
 * and a live real-time visual table geometry preview.
 */
class CalibrationActivity : AppCompatActivity() {

    private var fracXMin = 0.0674f
    private var fracYMin = 0.2922f
    private var fracXMax = 0.8721f
    private var fracYMax = 0.8703f

    private lateinit var previewView: TablePreviewView
    private lateinit var tvXMinLabel: TextView
    private lateinit var tvXMaxLabel: TextView
    private lateinit var tvYMinLabel: TextView
    private lateinit var tvYMaxLabel: TextView

    private lateinit var sbXMin: SeekBar
    private lateinit var sbXMax: SeekBar
    private lateinit var sbYMin: SeekBar
    private lateinit var sbYMax: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load existing bounds if available
        val existing = TableBoundsCalibration.getTableBounds(this, 1000, 1000)
        if (existing != null) {
            fracXMin = existing.xMin / 1000f
            fracYMin = existing.yMin / 1000f
            fracXMax = existing.xMax / 1000f
            fracYMax = existing.yMax / 1000f
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F141C"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }
        scroll.addView(root)

        val titleText = TextView(this).apply {
            text = "🎯 Manual Table Board Calibration"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(titleText)

        val subText = TextView(this).apply {
            text = "Adjust the 4 cushion boundaries to match your device screen & table layout."
            textSize = 13f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(0, 6, 0, 18)
        }
        root.addView(subText)

        // Live Table Preview
        previewView = TablePreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            ).apply {
                bottomMargin = 24
            }
        }
        root.addView(previewView)

        // Quick 1-Tap Presets Section
        val presetHeader = TextView(this).apply {
            text = "⚡ QUICK 1-TAP PRESETS"
            textSize = 13f
            setTextColor(Color.parseColor("#00E676"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 8, 0, 12)
        }
        root.addView(presetHeader)

        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }

        // Preset 1: Tablet / Wide Screen (Left 6.7%, Right 87.2%)
        val btnTablet = Button(this).apply {
            text = "📱 TABLET / WIDE"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                setMargins(0.0674f, 0.2922f, 0.8721f, 0.8703f)
                Toast.makeText(this@CalibrationActivity, "Loaded Tablet / Wide Preset!", Toast.LENGTH_SHORT).show()
            }
        }
        presetRow.addView(btnTablet)

        // Preset 2: Standard Mobile (Left 12.7%, Right 87.2%)
        val btnMobile = Button(this).apply {
            text = "📱 MOBILE 16:9"
            setBackgroundColor(Color.parseColor("#1A2230"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 12
            }
            setOnClickListener {
                setMargins(0.1270f, 0.2922f, 0.8721f, 0.8703f)
                Toast.makeText(this@CalibrationActivity, "Loaded Standard Mobile Preset!", Toast.LENGTH_SHORT).show()
            }
        }
        presetRow.addView(btnMobile)

        // Preset 3: Edge-to-Edge
        val btnFull = Button(this).apply {
            text = "📱 FULLSCREEN"
            setBackgroundColor(Color.parseColor("#1A2230"))
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                setMargins(0.0500f, 0.2500f, 0.9500f, 0.9000f)
                Toast.makeText(this@CalibrationActivity, "Loaded Fullscreen Preset!", Toast.LENGTH_SHORT).show()
            }
        }
        presetRow.addView(btnFull)
        root.addView(presetRow)

        // Sliders Card
        val slidersCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A2230"))
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
            }
        }

        // 1. Left Cushion Slider
        tvXMinLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        slidersCard.addView(tvXMinLabel)

        sbXMin = SeekBar(this).apply {
            max = 300 // 0.0% to 30.0%
            progress = (fracXMin * 1000).toInt().coerceIn(0, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fracXMin = p / 1000f
                    updateLabelsAndPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        slidersCard.addView(sbXMin)

        // 2. Right Cushion Slider
        tvXMaxLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        slidersCard.addView(tvXMaxLabel)

        sbXMax = SeekBar(this).apply {
            max = 300 // 70.0% to 100.0%
            progress = ((fracXMax - 0.70f) * 1000).toInt().coerceIn(0, 300)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fracXMax = 0.70f + (p / 1000f)
                    updateLabelsAndPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        slidersCard.addView(sbXMax)

        // 3. Top Cushion Slider
        tvYMinLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        slidersCard.addView(tvYMinLabel)

        sbYMin = SeekBar(this).apply {
            max = 400 // 10.0% to 50.0%
            progress = ((fracYMin - 0.10f) * 1000).toInt().coerceIn(0, 400)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fracYMin = 0.10f + (p / 1000f)
                    updateLabelsAndPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        slidersCard.addView(sbYMin)

        // 4. Bottom Cushion Slider
        tvYMaxLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        slidersCard.addView(tvYMaxLabel)

        sbYMax = SeekBar(this).apply {
            max = 350 // 65.0% to 100.0%
            progress = ((fracYMax - 0.65f) * 1000).toInt().coerceIn(0, 350)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fracYMax = 0.65f + (p / 1000f)
                    updateLabelsAndPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        slidersCard.addView(sbYMax)
        root.addView(slidersCard)

        updateLabelsAndPreview()

        // SAVE BUTTON
        val btnSave = Button(this).apply {
            text = "💾 SAVE & APPLY CALIBRATION"
            setBackgroundColor(Color.parseColor("#00E676"))
            setTextColor(Color.BLACK)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(32, 36, 32, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
            setOnClickListener {
                TableBoundsCalibration.saveTableBoundsNormalized(
                    this@CalibrationActivity,
                    fracXMin = fracXMin,
                    fracYMin = fracYMin,
                    fracXMax = fracXMax,
                    fracYMax = fracYMax
                )
                Toast.makeText(this@CalibrationActivity, "Calibration Saved! Guidelines are now Active.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        root.addView(btnSave)

        val btnReset = Button(this).apply {
            text = "🔄 RESET CALIBRATION"
            setBackgroundColor(Color.parseColor("#1A2230"))
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                TableBoundsCalibration.clearTableBounds(this@CalibrationActivity)
                Toast.makeText(this@CalibrationActivity, "Calibration Cleared!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(btnReset)

        setContentView(scroll)
    }

    private fun setMargins(xMin: Float, yMin: Float, xMax: Float, yMax: Float) {
        fracXMin = xMin
        fracYMin = yMin
        fracXMax = xMax
        fracYMax = yMax

        sbXMin.progress = (fracXMin * 1000).toInt().coerceIn(0, sbXMin.max)
        sbXMax.progress = ((fracXMax - 0.70f) * 1000).toInt().coerceIn(0, sbXMax.max)
        sbYMin.progress = ((fracYMin - 0.10f) * 1000).toInt().coerceIn(0, sbYMin.max)
        sbYMax.progress = ((fracYMax - 0.65f) * 1000).toInt().coerceIn(0, sbYMax.max)

        updateLabelsAndPreview()
    }

    private fun updateLabelsAndPreview() {
        tvXMinLabel.text = "Left Cushion Margin (X-Min): ${(fracXMin * 100).toInt() / 10.0}%"
        tvXMaxLabel.text = "Right Cushion Margin (X-Max): ${(fracXMax * 100).toInt() / 10.0}%"
        tvYMinLabel.text = "Top Cushion Margin (Y-Min): ${(fracYMin * 100).toInt() / 10.0}%"
        tvYMaxLabel.text = "Bottom Cushion Margin (Y-Max): ${(fracYMax * 100).toInt() / 10.0}%"

        previewView.updateBounds(fracXMin, fracYMin, fracXMax, fracYMax)
    }

    inner class TablePreviewView(context: Context) : View(context) {
        private var x1 = 0.0674f
        private var y1 = 0.2922f
        private var x2 = 0.8721f
        private var y2 = 0.8703f

        private val bgPaint = Paint().apply { color = Color.parseColor("#0A0E14") }
        private val feltPaint = Paint().apply { color = Color.parseColor("#0288D1") }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val pocketPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }

        fun updateBounds(xMin: Float, yMin: Float, xMax: Float, yMax: Float) {
            x1 = xMin
            y1 = yMin
            x2 = xMax
            y2 = yMax
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // Outer Frame
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            // Playfield Felt
            val rx1 = x1 * w
            val ry1 = y1 * h
            val rx2 = x2 * w
            val ry2 = y2 * h
            canvas.drawRect(rx1, ry1, rx2, ry2, feltPaint)
            canvas.drawRect(rx1, ry1, rx2, ry2, borderPaint)

            // Pockets
            val pr = 12f
            canvas.drawCircle(rx1, ry1, pr, pocketPaint)
            canvas.drawCircle((rx1 + rx2) / 2f, ry1, pr, pocketPaint)
            canvas.drawCircle(rx2, ry1, pr, pocketPaint)
            canvas.drawCircle(rx1, ry2, pr, pocketPaint)
            canvas.drawCircle((rx1 + rx2) / 2f, ry2, pr, pocketPaint)
            canvas.drawCircle(rx2, ry2, pr, pocketPaint)

            canvas.drawText("LIVE TABLE PLAYFIELD PREVIEW", w / 2f, h / 2f + 8f, labelPaint)
        }
    }
}
