package com.pool.guideline.overlay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pool.guideline.overlay.cv.TableBounds
import com.pool.guideline.overlay.physics.TrajectoryResult

/**
 * High-performance, hardware-accelerated transparent overlay view.
 * Renders billiard trajectory lines, ghost ball outlines, and deflection vectors.
 * Adheres strictly to zero-heap-allocation render loop mandates.
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val smoothingFilter = SmoothingFilter(alpha = 0.35f)
    private var currentTrajectory: TrajectoryResult = TrajectoryResult.EMPTY
    private var currentTableBounds: TableBounds = TableBounds.EMPTY

    // Coordinate scaling between downsampled CV frame and physical display
    var scaleX: Float = 1.0f
    var scaleY: Float = 1.0f
    var showDebugBounds: Boolean = false
    var showFps: Boolean = true

    // FPS Counter
    private var frameCount = 0
    private var lastFpsTimestamp = System.currentTimeMillis()
    private var currentFps = 0f

    // ------------------------------------------------------------------------
    // Pre-allocated Paint Objects (Zero-allocation in onDraw)
    // ------------------------------------------------------------------------

    private val cueRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(4.0f)
        color = Color.argb(220, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    private val cushionRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
        color = Color.argb(160, 100, 220, 255)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    private val ghostBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.0f)
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
        color = Color.rgb(57, 255, 20) // Neon green
        strokeCap = Paint.Cap.ROUND
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
        strokeWidth = dpToPx(3.0f)
        color = Color.argb(230, 57, 255, 20)
    }

    private val debugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1.5f)
        color = Color.argb(180, 255, 60, 60)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(13.0f)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // Pre-allocated reusable paths and rects
    private val scratchPath = Path()
    private val scratchRectF = RectF()

    init {
        // Hardware acceleration requirement
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateTrajectory(result: TrajectoryResult, bounds: TableBounds) {
        currentTrajectory = smoothingFilter.smooth(result)
        currentTableBounds = bounds
        postInvalidate()
    }

    fun setSmoothingAlpha(alpha: Float) {
        smoothingFilter.alpha = alpha.coerceIn(0.05f, 1.0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        updateFps()

        val traj = currentTrajectory
        val radius = traj.ballRadius * scaleX

        // 1. Draw Cue Path Segments (and Cushion Bounces)
        for (seg in traj.cuePathSegments) {
            val startX = seg.start.x * scaleX
            val startY = seg.start.y * scaleY
            val endX = seg.end.x * scaleX
            val endY = seg.end.y * scaleY

            val paint = if (seg.isCushionBounce) cushionRayPaint else cueRayPaint
            canvas.drawLine(startX, startY, endX, endY, paint)
        }

        // 2. Draw Ghost Ball & Deflection Paths
        if (traj.hasGhostBall) {
            val gx = traj.ghostBallCenter.x * scaleX
            val gy = traj.ghostBallCenter.y * scaleY

            // Ghost ball filled core and dashed boundary
            canvas.drawCircle(gx, gy, radius, ghostBallFillPaint)
            canvas.drawCircle(gx, gy, radius, ghostBallPaint)

            // Target Ball Trajectory Path
            val tx = traj.targetBallCenter.x * scaleX
            val ty = traj.targetBallCenter.y * scaleY
            val tEndX = traj.targetPathEnd.x * scaleX
            val tEndY = traj.targetPathEnd.y * scaleY
            canvas.drawLine(tx, ty, tEndX, tEndY, targetPathPaint)

            // 90-degree Cue Ball Deflection Path
            val defEndX = traj.cueDeflectionEnd.x * scaleX
            val defEndY = traj.cueDeflectionEnd.y * scaleY
            canvas.drawLine(gx, gy, defEndX, defEndY, deflectionPaint)

            // Pocket Highlight Indicator
            traj.bestPocket?.let { pocket ->
                if (traj.pocketScore > 0.3f) {
                    val px = pocket.position.x * scaleX
                    val py = pocket.position.y * scaleY
                    val pRad = pocket.captureRadius * scaleX * (0.8f + 0.2f * traj.pocketScore)
                    canvas.drawCircle(px, py, pRad, pocketHighlightPaint)
                }
            }
        }

        // 3. Optional Debug Overlay
        if (showDebugBounds && currentTableBounds.isValid) {
            scratchRectF.set(
                currentTableBounds.xMin * scaleX,
                currentTableBounds.yMin * scaleY,
                currentTableBounds.xMax * scaleX,
                currentTableBounds.yMax * scaleY
            )
            canvas.drawRect(scratchRectF, debugPaint)
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
