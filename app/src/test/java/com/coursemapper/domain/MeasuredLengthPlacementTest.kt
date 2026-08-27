package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Markers placed against the measured length instead of the drawn line. The
 * marathon is drawn at 42 650 m and measures 42 195 m, so the 42 km sign landed
 * 448 m early. Unmeasured courses are untouched, start and finish don't move.
 */
class MeasuredLengthPlacementTest {

    private val calc = CumulativeDistanceCalculator()
    private val composer = CourseCompositionEngine(calc)
    private val placer = MarkerPlacementEngine(calc)

    private val kilometrePreset = MarkerPreset(
        id = 1L, name = "1 km",
        rules = listOf(
            MarkerRule(MarkerType.START),
            MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
            MarkerRule(MarkerType.FINISH)
        ),
        createdAt = 0L, updatedAt = 0L
    )

    /** Metres between two positions, computed independently of the engine. */
    private fun metresApart(a: DistanceMarker, b: DistanceMarker): Double {
        val r = 6_371_008.8
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    private fun marathon(): List<RoutePoint> = GpxFixtures.load(GpxFixtures.MARATHON)

    private fun place(
        points: List<RoutePoint>,
        scale: Double,
        preset: MarkerPreset = kilometrePreset,
        withLaps: Boolean = true
    ): List<DistanceMarker> {
        val comp = composer.compose(points, CourseBuildSpec.FixedLaps(1))
        return placer.place(
            composedPoints = comp.composedPoints,
            totalDistanceMetres = comp.totalDistanceMetres,
            preset = preset,
            courseId = 7L,
            lapTemplatePoints = if (withLaps) comp.lapTemplatePoints else emptyList(),
            laps = if (withLaps) comp.laps else emptyList(),
            distanceScale = scale
        ).markers
    }

    /** Unmeasured (scale 1.0) must be identical to before, to the bit. */
    @Test
    fun `a scale of one changes nothing at all`() {
        val points = marathon()
        val comp = composer.compose(points, CourseBuildSpec.FixedLaps(1))

        for (withLaps in listOf(true, false)) {
            val baseline = placer.place(
                composedPoints = comp.composedPoints,
                totalDistanceMetres = comp.totalDistanceMetres,
                preset = kilometrePreset,
                courseId = 7L,
                lapTemplatePoints = if (withLaps) comp.lapTemplatePoints else emptyList(),
                laps = if (withLaps) comp.laps else emptyList()
            ).markers
            val explicit = place(points, scale = 1.0, withLaps = withLaps)

            assertEquals("marker count (lapAware=$withLaps)", baseline.size, explicit.size)
            baseline.zip(explicit).forEach { (a, b) ->
                assertEquals(a.label, b.label)
                assertEquals(a.type, b.type)
                assertEquals(a.sequenceIndex, b.sequenceIndex)
                assertEquals(a.cumulativeDistanceMetres, b.cumulativeDistanceMetres, 0.0)
                assertEquals(a.lat, b.lat, 0.0)
                assertEquals(a.lon, b.lon, 0.0)
            }
        }
    }

    /** A nonsensical scale falls back to uncalibrated rather than to NaN. */
    @Test
    fun `an impossible scale is ignored rather than obeyed`() {
        val points = marathon()
        val sane = place(points, scale = 1.0)
        for (nonsense in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val markers = place(points, scale = nonsense)
            assertEquals("scale=$nonsense should behave as 1.0", sane.size, markers.size)
            sane.zip(markers).forEach { (a, b) ->
                assertEquals(a.lat, b.lat, 0.0)
                assertEquals(a.cumulativeDistanceMetres, b.cumulativeDistanceMetres, 0.0)
            }
        }
    }

    /** Start and finish are fixed points, measuring isn't trimming. */
    @Test
    fun `measuring a course never moves its start or finish`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val scale = drawn / 42_195.0

        val before = place(points, scale = 1.0)
        val after = place(points, scale = scale)

        val startBefore = before.first { it.type == MarkerType.START }
        val startAfter = after.first { it.type == MarkerType.START }
        val finishBefore = before.first { it.type == MarkerType.FINISH }
        val finishAfter = after.first { it.type == MarkerType.FINISH }

        assertEquals("start must not move", 0.0, metresApart(startBefore, startAfter), 1e-9)
        assertEquals("finish must not move", 0.0, metresApart(finishBefore, finishAfter), 1e-9)

        // The finish's READING changes - that number was the one that was wrong.
        assertEquals(drawn, finishBefore.cumulativeDistanceMetres, 0.01)
        assertEquals(42_195.0, finishAfter.cumulativeDistanceMetres, 0.01)
    }

    /** On the real trace: 42 signs, the last one 195 m from the finish. */
    @Test
    fun `the marathon gets its kilometres in the right places`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val markers = place(points, scale = drawn / 42_195.0)

        val kilometres = markers.filter { it.type == MarkerType.DISTANCE }
        assertEquals("a marathon has 42 kilometre signs", 42, kilometres.size)

        kilometres.forEachIndexed { i, m ->
            assertEquals(
                "sign ${i + 1} stands at ${i + 1} km of course distance",
                (i + 1) * 1_000.0, m.cumulativeDistanceMetres, 0.001
            )
        }

        val finish = markers.first { it.type == MarkerType.FINISH }
        assertEquals(
            "the last sign is 195 m from the finish, as a marathon's is",
            195.0,
            finish.cumulativeDistanceMetres - kilometres.last().cumulativeDistanceMetres,
            0.01
        )
    }

    /** Without a measurement the last sign is the whole surplus from the finish. */
    @Test
    fun `without a measurement the last sign is the whole surplus out`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val markers = place(points, scale = 1.0)
        val kilometres = markers.filter { it.type == MarkerType.DISTANCE }
        val finish = markers.first { it.type == MarkerType.FINISH }

        assertEquals(42, kilometres.size)
        assertEquals(
            "the gap is the drawn line's whole overshoot past 42 km",
            drawn - 42_000.0,
            finish.cumulativeDistanceMetres - kilometres.last().cumulativeDistanceMetres,
            0.01
        )
        assertTrue(
            "and that is far more than a marathon's 195 m",
            drawn - 42_000.0 > 400.0
        )
    }

    /** Positions shift by the accumulated surplus, ~10.8 m per km here. */
    @Test
    fun `signs move by the surplus that has accumulated by then`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val scale = drawn / 42_195.0

        val before = place(points, scale = 1.0).filter { it.type == MarkerType.DISTANCE }
        val after = place(points, scale = scale).filter { it.type == MarkerType.DISTANCE }

        assertEquals(before.size, after.size)
        val oneKm = metresApart(before[0], after[0])
        val twentyOneKm = metresApart(before[20], after[20])
        assertTrue("the 1 km sign barely moves (was $oneKm m)", oneKm < 30.0)
        assertTrue("the 21 km sign moves hundreds of metres (was $twentyOneKm m)",
            twentyOneKm > 150.0)
        assertTrue("and the error grows along the course", twentyOneKm > oneKm * 5)
    }

    @Test
    fun `markers stay in ascending order with consecutive indices`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val markers = place(points, scale = drawn / 42_195.0)

        markers.forEachIndexed { i, m ->
            assertEquals("sequence index", i + 1, m.sequenceIndex)
            if (i > 0) {
                assertTrue(
                    "markers must ascend",
                    m.cumulativeDistanceMetres >= markers[i - 1].cumulativeDistanceMetres
                )
            }
        }
    }

    /**
     * Lapped: the counter is in course metres and the lap template in polyline
     * metres, so the conversions have to cancel.
     */
    @Test
    fun `a lapped course keeps whole kilometres and honest lap boundaries`() {
        val loop = GpxFixtures.load(GpxFixtures.FIVE_KM)
        val comp = composer.compose(loop, CourseBuildSpec.FixedLaps(4))
        val measured = 20_000.0
        val scale = comp.totalDistanceMetres / measured

        val markers = placer.place(
            composedPoints = comp.composedPoints,
            totalDistanceMetres = comp.totalDistanceMetres,
            preset = kilometrePreset,
            courseId = 7L,
            lapTemplatePoints = comp.lapTemplatePoints,
            laps = comp.laps,
            distanceScale = scale
        ).markers

        val kilometres = markers.filter { it.type == MarkerType.DISTANCE }
        assertEquals("a 20 km course has 19 interval signs plus the finish", 19, kilometres.size)
        kilometres.forEachIndexed { i, m ->
            assertEquals((i + 1) * 1_000.0, m.cumulativeDistanceMetres, 0.001)
        }
        assertEquals(
            measured,
            markers.first { it.type == MarkerType.FINISH }.cumulativeDistanceMetres,
            0.01
        )

        // Each lap is 5 000 course metres, so the 5/10/15 km signs sit at the
        // same offset within the template and therefore on the same ground - 
        // which is what lets PlacementPlanner merge them into one field stop.
        val five = kilometres.first { it.cumulativeDistanceMetres == 5_000.0 }
        val ten = kilometres.first { it.cumulativeDistanceMetres == 10_000.0 }
        val fifteen = kilometres.first { it.cumulativeDistanceMetres == 15_000.0 }
        assertEquals("5 km and 10 km fall on the same lap offset", 0.0, metresApart(five, ten), 0.01)
        assertEquals("and so does 15 km", 0.0, metresApart(ten, fifteen), 0.01)
    }

    @Test
    fun `custom distances are course metres too`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val scale = drawn / 42_195.0
        val preset = MarkerPreset(
            id = 2L, name = "halfway",
            rules = listOf(MarkerRule(
                MarkerType.CUSTOM_DISTANCES,
                customDistancesMetres = listOf(21_097.5, 42_000.0)
            )),
            createdAt = 0L, updatedAt = 0L
        )

        val markers = place(points, scale = scale, preset = preset)
        assertEquals(2, markers.size)
        assertEquals(21_097.5, markers[0].cumulativeDistanceMetres, 0.001)
        assertEquals(42_000.0, markers[1].cumulativeDistanceMetres, 0.001)

        // The halfway marker of a measured marathon stands where the runner has
        // covered 21 097.5 m, which on this course is 213 m further along the
        // drawn line than the uncalibrated placement put it.
        val uncalibrated = place(points, scale = 1.0, preset = preset)
        assertTrue(metresApart(uncalibrated[0], markers[0]) > 150.0)
    }

    @Test
    fun `checkpoints and water stations are placed in course metres`() {
        val points = marathon()
        val drawn = calc.pathDistanceMetres(points, smoothedOnly = false)
        val preset = MarkerPreset(
            id = 3L, name = "support",
            rules = listOf(
                MarkerRule(MarkerType.WATER_STATION, intervalMetres = 5_000.0),
                MarkerRule(MarkerType.CHECKPOINT, intervalMetres = 10_000.0)
            ),
            createdAt = 0L, updatedAt = 0L
        )

        val markers = place(points, scale = drawn / 42_195.0, preset = preset)
        val water = markers.filter { it.type == MarkerType.WATER_STATION }
        val checkpoints = markers.filter { it.type == MarkerType.CHECKPOINT }

        assertEquals(8, water.size)
        assertEquals(4, checkpoints.size)
        water.forEachIndexed { i, m ->
            assertEquals((i + 1) * 5_000.0, m.cumulativeDistanceMetres, 0.001)
        }
        checkpoints.forEachIndexed { i, m ->
            assertEquals((i + 1) * 10_000.0, m.cumulativeDistanceMetres, 0.001)
        }
    }

    @Test
    fun `an unmeasured course reports a scale of one and its drawn length`() {
        val course = com.coursemapper.domain.model.ComposedCourse(
            id = 1L, baseRouteId = 1L, name = "Helmaraton",
            createdAt = 0L, updatedAt = 0L, lapCount = 1,
            finalLapDistanceMetres = 0.0, totalDistanceMetres = 42_650.36,
            presetSnapshot = com.coursemapper.domain.model.MarkerPresetSnapshot(null, emptyList())
        )
        assertEquals(1.0, course.distanceScale, 0.0)
        assertEquals(42_650.36, course.displayDistanceMetres, 0.0)
        assertTrue(!course.isMeasured)
    }

    @Test
    fun `a measured course reports its measurement and the derived scale`() {
        val course = com.coursemapper.domain.model.ComposedCourse(
            id = 1L, baseRouteId = 1L, name = "Helmaraton",
            createdAt = 0L, updatedAt = 0L, lapCount = 1,
            finalLapDistanceMetres = 0.0, totalDistanceMetres = 42_650.36,
            presetSnapshot = com.coursemapper.domain.model.MarkerPresetSnapshot(null, emptyList()),
            measuredLengthMetres = 42_195.0
        )
        assertTrue(course.isMeasured)
        assertEquals(42_195.0, course.displayDistanceMetres, 0.0)
        assertEquals(42_650.36 / 42_195.0, course.distanceScale, 1e-12)
        assertTrue("the drawn line is about 1.08 % long",
            abs(course.distanceScale - 1.0108) < 0.0005)
    }

    /** A course with no geometry yet must not divide its way to infinity. */
    @Test
    fun `a measured course with no geometry still reports a usable scale`() {
        val course = com.coursemapper.domain.model.ComposedCourse(
            id = 1L, baseRouteId = 1L, name = "Empty",
            createdAt = 0L, updatedAt = 0L, lapCount = 1,
            finalLapDistanceMetres = 0.0, totalDistanceMetres = 0.0,
            presetSnapshot = com.coursemapper.domain.model.MarkerPresetSnapshot(null, emptyList()),
            measuredLengthMetres = 42_195.0
        )
        assertEquals(1.0, course.distanceScale, 0.0)
    }
}
