package com.coursemapper.ui.navigate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.offline.OfflineReconciler
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.location.LocationProvider
import com.coursemapper.location.LocationSettingsChecker
import com.coursemapper.ui.record.GateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Offline readiness is kept apart from GPS state, a GPS problem must never look like a missing map. */
data class NavigationGateUiState(
    val gateState: GateState = GateState.Initializing,
    val lastLocation: Location? = null,
    val settingsException: com.google.android.gms.common.api.ResolvableApiException? = null,
    /** Worst pack state across the run's courses, null when there's no pack. */
    val offlineStatus: OfflinePackStatus? = null,
    /** True until the first coverage reading arrives. */
    val offlineChecking: Boolean = true,
    /** Live download fraction, or null while it cannot be stated honestly. */
    val offlineProgress: Float? = null,
    /** Why the pack stopped, when [offlineStatus] is FAILED. */
    val offlineFailure: String? = null,
    /** Size of the download the gate is offering, once quoted. */
    val offlineEstimatedBytes: Long? = null,
    val runName: String? = null,
    val stopCount: Int = 0,
    /** The Settings toggle; false pins the gate to its old always-stop behaviour. */
    val autoAdvanceEnabled: Boolean = true,
    /** Set by Wait - cancels auto-advance for the rest of this visit. */
    val autoAdvanceCancelled: Boolean = false
) {
    /**
     * Whether the gate may continue by itself: green GPS and a pack that is at
     * least on its way (QUEUED and DOWNLOADING pass, navigation falls back to
     * online tiles). PAUSED doesn't, a partial map isn't coverage. The countdown
     * lives in the composable, this is just the predicate.
     */
    val canAutoAdvance: Boolean
        get() = autoAdvanceEnabled &&
            !autoAdvanceCancelled &&
            gateState is GateState.Green &&
            !offlineChecking &&
            offlineStatus != null &&
            offlineStatus != OfflinePackStatus.FAILED &&
            offlineStatus != OfflinePackStatus.PAUSED
}

/** Readiness gate before a run: GPS and offline coverage for all courses, plus the packing list. */
@HiltViewModel
class NavigationGateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider,
    private val settingsChecker: LocationSettingsChecker,
    private val gpsEvaluator: GpsReadinessEvaluator,
    private val reconciler: OfflineReconciler,
    private val repository: RouteRepository,
    private val userPreferences: com.coursemapper.data.prefs.UserPreferencesRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val runId: Long = savedStateHandle["runId"] ?: -1L

    private val _state = MutableStateFlow(NavigationGateUiState())
    val uiState: StateFlow<NavigationGateUiState> = _state.asStateFlow()

    private var courseIds: List<Long> = emptyList()

    init {
        viewModelScope.launch {
            val run = repository.getRun(runId)
            courseIds = run?.courseIds.orEmpty()
            _state.update { it.copy(runName = run?.name, stopCount = run?.stops?.size ?: 0) }
            observeOfflineCoverage()
        }
        viewModelScope.launch {
            userPreferences.skipGreenGate.collect { enabled ->
                _state.update { it.copy(autoAdvanceEnabled = enabled) }
            }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
        ) {
            _state.update { it.copy(gateState = GateState.NoPermission) }
        } else {
            checkSettingsThenStartUpdates()
        }
    }

    /** Called after the user grants location permission from the gate screen. */
    fun onPermissionGranted() {
        _state.update { it.copy(gateState = GateState.Initializing) }
        checkSettingsThenStartUpdates()
    }

    /**
     * Download a pack for all courses in the run. Ignores the download policy,
     * being at the start line without a map overrides an earlier "not now".
     */
    fun requestOfflineDownload() {
        if (courseIds.isEmpty()) return
        _state.update {
            it.copy(offlineStatus = OfflinePackStatus.DOWNLOADING, offlineChecking = false)
        }
        viewModelScope.launch {
            val proposal = repository.quoteOfflinePack(courseIds)
            if (proposal == null) {
                _state.update {
                    it.copy(
                        offlineStatus = OfflinePackStatus.FAILED,
                        offlineFailure = "These courses have no usable route to map"
                    )
                }
                return@launch
            }
            if (!proposal.quote.isViable) {
                _state.update {
                    it.copy(
                        offlineStatus = OfflinePackStatus.FAILED,
                        offlineFailure = if (proposal.quote.hasRoom) {
                            "This area is too large to download as one map"
                        } else {
                            "Not enough free space on this device"
                        }
                    )
                }
                return@launch
            }
            repository.startOfflineDownload(proposal)
        }
    }

    /** Worst offline state across the run's courses, from the pack tables. */
    private fun observeOfflineCoverage() {
        if (courseIds.isEmpty()) {
            _state.update { it.copy(offlineStatus = null, offlineChecking = false) }
            return
        }
        viewModelScope.launch {
            // The other moment worth checking that Room and MapLibre still
            // agree - see [OfflineReconciler].  A pack whose tiles were cleared
            // would otherwise show green here over an empty map.
            reconciler.reconcile()

            val quoted = repository.quoteOfflinePack(courseIds)
            _state.update { it.copy(offlineEstimatedBytes = quoted?.quote?.estimatedBytes) }
        }
        viewModelScope.launch {
            repository.observeOfflineCoverage(courseIds).collect { coverage ->
                _state.update {
                    it.copy(
                        offlineStatus  = coverage.status,
                        offlineChecking = false,
                        offlineProgress = coverage.progress?.fraction,
                        offlineFailure = coverage.failureReason?.let(::describeFailure)
                    )
                }
            }
        }
    }

    private fun describeFailure(reason: OfflineFailureReason): String = when (reason) {
        OfflineFailureReason.NETWORK -> "The connection dropped before the map finished"
        OfflineFailureReason.TILE_LIMIT -> "This area is too large to download as one map"
        OfflineFailureReason.DISK -> "Not enough free space on this device"
        OfflineFailureReason.NO_GEOMETRY -> "These courses have no usable route to map"
        OfflineFailureReason.MISSING_TILES -> "The downloaded map is no longer on this device"
        OfflineFailureReason.UNKNOWN -> "The map download did not finish"
    }

    private fun checkSettingsThenStartUpdates() {
        viewModelScope.launch {
            when (val result = settingsChecker.check()) {
                is LocationSettingsChecker.Result.Satisfied  -> startLocationUpdates()
                is LocationSettingsChecker.Result.Resolvable -> {
                    _state.update {
                        it.copy(
                            gateState         = GateState.SettingsRequired,
                            settingsException = result.exception
                        )
                    }
                }
                is LocationSettingsChecker.Result.Failed -> {
                    _state.update { it.copy(gateState = GateState.Red) }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        viewModelScope.launch {
            locationProvider.locationUpdates().collect { location ->
                val readiness = gpsEvaluator.evaluate(location, GpsReadinessEvaluator.Context.NAVIGATION)
                val gateState = when (readiness) {
                    is GpsReadinessEvaluator.GpsReadiness.Green  ->
                        GateState.Green(readiness.accuracyMetres, readiness.ageSeconds)
                    is GpsReadinessEvaluator.GpsReadiness.Yellow ->
                        GateState.Yellow(readiness.accuracyMetres, readiness.ageSeconds)
                    is GpsReadinessEvaluator.GpsReadiness.Red    -> GateState.Red
                }
                _state.update { it.copy(gateState = gateState, lastLocation = location) }
            }
        }
    }

    /** Stop the countdown for the rest of this visit. */
    fun cancelAutoAdvance() {
        _state.update { it.copy(autoAdvanceCancelled = true) }
    }

    fun retrySettings() {
        _state.update { it.copy(settingsException = null) }
        checkSettingsThenStartUpdates()
    }
}
