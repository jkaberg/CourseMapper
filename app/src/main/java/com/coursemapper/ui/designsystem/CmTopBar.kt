package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * App bar with a back arrow, used by every screen below Home.
 *
 * Home (no back) and the preset editor (close, not back) have their own bar.
 */
@Composable
fun CmTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    CmTopBar(
        onBack = onBack,
        modifier = modifier,
        actions = actions,
        title = { Text(title) }
    )
}

/** Slot variant for titles that aren't a plain string, eg the marker table count. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit
) {
    TopAppBar(
        modifier = modifier,
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = actions
    )
}
