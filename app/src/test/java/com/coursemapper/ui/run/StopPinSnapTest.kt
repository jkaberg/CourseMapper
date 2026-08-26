package com.coursemapper.ui.run

import com.coursemapper.domain.CorridorAnalyzer
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.MarkerPlacementEngine
import com.coursemapper.domain.PlacementPlanner
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

/**
 * Stop pins sit on the drawn line. Markers are on their own course's trace and
 * merged stops at a centroid, so without snapping they float beside the line.
 */
class StopPinSnapTest {

    private val calc = CumulativeDistanceCalculator()
    private val analyzer = CorridorAnalyzer(calc)
    private val display = CourseDisplayPlanner(calc, analyzer)
    private val composer = CourseCompositionEngine(calc)
    private val placer = MarkerPlacementEngine(calc)
    private val planner = PlacementPlanner(calc)

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    private fun pt(northM: Double, eastM: Double) =
        lat0 + northM / metresPerDegLat to 10.4 + eastM / metresPerDegLon

    private fun line(
        courseId: Long,
        path: List<Pair<Double, Double>>,
        solo: Boolean = true
    ) = RunCourseLine(
        courseId = courseId,
        soloPaths = if (solo) listOf(path) else emptyList(),
        ribbonPaths = if (solo) emptyList() else listOf(path),
        colorHex = "#FFFFFF",
        courseName = "c$courseId"
    )

    private fun distanceToPaths(lines: List<RunCourseLine>, lat: Double, lon: Double): Double {
        var nearest = Double.MAX_VALUE
        lines.flatMap { it.soloPaths + it.ribbonPaths }.forEach { path ->
            for (i in 0 until path.size - 1) {
                val proj = calc.closestPointOnSegment(
                    path[i].first, path[i].second,
                    path[i + 1].first, path[i + 1].second,
                    lat, lon
                )
                nearest = minOf(nearest, calc.haversineMetres(lat, lon, proj.lat, proj.lon))
            }
        }
        return nearest
    }

    @Test
    fun `a pin beside the drawn line is pulled onto it`() {
        val drawn = listOf(line(1L, listOf(pt(0.0, 0.0), pt(0.0, 500.0))))
        val (lat, lon) = pt(8.0, 250.0)   // 8 m north of the line

        val (snappedLat, snappedLon) = drawn.snapStopToDrawnLine(lat, lon)

        assertEquals(
            "pin was not pulled onto the line",
            0.0, distanceToPaths(drawn, snappedLat, snappedLon), 0.05
        )
    }

    @Test
    fun `a pin already on the line is left alone`() {
        val drawn = listOf(line(1L, listOf(pt(0.0, 0.0), pt(0.0, 500.0))))
        val (lat, lon) = pt(0.0, 250.0)

        val (snappedLat, snappedLon) = drawn.snapStopToDrawnLine(lat, lon)

        assertEquals(lat, snappedLat, 1e-9)
        assertEquals(lon, snappedLon, 1e-9)
    }

    /** Further than the "same ground" tolerance and the pin stays where it is. */
    @Test
    fun `a pin beyond the tolerance is not moved`() {
        val drawn = listOf(line(1L, listOf(pt(0.0, 0.0), pt(0.0, 500.0))))
        val far = CorridorAnalyzer.EXIT_TOLERANCE_METRES + 10.0
        val (lat, lon) = pt(far, 250.0)

        val (snappedLat, snappedLon) = drawn.snapStopToDrawnLine(lat, lon)

        assertEquals(lat, snappedLat, 1e-9)
        assertEquals(lon, snappedLon, 1e-9)
    }

    @Test
    fun `nothing drawn means nothing moved`() {
        val (lat, lon) = pt(5.0, 100.0)

        val (snappedLat, snappedLon) = emptyList<RunCourseLine>().snapStopToDrawnLine(lat, lon)

        assertEquals(lat, snappedLat, 1e-9)
        assertEquals(lon, snappedLon, 1e-9)
    }

    /** Ribbon chunks are what is on screen over shared ground, so they count. */
    @Test
    fun `ribbon chunks are snapped to as well as solo paths`() {
        val drawn = listOf(line(1L, listOf(pt(0.0, 0.0), pt(0.0, 500.0)), solo = false))
        val (lat, lon) = pt(9.0, 250.0)

        val (snappedLat, snappedLon) = drawn.snapStopToDrawnLine(lat, lon)

        assertEquals(0.0, distanceToPaths(drawn, snappedLat, snappedLon), 0.05)
    }

    private fun markersOf(resource: String, courseId: Long): List<DistanceMarker> {
        val composed = composer.compose(GpxFixtures.load(resource), CourseBuildSpec.FixedLaps(1))
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
            composedPoints = composed.composedPoints,
            totalDistanceMetres = composed.totalDistanceMetres,
            preset = preset,
            courseId = courseId,
            lapTemplatePoints = composed.lapTemplatePoints,
            laps = composed.laps
        ).markers
    }

    /** Every stop of the real event lands on a drawn line, up to 10.2 m off before. */
    @Test
    fun `every stop of the real event lands on a drawn line`() {
        val sources = listOf(
            1L to GpxFixtures.MARATHON,
            2L to GpxFixtures.HALF_MARATHON,
            3L to GpxFixtures.TEN_KM,
            4L to GpxFixtures.FIVE_KM
        )
        val indexed = sources.mapNotNull { (id, res) -> display.index(id, GpxFixtures.load(res)) }
        val tolerance = CorridorAnalyzer.ENTER_TOLERANCE_METRES

        val drawn = display.plan(indexed, toleranceMetres = tolerance).coursePaths.map { paths ->
            RunCourseLine(
                courseId = paths.courseId,
                soloPaths = paths.soloPaths,
                ribbonPaths = paths.ribbonPaths,
                colorHex = "#FFFFFF",
                courseName = "c${paths.courseId}"
            )
        }

        val stops = planner.plan(
            sources.flatMap { (id, res) -> markersOf(res, id) },
            courseId = 0L,
            groupingThresholdMetres = tolerance
        ).stops
        assertTrue("no stops were produced", stops.size > 50)

        var worstBefore = 0.0
        var worstAfter = 0.0
        stops.forEach { stop ->
            worstBefore = maxOf(worstBefore, distanceToPaths(drawn, stop.lat, stop.lon))
            val (lat, lon) = drawn.snapStopToDrawnLine(stop.lat, stop.lon)
            worstAfter = maxOf(worstAfter, distanceToPaths(drawn, lat, lon))
        }

        assertTrue(
            "the fixtures no longer reproduce the problem: worst stop was only " +
                "%.1f m off the drawn line before snapping".format(worstBefore),
            worstBefore > 3.0
        )
        assertEquals(
            "a stop is still %.1f m off every drawn line after snapping".format(worstAfter),
            0.0, worstAfter, 0.05
        )
    }

    /** Never moved further than the tolerance, or pins near junctions jump to the crossing road. */
    @Test
    fun `snapping never moves a stop further than the tolerance`() {
        val sources = listOf(
            1L to GpxFixtures.MARATHON,
            2L to GpxFixtures.HALF_MARATHON,
            3L to GpxFixtures.TEN_KM,
            4L to GpxFixtures.FIVE_KM
        )
        val indexed = sources.mapNotNull { (id, res) -> display.index(id, GpxFixtures.load(res)) }
        val drawn = display.plan(indexed).coursePaths.map { paths ->
            RunCourseLine(paths.courseId, paths.soloPaths, paths.ribbonPaths, "#FFFFFF", "c")
        }

        planner.plan(sources.flatMap { (id, res) -> markersOf(res, id) }).stops.forEach { stop ->
            val (lat, lon) = drawn.snapStopToDrawnLine(stop.lat, stop.lon)
            val moved = calc.haversineMetres(stop.lat, stop.lon, lat, lon)
            assertTrue(
                "a stop was moved %.1f m, past the %.0f m bound".format(moved, STOP_SNAP_MAX_METRES),
                moved <= STOP_SNAP_MAX_METRES
            )
        }
    }
}
