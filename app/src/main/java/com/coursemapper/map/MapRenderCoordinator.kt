package com.coursemapper.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Applies a [MapScene] through [MapOverlayManager]. Screens only build scenes.
 * Main thread, and only once [MapState.isReady].
 */
object MapRenderCoordinator {

    /**
     * Last applied scene. Navigation rebuilds the scene every fix but little
     * changes, so only differences are applied. Keyed on [Style] too, a reload
     * empties the map while the scene stays the same.
     */
    private class Applied(val map: MapLibreMap, val style: Style, val scene: MapScene)

    private var applied: Applied? = null

    /** Sync the map with [scene]. The diff is only an optimisation, overlays are idempotent. */
    fun apply(map: MapLibreMap?, scene: MapScene) {
        if (map == null) return
        val style = map.style ?: return
        val previous = applied?.takeIf { it.map === map && it.style === style }?.scene

        if (previous == null ||
            previous.routePoints != scene.routePoints ||
            previous.routeColorHex != scene.routeColorHex
        ) {
            MapOverlayManager.setRoute(map, scene.routePoints, scene.routeColorHex)
        }

        // additional polylines, compared by signature since deep comparison of
        // four courses was walking tens of thousands of pairs every second
        if (previous == null ||
            previous.additionalPolylines.map { it.renderSignature } !=
                scene.additionalPolylines.map { it.renderSignature }
        ) {
            if (scene.additionalPolylines.isNotEmpty()) {
                MapOverlayManager.setAdditionalPolylines(map, scene.additionalPolylines)
            } else {
                MapOverlayManager.clearAdditionalPolylines(map)
            }
        }

        if (previous == null || previous.approachLine != scene.approachLine) {
            val approach = scene.approachLine
            val approachPath = approach?.paths?.firstOrNull().orEmpty()
            if (approachPath.size >= 2) {
                MapOverlayManager.setApproachLine(
                    map, approachPath, approach!!.colorHex, approach.widthDp, approach.dashed
                )
            } else {
                MapOverlayManager.clearApproachLine(map)
            }
        }

        if (previous == null || previous.distanceMarkers != scene.distanceMarkers) {
            MapOverlayManager.setMarkers(map, scene.distanceMarkers)
        }
        if (previous == null || previous.placementStops != scene.placementStops) {
            MapOverlayManager.setPlacementStops(map, scene.placementStops)
        }

        if (previous == null || previous.junctionPins != scene.junctionPins) {
            if (scene.junctionPins.isNotEmpty()) {
                MapOverlayManager.setJunctionPins(map, scene.junctionPins)
            } else {
                MapOverlayManager.clearJunctionPins(map)
            }
        }

        if (previous == null || previous.activeTarget != scene.activeTarget) {
            val target = scene.activeTarget
            if (target != null) {
                MapOverlayManager.setActiveTarget(
                    map,
                    target.lat,
                    target.lon,
                    target.label,
                    target.placementAccuracyMetres
                )
            } else {
                MapOverlayManager.clearActiveTarget(map)
            }
        }

        val pos = scene.currentPosition
        if (pos != null && (
                previous == null ||
                    previous.currentPosition != pos ||
                    previous.currentBearingDeg != scene.currentBearingDeg
                )
        ) {
            MapOverlayManager.setCurrentPosition(map, pos.first, pos.second, scene.currentBearingDeg)
        }

        applied = Applied(map, style, scene)
    }

    /**
     * Move just the puck between scene updates, for screens animating it per
     * frame. Those build their scene with a null [MapScene.currentPosition].
     */
    fun applyPosition(map: MapLibreMap?, lat: Double, lon: Double, bearingDeg: Float?) {
        MapOverlayManager.setCurrentPosition(map, lat, lon, bearingDeg)
    }

    /** Drop cached references to a map being disposed. Called from `CourseMapView`. */
    fun release(map: MapLibreMap?) {
        if (map == null || applied?.map === map) applied = null
        MapOverlayManager.releaseHandles()
    }
}
