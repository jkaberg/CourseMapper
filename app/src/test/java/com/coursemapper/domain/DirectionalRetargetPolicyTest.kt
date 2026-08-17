package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures at 63.43°N, not the equator - a longitude degree is 0.446 of a latitude one there. */
class DirectionalRetargetPolicyTest {

    private val calculator = CumulativeDistanceCalculator()
    private val policy = DirectionalRetargetPolicy(calculator)

    private val baseLat = 63.43
    private val baseLon = 10.39

    /** A point [metres] due north (positive) or south (negative) of the base. */
    private fun northOf(metres: Double, lonOffsetMetres: Double = 0.0): Pair<Double, Double> {
        val metresPerDegreeLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
        val metresPerDegreeLon = metresPerDegreeLat * Math.cos(Math.toRadians(baseLat))
        return baseLat + metres / metresPerDegreeLat to baseLon + lonOffsetMetres / metresPerDegreeLon
    }

    private fun stop(id: Long, metresNorth: Double, metresEast: Double = 0.0): DirectionalRetargetPolicy.Stop {
        val (lat, lon) = northOf(metresNorth, metresEast)
        return DirectionalRetargetPolicy.Stop(id, lat, lon)
    }

    /** Target 500 m north; the rider is at the base driving south, away from it. */
    private fun drivingAway(
        nowMs: Long,
        state: DirectionalRetargetPolicy.State,
        riderMetresNorth: Double = 0.0,
        pending: List<DirectionalRetargetPolicy.Stop> = listOf(stop(1L, 500.0), stop(2L, -600.0))
    ): Pair<DirectionalRetargetPolicy.Decision?, DirectionalRetargetPolicy.State> {
        val (lat, lon) = northOf(riderMetresNorth)
        return policy.evaluate(
            riderLat               = lat,
            riderLon               = lon,
            travelBearingDeg       = 180f,
            speedMetresPerSecond   = 8f,
            activeStop             = stop(1L, 500.0),
            distanceToActiveMetres = null,
            pendingStops           = pending,
            nowMs                  = nowMs,
            state                  = state
        )
    }

    @Test
    fun `a held turn hands over to the next pending stop ahead`() {
        val (first, afterFirst) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        assertNull("the window only opens on the first qualifying fix", first)

        // Held for longer than the confirmation window, and 100 m of ground.
        val (decision, _) = drivingAway(
            nowMs = DirectionalRetargetPolicy.CONFIRM_MS + 1_000L,
            state = afterFirst,
            riderMetresNorth = -100.0
        )
        assertNotNull(decision)
        assertEquals(2L, decision!!.stopId)
    }

    @Test
    fun `time alone does not confirm - a hairpin covers no ground`() {
        val (_, opened) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        val (decision, _) = drivingAway(
            nowMs = DirectionalRetargetPolicy.CONFIRM_MS + 5_000L,
            state = opened,
            riderMetresNorth = -5.0
        )
        assertNull(decision)
    }

    @Test
    fun `ground alone does not confirm - a fast sweep is not a turn`() {
        val (_, opened) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        val (decision, _) = drivingAway(
            nowMs = 1_000L,
            state = opened,
            riderMetresNorth = -300.0
        )
        assertNull(decision)
    }

    @Test
    fun `driving towards the target never re-targets`() {
        var state = DirectionalRetargetPolicy.State.IDLE
        repeat(20) { i ->
            val (lat, lon) = northOf(i * 20.0)
            val (decision, next) = policy.evaluate(
                riderLat               = lat,
                riderLon               = lon,
                travelBearingDeg       = 0f,   // north, straight at the target
                speedMetresPerSecond   = 8f,
                activeStop             = stop(1L, 500.0),
                distanceToActiveMetres = null,
                pendingStops           = listOf(stop(1L, 500.0), stop(2L, -600.0)),
                nowMs                  = i * 1_000L,
                state                  = state
            )
            assertNull(decision)
            state = next
        }
    }

    @Test
    fun `nothing ahead means the plan stands`() {
        var state = DirectionalRetargetPolicy.State.IDLE
        repeat(20) { i ->
            val (decision, next) = drivingAway(
                nowMs = i * 1_000L,
                state = state,
                riderMetresNorth = -i * 20.0,
                // Every other pending stop is behind the rider as well.
                pending = listOf(stop(1L, 500.0), stop(3L, 700.0))
            )
            assertNull(decision)
            state = next
        }
    }

    @Test
    fun `manoeuvring at the target does not count as turning away`() {
        var state = DirectionalRetargetPolicy.State.IDLE
        repeat(20) { i ->
            val (decision, next) = policy.evaluate(
                riderLat               = northOf(490.0 - i).first,
                riderLon               = baseLon,
                travelBearingDeg       = 180f,
                speedMetresPerSecond   = 3f,
                activeStop             = stop(1L, 500.0),
                distanceToActiveMetres = null,
                pendingStops           = listOf(stop(1L, 500.0), stop(2L, -600.0)),
                nowMs                  = i * 1_000L,
                state                  = state
            )
            assertNull(decision)
            state = next
        }
    }

    @Test
    fun `a stopped rider swinging the machine round is not turning away`() {
        val (_, opened) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        val (decision, state) = policy.evaluate(
            riderLat               = northOf(-100.0).first,
            riderLon               = baseLon,
            travelBearingDeg       = 180f,
            speedMetresPerSecond   = 0.5f,
            activeStop             = stop(1L, 500.0),
            distanceToActiveMetres = null,
            pendingStops           = listOf(stop(1L, 500.0), stop(2L, -600.0)),
            nowMs                  = DirectionalRetargetPolicy.CONFIRM_MS + 1_000L,
            state                  = opened
        )
        assertNull(decision)
        assertNull("the window closes when a gate stops holding", state.turnStartMs)
    }

    @Test
    fun `the nearest stop ahead wins, not the one the plan visits first`() {
        val (_, opened) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        val (decision, _) = drivingAway(
            nowMs = DirectionalRetargetPolicy.CONFIRM_MS + 1_000L,
            state = opened,
            riderMetresNorth = -100.0,
            pending = listOf(stop(1L, 500.0), stop(2L, -1_500.0), stop(3L, -400.0))
        )
        assertEquals(3L, decision!!.stopId)
    }

    @Test
    fun `a second re-target waits out the cooldown`() {
        val (_, opened) = drivingAway(0L, DirectionalRetargetPolicy.State.IDLE)
        val confirmedAt = DirectionalRetargetPolicy.CONFIRM_MS + 1_000L
        val (decision, afterRetarget) = drivingAway(confirmedAt, opened, riderMetresNorth = -100.0)
        assertNotNull(decision)

        var state = afterRetarget
        var fired = false
        // Drive on under exactly the same conditions for the whole cooldown.
        for (i in 1 until (DirectionalRetargetPolicy.COOLDOWN_MS / 1_000L).toInt()) {
            val (next, nextState) = drivingAway(
                nowMs = confirmedAt + i * 1_000L,
                state = state,
                riderMetresNorth = -100.0 - i * 20.0
            )
            if (next != null) fired = true
            state = nextState
        }
        assertEquals("nothing may fire inside the cooldown", false, fired)
    }
}
