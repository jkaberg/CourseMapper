package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Clustering must depend only on the marker set and the threshold, not on
 * threshold quirks or course order - four finishes on one gantry came out as two
 * or three stops with centroid linkage.
 */
class PlacementPlannerLinkageTest {

    private val calc = CumulativeDistanceCalculator()
    private val planner = PlacementPlanner(calc)
    private val composer = CourseCompositionEngine(calc)
    private val placer = MarkerPlacementEngine(calc)

    /** Offset latitude by ~metres (≈ 1° lat = 111 320 m). */
    private fun latOffset(metres: Double) = metres / 111_320.0

    private fun marker(id: Long, northMetres: Double, courseId: Long = 1L) = DistanceMarker(
        id = id,
        courseId = courseId,
        sequenceIndex = id.toInt(),
        lat = 63.43 + latOffset(northMetres),
        lon = 10.395,
        cumulativeDistanceMetres = 0.0,
        type = MarkerType.DISTANCE,
        label = "m$id",
        isManuallyMoved = false
    )

    /** Four markers over 11.6 m like the real gantry. Centroid linkage left the fourth out at 7 m. */
    @Test
    fun `a marker within the threshold of a group member joins that group`() {
        val markers = listOf(
            marker(1, 0.0),      // coincident pair, as Hel and Halv finish are
            marker(2, 0.0),
            marker(3, 6.08),     // 6.08 m from the pair
            marker(4, 11.60)     // 5.52 m from marker 3, 11.60 m from the pair
        )

        val stops = planner.plan(markers, courseId = 1L, groupingThresholdMetres = 7.0).stops

        assertEquals("all four are transitively within 7 m", 1, stops.size)
        assertEquals(4, stops[0].markers.size)
    }

    /** Loosening the tolerance must never split a group that was already merged. */
    @Test
    fun `stop count never increases as the threshold is loosened`() {
        val markers = listOf(marker(1, 0.0), marker(2, 0.0), marker(3, 6.08), marker(4, 11.60))

        var previous = Int.MAX_VALUE
        for (t in 1..30) {
            val count = planner.plan(markers, 1L, t.toDouble()).stops.size
            assertTrue(
                "threshold ${t} m produced $count stops, more than the $previous at ${t - 1} m",
                count <= previous
            )
            previous = count
        }
    }

    /** Genuinely separate posts must stay separate - single linkage is not a merge-all. */
    @Test
    fun `markers beyond the threshold of every group member stay separate`() {
        val markers = listOf(marker(1, 0.0), marker(2, 5.0), marker(3, 40.0))

        val stops = planner.plan(markers, 1L, groupingThresholdMetres = 6.0).stops

        assertEquals(2, stops.size)
        assertEquals(2, stops.first { it.markers.size > 1 }.markers.size)
    }

    private fun markersFor(resource: String, courseId: Long): List<DistanceMarker> {
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

    private fun eventMarkers(): List<DistanceMarker> =
        markersFor(GpxFixtures.MARATHON, 1L) +
        markersFor(GpxFixtures.HALF_MARATHON, 2L) +
        markersFor(GpxFixtures.TEN_KM, 3L) +
        markersFor(GpxFixtures.FIVE_KM, 4L)

    /** Four finishes over 11.3 m, no gap over 6.08 m. From 7 m up that's one stop with four signs. */
    @Test
    fun `the shared finish gantry is a single stop at every workable threshold`() {
        val markers = eventMarkers()

        for (threshold in 7..20) {
            val stops = planner.plan(markers, 0L, threshold.toDouble()).stops
            val withFinish = stops.filter { s -> s.markers.any { it.type == MarkerType.FINISH } }

            assertEquals(
                "threshold ${threshold} m split the finish gantry across ${withFinish.size} stops",
                1, withFinish.size
            )
            assertEquals(
                "threshold ${threshold} m lost a finish sign",
                4, withFinish.single().markers.count { it.type == MarkerType.FINISH }
            )
        }
    }

    @Test
    fun `stop count never increases as the threshold is loosened on the real event`() {
        val markers = eventMarkers()

        var previous = Int.MAX_VALUE
        for (t in 1..40) {
            val count = planner.plan(markers, 0L, t.toDouble()).stops.size
            assertTrue(
                "threshold ${t} m produced $count stops, more than the $previous at ${t - 1} m",
                count <= previous
            )
            previous = count
        }
    }

    /** Course order in the group must not change the stops. */
    @Test
    fun `grouping is independent of marker order`() {
        val markers = eventMarkers()
        val reference = planner.plan(markers, 0L, 10.0).stops
            .map { stop -> stop.markers.map { it.courseId to it.label }.toSet() }
            .toSet()

        val random = Random(20260828)
        repeat(20) { attempt ->
            val shuffled = markers.shuffled(random)
            val groups = planner.plan(shuffled, 0L, 10.0).stops
                .map { stop -> stop.markers.map { it.courseId to it.label }.toSet() }
                .toSet()
            assertEquals("shuffle #$attempt produced a different grouping", reference, groups)
        }
    }
}
