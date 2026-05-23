package com.coursemapper

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.coursemapper.map.initMapLibre
import com.coursemapper.offline.MapLibreOfflineGateway
import com.coursemapper.offline.OfflineReconciler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkManager is set up with [HiltWorkerFactory] here, the default initializer
 * is removed in the manifest.
 */
@HiltAndroidApp
class CourseMapperApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var offlineReconciler: OfflineReconciler

    @Inject
    lateinit var offlineGateway: MapLibreOfflineGateway

    override fun onCreate() {
        super.onCreate()
        initMapLibre(this)

            // Room and MapLibre can disagree silently about what's downloaded,
            // reconcile on startup (and at the navigation gate)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            offlineGateway.setTileCountLimit(MapLibreOfflineGateway.DEFAULT_TILE_LIMIT)
            offlineReconciler.reconcile()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
