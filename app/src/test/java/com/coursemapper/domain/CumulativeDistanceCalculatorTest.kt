package com.coursemapper.domain

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class CumulativeDistanceCalculatorTest {

    private lateinit var calc: CumulativeDistanceCalculator

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
    }

    @Test
    fun `haversine identical points returns 0`() {
        val d = calc.haversineMetres(51.5074, -0.1278, 51.5074, -0.1278)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `haversine London to Paris approximate distance`() {
        // London (51.5074,-0.1278) to Paris (48.8566,2.3522)
        // Known great-circle distance ≈ 340 km
        val d = calc.haversineMetres(51.5074, -0.1278, 48.8566, 2.3522)
        assertTrue("Expected ~340km, got ${d/1000} km", abs(d - 340_000.0) < 5_000.0)
    }

    @Test
    fun `haversine 1 degree latitude is approximately 111 km`() {
        val d = calc.haversineMetres(0.0, 0.0, 1.0, 0.0)
        assertTrue("1° lat ≈ 111 km, got ${d/1000} km", abs(d - 111_320.0) < 200.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val d1 = calc.haversineMetres(10.0, 20.0, 11.0, 21.0)
        val d2 = calc.haversineMetres(11.0, 21.0, 10.0, 20.0)
        assertEquals(d1, d2, 0.001)
    }

    @Test
    fun `pathDistance empty list returns 0`() {
        assertEquals(0.0, calc.pathDistanceMetres(emptyList()), 0.0)
    }

    @Test
    fun `pathDistance single point returns 0`() {
        assertEquals(0.0, calc.pathDistanceMetres(listOf(makePoint(0.0, 0.0))), 0.0)
    }

    @Test
    fun `pathDistance two points matches haversine`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(1.0, 0.0))
        val expected = calc.haversineMetres(0.0, 0.0, 1.0, 0.0)
        assertEquals(expected, calc.pathDistanceMetres(pts, smoothedOnly = false), 0.001)
    }

    @Test
    fun `pathDistance skips unsmoothed points when smoothedOnly true`() {
        val pts = listOf(
            makePoint(0.0, 0.0, isSmoothed = true),
            makePoint(0.5, 0.0, isSmoothed = false),   // skipped
            makePoint(1.0, 0.0, isSmoothed = true)
        )
        val full = calc.pathDistanceMetres(pts, smoothedOnly = false)
        val smooth = calc.pathDistanceMetres(pts, smoothedOnly = true)
        // Direct 0°→1° vs via 0.5° should be equal (collinear along lat)
        assertEquals(smooth, full, 0.1)
    }

    @Test
    fun `cumulativeDistances first element is 0`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(1.0, 0.0))
        val cum = calc.cumulativeDistances(pts, smoothedOnly = false)
        assertEquals(0.0, cum.first(), 0.0)
    }

    @Test
    fun `cumulativeDistances last element equals pathDistance`() {
        val pts = listOf(
            makePoint(0.0, 0.0),
            makePoint(0.5, 0.0),
            makePoint(1.0, 0.0)
        )
        val cum  = calc.cumulativeDistances(pts, smoothedOnly = false)
        val path = calc.pathDistanceMetres(pts, smoothedOnly = false)
        assertEquals(path, cum.last(), 0.001)
    }

    @Test
    fun `cumulativeDistances is monotonically increasing`() {
        val pts = listOf(
            makePoint(0.0, 0.0), makePoint(1.0, 0.0),
            makePoint(2.0, 0.0), makePoint(3.0, 0.0)
        )
        val cum = calc.cumulativeDistances(pts, smoothedOnly = false)
        for (i in 1 until cum.size) {
            assertTrue("Not monotonic at $i", cum[i] > cum[i - 1])
        }
    }

    @Test
    fun `interpolateAt 0 returns first point`() {
        val pts = listOf(makePoint(10.0, 20.0), makePoint(11.0, 21.0))
        val (lat, lon) = calc.interpolateAt(pts, 0.0, smoothedOnly = false)!!
        assertEquals(10.0, lat, 0.0001)
        assertEquals(20.0, lon, 0.0001)
    }

    @Test
    fun `interpolateAt beyond total returns last point`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(1.0, 0.0))
        val total = calc.pathDistanceMetres(pts, smoothedOnly = false)
        val (lat, lon) = calc.interpolateAt(pts, total * 2, smoothedOnly = false)!!
        assertEquals(1.0, lat, 0.0001)
    }

    @Test
    fun `interpolateAt midpoint of two collinear points is correct`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(2.0, 0.0))
        val total = calc.pathDistanceMetres(pts, smoothedOnly = false)
        val (lat, _) = calc.interpolateAt(pts, total / 2.0, smoothedOnly = false)!!
        assertEquals(1.0, lat, 0.01)   // tolerance for Haversine interpolation
    }

    @Test
    fun `interpolateAt empty list returns null`() {
        assertNull(calc.interpolateAt(emptyList(), 100.0))
    }

    @Test
    fun `projectOntoPolyline point on line has near-zero distance`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(0.0, 1.0))
        val result = calc.projectOntoPolyline(pts, 0.0, 0.5, smoothedOnly = false)!!
        assertEquals(0.0, result.distanceToPolylineMetres, 1.0)  // within 1 m
    }

    @Test
    fun `projectOntoPolyline cumulativeMetres is within path length`() {
        val pts = listOf(makePoint(0.0, 0.0), makePoint(0.0, 1.0))
        val total = calc.pathDistanceMetres(pts, smoothedOnly = false)
        val result = calc.projectOntoPolyline(pts, 0.0, 0.5, smoothedOnly = false)!!
        assertTrue(result.cumulativeMetres in 0.0..total)
    }

    private fun makePoint(
        lat: Double,
        lon: Double,
        isSmoothed: Boolean = true
    ) = com.coursemapper.domain.model.RoutePoint(
        lat            = lat,
        lon            = lon,
        altMetres      = null,
        accuracyMetres = null,
        timestampMs    = System.currentTimeMillis(),
        isSmoothed     = isSmoothed
    )
}
