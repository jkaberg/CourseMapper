package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.SegmentPolylineSlice
import com.coursemapper.domain.VariantCompilationEngine
import com.coursemapper.domain.model.RouteNetwork
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.RouteSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VariantBuilderUiState(
    val network: RouteNetwork? = null,
    val selectedSegmentIds: List<Long> = emptyList(),
    val name: String = "",
    /** Length of the stitched and trimmed preview, not the sum of full segments. */
    val estimatedMetres: Double = 0.0,
    val connectivityError: String? = null,
    val isSaving: Boolean = false,
    /** Non-null when Compile & Save failed; shown as a snackbar. */
    val saveError: String? = null,
    val savedVariantId: Long? = null,
    val isLoading: Boolean = true,
    /** Live preview polyline compiled from selected segments. */
    val previewPolyline: List<RoutePoint> = emptyList(),
    /** Where consecutive segments don't connect, drawn as red markers. */
    val disconnectionPoints: List<Pair<Double, Double>> = emptyList(),
    /** True while the save was successful (used to show success snackbar). */
    val showSaveSuccess: Boolean = false,
) {
    val canSave: Boolean get() =
        name.isNotBlank() && selectedSegmentIds.isNotEmpty() && connectivityError == null && !isSaving
}

@HiltViewModel
class VariantBuilderViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val compilationEngine: VariantCompilationEngine,
    private val distanceCalc: CumulativeDistanceCalculator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val networkId: Long = savedStateHandle["networkId"] ?: -1L

    private val _state = MutableStateFlow(VariantBuilderUiState())
    val uiState: StateFlow<VariantBuilderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val network = repository.getNetwork(networkId)
            _state.update { it.copy(network = network, isLoading = false) }
        }
    }

    fun onNameChanged(value: String) {
        _state.update { it.copy(name = value) }
    }

    fun toggleSegment(segmentId: Long) {
        val current = _state.value.selectedSegmentIds.toMutableList()
        if (segmentId in current) current.remove(segmentId) else current.add(segmentId)
        applyNewOrder(current)
    }

    /** Move the segment at position [segmentId] one step earlier in the traversal order. */
    fun moveSegmentUp(segmentId: Long) {
        val current = _state.value.selectedSegmentIds.toMutableList()
        val idx = current.indexOf(segmentId)
        if (idx <= 0) return
        current.removeAt(idx)
        current.add(idx - 1, segmentId)
        applyNewOrder(current)
    }

    /** Move the segment at position [segmentId] one step later in the traversal order. */
    fun moveSegmentDown(segmentId: Long) {
        val current = _state.value.selectedSegmentIds.toMutableList()
        val idx = current.indexOf(segmentId)
        if (idx < 0 || idx >= current.lastIndex) return
        current.removeAt(idx)
        current.add(idx + 1, segmentId)
        applyNewOrder(current)
    }

    private fun applyNewOrder(reordered: List<Long>) {
        val network = _state.value.network ?: return
        val selectedSegments = reordered.mapNotNull { id -> network.segments.find { it.id == id } }
        _state.update {
            it.copy(
                selectedSegmentIds = reordered,
                // Provisional estimate; compilePreview replaces it with the
                // stitched (slice-aware) total.
                estimatedMetres    = selectedSegments.sumOf { s -> s.distanceMetres },
                connectivityError  = validateConnectivity(selectedSegments)
            )
        }
        viewModelScope.launch { compilePreview(reordered) }
    }

    private suspend fun compilePreview(selectedIds: List<Long>) {
        if (selectedIds.isEmpty()) {
            _state.update {
                it.copy(previewPolyline = emptyList(), disconnectionPoints = emptyList(), estimatedMetres = 0.0)
            }
            return
        }
        val network = _state.value.network ?: return
        val slices = selectedIds.mapNotNull { id ->
            val seg   = network.segments.find { it.id == id } ?: return@mapNotNull null
            val route = repository.getRoute(seg.baseRouteId) ?: return@mapNotNull null
            val pts   = route.smoothedPoints.ifEmpty { route.rawPoints }
            SegmentPolylineSlice(
                points     = pts,
                fromMetres = seg.fromMetres,
                toMetres   = seg.toMetres
            )
        }
        val compiled = compilationEngine.compile(slices)

        // compare what each segment actually contributes after slicing, full
        // polyline endpoints give false gaps
        val contributed = slices.map { slice ->
            if (slice.fromMetres != null && slice.toMetres != null) {
                distanceCalc.slicePolyline(slice.points, slice.fromMetres, slice.toMetres)
            } else {
                slice.points
            }
        }
        val gapPoints = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until contributed.size - 1) {
            val aLast  = contributed[i].lastOrNull() ?: continue
            val bFirst = contributed[i + 1].firstOrNull() ?: continue
            val distM  = distanceCalc.haversineMetres(aLast.lat, aLast.lon, bFirst.lat, bFirst.lon)
            if (distM > VariantCompilationEngine.JUNCTION_DEDUP_THRESHOLD_M) {
                gapPoints.add(aLast.lat to aLast.lon)
                gapPoints.add(bFirst.lat to bFirst.lon)
            }
        }
        _state.update {
            it.copy(
                previewPolyline     = compiled.compiledPoints,
                disconnectionPoints = gapPoints,
                estimatedMetres     = compiled.totalDistanceMetres
            )
        }
    }

    /** Error message if [segments] don't form a connected path, null if fine. One segment always passes. */
    internal fun validateConnectivity(segments: List<RouteSegment>): String? {
        if (segments.size <= 1) return null
        for (i in 0 until segments.lastIndex) {
            val a = segments[i]
            val b = segments[i + 1]
            // If both junctions are defined, they must match.
            if (a.toJunctionId != null && b.fromJunctionId != null &&
                a.toJunctionId != b.fromJunctionId
            ) {
                return "Segment ${i + 1} does not connect to segment ${i + 2}. " +
                       "Check that split/rejoin junction pins are correct."
            }
        }
        return null
    }

    /** Compile the selected segments and save the variant. Navigates via [savedVariantId]. */
    fun compileAndSave() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, saveError = null) }
            try {
                val variantId = repository.createVariant(networkId, s.name, s.selectedSegmentIds)
                repository.compileAndSaveVariant(variantId)
                _state.update { it.copy(isSaving = false, savedVariantId = variantId, showSaveSuccess = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving  = false,
                        saveError = "Could not compile this distance: ${e.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun consumeSaved() {
        _state.update { it.copy(savedVariantId = null) }
    }

    fun dismissSaveSuccess() {
        _state.update { it.copy(showSaveSuccess = false) }
    }

    fun dismissSaveError() {
        _state.update { it.copy(saveError = null) }
    }
}
