package com.coursemapper.ui.run

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RunSign
import com.coursemapper.domain.model.RunStopState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Packing list row. Not a RunStop since the list works before a run exists. */
data class PackingStop(
    val visitNumber: Int,
    val signs: List<RunSign>,
    /** Only a real run has stop state; a preview's stops are all still ahead. */
    val state: RunStopState? = null
)

data class PackingListUiState(
    val title: String = "Packing list",
    val stops: List<PackingStop> = emptyList(),
    val isLoading: Boolean = true,
    val isFound: Boolean = true,
    /** Show the list last-stop-first, i.e. the order to load the rack. */
    val reversed: Boolean = false,
    /** Course name → sign count, for the loading summary. */
    val signsPerCourse: Map<String, Int> = emptyMap(),
    /** CSV export needs a real run to log; a preview has nothing to export yet. */
    val canExport: Boolean = false,
    val shareIntent: Intent? = null
)

/**
 * Packing list from a run ([runId]) or from courses ([courseIdsArg]), so the
 * rack can be loaded before a run is created.
 *
 * Never picks its own order. With a run it uses the run's order, otherwise the
 * ordering the launch screen offers - guessing gave the wrong order for 76 of
 * 80 stops once.
 */
@HiltViewModel
class PackingListViewModel @Inject constructor(
    private val repository: RouteRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val runId: Long = savedStateHandle["runId"] ?: -1L
    private val courseIdsArg: String = savedStateHandle["courseIds"] ?: ""
    private val orderingArg: String = savedStateHandle["ordering"] ?: ""

    /** Which candidate order to preview; ignored once [runId] names a run. */
    private val ordering: RunOrdering =
        RunOrdering.entries.firstOrNull { it.name == orderingArg } ?: RunOrdering.SPINE

    private val _state = MutableStateFlow(PackingListUiState())
    val uiState: StateFlow<PackingListUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (runId > 0) loadFromRun() else loadFromCourses()
        }
    }

    private suspend fun loadFromRun() {
        val run = repository.getRun(runId)
        if (run == null) {
            _state.update { it.copy(isLoading = false, isFound = false) }
            return
        }
        val ordered = run.stops.sortedBy { it.stopIndex }
        _state.update {
            it.copy(
                title          = "Packing — ${run.name}",
                stops          = ordered.mapIndexed { i, stop ->
                    PackingStop(i + 1, stop.signs, stop.state)
                },
                signsPerCourse = ordered.flatMap { s -> s.signs }
                    .groupingBy { s -> s.courseName }.eachCount(),
                isLoading      = false,
                canExport      = true
            )
        }
    }

    private suspend fun loadFromCourses() {
        val courseIds = courseIdsArg.split(',').mapNotNull { it.trim().toLongOrNull() }
        val preview = if (courseIds.isEmpty()) null else repository.previewRun(courseIds)
        if (preview == null || preview.stopCount == 0) {
            _state.update { it.copy(isLoading = false, isFound = false) }
            return
        }
        // launch screen's ordering, and previewRun's spine is what createRun
        // will pick too
        val order = when (ordering) {
            RunOrdering.SPINE     -> preview.spineOrder
            RunOrdering.OPTIMIZED -> preview.optimizedOrder
        }.ifEmpty { preview.stopPositions.indices.toList() }
        val stops = order.mapIndexed { visitIdx, clusterIdx ->
            PackingStop(visitIdx + 1, preview.signs.getOrElse(clusterIdx) { emptyList() })
        }
        _state.update {
            it.copy(
                title          = "Packing list",
                stops          = stops,
                signsPerCourse = stops.flatMap { s -> s.signs }
                    .groupingBy { s -> s.courseName }.eachCount(),
                isLoading      = false,
                canExport      = false
            )
        }
    }

    fun toggleReversed() = _state.update { it.copy(reversed = !it.reversed) }

    fun exportCsv() {
        if (!_state.value.canExport) return
        viewModelScope.launch {
            val intent = repository.buildRunLogShareIntent(runId) ?: return@launch
            _state.update { it.copy(shareIntent = intent) }
        }
    }

    fun consumeShareIntent() = _state.update { it.copy(shareIntent = null) }
}

/**
 * What to load and in what order, with sign totals per route. Reverse gives
 * loading order, last stop's signs at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackingListScreen(
    onBack: () -> Unit,
    viewModel: PackingListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.shareIntent) {
        val intent = state.shareIntent ?: return@LaunchedEffect
        context.startActivity(Intent.createChooser(intent, "Share packing list"))
        viewModel.consumeShareIntent()
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = state.title,
                onBack  = onBack,
                actions = {
                    IconButton(onClick = viewModel::toggleReversed) {
                        Icon(Icons.Default.SwapVert, contentDescription = "Toggle loading order")
                    }
                    if (state.canExport) {
                        IconButton(onClick = viewModel::exportCsv) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export CSV")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }
        if (!state.isFound) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing to pack yet.", color = MaterialTheme.colorScheme.error)
            }
            return@Scaffold
        }

        val total = state.stops.size
        val orderedStops = if (state.reversed) state.stops.reversed() else state.stops

        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Load the rack",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        state.signsPerCourse.forEach { (course, count) ->
                            Text(
                                "$course: $count sign(s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            if (state.reversed) {
                                "Loading order — stack top-of-list first; the last stop's signs " +
                                "end up at the bottom of the rack."
                            } else {
                                "Visit order — toggle ⇅ for the rack loading order."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            items(orderedStops, key = { it.visitNumber }) { stop ->
                ListItem(
                    headlineContent = {
                        Text(stop.signs.joinToString(" · ") { "${it.courseName} ${it.label}" }
                            .ifBlank { "Stop ${stop.visitNumber}" })
                    },
                    supportingContent = {
                        Text(
                            "Stop ${stop.visitNumber} of $total" + when (stop.state) {
                                RunStopState.DONE    -> " · done"
                                RunStopState.SKIPPED -> " · skipped"
                                else                 -> ""
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    leadingContent = {
                        Text(
                            "${stop.visitNumber}",
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.primary
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            }
        }
    }
}
