package com.coursemapper.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks a new target when the rider deliberately drives away from the current one
 * (locked gate, dead end, a sign that has to go up first).
 *
 * Retargeting on a whim is worse than never doing it, so all of these must hold:
 * - moving faster than [MIN_SPEED_MPS], below that bearing is noise
 * - target at least [BEHIND_CONE_DEG] off the direction of travel
 * - further than [MIN_TARGET_DISTANCE_M] from it
 * - a pending stop within [AHEAD_CONE_DEG] of the heading
 * - held for both [CONFIRM_MS] and [CONFIRM_DISTANCE_M] (hairpins hold heading
 *   without distance, fast curves cover distance in a second)
 *
 * Then [COOLDOWN_MS] before the next one. Caller keeps [State].
 */
@Singleton
class DirectionalRetargetPolicy @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {

    companion object {
        /** Travel-vs-target bearing gap at which the target counts as behind. */
        const val BEHIND_CONE_DEG = 115.0

        /** A replacement must lie within this cone of the direction of travel. */
        const val AHEAD_CONE_DEG = 55.0

        /** Below this speed the bearing is noise; nothing re-targets. */
        const val MIN_SPEED_MPS = 2.0f

        /** No re-targeting closer than this to the current target. */
        const val MIN_TARGET_DISTANCE_M = 120.0

        /** The turn must hold for this long… */
        const val CONFIRM_MS = 5_000L

        /** …and cover this much ground. */
        const val CONFIRM_DISTANCE_M = 60.0

        /** Quiet period after a re-target before another may fire. */
        const val COOLDOWN_MS = 60_000L
    }

    /** A stop considered as a target: identity and position, nothing else. */
    data class Stop(val id: Long, val lat: Double, val lon: Double)

    /** Where the rider started driving away, cleared as soon as any condition fails. */
    data class State(
        val turnStartMs: Long? = null,
        val turnStartLat: Double = 0.0,
        val turnStartLon: Double = 0.0,
        val lastRetargetMs: Long? = null
    ) {
        companion object { val IDLE = State() }
    }

    /** The stop that should become the target, and how far off it is. */
    data class Decision(val stopId: Long, val distanceMetres: Double)

    /**
     * @param pendingStops all pending stops including the active one, order doesn't matter
     * @param distanceToActiveMetres computed here when null
     * @return the retarget (null keeps the plan) and the state to keep
     */
    fun evaluate(
        riderLat: Double,
        riderLon: Double,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        activeStop: Stop?,
        distanceToActiveMetres: Double?,
        pendingStops: List<Stop>,
        nowMs: Long,
        state: State
    ): Pair<Decision?, State> {
        // Any gate that fails closes the confirmation window but keeps the
        // cooldown - the turn has to be held from scratch, not resumed.
        val closed = state.copy(turnStartMs = null)

        if (activeStop == null || travelBearingDeg == null) return null to closed
        if ((speedMetresPerSecond ?: 0f) < MIN_SPEED_MPS) return null to closed

        val lastRetargetMs = state.lastRetargetMs
        if (lastRetargetMs != null && nowMs - lastRetargetMs < COOLDOWN_MS) return null to closed

        val bearing = travelBearingDeg.toDouble()
        val distanceToActive = distanceToActiveMetres ?: distanceCalculator.haversineMetres(
            riderLat, riderLon, activeStop.lat, activeStop.lon
        )
        if (distanceToActive < MIN_TARGET_DISTANCE_M) return null to closed

        val awayFromTarget = CumulativeDistanceCalculator.bearingDeltaDegrees(
            bearing,
            distanceCalculator.bearingDegrees(riderLat, riderLon, activeStop.lat, activeStop.lon)
        ) >= BEHIND_CONE_DEG
        if (!awayFromTarget) return null to closed

        // The next one along the new direction: nearest of those ahead.  Chosen
        // fresh on every fix rather than pinned when the window opened, so the
        // stop adopted is the one that is ahead *now*, at the end of the turn.
        val candidate = pendingStops
            .asSequence()
            .filter { it.id != activeStop.id }
            .mapNotNull { stop ->
                val delta = CumulativeDistanceCalculator.bearingDeltaDegrees(
                    bearing,
                    distanceCalculator.bearingDegrees(riderLat, riderLon, stop.lat, stop.lon)
                )
                if (delta > AHEAD_CONE_DEG) return@mapNotNull null
                stop to distanceCalculator.haversineMetres(riderLat, riderLon, stop.lat, stop.lon)
            }
            .minByOrNull { (_, distance) -> distance }
            ?: return null to closed

        val turnStartMs = state.turnStartMs
            ?: return null to state.copy(
                turnStartMs  = nowMs,
                turnStartLat = riderLat,
                turnStartLon = riderLon
            )

        val heldMs = nowMs - turnStartMs
        val heldMetres = distanceCalculator.haversineMetres(
            state.turnStartLat, state.turnStartLon, riderLat, riderLon
        )
        if (heldMs < CONFIRM_MS || heldMetres < CONFIRM_DISTANCE_M) return null to state

        val (stop, distance) = candidate
        return Decision(stop.id, distance) to State(lastRetargetMs = nowMs)
    }
}
