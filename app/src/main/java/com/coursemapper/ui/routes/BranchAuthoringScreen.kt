package com.coursemapper.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/**
 * Attach an approved route to the network as a branch, optionally with split
 * and rejoin pins on the trunk. Record the branch first, then attach it here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchAuthoringScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: BranchAuthoringViewModel = hiltViewModel()
) {
    val state    by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()
    val hasFitCamera = remember { mutableStateOf(false) }

    LaunchedEffect(state.trunkPoints, state.selectedRoutePoints, state.splitJunction, state.rejoinJunction, state.slicePreviewPoints, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        val trunkPts = state.trunkPoints.map { it.lat to it.lon }
        val additionalPolylines = buildList {
            // Selected branch shown as a distinct-colour polyline.
            if (state.selectedRoutePoints.isNotEmpty()) {
                add(MapPolylineOverlay(
                    points   = state.selectedRoutePoints.map { it.lat to it.lon },
                    colorHex = MapPalette.BRANCH,
                    widthDp  = 4f
                ))
            }
            // Bold amber overlay for the trunk slice between the two junction pins.
            if (state.slicePreviewPoints.isNotEmpty()) {
                add(MapPolylineOverlay(
                    points   = state.slicePreviewPoints.map { it.lat to it.lon },
                    colorHex = MapPalette.BRANCH_HIGHLIGHT,
                    widthDp  = 7f
                ))
            }
        }
        // Junction pins rendered via dedicated junctionPins layer (amber circles).
        val junctionPins = buildList {
            state.splitJunction?.let { add(Triple(it.lat, it.lon, "Split")) }
            state.rejoinJunction?.let { add(Triple(it.lat, it.lon, "Rejoin")) }
        }
        MapRenderCoordinator.apply(
            mapState.map,
            MapScene(
                routePoints         = trunkPts,
                additionalPolylines = additionalPolylines,
                junctionPins        = junctionPins,
            )
        )
        // Fit camera to trunk on first load only - refitting on every pin tap
        // would fight the user's pan/zoom.
        if (trunkPts.isNotEmpty() && !hasFitCamera.value) {
            MapOverlayManager.fitCamera(mapState.map, trunkPts)
            hasFitCamera.value = true
        }
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onSaved()
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Add Branch",
                onBack  = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Box {
                    CourseMapView(
                        modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                        state    = mapState,
                        onMapClick = { lat, lon -> viewModel.onMapTap(lat, lon) }
                    )
                    // Tap mode hint (replaced by snap-miss hint when applicable)
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                        color  = if (state.snapMissHint != null) MaterialTheme.colorScheme.errorContainer
                                 else MaterialTheme.colorScheme.secondaryContainer,
                        shape  = MaterialTheme.shapes.small
                    ) {
                        val hint = state.snapMissHint ?: when {
                            state.splitJunction == null  -> "Tap trunk to set split point"
                            state.rejoinJunction == null -> "Tap trunk to set rejoin point"
                            state.sliceDistanceHint != null -> "Both pins set · ${state.sliceDistanceHint}"
                            else                         -> "Both junction pins set"
                        }
                        Text(
                            hint,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.snapMissHint != null) MaterialTheme.colorScheme.onErrorContainer
                                    else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    JunctionChip(
                        label = "Split",
                        set   = state.splitJunction != null,
                        modifier = Modifier.weight(1f)
                    )
                    JunctionChip(
                        label = "Rejoin",
                        set   = state.rejoinJunction != null,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.splitJunction != null || state.rejoinJunction != null) {
                        OutlinedButton(
                            onClick = viewModel::clearPins,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }

            item {
                Text(
                    "Select Branch Route",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (state.availableRoutes.isEmpty()) {
                    Text(
                        "No other approved routes available. Record a branch route first, then return here to attach it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            items(state.availableRoutes, key = { it.id }) { route ->
                val selected = route.id == state.selectedRouteId
                ListItem(
                    headlineContent = { Text(route.name) },
                    supportingContent = {
                        val formatter = LocalDistanceFormatter.current
                        val unit by rememberDistanceUnit()
                        Text(formatter.formatCompact(route.distanceMetres, unit))
                    },
                    leadingContent = {
                        RadioButton(
                            selected = selected,
                            onClick  = { viewModel.selectRoute(route.id) }
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }

            if (state.errorMessage != null) {
                item {
                    Text(
                        state.errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = viewModel::saveBranch,
                        enabled  = state.canSave,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save Branch")
                    }
                    if (state.splitJunction == null && state.rejoinJunction == null) {
                        Text(
                            "Tip: junction pins are optional. You can save without them and edit later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun JunctionChip(label: String, set: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color  = if (set) MaterialTheme.colorScheme.primaryContainer
                 else MaterialTheme.colorScheme.surfaceVariant,
        shape  = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                if (set) Icons.Default.PinDrop else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (set) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
