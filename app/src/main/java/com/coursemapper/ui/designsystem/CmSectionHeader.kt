package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Label above a group of rows.
 *
 * `onSurfaceVariant` rather than `primary` - primary is the interactive colour
 * and means "GPS ready" on the readiness gate.
 *
 * Only vertical padding since the lists disagree on horizontal inset. Pass
 * `Modifier.padding(horizontal = ScreenGutter.horizontal)` if the list doesn't
 * inset.
 */
@Composable
fun CmSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.lg, bottom = Spacing.xs)
    )
}
