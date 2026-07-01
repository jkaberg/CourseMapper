package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowCameraSmootherTest {

    private val calculator = CumulativeDistanceCalculator()
    private val smoother = FollowCameraSmoother(calculator)

    private val baseLat = 63.43
    private val baseLon = 10.39
    private val metresPerDegreeLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0

    private fun target(
        metresNorth: Double = 0.0,
        headingDeg: Double? = 0.0,
        cameraBearingDeg: Double = 0.0,
        zoom: Double = 17.0,
        tiltDeg: Double = 50.0
    ) = FollowCameraSmoother.Target(
        lat              = baseLat + metresNorth / metresPerDegreeLat,
        lon              = baseLon,
        headingDeg       = headingDeg,
        cameraBearingDeg = cameraBearingDeg,
        zoom             = zoom,
        tiltDeg          = tiltDeg
    )

    private fun metresNorthOf(frame: FollowCameraSmoother.Frame): Double =
        (frame.lat - baseLat) * metresPerDegreeLat

    /** One second of 60 Hz frames. */
    private fun runSeconds(seconds: Double, target: FollowCameraSmoother.Target): FollowCameraSmoother.Frame {
        var frame = smoother.advance(target, 0.0)
        repeat((seconds * 60).toInt()) {
            frame = smoother.advance(target, 1.0 / 60.0)
        }
        return frame
    }

    @Test
    fun `the first frame adopts its target whole`() {
        val frame = smoother.advance(target(metresNorth = 25.0), 0.0)
        assertEquals(25.0, metresNorthOf(frame), 0.01)
        assertEquals(17.0, frame.zoom, 1e-9)
        assertEquals(50.0, frame.tiltDeg, 1e-9)
    }

    @Test
    fun `a fix is converged on over frames rather than jumped to`() {
        smoother.advance(target(), 0.0)
        val afterOneFrame = smoother.advance(target(metresNorth = 10.0), 1.0 / 60.0)
        val moved = metresNorthOf(afterOneFrame)
        assertTrue("a single frame must not consume the whole gap", moved < 1.0)
        assertTrue("but it must move", moved > 0.0)

        // Give it long enough and it arrives.
        val settled = runSeconds(4.0, target(metresNorth = 10.0))
        assertEquals(10.0, metresNorthOf(settled), 0.05)
    }

    @Test
    fun `halving the frame rate reaches the same place at the same time`() {
        val fast = FollowCameraSmoother(calculator)
        val slow = FollowCameraSmoother(calculator)
        val destination = target(metresNorth = 40.0)
        fast.advance(target(), 0.0)
        slow.advance(target(), 0.0)

        var fastFrame = fast.advance(destination, 0.0)
        repeat(60) { fastFrame = fast.advance(destination, 1.0 / 60.0) }
        var slowFrame = slow.advance(destination, 0.0)
        repeat(30) { slowFrame = slow.advance(destination, 1.0 / 30.0) }

        assertEquals(metresNorthOf(fastFrame), metresNorthOf(slowFrame), 0.05)
    }

    @Test
    fun `a teleport is adopted outright instead of gliding across the map`() {
        smoother.advance(target(), 0.0)
        val jump = FollowCameraSmoother.SNAP_DISTANCE_M + 100.0
        val frame = smoother.advance(target(metresNorth = jump), 1.0 / 60.0)
        assertEquals(jump, metresNorthOf(frame), 0.01)
    }

    @Test
    fun `a stalled frame clock cannot reproduce the jump`() {
        smoother.advance(target(), 0.0)
        // Ten seconds between frames - an app returning from the background.
        val frame = smoother.advance(target(metresNorth = 40.0), 10.0)
        val expected = 40.0 * (1.0 - Math.exp(
            -FollowCameraSmoother.MAX_FRAME_SECONDS / FollowCameraSmoother.POSITION_TIME_CONSTANT_S
        ))
        assertEquals(expected, metresNorthOf(frame), 0.01)
    }

    @Test
    fun `bearing turns the short way round`() {
        smoother.advance(target(headingDeg = 350.0, cameraBearingDeg = 350.0), 0.0)
        val turning = target(headingDeg = 10.0, cameraBearingDeg = 10.0)
        val frame = smoother.advance(turning, 1.0 / 60.0)
        val heading = frame.headingDeg!!
        // Past 350° towards 360/0, never back through 180.
        assertTrue("expected a small turn upward from 350, got $heading", heading > 350.0)
        assertTrue(heading < 360.0)

        val settled = runSeconds(5.0, turning)
        assertEquals(10.0, settled.headingDeg!!, 0.1)
    }

    @Test
    fun `losing the direction of travel renders a dot, not a stale arrow`() {
        smoother.advance(target(headingDeg = 90.0, cameraBearingDeg = 90.0), 0.0)
        val frame = smoother.advance(
            target(headingDeg = null, cameraBearingDeg = 90.0), 1.0 / 60.0
        )
        assertNull(frame.headingDeg)
        // The camera keeps its rotation regardless - the map does not spin back
        // to north because the bearing went quiet for a fix.
        assertEquals(90.0, frame.cameraBearingDeg, 0.001)
    }

    @Test
    fun `settling reports only once everything has arrived`() {
        val destination = target(metresNorth = 30.0, zoom = 18.0, tiltDeg = 22.0)
        smoother.advance(target(), 0.0)
        assertFalse(smoother.isSettled(destination))
        runSeconds(12.0, destination)
        assertTrue(smoother.isSettled(destination))
    }

    @Test
    fun `reset makes the next frame adopt its target whole again`() {
        smoother.advance(target(), 0.0)
        smoother.reset()
        val frame = smoother.advance(target(metresNorth = 5.0), 1.0 / 60.0)
        assertEquals(5.0, metresNorthOf(frame), 0.01)
    }

    @Test
    fun `a hairpin cannot leave the camera facing where the rider came from`() {
        // the time constant bounds turn speed, not lag - on a 180° turn the map
        // would show where the rider has been
        smoother.advance(target(headingDeg = 0.0, cameraBearingDeg = 0.0), 0.0)
        val hairpin = target(headingDeg = 180.0, cameraBearingDeg = 180.0)

        val frame = smoother.advance(hairpin, 1.0 / 60.0)
        val lag = angleGap(frame.cameraBearingDeg, 180.0)
        assertTrue(
            "camera lagged the travel bearing by $lag°",
            lag <= FollowCameraSmoother.MAX_BEARING_LAG_DEG + 0.001
        )
    }

    @Test
    fun `an ordinary curve is still eased, not snapped`() {
        // The cap must only take away the excess: inside it the smoothing does
        // all the visible work, which is what stops the map twitching on every
        // noisy bearing.
        smoother.advance(target(headingDeg = 0.0, cameraBearingDeg = 0.0), 0.0)
        val curve = target(headingDeg = 20.0, cameraBearingDeg = 20.0)

        val frame = smoother.advance(curve, 1.0 / 60.0)
        assertTrue("expected easing, got ${frame.cameraBearingDeg}", frame.cameraBearingDeg > 0.0)
        assertTrue("expected easing, got ${frame.cameraBearingDeg}", frame.cameraBearingDeg < 1.0)
    }

    @Test
    fun `the cap turns the short way round`() {
        // 350° → 10° is a 20° turn; nothing to cap, and certainly not a 340°
        // spin back through south.
        smoother.advance(target(headingDeg = 350.0, cameraBearingDeg = 350.0), 0.0)
        val frame = smoother.advance(
            target(headingDeg = 10.0, cameraBearingDeg = 10.0), 1.0 / 60.0
        )
        assertTrue(frame.cameraBearingDeg > 350.0 || frame.cameraBearingDeg < 10.0)
    }

    @Test
    fun `the camera still converges all the way, cap or no cap`() {
        smoother.advance(target(headingDeg = 0.0, cameraBearingDeg = 0.0), 0.0)
        val hairpin = target(headingDeg = 180.0, cameraBearingDeg = 180.0)
        runSeconds(12.0, hairpin)
        assertEquals(180.0, smoother.advance(hairpin, 0.0).cameraBearingDeg, 0.5)
    }

    private fun angleGap(from: Double, to: Double): Double =
        kotlin.math.abs(((to - from + 540.0) % 360.0) - 180.0)
}
