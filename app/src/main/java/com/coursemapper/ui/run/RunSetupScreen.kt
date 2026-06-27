package com.coursemapper.ui.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.ScreenGutter
import com.coursemapper.ui.designsystem.CmSectionHeader
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.MapStopPin
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/** Run setup: pick courses, compare the two orderings with drive estimates, start. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSetupScreen(
    onBack: () -> Unit,
    onRunCreated: (runId: Long) -> Unit,
    viewModel: RunSetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()

    // Preview map: selected course lines, clustered stops, planned path (dashed).
    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(state.preview, state.courseLines, state.ordering, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        val preview = state.preview
        val order = when (state.ordering) {
            RunOrdering.SPINE     -> preview?.spineOrder
            RunOrdering.OPTIMIZED -> preview?.optimizedOrder
        }
        // Snap once: the pins and the dashed path between them must agree.
        val pins = preview?.stopPositions?.mapIndexed { i, (lat, lon) ->
            val (pinLat, pinLon) = state.courseLines.snapStopToDrawnLine(lat, lon)
            MapStopPin(pinLat, pinLon, label = "", badge = "${preview.signCounts[i]}")
        }.orEmpty()
        val plannedPath = if (order != null && order.size >= 2) {
            order.map { pins[it].lat to pins[it].lon }
        } else emptyList()

        MapRenderCoordinator.apply(
            mapState.map,
            MapScene(
                additionalPolylines = state.courseLines.toMapOverlays(state.sharedCorridors),
                placementStops      = pins,
                approachLine        = if (plannedPath.size >= 2) {
                    MapPolylineOverlay(plannedPath, MapPalette.APPROACH, 4f, dashed = true)
                } else null
            )
        )
        if (!hasFitCamera.value && state.courseLines.isNotEmpty()) {
            MapOverlayManager.fitCamera(
                mapState.map,
                state.courseLines.flatMap { it.allPoints }
            )
            hasFitCamera.value = true
        }
    }

    LaunchedEffect(state.createdRunId) {
        val id = state.createdRunId ?: return@LaunchedEffect
        viewModel.consumeCreatedRun()
        onRunCreated(id)
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "New Placement Run",
                onBack  = onBack
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Box {
                    CourseMapView(
                        modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                        state    = mapState
                    )
                    if (state.isPreviewLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        )
                    }
                }
                state.preview?.let { preview ->
                    Text(
                        "${preview.stopCount} stops · ${preview.signCount} signs — " +
                            "stops shared by several routes are visited once",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value         = state.runName,
                    onValueChange = viewModel::onRunNameChange,
                    label         = { Text("Run name") },
                    singleLine    = true,
                    modifier      = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }

            item {
                CmSectionHeader(
                    "Courses to mark in this pass",
                    Modifier.padding(horizontal = ScreenGutter.horizontal)
                )
            }
            if (state.availableCourses.isEmpty()) {
                item {
                    Text(
                        "No published courses with markers yet. Import routes or publish a course first.",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
            items(state.availableCourses, key = { it.id }) { course ->
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                ListItem(
                    headlineContent   = {
                        Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${formatter.formatCompact(course.displayDistanceMetres, unit)} · " +
                                "${course.markers.size} markers",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent    = {
                        Checkbox(
                            checked         = course.id in state.selectedCourseIds,
                            onCheckedChange = { viewModel.toggleCourse(course.id) }
                        )
                    },
                    modifier = Modifier.clickable { viewModel.toggleCourse(course.id) }
                )
            }

            if (state.selectedCourseIds.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("Travel mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Car keeps to drivable roads; bike and walk may use paths " +
                            "and walkways. Used to route you between stops.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = state.travelProfile == TravelProfile.CAR,
                                onClick  = { viewModel.selectTravelProfile(TravelProfile.CAR) },
                                label    = { Text("Car / ATV") },
                                leadingIcon = {
                                    Icon(Icons.Default.DirectionsCar, contentDescription = null,
                                        modifier = Modifier.size(16.dp))
                                }
                            )
                            FilterChip(
                                selected = state.travelProfile == TravelProfile.BIKE,
                                onClick  = { viewModel.selectTravelProfile(TravelProfile.BIKE) },
                                label    = { Text("Bike") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null,
                                        modifier = Modifier.size(16.dp))
                                }
                            )
                            FilterChip(
                                selected = state.travelProfile == TravelProfile.FOOT,
                                onClick  = { viewModel.selectTravelProfile(TravelProfile.FOOT) },
                                label    = { Text("Walk") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null,
                                        modifier = Modifier.size(16.dp))
                                }
                            )
                        }
                    }
                }
            }

            state.preview?.let { preview ->
                item {
                    Text(
                        "Visit order",
                        style    = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                item {
                    OrderingOption(
                        selected    = state.ordering == RunOrdering.SPINE,
                        title       = "Follow main route" +
                            (preview.spineCourseName?.let { " ($it)" } ?: ""),
                        description = "Predictable: ride the longest course start to finish, " +
                            "picking up the other routes' stops along the way. " +
                            "≈ ${viewModel.formatDistance(preview.spineMetres)} between stops.",
                        onClick     = { viewModel.selectOrdering(RunOrdering.SPINE) }
                    )
                }
                item {
                    OrderingOption(
                        selected    = state.ordering == RunOrdering.OPTIMIZED,
                        title       = "Shortest path",
                        description = "Optimized stop-to-stop order (straight-line estimate — " +
                            "the dashed path is a suggestion, not turn-by-turn). " +
                            "≈ ${viewModel.formatDistance(preview.optimizedMetres)} between stops.",
                        onClick     = { viewModel.selectOrdering(RunOrdering.OPTIMIZED) }
                    )
                }
            }

            item {
                Button(
                    onClick  = viewModel::createRun,
                    enabled  = state.selectedCourseIds.isNotEmpty() &&
                        state.preview != null && !state.isCreating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(52.dp)
                ) {
                    if (state.isCreating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Start placement run")
                }
            }
        }
    }
}

@Composable
private fun OrderingOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment     = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
