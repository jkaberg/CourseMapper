package com.coursemapper.map

/** Coloured polyline drawn above the route layer. */
data class MapPolylineOverlay(
    /**
     * Disjoint paths of (lat, lon). A course sharing roads is several chunks plus
     * its solo stretches, see [com.coursemapper.domain.CourseDisplayPlanner].
     * Paths under two points are skipped.
     */
    val paths: List<List<Pair<Double, Double>>>,
    /** HTML hex colour, e.g. "#00C853". */
    val colorHex: String,
    /** Stroke width in display-independent pixels. */
    val widthDp: Float = 5f,
    /** Dashed for suggested paths (straight-line legs), so they're not mistaken for real geometry. */
    val dashed: Boolean = false,
    /** Hide below this zoom.  Null = visible at every zoom. */
    val minZoom: Float? = null,
    /** Hide at and above this zoom.  Null = visible at every zoom. */
    val maxZoom: Float? = null,
    /**
     * Sideways offset in dp, positive to the right. Puts each course in its own
     * lane on a shared corridor ([RibbonLod.laneOffsetDp]), constant at every zoom.
     */
    val lineOffsetDp: Float = 0f,
    /**
     * Geometry revision stamped by the builder, so [MapRenderCoordinator] can
     * compare one number instead of all the points. 0 compares the geometry.
     */
    val revision: Long = 0L,
) {
    /** Single-path overlay - the common case. */
    constructor(
        points: List<Pair<Double, Double>>,
        colorHex: String,
        widthDp: Float = 5f,
        dashed: Boolean = false,
    ) : this(listOf(points), colorHex, widthDp, dashed)

    /** True when there is at least one drawable path. */
    val hasGeometry: Boolean get() = paths.any { it.size >= 2 }

    /** What [MapRenderCoordinator] compares. */
    val renderSignature: Any
        get() = if (revision == 0L) this else listOf(
            revision, colorHex, widthDp, dashed, minZoom, maxZoom, lineOffsetDp
        )
}

/**
 * Everything the map should render. Screens build this from their state and
 * hand it to [MapRenderCoordinator.apply].
 */
data class MapScene(
    /** Route polyline as ordered (lat, lon) pairs. Empty = no route drawn. */
    val routePoints: List<Pair<Double, Double>> = emptyList(),

    /** Main route colour, blue for trunk courses and teal for variants. */
    val routeColorHex: String = MapPalette.ROUTE,

    /** Extra polylines above the route, last on top. */
    val additionalPolylines: List<MapPolylineOverlay> = emptyList(),

    /** Distance markers, (lat, lon, label). */
    val distanceMarkers: List<Triple<Double, Double, String>> = emptyList(),

    /** All placement stops, colour by state, optional count badge. */
    val placementStops: List<MapStopPin> = emptyList(),

    /** The currently targeted stop or marker, highlighted on the map. Null = none. */
    val activeTarget: MapActiveTarget? = null,

    /** Current GPS position for the blue dot. Null = position not yet known. */
    val currentPosition: Pair<Double, Double>? = null,

    /** Travel direction, non-null draws the arrow instead of the dot. */
    val currentBearingDeg: Float? = null,

    /** Guidance to the next stop. Solid is routed, dashed is straight line, null is none. */
    val approachLine: MapPolylineOverlay? = null,

    /** Junction pins for branch authoring, (lat, lon, label). */
    val junctionPins: List<Triple<Double, Double, String>> = emptyList(),

)

/**
 * A single map target to highlight as the active navigation destination.
 */
data class MapActiveTarget(
    val lat: Double,
    val lon: Double,
    val label: String,
    /** GPS-sized placement uncertainty halo in metres; not an acceptance radius. */
    val placementAccuracyMetres: Double? = null,
)

/** Stop pin state - determines the pin colour (red / green / grey). */
enum class MapStopState { PENDING, DONE, SKIPPED }

/**
 * A stop pin. [badge] goes inside the circle (sign count), [label] above.
 * Circles and text use separate sources so the circles still show if glyphs
 * fail offline.
 */
data class MapStopPin(
    val lat: Double,
    val lon: Double,
    val label: String,
    val badge: String? = null,
    val state: MapStopState = MapStopState.PENDING,
)
