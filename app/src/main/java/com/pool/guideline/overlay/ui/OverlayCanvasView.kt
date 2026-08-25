package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pool.guideline.overlay.cv.BallData
import com.pool.guideline.overlay.cv.RawContourData
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.physics.TrajectoryResult
import com.pool.guideline.overlay.physics.Vector2D

/**
 * High-performance hardware-accelerated transparent overlay view.
 * Renders:
 * 1. Primary cue aiming line
 * 2. Ghost ball contact ring
 * 3. Object ball multi-cushion bank trajectory into pockets
 * 4. Post-impact cue ball deflection tangent line
 * 5. Debug Dual-Direction Mode: Green (Forward/Chosen), Red (Backward/Rejected)
 * 6. Prompts calibration if table bounds are not calibrated
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val smoothingFilter = SmoothingFilter(alpha = 0.35f)
    private var currentTrajectory: TrajectoryResult = TrajectoryResult.EMPTY
    private var currentTableBounds: TableBounds = TableBounds.EMPTY

    private var debugRawContours: List<RawContourData> = emptyList()
    private var debugAcceptedBalls: List<BallData> = emptyList()

    var isTableCalibrated: Boolean = true

    // Debug polarity direction vectors
    var debugChosenForward: Vector2D = Vector2D.ZERO
    var debugRejectedBackward: Vector2D = Vector2D.ZERO

    // Coordinate scaling
    var coordScaleX: Float = 1.0f
    var coordScaleY: Float = 1.0f
    var showDebugBounds: Boolean = false
    var debugCvMode: Boolean = false
    var showFps: Boolean = true

    // FPS Counter
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0f

    // ------------------------------------------------------------------------
    // Pre-allocated Paint Objects
    // ------------------------------------------------------------------------

    private val cueRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.8f)
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.2f)
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    private val ghostBallFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(45, 255, 255, 255)
    }

    private val targetPathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
        color = Color.rgb(57, 255, 20) // Neon lime green
        strokeCap = Paint.Cap.ROUND
    }

    private val targetBankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
        color = Color.rgb(0, 230, 118) // Bright green bank ray
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
    }

    private val deflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        color = Color.rgb(255, 179, 0) // Amber/Gold
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val pocketHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
        color = Color.argb(240, 57, 255, 20)
    }

    private val debugTablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
        color = Color.CYAN
    }

    private val debugForwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
        color = Color.GREEN
        strokeCap = Paint.Cap.ROUND
    }

    private val debugBackwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
        color = Color.RED
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val rawContourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
        color = Color.RED
    }

    private val acceptedBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
        color = Color.GREEN
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(12.0f)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val warningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = dpToPx(14.0f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 2f, 2f, Color.BLACK)
    }

    private val scratchRectF = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateTrajectory(
        result: TrajectoryResult,
        bounds: TableBounds,
        rawContours: List<RawContourData> = emptyList(),
        acceptedBalls: List<BallData> = emptyList()
    ) {
        currentTrajectory = smoothingFilter.smooth(result)
        currentTableBounds = bounds
        debugRawContours = rawContours
        debugAcceptedBalls = acceptedBalls
        postInvalidate()
    }

    fun setSmoothingAlpha(alpha: Float) {
        smoothingFilter.alpha = alpha.coerceIn(0.05f, 1.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        updateFps()

        // If table is not calibrated, display prompt and do not render trajectory
        if (!isTableCalibrated) {
            val midX = width / 2.0f
            val midY = height * 0.15f
            canvas.drawText("Table not calibrated — open app or notification to calibrate", midX, midY, warningTextPaint)
            if (showFps) {
                canvas.drawText("AI Guide FPS: ${currentFps.toInt()}", 40f, 80f, textPaint)
            }
            return
        }

        val traj = currentTrajectory
        val radius = traj.ballRadius * coordScaleX

        // 1. Always Draw Calibrated Table Bounds Outline (Yellow) if Debug is Enabled
        if ((showDebugBounds || debugCvMode) && currentTableBounds.isValid) {
            val left = currentTableBounds.xMin * coordScaleX
            val top = currentTableBounds.yMin * coordScaleY
            val right = currentTableBounds.xMax * coordScaleX
            val bottom = currentTableBounds.yMax * coordScaleY
            scratchRectF.set(left, top, right, bottom)
            canvas.drawRect(scratchRectF, debugTablePaint)

            val pr = dpToPx(6f)
            val midX = (left + right) / 2f
            canvas.drawCircle(left, top, pr, pocketHighlightPaint)
            canvas.drawCircle(midX, top, pr, pocketHighlightPaint)
            canvas.drawCircle(right, top, pr, pocketHighlightPaint)
            canvas.drawCircle(left, bottom, pr, pocketHighlightPaint)
            canvas.drawCircle(midX, bottom, pr, pocketHighlightPaint)
            canvas.drawCircle(right, bottom, pr, pocketHighlightPaint)
        }

        // 2. Draw Cue Pre-Impact Ray
        for (seg in traj.cuePathSegments) {
            val sx = seg.start.x * coordScaleX
            val sy = seg.start.y * coordScaleY
            val ex = seg.end.x * coordScaleX
            val ey = seg.end.y * coordScaleY
            canvas.drawLine(sx, sy, ex, ey, cueRayPaint)
        }

        // 3. Draw Ghost Ball Ring & Target Trajectory
        if (traj.hasGhostBall) {
            val gx = traj.ghostBallCenter.x * coordScaleX
            val gy = traj.ghostBallCenter.y * coordScaleY
            canvas.drawCircle(gx, gy, radius, ghostBallFillPaint)
            canvas.drawCircle(gx, gy, radius, ghostBallPaint)

            // Object Ball Multi-Cushion Bank Path
            for (seg in traj.targetBallSegments) {
                val sx = seg.start.x * coordScaleX
                val sy = seg.start.y * coordScaleY
                val ex = seg.end.x * coordScaleX
                val ey = seg.end.y * coordScaleY
                val paint = if (seg.isCushionBounce) targetBankPaint else targetPathPaint
                canvas.drawLine(sx, sy, ex, ey, paint)
            }

            // Tangent Cue Ball Deflection
            for (seg in traj.cuePostImpactSegments) {
                val sx = seg.start.x * coordScaleX
                val sy = seg.start.y * coordScaleY
                val ex = seg.end.x * coordScaleX
                val ey = seg.end.y * coordScaleY
                canvas.drawLine(sx, sy, ex, ey, deflectionPaint)
            }

            // Pocket Highlight
            traj.bestPocket?.let { pocket ->
                if (traj.pocketScore > 0.20f) {
                    val px = pocket.position.x * coordScaleX
                    val py = pocket.position.y * coordScaleY
                    val pRad = pocket.captureRadius * coordScaleX
                    canvas.drawCircle(px, py, pRad, pocketHighlightPaint)
                }
            }
        }

        // 4. Debug Candidate Direction Vectors: Green (Forward/Chosen), Red (Backward/Rejected)
        if ((debugCvMode || showDebugBounds) && debugChosenForward.lengthSq() > 0.1f && traj.cuePathSegments.isNotEmpty()) {
            val origin = traj.cuePathSegments[0].start
            val cx = origin.x * coordScaleX
            val cy = origin.y * coordScaleY

            val fwdEndX = cx + debugChosenForward.x * dpToPx(60f)
            val fwdEndY = cy + debugChosenForward.y * dpToPx(60f)
            canvas.drawLine(cx, cy, fwdEndX, fwdEndY, debugForwardPaint)

            if (debugRejectedBackward.lengthSq() > 0.1f) {
                val bwdEndX = cx + debugRejectedBackward.x * dpToPx(60f)
                val bwdEndY = cy + debugRejectedBackward.y * dpToPx(60f)
                canvas.drawLine(cx, cy, bwdEndX, bwdEndY, debugBackwardPaint)
            }
        }

        // 5. CV Debug Contours
        if (debugCvMode) {
            for (c in debugRawContours) {
                if (!c.isAccepted) {
                    canvas.drawCircle(c.center.x * coordScaleX, c.center.y * coordScaleY, c.radius * coordScaleX, rawContourPaint)
                }
            }

            for (b in debugAcceptedBalls) {
                val bx = b.center.x * coordScaleX
                val by = b.center.y * coordScaleY
                val br = b.radius * coordScaleX
                canvas.drawCircle(bx, by, br, acceptedBallPaint)
                canvas.drawText("C:${String.format("%.2f", b.circularity)}", bx - br, by - br - 4f, textPaint)
            }
        }

        if (showFps) {
            canvas.drawText("AI Guide FPS: ${currentFps.toInt()}", 40f, 80f, textPaint)
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val delta = now - lastFpsTimestamp
        if (delta >= 1000) {
            currentFps = (frameCount * 1000f) / delta
            frameCount = 0
            lastFpsTimestamp = now
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
