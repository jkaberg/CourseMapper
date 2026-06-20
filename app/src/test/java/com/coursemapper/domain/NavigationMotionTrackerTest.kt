package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionTrackerTest {
    private val tracker = NavigationMotionTracker(CumulativeDistanceCalculator())

    private fun fix(
        state: NavigationMotionTracker.State,
        nowMs: Long,
        lat: Double = 0.0,
        lon: Double = 0.0,
        speed: Float? = 0f,
        bearing: Float? = null,
        accuracy: Float = 5f
    ) = tracker.process(lat, lon, accuracy, nowMs, speed, bearing, state)

    @Test
    fun `movement uses hysteresis before becoming stationary`() {
        val (moving, s1) = fix(NavigationMotionTracker.State.IDLE, 0L, speed = 3f)
        assertEquals(NavigationMotion.MOVING, moving.motion)
        assertTrue(moving.becameMoving)

        val (firstSlow, s2) = fix(s1, 1_000L, speed = 0.2f)
        assertEquals(NavigationMotion.MOVING, firstSlow.motion)
        val (stationary, _) = fix(s2, 2_000L, speed = 0.2f)
        assertEquals(NavigationMotion.STATIONARY, stationary.motion)
    }

    @Test
    fun `bearing is retained briefly while stopped then expires`() {
        val (_, s1) = fix(
            NavigationMotionTracker.State.IDLE, 0L, speed = 4f, bearing = 90f
        )
        val (_, s2) = fix(s1, 1_000L, speed = 0f)
        val (stopped, s3) = fix(s2, 2_000L, speed = 0f)
        assertEquals(90f, stopped.travelBearingDeg)

        val (stale, _) = fix(s3, 13_000L, accuracy = 40f)
        assertNull(stale.travelBearingDeg)
    }

    @Test
    fun `displacement derives movement and bearing when fused speed is absent`() {
        val (_, first) = fix(
            NavigationMotionTracker.State.IDLE, 0L, speed = null
        )
        val (second, _) = fix(
            first,
            1_000L,
            lat = 0.0001,
            speed = null
        )

        assertEquals(NavigationMotion.MOVING, second.motion)
        assertTrue(second.travelBearingDeg!! < 1f || second.travelBearingDeg > 359f)
    }
}
