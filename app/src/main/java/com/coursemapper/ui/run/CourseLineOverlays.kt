package com.coursemapper.ui.run

import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CorridorAnalyzer
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.RibbonLod

/**
 * A course on a map showing several. Shared stretches are drawn once as a
 * ribbon, so a course is some [ribbonPaths] plus its [soloPaths].
 * See `CourseDisplayPlanner`.
 */
data class RunCourseLine(
    val courseId: Long,
    val soloPaths: List<List<Pair<Double, Double>>>,
    /** Ribbon chunks at the finest length - the zoomed-in look. */
    val ribbonPaths: List<List<Pair<Double, Double>>>,
    val colorHex: String,
    val courseName: String,
    /** Ribbons at each coarser length, aligned with `RibbonLod.CHUNK_METRES` after the first. */
    val coarseRibbonBands: List<List<List<Pair<Double, Double>>>> = emptyList()
) {
    /** Points for camera fits, the coarse bands are the same ground. */
    val allPoints: List<Pair<Double, Double>> get() = (soloPaths + ribbonPaths).flatten()

    /** Every level of detail, finest first. */
    val ribbonBands: List<List<List<Pair<Double, Double>>>>
        get() = listOf(ribbonPaths) + coarseRibbonBands
}

/** Build counter for [toMapOverlays], process wide like the coordinator's cache. */
private val overlayRevisions = java.util.concurrent.atomic.AtomicLong(0L)

/** Adapt what the repository loaded into the UI's own line type. */
fun RouteRepository.CourseDisplay.toRunCourseLines(): List<RunCourseLine> =
    lines.map {
        RunCourseLine(
            courseId = it.courseId,
            soloPaths = it.soloPaths,
            ribbonPaths = it.ribbonPaths,
            colorHex = it.colorHex,
            courseName = it.courseName,
            coarseRibbonBands = it.coarseRibbonBands
        )
    }

/**
 * Course lines to map overlays, bottom to top:
 *
 * 1. grey casing under shared stretches, backing for the lanes
 * 2. lanes below the coarsest chunk band, each course offset by
 *    [RibbonLod.laneOffsetDp] on the shared geometry
 * 3. solo stretches, one layer per course
 * 4. ribbon chunks, one layer per course per detail level, zoom-bounded
 *
 * Bounds come from [RibbonLod] at the map's latitude so there's no zoom where a
 * corridor goes grey. Plain zoom limits and static paints only.
 */
fun List<RunCourseLine>.toMapOverlays(
    sharedCorridors: List<CourseDisplayPlanner.SharedCorridor> = emptyList(),
    widthDp: Float = 4f
): List<MapPolylineOverlay> {
    // one revision per build, callers memoise this so a new revision means new geometry
    val revision = overlayRevisions.incrementAndGet()
    val overlays = mutableListOf<MapPolylineOverlay>()
    val latitude = representativeLatitude()
    val bands = RibbonLod.bands(latitude, widthDp)
    val laneMaxZoom = bands.last().minZoom

    val drawableCorridors = sharedCorridors.filter { it.path.size >= 2 && it.courseIds.isNotEmpty() }
    if (drawableCorridors.isNotEmpty()) {
        overlays += MapPolylineOverlay(
            paths = drawableCorridors.map { it.path },
            colorHex = MapPalette.SHARED_CORRIDOR,
            widthDp = RibbonLod.casingWidthDp(widthDp, drawableCorridors.maxOf { it.courseIds.size }),
            revision = revision,
            maxZoom = laneMaxZoom
        )
    }

    // one layer per (course, lane offset), stays a handful of layers
    val lanes = LinkedHashMap<Pair<Long, Float>, MutableList<List<Pair<Double, Double>>>>()
    drawableCorridors.forEach { corridor ->
        corridor.courseIds.forEachIndexed { position, courseId ->
            val offset = RibbonLod.laneOffsetDp(position, corridor.courseIds.size)
            lanes.getOrPut(courseId to offset) { mutableListOf() } += corridor.path
        }
    }
    forEach { line ->
        lanes.entries
            .filter { it.key.first == line.courseId }
            .sortedBy { it.key.second }
            .forEach { (key, paths) ->
                overlays += MapPolylineOverlay(
                    paths = paths,
                    colorHex = line.colorHex,
                    widthDp = RibbonLod.laneWidthDp(widthDp),
                    maxZoom = laneMaxZoom,
                    lineOffsetDp = key.second,
                    revision = revision
                )
            }
    }

    forEach { line ->
        if (line.soloPaths.isNotEmpty()) {
            overlays += MapPolylineOverlay(
                paths = line.soloPaths,
                colorHex = line.colorHex,
                widthDp = widthDp,
                revision = revision
            )
        }
    }

    // Bands the planner did not build are simply not drawn: zipping rather than
    // indexing means a planner and a LOD table that disagree lose detail at the
    // coarse end instead of crashing.
    forEach { line ->
        line.ribbonBands.zip(bands).forEach { (paths, band) ->
            if (paths.isNotEmpty()) {
                overlays += MapPolylineOverlay(
                    paths = paths,
                    colorHex = line.colorHex,
                    widthDp = widthDp,
                    minZoom = band.minZoom,
                    maxZoom = band.maxZoom,
                    revision = revision
                )
            }
        }
    }

    return overlays
}

/** Latitude for sizing the zoom bands, the first point is plenty for an event. */
private fun List<RunCourseLine>.representativeLatitude(): Double =
    firstNotNullOfOrNull { line ->
        (line.soloPaths.asSequence() + line.ribbonPaths.asSequence())
            .firstOrNull { it.isNotEmpty() }
            ?.first()
            ?.first
    } ?: RibbonLod.DEFAULT_LATITUDE

/**
 * Max distance a stop pin is moved onto the drawn line. Same as the corridor
 * exit tolerance, further than that it's not on this line.
 */
const val STOP_SNAP_MAX_METRES = CorridorAnalyzer.EXIT_TOLERANCE_METRES

/**
 * Put a stop pin on the line that's actually drawn. Where courses share a road
 * the drawn line is another course's trace, and merged stops sit at a centroid,
 * so pins would float beside the line.
 *
 * Display only, like [com.coursemapper.domain.PositionSnapPolicy] for the puck.
 * Navigation and dwell use the stored position. Unchanged if nothing is drawn
 * within [STOP_SNAP_MAX_METRES].
 */
fun List<RunCourseLine>.snapStopToDrawnLine(
    lat: Double,
    lon: Double,
    calc: CumulativeDistanceCalculator = sharedCalculator,
    maxMetres: Double = STOP_SNAP_MAX_METRES
): Pair<Double, Double> {
    var bestLat = lat
    var bestLon = lon
    var best = maxMetres

    forEach { line ->
        // Ribbons first: on shared ground that is the line actually on screen.
        for (path in line.ribbonPaths + line.soloPaths) {
            for (i in 0 until path.size - 1) {
                val projection = calc.closestPointOnSegment(
                    path[i].first, path[i].second,
                    path[i + 1].first, path[i + 1].second,
                    lat, lon
                )
                val offset = calc.haversineMetres(lat, lon, projection.lat, projection.lon)
                if (offset < best) {
                    best = offset
                    bestLat = projection.lat
                    bestLon = projection.lon
                    // Already on the line to well under a pin's own radius.
                    if (offset < 0.25) return bestLat to bestLon
                }
            }
        }
    }
    return bestLat to bestLon
}

/** Stateless, one instance for all display snapping. */
private val sharedCalculator = CumulativeDistanceCalculator()
