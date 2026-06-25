package com.coursemapper.ui.routes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/**
 * Read-only marker table for preparing before heading out, sorted by distance
 * from the start. Not used while riding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerTableScreen(
    onBack: () -> Unit,
    viewModel: MarkerTableViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CmTopBar(
                onBack = onBack,
                title  = {
                    Column {
                        val title = state.variantName ?: state.courseName
                        Text(title.ifBlank { "Markers" })
                        if (!state.isLoading) {
                            Text(
                                "${state.markers.size} markers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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

        if (state.markers.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No markers for this course.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            MarkerTableHeader()
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(
                modifier       = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                itemsIndexed(state.markers, key = { _, m -> m.id }) { _, marker ->
                    MarkerTableRow(marker)
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#",
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Text(
            "Type",
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(
            "Label",
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            "Dist",
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            "Coords",
            style    = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
    }
}

@Composable
private fun MarkerTableRow(marker: DistanceMarker) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sequence number
        Text(
            text     = "${marker.sequenceIndex}",
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )

        // Type chip
        MarkerTypeChip(marker.type, modifier = Modifier.width(80.dp))

        // Label
        Text(
            text     = marker.label.ifBlank { marker.type.name.lowercase().replaceFirstChar { it.uppercase() } },
            style    = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Distance from variant start
        run {
            val formatter = LocalDistanceFormatter.current
            val unit by rememberDistanceUnit()
            Text(
                text     = formatter.formatCompact(marker.cumulativeDistanceMetres, unit),
                style    = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(56.dp)
            )
        }

        // GPS coordinates (compact)
        Column(modifier = Modifier.width(100.dp)) {
            Text(
                text  = "%.5f".format(marker.lat),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text  = "%.5f".format(marker.lon),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MarkerTypeChip(type: MarkerType, modifier: Modifier = Modifier) {
    val (label, containerColor) = when (type) {
        MarkerType.START            -> "Start"  to MaterialTheme.colorScheme.primaryContainer
        MarkerType.FINISH           -> "Finish" to MaterialTheme.colorScheme.tertiaryContainer
        MarkerType.CHECKPOINT       -> "Check"  to MaterialTheme.colorScheme.secondaryContainer
        MarkerType.WATER_STATION    -> "Water"  to MaterialTheme.colorScheme.secondaryContainer
        MarkerType.DISTANCE         -> "Dist"   to MaterialTheme.colorScheme.surfaceVariant
        MarkerType.CUSTOM_DISTANCES -> "Custom" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        modifier  = modifier.padding(end = 4.dp),
        color     = containerColor,
        shape     = MaterialTheme.shapes.extraSmall
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}
