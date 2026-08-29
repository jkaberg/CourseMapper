package com.coursemapper.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.coursemapper.domain.model.CourseStatus

/**
 * Course status as a chip, same on Home and in the workspace.
 *
 * @param label overrides the text, the group row uses it to name the member
 *   holding the event back ("5 km · draft")
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseStatusBadge(
    status: CourseStatus,
    modifier: Modifier = Modifier,
    label: String = courseStatusLabel(status)
) {
    AssistChip(
        onClick = {},
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        colors = courseStatusChipColors(status),
        modifier = modifier.height(24.dp)
    )
}

fun courseStatusLabel(status: CourseStatus): String = when (status) {
    CourseStatus.DRAFT            -> "Draft"
    CourseStatus.MARKERLESS       -> "Markerless"
    CourseStatus.PUBLISHED        -> "Published"
    CourseStatus.NEEDS_FIELD_PREP -> "Needs prep"
    CourseStatus.OFFLINE_READY    -> "Offline ready"
}

/** Neutral for draft, error without markers, primary when published, tertiary needing prep, secondary when offline ready. */
@Composable
fun courseStatusChipColors(status: CourseStatus): ChipColors = when (status) {
    CourseStatus.DRAFT            -> AssistChipDefaults.assistChipColors()
    CourseStatus.MARKERLESS       -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        labelColor     = MaterialTheme.colorScheme.onErrorContainer
    )
    CourseStatus.PUBLISHED        -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        labelColor     = MaterialTheme.colorScheme.onPrimaryContainer
    )
    CourseStatus.NEEDS_FIELD_PREP -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        labelColor     = MaterialTheme.colorScheme.onTertiaryContainer
    )
    CourseStatus.OFFLINE_READY    -> AssistChipDefaults.assistChipColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        labelColor     = MaterialTheme.colorScheme.onSecondaryContainer
    )
}
