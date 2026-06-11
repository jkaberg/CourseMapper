package com.coursemapper.ui.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.PlacementPlanner
import com.coursemapper.domain.model.*
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.format.toDistanceInputOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

sealed interface WorkspaceAction {
    data object Idle : WorkspaceAction
    data class NavigateHome(val courseId: Long) : WorkspaceAction
    data class NavigateToNetwork(val networkId: Long) : WorkspaceAction
    data class NavigateToRunGate(val runId: Long) : WorkspaceAction
    data object Rerecord : WorkspaceAction
}

data class PendingNetworkData(val courseId: Long, val routeId: Long, val routeName: String)

data class RouteWarning(val message: String, val severity: Severity) {
    enum class Severity { INFO, WARNING, ERROR }
}

data class CourseWorkspaceUiState(
    val isLoading: Boolean = true,
    val isCreatingDraft: Boolean = false,

    // Persisted course data
    val course: ComposedCourse? = null,
    val baseRoute: BaseRoute? = null,
    val stops: List<PlacementStop> = emptyList(),
    /** Authoritative navigable geometry for this course (variant or composed). */
    val courseGeometry: List<RoutePoint> = emptyList(),
    /** The course's run once it has progress, see [RouteRepository.getCommittedSingleCourseRunId]. */
    val committedRunId: Long? = null,

    // Editable fields (bound to Essentials panel inputs)
    val editName: String = "",
    val editNotes: String = "",
    val editLapCount: Int = 1,
    val editTargetDistanceCm: Long = 0L,
    /** Raw text of the target-distance field, in the user's preferred unit. */
    val editTargetDistanceText: String = "",
    /** Unit the target field is entered/displayed in. */
    val distanceUnit: DistanceFormatter.DistanceUnit = DistanceFormatter.DistanceUnit.KM,
    /** -1L = keep current preset; null = no preset; else = switch */
    val editPresetId: Long? = -1L,
    /** Start/finish markers, read back from the course's rule snapshot. */
    val editIncludeStart: Boolean = false,
    val editIncludeFinish: Boolean = false,
    val availablePresets: List<MarkerPreset> = emptyList(),
    val compositionPreview: String = "",

    /** Measured length field text, always metres. */
    val editMeasuredLengthText: String = "",
    /** Parsed [editMeasuredLengthText]; 0.0 means "not measured". */
    val editMeasuredLengthMetres: Double = 0.0,
    /** Human-readable effect of the measurement, or "" when unmeasured. */
    val measuredLengthEffect: String = "",
    /** Set when the implied correction is too large to be drawing slop. */
    val measuredLengthWarning: String = "",

    // Quality warnings (shown for draft courses from recording)
    val warnings: List<RouteWarning> = emptyList(),
    val showWarnings: Boolean = false,

    // Marker list collapse state
    val showMarkers: Boolean = false,

    /** Opened from a finished recording and the lap question isn't settled yet. */
    val showRecordedLapPrompt: Boolean = false,

    /** Waiting for confirmation to recompose while a run with this course is in progress. */
    val pendingRecomposeConfirm: Boolean = false,

    // Async flags
    val isSaving: Boolean = false,
    val isPublishing: Boolean = false,
    /** Offline map offered after publishing, null when nothing is asked. */
    val offlinePrompt: RouteRepository.OfflinePackProposal? = null,
    val isStartingRun: Boolean = false,

    // Share intent
    val shareIntent: android.content.Intent? = null,
    val shareConsumed: Boolean = true,

    // Post-publish network dialog
    val pendingNetwork: PendingNetworkData? = null,

    // One-shot navigation events
    val action: WorkspaceAction = WorkspaceAction.Idle,

    // Variant breadcrumb
    val variantName: String? = null,
    val networkId: Long? = null
)

/**
 * Course workspace. [courseId] > 0 opens a course, [routeId] > 0 creates a
 * draft from a fresh recording first.
 */
@HiltViewModel
class CourseWorkspaceViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val placementPlanner: PlacementPlanner,
    private val userPreferences: UserPreferencesRepository,
    private val distanceCalc: CumulativeDistanceCalculator,
    private val compositionEngine: CourseCompositionEngine,
    private val distanceFormatter: DistanceFormatter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val courseIdArg: Long  = savedStateHandle["courseId"]         ?: -1L
    private val routeIdArg: Long   = savedStateHandle["routeId"]          ?: -1L
    private val presetIdArg: Long  = savedStateHandle["presetId"]         ?: -1L
    private val lapCountArg: Int   = savedStateHandle["lapCount"]         ?: 1
    private val targetDistArg: Long = savedStateHandle["targetDistanceCm"] ?: 0L
    private val includeStartArg: Boolean  = savedStateHandle["includeStart"]  ?: false
    private val includeFinishArg: Boolean = savedStateHandle["includeFinish"] ?: false

    private var resolvedCourseId: Long = courseIdArg

    private val _state = MutableStateFlow(CourseWorkspaceUiState())
    val uiState: StateFlow<CourseWorkspaceUiState> = _state.asStateFlow()

    private val distanceUnit: StateFlow<DistanceFormatter.DistanceUnit> =
        distanceFormatter.unitEnumFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, DistanceFormatter.DistanceUnit.KM)

    init {
        initWorkspace()
        // Rebuild the preview (and expose the unit) whenever the preference changes.
        viewModelScope.launch {
            distanceUnit.collect { unit ->
                val s = _state.value
                val baseDistM = s.baseRoute?.distanceMetres
                _state.update { st ->
                    st.copy(
                        distanceUnit = unit,
                        compositionPreview = if (baseDistM != null) {
                            buildCompositionPreview(baseDistM, st.editLapCount, st.editTargetDistanceCm)
                        } else st.compositionPreview
                    )
                }
            }
        }
    }

    private fun initWorkspace() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val courseId = when {
                courseIdArg > 0 -> courseIdArg
                routeIdArg > 0  -> {
                    _state.update { it.copy(isCreatingDraft = true) }
                    val id = repository.createDraftCourse(
                        routeId          = routeIdArg,
                        presetId         = presetIdArg,
                        lapCount         = lapCountArg,
                        targetDistanceCm = targetDistArg,
                        includeStart     = includeStartArg,
                        includeFinish    = includeFinishArg
                    )
                    _state.update { it.copy(isCreatingDraft = false) }
                    id
                }
                else -> -1L
            }

            resolvedCourseId = courseId
            loadCourse(courseId)

            // Only after a recording - a course opened from Home or from the
            // GPX wizard has already been through this decision.
            if (routeIdArg > 0 && courseIdArg <= 0) {
                _state.update { it.copy(showRecordedLapPrompt = true) }
            }
        }
    }

    fun dismissRecordedLapPrompt() = _state.update { it.copy(showRecordedLapPrompt = false) }

    private suspend fun loadCourse(courseId: Long) {
        val course = repository.getCourse(courseId)
        if (course == null) {
            _state.update { it.copy(isLoading = false) }
            return
        }

        val route   = repository.getRoute(course.baseRouteId)
        val stops          = repository.getStops(courseId)
        val committedRunId = repository.getCommittedSingleCourseRunId(courseId)
        val presets        = repository.observePresets().first()
        val courseGeometry = repository.getCourseGeometry(courseId)

        val warnings = if (course.isDraft && route != null) {
            buildWarnings(route, route.smoothedPoints.ifEmpty { route.rawPoints })
        } else emptyList()

        var variantName: String? = null
        var networkId: Long?     = null
        if (course.variantId != null) {
            val variant = repository.getVariant(course.variantId)
            variantName = variant?.name
            networkId   = variant?.networkId
        }

        val baseDistM = route?.distanceMetres ?: 0.0
        // Recover the original target distance from the persisted build spec - 
        // the stored intent, not arithmetic over possibly-stale lap columns.
        val targetDistanceCm = when (val spec = course.buildSpec) {
            is CourseBuildSpec.TargetDistance -> spec.totalMetres.targetMetresToCentimetres()
            else                              -> 0L
        }
        val preview = buildCompositionPreview(
            baseDistM, course.lapCount, targetDistanceCm,
            basePoints = route?.let { it.smoothedPoints.ifEmpty { it.rawPoints } }.orEmpty()
        )

        _state.update {
            it.copy(
                isLoading            = false,
                course               = course,
                baseRoute            = route,
                stops                = stops,
                committedRunId       = committedRunId,
                courseGeometry       = courseGeometry,
                warnings             = warnings,
                editName           = course.name,
                editNotes          = course.notes,
                editLapCount       = course.lapCount,
                editTargetDistanceCm = targetDistanceCm,
                editTargetDistanceText = formatTargetForEditing(targetDistanceCm),
                editPresetId       = course.presetSnapshot.sourcePresetId,
                editIncludeStart   = course.presetSnapshot.rules.hasEndpoint(MarkerType.START),
                editIncludeFinish  = course.presetSnapshot.rules.hasEndpoint(MarkerType.FINISH),
                availablePresets   = presets,
                compositionPreview = preview,
                variantName        = variantName,
                networkId          = networkId,
                editMeasuredLengthText =
                    if (course.isMeasured) formatMeasuredForEditing(course.measuredLengthMetres) else "",
                editMeasuredLengthMetres = course.measuredLengthMetres,
                measuredLengthEffect = measuredLengthEffect(
                    course.measuredLengthMetres, course.totalDistanceMetres
                ),
                measuredLengthWarning = measuredLengthWarning(
                    course.measuredLengthMetres, course.totalDistanceMetres
                )
            )
        }

        ensureStopsPlanned(course, stops)
    }

    fun onNameChange(value: String)  = _state.update { it.copy(editName = value) }
    fun onNotesChange(value: String) = _state.update { it.copy(editNotes = value) }

    fun onLapCountChange(count: Int) {
        val base = _state.value.baseRoute?.distanceMetres ?: 0.0
        val newCount = count.coerceAtLeast(1)
        _state.update {
            it.copy(
                editLapCount       = newCount,
                compositionPreview = buildCompositionPreview(base, newCount, it.editTargetDistanceCm)
            )
        }
    }

    fun onTargetDistanceChange(value: String) {
        // Typed in the user's preferred unit; canonical storage is centimetres.
        val unit = distanceUnit.value
        val cm = ((value.toDistanceInputOrNull() ?: 0.0) * unit.metresPerUnit).targetMetresToCentimetres()
        val base = _state.value.baseRoute?.distanceMetres ?: 0.0
        _state.update {
            it.copy(
                editTargetDistanceCm   = cm,
                editTargetDistanceText = value,
                compositionPreview     = buildCompositionPreview(base, it.editLapCount, cm)
            )
        }
    }

    /** Race distance chip in centimetres, 0 clears. Goes through the same text field. */
    fun onTargetDistancePreset(targetDistanceCm: Long) =
        onTargetDistanceChange(formatTargetForEditing(targetDistanceCm))

    /** Typed into the measured-length field, always in metres. Blank clears it. */
    fun onMeasuredLengthChange(value: String) {
        val metres = value.toDistanceInputOrNull()?.coerceAtLeast(0.0) ?: 0.0
        val drawn = _state.value.course?.totalDistanceMetres ?: 0.0
        _state.update {
            it.copy(
                editMeasuredLengthText   = value,
                editMeasuredLengthMetres = metres,
                measuredLengthEffect     = measuredLengthEffect(metres, drawn),
                measuredLengthWarning    = measuredLengthWarning(metres, drawn)
            )
        }
    }

    /** Apply a tapped race-distance chip, in metres; 0 clears the measurement. */
    fun onMeasuredLengthPreset(metres: Double) =
        onMeasuredLengthChange(if (metres > 0.0) formatMeasuredForEditing(metres) else "")

    /** Stored measurement as edit text, 42195 not 42195.0 but 21097.5 stays. */
    private fun formatMeasuredForEditing(metres: Double): String =
        if (metres % 1.0 == 0.0) metres.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", metres)

    /** What the measurement does, always naming both lengths. */
    private fun measuredLengthEffect(measuredMetres: Double, drawnMetres: Double): String {
        if (measuredMetres <= 0.0 || drawnMetres <= 0.0) return ""
        val scale = drawnMetres / measuredMetres
        val percent = (scale - 1.0) * 100.0
        val biggestShift = measuredMetres * (scale - 1.0)
        return buildString {
            append("Drawn line %.0f m · measured %.0f m".format(drawnMetres, measuredMetres))
            append("\nMarkers corrected by %+.2f %%".format(percent))
            if (kotlin.math.abs(biggestShift) >= 1.0) {
                append(" — signs move up to %.0f m along the course.".format(kotlin.math.abs(biggestShift)))
            } else {
                append(".")
            }
            append("\nStart and finish do not move.")
        }
    }

    /**
     * Warn when the correction is too big to be corner rounding. The half and
     * marathon are at 1.08 %, the 5 km (4.17 %) and 10 km (2.37 %) have wrong
     * endpoints instead. 2 % is a judgement call.
     */
    private fun measuredLengthWarning(measuredMetres: Double, drawnMetres: Double): String {
        if (measuredMetres <= 0.0 || drawnMetres <= 0.0) return ""
        val scale = drawnMetres / measuredMetres
        return when {
            scale > MEASURED_LENGTH_MAX_SCALE || scale < MEASURED_LENGTH_MIN_SCALE ->
                "The drawn line is %.1f %% off this measurement. That is too far apart to be a measurement question — check that this is the right course and the right figure."
                    .format(kotlin.math.abs(scale - 1.0) * 100.0)
            scale > MEASURED_LENGTH_WARN_SCALE || scale < 1.0 / MEASURED_LENGTH_WARN_SCALE ->
                ("The drawn line is %.1f %% longer than this measurement — more than drawing slop. " +
                    "Check the start and finish first: correcting a misplaced endpoint by scaling " +
                    "spreads that error over every marker instead of removing it.")
                    .format((scale - 1.0) * 100.0)
            else -> ""
        }
    }

    /** Stored target as edit text. Four decimals, 21.0975 rounded to 21.10 adds 2.5 m. */
    private fun formatTargetForEditing(targetDistanceCm: Long): String {
        if (targetDistanceCm <= 0L) return ""
        val units = targetDistanceCm.targetCentimetresToMetres() / distanceUnit.value.metresPerUnit
        if (abs(units - units.roundToLong()) < 1e-6) return units.roundToLong().toString()
        // Locale.US, not the default locale: this text goes straight back into
        // the edit field, and toDoubleOrNull only understands a '.' separator - 
        // a comma-decimal locale would render a target the app cannot re-read.
        return String.format(Locale.US, "%.4f", units).trimEnd('0').trimEnd('.')
    }

    fun onIncludeStartChange(value: Boolean) =
        _state.update { it.copy(editIncludeStart = value) }

    fun onIncludeFinishChange(value: Boolean) =
        _state.update { it.copy(editIncludeFinish = value) }

    /** Switch preset and reseed the endpoint toggles from it. -1L keeps the current one. */
    fun onPresetChange(presetId: Long?) = _state.update { state ->
        if (presetId == -1L) return@update state.copy(editPresetId = presetId)
        val rules = presetId
            ?.let { id -> state.availablePresets.firstOrNull { it.id == id }?.rules }
            .orEmpty()
        state.copy(
            editPresetId      = presetId,
            editIncludeStart  = rules.hasEndpoint(MarkerType.START),
            editIncludeFinish = rules.hasEndpoint(MarkerType.FINISH)
        )
    }

    fun toggleWarnings() = _state.update { it.copy(showWarnings = !it.showWarnings) }

    /** Create or resume the single-course run and go to the readiness gate. */
    fun startRun() {
        if (_state.value.isStartingRun) return
        viewModelScope.launch {
            _state.update { it.copy(isStartingRun = true) }
            val runId = repository.getOrCreateSingleCourseRun(resolvedCourseId)
            _state.update {
                it.copy(
                    isStartingRun = false,
                    action = if (runId > 0) WorkspaceAction.NavigateToRunGate(runId) else it.action
                )
            }
        }
    }

    fun toggleMarkers() = _state.update { it.copy(showMarkers = !it.showMarkers) }

    fun deleteMarker(markerId: Long) {
        viewModelScope.launch {
            repository.deleteMarker(markerId)
            _state.update { s ->
                s.copy(course = s.course?.copy(
                    markers = s.course.markers.filter { it.id != markerId }
                ))
            }
        }
    }

    fun saveChanges() {
        val s = _state.value
        if (s.editName.isBlank()) return
        viewModelScope.launch {
            // Changing the composition rebuilds markers and stops.  A run that
            // is already under way holds its own snapshot of the old stops and
            // will not pick the change up, so confirm before stranding it.
            if (changesComposition(s) && repository.hasRunInProgress(resolvedCourseId)) {
                _state.update { it.copy(pendingRecomposeConfirm = true) }
                return@launch
            }
            applyChanges(s)
        }
    }

    /** Proceed with a composition change that will strand an in-progress run. */
    fun confirmRecomposeAndSave() {
        val s = _state.value
        _state.update { it.copy(pendingRecomposeConfirm = false) }
        if (s.editName.isBlank()) return
        viewModelScope.launch { applyChanges(s) }
    }

    fun dismissRecomposeConfirm() = _state.update { it.copy(pendingRecomposeConfirm = false) }

    private suspend fun applyChanges(s: CourseWorkspaceUiState) {
        _state.update { it.copy(isSaving = true) }
        repository.updateCourse(
            courseId            = resolvedCourseId,
            newName             = s.editName.trim(),
            newNotes            = s.editNotes.trim(),
            newLapCount         = s.editLapCount,
            newTargetDistanceCm = s.editTargetDistanceCm,
            newPresetId         = s.editPresetId,
            includeStart        = s.editIncludeStart,
            includeFinish       = s.editIncludeFinish,
            newMeasuredLengthMetres = s.editMeasuredLengthMetres
        )
        _state.update { it.copy(isSaving = false) }
        loadCourse(resolvedCourseId)
    }

    /** Whether the edits change geometry or markers. Name and notes don't. */
    private fun changesComposition(s: CourseWorkspaceUiState): Boolean {
        val course = s.course ?: return false
        val loadedTargetCm = when (val spec = course.buildSpec) {
            is CourseBuildSpec.TargetDistance -> spec.totalMetres.targetMetresToCentimetres()
            else                              -> 0L
        }
        val presetChanged = s.editPresetId != -1L &&
            s.editPresetId != course.presetSnapshot.sourcePresetId
        // Toggling an endpoint adds or removes a marker, and therefore a
        // placement stop - as much a composition change as a lap count.
        val endpointsChanged =
            s.editIncludeStart != course.presetSnapshot.rules.hasEndpoint(MarkerType.START) ||
            s.editIncludeFinish != course.presetSnapshot.rules.hasEndpoint(MarkerType.FINISH)
        // A measurement change re-places every marker and therefore every stop,
        // exactly as a lap change does - a run already under way would be left
        // holding the old positions.
        val measuredChanged =
            kotlin.math.abs(s.editMeasuredLengthMetres - course.measuredLengthMetres) > 0.001
        return s.editLapCount != course.lapCount ||
            s.editTargetDistanceCm != loadedTargetCm ||
            presetChanged ||
            endpointsChanged ||
            measuredChanged
    }

    fun publish() {
        viewModelScope.launch {
            _state.update { it.copy(isPublishing = true) }
            // Persist pending edits only if something actually changed - 
            // updateCourse regenerates ALL markers, which would silently undo
            // any manual marker deletions when the organiser just taps Publish.
            val s = _state.value
            if (s.editName.isNotBlank() && hasUnsavedEdits(s)) {
                repository.updateCourse(
                    courseId            = resolvedCourseId,
                    newName             = s.editName.trim(),
                    newNotes            = s.editNotes.trim(),
                    newLapCount         = s.editLapCount,
                    newTargetDistanceCm = s.editTargetDistanceCm,
                    newPresetId         = s.editPresetId,
                    // Omitting these would leave the profile's own endpoints in
                    // place, silently discarding a toggle the organiser flipped
                    // just before tapping Publish.
                    includeStart        = s.editIncludeStart,
                    includeFinish       = s.editIncludeFinish,
                    newMeasuredLengthMetres = s.editMeasuredLengthMetres
                )
            }
            repository.publishCourse(resolvedCourseId)
            val course = repository.getCourse(resolvedCourseId)
            // Publishing is when this course becomes something to drive, and
            // the user is at a desk rather than at the trailhead - so this is
            // where the offline map is offered.
            val offlinePrompt = repository.proposeOfflinePackOnCreate(listOf(resolvedCourseId))
            _state.update {
                it.copy(
                    isPublishing   = false,
                    offlinePrompt  = offlinePrompt,
                    pendingNetwork = course?.let { c ->
                        PendingNetworkData(c.id, c.baseRouteId, c.name)
                    }
                )
            }
        }
    }

    fun acceptOfflineDownload(alwaysFromNowOn: Boolean) {
        val proposal = _state.value.offlinePrompt ?: return
        _state.update { it.copy(offlinePrompt = null) }
        viewModelScope.launch {
            if (alwaysFromNowOn) {
                userPreferences.setOfflineDownloadPolicy(OfflineDownloadPolicy.ALWAYS)
            }
            repository.startOfflineDownload(proposal)
        }
    }

    /** Remembered against the area, so the next course here does not re-ask. */
    fun declineOfflineDownload() {
        val proposal = _state.value.offlinePrompt ?: return
        _state.update { it.copy(offlinePrompt = null) }
        viewModelScope.launch { repository.declineOfflineDownload(proposal) }
    }

    fun dismissOfflinePrompt() = _state.update { it.copy(offlinePrompt = null) }

    /** True when any Essentials field differs from the loaded course. */
    private fun hasUnsavedEdits(s: CourseWorkspaceUiState): Boolean {
        val course = s.course ?: return false
        return s.editName.trim() != course.name ||
            s.editNotes.trim() != course.notes ||
            changesComposition(s)
    }

    fun createNetworkAndNavigate() {
        val pending = _state.value.pendingNetwork ?: return
        viewModelScope.launch {
            val networkId = repository.createNetwork(pending.routeId, pending.routeName)
            _state.update {
                it.copy(
                    pendingNetwork = null,
                    action         = WorkspaceAction.NavigateToNetwork(networkId)
                )
            }
        }
    }

    fun skipNetworkCreation() {
        val pending = _state.value.pendingNetwork ?: return
        _state.update {
            it.copy(
                pendingNetwork = null,
                action         = WorkspaceAction.NavigateHome(pending.courseId)
            )
        }
    }

    fun requestRerecord() = _state.update { it.copy(action = WorkspaceAction.Rerecord) }

    /** Repair for published courses from before stops were always computed. */
    private suspend fun ensureStopsPlanned(course: ComposedCourse, existing: List<PlacementStop>) {
        if (course.isDraft || course.markers.isEmpty() || existing.isNotEmpty()) return
        val threshold = userPreferences.clusteringThresholdMetres.first()
        val result = placementPlanner.plan(
            markers = course.markers,
            courseId = course.id,
            groupingThresholdMetres = threshold
        )
        repository.savePlacementStops(course.id, result.stops)
        _state.update { it.copy(stops = result.stops) }
    }

    fun shareRoute() {
        if (_state.value.course == null) return
        viewModelScope.launch {
            // Export the same geometry shown on the workspace map - including
            // variant polylines and partial final laps.
            val geometry = repository.getCourseGeometry(resolvedCourseId)
            if (geometry.isEmpty()) return@launch
            val intent = repository.buildCourseShareIntent(resolvedCourseId, geometry)
            _state.update { it.copy(shareIntent = intent, shareConsumed = false) }
        }
    }

    fun consumeShareIntent() = _state.update { it.copy(shareConsumed = true) }

    private fun buildCompositionPreview(
        baseDistanceMetres: Double,
        lapCount: Int,
        targetDistanceCm: Long,
        basePoints: List<RoutePoint> = _state.value.baseRoute
            ?.let { it.smoothedPoints.ifEmpty { it.rawPoints } }
            .orEmpty()
    ): String {
        val unit = distanceUnit.value
        val targetMetres = targetDistanceCm.targetCentimetresToMetres()
        val baseDisplay  = distanceFormatter.formatCompact(baseDistanceMetres, unit)

        // Preview by running the real composition so the text always matches
        // what Save will produce, including extra full laps and the partial.
        if (basePoints.size >= 2) {
            val spec: CourseBuildSpec = if (targetMetres > 0.0) {
                CourseBuildSpec.TargetDistance(targetMetres, lapCount.coerceAtLeast(1))
            } else {
                CourseBuildSpec.FixedLaps(lapCount.coerceAtLeast(1))
            }
            // summarize, not compose: this runs on every keystroke and every tap
            // of the lap stepper, and the composed polyline would be built only
            // to be thrown away.
            val composition  = compositionEngine.summarize(basePoints, spec)
            val fullLaps     = composition.fullLapCount
            val partialM     = composition.partialDistanceMetres
            val totalDisplay = distanceFormatter.formatCompact(composition.totalDistanceMetres, unit)
            val partialPart  = if (partialM > 0.0) " + ${distanceFormatter.formatCompact(partialM, unit)} partial" else ""
            var text = "$fullLaps full lap(s) of $baseDisplay$partialPart = $totalDisplay total"
            if (targetMetres > 0.0 && composition.totalDistanceMetres > targetMetres + 1.0) {
                val targetDisplay = distanceFormatter.formatCompact(targetMetres, unit)
                text += "\n⚠ Target $targetDisplay is shorter than $lapCount full lap(s) — the course will be $totalDisplay."
            }
            // Repeating a route joins its end back to its start.  A large gap
            // there means the base route is probably point-to-point, and every
            // lap will jump straight across ground nobody recorded.
            val closure = composition.loopClosure
            if (closure is CourseCompositionEngine.LoopClosure.SuspectOpenRoute) {
                val gapDisplay = distanceFormatter.formatCompact(closure.gapMetres, unit)
                text += "\n⚠ This route does not close — its end is $gapDisplay from its start. " +
                        "Each lap crosses that gap in a straight line. Use one lap, or re-record " +
                        "the route finishing where it began."
            }
            return text
        }

        return when {
            targetMetres > 0.0 -> {
                val targetDisplay = distanceFormatter.formatCompact(targetMetres, unit)
                "Targeting $targetDisplay — full laps + final partial computed from the base route"
            }
            lapCount == 1  -> "1 full lap of $baseDisplay base route"
            else           -> {
                val totalDisplay = distanceFormatter.formatCompact(baseDistanceMetres * lapCount, unit)
                "%d laps × $baseDisplay = $totalDisplay total".format(lapCount)
            }
        }
    }

    private fun buildWarnings(
        route: BaseRoute,
        smoothed: List<RoutePoint>
    ): List<RouteWarning> = buildList {
        val raw = route.rawPoints
        if (raw.isEmpty()) {
            add(RouteWarning("No GPS points recorded.", RouteWarning.Severity.ERROR))
            return@buildList
        }
        if (smoothed.size < 2) {
            add(RouteWarning(
                "Too few quality points — most fixes were rejected. Try re-recording in clearer conditions.",
                RouteWarning.Severity.WARNING
            ))
        }
        if (route.distanceMetres < 100.0) {
            add(RouteWarning(
                "Route is very short (%.0f m). Did you record the full course?".format(route.distanceMetres),
                RouteWarning.Severity.WARNING
            ))
        }
        val poorCount = raw.count { (it.accuracyMetres ?: 0f) > 30f }
        if (poorCount > raw.size / 4) {
            add(RouteWarning(
                "$poorCount of ${raw.size} GPS fixes had accuracy worse than 30 m. Some segments may be imprecise.",
                RouteWarning.Severity.INFO
            ))
        }
        val acceptedRatio = smoothed.size.toDouble() / raw.size.coerceAtLeast(1)
        if (acceptedRatio < 0.6) {
            add(RouteWarning(
                "Only ${(acceptedRatio * 100).toInt()}% of recorded points passed quality filters.",
                RouteWarning.Severity.WARNING
            ))
        }
    }

    companion object {
        /** Above this drawn-to-measured ratio, warn - see [measuredLengthWarning]. */
        const val MEASURED_LENGTH_WARN_SCALE = 1.02

        /** Outside this the two lengths aren't the same course. Still saved, but warned about. */
        const val MEASURED_LENGTH_MAX_SCALE = 1.25
        const val MEASURED_LENGTH_MIN_SCALE = 0.80

        /** Race distances offered as chips beside the measured-length field, in metres. */
        val MEASURED_LENGTH_PRESETS = listOf(
            "5 km" to 5_000.0,
            "10 km" to 10_000.0,
            "Half" to 21_097.5,
            "Marathon" to 42_195.0
        )
    }
}
