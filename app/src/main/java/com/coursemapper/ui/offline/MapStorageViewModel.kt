package com.coursemapper.ui.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.offline.OfflineMapRepository
import com.coursemapper.offline.OfflineReconciler
import com.coursemapper.offline.PackWithProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapStorageUiState(
    val packs: List<PackWithProgress> = emptyList(),
    val totalBytes: Long = 0,
    val wifiOnly: Boolean = false,
    val bothThemes: Boolean = true
)

/** Map Storage: all packs, their size, and refresh, pause or delete. */
@HiltViewModel
class MapStorageViewModel @Inject constructor(
    private val offlineMapRepository: OfflineMapRepository,
    private val reconciler: OfflineReconciler,
    private val prefs: UserPreferencesRepository
) : ViewModel() {

    val uiState: StateFlow<MapStorageUiState> = combine(
        offlineMapRepository.observePacks(),
        offlineMapRepository.observeTotalBytes(),
        prefs.offlineWifiOnly,
        prefs.offlineBothThemes
    ) { packs, total, wifiOnly, bothThemes ->
        MapStorageUiState(
            packs = packs,
            totalBytes = total,
            wifiOnly = wifiOnly,
            bothThemes = bothThemes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MapStorageUiState()
    )

    init {
        // Opening this screen is a good moment to find out whether the packs it
        // is about to list still have tiles behind them.
        viewModelScope.launch { reconciler.reconcile() }
    }

    /** Start or resume a pack's download. */
    fun download(packId: Long) {
        viewModelScope.launch { offlineMapRepository.startDownload(packId) }
    }

    /** Stop a download, keeping what has arrived so far. */
    fun pause(packId: Long) {
        viewModelScope.launch { offlineMapRepository.pauseDownload(packId) }
    }

    fun delete(packId: Long) {
        viewModelScope.launch { offlineMapRepository.deletePack(packId) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfflineWifiOnly(enabled) }
    }

    fun setBothThemes(enabled: Boolean) {
        viewModelScope.launch { prefs.setOfflineBothThemes(enabled) }
    }

    /** Whether [status] can be resumed or retried by tapping Download. */
    fun isStartable(status: OfflinePackStatus): Boolean =
        status == OfflinePackStatus.PAUSED ||
            status == OfflinePackStatus.FAILED ||
            status == OfflinePackStatus.STALE
}
