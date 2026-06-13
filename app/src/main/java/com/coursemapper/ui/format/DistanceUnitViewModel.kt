package com.coursemapper.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Distance unit preference as a [StateFlow], see [rememberDistanceUnit]. */
@HiltViewModel
class DistanceUnitViewModel @Inject constructor(
    prefs: UserPreferencesRepository
) : ViewModel() {
    val unit: StateFlow<DistanceFormatter.DistanceUnit> = prefs.distanceUnit
        .map { DistanceFormatter.DistanceUnit.from(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DistanceFormatter.DistanceUnit.KM
        )
}

/** Current distance unit as Compose [State], recomposes when the preference changes. */
@Composable
fun rememberDistanceUnit(): State<DistanceFormatter.DistanceUnit> {
    // A preview or a screenshot test has no Hilt graph, so hiltViewModel() below
    // would throw before anything could be drawn.  See [LocalDistanceUnit].
    LocalDistanceUnit.current?.let { fixed ->
        return remember(fixed) { mutableStateOf(fixed) }
    }
    val vm: DistanceUnitViewModel = hiltViewModel()
    return vm.unit.collectAsState()
}
