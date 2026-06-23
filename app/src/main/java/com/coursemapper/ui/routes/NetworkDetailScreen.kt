package com.coursemapper.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.toComposeColor
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.RouteVariant
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.rememberMapState

/**
 * Network detail: trunk and branches on a map, all distances, adding branches
 * and distances. "Create Draft" goes through the workspace before publishing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    onBack: () -> Unit,
    onAddBranch: (networkId: Long) -> Unit,
    onCreateVariant: (networkId: Long) -> Unit,
    onVariantCourseClicked: (courseId: Long) -> Unit,
    viewModel: NetworkDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()

    // Navigate to the draft workspace once a draft has been created.
    LaunchedEffect(state.draftCourseId) {
        val courseId = state.draftCourseId ?: return@LaunchedEffect
        viewModel.consumeDraftCourse()
        onVariantCourseClicked(courseId)
    }

    // Network map: trunk (blue) + branches (purple) + junction pins (amber dots).
    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(state.trunkPoints, state.branchPointsList, state.junctionPins, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        val trunkPts = state.trunkPoints.map { it.lat to it.lon }
        val branches = state.branchPointsList.map { pts ->
            MapPolylineOverlay(
                points   = pts.map { it.lat to it.lon },
                colorHex = MapPalette.BRANCH,
                widthDp  = 4f
            )
        }
        val scene = MapScene(
            routePoints          = trunkPts,
            routeColorHex        = MapPalette.ROUTE,
            additionalPolylines  = branches,
            // Junctions use the dedicated junction layer (amber) so they share
            // a visual identity with Branch Authoring, distinct from markers.
            junctionPins         = state.junctionPins
        )
        MapRenderCoordinator.apply(mapState.map, scene)
        if (trunkPts.isNotEmpty() && !hasFitCamera.value) {
            val allPts = trunkPts + state.branchPointsList.flatMap { pts -> pts.map { it.lat to it.lon } }
            MapOverlayManager.fitCamera(mapState.map, allPts)
            hasFitCamera.value = true
        }
    }

    // Preset picker bottom sheet
    val pendingVariantId = state.pendingDraftVariantId
    if (pendingVariantId != null) {
        PresetPickerSheet(
            presets      = state.availablePresets,
            noMarkers    = state.pendingDraftNoMarkers,
            isCreating   = state.isCreatingDraft,
            onNoMarkersToggled = viewModel::toggleNoMarkers,
            onConfirm = { presetId ->
                viewModel.confirmCreateDraft(presetId)
            },
            onDismiss = viewModel::dismissDraftPicker
        )
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = state.network?.name ?: "Route Network",
                onBack  = onBack
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }

        val network = state.network
        if (network == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Network not found.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                CourseMapView(
                    modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                    state    = mapState
                )
                // Legend below the map - colours must match the actual map
                // palette, not the Material theme, or the legend lies.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LegendDot(color = MapPalette.ROUTE.toComposeColor(), label = "Main route")
                    if (state.branchPointsList.isNotEmpty()) {
                        LegendDot(color = MapPalette.BRANCH.toComposeColor(), label = "Branch")
                    }
                    if (state.junctionPins.isNotEmpty()) {
                        LegendDot(color = MapPalette.JUNCTION.toComposeColor(), label = "Junction")
                    }
                }
                HorizontalDivider()
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SectionLabel("Main Route")
                    val trunk = network.segments.firstOrNull { it.isMainTrunk }
                    if (trunk != null) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Column {
                                    val formatter = LocalDistanceFormatter.current
                                    val unit by rememberDistanceUnit()
                                    Text(network.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        formatter.formatCompact(trunk.distanceMetres, unit),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                val branches = network.segments.filter { !it.isMainTrunk }
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel("Branches (${branches.size})")
                        TextButton(onClick = { onAddBranch(network.id) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Branch")
                        }
                    }
                    if (branches.isEmpty()) {
                        Text(
                            "No branch segments yet. Add a branch to create shorter distances.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            items(network.segments.filter { !it.isMainTrunk }, key = { it.id }) { seg ->
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val formatter = LocalDistanceFormatter.current
                            val unit by rememberDistanceUnit()
                            Text(
                                state.branchRouteNames[seg.id] ?: "Branch segment",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val sliceLabel = when {
                                seg.fromMetres != null && seg.toMetres != null ->
                                    "${formatter.formatCompact(seg.fromMetres, unit)}\u2013" +
                                        "${formatter.formatCompact(seg.toMetres, unit)} \u00b7 junction-trimmed"
                                else ->
                                    "${formatter.formatCompact(seg.distanceMetres, unit)} \u00b7 full polyline"
                            }
                            Text(sliceLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabel("Distances (${state.variants.size})")
                        TextButton(onClick = { onCreateVariant(network.id) }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New Distance")
                        }
                    }
                    if (state.variants.isEmpty()) {
                        Text(
                            "No distances yet. Create a distance to compile a Marathon, Half Marathon, or 10K from this network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            items(state.variants, key = { it.id }) { variant ->
                VariantCard(
                    variant   = variant,
                    onCreateDraft = if (!variant.isApproved) {
                        { viewModel.requestCreateDraft(variant.id) }
                    } else null,
                    onClicked = if (variant.isApproved) {
                        val courseId = state.approvedCourseIds[variant.id]
                        if (courseId != null) { { onVariantCourseClicked(courseId) } } else null
                    } else null
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetPickerSheet(
    presets: List<MarkerPreset>,
    noMarkers: Boolean,
    isCreating: Boolean,
    onNoMarkersToggled: (Boolean) -> Unit,
    onConfirm: (presetId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPresetId by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Choose Marker Preset", style = MaterialTheme.typography.titleMedium)
            Text(
                "Select a preset to auto-place markers, or toggle \"No markers\" for a markerless draft.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Preset list
            presets.forEach { preset ->
                val selected = !noMarkers && selectedPresetId == preset.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !noMarkers) {
                            selectedPresetId = preset.id
                            onNoMarkersToggled(false)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(selected = selected, onClick = {
                        selectedPresetId = preset.id
                        onNoMarkersToggled(false)
                    }, enabled = !noMarkers)
                    Text(preset.name, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (presets.isEmpty()) {
                Text(
                    "No presets configured. Create one in Settings → Marker Presets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // No markers toggle
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onNoMarkersToggled(!noMarkers); selectedPresetId = null },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Checkbox(checked = noMarkers, onCheckedChange = { onNoMarkersToggled(it); if (it) selectedPresetId = null })
                Column {
                    Text("No markers (markerless course)", style = MaterialTheme.typography.bodyMedium)
                    Text("A banner will show in Course Workspace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))

            val canConfirm = noMarkers || selectedPresetId != null
            Button(
                onClick = { onConfirm(if (noMarkers) null else selectedPresetId) },
                enabled = canConfirm && !isCreating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCreating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Create Draft")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.size(10.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = color
        ) {}
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style    = MaterialTheme.typography.labelLarge,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun VariantCard(
    variant: RouteVariant,
    onCreateDraft: (() -> Unit)?,
    onClicked: (() -> Unit)?
) {
    val clickableModifier = if (onClicked != null) Modifier.clickable(onClick = onClicked) else Modifier
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(clickableModifier),
        colors = if (variant.isApproved) CardDefaults.cardColors()
                 else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (variant.isApproved) Icons.Default.CheckCircle else Icons.Default.Schedule,
                contentDescription = null,
                tint = if (variant.isApproved) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                Text(
                    variant.name,
                    style    = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val distText = variant.totalDistanceMetres?.let { formatter.formatCompact(it, unit) }
                    ?: "${variant.segmentIds.size} segment(s) — not yet compiled"
                val segCount = "${variant.segmentIds.size} segment(s)"
                Text(
                    if (variant.totalDistanceMetres != null) "$distText · $segCount" else distText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (variant.isApproved) {
                    Text("Draft created — open to review", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (onCreateDraft != null) {
                TextButton(onClick = onCreateDraft) { Text("Create Draft") }
            }
            if (variant.isApproved && onClicked != null) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Open course", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
