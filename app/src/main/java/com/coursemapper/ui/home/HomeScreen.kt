package com.coursemapper.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmOverflowMenu
import com.coursemapper.ui.designsystem.CmMenuAction
import com.coursemapper.ui.designsystem.CmMenu
import com.coursemapper.ui.designsystem.CmSectionHeader
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.Spacing
import com.coursemapper.ui.designsystem.CmEmptyState
import com.coursemapper.ui.about.AboutDialog
import com.coursemapper.ui.about.AboutViewModel
import com.coursemapper.ui.components.CourseStatusBadge
import com.coursemapper.ui.components.courseStatusLabel
import com.coursemapper.ui.designsystem.toComposeColor
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.CourseStatus
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RunStopState
import com.coursemapper.domain.model.status
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit
import com.coursemapper.ui.offline.formatBytes
import com.coursemapper.ui.run.CombineCoursesSheet

/**
 * Home: courses and groups, resumable runs on top. Both rows open on tap and
 * have the same long-press menu, groups show member colours and the worst
 * member's readiness.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecordClicked: () -> Unit = {},
    onImportGpxClicked: () -> Unit = {},
    onCourseClicked: (Long) -> Unit = {},
    onNewRunClicked: () -> Unit = {},
    onResumeRun: (runId: Long) -> Unit = {},
    onGroupClicked: (groupId: Long) -> Unit = {},
    onOpenRouteWorkspace: (routeId: Long) -> Unit = {},
    onSettingsClicked: () -> Unit = {},
    onHelpClicked: () -> Unit = {},
    onNetworksClicked: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    // Hoisted for the same reason as [viewModel]: resolved inside the body it
    // throws wherever there is no Hilt graph - a preview, a screenshot baseline - 
    // before a single pixel of the screen is drawn.
    aboutViewModel: AboutViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }
    var showCombineSheet by remember { mutableStateOf(false) }
    // Non-null while editing an existing combined course's membership.
    var editRoutesGroupId by remember { mutableStateOf<Long?>(null) }

    state.confirmDeleteCourse?.let { request ->
        CmConfirmDialog(
            title        = "Delete course?",
            icon         = Icons.Default.Delete,
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = viewModel::confirmDelete,
            onDismiss    = viewModel::cancelDelete
        ) {
            // "Permanently removed" described a soft delete.  It is
            // removed from the app; the recording it was composed from
            // stays, and so does everything else composed from it.
            Text(
                "\"${request.course.name}\" is removed from your courses. " +
                    "The recording it was built from is kept."
            )
            if (request.groupNames.isNotEmpty()) {
                Text(
                    "Also removes it from " +
                        request.groupNames.joinToString(", ") + ".",
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (request.hasRunInProgress) {
                Text(
                    "A placement run covering this course is part-way through. " +
                        "Its remaining stops keep the signs they were planned with.",
                    color = MaterialTheme.colorScheme.error
                )
            }
            // only mention the offline map when it's actually released
            request.offlineBytesFreed?.let { bytes ->
                Text(
                    "Frees ${formatBytes(bytes)} of offline map.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    state.confirmDeleteGroup?.let { request ->
        CmConfirmDialog(
            title        = "Delete combined course?",
            icon         = Icons.Default.Delete,
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = viewModel::confirmDeleteGroup,
            onDismiss    = viewModel::cancelDeleteGroup
        ) {
            Text("Only the grouping is removed — the individual courses stay.")
            if (request.memberNames.isNotEmpty()) {
                Text(
                    "Kept: " + request.memberNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    state.rename?.let { request ->
        RenameDialog(
            currentName = request.currentName,
            onDismiss   = viewModel::cancelRename,
            onConfirm   = viewModel::confirmRename
        )
    }

    // Discard-interrupted-recording confirmation dialog
    state.confirmDiscardRecording?.let { route ->
        CmConfirmDialog(
            title        = "Discard recording?",
            text         = "The interrupted recording \"${route.name}\" and its GPS points will be removed.",
            icon         = Icons.Default.Delete,
            confirmLabel = "Discard",
            destructive  = true,
            onConfirm    = viewModel::confirmDiscardRecording,
            onDismiss    = viewModel::cancelDiscardRecording
        )
    }

    if (showCreateSheet) {
        CreateCourseSheet(
            onDismiss    = { showCreateSheet = false },
            onRecordLive = { showCreateSheet = false; onRecordClicked() },
            onImportGpx  = { showCreateSheet = false; onImportGpxClicked() },
            onCombine    = { showCreateSheet = false; showCombineSheet = true },
            canCombine   = state.courses.count { !it.isDraft && it.markers.isNotEmpty() } >= 2
        )
    }

    if (showCombineSheet) {
        CombineCoursesSheet(
            onDismiss = { showCombineSheet = false },
            onSaved   = { groupId -> showCombineSheet = false; onGroupClicked(groupId) }
        )
    }

    editRoutesGroupId?.let { groupId ->
        CombineCoursesSheet(
            groupId   = groupId,
            onDismiss = { editRoutesGroupId = null },
            onSaved   = { editRoutesGroupId = null }
        )
    }

    // About: shown once by itself on first run, and on demand from the overflow
    // thereafter.  The first-run showing wins if both are somehow true, so the
    // gate can never be attached to a card the user opened deliberately.
    val showAboutOnFirstRun by aboutViewModel.showOnFirstRun.collectAsStateWithLifecycle()
    var showAboutFromMenu by remember { mutableStateOf(false) }

    if (showAboutOnFirstRun) {
        AboutDialog(
            onDismiss   = aboutViewModel::markSeen,
            gateSeconds = 8
        )
    } else if (showAboutFromMenu) {
        AboutDialog(onDismiss = { showAboutFromMenu = false })
    }

    Scaffold(
        topBar = {
            HomeTopBar(
                onCreateClicked   = { showCreateSheet = true },
                onNewRunClicked   = onNewRunClicked,
                onNetworksClicked = onNetworksClicked,
                onSettingsClicked = onSettingsClicked,
                onHelpClicked     = onHelpClicked,
                onAboutClicked    = { showAboutFromMenu = true }
            )
        }
    ) { padding ->
        if (state.courses.isEmpty() && state.incompleteRuns.isEmpty() &&
            state.interruptedRecordings.isEmpty()
        ) {
            // Empty state
            Box(
                modifier         = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                AddRouteEmptyState(onRecordClicked = { showCreateSheet = true })
            }
        } else {
            LazyColumn(
                modifier        = Modifier.fillMaxSize().padding(padding),
                contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Interrupted recording recovery banners
                items(state.interruptedRecordings, key = { "interrupted_${it.id}" }) { route ->
                    InterruptedRecordingBanner(
                        route     = route,
                        onSave    = {
                            viewModel.saveInterruptedRecording(route) { routeId ->
                                onOpenRouteWorkspace(routeId)
                            }
                        },
                        onDiscard = { viewModel.requestDiscardRecording(route) }
                    )
                }

                // Resume banners for unfinished placement runs
                items(state.incompleteRuns, key = { "run_${it.id}" }) { run ->
                    RunResumeBanner(run = run, onResume = onResumeRun)
                }

                // Combined courses (events)
                if (state.courseGroups.isNotEmpty()) {
                    item { CmSectionHeader("Combined courses") }
                    items(state.courseGroups, key = { "group_${it.id}" }) { group ->
                        GroupListItem(
                            group       = group,
                            info        = state.groupRowInfo[group.id] ?: GroupRowInfo(),
                            onClick     = { onGroupClicked(group.id) },
                            onRename    = { viewModel.requestRenameGroup(group) },
                            onEditRoutes = { editRoutesGroupId = group.id },
                            onDelete    = { viewModel.requestDeleteGroup(group) }
                        )
                    }
                }

                // Course list
                if (state.courses.isNotEmpty()) {
                    item { CmSectionHeader("Courses") }
                    items(state.courses, key = { it.id }) { course ->
                        CourseListItem(
                            course     = course,
                            status     = state.courseStatuses[course.id] ?: course.status,
                            groupNames = state.courseGroupNames[course.id].orEmpty(),
                            onClick    = { onCourseClicked(course.id) },
                            onRename   = { viewModel.requestRenameCourse(course) },
                            onDelete   = { viewModel.requestDelete(course) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon    = { Icon(Icons.Default.Edit, contentDescription = null) },
        title   = { Text("Rename") },
        text    = {
            OutlinedTextField(
                value         = value,
                onValueChange = { value = it },
                singleLine    = true,
                label         = { Text("Name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Record, import or combine, as a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCourseSheet(
    onDismiss: () -> Unit,
    onRecordLive: () -> Unit,
    onImportGpx: () -> Unit,
    onCombine: () -> Unit,
    canCombine: Boolean
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Add a course",
                style    = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent   = { Text("Record live") },
                supportingContent = {
                    Text("Drive or walk the course while the phone records the track.")
                },
                leadingContent    = {
                    Icon(Icons.Default.FiberManualRecord, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable(onClick = onRecordLive)
            )
            ListItem(
                headlineContent   = { Text("Import GPX") },
                supportingContent = {
                    Text("Load one or more GPX files — every track becomes a course.")
                },
                leadingContent    = {
                    Icon(Icons.Default.FileUpload, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable(onClick = onImportGpx)
            )
            ListItem(
                headlineContent   = { Text("Combine existing courses") },
                supportingContent = {
                    Text(
                        if (canCombine) {
                            "Group courses you already have into one event, placed in one pass."
                        } else {
                            "Needs at least two published courses with markers."
                        }
                    )
                },
                leadingContent    = {
                    Icon(Icons.Default.Layers, contentDescription = null,
                        tint = if (canCombine) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline)
                },
                modifier = if (canCombine) Modifier.clickable(onClick = onCombine) else Modifier
            )
        }
    }
}

@Composable
private fun InterruptedRecordingBanner(
    route: com.coursemapper.domain.model.BaseRoute,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Recording interrupted",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "\"${route.name}\" was not finished. Save it to review the captured track, or discard it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onSave) { Text("Save Route") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

/** Two labelled actions and an overflow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeTopBar(
    onCreateClicked: () -> Unit,
    onNewRunClicked: () -> Unit,
    onNetworksClicked: () -> Unit,
    onSettingsClicked: () -> Unit,
    onHelpClicked: () -> Unit,
    onAboutClicked: () -> Unit
) {
    TopAppBar(
        title = { Text("CourseMapper") },
        actions = {
            IconButton(onClick = onCreateClicked) {
                Icon(Icons.Default.Add, contentDescription = "Add a course")
            }
            IconButton(onClick = onNewRunClicked) {
                Icon(Icons.Default.Navigation, contentDescription = "Start placement run")
            }
            CmOverflowMenu(
                listOf(
                    CmMenuAction("Route networks", Icons.Default.AccountTree, onNetworksClicked),
                    CmMenuAction("Settings", Icons.Default.Settings, onSettingsClicked),
                    CmMenuAction(
                        "Help",
                        Icons.AutoMirrored.Filled.HelpOutline,
                        onHelpClicked
                    ),
                    CmMenuAction("About", Icons.Default.Info, onAboutClicked)
                )
            )
        }
    )
}

@Composable
private fun AddRouteEmptyState(onRecordClicked: () -> Unit) {
    CmEmptyState(
        icon  = Icons.Default.Route,
        title = "No courses yet",
        body  = "Tap + to record or import your first course."
    ) {
        OutlinedButton(onClick = onRecordClicked) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text("Create your first course")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupListItem(
    group: CourseGroup,
    info: GroupRowInfo,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onEditRoutes: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent   = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "${group.courseIds.size} routes · one-pass placement",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // The same colour cycle the detail map draws its lines in.
                    if (info.memberColorsHex.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            info.memberColorsHex.forEach { hex ->
                                Surface(
                                    modifier = Modifier.size(width = 18.dp, height = 5.dp),
                                    shape    = MaterialTheme.shapes.extraSmall,
                                    color    = hex.toComposeColor()
                                ) {}
                            }
                        }
                    }
                    GroupReadinessChip(info)
                }
            },
            leadingContent  = {
                Icon(Icons.Default.Layers, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            },
            modifier = Modifier.combinedClickable(
                onClick     = onClick,
                onLongClick = { menuExpanded = true }
            )
        )

        CmMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            actions = listOf(
                CmMenuAction("Open", Icons.Default.Map, onClick),
                CmMenuAction("Rename", Icons.Default.Edit, onRename),
                CmMenuAction("Edit routes", Icons.Default.Layers, onEditRoutes),
                CmMenuAction("Delete", Icons.Default.Delete, onDelete)
            )
        )
    }
    HorizontalDivider()
}

/** Group readiness from its worst member, naming it so you know what to fix. */
@Composable
private fun GroupReadinessChip(info: GroupRowInfo) {
    val status = info.worstStatus ?: return
    val label = when {
        status == CourseStatus.OFFLINE_READY -> courseStatusLabel(status)
        info.worstCourseName.isNullOrBlank() -> courseStatusLabel(status)
        else -> "${info.worstCourseName} · ${courseStatusLabel(status).lowercase()}"
    }
    CourseStatusBadge(status = status, label = label)
}

@Composable
private fun RunResumeBanner(
    run: PlacementRun,
    onResume: (runId: Long) -> Unit
) {
    val resolved = run.stops.count { it.state != RunStopState.PENDING }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (run.startedAt != null) "Paused placement run" else "Placement run ready",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "${run.name} — $resolved of ${run.stops.size} stops resolved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            FilledTonalButton(onClick = { onResume(run.id) }) {
                Text(if (run.startedAt != null) "Resume" else "Start")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CourseListItem(
    course: ComposedCourse,
    status: CourseStatus,
    groupNames: List<String>,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        ListItem(
            headlineContent = {
                Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                Column {
                    Text(
                        "${formatter.formatCompact(course.displayDistanceMetres, unit)} · ${course.lapCount} lap(s) · ${course.markers.size} markers",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        CourseStatusBadge(status)
                        // Which event this course belongs to, so a member row
                        // is not an orphan sitting under the group it is in.
                        groupNames.forEach { name ->
                            AssistChip(
                                onClick     = {},
                                label       = {
                                    Text(name, style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Layers, contentDescription = null,
                                        modifier = Modifier.size(14.dp))
                                },
                                modifier    = Modifier.height(24.dp)
                            )
                        }
                    }
                }
            },
            leadingContent = {
                Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            },
            modifier = Modifier.combinedClickable(
                onClick     = onClick,
                onLongClick = { menuExpanded = true }
            )
        )

        CmMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            actions = listOf(
                CmMenuAction("Open", Icons.Default.Map, onClick),
                CmMenuAction("Rename", Icons.Default.Edit, onRename),
                CmMenuAction("Delete", Icons.Default.Delete, onDelete)
            )
        )
    }
    HorizontalDivider()
}


