package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Two-button confirmation dialog.
 *
 * Both buttons are text buttons and [destructive] tints the confirm one `error`.
 * A filled red button would be the easiest target in a dialog where the safe
 * answer is usually the other one.
 *
 * [dismissLabel] is "Cancel" unless that is ambiguous: use "Keep going" when
 * dismissing returns to an activity (pausing a run), and "Not now" when the
 * dialog offers something optional (offline maps).
 *
 * Three-action dialogs keep their own `AlertDialog`.
 */
@Composable
fun CmConfirmDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    body: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm), content = body)
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                }
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}

/** Single paragraph variant. */
@Composable
fun CmConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false
) {
    CmConfirmDialog(
        title = title,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        icon = icon,
        dismissLabel = dismissLabel,
        destructive = destructive,
        body = { Text(text) }
    )
}
