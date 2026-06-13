package com.coursemapper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.db.OfflinePackDao
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.CourseStatus
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.fieldSeverity
import com.coursemapper.domain.model.status
import com.coursemapper.map.MapPalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Extra info for a group row. Colours use the same cycle as `RouteRepository.getCourseDisplayLines`. */
data class GroupRowInfo(
    val memberNames: List<String> = emptyList(),
    val memberColorsHex: List<String> = emptyList(),
    /** Worst member's status - see [fieldSeverity]. Null when the group is empty. */
    val worstStatus: CourseStatus? = null,
    /** Name of the member carrying [worstStatus], so the chip can name the blocker. */
    val worstCourseName: String? = null
)

/** A course deletion awaiting confirmation, with everything the dialog must disclose. */
data class CourseDeleteRequest(
    val course: ComposedCourse,
    /** Combined courses that lose this member - deleting is not a local act. */
    val groupNames: List<String> = emptyList(),
    /** A run including this course is part-way through the field. */
    val hasRunInProgress: Boolean = false
,
    /** Offline bytes this delete frees, null if the pack is shared with a course that stays. */
    val offlineBytesFreed: Long? = null
)

/** A combined-course deletion awaiting confirmation. */
data class GroupDeleteRequest(
    val group: CourseGroup,
    /** Members that survive - the whole point of the group's softer semantics. */
    val memberNames: List<String> = emptyList()
)

enum class RenameKind { COURSE, GROUP }

/** An in-place rename awaiting a new name. */
data class RenameRequest(
    val kind: RenameKind,
    val id: Long,
    val currentName: String
)

/** Dialog state, grouped so the main [combine] stays inside its arity. */
private data class HomeDialogs(
    val confirmDeleteCourse: CourseDeleteRequest? = null,
    val confirmDeleteGroup: GroupDeleteRequest? = null,
    val confirmDiscardRecording: BaseRoute? = null,
    val rename: RenameRequest? = null
)

data class HomeUiState(
    val courses: List<ComposedCourse> = emptyList(),
    /** Combined courses (events) shown alongside the individual courses. */
    val courseGroups: List<CourseGroup> = emptyList(),
    /** Unfinished placement runs, resumable with one tap. */
    val incompleteRuns: List<PlacementRun> = emptyList(),
    /** Status per course id including offline state (OFFLINE_READY / NEEDS_FIELD_PREP). */
    val courseStatuses: Map<Long, CourseStatus> = emptyMap(),
    /** Row decoration per group id: swatches and the worst-member readiness chip. */
    val groupRowInfo: Map<Long, GroupRowInfo> = emptyMap(),
    /** Groups each course belongs to, shown on the course rows. */
    val courseGroupNames: Map<Long, List<String>> = emptyMap(),
    /** Recordings that were never approved, eg the process died while recording. */
    val interruptedRecordings: List<BaseRoute> = emptyList(),
    /** Non-null while the course delete-confirmation dialog is showing. */
    val confirmDeleteCourse: CourseDeleteRequest? = null,
    /** Non-null while the combined-course delete-confirmation dialog is showing. */
    val confirmDeleteGroup: GroupDeleteRequest? = null,
    /** Non-null while the discard-recording confirmation dialog is showing. */
    val confirmDiscardRecording: BaseRoute? = null,
    /** Non-null while a rename dialog is showing. */
    val rename: RenameRequest? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val offlinePackDao: OfflinePackDao
) : ViewModel() {

    private val _dialogs = MutableStateFlow(HomeDialogs())

    val uiState: StateFlow<HomeUiState> = combine(
        combine(repository.observeCourses(), repository.observeCourseGroups()) { c, g -> c to g },
        repository.observeIncompleteRuns(),
        repository.observeInterruptedRecordings(),
        offlinePackDao.observeCourseOfflineRanks(),
        _dialogs
    ) { (courses, groups), runs, interrupted, offlineRanks, dialogs ->
        // ranks, not rows - full rows re-ran this combine per downloaded tile,
        // see `observeCourseOfflineRanks`
        val readyCourseIds = offlineRanks
            .filter { it.isUsable }
            .map { it.courseId }
            .toSet()
        val inProgressCourseIds = offlineRanks
            .filter { it.isInFlight }
            .map { it.courseId }
            .toSet()

        val statuses = courses.associate { course ->
            val base = course.status
            course.id to when {
                base != CourseStatus.PUBLISHED        -> base
                course.id in readyCourseIds           -> CourseStatus.OFFLINE_READY
                course.id in inProgressCourseIds      -> CourseStatus.PUBLISHED
                else                                  -> CourseStatus.NEEDS_FIELD_PREP
            }
        }

        val coursesById = courses.associateBy { it.id }
        val groupRowInfo = groups.associate { group ->
            val members = group.courseIds.mapNotNull { coursesById[it] }
            val worst = members.maxByOrNull { (statuses[it.id] ?: it.status).fieldSeverity }
            group.id to GroupRowInfo(
                memberNames     = members.map { it.name },
                memberColorsHex = members.indices.map {
                    MapPalette.COURSE_CYCLE[it % MapPalette.COURSE_CYCLE.size]
                },
                worstStatus     = worst?.let { statuses[it.id] ?: it.status },
                worstCourseName = worst?.name
            )
        }

        val courseGroupNames = mutableMapOf<Long, MutableList<String>>()
        groups.forEach { group ->
            group.courseIds.forEach { id ->
                courseGroupNames.getOrPut(id) { mutableListOf() }.add(group.name)
            }
        }

        HomeUiState(
            courses                 = courses,
            courseGroups            = groups,
            incompleteRuns          = runs,
            courseStatuses          = statuses,
            groupRowInfo            = groupRowInfo,
            courseGroupNames        = courseGroupNames,
            interruptedRecordings   = interrupted,
            confirmDeleteCourse     = dialogs.confirmDeleteCourse,
            confirmDeleteGroup      = dialogs.confirmDeleteGroup,
            confirmDiscardRecording = dialogs.confirmDiscardRecording,
            rename                  = dialogs.rename
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    /** Open the delete dialog right away and fill in what else it touches as it loads. */
    fun requestDelete(course: ComposedCourse) {
        _dialogs.value = _dialogs.value.copy(confirmDeleteCourse = CourseDeleteRequest(course))
        viewModelScope.launch {
            val groupNames  = repository.getGroupsContainingCourse(course.id).map { it.name }
            val runUnderway = repository.hasRunInProgress(course.id)
            // The offline map is the largest thing this deletion removes, and
            // the only one the user cannot see from the course row.
            val offlineFreed = repository.offlineBytesFreedByDeleting(course.id)
            _dialogs.value = _dialogs.value.let { current ->
                // Only fill in the request still on screen: a second long-press
                // during the load must not be overwritten by the first result.
                if (current.confirmDeleteCourse?.course?.id != course.id) current
                else current.copy(
                    confirmDeleteCourse =
                        CourseDeleteRequest(course, groupNames, runUnderway, offlineFreed)
                )
            }
        }
    }

    /** Dismiss the confirmation dialog without deleting. */
    fun cancelDelete() {
        _dialogs.value = _dialogs.value.copy(confirmDeleteCourse = null)
    }

    /** Confirm deletion of the currently-pending course. */
    fun confirmDelete() {
        val course = _dialogs.value.confirmDeleteCourse?.course ?: return
        _dialogs.value = _dialogs.value.copy(confirmDeleteCourse = null)
        viewModelScope.launch { repository.deleteCourse(course.id) }
    }

    fun requestDeleteGroup(group: CourseGroup) {
        val names = uiState.value.groupRowInfo[group.id]?.memberNames.orEmpty()
        _dialogs.value = _dialogs.value.copy(confirmDeleteGroup = GroupDeleteRequest(group, names))
    }

    fun cancelDeleteGroup() {
        _dialogs.value = _dialogs.value.copy(confirmDeleteGroup = null)
    }

    fun confirmDeleteGroup() {
        val group = _dialogs.value.confirmDeleteGroup?.group ?: return
        _dialogs.value = _dialogs.value.copy(confirmDeleteGroup = null)
        viewModelScope.launch { repository.deleteCourseGroup(group.id) }
    }

    fun requestRenameCourse(course: ComposedCourse) {
        _dialogs.value = _dialogs.value.copy(
            rename = RenameRequest(RenameKind.COURSE, course.id, course.name)
        )
    }

    fun requestRenameGroup(group: CourseGroup) {
        _dialogs.value = _dialogs.value.copy(
            rename = RenameRequest(RenameKind.GROUP, group.id, group.name)
        )
    }

    fun cancelRename() {
        _dialogs.value = _dialogs.value.copy(rename = null)
    }

    fun confirmRename(newName: String) {
        val request = _dialogs.value.rename ?: return
        _dialogs.value = _dialogs.value.copy(rename = null)
        if (newName.isBlank()) return
        viewModelScope.launch {
            when (request.kind) {
                RenameKind.COURSE -> repository.renameCourse(request.id, newName)
                RenameKind.GROUP  -> repository.renameCourseGroup(request.id, newName)
            }
        }
    }

    /** Approve an interrupted recording so it can be reviewed as a draft. */
    fun saveInterruptedRecording(route: BaseRoute, onApproved: (routeId: Long) -> Unit) {
        viewModelScope.launch {
            repository.approveRoute(route.id)
            onApproved(route.id)
        }
    }

    fun requestDiscardRecording(route: BaseRoute) {
        _dialogs.value = _dialogs.value.copy(confirmDiscardRecording = route)
    }

    fun cancelDiscardRecording() {
        _dialogs.value = _dialogs.value.copy(confirmDiscardRecording = null)
    }

    fun confirmDiscardRecording() {
        val route = _dialogs.value.confirmDiscardRecording ?: return
        _dialogs.value = _dialogs.value.copy(confirmDiscardRecording = null)
        viewModelScope.launch { repository.deleteRoute(route.id) }
    }
}
