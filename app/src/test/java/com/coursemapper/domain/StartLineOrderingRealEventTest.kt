package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.PlacementStop
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visit ordering over the event as stored on the phone, where the marathon's
 * route starts at its own gantry, 125 m on from where the half and 5 km start.
 * Those starts then sit 32 m and 55 m off the axis, beyond
 * [VisitOrderPlanner.ON_AXIS_TOLERANCE_METRES], and each got dragged up the road
 * next to its own 1 km sign:
 *
 *     Halvmaraton Start, Halvmaraton 1 km, 5km Start, 5km 1 km, Helmaraton 1 km
 *
 * The four starts are within 55 m of each other and belong at the front.
 */
class StartLineOrderingRealEventTest {

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
        HALF to GpxFixtures.HALF_MARATHON_CURRENT,
        TEN_KM to GpxFixtures.TEN_KM,
        FIVE_KM to GpxFixtures.FIVE_KM
    )

    /**
     * The marathon's start as stored on the phone: the recorded track with its
     * first point moved to the gantry. Gives a 21 293.8 m lap with the half's start
     * 32.1 m off and the 5 km's 55.2 m off.
     */
    private val marathonStartLine = RoutePoint(
        lat = 63.4303636916846, lon = 10.3986301740133,
        altMetres = null, accuracyMetres = 5f, timestampMs = 0L, isSmoothed = true
    )

    private fun geometry(courseId: Long): List<RoutePoint> {
        val raw = GpxFixtures.load(resources.getValue(courseId))
        return if (courseId == MARATHON) listOf(marathonStartLine) + raw.drop(1) else raw
    }

    private fun markersFor(courseId: Long): List<DistanceMarker> {
        val c = composer.compose(geometry(courseId), CourseBuildSpec.FixedLaps(1))
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

    private fun order(stops: List<PlacementStop>) = visitOrder.axisOrder(
        stops.map { s ->
            VisitOrderPlanner.AxisStop(
                lat = s.lat, lon = s.lon,
                courseDistances = s.markers.groupBy { it.courseId }
                    .mapValues { (_, m) -> m.minOf { it.cumulativeDistanceMetres } }
            )
        },
        axisBuilder.build(composer.compose(geometry(MARATHON), CourseBuildSpec.FixedLaps(1)).composedPoints)!!
    )

    private fun isStart(stop: PlacementStop) = stop.markers.any { it.type == MarkerType.START }

    /** The geometry this test depends on, asserted so it cannot drift silently. */
    @Test
    fun `three of the four start lines sit off the marathon axis`() {
        val axis = axisBuilder.build(
            composer.compose(geometry(MARATHON), CourseBuildSpec.FixedLaps(1)).composedPoints
        )!!
        val offAxis = stops().filter { isStart(it) }.count { stop ->
            axis.positionOf(stop.lat, stop.lon, VisitOrderPlanner.ON_AXIS_TOLERANCE_METRES) == null
        }
        assertTrue("the start lines must not all sit on the axis", offAxis > 0)
    }

    /** Every start line is in the plaza, so they come before anything a km up the road. */
    @Test
    fun `the start lines are the first stops of the run`() {
        val stops = stops()
        val order = order(stops)
        val startCount = stops.count { isStart(it) }

        val leading = order.take(startCount).count { isStart(stops[it]) }
        assertEquals(
            "the first $startCount stops should be the $startCount start lines, but the run opens " +
                order.take(startCount + 2).joinToString(", ") { idx ->
                    stops[idx].markers.joinToString("·") { it.label }
                },
            startCount, leading
        )
    }

    /** No start line may be visited after its own course's first kilometre. */
    @Test
    fun `no course reaches its own 1 km sign before its start`() {
        val stops = stops()
        val order = order(stops)

        for (courseId in resources.keys) {
            val startVisit = order.indexOfFirst { idx ->
                stops[idx].markers.any { it.courseId == courseId && it.type == MarkerType.START }
            }
            val firstKmVisit = order.indexOfFirst { idx ->
                stops[idx].markers.any {
                    it.courseId == courseId && it.type == MarkerType.DISTANCE &&
                        it.cumulativeDistanceMetres <= 1000.0
                }
            }
            assertTrue(
                "course $courseId is sent to its 1 km sign (visit $firstKmVisit) before its " +
                    "start line (visit $startVisit)",
                startVisit >= 0 && firstKmVisit >= 0 && startVisit < firstKmVisit
            )
        }
    }

    /** The invariant the whole ordering exists to keep, on this geometry too. */
    @Test
    fun `every course is visited in its own km order`() {
        val stops = stops()
        val order = order(stops)

        for (courseId in listOf(HALF, TEN_KM, FIVE_KM)) {
            val seq = order.mapNotNull { idx ->
                stops[idx].markers.filter { it.courseId == courseId }
                    .minOfOrNull { it.cumulativeDistanceMetres }
            }
            val inversions = (1 until seq.size).count { seq[it] < seq[it - 1] }
            assertEquals(
                "course $courseId is visited out of order: " +
                    seq.joinToString(",") { "%.0f".format(it / 1000) },
                0, inversions
            )
        }
    }
}
