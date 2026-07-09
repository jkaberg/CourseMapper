package com.coursemapper.ui.run

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.hasEndpoint
import com.coursemapper.domain.model.targetCentimetresToMetres
import com.coursemapper.gpx.GpxImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One parsed GPX track row in the wizard. */
data class WizardTrack(
    val track: GpxImporter.ImportResult,
    val included: Boolean = true,
    val name: String,
    /** -1 = no preset (markerless course). */
    val presetId: Long = -1L,
    /** Start/finish marker, seeded from the profile and overridable per track. */
    val includeStart: Boolean = false,
    val includeFinish: Boolean = false,
    /** Track length, computed once at parse time. */
    val distanceMetres: Double = 0.0,
    /** Laps of this track, a GPX file usually holds one lap of a multi-lap race. */
    val lapCount: Int = 1,
    /** Target total distance in centimetres; 0 = use exactly [lapCount] laps. */
    val targetDistanceCm: Long = 0L,

    // composition result from CourseCompositionEngine.summarize, in metres so
    // the row renders in the display unit

    /** Full laps the engine will lay down; may exceed [lapCount] in target mode. */
    val composedFullLaps: Int = 1,
    /** One lap's length including the closing leg when the seam is closed. */
    val composedLapMetres: Double = 0.0,
    /** Trailing partial lap; 0.0 when there is none. */
    val composedPartialMetres: Double = 0.0,
    /** Composed total; equals [distanceMetres] for a single unrepeated lap. */
    val composedDistanceMetres: Double = 0.0,
    /** Set when repeating the track would jump a gap, probably point to point. */
    val openRouteGapMetres: Double? = null
) {
    /** True when this row composes more than one lap of the base track. */
    val isMultiLap: Boolean get() = lapCount > 1 || targetDistanceCm > 0L
}

/** What one pass of the wizard created. */
data class EventCreationResult(
    /** Every member course: the newly imported ones plus any existing selections. */
    val courseIds: List<Long>,
    /** The combined course created over them, or null when there was only one. */
    val groupId: Long?
)

data class EventWizardUiState(
    val isLoading: Boolean = false,
    val tracks: List<WizardTrack> = emptyList(),
    val presets: List<MarkerPreset> = emptyList(),
    /** Name for the combined course created over all selected routes. */
    val eventName: String = "",
    /** Published courses that can be included alongside the new imports. */
    val existingCourses: List<com.coursemapper.domain.model.ComposedCourse> = emptyList(),
    val selectedExistingIds: Set<Long> = emptySet(),
    val isCreating: Boolean = false,
    /** Awaiting confirmation that repeating a non-closing track is intended. */
    val pendingOpenLoopConfirm: Boolean = false,
    val error: String? = null,
    /** What creation produced. Group id is set when there's more than one course. */
    val created: EventCreationResult? = null,
    /** One offline prompt for the whole import, the courses share ground. */
    val offlinePrompt: RouteRepository.OfflinePackProposal? = null
) {
    val includedTrackCount: Int get() = tracks.count { it.included }
    val canCreate: Boolean get() =
        (includedTrackCount > 0 || selectedExistingIds.isNotEmpty()) && !isCreating &&
            tracks.filter { it.included }.all { it.name.isNotBlank() }

    /** Included rows repeating a track that doesn't close, confirmed before creating. */
    val openLoopTracks: List<WizardTrack> get() =
        tracks.filter { it.included && it.openRouteGapMetres != null }
}

/**
 * Event wizard: pick GPX files, every track becomes a row with include, name and
 * profile. Create publishes a course per track (approved, markers placed, stops
 * planned) and groups them when there's more than one. No workspace review,
 * markers follow from the profile and the workspace is there for later edits.
 */
@HiltViewModel
class EventWizardViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val distanceCalc: CumulativeDistanceCalculator,
    private val compositionEngine: CourseCompositionEngine,
    private val userPreferences: UserPreferencesRepository
) : ViewModel() {

    private val _state = MutableStateFlow(EventWizardUiState())
    val uiState: StateFlow<EventWizardUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observePresets().collect { presets ->
                _state.update { state ->
                    state.copy(
                        presets = presets,
                        // Default new rows to the first profile once presets
                        // load, seeding their endpoints from it too.
                        tracks = state.tracks.map { row ->
                            if (row.presetId == -1L && presets.isNotEmpty()) {
                                row.withPresetEndpoints(presets.first())
                            } else row
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeCourses().collect { courses ->
                _state.update { state ->
                    state.copy(existingCourses = courses.filter { !it.isDraft && it.markers.isNotEmpty() })
                }
            }
        }
    }

    fun onEventNameChange(value: String) = _state.update { it.copy(eventName = value) }

    fun toggleExistingCourse(courseId: Long) = _state.update { state ->
        state.copy(
            selectedExistingIds = if (courseId in state.selectedExistingIds) {
                state.selectedExistingIds - courseId
            } else {
                state.selectedExistingIds + courseId
            }
        )
    }

    /** Parse [uris] and append every contained track as a wizard row. */
    fun loadGpxFiles(uris: List<Uri>, contentResolver: ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val newRows = mutableListOf<WizardTrack>()
            var failure: String? = null
            for (uri in uris) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val defaultPreset = _state.value.presets.firstOrNull()
                        repository.previewGpx(stream).forEach { result ->
                            newRows.add(
                                recompose(
                                    WizardTrack(
                                        track          = result,
                                        name           = result.route.name,
                                        distanceMetres = distanceCalc.pathDistanceMetres(
                                            result.points, smoothedOnly = false
                                        )
                                    ).withPresetEndpoints(defaultPreset)
                                )
                            )
                        }
                    } ?: run { failure = "Could not open one of the files." }
                } catch (e: Exception) {
                    failure = "Failed to parse GPX: ${e.message}"
                }
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    tracks    = it.tracks + newRows,
                    error     = when {
                        failure != null -> failure
                        newRows.isEmpty() && it.tracks.isEmpty() -> "No tracks found in the selected file(s)."
                        else -> null
                    }
                )
            }
        }
    }

    fun toggleTrack(index: Int) = updateRow(index) { it.copy(included = !it.included) }

    fun renameTrack(index: Int, name: String) = updateRow(index) { it.copy(name = name) }

    /** Choose this track's profile, re-seeding its endpoint toggles from it. */
    fun setTrackPreset(index: Int, presetId: Long) = updateRow(index) { row ->
        row.withPresetEndpoints(_state.value.presets.firstOrNull { it.id == presetId }, presetId)
    }

    fun setTrackIncludeStart(index: Int, value: Boolean) =
        updateRow(index) { it.copy(includeStart = value) }

    fun setTrackIncludeFinish(index: Int, value: Boolean) =
        updateRow(index) { it.copy(includeFinish = value) }

    /** Set the number of full laps of this track to compose (clamped to ≥ 1). */
    fun setTrackLaps(index: Int, lapCount: Int) = updateRow(index) {
        it.copy(lapCount = lapCount.coerceAtLeast(1))
    }

    /** Target distance for a track in centimetres, 0 goes back to plain laps. */
    fun setTrackTarget(index: Int, targetDistanceCm: Long) = updateRow(index) {
        it.copy(targetDistanceCm = targetDistanceCm.coerceAtLeast(0L))
    }

    /** Use [preset]'s endpoint rules as toggles. [presetId] is explicit so -1 (no markers) is kept. */
    private fun WizardTrack.withPresetEndpoints(
        preset: MarkerPreset?,
        presetId: Long = preset?.id ?: -1L
    ): WizardTrack = copy(
        presetId      = presetId,
        includeStart  = preset?.rules.orEmpty().hasEndpoint(MarkerType.START),
        includeFinish = preset?.rules.orEmpty().hasEndpoint(MarkerType.FINISH)
    )

    private fun updateRow(index: Int, transform: (WizardTrack) -> WizardTrack) {
        _state.update { state ->
            if (index !in state.tracks.indices) return@update state
            state.copy(
                tracks = state.tracks.mapIndexed { i, row ->
                    if (i == index) recompose(transform(row)) else row
                }
            )
        }
    }

    /** Recompute a row's composition. Runs on every edit, summarize is cheap. */
    private fun recompose(row: WizardTrack): WizardTrack {
        val targetMetres = row.targetDistanceCm.targetCentimetresToMetres()
        val spec: CourseBuildSpec = if (targetMetres > 0.0) {
            CourseBuildSpec.TargetDistance(totalMetres = targetMetres, lapCount = row.lapCount)
        } else {
            CourseBuildSpec.FixedLaps(row.lapCount)
        }
        val summary = compositionEngine.summarize(row.track.points, spec)

        // Only warn when the gap actually gets crossed: a point-to-point track
        // imported as a single lap is perfectly normal.
        val gap = (summary.loopClosure as? CourseCompositionEngine.LoopClosure.SuspectOpenRoute)
            ?.gapMetres

        return row.copy(
            composedFullLaps      = summary.fullLapCount,
            composedLapMetres     = summary.lapDistanceMetres,
            composedPartialMetres = summary.partialDistanceMetres,
            composedDistanceMetres = summary.totalDistanceMetres,
            openRouteGapMetres    = gap
        )
    }

    /**
     * Publish a course per included track and group them (with any selected
     * existing courses) when there's more than one.
     */
    fun createCourses() {
        val s = _state.value
        if (!s.canCreate) return
        // Courses here are published without a workspace review step, so a
        // route that will visibly jump across un-recorded ground on every lap
        // is confirmed before it is built, not discovered afterwards.
        if (s.openLoopTracks.isNotEmpty()) {
            _state.update { it.copy(pendingOpenLoopConfirm = true) }
            return
        }
        doCreate()
    }

    /** Proceed with creation despite the open-loop warning. */
    fun confirmOpenLoopAndCreate() {
        _state.update { it.copy(pendingOpenLoopConfirm = false) }
        doCreate()
    }

    fun dismissOpenLoopConfirm() = _state.update { it.copy(pendingOpenLoopConfirm = false) }

    private fun doCreate() {
        val s = _state.value
        if (!s.canCreate) return
        val rows = s.tracks.filter { it.included }
        viewModelScope.launch {
            _state.update { it.copy(isCreating = true, error = null) }
            try {
                val newIds = rows.map { row ->
                    val courseId = repository.importGpxTrackAsDraft(
                        result           = row.track,
                        name             = row.name.trim(),
                        notes            = "",
                        presetId         = row.presetId,
                        lapCount         = row.lapCount,
                        targetDistanceCm = row.targetDistanceCm,
                        includeStart     = row.includeStart,
                        includeFinish    = row.includeFinish
                    )
                    repository.publishCourse(courseId)
                    courseId
                }
                val allIds = newIds + s.selectedExistingIds
                val groupId = if (allIds.size > 1) {
                    repository.createCourseGroup(
                        name = s.eventName.ifBlank { "Event (${allIds.size} routes)" },
                        courseIds = allIds
                    ).takeIf { it > 0 }
                } else null
                val result = EventCreationResult(allIds, groupId)

                // offer offline maps now, while still at home on wifi
                val proposal = repository.proposeOfflinePackOnCreate(allIds)
                if (proposal != null) {
                    // Navigation waits for an answer, so the dialog is not left
                    // behind on a screen the user has already left.
                    pendingResult = result
                    _state.update { it.copy(isCreating = false, offlinePrompt = proposal) }
                } else {
                    _state.update { it.copy(isCreating = false, created = result) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isCreating = false, error = "Import failed: ${e.message}") }
            }
        }
    }

    /** Held while the offline prompt is up; released as [EventWizardUiState.created]. */
    private var pendingResult: EventCreationResult? = null

    /** Start the offered download, optionally never asking again. */
    fun acceptOfflineDownload(alwaysFromNowOn: Boolean) {
        val proposal = _state.value.offlinePrompt ?: return
        _state.update { it.copy(offlinePrompt = null) }
        viewModelScope.launch {
            if (alwaysFromNowOn) {
                userPreferences.setOfflineDownloadPolicy(OfflineDownloadPolicy.ALWAYS)
            }
            repository.startOfflineDownload(proposal)
            releasePendingResult()
        }
    }

    /** Decline the download, remembered for the area so the same venue isn't asked again. */
    fun declineOfflineDownload() {
        val proposal = _state.value.offlinePrompt ?: return
        _state.update { it.copy(offlinePrompt = null) }
        viewModelScope.launch {
            repository.declineOfflineDownload(proposal)
            releasePendingResult()
        }
    }

    /** Dismissed without answering - ask again next time, and carry on. */
    fun dismissOfflinePrompt() {
        _state.update { it.copy(offlinePrompt = null) }
        releasePendingResult()
    }

    private fun releasePendingResult() {
        pendingResult?.let { result ->
            pendingResult = null
            _state.update { it.copy(created = result) }
        }
    }

    fun consumeCreated() = _state.update { it.copy(created = null) }
}
