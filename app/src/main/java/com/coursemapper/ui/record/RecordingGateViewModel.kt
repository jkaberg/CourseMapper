package com.coursemapper.ui.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.location.LocationProvider
import com.coursemapper.location.LocationSettingsChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GateState {
    data object Initializing : GateState
    data class Green(val accuracyMetres: Float, val ageSeconds: Long) : GateState
    data class Yellow(val accuracyMetres: Float, val ageSeconds: Long) : GateState
    data object Red : GateState
    data object NoPermission : GateState
    data object SettingsRequired : GateState
}

data class RecordingGateUiState(
    val gateState: GateState = GateState.Initializing,
    val lastLocation: Location? = null,
    val settingsException: com.google.android.gms.common.api.ResolvableApiException? = null
)

@HiltViewModel
class RecordingGateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val locationProvider: LocationProvider,
    private val settingsChecker: LocationSettingsChecker,
    private val gpsEvaluator: GpsReadinessEvaluator
) : ViewModel() {

    private val _state = MutableStateFlow(RecordingGateUiState())
    val uiState: StateFlow<RecordingGateUiState> = _state.asStateFlow()

    init {
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

    private fun checkSettingsThenStartUpdates() {
        viewModelScope.launch {
            when (val result = settingsChecker.check()) {
                is LocationSettingsChecker.Result.Satisfied -> startLocationUpdates()
                is LocationSettingsChecker.Result.Resolvable -> {
                    _state.update {
                        it.copy(
                            gateState          = GateState.SettingsRequired,
                            settingsException  = result.exception
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
            locationProvider.locationUpdates()
                .collect { location ->
                    val readiness = gpsEvaluator.evaluate(location, GpsReadinessEvaluator.Context.RECORDING)
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

    fun retrySettings() {
        _state.update { it.copy(settingsException = null) }
        checkSettingsThenStartUpdates()
    }
}
