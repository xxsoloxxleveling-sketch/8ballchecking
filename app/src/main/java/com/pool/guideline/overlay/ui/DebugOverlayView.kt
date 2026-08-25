package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.RawContourData
import com.pool.guideline.overlay.cv.TableBounds

/**
 * Real-time CV Debug Overlay View.
 * Renders:
 * - Table bounding box (cyan outline)
 * - Raw contour candidates (red outline)
 * - Accepted balls (green outline + circularity score text)
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var coordScaleX: Float = 1.0f
    var coordScaleY: Float = 1.0f

    private var tableBounds: TableBounds = TableBounds.EMPTY
    private var rawContours: List<RawContourData> = emptyList()
    private var acceptedBalls: List<BallData> = emptyList()

    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        color = Color.CYAN
    }

    private val rawContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.RED
    }

    private val acceptedBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        color = Color.GREEN
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24.0f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val scratchRect = RectF()

    fun updateDebugData(
        bounds: TableBounds,
        raw: List<RawContourData>,
        accepted: List<BallData>
    ) {
        this.tableBounds = bounds
        this.rawContours = raw
        this.acceptedBalls = accepted
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (tableBounds.isValid) {
            scratchRect.set(
                tableBounds.xMin * coordScaleX,
                tableBounds.yMin * coordScaleY,
                tableBounds.xMax * coordScaleX,
                tableBounds.yMax * coordScaleY
            )
            canvas.drawRect(scratchRect, tablePaint)
        }

        // Draw Raw Contours (Red)
        for (c in rawContours) {
            if (!c.isAccepted) {
                val cx = c.center.x * coordScaleX
                val cy = c.center.y * coordScaleY
                val r = c.radius * coordScaleX
                canvas.drawCircle(cx, cy, r, rawContourPaint)
            }
        }

        // Draw Accepted Balls (Green + Score)
        for (b in acceptedBalls) {
            val cx = b.center.x * coordScaleX
            val cy = b.center.y * coordScaleY
            val r = b.radius * coordScaleX
            canvas.drawCircle(cx, cy, r, acceptedBallPaint)
            canvas.drawText(
                "C:${String.format("%.2f", b.circularity)}",
                cx - r,
                cy - r - 6f,
                textPaint
            )
        }
    }
}
