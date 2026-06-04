package com.coursemapper.map

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/*
 * All MapLibre surface calls live in this file, nothing outside `map` imports
 * [MapView] or [MapLibreMap]. Overlays go through [MapOverlayManager].
 */

/** Initialise MapLibre once per process.  Call from [Application.onCreate]. */
fun initMapLibre(context: Context) {
    MapLibre.getInstance(context)
}

/** OpenFreeMap, free and no API key. https://openfreemap.org */
const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/bright"
const val STYLE_URL_DARK  = "https://tiles.openfreemap.org/styles/dark"

/**
 * Fix up a freshly loaded style, see [DarkMapStyleTuning]. Runs on every load
 * since a reload throws away previous overrides.
 */
private fun tuneBasemapLegibility(styleUrl: String, style: Style) {
    if (styleUrl == STYLE_URL_DARK) DarkMapStyleTuning.apply(style)
}

data class MapViewportPadding(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

/**
 * Holds the [MapLibreMap]. [isReady] goes true when a style has loaded and false
 * while disposed or reloading, so screens gating on it re-apply overlays after a
 * style change. Use [rememberMapState].
 */
class MapState {
    var map: MapLibreMap? = null
        internal set

    /** True once the style has finished loading and overlays can be applied. */
    var isReady: Boolean by mutableStateOf(false)
        internal set

    /** URL of the style that is currently loaded into the map. */
    internal var loadedStyleUrl: String? = null

    /** Clear map, readiness and style on dispose. */
    internal fun invalidate() {
        map            = null
        isReady        = false
        loadedStyleUrl = null
    }
}

@Composable
fun rememberMapState(): MapState = remember { MapState() }

/**
 * Compose wrapper around [MapView]. Forwards lifecycle events, clears
 * [MapState] on dispose, and reloads the style when [styleUrl] changes.
 *
 * @param onMapReady called when map and style are loaded, also after reloads
 * @param onUserGesture user panned or zoomed, used to leave follow mode
 */
@Composable
fun CourseMapView(
    modifier: Modifier = Modifier,
    state: MapState = rememberMapState(),
    styleUrl: String = if (isSystemInDarkTheme()) STYLE_URL_DARK else STYLE_URL_LIGHT,
    viewportPadding: MapViewportPadding = MapViewportPadding(),
    onMapReady: (MapLibreMap) -> Unit = {},
    onMapClick: ((lat: Double, lon: Double) -> Unit)? = null,
    onUserGesture: (() -> Unit)? = null
) {
    // rememberUpdatedState lets lambdas captured in factory always call the
    // latest version even if the caller recomposes with new lambda instances.
    val latestOnMapReady by rememberUpdatedState(onMapReady)
    val latestOnMapClick by rememberUpdatedState(onMapClick)
    val latestOnGesture  by rememberUpdatedState(onUserGesture)

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Forward lifecycle events to MapView; invalidate state on disposal.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapViewRef ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START   -> mv.onStart()
                Lifecycle.Event.ON_RESUME  -> mv.onResume()
                Lifecycle.Event.ON_PAUSE   -> mv.onPause()
                Lifecycle.Event.ON_STOP    -> mv.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    state.invalidate()
                    mv.onDestroy()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // The render caches are process-wide single slots holding this map
            // and its Style, and a Style holds the native map behind it.  This
            // is the one place that knows the map is finished.
            MapRenderCoordinator.release(state.map)
            state.invalidate()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { mv ->
                mapViewRef = mv
                mv.getMapAsync { mapLibreMap ->
                    mapLibreMap.setPadding(
                        viewportPadding.left,
                        viewportPadding.top,
                        viewportPadding.right,
                        viewportPadding.bottom
                    )
                    // Gesture listener - always registered; delegates to the
                    // latest callback via rememberUpdatedState.
                    mapLibreMap.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            latestOnGesture?.invoke()
                        }
                    }
                    // Click listener - always registered; no-ops if null.
                    mapLibreMap.addOnMapClickListener { latLng ->
                        latestOnMapClick?.invoke(latLng.latitude, latLng.longitude)
                        true
                    }
                    // Load initial style; record the URL so reactive updates can diff.
                    mapLibreMap.setStyle(styleUrl) { loadedStyle ->
                        tuneBasemapLegibility(styleUrl, loadedStyle)
                        state.map            = mapLibreMap
                        state.loadedStyleUrl = styleUrl
                        state.isReady        = true
                        latestOnMapReady(mapLibreMap)
                    }
                }
            }
        },
        update = { _ ->
            // reload on styleUrl change, skip if a reload is already in flight
            val currentMap = state.map
            if (currentMap != null && state.isReady && state.loadedStyleUrl != styleUrl) {
                state.isReady = false   // signals screens to pause overlay updates
                currentMap.setStyle(styleUrl) { loadedStyle ->
                    tuneBasemapLegibility(styleUrl, loadedStyle)
                    state.loadedStyleUrl = styleUrl
                    state.isReady        = true   // re-triggers screen effects
                    latestOnMapReady(currentMap)
                }
            }

            currentMap?.setPadding(
                viewportPadding.left,
                viewportPadding.top,
                viewportPadding.right,
                viewportPadding.bottom
            )
        }
    )
}
