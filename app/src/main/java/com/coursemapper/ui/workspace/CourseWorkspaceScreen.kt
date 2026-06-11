package com.coursemapper.ui.workspace

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.CmDisclosureIcon
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.domain.model.CourseStatus
import com.coursemapper.ui.components.CourseStatusBadge
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.domain.model.*
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.MapStopPin
import com.coursemapper.map.rememberMapState
import com.coursemapper.ui.components.EndpointMarkerToggles
import com.coursemapper.ui.components.TargetDistanceChips
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.offline.OfflineDownloadPrompt
import com.coursemapper.ui.format.rememberDistanceUnit

/**
 * Course workspace for recorded drafts, imported drafts and published courses.
 * Map on top, then essentials, quality warnings and markers. Drafts get the
 * publish area, published courses the facts and run actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseWorkspaceScreen(
    onPublished: (courseId: Long) -> Unit,
    onNetworkCreated: (networkId: Long) -> Unit,
    onRerecord: () -> Unit,
    onStartRun: (runId: Long) -> Unit,
    onViewMarkers: (courseId: Long) -> Unit,
    /** [runId] is the course's run once it has progress, pack for that order. */
    onPackingList: (courseId: Long, runId: Long?) -> Unit = { _, _ -> },
    onAdjustGeometry: (courseId: Long) -> Unit,
    onBackToNetwork: (networkId: Long) -> Unit = {},
    onBack: () -> Unit,
    viewModel: CourseWorkspaceViewModel = hiltViewModel()
) {
    val state   = viewModel.uiState.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val mapState = rememberMapState()

    // viewing and editing are separate, drafts open straight into editing
    val isDraftCourse = state.course?.isDraft == true
    var isEditing by remember(state.course?.id, isDraftCourse) {
        mutableStateOf(isDraftCourse)
    }

    // ── Overlay scene - re-applies whenever route, markers, stops, or style changes.
    // fitCamera is intentionally excluded: the initial fit lives in its own effect
    // so unrelated data changes (e.g. stop edits) do not reset the user’s view.
    val route = state.baseRoute
    val course = state.course
    LaunchedEffect(state.courseGeometry, course?.markers, state.stops, mapState.isReady) {
        if (!mapState.isReady || state.courseGeometry.isEmpty()) return@LaunchedEffect
        val pts = state.courseGeometry.map { it.lat to it.lon }
        val scene = MapScene(
            routePoints    = pts,
            distanceMarkers = course?.markers.orEmpty().map { m ->
                Triple(m.lat, m.lon, m.label.ifBlank { m.sequenceIndex.toString() })
            },
            placementStops = state.stops.map { stop ->
                val label = stop.markers.joinToString(" · ") { it.label.ifBlank { it.type.name } }
                MapStopPin(
                    lat   = stop.lat,
                    lon   = stop.lon,
                    label = label,
                    badge = stop.markers.size.takeIf { it > 1 }?.toString()
                )
            },
        )
        MapRenderCoordinator.apply(mapState.map, scene)
    }

    // ── One-shot initial camera fit - fires once after the first route load.
    // A style reload (isReady false→true) will re-check, but hasFitCamera
    // prevents the camera from resetting to fit-bounds on style changes.
    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(mapState.isReady, state.courseGeometry.isNotEmpty()) {
        if (!mapState.isReady || state.courseGeometry.isEmpty() || hasFitCamera.value) return@LaunchedEffect
        val pts = state.courseGeometry.map { it.lat to it.lon }
        MapOverlayManager.fitCamera(mapState.map, pts)
        hasFitCamera.value = true
    }

    // Share intent.
    LaunchedEffect(state.shareIntent, state.shareConsumed) {
        if (!state.shareConsumed && state.shareIntent != null) {
            context.startActivity(Intent.createChooser(state.shareIntent, "Share course"))
            viewModel.consumeShareIntent()
        }
    }

    // One-shot navigation.
    LaunchedEffect(state.action) {
        when (val a = state.action) {
            is WorkspaceAction.NavigateHome      -> onPublished(a.courseId)
            is WorkspaceAction.NavigateToNetwork -> onNetworkCreated(a.networkId)
            is WorkspaceAction.NavigateToRunGate -> onStartRun(a.runId)
            is WorkspaceAction.Rerecord          -> onRerecord()
            WorkspaceAction.Idle                 -> Unit
        }
    }

    // Recomposing a course whose placement run is already under way.
    if (state.pendingRecomposeConfirm) {
        CmConfirmDialog(
            title        = "A placement run is under way",
            icon         = Icons.Default.Warning,
            confirmLabel = "Change anyway",
            onConfirm    = viewModel::confirmRecomposeAndSave,
            onDismiss    = viewModel::dismissRecomposeConfirm
        ) {
            Text(
                "This course is being placed right now, and that run keeps its own copy " +
                "of the stops it was started with. Changing the laps, target distance, " +
                "measured length, or marker preset rebuilds this course's markers and " +
                "stops, but the run in progress will carry on with the old ones.\n\n" +
                "Finish or delete that run first if you want it to match."
            )
        }
    }

    // Post-publish offline map offer.  Shown before the network dialog so the
    // two never stack; the network question is about how the course is stored,
    // this one is about whether it will work in the field.
    state.offlinePrompt?.let { proposal ->
        OfflineDownloadPrompt(
            proposal = proposal,
            onDownload = viewModel::acceptOfflineDownload,
            onDecline = viewModel::declineOfflineDownload,
            onDismiss = viewModel::dismissOfflinePrompt
        )
    }

    // Post-publish "Create Network?" dialog.
    if (state.offlinePrompt == null) state.pendingNetwork?.let { pending ->
        CmConfirmDialog(
            title        = "Create route network?",
            icon         = Icons.Default.AccountTree,
            confirmLabel = "Create network",
            // Not "Cancel": declining here is a real choice about how the
            // course is stored, not an escape from a pending action.
            dismissLabel = "Keep standalone",
            onConfirm    = viewModel::createNetworkAndNavigate,
            onDismiss    = viewModel::skipNetworkCreation
        ) {
            Text(
                "A route network lets you record branch segments (Half Marathon, 10K) that " +
                "diverge from and rejoin this main route. You can always do this later."
            )
        }
    }

    Scaffold(
        topBar = {
            CmTopBar(
                onBack = onBack,
                actions = {
                    if (state.networkId != null) {
                        IconButton(onClick = { onBackToNetwork(state.networkId) }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Back to network")
                        }
                    }
                    if (!state.isLoading && state.course != null) {
                        IconButton(onClick = viewModel::shareRoute) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        // Drafts stay in edit mode: there is no "done editing"
                        // for something whose job is to be finished and published.
                        if (!isDraftCourse || !isEditing) {
                            if (isEditing) {
                                TextButton(onClick = { isEditing = false }) { Text("Done") }
                            } else {
                                IconButton(onClick = { isEditing = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit course")
                                }
                            }
                        }
                    }
                },
                title  = {
                    Column {
                        if (state.variantName != null) {
                            Text(state.variantName, style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text(state.course?.name ?: if (state.isCreatingDraft) "Creating…" else "Course Workspace")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading || state.isCreatingDraft) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    if (state.isCreatingDraft) {
                        Text("Composing course…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@Scaffold
        }

        val course = course ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Course not found.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        val isDraft = course.isDraft

        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                CourseMapView(
                    modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                    state    = mapState
                )
            }

            item {
                CourseFactsRow(state = state, isDraft = isDraft)
            }

            if (!isDraft) {
                item {
                    StartRunButton(state = state, onStartRun = viewModel::startRun)
                }
            }

            if (!isEditing) {
                item {
                    PlacementStopsSummary(
                        stops      = state.stops,
                        hasMarkers = course.markers.isNotEmpty()
                    )
                }
                item {
                    MarkersSummary(
                        count         = course.markers.size,
                        onViewMarkers = { onViewMarkers(course.id) }
                    )
                }
                // The rack is loaded in the workshop, before anyone stands in a
                // car park waiting for a fix.
                if (state.stops.isNotEmpty()) {
                    item {
                        OutlinedButton(
                            onClick  = { onPackingList(course.id, state.committedRunId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Inventory2, contentDescription = null,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Packing list (${state.stops.size} stops)")
                        }
                    }
                }
                return@LazyColumn
            }

            if (state.showRecordedLapPrompt && course.variantId == null) {
                item {
                    RecordedLapPromptCard(
                        state                  = state,
                        onLapCountChange       = viewModel::onLapCountChange,
                        onTargetDistanceChange = viewModel::onTargetDistanceChange,
                        onTargetPreset         = viewModel::onTargetDistancePreset,
                        onApply                = {
                            viewModel.saveChanges()
                            viewModel.dismissRecordedLapPrompt()
                        },
                        onDismiss              = viewModel::dismissRecordedLapPrompt
                    )
                }
            }

            item {
                EssentialsCard(
                    state        = state,
                    isDraft      = isDraft,
                    onNameChange = viewModel::onNameChange,
                    onNotesChange = viewModel::onNotesChange,
                    onLapCountChange = viewModel::onLapCountChange,
                    onTargetDistanceChange = viewModel::onTargetDistanceChange,
                    onTargetPreset = viewModel::onTargetDistancePreset,
                    onPresetChange = viewModel::onPresetChange,
                    onIncludeStartChange = viewModel::onIncludeStartChange,
                    onIncludeFinishChange = viewModel::onIncludeFinishChange,
                    onMeasuredLengthChange = viewModel::onMeasuredLengthChange,
                    onMeasuredPreset = viewModel::onMeasuredLengthPreset,
                    onSave       = viewModel::saveChanges
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("Adjust start & finish") },
                    supportingContent = {
                        Text(
                            "Move where this course begins and ends along the recording, or " +
                                "run it backwards. Markers are re-measured from the new start."
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.ContentCut, contentDescription = null)
                    },
                    modifier = Modifier.clickable { onAdjustGeometry(course.id) }
                )
            }

            if (state.warnings.isNotEmpty()) {
                item {
                    WarningsSummaryRow(
                        count        = state.warnings.size,
                        expanded     = state.showWarnings,
                        onToggle     = viewModel::toggleWarnings
                    )
                }
                if (state.showWarnings) {
                    items(state.warnings) { w -> WarningRow(w) }
                }
            }

            item {
                MarkersHeader(
                    count    = course.markers.size,
                    expanded = state.showMarkers,
                    onToggle = viewModel::toggleMarkers
                )
            }
            if (course.markers.isNotEmpty() && state.showMarkers) {
                items(course.markers, key = { it.id }) { marker ->
                    MarkerRow(marker = marker, onDelete = { viewModel.deleteMarker(marker.id) })
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
                item {
                    OutlinedButton(
                        onClick  = { onViewMarkers(course.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.GridOn, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View All Markers")
                    }
                }
            }

            if (isDraft) {
                item {
                    DraftActionsSection(
                        state       = state,
                        source      = state.baseRoute?.source,
                        onPublish   = viewModel::publish,
                        onRerecord  = viewModel::requestRerecord
                    )
                }
            }

            if (!isDraft) {
                item {
                    PlacementStopsSummary(
                        stops      = state.stops,
                        hasMarkers = course.markers.isNotEmpty()
                    )
                }
            }
        }
    }
}

/** Distance, laps, markers and readiness in one line. */
@Composable
private fun CourseFactsRow(state: CourseWorkspaceUiState, isDraft: Boolean) {
    val course = state.course ?: return
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            buildString {
                append(formatter.formatCompact(course.totalDistanceMetres, unit))
                append(" · ${course.lapCount} lap(s)")
                append(" · ${course.markers.size} markers")
                if (state.stops.isNotEmpty()) append(" · ${state.stops.size} stops")
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CourseStatusBadge(
                if (isDraft) CourseStatus.DRAFT else CourseStatus.PUBLISHED
            )
            val sourceLabel = when (state.baseRoute?.source) {
                "gpx_import" -> "GPX import"
                else         -> "Recorded"
            }
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (course.notes.isNotBlank()) {
            Text(
                course.notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The primary action, worded the one way it is worded everywhere else. */
@Composable
private fun StartRunButton(state: CourseWorkspaceUiState, onStartRun: () -> Unit) {
    val course = state.course ?: return
    Column {
        Button(
            onClick  = onStartRun,
            enabled  = course.markers.isNotEmpty() && !state.isStartingRun,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(56.dp)
        ) {
            if (state.isStartingRun) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text("Start placement run")
        }
        if (course.markers.isEmpty()) {
            Text(
                "A marker preset is required before a placement run can be started.",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
            )
        }
    }
}

/** Markers, collapsed to a count and a way to see them all. */
@Composable
private fun MarkersSummary(count: Int, onViewMarkers: () -> Unit) {
    ListItem(
        headlineContent   = { Text("Markers") },
        supportingContent = {
            Text(
                if (count == 0) "None — this course has no marker preset."
                else "$count signs, measured along the course.",
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent    = { Icon(Icons.Default.GridOn, contentDescription = null) },
        trailingContent   = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
        modifier          = Modifier.clickable(enabled = count > 0, onClick = onViewMarkers)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EssentialsCard(
    state: CourseWorkspaceUiState,
    isDraft: Boolean,
    onNameChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onLapCountChange: (Int) -> Unit,
    onTargetDistanceChange: (String) -> Unit,
    onTargetPreset: (Long) -> Unit,
    onPresetChange: (Long?) -> Unit,
    onIncludeStartChange: (Boolean) -> Unit,
    onIncludeFinishChange: (Boolean) -> Unit,
    onMeasuredLengthChange: (String) -> Unit,
    onMeasuredPreset: (Double) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Essentials", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            OutlinedTextField(
                value         = state.editName,
                onValueChange = onNameChange,
                label         = { Text("Course name *") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value         = state.editNotes,
                onValueChange = onNotesChange,
                label         = { Text("Notes (optional)") },
                minLines      = 2,
                maxLines      = 4,
                modifier      = Modifier.fillMaxWidth()
            )

            // Preset picker
            var presetMenuExpanded by remember { mutableStateOf(false) }
            val currentPreset = state.availablePresets.find { it.id == state.editPresetId }
            val presetLabel   = currentPreset?.name
                ?: if (state.editPresetId == null) "None" else "Current preset"

            ExposedDropdownMenuBox(
                expanded         = presetMenuExpanded,
                onExpandedChange = { presetMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value         = presetLabel,
                    onValueChange = {},
                    readOnly      = true,
                    label         = { Text("Marker preset") },
                    trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuExpanded) },
                    modifier      = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded         = presetMenuExpanded,
                    onDismissRequest = { presetMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text    = { Text("None") },
                        onClick = { onPresetChange(null); presetMenuExpanded = false }
                    )
                    state.availablePresets.forEach { preset ->
                        DropdownMenuItem(
                            text    = { Text(preset.name) },
                            onClick = { onPresetChange(preset.id); presetMenuExpanded = false }
                        )
                    }
                }
            }

            // Endpoints apply to every course, variant-backed or not: a compiled
            // variant still has a start and a finish.
            EndpointMarkerToggles(
                includeStart   = state.editIncludeStart,
                includeFinish  = state.editIncludeFinish,
                onStartChange  = onIncludeStartChange,
                onFinishChange = onIncludeFinishChange
            )

            val isVariantCourse = state.course?.variantId != null
            if (isVariantCourse) {
                // Variant-backed courses take their geometry from the compiled
                // distance; laps and target distance do not apply.
                Text(
                    "Geometry comes from the ${state.variantName ?: "selected"} distance — " +
                        "laps and target distance don't apply to this course.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LapCompositionControls(
                    state                  = state,
                    onLapCountChange       = onLapCountChange,
                    onTargetDistanceChange = onTargetDistanceChange,
                    onTargetPreset         = onTargetPreset
                )
            }

            // Composition preview
            if (!isVariantCourse && state.compositionPreview.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            state.compositionPreview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            // measured length lives with the facts, away from the target field -
            // one builds geometry, the other describes the ground
            CourseMeasuredLengthControls(
                state            = state,
                onMeasuredChange = onMeasuredLengthChange,
                onMeasuredPreset = onMeasuredPreset
            )

            // Summary row (distance) from persisted data
            state.course?.let { c ->
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                if (c.isMeasured) {
                    SummaryRow(Icons.Default.Straighten,
                        "Measured length", formatter.format(c.measuredLengthMetres, unit))
                    // The drawn line is never hidden once a course is measured:
                    // the map still shows it, and anything read off the map is
                    // still that number.
                    SummaryRow(Icons.Default.Straighten,
                        "Drawn line", formatter.format(c.totalDistanceMetres, unit))
                } else {
                    SummaryRow(Icons.Default.Straighten,
                        "Total distance", formatter.format(c.totalDistanceMetres, unit))
                }
                SummaryRow(Icons.Default.Place, "Markers", "${c.markers.size}")
            }

            // Save changes button
            Button(
                onClick  = onSave,
                enabled  = !state.isSaving && state.editName.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save Changes")
            }
        }
    }
}

/** Laps, race distance chips and target field. Shared with the post-recording prompt. */
@Composable
private fun LapCompositionControls(
    state: CourseWorkspaceUiState,
    onLapCountChange: (Int) -> Unit,
    onTargetDistanceChange: (String) -> Unit,
    onTargetPreset: (Long) -> Unit
) {
    // Lap count stepper
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Laps", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onLapCountChange(state.editLapCount - 1) },
                enabled = state.editLapCount > 1
            ) { Icon(Icons.Default.Remove, contentDescription = "Fewer laps") }
            Text(
                "${state.editLapCount}",
                style    = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(
                onClick = { onLapCountChange(state.editLapCount + 1) }
            ) { Icon(Icons.Default.Add, contentDescription = "More laps") }
        }
    }

    Text(
        "Or compose to a race distance:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TargetDistanceChips(
        targetDistanceCm = state.editTargetDistanceCm,
        onTargetSelected = onTargetPreset,
        modifier         = Modifier.fillMaxWidth()
    )

    OutlinedTextField(
        value         = state.editTargetDistanceText,
        onValueChange = onTargetDistanceChange,
        label         = { Text("Target total distance (${state.distanceUnit.symbol}, optional)") },
        supportingText = { Text("Leave blank to use exactly ${state.editLapCount} full lap(s)") },
        singleLine    = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier      = Modifier.fillMaxWidth()
    )
}

/**
 * Measured course length, what the course measures on the ground (calibrated
 * bike on the shortest legal line). Lets km signs go where the runner has
 * covered a km, not where the drawn line has. In metres since it's copied off
 * a certificate.
 */
@Composable
private fun CourseMeasuredLengthControls(
    state: CourseWorkspaceUiState,
    onMeasuredChange: (String) -> Unit,
    onMeasuredPreset: (Double) -> Unit
) {
    Text(
        "Course measured length",
        style = MaterialTheme.typography.titleSmall
    )
    Text(
        "What this course measures on the ground, if it has been measured. " +
            "Kilometre markers are placed against this rather than the drawn line.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CourseWorkspaceViewModel.MEASURED_LENGTH_PRESETS.forEach { (label, metres) ->
            FilterChip(
                selected = kotlin.math.abs(state.editMeasuredLengthMetres - metres) < 0.05,
                onClick  = {
                    val already = kotlin.math.abs(state.editMeasuredLengthMetres - metres) < 0.05
                    onMeasuredPreset(if (already) 0.0 else metres)
                },
                label    = { Text(label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    OutlinedTextField(
        value         = state.editMeasuredLengthText,
        onValueChange = onMeasuredChange,
        label         = { Text("Measured length (m, optional)") },
        supportingText = {
            Text(
                if (state.editMeasuredLengthText.isBlank()) {
                    "Leave blank if this course has not been measured."
                } else {
                    "Metres, e.g. 42195"
                }
            )
        },
        singleLine    = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier      = Modifier.fillMaxWidth()
    )

    if (state.measuredLengthEffect.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Straighten, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    state.measuredLengthEffect,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }

    if (state.measuredLengthWarning.isNotBlank()) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    state.measuredLengthWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Asks for laps again after recording, now that the distance is known. Uses
 * the same edit state as the essentials panel.
 */
@Composable
private fun RecordedLapPromptCard(
    state: CourseWorkspaceUiState,
    onLapCountChange: (Int) -> Unit,
    onTargetDistanceChange: (String) -> Unit,
    onTargetPreset: (Long) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()
    val recorded = state.baseRoute?.distanceMetres ?: 0.0

    Card(
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recorded ${formatter.formatCompact(recorded, unit)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Text(
                "Is that the whole course, or one lap of it? You can repeat it now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            LapCompositionControls(
                state                  = state,
                onLapCountChange       = onLapCountChange,
                onTargetDistanceChange = onTargetDistanceChange,
                onTargetPreset         = onTargetPreset
            )

            if (state.compositionPreview.isNotBlank()) {
                Text(
                    state.compositionPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Keep as recorded")
                }
                Button(
                    onClick  = onApply,
                    enabled  = !state.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WarningsSummaryRow(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick      = onToggle,
        modifier     = Modifier.padding(horizontal = 12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        CmDisclosureIcon(
            expanded,
            what = "warnings",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(IconSize.sm)
        )
        Spacer(Modifier.width(4.dp))
        Text("$count warning(s)", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun WarningRow(warning: RouteWarning) {
    val (color, icon) = when (warning.severity) {
        RouteWarning.Severity.ERROR   -> MaterialTheme.colorScheme.error    to Icons.Default.Error
        RouteWarning.Severity.WARNING -> MaterialTheme.colorScheme.tertiary to Icons.Default.Warning
        RouteWarning.Severity.INFO    -> MaterialTheme.colorScheme.primary  to Icons.Default.Info
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(warning.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun MarkersHeader(count: Int, expanded: Boolean = false, onToggle: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Markers ($count)", style = MaterialTheme.typography.titleSmall)
        if (count == 0) {
            Text("No preset selected", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(
                onClick        = onToggle,
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                CmDisclosureIcon(
                    expanded,
                    what = "markers",
                    modifier = Modifier.size(IconSize.sm)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun MarkerRow(marker: DistanceMarker, onDelete: () -> Unit) {
    val typeIcon = when (marker.type) {
        MarkerType.START            -> Icons.Default.PlayArrow
        MarkerType.FINISH           -> Icons.Default.Flag
        MarkerType.DISTANCE         -> Icons.Default.LocationOn
        MarkerType.CHECKPOINT       -> Icons.Default.CheckCircle
        MarkerType.WATER_STATION    -> Icons.Default.LocalDrink
        MarkerType.CUSTOM_DISTANCES -> Icons.Default.Route
    }
    ListItem(
        headlineContent   = { Text(marker.label) },
        supportingContent = {
            val formatter = LocalDistanceFormatter.current
            val unit by rememberDistanceUnit()
            Text("${formatter.format(marker.cumulativeDistanceMetres, unit)} · #${marker.sequenceIndex}",
                style = MaterialTheme.typography.bodySmall)
        },
        leadingContent  = {
            Icon(typeIcon, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Close, contentDescription = "Remove marker",
                    modifier = Modifier.size(18.dp))
            }
        }
    )
}

@Composable
private fun DraftActionsSection(
    state: CourseWorkspaceUiState,
    source: String?,
    onPublish: () -> Unit,
    onRerecord: () -> Unit
) {
    var warningsAcknowledged by remember(state.warnings.size) {
        mutableStateOf(state.warnings.isEmpty())
    }
    var showPublishConfirm by remember { mutableStateOf(false) }

    if (showPublishConfirm) {
        val course = state.course
        CmConfirmDialog(
            title        = "Publish course?",
            icon         = Icons.Default.CheckCircle,
            confirmLabel = "Publish",
            onConfirm    = { showPublishConfirm = false; onPublish() },
            onDismiss    = { showPublishConfirm = false }
        ) {
            if (course != null) {
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                Text(
                    "${course.name} · ${formatter.formatCompact(course.totalDistanceMetres, unit)} · " +
                        "${course.lapCount} lap(s) · ${course.markers.size} marker(s)",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (course.markers.isEmpty()) {
                    Text(
                        "⚠ This course has no markers — it will be published as markerless.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                "Publishing unlocks placement stops and navigation. " +
                "You can still edit details later, but saving changes regenerates all markers.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Publish", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            if (state.warnings.isNotEmpty() && !warningsAcknowledged) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked         = warningsAcknowledged,
                        onCheckedChange = { warningsAcknowledged = it }
                    )
                    Text(
                        "I've reviewed the warnings above and wish to proceed.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick  = { showPublishConfirm = true },
                enabled  = warningsAcknowledged && !state.isPublishing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Publish Course")
            }

            if (source == "recorded") {
                OutlinedButton(
                    onClick  = onRerecord,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Re-record")
                }
            }
        }
    }
}

/**
 * Placement stops, read-only. Stops are derived from markers and the
 * clustering threshold and recomputed on every rebuild, so editing them here
 * would just be wiped.
 */
@Composable
private fun PlacementStopsSummary(
    stops: List<PlacementStop>,
    hasMarkers: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Placement stops", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)

            if (stops.isEmpty()) {
                Text(
                    if (hasMarkers) {
                        "No stops yet — they are grouped from the markers automatically."
                    } else {
                        "A marker preset is required before there is anything to plant."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "${stops.size} stops · grouped from the markers using the clustering " +
                        "distance in Settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
                stops.take(3).forEach { stop ->
                    val labels = stop.markers.joinToString(" · ") { it.label.ifBlank { it.type.name } }
                    Text("Stop ${stop.stopIndex + 1}: $labels",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (stops.size > 3) {
                    Text("…and ${stops.size - 3} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

