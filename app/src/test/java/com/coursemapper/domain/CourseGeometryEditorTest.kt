package com.coursemapper.domain

import com.coursemapper.domain.model.CourseGeometryEdit
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class CourseGeometryEditorTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var editor: CourseGeometryEditor

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        editor = CourseGeometryEditor(calc)
    }

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    /** A straight 1000 m line east, a vertex every 10 m. */
    private fun straight() = (0..100).map { pt(0.0, it * 10.0) }

    /**
     * A closed 800 m square loop, a vertex every 10 m, ending where it started.
     */
    private fun loop(): List<RoutePoint> = buildList {
        for (i in 0..20) add(pt(0.0, i * 10.0))
        for (i in 1..20) add(pt(i * 10.0, 200.0))
        for (i in 1..20) add(pt(200.0, 200.0 - i * 10.0))
        for (i in 1..20) add(pt(200.0 - i * 10.0, 0.0))
    }

    private fun length(points: List<RoutePoint>) =
        calc.pathDistanceMetres(points, smoothedOnly = false)

    @Test
    fun `no edit leaves the route exactly as recorded`() {
        val route = straight()
        assertEquals(route, editor.apply(route, CourseGeometryEdit.NONE))
    }

    @Test
    fun `an edit on a degenerate route is ignored`() {
        val single = listOf(pt(0.0, 0.0))
        assertEquals(single, editor.apply(single, CourseGeometryEdit(startOffsetMetres = 5.0)))
    }

    @Test
    fun `trimming the start moves where the course begins`() {
        // The recorder was switched on 120 m before the start line.
        val trimmed = editor.apply(straight(), CourseGeometryEdit(startOffsetMetres = 120.0))

        assertEquals(880.0, length(trimmed), 1.0)
        assertEquals(120.0, calc.haversineMetres(lat0, 10.4, trimmed.first().lat, trimmed.first().lon), 1.0)
    }

    @Test
    fun `keeping a length trims the end`() {
        val trimmed = editor.apply(straight(), CourseGeometryEdit(keepLengthMetres = 750.0))
        assertEquals(750.0, length(trimmed), 1.0)
    }

    @Test
    fun `both ends can be trimmed at once`() {
        val trimmed = editor.apply(
            straight(),
            CourseGeometryEdit(startOffsetMetres = 100.0, keepLengthMetres = 500.0)
        )
        assertEquals(500.0, length(trimmed), 1.0)
        assertEquals(100.0, calc.haversineMetres(lat0, 10.4, trimmed.first().lat, trimmed.first().lon), 1.0)
    }

    @Test
    fun `an edit that would erase the course is ignored`() {
        val route = straight()
        val absurd = editor.apply(route, CourseGeometryEdit(startOffsetMetres = 999.0, keepLengthMetres = 1.0))
        assertEquals("a course must never be trimmed to nothing", route, absurd)
    }

    @Test
    fun `reversing runs the course the other way`() {
        val reversed = editor.apply(straight(), CourseGeometryEdit(reversed = true))

        assertEquals(1000.0, length(reversed), 1.0)
        // Starts where the recording ended.
        assertEquals(1000.0, calc.haversineMetres(lat0, 10.4, reversed.first().lat, reversed.first().lon), 1.0)
    }

    @Test
    fun `offsets are measured along the reversed route`() {
        // Trimming 200 m off the start of a reversed course cuts the *recorded*
        // end, not the recorded start.  Getting this backwards would silently
        // trim the wrong end of every reversed course.
        val edited = editor.apply(
            straight(),
            CourseGeometryEdit(reversed = true, startOffsetMetres = 200.0)
        )

        assertEquals(800.0, length(edited), 1.0)
        assertEquals(800.0, calc.haversineMetres(lat0, 10.4, edited.first().lat, edited.first().lon), 1.0)
    }

    @Test
    fun `a loop can start somewhere else and keep its full length`() {
        val route = loop()
        val moved = editor.apply(route, CourseGeometryEdit(startOffsetMetres = 300.0))

        // The whole loop is still there, just entered at a different point.
        assertEquals(length(route), length(moved), 5.0)
        // …and it now begins 300 m along the original.
        val expectedStart = calc.interpolateAt(route, 300.0, smoothedOnly = false)!!
        assertEquals(
            0.0,
            calc.haversineMetres(expectedStart.first, expectedStart.second, moved.first().lat, moved.first().lon),
            2.0
        )
    }

    @Test
    fun `an open route refuses to wrap`() {
        // Asking for more than remains must not join the finish back to the
        // start across ground nobody drove.
        val edited = editor.apply(
            straight(),
            CourseGeometryEdit(startOffsetMetres = 800.0, keepLengthMetres = 600.0)
        )

        assertEquals("an open route can only give up what it has", 200.0, length(edited), 1.0)
        assertFalse(editor.describe(straight(), CourseGeometryEdit(startOffsetMetres = 800.0, keepLengthMetres = 600.0)).wraps)
    }

    @Test
    fun `a loop wraps past its seam`() {
        val route = loop()
        val description = editor.describe(
            route,
            CourseGeometryEdit(startOffsetMetres = 600.0, keepLengthMetres = 400.0)
        )
        assertTrue(description.isLoop)
        assertTrue("a loop must be allowed to start late and wrap", description.wraps)

        val edited = editor.apply(
            route,
            CourseGeometryEdit(startOffsetMetres = 600.0, keepLengthMetres = 400.0)
        )
        assertEquals(400.0, length(edited), 5.0)
    }

    @Test
    fun `closing the loop adds the leg back to the start`() {
        // A recording that stops 200 m short of where it began.
        val open = (0..80).map { pt(0.0, it * 10.0) }
        val closed = editor.apply(open, CourseGeometryEdit(closeLoop = true))

        assertEquals(open.size + 1, closed.size)
        assertEquals(open.first().lat, closed.last().lat, 1e-9)
        assertEquals(open.first().lon, closed.last().lon, 1e-9)
        assertEquals(length(open) * 2, length(closed), 1.0)
    }

    @Test
    fun `closing an already closed loop adds nothing`() {
        val route = loop()
        val closed = editor.apply(route, CourseGeometryEdit(closeLoop = true))
        assertEquals(length(route), length(closed), 0.5)
    }

    @Test
    fun `description reports the endpoint gap and what an edit would leave`() {
        val open = (0..80).map { pt(0.0, it * 10.0) }
        val description = editor.describe(open, CourseGeometryEdit(startOffsetMetres = 100.0))

        assertEquals(800.0, description.baseLengthMetres, 1.0)
        assertEquals(700.0, description.resultLengthMetres, 1.0)
        assertEquals(100.0, description.trimmedMetres, 1.0)
        assertEquals(800.0, description.endpointGapMetres, 1.0)
        assertFalse("an 800 m gap is not a loop", description.isLoop)
    }

    @Test
    fun `the real races report the endpoint gaps the composition engine warns about`() {
        // These are the numbers the loop-closure tolerance was calibrated on.
        val half = editor.describe(GpxFixtures.load(GpxFixtures.HALF_MARATHON), CourseGeometryEdit.NONE)
        val fiveK = editor.describe(GpxFixtures.load(GpxFixtures.FIVE_KM), CourseGeometryEdit.NONE)
        val tenK = editor.describe(GpxFixtures.load(GpxFixtures.TEN_KM), CourseGeometryEdit.NONE)

        assertTrue("half marathon starts and finishes a block apart", half.isLoop)
        assertFalse("the 5 km start and finish differ", fiveK.isLoop)
        assertFalse("the 10 km is clearly point-to-point", tenK.isLoop)
    }

    @Test
    fun `trimming a real course to a round distance holds to the metre`() {
        // The organiser's actual problem: a 10 km recording that measures long
        // because the watch ran on past the finish.
        val route = GpxFixtures.load(GpxFixtures.TEN_KM)
        val recorded = length(route)
        assertTrue("fixture should be over 10 km, was $recorded", recorded > 10_000.0)

        val edited = editor.apply(route, CourseGeometryEdit(keepLengthMetres = 10_000.0))
        assertEquals(10_000.0, length(edited), 1.0)
    }

    @Test
    fun `a trimmed course still starts on the recorded track`() {
        val route = GpxFixtures.load(GpxFixtures.FIVE_KM)
        val index = PolylineIndex.build(route, calc)!!
        val edited = editor.apply(
            route,
            CourseGeometryEdit(startOffsetMetres = 250.0, keepLengthMetres = 3000.0)
        )

        // Both new ends must lie on the recording - an edit may re-cut a route,
        // never move it.
        listOf(edited.first(), edited.last()).forEach { p ->
            val onTrack = index.nearest(p.lat, p.lon, 1.0)
            assertTrue("edited endpoint left the recorded track", onTrack != null)
        }
    }
}
