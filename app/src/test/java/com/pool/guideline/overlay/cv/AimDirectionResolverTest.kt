package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.TrajectoryPhysicsEngine
import com.pool.guideline.overlay.physics.Vector2D
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimDirectionResolverTest {

    private val tableBounds = TableBounds(
        xMin = 130f,
        yMin = 187f,
        xMax = 893f,
        yMax = 557f
    )
    private val width = 1024
    private val height = 640

    private fun createFrameWithStick(cue: Vector2D, stickDir: Vector2D): IntArray {
        val pixels = IntArray(width * height) { 0xFF004466.toInt() } // Table felt background
        val u = stickDir.normalized()
        for (d in 10..120) {
            val px = (cue.x + u.x * d).toInt().coerceIn(0, width - 1)
            val py = (cue.y + u.y * d).toInt().coerceIn(0, height - 1)
            // Inject wood / leather texture (R: 160, G: 110, B: 55)
            pixels[py * width + px] = (0xFF shl 24) or (160 shl 16) or (110 shl 8) or 55
        }
        return pixels
    }

    @Test
    fun testCase1_media746_upLeftAim() {
        val cueBall = Vector2D(645f, 420f)
        val unorientedAxis = Vector2D(0.91f, 0.42f) // axis along stick (down-right) / aim (up-left)
        val stickDir = Vector2D(0.91f, 0.42f) // stick extends down-right

        val pixels = createFrameWithStick(cueBall, stickDir)

        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = unorientedAxis,
            tableBounds = tableBounds,
            pixels = pixels,
            width = width,
            height = height
        )

        assertNotNull("Resolved aim should not be null", resolved)
        // Must resolve to forward up-left: negative x and negative y
        assertTrue("Forward x must be negative", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be negative", resolved.forwardDir.y < 0f)
        assertTrue("Direction must be flipped from stick", resolved.isFlipped)
    }

    @Test
    fun testCase2_media361_downLeftAim() {
        val cueBall = Vector2D(645f, 420f)
        val unorientedAxis = Vector2D(0.74f, -0.67f) // axis along stick (up-right) / aim (down-left)
        val stickDir = Vector2D(0.74f, -0.67f) // stick extends up-right

        val pixels = createFrameWithStick(cueBall, stickDir)

        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = unorientedAxis,
            tableBounds = tableBounds,
            pixels = pixels,
            width = width,
            height = height
        )

        assertNotNull("Resolved aim should not be null", resolved)
        // Must resolve to forward down-left: negative x and positive y
        assertTrue("Forward x must be negative", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be positive", resolved.forwardDir.y > 0f)
        assertTrue("Direction must be flipped from stick", resolved.isFlipped)
    }

    @Test
    fun testCase3_media016_downRightAim() {
        val cueBall = Vector2D(420f, 350f)
        val unorientedAxis = Vector2D(-0.88f, -0.47f) // axis along stick (up-left) / aim (down-right)
        val stickDir = Vector2D(-0.88f, -0.47f) // stick extends up-left

        val pixels = createFrameWithStick(cueBall, stickDir)

        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = unorientedAxis,
            tableBounds = tableBounds,
            pixels = pixels,
            width = width,
            height = height
        )

        assertNotNull("Resolved aim should not be null", resolved)
        // Must resolve to forward down-right: positive x and positive y
        assertTrue("Forward x must be positive", resolved!!.forwardDir.x > 0f)
        assertTrue("Forward y must be positive", resolved.forwardDir.y > 0f)
        assertTrue("Direction must be flipped from stick", resolved.isFlipped)
    }

    @Test
    fun testNearRailEdgeCase_woodTextureFallback() {
        val cueBall = Vector2D(145f, 370f) // Close to left rail
        val unorientedAxis = Vector2D(-1f, 0f) // Pointing left into rail
        val stickDir = Vector2D(-1f, 0f) // Stick behind cue ball to the left

        val pixelsWithWood = createFrameWithStick(cueBall, stickDir)

        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = unorientedAxis,
            tableBounds = tableBounds,
            pixels = pixelsWithWood,
            width = width,
            height = height
        )

        assertNotNull("Resolved aim should not be null", resolved)
        // Forward aim must be into the table (positive x)
        assertTrue("Forward aim must point into the table", resolved!!.forwardDir.x > 0f)
    }

    @Test
    fun testIsValidTarget_validatesEndpointNotCue() {
        val engine = TrajectoryPhysicsEngine()

        val validTarget = Vector2D(500f, 350f)
        val invalidTargetOutsideRight = Vector2D(950f, 350f)
        val invalidTargetOutsideTop = Vector2D(500f, 150f)

        assertTrue(
            "Target inside table bounds must be valid",
            engine.isValidTarget(validTarget, tableBounds)
        )
        assertFalse(
            "Target landing outside right cushion must be invalid",
            engine.isValidTarget(invalidTargetOutsideRight, tableBounds)
        )
        assertFalse(
            "Target landing outside top cushion must be invalid",
            engine.isValidTarget(invalidTargetOutsideTop, tableBounds)
        )
    }
}
