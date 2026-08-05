package com.coursemapper.offline

import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.OfflineFailureReason
import kotlinx.coroutines.flow.Flow

/**
 * What we need from MapLibre's offline API, behind an interface so the download
 * orchestration can be tested with a fake. [MapLibreOfflineGatewayImpl] is the
 * real one.
 */
interface MapLibreOfflineGateway {

    /** Raise MapLibre's ceiling on tiles per region. See [DEFAULT_TILE_LIMIT]. */
    suspend fun setTileCountLimit(limit: Long)

    /** Create a region for [spec], returns MapLibre's id. Doesn't start downloading. */
    suspend fun createRegion(spec: OfflineRegionSpec): Long

    /** MapLibre ids of every region it currently knows about. */
    suspend fun listRegionIds(): Set<Long>

    /** Current status of [maplibreRegionId], or null if MapLibre has no such region. */
    suspend fun statusOf(maplibreRegionId: Long): RegionStatusSnapshot?

    /**
     * Download [maplibreRegionId] and emit status. Completes when done, throws
     * [OfflineDownloadException] on error.
     *
     * Emits the current status first - an already complete region never calls
     * back again, so waiting for one hangs forever. Cancelling parks the region.
     */
    fun download(maplibreRegionId: Long): Flow<RegionStatusSnapshot>

    /** Delete [maplibreRegionId] from MapLibre's store. Missing ids are a no-op. */
    suspend fun deleteRegion(maplibreRegionId: Long)

    /**
     * Reclaim disk from deleted regions. The store is SQLite and deleting doesn't
     * shrink the file, so without this Settings -> Storage shows no change.
     */
    suspend fun packDatabase()

    companion object {
        /**
         * MapLibre defaults to 6000 tiles per region, a buffered marathon up here
         * gets close to that. The estimator checks against this before starting.
         */
        const val DEFAULT_TILE_LIMIT = 50_000L
    }
}

/** What to download: one style, one area, one zoom window. */
data class OfflineRegionSpec(
    val styleUrl: String,
    val bounds: GeoBounds,
    val minZoom: Double,
    val maxZoom: Double,
    val pixelRatio: Float
)

/**
 * Download progress reading. Until [isRequiredCountPrecise], [requiredResources]
 * keeps growing and a percentage would go backwards.
 */
data class RegionStatusSnapshot(
    val completedResources: Long,
    val requiredResources: Long,
    val isRequiredCountPrecise: Boolean,
    val completedBytes: Long,
    val completedTiles: Long,
    val completedTileBytes: Long,
    val isComplete: Boolean
) {
    /** Mean bytes per tile, for calibrating the size estimator. Null if no tiles yet. */
    val bytesPerTile: Double?
        get() = if (completedTiles > 0) completedTileBytes.toDouble() / completedTiles else null
}

/** Download failure with a [reason], so the UI can tell "lost wifi" from "too big". */
class OfflineDownloadException(
    val reason: OfflineFailureReason,
    message: String
) : Exception(message)
