package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.PlacementStop
import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four start lines in one plaza. The half and 5 km start 93 m and 55 m before
 * the marathon's gantry, all read 0.00 on the folded axis, and the order fell
 * back to the order the courses were added to the group.
 * See [VisitOrderPlanner.seamRank] and [CourseAxis.metresToOrigin].
 */
class StartPlazaOrderingTest {

    private val calc = CumulativeDistanceCalculator()
    private val axisBuilder = CourseAxisBuilder(calc)
    private val composer = CourseCompositionEngine(calc)
    private val placer = MarkerPlacementEngine(calc)
    private val planner = PlacementPlanner(calc)
    private val visitOrder = VisitOrderPlanner(calc, axisBuilder)

    private val MARATHON = 10L
    private val HALF = 9L
    private val TEN_KM = 8L
    private val FIVE_KM = 7L

    private val names = mapOf(
        MARATHON to "Helmaraton", HALF to "Halvmaraton",
        TEN_KM to "10km", FIVE_KM to "5km"
    )

    private val resources = mapOf(
        MARATHON to "current_helmaraton.csv",
        HALF to "current_halvmaraton.csv",
        TEN_KM to "current_10km.csv",
        FIVE_KM to "current_5km.csv"
    )

    /** Measured lengths exactly as the organiser has them stored. */
    private val measured = mapOf(
        MARATHON to 42_195.0, HALF to 21_098.0, TEN_KM to 10_000.0, FIVE_KM to 5_000.0
    )

    private fun load(res: String): List<RoutePoint> {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(res)) {
            "missing test resource: $res"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return text.trim().lines().map { line ->
            val (la, lo) = line.split(",")
            RoutePoint(la.toDouble(), lo.toDouble(), null, 5f, 0L, isSmoothed = true)
        }
    }

    private val preset = MarkerPreset(
        id = 1L, name = "km + endpoints",
        rules = listOf(
            MarkerRule(MarkerType.DISTANCE, intervalMetres = 1000.0),
            MarkerRule(MarkerType.START),
            MarkerRule(MarkerType.FINISH)
        ),
        createdAt = 0L, updatedAt = 0L
    )

    private fun markers(courseId: Long): List<DistanceMarker> {
        val pts = load(resources.getValue(courseId))
        val c = composer.compose(pts, CourseBuildSpec.FixedLaps(1))
        return placer.place(
            composedPoints = c.composedPoints,
            totalDistanceMetres = c.totalDistanceMetres,
            preset = preset,
            courseId = courseId,
            lapTemplatePoints = c.lapTemplatePoints,
            laps = c.laps,
            distanceScale = c.totalDistanceMetres / measured.getValue(courseId)
        ).markers
    }

    private fun axis(): CourseAxis = axisBuilder.build(
        composer.compose(load(resources.getValue(MARATHON)), CourseBuildSpec.FixedLaps(1))
            .composedPoints
    )!!

    /** [order] is the order the courses were added to the combined course. */
    private fun stops(order: List<Long>): List<PlacementStop> = planner.plan(
        markers = order.flatMap { markers(it) },
        courseId = 0L,
        groupingThresholdMetres = 10.0
    ).stops

    private fun visitOrderOf(stops: List<PlacementStop>): List<Int> = visitOrder.axisOrder(
        stops.map { s ->
            VisitOrderPlanner.AxisStop(
                lat = s.lat, lon = s.lon,
                courseDistances = s.markers.groupBy { it.courseId }
                    .mapValues { (_, m) -> m.minOf { it.cumulativeDistanceMetres } }
            )
        },
        axis()
    )

    private fun km(value: Double) = "${String.format("%.1f", value)} km"

    private fun describe(stop: PlacementStop) =
        stop.markers.joinToString(" · ") { "${names[it.courseId]} ${it.label}" }

    private fun openingStops(order: List<Long>, count: Int): List<String> {
        val s = stops(order)
        return visitOrderOf(s).take(count).map { describe(s[it]) }
    }

    /** The distances before the origin that the folded axis can't express. */
    @Test
    fun `the plaza start lines are measured against the origin`() {
        val a = axis()
        val marathon = load(resources.getValue(MARATHON))

        fun startOf(courseId: Long) = markers(courseId).first { it.type == MarkerType.START }

        val toOrigin = resources.keys.associateWith { id ->
            val m = startOf(id)
            a.metresToOrigin(m.lat, m.lon)
        }

        assertEquals("the spine's own start is the origin", 0.0, toOrigin[MARATHON]!!, 0.5)
        assertEquals("the half starts 93 m before it", 93.5, toOrigin[HALF]!!, 1.0)
        assertEquals("the 5 km starts 55 m before it", 55.2, toOrigin[FIVE_KM]!!, 1.0)
        // The 10 km starts up the road, not in the plaza: it is nearly a whole
        // lap from reaching the origin again.
        assertTrue(
            "the 10 km is not in the plaza (${toOrigin[TEN_KM]})",
            toOrigin[TEN_KM]!! > VisitOrderPlanner.START_PLAZA_METRES
        )

        // And the axis alone genuinely cannot tell them apart.
        val positions = resources.keys.map { id ->
            val m = startOf(id)
            a.positionOf(m.lat, m.lon)?.metres
        }
        assertEquals(
            "three of the four start lines read the same axis position",
            3, positions.count { it != null && it < 0.5 }
        )
        assertNotNull(marathon)
    }

    /** A place the course never goes has no answer, rather than a wrong one. */
    @Test
    fun `somewhere off the course has no reading`() {
        assertNull(axis().metresToOrigin(63.0, 10.0))
    }

    /** Driving in you pass the half's start, then the 5 km's, then the gantry. */
    @Test
    fun `the plaza is visited in the order it is driven past`() {
        val opening = openingStops(listOf(MARATHON, HALF, TEN_KM, FIVE_KM), 4)
        assertEquals(
            listOf(
                "Halvmaraton Start",
                "5km Start",
                "Helmaraton Start",
                "10km Start"
            ),
            opening
        )
    }

    /** Every permutation of group order opens the same way. */
    @Test
    fun `the order does not depend on how the courses were combined`() {
        val orders = listOf(
            listOf(MARATHON, HALF, TEN_KM, FIVE_KM),   // as the organiser's group is stored
            listOf(FIVE_KM, TEN_KM, HALF, MARATHON),   // ascending course id
            listOf(HALF, FIVE_KM, MARATHON, TEN_KM),
            listOf(TEN_KM, MARATHON, FIVE_KM, HALF)
        )
        val openings = orders.map { openingStops(it, 4) }
        openings.forEach { assertEquals(openings.first(), it) }
    }

    /** No course may be sent to its own first kilometre before its start line. */
    @Test
    fun `no course reaches its own 1 km sign before its start`() {
        val s = stops(listOf(MARATHON, HALF, TEN_KM, FIVE_KM))
        val order = visitOrderOf(s)

        for (courseId in resources.keys) {
            val startVisit = order.indexOfFirst { idx ->
                s[idx].markers.any { it.courseId == courseId && it.type == MarkerType.START }
            }
            val firstKmVisit = order.indexOfFirst { idx ->
                s[idx].markers.any {
                    it.courseId == courseId && it.type == MarkerType.DISTANCE &&
                        it.cumulativeDistanceMetres <= 1000.0
                }
            }
            assertTrue(
                "${names[courseId]} is sent to its 1 km sign (visit $firstKmVisit) " +
                    "before its start line (visit $startVisit)",
                startVisit in 0 until firstKmVisit
            )
        }
    }

    /** Km order is still kept. Not the marathon, it's folded to one lap on purpose. */
    @Test
    fun `every single-lap course is still visited in its own km order`() {
        val s = stops(listOf(MARATHON, HALF, TEN_KM, FIVE_KM))
        val order = visitOrderOf(s)

        for (courseId in listOf(HALF, TEN_KM, FIVE_KM)) {
            val seq = order.mapNotNull { idx ->
                s[idx].markers.filter { it.courseId == courseId }
                    .minOfOrNull { it.cumulativeDistanceMetres }
            }
            val inversions = (1 until seq.size).count { seq[it] < seq[it - 1] }
            assertEquals(
                "${names[courseId]} is visited out of order: " +
                    seq.joinToString(",") { "%.0f".format(it / 1000) },
                0, inversions
            )
        }
    }

    /** The seam rule must not disturb anything past the plaza. */
    @Test
    fun `the rest of the run is untouched`() {
        val s = stops(listOf(MARATHON, HALF, TEN_KM, FIVE_KM))
        val order = visitOrderOf(s)
        val afterPlaza = order.drop(4).take(5).map { describe(s[it]) }
        assertEquals(
            // labels follow the default locale, same as the engine
            listOf(
                "Helmaraton ${km(22.0)}",
                "Halvmaraton ${km(1.0)}",
                "5km ${km(1.0)}",
                "Helmaraton ${km(1.0)}",
                "10km ${km(1.0)}"
            ),
            afterPlaza
        )
    }

    /** A course with no lap and no plaza behind it is unaffected. */
    @Test
    fun `an ordinary course is ranked exactly as before`() {
        val tenKm = load(resources.getValue(TEN_KM))
        val a = axisBuilder.build(tenKm)!!
        val stop = VisitOrderPlanner.AxisStop(
            lat = tenKm[40].lat, lon = tenKm[40].lon,
            courseDistances = mapOf(TEN_KM to 3_000.0)
        )
        val order = visitOrder.axisOrder(listOf(stop), a)
        assertEquals(listOf(0), order)
    }
}
