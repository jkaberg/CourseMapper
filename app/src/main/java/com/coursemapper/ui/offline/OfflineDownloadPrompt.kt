package com.coursemapper.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coursemapper.data.repository.RouteRepository.OfflinePackProposal

/**
 * The offline map download dialog, shared by every place that asks. Always
 * shows size, what it covers, and a way to stop being asked.
 */
@Composable
fun OfflineDownloadPrompt(
    proposal: OfflinePackProposal,
    onDownload: (alwaysFromNowOn: Boolean) -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit
) {
    var alwaysFromNowOn by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
        title = { Text("Download offline map?") },
        text = {
            Column {
                Text(
                    coverageSentence(proposal),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "About ${formatBytes(proposal.quote.estimatedBytes)} — " +
                        "${formatCount(proposal.quote.tileCount)} map tiles.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Without it the map needs a signal, which most courses do " +
                        "not have all the way round.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = alwaysFromNowOn,
                        onCheckedChange = { alwaysFromNowOn = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Always download for new courses",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(alwaysFromNowOn) }) { Text("Download") }
        },
        dismissButton = {
            // "Not now" rather than "Cancel": this is remembered as a decision
            // about this area, and the pack stays startable from the gate.
            TextButton(onClick = onDecline) { Text("Not now") }
        }
    )
}

/** Names what the pack covers, because "a map" is not something to weigh. */
private fun coverageSentence(proposal: OfflinePackProposal): String {
    val names = proposal.courseNames
    return when {
        names.isEmpty() -> "Download the map for this area so it works without a signal."
        names.size == 1 ->
            "Download the map around \"${names.first()}\" so it works without a signal."
        else ->
            "Download one map covering ${names.joinToString(", ")} — they share the " +
                "same ground, so this is a single download for all of them."
    }
}

/** Shown when the area can't be downloaded at all, so the user finds out here. */
@Composable
fun OfflineDownloadUnavailableDialog(
    proposal: OfflinePackProposal,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline map unavailable") },
        text = {
            Text(
                if (!proposal.quote.hasRoom) {
                    "This map needs about ${formatBytes(proposal.quote.estimatedBytes)}, and " +
                        "there is only ${formatBytes(proposal.quote.freeBytes)} free on this " +
                        "device. Free some space and try again from Settings › Map Storage."
                } else {
                    "This area is too large to download as a single map " +
                        "(${formatCount(proposal.quote.tileCount)} tiles). Splitting the " +
                        "courses into separate events will bring each one within range."
                },
                textAlign = TextAlign.Start
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

/** Bytes formatted like Android's own storage screens. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format("%.0f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format("%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> String.format("%.1f M", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.0f k", count / 1_000.0)
    else -> count.toString()
}
