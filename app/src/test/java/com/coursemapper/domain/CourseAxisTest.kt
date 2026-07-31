package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.*
import org.junit.Test

/**
 * The marathon is the fixture that matters, it drives the same loop twice. The
 * synthetic cases cover folding a course that doesn't repeat and missing one that does.
 */
class CourseAxisTest {

    private val calc = CumulativeDistanceCalculator()
    private val builder = CourseAxisBuilder(calc)

    private fun rp(lat: Double, lon: Double) = RoutePoint(
        lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
        timestampMs = 0L, isSmoothed = true
    )

    /** Points along a closed square loop of roughly [side] degrees, [laps] times. */
    private fun loop(laps: Int, side: Double = 0.01, steps: Int = 40): List<RoutePoint> {
        val corners = listOf(0.0 to 0.0, side to 0.0, side to side, 0.0 to side)
        val out = mutableListOf<RoutePoint>()
        repeat(laps) {
            for (c in corners.indices) {
                val (aLat, aLon) = corners[c]
                val (bLat, bLon) = corners[(c + 1) % corners.size]
                for (s in 0 until steps) {
                    val f = s.toDouble() / steps
                    out.add(rp(63.43 + aLat + f * (bLat - aLat), 10.39 + aLon + f * (bLon - aLon)))
                }
            }
        }
        out.add(rp(63.43, 10.39))
        return out
    }

    @Test
    fun `the marathon folds onto its single lap`() {
        val axis = builder.build(GpxFixtures.load(GpxFixtures.MARATHON))!!
        assertTrue("marathon drives the same loop twice and must be detected as lapped", axis.isLapped)
        assertEquals(21_419.0, axis.lapMetres!!, 60.0)
        assertEquals(axis.lapMetres!!, axis.lengthMetres, 0.001)
    }

    /**
     * Two stops on the same street of the 5 km read 22 473 m and 2 041 m unfolded.
     * They're ~1 km apart on the ground, so on the axis too.
     */
    @Test
    fun `stops on the same street of a lapped course get neighbouring axis positions`() {
        val axis = builder.build(GpxFixtures.load(GpxFixtures.MARATHON))!!
        val fiveKm = GpxFixtures.load(GpxFixtures.FIVE_KM)
        val cum = calc.cumulativeDistances(fiveKm, smoothedOnly = false)

        fun at(metres: Double): RoutePoint {
            val i = cum.indexOfFirst { it >= metres }.coerceAtLeast(0)
            return fiveKm[i]
        }

        val oneKm = at(1_000.0)
        val twoKm = at(2_000.0)
        val a = axis.positionOf(oneKm.lat, oneKm.lon, maxOffsetMetres = 30.0)!!
        val b = axis.positionOf(twoKm.lat, twoKm.lon, maxOffsetMetres = 30.0)!!

        assertTrue("the 1 km stop must sort before the 2 km stop", a.metres < b.metres)
        assertTrue(
            "axis gap ${b.metres - a.metres} m is not the ~1 km of ground between them",
            (b.metres - a.metres) < 2_000.0
        )
    }

    @Test
    fun `every position lands inside the folded axis`() {
        val axis = builder.build(GpxFixtures.load(GpxFixtures.MARATHON))!!
        for (p in GpxFixtures.load(GpxFixtures.MARATHON)) {
            val pos = axis.positionOf(p.lat, p.lon, maxOffsetMetres = 30.0) ?: continue
            assertTrue("position ${pos.metres} outside [0, ${axis.lengthMetres})",
                pos.metres >= 0.0 && pos.metres < axis.lengthMetres + 0.001)
        }
    }

    /** The single-lap races must not be folded - there is no repeat to fold. */
    @Test
    fun `single lap races are not folded`() {
        for (resource in listOf(GpxFixtures.HALF_MARATHON, GpxFixtures.TEN_KM, GpxFixtures.FIVE_KM)) {
            val axis = builder.build(GpxFixtures.load(resource))!!
            assertFalse("$resource was wrongly folded at ${axis.lapMetres} m", axis.isLapped)
        }
    }

    @Test
    fun `two laps of a loop are detected`() {
        val axis = builder.build(loop(laps = 2))!!
        assertTrue(axis.isLapped)
        val oneLap = calc.pathDistanceMetres(loop(laps = 1), smoothedOnly = false)
        assertEquals(oneLap, axis.lapMetres!!, oneLap * 0.05)
    }

    @Test
    fun `a single loop is not folded`() {
        assertFalse(builder.build(loop(laps = 1))!!.isLapped)
    }

    /**
     * An out-and-back retraces itself but never returns to the start mid-route.
     * Must not fold, the two legs are driven in opposite directions.
     */
    @Test
    fun `an out and back is not folded`() {
        val out = (0..100).map { rp(63.43 + it * 0.0002, 10.39) }
        val there = out + out.reversed()
        assertFalse(builder.build(there)!!.isLapped)
    }

    @Test
    fun `gaps wrap forwards and backwards on a lapped axis`() {
        val axis = builder.build(loop(laps = 2))!!
        val len = axis.lengthMetres

        assertEquals(100.0, axis.gapMetres(0.0, 100.0, forward = true)!!, 0.001)
        assertEquals(len - 100.0, axis.gapMetres(100.0, 0.0, forward = true)!!, 0.001)
        assertEquals(100.0, axis.gapMetres(100.0, 0.0, forward = false)!!, 0.001)
        assertEquals(len - 100.0, axis.gapMetres(0.0, 100.0, forward = false)!!, 0.001)
    }

    @Test
    fun `an open axis reports nothing behind as ahead`() {
        val axis = builder.build((0..100).map { rp(63.43 + it * 0.0002, 10.39) })!!
        assertFalse(axis.isLapped)
        assertEquals(100.0, axis.gapMetres(0.0, 100.0, forward = true)!!, 0.001)
        assertNull("a stop behind is not ahead on a course that does not close",
            axis.gapMetres(100.0, 0.0, forward = true))
    }

    @Test
    fun `travel direction is read against the course bearing`() {
        // The loop's first leg runs due north.
        val axis = builder.build(loop(laps = 2))!!
        assertTrue(axis.isTravellingForward(50.0, headingDegrees = 0.0))
        assertFalse(axis.isTravellingForward(50.0, headingDegrees = 180.0))
    }
}
