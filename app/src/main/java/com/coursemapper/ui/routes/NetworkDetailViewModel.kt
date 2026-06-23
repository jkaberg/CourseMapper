package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.RouteNetwork
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.RouteVariant
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NetworkDetailUiState(
    val network: RouteNetwork? = null,
    val variants: List<RouteVariant> = emptyList(),
    val isLoading: Boolean = true,
    /** Maps approved variant id → the course id generated for that variant. */
    val approvedCourseIds: Map<Long, Long> = emptyMap(),
    /** Non-null when a variant draft has been created and we need to navigate to it. */
    val draftCourseId: Long? = null,
    /** Available presets for the "Create Draft" bottom sheet. */
    val availablePresets: List<MarkerPreset> = emptyList(),
    /** Variant awaiting preset selection; non-null while the preset picker sheet is open. */
    val pendingDraftVariantId: Long? = null,
    /** Whether the preset picker shows the "no markers" toggle as selected. */
    val pendingDraftNoMarkers: Boolean = false,
    /** True while a draft is being created. */
    val isCreatingDraft: Boolean = false,

    // Map header data
    /** Trunk polyline points for the network map header. */
    val trunkPoints: List<RoutePoint> = emptyList(),
    /** Branch polylines: one list per non-trunk segment. */
    val branchPointsList: List<List<RoutePoint>> = emptyList(),
    /** Junction pins for the map header: (lat, lon, label). */
    val junctionPins: List<Triple<Double, Double, String>> = emptyList(),
    /** Maps segment id → source route name, for labelling branch segment cards. */
    val branchRouteNames: Map<Long, String> = emptyMap(),
)

@HiltViewModel
class NetworkDetailViewModel @Inject constructor(
    private val repository: RouteRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val networkId: Long = savedStateHandle["networkId"] ?: -1L

    private val _state = MutableStateFlow(NetworkDetailUiState())
    val uiState: StateFlow<NetworkDetailUiState> = _state.asStateFlow()

    init {
        loadNetwork()
        observeVariants()
        loadPresets()
    }

    private fun loadNetwork() {
        viewModelScope.launch {
            val network = repository.getNetwork(networkId)
            _state.update { it.copy(network = network, isLoading = false) }
            if (network != null) loadMapData(network)
        }
    }

    private suspend fun loadMapData(network: com.coursemapper.domain.model.RouteNetwork) {
        val trunk = network.segments.firstOrNull { it.isMainTrunk }
        val trunkPts = if (trunk != null) {
            val route = repository.getRoute(trunk.baseRouteId)
            route?.smoothedPoints?.ifEmpty { route.rawPoints } ?: emptyList()
        } else emptyList()

        val branchPtsList = network.segments
            .filter { !it.isMainTrunk }
            .mapNotNull { seg ->
                val route = repository.getRoute(seg.baseRouteId)
                route?.smoothedPoints?.ifEmpty { route.rawPoints }
            }

        val branchRouteNames = network.segments
            .filter { !it.isMainTrunk }
            .mapNotNull { seg ->
                val route = repository.getRoute(seg.baseRouteId)
                if (route != null) seg.id to route.name else null
            }.toMap()

        val junctions = network.junctions.map { j ->
            Triple(j.lat, j.lon, j.label ?: "Junction")
        }

        _state.update {
            it.copy(
                trunkPoints      = trunkPts,
                branchPointsList = branchPtsList,
                junctionPins     = junctions,
                branchRouteNames = branchRouteNames
            )
        }
    }

    private fun observeVariants() {
        viewModelScope.launch {
            repository.observeVariants(networkId).collect { variants ->
                val courseIdMap = variants
                    .filter { it.isApproved }
                    .mapNotNull { v ->
                        repository.getCourseByVariantId(v.id)?.let { course -> v.id to course.id }
                    }
                    .toMap()
                _state.update { it.copy(variants = variants, approvedCourseIds = courseIdMap) }
            }
        }
    }

    private fun loadPresets() {
        viewModelScope.launch {
            repository.observePresets().collect { presets ->
                _state.update { it.copy(availablePresets = presets) }
            }
        }
    }

    /** Called when the user taps "Create Draft" on a distance row. Opens the preset picker. */
    fun requestCreateDraft(variantId: Long) {
        _state.update { it.copy(pendingDraftVariantId = variantId, pendingDraftNoMarkers = false) }
    }

    fun dismissDraftPicker() {
        _state.update { it.copy(pendingDraftVariantId = null, pendingDraftNoMarkers = false) }
    }

    fun toggleNoMarkers(value: Boolean) {
        _state.update { it.copy(pendingDraftNoMarkers = value) }
    }

    /** Confirmed from the preset picker: create the draft course. */
    fun confirmCreateDraft(presetId: Long?) {
        val variantId = _state.value.pendingDraftVariantId ?: return
        _state.update { it.copy(isCreatingDraft = true, pendingDraftVariantId = null) }
        viewModelScope.launch {
            val courseId = repository.createVariantCourseDraft(variantId, presetId)
            val updatedVariants = repository.getVariantsForNetwork(networkId)
            val courseIdMap = updatedVariants
                .filter { it.isApproved }
                .mapNotNull { v ->
                    repository.getCourseByVariantId(v.id)?.let { c -> v.id to c.id }
                }
                .toMap()
            _state.update {
                it.copy(
                    isCreatingDraft   = false,
                    variants          = updatedVariants,
                    approvedCourseIds = courseIdMap,
                    draftCourseId     = if (courseId > 0) courseId else null
                )
            }
        }
    }

    fun consumeDraftCourse() {
        _state.update { it.copy(draftCourseId = null) }
    }

    // Legacy alias kept for call sites that haven't been migrated yet.
    fun approveVariant(variantId: Long, presetId: Long?) {
        requestCreateDraft(variantId)
    }

    fun consumeApprovedCourse() = consumeDraftCourse()
}
