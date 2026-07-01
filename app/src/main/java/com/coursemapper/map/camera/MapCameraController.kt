package com.coursemapper.map.camera

import com.coursemapper.domain.FollowCameraSmoother
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapViewportPadding
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/**
 * Navigation camera: fit to route, per-frame follow, recenter. Main thread,
 * gated on [MapState.isReady].
 */
object MapCameraController {

    /**
     * Where the rider sits in follow mode, as a fraction of the unobstructed map
     * band from the top. A screen fraction instead of a ground distance so the
     * puck can't slide off screen with speed, zoom or tilt.
     */
    const val FOLLOW_ANCHOR_FRACTION = 0.65

    /**
     * [FOLLOW_ANCHOR_FRACTION] as a fraction of the whole viewport, with banner and
     * controls covering [topChromePx] and [bottomChromePx]:
     *
     *     anchorY = top + fraction × (height − top − bottom)
     *
     * Unchanged while the chrome isn't measured yet.
     */
    fun anchorFractionInBand(
        viewportHeightPx: Int,
        topChromePx: Int,
        bottomChromePx: Int,
        bandFraction: Double = FOLLOW_ANCHOR_FRACTION
    ): Double {
        if (viewportHeightPx <= 0) return bandFraction
        val top = topChromePx.coerceIn(0, viewportHeightPx)
        val bottom = bottomChromePx.coerceIn(0, viewportHeightPx)
        val band = viewportHeightPx - top - bottom
        if (band <= 0) return bandFraction
        return ((top + bandFraction * band) / viewportHeightPx).coerceIn(0.0, 1.0)
    }

    /**
     * Padding that puts the camera target at [anchorFraction] down the viewport.
     * MapLibre centres the target in the padded box, so it's all top padding:
     *
     *     top - bottom = (2 × anchor - 1) × height
     *
     * Zero before the viewport is measured.
     */
    fun anchorPadding(
        viewportHeightPx: Int,
        anchorFraction: Double = FOLLOW_ANCHOR_FRACTION
    ): MapViewportPadding {
        if (viewportHeightPx <= 0) return MapViewportPadding()
        val offset = ((2.0 * anchorFraction - 1.0) * viewportHeightPx).roundToInt()
        return if (offset >= 0) {
            MapViewportPadding(top = offset.coerceAtMost(viewportHeightPx - 1))
        } else {
            MapViewportPadding(bottom = (-offset).coerceAtMost(viewportHeightPx - 1))
        }
    }

    /** Fit to [routePoints], at start and for Overview. [northUp] resets rotation. */
    fun fitRoute(
        map: MapLibreMap?,
        routePoints: List<Pair<Double, Double>>,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        northUp: Boolean = false
    ) {
        MapOverlayManager.fitCamera(
            map, routePoints,
            viewportPadding = viewportPadding,
            bearingDeg      = if (northUp) 0.0 else null
        )
    }

    /** One frame of follow mode, an immediate move since [frame] is already smoothed. */
    fun followFrame(
        map: MapLibreMap?,
        frame: FollowCameraSmoother.Frame,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        courseUp: Boolean = true
    ) {
        MapOverlayManager.setCamera(
            map,
            lat = frame.lat,
            lon = frame.lon,
            zoom = frame.zoom,
            viewportPadding = viewportPadding,
            bearingDeg = if (courseUp) frame.cameraBearingDeg else 0.0,
            tiltDeg = frame.tiltDeg
        )
    }

    /** How long [easeToFrame] takes, milliseconds. */
    const val EASE_DURATION_MS = MapOverlayManager.CAMERA_ANIMATION_MS.toLong()

    /**
     * Animate to [frame] over [EASE_DURATION_MS], for coming back from browse
     * mode. The caller holds off per-frame moves meanwhile.
     */
    fun easeToFrame(
        map: MapLibreMap?,
        frame: FollowCameraSmoother.Frame,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        courseUp: Boolean = true
    ) {
        MapOverlayManager.moveCamera(
            map,
            lat = frame.lat,
            lon = frame.lon,
            zoom = frame.zoom,
            viewportPadding = viewportPadding,
            bearingDeg = if (courseUp) frame.cameraBearingDeg else 0.0,
            tiltDeg = frame.tiltDeg
        )
    }
}
