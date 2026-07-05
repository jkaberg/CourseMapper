package com.coursemapper.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.domain.model.RaceDistance
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapActiveTarget
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.MapStopPin
import com.coursemapper.map.MapStopState
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Adjust start, finish and direction. Recording in grey underneath, course in
 * blue on top. Tap to place, nudge buttons to fine-tune - dragging would hide
 * the point under your finger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseGeometryEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: CourseGeometryEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()
    val hasFitCamera = remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    LaunchedEffect(state.basePath, state.editedPath, state.tapMode, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        MapRenderCoordinator.apply(
            mapState.map,
            MapScene(
                // The recording underneath, so the trimmed-off stretches stay
                // visible - an organiser cannot judge a trim they cannot see.
                additionalPolylines = listOf(
                    MapPolylineOverlay(state.basePath, MapPalette.MARKER_DONE, widthDp = 3f),
                    MapPolylineOverlay(state.editedPath, MapPalette.ROUTE, widthDp = 5f)
                ),
                placementStops = buildList {
                    state.startPoint?.let {
                        add(MapStopPin(it.first, it.second, label = "Start", state = MapStopState.DONE))
                    }
                    state.finishPoint?.let {
                        add(MapStopPin(it.first, it.second, label = "Finish", state = MapStopState.PENDING))
                    }
                },
                activeTarget = when (state.tapMode) {
                    GeometryTapMode.SET_START -> state.startPoint?.let {
                        MapActiveTarget(it.first, it.second, "Tap the map to move the start")
                    }
                    GeometryTapMode.SET_FINISH -> state.finishPoint?.let {
                        MapActiveTarget(it.first, it.second, "Tap the map to move the finish")
                    }
                    GeometryTapMode.NONE -> null
                }
            )
        )
        if (!hasFitCamera.value && state.basePath.isNotEmpty()) {
            MapOverlayManager.fitCamera(mapState.map, state.basePath)
            hasFitCamera.value = true
        }
    }

    if (state.confirmSave) {
        CmConfirmDialog(
            title        = "A placement run is under way",
            confirmLabel = "Change anyway",
            onConfirm    = viewModel::save,
            onDismiss    = viewModel::cancelSave
        ) {
            Text(
                "This course's stops are already being visited in a run. Changing where " +
                    "the course starts moves every marker after it, and the run's stops " +
                    "will be rebuilt — the progress recorded so far will not match them."
            )
        }
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = state.courseName.ifBlank { "Adjust course" },
                onBack  = onBack,
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = viewModel::requestSave, enabled = state.hasChanges) {
                            Text("Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            CourseMapView(
                modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                state = mapState,
                onMapClick = { lat, lon -> viewModel.onMapTap(lat, lon) }
            )

            MeasurementsCard(state)

            EndpointControls(
                label = "Start",
                icon = Icons.Default.PlayArrow,
                isActive = state.tapMode == GeometryTapMode.SET_START,
                onPickOnMap = { viewModel.setTapMode(GeometryTapMode.SET_START) },
                onNudge = viewModel::nudgeStart
            )
            EndpointControls(
                label = "Finish",
                icon = Icons.Default.Flag,
                isActive = state.tapMode == GeometryTapMode.SET_FINISH,
                onPickOnMap = { viewModel.setTapMode(GeometryTapMode.SET_FINISH) },
                onNudge = viewModel::nudgeFinish
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text("Run the course backwards") },
                supportingContent = {
                    Text("Swaps the start and the finish, keeping the same ground.")
                },
                leadingContent = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                trailingContent = {
                    Switch(checked = state.edit.reversed, onCheckedChange = { viewModel.toggleReversed() })
                }
            )

            GapRow(state, onCloseLoop = viewModel::toggleCloseLoop)

            if (state.canTrimToDistance) {
                TrimToRaceDistanceRow(
                    currentMetres = state.totalMetres,
                    onTrim = viewModel::trimToDistance
                )
            }

            TextButton(
                onClick = viewModel::reset,
                enabled = !state.edit.isIdentity,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Use the recording exactly as it is")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Lap length, composed total, and how far that misses the course's target. */
@Composable
private fun MeasurementsCard(state: CourseGeometryUiState) {
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()

    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            MeasurementRow("One lap", formatter.formatCompact(state.lapMetres, unit))
            MeasurementRow("Whole course", formatter.formatCompact(state.totalMetres, unit))

            val delta = state.targetDeltaMetres
            if (delta != null) {
                val rounded = delta.roundToInt()
                MeasurementRow(
                    label = "Against target",
                    value = when {
                        rounded == 0 -> "exact"
                        rounded > 0 -> "$rounded m over"
                        else -> "${-rounded} m under"
                    },
                    emphasis = abs(rounded) > 5
                )
            }
        }
    }
}

@Composable
private fun MeasurementRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EndpointControls(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onPickOnMap: () -> Unit,
    onNudge: (Double) -> Unit
) {
    val step = CourseGeometryEditorViewModel.NUDGE_METRES
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = isActive,
                onClick = onPickOnMap,
                label = { Text(if (isActive) "Tap the map" else "Move") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onNudge(-step) }, modifier = Modifier.weight(1f)) {
                Text("− ${step.roundToInt()} m")
            }
            OutlinedButton(onClick = { onNudge(step) }, modifier = Modifier.weight(1f)) {
                Text("+ ${step.roundToInt()} m")
            }
        }
    }
}

/** Start to finish gap with a one-tap fix, the usual reason a course measures wrong. */
@Composable
private fun GapRow(state: CourseGeometryUiState, onCloseLoop: () -> Unit) {
    val gap = state.endpointGapMetres.roundToInt()
    ListItem(
        headlineContent = {
            Text(
                if (state.isLoop) "Start and finish meet (within $gap m)"
                else "Start and finish are $gap m apart"
            )
        },
        supportingContent = {
            Text(
                if (state.isLoop) {
                    "Close enough to treat as a lap."
                } else {
                    "If the course really does cover that ground, add it as a straight leg " +
                        "back to the start. If it is a point-to-point course, leave this off."
                }
            )
        },
        trailingContent = {
            Switch(checked = state.edit.closeLoop, onCheckedChange = { onCloseLoop() })
        }
    )
}

/** Trim to a standard race distance in one tap, the finish moves to where it lands. */
@Composable
private fun TrimToRaceDistanceRow(currentMetres: Double, onTrim: (Double) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Make it measure exactly", style = MaterialTheme.typography.titleSmall)
        Text(
            "Moves the finish until the whole course is that distance.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaceDistance.entries.forEach { distance ->
                val isExact = abs(currentMetres - distance.metres) < 1.0
                AssistChip(
                    onClick = { onTrim(distance.metres) },
                    enabled = !isExact && distance.metres < currentMetres,
                    label = { Text(distance.label) }
                )
            }
        }
    }
}
