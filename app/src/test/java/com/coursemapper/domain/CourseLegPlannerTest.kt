package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLegPlannerTest {

    private val calc = CumulativeDistanceCalculator()
    private val planner = CourseLegPlanner(calc)

    /** Metres per degree of latitude, near enough for building fixtures. */
    private val mPerDegLat = 111_320.0

    private fun point(lat: Double, lon: Double) =
        RoutePoint(lat, lon, null, null, 0L, isSmoothed = true)

    /** Straight course north from (63.0, 10.0), a point every 10 m. */
    private fun straightCourse(lengthMetres: Double = 1_000.0): CourseLegPlanner.Course {
        val step = 10.0 / mPerDegLat
        val count = (lengthMetres / 10.0).toInt()
        val points = (0..count).map { point(63.0 + it * step, 10.0) }
        return CourseLegPlanner.Course(1L, PolylineIndex.build(points, calc)!!)
    }

    @Test
    fun `follows the course from the rider to the stop`() {
        val course = straightCourse()
        // Rider 100 m along, stop 500 m along.
        val riderLat = 63.0 + 100.0 / mPerDegLat
        val stopLat = 63.0 + 500.0 / mPerDegLat

        val leg = planner.plan(listOf(course), riderLat, 10.0, stopLat, 10.0)

        assertNotNull(leg)
        assertEquals(1L, leg!!.courseId)
        assertEquals(400.0, leg.remainingMetres, 1.0)
        // The line starts at the rider and ends on the stop.
        assertEquals(riderLat, leg.points.first().first, 1e-9)
        assertEquals(stopLat, leg.points.last().first, 1e-6)
        // And it is the course, not a two-point straight line.
        assertTrue("expected the course vertices in between", leg.points.size > 10)
    }

    @Test
    fun `runs backwards along the course when the stop is behind`() {
        val course = straightCourse()
        val riderLat = 63.0 + 500.0 / mPerDegLat
        val stopLat = 63.0 + 100.0 / mPerDegLat

        val leg = planner.plan(listOf(course), riderLat, 10.0, stopLat, 10.0)

        assertNotNull(leg)
        assertEquals(400.0, leg!!.remainingMetres, 1.0)
        // Ordered rider-first, so the line is drawn the way it will be ridden.
        assertTrue(leg.points.first().first > leg.points.last().first)
    }

    @Test
    fun `declines when the rider is off the course`() {
        val course = straightCourse()
        val riderLat = 63.0 + 100.0 / mPerDegLat
        // 200 m east - well past RIDER_TOLERANCE_METRES.
        val riderLon = 10.0 + 200.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val stopLat = 63.0 + 500.0 / mPerDegLat

        assertNull(planner.plan(listOf(course), riderLat, riderLon, stopLat, 10.0))
    }

    @Test
    fun `declines when the stop is not on the course`() {
        val course = straightCourse()
        val riderLat = 63.0 + 100.0 / mPerDegLat
        val stopLat = 63.0 + 500.0 / mPerDegLat
        val stopLon = 10.0 + 100.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))

        assertNull(planner.plan(listOf(course), riderLat, 10.0, stopLat, stopLon))
    }

    @Test
    fun `declines with no courses at all`() {
        assertNull(planner.plan(emptyList(), 63.0, 10.0, 63.001, 10.0))
    }

    @Test
    fun `picks the course that joins the two over the least ground`() {
        val direct = straightCourse()
        // A second course over the same corridor, but reaching the stop the long
        // way round via a 600 m eastward excursion.
        val step = 10.0 / mPerDegLat
        val east = 300.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val detourPoints = buildList {
            for (i in 0..10) add(point(63.0 + i * step, 10.0))            // to 100 m
            for (i in 1..30) add(point(63.0 + 10 * step, 10.0 + i * east / 30))
            for (i in 1..30) add(point(63.0 + 10 * step + i * step * 4 / 3, 10.0 + east))
            for (i in 1..30) add(point(63.0 + 50 * step, 10.0 + east - i * east / 30))
        }
        val detour = CourseLegPlanner.Course(2L, PolylineIndex.build(detourPoints, calc)!!)

        val riderLat = 63.0 + 100.0 / mPerDegLat
        val stopLat = 63.0 + 500.0 / mPerDegLat

        // Offered detour-first, so a naive "first match wins" would fail.
        val leg = planner.plan(listOf(detour, direct), riderLat, 10.0, stopLat, 10.0)

        assertNotNull(leg)
        assertEquals(1L, leg!!.courseId)
    }

    @Test
    fun `takes the near pass, not a whole lap, on a course that repeats itself`() {
        // Two laps of the same 1 km out-and-back: the rider and the stop each
        // project onto the course twice.
        val step = 10.0 / mPerDegLat
        val oneLap = buildList {
            for (i in 0..100) add(point(63.0 + i * step, 10.0))
            for (i in 99 downTo 0) add(point(63.0 + i * step, 10.0))
        }
        val course = CourseLegPlanner.Course(
            3L, PolylineIndex.build(oneLap + oneLap, calc)!!
        )

        val riderLat = 63.0 + 100.0 / mPerDegLat
        val stopLat = 63.0 + 200.0 / mPerDegLat

        val leg = planner.plan(listOf(course), riderLat, 10.0, stopLat, 10.0)

        assertNotNull(leg)
        // 100 m of ground between them.  Pairing the wrong passes would give
        // hundreds or thousands of metres of course instead.
        assertEquals(100.0, leg!!.remainingMetres, 15.0)
    }

    @Test
    fun `declines a wrap-around dressed up as a route`() {
        // A closed loop: the stop is 20 m behind the rider the short way, and
        // almost the whole loop the other way.  Cut the loop so that the only
        // available pairing is the long way round.
        val step = 10.0 / mPerDegLat
        val loop = buildList {
            for (i in 0..80) add(point(63.0 + i * step, 10.0))
            // come back down a parallel line 100 m east, ending near the start
            val east = 100.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
            for (i in 80 downTo 0) add(point(63.0 + i * step, 10.0 + east))
        }
        val course = CourseLegPlanner.Course(4L, PolylineIndex.build(loop, calc)!!)

        // Rider halfway up the outbound line; the stop is 100 m east of them, on
        // the return line.  Across the grass that is 100 m; along the course it
        // is up to the turn and all the way back down - 900 m.
        val riderLat = 63.0 + 400.0 / mPerDegLat
        val stopLon = 10.0 + 100.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))

        val leg = planner.plan(listOf(course), riderLat, 10.0, riderLat, stopLon)

        // 100 m of ground for 900 m of course: the road graph will do better,
        // and a line drawn all the way round would just be wrong.
        assertNull(leg)
    }

    /** Main line north with a 300 m spur east at 400 m. */
    private fun mainLineWithSpur(): CourseLegPlanner.Course {
        val step = 10.0 / mPerDegLat
        val east = 10.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val points = buildList {
            for (i in 0..40) add(point(63.0 + i * step, 10.0))              // 0-400 m north
            for (i in 1..30) add(point(63.0 + 40 * step, 10.0 + i * east))  // spur out east
            for (i in 29 downTo 0) add(point(63.0 + 40 * step, 10.0 + i * east))
            for (i in 41..100) add(point(63.0 + i * step, 10.0))            // on north
        }
        return CourseLegPlanner.Course(9L, PolylineIndex.build(points, calc)!!)
    }

    @Test
    fun `a stop only one course reaches is still followed along that course`() {
        // The 5 km course does not go up the spur; the marathon does.  The rider
        // is on ground both share, so the course carrying the stop carries them
        // too, and guidance rides it out to the marker.
        val shared = straightCourse(400.0).copy(courseId = 10L)
        val withSpur = mainLineWithSpur()

        val riderLat = 63.0 + 200.0 / mPerDegLat
        val spurTipLon = 10.0 + 300.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val spurTipLat = 63.0 + 400.0 / mPerDegLat

        val leg = planner.plan(listOf(shared, withSpur), riderLat, 10.0, spurTipLat, spurTipLon)

        assertNotNull(leg)
        // Planned on the only course that reaches the marker.
        assertEquals(9L, leg!!.courseId)
        // 200 m up the main line then 300 m out the spur.
        assertEquals(500.0, leg.remainingMetres, 20.0)
    }

    @Test
    fun `leaving a spur tip for the main line rides back down the spur`() {
        // The marshal has marked the spur tip; the next stop is further up the
        // main line.  There is no way there but back down the spur, and that is
        // what the recording says, so that is what is drawn.
        val withSpur = mainLineWithSpur()

        val spurTipLat = 63.0 + 400.0 / mPerDegLat
        val spurTipLon = 10.0 + 300.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val onwardLat = 63.0 + 700.0 / mPerDegLat

        val leg = planner.plan(listOf(withSpur), spurTipLat, spurTipLon, onwardLat, 10.0)

        assertNotNull(leg)
        // 300 m back down the spur, then 300 m on up the main line.
        assertEquals(600.0, leg!!.remainingMetres, 25.0)
    }

    @Test
    fun `a marker no course reaches is left to road routing`() {
        // A depot, a car park, a sign dropped off the route: nothing recorded
        // goes there, so there is no honest course line to draw.
        val course = straightCourse()
        val riderLat = 63.0 + 100.0 / mPerDegLat
        val strandedLat = 63.0 + 300.0 / mPerDegLat
        val strandedLon = 10.0 + 250.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))

        assertNull(planner.plan(listOf(course), riderLat, 10.0, strandedLat, strandedLon))
    }

    @Test
    fun `standing at an off-course marker, the way back is left to road routing`() {
        // Having been road-routed out to a stranded marker, the marshal is now
        // off every recording.  Guidance cannot follow a course they are not on;
        // it road-routes them back, and picks the course up again on arrival.
        val course = straightCourse()
        val strandedLat = 63.0 + 300.0 / mPerDegLat
        val strandedLon = 10.0 + 250.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val backOnCourseLat = 63.0 + 500.0 / mPerDegLat

        assertNull(
            planner.plan(listOf(course), strandedLat, strandedLon, backOnCourseLat, 10.0)
        )

        // …and once they are back within reach of it, it takes over again.
        val nearlyBackLat = 63.0 + 300.0 / mPerDegLat
        val nearlyBackLon = 10.0 + 30.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        assertNotNull(
            planner.plan(listOf(course), nearlyBackLat, nearlyBackLon, backOnCourseLat, 10.0)
        )
    }

    @Test
    fun `line starts at the rider even when they are slightly off the course`() {
        val course = straightCourse()
        val riderLat = 63.0 + 100.0 / mPerDegLat
        // 25 m east - inside tolerance, but visibly off the line.
        val riderLon = 10.0 + 25.0 / (mPerDegLat * Math.cos(Math.toRadians(63.0)))
        val stopLat = 63.0 + 500.0 / mPerDegLat

        val leg = planner.plan(listOf(course), riderLat, riderLon, stopLat, 10.0)

        assertNotNull(leg)
        assertEquals(riderLat, leg!!.points.first().first, 1e-9)
        assertEquals(riderLon, leg.points.first().second, 1e-9)
        assertEquals(25.0, leg.riderOffsetMetres, 1.0)
        // Remaining counts the hop back onto the course, so it cannot read short.
        assertTrue(leg.remainingMetres > 400.0)
    }
}
