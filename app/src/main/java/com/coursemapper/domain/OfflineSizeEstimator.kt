package com.coursemapper.domain

import com.coursemapper.domain.model.GeoBounds
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.tan

/**
 * Tiles and bytes a pack will cost, before downloading. Used for the prompt,
 * the MapLibre tile limit and free disk space.
 */
@Singleton
class OfflineSizeEstimator @Inject constructor() {

    /** Web Mercator tiles covering [bounds] from [minZoom] to [maxZoom]. Upper bound for a bbox region. */
    fun tileCount(
        bounds: GeoBounds,
        minZoom: Double,
        maxZoom: Double
    ): Long {
        val lo = floor(minZoom).toInt().coerceAtLeast(0)
        // capped, vector basemaps stop a few levels below render zoom and
        // overzoom the rest. The calibration in [estimateBytes] covers the rest.
        val hi = floor(minOf(maxZoom, SOURCE_MAX_ZOOM)).toInt().coerceAtLeast(lo)

        var total = 0L
        for (z in lo..hi) {
            total += tilesAtZoom(bounds, z)
        }
        return total
    }

    /** Tile-index rectangle covering [bounds] at [zoom]. */
    fun tilesAtZoom(bounds: GeoBounds, zoom: Int): Long {
        val n = 2.0.pow(zoom)

        val xMin = lonToTileX(bounds.west, n)
        val xMax = lonToTileX(bounds.east, n)
        // Tile Y runs north to south, so the north edge gives the smaller index.
        val yMin = latToTileY(bounds.north, n)
        val yMax = latToTileY(bounds.south, n)

        val wide = (xMax - xMin + 1).coerceAtLeast(1)
        val tall = (yMax - yMin + 1).coerceAtLeast(1)
        return wide.toLong() * tall.toLong()
    }

    /**
     * Expected bytes. [bytesPerTile] is measured (downloaded bytes / predicted
     * tiles) after each pack, so the estimate corrects itself from the second
     * download. See [com.coursemapper.offline.OfflineMapRepository.calibrateFrom].
     */
    fun estimateBytes(tileCount: Long, bytesPerTile: Double): Long =
        (tileCount * bytesPerTile).toLong().coerceAtLeast(0L)

    /**
     * Estimate for [bounds] with both styles. Tiles are shared between them so
     * the second style only adds [SECOND_STYLE_OVERHEAD].
     */
    fun estimate(
        bounds: GeoBounds,
        minZoom: Double,
        maxZoom: Double,
        bytesPerTile: Double,
        includeBothStyles: Boolean
    ): Estimate {
        val tiles = tileCount(bounds, minZoom, maxZoom)
        val tileBytes = estimateBytes(tiles, bytesPerTile)
        val styleBytes = if (includeBothStyles) SECOND_STYLE_OVERHEAD else 0L
        return Estimate(
            tileCount = tiles,
            bytes = tileBytes + styleBytes,
            exceedsTileLimit = tiles > TILE_LIMIT_WARNING
        )
    }

    data class Estimate(
        val tileCount: Long,
        val bytes: Long,
        /** True when the area is large enough that MapLibre may refuse it. */
        val exceedsTileLimit: Boolean
    )

    companion object {
        /** First guess until a pack has been measured. */
        const val DEFAULT_BYTES_PER_TILE = 12_000.0

        /**
         * Highest zoom assumed to exist in the tile source. See [tileCount].
         */
        const val SOURCE_MAX_ZOOM = 14.0

        /** Extra bytes for the second style's descriptor, sprite and glyphs. */
        const val SECOND_STYLE_OVERHEAD = 3L * 1024 * 1024

        /** Warn below MapLibre's tile limit so the warning comes before the failure. */
        const val TILE_LIMIT_WARNING = 40_000L

        private fun lonToTileX(lon: Double, n: Double): Int {
            val clamped = lon.coerceIn(-180.0, 180.0)
            val x = floor((clamped + 180.0) / 360.0 * n).toInt()
            return x.coerceIn(0, (n - 1).toInt())
        }

        private fun latToTileY(lat: Double, n: Double): Int {
            // Web Mercator is undefined at the poles; clamp to the standard
            // ±85.0511° cutoff that the tile scheme itself uses.
            val clamped = lat.coerceIn(-85.05112878, 85.05112878)
            val rad = clamped * PI / 180.0
            val y = floor((1.0 - asinh(tan(rad)) / PI) / 2.0 * n).toInt()
            return y.coerceIn(0, (n - 1).toInt())
        }
    }
}
