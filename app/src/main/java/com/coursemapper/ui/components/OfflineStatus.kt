package com.coursemapper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.Spacing

/** What's offered next when the map isn't ready, separate from the label. */
enum class OfflineStatusAction(val label: String) {
    DOWNLOAD("Download"),
    RESUME("Resume"),
    RETRY("Retry")
}

/** One offline-pack state, resolved to something a screen can draw. */
data class OfflineStatusPresentation(
    /** Null while the check is still running - draw a spinner in its place. */
    val icon: ImageVector?,
    val color: Color,
    val label: String,
    val action: OfflineStatusAction?
)

/**
 * Offline pack state to icon, colour and sentence. Used everywhere so the
 * screens agree on what a state means (PAUSED is not missing).
 *
 * @param status null when there's no pack
 * @param progress download fraction, when MapLibre knows it
 * @param failure specific reason, preferred over the generic label
 * @param estimatedBytes size of the offered download, once quoted
 */
@Composable
fun offlineStatusPresentation(
    status: OfflinePackStatus?,
    checking: Boolean = false,
    progress: Float? = null,
    failure: String? = null,
    estimatedSize: String? = null
): OfflineStatusPresentation = when {
    checking -> OfflineStatusPresentation(
        icon = null,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        label = "Checking offline maps…",
        action = null
    )

    status == null -> OfflineStatusPresentation(
        icon = Icons.Default.CloudOff,
        color = MaterialTheme.colorScheme.tertiary,
        label = estimatedSize?.let { "No offline map — about $it" } ?: "No offline map",
        action = OfflineStatusAction.DOWNLOAD
    )

    status == OfflinePackStatus.READY -> OfflineStatusPresentation(
        icon = Icons.Default.OfflinePin,
        color = MaterialTheme.colorScheme.primary,
        label = "Map ready for the field",
        action = null
    )

    // usable and never blocks a run, but it isn't READY either
    status == OfflinePackStatus.STALE -> OfflineStatusPresentation(
        icon = Icons.Default.Update,
        color = MaterialTheme.colorScheme.tertiary,
        label = "Map ready, but downloaded a while ago",
        action = OfflineStatusAction.DOWNLOAD
    )

    status == OfflinePackStatus.DOWNLOADING -> OfflineStatusPresentation(
        icon = Icons.Default.CloudDownload,
        color = MaterialTheme.colorScheme.tertiary,
        // A percentage the moment MapLibre can be believed about one.  A bare
        // "downloading…" leaves the rider with no idea whether to wait thirty
        // seconds or ten minutes, which is the whole decision here.
        label = progress
            ?.let { "Downloading map — ${(it * 100).toInt()}%" }
            ?: "Downloading map…",
        action = null
    )

    status == OfflinePackStatus.QUEUED -> OfflineStatusPresentation(
        icon = Icons.Default.CloudDownload,
        color = MaterialTheme.colorScheme.tertiary,
        label = "Map waiting for a connection",
        action = null
    )

    status == OfflinePackStatus.PAUSED -> OfflineStatusPresentation(
        icon = Icons.Default.PauseCircleOutline,
        color = MaterialTheme.colorScheme.tertiary,
        label = "Map paused part-way — it can be resumed",
        action = OfflineStatusAction.RESUME
    )

    else -> OfflineStatusPresentation(
        icon = Icons.Default.Warning,
        color = MaterialTheme.colorScheme.error,
        label = failure ?: "The map download did not finish",
        action = OfflineStatusAction.RETRY
    )
}

/**
 * Offline state as one row with its action, used by the gate and group
 * detail. Map Storage has its own layout but uses [offlineStatusPresentation].
 *
 * @param showProgressBar progress bar under the row while downloading
 */
@Composable
fun OfflineStatusRow(
    status: OfflinePackStatus?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
    progress: Float? = null,
    failure: String? = null,
    estimatedSize: String? = null,
    showProgressBar: Boolean = false
) {
    val presentation = offlineStatusPresentation(
        status = status,
        checking = checking,
        progress = progress,
        failure = failure,
        estimatedSize = estimatedSize
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            val icon = presentation.icon
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = presentation.color,
                    modifier = Modifier.size(IconSize.md)
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(IconSize.md),
                    strokeWidth = 2.dp,
                    color = presentation.color
                )
            }
            Text(
                presentation.label,
                style = MaterialTheme.typography.bodySmall,
                color = presentation.color,
                modifier = Modifier.weight(1f)
            )
            presentation.action?.let { action ->
                TextButton(onClick = onAction) {
                    Text(action.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (showProgressBar && status?.isInFlight == true) {
            Spacer(Modifier.height(Spacing.xs))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
