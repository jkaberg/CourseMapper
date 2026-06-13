package com.coursemapper.ui.settings

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.PlacementPlanner
import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.offline.OfflineMapRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val distanceUnit: String = "km",
    val clusteringThresholdMetres: Double = 10.0,
    /** Arrival radius for dwell auto-complete, metres. */
    val dwellRadiusMetres: Double = 25.0,
    /** Required dwell time inside the radius, seconds. */
    val dwellSeconds: Double = 4.0,
    /** Rotate the run map to the direction of travel. */
    val courseUpCamera: Boolean = true,
    /** Oversized run controls for gloved use. */
    val largeControls: Boolean = false,
    /** Re-target to the next marker ahead when the rider turns around. */
    val autoRetarget: Boolean = true,
    /** Let the readiness gate advance itself when GPS is green and tiles are cached. */
    val skipGreenGate: Boolean = true,
    /** Snap radius for the drawn position marker, metres; 0 = off. */
    val positionSnapRadiusMetres: Double = 10.0,
    /** Whether creating a course offers an offline map, downloads one, or neither. */
    val offlineDownloadPolicy: OfflineDownloadPolicy = OfflineDownloadPolicy.ASK,
    /** Hold map downloads until the device is on an unmetered network. */
    val offlineWifiOnly: Boolean = false,
    /** Download the light and dark basemap, not just the current one. */
    val offlineBothThemes: Boolean = true,
    /** Bytes of offline maps on the device, for the Map Storage row. */
    val offlineTotalBytes: Long = 0L,
    /** True when CourseMapper is exempt from battery optimisation (Unrestricted). */
    val isBatteryUnrestricted: Boolean = false,
    /** True when the device location services are enabled. */
    val isLocationEnabled: Boolean = false
)

/** The run-screen preferences, grouped so they fit one [combine] arm. */
private data class RunPrefs(
    val courseUpCamera: Boolean,
    val largeControls: Boolean,
    val positionSnapRadiusMetres: Double,
    val autoRetarget: Boolean,
    val skipGreenGate: Boolean
)

/** The offline-map preferences, grouped so they fit one [combine] arm. */
private data class OfflinePrefs(
    val policy: OfflineDownloadPolicy,
    val wifiOnly: Boolean,
    val bothThemes: Boolean,
    val totalBytes: Long
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val offlineMapRepository: OfflineMapRepository,
    private val repository: RouteRepository,
    private val placementPlanner: PlacementPlanner,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val powerManager    = context.getSystemService(Context.POWER_SERVICE)    as PowerManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _isBatteryUnrestricted = MutableStateFlow(false)
    private val _isLocationEnabled     = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(prefs.distanceUnit, prefs.clusteringThresholdMetres) { unit, threshold ->
            unit to threshold
        },
        combine(prefs.dwellRadiusMetres, prefs.dwellSeconds) { radius, seconds ->
            radius to seconds
        },
        combine(
            prefs.courseUpCamera,
            prefs.largeControls,
            prefs.positionSnapRadiusMetres,
            prefs.autoRetarget,
            prefs.skipGreenGate
        ) { courseUp, large, snapRadius, autoRetarget, skipGate ->
            RunPrefs(courseUp, large, snapRadius, autoRetarget, skipGate)
        },
        combine(
            prefs.offlineDownloadPolicy,
            prefs.offlineWifiOnly,
            prefs.offlineBothThemes,
            offlineMapRepository.observeTotalBytes()
        ) { policy, wifiOnly, bothThemes, totalBytes ->
            OfflinePrefs(policy, wifiOnly, bothThemes, totalBytes)
        },
        // Battery and location paired so the offline arm fits: `combine` takes
        // five flows, and these two are read together anyway.
        combine(_isBatteryUnrestricted, _isLocationEnabled) { battery, location ->
            battery to location
        }
    ) { (unit, threshold), (radius, seconds), run, offline, (battery, location) ->
        SettingsUiState(
            distanceUnit              = unit,
            clusteringThresholdMetres = threshold,
            dwellRadiusMetres         = radius,
            dwellSeconds              = seconds,
            courseUpCamera            = run.courseUpCamera,
            largeControls             = run.largeControls,
            autoRetarget              = run.autoRetarget,
            skipGreenGate             = run.skipGreenGate,
            positionSnapRadiusMetres  = run.positionSnapRadiusMetres,
            offlineDownloadPolicy     = offline.policy,
            offlineWifiOnly           = offline.wifiOnly,
            offlineBothThemes         = offline.bothThemes,
            offlineTotalBytes         = offline.totalBytes,
            isBatteryUnrestricted     = battery,
            isLocationEnabled         = location
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    init { refreshSystemState() }

    /** Re-read live battery and location state from the OS (call on screen resume). */
    fun refreshSystemState() {
        _isBatteryUnrestricted.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        _isLocationEnabled.value     = LocationManagerCompat.isLocationEnabled(locationManager)
    }

    /** OS dialog to exempt the app from battery optimisation. */
    fun requestBatteryUnrestricted(activityContext: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        activityContext.startActivity(intent)
    }

    /** Opens the system Location Settings screen. */
    fun openLocationSettings(activityContext: Context) {
        activityContext.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    fun setDistanceUnit(unit: String) {
        viewModelScope.launch { prefs.setDistanceUnit(unit) }
    }

    fun setOfflineDownloadPolicy(policy: OfflineDownloadPolicy) {
        viewModelScope.launch { prefs.setOfflineDownloadPolicy(policy) }
    }

    fun setOfflineWifiOnly(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfflineWifiOnly(enabled) }
    }

    fun setOfflineBothThemes(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfflineBothThemes(enabled) }
    }

    fun setDwellRadius(value: Double) {
        viewModelScope.launch { prefs.setDwellRadiusMetres(value.coerceIn(10.0, 50.0)) }
    }

    fun setDwellSeconds(value: Double) {
        viewModelScope.launch { prefs.setDwellSeconds(value.coerceIn(2.0, 15.0)) }
    }

    fun setPositionSnapRadius(value: Double) {
        viewModelScope.launch { prefs.setPositionSnapRadiusMetres(value) }
    }

    fun setCourseUpCamera(enabled: Boolean) {
        viewModelScope.launch { prefs.setCourseUpCamera(enabled) }
    }

    fun setLargeControls(enabled: Boolean) {
        viewModelScope.launch { prefs.setLargeControls(enabled) }
    }

    fun setAutoRetarget(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoRetarget(enabled) }
    }

    fun setSkipGreenGate(enabled: Boolean) {
        viewModelScope.launch { prefs.setSkipGreenGate(enabled) }
    }

    fun setClusteringThreshold(value: Double) {
        viewModelScope.launch {
            prefs.setClusteringThresholdMetres(value)
            // replan stops for courses that have them so the new threshold applies
            // now. Runs keep their own snapshot.
            val courses = repository.observeCourses().first()
            courses.forEach { course ->
                val existingStops = repository.getStops(course.id)
                if (existingStops.isNotEmpty()) {
                    val result = placementPlanner.plan(
                        markers                 = course.markers,
                        courseId                = course.id,
                        groupingThresholdMetres = value
                    )
                    repository.savePlacementStops(course.id, result.stops)
                }
            }
        }
    }
}

