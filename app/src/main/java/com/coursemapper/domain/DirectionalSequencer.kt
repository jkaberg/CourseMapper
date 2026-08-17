package com.coursemapper.domain

import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reorders pending stops so the next one is the nearest stop ahead of the rider.
 *
 * Unlike [NavigationSequenceEngine.takeNextThenContinue] this changes the whole
 * remainder, a rider who turned around shouldn't get pointed back again after
 * one stop.
 *
 * "Ahead" depends on the mode:
 * - [RunOrdering.SPINE] further along the [CourseAxis]. Bearing alone fails on
 *   out-and-backs and hairpins, the axis knows the road.
 * - [RunOrdering.OPTIMIZED] inside [AHEAD_CONE_DEG] of the heading, then the rest
 *   is re-optimised.
 *
 * Resolved stops stay first. [DirectionalRetargetPolicy] decides when to call this.
 */
@Singleton
class DirectionalSequencer @Inject constructor(
    private val calc: CumulativeDistanceCalculator,
    private val visitOrderPlanner: VisitOrderPlanner
) {

    companion object {
        /** Same cone as [DirectionalRetargetPolicy], so the stop that triggered a retarget still qualifies here. */
        const val AHEAD_CONE_DEG = DirectionalRetargetPolicy.AHEAD_CONE_DEG
    }

    /**
     * [stops] resequenced for the rider, `stopIndex` renumbered from 0. [axis] is
     * null in shortest path mode. Null [headingDegrees] (stopped, no bearing)
     * assumes the course direction, so turning around at a gantry doesn't spin the plan.
     */
    fun resequence(
        stops: List<RunStop>,
        riderLat: Double,
        riderLon: Double,
        headingDegrees: Double?,
        axis: CourseAxis?
    ): List<RunStop> {
        val ordered = stops.sortedBy { it.stopIndex }
        val resolved = ordered.filter { it.state != RunStopState.PENDING }
        val pending = ordered.filter { it.state == RunStopState.PENDING }
        if (pending.size <= 1) return ordered

        val sequenced = if (axis != null) {
            alongAxis(pending, riderLat, riderLon, headingDegrees, axis)
        } else {
            byProximity(pending, riderLat, riderLon, headingDegrees)
        }

        return (resolved + sequenced).mapIndexed { index, stop -> stop.copy(stopIndex = index) }
    }

    /**
     * Order by distance ahead on the axis. On a lapped axis the wrap handles
     * everything, on an open one stops behind are appended nearest first.
     */
    private fun alongAxis(
        pending: List<RunStop>,
        riderLat: Double,
        riderLon: Double,
        headingDegrees: Double?,
        axis: CourseAxis
    ): List<RunStop> {
        val rider = axis.positionOf(riderLat, riderLon)?.metres ?: return pending
        val forward = headingDegrees?.let { axis.isTravellingForward(rider, it) } ?: true

        // Larger than any real forward gap, so "behind" always sorts after
        // everything ahead while still being ordered sensibly among itself.
        val behindBase = axis.lengthMetres + 1.0

        fun key(stop: RunStop): Double {
            val position = axis.positionOf(stop.lat, stop.lon)?.metres ?: return Double.MAX_VALUE
            axis.gapMetres(rider, position, forward)?.let { return it }
            val behind = axis.gapMetres(rider, position, !forward) ?: 0.0
            return behindBase + behind
        }

        return pending.sortedWith(compareBy({ key(it) }, { it.stopIndex }))
    }

    /**
     * Nearest stop in the forward cone first, then re-optimise. Falls back to the
     * nearest pending stop when nothing is ahead.
     */
    private fun byProximity(
        pending: List<RunStop>,
        riderLat: Double,
        riderLon: Double,
        headingDegrees: Double?
    ): List<RunStop> {
        val points = pending.map { VisitOrderPlanner.StopPoint(it.lat, it.lon) }

        val ahead = pending.indices.filter { i ->
            headingDegrees == null || CumulativeDistanceCalculator.bearingDeltaDegrees(
                headingDegrees,
                calc.bearingDegrees(riderLat, riderLon, pending[i].lat, pending[i].lon)
            ) <= AHEAD_CONE_DEG
        }

        val first = (ahead.ifEmpty { pending.indices.toList() }).minByOrNull { i ->
            calc.haversineMetres(riderLat, riderLon, pending[i].lat, pending[i].lon)
        } ?: return pending

        val order = visitOrderPlanner.optimizedOrder(
            stops = points,
            start = VisitOrderPlanner.StopPoint(riderLat, riderLon),
            firstIndex = first
        )
        return order.map { pending[it] }
    }
}
