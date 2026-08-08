package com.coursemapper.offline

import com.coursemapper.data.db.OfflinePackDao
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Makes Room and MapLibre agree on what's downloaded. They drift silently:
 * - app storage cleared, Room still says READY over an empty map
 * - process died between deleting region and row (either way)
 * - a download died and still says DOWNLOADING
 * - the last course of a pack was deleted while the app wasn't running
 *
 * Runs on startup and when the navigation gate opens.
 */
@Singleton
class OfflineReconciler @Inject constructor(
    private val packDao: OfflinePackDao,
    private val gateway: MapLibreOfflineGateway,
    private val registry: OfflineDownloadRegistry
) {

    /** Sweep everything. Safe to repeat, failures are swallowed so startup isn't blocked. */
    suspend fun reconcile(now: Long = System.currentTimeMillis()) {
        runCatching { reconcileInternal(now) }
    }

    private suspend fun reconcileInternal(now: Long) {
        val knownToMapLibre = gateway.listRegionIds()
        var needsPacking = false

        // 1. Packs no live course needs any more.  Covers deletions that
        //    happened while this process was not running to hear about them.
        for (orphanId in packDao.findOrphanPackIds()) {
            for (region in packDao.getRegions(orphanId)) {
                region.maplibreRegionId?.let { runCatching { gateway.deleteRegion(it) } }
            }
            packDao.deletePack(orphanId)
            needsPacking = true
        }

        // 2. Packs whose tiles are gone, and downloads with no worker behind
        //    them.  Both leave a pack claiming a state it is not in.
        val packs = packDao.observeAllSnapshot()
        for (pack in packs) {
            val regions = packDao.getRegions(pack.id)
            val liveRegionIds = regions.mapNotNull { it.maplibreRegionId }
                .filter { it in knownToMapLibre }

            when {
                // Claimed ready, but MapLibre has none of its regions.
                pack.status.isUsable && liveRegionIds.isEmpty() -> {
                    packDao.setStatus(
                        pack.id,
                        OfflinePackStatus.FAILED,
                        OfflineFailureReason.MISSING_TILES,
                        "The downloaded tiles are no longer on this device"
                    )
                }

                // Claimed ready, but only some styles survived.  Not a failure
                // - the map still draws in one theme - but it must not stay
                // READY, or nothing will ever fetch the missing half back.
                pack.status.isUsable && liveRegionIds.size < regions.size -> {
                    packDao.setStatus(
                        pack.id,
                        OfflinePackStatus.PAUSED,
                        null,
                        "Part of this map is missing"
                    )
                }

                // Downloading, but this process has no download running.  A
                // worker in flight always has a registry entry; without one the
                // status is left over from a process that died.
                pack.status == OfflinePackStatus.DOWNLOADING &&
                    registry.snapshotOf(pack.id) == null -> {
                    packDao.setStatus(pack.id, OfflinePackStatus.PAUSED, null, null)
                }

                // Old enough that the map data underneath has likely moved.
                // Still usable - this is an offer to refresh, not a fault.
                pack.status == OfflinePackStatus.READY &&
                    pack.downloadedAt != null &&
                    now - pack.downloadedAt > STALE_AFTER_MS -> {
                    packDao.setStatus(pack.id, OfflinePackStatus.STALE, null, null)
                }
            }
        }

        // 3. MapLibre regions no pack row claims.  These are invisible to every
        //    screen and can only ever be found from this side.
        val claimed = packDao.getAllRegions().mapNotNull { it.maplibreRegionId }.toSet()
        for (strayId in knownToMapLibre - claimed) {
            runCatching { gateway.deleteRegion(strayId) }
            needsPacking = true
        }

        if (needsPacking) gateway.packDatabase()
    }

    companion object {
        /** After 90 days a pack is offered a refresh, it's still a working map. */
        const val STALE_AFTER_MS = 90L * 24 * 60 * 60 * 1000
    }
}
