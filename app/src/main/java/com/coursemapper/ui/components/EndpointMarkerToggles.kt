package com.coursemapper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.Spacing

/**
 * Start and finish marker toggles for one course, seeded from the profile.
 * Same component in record setup, the import wizard and the workspace.
 */
@Composable
fun EndpointMarkerToggles(
    includeStart: Boolean,
    includeFinish: Boolean,
    onStartChange: (Boolean) -> Unit,
    onFinishChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        EndpointRow(
            label     = "Start marker",
            checked   = includeStart,
            onChange  = onStartChange,
            enabled   = enabled
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(IconSize.md),
                tint     = MaterialTheme.colorScheme.primary
            )
        }
        EndpointRow(
            label     = "Finish marker",
            checked   = includeFinish,
            onChange  = onFinishChange,
            enabled   = enabled
        ) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                modifier = Modifier.size(IconSize.md),
                tint     = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean,
    icon: @Composable () -> Unit
) {
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        icon()
        Text(
            label,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = Spacing.sm)
        )
    }
}
