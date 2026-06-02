package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RouteSmoothingEngineTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var engine: RouteSmoothingEngine

    @Before
    fun setUp() {
        calc   = CumulativeDistanceCalculator()
        engine = RouteSmoothingEngine(calc)
    }

    @Test
    fun `empty list returns empty`() {
        assertTrue(engine.smooth(emptyList()).isEmpty())
    }

    @Test
    fun `single point preserved`() {
        val pts    = listOf(point(0.0, 0.0))
        val result = engine.smooth(pts)
        assertEquals(1, result.size)
    }

    @Test
    fun `rejected points remain in output with isSmoothed false`() {
        val pts = listOf(
            point(0.0, 0.0, isSmoothed = true,  ts = 1000L),
            point(0.5, 0.0, isSmoothed = false, ts = 2000L),   // rejected by filter
            point(1.0, 0.0, isSmoothed = true,  ts = 3000L)
        )
        val result = engine.smooth(pts)
        assertEquals(3, result.size)
        // The rejected point must remain false.
        val rejected = result.find { it.timestampMs == pts[1].timestampMs }
        assertNotNull(rejected)
        assertFalse(rejected!!.isSmoothed)
    }

    @Test
    fun `output count equals input count`() {
        val pts = (0..9).map { i ->
            point(i * 0.01, 0.0, isSmoothed = true, ts = i.toLong())
        }
        val result = engine.smooth(pts)
        assertEquals(pts.size, result.size)
    }

    @Test
    fun `distance gate suppresses points closer than minSpacing`() {
        // Set a large spacing so most points are suppressed
        engine.minPointSpacingMetres = 10_000.0   // 10 km

        // Points 1 m apart - all except first and last should be collapsed
        val pts = (0..10).map { i ->
            point(i * 0.000009, 0.0, isSmoothed = true, ts = i.toLong())
        }
        val result = engine.smooth(pts)
        val smoothed = result.count { it.isSmoothed }
        // With 10 km spacing, only first + last should survive (small route)
        assertTrue("Expected few smoothed points, got $smoothed", smoothed <= 3)
    }

    @Test
    fun `last point is always preserved in smoothed output`() {
        engine.minPointSpacingMetres = 100_000.0   // 100 km - very large
        val pts = (0..5).map { i ->
            point(i * 0.001, 0.0, isSmoothed = true, ts = i.toLong())
        }
        val result  = engine.smooth(pts)
        val lastOut = result.last()
        val lastIn  = pts.last()
        // Last point's timestamp should be in the output
        assertTrue(
            "Last input point not in smoothed output",
            result.any { it.timestampMs == lastIn.timestampMs }
        )
    }

    @Test
    fun `kalman does not produce NaN or Infinity values`() {
        val pts = (0..50).map { i ->
            point(i * 0.01, i * 0.005, isSmoothed = true, ts = i.toLong())
        }
        engine.smooth(pts).filter { it.isSmoothed }.forEach { p ->
            assertFalse(p.lat.isNaN()); assertFalse(p.lat.isInfinite())
            assertFalse(p.lon.isNaN()); assertFalse(p.lon.isInfinite())
        }
    }

    // length preservation - markers are placed by distance along the smoothed
    // line, so any length bias moves every sign

    /** No systematic loss on clean input. */
    @Test
    fun `noise-free route keeps its length`() {
        val truth = syntheticCourse()
        val trueLength = calc.pathDistanceMetres(truth, smoothedOnly = false)

        val got = calc.pathDistanceMetres(
            engine.smooth(truth).filter { it.isSmoothed }, smoothedOnly = false
        )

        assertEquals(
            "noise-free input must not be shrunk or stretched",
            trueLength, got, trueLength * 0.02
        )
    }

    /** Why filtering is mandatory: raw 1 Hz fixes measure way too long. */
    @Test
    fun `unfiltered noisy fixes measure far too long`() {
        val truth = syntheticCourse()
        val trueLength = calc.pathDistanceMetres(truth, smoothedOnly = false)
        val noisy = addNoise(truth, sigmaMetres = 5.0, seed = 7)

        val rawLength = calc.pathDistanceMetres(noisy, smoothedOnly = false)

        assertTrue(
            "expected raw noisy fixes to over-measure badly, got ${rawLength / trueLength}x",
            rawLength > trueLength * 1.5
        )
    }

    @Test
    fun `route length survives realistic GPS noise`() {
        val truth = syntheticCourse()
        val trueLength = calc.pathDistanceMetres(truth, smoothedOnly = false)

        for (sigma in listOf(3.0, 5.0, 8.0)) {
            for (seed in 1L..3L) {
                val got = calc.pathDistanceMetres(
                    engine.smooth(addNoise(truth, sigma, seed)).filter { it.isSmoothed },
                    smoothedOnly = false
                )
                val errorFraction = kotlin.math.abs(got - trueLength) / trueLength
                assertTrue(
                    "sigma=$sigma seed=$seed: length error ${"%.1f".format(errorFraction * 100)}%",
                    errorFraction <= 0.06
                )
            }
        }
    }

    /** Chunking must not change the answer, length can't depend on the flush interval. */
    @Test
    fun `result does not depend on how the input was batched`() {
        val noisy = addNoise(syntheticCourse(), sigmaMetres = 5.0, seed = 3)
        val whole = calc.pathDistanceMetres(
            engine.smooth(noisy).filter { it.isSmoothed }, smoothedOnly = false
        )
        val chunked = calc.pathDistanceMetres(
            noisy.chunked(20).flatMap { engine.smooth(it).filter { p -> p.isSmoothed } },
            smoothedOnly = false
        )
        // Not an equality check - the point is that whole-route filtering is the
        // contract, and that chunking visibly degrades it.
        assertTrue(
            "chunked=${chunked.toInt()} whole=${whole.toInt()}: whole-route filtering should be at least as accurate",
            kotlin.math.abs(whole - 2_000.0) <= kotlin.math.abs(chunked - 2_000.0)
        )
    }

    /** 2 km with four right-angle corners at Trondheim latitude, a fix every 3 m. Corners are where a stiff filter cuts. */
    private fun syntheticCourse(): List<RoutePoint> {
        val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
        val metresPerDegLon = metresPerDegLat * kotlin.math.cos(Math.toRadians(LAT0))
        val side = 500.0
        val corners = listOf(
            0.0 to 0.0,
            0.0 to side,
            side to side,
            side to 0.0,
            0.0 to 0.0
        )
        val pts = mutableListOf<RoutePoint>()
        var t = 0L
        for (i in 0 until corners.lastIndex) {
            val (aN, aE) = corners[i]
            val (bN, bE) = corners[i + 1]
            val steps = (side / 3.0).toInt()
            for (s in 0 until steps) {
                val u = s.toDouble() / steps
                pts += RoutePoint(
                    lat            = LAT0 + (aN + u * (bN - aN)) / metresPerDegLat,
                    lon            = LON0 + (aE + u * (bE - aE)) / metresPerDegLon,
                    altMetres      = null,
                    accuracyMetres = 5f,
                    timestampMs    = (t++) * 1_000L,
                    isSmoothed     = true
                )
            }
        }
        pts += pts.first().copy(timestampMs = t * 1_000L)
        return pts
    }

    /** White Gaussian noise of [sigmaMetres], seeded for determinism. */
    private fun addNoise(pts: List<RoutePoint>, sigmaMetres: Double, seed: Long): List<RoutePoint> {
        val rng = java.util.Random(seed)
        val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
        val metresPerDegLon = metresPerDegLat * kotlin.math.cos(Math.toRadians(LAT0))
        return pts.map {
            it.copy(
                lat            = it.lat + rng.nextGaussian() * sigmaMetres / metresPerDegLat,
                lon            = it.lon + rng.nextGaussian() * sigmaMetres / metresPerDegLon,
                accuracyMetres = sigmaMetres.toFloat()
            )
        }
    }

    private fun point(
        lat: Double,
        lon: Double,
        isSmoothed: Boolean = true,
        ts: Long = System.currentTimeMillis()
    ) = RoutePoint(
        lat            = lat,
        lon            = lon,
        altMetres      = null,
        accuracyMetres = null,
        timestampMs    = ts,
        isSmoothed     = isSmoothed
    )

    private companion object {
        const val LAT0 = 63.4305
        const val LON0 = 10.3961
    }
}
