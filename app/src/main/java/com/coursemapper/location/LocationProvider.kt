package com.coursemapper.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Cold [Flow] of high accuracy location updates. Needs fine location permission. */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedClient: FusedLocationProviderClient
) {
    companion object {
        private const val INTERVAL_MS         = 2_000L   // 2 s target
        private const val MIN_UPDATE_INTERVAL = 1_000L   // 1 s fastest
        private const val NAVIGATION_INTERVAL_MS = 1_000L
    }

    enum class Cadence { DEFAULT, NAVIGATION }

    @SuppressLint("MissingPermission")
    fun locationUpdates(cadence: Cadence = Cadence.DEFAULT): Flow<android.location.Location> = callbackFlow {
        val intervalMs = when (cadence) {
            Cadence.DEFAULT -> INTERVAL_MS
            Cadence.NAVIGATION -> NAVIGATION_INTERVAL_MS
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(
                if (cadence == Cadence.NAVIGATION) NAVIGATION_INTERVAL_MS else MIN_UPDATE_INTERVAL
            )
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations
                    .sortedBy { it.elapsedRealtimeNanos }
                    .forEach { trySend(it) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, context.mainLooper)

        awaitClose { fusedClient.removeLocationUpdates(callback) }
    }
}
