package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

/**
 * Segment projection at 63.43°N, where a raw-degree projection is skewed
 * 2.24:1. At the equator the two can't be told apart.
 */
class SegmentProjectionLatitudeTest {

    private lateinit var calc: CumulativeDistanceCalculator

    /** Trondheim - where the fixture GPX files in this repo were recorded. */
    private val lat0 = 63.43
    private val lonScale = cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
    }

    private fun pt(lat: Double, lon: Double) = RoutePoint(
        lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
        timestampMs = 0, isSmoothed = true
    )

    /** Degrees of longitude that span [metres] on the ground at [lat0]. */
    private fun lonDegFor(metres: Double): Double =
        metres / (CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0 * lonScale)

    /** Degrees of latitude that span [metres] on the ground. */
    private fun latDegFor(metres: Double): Double =
        metres / (CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0)

    @Test
    fun `foot on a diagonal segment lands at the true perpendicular`() {
        // A segment running exactly north-east on the ground: 100 m north and
        // 100 m east.  In raw degrees this looks like a shallow 24° slope
        // instead of 45°, which is what slides the foot.
        val a = pt(lat0, 10.4)
        val b = pt(lat0 + latDegFor(100.0), 10.4 + lonDegFor(100.0))

        // Query point offset 20 m perpendicular (north-west) from the midpoint.
        val midLat = lat0 + latDegFor(50.0)
        val midLon = 10.4 + lonDegFor(50.0)
        val perpM = 20.0 / Math.sqrt(2.0)
        val p = pt(midLat + latDegFor(perpM), midLon - lonDegFor(perpM))

        val foot = calc.closestPointOnSegment(a.lat, a.lon, b.lat, b.lon, p.lat, p.lon)

        // The true foot is the midpoint: t = 0.5.
        assertEquals(0.5, foot.t, 0.01)
        // …and it is 20 m from the query point, not more.
        assertEquals(20.0, calc.haversineMetres(p.lat, p.lon, foot.lat, foot.lon), 0.5)
    }

    @Test
    fun `cumulative progress on a diagonal segment matches ground distance`() {
        val a = pt(lat0, 10.4)
        val b = pt(lat0 + latDegFor(100.0), 10.4 + lonDegFor(100.0))
        val route = listOf(a, b)

        // Stand exactly on the segment, one quarter along it.
        val q = pt(lat0 + latDegFor(25.0), 10.4 + lonDegFor(25.0))
        val projection = calc.projectOntoPolyline(route, q.lat, q.lon)!!

        val segmentLength = calc.haversineMetres(a.lat, a.lon, b.lat, b.lon)
        assertEquals(segmentLength * 0.25, projection.cumulativeMetres, 1.0)
        assertTrue(
            "standing on the line should project to ~0 m off it",
            projection.distanceToPolylineMetres < 0.5
        )
    }

    @Test
    fun `snap engine reports offset in real metres at high latitude`() {
        // East-west route: the axis raw degrees stretches most.
        val route = (0..10).map { pt(lat0, 10.4 + lonDegFor(it * 20.0)) }
        val snap = MarkerSnapEngine(calc)

        // 12 m due north of the middle of the route.
        val off = pt(lat0 + latDegFor(12.0), 10.4 + lonDegFor(100.0))
        val result = snap.snap(route, off.lat, off.lon)!!

        assertEquals(12.0, result.offsetMetres, 0.5)
        assertEquals(100.0, result.cumulativeDistanceMetres, 1.0)
    }

    @Test
    fun `a ten metre tolerance means ten metres on both axes`() {
        // The skew's practical consequence: a tolerance checked in the projected
        // space would admit east-west offsets it rejects north-south (or the
        // reverse).  Both must behave identically.
        val eastWest = (0..10).map { pt(lat0, 10.4 + lonDegFor(it * 20.0)) }
        val northSouth = (0..10).map { pt(lat0 + latDegFor(it * 20.0), 10.4) }
        val snap = MarkerSnapEngine(calc)

        val offEastWest = snap.snap(
            eastWest,
            lat0 + latDegFor(9.0),
            10.4 + lonDegFor(100.0)
        )!!.offsetMetres
        val offNorthSouth = snap.snap(
            northSouth,
            lat0 + latDegFor(100.0),
            10.4 + lonDegFor(9.0)
        )!!.offsetMetres

        assertEquals(9.0, offEastWest, 0.5)
        assertEquals(9.0, offNorthSouth, 0.5)
        assertEquals(offEastWest, offNorthSouth, 0.2)
    }
}
