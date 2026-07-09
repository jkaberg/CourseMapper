package com.coursemapper.ui.run

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmDisclosureIcon
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.ui.components.EndpointMarkerToggles
import com.coursemapper.ui.components.TargetDistanceChips
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.offline.OfflineDownloadPrompt
import com.coursemapper.ui.format.rememberDistanceUnit

/** Batch GPX import: pick files, choose tracks, name them, pick presets, create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventWizardScreen(
    onBack: () -> Unit,
    onPresetsClicked: () -> Unit,
    onCoursesCreated: (courseIds: List<Long>, groupId: Long?) -> Unit,
    viewModel: EventWizardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.loadGpxFiles(uris, context.contentResolver)
    }

    LaunchedEffect(state.created) {
        val result = state.created ?: return@LaunchedEffect
        viewModel.consumeCreated()
        onCoursesCreated(result.courseIds, result.groupId)
    }

    // Asked once for the whole import, before navigating on - the courses are
    // already created and saved either way, so declining costs nothing but the
    // map.
    state.offlinePrompt?.let { proposal ->
        OfflineDownloadPrompt(
            proposal = proposal,
            onDownload = viewModel::acceptOfflineDownload,
            onDecline = viewModel::declineOfflineDownload,
            onDismiss = viewModel::dismissOfflinePrompt
        )
    }

    if (state.pendingOpenLoopConfirm) {
        val formatter = LocalDistanceFormatter.current
        val unit by rememberDistanceUnit()
        CmConfirmDialog(
            title        = "These routes don't close",
            icon         = Icons.Default.Warning,
            confirmLabel = "Create anyway",
            onConfirm    = viewModel::confirmOpenLoopAndCreate,
            onDismiss    = viewModel::dismissOpenLoopConfirm
        ) {
            Text(
                "Repeating a route joins its end back to its start. " +
                    "These tracks finish a long way from where they begin, so " +
                    "every lap will cut straight across ground nobody recorded:"
            )
            state.openLoopTracks.forEach { row ->
                Text(
                    "• ${row.name} — gap of " +
                        formatter.formatCompact(row.openRouteGapMetres ?: 0.0, unit),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Use one lap for these, or import a track that finishes where it started.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Import Event Routes",
                onBack  = onBack,
                actions = {
                    IconButton(onClick = onPresetsClicked) {
                        Icon(Icons.Default.Tune, contentDescription = "Manage marker presets")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedButton(
                    onClick  = { filePicker.launch(arrayOf("application/gpx+xml", "application/octet-stream", "text/xml", "application/xml")) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.tracks.isEmpty()) "Pick GPX file(s)" else "Add more GPX files")
                }
            }

            if (state.isLoading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            state.error?.let { error ->
                item {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.tracks.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        "Pick the GPX files for your event — Marathon, Half Marathon, 10K, 5K… " +
                        "Every track becomes a course with its own marker preset, " +
                        "ready to place in one pass.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            itemsIndexed(state.tracks) { index, row ->
                TrackCard(
                    row        = row,
                    presets    = state.presets,
                    onToggle   = { viewModel.toggleTrack(index) },
                    onRename   = { viewModel.renameTrack(index, it) },
                    onPreset   = { viewModel.setTrackPreset(index, it) },
                    onLaps     = { viewModel.setTrackLaps(index, it) },
                    onTarget   = { viewModel.setTrackTarget(index, it) },
                    onStart    = { viewModel.setTrackIncludeStart(index, it) },
                    onFinish   = { viewModel.setTrackIncludeFinish(index, it) }
                )
            }

            // existing courses can join the event, also without any GPX
            if (state.existingCourses.isNotEmpty()) {
                item {
                    Text(
                        "Include existing courses",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(state.existingCourses.size) { i ->
                    val course = state.existingCourses[i]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked         = course.id in state.selectedExistingIds,
                            onCheckedChange = { viewModel.toggleExistingCourse(course.id) }
                        )
                        Column {
                            Text(course.name, style = MaterialTheme.typography.bodyMedium)
                            val formatter = LocalDistanceFormatter.current
                            val unit by rememberDistanceUnit()
                            Text(
                                "${formatter.formatCompact(course.displayDistanceMetres, unit)} · " +
                                    "${course.markers.size} markers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (state.tracks.isNotEmpty() || state.selectedExistingIds.isNotEmpty()) {
                val memberCount = state.includedTrackCount + state.selectedExistingIds.size
                if (memberCount > 1) {
                    item {
                        OutlinedTextField(
                            value         = state.eventName,
                            onValueChange = viewModel::onEventNameChange,
                            label         = { Text("Event name (combined course)") },
                            supportingText = {
                                Text("The routes are grouped under this name on the home screen.")
                            },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth()
                        )
                    }
                }
                item {
                    if (state.presets.isEmpty() && state.includedTrackCount > 0) {
                        Text(
                            "No marker presets yet — courses will import without markers. " +
                            "Create profiles via the ⚙ icon above (e.g. \"Every km\").",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Button(
                        onClick  = viewModel::createCourses,
                        enabled  = state.canCreate,
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (state.isCreating) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Creating courses…")
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (memberCount > 1) "Create event ($memberCount routes) & plan run"
                                else "Create course & plan run"
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackCard(
    row: WizardTrack,
    presets: List<MarkerPreset>,
    onToggle: () -> Unit,
    onRename: (String) -> Unit,
    onPreset: (Long) -> Unit,
    onLaps: (Int) -> Unit,
    onTarget: (Long) -> Unit,
    onStart: (Boolean) -> Unit,
    onFinish: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.included, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    val formatter = LocalDistanceFormatter.current
                    val unit by rememberDistanceUnit()
                    Text(
                        "${row.track.points.size} GPS points · " +
                            formatter.formatCompact(row.distanceMetres, unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (row.included) {
                OutlinedTextField(
                    value         = row.name,
                    onValueChange = onRename,
                    label         = { Text("Course name *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )

                var expanded by remember { mutableStateOf(false) }
                val selectedName = presets.firstOrNull { it.id == row.presetId }?.name ?: "No markers"
                ExposedDropdownMenuBox(
                    expanded         = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value         = selectedName,
                        onValueChange = {},
                        readOnly      = true,
                        label         = { Text("Marker preset") },
                        trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded         = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text    = { Text("No markers") },
                            onClick = { onPreset(-1L); expanded = false }
                        )
                        presets.forEach { preset ->
                            DropdownMenuItem(
                                text    = { Text(preset.name) },
                                onClick = { onPreset(preset.id); expanded = false }
                            )
                        }
                    }
                }

                EndpointMarkerToggles(
                    includeStart   = row.includeStart,
                    includeFinish  = row.includeFinish,
                    onStartChange  = onStart,
                    onFinishChange = onFinish
                )

                LapSection(row = row, onLaps = onLaps, onTarget = onTarget)
            }
        }
    }
}

/** Laps and target for one track. Collapsed by default, the header shows the total when multi-lap. */
@Composable
private fun LapSection(
    row: WizardTrack,
    onLaps: (Int) -> Unit,
    onTarget: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Repeat,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint     = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (row.isMultiLap) {
                    "${row.composedFullLaps} laps · " +
                        formatter.formatCompact(row.composedDistanceMetres, unit)
                } else {
                    "1 lap — tap to repeat"
                },
                style    = MaterialTheme.typography.bodySmall,
                color    = if (row.isMultiLap) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            CmDisclosureIcon(
                expanded,
                what = "lap options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Laps", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onLaps(row.lapCount - 1) },
                        enabled = row.lapCount > 1
                    ) { Icon(Icons.Default.Remove, contentDescription = "Fewer laps") }
                    Text(
                        row.lapCount.toString(),
                        style     = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.widthIn(min = 32.dp)
                    )
                    IconButton(
                        onClick = { onLaps(row.lapCount + 1) }
                    ) { Icon(Icons.Default.Add, contentDescription = "More laps") }
                }
            }

            Text(
                "Or compose to a race distance:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TargetDistanceChips(
                targetDistanceCm = row.targetDistanceCm,
                onTargetSelected = onTarget,
                modifier         = Modifier.fillMaxWidth()
            )

            CompositionLine(row = row)
        }
    }
}

/** The exact composition this row will produce, in the organiser's unit. */
@Composable
private fun CompositionLine(row: WizardTrack) {
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()

    val lapPart = "${row.composedFullLaps} × ${formatter.formatCompact(row.composedLapMetres, unit)}"
    val partial = if (row.composedPartialMetres > 0.0) {
        " + ${formatter.formatCompact(row.composedPartialMetres, unit)}"
    } else ""
    val total = formatter.formatCompact(row.composedDistanceMetres, unit)

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                "$lapPart$partial = $total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            // Repeating a track joins its end back to its start.  A large gap
            // there means it is probably point-to-point, and every lap will
            // jump straight across ground nobody recorded.
            row.openRouteGapMetres?.let { gap ->
                Text(
                    "⚠ This track does not close — its end is " +
                        "${formatter.formatCompact(gap, unit)} from its start. " +
                        "Each lap crosses that gap in a straight line.",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
