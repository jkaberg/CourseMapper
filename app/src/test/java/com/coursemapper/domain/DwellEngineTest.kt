package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DwellEngineTest {
    private lateinit var engine: DwellEngine

    @Before
    fun setUp() {
        engine = DwellEngine()
    }

    private fun fix(
        state: DwellEngine.State,
        nowMs: Long,
        distM: Double = 5.0,
        accuracy: Float = 8f,
        radius: Double = 25.0,
        dwellSeconds: Double = 4.0,
        motion: NavigationMotion = NavigationMotion.STATIONARY,
        arrivalEnabled: Boolean = true
    ) = engine.process(
        distanceToTargetMetres = distM,
        accuracyMetres = accuracy,
        nowMs = nowMs,
        state = state,
        radiusMetres = radius,
        dwellSeconds = dwellSeconds,
        motion = motion,
        arrivalEnabled = arrivalEnabled
    )

    @Test
    fun `stationary arrival confirms at exact configured duration`() {
        var state = DwellEngine.State.IDLE
        for (time in 0L..3_000L step 1_000L) {
            val (result, next) = fix(state, time)
            assertFalse(result is DwellEngine.Result.Confirmed)
            state = next
        }

        val (result, confirmed) = fix(state, 4_000L)
        assertTrue(result is DwellEngine.Result.Confirmed)
        assertEquals(4_000L, confirmed.accruedMs)
    }

    @Test
    fun `moving drive-by never arms arrival`() {
        var state = DwellEngine.State.IDLE
        repeat(10) { index ->
            val (result, next) = fix(
                state,
                nowMs = index * 1_000L,
                motion = NavigationMotion.MOVING
            )
            assertTrue(result is DwellEngine.Result.Waiting)
            state = next
        }
        assertFalse(state.armed)
        assertEquals(0L, state.accruedMs)
    }

    @Test
    fun `poor accuracy pauses and clears accrual baseline`() {
        var state = fix(DwellEngine.State.IDLE, 0L).second
        state = fix(state, 1_000L).second
        assertEquals(1_000L, state.accruedMs)

        val (paused, pausedState) = fix(state, 2_000L, accuracy = 80f)
        assertTrue(paused is DwellEngine.Result.Paused)
        assertEquals(1_000L, pausedState.accruedMs)
        assertNull(pausedState.lastAccrualFixMs)

        val (_, resumed) = fix(pausedState, 20_000L)
        assertEquals(1_000L, resumed.accruedMs)
    }

    @Test
    fun `monotonic regression never adds dwell time`() {
        var state = fix(DwellEngine.State.IDLE, 2_000L).second
        state = fix(state, 3_000L).second
        val (_, regressed) = fix(state, 2_500L)
        assertEquals(1_000L, regressed.accruedMs)
    }

    @Test
    fun `radius edge jitter accrues in departure grace`() {
        var state = fix(DwellEngine.State.IDLE, 0L).second
        state = fix(state, 1_000L).second
        val (result, jittered) = fix(state, 2_000L, distM = 28.0)

        assertTrue(result is DwellEngine.Result.DepartureGrace)
        assertEquals(2_000L, jittered.accruedMs)
        assertTrue(jittered.armed)
    }

    @Test
    fun `brief departure after arrival can finish countdown`() {
        var state = fix(DwellEngine.State.IDLE, 0L, dwellSeconds = 3.0).second
        state = fix(state, 1_000L, dwellSeconds = 3.0).second
        state = fix(state, 2_000L, distM = 28.0, dwellSeconds = 3.0).second
        val (result, _) = fix(state, 3_000L, distM = 28.0, dwellSeconds = 3.0)

        assertTrue(result is DwellEngine.Result.Confirmed)
    }

    @Test
    fun `sustained departure beyond exit radius resets`() {
        var state = fix(DwellEngine.State.IDLE, 0L).second
        state = fix(state, 1_000L).second
        state = fix(state, 2_000L, distM = 40.0).second
        val (result, reset) = fix(state, 4_000L, distM = 40.0)

        assertTrue(result is DwellEngine.Result.Outside)
        assertEquals(DwellEngine.State.IDLE, reset)
    }

    @Test
    fun `arrival disabled only rearms after reliable sustained exit`() {
        var state = DwellEngine.State.IDLE
        repeat(5) { index ->
            val (result, next) = fix(
                state,
                nowMs = index * 1_000L,
                arrivalEnabled = false
            )
            assertTrue(result is DwellEngine.Result.Waiting)
            state = next
        }
        val (firstOutside, firstState) = fix(
            state, 5_000L, distM = 40.0, arrivalEnabled = false
        )
        assertFalse(firstOutside is DwellEngine.Result.Outside)
        val (outside, _) = fix(
            firstState, 7_000L, distM = 40.0, arrivalEnabled = false
        )
        assertTrue(outside is DwellEngine.Result.Outside)
    }

    @Test
    fun `inaccurate interval cannot confirm a suppressed exit`() {
        var state = fix(
            DwellEngine.State.IDLE, 0L, distM = 40.0, arrivalEnabled = false
        ).second
        state = fix(
            state, 10_000L, distM = 40.0, accuracy = 80f, arrivalEnabled = false
        ).second
        val (result, resumed) = fix(
            state, 20_000L, distM = 40.0, arrivalEnabled = false
        )

        assertFalse(result is DwellEngine.Result.Outside)
        assertEquals(1, resumed.departureFixCount)
    }

    @Test
    fun `confirmation is emitted only once`() {
        var state = DwellEngine.State.IDLE
        for (time in 0L..4_000L step 1_000L) state = fix(state, time).second

        val (nextResult, nextState) = fix(state, 5_000L)
        assertTrue(nextResult is DwellEngine.Result.Waiting)
        assertTrue(nextState.confirmed)
    }

    @Test
    fun `single long gap remains capped`() {
        val armed = fix(DwellEngine.State.IDLE, 0L).second
        val (result, state) = fix(armed, 30_000L, dwellSeconds = 10.0)

        assertTrue(result is DwellEngine.Result.Counting)
        assertEquals(DwellEngine.MAX_ACCRUAL_PER_FIX_MS, state.accruedMs)
    }
}
