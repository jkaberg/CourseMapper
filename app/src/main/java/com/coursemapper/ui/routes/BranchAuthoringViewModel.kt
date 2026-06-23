package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.MarkerSnapEngine
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.RouteJunction
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.ui.format.DistanceFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BranchAuthoringUiState(
    val networkId: Long = -1L,
    val trunkPoints: List<RoutePoint> = emptyList(),
    val availableRoutes: List<BaseRoute> = emptyList(),
    val selectedRouteId: Long? = null,
    val selectedRoutePoints: List<RoutePoint> = emptyList(),
    /** Snapped split-point junction (set on first tap). */
    val splitJunction: SnapPin? = null,
    /** Snapped rejoin-point junction (set on second tap). */
    val rejoinJunction: SnapPin? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isLoading: Boolean = true,
    /** Non-null when the user taps the map and we need to decide split or rejoin. */
    val tapMode: TapMode = TapMode.SPLIT,
    val errorMessage: String? = null,
    /** Non-null hint when a tap was too far from the route to snap. */
    val snapMissHint: String? = null,
    /** Trunk slice between the two junction pins, drawn amber to check the right section is picked. */
    val slicePreviewPoints: List<RoutePoint> = emptyList(),
    /** Human-readable distance of the current trunk slice (null when slice is unavailable). */
    val sliceDistanceHint: String? = null,
) {
    val canSave: Boolean get() = selectedRouteId != null && !isSaving
}

data class SnapPin(val lat: Double, val lon: Double, val cumulativeM: Double)

enum class TapMode { SPLIT, REJOIN }

@HiltViewModel
class BranchAuthoringViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val snapEngine: MarkerSnapEngine,
    private val distanceCalc: CumulativeDistanceCalculator,
    private val distanceFormatter: DistanceFormatter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val networkId: Long = savedStateHandle["networkId"] ?: -1L

    private val _state = MutableStateFlow(BranchAuthoringUiState(networkId = networkId))
    val uiState: StateFlow<BranchAuthoringUiState> = _state.asStateFlow()

    /** Latest unit preference for the slice-length hint. */
    private var currentUnit = DistanceFormatter.DistanceUnit.KM

    companion object {
        /** Maximum metres a tap may be from the route polyline before it is ignored. */
        const val SNAP_RADIUS_M = 50.0
    }

    init {
        loadTrunkPoints()
        loadAvailableRoutes()
        viewModelScope.launch {
            distanceFormatter.unitEnumFlow.collect {
                currentUnit = it
                recomputeSlicePreview()
            }
        }
    }

    private fun loadTrunkPoints() {
        viewModelScope.launch {
            val network = repository.getNetwork(networkId)
            if (network == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            val trunkSeg = network.segments.firstOrNull { it.isMainTrunk }
            if (trunkSeg == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            val route = repository.getRoute(trunkSeg.baseRouteId)
            if (route == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    trunkPoints = route.smoothedPoints.ifEmpty { route.rawPoints },
                    isLoading = false
                )
            }
        }
    }

    private fun loadAvailableRoutes() {
        viewModelScope.launch {
            repository.observeRoutes().collect { routes ->
                // Offer approved routes not already used as trunk in this network.
                val network = repository.getNetwork(networkId)
                val usedRouteIds = network?.segments?.map { it.baseRouteId }?.toSet() ?: emptySet()
                _state.update {
                    it.copy(availableRoutes = routes.filter { r -> r.isApproved && r.id !in usedRouteIds })
                }
            }
        }
    }

    fun selectRoute(routeId: Long) {
        viewModelScope.launch {
            val route = repository.getRoute(routeId) ?: return@launch
            _state.update {
                it.copy(
                    selectedRouteId = routeId,
                    selectedRoutePoints = route.smoothedPoints.ifEmpty { route.rawPoints }
                )
            }
        }
    }

    /** Called when the user taps the map over the trunk polyline. */
    fun onMapTap(lat: Double, lon: Double) {
        val trunk = _state.value.trunkPoints
        if (trunk.size < 2) return

        val snap = snapEngine.snap(
            routePoints        = trunk,
            lat                = lat,
            lon                = lon,
            nearCumulative     = -1.0,
            searchWindowMetres = Double.MAX_VALUE
        ) ?: run {
            _state.update { it.copy(snapMissHint = "Tap closer to the route (within 50 m)") }
            return
        }

        if (snap.offsetMetres > SNAP_RADIUS_M) {
            _state.update { it.copy(snapMissHint = "Tap closer to the route (within 50 m)") }
            return
        }

        val pin = SnapPin(snap.lat, snap.lon, snap.cumulativeDistanceMetres)

        _state.update {
            when (it.tapMode) {
                TapMode.SPLIT  -> it.copy(splitJunction = pin, tapMode = TapMode.REJOIN, snapMissHint = null)
                TapMode.REJOIN -> it.copy(rejoinJunction = pin, tapMode = TapMode.SPLIT, snapMissHint = null)
            }
        }
        recomputeSlicePreview()
    }

    /** Recompute the slice preview, no-op unless both pins are set. */
    private fun recomputeSlicePreview() {
        val s = _state.value
        val split  = s.splitJunction  ?: run { _state.update { it.copy(slicePreviewPoints = emptyList(), sliceDistanceHint = null) }; return }
        val rejoin = s.rejoinJunction ?: run { _state.update { it.copy(slicePreviewPoints = emptyList(), sliceDistanceHint = null) }; return }
        val trunk  = s.trunkPoints
        if (trunk.size < 2) return

        val from = minOf(split.cumulativeM, rejoin.cumulativeM)
        val to   = maxOf(split.cumulativeM, rejoin.cumulativeM)
        if (to - from < 1.0) return

        val cumDists = distanceCalc.cumulativeDistances(trunk, smoothedOnly = false)
        val pts = mutableListOf<RoutePoint>()

        val startPt = distanceCalc.interpolateAt(trunk, from, smoothedOnly = false)
        if (startPt != null) pts.add(RoutePoint(startPt.first, startPt.second, null, null, 0L, true))

        for (i in trunk.indices) {
            val d = cumDists.getOrElse(i) { 0.0 }
            if (d > from && d < to) pts.add(trunk[i])
        }

        val endPt = distanceCalc.interpolateAt(trunk, to, smoothedOnly = false)
        if (endPt != null) pts.add(RoutePoint(endPt.first, endPt.second, null, null, 0L, true))

        val sliceM = to - from
        val hint = if (sliceM >= 1_000) "${distanceFormatter.formatCompact(sliceM, currentUnit)} slice"
                   else "${sliceM.toInt()} m slice"

        _state.update { it.copy(slicePreviewPoints = pts, sliceDistanceHint = hint) }
    }

    fun clearPins() {
        _state.update { it.copy(splitJunction = null, rejoinJunction = null, tapMode = TapMode.SPLIT, slicePreviewPoints = emptyList(), sliceDistanceHint = null) }
    }

    /** Persist the branch segment (with optional junction pins) and mark saved. */
    fun saveBranch() {
        val s = _state.value
        val routeId = s.selectedRouteId ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                var fromJunctionId: Long? = null
                var toJunctionId:   Long? = null

                if (s.splitJunction != null) {
                    fromJunctionId = repository.saveJunction(
                        RouteJunction(0, networkId, s.splitJunction.lat, s.splitJunction.lon, "Split")
                    )
                }
                if (s.rejoinJunction != null) {
                    toJunctionId = repository.saveJunction(
                        RouteJunction(0, networkId, s.rejoinJunction.lat, s.rejoinJunction.lon, "Rejoin")
                    )
                }

                repository.addBranchSegment(
                    networkId      = networkId,
                    baseRouteId    = routeId,
                    fromJunctionId = fromJunctionId,
                    toJunctionId   = toJunctionId,
                    fromMetres     = s.splitJunction?.cumulativeM,
                    toMetres       = s.rejoinJunction?.cumulativeM
                )
                _state.update { it.copy(isSaving = false, isSaved = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, errorMessage = "Failed to save branch.") }
            }
        }
    }
}
