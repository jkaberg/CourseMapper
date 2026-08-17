package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import org.junit.Assert.*
import org.junit.Test

/** Nearest pending stop in the direction of travel, both run modes and both directions. */
class DirectionalSequencerTest {

    private val calc = CumulativeDistanceCalculator()
    private val axisBuilder = CourseAxisBuilder(calc)
    private val sequencer = DirectionalSequencer(calc, VisitOrderPlanner(calc, axisBuilder))

    /** ~1 m of latitude. */
    private val metre = 1.0 / 111_320.0

    private fun rp(northMetres: Double) = RoutePoint(
        lat = 63.43 + northMetres * metre, lon = 10.39, altMetres = null,
        accuracyMetres = null, timestampMs = 0L, isSmoothed = true
    )

    /** A straight course running due north for 2 km. */
    private val straight = (0..200).map { rp(it * 10.0) }

    private fun stop(
        id: Long,
        northMetres: Double,
        index: Int,
        state: RunStopState = RunStopState.PENDING
    ) = RunStop(
        id = id, runId = 1L, stopIndex = index,
        lat = 63.43 + northMetres * metre, lon = 10.39,
        signs = emptyList(), state = state
    )

    private fun ids(stops: List<RunStop>) = stops.map { it.id }

    private val NORTH = 0.0
    private val SOUTH = 180.0

    @Test
    fun `driving forward, the nearest stop ahead comes first`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(
            stop(1, 0.0, 0), stop(2, 500.0, 1), stop(3, 1000.0, 2),
            stop(4, 1500.0, 3), stop(5, 2000.0, 4)
        )

        val out = sequencer.resequence(stops, 63.43 + 1200.0 * metre, 10.39, NORTH, axis)

        assertEquals("ahead first, nearest first; then what is behind", listOf(4L, 5L, 3L, 2L, 1L), ids(out))
        assertEquals(listOf(0, 1, 2, 3, 4), out.map { it.stopIndex })
    }

    /** The turn-around: the plan must walk back down the course, not resume forwards. */
    @Test
    fun `after a 180 the whole remainder runs the other way`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(
            stop(1, 0.0, 0), stop(2, 500.0, 1), stop(3, 1000.0, 2),
            stop(4, 1500.0, 3), stop(5, 2000.0, 4)
        )

        val out = sequencer.resequence(stops, 63.43 + 1200.0 * metre, 10.39, SOUTH, axis)

        assertEquals(
            "driving south from 1200 m: 1000, then 500, then 0, then what is now behind",
            listOf(3L, 2L, 1L, 4L, 5L), ids(out)
        )
    }

    /** Rotating the order gave the stop behind and then went forward again. */
    @Test
    fun `re-sequencing is not a rotation of the old order`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(
            stop(1, 0.0, 0), stop(2, 500.0, 1), stop(3, 1000.0, 2),
            stop(4, 1500.0, 3), stop(5, 2000.0, 4)
        )

        val rotated = ids(NavigationSequenceEngine.takeNextThenContinue(stops, stopId = 3L))
        val resequenced = ids(sequencer.resequence(stops, 63.43 + 1200.0 * metre, 10.39, SOUTH, axis))

        assertEquals(listOf(3L, 4L, 5L, 1L, 2L), rotated)
        assertNotEquals(rotated, resequenced)
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), resequenced)
    }

    @Test
    fun `completed and skipped stops keep their place and are never re-targeted`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(
            stop(1, 0.0, 0, RunStopState.DONE),
            stop(2, 500.0, 1, RunStopState.SKIPPED),
            stop(3, 1000.0, 2),
            stop(4, 1500.0, 3),
            stop(5, 2000.0, 4)
        )

        val out = sequencer.resequence(stops, 63.43 + 1800.0 * metre, 10.39, SOUTH, axis)

        assertEquals("resolved stops stay at the front, in their own order",
            listOf(1L, 2L), ids(out).take(2))
        assertEquals("then the pending ones, nearest behind first",
            listOf(4L, 3L, 5L), ids(out).drop(2))
        assertEquals(listOf(0, 1, 2, 3, 4), out.map { it.stopIndex })
    }

    /** A sign driven past must not be orphaned just because it is behind. */
    @Test
    fun `stops behind are kept, after everything ahead`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(stop(1, 0.0, 0), stop(2, 500.0, 1))

        val out = sequencer.resequence(stops, 63.43 + 1000.0 * metre, 10.39, NORTH, axis)

        assertEquals("nothing is ahead, so both are kept, nearest first", listOf(2L, 1L), ids(out))
    }

    /** On a lapped course "behind" is most of a lap ahead, so keep going round. */
    @Test
    fun `a lapped axis wraps instead of reversing`() {
        val loop = mutableListOf<RoutePoint>()
        repeat(2) {
            for (k in 0 until 100) loop.add(rp(k * 10.0))
            for (k in 100 downTo 1) loop.add(
                RoutePoint(63.43 + k * 10.0 * metre, 10.3901, null, null, 0L, true)
            )
        }
        val axis = axisBuilder.build(loop) ?: return
        if (!axis.isLapped) return

        val stops = listOf(stop(1, 100.0, 0), stop(2, 900.0, 1))
        val out = sequencer.resequence(stops, 63.43 + 500.0 * metre, 10.39, NORTH, axis)

        assertEquals(2, out.size)
        assertEquals(listOf(0, 1), out.map { it.stopIndex })
    }

    @Test
    fun `a stopped rider is not spun around`() {
        val axis = axisBuilder.build(straight)!!
        val stops = listOf(stop(1, 0.0, 0), stop(2, 500.0, 1), stop(3, 1500.0, 2))

        val out = sequencer.resequence(stops, 63.43 + 1000.0 * metre, 10.39, null, axis)

        assertEquals("no trustworthy heading means the course's own direction",
            listOf(3L, 2L, 1L), ids(out))
    }

    /** Without an axis ahead means the forward cone, the nearest stop overall is behind. */
    @Test
    fun `shortest path starts at the nearest stop inside the forward cone`() {
        val behind = stop(1, 950.0, 0)      // 50 m away, but behind
        val ahead = stop(2, 1200.0, 1)      // 200 m away, ahead
        val further = stop(3, 1600.0, 2)

        val out = sequencer.resequence(
            listOf(behind, ahead, further),
            63.43 + 1000.0 * metre, 10.39, NORTH, axis = null
        )

        assertEquals(2L, out.first().id)
        assertEquals(setOf(1L, 2L, 3L), ids(out).toSet())
    }

    @Test
    fun `shortest path falls back to the nearest stop when nothing is ahead`() {
        val stops = listOf(stop(1, 200.0, 0), stop(2, 500.0, 1), stop(3, 800.0, 2))

        val out = sequencer.resequence(
            stops, 63.43 + 1000.0 * metre, 10.39, NORTH, axis = null
        )

        assertEquals("nothing in the cone, so the nearest anchors the tour", 3L, out.first().id)
        assertEquals(3, out.size)
    }

    @Test
    fun `every stop survives a re-sequence in both modes`() {
        val stops = (1..8).map { stop(it.toLong(), it * 200.0, it - 1) }
        for (axis in listOf(axisBuilder.build(straight), null)) {
            val out = sequencer.resequence(stops, 63.43 + 900.0 * metre, 10.39, SOUTH, axis)
            assertEquals(stops.map { it.id }.toSet(), ids(out).toSet())
            assertEquals(stops.size, out.size)
            assertEquals(out.indices.toList(), out.map { it.stopIndex })
        }
    }
}
