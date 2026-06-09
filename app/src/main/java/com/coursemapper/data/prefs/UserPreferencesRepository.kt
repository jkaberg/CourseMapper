package com.coursemapper.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coursemapper.domain.OfflineSizeEstimator
import com.coursemapper.domain.model.OfflineDownloadPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "user_prefs")

/** DataStore backed user settings. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDING_COMPLETE        = booleanPreferencesKey("onboarding_complete")
        val ABOUT_SEEN                 = booleanPreferencesKey("about_seen")
        val DISTANCE_UNIT              = stringPreferencesKey("distance_unit")
        val CLUSTERING_THRESHOLD_M     = doublePreferencesKey("clustering_threshold_m")
        val DWELL_RADIUS_M             = doublePreferencesKey("dwell_radius_m")
        val DWELL_SECONDS              = doublePreferencesKey("dwell_seconds")
        val POSITION_SNAP_RADIUS_M     = doublePreferencesKey("position_snap_radius_m")
        val COURSE_UP_CAMERA           = booleanPreferencesKey("course_up_camera")
        val LARGE_CONTROLS             = booleanPreferencesKey("large_controls")
        val AUTO_RETARGET              = booleanPreferencesKey("auto_retarget")
        val SKIP_GREEN_GATE            = booleanPreferencesKey("skip_green_gate")
        val OFFLINE_POLICY             = stringPreferencesKey("offline_download_policy")
        val OFFLINE_WIFI_ONLY          = booleanPreferencesKey("offline_wifi_only")
        val OFFLINE_BOTH_THEMES        = booleanPreferencesKey("offline_both_themes")
        val OFFLINE_BYTES_PER_TILE     = doublePreferencesKey("offline_bytes_per_tile")
    }

    /** `true` once the user has completed or dismissed the onboarding flow. */
    val isOnboardingComplete: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ONBOARDING_COMPLETE] ?: false }

    /**
     * Whether the About card has been shown. Separate from onboarding so
     * existing installs still get it.
     */
    val hasSeenAbout: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.ABOUT_SEEN] ?: false }

    /**
     * Preferred distance unit - either `"km"` (default) or `"mi"`.
     */
    val distanceUnit: Flow<String> = context.dataStore.data
        .map { it[Keys.DISTANCE_UNIT] ?: "km" }

    /** Clustering threshold for placement stops, metres (default 10, range 1-100). */
    val clusteringThresholdMetres: Flow<Double> = context.dataStore.data
        .map { it[Keys.CLUSTERING_THRESHOLD_M] ?: 10.0 }

    /** Arrival radius for dwell auto-complete, metres (default 25, range 10–50). */
    val dwellRadiusMetres: Flow<Double> = context.dataStore.data
        .map { it[Keys.DWELL_RADIUS_M] ?: 25.0 }

    /** Required dwell time inside the radius, seconds (default 4, range 2–15). */
    val dwellSeconds: Flow<Double> = context.dataStore.data
        .map { it[Keys.DWELL_SECONDS] ?: 4.0 }

    /**
     * Snap the drawn position to a course line within this many metres, 0 is off.
     * Only affects drawing, arrival and routing use the raw fix. See [PositionSnapPolicy].
     */
    val positionSnapRadiusMetres: Flow<Double> = context.dataStore.data
        .map { it[Keys.POSITION_SNAP_RADIUS_M] ?: 10.0 }

    /** Rotate the navigation map to the direction of travel (course-up). */
    val courseUpCamera: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.COURSE_UP_CAMERA] ?: true }

    /** Oversized touch targets and text for gloved, mounted-phone use. */
    val largeControls: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.LARGE_CONTROLS] ?: false }

    /**
     * Retarget to the next marker ahead when the rider turns around. On by
     * default, following the plan backwards is how markers get missed.
     * See [com.coursemapper.domain.DirectionalRetargetPolicy].
     */
    val autoRetarget: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.AUTO_RETARGET] ?: true }

    /**
     * Let the readiness gate continue by itself when GPS is green and tiles are
     * cached. Anything else still stops.
     */
    val skipGreenGate: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.SKIP_GREEN_GATE] ?: true }

    suspend fun completeOnboarding() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    suspend fun markAboutSeen() {
        context.dataStore.edit { it[Keys.ABOUT_SEEN] = true }
    }

    suspend fun setDistanceUnit(unit: String) {
        require(unit == "km" || unit == "mi") { "Invalid unit: $unit" }
        context.dataStore.edit { it[Keys.DISTANCE_UNIT] = unit }
    }

    suspend fun setClusteringThresholdMetres(value: Double) {
        require(value in 1.0..100.0) { "Clustering threshold out of range: $value" }
        context.dataStore.edit { it[Keys.CLUSTERING_THRESHOLD_M] = value }
    }

    suspend fun setDwellRadiusMetres(value: Double) {
        require(value in 10.0..50.0) { "Dwell radius out of range: $value" }
        context.dataStore.edit { it[Keys.DWELL_RADIUS_M] = value }
    }

    suspend fun setDwellSeconds(value: Double) {
        require(value in 2.0..15.0) { "Dwell seconds out of range: $value" }
        context.dataStore.edit { it[Keys.DWELL_SECONDS] = value }
    }

    /** 0 disables snapping; above that, 5–30 m is the useful band. */
    suspend fun setPositionSnapRadiusMetres(value: Double) {
        require(value == 0.0 || value in 5.0..30.0) { "Snap radius out of range: $value" }
        context.dataStore.edit { it[Keys.POSITION_SNAP_RADIUS_M] = value }
    }

    suspend fun setCourseUpCamera(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COURSE_UP_CAMERA] = enabled }
    }

    suspend fun setLargeControls(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LARGE_CONTROLS] = enabled }
    }

    suspend fun setAutoRetarget(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_RETARGET] = enabled }
    }

    suspend fun setSkipGreenGate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SKIP_GREEN_GATE] = enabled }
    }

    /**
     * Ask, always or never download an offline map when creating a course.
     * Defaults to ask, it's the biggest download the app does.
     */
    val offlineDownloadPolicy: Flow<OfflineDownloadPolicy> = context.dataStore.data
        .map { OfflineDownloadPolicy.fromKey(it[Keys.OFFLINE_POLICY]) }

    /**
     * Only download on unmetered networks. Off by default, refusing on a venue
     * hotspot defeats the point and the prompt shows the size anyway.
     */
    val offlineWifiOnly: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.OFFLINE_WIFI_ONLY] ?: false }

    /**
     * Download both light and dark basemaps. On by default, a missing style
     * renders as a blank map when the system switches theme.
     */
    val offlineBothThemes: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.OFFLINE_BOTH_THEMES] ?: true }

    /**
     * Measured bytes per tile from the last completed pack, used for the next
     * estimate. See [com.coursemapper.domain.OfflineSizeEstimator.estimateBytes].
     */
    val offlineBytesPerTile: Flow<Double> = context.dataStore.data
        .map { it[Keys.OFFLINE_BYTES_PER_TILE] ?: OfflineSizeEstimator.DEFAULT_BYTES_PER_TILE }

    suspend fun setOfflineDownloadPolicy(policy: OfflineDownloadPolicy) {
        context.dataStore.edit { it[Keys.OFFLINE_POLICY] = policy.name }
    }

    suspend fun setOfflineWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_WIFI_ONLY] = enabled }
    }

    suspend fun setOfflineBothThemes(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OFFLINE_BOTH_THEMES] = enabled }
    }

    suspend fun setOfflineBytesPerTile(value: Double) {
        require(value > 0.0) { "Bytes per tile must be positive: $value" }
        context.dataStore.edit { it[Keys.OFFLINE_BYTES_PER_TILE] = value }
    }
}
