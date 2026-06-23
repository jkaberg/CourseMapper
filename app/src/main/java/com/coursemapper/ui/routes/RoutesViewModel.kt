package com.coursemapper.ui.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.RouteNetwork
import com.coursemapper.domain.model.RouteVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StandaloneCourseItem(
    val courseId: Long,
    val name: String,
    val totalKm: Double,
    val markerCount: Int
)

data class VariantCourseItem(
    val variantId: Long,
    val variantName: String,
    val courseId: Long?,
    val totalKm: Double?,
    val isApproved: Boolean
)

data class NetworkDisplayItem(
    val networkId: Long,
    val networkName: String,
    val updatedAt: Long,
    val trunkDistanceKm: Double?,
    /** For trivially-wrapped single-segment networks with no named variants. */
    val standaloneCourses: List<StandaloneCourseItem>,
    /** For networks with explicitly created named variants (Marathon, Half, etc.). */
    val variantCourses: List<VariantCourseItem>
)

data class RoutesUiState(
    val items: List<NetworkDisplayItem> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class RoutesViewModel @Inject constructor(
    private val repository: RouteRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RoutesUiState())
    val uiState: StateFlow<RoutesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeNetworks(),
                repository.observeCourses()
            ) { networks, courses ->
                buildItems(networks, courses)
            }.collect { items ->
                _state.update { it.copy(items = items, isLoading = false) }
            }
        }
    }

    private suspend fun buildItems(
        networks: List<RouteNetwork>,
        courses: List<ComposedCourse>
    ): List<NetworkDisplayItem> {
        val coursesByVariantId   = courses.filter { it.variantId != null }.associateBy { it.variantId!! }
        val coursesByBaseRouteId = courses.filter { it.variantId == null }.groupBy { it.baseRouteId }

        return networks.map { network ->
            val trunkSeg        = network.segments.firstOrNull { it.isMainTrunk }
            val trunkDistanceKm = trunkSeg?.distanceMetres?.div(1_000.0)
            val variants        = repository.getVariantsForNetwork(network.id)

            if (variants.isEmpty()) {
                // Trivially-wrapped route - show its direct composed course(s).
                val directCourses = trunkSeg?.let { coursesByBaseRouteId[it.baseRouteId] }?.map { c ->
                    StandaloneCourseItem(
                        courseId    = c.id,
                        name        = c.name,
                        totalKm     = c.totalDistanceMetres / 1_000.0,
                        markerCount = c.markers.size
                    )
                } ?: emptyList()
                NetworkDisplayItem(
                    networkId       = network.id,
                    networkName     = network.name,
                    updatedAt       = network.updatedAt,
                    trunkDistanceKm = trunkDistanceKm,
                    standaloneCourses = directCourses,
                    variantCourses    = emptyList()
                )
            } else {
                // Network with named distances.
                val variantItems = variants.map { v ->
                    val course = coursesByVariantId[v.id]
                    VariantCourseItem(
                        variantId   = v.id,
                        variantName = v.name,
                        courseId    = course?.id,
                        totalKm     = (v.totalDistanceMetres ?: course?.totalDistanceMetres)?.div(1_000.0),
                        isApproved  = v.isApproved
                    )
                }
                NetworkDisplayItem(
                    networkId         = network.id,
                    networkName       = network.name,
                    updatedAt         = network.updatedAt,
                    trunkDistanceKm   = trunkDistanceKm,
                    standaloneCourses = emptyList(),
                    variantCourses    = variantItems
                )
            }
        }
    }

    fun deleteCourse(courseId: Long) {
        viewModelScope.launch { repository.deleteCourse(courseId) }
    }
}
