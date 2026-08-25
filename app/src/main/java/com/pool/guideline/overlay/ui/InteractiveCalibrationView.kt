package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.pool.guideline.overlay.cv.TableBoundsCalibration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Interactive In-Game 4-Corner Calibration View.
 * Renders 4 draggable corner handles (TL, TR, BL, BR) directly over the active game screen.
 * Computes exact measured pixel coordinates and persists normalized fractions.
 */
class InteractiveCalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCalibrationSavedListener: (() -> Unit)? = null
    var onCalibrationCancelledListener: (() -> Unit)? = null

    // 4 Corner Handles (in local view pixel coordinates)
    private var handleTL = PointF(0f, 0f)
    private var handleTR = PointF(0f, 0f)
    private var handleBL = PointF(0f, 0f)
    private var handleBR = PointF(0f, 0f)

    private var activeHandleIndex = -1
    private val handleTouchRadius = dpToPx(36f)
    private var isInitialized = false

    // Button Bounding Boxes
    private val saveBtnRect = RectF()
    private val cancelBtnRect = RectF()

    // ------------------------------------------------------------------------
    // Paints
    // ------------------------------------------------------------------------

    private val tableOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118) // Bright green
        style = Paint.Style.FILL
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD600") // Vivid Amber
        style = Paint.Style.FILL
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(13f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val btnSaveBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.FILL
    }

    private val btnCancelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }

    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dpToPx(14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && !isInitialized) {
            initHandles(w.toFloat(), h.toFloat())
            isInitialized = true
        }
    }

    fun initHandles(w: Float, h: Float) {
        // Load existing bounds if present, or use default wide-screen bounds
        val existing = TableBoundsCalibration.getTableBounds(context, w.toInt(), h.toInt())
        if (existing != null && existing.isValid) {
            handleTL.set(existing.xMin, existing.yMin)
            handleTR.set(existing.xMax, existing.yMin)
            handleBL.set(existing.xMin, existing.yMax)
            handleBR.set(existing.xMax, existing.yMax)
        } else {
            handleTL.set(w * 0.08f, h * 0.28f)
            handleTR.set(w * 0.88f, h * 0.28f)
            handleBL.set(w * 0.08f, h * 0.88f)
            handleBR.set(w * 0.88f, h * 0.88f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val minX = min(handleTL.x, handleBL.x)
        val maxX = max(handleTR.x, handleBR.x)
        val minY = min(handleTL.y, handleTR.y)
        val maxY = max(handleBL.y, handleBR.y)

        // 1. Draw Table Boundary Rect
        canvas.drawRect(minX, minY, maxX, maxY, tableOutlinePaint)

        // 2. Draw 6 Pocket Indicators
        val midX = (minX + maxX) / 2f
        val pocketRad = dpToPx(8f)
        canvas.drawCircle(minX, minY, pocketRad, pocketPaint)
        canvas.drawCircle(midX, minY, pocketRad, pocketPaint)
        canvas.drawCircle(maxX, minY, pocketRad, pocketPaint)
        canvas.drawCircle(minX, maxY, pocketRad, pocketPaint)
        canvas.drawCircle(midX, maxY, pocketRad, pocketPaint)
        canvas.drawCircle(maxX, maxY, pocketRad, pocketPaint)

        // 3. Draw 4 Draggable Handles
        drawHandle(canvas, handleTL, "TL")
        drawHandle(canvas, handleTR, "TR")
        drawHandle(canvas, handleBL, "BL")
        drawHandle(canvas, handleBR, "BR")

        // 4. Draw Floating Action Buttons at Top
        val btnW = dpToPx(140f)
        val btnH = dpToPx(44f)
        val topMargin = dpToPx(16f)
        val centerX = w / 2f

        saveBtnRect.set(centerX - btnW - dpToPx(8f), topMargin, centerX - dpToPx(8f), topMargin + btnH)
        cancelBtnRect.set(centerX + dpToPx(8f), topMargin, centerX + btnW + dpToPx(8f), topMargin + btnH)

        canvas.drawRoundRect(saveBtnRect, dpToPx(8f), dpToPx(8f), btnSaveBgPaint)
        canvas.drawText("💾 SAVE BOUNDS", saveBtnRect.centerX(), saveBtnRect.centerY() + dpToPx(5f), btnTextPaint)

        canvas.drawRoundRect(cancelBtnRect, dpToPx(8f), dpToPx(8f), btnCancelBgPaint)
        canvas.drawText("✖ CANCEL", cancelBtnRect.centerX(), cancelBtnRect.centerY() + dpToPx(5f), btnTextPaint)

        // Helper instruction text
        canvas.drawText("Drag the 4 corner handles onto your table cushions, then tap SAVE", centerX, topMargin + btnH + dpToPx(20f), textPaint)
    }

    private fun drawHandle(canvas: Canvas, pt: PointF, label: String) {
        val rad = dpToPx(16f)
        canvas.drawCircle(pt.x, pt.y, rad, handleFillPaint)
        canvas.drawCircle(pt.x, pt.y, rad, handleStrokePaint)
        canvas.drawText(label, pt.x, pt.y + dpToPx(4f), btnTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if Save button tapped
                if (saveBtnRect.contains(x, y)) {
                    saveCalibration()
                    return true
                }
                // Check if Cancel button tapped
                if (cancelBtnRect.contains(x, y)) {
                    onCalibrationCancelledListener?.invoke()
                    return true
                }

                // Check which handle is closest
                activeHandleIndex = getClosestHandleIndex(x, y)
                return activeHandleIndex != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandleIndex != -1) {
                    val w = width.toFloat()
                    val h = height.toFloat()
                    val clampedX = x.coerceIn(0f, w)
                    val clampedY = y.coerceIn(0f, h)

                    when (activeHandleIndex) {
                        0 -> { // TL
                            handleTL.set(clampedX, clampedY)
                            handleTR.y = clampedY
                            handleBL.x = clampedX
                        }
                        1 -> { // TR
                            handleTR.set(clampedX, clampedY)
                            handleTL.y = clampedY
                            handleBR.x = clampedX
                        }
                        2 -> { // BL
                            handleBL.set(clampedX, clampedY)
                            handleTL.x = clampedX
                            handleBR.y = clampedY
                        }
                        3 -> { // BR
                            handleBR.set(clampedX, clampedY)
                            handleTR.x = clampedX
                            handleBL.y = clampedY
                        }
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandleIndex = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getClosestHandleIndex(x: Float, y: Float): Int {
        val handles = arrayOf(handleTL, handleTR, handleBL, handleBR)
        var closestIdx = -1
        var minDSq = handleTouchRadius * handleTouchRadius

        for (i in handles.indices) {
            val dx = handles[i].x - x
            val dy = handles[i].y - y
            val dSq = dx * dx + dy * dy
            if (dSq < minDSq) {
                minDSq = dSq
                closestIdx = i
            }
        }
        return closestIdx
    }

    private fun saveCalibration() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val minX = min(handleTL.x, handleBL.x)
        val maxX = max(handleTR.x, handleBR.x)
        val minY = min(handleTL.y, handleTR.y)
        val maxY = max(handleBL.y, handleBR.y)

        val fracXMin = minX / w
        val fracYMin = minY / h
        val fracXMax = maxX / w
        val fracYMax = maxY / h

        TableBoundsCalibration.saveTableBoundsNormalized(
            context,
            fracXMin = fracXMin,
            fracYMin = fracYMin,
            fracXMax = fracXMax,
            fracYMax = fracYMax
        )
        onCalibrationSavedListener?.invoke()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
