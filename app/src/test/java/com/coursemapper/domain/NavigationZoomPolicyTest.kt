package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

class NavigationZoomPolicyTest {
    private val policy = NavigationZoomPolicy()

    @Test
    fun `faster travel gives more context`() {
        val stopped = policy.zoom(0f, 1_000.0, null)
        val fast = policy.zoom(20f, 1_000.0, null)
        assertTrue(stopped > fast)
    }

    @Test
    fun `approach adds bounded zoom`() {
        val far = policy.zoom(8f, 1_000.0, null)
        val near = policy.zoom(8f, 10.0, null)
        assertTrue(near > far)
        assertTrue(near <= NavigationZoomPolicy.MAX_ZOOM)
        assertEquals(NavigationZoomPolicy.MAX_ZOOM, near, 0.0001)
    }

    @Test
    fun `zoom is clamped at conservative navigation bounds`() {
        assertTrue(policy.zoom(100f, 1_000.0, null) >= NavigationZoomPolicy.MIN_ZOOM)
        assertTrue(policy.zoom(0f, 0.0, null) <= NavigationZoomPolicy.MAX_ZOOM)
    }

    @Test
    fun `travelling uses the navigation tilt`() {
        assertEquals(
            NavigationZoomPolicy.MOVING_TILT_DEGREES,
            policy.tiltDegrees(6f, 500.0),
            0.0001
        )
    }

    @Test
    fun `near marker reduces tilt`() {
        assertEquals(
            NavigationZoomPolicy.NEAR_MARKER_TILT_DEGREES,
            policy.tiltDegrees(6f, 20.0),
            0.0001
        )
    }

    @Test
    fun `stationary startup still enters navigation perspective`() {
        assertEquals(
            NavigationZoomPolicy.MOVING_TILT_DEGREES,
            policy.tiltDegrees(0f, 500.0),
            0.0001
        )
    }

    /** Phone sized map at Trondheim's latitude. */
    private val framing = NavigationZoomPolicy.Framing(
        visibleBandHeightDp = 500.0,
        viewportWidthDp     = 400.0,
        latitudeDeg         = 63.43,
        anchorFraction      = 0.65,
        cameraBearingDeg    = 0.0
    )

    /** Metres of ground per logical point at [zoom] - MapLibre's own scale. */
    private fun metresPerPoint(zoom: Double, latitudeDeg: Double = 63.43): Double =
        cos(latitudeDeg * PI / 180.0) * 2.0 * PI * 6_378_137.0 / (2.0.pow(zoom) * 512.0)

    @Test
    fun `a nearer stop is framed at a closer zoom than a distant one`() {
        val near = policy.zoom(8f, 50.0, null, framing)
        val far = policy.zoom(8f, 1_000.0, null, framing)
        assertTrue("expected $near > $far", near > far)
    }

    @Test
    fun `the look-ahead is the floor, so a distant stop does not zoom the map out`() {
        // Framing alone would answer "a stop a kilometre away needs zoom 13",
        // which is an aerial photograph of a course nobody is looking at yet.
        val distant = policy.zoom(8f, 1_000.0, null, framing)
        val noTarget = policy.zoom(8f, null, null, framing)
        assertEquals(noTarget, distant, 0.0001)
    }

    @Test
    fun `once the stop is in reach, framing takes over from speed`() {
        // near the stop the camera shows the stop, not the speed. 25 m since at
        // 40 m and 2 m/s the look-ahead floor still wins
        val slow = policy.zoom(2f, 25.0, null, framing)
        val fast = policy.zoom(20f, 25.0, null, framing)
        assertEquals(slow, fast, 0.0001)
        assertEquals(policy.frameZoom(25.0, framing), fast, 0.0001)
    }

    @Test
    fun `the framed target actually fits the screen ahead of the rider`() {
        val distance = 400.0
        val zoom = policy.frameZoom(distance, framing)
        val pointsAhead = framing.anchorFraction * framing.visibleBandHeightDp
        val visibleMetresAhead = pointsAhead * metresPerPoint(zoom)
        assertTrue(
            "target at ${distance}m needs $visibleMetresAhead m of screen",
            visibleMetresAhead >= distance
        )
        // …and not wastefully more: FRAME_FILL leaves a margin, nothing else.
        assertEquals(
            distance / NavigationZoomPolicy.FRAME_FILL, visibleMetresAhead, 1.0
        )
    }

    @Test
    fun `framing stays inside the navigation zoom bounds`() {
        assertTrue(policy.zoom(8f, 10_000.0, null, framing) >= NavigationZoomPolicy.MIN_ZOOM)
        assertTrue(policy.zoom(8f, 1.0, null, framing) <= NavigationZoomPolicy.MAX_ZOOM)
    }

    @Test
    fun `with no target the look-ahead still decides`() {
        val stopped = policy.zoom(0f, null, null, framing)
        val fast = policy.zoom(20f, null, null, framing)
        assertTrue(stopped > fast)
    }

    @Test
    fun `an unmeasured viewport falls back to the distance curve`() {
        val unmeasured = NavigationZoomPolicy.Framing(0.0, 0.0, 63.43, 0.65)
        assertEquals(
            policy.zoom(8f, 400.0, null),
            policy.zoom(8f, 400.0, null, unmeasured),
            0.0001
        )
    }

    /** Seconds of travel visible ahead of the rider at [speed]. */
    private fun lookAheadSeconds(speed: Double): Double {
        val zoom = policy.lookAheadZoom(speed, framing)
        val pointsAhead = framing.anchorFraction * framing.visibleBandHeightDp
        return pointsAhead * metresPerPoint(zoom) / speed
    }

    @Test
    fun `every travelling speed gets the same seconds of warning`() {
        // The whole point of the change.  The old speed table produced a
        // near-constant ELEVEN seconds however fast you went, because it was
        // tuned in zoom levels rather than in the thing a rider needs.
        listOf(5.0, 8.0, 12.0, 16.0, 20.0).forEach { speed ->
            assertEquals(
                "at $speed m/s",
                NavigationZoomPolicy.LOOKAHEAD_SECONDS,
                lookAheadSeconds(speed),
                0.2
            )
        }
    }

    @Test
    fun `going faster really does zoom the map out`() {
        val slow = policy.lookAheadZoom(5.0, framing)
        val quick = policy.lookAheadZoom(12.0, framing)
        val fast = policy.lookAheadZoom(20.0, framing)
        assertTrue("$slow > $quick", slow > quick)
        assertTrue("$quick > $fast", quick > fast)
        // and far enough out to matter, z16 only shows about 280 m
        val pointsAhead = framing.anchorFraction * framing.visibleBandHeightDp
        assertTrue(pointsAhead * metresPerPoint(fast) > 300.0)
    }

    @Test
    fun `the look-ahead is bounded at both ends`() {
        val pointsAhead = framing.anchorFraction * framing.visibleBandHeightDp
        val stationary = pointsAhead * metresPerPoint(policy.lookAheadZoom(0.0, framing))
        val flatOut = pointsAhead * metresPerPoint(policy.lookAheadZoom(60.0, framing))
        assertEquals(NavigationZoomPolicy.MIN_LOOKAHEAD_M, stationary, 1.0)
        assertEquals(NavigationZoomPolicy.MAX_LOOKAHEAD_M, flatOut, 1.0)
    }

    @Test
    fun `the zoom-out floor never clips the look-ahead, on any phone`() {
        // a floor near MAX_LOOKAHEAD_M caps the top of the curve, a short band
        // needs a lower zoom for the same distance
        listOf(300.0, 400.0, 500.0, 700.0).forEach { bandHeightDp ->
            val onThisPhone = framing.copy(visibleBandHeightDp = bandHeightDp)
            assertTrue(
                "a ${bandHeightDp}dp band bottoms out below the floor",
                policy.lookAheadZoom(60.0, onThisPhone) >= NavigationZoomPolicy.MIN_ZOOM
            )
        }
    }

    @Test
    fun `a stop off to the side is framed by the width, not by the distance`() {
        // 300 m abeam is 300 m of LATERAL extent and almost none ahead.  Fitting
        // it into the screen ahead - which is what the straight-line version
        // did - frames it and leaves it off the left edge.
        val abeam = policy.frameZoom(300.0, framing, bearingToTargetDeg = 90.0)
        val lateralBudget = 0.5 * framing.viewportWidthDp * NavigationZoomPolicy.FRAME_FILL
        val visibleMetresSideways = lateralBudget * metresPerPoint(abeam)
        assertTrue(
            "300 m abeam needs $visibleMetresSideways m of half-width",
            visibleMetresSideways >= 300.0 - 1.0
        )
        // A stop the same distance dead ahead has more screen to use, so it is
        // framed closer in.
        val ahead = policy.frameZoom(300.0, framing, bearingToTargetDeg = 0.0)
        assertTrue("$ahead > $abeam", ahead > abeam)
    }

    @Test
    fun `a stop behind the rider is framed into the screen behind them`() {
        val ahead = policy.frameZoom(200.0, framing, bearingToTargetDeg = 0.0)
        val behind = policy.frameZoom(200.0, framing, bearingToTargetDeg = 180.0)
        // Only 35% of the band is behind the rider, so the same distance has to
        // be fitted into less screen - a wider view, not the same one.
        assertTrue("$behind < $ahead", behind < ahead)
        val behindBudget =
            (1.0 - framing.anchorFraction) * framing.visibleBandHeightDp *
                NavigationZoomPolicy.FRAME_FILL
        assertTrue(behindBudget * metresPerPoint(behind) >= 200.0 - 1.0)
    }

    @Test
    fun `the camera bearing is what ahead is measured against`() {
        // Course-up: the map is turned to 90°, so a stop due east is dead ahead
        // and must frame exactly as a north stop does on a north-up map.
        val courseUp = framing.copy(cameraBearingDeg = 90.0)
        assertEquals(
            policy.frameZoom(250.0, framing, bearingToTargetDeg = 0.0),
            policy.frameZoom(250.0, courseUp, bearingToTargetDeg = 90.0),
            0.0001
        )
    }

    @Test
    fun `an unknown bearing frames the stop as if it were ahead`() {
        assertEquals(
            policy.frameZoom(250.0, framing, bearingToTargetDeg = 0.0),
            policy.frameZoom(250.0, framing, bearingToTargetDeg = null),
            0.0001
        )
    }

    @Test
    fun `zooming in stops at the frame floor`() {
        val atFloor = policy.frameZoom(NavigationZoomPolicy.TARGET_FRAME_FLOOR_M, framing)
        // zero included, an early MAX_ZOOM there made the camera close in over
        // the last metres
        listOf(10.0, 5.0, 1.0, 0.0).forEach { closer ->
            assertEquals(
                "at ${closer}m the camera has already arrived",
                atFloor,
                policy.frameZoom(closer, framing),
                0.0001
            )
        }
        // Further out it is still tightening, or the floor would be doing
        // nothing at all.
        assertTrue(policy.frameZoom(40.0, framing) < atFloor)
    }

    @Test
    fun `the look-ahead floor never hides the stop being driven at`() {
        // crawling gets MIN_LOOKAHEAD_M which is tighter than framing a stop 70 m
        // out, inside FRAME_TAKEOVER_RANGE_M the stop wins
        val crawling = 2f
        val distance = 70.0
        val chosen = policy.zoom(crawling, distance, null, framing)
        assertEquals(policy.frameZoom(distance, framing), chosen, 0.0001)
        assertTrue(
            "the floor is genuinely tighter here, or this proves nothing",
            policy.lookAheadZoom(crawling.toDouble(), framing) > policy.frameZoom(distance, framing)
        )
        // The stop really is on screen at that zoom.
        val budget = framing.anchorFraction * framing.visibleBandHeightDp
        assertTrue(budget * metresPerPoint(chosen) >= distance)
    }

    @Test
    fun `a stop beyond the takeover range still does not zoom the map out`() {
        // The other half of the same rule: a distant stop is ignored, or a
        // crawling rider with a sign 2 km away gets an aerial photograph.
        val crawling = 2f
        val far = NavigationZoomPolicy.FRAME_TAKEOVER_RANGE_M * 4
        assertEquals(
            policy.lookAheadZoom(crawling.toDouble(), framing),
            policy.zoom(crawling, far, null, framing),
            0.0001
        )
    }

    @Test
    fun `the frame floor, not the ceiling, is what stops the approach`() {
        // A MAX_ZOOM that bound first would make the 15 m rule a fiction.
        assertTrue(
            policy.frameZoom(NavigationZoomPolicy.TARGET_FRAME_FLOOR_M, framing) <
                NavigationZoomPolicy.MAX_ZOOM
        )
    }

    @Test
    fun `the zoom target is rate limited in zoom levels per second`() {
        val previous = 16.0
        val halfSecond = policy.zoom(
            0f, 0.0, previousZoom = previous, framing = framing, elapsedSeconds = 0.5
        )
        assertEquals(
            previous + NavigationZoomPolicy.MAX_ZOOM_RATE_PER_SECOND * 0.5,
            halfSecond,
            0.0001
        )
        // twice the elapsed time, twice the movement - per second, not per fix
        val fullSecond = policy.zoom(
            0f, 0.0, previousZoom = previous, framing = framing, elapsedSeconds = 1.0
        )
        assertEquals(
            previous + NavigationZoomPolicy.MAX_ZOOM_RATE_PER_SECOND,
            fullSecond,
            0.0001
        )
    }

    @Test
    fun `a long GPS gap does not license an unbounded jump`() {
        val afterOutage = policy.zoom(
            0f, 0.0, previousZoom = 15.0, framing = framing, elapsedSeconds = 120.0
        )
        assertEquals(
            15.0 + NavigationZoomPolicy.MAX_ZOOM_RATE_PER_SECOND *
                NavigationZoomPolicy.MAX_LIMITER_SECONDS,
            afterOutage,
            0.0001
        )
    }

    @Test
    fun `the first fix adopts its zoom outright`() {
        // Nothing to ease from, and easing from a default would show the rider
        // a second of the wrong scale before it caught up.
        assertEquals(
            NavigationZoomPolicy.MAX_ZOOM,
            policy.zoom(0f, 0.0, previousZoom = null),
            0.0001
        )
    }

    @Test
    fun `flattening starts further out the faster you are going`() {
        // 70 m from the stop: at ATV speed that is under four seconds' warning,
        // so the camera should already be coming down; at walking pace it is
        // still a long way off.
        val fast = policy.tiltDegrees(12f, 70.0)
        val slow = policy.tiltDegrees(2f, 70.0)
        assertTrue("expected $fast < $slow", fast < slow)
        assertEquals(NavigationZoomPolicy.MOVING_TILT_DEGREES, slow, 0.0001)
    }

    @Test
    fun `flattening is gradual rather than a step`() {
        val far = policy.tiltDegrees(10f, 75.0)
        val closer = policy.tiltDegrees(10f, 50.0)
        val near = policy.tiltDegrees(10f, 35.0)
        assertTrue(far > closer)
        assertTrue(closer > near)
        assertTrue(near > NavigationZoomPolicy.NEAR_MARKER_TILT_DEGREES)
    }

    @Test
    fun `dense stops cannot leave the camera permanently flat`() {
        // Mapbox flattens 180 m out, which on a course with stops 200 m apart
        // would mean the camera never comes back up.  The cap is what stops it.
        val betweenDenseStops = policy.tiltDegrees(30f, 150.0)
        assertEquals(NavigationZoomPolicy.MOVING_TILT_DEGREES, betweenDenseStops, 0.0001)
        assertTrue(NavigationZoomPolicy.MAX_FLATTEN_DISTANCE_M < 150.0)
    }

    @Test
    fun `a stationary rider gets the minimum look-ahead zoom`() {
        assertEquals(
            NavigationZoomPolicy.NEAR_MARKER_TILT_DEGREES,
            policy.tiltDegrees(0f, NavigationZoomPolicy.NEAR_MARKER_DISTANCE_M),
            0.0001
        )
        assertEquals(
            NavigationZoomPolicy.MOVING_TILT_DEGREES,
            policy.tiltDegrees(0f, NavigationZoomPolicy.NEAR_MARKER_DISTANCE_M + 0.1),
            0.0001
        )
    }
}
