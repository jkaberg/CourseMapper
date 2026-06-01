package com.coursemapper.ui.record

import android.content.Context
import android.location.Location
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.domain.LocationFilter
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.location.LocationProvider
import com.coursemapper.location.RecordingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RecordingUiState(
    val routeId: Long = -1L,
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val distanceMetres: Double = 0.0,
    val pointCount: Int = 0,
    val currentAccuracyMetres: Float? = null,
    val gpsQualityLabel: String = "Acquiring GPS…",
    /** Markers the selected preset would have auto-placed by the current distance. */
    val autoMarkerCount: Int = 0,
    val isStopped: Boolean = false,
    /** Set when the user discards the recording; triggers navigation home. */
    val isDiscarded: Boolean = false,
    val error: String? = null
)

private const val FLUSH_INTERVAL_POINTS = 20

@HiltViewModel
class RecordingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RouteRepository,
    private val locationProvider: LocationProvider,
    private val locationFilter: LocationFilter,
    private val gpsEvaluator: GpsReadinessEvaluator,
    private val distanceCalc: com.coursemapper.domain.CumulativeDistanceCalculator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val routeName: String   = savedStateHandle["routeName"]   ?: ""
    val notes: String       = savedStateHandle["notes"]       ?: ""
    val presetId: Long      = savedStateHandle["presetId"]    ?: -1L
    val lapCount: Int       = savedStateHandle["lapCount"]    ?: 1
    val targetDistanceCm: Long = savedStateHandle["targetDistanceCm"] ?: 0L
    val includeStart: Boolean  = savedStateHandle["includeStart"]  ?: false
    val includeFinish: Boolean = savedStateHandle["includeFinish"] ?: false

    private val _state = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _state.asStateFlow()

    // Persistent coordinate accumulator for live map display - never cleared on flush.
    private val allCoords = mutableListOf<Pair<Double, Double>>()
    private val _liveCoords = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val liveCoords: StateFlow<List<Pair<Double, Double>>> = _liveCoords.asStateFlow()

    // In-memory accumulator - flushed periodically to the repository
    private val pendingPoints = mutableListOf<RoutePoint>()
    private var totalAcceptedCount = 0
    private var cumulativeDistanceM = 0.0
    private var lastAcceptedLat: Double? = null
    private var lastAcceptedLon: Double? = null
    private var locationJob: Job? = null
    private var timerJob: Job? = null
    private var sessionStartMs = 0L

    /** Marker rules of the selected preset; drives the live auto-marker count. */
    private var presetRules: List<com.coursemapper.domain.model.MarkerRule> = emptyList()

    init {
        startSession()
    }

    private fun startSession() {
        viewModelScope.launch {
            val routeId = repository.createRoute(
                name   = routeName.ifBlank { "Unnamed route" },
                notes  = notes,
                source = "recorded"
            )
            if (presetId > 0) {
                presetRules = repository.getPreset(presetId)?.rules.orEmpty()
            }
            locationFilter.reset()
            _state.update { it.copy(routeId = routeId, isRecording = true) }
            sessionStartMs = System.currentTimeMillis()
            context.startForegroundService(RecordingForegroundService.startIntent(context, routeId))
            startTimer()
            startLocationCollection(routeId)
        }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                if (!_state.value.isPaused) {
                    _state.update {
                        it.copy(elapsedMs = System.currentTimeMillis() - sessionStartMs)
                    }
                }
                delay(1_000)
            }
        }
    }

    private fun startLocationCollection(routeId: Long) {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationProvider.locationUpdates()
                .collect { location ->
                    if (_state.value.isPaused) return@collect
                    processLocation(location, routeId)
                }
        }
    }

    private suspend fun processLocation(location: Location, routeId: Long) {
        val result = locationFilter.evaluate(location)
        val point = RoutePoint(
            lat            = location.latitude,
            lon            = location.longitude,
            altMetres      = if (location.hasAltitude()) location.altitude else null,
            accuracyMetres = location.accuracy,
            timestampMs    = location.time,
            isSmoothed     = result.shouldUseForRoute
        )

        val readiness = gpsEvaluator.evaluate(location, GpsReadinessEvaluator.Context.RECORDING)
        val qualityLabel = when (readiness) {
            is GpsReadinessEvaluator.GpsReadiness.Green  -> "GPS good · ±${location.accuracy.toInt()} m"
            is GpsReadinessEvaluator.GpsReadiness.Yellow -> "GPS weak · ±${location.accuracy.toInt()} m"
            is GpsReadinessEvaluator.GpsReadiness.Red    -> "GPS poor · ±${location.accuracy.toInt()} m"
        }

        synchronized(pendingPoints) { pendingPoints.add(point) }
        if (point.isSmoothed) {
            totalAcceptedCount++
            // Running haversine from the last accepted fix.
            val prevLat = lastAcceptedLat
            val prevLon = lastAcceptedLon
            if (prevLat != null && prevLon != null) {
                cumulativeDistanceM += distanceCalc.haversineMetres(prevLat, prevLon, point.lat, point.lon)
            }
            lastAcceptedLat = point.lat
            lastAcceptedLon = point.lon
        }

        // Always accumulate for live map display (never cleared on flush).
        synchronized(allCoords) { allCoords.add(point.lat to point.lon) }
        _liveCoords.value = synchronized(allCoords) { allCoords.toList() }

        _state.update { s ->
            s.copy(
                pointCount            = totalAcceptedCount,
                distanceMetres        = cumulativeDistanceM,
                currentAccuracyMetres = location.accuracy,
                gpsQualityLabel       = qualityLabel,
                autoMarkerCount       = computeAutoMarkerCount(cumulativeDistanceM)
            )
        }

        // Periodic flush to DB
        if (synchronized(pendingPoints) { pendingPoints.size } >= FLUSH_INTERVAL_POINTS) {
            flushPoints(routeId)
        }
    }

    fun togglePause() {
        val nowPaused = !_state.value.isPaused
        if (!nowPaused) {
            // Resuming: rebase the session start so the paused interval is not
            // counted.  (The timer freezes elapsedMs while paused; without this
            // rebase, elapsed would jump forward by the pause duration.)
            sessionStartMs = System.currentTimeMillis() - _state.value.elapsedMs
        }
        _state.update { it.copy(isPaused = nowPaused) }
    }

    fun stopRecording() {
        locationJob?.cancel()
        timerJob?.cancel()
        val routeId = _state.value.routeId
        viewModelScope.launch {
            flushPoints(routeId)
            repository.approveRoute(routeId)
            context.startService(RecordingForegroundService.stopIntent(context))
            _state.update { it.copy(isRecording = false, isStopped = true) }
        }
    }

    /** Stop GPS and the service, soft-delete the route and go home. */
    fun discardRecording() {
        locationJob?.cancel()
        timerJob?.cancel()
        val routeId = _state.value.routeId
        viewModelScope.launch {
            context.startService(RecordingForegroundService.stopIntent(context))
            if (routeId > 0) repository.deleteRoute(routeId)
            _state.update { it.copy(isRecording = false, isDiscarded = true) }
        }
    }

    /** Markers the preset would have placed within [distM]. */
    private fun computeAutoMarkerCount(distM: Double): Int = presetRules.sumOf { rule ->
        when (rule.type) {
            com.coursemapper.domain.model.MarkerType.DISTANCE,
            com.coursemapper.domain.model.MarkerType.CHECKPOINT,
            com.coursemapper.domain.model.MarkerType.WATER_STATION ->
                if (rule.intervalMetres > 0.0) (distM / rule.intervalMetres).toInt() else 0
            com.coursemapper.domain.model.MarkerType.CUSTOM_DISTANCES ->
                rule.customDistancesMetres.count { it > 0.0 && it <= distM }
            else -> 0
        }
    }

    private suspend fun flushPoints(routeId: Long) {
        val batch = synchronized(pendingPoints) {
            val copy = pendingPoints.toList()
            pendingPoints.clear()
            copy
        }
        if (batch.isNotEmpty()) {
            repository.saveRoutePoints(routeId, batch)
        }
    }

    override fun onCleared() {
        locationJob?.cancel()
        timerJob?.cancel()
        // if we die mid-recording (disposed without Stop or Discard) take the
        // notification down. startService is synchronous so it's fine in onCleared.
        if (_state.value.isRecording) {
            context.startService(RecordingForegroundService.stopIntent(context))
        }
        super.onCleared()
    }

}
