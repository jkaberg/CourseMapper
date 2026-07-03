package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RerouteDecisionPolicyTest {

    private val policy = RerouteDecisionPolicy()

    /** Freshly adopted leg, so neither clock is due. */
    private fun onLeg(nowMs: Long = 0L) = policy.onLegAdopted(nowMs)

    private fun evaluate(
        state: RerouteDecisionPolicy.State,
        nowMs: Long,
        offsetMetres: Double? = 5.0,
        legBearingDeg: Double? = 0.0,
        travelBearingDeg: Float? = 0f,
        speedMetresPerSecond: Float? = 8f,
        remainingMetres: Double? = 200.0
    ) = policy.evaluate(
        offsetMetres         = offsetMetres,
        legBearingDeg        = legBearingDeg,
        travelBearingDeg     = travelBearingDeg,
        speedMetresPerSecond = speedMetresPerSecond,
        remainingMetres      = remainingMetres,
        nowMs                = nowMs,
        state                = state
    )

    @Test
    fun `driving the leg asks for nothing`() {
        var state = onLeg()
        repeat(10) { i ->
            val (reason, next) = evaluate(state, nowMs = (i + 1) * 1_000L)
            assertNull(reason)
            state = next
        }
    }

    @Test
    fun `one wild fix off the line is not a re-route`() {
        val start = onLeg()
        val (reason, _) = evaluate(
            start,
            nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + 1_000L,
            offsetMetres = 120.0
        )
        assertNull(reason)
    }

    @Test
    fun `leaving the leg re-routes once the fixes agree`() {
        var state = onLeg()
        var reason: RerouteDecisionPolicy.Reason? = null
        repeat(RerouteDecisionPolicy.OFF_ROUTE_FIXES) { i ->
            val nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + (i + 1) * 1_000L
            val (r, next) = evaluate(state, nowMs = nowMs, offsetMetres = 120.0)
            reason = r
            state = next
        }
        assertEquals(RerouteDecisionPolicy.Reason.OFF_ROUTE, reason)
        assertEquals("counters reset with the new leg", 0, state.offRouteFixes)
    }

    @Test
    fun `a leg that cannot be projected onto at all is off route`() {
        var state = onLeg()
        var reason: RerouteDecisionPolicy.Reason? = null
        repeat(RerouteDecisionPolicy.OFF_ROUTE_FIXES) { i ->
            val nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + (i + 1) * 1_000L
            val (r, next) = evaluate(state, nowMs = nowMs, offsetMetres = null)
            reason = r
            state = next
        }
        assertEquals(RerouteDecisionPolicy.Reason.OFF_ROUTE, reason)
    }

    @Test
    fun `driving the leg backwards re-routes even though the rider is on it`() {
        var state = onLeg()
        var reason: RerouteDecisionPolicy.Reason? = null
        repeat(RerouteDecisionPolicy.WRONG_WAY_FIXES) { i ->
            val nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + (i + 1) * 1_000L
            val (r, next) = evaluate(
                state, nowMs = nowMs,
                offsetMetres = 4.0, legBearingDeg = 10.0, travelBearingDeg = 190f
            )
            reason = r
            state = next
        }
        assertEquals(RerouteDecisionPolicy.Reason.WRONG_WAY, reason)
    }

    @Test
    fun `a stationary rider is never driving the wrong way`() {
        var state = onLeg()
        repeat(10) { i ->
            val (reason, next) = evaluate(
                state,
                nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + (i + 1) * 1_000L,
                offsetMetres = 4.0, legBearingDeg = 10.0, travelBearingDeg = 190f,
                speedMetresPerSecond = 0.4f,
                // Short enough that the better-route probe stays out of the way.
                remainingMetres = 100.0
            )
            assertNull(reason)
            state = next
        }
    }

    @Test
    fun `corrections are throttled`() {
        var state = onLeg()
        // First correction.
        repeat(RerouteDecisionPolicy.OFF_ROUTE_FIXES) { i ->
            val nowMs = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS + (i + 1) * 1_000L
            state = evaluate(state, nowMs = nowMs, offsetMetres = 120.0).second
        }
        val firedAt = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS +
            RerouteDecisionPolicy.OFF_ROUTE_FIXES * 1_000L

        // Still off route, still inside the throttle: nothing more goes out.
        repeat((RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS / 1_000L).toInt() - 1) { i ->
            val (reason, next) = evaluate(
                state, nowMs = firedAt + (i + 1) * 1_000L, offsetMetres = 120.0
            )
            assertNull(reason)
            state = next
        }
    }

    @Test
    fun `a long leg is probed for something shorter, but only occasionally`() {
        var state = onLeg()
        // Nothing while the leg is young.
        repeat(10) { i ->
            val (reason, next) = evaluate(state, nowMs = (i + 1) * 1_000L, remainingMetres = 4_000.0)
            assertNull(reason)
            state = next
        }
        val (reason, next) = evaluate(
            state,
            nowMs = RerouteDecisionPolicy.ALTERNATIVE_INTERVAL_MS,
            remainingMetres = 4_000.0
        )
        assertEquals(RerouteDecisionPolicy.Reason.BETTER_ROUTE, reason)
        assertEquals(
            "a probe is not a correction and must not start the correction clock",
            0L, next.lastRerouteMs
        )
    }

    @Test
    fun `a leg with little left is not worth probing`() {
        var state = onLeg()
        repeat(200) { i ->
            val (reason, next) = evaluate(
                state,
                nowMs = (i + 1) * 1_000L,
                remainingMetres = RerouteDecisionPolicy.ALTERNATIVE_MIN_REMAINING_M - 1.0
            )
            assertNull(reason)
            state = next
        }
    }

    @Test
    fun `an alternative must be clearly shorter to be adopted`() {
        // 4 km left: a 200 m saving is inside the noise, 1 km is not.
        assertFalse(policy.shouldAdoptAlternative(4_000.0, 3_800.0))
        assertTrue(policy.shouldAdoptAlternative(4_000.0, 3_000.0))
        // 500 m left: 15 % of it is under the absolute floor, so nothing passes.
        assertFalse(policy.shouldAdoptAlternative(500.0, 420.0))
        // No current leg to compare against - anything is an improvement.
        assertTrue(policy.shouldAdoptAlternative(null, 9_000.0))
    }

    @Test
    fun `a shorter answer from a worse-weighted source is never adopted`() {
        // Same numbers that pass above, and a saving big enough to be real.
        assertTrue(policy.shouldAdoptAlternative(4_000.0, 3_000.0))
        // The fallback router does not weight cycling infrastructure, so its
        // shorter answer is the arterial, not a better route.  Adopting it
        // would quietly undo the rider's travel profile mid-run.
        assertFalse(
            policy.shouldAdoptAlternative(4_000.0, 3_000.0, alternativeIsLowerQuality = true)
        )
    }
}
