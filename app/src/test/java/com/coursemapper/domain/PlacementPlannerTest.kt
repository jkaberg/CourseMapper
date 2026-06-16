package com.coursemapper.domain

import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlacementPlannerTest {

    private lateinit var planner: PlacementPlanner

    @Before
    fun setUp() {
        planner = PlacementPlanner(CumulativeDistanceCalculator())
    }

    private fun marker(
        id: Long,
        lat: Double,
        lon: Double,
        type: MarkerType = MarkerType.DISTANCE,
        seq: Int = id.toInt(),
        dist: Double = 0.0,
        label: String = ""
    ) = DistanceMarker(
        id                       = id,
        courseId                 = 1L,
        sequenceIndex            = seq,
        lat                      = lat,
        lon                      = lon,
        cumulativeDistanceMetres = dist,
        type                     = type,
        label                    = label,
        isManuallyMoved          = false
    )

    /** Offset latitude by ~metres (≈ 1° lat = 111 320 m). */
    private fun latOffset(metres: Double) = metres / 111_320.0

    @Test
    fun `empty marker list produces no stops`() {
        val result = planner.plan(emptyList(), courseId = 1L)
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun `single marker produces one stop with one marker`() {
        val result = planner.plan(listOf(marker(1, 0.0, 0.0)), courseId = 1L)
        assertEquals(1, result.stops.size)
        assertEquals(1, result.stops[0].markers.size)
    }

    @Test
    fun `two distant markers produce two stops`() {
        // 100 m apart - well above default 12 m threshold
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(100.0), 0.0)
        val result = planner.plan(listOf(m1, m2), courseId = 1L)
        assertEquals(2, result.stops.size)
    }

    @Test
    fun `two markers within threshold are grouped into one stop`() {
        // 8 m apart - below 12 m threshold
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(8.0), 0.0)
        val result = planner.plan(listOf(m1, m2), courseId = 1L)
        assertEquals(1, result.stops.size)
        assertEquals(2, result.stops[0].markers.size)
    }

    @Test
    fun `markers exactly at threshold boundary are grouped`() {
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(12.0), 0.0)
        val result = planner.plan(listOf(m1, m2), groupingThresholdMetres = 12.0)
        // Exactly at boundary - centroid after first is the marker itself so m2 is ≤ 12 m
        assertEquals(1, result.stops.size)
    }

    @Test
    fun `markers just above threshold are NOT grouped`() {
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(13.0), 0.0)
        val result = planner.plan(listOf(m1, m2), groupingThresholdMetres = 12.0)
        assertEquals(2, result.stops.size)
    }

    @Test
    fun `three close markers form single stop`() {
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(5.0), 0.0)
        val m3 = marker(3, latOffset(10.0), 0.0)
        val result = planner.plan(listOf(m1, m2, m3), groupingThresholdMetres = 12.0)
        assertEquals(1, result.stops.size)
        assertEquals(3, result.stops[0].markers.size)
    }

    @Test
    fun `stop indices are 0-based and sequential`() {
        val markers = (1L..5L).map { marker(it, latOffset(it * 100.0), 0.0) }
        val result = planner.plan(markers, courseId = 1L)
        result.stops.forEachIndexed { i, stop ->
            assertEquals(i, stop.stopIndex)
        }
    }

    @Test
    fun `stop centroid lies between grouped markers`() {
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(8.0), 0.0)
        val result = planner.plan(listOf(m1, m2), courseId = 1L)
        assertEquals(1, result.stops.size)
        val stop = result.stops[0]
        // Centroid lat should be between m1.lat and m2.lat
        assertTrue(stop.lat >= 0.0)
        assertTrue(stop.lat <= m2.lat)
    }

    @Test
    fun `custom zero threshold never groups markers`() {
        val m1 = marker(1, 0.0, 0.0)
        val m2 = marker(2, latOffset(0.001), 0.0) // < 1 mm apart
        val result = planner.plan(listOf(m1, m2), groupingThresholdMetres = 0.0)
        assertEquals(2, result.stops.size)
    }

    @Test
    fun `very large threshold groups all markers`() {
        val markers = (1L..10L).map { marker(it, latOffset(it * 200.0), 0.0) }
        val result = planner.plan(markers, groupingThresholdMetres = 100_000.0)
        assertEquals(1, result.stops.size)
        assertEquals(10, result.stops[0].markers.size)
    }

    @Test
    fun `stops carry the courseId`() {
        val result = planner.plan(listOf(marker(1, 0.0, 0.0)), courseId = 42L)
        assertEquals(42L, result.stops[0].courseId)
    }

    @Test
    fun `markers 1 km apart produce individual stops`() {
        // 10 markers at 1 km intervals along latitude
        val markers = (1L..10L).map { marker(it, latOffset(it * 1_000.0), 0.0) }
        val result = planner.plan(markers, groupingThresholdMetres = 12.0)
        assertEquals(10, result.stops.size)
    }
}
