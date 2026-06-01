package com.coursemapper.ui.record

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/**
 * Recording screen: route on the map, time, distance, GPS and marker count.
 * Keeps the screen on, and goes to the workspace after Stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateToWorkspace: (
        routeId: Long, presetId: Long, lapCount: Int, targetDistanceCm: Long,
        includeStart: Boolean, includeFinish: Boolean
    ) -> Unit,
    onDiscarded: () -> Unit = {},
    viewModel: RecordingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveCoords by viewModel.liveCoords.collectAsStateWithLifecycle()
    val mapState = rememberMapState()

    // Draw the growing polyline on the map as GPS points arrive.
    LaunchedEffect(liveCoords, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        MapRenderCoordinator.apply(mapState.map, MapScene(routePoints = liveCoords))
        // On the very first fix, centre the camera on the GPS position.
        if (liveCoords.size == 1) {
            MapOverlayManager.fitCamera(mapState.map, liveCoords)
        }
    }

    // Keep screen awake while recording
    val view = LocalView.current
    DisposableEffect(state.isRecording) {
        val window = (view.context as? android.app.Activity)?.window
        if (state.isRecording) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Navigate to workspace when recording stops
    LaunchedEffect(state.isStopped) {
        if (state.isStopped && state.routeId > 0) {
            onNavigateToWorkspace(
                state.routeId,
                viewModel.presetId,
                viewModel.lapCount,
                viewModel.targetDistanceCm,
                viewModel.includeStart,
                viewModel.includeFinish
            )
        }
    }

    // Navigate home when the recording is discarded.
    LaunchedEffect(state.isDiscarded) {
        if (state.isDiscarded) onDiscarded()
    }

    var showStopDialog by remember { mutableStateOf(false) }

    // System back must not silently abandon a live recording (the foreground
    // notification would keep claiming one is active) - route it to the same
    // stop dialog, which also offers Discard.
    BackHandler(enabled = state.isRecording) {
        showStopDialog = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map fills the full screen
        CourseMapView(
            modifier = Modifier.fillMaxSize(),
            state    = mapState
        )

        // Top bar overlaid on map
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
        ) {
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text  = if (state.isPaused) "⏸ Paused" else "● Recording",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.isPaused)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    text  = state.gpsQualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Bottom stats + controls bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats row
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                StatItem(label = "Time",     value = formatElapsed(state.elapsedMs))
                StatItem(label = "Distance", value = formatter.formatCompact(state.distanceMetres, unit))
                StatItem(label = "Points",   value = state.pointCount.toString())
                StatItem(label = "Markers",  value = state.autoMarkerCount.toString())
            }

            // Action buttons
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick  = viewModel::togglePause,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.isPaused) "Resume" else "Pause")
                }

                Button(
                    onClick  = { showStopDialog = true },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        // Without this the label stays onPrimary on an error ground.
                        contentColor   = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title            = { Text("Stop recording?") },
            text             = {
                Text(
                    "Stop & Continue saves the route for review. " +
                    "Discard permanently throws away this recording."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showStopDialog = false
                    viewModel.stopRecording()
                }) { Text("Stop & Continue") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            showStopDialog = false
                            viewModel.discardRecording()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("Discard") }
                    TextButton(onClick = { showStopDialog = false }) { Text("Keep Recording") }
                }
            }
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text  = value,
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1_000
    val h = totalSec / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
