package com.coursemapper.ui.navigate

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
import com.coursemapper.ui.components.OfflineStatusRow
import com.coursemapper.ui.components.GpsStatusIndicator
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.offline.formatBytes
import com.coursemapper.ui.record.GateState
import kotlinx.coroutines.delay

/** Delay before auto-advancing on green. Long enough to hit Wait with gloves on. */
private const val AUTO_ADVANCE_DELAY_MS = 3_000L

/**
 * GPS readiness gate before navigation, stricter than recording (≤ 15 s,
 * ≤ 30 m for green). Wait, refresh or continue anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationGateScreen(
    onBack: () -> Unit,
    onProceed: () -> Unit,
    onPackingList: () -> Unit = {},
    viewModel: NavigationGateViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.onPermissionGranted() }

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

    // the decision is NavigationGateUiState.canAutoAdvance, the clock is here.
    // Restarts when it stops being green, Wait cancels it for good.
    LaunchedEffect(state.canAutoAdvance) {
        if (!state.canAutoAdvance) return@LaunchedEffect
        delay(AUTO_ADVANCE_DELAY_MS)
        onProceed()
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = state.runName?.let { "Ready to mark — $it" } ?: "Checking GPS",
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
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        navGateDescription(state.gateState),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Navigation requires a fresher, more accurate fix than recording.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OfflineStatusRow(
                status          = state.offlineStatus,
                onAction        = viewModel::requestOfflineDownload,
                checking        = state.offlineChecking,
                progress        = state.offlineProgress,
                failure         = state.offlineFailure,
                estimatedSize   = state.offlineEstimatedBytes?.let(::formatBytes),
                showProgressBar = true
            )

            OutlinedButton(onClick = onPackingList, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Inventory2, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Packing list (${state.stopCount} stops)")
            }

            when (state.gateState) {
                is GateState.Green -> {
                    Button(onClick = onProceed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.canAutoAdvance) "Starting…" else "Start placement run")
                    }
                    if (state.canAutoAdvance) {
                        OutlinedButton(
                            onClick  = viewModel::cancelAutoAdvance,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Wait")
                        }
                    }
                }

                is GateState.Yellow -> {
                    Button(onClick = onProceed, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Continue Anyway")
                    }
                    Text(
                        "Signal usually improves within a minute in open sky — this screen updates automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        "Location services must be enabled to navigate.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                GateState.NoPermission -> {
                    Text(
                        "Location permission is required to navigate.",
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

            // Open-sky hint card.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.WbSunny,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        "For the best accuracy, stand in an open area away from buildings and tree cover. " +
                        "Keeping Wi-Fi or mobile data on also speeds up GPS lock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

private fun navGateDescription(state: GateState): String = when (state) {
    is GateState.Green      -> "GPS is accurate and fresh. Ready to navigate."
    is GateState.Yellow     -> "Signal is marginal (±${state.accuracyMetres.toInt()} m). You can continue, but accuracy may be lower."
    is GateState.Initializing -> "Acquiring GPS lock — this usually takes under 30 seconds in open sky."
    GateState.Red           -> "No usable GPS fix. Move to open sky and wait."
    GateState.NoPermission  -> "Grant location permission to use navigation."
    GateState.SettingsRequired -> "Enable high-accuracy location in settings."
}

