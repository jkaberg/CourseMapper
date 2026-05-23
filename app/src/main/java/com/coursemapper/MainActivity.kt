package com.coursemapper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.CompositionLocalProvider
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.navigation.CourseMapperNavHost
import com.coursemapper.ui.theme.CourseMapperTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Single activity. Asks for location and (API 33+) notification permission on start. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var prefs: UserPreferencesRepository
    @Inject lateinit var distanceFormatter: DistanceFormatter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CourseMapperTheme(darkTheme = isSystemInDarkTheme()) {

                // Request all dangerous permissions up-front so users are not
                // surprised deep inside the recording or navigation flow.
                val permissionsLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* Results are checked contextually by each gate screen. */ }

                LaunchedEffect(Unit) {
                    val needed = buildList {
                        // Location - always required (dangerous on all API levels we support)
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                        // Notification - only needs runtime request on API 33+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    if (needed.isNotEmpty()) {
                        permissionsLauncher.launch(needed.toTypedArray())
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CompositionLocalProvider(LocalDistanceFormatter provides distanceFormatter) {
                        // wait for the real value, an optimistic default would
                        // skip onboarding on first launch
                        val onboardingComplete by prefs.isOnboardingComplete
                            .collectAsState(initial = null)
                        when (onboardingComplete) {
                            null -> Unit  // one blank frame while the flag loads
                            else -> CourseMapperNavHost(
                                startOnboarding = onboardingComplete == false
                            )
                        }
                    }
                }
            }
        }
    }
}

