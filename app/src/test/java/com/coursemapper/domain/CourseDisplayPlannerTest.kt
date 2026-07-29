package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class CourseDisplayPlannerTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var planner: CourseDisplayPlanner

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        planner = CourseDisplayPlanner(calc, CorridorAnalyzer(calc))
    }

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    private fun eastLine(northM: Double, fromEastM: Double, toEastM: Double, stepM: Double = 5.0) =
        generateSequence(fromEastM) { it + stepM }
            .takeWhile { it <= toEastM + 1e-9 }
            .map { pt(northM, it) }
            .toList()

    private fun course(id: Long, points: List<RoutePoint>) =
        CorridorAnalyzer.IndexedCourse(id, PolylineIndex.build(points, calc)!!)

    private fun pathLength(path: List<Pair<Double, Double>>): Double =
        path.zipWithNext { a, b -> calc.haversineMetres(a.first, a.second, b.first, b.second) }.sum()

    private fun CourseDisplayPlanner.Plan.pathsFor(courseId: Long) =
        coursePaths.first { it.courseId == courseId }.paths

    private fun CourseDisplayPlanner.Plan.drawnMetres(courseId: Long) =
        pathsFor(courseId).sumOf { pathLength(it) }

    @Test
    fun `a lone course is drawn whole`() {
        val plan = planner.plan(listOf(course(1L, eastLine(0.0, 0.0, 1000.0))))

        assertEquals(1, plan.pathsFor(1L).size)
        assertEquals(1000.0, plan.drawnMetres(1L), 5.0)
        assertTrue(plan.sharedCasings.isEmpty())
    }

    @Test
    fun `separate courses are each drawn whole`() {
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(500.0, 0.0, 800.0))
            )
        )

        assertEquals(1000.0, plan.drawnMetres(1L), 5.0)
        assertEquals(800.0, plan.drawnMetres(2L), 5.0)
        assertTrue(plan.sharedCasings.isEmpty())
    }

    @Test
    fun `unify off restores the plain one-line-per-course picture`() {
        val courses = listOf(
            course(1L, eastLine(0.0, 0.0, 1000.0)),
            course(2L, eastLine(6.0, 0.0, 1000.0))
        )
        val plan = planner.plan(courses, unifyShared = false)

        assertEquals(1, plan.pathsFor(1L).size)
        assertEquals(1, plan.pathsFor(2L).size)
        assertTrue(plan.sharedCasings.isEmpty())
        assertTrue(plan.analysis.corridors.isEmpty())
    }

    @Test
    fun `a fully shared road is drawn once, not twice`() {
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0))
            )
        )

        // Two courses, 1 km each: drawn naively that is 2 km of line.  Unified,
        // the ribbon is one 1 km line split between the two colours.
        val total = plan.drawnMetres(1L) + plan.drawnMetres(2L)
        assertEquals(1000.0, total, 100.0)
        assertEquals(1, plan.sharedCasings.size)
        assertEquals(1000.0, pathLength(plan.sharedCasings.single()), 20.0)
    }

    @Test
    fun `both courses appear in the shared ribbon`() {
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0))
            )
        )

        // The whole point of the colour switching: neither course disappears.
        assertTrue("course 1 must appear on the shared road", plan.drawnMetres(1L) > 100.0)
        assertTrue("course 2 must appear on the shared road", plan.drawnMetres(2L) > 100.0)
    }

    @Test
    fun `chunks alternate between the courses`() {
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0))
            ),
            chunkMetres = listOf(40.0)
        )

        // ~1000 m of ribbon at 40 m a chunk, dealt between two courses.
        assertTrue(plan.pathsFor(1L).size >= 10)
        assertTrue(plan.pathsFor(2L).size >= 10)
        plan.pathsFor(1L).forEach {
            assertTrue("chunk far longer than asked for: ${pathLength(it)}", pathLength(it) < 60.0)
        }
    }

    @Test
    fun `every participant gets a turn on a short corridor`() {
        // 120 m shared by three courses at the default 40 m chunk would be three
        // chunks - just enough - but the floor must hold as it gets shorter.
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 90.0)),
                course(2L, eastLine(4.0, 0.0, 90.0)),
                course(3L, eastLine(-4.0, 0.0, 90.0))
            )
        )

        listOf(1L, 2L, 3L).forEach { id ->
            assertTrue("course $id vanished from a short corridor", plan.pathsFor(id).isNotEmpty())
        }
    }

    @Test
    fun `the unshared part of a course stays its own`() {
        // Course 2 shares only the first 400 m of course 1's kilometre.
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 400.0))
            )
        )

        // Course 1 keeps its 600 m solo tail plus its share of the ribbon.
        assertTrue(plan.drawnMetres(1L) > 600.0)
        // Course 2 only ever appears inside the ribbon.
        assertTrue(plan.drawnMetres(2L) < 400.0)
        assertEquals(400.0, pathLength(plan.sharedCasings.single()), 40.0)
    }

    @Test
    fun `a course leaving a ribbon is stitched to it`() {
        // Course 2 runs beside course 1 for 400 m, then strikes off north.
        val branching = eastLine(6.0, 0.0, 400.0) + (1..40).map { pt(6.0 + it * 10.0, 400.0) }
        val plan = planner.plan(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, branching)
            )
        )

        // Course 2's solo path must begin on the ribbon it just left, not 6 m
        // beside it - otherwise the map shows a floating line end.
        val soloTail = plan.pathsFor(2L).maxByOrNull { pathLength(it) }
        assertNotNull(soloTail)
        val ribbon = plan.sharedCasings.single()
        val nearestRibbonPoint = ribbon.minOf { r ->
            calc.haversineMetres(r.first, r.second, soloTail!!.first().first, soloTail.first().second)
        }
        assertTrue(
            "solo stretch starts $nearestRibbonPoint m from the ribbon it leaves",
            nearestRibbonPoint < 1.0
        )
    }

    @Test
    fun `the three real races draw less line than they would separately`() {
        val courses = listOf(
            course(1L, GpxFixtures.load(GpxFixtures.FIVE_KM)),
            course(2L, GpxFixtures.load(GpxFixtures.TEN_KM)),
            course(3L, GpxFixtures.load(GpxFixtures.HALF_MARATHON))
        )
        val separately = courses.sumOf { it.index.lengthMetres }

        val plan = planner.plan(courses)
        val unified = courses.sumOf { plan.drawnMetres(it.courseId) }

        assertTrue("unifying must remove duplicated line, not add it", unified < separately)
        // These races overlap heavily; expect a real reduction, not a rounding one.
        assertTrue(
            "expected a meaningful reduction, drew $unified of $separately m",
            unified < separately * 0.85
        )
    }

    @Test
    fun `every real course still appears on the map`() {
        val courses = listOf(
            course(1L, GpxFixtures.load(GpxFixtures.FIVE_KM)),
            course(2L, GpxFixtures.load(GpxFixtures.TEN_KM)),
            course(3L, GpxFixtures.load(GpxFixtures.HALF_MARATHON))
        )
        val plan = planner.plan(courses)

        courses.forEach { c ->
            assertTrue(
                "course ${c.courseId} was unified out of existence",
                plan.drawnMetres(c.courseId) > 500.0
            )
        }
    }

    @Test
    fun `the drawn picture covers the same ground as the courses`() {
        // Unifying must not lose ground: every metre of every course is still
        // covered by something on the map, whether its own line or a ribbon.
        val courses = listOf(
            course(1L, GpxFixtures.load(GpxFixtures.FIVE_KM)),
            course(2L, GpxFixtures.load(GpxFixtures.TEN_KM))
        )
        val plan = planner.plan(courses)

        val drawn = plan.coursePaths.flatMap { it.paths } + plan.sharedCasings
        val drawnIndices = drawn.filter { it.size >= 2 }.mapNotNull { path ->
            PolylineIndex.build(
                path.map { (lat, lon) ->
                    RoutePoint(lat, lon, null, null, 0L, true)
                },
                calc
            )
        }

        courses.forEach { c ->
            val uncovered = c.index.sample(50.0).count { sample ->
                drawnIndices.none { it.nearest(sample.lat, sample.lon, 12.0) != null }
            }
            assertEquals("course ${c.courseId} has ground nothing draws", 0, uncovered)
        }
    }
}
