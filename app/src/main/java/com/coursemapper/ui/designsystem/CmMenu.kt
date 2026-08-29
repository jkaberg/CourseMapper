package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/** One row in a [CmMenu]. */
data class CmMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit
)

/**
 * Dropdown menu contents. Each item closes the menu before running its action,
 * otherwise the menu stays open over the screen the action navigated to.
 *
 * [actions] is an unstable list so this isn't skippable - fine in app bars and
 * lists, but don't use it on the run navigation screen (frame loop).
 */
@Composable
fun CmMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<CmMenuAction>,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = action.icon?.let {
                    { Icon(it, contentDescription = null) }
                },
                onClick = { onDismissRequest(); action.onClick() }
            )
        }
    }
}

/** Three-dot overflow button in an app bar, with its menu. */
@Composable
fun CmOverflowMenu(
    actions: List<CmMenuAction>,
    modifier: Modifier = Modifier,
    contentDescription: String = "More"
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = contentDescription)
        }
        CmMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            actions = actions
        )
    }
}

/**
 * Expand/collapse chevron with a content description.
 *
 * @param what the thing being expanded, "markers" gives "Expand markers".
 *   Null gives plain "Expand" / "Collapse".
 */
@Composable
fun CmDisclosureIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    what: String? = null,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current
) {
    val verb = if (expanded) "Collapse" else "Expand"
    Icon(
        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = if (what == null) verb else "$verb $what",
        tint = tint,
        modifier = modifier
    )
}
