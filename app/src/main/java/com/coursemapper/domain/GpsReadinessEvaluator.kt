package com.coursemapper.domain

import android.location.Location
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS fix to [GpsReadiness] for recording and navigation.
 *
 * Recording: green ≤ 30 s and ≤ 20 m, yellow ≤ 60 s and ≤ 50 m, otherwise red.
 * Navigation: green ≤ 15 s and ≤ 30 m, yellow ≤ 30 s and ≤ 50 m, otherwise red.
 */
@Singleton
class GpsReadinessEvaluator @Inject constructor() {

    enum class Context { RECORDING, NAVIGATION }

    sealed interface GpsReadiness {
        data class Green(val accuracyMetres: Float, val ageSeconds: Long) : GpsReadiness
        data class Yellow(val accuracyMetres: Float, val ageSeconds: Long) : GpsReadiness
        data object Red : GpsReadiness
    }

    fun evaluate(location: Location?, context: Context): GpsReadiness {
        if (location == null) return GpsReadiness.Red

        val ageSeconds = (System.currentTimeMillis() - location.time) / 1_000L
        val accuracy   = location.accuracy  // metres

        return when (context) {
            Context.RECORDING -> when {
                ageSeconds <= 30 && accuracy <= 20f -> GpsReadiness.Green(accuracy, ageSeconds)
                ageSeconds <= 60 && accuracy <= 50f -> GpsReadiness.Yellow(accuracy, ageSeconds)
                else                                -> GpsReadiness.Red
            }
            Context.NAVIGATION -> when {
                ageSeconds <= 15 && accuracy <= 30f -> GpsReadiness.Green(accuracy, ageSeconds)
                ageSeconds <= 30 && accuracy <= 50f -> GpsReadiness.Yellow(accuracy, ageSeconds)
                else                                -> GpsReadiness.Red
            }
        }
    }
}
