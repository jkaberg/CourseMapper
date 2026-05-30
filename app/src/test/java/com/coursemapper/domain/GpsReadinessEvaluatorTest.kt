package com.coursemapper.domain

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GpsReadinessEvaluatorTest {

    private lateinit var evaluator: GpsReadinessEvaluator

    @Before
    fun setUp() {
        evaluator = GpsReadinessEvaluator()
    }

    private fun mockLocation(ageSeconds: Long, accuracyMetres: Float): Location {
        val loc = mockk<Location>()
        every { loc.time }     returns System.currentTimeMillis() - ageSeconds * 1_000L
        every { loc.accuracy } returns accuracyMetres
        return loc
    }

    @Test
    fun `recording null location is Red`() {
        val result = evaluator.evaluate(null, GpsReadinessEvaluator.Context.RECORDING)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `recording green - age 10s accuracy 10m`() {
        val loc = mockLocation(10L, 10f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Green)
    }

    @Test
    fun `recording green - age exactly 30s accuracy exactly 20m`() {
        val loc = mockLocation(30L, 20f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Green)
    }

    @Test
    fun `recording yellow - age 31s accuracy 20m`() {
        val loc = mockLocation(31L, 20f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `recording yellow - age 10s accuracy 30m`() {
        val loc = mockLocation(10L, 30f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `recording yellow - age exactly 60s accuracy exactly 50m`() {
        val loc = mockLocation(60L, 50f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `recording red - age 61s`() {
        val loc = mockLocation(61L, 10f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `recording red - accuracy 51m`() {
        val loc = mockLocation(10L, 51f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `recording red - both age and accuracy exceed thresholds`() {
        val loc = mockLocation(90L, 80f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `navigation null location is Red`() {
        val result = evaluator.evaluate(null, GpsReadinessEvaluator.Context.NAVIGATION)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `navigation green - age 10s accuracy 20m`() {
        val loc = mockLocation(10L, 20f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Green)
    }

    @Test
    fun `navigation green - age exactly 15s accuracy exactly 30m`() {
        val loc = mockLocation(15L, 30f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Green)
    }

    @Test
    fun `navigation yellow - age 16s accuracy 30m`() {
        val loc = mockLocation(16L, 30f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `navigation yellow - age 10s accuracy 40m`() {
        val loc = mockLocation(10L, 40f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `navigation yellow - age exactly 30s accuracy exactly 50m`() {
        val loc = mockLocation(30L, 50f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(result is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }

    @Test
    fun `navigation red - age 31s`() {
        val loc = mockLocation(31L, 20f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `navigation red - accuracy 51m`() {
        val loc = mockLocation(10L, 51f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertEquals(GpsReadinessEvaluator.GpsReadiness.Red, result)
    }

    @Test
    fun `green result carries accuracy and age`() {
        val loc = mockLocation(5L, 8f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING) as GpsReadinessEvaluator.GpsReadiness.Green
        assertEquals(8f, result.accuracyMetres, 0.01f)
        assertTrue(result.ageSeconds in 4L..6L)  // allow 1 s clock skew
    }

    @Test
    fun `yellow result carries accuracy and age`() {
        val loc = mockLocation(45L, 35f)
        val result = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING) as GpsReadinessEvaluator.GpsReadiness.Yellow
        assertEquals(35f, result.accuracyMetres, 0.01f)
        assertTrue(result.ageSeconds in 44L..46L)
    }

    @Test
    fun `age-20s fix is green for recording but yellow for navigation`() {
        val loc = mockLocation(20L, 10f)
        val recResult = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.RECORDING)
        val navResult = evaluator.evaluate(loc, GpsReadinessEvaluator.Context.NAVIGATION)
        assertTrue(recResult is GpsReadinessEvaluator.GpsReadiness.Green)
        assertTrue(navResult is GpsReadinessEvaluator.GpsReadiness.Yellow)
    }
}
