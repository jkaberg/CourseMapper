package com.coursemapper.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.ComposedCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CombineCoursesUiState(
    val isLoading: Boolean = true,
    /** Null while creating a new combined course; set when editing an existing one. */
    val groupId: Long? = null,
    val name: String = "",
    /** Published courses with markers - the only things worth combining. */
    val availableCourses: List<ComposedCourse> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val isSaving: Boolean = false,
    /** Set once the group is written; the host consumes it and dismisses. */
    val savedGroupId: Long? = null
) {
    val isEditing: Boolean get() = groupId != null

    /** At least two members, a group of one is just a course. */
    val canSave: Boolean get() = selectedIds.size >= 2 && !isSaving && !isLoading
}

/**
 * Combine existing courses into a group, or edit a group's members. Not via
 * the event wizard, that's for GPX import.
 */
@HiltViewModel
class CombineCoursesViewModel @Inject constructor(
    private val repository: RouteRepository
) : ViewModel() {

    private val _state = MutableStateFlow(CombineCoursesUiState())
    val uiState: StateFlow<CombineCoursesUiState> = _state.asStateFlow()

    private var started = false

    /** Load courses and, with [groupId], the current members. Idempotent. */
    fun start(groupId: Long? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            val courses = repository.observeCourses().first()
                .filter { !it.isDraft && it.markers.isNotEmpty() }
            val group = groupId?.let { repository.getCourseGroup(it) }
            _state.update {
                it.copy(
                    isLoading        = false,
                    groupId          = group?.id,
                    name             = group?.name ?: "",
                    availableCourses = courses,
                    // Membership is already filtered to live courses by the DAO
                    // join, so a soft-deleted member cannot come back through
                    // this screen as a tick the organiser never made.
                    selectedIds      = group?.courseIds.orEmpty().toSet()
                )
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value) }

    fun toggle(courseId: Long) = _state.update {
        it.copy(
            selectedIds = if (courseId in it.selectedIds) it.selectedIds - courseId
                          else it.selectedIds + courseId
        )
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        // Keep the order the list shows, so the member colours on the row and
        // on the detail map follow the same cycle the organiser sees here.
        val ordered = s.availableCourses.map { it.id }.filter { it in s.selectedIds }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val id = s.groupId
            if (id != null) {
                repository.setCourseGroupMembers(id, ordered)
                if (s.name.isNotBlank()) repository.renameCourseGroup(id, s.name)
                _state.update { it.copy(isSaving = false, savedGroupId = id) }
            } else {
                val newId = repository.createCourseGroup(
                    name = s.name.ifBlank { "Combined course (${ordered.size} routes)" },
                    courseIds = ordered
                )
                _state.update {
                    it.copy(isSaving = false, savedGroupId = newId.takeIf { v -> v > 0 })
                }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(savedGroupId = null) }

    /** Reset so the next opening starts from what's stored, the VM outlives the sheet. */
    fun reset() {
        started = false
        _state.value = CombineCoursesUiState()
    }
}
