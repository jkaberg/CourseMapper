package com.coursemapper.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.google.gson.JsonObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

/**
 * App overlays on a [MapLibreMap]: route, markers, stops, position and target.
 * Main thread only.
 *
 * Everything is a style layer (no legacy `Marker` annotations) so z-order is
 * fixed by [LAYER_STACK] via [insertInOrder]. Every set* call is idempotent.
 */
object MapOverlayManager {

    private const val SOURCE_ROUTE         = "cm_route_src"
    private const val SOURCE_APPROACH      = "cm_approach_src"
    private const val SOURCE_POSITION      = "cm_position_src"
    private const val SOURCE_MARKERS       = "cm_markers_src"
    private const val SOURCE_MARKERS_TEXT  = "cm_markers_text_src"
    private const val SOURCE_ACTIVE_TARGET = "cm_active_target_src"
    private const val SOURCE_TARGET_PLACEMENT_AREA = "cm_target_placement_area_src"
    private const val SOURCE_JUNCTION_PINS  = "cm_junction_pins_src"
    private const val SOURCE_STOPS_PENDING  = "cm_stops_pending_src"
    private const val SOURCE_STOPS_DONE     = "cm_stops_done_src"
    private const val SOURCE_STOPS_SKIPPED  = "cm_stops_skipped_src"
    private const val SOURCE_STOPS_TEXT     = "cm_stops_text_src"
    private const val SOURCE_POSITION_ARROW = "cm_position_arrow_src"
    private const val IMAGE_POSITION_PUCK   = "cm_position_puck_image"

    /**
     * Puck diameter, about Google Maps' navigation chevron. Constant on screen,
     * rasterised at display density and drawn at icon-size 1.
     */
    private const val PUCK_DIAMETER_DP = 44f

    /** Duration of an animated camera move, milliseconds. */
    const val CAMERA_ANIMATION_MS = 300
    private const val LAYER_ROUTE          = "cm_route"
    private const val LAYER_APPROACH       = "cm_approach"
    private const val LAYER_MARKERS        = "cm_markers"
    private const val LAYER_MARKERS_LABEL  = "cm_markers_label"
    private const val LAYER_STOPS_SKIPPED  = "cm_stops_skipped"
    private const val LAYER_STOPS_DONE     = "cm_stops_done"
    private const val LAYER_STOPS_PENDING  = "cm_stops_pending"
    private const val LAYER_STOPS_BADGE    = "cm_stops_badge"
    private const val LAYER_STOPS_LABEL    = "cm_stops_label"
    private const val LAYER_POSITION       = "cm_position"
    private const val LAYER_POSITION_ARROW = "cm_position_arrow"
    private const val LAYER_POSITION_ARROW_OUTLINE = "cm_position_arrow_outline"
    private const val LAYER_ACTIVE_TARGET  = "cm_active_target"
    private const val LAYER_ACTIVE_TARGET_LABEL = "cm_active_target_label"
    private const val LAYER_TARGET_PLACEMENT_AREA = "cm_target_placement_area"
    private const val LAYER_TARGET_PLACEMENT_AREA_OUTLINE = "cm_target_placement_area_outline"
    private const val LAYER_JUNCTION_PINS   = "cm_junction_pins"

    /** Default route polyline colour - see [MapPalette.ROUTE]. */
    private val ROUTE_COLOUR = MapPalette.ROUTE
    /** Approach/follow line colour - see [MapPalette.APPROACH]. */
    private val APPROACH_COLOUR = MapPalette.APPROACH

    /** Zoom below which pin badges and labels are hidden (overview declutter). */
    private const val TEXT_MIN_ZOOM = 12f

    /** Pin radius by zoom, small dots at overview. Camera expression only, render safe. */
    private fun zoomScaledRadius(minRadius: Float, maxRadius: Float): Expression =
        Expression.interpolate(
            Expression.linear(), Expression.zoom(),
            Expression.stop(10f, minRadius),
            Expression.stop(16f, maxRadius)
        )

    // Layer ordering

    /**
     * Bottom to top. The approach line sits above the course so guidance stays
     * visible, the heading above the dot so it reads as part of it.
     */
    private val LAYER_STACK = listOf(
        LAYER_ROUTE,
        LAYER_APPROACH,
        LAYER_TARGET_PLACEMENT_AREA,
        LAYER_TARGET_PLACEMENT_AREA_OUTLINE,
        LAYER_MARKERS,
        LAYER_MARKERS_LABEL,
        LAYER_STOPS_SKIPPED,
        LAYER_STOPS_DONE,
        LAYER_STOPS_PENDING,
        LAYER_STOPS_BADGE,
        LAYER_STOPS_LABEL,
        LAYER_POSITION,
        LAYER_POSITION_ARROW,
        LAYER_POSITION_ARROW_OUTLINE,
        LAYER_ACTIVE_TARGET,
        LAYER_ACTIVE_TARGET_LABEL
    )

    private fun removePlacementStopLayers(style: Style) {
        listOf(
            LAYER_STOPS_LABEL, LAYER_STOPS_BADGE,
            LAYER_STOPS_PENDING, LAYER_STOPS_DONE, LAYER_STOPS_SKIPPED
        ).forEach { if (style.getLayer(it) != null) style.removeLayer(it) }
        listOf(
            SOURCE_STOPS_TEXT, SOURCE_STOPS_PENDING, SOURCE_STOPS_DONE, SOURCE_STOPS_SKIPPED
        ).forEach { if (style.getSource(it) != null) style.removeSource(it) }
    }

    /** Topmost extra course line, or null. */
    private fun Style.topmostExtraLineId(): String? {
        var idx = 0
        var last: String? = null
        while (getLayer("cm_extra_line_$idx") != null) {
            last = "cm_extra_line_$idx"
            idx++
        }
        return last
    }

    /**
     * Insert [layer] above the highest layer that belongs below it, or below the
     * lowest that belongs above. "Above the route" means above the extra course
     * lines too (`cm_extra_line_*`), otherwise pins end up under them.
     */
    private fun Style.insertInOrder(layer: Layer, layerId: String) {
        val idx = LAYER_STACK.indexOf(layerId)
        // Prefer anchoring above the nearest lower layer that is already present.
        val aboveAnchor = LAYER_STACK.take(idx).lastOrNull { getLayer(it) != null }
        if (aboveAnchor != null) {
            val anchor = if (aboveAnchor == LAYER_ROUTE) {
                topmostExtraLineId() ?: LAYER_ROUTE
            } else aboveAnchor
            addLayerAbove(layer, anchor)
            return
        }
        // Fall back: anchor below the nearest higher layer that is already present.
        val belowAnchor = LAYER_STACK.drop(idx + 1).firstOrNull { getLayer(it) != null }
        if (belowAnchor == null) {
            // multi-course map, no route layer - anchor above the extra lines
            // explicitly instead of relying on creation order
            topmostExtraLineId()?.let { addLayerAbove(layer, it); return }
        }
        if (belowAnchor != null) {
            addLayerBelow(layer, belowAnchor)
            return
        }
        addLayer(layer)
    }

    // Public overlay API

    /**
     * Route polyline from (lat, lon) pairs. Empty [points] removes it. Pass teal
     * (#00897B) for variant courses.
     */
    fun setRoute(
        map: MapLibreMap?,
        points: List<Pair<Double, Double>>,
        colorHex: String = ROUTE_COLOUR
    ) {
        val style = map?.style ?: return

        if (points.isEmpty()) {
            if (style.getLayer(LAYER_ROUTE) != null)  style.removeLayer(LAYER_ROUTE)
            if (style.getSource(SOURCE_ROUTE) != null) style.removeSource(SOURCE_ROUTE)
            return
        }

        // GeoJSON uses (lon, lat) order - swap from internal (lat, lon).
        val linePoints = points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        val featureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(linePoints))
        )

        val existingSource = style.getSource(SOURCE_ROUTE)
        if (existingSource != null) {
            (existingSource as GeoJsonSource).setGeoJson(featureCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_ROUTE, featureCollection))
        }

        if (style.getLayer(LAYER_ROUTE) == null) {
            style.insertInOrder(
                LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
                    PropertyFactory.lineColor(colorHex),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                ),
                LAYER_ROUTE
            )
        } else {
            // Update colour in case the course type changed (e.g. trunk → variant).
            (style.getLayer(LAYER_ROUTE) as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(colorHex)
            )
        }
    }

    /** Green approach line to the next stop, empty [points] removes it. */
    fun setApproachLine(
        map: MapLibreMap?,
        points: List<Pair<Double, Double>>,
        colorHex: String = APPROACH_COLOUR,
        widthDp: Float  = 6f,
        dashed: Boolean = false
    ) {
        val style = map?.style ?: return

        if (points.size < 2) {
            clearApproachLine(map)
            return
        }

        val linePoints = points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        val featureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(LineString.fromLngLats(linePoints))
        )

        val existingSource = style.getSource(SOURCE_APPROACH)
        if (existingSource != null) {
            (existingSource as GeoJsonSource).setGeoJson(featureCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_APPROACH, featureCollection))
        }

        // Dashed = "suggested path", solid = real geometry guidance.
        val dashArray = if (dashed) arrayOf(1.5f, 1.5f) else arrayOf(1f, 0f)

        if (style.getLayer(LAYER_APPROACH) == null) {
            style.insertInOrder(
                LineLayer(LAYER_APPROACH, SOURCE_APPROACH).withProperties(
                    PropertyFactory.lineColor(colorHex),
                    PropertyFactory.lineWidth(widthDp),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(0.9f),
                    PropertyFactory.lineDasharray(dashArray)
                ),
                LAYER_APPROACH
            )
        } else {
            (style.getLayer(LAYER_APPROACH) as? LineLayer)?.setProperties(
                PropertyFactory.lineColor(colorHex),
                PropertyFactory.lineWidth(widthDp),
                PropertyFactory.lineDasharray(dashArray)
            )
        }
    }

    /** Remove the approach line layer and its source. No-op if not present. */
    fun clearApproachLine(map: MapLibreMap?) {
        val style = map?.style ?: return
        if (style.getLayer(LAYER_APPROACH) != null) style.removeLayer(LAYER_APPROACH)
        if (style.getSource(SOURCE_APPROACH) != null) style.removeSource(SOURCE_APPROACH)
    }

    /**
     * Fit [points] with [paddingPx] on top of [viewportPadding]. [bearingDeg] 0.0
     * for north-up overview (a fit keeps rotation otherwise), null keeps bearing.
     */
    fun fitCamera(
        map: MapLibreMap?,
        points: List<Pair<Double, Double>>,
        paddingPx: Int = 80,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        bearingDeg: Double? = null
    ) {
        if (map == null || points.isEmpty()) return
        val leftPadding = paddingPx + viewportPadding.left
        val topPadding = paddingPx + viewportPadding.top
        val rightPadding = paddingPx + viewportPadding.right
        val bottomPadding = paddingPx + viewportPadding.bottom
        if (points.size == 1) {
            val (lat, lon) = points[0]
            val builder = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(15.0)
                .padding(
                    leftPadding.toDouble(),
                    topPadding.toDouble(),
                    rightPadding.toDouble(),
                    bottomPadding.toDouble()
                )
            if (bearingDeg != null) builder.bearing(bearingDeg)
            map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
            return
        }
        val bounds = LatLngBounds.Builder()
            .also { builder -> points.forEach { (lat, lon) -> builder.include(LatLng(lat, lon)) } }
            .build()
        val update = if (bearingDeg != null) {
            CameraUpdateFactory.newLatLngBounds(
                bounds, bearingDeg, 0.0,
                leftPadding, topPadding, rightPadding, bottomPadding
            )
        } else {
            CameraUpdateFactory.newLatLngBounds(
                bounds, leftPadding, topPadding, rightPadding, bottomPadding
            )
        }
        map.animateCamera(update)
    }

    /**
     * Cached position source and layers, keyed on [Style] since a reload makes
     * them dead. Avoids JNI lookups by id on every update.
     */
    private class PositionHandles(
        val style: Style,
        var arrowSource: GeoJsonSource? = null,
        var arrowLayer: SymbolLayer? = null,
        var dotSource: GeoJsonSource? = null,
        var dotLayer: CircleLayer? = null,
        /** Which of the two is currently installed; null before the first update. */
        var arrowMode: Boolean? = null,
        /** Last rotation pushed, so an unchanged bearing costs nothing. */
        var lastBearingDeg: Float? = null
    )

    private var positionHandles: PositionHandles? = null

    private fun handlesFor(style: Style): PositionHandles =
        positionHandles?.takeIf { it.style === style }
            ?: PositionHandles(style).also { positionHandles = it }

    /**
     * Position indicator. With [bearingDeg] it's the navigation puck, without it
     * the blue dot. Dot/arrow switches only on transition, not every update.
     *
     * Follow mode doesn't use this, the puck is a Compose overlay there
     * ([com.coursemapper.ui.run.RiderPuckOverlay]).
     */
    fun setCurrentPosition(map: MapLibreMap?, lat: Double, lon: Double, bearingDeg: Float? = null) {
        val style = map?.style ?: return
        val handles = handlesFor(style)
        val wantArrow = bearingDeg != null && bearingDeg.isFinite()
        val featureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(lon, lat))
        )

        if (wantArrow) {
            if (handles.arrowMode != true) {
                removeDotLayers(style, handles)
                handles.arrowMode = true
            }
            // field reads from here, handlesFor already returns fresh handles
            // after a style reload
            var source = handles.arrowSource
            if (source == null) {
                source = (style.getSource(SOURCE_POSITION_ARROW) as? GeoJsonSource)
                    ?: GeoJsonSource(SOURCE_POSITION_ARROW, featureCollection)
                        .also { style.addSource(it) }
                handles.arrowSource = source
            }
            source.setGeoJson(featureCollection)

            var layer = handles.arrowLayer
            if (layer == null) {
                if (style.getLayer(LAYER_POSITION_ARROW_OUTLINE) != null) {
                    style.removeLayer(LAYER_POSITION_ARROW_OUTLINE)
                }
                layer = style.getLayer(LAYER_POSITION_ARROW) as? SymbolLayer
                if (layer == null) {
                    style.addImage(IMAGE_POSITION_PUCK, createNavigationPuckBitmap())
                    layer = SymbolLayer(LAYER_POSITION_ARROW, SOURCE_POSITION_ARROW)
                        .withProperties(
                            PropertyFactory.iconImage(IMAGE_POSITION_PUCK),
                            // bitmap is already at PUCK_DIAMETER_DP, keep size 1
                            PropertyFactory.iconSize(1f),
                            PropertyFactory.iconRotate(bearingDeg!!),
                            PropertyFactory.iconAllowOverlap(true),
                            PropertyFactory.iconIgnorePlacement(true),
                            PropertyFactory.iconRotationAlignment(
                                Property.ICON_ROTATION_ALIGNMENT_MAP
                            ),
                            PropertyFactory.iconPitchAlignment(
                                Property.ICON_PITCH_ALIGNMENT_VIEWPORT
                            )
                        )
                    style.insertInOrder(layer, LAYER_POSITION_ARROW)
                    handles.lastBearingDeg = bearingDeg
                }
                handles.arrowLayer = layer
            }
            if (handles.lastBearingDeg != bearingDeg) {
                layer.setProperties(PropertyFactory.iconRotate(bearingDeg!!))
                handles.lastBearingDeg = bearingDeg
            }
            return
        }

        if (handles.arrowMode != false) {
            removeArrowLayers(style, handles)
            handles.arrowMode = false
        }
        var source = handles.dotSource
        if (source == null) {
            source = (style.getSource(SOURCE_POSITION) as? GeoJsonSource)
                ?: GeoJsonSource(SOURCE_POSITION, featureCollection).also { style.addSource(it) }
            handles.dotSource = source
        }
        source.setGeoJson(featureCollection)

        if (handles.dotLayer == null) {
            var layer = style.getLayer(LAYER_POSITION) as? CircleLayer
            if (layer == null) {
                layer = CircleLayer(LAYER_POSITION, SOURCE_POSITION).withProperties(
                    PropertyFactory.circleColor(MapPalette.ROUTE),
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f)
                )
                style.insertInOrder(layer, LAYER_POSITION)
            }
            handles.dotLayer = layer
        }
    }

    private fun removeArrowLayers(style: Style, handles: PositionHandles) {
        if (style.getLayer(LAYER_POSITION_ARROW_OUTLINE) != null) {
            style.removeLayer(LAYER_POSITION_ARROW_OUTLINE)
        }
        if (style.getLayer(LAYER_POSITION_ARROW) != null) style.removeLayer(LAYER_POSITION_ARROW)
        if (style.getSource(SOURCE_POSITION_ARROW) != null) style.removeSource(SOURCE_POSITION_ARROW)
        handles.arrowLayer = null
        handles.arrowSource = null
        handles.lastBearingDeg = null
    }

    private fun removeDotLayers(style: Style, handles: PositionHandles) {
        if (style.getLayer(LAYER_POSITION) != null) style.removeLayer(LAYER_POSITION)
        if (style.getSource(SOURCE_POSITION) != null) style.removeSource(SOURCE_POSITION)
        handles.dotLayer = null
        handles.dotSource = null
    }

    /** Remove the puck, follow mode draws its own and a leftover would show a stale rider. */
    /**
     * Drop cached handles. They hold the [Style] and with it the native map.
     * Called from [MapRenderCoordinator.release].
     */
    fun releaseHandles() {
        positionHandles = null
    }

    fun clearCurrentPosition(map: MapLibreMap?) {
        val style = map?.style ?: return
        val handles = handlesFor(style)
        removeArrowLayers(style, handles)
        removeDotLayers(style, handles)
        handles.arrowMode = null
    }

    /**
     * Puck bitmap at display density - MapLibre uses `density / 160` as pixel
     * ratio, so this ends up exactly [PUCK_DIAMETER_DP] on every device.
     */
    private fun createNavigationPuckBitmap(): Bitmap {
        val metrics = Resources.getSystem().displayMetrics
        val size    = (PUCK_DIAMETER_DP * metrics.density).roundToInt().coerceAtLeast(1)
        val bitmap  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = metrics.densityDpi

        val canvas = Canvas(bitmap)
        val paint  = Paint(Paint.ANTI_ALIAS_FLAG)
        val s      = size.toFloat()
        val center = s / 2f

        paint.color = Color.argb(55, 0, 0, 0)
        canvas.drawCircle(center, center + s * 0.031f, s * 0.469f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, s * 0.448f, paint)
        paint.color = Color.parseColor(MapPalette.ROUTE)
        canvas.drawCircle(center, center, s * 0.385f, paint)

        val arrow = Path().apply {
            moveTo(center,     s * 0.187f)
            lineTo(s * 0.708f, s * 0.740f)
            lineTo(center,     s * 0.635f)
            lineTo(s * 0.292f, s * 0.740f)
            close()
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        canvas.drawPath(arrow, paint)
        return bitmap
    }

    /** Great-circle destination point [distM] metres from start along [bearingDeg]. */
    private fun destinationPoint(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distM: Double
    ): Pair<Double, Double> {
        val r      = 6_371_000.0
        val brRad  = Math.toRadians(bearingDeg)
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val dLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(distM / r) +
            Math.cos(latRad) * Math.sin(distM / r) * Math.cos(brRad)
        )
        val dLonRad = lonRad + Math.atan2(
            Math.sin(brRad) * Math.sin(distM / r) * Math.cos(latRad),
            Math.cos(distM / r) - Math.sin(latRad) * Math.sin(dLatRad)
        )
        return Math.toDegrees(dLatRad) to Math.toDegrees(dLonRad)
    }

    /**
     * Move the camera with no animation, for the per-frame follow camera
     * ([com.coursemapper.domain.FollowCameraSmoother] already interpolates).
     */
    fun setCamera(
        map: MapLibreMap?,
        lat: Double,
        lon: Double,
        zoom: Double,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        bearingDeg: Double? = null,
        tiltDeg: Double = 0.0
    ) {
        if (map == null) return
        val builder = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .padding(
                viewportPadding.left.toDouble(),
                viewportPadding.top.toDouble(),
                viewportPadding.right.toDouble(),
                viewportPadding.bottom.toDouble()
            )
        if (bearingDeg != null) builder.bearing(bearingDeg)
        builder.tilt(tiltDeg)
        map.moveCamera(CameraUpdateFactory.newCameraPosition(builder.build()))
    }

    /** Animate the camera to [lat]/[lon]. Null [bearingDeg] keeps rotation. */
    fun moveCamera(
        map: MapLibreMap?,
        lat: Double,
        lon: Double,
        zoom: Double = 16.0,
        viewportPadding: MapViewportPadding = MapViewportPadding(),
        bearingDeg: Double? = null,
        tiltDeg: Double = 0.0
    ) {
        val builder = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .padding(
                viewportPadding.left.toDouble(),
                viewportPadding.top.toDouble(),
                viewportPadding.right.toDouble(),
                viewportPadding.bottom.toDouble()
            )
        if (bearingDeg != null) builder.bearing(bearingDeg)
        builder.tilt(tiltDeg)
        map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(builder.build()),
            CAMERA_ANIMATION_MS
        )
    }

    /** Distance markers as orange dots with labels, (lat, lon, label). Replaces previous. */
    fun setMarkers(map: MapLibreMap?, markers: List<Triple<Double, Double, String>>) {
        val style = map?.style ?: return

        if (markers.isEmpty()) {
            if (style.getLayer(LAYER_MARKERS_LABEL) != null) style.removeLayer(LAYER_MARKERS_LABEL)
            if (style.getLayer(LAYER_MARKERS) != null)       style.removeLayer(LAYER_MARKERS)
            if (style.getSource(SOURCE_MARKERS) != null)     style.removeSource(SOURCE_MARKERS)
            if (style.getSource(SOURCE_MARKERS_TEXT) != null) style.removeSource(SOURCE_MARKERS_TEXT)
            return
        }

        // Circles: property-free features + static paint (hardened recipe).
        val circleCollection = FeatureCollection.fromFeatures(
            markers.map { (lat, lon, _) ->
                Feature.fromGeometry(Point.fromLngLat(lon, lat))
            }
        )
        val existingSource = style.getSource(SOURCE_MARKERS)
        if (existingSource != null) {
            (existingSource as GeoJsonSource).setGeoJson(circleCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_MARKERS, circleCollection))
        }

        if (style.getLayer(LAYER_MARKERS) == null) {
            style.insertInOrder(
                CircleLayer(LAYER_MARKERS, SOURCE_MARKERS).withProperties(
                    PropertyFactory.circleColor(MapPalette.MARKER),
                    PropertyFactory.circleRadius(zoomScaledRadius(2.5f, 8f)),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(10f, 1f),
                            Expression.stop(16f, 2f)
                        )
                    )
                ),
                LAYER_MARKERS
            )
        }

        // Labels: best-effort on a separate property-bearing source.
        val textCollection = FeatureCollection.fromFeatures(
            markers.map { (lat, lon, label) ->
                val props = JsonObject().also { it.addProperty("label", label) }
                Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
            }
        )
        val existingText = style.getSource(SOURCE_MARKERS_TEXT)
        if (existingText != null) {
            (existingText as GeoJsonSource).setGeoJson(textCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_MARKERS_TEXT, textCollection))
        }

        if (style.getLayer(LAYER_MARKERS_LABEL) == null) {
            // Marker labels yield to stop labels in dense overlap: allow overlap
            // but do not force-place with ignorePlacement so stop labels win when
            // the collision budget is tight.
            val markerLabelLayer = SymbolLayer(LAYER_MARKERS_LABEL, SOURCE_MARKERS_TEXT).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(MapPalette.MARKER),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(false),
                PropertyFactory.textOffset(arrayOf(0f, -1.6f)),
                PropertyFactory.textAnchor("bottom")
            )
            markerLabelLayer.minZoom = TEXT_MIN_ZOOM
            style.insertInOrder(markerLabelLayer, LAYER_MARKERS_LABEL)
        }
    }

    /** Placement stops as pins with labels, above route and markers but below position and target. */
    fun setPlacementStops(map: MapLibreMap?, stops: List<MapStopPin>) {
        val style = map?.style ?: return

        if (stops.isEmpty()) {
            removePlacementStopLayers(style)
            return
        }

        // property-free features and static paints only - features with
        // properties didn't render reliably on device. One layer per state.
        fun pointsOf(state: MapStopState) = FeatureCollection.fromFeatures(
            stops.filter { it.state == state }.map {
                Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))
            }
        )

        fun upsertCircleLayer(sourceId: String, layerId: String, colorHex: String, fc: FeatureCollection) {
            val existing = style.getSource(sourceId)
            if (existing != null) {
                (existing as GeoJsonSource).setGeoJson(fc)
            } else {
                style.addSource(GeoJsonSource(sourceId, fc))
            }
            if (style.getLayer(layerId) == null) {
                style.insertInOrder(
                    CircleLayer(layerId, sourceId).withProperties(
                        PropertyFactory.circleColor(colorHex),
                        PropertyFactory.circleRadius(zoomScaledRadius(3.5f, 12f)),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(
                            Expression.interpolate(
                                Expression.linear(), Expression.zoom(),
                                Expression.stop(10f, 1f),
                                Expression.stop(16f, 2f)
                            )
                        )
                    ),
                    layerId
                )
            }
        }

        upsertCircleLayer(
            SOURCE_STOPS_SKIPPED, LAYER_STOPS_SKIPPED,
            MapPalette.SKIPPED_STOP, pointsOf(MapStopState.SKIPPED)
        )
        upsertCircleLayer(
            SOURCE_STOPS_DONE, LAYER_STOPS_DONE,
            MapPalette.COMPLETED_STOP, pointsOf(MapStopState.DONE)
        )
        upsertCircleLayer(
            SOURCE_STOPS_PENDING, LAYER_STOPS_PENDING,
            MapPalette.ACTIVE_TARGET, pointsOf(MapStopState.PENDING)
        )

        // ── Badges + labels: best-effort text on a separate symbol source ─────
        // If text rendering fails on-device, the circles above still show.
        val textFeatures = stops.map { pin ->
            val props = JsonObject().also {
                it.addProperty("label", pin.label)
                it.addProperty("badge", pin.badge ?: "")
            }
            Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat), props)
        }
        val textCollection = FeatureCollection.fromFeatures(textFeatures)
        val existingText = style.getSource(SOURCE_STOPS_TEXT)
        if (existingText != null) {
            (existingText as GeoJsonSource).setGeoJson(textCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_STOPS_TEXT, textCollection))
        }

        // Badge INSIDE the pin (sign count) - never collided away, but hidden
        // at overview zooms where the pins are too small to hold text.
        if (style.getLayer(LAYER_STOPS_BADGE) == null) {
            val badgeLayer = SymbolLayer(LAYER_STOPS_BADGE, SOURCE_STOPS_TEXT).withProperties(
                PropertyFactory.textField(Expression.get("badge")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAnchor("center")
            )
            badgeLayer.minZoom = TEXT_MIN_ZOOM
            style.insertInOrder(badgeLayer, LAYER_STOPS_BADGE)
        }

        if (style.getLayer(LAYER_STOPS_LABEL) == null) {
            val labelLayer = SymbolLayer(LAYER_STOPS_LABEL, SOURCE_STOPS_TEXT).withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(MapPalette.ACTIVE_TARGET),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textIgnorePlacement(false),
                PropertyFactory.textOffset(arrayOf(0f, -1.8f)),
                PropertyFactory.textAnchor("bottom")
            )
            labelLayer.minZoom = TEXT_MIN_ZOOM
            style.insertInOrder(labelLayer, LAYER_STOPS_LABEL)
        }
    }

    /** Ring around the active target, same property-free recipe as the pins. */
    fun setActiveTarget(
        map: MapLibreMap?,
        lat: Double,
        lon: Double,
        label: String,
        placementAccuracyMetres: Double? = null
    ) {
        val style = map?.style ?: return
        setTargetPlacementArea(map, lat, lon, placementAccuracyMetres)
        val featureCollection = FeatureCollection.fromFeature(
            Feature.fromGeometry(Point.fromLngLat(lon, lat))
        )

        val existingSource = style.getSource(SOURCE_ACTIVE_TARGET)
        if (existingSource != null) {
            (existingSource as GeoJsonSource).setGeoJson(featureCollection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_ACTIVE_TARGET, featureCollection))
        }

        if (style.getLayer(LAYER_ACTIVE_TARGET) == null) {
            // same green as the approach line, "go here next"
            style.insertInOrder(
                CircleLayer(LAYER_ACTIVE_TARGET, SOURCE_ACTIVE_TARGET).withProperties(
                    PropertyFactory.circleColor(MapPalette.APPROACH),
                    PropertyFactory.circleRadius(zoomScaledRadius(6f, 18f)),
                    PropertyFactory.circleOpacity(0.18f),
                    PropertyFactory.circleStrokeColor(MapPalette.APPROACH),
                    PropertyFactory.circleStrokeWidth(
                        Expression.interpolate(
                            Expression.linear(), Expression.zoom(),
                            Expression.stop(10f, 2f),
                            Expression.stop(16f, 4f)
                        )
                    ),
                    PropertyFactory.circleStrokeOpacity(0.95f)
                ),
                LAYER_ACTIVE_TARGET
            )
        }

        // Single feature → the label can be a literal layer property instead of
        // a data expression.  Update it on every call (the target moves).
        val labelLayer = style.getLayer(LAYER_ACTIVE_TARGET_LABEL) as? SymbolLayer
        if (labelLayer == null) {
            style.insertInOrder(
                SymbolLayer(LAYER_ACTIVE_TARGET_LABEL, SOURCE_ACTIVE_TARGET).withProperties(
                    PropertyFactory.textField(label),
                    PropertyFactory.textSize(13f),
                    PropertyFactory.textColor("#1B5E20"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.textOffset(arrayOf(0f, 2.0f)),
                    PropertyFactory.textAnchor("top")
                ),
                LAYER_ACTIVE_TARGET_LABEL
            )
        } else {
            labelLayer.setProperties(PropertyFactory.textField(label))
        }
    }

    fun clearActiveTarget(map: MapLibreMap?) {
        val style = map?.style ?: return
        if (style.getLayer(LAYER_ACTIVE_TARGET_LABEL) != null) style.removeLayer(LAYER_ACTIVE_TARGET_LABEL)
        if (style.getLayer(LAYER_ACTIVE_TARGET) != null)       style.removeLayer(LAYER_ACTIVE_TARGET)
        if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA_OUTLINE) != null) {
            style.removeLayer(LAYER_TARGET_PLACEMENT_AREA_OUTLINE)
        }
        if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA) != null) {
            style.removeLayer(LAYER_TARGET_PLACEMENT_AREA)
        }
        if (style.getSource(SOURCE_ACTIVE_TARGET) != null)     style.removeSource(SOURCE_ACTIVE_TARGET)
        if (style.getSource(SOURCE_TARGET_PLACEMENT_AREA) != null) {
            style.removeSource(SOURCE_TARGET_PLACEMENT_AREA)
        }
    }

    private fun setTargetPlacementArea(
        map: MapLibreMap?,
        lat: Double,
        lon: Double,
        accuracyMetres: Double?
    ) {
        val style = map?.style ?: return
        if (accuracyMetres == null || !accuracyMetres.isFinite() || accuracyMetres <= 0.0) {
            if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA_OUTLINE) != null) {
                style.removeLayer(LAYER_TARGET_PLACEMENT_AREA_OUTLINE)
            }
            if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA) != null) {
                style.removeLayer(LAYER_TARGET_PLACEMENT_AREA)
            }
            if (style.getSource(SOURCE_TARGET_PLACEMENT_AREA) != null) {
                style.removeSource(SOURCE_TARGET_PLACEMENT_AREA)
            }
            return
        }

        val ring = (0..48).map { step ->
            val bearing = step * 360.0 / 48.0
            val (pointLat, pointLon) = destinationPoint(lat, lon, bearing, accuracyMetres)
            Point.fromLngLat(pointLon, pointLat)
        }
        val collection = FeatureCollection.fromFeature(
            Feature.fromGeometry(org.maplibre.geojson.Polygon.fromLngLats(listOf(ring)))
        )
        val source = style.getSource(SOURCE_TARGET_PLACEMENT_AREA) as? GeoJsonSource
        if (source != null) {
            source.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(SOURCE_TARGET_PLACEMENT_AREA, collection))
        }

        if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA) == null) {
            style.insertInOrder(
                org.maplibre.android.style.layers.FillLayer(
                    LAYER_TARGET_PLACEMENT_AREA,
                    SOURCE_TARGET_PLACEMENT_AREA
                ).withProperties(
                    PropertyFactory.fillColor(MapPalette.APPROACH),
                    PropertyFactory.fillOpacity(0.12f)
                ),
                LAYER_TARGET_PLACEMENT_AREA
            )
        }
        if (style.getLayer(LAYER_TARGET_PLACEMENT_AREA_OUTLINE) == null) {
            style.insertInOrder(
                LineLayer(
                    LAYER_TARGET_PLACEMENT_AREA_OUTLINE,
                    SOURCE_TARGET_PLACEMENT_AREA
                ).withProperties(
                    PropertyFactory.lineColor(MapPalette.APPROACH),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineOpacity(0.65f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f))
                ),
                LAYER_TARGET_PLACEMENT_AREA_OUTLINE
            )
        }
    }

    /**
     * Extra polylines above the route, by stable index. Indices that disappear
     * are removed, empty list clears all.
     */
    fun setAdditionalPolylines(map: MapLibreMap?, overlays: List<MapPolylineOverlay>) {
        val style = map?.style ?: return

        // Remove stale layers from previous calls (by counting up until no layer is found).
        var idx = 0
        while (style.getLayer("cm_extra_line_$idx") != null) {
            style.removeLayer("cm_extra_line_$idx")
            if (style.getSource("cm_extra_src_$idx") != null) style.removeSource("cm_extra_src_$idx")
            idx++
        }

        // Chain the lines upward from the route (e0 above ROUTE, e1 above e0…)
        // so list order = z-order and the band stays contiguous directly above
        // LAYER_ROUTE - always below the approach line and the stop pins.
        var previousLayerId: String? = null
        for ((i, overlay) in overlays.withIndex()) {
            if (!overlay.hasGeometry) continue
            val srcId   = "cm_extra_src_$i"
            val layerId = "cm_extra_line_$i"

            // one feature per path, all on one layer with static paint
            val fc = FeatureCollection.fromFeatures(
                overlay.paths
                    .filter { it.size >= 2 }
                    .map { path ->
                        Feature.fromGeometry(
                            LineString.fromLngLats(path.map { (lat, lon) -> Point.fromLngLat(lon, lat) })
                        )
                    }
            )
            val existing = style.getSource(srcId)
            if (existing != null) {
                (existing as GeoJsonSource).setGeoJson(fc)
            } else {
                style.addSource(GeoJsonSource(srcId, fc))
            }
            if (style.getLayer(layerId) == null) {
                val layer = LineLayer(layerId, srcId).withProperties(
                    PropertyFactory.lineColor(overlay.colorHex),
                    PropertyFactory.lineWidth(overlay.widthDp),
                    // static offset in screen units, puts each course in its own lane
                    PropertyFactory.lineOffset(overlay.lineOffsetDp),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineOpacity(0.85f)
                )
                overlay.minZoom?.let { layer.minZoom = it }
                overlay.maxZoom?.let { layer.maxZoom = it }
                when {
                    previousLayerId != null                -> style.addLayerAbove(layer, previousLayerId)
                    style.getLayer(LAYER_APPROACH) != null -> style.addLayerBelow(layer, LAYER_APPROACH)
                    style.getLayer(LAYER_ROUTE) != null    -> style.addLayerAbove(layer, LAYER_ROUTE)
                    else                                    -> style.addLayer(layer)
                }
            } else {
                (style.getLayer(layerId) as? LineLayer)?.apply {
                    setProperties(
                        PropertyFactory.lineColor(overlay.colorHex),
                        PropertyFactory.lineWidth(overlay.widthDp),
                        PropertyFactory.lineOffset(overlay.lineOffsetDp)
                    )
                    // Reset rather than only set: an index is reused by whatever
                    // overlay lands at that position, and a bound left over from
                    // the previous one would hide a layer that wants every zoom.
                    minZoom = overlay.minZoom ?: 0f
                    maxZoom = overlay.maxZoom ?: 24f
                }
            }
            previousLayerId = layerId
        }
    }

    /** Remove all additional polyline layers added by [setAdditionalPolylines]. */
    fun clearAdditionalPolylines(map: MapLibreMap?) {
        setAdditionalPolylines(map, emptyList())
    }

    /** Junction pins as amber circles, (lat, lon, label). */
    fun setJunctionPins(map: MapLibreMap?, pins: List<Triple<Double, Double, String>>) {
        val style = map?.style ?: return
        if (pins.isEmpty()) { clearJunctionPins(map); return }

        // Property-free features (hardened recipe); the label is unused - the
        // junction layer renders circles only.
        val fc = FeatureCollection.fromFeatures(
            pins.map { (lat, lon, _) ->
                Feature.fromGeometry(Point.fromLngLat(lon, lat))
            }
        )

        val existing = style.getSource(SOURCE_JUNCTION_PINS)
        if (existing != null) {
            (existing as GeoJsonSource).setGeoJson(fc)
        } else {
            style.addSource(GeoJsonSource(SOURCE_JUNCTION_PINS, fc))
        }

        if (style.getLayer(LAYER_JUNCTION_PINS) == null) {
            val layer = CircleLayer(LAYER_JUNCTION_PINS, SOURCE_JUNCTION_PINS).withProperties(
                PropertyFactory.circleColor(MapPalette.JUNCTION),
                PropertyFactory.circleRadius(zoomScaledRadius(4f, 11f)),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f)
            )
            val anchor = style.getLayer(LAYER_ACTIVE_TARGET)
            if (anchor != null) style.addLayerAbove(layer, LAYER_ACTIVE_TARGET)
            else style.addLayer(layer)
        }
    }

    fun clearJunctionPins(map: MapLibreMap?) {
        val style = map?.style ?: return
        if (style.getLayer(LAYER_JUNCTION_PINS) != null) style.removeLayer(LAYER_JUNCTION_PINS)
        if (style.getSource(SOURCE_JUNCTION_PINS) != null) style.removeSource(SOURCE_JUNCTION_PINS)
    }

}
