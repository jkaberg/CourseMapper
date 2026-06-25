package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.DistanceMarker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MarkerTableUiState(
    val courseName: String = "",
    val variantName: String? = null,
    val markers: List<DistanceMarker> = emptyList(),
    val isLoading: Boolean = true
)

/** Markers for the table, sorted by distance from the variant's start. */
@HiltViewModel
class MarkerTableViewModel @Inject constructor(
    private val repository: RouteRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val courseId: Long = savedStateHandle["courseId"] ?: -1L

    private val _state = MutableStateFlow(MarkerTableUiState())
    val uiState: StateFlow<MarkerTableUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val course = repository.getCourse(courseId) ?: run {
            _state.update { it.copy(isLoading = false) }
            return
        }

        // Resolve variant name when this course was compiled from a named variant.
        var variantName: String? = null
        if (course.variantId != null) {
            variantName = repository.getVariant(course.variantId)?.name
        }

        // Sort by cumulative distance - this is the physical packing order.
        val sorted = course.markers.sortedBy { it.cumulativeDistanceMetres }

        _state.update {
            it.copy(
                courseName  = course.name,
                variantName = variantName,
                markers     = sorted,
                isLoading   = false
            )
        }
    }
}
