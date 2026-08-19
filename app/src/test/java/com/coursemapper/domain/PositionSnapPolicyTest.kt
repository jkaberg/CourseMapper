package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class PositionSnapPolicyTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var policy: PositionSnapPolicy

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    /** The dwell radius in force in these tests - the shipped default. */
    private val dwellRadius = DwellEngine.DEFAULT_RADIUS_M

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        policy = PositionSnapPolicy()
    }

    private fun latOf(northM: Double) = lat0 + northM / metresPerDegLat
    private fun lonOf(eastM: Double) = 10.4 + eastM / metresPerDegLon

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = latOf(northM), lon = lonOf(eastM),
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    /** A course running due east for 1 km at [northM]. */
    private fun eastCourse(id: Long, northM: Double = 0.0) =
        CorridorAnalyzer.IndexedCourse(
            id,
            PolylineIndex.build((0..100).map { pt(northM, it * 10.0) }, calc)!!
        )

    /** Driving east, well above the movement threshold. */
    private fun snapWhileDriving(
        courses: List<CorridorAnalyzer.IndexedCourse>,
        northM: Double,
        eastM: Double,
        bearing: Float? = 90f,
        speed: Float? = 6f,
        distanceToStop: Double? = null,
        radius: Double = PositionSnapPolicy.DEFAULT_RADIUS_M,
        state: PositionSnapPolicy.State = PositionSnapPolicy.State.IDLE
    ) = policy.snap(
        courses = courses,
        lat = latOf(northM),
        lon = lonOf(eastM),
        travelBearingDeg = bearing,
        speedMetresPerSecond = speed,
        distanceToActiveStopMetres = distanceToStop,
        dwellRadiusMetres = dwellRadius,
        radiusMetres = radius,
        state = state
    )

    @Test
    fun `a rider driving just off the line is drawn on it`() {
        val (snap, state) = snapWhileDriving(listOf(eastCourse(1L)), northM = 6.0, eastM = 500.0)

        assertNotNull(snap)
        assertEquals(1L, snap!!.courseId)
        assertEquals(6.0, snap.offsetMetres, 0.5)
        assertEquals(500.0, snap.cumulativeMetres, 1.0)
        // Drawn ON the line, not where the fix was.
        assertEquals(0.0, calc.haversineMetres(snap.lat, snap.lon, latOf(0.0), lonOf(500.0)), 1.0)
        assertTrue(state.isSnapped)
    }

    @Test
    fun `a rider well off the line is left where they are`() {
        val (snap, state) = snapWhileDriving(listOf(eastCourse(1L)), northM = 40.0, eastM = 500.0)
        assertNull(snap)
        assertTrue(!state.isSnapped)
    }

    @Test
    fun `a zero radius turns snapping off`() {
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, radius = 0.0)
        assertNull("radius 0 must mean off, not 'snap to anything'", snap)
    }

    @Test
    fun `no courses means nothing to snap to`() {
        val (snap, _) = snapWhileDriving(emptyList(), 0.0, 500.0)
        assertNull(snap)
    }

    @Test
    fun `an engaged snap holds past the entry radius`() {
        val course = listOf(eastCourse(1L))
        val (_, engaged) = snapWhileDriving(course, northM = 6.0, eastM = 400.0)

        // 13 m out: beyond the 10 m entry radius, inside the 15 m release radius.
        val (held, _) = snapWhileDriving(course, northM = 13.0, eastM = 500.0, state = engaged)
        assertNotNull("an engaged snap must not drop at the entry radius", held)

        // From cold, the same position would not snap at all.
        val (cold, _) = snapWhileDriving(course, northM = 13.0, eastM = 500.0)
        assertNull(cold)
    }

    @Test
    fun `a snap releases beyond the release radius`() {
        val course = listOf(eastCourse(1L))
        val (_, engaged) = snapWhileDriving(course, northM = 6.0, eastM = 400.0)

        val (released, state) = snapWhileDriving(course, northM = 20.0, eastM = 500.0, state = engaged)
        assertNull(released)
        assertTrue(!state.isSnapped)
    }

    @Test
    fun `the puck stays on the course it is already on`() {
        // Two courses 8 m apart, the rider between them and marginally nearer
        // the second.  Without the incumbent rule the puck would hop across.
        val courses = listOf(eastCourse(1L, northM = 0.0), eastCourse(2L, northM = 8.0))
        val (first, engaged) = snapWhileDriving(courses, northM = 3.0, eastM = 400.0)
        assertEquals(1L, first!!.courseId)

        val (second, _) = snapWhileDriving(courses, northM = 4.5, eastM = 500.0, state = engaged)
        assertEquals("the puck must not hop between courses on a shared road", 1L, second!!.courseId)
    }

    @Test
    fun `a stationary rider is never snapped`() {
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, speed = 0.3f)
        assertNull("standing beside the course is not driving it", snap)
    }

    @Test
    fun `an unknown speed is treated as stopped`() {
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, speed = null)
        assertNull(snap)
    }

    @Test
    fun `crossing the course does not snap to it`() {
        // Driving due north across an east-west course.
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, bearing = 0f)
        assertNull("a perpendicular crossing must not capture the puck", snap)
    }

    @Test
    fun `driving the course backwards still snaps`() {
        // Sweeping back along the course to collect a missed stop is normal.
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, bearing = 270f)
        assertNotNull("driving the course in reverse is still driving it", snap)
    }

    @Test
    fun `an unknown bearing does not block snapping`() {
        val (snap, _) = snapWhileDriving(listOf(eastCourse(1L)), 6.0, 500.0, bearing = null)
        assertNotNull(snap)
    }

    @Test
    fun `nothing snaps inside the dwell radius of the active stop`() {
        // The case that would put the puck inside the target ring while the
        // banner still said "not arrived".
        val (snap, _) = snapWhileDriving(
            listOf(eastCourse(1L)), 6.0, 500.0,
            distanceToStop = dwellRadius - 5.0
        )
        assertNull(snap)

        val (outside, _) = snapWhileDriving(
            listOf(eastCourse(1L)), 6.0, 500.0,
            distanceToStop = dwellRadius + 30.0
        )
        assertNotNull("outside the arrival zone, snapping resumes", outside)
    }

    @Test
    fun `leaving the arrival zone re-engages without stale state`() {
        val course = listOf(eastCourse(1L))
        val (_, engaged) = snapWhileDriving(course, 6.0, 400.0)

        // Arrive: state must clear, not merely stop reporting.
        val (atStop, cleared) = snapWhileDriving(
            course, 6.0, 450.0, distanceToStop = 10.0, state = engaged
        )
        assertNull(atStop)
        assertTrue("arriving must clear the snap, not hide it", !cleared.isSnapped)
    }
}
