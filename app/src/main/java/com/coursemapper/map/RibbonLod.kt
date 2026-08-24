package com.coursemapper.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * How shared corridors are drawn at each zoom.
 *
 * A chunk length is on the ground but legibility is on screen: a 40 m chunk is
 * 2.3 px at zoom 12 in Trondheim, less than the line caps. So:
 *
 * - zoomed in: chunk bands from [CHUNK_METRES], each 4x the last, visible while
 *   their chunks are [MIN_CHUNK_STROKES] to 4x that in stroke widths. Zoom bounds
 *   are derived in [zoomWhere], and the bands tile with no gaps.
 * - zoomed out: lanes. Each course is drawn on the shared geometry with its own
 *   [laneOffsetDp], a static screen-space offset that works at any zoom.
 *
 * The grey casing under the lanes is only a fallback.
 */
object RibbonLod {

    /** Web Mercator: metres per dp is `156543.03392 * cos(lat) / 2^zoom`. */
    const val METRES_PER_PIXEL_AT_ZOOM_0 = 156543.03392

    /** Shorter than this in stroke widths and the round caps eat the chunk. */
    const val MIN_CHUNK_STROKES = 2.5

    /** Band span, 4x (two zoom levels). Must match the ratio between [CHUNK_METRES]. */
    const val BAND_SPAN = 4.0

    /**
     * Chunk lengths, finest first, each [BAND_SPAN] times the last. Covers zoom
     * ~10.1 and up here, lanes below that.
     *
     * Three and not two because the 240 dp map strips fit a four-race event at
     * 11.6, below where two bands end. Longer than 640 m and most corridors
     * wouldn't alternate at all. `RibbonPreviewMapZoomTest` checks both ends.
     */
    val CHUNK_METRES = listOf(40.0, 160.0, 640.0)

    /** Gap between the centres of two neighbouring lanes, in dp. */
    const val LANE_SPACING_DP = 2.6f

    /** A lane's stroke as a fraction of the width a course's line would have. */
    const val LANE_WIDTH_FRACTION = 0.55f

    /** Thinnest a lane may get, so a four-course band never fades to hairlines. */
    const val MIN_LANE_WIDTH_DP = 1.5f

    /** Fallback latitude when there is no geometry to measure - nothing is drawn anyway. */
    const val DEFAULT_LATITUDE = 0.0

    /** A chunk length and its zoom range. The finest band has no [maxZoom]. */
    data class Band(
        val chunkMetres: Double,
        val minZoom: Float,
        val maxZoom: Float?
    )

    /** Ground metres covered by one style pixel at [zoom], at [latitudeDeg]. */
    fun metresPerPixel(zoom: Double, latitudeDeg: Double): Double =
        METRES_PER_PIXEL_AT_ZOOM_0 * cos(Math.toRadians(latitudeDeg)) / Math.pow(2.0, zoom)

    /** Zoom where [metres] is [pixels] on screen. */
    fun zoomWhere(metres: Double, pixels: Double, latitudeDeg: Double): Double {
        val scale = METRES_PER_PIXEL_AT_ZOOM_0 * cos(Math.toRadians(latitudeDeg)) * pixels / metres
        return ln(scale) / ln(2.0)
    }

    /**
     * Chunk bands for a map near [latitudeDeg]. Latitude matters, the same chunk
     * is legible 1.2 zoom levels earlier in Trondheim than at the equator.
     */
    fun bands(latitudeDeg: Double, strokeWidthDp: Float): List<Band> {
        val minPixels = MIN_CHUNK_STROKES * strokeWidthDp
        var ceiling: Float? = null
        return CHUNK_METRES.map { metres ->
            val floor = zoomWhere(metres, minPixels, latitudeDeg).toFloat()
            Band(metres, minZoom = floor, maxZoom = ceiling).also { ceiling = floor }
        }
    }

    /** Below this corridors are lanes, the coarsest band's floor. */
    fun laneMaxZoom(latitudeDeg: Double, strokeWidthDp: Float): Float =
        bands(latitudeDeg, strokeWidthDp).last().minZoom

    /**
     * Offset for participant [index] of [count]. Centred on the corridor so the
     * lanes sit on the road, fixed global lanes would drift off to one side.
     */
    fun laneOffsetDp(index: Int, count: Int): Float {
        if (count <= 1) return 0f
        return (index - (count - 1) / 2f) * LANE_SPACING_DP
    }

    /** Stroke for one lane: thinner than a whole course's line, so a band of them is not a slab. */
    fun laneWidthDp(strokeWidthDp: Float): Float =
        max(MIN_LANE_WIDTH_DP, strokeWidthDp * LANE_WIDTH_FRACTION)

    /** Width of the grey backing under the widest lane band. */
    fun casingWidthDp(strokeWidthDp: Float, maxLaneCount: Int): Float {
        val lanes = max(1, maxLaneCount)
        val span = abs(laneOffsetDp(0, lanes)) + abs(laneOffsetDp(lanes - 1, lanes))
        return max(strokeWidthDp, span + laneWidthDp(strokeWidthDp))
    }
}
