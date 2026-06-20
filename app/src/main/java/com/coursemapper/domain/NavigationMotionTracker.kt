package com.coursemapper.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import javax.inject.Inject

enum class NavigationMotion { UNKNOWN, STATIONARY, MOVING }

/** Pure motion hysteresis and short-lived travel-bearing retention. */
class NavigationMotionTracker @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {
    companion object {
        const val MAX_RELIABLE_ACCURACY_M = DwellEngine.DEFAULT_MAX_ACCURACY_M
        const val ENTER_MOVING_SPEED_MPS = 1.8f
        const val EXIT_MOVING_SPEED_MPS = 0.8f
        const val STATIONARY_FIXES_REQUIRED = 2
        const val BEARING_RETENTION_MS = 12_000L
        private const val MIN_BEARING_DISPLACEMENT_M = 3.0
    }

    data class State(
        val lastLat: Double? = null,
        val lastLon: Double? = null,
        val lastFixMs: Long? = null,
        val motion: NavigationMotion = NavigationMotion.UNKNOWN,
        val lowSpeedFixes: Int = 0,
        val speedMetresPerSecond: Float? = null,
        val reliableBearingDeg: Float? = null,
        val bearingFixMs: Long? = null
    ) {
        companion object { val IDLE = State() }
    }

    data class Snapshot(
        val motion: NavigationMotion,
        val becameMoving: Boolean,
        val speedMetresPerSecond: Float?,
        val travelBearingDeg: Float?
    )

    fun process(
        latitude: Double,
        longitude: Double,
        accuracyMetres: Float,
        nowMs: Long,
        fusedSpeedMetresPerSecond: Float?,
        fusedBearingDeg: Float?,
        state: State
    ): Pair<Snapshot, State> {
        if (accuracyMetres > MAX_RELIABLE_ACCURACY_M) {
            return snapshot(state, nowMs, becameMoving = false) to state
        }

        val elapsedSeconds = state.lastFixMs
            ?.let { (nowMs - it).coerceAtLeast(0L) / 1_000.0 }
            ?: 0.0
        val displacement = if (state.lastLat != null && state.lastLon != null) {
            distanceCalculator.haversineMetres(
                state.lastLat, state.lastLon, latitude, longitude
            )
        } else null
        val derivedSpeed = if (displacement != null && elapsedSeconds > 0.0) {
            (displacement / elapsedSeconds).toFloat()
        } else null
        val measuredSpeed = fusedSpeedMetresPerSecond?.takeIf { it >= 0f } ?: derivedSpeed

        val previousMotion = state.motion
        val (motion, lowSpeedFixes) = when {
            measuredSpeed == null -> previousMotion to state.lowSpeedFixes
            measuredSpeed >= ENTER_MOVING_SPEED_MPS -> NavigationMotion.MOVING to 0
            previousMotion == NavigationMotion.MOVING && measuredSpeed <= EXIT_MOVING_SPEED_MPS -> {
                val count = state.lowSpeedFixes + 1
                if (count >= STATIONARY_FIXES_REQUIRED) NavigationMotion.STATIONARY to count
                else NavigationMotion.MOVING to count
            }
            previousMotion == NavigationMotion.UNKNOWN && measuredSpeed <= EXIT_MOVING_SPEED_MPS ->
                NavigationMotion.STATIONARY to 1
            previousMotion != NavigationMotion.MOVING -> NavigationMotion.STATIONARY to 0
            else -> NavigationMotion.MOVING to 0
        }

        val displacementBearing = if (
            displacement != null && displacement >= MIN_BEARING_DISPLACEMENT_M &&
            state.lastLat != null && state.lastLon != null
        ) bearingDegrees(state.lastLat, state.lastLon, latitude, longitude) else null
        val candidateBearing = fusedBearingDeg?.takeIf {
            measuredSpeed != null && measuredSpeed >= EXIT_MOVING_SPEED_MPS
        } ?: displacementBearing?.takeIf { motion == NavigationMotion.MOVING }
        val bearing = candidateBearing ?: state.reliableBearingDeg
        val bearingFixMs = if (candidateBearing != null) nowMs else state.bearingFixMs

        val newState = State(
            lastLat = latitude,
            lastLon = longitude,
            lastFixMs = nowMs,
            motion = motion,
            lowSpeedFixes = lowSpeedFixes,
            speedMetresPerSecond = measuredSpeed,
            reliableBearingDeg = bearing,
            bearingFixMs = bearingFixMs
        )
        return snapshot(newState, nowMs, becameMoving = previousMotion != NavigationMotion.MOVING && motion == NavigationMotion.MOVING) to newState
    }

    private fun snapshot(state: State, nowMs: Long, becameMoving: Boolean): Snapshot {
        val bearing = state.reliableBearingDeg?.takeIf {
            state.bearingFixMs != null && nowMs - state.bearingFixMs <= BEARING_RETENTION_MS
        }
        return Snapshot(state.motion, becameMoving, state.speedMetresPerSecond, bearing)
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}
