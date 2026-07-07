package com.coursemapper.ui.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.CmDisclosureIcon
import com.coursemapper.ui.designsystem.CmOverflowMenu
import com.coursemapper.ui.designsystem.CmMenuAction
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.components.OfflineStatusRow
import com.coursemapper.ui.designsystem.toComposeColor
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.offline.OfflineCoverage
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.MapStopPin
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/**
 * Group detail: routes and one-pass stops on one map, members, and run
 * launch. Run setup is still there for ad-hoc runs from Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onRunReady: (runId: Long) -> Unit,
    onCourseClicked: (courseId: Long) -> Unit,
    /** Run to pack for if there's one to resume, otherwise the ordering for a preview. */
    onPackingList: (runId: Long?, courseIds: List<Long>, ordering: RunOrdering) -> Unit =
        { _, _, _ -> },
    viewModel: GroupDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()
    var showEditRoutes by remember { mutableStateOf(false) }

    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(state.courseLines, state.preview, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        val preview = state.preview
        MapRenderCoordinator.apply(
            mapState.map,
            MapScene(
                additionalPolylines = state.courseLines.toMapOverlays(state.sharedCorridors),
                placementStops      = preview?.stopPositions?.mapIndexed { i, (lat, lon) ->
                    val (pinLat, pinLon) = state.courseLines.snapStopToDrawnLine(lat, lon)
                    MapStopPin(pinLat, pinLon, label = "", badge = "${preview.signCounts[i]}")
                }.orEmpty()
            )
        )
        if (!hasFitCamera.value && state.courseLines.isNotEmpty()) {
            MapOverlayManager.fitCamera(mapState.map, state.courseLines.flatMap { it.allPoints })
            hasFitCamera.value = true
        }
    }

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    LaunchedEffect(state.startedRunId) {
        val runId = state.startedRunId ?: return@LaunchedEffect
        viewModel.consumeStartedRun()
        onRunReady(runId)
    }

    if (showEditRoutes) {
        CombineCoursesSheet(
            groupId   = viewModel.groupId,
            onDismiss = { showEditRoutes = false },
            onSaved   = { showEditRoutes = false; viewModel.load() }
        )
    }

    if (state.confirmDelete) {
        CmConfirmDialog(
            title        = "Delete combined course?",
            icon         = Icons.Default.Delete,
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = viewModel::confirmDelete,
            onDismiss    = viewModel::cancelDelete
        ) {
            Text("Only the grouping is removed — the individual courses stay.")
            if (state.courses.isNotEmpty()) {
                Text(
                    "Kept: " + state.courses.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = state.group?.name ?: "Combined course",
                onBack  = onBack,
                actions = {
                    CmOverflowMenu(
                        listOf(
                            CmMenuAction("Edit routes", Icons.Default.Layers) {
                                showEditRoutes = true
                            },
                            CmMenuAction(
                                "Delete combined course",
                                Icons.Default.Delete,
                                viewModel::requestDelete
                            )
                        )
                    )
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }
        val group = state.group
        if (group == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Combined course not found.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                CourseMapView(
                    modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                    state    = mapState
                )
                state.preview?.let { preview ->
                    Text(
                        "${preview.stopCount} shared stops · ${preview.signCount} signs in one pass",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            item {
                RunLaunchSection(
                    state           = state,
                    onStart         = viewModel::startRun,
                    onToggleOptions = viewModel::toggleOptions,
                    onDownloadOfflineMap = viewModel::downloadOfflineMap,
                    onOrdering      = viewModel::selectOrdering,
                    onTravelProfile = viewModel::selectTravelProfile,
                    formatDistance  = viewModel::formatDistance
                )
            }

            // ── Packing list - loaded in the workshop, not in the car park ─────
            // Available before any run exists: that is when the rack is filled.
            if ((state.preview?.stopCount ?: 0) > 0) {
                item {
                    OutlinedButton(
                        onClick  = {
                            // Resuming an outing means packing for the plan it
                            // is already half-way through, not for a fresh one.
                            onPackingList(state.resumableRunId, group.courseIds, state.ordering)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Default.Inventory2, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Packing list (${state.preview?.stopCount ?: 0} stops)")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(
                        "Routes in this combined course",
                        style = MaterialTheme.typography.titleSmall
                    )
                    TextButton(onClick = { showEditRoutes = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                }
            }
            items(state.courses, key = { it.id }) { course ->
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                val lineColor = state.courseLines
                    .firstOrNull { it.courseId == course.id }?.colorHex
                ListItem(
                    headlineContent = {
                        Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${formatter.formatCompact(course.displayDistanceMetres, unit)} · " +
                                "${course.markers.size} markers",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Surface(
                            modifier = Modifier.size(14.dp),
                            shape    = MaterialTheme.shapes.extraSmall,
                            color    = lineColor?.let {
                                it.toComposeColor()
                            } ?: MaterialTheme.colorScheme.outline
                        ) {}
                    },
                    modifier = Modifier.clickable { onCourseClicked(course.id) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
            }
        }
    }
}

/** Launch button with the plan in one line, Change expands ordering and travel mode inline. */
@Composable
private fun RunLaunchSection(
    state: GroupDetailUiState,
    onStart: () -> Unit,
    onToggleOptions: () -> Unit,
    onDownloadOfflineMap: () -> Unit,
    onOrdering: (RunOrdering) -> Unit,
    onTravelProfile: (TravelProfile) -> Unit,
    formatDistance: (Double) -> String
) {
    Column(
        modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Above the launch button, because this is the last place the map can
        // be arranged with a roof overhead.  A chip, not a dialog: the user came
        // here to start a run, not to be interrupted about storage.
        OfflineCoverageChip(
            coverage = state.offlineCoverage,
            onDownload = onDownloadOfflineMap
        )

        Button(
            onClick  = onStart,
            enabled  = state.canStart,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.isStartingRun) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(
                    if (state.runAction == GroupRunAction.RESUME) Icons.Default.PlayArrow
                    else Icons.Default.Navigation,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
            }
            // One verb for one journey, everywhere it appears.
            Text(
                if (state.runAction == GroupRunAction.RESUME) "Resume placement run"
                else "Start placement run"
            )
        }

        // The plan, in the organiser's own units.
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                buildString {
                    append(
                        if (state.ordering == RunOrdering.OPTIMIZED) "Shortest path"
                        else "Follow main route"
                    )
                    append(" · ")
                    append(state.travelProfile.label())
                    state.plannedMetres?.let { append(" · ≈${formatDistance(it)}") }
                },
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onToggleOptions) {
                Text("Change")
                CmDisclosureIcon(
                    state.optionsExpanded,
                    what = "run options",
                    modifier = Modifier.size(IconSize.md)
                )
            }
        }

        if (state.runAction == GroupRunAction.RESUME) {
            Text(
                "Picks up the run already under way. Its stop order was fixed when it " +
                    "was created; changing the ordering starts to matter again on the next run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (state.optionsExpanded) {
            Text("Visit order", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.ordering == RunOrdering.SPINE,
                    onClick  = { onOrdering(RunOrdering.SPINE) },
                    shape    = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Follow main route" +
                        (state.preview?.spineMetres?.let { " · ${formatDistance(it)}" } ?: ""))
                }
                SegmentedButton(
                    selected = state.ordering == RunOrdering.OPTIMIZED,
                    onClick  = { onOrdering(RunOrdering.OPTIMIZED) },
                    shape    = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text("Shortest" +
                        (state.preview?.optimizedMetres?.let { " · ${formatDistance(it)}" } ?: ""))
                }
            }

            Text("Travel mode", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TravelProfile.entries.forEachIndexed { index, profile ->
                    SegmentedButton(
                        selected = state.travelProfile == profile,
                        onClick  = { onTravelProfile(profile) },
                        shape    = SegmentedButtonDefaults.itemShape(
                            index = index, count = TravelProfile.entries.size
                        )
                    ) {
                        Text(profile.label())
                    }
                }
            }
        }

        if (state.courses.none { it.markers.isNotEmpty() }) {
            Text(
                "None of these routes has markers yet, so there is nothing to place.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun TravelProfile.label(): String = when (this) {
    TravelProfile.CAR  -> "Car / ATV"
    TravelProfile.BIKE -> "Bike"
    TravelProfile.FOOT -> "On foot"
}

/**
 * Offline readiness above the launch button, hidden when ready. Uses
 * [OfflineStatusRow] like the gate.
 */
@Composable
private fun OfflineCoverageChip(
    coverage: OfflineCoverage?,
    onDownload: () -> Unit
) {
    if (coverage == null || coverage.status == OfflinePackStatus.READY) return

    OfflineStatusRow(
        status   = coverage.status,
        onAction = onDownload,
        progress = coverage.progress?.fraction
    )
}
