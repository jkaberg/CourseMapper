package com.coursemapper.offline

import com.coursemapper.domain.model.OfflinePackProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live download progress, in memory only.
 *
 * MapLibre reports per resource, thousands of times per pack, and writing each
 * to Room made Home stutter during downloads. Room gets state changes and a
 * checkpoint every [OfflineMapRepository.CHECKPOINT_INTERVAL_MS].
 */
@Singleton
class OfflineDownloadRegistry @Inject constructor() {

    private val _progress = MutableStateFlow<Map<Long, OfflinePackProgress>>(emptyMap())

    /** Progress for every pack currently downloading, keyed by pack id. */
    val progress: StateFlow<Map<Long, OfflinePackProgress>> = _progress.asStateFlow()

    /**
     * Record a reading. Counters only go up - the two style regions download one
     * after the other and each starts from zero.
     */
    fun report(packId: Long, snapshot: RegionStatusSnapshot) {
        _progress.update { current ->
            val previous = current[packId]
            current + (packId to OfflinePackProgress(
                packId = packId,
                completedResources = maxOf(
                    snapshot.completedResources,
                    previous?.completedResources ?: 0L
                ),
                requiredResources = maxOf(
                    snapshot.requiredResources,
                    previous?.requiredResources ?: 0L
                ),
                // not a high-water mark, the second region makes the total imprecise again
                isRequiredCountPrecise = snapshot.isRequiredCountPrecise,
                completedBytes = maxOf(
                    snapshot.completedBytes,
                    previous?.completedBytes ?: 0L
                )
            ))
        }
    }

    /** Forget [packId] - the download finished, failed, or was cancelled. */
    fun clear(packId: Long) {
        _progress.update { it - packId }
    }

    fun snapshotOf(packId: Long): OfflinePackProgress? = _progress.value[packId]
}
