package com.coursemapper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.Spacing
import com.coursemapper.ui.record.GateState

/**
 * GPS readiness dial, shared by the recording and navigation gates. Uses the
 * specific labels ("Location settings required") rather than "GPS
 * unavailable", the fix is often two taps away.
 */
@Composable
fun GpsStatusIndicator(state: GateState, modifier: Modifier = Modifier) {
    val icon: ImageVector
    val label: String
    val color = when (state) {
        is GateState.Green -> {
            icon = Icons.Default.GpsFixed
            label = "GPS ready · ±${state.accuracyMetres.toInt()} m"
            MaterialTheme.colorScheme.primary
        }
        is GateState.Yellow -> {
            icon = Icons.Default.GpsNotFixed
            label = "Weak signal · ±${state.accuracyMetres.toInt()} m"
            MaterialTheme.colorScheme.tertiary
        }
        GateState.Initializing -> {
            icon = Icons.Default.GpsNotFixed
            label = "Waiting for GPS lock…"
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        GateState.Red -> {
            icon = Icons.Default.GpsOff
            label = "No GPS signal"
            MaterialTheme.colorScheme.error
        }
        GateState.NoPermission -> {
            icon = Icons.Default.GpsOff
            label = "Permission needed"
            MaterialTheme.colorScheme.error
        }
        GateState.SettingsRequired -> {
            // A different icon as well as a different sentence: this one is
            // fixed in the phone's own settings, not by moving into open sky.
            icon = Icons.Default.Settings
            label = "Location settings required"
            MaterialTheme.colorScheme.error
        }
    }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(IconSize.hero)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(label, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
