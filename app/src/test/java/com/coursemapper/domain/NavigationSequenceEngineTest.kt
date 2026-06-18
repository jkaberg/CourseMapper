package com.coursemapper.domain

import com.coursemapper.domain.model.RunSign
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationSequenceEngineTest {

    @Test
    fun `selected marker continues forward then wraps`() {
        val reordered = NavigationSequenceEngine.takeNextThenContinue(
            stops = (1L..4L).map { stop(it) },
            stopId = 3L
        )

        assertEquals(listOf(3L, 4L, 1L, 2L), reordered.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), reordered.map { it.stopIndex })
    }

    @Test
    fun `selection at end wraps to first pending marker`() {
        val reordered = NavigationSequenceEngine.takeNextThenContinue(
            stops = (1L..4L).map { stop(it) },
            stopId = 4L
        )

        assertEquals(listOf(4L, 1L, 2L, 3L), reordered.map { it.id })
    }

    @Test
    fun `resolved markers remain before rotated pending sequence`() {
        val reordered = NavigationSequenceEngine.takeNextThenContinue(
            stops = listOf(
                stop(1L, RunStopState.DONE),
                stop(2L),
                stop(3L),
                stop(4L, RunStopState.SKIPPED),
                stop(5L)
            ),
            stopId = 3L
        )

        assertEquals(listOf(1L, 4L, 3L, 5L, 2L), reordered.map { it.id })
    }

    @Test
    fun `non-pending selection leaves order unchanged`() {
        val original = listOf(stop(1L, RunStopState.DONE), stop(2L))
        val reordered = NavigationSequenceEngine.takeNextThenContinue(original, 1L)

        assertEquals(original, reordered)
    }

    private fun stop(id: Long, state: RunStopState = RunStopState.PENDING) = RunStop(
        id = id,
        runId = 42L,
        stopIndex = id.toInt() - 1,
        lat = 0.0,
        lon = 0.0,
        signs = listOf(RunSign("Course", "$id km")),
        state = state
    )
}
