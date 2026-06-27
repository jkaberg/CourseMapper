package com.coursemapper.ui.run

import com.coursemapper.domain.CourseDisplayPlanner

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.TravelProfile
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

data class RunSetupUiState(
    val isLoading: Boolean = true,
    /** Published courses with markers - the only valid run inputs. */
    val availableCourses: List<ComposedCourse> = emptyList(),
    val selectedCourseIds: List<Long> = emptyList(),
    val runName: String = "",
    val ordering: RunOrdering = RunOrdering.SPINE,
    val travelProfile: TravelProfile = TravelProfile.CAR,
    /** Null while no selection or assembly in flight. */
    val preview: RouteRepository.RunPreview? = null,
    val isPreviewLoading: Boolean = false,
    /** Course polylines of the selection, for the preview map. */
    val courseLines: List<RunCourseLine> = emptyList(),
    /** Stretches carrying more than one course, for the low-zoom shared casing. */
    val sharedCorridors: List<CourseDisplayPlanner.SharedCorridor> = emptyList(),
    val isCreating: Boolean = false,
    /** Set when the run is created; triggers navigation to the gate. */
    val createdRunId: Long? = null,
    val distanceUnit: DistanceFormatter.DistanceUnit = DistanceFormatter.DistanceUnit.KM,
)

/** Pick published courses for one pass, compare orderings, start. */
@HiltViewModel
class RunSetupViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val distanceFormatter: DistanceFormatter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Comma-separated course ids preselected by the event wizard. */
    private val preselectArg: String = savedStateHandle["preselect"] ?: ""

    private val _state = MutableStateFlow(RunSetupUiState())
    val uiState: StateFlow<RunSetupUiState> = _state.asStateFlow()

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val unit = distanceFormatter.unitEnumFlow.first()
            val courses = repository.observeCourses().first()
                .filter { !it.isDraft && it.markers.isNotEmpty() }
            val preselected = preselectArg.split(',')
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { id -> courses.any { it.id == id } }
            _state.update {
                it.copy(
                    isLoading         = false,
                    availableCourses  = courses,
                    selectedCourseIds = preselected,
                    distanceUnit      = unit,
                    runName           = defaultRunName(courses, preselected)
                )
            }
            if (preselected.isNotEmpty()) refreshPreview()
        }
    }

    fun onRunNameChange(value: String) = _state.update { it.copy(runName = value) }

    fun toggleCourse(courseId: Long) {
        _state.update {
            val selected = if (courseId in it.selectedCourseIds) {
                it.selectedCourseIds - courseId
            } else {
                it.selectedCourseIds + courseId
            }
            it.copy(
                selectedCourseIds = selected,
                runName = if (it.runName.isBlank() || it.runName == defaultRunName(it.availableCourses, it.selectedCourseIds)) {
                    defaultRunName(it.availableCourses, selected)
                } else it.runName
            )
        }
        refreshPreview()
    }

    fun selectOrdering(ordering: RunOrdering) = _state.update { it.copy(ordering = ordering) }

    fun selectTravelProfile(profile: TravelProfile) = _state.update { it.copy(travelProfile = profile) }

    private fun refreshPreview() {
        previewJob?.cancel()
        val selected = _state.value.selectedCourseIds
        if (selected.isEmpty()) {
            _state.update {
                it.copy(
                    preview = null, courseLines = emptyList(),
                    sharedCorridors = emptyList(), isPreviewLoading = false
                )
            }
            return
        }
        previewJob = viewModelScope.launch {
            _state.update { it.copy(isPreviewLoading = true) }
            val preview = repository.previewRun(selected)
            val display = repository.getCourseDisplayLines(selected)
            _state.update {
                it.copy(
                    preview         = preview,
                    courseLines     = display.toRunCourseLines(),
                    sharedCorridors = display.sharedCorridors,
                    isPreviewLoading = false
                )
            }
        }
    }

    fun createRun() {
        val s = _state.value
        if (s.selectedCourseIds.isEmpty() || s.isCreating) return
        viewModelScope.launch {
            _state.update { it.copy(isCreating = true) }
            val runId = repository.createRun(
                name          = s.runName.ifBlank { "Placement run" },
                courseIds     = s.selectedCourseIds,
                ordering      = s.ordering,
                travelProfile = s.travelProfile
            )
            _state.update {
                it.copy(isCreating = false, createdRunId = if (runId > 0) runId else null)
            }
        }
    }

    fun consumeCreatedRun() = _state.update { it.copy(createdRunId = null) }

    fun formatDistance(metres: Double): String =
        distanceFormatter.formatCompact(metres, _state.value.distanceUnit)

    private fun defaultRunName(courses: List<ComposedCourse>, selected: List<Long>): String =
        when {
            selected.isEmpty() -> ""
            selected.size == 1 -> courses.firstOrNull { it.id == selected.first() }?.name ?: ""
            else               -> "Placement run (${selected.size} courses)"
        }
}
