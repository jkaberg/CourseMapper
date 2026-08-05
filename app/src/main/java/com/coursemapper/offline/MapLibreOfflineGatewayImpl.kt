package com.coursemapper.offline

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.coursemapper.domain.model.OfflineFailureReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * The only class touching MapLibre's [OfflineManager]. MapLibre wants the main
 * thread, so every call posts there and suspends until the callback.
 */
@Singleton
class MapLibreOfflineGatewayImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MapLibreOfflineGateway {

    private val main = Handler(Looper.getMainLooper())

    private fun manager(): OfflineManager = OfflineManager.getInstance(context)

    /** Run [block] on the main thread; MapLibre asserts on any other. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    override suspend fun setTileCountLimit(limit: Long) = suspendCoroutine { cont ->
        onMain {
            manager().setOfflineMapboxTileCountLimit(limit)
            cont.resume(Unit)
        }
    }

    override suspend fun createRegion(spec: OfflineRegionSpec): Long {
        val deferred = CompletableDeferred<Long>()
        val definition = OfflineTilePyramidRegionDefinition(
            spec.styleUrl,
            LatLngBounds.from(
                spec.bounds.north,
                spec.bounds.east,
                spec.bounds.south,
                spec.bounds.west
            ),
            spec.minZoom,
            spec.maxZoom,
            spec.pixelRatio
        )
        onMain {
            manager().createOfflineRegion(
                definition,
                EMPTY_METADATA,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        deferred.complete(offlineRegion.id)
                    }

                    override fun onError(error: String) {
                        deferred.completeExceptionally(
                            OfflineDownloadException(
                                OfflineFailureReason.UNKNOWN,
                                "createOfflineRegion: $error"
                            )
                        )
                    }
                }
            )
        }
        return deferred.await()
    }

    override suspend fun listRegionIds(): Set<Long> =
        listRegions().map { it.id }.toSet()

    override suspend fun statusOf(maplibreRegionId: Long): RegionStatusSnapshot? {
        val region = findRegion(maplibreRegionId) ?: return null
        return statusOf(region).toSnapshot()
    }

    override fun download(maplibreRegionId: Long): Flow<RegionStatusSnapshot> = callbackFlow {
        val region = findRegion(maplibreRegionId)
            ?: throw OfflineDownloadException(
                OfflineFailureReason.MISSING_TILES,
                "MapLibre has no region $maplibreRegionId"
            )

        val observer = object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                trySend(status.toSnapshot())
                if (status.isComplete) close()
            }

            override fun onError(error: OfflineRegionError) {
                close(
                    OfflineDownloadException(
                        error.reason.toFailureReason(),
                        error.message.ifBlank { "Offline download failed (${error.reason})" }
                    )
                )
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                close(
                    OfflineDownloadException(
                        OfflineFailureReason.TILE_LIMIT,
                        "This area needs more than $limit tiles"
                    )
                )
            }
        }

        onMain {
            region.setObserver(observer)
            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        }

        // emit current status, a complete region won't call back again
        val current = statusOf(region).toSnapshot()
        trySend(current)
        if (current.isComplete) close()

        awaitClose {
            // Detach before parking: an observer left attached keeps a
            // reference to this flow's channel and goes on reporting into a
            // collector that is gone.
            onMain {
                region.setObserver(null)
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            }
        }
    }

    override suspend fun deleteRegion(maplibreRegionId: Long) {
        val region = findRegion(maplibreRegionId) ?: return
        suspendCoroutine { cont ->
            onMain {
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = cont.resume(Unit)
                    override fun onError(error: String) =
                        cont.resumeWithException(
                            OfflineDownloadException(
                                OfflineFailureReason.UNKNOWN,
                                "OfflineRegion.delete: $error"
                            )
                        )
                })
            }
        }
    }

    override suspend fun packDatabase() {
        suspendCoroutine { cont ->
            onMain {
                manager().packDatabase(object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() = cont.resume(Unit)
                    override fun onError(message: String) {
                        // packing is just disk space, don't fail the delete over it
                        cont.resume(Unit)
                    }
                })
            }
        }
    }

    private suspend fun listRegions(): List<OfflineRegion> {
        val deferred = CompletableDeferred<List<OfflineRegion>>()
        onMain {
            manager().listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    deferred.complete(offlineRegions?.toList().orEmpty())
                }

                override fun onError(error: String) {
                    deferred.completeExceptionally(
                        OfflineDownloadException(
                            OfflineFailureReason.UNKNOWN,
                            "listOfflineRegions: $error"
                        )
                    )
                }
            })
        }
        return deferred.await()
    }

    private suspend fun findRegion(maplibreRegionId: Long): OfflineRegion? =
        listRegions().firstOrNull { it.id == maplibreRegionId }

    private suspend fun statusOf(region: OfflineRegion): OfflineRegionStatus {
        val deferred = CompletableDeferred<OfflineRegionStatus>()
        onMain {
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status != null) deferred.complete(status)
                    else deferred.completeExceptionally(
                        OfflineDownloadException(
                            OfflineFailureReason.UNKNOWN,
                            "getStatus returned null"
                        )
                    )
                }

                override fun onError(error: String?) {
                    deferred.completeExceptionally(
                        OfflineDownloadException(
                            OfflineFailureReason.UNKNOWN,
                            "getStatus: $error"
                        )
                    )
                }
            })
        }
        return deferred.await()
    }

    private companion object {
        val EMPTY_METADATA = ByteArray(0)
    }
}

private fun OfflineRegionStatus.toSnapshot() = RegionStatusSnapshot(
    completedResources = completedResourceCount,
    requiredResources = requiredResourceCount,
    isRequiredCountPrecise = isRequiredResourceCountPrecise,
    completedBytes = completedResourceSize,
    completedTiles = completedTileCount,
    completedTileBytes = completedTileSize,
    isComplete = isComplete
)

/** Connection and server failures are both retryable, neither means damaged tiles. */
private fun String.toFailureReason(): OfflineFailureReason = when (this) {
    OfflineRegionError.REASON_CONNECTION -> OfflineFailureReason.NETWORK
    OfflineRegionError.REASON_SERVER -> OfflineFailureReason.NETWORK
    OfflineRegionError.REASON_NOT_FOUND -> OfflineFailureReason.MISSING_TILES
    else -> OfflineFailureReason.UNKNOWN
}
