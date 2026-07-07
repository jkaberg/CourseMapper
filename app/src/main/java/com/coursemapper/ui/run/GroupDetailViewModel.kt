package com.coursemapper.ui.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.offline.OfflineCoverage
import com.coursemapper.ui.format.DistanceFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the launch button does, and what it should say. */
enum class GroupRunAction {
    /** No run has ever been launched from this group - create one with defaults. */
    START_FIRST,
    /** A previous run exists and finished - create a new one with its settings. */
    START_AGAIN,
    /** A run from this group is unfinished - pick it up where it was left. */
    RESUME
}

data class GroupDetailUiState(
    val group: CourseGroup? = null,
    val courses: List<ComposedCourse> = emptyList(),
    val courseLines: List<RunCourseLine> = emptyList(),
    /** Stretches carrying more than one course, for the low-zoom shared casing. */
    val sharedCorridors: List<CourseDisplayPlanner.SharedCorridor> = emptyList(),
    /** Cross-course stop preview (clustered, with sign counts). */
    val preview: RouteRepository.RunPreview? = null,
    val isLoading: Boolean = true,

    val ordering: RunOrdering = RunOrdering.SPINE,
    val travelProfile: TravelProfile = TravelProfile.CAR,
    val runAction: GroupRunAction = GroupRunAction.START_FIRST,
    /** Set when [runAction] is RESUME - the run the button picks up. */
    val resumableRunId: Long? = null,
    /** True while the inline ordering / travel-mode controls are expanded. */
    val optionsExpanded: Boolean = false,
    val isStartingRun: Boolean = false,
    /** Set once a run is ready; the screen consumes it and opens the gate. */
    val startedRunId: Long? = null,

    /** Worst offline state across the members, null until observed. */
    val offlineCoverage: OfflineCoverage? = null,

    val confirmDelete: Boolean = false,
    /** Set after deletion; triggers navigation back. */
    val deleted: Boolean = false,
    val distanceUnit: DistanceFormatter.DistanceUnit = DistanceFormatter.DistanceUnit.KM
) {
    /** Planned drive for the currently-chosen ordering, metres. */
    val plannedMetres: Double?
        get() = preview?.let {
            if (ordering == RunOrdering.OPTIMIZED) it.optimizedMetres else it.spineMetres
        }

    val canStart: Boolean get() = !isStartingRun && courses.any { it.markers.isNotEmpty() }
}

/**
 * Group detail: all routes on one map, the one-pass stops, and run launch.
 * Runs remember which group they came from, so ordering, profile and spine
 * default to last time.
 */
@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val distanceFormatter: DistanceFormatter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val groupId: Long = savedStateHandle["groupId"] ?: -1L

    private val _state = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _state.asStateFlow()

    init { load() }

    /** (Re)load group, map and run history. Public since editing members changes all of it. */
    fun load() {
        viewModelScope.launch {
            val unit = distanceFormatter.unitEnumFlow.first()
            val group = repository.getCourseGroup(groupId)
            if (group == null) {
                _state.update { it.copy(isLoading = false, distanceUnit = unit) }
                return@launch
            }
            // Membership is already joined against live courses by the DAO, so
            // a soft-deleted member cannot reach previewRun or createRun here.
            val courses = group.courseIds.mapNotNull { repository.getCourse(it) }
            val display = repository.getCourseDisplayLines(group.courseIds)
            val preview = repository.previewRun(group.courseIds)

            val unfinished = repository.getUnfinishedRunForGroup(groupId)
            val latest     = unfinished ?: repository.getLatestRunForGroup(groupId)

            _state.update {
                it.copy(
                    group           = group,
                    courses         = courses,
                    courseLines     = display.toRunCourseLines(),
                    sharedCorridors = display.sharedCorridors,
                    preview         = preview,
                    isLoading       = false,
                    distanceUnit    = unit,
                    // Last outing's settings, or the defaults on the first one.
                    ordering        = latest?.ordering ?: RunOrdering.SPINE,
                    travelProfile   = latest?.travelProfile ?: TravelProfile.CAR,
                    runAction       = when {
                        unfinished != null -> GroupRunAction.RESUME
                        latest != null     -> GroupRunAction.START_AGAIN
                        else               -> GroupRunAction.START_FIRST
                    },
                    resumableRunId  = unfinished?.id
                )
            }

            // Offline coverage for the whole event, observed rather than read
            // once: a download started from here should fill the chip in as it
            // runs, not only on the next visit.
            observeCoverage(group.courseIds)
        }
    }

    private var coverageJob: kotlinx.coroutines.Job? = null

    private fun observeCoverage(courseIds: List<Long>) {
        coverageJob?.cancel()
        coverageJob = viewModelScope.launch {
            repository.observeOfflineCoverage(courseIds).collect { coverage ->
                _state.update { it.copy(offlineCoverage = coverage) }
            }
        }
    }

    /**
     * Download one map for the whole event. A chip, not a dialog, see
     * [com.coursemapper.domain.OfflinePromptPolicy.shouldWarnUncovered].
     */
    fun downloadOfflineMap() {
        val courseIds = _state.value.group?.courseIds ?: return
        if (courseIds.isEmpty()) return
        viewModelScope.launch {
            val proposal = repository.quoteOfflinePack(courseIds) ?: return@launch
            if (proposal.quote.isViable) repository.startOfflineDownload(proposal)
        }
    }

    fun toggleOptions() = _state.update { it.copy(optionsExpanded = !it.optionsExpanded) }

    fun selectOrdering(ordering: RunOrdering) = _state.update { it.copy(ordering = ordering) }

    fun selectTravelProfile(profile: TravelProfile) =
        _state.update { it.copy(travelProfile = profile) }

    /** Resume the unfinished run or create a new one for the group. */
    fun startRun() {
        val s = _state.value
        val group = s.group ?: return
        if (!s.canStart) return

        val resumable = s.resumableRunId
        if (resumable != null) {
            // Ordering and travel profile can still be changed from here; the
            // ordering is baked into the existing stop order, but the travel
            // profile only affects leg routing and is safe to update in place.
            viewModelScope.launch {
                repository.updateRunTravelProfile(resumable, s.travelProfile)
                _state.update { it.copy(startedRunId = resumable) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isStartingRun = true) }
            val runId = repository.createRun(
                name          = group.name,
                courseIds     = group.courseIds,
                ordering      = s.ordering,
                travelProfile = s.travelProfile,
                groupId       = group.id
            )
            _state.update {
                it.copy(isStartingRun = false, startedRunId = runId.takeIf { id -> id > 0 })
            }
        }
    }

    fun consumeStartedRun() = _state.update { it.copy(startedRunId = null) }

    fun requestDelete() = _state.update { it.copy(confirmDelete = true) }
    fun cancelDelete() = _state.update { it.copy(confirmDelete = false) }

    fun confirmDelete() {
        viewModelScope.launch {
            repository.deleteCourseGroup(groupId)
            _state.update { it.copy(confirmDelete = false, deleted = true) }
        }
    }

    fun formatDistance(metres: Double): String =
        distanceFormatter.formatCompact(metres, _state.value.distanceUnit)
}
