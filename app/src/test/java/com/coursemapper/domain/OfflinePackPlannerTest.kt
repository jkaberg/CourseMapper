package com.coursemapper.domain

import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePackPlannerTest {

    private val planner = OfflinePackPlanner()

    private fun pt(lat: Double, lon: Double, smoothed: Boolean = true) = RoutePoint(
        lat = lat,
        lon = lon,
        altMetres = null,
        accuracyMetres = 5f,
        timestampMs = 0L,
        isSmoothed = smoothed
    )

    @Test
    fun `a planned box contains every point it was built from`() {
        val points = listOf(pt(63.40, 10.30), pt(63.45, 10.40), pt(63.42, 10.25))
        val bounds = (planner.planForPoints(points) as OfflinePackPlanner.PlanResult.Planned).bounds

        points.forEach { p ->
            assertTrue(
                "(${p.lat}, ${p.lon}) fell outside the planned area",
                p.lat in bounds.south..bounds.north && p.lon in bounds.west..bounds.east
            )
        }
    }

    /** No usable geometry must be a typed result, not a silent null. */
    @Test
    fun `a route with no smoothed points reports why rather than returning nothing`() {
        val rejected = listOf(pt(63.4, 10.3, smoothed = false), pt(63.5, 10.4, smoothed = false))

        assertEquals(
            OfflinePackPlanner.PlanResult.NoGeometry,
            planner.planForPoints(rejected)
        )
        assertEquals(
            OfflinePackPlanner.PlanResult.NoGeometry,
            planner.planForPoints(emptyList())
        )
    }

    @Test
    fun `rejected outliers do not drag the area across the county`() {
        val points = listOf(
            pt(63.40, 10.30),
            pt(63.41, 10.31),
            // A GPS outlier the smoother threw out: 100 km away.
            pt(64.40, 11.30, smoothed = false)
        )
        val bounds = (planner.planForPoints(points) as OfflinePackPlanner.PlanResult.Planned).bounds

        assertTrue(
            "the discarded fix must not extend the download area",
            bounds.north < 63.5
        )
    }

    @Test
    fun `the buffer widens the box by roughly the requested distance`() {
        val point = listOf(pt(63.40, 10.30), pt(63.40, 10.30))
        val bounds = (
            planner.planForPoints(point, bufferMetres = 2_000.0)
                as OfflinePackPlanner.PlanResult.Planned
            ).bounds

        val latSpanMetres =
            (bounds.north - bounds.south) * OfflinePackPlanner.METRES_PER_DEGREE_LAT
        assertEquals(
            "2 km on each side of a point is a 4 km tall box",
            4_000.0,
            latSpanMetres,
            50.0
        )
    }

    /** Buffer in metres, not degrees - a degree of longitude is half as wide at 63° N. */
    @Test
    fun `the longitude buffer is corrected for latitude`() {
        val equator = (
            planner.planForPoints(listOf(pt(0.0, 10.0), pt(0.0, 10.0)))
                as OfflinePackPlanner.PlanResult.Planned
            ).bounds
        val north = (
            planner.planForPoints(listOf(pt(63.4, 10.0), pt(63.4, 10.0)))
                as OfflinePackPlanner.PlanResult.Planned
            ).bounds

        assertTrue(
            "the same buffer must span more degrees of longitude further north",
            (north.east - north.west) > (equator.east - equator.west) * 1.8
        )
    }

    @Test
    fun `planning several courses covers all of them`() {
        val a = listOf(pt(63.40, 10.30), pt(63.41, 10.31))
        val b = listOf(pt(63.50, 10.50), pt(63.51, 10.51))

        val union =
            (planner.planForCourses(listOf(a, b)) as OfflinePackPlanner.PlanResult.Planned).bounds
        val justA =
            (planner.planForPoints(a) as OfflinePackPlanner.PlanResult.Planned).bounds
        val justB =
            (planner.planForPoints(b) as OfflinePackPlanner.PlanResult.Planned).bounds

        assertTrue("union must contain the first course", union.contains(justA))
        assertTrue("union must contain the second course", union.contains(justB))
    }

    /** One draft without geometry must not cost the other three their map. */
    @Test
    fun `a course with no geometry is skipped rather than failing the batch`() {
        val good = listOf(pt(63.40, 10.30), pt(63.41, 10.31))
        val empty = emptyList<RoutePoint>()

        val result = planner.planForCourses(listOf(good, empty))

        assertTrue(result is OfflinePackPlanner.PlanResult.Planned)
        val bounds = (result as OfflinePackPlanner.PlanResult.Planned).bounds
        assertTrue(
            bounds.contains(
                (planner.planForPoints(good) as OfflinePackPlanner.PlanResult.Planned).bounds
            )
        )
    }

    @Test
    fun `a batch where every course is unusable reports no geometry`() {
        assertEquals(
            OfflinePackPlanner.PlanResult.NoGeometry,
            planner.planForCourses(listOf(emptyList(), emptyList()))
        )
    }

    @Test
    fun `the four real event courses plan to one area containing each of them`() {
        val courses = listOf(
            GpxFixtures.load(GpxFixtures.MARATHON),
            GpxFixtures.load(GpxFixtures.HALF_MARATHON),
            GpxFixtures.load(GpxFixtures.TEN_KM),
            GpxFixtures.load(GpxFixtures.FIVE_KM)
        )

        val union = (
            planner.planForCourses(courses) as OfflinePackPlanner.PlanResult.Planned
            ).bounds

        courses.forEach { course ->
            val own = (planner.planForPoints(course) as OfflinePackPlanner.PlanResult.Planned).bounds
            assertTrue("one pack must cover every race in the event", union.contains(own))
        }
    }

    @Test
    fun `bounds round-trip through json`() {
        val original = GeoBounds(south = 63.4, north = 63.5, west = 10.3, east = 10.4)
        val parsed = GeoBounds.fromJsonOrNull(original.toJson())

        assertEquals(original, parsed)
    }

    @Test
    fun `malformed bounds json parses to null rather than throwing`() {
        assertEquals(null, GeoBounds.fromJsonOrNull("{}"))
        assertEquals(null, GeoBounds.fromJsonOrNull("not json"))
        assertEquals(null, GeoBounds.fromJsonOrNull("""{"south":1,"north":2,"west":3}"""))
    }

    @Test
    fun `containment and overlap agree with each other`() {
        val outer = GeoBounds(south = 0.0, north = 10.0, west = 0.0, east = 10.0)
        val inner = GeoBounds(south = 2.0, north = 4.0, west = 2.0, east = 4.0)
        val elsewhere = GeoBounds(south = 20.0, north = 30.0, west = 20.0, east = 30.0)

        assertTrue(outer.contains(inner))
        assertTrue(outer.overlaps(inner))
        assertTrue(!outer.contains(elsewhere))
        assertTrue(!outer.overlaps(elsewhere))
    }
}
