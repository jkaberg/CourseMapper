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
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.designsystem.MapStrip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit
import com.coursemapper.domain.model.RouteSegment
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.rememberMapState

/**
 * Name a distance and pick its segments. "Compile & Save" stitches them with
 * [VariantCompilationEngine]. Markers come later in [RouteRepository.approveVariant].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariantBuilderScreen(
    onBack: () -> Unit,
    onSaved: (variantId: Long) -> Unit,
    viewModel: VariantBuilderViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Live preview: re-render when the compiled preview polyline or disconnections change.
    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(state.previewPolyline, state.disconnectionPoints, mapState.isReady) {
        if (!mapState.isReady) return@LaunchedEffect
        val pts = state.previewPolyline.map { it.lat to it.lon }
        // Red gap markers shown as additional short dashed overlays.
        val gapOverlays = state.disconnectionPoints
            .chunked(2)
            .filter { it.size == 2 }
            .map { (a, b) ->
                MapPolylineOverlay(listOf(a, b), MapPalette.DISCONNECTION, widthDp = 4f)
            }
        MapRenderCoordinator.apply(
            mapState.map,
            MapScene(
                routePoints         = pts,
                routeColorHex       = MapPalette.ROUTE_VARIANT,
                additionalPolylines = gapOverlays
            )
        )
        // Fit the camera the first time geometry appears; after that, leave
        // the user's pan/zoom alone.
        if (pts.isNotEmpty() && !hasFitCamera.value) {
            MapOverlayManager.fitCamera(mapState.map, pts)
            hasFitCamera.value = true
        }
    }

    LaunchedEffect(state.showSaveSuccess) {
        if (state.showSaveSuccess) {
            snackbarHostState.showSnackbar("Distance saved!", duration = SnackbarDuration.Short)
            viewModel.dismissSaveSuccess()
        }
    }

    LaunchedEffect(state.saveError) {
        val error = state.saveError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error, duration = SnackbarDuration.Long)
        viewModel.dismissSaveError()
    }

    LaunchedEffect(state.savedVariantId) {
        val id = state.savedVariantId
        if (id != null) {
            viewModel.consumeSaved()
            onSaved(id)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CmTopBar(
                title   = "New Distance",
                onBack  = onBack
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CourseMapView(
                    modifier = Modifier.fillMaxWidth().height(MapStrip.height),
                    state    = mapState
                )
                if (state.previewPolyline.isEmpty()) {
                    Text(
                        "Select segments below to preview the route",
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                OutlinedTextField(
                    value         = state.name,
                    onValueChange = viewModel::onNameChanged,
                    label         = { Text("Distance name") },
                    placeholder   = { Text("e.g. Marathon, Half Marathon, 10K") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    leadingIcon   = { Icon(Icons.Default.Edit, contentDescription = null) }
                )
            }

            if (state.selectedSegmentIds.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Straighten,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        run {
                            val formatter = LocalDistanceFormatter.current
                            val unit by rememberDistanceUnit()
                            Text(
                                "Estimated distance: ${formatter.formatCompact(state.estimatedMetres, unit)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (state.connectivityError != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            state.connectivityError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            item {
                Text(
                    "Select segments (in traversal order)",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            val segments = state.network?.segments ?: emptyList()
            if (segments.isEmpty()) {
                item {
                    Text(
                        "No segments available in this network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(segments, key = { it.id }) { segment ->
                val orderIndex = state.selectedSegmentIds.indexOf(segment.id).takeIf { it >= 0 }
                SegmentCheckItem(
                    segment    = segment,
                    checked    = segment.id in state.selectedSegmentIds,
                    orderIndex = orderIndex,
                    onToggle   = { viewModel.toggleSegment(segment.id) },
                    onMoveUp   = if (orderIndex != null && orderIndex > 0)
                                    { { viewModel.moveSegmentUp(segment.id) } } else null,
                    onMoveDown = if (orderIndex != null && orderIndex < state.selectedSegmentIds.lastIndex)
                                    { { viewModel.moveSegmentDown(segment.id) } } else null,
                )
            }

            item {
                if (state.isSaving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                }
                Button(
                    onClick  = viewModel::compileAndSave,
                    enabled  = state.canSave,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Compiling…")
                    } else {
                        Icon(Icons.Default.Merge, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Compile & Save")
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentCheckItem(
    segment: RouteSegment,
    checked: Boolean,
    orderIndex: Int?,
    onToggle: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(if (segment.isMainTrunk) "Main trunk" else "Branch segment")
        },
        supportingContent = {
            val formatter = LocalDistanceFormatter.current
            val unit by rememberDistanceUnit()
            Text(formatter.formatCompact(segment.distanceMetres, unit))
        },
        leadingContent = {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (orderIndex != null) {
                    Badge { Text("${orderIndex + 1}") }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick  = { onMoveUp?.invoke() },
                        enabled  = onMoveUp != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up",
                            modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick  = { onMoveDown?.invoke() },
                        enabled  = onMoveDown != null,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down",
                            modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp)
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}
