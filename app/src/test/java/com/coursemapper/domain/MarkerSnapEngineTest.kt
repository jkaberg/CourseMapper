package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class MarkerSnapEngineTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var snap: MarkerSnapEngine

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        snap = MarkerSnapEngine(calc)
    }

    private fun pt(lat: Double, lon: Double = 0.0, smoothed: Boolean = true) = RoutePoint(
        lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
        timestampMs = 0, isSmoothed = smoothed
    )

    private fun straightRoute(latFrom: Double, latTo: Double, steps: Int = 10): List<RoutePoint> {
        val delta = (latTo - latFrom) / (steps - 1)
        return (0 until steps).map { i -> pt(latFrom + i * delta) }
    }

    @Test
    fun `snap on empty route returns null`() {
        assertNull(snap.snap(emptyList(), 0.5, 0.0))
    }

    @Test
    fun `snap on single point route returns null`() {
        assertNull(snap.snap(listOf(pt(0.0)), 0.5, 0.0))
    }

    @Test
    fun `snap at route start returns zero cumulative distance`() {
        val route = straightRoute(0.0, 1.0)
        val result = snap.snap(route, 0.0, 0.0)!!
        assertEquals(0.0, result.cumulativeDistanceMetres, 1.0)   // within 1 m
    }

    @Test
    fun `snap at route end returns total route distance`() {
        val route = straightRoute(0.0, 1.0)
        val totalDist = calc.pathDistanceMetres(route, smoothedOnly = false)
        val result = snap.snap(route, 1.0, 0.0)!!
        assertEquals(totalDist, result.cumulativeDistanceMetres, 1.0)
    }

    @Test
    fun `snap at midpoint returns approximately half the total distance`() {
        val route = straightRoute(0.0, 1.0, 100)
        val totalDist = calc.pathDistanceMetres(route, smoothedOnly = false)
        val result = snap.snap(route, 0.5, 0.0)!!
        assertEquals(totalDist / 2.0, result.cumulativeDistanceMetres, totalDist * 0.01)
    }

    @Test
    fun `snap on route segment has near-zero offset`() {
        val route = straightRoute(0.0, 1.0, 20)
        val result = snap.snap(route, 0.3, 0.0)!!
        // Point is ON the route (same longitude), offset should be very small
        assertTrue("Offset too large: ${result.offsetMetres}", result.offsetMetres < 10.0)
    }

    @Test
    fun `snap off route to side projects onto nearest segment`() {
        val route = straightRoute(0.0, 1.0, 10)
        // Move 0.01° east of the route midpoint
        val result = snap.snap(route, 0.5, 0.01)!!
        // Cumulative should still be roughly in the middle
        val totalDist = calc.pathDistanceMetres(route, smoothedOnly = false)
        assertTrue(result.cumulativeDistanceMetres in totalDist * 0.3..totalDist * 0.7)
        // Offset should be non-zero
        assertTrue(result.offsetMetres > 0.0)
    }

    @Test
    fun `snap far off route is clamped to nearest endpoint`() {
        val route = straightRoute(0.0, 0.01, 5)
        // Point well beyond the route end
        val result = snap.snap(route, 10.0, 0.0)!!
        val totalDist = calc.pathDistanceMetres(route, smoothedOnly = false)
        assertEquals(totalDist, result.cumulativeDistanceMetres, totalDist * 0.05)
    }

    @Test
    fun `unsmoothed points do not distort snap result`() {
        val smoothed = straightRoute(0.0, 1.0, 10)
        // Insert an unsmoothed outlier far from the route
        val withOutlier = smoothed.toMutableList().also {
            it.add(4, pt(50.0, 50.0, smoothed = false))  // wildly off route
        }
        val resultA = snap.snap(smoothed, 0.5, 0.0)!!
        val resultB = snap.snap(withOutlier, 0.5, 0.0)!!
        assertEquals(resultA.cumulativeDistanceMetres, resultB.cumulativeDistanceMetres, 100.0)
    }

    @Test
    fun `nearCumulative bias prefers segment near expected progress`() {
        // Create an out-and-back route so two segments overlap in lat
        // Leg 1: 0.0 → 0.5; Leg 2: 0.5 → 0.0 (reversed)
        val outbound = straightRoute(0.0, 0.5, 6)
        val returnLeg = straightRoute(0.5, 0.0, 6)
        val route = outbound + returnLeg.drop(1)

        val totalDist = calc.pathDistanceMetres(route, smoothedOnly = false)
        val halfwayDist = totalDist / 2.0

        // Without bias, a point at 0.25 lat might snap to either leg
        // With near=3/4 of totalDist, it should prefer the return leg
        val biasedResult = snap.snap(
            routePoints      = route,
            lat              = 0.25,
            lon              = 0.0,
            nearCumulative   = totalDist * 0.75,
            searchWindowMetres = 1000.0
        )!!
        // Biased snap should be in the second half of the course
        assertTrue(biasedResult.cumulativeDistanceMetres > halfwayDist)
    }

    @Test
    fun `snap along straight route produces increasing cumulative distances`() {
        val route = straightRoute(0.0, 1.0, 50)
        val latPoints = listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)
        val results = latPoints.map { lat -> snap.snap(route, lat, 0.0)!! }
        for (i in 1 until results.size) {
            assertTrue(
                "Not monotonic at index $i",
                results[i].cumulativeDistanceMetres > results[i - 1].cumulativeDistanceMetres
            )
        }
    }
}
