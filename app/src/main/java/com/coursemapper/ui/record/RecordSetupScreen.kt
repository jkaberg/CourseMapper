package com.coursemapper.ui.record

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.ui.components.EndpointMarkerToggles
import com.coursemapper.ui.components.TargetDistanceChips

/**
 * Record setup: name, notes, preset, target distance and laps, with a preview
 * of the composition before going to the GPS gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordSetupScreen(
    onBack: () -> Unit,
    onPresetsClicked: () -> Unit = {},
    onProceed: (
        name: String, notes: String, presetId: Long, lapCount: Int,
        targetDistanceCm: Long, includeStart: Boolean, includeFinish: Boolean
    ) -> Unit,
    viewModel: RecordSetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "New Recording",
                onBack  = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    value         = state.name,
                    onValueChange = viewModel::onNameChange,
                    label         = { Text("Route name *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value         = state.notes,
                    onValueChange = viewModel::onNotesChange,
                    label         = { Text("Notes (optional)") },
                    minLines      = 2,
                    maxLines      = 4,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            item {
                PresetDropdown(
                    presets         = state.presets,
                    selectedPresetId = state.selectedPresetId,
                    onSelected      = viewModel::onPresetSelected
                )
            }

            item {
                EndpointMarkerToggles(
                    includeStart   = state.includeStart,
                    includeFinish  = state.includeFinish,
                    onStartChange  = viewModel::onIncludeStartChange,
                    onFinishChange = viewModel::onIncludeFinishChange
                )
            }

            item {
                OutlinedButton(
                    onClick  = onPresetsClicked,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Manage Presets")
                }
            }
            item {
                LapCountRow(
                    lapCount  = state.lapCount,
                    onDecrement = { viewModel.onLapCountChange(state.lapCount - 1) },
                    onIncrement = { viewModel.onLapCountChange(state.lapCount + 1) }
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Or compose to a race distance:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TargetDistanceChips(
                        targetDistanceCm = state.targetDistanceCm,
                        onTargetSelected = viewModel::onTargetDistancePreset,
                        modifier         = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value         = state.targetDistanceKm,
                        onValueChange = viewModel::onTargetDistanceChange,
                        label         = { Text("Target total distance (${state.distanceUnit.symbol}, optional)") },
                        supportingText = { Text("Leave blank to use exactly ${state.lapCount} full lap(s)") },
                        singleLine    = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                CompositionPreviewCard(preview = state.compositionPreview)
            }

            item {
                Button(
                    onClick  = {
                        onProceed(
                            state.name.trim(),
                            state.notes.trim(),
                            state.selectedPresetId,
                            state.lapCount,
                            state.targetDistanceCm,
                            state.includeStart,
                            state.includeFinish
                        )
                    },
                    enabled  = state.canProceed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check GPS & Start Recording")
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDropdown(
    presets: List<MarkerPreset>,
    selectedPresetId: Long,
    onSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = presets.find { it.id == selectedPresetId }?.name ?: "No preset"

    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value         = selectedName,
            onValueChange = {},
            readOnly      = true,
            label         = { Text("Marker preset") },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier      = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text    = { Text("No preset") },
                onClick = { onSelected(-1L); expanded = false }
            )
            presets.forEach { preset ->
                DropdownMenuItem(
                    text    = { Text(preset.name) },
                    onClick = { onSelected(preset.id); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun LapCountRow(
    lapCount: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        verticalAlignment      = Alignment.CenterVertically,
        horizontalArrangement  = Arrangement.SpaceBetween,
        modifier               = Modifier.fillMaxWidth()
    ) {
        Text("Number of laps", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDecrement, enabled = lapCount > 1) {
                Icon(Icons.Default.Remove, contentDescription = "Fewer laps")
            }
            Text(
                text  = lapCount.toString(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.widthIn(min = 32.dp),
            )
            IconButton(onClick = onIncrement) {
                Icon(Icons.Default.Add, contentDescription = "More laps")
            }
        }
    }
}

@Composable
private fun CompositionPreviewCard(preview: String) {
    if (preview.isBlank()) return
    Card(
        colors  = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint     = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp).padding(top = 2.dp)
            )
            Text(
                text  = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
