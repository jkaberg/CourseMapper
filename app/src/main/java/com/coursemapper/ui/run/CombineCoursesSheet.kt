package com.coursemapper.ui.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit

/** Pick courses for a group, both for creating ([groupId] null) and editing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombineCoursesSheet(
    onDismiss: () -> Unit,
    onSaved: (groupId: Long) -> Unit,
    groupId: Long? = null,
    viewModel: CombineCoursesViewModel = hiltViewModel(
        key = "combine_${groupId ?: "new"}"
    )
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(groupId) { viewModel.start(groupId) }

    // The view model is scoped to the screen hosting the sheet, not to the
    // sheet, so it has to be told when the sheet goes away - or reopening it
    // shows last time's ticks as if they were the saved membership.
    DisposableEffect(Unit) { onDispose { viewModel.reset() } }

    LaunchedEffect(state.savedGroupId) {
        val id = state.savedGroupId ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(id)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Layers, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    if (state.isEditing) "Edit routes" else "Combine existing courses",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (state.availableCourses.size < 2) {
                Text(
                    "You need at least two published courses with markers before they " +
                        "can be combined into one event.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            OutlinedTextField(
                value         = state.name,
                onValueChange = viewModel::onNameChange,
                label         = { Text("Event name") },
                placeholder   = { Text("Trondheim Marathon") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )

            Text(
                "The selected routes are placed in one pass and grouped under this " +
                    "name on the home screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)
            ) {
                items(state.availableCourses, key = { it.id }) { course ->
                    val formatter = LocalDistanceFormatter.current
                    val unit by rememberDistanceUnit()
                    ListItem(
                        headlineContent = {
                            Text(course.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                "${formatter.formatCompact(course.displayDistanceMetres, unit)} · " +
                                    "${course.markers.size} markers",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingContent = {
                            Checkbox(
                                checked         = course.id in state.selectedIds,
                                onCheckedChange = { viewModel.toggle(course.id) }
                            )
                        },
                        modifier = Modifier.clickable { viewModel.toggle(course.id) }
                    )
                }
            }

            if (state.selectedIds.size < 2) {
                Text(
                    "Select at least two routes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick  = viewModel::save,
                enabled  = state.canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.isEditing) "Save routes" else "Create combined course")
            }
        }
    }
}

