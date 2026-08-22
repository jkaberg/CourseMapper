package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Bridging a short gap must not jump along the partner. A course taking a link
 * road and rejoining kilometres later along the partner would otherwise get a
 * corridor over ground they don't share, and the link road disappears.
 */
class CorridorBridgeContinuityTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var analyzer: CorridorAnalyzer
    private lateinit var display: CourseDisplayPlanner

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        analyzer = CorridorAnalyzer(calc)
        display = CourseDisplayPlanner(calc, analyzer)
    }

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    private fun leg(
        fromNorth: Double, fromEast: Double,
        toNorth: Double, toEast: Double,
        stepM: Double = 5.0
    ): List<RoutePoint> {
        val dN = toNorth - fromNorth
        val dE = toEast - fromEast
        val steps = maxOf(1, (hypot(dN, dE) / stepM).toInt())
        return (0..steps).map { i ->
            val t = i.toDouble() / steps
            pt(fromNorth + dN * t, fromEast + dE * t)
        }
    }

    private fun course(id: Long, points: List<RoutePoint>) =
        CorridorAnalyzer.IndexedCourse(id, PolylineIndex.build(points, calc)!!)

    /** Distance from a position to the nearest of [paths]. */
    private fun distanceToPaths(
        paths: List<List<Pair<Double, Double>>>,
        lat: Double,
        lon: Double,
        stopBelow: Double = 0.0
    ): Double {
        var nearest = Double.MAX_VALUE
        for (path in paths) {
            for (i in 0 until path.size - 1) {
                val proj = calc.closestPointOnSegment(
                    path[i].first, path[i].second,
                    path[i + 1].first, path[i + 1].second,
                    lat, lon
                )
                val d = calc.haversineMetres(lat, lon, proj.lat, proj.lon)
                if (d < nearest) nearest = d
                if (nearest <= stopBelow) return nearest
            }
        }
        return nearest
    }

    /**
     * Long course runs east, loops 600 m north and comes back further east. The
     * short one skips the loop with a 50 m link, shorter than
     * [CorridorAnalyzer.MAX_GAP_METRES].
     */
    private fun shortcutPair(): Pair<CorridorAnalyzer.IndexedCourse, CorridorAnalyzer.IndexedCourse> {
        val long =
            leg(0.0, 0.0, 0.0, 400.0) +          // shared street, west half
            leg(0.0, 400.0, 600.0, 400.0) +      // 600 m north
            leg(600.0, 400.0, 600.0, 450.0) +    // across the top
            leg(600.0, 450.0, 0.0, 450.0) +      // 600 m back south
            leg(0.0, 450.0, 0.0, 900.0)          // shared street, east half
        val short =
            leg(0.0, 0.0, 0.0, 400.0) +          // the same street
            leg(0.0, 400.0, 0.0, 450.0, stepM = 2.0) +  // the 50 m link
            leg(0.0, 450.0, 0.0, 900.0)          // the same street again
        return course(1L, long) to course(2L, short)
    }

    @Test
    fun `a corridor never claims far more of the partner than of its own course`() {
        val (long, short) = shortcutPair()

        val analysis = analyzer.analyze(listOf(long, short))

        assertTrue("no corridor was found at all", analysis.corridors.isNotEmpty())
        analysis.corridors.forEach { corridor ->
            corridor.partners.forEach { member ->
                assertTrue(
                    "corridor %.0f..%.0f on course %d (len %.0f m) claims %.0f m of course %d".format(
                        corridor.fromMetres, corridor.toMetres, corridor.courseId,
                        corridor.lengthMetres, member.lengthMetres, member.courseId
                    ),
                    member.lengthMetres <= corridor.lengthMetres + 2 * CorridorAnalyzer.MAX_GAP_METRES
                )
            }
        }
    }

    @Test
    fun `the link road only the short course runs is still drawn`() {
        val (long, short) = shortcutPair()

        val drawn = display.plan(listOf(long, short)).coursePaths.flatMap { it.paths }

        // Midpoint of the 50 m link, which no other course runs.
        val (lat, lon) = short.index.pointAt(425.0)
        val nearest = distanceToPaths(drawn, lat, lon, stopBelow = 1.0)

        assertTrue(
            "nothing is drawn within %.0f m of the link road".format(nearest),
            nearest <= CorridorAnalyzer.ENTER_TOLERANCE_METRES
        )
    }

    /**
     * A real short interruption still bridges: 55 m on its own course, 25 m off
     * the partner, and the partner only moves 24 m.
     */
    @Test
    fun `a brief excursion that rejoins the partner in place is still bridged`() {
        val straight = course(1L, leg(0.0, 0.0, 0.0, 900.0))
        val wobble = course(
            2L,
            leg(0.0, 0.0, 0.0, 400.0) +
                leg(0.0, 400.0, 25.0, 412.0) +   // out to a lay-by
                leg(25.0, 412.0, 0.0, 424.0) +   // and back onto the same street
                leg(0.0, 424.0, 0.0, 900.0)
        )

        val corridors = analyzer.analyze(listOf(straight, wobble)).corridorsFor(2L)

        assertEquals(
            "the lay-by split the corridor instead of being bridged: " +
                corridors.joinToString { "%.0f..%.0f".format(it.fromMetres, it.toMetres) },
            1, corridors.size
        )
    }

    private fun realEvent() = listOf(
        1L to GpxFixtures.MARATHON,
        2L to GpxFixtures.HALF_MARATHON,
        3L to GpxFixtures.TEN_KM,
        4L to GpxFixtures.FIVE_KM
    ).mapNotNull { (id, res) -> display.index(id, GpxFixtures.load(res)) }

    /**
     * No stretch of a route may go undrawn. Parting up to the exit tolerance
     * inside a corridor is fine, but a course alone for
     * [CorridorAnalyzer.MIN_RUN_METRES] must be drawn. Worst on this event is 25 m.
     */
    @Test
    fun `no real course goes undrawn for a whole stretch of route`() {
        val indexed = realEvent()
        val tolerance = CorridorAnalyzer.ENTER_TOLERANCE_METRES
        val drawn = display.plan(indexed, toleranceMetres = tolerance).coursePaths.flatMap { it.paths }

        for (course in indexed) {
            var undrawnFrom: Double? = null
            var longest = 0.0
            var longestAt = 0.0

            fun close(until: Double) {
                undrawnFrom?.let {
                    if (until - it > longest) { longest = until - it; longestAt = it }
                }
                undrawnFrom = null
            }

            for (sample in course.index.sample(5.0)) {
                val d = distanceToPaths(
                    drawn, sample.lat, sample.lon,
                    stopBelow = CorridorAnalyzer.EXIT_TOLERANCE_METRES
                )
                if (d > CorridorAnalyzer.EXIT_TOLERANCE_METRES) {
                    if (undrawnFrom == null) undrawnFrom = sample.cumulativeMetres
                } else {
                    close(sample.cumulativeMetres)
                }
            }
            close(course.index.lengthMetres)

            assertTrue(
                "course %d has %.0f m of route from %.0f m with no line within %.0f m of it".format(
                    course.courseId, longest, longestAt, CorridorAnalyzer.EXIT_TOLERANCE_METRES
                ),
                longest < CorridorAnalyzer.MIN_RUN_METRES
            )
        }
    }

    /** A course's colour may only appear where that course actually runs. */
    @Test
    fun `no course colour is drawn on ground that course never ran`() {
        val indexed = realEvent()
        val plan = display.plan(indexed, toleranceMetres = CorridorAnalyzer.ENTER_TOLERANCE_METRES)

        for (course in indexed) {
            val paths = plan.coursePaths.first { it.courseId == course.courseId }.paths
            var strayed = 0.0
            paths.forEach { path ->
                for (i in 0 until path.size - 1) {
                    val midLat = (path[i].first + path[i + 1].first) / 2
                    val midLon = (path[i].second + path[i + 1].second) / 2
                    val off = course.index.nearest(midLat, midLon, 5_000.0)?.offsetMetres ?: 9_999.0
                    if (off > 2 * CorridorAnalyzer.EXIT_TOLERANCE_METRES) {
                        strayed += calc.haversineMetres(
                            path[i].first, path[i].second, path[i + 1].first, path[i + 1].second
                        )
                    }
                }
            }
            assertEquals(
                "course ${course.courseId} has its colour painted on ground it never ran",
                0.0, strayed, 0.5
            )
        }
    }
}
