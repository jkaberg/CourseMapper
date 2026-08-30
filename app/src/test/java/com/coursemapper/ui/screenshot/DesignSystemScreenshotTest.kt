package com.coursemapper.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.CmConfirmDialog
import com.coursemapper.ui.designsystem.CmDisclosureIcon
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmSectionHeader
import com.coursemapper.ui.designsystem.CmTopBar
import org.junit.Test

/** Baselines for the design system primitives in states no screen happens to show. */
class DesignSystemScreenshotTest : ScreenshotTest() {

    @Test
    fun `top bar, title only`() = screenshot("cm_top_bar_plain") {
        CmTopBar(title = "Map Storage", onBack = {})
    }

    @Test
    fun `top bar, title long enough to wrap`() = screenshot("cm_top_bar_long_title") {
        CmTopBar(title = "Ready to mark — Trondheim Marathon", onBack = {})
    }

    @Test
    fun `top bar, with actions`() = screenshot("cm_top_bar_actions") {
        CmTopBar(
            title = "Combined course",
            onBack = {},
            actions = {
                IconButton(onClick = {}) { Icon(Icons.Default.Share, contentDescription = "Share") }
                IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            }
        )
    }

    /** The slot form, as the marker table uses it: a count stacked under the name. */
    @Test
    fun `top bar, stacked title`() = screenshot("cm_top_bar_stacked_title") {
        CmTopBar(
            onBack = {},
            title = {
                Column {
                    Text("Marathon")
                    Text(
                        "34 markers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    @Test
    fun `loading fills its space and centres`() = screenshot("cm_loading") {
        CmLoading(Modifier.height(320.dp).fillMaxWidth())
    }

    /** Destructive variant: text button tinted `error`, not a filled red button. */
    @Test
    fun `confirm dialog, destructive`() = screenshotDialog("cm_confirm_destructive") {
        CmConfirmDialog(
            title = "Delete course?",
            text = "\"Marathon\" is removed from your courses. " +
                "The recording it was built from is kept.",
            icon = Icons.Default.Delete,
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {},
            onDismiss = {}
        )
    }

    @Test
    fun `confirm dialog, ordinary`() = screenshotDialog("cm_confirm_plain") {
        CmConfirmDialog(
            title = "Publish course?",
            text = "Publishing unlocks placement stops and navigation.",
            icon = Icons.Default.CheckCircle,
            confirmLabel = "Publish",
            onConfirm = {},
            onDismiss = {}
        )
    }

    /** A body of several lines, and a dismiss label that is not "Cancel". */
    @Test
    fun `confirm dialog, multi-line body and a named dismissal`() =
        screenshotDialog("cm_confirm_body") {
            CmConfirmDialog(
                title = "End run with stops remaining?",
                confirmLabel = "End run",
                destructive = true,
                dismissLabel = "Keep going",
                onConfirm = {},
                onDismiss = {}
            ) {
                Text("4 stop(s) are still pending. The run will be marked finished.")
                Text(
                    "The placement log keeps what was placed.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

    @Test
    fun `section header`() = screenshot("cm_section_header") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            CmSectionHeader("Combined courses")
            Text("A row under the header")
            CmSectionHeader("Courses")
            Text("Another row")
        }
    }

    @Test
    fun `disclosure icon, both directions`() = screenshot("cm_disclosure_icon") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            CmDisclosureIcon(expanded = false, what = "markers")
            CmDisclosureIcon(expanded = true, what = "markers")
        }
    }
}
