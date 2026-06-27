package com.coursemapper.domain

import com.coursemapper.domain.VisitOrderPlanner.StopPoint
import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VisitOrderPlannerTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var planner: VisitOrderPlanner

    @Before
    fun setUp() {
        calc    = CumulativeDistanceCalculator()
        planner = VisitOrderPlanner(calc, CourseAxisBuilder(calc))
    }

    private fun rp(lat: Double, lon: Double) = RoutePoint(
        lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
        timestampMs = 0L, isSmoothed = true
    )

    @Test
    fun `spine order sorts stops by projected position along the spine`() {
        // Spine runs straight north along lon 0.
        val spine = (0..10).map { rp(it * 0.01, 0.0) }
        // Stops given shuffled, slightly off-spine.
        val stops = listOf(
            StopPoint(0.08, 0.0005),  // far along
            StopPoint(0.01, -0.0005), // near start
            StopPoint(0.05, 0.0002)   // middle
        )
        val order = planner.spineOrder(stops, spine)
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun `spine order with degenerate spine returns input order`() {
        val stops = listOf(StopPoint(0.0, 0.0), StopPoint(1.0, 1.0))
        assertEquals(listOf(0, 1), planner.spineOrder(stops, emptyList()))
    }

    /**
     * Off the line isn't off the course. A start line 66 m beside the spine
     * projects at the beginning, where it belongs - the anchor rule would put it
     * a km up the road next to its own 1 km sign.
     */
    @Test
    fun `a start line beside the spine is visited first, not next to its own 1 km`() {
        val spine = (0..40).map { rp(it * 0.001, 0.0) }   // ~4.4 km due north
        val axis = CourseAxisBuilder(calc).build(spine)!!

        val stops = listOf(
            // Its own course, on the spine, at roughly 1 km and 2 km.
            VisitOrderPlanner.AxisStop(0.009, 0.0, mapOf(7L to 1000.0)),
            VisitOrderPlanner.AxisStop(0.018, 0.0, mapOf(7L to 2000.0)),
            // The start line: beside the spine at its very beginning.
            VisitOrderPlanner.AxisStop(0.0, 0.0006, mapOf(7L to 0.0)),
            // Another course's stop, on the spine 500 m along - between the
            // plaza and that 1 km sign, so being hung beside the sign is
            // visibly not the same as being visited first.
            VisitOrderPlanner.AxisStop(0.0045, 0.0, mapOf(8L to 500.0))
        )
        assertTrue(
            "the start must be off-axis for this to test anything",
            axis.positionOf(0.0, 0.0006, VisitOrderPlanner.ON_AXIS_TOLERANCE_METRES) == null
        )

        assertEquals(listOf(2, 3, 0, 1), planner.axisOrder(stops, axis))
    }

    /**
     * The case the off-axis rule is for: the 5 km's sign projecting near the
     * spine start while belonging near the end of its own course.
     */
    @Test
    fun `a projection that contradicts the course's own km is still discarded`() {
        val spine = (0..40).map { rp(it * 0.001, 0.0) }
        val axis = CourseAxisBuilder(calc).build(spine)!!

        val stops = listOf(
            VisitOrderPlanner.AxisStop(0.009, 0.0, mapOf(7L to 1000.0)),
            VisitOrderPlanner.AxisStop(0.018, 0.0, mapOf(7L to 2000.0)),
            VisitOrderPlanner.AxisStop(0.027, 0.0, mapOf(7L to 3000.0)),
            // 4 km of a 4 km course, but sitting off the line next to the start.
            VisitOrderPlanner.AxisStop(0.001, 0.0012, mapOf(7L to 4000.0))
        )

        assertEquals(
            "the 4 km sign belongs after the 3 km one, not beside the start",
            listOf(0, 1, 2, 3), planner.axisOrder(stops, axis)
        )
    }

    @Test
    fun `optimized order visits every stop exactly once`() {
        val stops = (0 until 20).map { StopPoint(it % 5 * 0.01, it / 5 * 0.01) }
        val order = planner.optimizedOrder(stops)
        assertEquals(stops.size, order.size)
        assertEquals(stops.indices.toSet(), order.toSet())
    }

    @Test
    fun `optimized order untangles a deliberately bad sequence`() {
        // Stops along a line; visiting them in zigzag order is far longer than
        // the sorted sweep the optimizer should find (or beat).
        val line = (0 until 10).map { StopPoint(it * 0.01, 0.0) }
        val zigzag = listOf(0, 9, 1, 8, 2, 7, 3, 6, 4, 5)

        val zigzagLength    = planner.pathLengthMetres(line, zigzag)
        val optimizedLength = planner.pathLengthMetres(line, planner.optimizedOrder(line))
        val sweepLength     = planner.pathLengthMetres(line, line.indices.toList())

        assertTrue(optimizedLength < zigzagLength * 0.5)
        assertEquals(sweepLength, optimizedLength, sweepLength * 0.01)
    }

    @Test
    fun `optimized order starts from the stop nearest the start position`() {
        val stops = listOf(
            StopPoint(0.00, 0.0),
            StopPoint(0.05, 0.0),
            StopPoint(0.10, 0.0)
        )
        // Start beyond the far end - the tour must begin at index 2.
        val order = planner.optimizedOrder(stops, start = StopPoint(0.12, 0.0))
        assertEquals(2, order.first())
    }

    @Test
    fun `path length is the sum of consecutive haversine legs`() {
        val stops = listOf(StopPoint(0.0, 0.0), StopPoint(0.01, 0.0), StopPoint(0.02, 0.0))
        val expected =
            calc.haversineMetres(0.0, 0.0, 0.01, 0.0) +
            calc.haversineMetres(0.01, 0.0, 0.02, 0.0)
        assertEquals(expected, planner.pathLengthMetres(stops, listOf(0, 1, 2)), 0.01)
    }

    @Test
    fun `two or fewer stops are returned unchanged`() {
        assertEquals(emptyList<Int>(), planner.optimizedOrder(emptyList()))
        assertEquals(listOf(0), planner.optimizedOrder(listOf(StopPoint(0.0, 0.0))))
        assertEquals(
            listOf(0, 1),
            planner.optimizedOrder(listOf(StopPoint(0.0, 0.0), StopPoint(0.01, 0.0)))
        )
    }
}
