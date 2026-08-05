package com.coursemapper.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.coursemapper.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Queues and cancels downloads, the one place that knows the WorkManager constraints. */
@Singleton
class OfflineWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Enqueue [packId]. [wifiOnly] means UNMETERED, default is CONNECTED.
     * KEEP so tapping Download twice doesn't restart it.
     */
    fun enqueue(packId: Long, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<OfflinePreparationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setInputData(workDataOf(OfflinePreparationWorker.KEY_PACK_ID to packId))
            .addTag(tagFor(packId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(tagFor(packId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(packId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(tagFor(packId))
    }

    companion object {
        fun tagFor(packId: Long) = "offline_pack_$packId"
    }
}

/** Downloads one pack. Foreground worker since it runs for minutes with the screen off. */
@HiltWorker
class OfflinePreparationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val offlineMapRepository: OfflineMapRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val packId = inputData.getLong(KEY_PACK_ID, -1L)
        if (packId == -1L) return Result.failure()

        runCatching { setForeground(foregroundInfo(packId)) }

        return try {
            offlineMapRepository.runDownload(packId)
            Result.success()
        } catch (e: Exception) {
            // runDownload already recorded the failure reason on the pack,
            // this retry is just for transient trouble
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun foregroundInfo(packId: Long): ForegroundInfo {
        ensureChannel()
        val notification: Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Downloading offline map")
                .setContentText("Preparing map tiles for use without a signal")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setSilent(true)
                .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                packId.toInt() + NOTIFICATION_ID_BASE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(packId.toInt() + NOTIFICATION_ID_BASE, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Offline map downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress while map tiles are downloaded for offline use"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        private const val CHANNEL_ID = "offline_map_downloads"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val MAX_ATTEMPTS = 3
    }
}
