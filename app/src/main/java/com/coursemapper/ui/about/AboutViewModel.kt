package com.coursemapper.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether the About card is still due its one automatic showing. Own view
 * model since HomeViewModel's combine is at its arity limit. Starts false so
 * the dialog doesn't flash while DataStore loads.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository
) : ViewModel() {

    val showOnFirstRun: StateFlow<Boolean> = prefs.hasSeenAbout
        .map { seen -> !seen }
        .stateIn(
            scope        = viewModelScope,
            started      = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /** Mark as seen, on every way out of the first showing. */
    fun markSeen() {
        viewModelScope.launch { prefs.markAboutSeen() }
    }
}
