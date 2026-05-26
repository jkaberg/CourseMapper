package com.coursemapper.domain

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LocationFilterTest {

    private lateinit var filter: LocationFilter

    @Before
    fun setUp() {
        filter = LocationFilter()
        filter.reset()
    }

    /** [distanceTo] goes on the earlier mock of a pair, the filter calls `lastAccepted.distanceTo(newFix)`. */
    private fun mockLocation(
        accuracy: Float = 10f,
        timeMs: Long = System.currentTimeMillis(),
        distanceTo: Float = 0f
    ): Location {
        val loc = mockk<Location>()
        every { loc.accuracy }                  returns accuracy
        every { loc.time }                      returns timeMs
        every { loc.distanceTo(any()) }         returns distanceTo
        return loc
    }

    @Test
    fun `fix with accuracy exactly at threshold is accepted`() {
        val loc = mockLocation(accuracy = 50f)
        val result = filter.evaluate(loc)
        assertTrue(result.accepted)
        assertTrue(result.shouldUseForRoute)
        assertNull(result.rejectedFor)
    }

    @Test
    fun `fix with accuracy just above threshold is rejected for POOR_ACCURACY`() {
        val loc = mockLocation(accuracy = 50.1f)
        val result = filter.evaluate(loc)
        assertFalse(result.accepted)
        assertFalse(result.shouldUseForRoute)
        assertEquals(LocationFilter.RejectionReason.POOR_ACCURACY, result.rejectedFor)
    }

    @Test
    fun `fix with very poor accuracy is rejected`() {
        val loc = mockLocation(accuracy = 200f)
        val result = filter.evaluate(loc)
        assertFalse(result.accepted)
        assertEquals(LocationFilter.RejectionReason.POOR_ACCURACY, result.rejectedFor)
    }

    @Test
    fun `fix with good accuracy is accepted`() {
        val loc = mockLocation(accuracy = 5f)
        val result = filter.evaluate(loc)
        assertTrue(result.accepted)
    }

    @Test
    fun `second fix with older timestamp is rejected for TIMESTAMP_REGRESSION`() {
        val now = System.currentTimeMillis()
        val first  = mockLocation(accuracy = 5f, timeMs = now)
        val second = mockLocation(accuracy = 5f, timeMs = now - 1_000L)  // 1 s older

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertFalse(result.accepted)
        assertEquals(LocationFilter.RejectionReason.TIMESTAMP_REGRESSION, result.rejectedFor)
    }

    @Test
    fun `second fix with equal timestamp is not rejected for regression`() {
        val now = System.currentTimeMillis()
        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 0f)
        val second = mockLocation(accuracy = 5f, timeMs = now)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertNotEquals(LocationFilter.RejectionReason.TIMESTAMP_REGRESSION, result.rejectedFor)
    }

    @Test
    fun `position spike at implausible speed is rejected`() {
        val now = System.currentTimeMillis()
        // Distance = 10 000 m in 1 s → 10 000 m/s >> 30 m/s max
        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 10_000f)
        val second = mockLocation(accuracy = 5f, timeMs = now + 1_000L)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertFalse(result.accepted)
        assertEquals(LocationFilter.RejectionReason.POSITION_SPIKE, result.rejectedFor)
    }

    @Test
    fun `plausible speed fix is accepted`() {
        val now = System.currentTimeMillis()
        // 100 m in 10 s = 10 m/s - normal walking/running speed
        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 100f)
        val second = mockLocation(accuracy = 5f, timeMs = now + 10_000L)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertTrue(result.accepted)
    }

    @Test
    fun `fix at exactly max speed is accepted`() {
        val now = System.currentTimeMillis()
        // Exactly 30 m/s in 1 s
        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 30f)
        val second = mockLocation(accuracy = 5f, timeMs = now + 1_000L)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertTrue(result.accepted)
    }

    @Test
    fun `fix just over max speed is rejected as spike`() {
        val now = System.currentTimeMillis()
        // 31 m/s in 1 s
        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 31f)
        val second = mockLocation(accuracy = 5f, timeMs = now + 1_000L)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertEquals(LocationFilter.RejectionReason.POSITION_SPIKE, result.rejectedFor)
    }

    @Test
    fun `first fix is never rejected for spike or regression`() {
        val loc = mockLocation(accuracy = 5f)
        val result = filter.evaluate(loc)
        assertTrue(result.accepted)
        assertNotEquals(LocationFilter.RejectionReason.POSITION_SPIKE, result.rejectedFor)
        assertNotEquals(LocationFilter.RejectionReason.TIMESTAMP_REGRESSION, result.rejectedFor)
    }

    @Test
    fun `after reset, old accepted fix does not influence new session`() {
        val now = System.currentTimeMillis()
        // First session: accept a fix
        filter.evaluate(mockLocation(accuracy = 5f, timeMs = now - 10_000L))
        filter.reset()

        // New session: fix with older timestamp than previous session should NOT be flagged
        val newFix = mockLocation(accuracy = 5f, timeMs = now - 20_000L)
        val result = filter.evaluate(newFix)
        // Should be accepted because reset cleared history
        assertNotEquals(LocationFilter.RejectionReason.TIMESTAMP_REGRESSION, result.rejectedFor)
    }

    @Test
    fun `custom accuracy threshold is respected`() {
        filter.maxAccuracyMetres = 20f
        val loc = mockLocation(accuracy = 25f)
        val result = filter.evaluate(loc)
        assertEquals(LocationFilter.RejectionReason.POOR_ACCURACY, result.rejectedFor)
    }

    @Test
    fun `custom speed threshold is respected`() {
        val now = System.currentTimeMillis()
        filter.maxSpeedMs = 5f

        val first  = mockLocation(accuracy = 5f, timeMs = now, distanceTo = 6f)
        val second = mockLocation(accuracy = 5f, timeMs = now + 1_000L)

        filter.evaluate(first)
        val result = filter.evaluate(second)

        assertEquals(LocationFilter.RejectionReason.POSITION_SPIKE, result.rejectedFor)
    }

    @Test
    fun `filterBatch processes all fixes and resets state`() {
        val now = System.currentTimeMillis()
        val locations = listOf(
            mockLocation(accuracy = 5f, timeMs = now,           distanceTo = 0f),
            mockLocation(accuracy = 5f, timeMs = now + 1_000L,  distanceTo = 5f),
            mockLocation(accuracy = 5f, timeMs = now + 2_000L,  distanceTo = 5f)
        )
        val results = filter.filterBatch(locations)
        assertEquals(3, results.size)
        assertTrue(results[0].accepted)
    }
}
