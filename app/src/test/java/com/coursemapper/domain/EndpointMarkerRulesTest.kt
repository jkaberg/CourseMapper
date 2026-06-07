package com.coursemapper.domain

import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.hasEndpoint
import com.coursemapper.domain.model.isEndpoint
import com.coursemapper.domain.model.withEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every entry point goes through [withEndpoints], so these semantics are the contract. */
class EndpointMarkerRulesTest {

    private val everyKm = listOf(
        MarkerRule(MarkerType.START),
        MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
        MarkerRule(MarkerType.FINISH)
    )

    private val alongOnly = listOf(
        MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
        MarkerRule(MarkerType.WATER_STATION, intervalMetres = 5_000.0)
    )

    @Test
    fun `endpoint types are start and finish only`() {
        assertTrue(MarkerType.START.isEndpoint())
        assertTrue(MarkerType.FINISH.isEndpoint())
        assertFalse(MarkerType.DISTANCE.isEndpoint())
        assertFalse(MarkerType.CHECKPOINT.isEndpoint())
        assertFalse(MarkerType.WATER_STATION.isEndpoint())
        assertFalse(MarkerType.CUSTOM_DISTANCES.isEndpoint())
    }

    @Test
    fun `both endpoints can be removed independently`() {
        val noStart = everyKm.withEndpoints(includeStart = false, includeFinish = true)
        assertFalse(noStart.hasEndpoint(MarkerType.START))
        assertTrue(noStart.hasEndpoint(MarkerType.FINISH))

        val noFinish = everyKm.withEndpoints(includeStart = true, includeFinish = false)
        assertTrue(noFinish.hasEndpoint(MarkerType.START))
        assertFalse(noFinish.hasEndpoint(MarkerType.FINISH))
    }

    @Test
    fun `removing both leaves only the along-course rules`() {
        val stripped = everyKm.withEndpoints(includeStart = false, includeFinish = false)
        assertEquals(listOf(MarkerRule(MarkerType.DISTANCE, 1_000.0)), stripped)
    }

    @Test
    fun `endpoints can be added to a profile that has none`() {
        val withBoth = alongOnly.withEndpoints(includeStart = true, includeFinish = true)
        assertTrue(withBoth.hasEndpoint(MarkerType.START))
        assertTrue(withBoth.hasEndpoint(MarkerType.FINISH))
        // The profile's own rules survive, in order.
        assertEquals(alongOnly, withBoth.filterNot { it.type.isEndpoint() })
    }

    @Test
    fun `endpoints alone are a valid rule set`() {
        // "No interval markers, but do give me a start and a finish" - not
        // expressible through a profile before this existed.
        val endpointsOnly = emptyList<MarkerRule>()
            .withEndpoints(includeStart = true, includeFinish = true)
        assertEquals(2, endpointsOnly.size)
        assertTrue(endpointsOnly.hasEndpoint(MarkerType.START))
        assertTrue(endpointsOnly.hasEndpoint(MarkerType.FINISH))
    }

    @Test
    fun `a null flag leaves that endpoint as the list already has it`() {
        val untouched = everyKm.withEndpoints(includeStart = null, includeFinish = null)
        assertTrue(untouched.hasEndpoint(MarkerType.START))
        assertTrue(untouched.hasEndpoint(MarkerType.FINISH))

        // The point of null: a caller with no opinion must not add a start
        // banner to a profile that deliberately omits one.
        val stillNone = alongOnly.withEndpoints(includeStart = null, includeFinish = null)
        assertFalse(stillNone.hasEndpoint(MarkerType.START))
        assertFalse(stillNone.hasEndpoint(MarkerType.FINISH))
    }

    @Test
    fun `flags can be mixed with null`() {
        val added = alongOnly.withEndpoints(includeStart = true, includeFinish = null)
        assertTrue(added.hasEndpoint(MarkerType.START))
        assertFalse(added.hasEndpoint(MarkerType.FINISH))
    }

    @Test
    fun `applying twice changes nothing`() {
        val once  = everyKm.withEndpoints(includeStart = false, includeFinish = true)
        val twice = once.withEndpoints(includeStart = false, includeFinish = true)
        assertEquals(once, twice)
    }

    @Test
    fun `duplicate endpoint rules are normalised to one of each`() {
        // Two "Start" rules would place two markers at 0 m, which cluster into
        // one stop carrying two identical signs.
        val duplicated = listOf(
            MarkerRule(MarkerType.START),
            MarkerRule(MarkerType.START),
            MarkerRule(MarkerType.DISTANCE, 1_000.0),
            MarkerRule(MarkerType.FINISH),
            MarkerRule(MarkerType.FINISH)
        )
        val normalised = duplicated.withEndpoints(includeStart = true, includeFinish = true)

        assertEquals(1, normalised.count { it.type == MarkerType.START })
        assertEquals(1, normalised.count { it.type == MarkerType.FINISH })
        assertEquals(3, normalised.size)
    }

    @Test
    fun `along-course rules keep their configuration`() {
        val result = alongOnly.withEndpoints(includeStart = true, includeFinish = true)
        val water  = result.first { it.type == MarkerType.WATER_STATION }
        assertEquals(5_000.0, water.intervalMetres, 0.001)
    }
}
