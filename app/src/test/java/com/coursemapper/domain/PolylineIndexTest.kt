package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class PolylineIndexTest {

    private lateinit var calc: CumulativeDistanceCalculator

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
    }

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    /** A straight eastward line of [lengthM] metres, one vertex every [stepM]. */
    private fun eastLine(lengthM: Double, stepM: Double = 10.0): List<RoutePoint> {
        val n = (lengthM / stepM).toInt()
        return (0..n).map { pt(0.0, it * stepM) }
    }

    @Test
    fun `build returns null below two points`() {
        assertNull(PolylineIndex.build(emptyList(), calc))
        assertNull(PolylineIndex.build(listOf(pt(0.0, 0.0)), calc))
    }

    @Test
    fun `build collapses consecutive duplicate points`() {
        val dupes = listOf(pt(0.0, 0.0), pt(0.0, 0.0), pt(0.0, 50.0), pt(0.0, 50.0))
        val index = PolylineIndex.build(dupes, calc)!!
        assertEquals(2, index.points.size)
        assertEquals(1, index.segmentCount)
    }

    @Test
    fun `a polyline of only duplicate points is not indexable`() {
        assertNull(PolylineIndex.build(listOf(pt(0.0, 0.0), pt(0.0, 0.0)), calc))
    }

    @Test
    fun `length matches the ground distance walked`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!
        assertEquals(1000.0, index.lengthMetres, 1.0)
    }

    @Test
    fun `pointAt interpolates inside a segment and clamps outside`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!

        val (midLat, midLon) = index.pointAt(500.0)
        assertEquals(500.0, calc.haversineMetres(lat0, 10.4, midLat, midLon), 1.0)

        val (endLat, endLon) = index.pointAt(99_999.0)
        assertEquals(1000.0, calc.haversineMetres(lat0, 10.4, endLat, endLon), 1.0)

        val (startLat, startLon) = index.pointAt(-50.0)
        assertEquals(0.0, calc.haversineMetres(lat0, 10.4, startLat, startLon), 1.0)
    }

    @Test
    fun `nearest finds the foot and its progress along the line`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!
        val query = pt(8.0, 250.0)   // 8 m north of the 250 m mark

        val match = index.nearest(query.lat, query.lon, maxMetres = 20.0)!!
        assertEquals(8.0, match.offsetMetres, 0.5)
        assertEquals(250.0, match.cumulativeMetres, 1.0)
    }

    @Test
    fun `nearest returns null outside the radius`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!
        val query = pt(40.0, 250.0)
        assertNull(index.nearest(query.lat, query.lon, maxMetres = 10.0))
        assertNotNull(index.nearest(query.lat, query.lon, maxMetres = 50.0))
    }

    @Test
    fun `grid and linear scan agree`() {
        // A radius above the grid limit falls back to a linear scan; the two
        // paths must not disagree, or a result would depend on the radius asked
        // for rather than on the geometry.
        val index = PolylineIndex.build(eastLine(4000.0), calc)!!
        val query = pt(15.0, 1234.0)

        val viaGrid   = index.nearest(query.lat, query.lon, maxMetres = 100.0)!!
        val viaScan   = index.nearest(query.lat, query.lon, maxMetres = Double.MAX_VALUE)!!

        assertEquals(viaScan.cumulativeMetres, viaGrid.cumulativeMetres, 0.01)
        assertEquals(viaScan.offsetMetres, viaGrid.offsetMetres, 0.01)
        assertEquals(viaScan.segmentIndex, viaGrid.segmentIndex)
    }

    @Test
    fun `a segment spanning several cells is still found from the middle`() {
        // Two vertices 600 m apart - far more than one 64 m cell.  Registering
        // only the endpoints' cells would lose the middle of this segment.
        val sparse = listOf(pt(0.0, 0.0), pt(0.0, 600.0))
        val index = PolylineIndex.build(sparse, calc)!!

        val match = index.nearest(pt(5.0, 300.0).lat, pt(5.0, 300.0).lon, maxMetres = 20.0)
        assertNotNull("mid-segment query must hit the long segment", match)
        assertEquals(5.0, match!!.offsetMetres, 0.5)
        assertEquals(300.0, match.cumulativeMetres, 1.0)
    }

    @Test
    fun `sample walks at a fixed ground interval and includes both ends`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!
        val samples = index.sample(stepMetres = 25.0)

        assertEquals(0.0, samples.first().cumulativeMetres, 0.001)
        assertEquals(index.lengthMetres, samples.last().cumulativeMetres, 0.001)
        for (i in 1 until samples.size - 1) {
            assertEquals(
                25.0,
                samples[i].cumulativeMetres - samples[i - 1].cumulativeMetres,
                0.001
            )
        }
    }

    @Test
    fun `sample spacing does not follow vertex density`() {
        // Vertices bunched at the start, sparse at the end: the whole point of
        // sampling by distance is that this makes no difference.
        val bunched = (0..20).map { pt(0.0, it * 2.0) } + listOf(pt(0.0, 500.0), pt(0.0, 1000.0))
        val index = PolylineIndex.build(bunched, calc)!!
        val samples = index.sample(stepMetres = 50.0)

        val gaps = samples.zipWithNext { a, b -> b.cumulativeMetres - a.cumulativeMetres }
        // Every gap is a full step except the trailing remainder.
        gaps.dropLast(1).forEach { assertEquals(50.0, it, 0.001) }
        assertTrue(gaps.last() <= 50.0 + 0.001)
    }

    @Test
    fun `bearing on an eastward line reads ninety degrees`() {
        val index = PolylineIndex.build(eastLine(1000.0), calc)!!
        assertEquals(90.0, index.bearingAt(500.0), 1.0)
    }

    @Test
    fun `bearing is measured over a window not a single noisy vertex`() {
        // eastbound line with one sideways glitch, the windowed bearing must still read east
        val jittered = (0..100).map { i ->
            if (i == 50) pt(1.5, i * 10.0) else pt(0.0, i * 10.0)
        }
        val index = PolylineIndex.build(jittered, calc)!!
        assertEquals(90.0, index.bearingAt(500.0), 15.0)
    }

    @Test
    fun `bearing delta wraps across north`() {
        assertEquals(20.0, CumulativeDistanceCalculator.bearingDeltaDegrees(350.0, 10.0), 0.001)
        assertEquals(180.0, CumulativeDistanceCalculator.bearingDeltaDegrees(0.0, 180.0), 0.001)
        assertEquals(0.0, CumulativeDistanceCalculator.bearingDeltaDegrees(45.0, 45.0), 0.001)
    }

    @Test
    fun `projections report every pass a lap course makes past a point`() {
        // Out 500 m east and back over the same ground: the polyline passes the
        // 200 m mark twice, and a caller pairing positions up has to see both.
        val out = (0..50).map { pt(0.0, it * 10.0) }
        val back = (49 downTo 0).map { pt(0.0, it * 10.0) }
        val index = PolylineIndex.build(out + back, calc)!!

        val probe = pt(0.0, 200.0)
        val passes = index.projectionsWithin(probe.lat, probe.lon, 5.0)

        assertEquals(2, passes.size)
        assertEquals(200.0, passes[0].cumulativeMetres, 1.0)
        assertEquals(800.0, passes[1].cumulativeMetres, 1.0)
    }

    @Test
    fun `one pass past a point is reported once, not once per segment`() {
        val index = PolylineIndex.build((0..100).map { pt(0.0, it * 10.0) }, calc)!!
        // A radius wide enough to touch several consecutive segments.
        val probe = pt(0.0, 500.0)
        assertEquals(1, index.projectionsWithin(probe.lat, probe.lon, 25.0).size)
    }

    @Test
    fun `projections outside the radius are not reported`() {
        val index = PolylineIndex.build((0..100).map { pt(0.0, it * 10.0) }, calc)!!
        val probe = pt(80.0, 500.0)
        assertTrue(index.projectionsWithin(probe.lat, probe.lon, 50.0).isEmpty())
    }

    @Test
    fun `slice returns the polyline between two distances with ends interpolated`() {
        val index = PolylineIndex.build((0..100).map { pt(0.0, it * 10.0) }, calc)!!
        val slice = index.slice(105.0, 235.0)

        assertEquals(130.0, pathLength(slice), 0.5)
        // Ends land exactly on the asked-for distances, not on the nearest vertex.
        assertEquals(index.pointAt(105.0), slice.first())
        assertEquals(index.pointAt(235.0), slice.last())
    }

    @Test
    fun `a slice asked for backwards comes back reversed`() {
        val index = PolylineIndex.build((0..100).map { pt(0.0, it * 10.0) }, calc)!!
        val forward = index.slice(100.0, 300.0)
        val backward = index.slice(300.0, 100.0)
        assertEquals(forward.reversed(), backward)
    }

    @Test
    fun `slice clamps distances outside the polyline`() {
        val index = PolylineIndex.build((0..10).map { pt(0.0, it * 10.0) }, calc)!!
        val slice = index.slice(-50.0, 5_000.0)
        assertEquals(index.pointAt(0.0), slice.first())
        assertEquals(index.pointAt(index.lengthMetres), slice.last())
    }

    private fun pathLength(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += calc.haversineMetres(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second
            )
        }
        return total
    }
}
