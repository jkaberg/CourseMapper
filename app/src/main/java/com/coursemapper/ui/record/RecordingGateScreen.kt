package com.coursemapper.ui.record

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.components.GpsStatusIndicator
import com.coursemapper.ui.designsystem.CmTopBar

/** GPS readiness gate before recording. Wait or continue anyway, settings problems get the OS dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingGateScreen(
    routeName: String,
    onBack: () -> Unit,
    onProceed: () -> Unit,
    viewModel: RecordingGateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.onPermissionGranted() }

    // Launch OS settings resolution when needed
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retrySettings()
    }

    LaunchedEffect(state.settingsException) {
        state.settingsException?.let { ex ->
            settingsLauncher.launch(
                IntentSenderRequest.Builder(ex.resolution.intentSender).build()
            )
        }
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Checking GPS",
                onBack  = onBack
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(24.dp))

            GpsStatusIndicator(state.gateState)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Route: $routeName", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        gateDescription(state.gateState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when (state.gateState) {
                is GateState.Green -> {
                    Button(onClick = onProceed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Recording")
                    }
                }

                is GateState.Yellow -> {
                    Button(onClick = onProceed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Recording")
                    }
                    OutlinedButton(onClick = { /* wait — updates come automatically */ }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.HourglassTop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Wait for better signal")
                    }
                }

                GateState.Red, GateState.Initializing -> {
                    OutlinedButton(
                        onClick  = onProceed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Proceed anyway (not recommended)")
                    }
                }

                GateState.SettingsRequired -> {
                    Text(
                        "Location services need to be enabled to record a route.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                GateState.NoPermission -> {
                    Text(
                        "Location permission is required to record a route.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick  = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Grant Location Permission")
                    }
                }
            }

            OpenSkyHint()
        }
    }
}

@Composable
private fun OpenSkyHint() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.WbSunny,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "For the best GPS accuracy, stand in an open area away from buildings and dense tree cover. Keep mobile data or Wi-Fi on to speed up satellite lock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun gateDescription(state: GateState): String = when (state) {
    is GateState.Green       -> "Signal strength is good. You're ready to start recording."
    is GateState.Yellow      -> "Signal is usable but weak. You can start now or wait for a better lock."
    is GateState.Initializing -> "Acquiring GPS satellite signal. This usually takes 10–30 seconds."
    GateState.Red            -> "GPS signal is too old or inaccurate. Move to a clear area and wait."
    GateState.NoPermission   -> "Grant location permission in app settings to continue."
    GateState.SettingsRequired -> "Tap the prompt to enable high-accuracy location mode."
}
