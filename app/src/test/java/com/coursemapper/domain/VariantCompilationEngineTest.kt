package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** No mocks, both engines are plain classes. */
class VariantCompilationEngineTest {

    private lateinit var engine: VariantCompilationEngine

    @Before
    fun setUp() {
        engine = VariantCompilationEngine(CumulativeDistanceCalculator())
    }

    /** Minimal point, altitude and accuracy don't matter for stitching. */
    private fun pt(lat: Double, lon: Double) = RoutePoint(
        lat            = lat,
        lon            = lon,
        timestampMs    = System.currentTimeMillis(),
        isSmoothed     = false,
        altMetres      = 0.0,
        accuracyMetres = 1.0f
    )

    @Test
    fun `empty input returns empty result with zero distance`() {
        val result = engine.compile(emptyList<SegmentPolylineSlice>())
        assertTrue(result.compiledPoints.isEmpty())
        assertEquals(0.0, result.totalDistanceMetres, 0.001)
    }

    @Test
    fun `single segment returns all points unchanged`() {
        // A short north-south segment (~111 m per 0.001°)
        val segment = listOf(
            pt(51.5000, -0.1000),
            pt(51.5005, -0.1000),
            pt(51.5010, -0.1000)
        )
        val result = engine.compile(listOf(segment))
        assertEquals(3, result.compiledPoints.size)
        assertEquals(segment[0].lat, result.compiledPoints[0].lat, 1e-9)
        assertEquals(segment[2].lat, result.compiledPoints[2].lat, 1e-9)
        assertTrue("Distance should be positive", result.totalDistanceMetres > 0.0)
    }

    @Test
    fun `two segments with junction within 5m - first point of second segment is dropped`() {
        val a = pt(51.5000, -0.1000)
        val b = pt(51.5005, -0.1000)   // tail of segment 1

        // Segment 2 starts with a point only ~1 m from b (same coords here, ≡ 0 m).
        val bDup = pt(51.5005, -0.1000) // identical to b → gap = 0 m → dedup
        val c    = pt(51.5010, -0.1000)

        val result = engine.compile(listOf(listOf(a, b), listOf(bDup, c)))

        // bDup should have been dropped: a, b, c → 3 points
        assertEquals(3, result.compiledPoints.size)
        assertEquals(a.lat,  result.compiledPoints[0].lat, 1e-9)
        assertEquals(b.lat,  result.compiledPoints[1].lat, 1e-9)
        assertEquals(c.lat,  result.compiledPoints[2].lat, 1e-9)
    }

    @Test
    fun `two segments with junction gap greater than 5m - no point dropped`() {
        val a = pt(51.5000, -0.1000)
        val b = pt(51.5005, -0.1000)  // tail of segment 1

        // Segment 2 starts ~100 m away from b
        val c = pt(51.5014, -0.1000)
        val d = pt(51.5020, -0.1000)

        val result = engine.compile(listOf(listOf(a, b), listOf(c, d)))

        // Gap > 5 m: all 4 points preserved
        assertEquals(4, result.compiledPoints.size)
    }

    @Test
    fun `total distance is consistent with sum of segment path distances`() {
        val calc = CumulativeDistanceCalculator()
        val seg1 = listOf(pt(51.5000, -0.1000), pt(51.5010, -0.1000)) // ~111 m
        val seg2 = listOf(pt(51.5010, -0.1000), pt(51.5020, -0.1000)) // ~111 m, shares junction

        val result = engine.compile(listOf(seg1, seg2))

        // bDup = seg2[0] duplicates seg1[1] → dropped → 3 points
        assertEquals(3, result.compiledPoints.size)

        val expected = calc.pathDistanceMetres(result.compiledPoints, smoothedOnly = false)
        assertEquals(expected, result.totalDistanceMetres, 0.01)
    }

    @Test
    fun `empty segment in middle is skipped gracefully`() {
        val a = pt(51.5000, -0.1000)
        val b = pt(51.5005, -0.1000)
        val c = pt(51.5010, -0.1000)

        val result = engine.compile(listOf(listOf(a, b), emptyList(), listOf(c)))

        // Empty middle segment does nothing; c has large gap from b so NOT dropped
        // Gap from b (51.5005) to c (51.5010) ≈ 55 m > 5 m
        assertEquals(3, result.compiledPoints.size)
    }
}
