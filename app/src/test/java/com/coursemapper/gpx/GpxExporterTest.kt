package com.coursemapper.gpx

import com.coursemapper.domain.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GpxExporterTest {

    private lateinit var exporter: GpxExporter

    @Before
    fun setUp() {
        exporter = GpxExporter()
    }

    @Test
    fun `exportRoute contains gpx declaration`() {
        val gpx = exporter.exportRoute(sampleRoute())
        assertTrue(gpx.contains("""<?xml version="1.0""""))
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("</gpx>"))
    }

    @Test
    fun `exportRoute contains track with route name`() {
        val gpx = exporter.exportRoute(sampleRoute())
        assertTrue(gpx.contains("<trk>"))
        assertTrue(gpx.contains("Test Route"))
        assertTrue(gpx.contains("<trkseg>"))
    }

    @Test
    fun `exportRoute includes trkpt elements`() {
        val gpx = exporter.exportRoute(sampleRoute())
        assertTrue(gpx.contains("<trkpt"))
        assertTrue(gpx.contains("lat="))
        assertTrue(gpx.contains("lon="))
    }

    @Test
    fun `exportRoute escapes xml special characters in name`() {
        val route = sampleRoute().copy(name = "Route <A> & \"B\"")
        val gpx   = exporter.exportRoute(route)
        assertFalse(gpx.contains("<A>"))
        assertTrue(gpx.contains("&lt;A&gt;"))
        assertTrue(gpx.contains("&amp;"))
    }

    @Test
    fun `exportRoute only includes smoothed points`() {
        val pts = listOf(
            point(10.0, 20.0, isSmoothed = true),
            point(11.0, 21.0, isSmoothed = false),   // should NOT appear
            point(12.0, 22.0, isSmoothed = true)
        )
        val route = sampleRoute(smoothedPoints = pts.filter { it.isSmoothed }, rawPoints = pts)
        val gpx   = exporter.exportRoute(route)
        // 11.0 should not appear in lat attributes (only 10.0 and 12.0)
        assertFalse(gpx.contains("lat=\"11.0\""))
        assertTrue(gpx.contains("lat=\"10.0\""))
        assertTrue(gpx.contains("lat=\"12.0\""))
    }

    @Test
    fun `exportCourse includes waypoints for markers`() {
        val course = sampleCourse()
        val points = listOf(point(0.0, 0.0), point(1.0, 0.0))
        val gpx    = exporter.exportCourse(course, points)
        assertTrue(gpx.contains("<wpt"))
    }

    @Test
    fun `exportCourse includes course name in track`() {
        val course = sampleCourse()
        val points = listOf(point(0.0, 0.0), point(1.0, 0.0))
        val gpx    = exporter.exportCourse(course, points)
        assertTrue(gpx.contains("Test Course"))
    }

    private fun point(lat: Double, lon: Double, isSmoothed: Boolean = true) = RoutePoint(
        lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
        timestampMs = System.currentTimeMillis(), isSmoothed = isSmoothed
    )

    private fun sampleRoute(
        smoothedPoints: List<RoutePoint> = listOf(point(51.5, -0.1), point(51.51, -0.11)),
        rawPoints:      List<RoutePoint> = smoothedPoints
    ) = BaseRoute(
        id             = 1L,
        name           = "Test Route",
        notes          = "Notes here",
        createdAt      = 0L,
        updatedAt      = 0L,
        isApproved     = true,
        distanceMetres = 1500.0,
        source         = "recorded",
        rawPoints      = rawPoints,
        smoothedPoints = smoothedPoints
    )

    private fun sampleCourse() = ComposedCourse(
        id                     = 1L,
        baseRouteId            = 1L,
        name                   = "Test Course",
        createdAt              = 0L,
        updatedAt              = 0L,
        lapCount               = 2,
        finalLapDistanceMetres = 0.0,
        totalDistanceMetres    = 3000.0,
        presetSnapshot         = MarkerPresetSnapshot(
            sourcePresetId = null,
            rules          = listOf(MarkerRule(MarkerType.DISTANCE, 1000.0))
        ),
        markers = listOf(
            DistanceMarker(
                id                       = 1L,
                courseId                 = 1L,
                sequenceIndex            = 1,
                lat                      = 0.0,
                lon                      = 0.0,
                cumulativeDistanceMetres = 1000.0,
                type                     = MarkerType.DISTANCE,
                label                    = "",
                isManuallyMoved          = false,
                version                  = 0
            )
        )
    )
}
