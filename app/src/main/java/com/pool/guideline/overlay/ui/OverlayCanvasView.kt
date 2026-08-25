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
 * High-performance hardware-accelerated transparent overlay view.
 * Renders:
 * 1. Primary cue aiming line + bank reflections
 * 2. Ghost ball contact ring
 * 3. Object ball multi-cushion bank trajectories (zigzagging into pockets)
 * 4. Post-impact cue ball deflection paths
 * 5. Multi-ball break shot projection paths with individual ball colors
 */
class OverlayCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val smoothingFilter = SmoothingFilter(alpha = 0.35f)
    private var currentTrajectory: TrajectoryResult = TrajectoryResult.EMPTY
    private var currentTableBounds: TableBounds = TableBounds.EMPTY

    // Coordinate scaling
    var scaleX: Float = 1.0f
    var scaleY: Float = 1.0f
    var showDebugBounds: Boolean = false
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
        color = Color.argb(240, 255, 255, 255)
        strokeCap = Paint.Cap.ROUND
    }

    private val cushionRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.0f)
        color = Color.argb(190, 120, 230, 255)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
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

    private val multiBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.8f)
        strokeCap = Paint.Cap.ROUND
    }

    private val pocketHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3.5f)
        color = Color.argb(240, 57, 255, 20)
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

    private val scratchRectF = RectF()

    init {
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

        // 1. Draw Cue Pre-Impact Ray Segments
        for (seg in traj.cuePathSegments) {
            val sx = seg.start.x * scaleX
            val sy = seg.start.y * scaleY
            val ex = seg.end.x * scaleX
            val ey = seg.end.y * scaleY
            val paint = if (seg.isCushionBounce) cushionRayPaint else cueRayPaint
            canvas.drawLine(sx, sy, ex, ey, paint)
        }

        // 2. Draw Ghost Ball Ring
        if (traj.hasGhostBall) {
            val gx = traj.ghostBallCenter.x * scaleX
            val gy = traj.ghostBallCenter.y * scaleY
            canvas.drawCircle(gx, gy, radius, ghostBallFillPaint)
            canvas.drawCircle(gx, gy, radius, ghostBallPaint)

            // 3. Draw Object Ball Multi-Cushion Bank Paths (Zigzag to Pocket - Image 1)
            for (seg in traj.targetBallSegments) {
                val sx = seg.start.x * scaleX
                val sy = seg.start.y * scaleY
                val ex = seg.end.x * scaleX
                val ey = seg.end.y * scaleY
                val paint = if (seg.isCushionBounce) targetBankPaint else targetPathPaint
                canvas.drawLine(sx, sy, ex, ey, paint)
            }

            // 4. Draw Post-Impact Cue Ball Deflection Bank Paths (Image 2)
            for (seg in traj.cuePostImpactSegments) {
                val sx = seg.start.x * scaleX
                val sy = seg.start.y * scaleY
                val ex = seg.end.x * scaleX
                val ey = seg.end.y * scaleY
                canvas.drawLine(sx, sy, ex, ey, deflectionPaint)
            }

            // 5. Draw Multi-Ball Break Shot Simulation (Image 3)
            for (ballPath in traj.multiBallPaths) {
                multiBallPaint.color = ballPath.ballColor
                for (seg in ballPath.segments) {
                    val sx = seg.start.x * scaleX
                    val sy = seg.start.y * scaleY
                    val ex = seg.end.x * scaleX
                    val ey = seg.end.y * scaleY
                    canvas.drawLine(sx, sy, ex, ey, multiBallPaint)
                }
            }

            // 6. Pocket Highlight
            traj.bestPocket?.let { pocket ->
                if (traj.pocketScore > 0.25f) {
                    val px = pocket.position.x * scaleX
                    val py = pocket.position.y * scaleY
                    val pRad = pocket.captureRadius * scaleX * (0.85f + 0.2f * traj.pocketScore)
                    canvas.drawCircle(px, py, pRad, pocketHighlightPaint)
                }
            }
        }

        // Debug ROI Bounds
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
