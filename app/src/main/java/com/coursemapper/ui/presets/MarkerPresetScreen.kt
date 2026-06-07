package com.coursemapper.ui.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmSectionHeader
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.CmEmptyState
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.hasEndpoint
import com.coursemapper.domain.model.isEndpoint
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/** Marker presets. Tap to edit in a sheet, FAB for a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerPresetScreen(
    onBack: () -> Unit,
    viewModel: MarkerPresetViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.editor != null) {
        PresetEditorSheet(
            editorState = state.editor!!,
            onNameChange           = viewModel::onNameChange,
            onAddRule              = viewModel::addRule,
            onRemoveRule           = viewModel::removeRule,
            onIntervalChange       = viewModel::updateRuleInterval,
            onAddCustomDistance    = viewModel::addCustomDistance,
            onRemoveCustomDistance = viewModel::removeCustomDistance,
            onSave                 = viewModel::savePreset,
            onDismiss              = viewModel::closeEditor
        )
        return
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Marker Presets",
                onBack  = onBack
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::startNewPreset) {
                Icon(Icons.Default.Add, contentDescription = "New preset")
            }
        }
    ) { padding ->
        if (state.presets.isEmpty()) {
            CmEmptyState(
                modifier = Modifier.padding(padding),
                icon     = Icons.Default.Tune,
                title    = "No presets yet",
                body     = "Tap + to create your first preset."
            )
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(padding),
                contentPadding      = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(state.presets, key = { _, p -> p.id }) { _, preset ->
                    PresetListItem(
                        preset    = preset,
                        onEdit    = { viewModel.startEditPreset(preset) },
                        onDelete  = { viewModel.deletePreset(preset.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PresetListItem(
    preset: MarkerPreset,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(preset.name) },
        supportingContent = {
            val formatter = LocalDistanceFormatter.current
            val unit by rememberDistanceUnit()
            Text(
                rulesSummary(preset.rules, formatter, unit),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            Icon(Icons.Default.Tune, contentDescription = null)
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onEdit)
    )

    if (showDeleteDialog) {
        CmConfirmDialog(
            title        = "Delete preset?",
            text         = "\"${preset.name}\" will be removed. Approved courses that used this preset are unaffected.",
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = { onDelete(); showDeleteDialog = false },
            onDismiss    = { showDeleteDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetEditorSheet(
    editorState: PresetEditorState,
    onNameChange: (String) -> Unit,
    onAddRule: (MarkerType) -> Unit,
    onRemoveRule: (Int) -> Unit,
    onIntervalChange: (Int, Double) -> Unit,
    onAddCustomDistance: (Int, Double) -> Unit,
    onRemoveCustomDistance: (Int, Double) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var showAddMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(if (editorState.editingPresetId == null) "New Preset" else "Edit Preset") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    TextButton(
                        onClick  = onSave,
                        enabled  = editorState.name.isNotBlank() && !editorState.isSaving
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value         = editorState.name,
                    onValueChange = onNameChange,
                    label         = { Text("Preset name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }

            item { CmSectionHeader("Marker rules") }

            itemsIndexed(editorState.rules, key = { i, _ -> i }) { index, rule ->
                RuleRow(
                    rule                   = rule,
                    canDelete              = editorState.rules.size > 1,
                    onDelete               = { onRemoveRule(index) },
                    onIntervalChange       = { onIntervalChange(index, it) },
                    onAddCustomDistance    = { onAddCustomDistance(index, it) },
                    onRemoveCustomDistance = { onRemoveCustomDistance(index, it) }
                )
            }

            item {
                Box {
                    OutlinedButton(
                        onClick  = { showAddMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add rule")
                    }
                    DropdownMenu(
                        expanded         = showAddMenu,
                        onDismissRequest = { showAddMenu = false }
                    ) {
                        // A course has one start and one finish; offering them
                        // again would only place a duplicate marker at the same
                        // distance.
                        MarkerType.entries
                            .filterNot { it.isEndpoint() && editorState.rules.hasEndpoint(it) }
                            .forEach { type ->
                                DropdownMenuItem(
                                    text    = { Text(type.displayName()) },
                                    onClick = {
                                        onAddRule(type)
                                        showAddMenu = false
                                    }
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(
    rule: MarkerRule,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onIntervalChange: (Double) -> Unit,
    onAddCustomDistance: (Double) -> Unit = {},
    onRemoveCustomDistance: (Double) -> Unit = {}
) {
    var intervalText by remember(rule.intervalMetres) {
        mutableStateOf(
            if (rule.type == MarkerType.DISTANCE || rule.intervalMetres > 0.0)
                (rule.intervalMetres / 1_000.0).let { if (it == it.toLong().toDouble()) it.toLong().toString() else String.format("%.2f", it) }
            else ""
        )
    }
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()
    Card {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector        = rule.type.icon(),
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary,
                modifier           = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.type.displayName(), style = MaterialTheme.typography.bodyMedium)
                when {
                    rule.type == MarkerType.CUSTOM_DISTANCES -> {
                        CustomDistancesEditor(
                            distances = rule.customDistancesMetres,
                            onAdd     = onAddCustomDistance,
                            onRemove  = onRemoveCustomDistance
                        )
                    }
                    rule.type != MarkerType.START && rule.type != MarkerType.FINISH -> {
                        OutlinedTextField(
                            value         = intervalText,
                            onValueChange = { v ->
                                intervalText = v
                                v.toDoubleOrNull()?.let { onIntervalChange(it * 1_000.0) }
                            },
                            label         = { Text("Every (${unit.symbol})") },
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier      = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Remove rule")
                }
            }
        }
    }
}

@Composable
private fun CustomDistancesEditor(
    distances: List<Double>,
    onAdd: (Double) -> Unit,
    onRemove: (Double) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val formatter = LocalDistanceFormatter.current
    val unit by rememberDistanceUnit()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        // Existing distances as dismissible chips
        distances.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { dist ->
                    val label = formatter.formatCompact(dist, unit)
                    InputChip(
                        selected  = false,
                        onClick   = {},
                        label     = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            IconButton(
                                onClick  = { onRemove(dist) },
                                modifier = Modifier.size(16.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove",
                                    modifier = Modifier.size(12.dp))
                            }
                        }
                    )
                }
            }
        }
        // Add new distance input
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = inputText,
                onValueChange = { inputText = it },
                label         = { Text("Add (${unit.symbol})") },
                singleLine    = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier      = Modifier.weight(1f)
            )
            IconButton(
                onClick  = {
                    inputText.toDoubleOrNull()?.let {
                        onAdd(it * 1_000.0)
                        inputText = ""
                    }
                },
                enabled  = inputText.toDoubleOrNull() != null
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add distance")
            }
        }
    }
}

private fun rulesSummary(
    rules: List<MarkerRule>,
    formatter: DistanceFormatter,
    unit: DistanceFormatter.DistanceUnit
): String {
    return rules.joinToString(" · ") { rule ->
        when (rule.type) {
            MarkerType.START         -> "Start"
            MarkerType.FINISH        -> "Finish"
            MarkerType.DISTANCE      -> "Every ${formatter.formatCompact(rule.intervalMetres, unit)}"
            MarkerType.CHECKPOINT    -> "CP every ${formatter.formatCompact(rule.intervalMetres, unit)}"
            MarkerType.WATER_STATION -> "Water every ${formatter.formatCompact(rule.intervalMetres, unit)}"
            MarkerType.CUSTOM_DISTANCES -> {
                val sorted = rule.customDistancesMetres.sortedBy { it }
                if (sorted.isEmpty()) "Custom (none)"
                else {
                    val shown = sorted.take(3).joinToString(", ") { formatter.formatCompact(it, unit) }
                    if (sorted.size > 3) "$shown +${sorted.size - 3} more" else shown
                }
            }
        }
    }
}

private fun MarkerType.displayName() = when (this) {
    MarkerType.START            -> "Start"
    MarkerType.FINISH           -> "Finish"
    MarkerType.DISTANCE         -> "Distance marker"
    MarkerType.CHECKPOINT       -> "Checkpoint"
    MarkerType.WATER_STATION    -> "Water station"
    MarkerType.CUSTOM_DISTANCES -> "Custom distances"
}

private fun MarkerType.icon() = when (this) {
    MarkerType.START            -> Icons.Default.PlayArrow
    MarkerType.FINISH           -> Icons.Default.Flag
    MarkerType.DISTANCE         -> Icons.Default.LocationOn
    MarkerType.CHECKPOINT       -> Icons.Default.CheckCircle
    MarkerType.WATER_STATION    -> Icons.Default.LocalDrink
    MarkerType.CUSTOM_DISTANCES -> Icons.Default.Route
}
