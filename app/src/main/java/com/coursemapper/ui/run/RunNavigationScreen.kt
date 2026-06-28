package com.coursemapper.ui.run

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.toComposeColor
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.domain.FollowCameraSmoother
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapActiveTarget
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.MapRenderCoordinator
import com.coursemapper.map.MapScene
import com.coursemapper.map.MapStopPin
import com.coursemapper.map.MapStopState
import com.coursemapper.map.MapViewportPadding
import com.coursemapper.map.camera.MapCameraController
import com.coursemapper.map.rememberMapState
import kotlinx.coroutines.flow.collectLatest

/** Chrome heights for the first frame before measuring, so the first camera fit is close. */
private val UNMEASURED_TOP_CHROME = 132.dp
private val UNMEASURED_BOTTOM_CHROME = 200.dp

/**
 * Placement run navigation, made for a mounted phone. Dwell does the
 * confirming, the approach line is a suggestion and not turn-by-turn, every pin
 * is tappable, and progress is saved on every change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunNavigationScreen(
    onExit: () -> Unit,
    viewModel: RunNavigationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mapState = rememberMapState()
    val context = LocalContext.current
    val density = LocalDensity.current
    // usable map band is between banner and controls, both measured since they
    // change with large controls, the nav bar and run completion. Only permanent
    // chrome counts, the maneuver strip and checklist come and go and would make
    // the map slide under the rider.
    var bannerCoreHeightPx by remember { mutableIntStateOf(0) }
    var gpsStripHeightPx by remember { mutableIntStateOf(0) }
    val topChromeHeightPx = bannerCoreHeightPx + gpsStripHeightPx
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    val bottomControlsHeight = with(density) { bottomControlsHeightPx.toDp() }
    var mapHeightPx by remember { mutableIntStateOf(0) }
    var mapWidthPx by remember { mutableIntStateOf(0) }
    // Until the first layout pass there is nothing measured to work from, and
    // guessing low would fit the course under the banner.  These are close to
    // the sizes the two pieces of chrome actually come out at.
    val effectiveTopChromePx = with(density) {
        topChromeHeightPx.takeIf { it > 0 } ?: UNMEASURED_TOP_CHROME.roundToPx()
    }
    val effectiveBottomChromePx = with(density) {
        bottomControlsHeightPx.takeIf { it > 0 } ?: UNMEASURED_BOTTOM_CHROME.roundToPx()
    }
    val topChromeHeight = with(density) { effectiveTopChromePx.toDp() }
    // A fitted overview belongs in the same band, plus a margin so pins do not
    // touch its edges.
    val overviewPadding = remember(density, effectiveTopChromePx, effectiveBottomChromePx) {
        with(density) {
            MapViewportPadding(
                left   = 24.dp.roundToPx(),
                top    = effectiveTopChromePx + 12.dp.roundToPx(),
                right  = 24.dp.roundToPx(),
                bottom = effectiveBottomChromePx + 12.dp.roundToPx()
            )
        }
    }
    // rider sits below middle in course-up so most of the view is ahead,
    // centred in north-up. Fraction of the whole viewport, like padding and puck.
    val anchorFraction = remember(
        mapHeightPx, effectiveTopChromePx, effectiveBottomChromePx, state.courseUpEnabled
    ) {
        MapCameraController.anchorFractionInBand(
            viewportHeightPx = mapHeightPx,
            topChromePx      = effectiveTopChromePx,
            bottomChromePx   = effectiveBottomChromePx,
            bandFraction     = if (state.courseUpEnabled) {
                MapCameraController.FOLLOW_ANCHOR_FRACTION
            } else 0.5
        )
    }
    // padding for the whole session, MapLibre shares one content padding between
    // map and camera so changing it per mode would jump the map when panning
    val followPadding = remember(mapHeightPx, anchorFraction) {
        MapCameraController.anchorPadding(
            viewportHeightPx = mapHeightPx,
            anchorFraction   = anchorFraction
        )
    }

    // rider marker isn't in the scene, it's animated per frame below and the
    // scene would snap it back. Course lines and stops don't change during a run
    // so they're memoised.
    val overlays = remember(state.courseLines, state.sharedCorridors) {
        state.courseLines.toMapOverlays(state.sharedCorridors)
    }
    val stopPins = remember(state.stops, state.courseLines) {
        state.stops.map { stop ->
            val (pinLat, pinLon) = state.courseLines.snapStopToDrawnLine(stop.lat, stop.lon)
            MapStopPin(
                lat   = pinLat,
                lon   = pinLon,
                label = stopLabel(stop),
                badge = "${stop.signs.size}",
                state = when (stop.state) {
                    RunStopState.PENDING -> MapStopState.PENDING
                    RunStopState.DONE    -> MapStopState.DONE
                    RunStopState.SKIPPED -> MapStopState.SKIPPED
                }
            )
        }
    }
    // The one part of the scene that really does change with the fix: the target
    // ring's accuracy halo.  Quantised to whole metres so sub-metre GPS jitter
    // stops re-applying a scene that is otherwise identical.
    val targetAccuracyMetres = state.currentLocation?.accuracy
        ?.takeIf { it.isFinite() && it > 0f }
        ?.toDouble()
        ?.coerceIn(3.0, 50.0)
        ?.let { kotlin.math.round(it) }
    val activeTarget = remember(state.activeStop, state.courseLines, targetAccuracyMetres) {
        state.activeStop?.let { stop ->
            // Same snap as its pin, or the target ring sits beside its own stop.
            val (targetLat, targetLon) = state.courseLines.snapStopToDrawnLine(stop.lat, stop.lon)
            MapActiveTarget(
                targetLat, targetLon, "Place: ${stopLabel(stop)}",
                placementAccuracyMetres = targetAccuracyMetres
            )
        }
    }

    LaunchedEffect(
        overlays, stopPins, activeTarget,
        state.approachLine, state.isRouted, state.isComplete,
        mapState.isReady
    ) {
        if (!mapState.isReady) return@LaunchedEffect
        MapRenderCoordinator.apply(
            mapState.map,
            buildRunScene(state, overlays, stopPins, activeTarget)
        )
    }

    // Report the band the zoom policy has to frame the next stop into - the
    // map minus its chrome, not the map.
    LaunchedEffect(mapWidthPx, mapHeightPx, effectiveTopChromePx, effectiveBottomChromePx, density) {
        val bandHeightPx = (mapHeightPx - effectiveTopChromePx - effectiveBottomChromePx)
            .coerceAtLeast(0)
        with(density) {
            viewModel.setMapViewportDp(
                widthDp             = mapWidthPx.toDp().value.toDouble(),
                visibleBandHeightDp = bandHeightPx.toDp().value.toDouble()
            )
        }
    }

    // Navigation starts in follow mode. Only perform the one-shot overview fit
    // when the screen was explicitly opened in browse mode.
    val hasFitCamera = remember { mutableStateOf(false) }
    LaunchedEffect(mapState.isReady, state.stops.isNotEmpty(), state.isFollowMode) {
        if (!mapState.isReady || state.stops.isEmpty() || hasFitCamera.value) return@LaunchedEffect
        if (state.isFollowMode) {
            hasFitCamera.value = true
            return@LaunchedEffect
        }
        MapCameraController.fitRoute(
            mapState.map,
            state.stops.map { it.lat to it.lon },
            overviewPadding,
            northUp = true
        )
        hasFitCamera.value = true
    }

    // follow camera per display frame, converging on each fix instead of
    // jumping to it
    val smoother = remember { FollowCameraSmoother() }
    // Coming back from browse mode is the one move the frame loop cannot make
    // smooth - the camera is wherever the rider panned it to.  That one gets
    // animated, and the per-frame moves stand back until it has played out.
    var easeUntilMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isFollowMode, mapState.isReady) {
        if (!state.isFollowMode || !mapState.isReady) return@LaunchedEffect
        val frame = state.toCameraTarget()?.let { smoother.advance(it, 0.0) }
            ?: return@LaunchedEffect
        easeUntilMs = System.currentTimeMillis() + MapCameraController.EASE_DURATION_MS
        MapCameraController.easeToFrame(
            mapState.map, frame, followPadding, courseUp = state.courseUpEnabled
        )
    }
    // puck rotation in screen space, zero in course-up since the camera already
    // follows the bearing
    var puckRotationDeg by remember { mutableFloatStateOf(0f) }
    // outside the frame loop effect, which restarts on padding changes and would
    // forget a puck still on the map
    val puckOnMap = remember { mutableStateOf(false) }

    LaunchedEffect(mapState.isReady, followPadding) {
        if (!mapState.isReady) {
            // A style reload takes the puck layer with it; start the next one
            // where the rider actually is rather than easing in from wherever
            // the old frame left off.
            smoother.reset()
            return@LaunchedEffect
        }
        var lastFrameNanos = 0L
        // collectLatest, so a new fix abandons the frame loop mid-flight and
        // restarts it against the newer target - the smoother keeps its current
        // frame across that, so nothing jumps at the seam.
        viewModel.uiState.collectLatest { current ->
            val target = current.toCameraTarget() ?: return@collectLatest
            var settled = false
            while (!settled) {
                settled = withFrameNanos { frameNanos ->
                    val elapsedSeconds = if (lastFrameNanos == 0L) 0.0
                        else (frameNanos - lastFrameNanos) / 1_000_000_000.0
                    lastFrameNanos = frameNanos

                    val frame = smoother.advance(target, elapsedSeconds)

                    // ── One switch, in one place: which renderer draws the
                    //    rider.  See [RiderPuckOverlay] for why there are two.
                    if (current.isFollowMode) {
                        if (puckOnMap.value) {
                            MapOverlayManager.clearCurrentPosition(mapState.map)
                            puckOnMap.value = false
                        }
                        puckRotationDeg = if (current.courseUpEnabled) {
                            0f
                        } else {
                            frame.headingDeg?.toFloat() ?: 0f
                        }
                    } else {
                        MapRenderCoordinator.applyPosition(
                            mapState.map, frame.lat, frame.lon, frame.headingDeg?.toFloat()
                        )
                        puckOnMap.value = true
                    }

                    if (current.isFollowMode && System.currentTimeMillis() >= easeUntilMs) {
                        MapCameraController.followFrame(
                            map             = mapState.map,
                            frame           = frame,
                            viewportPadding = followPadding,
                            courseUp        = current.courseUpEnabled
                        )
                    }
                    smoother.isSettled(target)
                }
            }
        }
    }

    // Keep the screen awake for the whole ride.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Back = pause: every stop change is already persisted, so pausing is just
    // exiting - the run waits on the home screen until resumed.
    var showPauseDialog by remember { mutableStateOf(false) }
    BackHandler { showPauseDialog = true }
    if (showPauseDialog) {
        CmConfirmDialog(
            title        = "Pause this run?",
            icon         = Icons.Default.Pause,
            confirmLabel = "Pause & exit",
            // "Cancel" here could be read as cancelling the run itself.
            dismissLabel = "Keep going",
            onConfirm    = { showPauseDialog = false; onExit() },
            onDismiss    = { showPauseDialog = false }
        ) {
            Text(
                "Progress is saved. Resume any time from the home screen — " +
                "the run continues exactly where you left it."
            )
        }
    }

    // Exit after Finish.
    LaunchedEffect(state.finished) { if (state.finished) onExit() }

    // Launch share sheet for the exported log.
    LaunchedEffect(state.shareIntent) {
        val intent = state.shareIntent ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(intent, "Share placement log"))
        viewModel.consumeShareIntent()
    }

    // Action snackbar with Undo.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.lastActionLabel) {
        val label = state.lastActionLabel ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message      = label,
            actionLabel  = if (state.undoCount > 0) "Undo" else null,
            duration     = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLast()
        viewModel.dismissActionLabel()
    }

    if (state.isLoading) {
        CmLoading()
        return
    }

    if (state.showSweepPrompt) {
        CmConfirmDialog(
            title        = "${state.skippedCount} stop(s) skipped",
            icon         = Icons.Default.Replay,
            confirmLabel = "Route me back",
            // An offer, not a pending action: declining defers it.
            dismissLabel = "Not now",
            onConfirm    = viewModel::acceptSweep,
            onDismiss    = viewModel::declineSweep
        ) {
            Text(
                "Route back to the skipped stops now? They will be re-planned " +
                "as the shortest path from your current position."
            )
        }
    }
    if (state.showSummary) {
        AlertDialog(
            onDismissRequest = viewModel::dismissSummary,
            icon  = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
            title = { Text("Run finished") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.doneCount} placed · ${state.skippedCount} skipped · ${state.stops.size} total stops")
                    Text(
                        "Export the placement log as CSV for the race director, or finish now.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { Button(onClick = viewModel::finishRun) { Text("Finish run") } },
            dismissButton = {
                Row {
                    TextButton(onClick = viewModel::exportLog) { Text("Export log") }
                    TextButton(onClick = viewModel::dismissSummary) { Text("Stay") }
                }
            }
        )
    }

    if (state.isStopListVisible) {
        StopListSheet(
            state         = state,
            onStopClicked = viewModel::selectStopFromList,
            onDone        = viewModel::markStopDone,
            onSkip        = viewModel::skipStop,
            onNext        = viewModel::makeStopNext,
            onReopen      = viewModel::reopenStop,
            onBackToList  = viewModel::dismissStopSheet,
            onDismiss     = viewModel::closeStopList
        )
    } else {
        state.selectedStop?.let { stop ->
            StopActionSheet(
                stop      = stop,
                isActive  = stop.id == state.activeStop?.id,
                onDone    = { viewModel.markStopDone(stop) },
                onSkip    = { viewModel.skipStop(stop) },
                onNext    = { viewModel.makeStopNext(stop) },
                onReopen  = { viewModel.reopenStop(stop) },
                onDismiss = viewModel::dismissStopSheet
            )
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                // The anchor is a fraction of this height and the framing needs
                // the width, so neither the camera nor the puck can be placed
                // until the map has been measured.
                .onSizeChanged { mapWidthPx = it.width; mapHeightPx = it.height }
        ) {
            CourseMapView(
                modifier        = Modifier.fillMaxSize(),
                state           = mapState,
                viewportPadding = followPadding,
                onMapClick      = { lat, lon -> viewModel.onMapTap(lat, lon) },
                onUserGesture   = viewModel::onUserGesture
            )

            // rider as a screen space overlay while following, cheaper than a
            // GeoJSON upload per frame. Browse mode uses the map-anchored puck.
            if (state.isFollowMode && state.currentLocation != null) {
                RiderPuckOverlay(
                    rotationDeg    = { puckRotationDeg },
                    anchorFraction = anchorFraction
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                RunBanner(
                    state               = state,
                    onStopList          = viewModel::toggleStopList,
                    onChecklistToggle   = viewModel::toggleChecklist,
                    onProfileCycle      = viewModel::cycleTravelProfile,
                    formatDistance      = viewModel::formatDistance,
                    onCoreHeightChanged = { bannerCoreHeightPx = it }
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { gpsStripHeightPx = it.height },
                    color    = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            text  = state.gpsAccuracyLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text  = "${state.doneCount + state.skippedCount} / ${state.stops.size} resolved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // sign checklist, auto expands when dwelling at a multi-sign stop
            val checklistVisible = state.activeStop != null && (
                state.showChecklist ||
                    (state.autoCompleteStatus.isArrivalActive() &&
                        (state.activeStop?.signs?.size ?: 0) > 1)
            )
            AnimatedVisibility(
                visible  = checklistVisible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = topChromeHeight),
                enter    = fadeIn() + slideInVertically(),
                exit     = fadeOut() + slideOutVertically()
            ) {
                SignChecklistPanel(
                    stop          = state.activeStop,
                    checkedSigns  = state.checkedSigns,
                    largeControls = state.largeControls,
                    onToggleSign  = viewModel::toggleSignChecked,
                    onDismiss     = viewModel::toggleChecklist
                )
            }

            if (state.stops.isEmpty()) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "This run has no stops",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Its courses have no markers to place. Assign a KM marker " +
                            "profile to the course(s) in the workspace, then start a new run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                            Text("Back to home")
                        }
                    }
                }
            }

            // browse mode controls, shown after any gesture. No auto-fit on
            // gesture, it would fight pinch zooming into a cluster.
            AnimatedVisibility(
                visible  = !state.isFollowMode,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomControlsHeight + 12.dp),
                enter    = fadeIn() + slideInVertically { it / 2 },
                exit     = fadeOut() + slideOutVertically { it / 2 }
            ) {
                val browseButtonHeight = if (state.largeControls) 56.dp else 46.dp
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Eagle eye: whole run, north-up.
                    FilledTonalButton(
                        onClick  = {
                            viewModel.showOverview()
                            MapCameraController.fitRoute(
                                mapState.map,
                                state.stops.map { it.lat to it.lon },
                                overviewPadding,
                                northUp = true
                            )
                        },
                        modifier = Modifier.height(browseButtonHeight)
                    ) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Overview")
                    }
                    // Back into navigation: centred, course-up, zoomed.
                    Button(
                        onClick  = viewModel::resumeFollow,
                        modifier = Modifier.height(browseButtonHeight)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Refocus", fontWeight = FontWeight.Bold)
                    }
                }
            }

            var showEndConfirm by remember { mutableStateOf(false) }
            if (showEndConfirm) {
                CmConfirmDialog(
                    title        = "End run with stops remaining?",
                    confirmLabel = "End run",
                    destructive  = true,
                    dismissLabel = "Keep going",
                    onConfirm    = { showEndConfirm = false; viewModel.finishRun() },
                    onDismiss    = { showEndConfirm = false }
                ) {
                    Text(
                        "${state.stops.count { it.state == RunStopState.PENDING }} stop(s) are " +
                        "still pending. The run will be marked finished."
                    )
                }
            }
            RunControls(
                state          = state,
                onMarkNow      = viewModel::markActiveDone,
                onUndo         = viewModel::undoLast,
                onCancel       = viewModel::cancelAutoComplete,
                onSkip         = viewModel::skipActive,
                onPauseRequest = { showPauseDialog = true },
                onEndRequest   = { showEndConfirm = true },
                onFinish       = viewModel::finishRun,
                modifier       = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { bottomControlsHeightPx = it.height }
            )
        }
    }
}

/** A stop's signs as one label. */
private fun stopLabel(stop: RunStop): String =
    stop.signs.joinToString("\n") { "${it.courseName} ${it.label}" }
        .ifBlank { "Stop ${stop.stopIndex + 1}" }

/** Assemble the scene from parts the caller already memoised. */
private fun buildRunScene(
    state: RunNavigationUiState,
    overlays: List<MapPolylineOverlay>,
    stopPins: List<MapStopPin>,
    activeTarget: MapActiveTarget?
): MapScene =
    // every course is its own overlay layer, shared corridors cut them into
    // several paths so there's no single "route" line
    MapScene(
        additionalPolylines = overlays,
        placementStops      = stopPins,
        activeTarget        = activeTarget,
        // no rider marker, see toCameraTarget. Solid is routed, dashed is straight.
        approachLine        = if (state.approachLine.size >= 2 && !state.isComplete) {
            MapPolylineOverlay(state.approachLine, MapPalette.APPROACH, 5f, dashed = !state.isRouted)
        } else null
    )

/**
 * Where the puck and camera are heading, null without a fix. Snapped position
 * when clearly on a course line, raw fix otherwise - drawing only.
 */
internal fun RunNavigationUiState.toCameraTarget(): FollowCameraSmoother.Target? {
    val position = displayPosition
        ?: currentLocation?.let { it.latitude to it.longitude }
        ?: return null
    // Known direction of travel turns the marker into an arrow; without one it
    // renders as a plain dot rather than an arrow pointing at a guess.
    val heading = travelBearingDeg?.toDouble()
        ?: cameraBearing?.takeIf { courseUpEnabled }
    return FollowCameraSmoother.Target(
        lat              = position.first,
        lon              = position.second,
        headingDeg       = heading,
        cameraBearingDeg = if (courseUpEnabled) cameraBearing ?: heading ?: 0.0 else 0.0,
        zoom             = navigationZoom,
        tiltDeg          = cameraTiltDeg
    )
}

private fun AutoCompleteStatus.isArrivalActive(): Boolean =
    this is AutoCompleteStatus.Counting ||
        this is AutoCompleteStatus.Paused ||
        this is AutoCompleteStatus.DepartureGrace

private fun AutoCompleteStatus.progressFraction(): Float? = when (this) {
    is AutoCompleteStatus.Counting -> fraction
    is AutoCompleteStatus.Paused -> fraction
    is AutoCompleteStatus.DepartureGrace -> fraction
    else -> null
}

@Composable
private fun RunBanner(
    state: RunNavigationUiState,
    onStopList: () -> Unit,
    onChecklistToggle: () -> Unit,
    onProfileCycle: () -> Unit,
    formatDistance: (Double) -> String,
    /** Height of the permanent part of the banner, the maneuver strip isn't counted. */
    onCoreHeightChanged: (Int) -> Unit = {}
) {
    val active = state.activeStop
    Surface(
        modifier       = Modifier.fillMaxWidth(),
        color          = if (state.autoCompleteStatus.isArrivalActive()) MaterialTheme.colorScheme.tertiaryContainer
                         else MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Before the padding, not after: a size reported from
                    // inside it is 16 dp short of the banner the map is
                    // actually hidden behind.
                    .onSizeChanged { onCoreHeightChanged(it.height) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // one line headline, sign details are in the checklist
                    val headlineStyle = if (state.largeControls) {
                        MaterialTheme.typography.titleLarge
                    } else {
                        MaterialTheme.typography.titleMedium
                    }
                    if (active == null) {
                        Text(
                            "All stops resolved!",
                            style      = headlineStyle,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        val headline = if (active.signs.size == 1) {
                            active.signs.first().let { "${it.courseName} ${it.label}" }
                        } else {
                            "${active.signs.size} signs at this stop"
                        }.ifBlank { "Stop ${active.stopIndex + 1}" }
                        Text(
                            text       = headline,
                            style      = headlineStyle,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 1,
                            overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color      = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        val position = state.stops.indexOfFirst { it.id == active.id } + 1
                        val statusText = when (val status = state.autoCompleteStatus) {
                            is AutoCompleteStatus.Counting ->
                                "Auto-completing in ${status.remainingSeconds} s"
                            is AutoCompleteStatus.Paused -> "Auto-complete paused · GPS"
                            is AutoCompleteStatus.DepartureGrace ->
                                "Auto-completing in ${status.remainingSeconds} s · verifying departure"
                            AutoCompleteStatus.Canceled ->
                                "Auto-complete canceled · Mark now or return later"
                            AutoCompleteStatus.Idle -> state.placementGuidance?.text ?: run {
                                val distance = (state.routedDistanceMetres ?: state.distanceToActiveMetres)
                                    ?.let { " · ${formatDistance(it)}" }
                                    .orEmpty()
                                "Stop $position of ${state.stops.size}$distance"
                            }
                        }
                        val announceStatus = state.autoCompleteStatus !is AutoCompleteStatus.Idle
                        Text(
                            text  = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            modifier = if (announceStatus) {
                                Modifier.semantics {
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = statusText
                                }
                            } else Modifier
                        )
                    }
                }

                // Arrival ring: dwell confirmation progress.
                val arrivalProgress = state.autoCompleteStatus.progressFraction()
                if (arrivalProgress != null && active != null) {
                    CircularProgressIndicator(
                        progress    = { arrivalProgress },
                        modifier    = Modifier
                            .size(if (state.largeControls) 44.dp else 34.dp)
                            .semantics {
                                contentDescription = "Auto-complete progress ${(arrivalProgress * 100).toInt()} percent"
                            },
                        strokeWidth = 4.dp,
                        color       = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                }

                // All stops + statuses.
                IconButton(onClick = onStopList) {
                    Icon(
                        Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = "Show all stops",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Travel profile cycle (Car → Bike → Walk).
                IconButton(onClick = onProfileCycle) {
                    Icon(
                        when (state.travelProfile) {
                            TravelProfile.CAR  -> Icons.Default.DirectionsCar
                            TravelProfile.BIKE -> Icons.AutoMirrored.Filled.DirectionsBike
                            TravelProfile.FOOT -> Icons.AutoMirrored.Filled.DirectionsWalk
                        },
                        contentDescription = "Change travel mode",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                if (active != null && active.signs.isNotEmpty()) {
                    IconButton(onClick = onChecklistToggle) {
                        Icon(
                            Icons.Default.Checklist,
                            contentDescription = "Show sign checklist",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Next-maneuver strip (Google-Maps-style), only when routed.
            val instruction = state.nextInstruction
            if (instruction != null && active != null) {
                // explicit contentColor, the translucent tint isn't a colorScheme
                // match so it would inherit onPrimaryContainer (unreadable in dark)
                Surface(
                    color        = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.TurnSharpLeft,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = instruction + (state.nextInstructionDistanceMetres
                                ?.let { " · ${formatDistance(it)}" } ?: ""),
                            style = if (state.largeControls) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color      = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignChecklistPanel(
    stop: RunStop?,
    checkedSigns: Set<Int>,
    largeControls: Boolean,
    onToggleSign: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    if (stop == null) return
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Signs at this stop", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close",
                        modifier = Modifier.size(18.dp))
                }
            }
            stop.signs.forEachIndexed { index, sign ->
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked         = index in checkedSigns,
                        onCheckedChange = { onToggleSign(index) },
                        modifier        = if (largeControls) Modifier.size(48.dp) else Modifier
                    )
                    Text(
                        text  = "${sign.courseName} — ${sign.label}",
                        style = if (largeControls) MaterialTheme.typography.titleMedium
                                else MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/** Pin-state colour as a Compose colour - mirrors the map palette exactly. */
private fun stopStateColor(state: RunStopState): Color = when (state) {
    RunStopState.PENDING -> MapPalette.ACTIVE_TARGET
    RunStopState.DONE    -> MapPalette.COMPLETED_STOP
    RunStopState.SKIPPED -> MapPalette.SKIPPED_STOP
}.toComposeColor()

/**
 * All pins as a list in visit order, same clustering and colours as the map.
 * Tapping a row shows that stop's actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopListSheet(
    state: RunNavigationUiState,
    onStopClicked: (RunStop) -> Unit,
    onDone: (RunStop) -> Unit,
    onSkip: (RunStop) -> Unit,
    onNext: (RunStop) -> Unit,
    onReopen: (RunStop) -> Unit,
    onBackToList: () -> Unit,
    onDismiss: () -> Unit
) {
    // Resolve the freshest copy of the selected stop so the detail reflects
    // state changes (e.g. a dwell auto-complete) that land while it is open.
    val selected = state.selectedStop?.let { sel ->
        state.stops.find { it.id == sel.id } ?: sel
    }
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = {
            // Back key fires this while the sheet is still visible - treat it as
            // one level of navigation from the detail view. Swipe-down/scrim has
            // already hidden the sheet, so those always dismiss the whole thing.
            if (selected != null && sheetState.isVisible) onBackToList() else onDismiss()
        },
        sheetState = sheetState
    ) {
        if (selected != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier          = Modifier
                        .clickable(onClick = onBackToList)
                        .padding(bottom = 8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to all stops")
                    Spacer(Modifier.width(8.dp))
                    Text("All stops", style = MaterialTheme.typography.titleSmall)
                }
                StopActionContent(
                    stop     = selected,
                    isActive = selected.id == state.activeStop?.id,
                    onDone   = { onDone(selected) },
                    onSkip   = { onSkip(selected) },
                    onNext   = { onNext(selected) },
                    onReopen = { onReopen(selected) }
                )
            }
            return@ModalBottomSheet
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("All stops", style = MaterialTheme.typography.titleMedium)

            // Progress summary in pin colours.
            val pendingCount = state.stops.count { it.state == RunStopState.PENDING }
            Row(
                modifier              = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                StatusCount(stopStateColor(RunStopState.DONE), "${state.doneCount} done")
                StatusCount(stopStateColor(RunStopState.SKIPPED), "${state.skippedCount} skipped")
                StatusCount(stopStateColor(RunStopState.PENDING), "$pendingCount left")
            }
            HorizontalDivider()

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(state.stops.size) { index ->
                    val stop = state.stops[index]
                    val isNext = stop.id == state.activeStop?.id
                    val signsLine = stop.signs
                        .joinToString(" · ") { "${it.courseName} ${it.label}" }
                        .ifBlank { "Stop ${index + 1}" }
                    ListItem(
                        headlineContent = {
                            Text(
                                signsLine,
                                maxLines   = 1,
                                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                fontWeight = if (isNext) FontWeight.Bold else null,
                                style      = if (state.largeControls) {
                                    MaterialTheme.typography.titleMedium
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                }
                            )
                        },
                        supportingContent = {
                            Text(
                                "Stop ${index + 1} of ${state.stops.size}" + when {
                                    isNext                                -> " · next target"
                                    stop.state == RunStopState.DONE       -> " · done"
                                    stop.state == RunStopState.SKIPPED    -> " · skipped"
                                    else                                  -> ""
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            // The pin, as it appears on the map.
                            Box(contentAlignment = Alignment.Center) {
                                Surface(
                                    modifier = Modifier.size(if (state.largeControls) 26.dp else 20.dp),
                                    shape    = androidx.compose.foundation.shape.CircleShape,
                                    color    = stopStateColor(stop.state),
                                    border   = androidx.compose.foundation.BorderStroke(2.dp, Color.White)
                                ) {}
                                if (stop.signs.size > 1) {
                                    Text(
                                        "${stop.signs.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                        },
                        colors = if (isNext) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            )
                        } else ListItemDefaults.colors(),
                        modifier = Modifier.clickable { onStopClicked(stop) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusCount(color: Color, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape    = androidx.compose.foundation.shape.CircleShape,
            color    = color
        ) {}
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/** Stop detail + actions, shared by the standalone sheet and the list sheet. */
@Composable
private fun StopActionContent(
    stop: RunStop,
    isActive: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onReopen: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val stateLabel = when (stop.state) {
            RunStopState.PENDING -> if (isActive) "Next target" else "Pending"
            RunStopState.DONE    -> "Done"
            RunStopState.SKIPPED -> "Skipped"
        }
        Text("Stop ${stop.stopIndex + 1} · $stateLabel", style = MaterialTheme.typography.titleMedium)
        stop.signs.forEach { sign ->
            Text(
                "• ${sign.courseName} — ${sign.label}",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        HorizontalDivider()

        if (stop.state == RunStopState.PENDING) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Mark done")
            }
            if (!isActive) {
                OutlinedButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Navigation, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Take me there next")
                }
            }
            TextButton(
                onClick  = onSkip,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Skip this stop") }
        } else {
            OutlinedButton(onClick = onReopen, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Replay, contentDescription = null,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Reopen (back to pending)")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StopActionSheet(
    stop: RunStop,
    isActive: Boolean,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
    onReopen: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            StopActionContent(
                stop     = stop,
                isActive = isActive,
                onDone   = onDone,
                onSkip   = onSkip,
                onNext   = onNext,
                onReopen = onReopen
            )
        }
    }
}

@Composable
private fun RunControls(
    state: RunNavigationUiState,
    onMarkNow: () -> Unit,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onPauseRequest: () -> Unit,
    onEndRequest: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonHeight = if (state.largeControls) 72.dp else 56.dp
    val labelSize    = if (state.largeControls) 18.sp else 14.sp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.isComplete) {
            Text(
                "All stops resolved",
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.fillMaxWidth()
            )
            Button(
                onClick  = onFinish,
                modifier = Modifier.fillMaxWidth().height(buttonHeight)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Finish run", fontSize = labelSize, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick  = onMarkNow,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(buttonHeight)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                        modifier = Modifier.size(if (state.largeControls) 26.dp else 20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mark Now", fontSize = labelSize, fontWeight = FontWeight.Bold)
                }
                if (state.autoCompleteStatus.isArrivalActive()) {
                    OutlinedButton(
                        onClick  = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(buttonHeight)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null,
                            modifier = Modifier.size(if (state.largeControls) 24.dp else 18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancel", fontSize = labelSize)
                    }
                } else {
                    OutlinedButton(
                        onClick  = onUndo,
                        enabled  = state.undoCount > 0,
                        modifier = Modifier
                            .weight(1f)
                            .height(buttonHeight)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null,
                            modifier = Modifier.size(if (state.largeControls) 24.dp else 18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Undo", fontSize = labelSize)
                    }
                }
            }
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick  = onPauseRequest,
                    modifier = Modifier.weight(1f).height(if (state.largeControls) 56.dp else 40.dp)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Pause", fontSize = labelSize)
                }
                OutlinedButton(
                    onClick  = onSkip,
                    modifier = Modifier.weight(1f).height(if (state.largeControls) 56.dp else 40.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Skip", fontSize = labelSize)
                }
                Button(
                    onClick  = onEndRequest,
                    modifier = Modifier.weight(1f).height(if (state.largeControls) 56.dp else 40.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        // Without this the label stays onPrimary on an error ground.
                        contentColor   = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("End", fontSize = labelSize)
                }
            }
        }
    }
}
