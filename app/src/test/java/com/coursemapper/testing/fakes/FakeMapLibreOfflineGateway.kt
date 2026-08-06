package com.coursemapper.testing.fakes

import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.offline.MapLibreOfflineGateway
import com.coursemapper.offline.OfflineDownloadException
import com.coursemapper.offline.OfflineRegionSpec
import com.coursemapper.offline.RegionStatusSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** In-memory [MapLibreOfflineGateway]. Script snapshots or an error per region. */
class FakeMapLibreOfflineGateway : MapLibreOfflineGateway {

    /** Regions MapLibre "knows about", by id. */
    val regions = mutableMapOf<Long, OfflineRegionSpec>()

    /** Snapshots each region emits while downloading, in order. */
    val scripted = mutableMapOf<Long, List<RegionStatusSnapshot>>()

    /** Regions that fail, and with what. */
    val failures = mutableMapOf<Long, OfflineDownloadException>()

    /** Status from [statusOf] after a download, defaults to the last scripted snapshot. */
    val finalStatus = mutableMapOf<Long, RegionStatusSnapshot>()

    var tileCountLimit: Long? = null
    var packDatabaseCalls = 0
    val deletedRegionIds = mutableListOf<Long>()
    val createdSpecs = mutableListOf<OfflineRegionSpec>()

    private var nextRegionId = 1L

    /** Register a region as already present, as if downloaded earlier. */
    fun seedRegion(id: Long, spec: OfflineRegionSpec) {
        regions[id] = spec
        if (id >= nextRegionId) nextRegionId = id + 1
    }

    override suspend fun setTileCountLimit(limit: Long) {
        tileCountLimit = limit
    }

    override suspend fun createRegion(spec: OfflineRegionSpec): Long {
        val id = nextRegionId++
        regions[id] = spec
        createdSpecs += spec
        return id
    }

    override suspend fun listRegionIds(): Set<Long> = regions.keys.toSet()

    override suspend fun statusOf(maplibreRegionId: Long): RegionStatusSnapshot? {
        if (maplibreRegionId !in regions) return null
        return finalStatus[maplibreRegionId]
            ?: scripted[maplibreRegionId]?.lastOrNull()
            ?: complete()
    }

    override fun download(maplibreRegionId: Long): Flow<RegionStatusSnapshot> = flow {
        if (maplibreRegionId !in regions) {
            throw OfflineDownloadException(
                OfflineFailureReason.MISSING_TILES,
                "no region $maplibreRegionId"
            )
        }
        val steps = scripted[maplibreRegionId] ?: listOf(complete())
        for (step in steps) emit(step)
        failures[maplibreRegionId]?.let { throw it }
    }

    override suspend fun deleteRegion(maplibreRegionId: Long) {
        deletedRegionIds += maplibreRegionId
        regions.remove(maplibreRegionId)
    }

    override suspend fun packDatabase() {
        packDatabaseCalls++
    }

    companion object {
        /** A snapshot part-way through, with the resource count not yet settled. */
        fun progress(
            completed: Long,
            required: Long,
            precise: Boolean = false,
            bytes: Long = completed * 1_000
        ) = RegionStatusSnapshot(
            completedResources = completed,
            requiredResources = required,
            isRequiredCountPrecise = precise,
            completedBytes = bytes,
            completedTiles = completed,
            completedTileBytes = bytes,
            isComplete = false
        )

        /** A finished snapshot. */
        fun complete(
            resources: Long = 100,
            bytes: Long = 100_000,
            tiles: Long = 90,
            tileBytes: Long = 90_000
        ) = RegionStatusSnapshot(
            completedResources = resources,
            requiredResources = resources,
            isRequiredCountPrecise = true,
            completedBytes = bytes,
            completedTiles = tiles,
            completedTileBytes = tileBytes,
            isComplete = true
        )
    }
}
