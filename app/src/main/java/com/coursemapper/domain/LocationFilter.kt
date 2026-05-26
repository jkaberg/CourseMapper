package com.coursemapper.domain

import android.location.Location
import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject

/**
 * Rejects fixes that are too inaccurate, too fast (GPS jumps) or go back in
 * time. Borderline fixes are kept but marked unsmoothed.
 *
 * Stateful, so not a singleton - recording and navigation each get their own.
 */
class LocationFilter @Inject constructor() {

    /** Fixes with accuracy worse than this are always rejected. */
    var maxAccuracyMetres: Float = 50f

    /** 30 m/s ≈ 108 km/h, upper bound for the marking vehicle. */
    var maxSpeedMs: Float = 30f

    private var lastAccepted: Location? = null

    /** Call before starting a new recording session. */
    fun reset() {
        lastAccepted = null
    }

    /** Evaluate a fix. Persist it either way, [FilterResult.shouldUseForRoute] decides the polyline. */
    fun evaluate(location: Location): FilterResult {
        // 1. Accuracy check.
        if (location.accuracy > maxAccuracyMetres) {
            return FilterResult(
                location     = location,
                accepted     = false,
                rejectedFor  = RejectionReason.POOR_ACCURACY,
                shouldUseForRoute = false
            )
        }

        // 2. Timestamp regression check.
        val prev = lastAccepted
        if (prev != null && location.time < prev.time) {
            return FilterResult(
                location     = location,
                accepted     = false,
                rejectedFor  = RejectionReason.TIMESTAMP_REGRESSION,
                shouldUseForRoute = false
            )
        }

        // 3. Speed / position spike check.
        if (prev != null) {
            val elapsedSec = (location.time - prev.time) / 1_000.0
            if (elapsedSec > 0) {
                val distM = prev.distanceTo(location)
                val speedMs = distM / elapsedSec
                if (speedMs > maxSpeedMs) {
                    return FilterResult(
                        location     = location,
                        accepted     = false,
                        rejectedFor  = RejectionReason.POSITION_SPIKE,
                        shouldUseForRoute = false
                    )
                }
            }
        }

        lastAccepted = location
        return FilterResult(
            location    = location,
            accepted    = true,
            rejectedFor = null,
            shouldUseForRoute = true
        )
    }

    /** Filter a batch (eg GPX import), state is reset before and after. */
    fun filterBatch(locations: List<Location>): List<FilterResult> {
        reset()
        val results = locations.map { evaluate(it) }
        reset()
        return results
    }

    data class FilterResult(
        val location: Location,
        val accepted: Boolean,
        val rejectedFor: RejectionReason?,
        /** If false, the point is persisted as raw but excluded from the smoothed polyline. */
        val shouldUseForRoute: Boolean
    )

    enum class RejectionReason {
        POOR_ACCURACY,
        POSITION_SPIKE,
        TIMESTAMP_REGRESSION
    }

    companion object {
        /** Convert a [FilterResult] to a [RoutePoint] for DB persistence. */
        fun FilterResult.toRoutePoint(index: Int): RoutePoint = RoutePoint(
            lat            = location.latitude,
            lon            = location.longitude,
            altMetres      = if (location.hasAltitude()) location.altitude else null,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy else null,
            timestampMs    = location.time,
            isSmoothed     = shouldUseForRoute
        )
    }
}
