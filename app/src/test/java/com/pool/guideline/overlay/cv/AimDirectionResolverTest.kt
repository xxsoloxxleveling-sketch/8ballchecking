package com.pool.guideline.overlay.cv

import com.pool.guideline.overlay.physics.TrajectoryPhysicsEngine
import com.pool.guideline.overlay.physics.Vector2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimDirectionResolverTest {

    private val tableBounds = TableBounds(
        xMin = 69f,
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
            // Inject wood / cue stick texture (R: 170, G: 120, B: 60 -> R > 110 and R > B * 1.25)
            pixels[py * width + px] = (0xFF shl 24) or (170 shl 16) or (120 shl 8) or 60
        }
        return pixels
    }

    @Test
    fun testTier2_rejectsWoodTextureSide_returnsOppositeDirection() {
        val cueBall = Vector2D(500f, 350f)
        val axisDir = Vector2D(1f, 0f)

        // Case A: Wood texture is along +axisDir (+x) -> Resolver must return -axisDir (-x)
        val pixelsWithWoodPlus = createFrameWithStick(cueBall, axisDir)
        val resolvedA = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = axisDir,
            tableBounds = tableBounds,
            pixels = pixelsWithWoodPlus,
            width = width,
            height = height
        )
        assertNotNull("Resolved aim should not be null", resolvedA)
        assertEquals("Must reject +u and return -u", "texture_reject_plus", resolvedA!!.resolutionMethod)
        assertTrue("Forward direction must be -x (opposite of wood)", resolvedA.forwardDir.x < 0f)

        // Case B: Wood texture is along -axisDir (-x) -> Resolver must return +axisDir (+x)
        val pixelsWithWoodMinus = createFrameWithStick(cueBall, -axisDir)
        val resolvedB = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = axisDir,
            tableBounds = tableBounds,
            pixels = pixelsWithWoodMinus,
            width = width,
            height = height
        )
        assertNotNull("Resolved aim should not be null", resolvedB)
        assertEquals("Must reject -u and return +u", "texture_reject_minus", resolvedB!!.resolutionMethod)
        assertTrue("Forward direction must be +x (opposite of wood)", resolvedB.forwardDir.x > 0f)
    }

    @Test
    fun testCase1_media746_upLeftAim() {
        val cueBall = Vector2D(645f, 420f)
        val unorientedAxis = Vector2D(0.91f, 0.42f)
        val stickDir = Vector2D(0.91f, 0.42f)

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
        assertTrue("Forward x must be negative", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be negative", resolved.forwardDir.y < 0f)
    }

    @Test
    fun testCase2_media361_downLeftAim() {
        val cueBall = Vector2D(645f, 420f)
        val unorientedAxis = Vector2D(0.74f, -0.67f)
        val stickDir = Vector2D(0.74f, -0.67f)

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
        assertTrue("Forward x must be negative", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be positive", resolved.forwardDir.y > 0f)
    }

    @Test
    fun testCase3_media016_downRightAim() {
        val cueBall = Vector2D(420f, 350f)
        val unorientedAxis = Vector2D(-0.88f, -0.47f)
        val stickDir = Vector2D(-0.88f, -0.47f)

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
        assertTrue("Forward x must be positive", resolved!!.forwardDir.x > 0f)
        assertTrue("Forward y must be positive", resolved.forwardDir.y > 0f)
    }

    // --- 5 New Live Screenshot Regression Tests ---

    @Test
    fun testCase4_screenshot1_upRightAim() {
        // Image 1: Aiming up-right toward striped ball, stick extends down-left
        val cueBall = Vector2D(654f, 372f)
        val stickDir = Vector2D(-0.45f, 0.89f) // Stick down-left
        val unorientedAxis = Vector2D(0.45f, -0.89f) // Axis

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
        assertTrue("Forward x must be positive (right)", resolved!!.forwardDir.x > 0f)
        assertTrue("Forward y must be negative (up)", resolved.forwardDir.y < 0f)
    }

    @Test
    fun testCase5_screenshot2_downRightAim() {
        // Image 2: Aiming down-right toward yellow ball, stick extends up-left
        val cueBall = Vector2D(638f, 368f)
        val stickDir = Vector2D(-0.71f, -0.71f) // Stick up-left
        val unorientedAxis = Vector2D(0.71f, 0.71f) // Axis

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
        assertTrue("Forward x must be positive (right)", resolved!!.forwardDir.x > 0f)
        assertTrue("Forward y must be positive (down)", resolved.forwardDir.y > 0f)
    }

    @Test
    fun testCase6_screenshot3_downLeftAim() {
        // Image 3 / 4: Aiming down-left toward blue ball, stick extends up-right
        val cueBall = Vector2D(639f, 366f)
        val stickDir = Vector2D(0.71f, -0.71f) // Stick up-right
        val unorientedAxis = Vector2D(-0.71f, 0.71f) // Axis

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
        assertTrue("Forward x must be negative (left)", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be positive (down)", resolved.forwardDir.y > 0f)
    }

    @Test
    fun testCase7_screenshot4_upLeftAim() {
        // Image 4: Aiming up-left toward orange ball, stick extends down-right
        val cueBall = Vector2D(653f, 367f)
        val stickDir = Vector2D(0.71f, 0.71f) // Stick down-right
        val unorientedAxis = Vector2D(-0.71f, -0.71f) // Axis

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
        assertTrue("Forward x must be negative (left)", resolved!!.forwardDir.x < 0f)
        assertTrue("Forward y must be negative (up)", resolved.forwardDir.y < 0f)
    }

    @Test
    fun testCase8_screenshot5_leftAim() {
        // Image 5: Aiming left toward #7 ball, stick extends right
        val cueBall = Vector2D(653f, 366f)
        val stickDir = Vector2D(1f, 0f) // Stick right
        val unorientedAxis = Vector2D(-1f, 0f) // Axis

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
        assertTrue("Forward x must be negative (left)", resolved!!.forwardDir.x < 0f)
    }

    @Test
    fun testNearRailEdgeCase_woodTextureFallback() {
        val cueBall = Vector2D(145f, 370f)
        val unorientedAxis = Vector2D(-1f, 0f)
        val stickDir = Vector2D(-1f, 0f)

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
        assertTrue("Forward aim must point into the table", resolved!!.forwardDir.x > 0f)
    }

    @Test
    fun testTier1_boundaryProbe_shortCircuitsWithoutTexture() {
        val cueBall = Vector2D(800f, 500f)
        val unorientedAxis = Vector2D(0.707f, 0.707f)
        val cleanPixels = IntArray(width * height) { 0xFF004466.toInt() }

        val resolved = AimDirectionResolver.resolveForwardDirection(
            cueBallPos = cueBall,
            axisDir = unorientedAxis,
            tableBounds = tableBounds,
            pixels = cleanPixels,
            width = width,
            height = height
        )

        assertNotNull("Tier 1 boundary probe must resolve without texture", resolved)
        assertEquals("Must resolve via boundary_probe_minus", "boundary_probe_minus", resolved!!.resolutionMethod)
        assertTrue("Forward x must point up-left (negative)", resolved.forwardDir.x < 0f)
        assertTrue("Forward y must point up-left (negative)", resolved.forwardDir.y < 0f)
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
