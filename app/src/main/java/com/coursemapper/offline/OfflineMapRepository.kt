package com.coursemapper.offline

import com.coursemapper.data.db.OfflinePackCourseEntity
import com.coursemapper.data.db.OfflinePackDao
import com.coursemapper.data.db.OfflinePackEntity
import com.coursemapper.data.db.OfflinePackRegionEntity
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.domain.OfflineSizeEstimator
import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.MapStyleVariant
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflineMapPack
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackProgress
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.domain.model.OfflineZoom
import com.coursemapper.map.STYLE_URL_DARK
import com.coursemapper.map.STYLE_URL_LIGHT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline maps: packs, downloads, coverage and deletion. Nothing else talks to
 * MapLibre or the pack tables.
 *
 * Doesn't load course geometry, [com.coursemapper.data.repository.RouteRepository]
 * passes it in. Maps only know course ids.
 */
@Singleton
class OfflineMapRepository @Inject constructor(
    private val packDao: OfflinePackDao,
    private val gateway: MapLibreOfflineGateway,
    private val registry: OfflineDownloadRegistry,
    private val estimator: OfflineSizeEstimator,
    private val storage: DeviceStorage,
    private val scheduler: OfflineWorkScheduler,
    private val prefs: UserPreferencesRepository
) {

    /** Every pack, newest activity first, with live progress merged in. */
    fun observePacks(): Flow<List<PackWithProgress>> =
        combine(packDao.observeAll(), registry.progress) { packs, progress ->
            packs.map { entity ->
                PackWithProgress(
                    pack = entity.toDomain(emptyList()),
                    progress = progress[entity.id]
                )
            }
        }

    /** Total bytes attributed to packs. See [OfflinePackEntity.sizeBytes]. */
    fun observeTotalBytes(): Flow<Long> = packDao.observeTotalBytes()

    /** Worst coverage across [courseIds], one uncovered course means the run goes blank somewhere. */
    fun observeCoverage(courseIds: List<Long>): Flow<OfflineCoverage> {
        if (courseIds.isEmpty()) {
            return kotlinx.coroutines.flow.flowOf(OfflineCoverage.NONE_NEEDED)
        }
        return combine(packDao.observeAll(), registry.progress) { packs, progress ->
            coverageOf(courseIds, packs, progress)
        }
    }

    /** One-shot version of [observeCoverage]. */
    suspend fun coverageOf(courseIds: List<Long>): OfflineCoverage {
        if (courseIds.isEmpty()) return OfflineCoverage.NONE_NEEDED
        return coverageOf(courseIds, packDao.observeAll().first(), registry.progress.value)
    }

    private suspend fun coverageOf(
        courseIds: List<Long>,
        packs: List<OfflinePackEntity>,
        progress: Map<Long, OfflinePackProgress>
    ): OfflineCoverage {
        val byId = packs.associateBy { it.id }
        var worst: OfflinePackStatus? = OfflinePackStatus.READY
        var anyPackId: Long? = null
        var failure: OfflineFailureReason? = null

        for (courseId in courseIds) {
            val covering = packDao.getPacksForCourse(courseId)
                .mapNotNull { byId[it.id] }
            val best = covering.minByOrNull { rank(it.status) }

            if (best == null) {
                worst = null
                continue
            }
            anyPackId = anyPackId ?: best.id
            if (best.status == OfflinePackStatus.FAILED) failure = best.failureReason
            if (worst != null && rank(best.status) > rank(worst)) worst = best.status
        }

        val inFlight = anyPackId?.let { progress[it] }
        return OfflineCoverage(
            status = worst,
            failureReason = failure,
            progress = inFlight,
            packId = anyPackId
        )
    }

    /** Lower is better; used to pick the best pack and the worst course. */
    private fun rank(status: OfflinePackStatus): Int = when (status) {
        OfflinePackStatus.READY -> 0
        OfflinePackStatus.STALE -> 1
        OfflinePackStatus.DOWNLOADING -> 2
        OfflinePackStatus.QUEUED -> 3
        OfflinePackStatus.PAUSED -> 4
        OfflinePackStatus.FAILED -> 5
    }

    /** Cost of a pack over [bounds], using the measured bytes per tile and real free space. */
    suspend fun quote(bounds: GeoBounds): PackQuote {
        val bytesPerTile = prefs.offlineBytesPerTile.first()
        val bothThemes = prefs.offlineBothThemes.first()
        val estimate = estimator.estimate(
            bounds = bounds,
            minZoom = OfflineZoom.MIN_ZOOM,
            maxZoom = OfflineZoom.MAX_ZOOM,
            bytesPerTile = bytesPerTile,
            includeBothStyles = bothThemes
        )
        return PackQuote(
            bounds = bounds,
            tileCount = estimate.tileCount,
            estimatedBytes = estimate.bytes,
            exceedsTileLimit = estimate.exceedsTileLimit,
            hasRoom = storage.hasRoomFor(estimate.bytes),
            freeBytes = storage.freeBytes()
        )
    }

    /**
     * Create a pack over [bounds] for [courseIds], queue it if [startNow]. Reuses
     * an existing pack covering the area, so it's safe to call twice.
     */
    suspend fun createPack(
        label: String,
        bounds: GeoBounds,
        courseIds: List<Long>,
        choice: OfflinePackChoice,
        startNow: Boolean
    ): Long {
        existingPackFor(bounds)?.let { existing ->
            packDao.insertCourseLinks(courseIds.map { OfflinePackCourseEntity(existing.id, it) })
            if (choice != OfflinePackChoice.UNSET) {
                packDao.setUserChoice(existing.id, choice)
            }
            if (startNow && !existing.status.isUsable && !existing.status.isInFlight) {
                startDownload(existing.id)
            }
            return existing.id
        }

        val quote = quote(bounds)
        val packId = packDao.insertPack(
            OfflinePackEntity(
                label = label,
                boundsJson = bounds.toJson(),
                status = if (startNow) OfflinePackStatus.QUEUED else OfflinePackStatus.PAUSED,
                userChoice = choice,
                estimatedBytes = quote.estimatedBytes,
                minZoom = OfflineZoom.MIN_ZOOM,
                maxZoom = OfflineZoom.MAX_ZOOM
            )
        )
        packDao.insertCourseLinks(courseIds.map { OfflinePackCourseEntity(packId, it) })

        for (variant in variantsToDownload()) {
            packDao.insertRegion(
                OfflinePackRegionEntity(packId = packId, styleVariant = variant.key)
            )
        }

        if (startNow) startDownload(packId)
        return packId
    }

    /**
     * Existing pack whose area contains [bounds], matched on geography and any
     * status. Next week's 5 K at the same venue joins the marathon's map, and a
     * declined (PAUSED) or FAILED pack is reused rather than duplicated. Best
     * status wins.
     */
    private suspend fun existingPackFor(bounds: GeoBounds): OfflinePackEntity? =
        packDao.observeAllSnapshot()
            .filter { pack ->
                GeoBounds.fromJsonOrNull(pack.boundsJson)?.contains(bounds) == true
            }
            .minByOrNull { rank(it.status) }

    /** Record that the user declined a pack, so nothing offers it again. */
    suspend fun declinePack(packId: Long) {
        packDao.setUserChoice(packId, OfflinePackChoice.DECLINED)
    }

    /** Strongest answer given for [courseIds]. One DECLINED covers the whole area. */
    suspend fun userChoiceFor(courseIds: List<Long>): OfflinePackChoice? {
        val choices = courseIds
            .flatMap { packDao.getPacksForCourse(it) }
            .map { it.userChoice }
        return when {
            choices.isEmpty() -> null
            choices.any { it == OfflinePackChoice.DECLINED } -> OfflinePackChoice.DECLINED
            choices.any { it == OfflinePackChoice.ACCEPTED } -> OfflinePackChoice.ACCEPTED
            else -> OfflinePackChoice.UNSET
        }
    }

    /** Bytes actually freed by deleting [courseId], null if its packs are shared. */
    suspend fun bytesFreedByDeletingCourse(courseId: Long): Long? {
        val freed = packDao.getPacksForCourse(courseId)
            .filter { pack ->
                // The pack is released only if this course is the last live one
                // on it.  Anything else and the tiles stay exactly where they are.
                packDao.liveCourseIds(pack.id).none { it != courseId }
            }
            .sumOf { it.sizeBytes }
        return freed.takeIf { it > 0L }
    }

    /** Queue [packId] with WorkManager, honouring the Wi-Fi-only preference. */
    suspend fun startDownload(packId: Long) {
        packDao.setStatus(packId, OfflinePackStatus.QUEUED, null, null)
        packDao.setUserChoice(packId, OfflinePackChoice.ACCEPTED)
        scheduler.enqueue(packId, wifiOnly = prefs.offlineWifiOnly.first())
    }

    /** Stop [packId], leaving what has been downloaded in place to resume from. */
    suspend fun pauseDownload(packId: Long) {
        scheduler.cancel(packId)
        registry.clear(packId)
        packDao.setStatus(packId, OfflinePackStatus.PAUSED, null, null)
    }

    /**
     * Download [packId], both styles. Called by [OfflinePreparationWorker].
     * Cancelling parks the pack as PAUSED.
     */
    suspend fun runDownload(packId: Long) {
        val withRegions = packDao.getWithRegions(packId) ?: return
        val pack = withRegions.pack
        val bounds = GeoBounds.fromJsonOrNull(pack.boundsJson)
            ?: run {
                fail(packId, OfflineFailureReason.NO_GEOMETRY, "Pack has no usable area")
                return
            }

        if (!storage.hasRoomFor(pack.estimatedBytes)) {
            fail(
                packId,
                OfflineFailureReason.DISK,
                "Not enough free space for about ${pack.estimatedBytes / (1024 * 1024)} MB"
            )
            return
        }

        gateway.setTileCountLimit(MapLibreOfflineGateway.DEFAULT_TILE_LIMIT)
        packDao.setStatus(packId, OfflinePackStatus.DOWNLOADING, null, null)

        var lastCheckpoint = 0L
        var biggestRegionBytes = 0L
        var biggestRegionTileBytes = 0L
        var lastSnapshot: RegionStatusSnapshot? = null

        try {
            // Sequential, not parallel: the two style variants share their tiles,
            // and downloading them at once would race for the same resources and
            // fetch a good number of them twice.
            for (regionRow in withRegions.regions) {
                val maplibreId = ensureMaplibreRegion(regionRow, bounds, pack)

                gateway.download(maplibreId).collect { snapshot ->
                    lastSnapshot = snapshot
                    registry.report(packId, snapshot)

                    val now = System.currentTimeMillis()
                    if (now - lastCheckpoint >= CHECKPOINT_INTERVAL_MS) {
                        lastCheckpoint = now
                        val merged = registry.snapshotOf(packId)
                        if (merged != null) {
                            packDao.checkpointProgress(
                                id = packId,
                                sizeBytes = merged.completedBytes,
                                completed = merged.completedResources,
                                required = merged.requiredResources
                            )
                        }
                    }
                }

                val finalStatus = gateway.statusOf(maplibreId)
                if (finalStatus != null) {
                    biggestRegionBytes = maxOf(biggestRegionBytes, finalStatus.completedBytes)
                    biggestRegionTileBytes =
                        maxOf(biggestRegionTileBytes, finalStatus.completedTileBytes)
                    packDao.setRegionStatus(
                        regionRow.id,
                        OfflinePackStatus.READY,
                        finalStatus.completedBytes
                    )
                }
            }

            val merged = registry.snapshotOf(packId)
            packDao.markReady(
                id = packId,
                // regions share tiles so summing double counts, the biggest one
                // is close enough (the other adds sprite and glyphs)
                sizeBytes = biggestRegionBytes,
                completed = merged?.completedResources ?: 0L,
                required = merged?.requiredResources ?: 0L
            )
            calibrateFrom(bounds, pack, biggestRegionTileBytes)
        } catch (e: CancellationException) {
            // NonCancellable: the scope is already cancelled, and without this
            // the status write is dropped and the pack is left claiming to be
            // downloading forever.
            withContext(NonCancellable) {
                packDao.setStatus(packId, OfflinePackStatus.PAUSED, null, null)
            }
            throw e
        } catch (e: OfflineDownloadException) {
            fail(packId, e.reason, e.message ?: "Download failed")
        } catch (e: Exception) {
            fail(packId, OfflineFailureReason.UNKNOWN, e.message ?: "Download failed")
        } finally {
            registry.clear(packId)
        }
    }

    /**
     * Create the region for [regionRow] if needed. A stored id MapLibre doesn't
     * know (cleared app storage) is replaced, not trusted.
     */
    private suspend fun ensureMaplibreRegion(
        regionRow: OfflinePackRegionEntity,
        bounds: GeoBounds,
        pack: OfflinePackEntity
    ): Long {
        val existing = regionRow.maplibreRegionId
        if (existing != null && existing in gateway.listRegionIds()) return existing

        val created = gateway.createRegion(
            OfflineRegionSpec(
                styleUrl = styleUrlFor(MapStyleVariant.fromKey(regionRow.styleVariant)),
                bounds = bounds,
                minZoom = pack.minZoom,
                maxZoom = pack.maxZoom,
                pixelRatio = PIXEL_RATIO
            )
        )
        packDao.setMaplibreRegionId(regionRow.id, created)
        return created
    }

    private suspend fun fail(packId: Long, reason: OfflineFailureReason, message: String) {
        withContext(NonCancellable) {
            packDao.setStatus(packId, OfflinePackStatus.FAILED, reason, message)
        }
    }

    /**
     * Store measured bytes per predicted tile for the next estimate. Covers both
     * tile weight and any error in the tile count.
     */
    private suspend fun calibrateFrom(
        bounds: GeoBounds,
        pack: OfflinePackEntity,
        tileBytes: Long
    ) {
        if (tileBytes <= 0L) return
        val predicted = estimator.tileCount(bounds, pack.minZoom, pack.maxZoom)
        if (predicted <= 0L) return
        val measured = tileBytes.toDouble() / predicted
        if (measured.isFinite() && measured > 0.0) {
            prefs.setOfflineBytesPerTile(measured)
        }
    }

    /** Remove packs no live course needs after deleting [courseId]. */
    suspend fun onCourseDeleted(courseId: Long) {
        val affected = packDao.getPacksForCourse(courseId).map { it.id }
        packDao.deleteCourseLinks(courseId)

        var deletedAny = false
        for (packId in affected) {
            if (packDao.liveCourseIds(packId).isEmpty()) {
                deletePackInternal(packId, pack = false)
                deletedAny = true
            }
        }
        if (deletedAny) gateway.packDatabase()
    }

    /** Delete [packId] and its tiles outright, at the user's request. */
    suspend fun deletePack(packId: Long) {
        deletePackInternal(packId, pack = true)
    }

    /**
     * MapLibre regions first, then rows. If we die in between, the rows point at
     * nothing and [OfflineReconciler] fixes it on next launch - the other order
     * would leave regions nothing can find.
     */
    private suspend fun deletePackInternal(packId: Long, pack: Boolean) {
        scheduler.cancel(packId)
        registry.clear(packId)

        for (region in packDao.getRegions(packId)) {
            region.maplibreRegionId?.let { runCatching { gateway.deleteRegion(it) } }
        }
        packDao.deletePack(packId)

        // Without this the SQLite file keeps every page the deleted tiles were
        // using, so the user deletes a course, checks their storage, and sees
        // that nothing was freed at all.
        if (pack) gateway.packDatabase()
    }

    private suspend fun variantsToDownload(): List<MapStyleVariant> =
        if (prefs.offlineBothThemes.first()) {
            listOf(MapStyleVariant.LIGHT, MapStyleVariant.DARK)
        } else {
            listOf(MapStyleVariant.LIGHT)
        }

    private fun styleUrlFor(variant: MapStyleVariant): String = when (variant) {
        MapStyleVariant.LIGHT -> STYLE_URL_LIGHT
        MapStyleVariant.DARK -> STYLE_URL_DARK
    }

    suspend fun getPack(packId: Long): OfflineMapPack? {
        val entity = packDao.getById(packId) ?: return null
        return entity.toDomain(packDao.liveCourseIds(packId))
    }

    companion object {
        /** Min gap between progress writes to Room. */
        const val CHECKPOINT_INTERVAL_MS = 2_000L

        /** Fixed at 1 so a pack works across screen densities. */
        const val PIXEL_RATIO = 1.0f
    }
}

/** A pack plus whatever it is doing right now. */
data class PackWithProgress(
    val pack: OfflineMapPack,
    val progress: OfflinePackProgress?
)

/** What a download would cost, before committing to it. */
data class PackQuote(
    val bounds: GeoBounds,
    val tileCount: Long,
    val estimatedBytes: Long,
    val exceedsTileLimit: Boolean,
    val hasRoom: Boolean,
    val freeBytes: Long
) {
    /** True when the download can be offered at all. */
    val isViable: Boolean get() = !exceedsTileLimit && hasRoom
}

/** Offline readiness for a set of courses. */
data class OfflineCoverage(
    /** Worst status across the set; null when a course has no pack at all. */
    val status: OfflinePackStatus?,
    val failureReason: OfflineFailureReason?,
    val progress: OfflinePackProgress?,
    val packId: Long?
) {
    val isReady: Boolean get() = status?.isUsable == true
    val isInFlight: Boolean get() = status?.isInFlight == true
    val isMissing: Boolean get() = status == null

    companion object {
        /** No courses to cover - vacuously fine, and never a reason to warn. */
        val NONE_NEEDED = OfflineCoverage(OfflinePackStatus.READY, null, null, null)
    }
}

private fun OfflinePackEntity.toDomain(courseIds: List<Long>) = OfflineMapPack(
    id = id,
    label = label,
    bounds = GeoBounds.fromJsonOrNull(boundsJson)
        ?: GeoBounds(0.0, 0.0, 0.0, 0.0),
    status = status,
    failureReason = failureReason,
    failureMessage = failureMessage,
    userChoice = userChoice,
    estimatedBytes = estimatedBytes,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    downloadedAt = downloadedAt,
    updatedAt = updatedAt,
    courseIds = courseIds
)
