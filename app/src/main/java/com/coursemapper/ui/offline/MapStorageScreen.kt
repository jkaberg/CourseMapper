package com.coursemapper.ui.offline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.components.offlineStatusPresentation
import com.coursemapper.ui.designsystem.CmEmptyState
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.domain.model.OfflineMapPack
import com.coursemapper.domain.model.OfflinePackProgress
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.offline.PackWithProgress

/** Map storage: every pack with state, size and progress, and resume, pause or delete. From Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapStorageScreen(
    onBack: () -> Unit,
    viewModel: MapStorageViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Map Storage",
                onBack  = onBack
            )
        }
    ) { padding ->
        if (state.packs.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${formatBytes(state.totalBytes)} of maps on this device",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "A map is deleted automatically when the last course using " +
                            "it is deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                ListItem(
                    headlineContent = { Text("Download on Wi-Fi only") },
                    supportingContent = {
                        Text("Off lets a download finish on mobile data when there is no Wi-Fi")
                    },
                    trailingContent = {
                        Switch(
                            checked = state.wifiOnly,
                            onCheckedChange = viewModel::setWifiOnly
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Include both map themes") },
                    supportingContent = {
                        Text(
                            "Downloads the light and dark basemap. Turning this off " +
                                "leaves the map blank in whichever theme was not downloaded."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.bothThemes,
                            onCheckedChange = viewModel::setBothThemes
                        )
                    }
                )
            }
            item { HorizontalDivider() }

            items(state.packs, key = { it.pack.id }) { entry ->
                PackItem(
                    entry = entry,
                    isStartable = viewModel.isStartable(entry.pack.status),
                    onDownload = { viewModel.download(entry.pack.id) },
                    onPause = { viewModel.pause(entry.pack.id) },
                    onDelete = { viewModel.delete(entry.pack.id) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    CmEmptyState(
        modifier = modifier,
        icon     = Icons.Default.OfflinePin,
        title    = "No offline maps",
        body     = "You are offered one when you create a course, and at the " +
            "readiness check before a placement run."
    )
}

@Composable
private fun PackItem(
    entry: PackWithProgress,
    isStartable: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit
) {
    val pack = entry.pack
    var showDeleteDialog by remember { mutableStateOf(false) }
    // no `failure` here, this screen prints the reason on its own line below
    val presentation = offlineStatusPresentation(
        status   = pack.status,
        progress = entry.progress?.fraction
    )

    ListItem(
        headlineContent = { Text(pack.label) },
        supportingContent = {
            Column {
                Text(
                    presentation.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = presentation.color
                )
                if (pack.status.isInFlight) {
                    Spacer(Modifier.height(6.dp))
                    DownloadProgress(entry.progress, pack)
                } else if (pack.displayBytes > 0) {
                    Text(
                        formatBytes(pack.displayBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                pack.failureMessage?.takeIf { pack.status == OfflinePackStatus.FAILED }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = presentation.icon ?: Icons.Default.CloudDownload,
                contentDescription = null,
                tint = presentation.color
            )
        },
        trailingContent = {
            Row {
                if (pack.status.isInFlight) {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause download")
                    }
                } else if (isStartable) {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                    }
                } else if (pack.status == OfflinePackStatus.READY) {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-download")
                    }
                }
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete map")
                }
            }
        }
    )

    if (showDeleteDialog) {
        CmConfirmDialog(
            title        = "Delete this offline map?",
            confirmLabel = "Delete",
            destructive  = true,
            onConfirm    = { onDelete(); showDeleteDialog = false },
            onDismiss    = { showDeleteDialog = false }
        ) {
            Text(
                "\"${pack.label}\" frees ${formatBytes(pack.displayBytes)}. " +
                    "The courses it covers are not affected, and you can download " +
                    "it again from the readiness check before a run."
            )
        }
    }
}

/** Determinate progress when MapLibre knows the total, indeterminate while it's still counting. */
@Composable
private fun DownloadProgress(progress: OfflinePackProgress?, pack: OfflineMapPack) {
    val fraction = progress?.fraction
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(4.dp))
    val downloaded = progress?.completedBytes ?: pack.sizeBytes
    Text(
        buildString {
            append(formatBytes(downloaded))
            if (pack.estimatedBytes > 0) append(" of about ${formatBytes(pack.estimatedBytes)}")
            fraction?.let { append(" · ${(it * 100).toInt()}%") }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

