package com.coursemapper.map.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Anchor arithmetic only. MapLibre centres the target in the padded box, so this decides where the rider sits. */
class MapCameraControllerTest {

    /** Where the padded box's centre lands, as a fraction of the viewport. */
    private fun anchorOf(heightPx: Int, fraction: Double): Double {
        val padding = MapCameraController.anchorPadding(heightPx, fraction)
        return (padding.top + (heightPx - padding.bottom)) / 2.0 / heightPx
    }

    @Test
    fun `the default anchor puts the rider below centre`() {
        assertEquals(
            MapCameraController.FOLLOW_ANCHOR_FRACTION,
            anchorOf(2_000, MapCameraController.FOLLOW_ANCHOR_FRACTION),
            0.001
        )
        assertTrue(MapCameraController.FOLLOW_ANCHOR_FRACTION > 0.5)
    }

    @Test
    fun `a half anchor is plain centring`() {
        val padding = MapCameraController.anchorPadding(2_000, 0.5)
        assertEquals(0, padding.top)
        assertEquals(0, padding.bottom)
        assertEquals(0.5, anchorOf(2_000, 0.5), 0.001)
    }

    @Test
    fun `an anchor above centre pads the other side`() {
        val padding = MapCameraController.anchorPadding(2_000, 0.3)
        assertEquals(0, padding.top)
        assertTrue(padding.bottom > 0)
        assertEquals(0.3, anchorOf(2_000, 0.3), 0.001)
    }

    @Test
    fun `padding never swallows the viewport`() {
        // Degenerate fractions must still leave a box to draw into, or the
        // camera update is rejected and the map stops following at all.
        listOf(0.0, 1.0).forEach { fraction ->
            val padding = MapCameraController.anchorPadding(1_000, fraction)
            assertTrue(padding.top < 1_000)
            assertTrue(padding.bottom < 1_000)
        }
    }

    @Test
    fun `an unmeasured viewport asks for no padding at all`() {
        val padding = MapCameraController.anchorPadding(0)
        assertEquals(0, padding.top)
        assertEquals(0, padding.bottom)
    }

    @Test
    fun `the anchor is measured inside the chrome, not across it`() {
        // 800 tall, 132 of banner, 170 of controls: the rider should sit at
        // 132 + 0.65 × 498 = 455.7, which is 0.57 of the whole viewport - not
        // 0.65 of it, which lands 78% of the way down the band they can see.
        val fraction = MapCameraController.anchorFractionInBand(800, 132, 170)
        assertEquals(455.7 / 800.0, fraction, 0.001)
        assertEquals(455.7, fraction * 800.0, 0.5)
    }

    @Test
    fun `chrome moves the rider up the screen, never down`() {
        val bare = MapCameraController.anchorFractionInBand(800, 0, 0)
        val chromed = MapCameraController.anchorFractionInBand(800, 132, 170)
        assertEquals(MapCameraController.FOLLOW_ANCHOR_FRACTION, bare, 0.001)
        assertTrue("expected $chromed < $bare", chromed < bare)
    }

    @Test
    fun `a north-up half anchor centres the band, not the viewport`() {
        // Unequal chrome: centring the viewport would put the rider below the
        // middle of what they can actually see.
        val fraction = MapCameraController.anchorFractionInBand(800, 132, 170, 0.5)
        assertEquals((132 + 0.5 * 498) / 800.0, fraction, 0.001)
    }

    @Test
    fun `unmeasured chrome leaves the fraction unchanged`() {
        // Before the first layout pass there is nothing to subtract, and
        // guessing would be worse than the fraction the caller asked for.
        assertEquals(
            MapCameraController.FOLLOW_ANCHOR_FRACTION,
            MapCameraController.anchorFractionInBand(800, 0, 0),
            0.0001
        )
        assertEquals(
            MapCameraController.FOLLOW_ANCHOR_FRACTION,
            MapCameraController.anchorFractionInBand(0, 132, 170),
            0.0001
        )
    }

    @Test
    fun `chrome that swallows the viewport cannot produce a nonsense anchor`() {
        // A momentary layout where the two pieces of chrome overlap the whole
        // map must not divide by a negative band and put the camera off screen.
        val fraction = MapCameraController.anchorFractionInBand(400, 300, 300)
        assertEquals(MapCameraController.FOLLOW_ANCHOR_FRACTION, fraction, 0.0001)
        val padding = MapCameraController.anchorPadding(400, fraction)
        assertTrue(padding.top < 400)
        assertTrue(padding.bottom < 400)
    }

    @Test
    fun `the camera lands on the fraction the puck is given`() {
        // The overlay and the camera must read one number, or the rider is
        // drawn somewhere the map is not centred.
        val fraction = MapCameraController.anchorFractionInBand(2_000, 330, 425)
        assertEquals(fraction, anchorOf(2_000, fraction), 0.001)
    }
}
