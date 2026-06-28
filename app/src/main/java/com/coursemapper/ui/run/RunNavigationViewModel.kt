package com.coursemapper.ui.run

import com.coursemapper.domain.CourseDisplayPlanner

import android.content.Intent
import android.location.Location
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseLegPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.DirectionalRetargetPolicy
import com.coursemapper.domain.DwellEngine
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.domain.LocationFilter
import com.coursemapper.domain.NavigationMotionTracker
import com.coursemapper.domain.NavigationZoomPolicy
import com.coursemapper.domain.PlacementGuidancePolicy
import com.coursemapper.domain.PolylineIndex
import com.coursemapper.domain.PositionSnapPolicy
import com.coursemapper.domain.RerouteDecisionPolicy
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.location.LocationProvider
import com.coursemapper.map.camera.MapCameraController
import com.coursemapper.routing.RouteDirectionsService
import com.coursemapper.ui.format.DistanceFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AutoCompleteStatus {
    data object Idle : AutoCompleteStatus
    data class Counting(val remainingSeconds: Int, val fraction: Float) : AutoCompleteStatus
    data class Paused(val remainingSeconds: Int, val fraction: Float) : AutoCompleteStatus
    data class DepartureGrace(val remainingSeconds: Int, val fraction: Float) : AutoCompleteStatus
    data object Canceled : AutoCompleteStatus
}

data class RunNavigationUiState(
    val run: PlacementRun? = null,
    val isLoading: Boolean = true,
    /** All stops in visit order. */
    val stops: List<RunStop> = emptyList(),
    /** First pending stop - the navigation target. */
    val activeStop: RunStop? = null,
    /** True when no pending stops remain. */
    val isComplete: Boolean = false,
    val doneCount: Int = 0,
    val skippedCount: Int = 0,

    // GPS / dwell
    val currentLocation: Location? = null,
    /** Where to draw the rider when snapped to a course line, null draws the raw fix. Rendering only. */
    val displayPosition: Pair<Double, Double>? = null,
    val gpsAccuracyLabel: String = "GPS: acquiring…",
    val distanceToActiveMetres: Double? = null,
    val autoCompleteStatus: AutoCompleteStatus = AutoCompleteStatus.Idle,

    // Map / guidance
    val courseLines: List<RunCourseLine> = emptyList(),
    /** Stretches carrying more than one course, for the low-zoom shared casing. */
    val sharedCorridors: List<CourseDisplayPlanner.SharedCorridor> = emptyList(),
    /** Guidance to the active stop, routed (solid) or straight (dashed), see [isRouted]. */
    val approachLine: List<Pair<Double, Double>> = emptyList(),
    val isRouted: Boolean = false,
    /** Road distance to the active stop along the routed leg, when routed. */
    val routedDistanceMetres: Double? = null,
    /** Next maneuver, Google-Maps-style ("Turn left onto Storgatan"). */
    val nextInstruction: String? = null,
    val nextInstructionDistanceMetres: Double? = null,
    /** Direction of travel for the position arrow; null = render dot. */
    val travelBearingDeg: Float? = null,
    val placementGuidance: PlacementGuidancePolicy.Guidance? = null,
    val travelProfile: TravelProfile = TravelProfile.CAR,
    val isFollowMode: Boolean = true,
    val navigationZoom: Double = NavigationZoomPolicy.INITIAL_ZOOM,
    val cameraTiltDeg: Double = 0.0,
    /** Camera bearing for course-up mode; null = north-up. */
    val cameraBearing: Double? = null,

    // Interactions
    val undoCount: Int = 0,
    /** Transient action feedback displayed by the Snackbar. */
    val lastActionLabel: String? = null,
    val showChecklist: Boolean = false,
    /** Per-sign tick state of the ACTIVE stop's checklist (indices into signs). */
    val checkedSigns: Set<Int> = emptySet(),
    /** Stop selected by tapping its pin; opens the stop action sheet. */
    val selectedStop: RunStop? = null,
    /** True while the all-stops status sheet is open. */
    val isStopListVisible: Boolean = false,

    // Run completion
    val showSweepPrompt: Boolean = false,
    val showSummary: Boolean = false,
    /** Set when the run has been finished and the screen should exit. */
    val finished: Boolean = false,
    val shareIntent: Intent? = null,

    // ATV ergonomics
    val largeControls: Boolean = false,
    val courseUpEnabled: Boolean = false,
)

/**
 * Runs a placement run: dwell confirmation, guidance between stops, undo, stop
 * actions and the end of run sweep. Stop state is saved on every change so a
 * process death resumes where it was.
 */
@HiltViewModel
class RunNavigationViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val dwellEngine: DwellEngine,
    private val distanceCalc: CumulativeDistanceCalculator,
    private val locationProvider: LocationProvider,
    private val locationFilter: LocationFilter,
    private val gpsEvaluator: GpsReadinessEvaluator,
    private val motionTracker: NavigationMotionTracker,
    private val placementGuidancePolicy: PlacementGuidancePolicy,
    private val zoomPolicy: NavigationZoomPolicy,
    private val positionSnapPolicy: PositionSnapPolicy,
    private val retargetPolicy: DirectionalRetargetPolicy,
    private val reroutePolicy: RerouteDecisionPolicy,
    private val courseLegPlanner: CourseLegPlanner,
    private val directionsService: RouteDirectionsService,
    private val userPreferences: UserPreferencesRepository,
    private val distanceFormatter: DistanceFormatter,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val runId: Long = savedStateHandle["runId"] ?: -1L

    companion object {
        /** Max distance from a map tap to a stop pin for it to count as a hit. */
        private const val PIN_TAP_RADIUS_M = 35.0
        private const val NEAR_TARGET_AUTO_FOLLOW_M = 75.0
        private const val SUPPRESSED_STOP_IDS_KEY = "autoSuppressedStopIds"

        /** Further from the leg than this counts as off route, keeps the search in the grid. */
        private const val MAX_LEG_PROJECTION_M = 400.0

        /** Retry interval when leg fetching fails (e.g. offline). */
        private const val FETCH_RETRY_MS = 20_000L
    }

    private val _state = MutableStateFlow(RunNavigationUiState())
    val uiState: StateFlow<RunNavigationUiState> = _state.asStateFlow()

    private data class UndoEntry(val stopId: Long, val prevState: RunStopState, val label: String)
    private val undoStack = ArrayDeque<UndoEntry>()

    /** Course geometry indexed once per run, the snap uses it on every fix. */
    private var courseIndices: List<com.coursemapper.domain.CorridorAnalyzer.IndexedCourse> = emptyList()

    private var dwellState = DwellEngine.State.IDLE
    private var motionState = NavigationMotionTracker.State.IDLE
    private var dwellRadiusM = DwellEngine.DEFAULT_RADIUS_M
    private var dwellSecs = DwellEngine.DEFAULT_DWELL_SECONDS
    private var positionSnapRadiusM = PositionSnapPolicy.DEFAULT_RADIUS_M
    private var positionSnapState = PositionSnapPolicy.State.IDLE
    private var autoRetargetEnabled = true
    private var retargetState = DirectionalRetargetPolicy.State.IDLE

    /** Stops that won't auto-complete until the rider leaves the radius, so undo while parked sticks. */
    private val autoSuppressedStopIds = savedStateHandle
        .get<LongArray>(SUPPRESSED_STOP_IDS_KEY)
        ?.toMutableSet()
        ?: mutableSetOf()
    private var completionInFlightStopId: Long? = null

    /** Re-prompt the sweep only if new skips happened since it was declined. */
    private var sweepDeclined = false

    private var manualBrowseLatch = false
    private var hasAcceptedFirstFix = false
    private var hasComputedNavigationZoom = false

    /** When [zoomPolicy] last ran, for its per-second rate limit. */
    private var lastZoomFixTimeMs: Long? = null

    /**
     * Map size in logical points, 0 until first layout. [visibleBandHeightDp] is
     * the part not covered by banner and controls.
     */
    private var viewportWidthDp = 0.0
    private var visibleBandHeightDp = 0.0

    /** Measured map size and visible band, for framing the next stop. */
    fun setMapViewportDp(widthDp: Double, visibleBandHeightDp: Double) {
        this.viewportWidthDp = widthDp.coerceAtLeast(0.0)
        this.visibleBandHeightDp = visibleBandHeightDp.coerceAtLeast(0.0)
    }

    /** Camera framing, null before the map is measured. */
    private fun framing(
        latitudeDeg: Double,
        cameraBearingDeg: Double
    ): NavigationZoomPolicy.Framing? {
        if (visibleBandHeightDp <= 0.0 || viewportWidthDp <= 0.0) return null
        return NavigationZoomPolicy.Framing(
            visibleBandHeightDp = visibleBandHeightDp,
            viewportWidthDp     = viewportWidthDp,
            latitudeDeg         = latitudeDeg,
            anchorFraction      = if (_state.value.courseUpEnabled) {
                MapCameraController.FOLLOW_ANCHOR_FRACTION
            } else 0.5,
            cameraBearingDeg    = cameraBearingDeg
        )
    }

    /** Seconds since the last zoom decision for the rate limiter, null on first fix. */
    private fun zoomElapsedSeconds(fixTimeMs: Long): Double? {
        val previous = lastZoomFixTimeMs
        lastZoomFixTimeMs = fixTimeMs
        return previous?.let { (fixTimeMs - it).coerceAtLeast(0L) / 1_000.0 }
    }

    private var currentUnit = DistanceFormatter.DistanceUnit.KM

    /** The routed leg for ([legStopId], [legProfile]); null = fallback line. */
    private var currentLeg: RouteDirectionsService.DirectionsLeg? = null
    /** [currentLeg]'s geometry, prepared for the per-fix projection. */
    private var legIndex: PolylineIndex? = null
    private var legStopId: Long = -1L
    private var legProfile: TravelProfile? = null
    private var legFetchJob: Job? = null
    private var lastFetchAttemptMs = 0L
    private var rerouteState = RerouteDecisionPolicy.State.IDLE

    init {
        locationFilter.reset()
        viewModelScope.launch {
            dwellRadiusM = userPreferences.dwellRadiusMetres.first()
            dwellSecs    = userPreferences.dwellSeconds.first()
            positionSnapRadiusM = userPreferences.positionSnapRadiusMetres.first()
            autoRetargetEnabled = userPreferences.autoRetarget.first()
            currentUnit  = distanceFormatter.unitEnumFlow.first()
            _state.update {
                it.copy(
                    largeControls   = userPreferences.largeControls.first(),
                    courseUpEnabled = userPreferences.courseUpCamera.first()
                )
            }
            loadRun()
            startLocationUpdates()
        }
    }

    private suspend fun loadRun() {
        val run = repository.getRun(runId)
        if (run == null) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        repository.markRunStarted(runId)

        // Course polylines for context: colour-cycled per course, and unified
        // into shared ribbons wherever the courses run the same roads.
        val display = repository.getCourseDisplayLines(run.courseIds)
        courseIndices = display.indices

        applyStops(
            run,
            courseLines = display.toRunCourseLines(),
            sharedCorridors = display.sharedCorridors,
            isInitialLoad = true
        )
    }

    /** Recompute all stop-derived state from a fresh run snapshot. */
    private fun applyStops(
        run: PlacementRun,
        courseLines: List<RunCourseLine>? = null,
        sharedCorridors: List<CourseDisplayPlanner.SharedCorridor>? = null,
        isInitialLoad: Boolean = false
    ) {
        val stops = run.stops.sortedBy { it.stopIndex }
        val active = stops.firstOrNull { it.state == RunStopState.PENDING }
        val isComplete = active == null

        val targetChanged = _state.value.activeStop?.id != active?.id
        if (targetChanged) {
            dwellState = DwellEngine.State.IDLE
            completionInFlightStopId = null
            clearLeg()
            // The turn being confirmed was a turn away from the *old* target;
            // it says nothing about this one.  The cooldown survives, so an
            // auto re-target cannot immediately chain into another.
            retargetState = retargetState.copy(turnStartMs = null)
        }

        _state.update { s ->
            s.copy(
                run           = run,
                isLoading     = false,
                stops         = stops,
                activeStop    = active,
                isComplete    = isComplete,
                doneCount     = stops.count { it.state == RunStopState.DONE },
                skippedCount  = stops.count { it.state == RunStopState.SKIPPED },
                courseLines   = courseLines ?: s.courseLines,
                sharedCorridors = sharedCorridors ?: s.sharedCorridors,
                travelProfile = run.travelProfile,
                checkedSigns  = if (targetChanged) emptySet() else s.checkedSigns,
                autoCompleteStatus = if (targetChanged) {
                    if (active != null && active.id in autoSuppressedStopIds) AutoCompleteStatus.Canceled
                    else AutoCompleteStatus.Idle
                } else s.autoCompleteStatus,
                placementGuidance = if (targetChanged) null else s.placementGuidance,
                distanceToActiveMetres = if (targetChanged) null else s.distanceToActiveMetres,
                approachLine = if (targetChanged) emptyList() else s.approachLine,
                isRouted = if (targetChanged) false else s.isRouted,
                routedDistanceMetres = if (targetChanged) null else s.routedDistanceMetres,
                nextInstruction = if (targetChanged) null else s.nextInstruction,
                nextInstructionDistanceMetres = if (targetChanged) null else s.nextInstructionDistanceMetres
            )
        }

        if (isComplete && !isInitialLoad) {
            onAllStopsResolved()
        }
    }

    private suspend fun reload() {
        repository.getRun(runId)?.let { applyStops(it) }
    }

    /** Snapped position when clearly on a course line, null draws the raw fix. */
    private fun snapDisplayPosition(
        location: Location,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        distanceToActiveStopMetres: Double?
    ): Pair<Double, Double>? {
        val (snap, nextState) = positionSnapPolicy.snap(
            courses = courseIndices,
            lat = location.latitude,
            lon = location.longitude,
            travelBearingDeg = travelBearingDeg,
            speedMetresPerSecond = speedMetresPerSecond,
            distanceToActiveStopMetres = distanceToActiveStopMetres,
            dwellRadiusMetres = dwellRadiusM,
            radiusMetres = positionSnapRadiusM,
            state = positionSnapState
        )
        positionSnapState = nextState
        return snap?.let { it.lat to it.lon }
    }

    private fun startLocationUpdates() {
        viewModelScope.launch {
            locationProvider.locationUpdates(LocationProvider.Cadence.NAVIGATION)
                .collect { location -> onFix(location) }
        }
    }

    private fun onFix(location: Location) {
        val filterResult = locationFilter.evaluate(location)
        if (!filterResult.accepted) return

        val fixTimeMs = (location.elapsedRealtimeNanos / 1_000_000L)
            .takeIf { it > 0L }
            ?: location.time
        val (motion, newMotionState) = motionTracker.process(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = location.accuracy,
            nowMs = fixTimeMs,
            fusedSpeedMetresPerSecond = if (location.hasSpeed()) location.speed else null,
            fusedBearingDeg = if (location.hasBearing()) location.bearing else null,
            state = motionState
        )
        motionState = newMotionState

        val readiness = gpsEvaluator.evaluate(location, GpsReadinessEvaluator.Context.NAVIGATION)
        val gpsLabel = when (readiness) {
            is GpsReadinessEvaluator.GpsReadiness.Green  -> "GPS ±${location.accuracy.toInt()} m"
            is GpsReadinessEvaluator.GpsReadiness.Yellow -> "GPS ±${location.accuracy.toInt()} m"
            is GpsReadinessEvaluator.GpsReadiness.Red    -> "GPS weak ±${location.accuracy.toInt()} m"
        }

        val active = _state.value.activeStop
        val travelBearing = motion.travelBearingDeg
        val routeBearing = active?.let {
            bearingDegrees(location.latitude, location.longitude, it.lat, it.lon)
        }
        val cameraBearing = if (_state.value.courseUpEnabled) {
            travelBearing?.toDouble() ?: routeBearing ?: 0.0
        } else 0.0
        if (active == null) {
            val follow = _state.value.isFollowMode || (!manualBrowseLatch && motion.becameMoving)
            hasAcceptedFirstFix = true
            val zoom = zoomPolicy.zoom(
                motion.speedMetresPerSecond,
                distanceToTargetMetres = null,
                previousZoom = _state.value.navigationZoom.takeIf { hasComputedNavigationZoom },
                // No stop to frame, but the look-ahead still needs to know how
                // much band it has to put the next twenty seconds into.
                framing = framing(location.latitude, cameraBearing),
                elapsedSeconds = zoomElapsedSeconds(fixTimeMs)
            )
            hasComputedNavigationZoom = true
            val tilt = zoomPolicy.tiltDegrees(motion.speedMetresPerSecond, null)
            _state.update {
                it.copy(
                    currentLocation = location,
                    displayPosition = snapDisplayPosition(
                        location = location,
                        travelBearingDeg = travelBearing,
                        speedMetresPerSecond = motion.speedMetresPerSecond,
                        distanceToActiveStopMetres = null
                    ),
                    gpsAccuracyLabel = gpsLabel,
                    distanceToActiveMetres = null, approachLine = emptyList(),
                    isRouted = false, routedDistanceMetres = null,
                    nextInstruction = null, nextInstructionDistanceMetres = null,
                    travelBearingDeg = travelBearing,
                    autoCompleteStatus = AutoCompleteStatus.Idle,
                    placementGuidance = null,
                    cameraBearing = cameraBearing,
                    isFollowMode = follow,
                    navigationZoom = zoom,
                    cameraTiltDeg = tilt
                )
            }
            return
        }

        // Dwell detection uses physical (straight-line) proximity - the radius
        // is about standing at the stop, not about road distance.
        val distM = distanceCalc.haversineMetres(
            location.latitude, location.longitude, active.lat, active.lon
        )
        val shouldAutoFollow = !manualBrowseLatch && (
            motion.becameMoving ||
                (!hasAcceptedFirstFix && distM <= maxOf(NEAR_TARGET_AUTO_FOLLOW_M, dwellRadiusM * 2.0))
            )
        hasAcceptedFirstFix = true

        val suppressed = active.id in autoSuppressedStopIds
        val (dwellResult, newDwellState) = dwellEngine.process(
            distanceToTargetMetres = distM,
            accuracyMetres         = location.accuracy,
            nowMs                  = fixTimeMs,
            state                  = dwellState,
            radiusMetres           = dwellRadiusM,
            dwellSeconds           = dwellSecs,
            motion                 = motion.motion,
            arrivalEnabled         = !suppressed
        )
        dwellState = newDwellState

        val autoCompleteStatus = when (dwellResult) {
            is DwellEngine.Result.Waiting -> {
                if (suppressed) AutoCompleteStatus.Canceled else AutoCompleteStatus.Idle
            }
            is DwellEngine.Result.Outside -> {
                if (autoSuppressedStopIds.remove(active.id)) persistSuppressedStops()
                AutoCompleteStatus.Idle
            }
            is DwellEngine.Result.Counting -> AutoCompleteStatus.Counting(
                remainingSeconds(dwellResult.remainingMs), dwellResult.fraction
            )
            is DwellEngine.Result.Paused -> {
                if (suppressed) AutoCompleteStatus.Canceled
                else if (dwellState.armed) AutoCompleteStatus.Paused(
                    remainingSeconds(dwellResult.remainingMs), dwellResult.fraction
                ) else AutoCompleteStatus.Idle
            }
            is DwellEngine.Result.DepartureGrace -> AutoCompleteStatus.DepartureGrace(
                remainingSeconds(dwellResult.remainingMs), dwellResult.fraction
            )
            is DwellEngine.Result.Confirmed -> {
                setStopState(active, RunStopState.DONE, auto = true)
                AutoCompleteStatus.Counting(0, 1f)
            }
        }

        val placementGuidance = placementGuidancePolicy.guidance(
            riderLat = location.latitude,
            riderLon = location.longitude,
            targetLat = active.lat,
            targetLon = active.lon,
            accuracyMetres = location.accuracy,
            travelBearingDeg = travelBearing
        )
        val navigationZoom = zoomPolicy.zoom(
            speedMetresPerSecond = motion.speedMetresPerSecond,
            distanceToTargetMetres = distM,
            previousZoom = _state.value.navigationZoom.takeIf { hasComputedNavigationZoom },
            framing = framing(location.latitude, cameraBearing),
            // Which way the stop lies, so the framing can tell "300 m ahead"
            // from "300 m off to the left" and from "300 m behind".
            bearingToTargetDeg = routeBearing,
            elapsedSeconds = zoomElapsedSeconds(fixTimeMs)
        )
        hasComputedNavigationZoom = true
        val cameraTilt = zoomPolicy.tiltDegrees(motion.speedMetresPerSecond, distM)

        val guidance = updateLegGuidance(
            location             = location,
            active               = active,
            travelBearingDeg     = travelBearing,
            speedMetresPerSecond = motion.speedMetresPerSecond,
            nowMs                = fixTimeMs
        )

        maybeRetarget(
            location               = location,
            active                 = active,
            travelBearingDeg       = travelBearing,
            speedMetresPerSecond   = motion.speedMetresPerSecond,
            distanceToActiveMetres = distM,
            nowMs                  = fixTimeMs
        )

        // ── Where to DRAW the rider.  Everything above this line - dwell,
        //    distance, guidance, re-routing - used the raw fix, and must.
        val displayPosition = snapDisplayPosition(
            location = location,
            travelBearingDeg = travelBearing,
            speedMetresPerSecond = motion.speedMetresPerSecond,
            distanceToActiveStopMetres = distM
        )

        _state.update {
            it.copy(
                currentLocation               = location,
                displayPosition               = displayPosition,
                gpsAccuracyLabel              = gpsLabel,
                distanceToActiveMetres        = distM,
                approachLine                  = guidance.line,
                isRouted                      = guidance.isRouted,
                routedDistanceMetres          = guidance.routedDistanceMetres,
                nextInstruction               = guidance.instruction,
                nextInstructionDistanceMetres = guidance.instructionDistanceMetres,
                travelBearingDeg              = travelBearing,
                autoCompleteStatus            = autoCompleteStatus,
                placementGuidance             = placementGuidance,
                cameraBearing                 = cameraBearing,
                isFollowMode                  = it.isFollowMode || shouldAutoFollow,
                navigationZoom                = navigationZoom,
                cameraTiltDeg                 = cameraTilt
            )
        }
    }

    /**
     * Retarget once [DirectionalRetargetPolicy] says the rider turned around and
     * stayed turned around.
     */
    private fun maybeRetarget(
        location: Location,
        active: RunStop,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        distanceToActiveMetres: Double,
        nowMs: Long
    ) {
        if (!autoRetargetEnabled || completionInFlightStopId != null) return

        val (decision, nextState) = retargetPolicy.evaluate(
            riderLat               = location.latitude,
            riderLon               = location.longitude,
            travelBearingDeg       = travelBearingDeg,
            speedMetresPerSecond   = speedMetresPerSecond,
            activeStop             = DirectionalRetargetPolicy.Stop(active.id, active.lat, active.lon),
            distanceToActiveMetres = distanceToActiveMetres,
            pendingStops           = _state.value.stops
                .filter { it.state == RunStopState.PENDING }
                .map { DirectionalRetargetPolicy.Stop(it.id, it.lat, it.lon) },
            nowMs                  = nowMs,
            state                  = retargetState
        )
        retargetState = nextState

        val stop = decision?.let { d -> _state.value.stops.firstOrNull { it.id == d.stopId } } ?: return
        viewModelScope.launch {
            // resequence everything, the rider turned around so the whole plan changed
            repository.resequenceFromDirection(
                runId = runId,
                riderLat = location.latitude,
                riderLon = location.longitude,
                headingDegrees = travelBearingDeg?.toDouble()
            )
            reload()
        }
        _state.update {
            it.copy(lastActionLabel = "Turned around — next: ${stopLabel(stop)}")
        }
    }

    private fun remainingSeconds(remainingMs: Long): Int =
        ((remainingMs + 999L) / 1_000L).toInt()

    private data class LegGuidance(
        val line: List<Pair<Double, Double>>,
        val isRouted: Boolean,
        val routedDistanceMetres: Double?,
        val instruction: String?,
        val instructionDistanceMetres: Double?
    )

    /**
     * Keep the routed leg to the active stop up to date. Planned on target or
     * profile change, then refetched per [RerouteDecisionPolicy]. Falls back to
     * the straight dashed line.
     */
    private fun updateLegGuidance(
        location: Location,
        active: RunStop,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        nowMs: Long
    ): LegGuidance {
            // follow main route: the path along the recorded course is the answer,
            // no routing request
        if (_state.value.run?.ordering == RunOrdering.SPINE) {
            val courseLeg = courseLegPlanner.plan(
                courses   = courseIndices.map {
                    CourseLegPlanner.Course(it.courseId, it.index)
                },
                riderLat  = location.latitude,
                riderLon  = location.longitude,
                stopLat   = active.lat,
                stopLon   = active.lon
            )
            if (courseLeg != null) {
                // Drop any road leg still in flight or in hand: it is not what
                // is being drawn, and leaving it live would let a late reply
                // re-enter the picture the moment the course answer blinks out.
                if (legStopId != -1L || currentLeg != null || legFetchJob != null) clearLeg()
                return LegGuidance(
                    line                      = courseLeg.points,
                    isRouted                  = true,
                    routedDistanceMetres      = courseLeg.remainingMetres,
                    // The course *is* the instruction; there are no maneuvers to
                    // call, and the banner hides itself when there is nothing to
                    // say.
                    instruction               = null,
                    instructionDistanceMetres = null
                )
            }
            // Fell through: off the course, or no course carries both ends.
            // Road routing takes over from here, and clearLeg above has already
            // reset the key so the fetch below happens on this fix.
        }

        val profile = _state.value.travelProfile

        val keyChanged = active.id != legStopId || profile != legProfile
        val retryDue = currentLeg == null && legFetchJob == null &&
            nowMs - lastFetchAttemptMs > FETCH_RETRY_MS
        if (keyChanged || retryDue) {
            fetchLeg(location, active, profile, replaceUnconditionally = true, nowMs = nowMs)
        }

        val leg = currentLeg
        val index = legIndex
        if (leg == null || index == null) return straightLineTo(location, active)

        val match = index.nearest(location.latitude, location.longitude, MAX_LEG_PROJECTION_M)
        val remaining = match?.let { (leg.distanceMetres - it.cumulativeMetres).coerceAtLeast(0.0) }

        val (reason, nextRerouteState) = reroutePolicy.evaluate(
            offsetMetres         = match?.offsetMetres,
            legBearingDeg        = match?.let { index.bearingAt(it.cumulativeMetres) },
            travelBearingDeg     = travelBearingDeg,
            speedMetresPerSecond = speedMetresPerSecond,
            remainingMetres      = remaining,
            nowMs                = nowMs,
            state                = rerouteState
        )
        rerouteState = nextRerouteState
        if (reason != null && legFetchJob == null) {
            fetchLeg(
                location, active, profile,
                nowMs = nowMs,
                // a correction always replaces, a probe only when clearly shorter
                replaceUnconditionally  = reason != RerouteDecisionPolicy.Reason.BETTER_ROUTE,
                comparedRemainingMetres = remaining
            )
        }

        if (match == null) return straightLineTo(location, active)

        val nextStep = leg.steps.firstOrNull {
            it.startCumulativeMetres > match.cumulativeMetres + 5.0
        }
        return LegGuidance(
            line                      = leg.points,
            isRouted                  = true,
            routedDistanceMetres      = remaining,
            instruction               = nextStep?.instruction,
            instructionDistanceMetres = nextStep
                ?.let { it.startCumulativeMetres - match.cumulativeMetres }
        )
    }

    /** The crow-flies suggestion drawn whenever there is no usable routed leg. */
    private fun straightLineTo(location: Location, active: RunStop) = LegGuidance(
        line = listOf(
            location.latitude to location.longitude,
            active.lat to active.lon
        ),
        isRouted                  = false,
        routedDistanceMetres      = null,
        instruction               = null,
        instructionDistanceMetres = null
    )

    /**
     * @param replaceUnconditionally true for plan or correction, false for a probe
     * @param comparedRemainingMetres what the current leg has left, for the probe
     */
    private fun fetchLeg(
        location: Location,
        active: RunStop,
        profile: TravelProfile,
        replaceUnconditionally: Boolean,
        nowMs: Long,
        comparedRemainingMetres: Double? = null
    ) {
        legStopId  = active.id
        legProfile = profile
        lastFetchAttemptMs = nowMs
        legFetchJob?.cancel()
        legFetchJob = viewModelScope.launch {
            val leg = directionsService.route(
                profile = profile,
                fromLat = location.latitude,
                fromLon = location.longitude,
                toLat   = active.lat,
                toLon   = active.lon
            )
            // Ignore stale results for a different target/profile.
            if (legStopId == active.id && legProfile == profile) {
                when {
                    leg == null -> if (replaceUnconditionally) clearLegGeometry()
                    replaceUnconditionally -> adoptLeg(leg, nowMs)
                    reroutePolicy.shouldAdoptAlternative(
                        currentRemainingMetres = comparedRemainingMetres,
                        alternativeMetres      = leg.distanceMetres,
                        // fallback answer vs primary leg, don't let it win on metres
                        alternativeIsLowerQuality =
                            leg.source < (currentLeg?.source ?: leg.source)
                    ) -> adoptLeg(leg, nowMs)
                }
            }
            legFetchJob = null
        }
    }

    private fun adoptLeg(leg: RouteDirectionsService.DirectionsLeg, nowMs: Long) {
        currentLeg = leg
        legIndex = PolylineIndex.build(
            leg.points.map { (lat, lon) -> RoutePoint(lat, lon, null, null, 0L, isSmoothed = true) },
            distanceCalc
        )
        // Stamped with the fix the fetch was made from, not with "now": the
        // whole pipeline runs on one monotonic clock, and starting the throttle
        // at the request rather than at the reply is the conservative end.
        rerouteState = reroutePolicy.onLegAdopted(nowMs)
    }

    private fun clearLegGeometry() {
        currentLeg = null
        legIndex = null
        rerouteState = RerouteDecisionPolicy.State.IDLE
    }

    private fun clearLeg() {
        legFetchJob?.cancel()
        legFetchJob = null
        clearLegGeometry()
        legStopId = -1L
        legProfile = null
        lastFetchAttemptMs = 0L
    }

    /** Cycle Car → Bike → Walk; persists on the run and re-routes the leg. */
    fun cycleTravelProfile() {
        val next = when (_state.value.travelProfile) {
            TravelProfile.CAR  -> TravelProfile.BIKE
            TravelProfile.BIKE -> TravelProfile.FOOT
            TravelProfile.FOOT -> TravelProfile.CAR
        }
        clearLeg()
        _state.update {
            it.copy(
                travelProfile   = next,
                isRouted        = false,
                lastActionLabel = "Travel mode: " + when (next) {
                    TravelProfile.CAR  -> "Car"
                    TravelProfile.BIKE -> "Bike"
                    TravelProfile.FOOT -> "Walk"
                }
            )
        }
        viewModelScope.launch { repository.updateRunTravelProfile(runId, next) }
    }

    /** Primary "Mark Now" - confirms the active stop manually. */
    fun markActiveDone() {
        val active = _state.value.activeStop ?: return
        setStopState(active, RunStopState.DONE, auto = false)
    }

    fun cancelAutoComplete() {
        val active = _state.value.activeStop ?: return
        suppressAutoComplete(active.id)
    }

    /** Skip the active stop (e.g. blocked access). Reopened by the end sweep. */
    fun skipActive() {
        val active = _state.value.activeStop ?: return
        sweepDeclined = false
        setStopState(active, RunStopState.SKIPPED, auto = false)
    }

    /** Tap-sheet action: mark any pending stop done. */
    fun markStopDone(stop: RunStop) {
        if (stop.state == RunStopState.PENDING) setStopState(stop, RunStopState.DONE, auto = false)
        dismissStopSheet()
    }

    /** Tap-sheet action: skip any pending stop. */
    fun skipStop(stop: RunStop) {
        if (stop.state == RunStopState.PENDING) {
            sweepDeclined = false
            setStopState(stop, RunStopState.SKIPPED, auto = false)
        }
        dismissStopSheet()
    }

    /** Tap-sheet action: reopen a done/skipped stop back to pending. */
    fun reopenStop(stop: RunStop) {
        if (stop.state != RunStopState.PENDING) {
            suppressAutoComplete(stop.id)
            viewModelScope.launch {
                repository.setRunStopState(runId, stop.id, RunStopState.PENDING)
                reload()
            }
            _state.update { it.copy(lastActionLabel = "Reopened: ${stopLabel(stop)}") }
        }
        dismissStopSheet()
    }

    /** Tap-sheet action: make a pending stop the next target. */
    fun makeStopNext(stop: RunStop) {
        if (stop.state == RunStopState.PENDING) {
            viewModelScope.launch {
                repository.makeStopNext(runId, stop.id)
                reload()
            }
            _state.update { it.copy(lastActionLabel = "Next target: ${stopLabel(stop)}") }
        }
        dismissStopSheet()
    }

    private fun setStopState(stop: RunStop, newState: RunStopState, auto: Boolean) {
        if (auto) {
            if (completionInFlightStopId != null) return
            completionInFlightStopId = stop.id
        }
        undoStack.addLast(UndoEntry(stop.id, stop.state, stopLabel(stop)))
        val actionLabel = when {
            auto -> completionLabel(stop)
            newState == RunStopState.DONE -> "Marked done: ${stopLabel(stop)}"
            else -> "Skipped: ${stopLabel(stop)}"
        }
        viewModelScope.launch {
            try {
                repository.setRunStopState(runId, stop.id, newState)
                reload()
            } finally {
                if (completionInFlightStopId == stop.id) completionInFlightStopId = null
            }
        }
        _state.update {
            it.copy(
                undoCount       = undoStack.size,
                lastActionLabel = actionLabel
            )
        }
    }

    /** Undo the most recent state change (auto or manual, done or skip). */
    fun undoLast() {
        val entry = undoStack.removeLastOrNull() ?: return
        // Keep dwell from instantly re-confirming the stop we just reopened.
        suppressAutoComplete(entry.stopId)
        viewModelScope.launch {
            repository.setRunStopState(runId, entry.stopId, entry.prevState)
            reload()
        }
        _state.update {
            it.copy(
                undoCount       = undoStack.size,
                lastActionLabel = "Undid: ${entry.label}"
            )
        }
    }

    private fun onAllStopsResolved() {
        val skipped = _state.value.skippedCount
        if (skipped > 0 && !sweepDeclined) {
            _state.update { it.copy(showSweepPrompt = true) }
        } else {
            _state.update { it.copy(showSummary = true) }
        }
    }

    /** Sweep accepted: reopen skipped stops, replanned from current position. */
    fun acceptSweep() {
        val loc = _state.value.currentLocation
        viewModelScope.launch {
            repository.reactivateSkippedStops(
                runId,
                fromLat = loc?.latitude ?: _state.value.stops.firstOrNull()?.lat ?: 0.0,
                fromLon = loc?.longitude ?: _state.value.stops.firstOrNull()?.lon ?: 0.0
            )
            reload()
        }
        _state.update { it.copy(showSweepPrompt = false, lastActionLabel = "Skipped stops re-planned") }
    }

    fun declineSweep() {
        sweepDeclined = true
        _state.update { it.copy(showSweepPrompt = false, showSummary = true) }
    }

    fun dismissSummary() {
        _state.update { it.copy(showSummary = false) }
    }

    /** Finish the run: persist completion and signal the screen to exit. */
    fun finishRun() {
        viewModelScope.launch {
            repository.completeRun(runId)
            _state.update { it.copy(showSummary = false, finished = true) }
        }
    }

    fun exportLog() {
        viewModelScope.launch {
            val intent = repository.buildRunLogShareIntent(runId) ?: return@launch
            _state.update { it.copy(shareIntent = intent) }
        }
    }

    fun consumeShareIntent() = _state.update { it.copy(shareIntent = null) }

    /** Map tap: select the nearest stop within [PIN_TAP_RADIUS_M]. */
    fun onMapTap(lat: Double, lon: Double) {
        val nearest = _state.value.stops.minByOrNull {
            distanceCalc.haversineMetres(lat, lon, it.lat, it.lon)
        } ?: return
        val distM = distanceCalc.haversineMetres(lat, lon, nearest.lat, nearest.lon)
        if (distM <= PIN_TAP_RADIUS_M) {
            _state.update { it.copy(selectedStop = nearest) }
        }
    }

    fun dismissStopSheet() = _state.update { it.copy(selectedStop = null) }

    fun toggleStopList() = _state.update { it.copy(isStopListVisible = !it.isStopListVisible) }

    /** Whole-sheet dismissal of the all-stops sheet (also drops any open stop detail). */
    fun closeStopList() = _state.update { it.copy(isStopListVisible = false, selectedStop = null) }

    /** Row tap in the all-stops sheet: show the stop's actions inside the sheet. */
    fun selectStopFromList(stop: RunStop) =
        _state.update { it.copy(selectedStop = stop) }

    fun toggleChecklist() = _state.update { it.copy(showChecklist = !it.showChecklist) }

    /** Tick/untick one sign of the active stop's checklist (display-only aid). */
    fun toggleSignChecked(index: Int) {
        _state.update {
            val checks = if (index in it.checkedSigns) it.checkedSigns - index
                         else it.checkedSigns + index
            it.copy(checkedSigns = checks)
        }
    }

    fun onUserGesture() {
        manualBrowseLatch = true
        _state.update { it.copy(isFollowMode = false) }
    }

    fun showOverview() = onUserGesture()

    fun resumeFollow() {
        manualBrowseLatch = false
        _state.update { it.copy(isFollowMode = true) }
    }

    fun dismissActionLabel() = _state.update { it.copy(lastActionLabel = null) }

    fun formatDistance(metres: Double): String =
        distanceFormatter.formatCompact(metres, currentUnit)

    private fun stopLabel(stop: RunStop): String =
        stop.signs.joinToString(" · ") { "${it.courseName} ${it.label}" }
            .ifBlank { "Stop ${stop.stopIndex + 1}" }

    private fun completionLabel(stop: RunStop): String = if (stop.signs.size > 1) {
        "${stop.signs.size} signs completed"
    } else {
        "${stopLabel(stop)} completed"
    }

    private fun suppressAutoComplete(stopId: Long) {
        if (autoSuppressedStopIds.add(stopId)) persistSuppressedStops()
        if (_state.value.activeStop?.id == stopId) {
            dwellState = DwellEngine.State.IDLE
            _state.update { it.copy(autoCompleteStatus = AutoCompleteStatus.Canceled) }
        }
    }

    private fun persistSuppressedStops() {
        savedStateHandle[SUPPRESSED_STOP_IDS_KEY] = autoSuppressedStopIds.toLongArray()
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val firstLat = Math.toRadians(lat1)
        val secondLat = Math.toRadians(lat2)
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val y = Math.sin(longitudeDelta) * Math.cos(secondLat)
        val x = Math.cos(firstLat) * Math.sin(secondLat) -
            Math.sin(firstLat) * Math.cos(secondLat) * Math.cos(longitudeDelta)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }
}
