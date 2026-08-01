package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.PlacementStop
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.*
import org.junit.Test

/**
 * Visit ordering over the four real Trondheim races: a marathon that laps, a
 * 5 km leaving the marathon route and rejoining, four finishes on one gantry.
 * Synthetic fixtures don't have those.
 */
class RunOrderingRealEventTest {

    private val calc = CumulativeDistanceCalculator()
    private val axisBuilder = CourseAxisBuilder(calc)
    private val planner = PlacementPlanner(calc)
    private val composer = CourseCompositionEngine(calc)
    private val placer = MarkerPlacementEngine(calc)
    private val visitOrder = VisitOrderPlanner(calc, axisBuilder)

    private val MARATHON = 1L
    private val HALF = 2L
    private val TEN_KM = 3L
    private val FIVE_KM = 4L

    private val resources = mapOf(
        MARATHON to GpxFixtures.MARATHON,
        HALF to GpxFixtures.HALF_MARATHON,
        TEN_KM to GpxFixtures.TEN_KM,
        FIVE_KM to GpxFixtures.FIVE_KM
    )

    private fun markersFor(courseId: Long): List<DistanceMarker> {
        val c = composer.compose(GpxFixtures.load(resources.getValue(courseId)), CourseBuildSpec.FixedLaps(1))
        val preset = MarkerPreset(
            id = 1L,
            name = "Every km + endpoints",
            rules = listOf(
                MarkerRule(MarkerType.DISTANCE, intervalMetres = 1000.0),
                MarkerRule(MarkerType.START),
                MarkerRule(MarkerType.FINISH)
            ),
            createdAt = 0L,
            updatedAt = 0L
        )
        return placer.place(
            composedPoints = c.composedPoints,
            totalDistanceMetres = c.totalDistanceMetres,
            preset = preset,
            courseId = courseId,
            lapTemplatePoints = c.lapTemplatePoints,
            laps = c.laps
        ).markers
    }

    private fun stops(): List<PlacementStop> = planner.plan(
        markers = resources.keys.sorted().flatMap { markersFor(it) },
        courseId = 0L,
        groupingThresholdMetres = 10.0
    ).stops

    private fun axisStops(stops: List<PlacementStop>) = stops.map { s ->
        VisitOrderPlanner.AxisStop(
            lat = s.lat, lon = s.lon,
            courseDistances = s.markers.groupBy { it.courseId }
                .mapValues { (_, m) -> m.minOf { it.cumulativeDistanceMetres } }
        )
    }

    private fun spine() = composer
        .compose(GpxFixtures.load(GpxFixtures.MARATHON), CourseBuildSpec.FixedLaps(1))
        .composedPoints

    private fun order(stops: List<PlacementStop>) =
        visitOrder.axisOrder(axisStops(stops), axisBuilder.build(spine())!!)

    /** Visit positions of the stops carrying a marker of [courseId], in visit order. */
    private fun visitedDistances(
        stops: List<PlacementStop>,
        order: List<Int>,
        courseId: Long
    ): List<Double> = order.mapNotNull { idx ->
        stops[idx].markers.filter { it.courseId == courseId }
            .minOfOrNull { it.cumulativeDistanceMetres }
    }

    /** The 5 km's 1 km sign sorted last when its projection landed on the marathon's second lap. */
    @Test
    fun `no stop is visited after the shared finish gantry`() {
        val stops = stops()
        val order = order(stops)

        val finishVisit = order.indexOfFirst { idx ->
            stops[idx].markers.count { it.type == MarkerType.FINISH } == 4
        }
        assertTrue("the four-way finish gantry should be one stop", finishVisit >= 0)
        assertEquals(
            "the gantry must be the last stop of the run",
            order.lastIndex, finishVisit
        )
    }

    /**
     * A course that doesn't lap is driven in its own km order. Not the marathon,
     * km 22 really is 581 m past the start there.
     */
    @Test
    fun `courses that do not lap are visited in their own km order`() {
        val stops = stops()
        val order = order(stops)

        for (courseId in listOf(HALF, TEN_KM, FIVE_KM)) {
            val seq = visitedDistances(stops, order, courseId)
            val inversions = (1 until seq.size).count { seq[it] < seq[it - 1] }
            assertEquals(
                "course $courseId is visited out of order: " +
                    seq.joinToString(",") { "%.0f".format(it / 1000) },
                0, inversions
            )
        }
    }

    /** The 5 km's 4 km sign is 138.8 m off the marathon line but only 599 m along it. */
    @Test
    fun `a stop off the spine is placed by its own course, not by its projection`() {
        val stops = stops()
        val order = order(stops)
        val seq = visitedDistances(stops, order, FIVE_KM)

        assertEquals(
            "the 5 km must be driven 0, 1, 2, 3, 4, 5 km then finish",
            listOf(0.0, 1000.0, 2000.0, 3000.0, 4000.0, 5000.0),
            seq.take(6)
        )
    }

    /** Shortest path must never be longer than follow main route. */
    @Test
    fun `shortest path is never longer than follow the main route`() {
        val stops = stops()
        val points = stops.map { VisitOrderPlanner.StopPoint(it.lat, it.lon) }
        val spineOrder = order(stops)
        val start = spine().firstOrNull()?.let { VisitOrderPlanner.StopPoint(it.lat, it.lon) }

        val spineMetres = visitOrder.pathLengthMetres(points, spineOrder)
        val optimizedMetres = visitOrder.pathLengthMetres(
            points, visitOrder.optimizedOrder(points, start, spineOrder)
        )

        assertTrue(
            "shortest path ${"%.0f".format(optimizedMetres)} m exceeds " +
                "follow-route ${"%.0f".format(spineMetres)} m",
            optimizedMetres <= spineMetres + 0.001
        )
    }

    @Test
    fun `both modes visit every stop exactly once`() {
        val stops = stops()
        val points = stops.map { VisitOrderPlanner.StopPoint(it.lat, it.lon) }
        val spineOrder = order(stops)
        val optimized = visitOrder.optimizedOrder(points, null, spineOrder)

        assertEquals(stops.indices.toSet(), spineOrder.toSet())
        assertEquals(stops.size, spineOrder.size)
        assertEquals(stops.indices.toSet(), optimized.toSet())
        assertEquals(stops.size, optimized.size)
    }

    /**
     * Follow main route walks the spine one way and never doubles back. That's
     * the invariant, not path length - use the other mode for the shorter drive.
     */
    @Test
    fun `the run never doubles back along the spine`() {
        val stops = stops()
        val axis = axisBuilder.build(spine())!!

        var previous = -1.0
        for (idx in order(stops)) {
            val pos = axis.positionOf(
                stops[idx].lat, stops[idx].lon, VisitOrderPlanner.ON_AXIS_TOLERANCE_METRES
            ) ?: continue    // off-spine stops are placed by their own course
            assertTrue(
                "axis position ${"%.0f".format(pos.metres)} follows ${"%.0f".format(previous)}",
                pos.metres >= previous
            )
            previous = pos.metres
        }
    }
}
