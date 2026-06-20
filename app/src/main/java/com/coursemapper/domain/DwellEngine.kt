package com.coursemapper.domain

import javax.inject.Inject
import javax.inject.Singleton

/** Pure arrival verifier for the active placement stop. */
@Singleton
class DwellEngine @Inject constructor() {

    companion object {
        const val DEFAULT_RADIUS_M = 25.0
        const val DEFAULT_DWELL_SECONDS = 4.0
        const val DEFAULT_MAX_ACCURACY_M = 35f
        const val MAX_ACCRUAL_PER_FIX_MS = 3_000L

        private const val EXIT_RADIUS_MULTIPLIER = 1.25
        private const val MIN_EXIT_MARGIN_M = 5.0
        private const val DEPARTURE_CONFIRM_MS = 1_500L
        private const val DEPARTURE_CONFIRM_FIXES = 2
    }

    data class State(
        val armed: Boolean = false,
        val accruedMs: Long = 0L,
        val lastAccrualFixMs: Long? = null,
        val departureStartedMs: Long? = null,
        val departureFixCount: Int = 0,
        val confirmed: Boolean = false
    ) {
        companion object { val IDLE = State() }
    }

    sealed interface Result {
        val accruedMs: Long
        val remainingMs: Long

        data class Waiting(
            override val accruedMs: Long,
            override val remainingMs: Long
        ) : Result

        data class Counting(
            override val accruedMs: Long,
            override val remainingMs: Long,
            val fraction: Float
        ) : Result

        data class Paused(
            override val accruedMs: Long,
            override val remainingMs: Long,
            val fraction: Float
        ) : Result

        data class DepartureGrace(
            override val accruedMs: Long,
            override val remainingMs: Long,
            val fraction: Float
        ) : Result

        data class Confirmed(
            override val accruedMs: Long,
            override val remainingMs: Long = 0L
        ) : Result

        data class Outside(
            override val accruedMs: Long = 0L,
            override val remainingMs: Long
        ) : Result
    }

    /** Exit threshold used by cancellation and arrival hysteresis. */
    fun exitRadiusMetres(radiusMetres: Double): Double =
        radiusMetres + maxOf(MIN_EXIT_MARGIN_M, radiusMetres * (EXIT_RADIUS_MULTIPLIER - 1.0))

    fun process(
        distanceToTargetMetres: Double,
        accuracyMetres: Float,
        nowMs: Long,
        state: State,
        radiusMetres: Double = DEFAULT_RADIUS_M,
        dwellSeconds: Double = DEFAULT_DWELL_SECONDS,
        maxAccuracyMetres: Float = DEFAULT_MAX_ACCURACY_M,
        motion: NavigationMotion = NavigationMotion.STATIONARY,
        arrivalEnabled: Boolean = true
    ): Pair<Result, State> {
        val dwellMs = (dwellSeconds * 1_000.0).toLong().coerceAtLeast(1L)
        val remaining = { accrued: Long -> (dwellMs - accrued).coerceAtLeast(0L) }
        val fraction = { accrued: Long -> (accrued.toFloat() / dwellMs).coerceIn(0f, 1f) }

        if (state.confirmed) {
            return Result.Waiting(state.accruedMs, remaining(state.accruedMs)) to state
        }

        if (accuracyMetres > maxAccuracyMetres) {
            val pausedState = state.copy(
                lastAccrualFixMs = null,
                departureStartedMs = null,
                departureFixCount = 0
            )
            return Result.Paused(
                state.accruedMs,
                remaining(state.accruedMs),
                fraction(state.accruedMs)
            ) to pausedState
        }

        val exitRadius = exitRadiusMetres(radiusMetres)
        val beyondExit = distanceToTargetMetres > exitRadius
        val outsideState = if (beyondExit) {
            state.copy(
                lastAccrualFixMs = null,
                departureStartedMs = state.departureStartedMs ?: nowMs,
                departureFixCount = state.departureFixCount + 1
            )
        } else {
            state.copy(departureStartedMs = null, departureFixCount = 0)
        }
        val departureDuration = outsideState.departureStartedMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
        val departureConfirmed = beyondExit &&
            outsideState.departureFixCount >= DEPARTURE_CONFIRM_FIXES &&
            departureDuration >= DEPARTURE_CONFIRM_MS

        if (departureConfirmed) {
            return Result.Outside(remainingMs = dwellMs) to State.IDLE
        }

        if (!arrivalEnabled) {
            return Result.Waiting(0L, dwellMs) to outsideState.copy(
                armed = false,
                accruedMs = 0L,
                lastAccrualFixMs = null
            )
        }

        if (!state.armed) {
            if (distanceToTargetMetres <= radiusMetres && motion == NavigationMotion.STATIONARY) {
                val armed = State(armed = true, lastAccrualFixMs = nowMs)
                return Result.Counting(0L, dwellMs, 0f) to armed
            }
            return Result.Waiting(0L, dwellMs) to outsideState
        }

        val gainMs = state.lastAccrualFixMs
            ?.let { (nowMs - it).coerceIn(0L, MAX_ACCRUAL_PER_FIX_MS) }
            ?: 0L
        val accrued = (state.accruedMs + gainMs).coerceAtMost(dwellMs)
        val progressed = outsideState.copy(
            armed = true,
            accruedMs = accrued,
            lastAccrualFixMs = nowMs
        )

        if (accrued >= dwellMs) {
            return Result.Confirmed(accrued) to progressed.copy(confirmed = true)
        }

        return if (distanceToTargetMetres > radiusMetres) {
            Result.DepartureGrace(accrued, remaining(accrued), fraction(accrued)) to progressed
        } else {
            Result.Counting(accrued, remaining(accrued), fraction(accrued)) to progressed
        }
    }
}
