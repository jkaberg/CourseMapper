package com.coursemapper.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * When to fetch the leg to the active stop again.
 *
 * - [Reason.OFF_ROUTE] off the leg for [OFF_ROUTE_FIXES] fixes, which allows the
 *   tight [OFF_ROUTE_METRES]
 * - [Reason.WRONG_WAY] on the leg but driving it backwards
 * - [Reason.BETTER_ROUTE] the leg is old, probe from here and keep it only if
 *   [shouldAdoptAlternative] says so
 *
 * Routing servers are public and keyless, so corrections are throttled by
 * [MIN_REROUTE_INTERVAL_MS] and probes by [ALTERNATIVE_INTERVAL_MS]. A failed
 * fetch changes nothing. Caller keeps [State].
 */
@Singleton
class RerouteDecisionPolicy @Inject constructor() {

    companion object {
        /** Distance from the leg that counts as off it. */
        const val OFF_ROUTE_METRES = 35.0

        /** Consecutive off-leg fixes before a re-route. */
        const val OFF_ROUTE_FIXES = 3

        /** Travel-vs-leg bearing gap that counts as driving the leg backwards. */
        const val WRONG_WAY_DELTA_DEG = 120.0

        /** Consecutive wrong-way fixes before a re-route. */
        const val WRONG_WAY_FIXES = 3

        /** Wrong-way needs real motion; below this the bearing means nothing. */
        const val WRONG_WAY_MIN_SPEED_MPS = 2.0f

        /** Minimum gap between two corrections. */
        const val MIN_REROUTE_INTERVAL_MS = 12_000L

        /** Minimum gap between two opportunistic better-route probes. */
        const val ALTERNATIVE_INTERVAL_MS = 90_000L

        /** Below this remaining distance a better route cannot save anything. */
        const val ALTERNATIVE_MIN_REMAINING_M = 400.0

        /** A probe is adopted only at this fraction of the current remaining… */
        const val ALTERNATIVE_GAIN_FRACTION = 0.85

        /** …and only if it saves at least this much ground. */
        const val ALTERNATIVE_MIN_GAIN_M = 150.0
    }

    enum class Reason {
        /** The rider has left the leg. */
        OFF_ROUTE,

        /** The rider is on the leg, driving it the wrong way. */
        WRONG_WAY,

        /** Routine check for a shorter approach from where the rider is now. */
        BETTER_ROUTE
    }

    data class State(
        val offRouteFixes: Int = 0,
        val wrongWayFixes: Int = 0,
        val lastRerouteMs: Long? = null,
        val lastAlternativeMs: Long? = null
    ) {
        companion object { val IDLE = State() }
    }

    /** Call when a leg is adopted. Resets counters and clocks so a fresh leg isn't probed right away. */
    fun onLegAdopted(nowMs: Long): State =
        State(lastRerouteMs = nowMs, lastAlternativeMs = nowMs)

    /**
     * @param offsetMetres distance to the leg, null (couldn't project) counts as off route
     * @param legBearingDeg null skips the wrong way check
     * @param remainingMetres road distance left on the leg
     * @return why to fetch (null keeps the leg) and the state to keep
     */
    fun evaluate(
        offsetMetres: Double?,
        legBearingDeg: Double?,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        remainingMetres: Double?,
        nowMs: Long,
        state: State
    ): Pair<Reason?, State> {
        val offRoute = offsetMetres == null || offsetMetres > OFF_ROUTE_METRES
        val wrongWay = !offRoute &&
            legBearingDeg != null &&
            travelBearingDeg != null &&
            (speedMetresPerSecond ?: 0f) >= WRONG_WAY_MIN_SPEED_MPS &&
            CumulativeDistanceCalculator.bearingDeltaDegrees(
                travelBearingDeg.toDouble(), legBearingDeg
            ) >= WRONG_WAY_DELTA_DEG

        val counted = state.copy(
            offRouteFixes  = if (offRoute) state.offRouteFixes + 1 else 0,
            wrongWayFixes  = if (wrongWay) state.wrongWayFixes + 1 else 0
        )

        val lastRerouteMs = counted.lastRerouteMs
        if (lastRerouteMs != null && nowMs - lastRerouteMs < MIN_REROUTE_INTERVAL_MS) {
            return null to counted
        }

        val correction = when {
            counted.offRouteFixes >= OFF_ROUTE_FIXES -> Reason.OFF_ROUTE
            counted.wrongWayFixes >= WRONG_WAY_FIXES -> Reason.WRONG_WAY
            else -> null
        }
        if (correction != null) {
            return correction to counted.copy(
                offRouteFixes = 0,
                wrongWayFixes = 0,
                lastRerouteMs = nowMs
            )
        }

        val lastAlternativeMs = counted.lastAlternativeMs
        val probeDue = lastAlternativeMs == null ||
            nowMs - lastAlternativeMs >= ALTERNATIVE_INTERVAL_MS
        val worthProbing = remainingMetres != null &&
            remainingMetres >= ALTERNATIVE_MIN_REMAINING_M &&
            (speedMetresPerSecond ?: 0f) >= WRONG_WAY_MIN_SPEED_MPS
        if (probeDue && worthProbing) {
            return Reason.BETTER_ROUTE to counted.copy(lastAlternativeMs = nowMs)
        }

        return null to counted
    }

    /**
     * Whether a probed route replaces the leg. Needs both a relative and an
     * absolute gain, either alone churns on short or long legs.
     */
    fun shouldAdoptAlternative(
        currentRemainingMetres: Double?,
        alternativeMetres: Double,
        /** The alternative's source respects the travel profile less than the current leg's. */
        alternativeIsLowerQuality: Boolean = false
    ): Boolean {
        // shorter from a worse source is just the arterial again, it would undo
        // the bike profile. Corrections don't care, being lost is worse.
        if (alternativeIsLowerQuality) return false
        val current = currentRemainingMetres ?: return true
        return alternativeMetres <= current * ALTERNATIVE_GAIN_FRACTION &&
            current - alternativeMetres >= ALTERNATIVE_MIN_GAIN_M
    }
}
