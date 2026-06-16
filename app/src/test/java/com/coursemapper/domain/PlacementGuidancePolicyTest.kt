package com.coursemapper.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementGuidancePolicyTest {
    private val policy = PlacementGuidancePolicy(CumulativeDistanceCalculator())

    private fun guidance(
        riderLat: Double = 0.0,
        riderLon: Double = 0.0,
        targetLat: Double = 0.0001,
        targetLon: Double = 0.0,
        accuracy: Float = 3f,
        heading: Float? = 0f
    ) = policy.guidance(riderLat, riderLon, targetLat, targetLon, accuracy, heading)

    @Test
    fun `strong accuracy distinguishes here ahead and behind`() {
        assertEquals(PlacementGuidancePolicy.Direction.HERE, guidance(targetLat = 0.00001).direction)
        assertEquals(PlacementGuidancePolicy.Direction.AHEAD, guidance().direction)
        val behind = guidance(targetLat = -0.0001)
        assertEquals(PlacementGuidancePolicy.Direction.BEHIND, behind.direction)
        assertTrue(behind.text.endsWith("m back"))
    }

    @Test
    fun `lateral marker does not claim ahead or behind`() {
        val result = guidance(targetLat = 0.0, targetLon = 0.0001)
        assertEquals(PlacementGuidancePolicy.Direction.LATERAL, result.direction)
        assertEquals("Near marker", result.text)
    }

    @Test
    fun `missing or stale heading produces unsigned guidance`() {
        val result = guidance(heading = null)
        assertEquals(PlacementGuidancePolicy.Direction.UNSIGNED, result.direction)
        assertTrue(result.text.contains("Marker about"))
    }

    @Test
    fun `moderate accuracy rounds coarsely and says about`() {
        val result = guidance(targetLat = 0.00014, accuracy = 15f)
        assertEquals(0, result.distanceMetres!! % 5)
        assertTrue(result.text.contains("about"))
    }

    @Test
    fun `moderate accuracy does not claim exact placement nearby`() {
        val result = guidance(targetLat = 0.00005, accuracy = 15f)
        assertEquals(PlacementGuidancePolicy.Direction.UNSIGNED, result.direction)
        assertEquals("Near marker", result.text)
    }

    @Test
    fun `poor accuracy degrades near marker to GPS settling`() {
        val result = guidance(accuracy = 30f)
        assertEquals(PlacementGuidancePolicy.Direction.SETTLING, result.direction)
        assertEquals("Near marker · GPS settling", result.text)
    }

    @Test
    fun `poor accuracy only gives coarse direction when farther away`() {
        val result = guidance(targetLat = 0.001, accuracy = 30f)
        assertEquals(PlacementGuidancePolicy.Direction.AHEAD, result.direction)
        assertEquals("Marker ahead", result.text)
    }
}
