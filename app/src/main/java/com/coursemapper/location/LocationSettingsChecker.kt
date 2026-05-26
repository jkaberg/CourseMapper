package com.coursemapper.location

import android.app.Activity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Checks location settings against the high accuracy request. On a
 * [ResolvableApiException] the caller starts the resolution from an [Activity].
 */
@Singleton
class LocationSettingsChecker @Inject constructor(
    private val settingsClient: SettingsClient
) {
    sealed interface Result {
        data object Satisfied : Result
        data class Resolvable(val exception: ResolvableApiException) : Result
        data class Failed(val cause: Exception) : Result
    }

    suspend fun check(): Result {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 2_000L
        ).build()

        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .build()

        return suspendCancellableCoroutine { continuation ->
            settingsClient.checkLocationSettings(settingsRequest)
                .addOnSuccessListener { continuation.resume(Result.Satisfied) }
                .addOnFailureListener { e ->
                    val result = when (e) {
                        is ResolvableApiException -> Result.Resolvable(e)
                        else                      -> Result.Failed(e as Exception)
                    }
                    continuation.resume(result)
                }
        }
    }
}
